package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** A node's text being typed over in place on the canvas. */
internal data class CanvasInlineTextEdit(val nodeId: String, val text: String)

/**
 * A text field over a node on the canvas, holding the text it shows: double-click a label and type
 * the new one where it is, rather than finding the same words in a panel across the screen.
 *
 * Enter or clicking away keeps the new text; Esc puts the old one back. [onDone] is told once, with
 * the text to commit or null for a cancel.
 */
@Composable
internal fun InlineTextEditor(
  edit: CanvasInlineTextEdit,
  onDone: (String?) -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  var value by
    remember(edit) {
      // All of it selected, so the first keystroke replaces the text — which is what the
      // double-click was for — and an arrow key keeps it to edit instead.
      mutableStateOf(TextFieldValue(edit.text, TextRange(0, edit.text.length)))
    }
  // Told once: Enter commits and then the field loses focus, which would commit a second time.
  var finished by remember(edit) { mutableStateOf(false) }
  fun finish(text: String?) {
    if (finished) return
    finished = true
    onTextInputFocusChanged(false)
    onDone(text)
  }
  val focus = remember(edit) { FocusRequester() }
  var focused by remember(edit) { mutableStateOf(false) }
  LaunchedEffect(edit) { focus.requestFocus() }
  Surface(
    modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
    shape = RoundedCornerShape(4.dp),
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = 4.dp,
  ) {
    BasicTextField(
      value = value,
      onValueChange = { value = it },
      singleLine = true,
      textStyle =
        MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { finish(value.text) }),
      modifier =
        Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
          .focusRequester(focus)
          .onFocusChanged { state ->
            if (state.isFocused) {
              focused = true
              onTextInputFocusChanged(true)
            } else if (focused) {
              // Clicking away is a yes, the way it is in every design tool's text box.
              finish(value.text)
            }
          }
          .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
              Key.Escape -> {
                finish(null)
                true
              }
              Key.Enter,
              Key.NumPadEnter -> {
                finish(value.text)
                true
              }
              else -> false
            }
          }
          .semantics { contentDescription = "Edit text in place" },
    )
  }
}

/**
 * Calls [onDoubleClick] when a second click follows the first in time and place, with what
 * [capture] made of the *first* click at the moment it happened.
 *
 * Captured then, not asked at the second click, because the first click selects and the editor may
 * open the Properties panel on it: the canvas re-fits, and the design moves under a pointer that
 * did not. What the first click was over is what the author aimed at; by the second, something else
 * is under the pointer, or nothing. Put this on something that stays still while the design moves.
 *
 * Watched in the initial pass and never consumed: the design drawn under it is made of real
 * components whose own click handling takes the presses first, so a `detectTapGestures` here would
 * never see them — and must not take them either, or a single click would stop selecting.
 */
internal fun <T : Any> Modifier.onDoubleClick(
  key: Any?,
  capture: (Offset) -> T?,
  onDoubleClick: (T) -> Unit,
): Modifier =
  pointerInput(key) {
    var firstUpAt = 0L
    var firstAt: Offset? = null
    var captured: T? = null
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      if (currentEvent.buttons.isSecondaryPressed) {
        firstAt = null
        return@awaitEachGesture
      }
      val first = firstAt
      val second =
        first != null &&
          down.uptimeMillis - firstUpAt <= viewConfiguration.doubleTapTimeoutMillis &&
          (down.position - first).getDistance() <= viewConfiguration.touchSlop
      val atDown = if (second) captured else capture(down.position)
      val up = waitForUpOrCancellation(PointerEventPass.Initial)
      if (up == null) {
        firstAt = null
      } else if (second) {
        firstAt = null
        atDown?.let(onDoubleClick)
      } else {
        firstUpAt = up.uptimeMillis
        firstAt = down.position
        captured = atDown
      }
    }
  }
