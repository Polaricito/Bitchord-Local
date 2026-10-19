package com.music.bitchord.desktop

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.drawWithContent

/**
 * The window as somewhere to let go of a folder.
 *
 * Over the whole window rather than over one column of it. A listener with a
 * folder open in Explorer is aiming at the app, not at the sidebar: asking them
 * to find the one part of it that lights up is asking for the feature to go
 * unnoticed.
 *
 * Only in a local build, and only because that is where it is the answer to a
 * question. There, making a playlist is otherwise impossible - the song menu's
 * "Add to playlist" needs an account to add it to - and a folder dropped here
 * becomes one. In the full build the account's own playlists are there, and a
 * row in the sidebar that quietly duplicated them would be a worse answer than
 * none.
 *
 * What arrives is a list of paths from the file manager, folders among them, and
 * one folder is all a playlist can be - see [dropFolders].
 *
 * Three things here are deliberate, because a drop target that is only
 * sometimes right is worse than none:
 *
 * - The hover tint is set on every event that can report the pointer entering,
 *   not only on `onStarted`. `onStarted` is for a drag that began inside this
 *   app; a folder coming from Explorer produces `onEntered` and never
 *   `onStarted`, and a tint driven by that alone would never appear.
 * - The callback is read through [rememberUpdatedState] and the target object is
 *   made once. Keying the target on the lambda rebuilt it on every
 *   recomposition, and the app recomposes constantly - a drag held over the
 *   window while the clock ticks would be swapped out from under itself.
 * - `dragData()` is guarded. It reads an AWT transferable, and a drag that
 *   changes flavour midway can hand it an event with nothing in it to read.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.dropFolderPlaylist(
    enabled: Boolean = DesktopVariant.isLocal,
    onFolders: (List<String>) -> Unit,
): Modifier {
    if (!enabled) return this
    val hovering = remember { mutableStateOf(false) }
    val latest = rememberUpdatedState(onFolders)

    val target = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                hovering.value = carriesFiles(event)
            }

            override fun onEntered(event: DragAndDropEvent) {
                hovering.value = carriesFiles(event)
            }

            override fun onMoved(event: DragAndDropEvent) {
                hovering.value = carriesFiles(event)
            }

            override fun onChanged(event: DragAndDropEvent) {
                hovering.value = carriesFiles(event)
            }

            override fun onExited(event: DragAndDropEvent) {
                hovering.value = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                hovering.value = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering.value = false
                val paths = filesIn(event)
                // Logged before anything is read: a folder that turns out to have
                // no audio and a drop that never arrived look the same in the UI,
                // and only one of them is a bug.
                DesktopTrackLog.log("drop: ${paths.size} path(s) ${paths.joinToString()}")
                if (paths.isEmpty()) return false
                latest.value(paths)
                // Taken, so a second target further down the tree does not also
                // claim the same drop and the same folder twice over.
                return true
            }
        }
    }

    return this
        .dragAndDropTarget(
            shouldStartDragAndDrop = { carriesFiles(it) },
            target = target,
        )
        .drawWithContent {
            drawContent()
            if (hovering.value) {
                drawRect(DesktopAccent.copy(alpha = FOLDER_DROP_TINT_ALPHA))
            }
        }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun carriesFiles(event: DragAndDropEvent): Boolean = filesIn(event).isNotEmpty()

/** The paths a dropped file list names, or empty when this is some other kind of drag. */
@OptIn(ExperimentalComposeUiApi::class)
private fun filesIn(event: DragAndDropEvent): List<String> {
    val data = runCatching { event.dragData() }.getOrNull() ?: return emptyList()
    val files = data as? DragData.FilesList ?: return emptyList()
    return runCatching { files.readFiles() }.getOrDefault(emptyList())
}

/** Enough to be seen over the player, little enough to read through. */
private const val FOLDER_DROP_TINT_ALPHA = 0.14f
