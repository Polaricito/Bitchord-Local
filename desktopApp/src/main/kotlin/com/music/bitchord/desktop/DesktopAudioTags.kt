package com.music.bitchord.desktop

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.Artwork
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * What an audio file says about itself, as distinct from what its name says.
 *
 * Android gets all of this from MediaStore, which indexes the tags of every
 * audio file the device holds. A desktop has no such index, so the tags have to
 * be read off the file itself — which is also what the system does, and is why
 * Windows Media Player has shown a local track's own cover art and album while
 * this app showed a grey square.
 *
 * Every field is optional: a file's tags may say nothing, or something this
 * reader cannot make sense of. The name is the fallback, not the other way round.
 */
internal data class DesktopTrackTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Position within the album, as `TRACKNUMBER` states it, when it states one. */
    val trackNumber: Int? = null,
    /**
     * The picture embedded in the file, already written out and named as a
     * `file:` URI — the same shape as a cover fetched over the network, so every
     * surface that draws a thumbnail draws this one without knowing where it
     * came from. Null when the file carries no picture.
     */
    val artworkUri: String? = null,
)

/**
 * Reads an audio file's tags and its embedded picture.
 *
 * Deliberately never fails: a file whose tags are absent, truncated or written
 * in something this cannot read is a file with no tags, and the library scan
 * carries on with the name instead.
 */
internal object DesktopAudioTags {

    /** The tags of [path], or empty ones when it has none this can read. */
    fun read(path: Path): DesktopTrackTags = runCatching { readStrictly(path) }
        .getOrElse { DesktopTrackTags() }

    private fun readStrictly(path: Path): DesktopTrackTags {
        val audioFile = AudioFileIO.read(path.toFile())
        val tag = audioFile.tag
        return DesktopTrackTags(
            title = tag.text(FieldKey.TITLE),
            artist = tag.text(FieldKey.ARTIST),
            album = tag.text(FieldKey.ALBUM),
            trackNumber = trackNumber(tag.text(FieldKey.TRACK)),
            artworkUri = artworkUri(path, tag?.firstArtwork),
        )
    }

    /** A tag's text, when it says something. Empty strings and the usual placeholder are not text. */
    private fun Tag?.text(key: FieldKey): String? = this
        ?.getFirst(key)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    /**
     * `TRACKNUMBER` is `3` in some files and `3/12` in others; both mean three.
     */
    private fun trackNumber(raw: String?): Int? = raw
        ?.substringBefore('/')
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    /**
     * Writes the picture out and names where it landed.
     *
     * Extracted rather than read in place because nothing can hand a region of an
     * audio file to the image decoders — they are given a URL or a stream of a
     * whole image. One copy per track, kept in the cover cache beside the media
     * cache, and written once: the name is derived from the audio file's own
     * path, size and timestamp, so a rescan finds what it wrote last time and a
     * changed file gets a new one rather than a stale picture.
     */
    private fun artworkUri(audio: Path, artwork: Artwork?): String? {
        // Bound before either read: a cover that says what it is but cannot be
        // read is still not drawable, and `runCatching` would not smart-cast the
        // nullable away for us on the far side.
        if (artwork == null) return null
        val bytes = runCatching { artwork.binaryData }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return DesktopLocalCoverCache.default.store(
            audio,
            bytes,
            runCatching { artwork.mimeType }.getOrNull(),
        )
    }
}

/**
 * The covers lifted out of local audio files, as ordinary image files.
 *
 * The app draws a local track's artwork through the same path it draws a
 * downloaded one — `file:` URLs are read by [DesktopArtworkCache] and fetched by
 * Coil's own file fetcher — so nothing downstream needs to know that these came
 * out of a tag block rather than off the network.
 *
 * A class over a directory rather than a singleton over one fixed directory, so
 * a test can have one of its own rather than clearing the listener's.
 *
 * It is a cache of something derivable, so it prunes itself: [LIMIT_BYTES] is
 * generous for cover art and, once passed, the least recently written picture
 * goes first.
 */
internal class DesktopLocalCoverCache(private val directory: Path) {

    init {
        // Best effort, like the media cache's: a cache folder that cannot be
        // created is a folder of pictures this app cannot draw, and it must not
        // stop the library scan that found them.
        runCatching { Files.createDirectories(directory) }
    }

