package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.reference.ReferenceImage

/**
 * How much of the editor a host shows around the canvas.
 *
 * - [Full]: the desktop editor — toolbar, the component and layer docks, the inspector and its
 *   rails, the status bar, and the panes beside the canvas. Every host had only this until a chat
 *   panel needed less.
 * - [FocusedCanvas]: the canvas and nothing else. A chat host (ChatGPT or Codex desktop, as an MCP
 *   App) opens a design in a side panel beside the conversation, where a big change is better asked
 *   of the agent than built by hand: the panel shows the design, lets the person point at a node
 *   and make one small change. What stays is what that needs — selection, the node's context menu,
 *   the quick editor, inline text editing, undo and the editor's keys. The host draws its own slim
 *   bar above it (name, saved state, a switch back to [Full]).
 */
enum class UiBuilderWorkspace {
  Full,
  FocusedCanvas,
}

/**
 * [base]'s controls, around a canvas with no docks: the focused layout as a chrome, so a host
 * chooses it the way IntelliJ chooses its Jewel controls, and [UiBuilderEditor] stays one editor.
 *
 * Switching between this and [base] while the editor is open keeps the editor's state — the
 * selection, the undo history, the zoom — because nothing about the design or the session is keyed
 * on the chrome.
 */
class FocusedCanvasUiBuilderChrome(private val base: UiBuilderChrome = MaterialUiBuilderChrome) :
  UiBuilderChrome by base {
  override val workspace: UiBuilderWorkspace
    get() = UiBuilderWorkspace.FocusedCanvas

  override fun equals(other: Any?): Boolean =
    other is FocusedCanvasUiBuilderChrome && other.base == base

  override fun hashCode(): Int = base.hashCode() * 31 + 1
}

/**
 * A comment a person wrote about one node, for the host to take to the conversation the editor sits
 * in.
 *
 * [image] is the node drawn on its own, as a PNG, where the platform could capture and encode it;
 * null otherwise, and the comment is sent without it.
 */
class UiBuilderNodeComment(val nodeId: String, val text: String, val image: ReferenceImage?)

/**
 * The small field the node menu's **Comment** opens, beside the node it is about.
 *
 * Drawn where the quick editor is drawn, by the same placement, because it answers the same kind of
 * question about the same node. **Send** is offered only once something is written: an empty
 * comment is not a message. Enter sends and Shift+Enter starts a new line, as a chat composer does;
 * Escape closes it.
 */
@Composable
internal fun NodeCommentCard(
  label: String,
  onSend: (String) -> Unit,
  onDismiss: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dragHandle: Modifier = Modifier,
) {
  var text by remember { mutableStateOf("") }
  val requester = remember { FocusRequester() }
  // The menu row was the gesture that asked to write, so the caret is already where to write.
  LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
  fun send() {
    val trimmed = text.trim()
    if (trimmed.isNotEmpty()) onSend(trimmed)
  }
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 4.dp,
    shadowElevation = 8.dp,
    modifier = Modifier.semantics { contentDescription = "Comment on node" },
  ) {
    Column(
      Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        "Comment on $label",
        dragHandle.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 2.dp)) {
        if (text.isEmpty()) {
          Text(
            "Ask the agent about this node…",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        BasicTextField(
          value = text,
          onValueChange = { text = it },
          textStyle =
            MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          maxLines = 6,
          modifier =
            Modifier.fillMaxWidth()
              .focusRequester(requester)
              .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
              .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                  (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                    !event.isShiftPressed -> {
                    send()
                    true
                  }
                  event.key == Key.Escape -> {
                    onDismiss()
                    true
                  }
                  else -> false
                }
              }
              .semantics { contentDescription = "Comment text" },
        )
      }
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Button(
          onClick = ::send,
          enabled = text.isNotBlank(),
          modifier = Modifier.semantics { contentDescription = "Send comment" },
        ) {
          Text("Send")
        }
      }
    }
  }
}
