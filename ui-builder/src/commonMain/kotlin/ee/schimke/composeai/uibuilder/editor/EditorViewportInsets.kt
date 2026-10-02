package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The parts of the window the editor must not put its chrome under, as the page measured them.
 *
 * A phone browser shows the page edge to edge once the shell asks for `viewport-fit=cover`: the
 * notch, the rounded corners and the home indicator are then over the page, and on Safari so is the
 * on-screen keyboard, which overlays the page rather than resizing it. Compose for web reports none
 * of that through `WindowInsets` (they are zero on the wasmJs target), so the page measures it —
 * `env(safe-area-inset-*)` and `visualViewport` — and hands it in here
 * (`BrowserViewportInsets.kt`). Everywhere else, the desktop app and every IDE host, this is [None]
 * and nothing moves.
 *
 * All values are CSS pixels, which are dp on the web target.
 */
@Immutable
data class EditorViewportInsets(
  val top: Dp = 0.dp,
  val bottom: Dp = 0.dp,
  val left: Dp = 0.dp,
  val right: Dp = 0.dp,
  /**
   * How much of the bottom of the window an on-screen keyboard covers, or zero when none does — or
   * when the browser resized the page for it instead (Chrome with `interactive-widget`), which is
   * the same thing as far as layout is concerned: nothing is behind it.
   */
  val keyboard: Dp = 0.dp,
) {
  val keyboardOpen: Boolean
    get() = keyboard > 0.dp

  companion object {
    val None: EditorViewportInsets = EditorViewportInsets()
  }
}

/** The insets of the window the editor is drawn in. See [EditorViewportInsets]. */
val LocalEditorViewportInsets = compositionLocalOf { EditorViewportInsets.None }

/**
 * Scrolls this field back into view whenever the space around it changes while it has focus.
 *
 * A Properties field near the bottom of the compact sheet is exactly where an on-screen keyboard
 * opens. Compose brings a field into view when it gains focus, but the keyboard arrives a moment
 * *after* that — and shrinks the sheet rather than the field — so without this the field being
 * typed into ends up behind the keyboard or below the sheet's fold.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.revealAboveKeyboard(): Modifier = composed {
  val requester = remember { BringIntoViewRequester() }
  var focused by remember { mutableStateOf(false) }
  val keyboard = LocalEditorViewportInsets.current.keyboard
  val windowHeight = LocalWindowInfo.current.containerSize.height
  LaunchedEffect(focused, keyboard, windowHeight) {
    if (!focused) return@LaunchedEffect
    // One frame for the sheet to lay out at its new height before asking where the field is.
    delay(KEYBOARD_SETTLE_MILLIS)
    requester.bringIntoView()
  }
  bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused }
}

private const val KEYBOARD_SETTLE_MILLIS = 32L
