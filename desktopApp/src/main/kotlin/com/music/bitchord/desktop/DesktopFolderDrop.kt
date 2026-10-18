package com.music.bitchord.desktop

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * The tint is the whole affordance: without a drop target drawn, a gesture that
 * silently does nothing is indistinguishable from a gesture the app never
 * noticed.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.dropFolderPlaylist(
    enabled: Boolean = DesktopVariant.isLocal,
    onFolders: (List<String>) -> Unit,
): Modifier {
    // False rather than skipped when there is nothing to accept: the modifier is
    // attached in both variants, and removing it would be a different tree.
    if (!enabled) return this
    var hovering by remember { mutableStateOf(false) }

    val target = remember(onFolders) {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                hovering = carriesFiles(event)
            }

            override fun onChanged(event: DragAndDropEvent) {
                hovering = carriesFiles(event)
            }

            override fun onExited(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val paths = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                if (paths.isEmpty()) return false
                onFolders(paths)
                // Taken, so a second target further down the tree does not also
                // claim the same drop and the same folder twice over.
                return true
            }
        }
    }

    return this
        .dragAndDropTarget(
            // Only file lists: anything else dragged across the window is none of
            // this app's business, and claiming it would take the gesture away
            // from whatever underneath was meant to have it.
            shouldStartDragAndDrop = ::carriesFiles,
            target = target,
        )
        .drawWithContent {
            drawContent()
            if (hovering) {
                drawRect(DesktopAccent.copy(alpha = FOLDER_DROP_TINT_ALPHA))
            }
        }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun carriesFiles(event: DragAndDropEvent): Boolean = event.dragData() is DragData.FilesList

/** Enough to be seen over the player, little enough to read through. */
private const val FOLDER_DROP_TINT_ALPHA = 0.14f
