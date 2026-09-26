package ee.schimke.composeai.uibuilder.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * How many editor-owned popups — menus and dialogs — are open right now.
 *
 * The editor draws every popup inside its one Compose canvas, but a device preview of a pinned
 * catalog is a DOM iframe **above** that canvas, because it has to take the pointer to be tried
 * out. So a menu that opened over a device frame was painted underneath it, and clicking where the
 * menu should have been clicked the design instead. A host with such a surface reads [anyOpen] and
 * drops the surface beneath the canvas while a popup is up; the canvas's hole keeps the preview
 * visible, and the popup, drawn over the hole, is on top.
 */
internal object EditorOverlays {
  private var openCount by mutableIntStateOf(0)

  /** True while at least one tracked popup is open. Reading it recomposes when it changes. */
  val anyOpen: Boolean
    get() = openCount > 0

  internal fun opened() {
    openCount++
  }

  internal fun closed() {
    openCount = (openCount - 1).coerceAtLeast(0)
  }
}

/** Counts this popup in [EditorOverlays] for as long as [open] is true and it is composed. */
@Composable
internal fun TrackEditorOverlay(open: Boolean) {
  DisposableEffect(open) {
    if (open) EditorOverlays.opened()
    onDispose { if (open) EditorOverlays.closed() }
  }
}
