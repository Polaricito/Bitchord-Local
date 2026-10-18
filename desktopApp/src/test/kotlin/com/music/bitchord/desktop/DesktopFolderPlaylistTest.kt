package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A folder dropped on the window is a playlist that keeps up with the folder.
 *
 * The persistence half is what stops the feature from being a one-afternoon
 * copy: a playlist line written before folders existed has to decode exactly as
 * it always did, and a line written after has to come back with the folder on it
 * or the playlist silently becomes an ordinary one on the next launch.
 */
class DesktopFolderPlaylistTest {
    @Test
    fun `a playlist line with no folder on it decodes as before`() {
        val playlist = DesktopPlaylist(id = "abc", title = "Mixed", songs = listOf(song("a"), song("b")))
        val decoded = DesktopPersistence().decodePlaylist(DesktopPersistence().encodePlaylist(playlist))
        assertEquals("abc", decoded?.id)
        assertEquals("Mixed", decoded?.title)
        assertEquals(listOf("a", "b"), decoded?.songs?.map(Song::videoId))
        assertEquals(null, decoded?.folderPath)
    }

    @Test
    fun `a playlist that watches a folder keeps the folder across a save`() {
        val playlist = DesktopPlaylist(
            id = "abc",
            title = "Album",
            songs = listOf(song("a")),
            folderPath = """C:\Users\me\Music\Album""",
        )
        val decoded = DesktopPersistence().decodePlaylist(DesktopPersistence().encodePlaylist(playlist))
        assertEquals("""C:\Users\me\Music\Album""", decoded?.folderPath)
        assertEquals("Album", decoded?.title)
        assertEquals(listOf("a"), decoded?.songs?.map(Song::videoId))
    }

    @Test
    fun `an empty folder is a playlist with nothing in it, not a broken one`() {
        val folder = Files.createTempDirectory("bitchord-empty-folder")
        try {
            assertEquals(emptyList<Song>(), DesktopLocalMusic.scanFolder(folder))
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a folder's own audio is what a playlist of it holds, and nothing above it`() {
        val root = Files.createTempDirectory("bitchord-folder-playlist")
        try {
            write(root.resolve("one.mp3"), bytes = 512L * 1024)
            write(root.resolve("two.mp3"), bytes = 512L * 1024)
            // Not audio, so not a track.
            write(root.resolve("cover.jpg"), bytes = 512L * 1024)
            // Whether a small file is a ringtone is the listener's own setting,
            // and it is read here exactly as the library scan reads it, so this
            // does not assert either way about one.
            write(root.resolve("blip.mp3"), bytes = 64L)
            val outside = Files.createTempDirectory("bitchord-outside")
            try {
                write(outside.resolve("elsewhere.mp3"), bytes = 512L * 1024)
                val songs = DesktopLocalMusic.scanFolder(root)
                assertTrue(
                    "cover.jpg is not audio and must not be a track",
                    songs.none { it.title == "cover" },
                )
                assertTrue(
                    "a file outside the dropped folder is not in it",
                    songs.none { it.title == "elsewhere" },
                )
                assertTrue(
                    "the folder's own audio is in it",
                    songs.any { it.title == "one" } && songs.any { it.title == "two" },
                )
            } finally {
                outside.toFile().deleteRecursively()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a folder that is not there is empty rather than an error`() {
        val missing = Files.createTempDirectory("bitchord-gone").resolve("not-here")
        assertTrue(DesktopLocalMusic.scanFolder(missing).isEmpty())
    }

    private fun write(path: Path, bytes: Long) {
        Files.write(path, ByteArray(bytes.toInt()) { 0 })
    }

    private fun song(id: String) = Song(videoId = id, title = id, artist = "a", thumbnailUrl = "")
}
