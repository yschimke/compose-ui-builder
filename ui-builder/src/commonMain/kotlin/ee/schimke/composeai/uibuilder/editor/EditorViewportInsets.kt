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
 * Window areas the editor must not put chrome under, as the page measured them.
 *
 * With `viewport-fit=cover`, a phone browser draws under the notch, corners, home indicator and (on
 * Safari) the keyboard. Compose for web reports none of these via `WindowInsets`, so the page
 * measures `env(safe-area-inset-*)` and `visualViewport` and passes them in
 * (`BrowserViewportInsets.kt`). Elsewhere this is [None]. Values are CSS pixels (dp on web).
 */
@Immutable
data class EditorViewportInsets(
  val top: Dp = 0.dp,
  val bottom: Dp = 0.dp,
  val left: Dp = 0.dp,
  val right: Dp = 0.dp,
  /**
   * How much of the window's bottom an on-screen keyboard covers, or zero (including when the
   * browser resized the page instead).
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
 * Scrolls this focused field back into view whenever the space around it changes. The keyboard
 * arrives after focus and shrinks the sheet, so Compose's focus scroll alone leaves the field
 * hidden.
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
