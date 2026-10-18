package com.music.bitchord.desktop

/**
 * Which of the two players this build is.
 *
 * [FULL] is BitChord as it was: YouTube Music, an account, playlists from the
 * service, and Search that asks the service. [LOCAL] is the same player with all
 * of that taken out — it plays the music on this computer, and its Search
 * answers from that library rather than from anywhere else.
 *
 * Decided when the package is built, by `-Pbitchord.variant`, and handed to the
 * app as a system property the same way the version is. Nothing here is a
 * setting: there is nothing to switch at runtime, and a local build cannot
 * quietly start talking to YouTube because a preference was flipped.
 */
internal enum class DesktopVariant {

    /** YouTube Music, an account, and Search over the service. */
    FULL,

    /** This computer's music only. Search answers from the local library. */
    LOCAL,

    ;

    companion object {
        /**
         * The variant this build was packaged as.
         *
         * Unknown values fall back to [FULL] rather than throwing: a missing or
         * misspelled property should leave a working app, and the full player is
         * the one that still works if it cannot tell.
         */
        val current: DesktopVariant = when (System.getProperty("bitchord.variant")?.lowercase()) {
            "local" -> LOCAL
            else -> FULL
        }

        /** True when this build has no streaming source at all. */
        val isLocal: Boolean get() = current == LOCAL

        /** What the settings footer calls this build, which is how it is told apart. */
        val label: String get() = if (isLocal) "Local music player" else "YouTube Music client"

        /** The package's own name, matching what jpackage was told to call it. */
        val appName: String get() = if (isLocal) "BitChord Local" else "BitChord"
    }
}