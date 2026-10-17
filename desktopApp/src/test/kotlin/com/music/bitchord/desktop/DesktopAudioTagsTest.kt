package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a local file's own tags, and lifting its cover out of them.
 *
 * The thing worth pinning is the contract the library scan relies on: this never
 * throws. A scan walks a folder of whatever happens to be in it, so a file with
 * no tags, a truncated one, and something that is not audio at all all have to
 * come back as "no tags" rather than take the scan down with them.
 *
 * The cover is written out rather than read in place — nothing can hand a region
 * of an audio file to an image decoder — so what matters there is that the name
 * it lands under is the same on every rescan, and that a rescan does not write
 * it again.
 */
class DesktopAudioTagsTest {

    private val scratch: Path = Files.createTempDirectory("bitchord-tags")

    /** The app's own cover folder, for a test that must not reach it. */
    private val covers = DesktopLocalCoverCache(scratch.resolve("covers"))

    @AfterTest
    fun cleanUp() {
        scratch.toFile().deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray): Path {
        val path = scratch.resolve(name)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        return path
    }

    /** The smallest thing that is definitely not audio, whatever the name says. */
    private fun notAudio(name: String): Path = file(name, ByteArray(64) { it.toByte() })

    @Test
    fun `a file that is not audio has no tags rather than an exception`() {
        val tags = DesktopAudioTags.read(notAudio("broken.mp3"))
        assertNull(tags.title)
        assertNull(tags.artist)
        assertNull(tags.album)
        assertNull(tags.trackNumber)
        assertNull(tags.artworkUri)
    }

    @Test
    fun `a file that is not there has no tags rather than an exception`() {
        val tags = DesktopAudioTags.read(scratch.resolve("never-written.flac"))
        assertNull(tags.title)
        assertNull(tags.artworkUri)
    }

    @Test
    fun `a cover is written out and named as a file URI`() {
        // Not read out of a real file: the store is what is being tested here,
        // and the bytes only have to be enough to write.
        val audio = file("Esperon - Mi Hogar.flac", ByteArray(1024))
        val jpeg = ByteArray(512) { 0x7F.toByte() }

        val uri = assertNotNull(
            covers.store(audio, jpeg, "image/jpeg"),
            "a cover with bytes in it should have been written",
        )
        assertTrue(uri.startsWith("file:"), "the artwork must be a file URI so Coil's own fetcher reads it: $uri")

        val written = Path.of(java.net.URI(uri))
        assertTrue(Files.isRegularFile(written), "nothing is at $written")
        assertEquals(jpeg.size.toLong(), Files.size(written), "the bytes should have been written whole")
        assertTrue(written.fileName.toString().endsWith(".jpg"), "the extension should follow the picture's own type")
    }

    @Test
    fun `the same audio file always lands on the same cover`() {
        val audio = file("repeat.flac", ByteArray(2048))
        val first = covers.store(audio, ByteArray(256), "image/png")
        val second = covers.store(audio, ByteArray(256), "image/png")
        assertEquals(first, second, "a rescan must find what it wrote last time, not write it again")
    }

    @Test
    fun `different audio files do not share a cover`() {
        val one = file("a/one.flac", ByteArray(64))
        val other = file("b/other.flac", ByteArray(64))
        assertNotEquals(
            covers.store(one, ByteArray(16), "image/jpeg"),
            covers.store(other, ByteArray(16), "image/jpeg"),
            "two tracks must not be given one track's picture",
        )
    }

    @Test
    fun `clearing the cache takes the covers with it`() {
        val audio = file("cleared.flac", ByteArray(512))
        val uri = assertNotNull(covers.store(audio, ByteArray(128), "image/jpeg"))
        val written = Path.of(java.net.URI(uri))
        assertTrue(Files.isRegularFile(written))

        covers.clear()
        assertTrue(!Files.exists(written), "the cover outlived the cache it belongs to")
    }
}