    /** Beside the media cache, so clearing one does not empty the other. */
    companion object {
        /**
         * About a gigabyte of covers: a few thousand releases' worth, and far
         * less than the audio it describes.
         */
        private const val LIMIT_BYTES = 1024L * 1024 * 1024

        /** The one the app uses. */
        val default: DesktopLocalCoverCache by lazy {
            DesktopLocalCoverCache(DesktopMediaCache.directory.resolveSibling("covers"))
        }
    }

    /**
     * What the folder holds, so pruning is not a question asked on every track.
     *
     * `store` runs once per file in the library, and a scan of a large one is
     * thousands of them; measuring the folder each time turned one scan into
     * millions of directory lookups. Negative until the first time it is
     * actually needed, which is what lets it be asked for on every write and
     * answered without touching the disk.
     */
    private val held = AtomicLong(-1L)

    /**
     * Writes [bytes] as [audio]'s cover and returns where, or null when it could
     * not be written — a read-only or full disk is a missing picture, not a
     * failed library scan.
     */
    fun store(audio: Path, bytes: ByteArray, mimeType: String?): String? {
        val target = directory.resolve(fileNameFor(audio, extensionFor(mimeType)))
        val alreadyThere = Files.isReadable(target) &&
            runCatching { Files.size(target) }.getOrDefault(-1L) == bytes.size.toLong()
        if (!alreadyThere) {
            val written = runCatching {
                Files.write(
                    target,
                    bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }.isSuccess
            if (!written) return null
            held.addAndGet(bytes.size.toLong())
        }
        if (heldBytes() > LIMIT_BYTES) trim()
        return target.toUri().toString()
    }

    /** Everything in it, thrown away — what Storage's cache row clears. */
    fun clear(): Long {
        val present = files()
        val freed = present.sumOf { sizeOf(it) }
        present.forEach { runCatching { Files.deleteIfExists(it) } }
        held.set(0L)
        return freed
    }

    /** What the folder holds, measuring it the first time it is asked for. */
    private fun heldBytes(): Long {
        val known = held.get()
        if (known >= 0L) return known
        val measured = files().sumOf { sizeOf(it) }
        held.compareAndSet(-1L, measured)
        return held.get()
    }

    private fun sizeOf(path: Path): Long = runCatching { Files.size(path) }.getOrDefault(0L)

    /**
     * A name that is the same for the same audio file every time, and different
     * for a different one. The size and the timestamp are in it so that replacing
     * a track's file — same name, new recording — does not leave the old cover
     * standing in for the new one.
     */
    private fun fileNameFor(audio: Path, extension: String): String {
        val key = runCatching {
            val attributes = Files.readAttributes(audio, BasicFileAttributes::class.java)
            "${audio.toAbsolutePath()}|${attributes.size()}|${attributes.lastModifiedTime().toMillis()}"
        }.getOrElse { audio.toAbsolutePath().toString() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return "$digest.$extension"
    }

    /** From the picture's own MIME type; `.img` for the formats that state none. */
    private fun extensionFor(mimeType: String?): String = when (mimeType?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/webp" -> "webp"
        else -> "img"
    }

    private fun files(): List<Path> = runCatching {
        Files.list(directory).use { stream ->
            stream.filter(Files::isRegularFile).toList()
        }
    }.getOrDefault(emptyList())

    /**
     * Drops the least recently written pictures until the folder fits.
     *
     * Only reached once [LIMIT_BYTES] has actually been passed, which is the
     * only moment the folder can be over it.
     */
    private fun trim() {
        var total = heldBytes()
        if (total <= LIMIT_BYTES) return
        val coldestFirst = files()
            .sortedBy { path ->
                runCatching {
                    Files.readAttributes(path, BasicFileAttributes::class.java).lastModifiedTime().toMillis()
                }.getOrDefault(0L)
            }
        for (path in coldestFirst) {
            // `break` rather than an early return, so what is left is written
            // back: leaving the old total behind would ask every later track to
            // prune again.
            if (total <= LIMIT_BYTES) break
            val size = sizeOf(path)
            if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) total -= size
        }
        held.set(total)
    }
}