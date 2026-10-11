package ee.schimke.composeai.uibuilder.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * How many editor-owned popups (menus, dialogs) are open. A pinned catalog's device preview is a
 * DOM iframe above the Compose canvas, so a host reads [anyOpen] and drops that surface below the
 * canvas while a popup is up; the canvas hole keeps the preview visible beneath the popup.
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
