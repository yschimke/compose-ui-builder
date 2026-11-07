package ee.schimke.composeai.uibuilder.inspector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.export.HostLogic
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.hostlogic.HostLogicController
import ee.schimke.composeai.uibuilder.hostlogic.HostLogicStatus
import kotlinx.serialization.json.JsonNull

/** The editor's running host logic, for the panel that shows and resets it; null outside one. */
internal val LocalHostLogicController = staticCompositionLocalOf<HostLogicController?> { null }

/** What a design without host logic starts from: the contract, and nothing else. */
internal val HOST_LOGIC_TEMPLATE: String =
  """
  // Host logic: stands in for the host that feeds this design. It answers the design's host
  // actions and sets its host inputs (state whose persistence is "host").
  export function init(env) {
    return { state: {}, values: {} };
  }

  export function onAction(state, action, env) {
    // action.name, and action.value when the action has one; env.values holds the host inputs.
    return { state, values: {} };
  }
  """
    .trimIndent() + "\n"

/**
 * The design's host logic: its source, whether it is running, its private state, and a reset. An
 * edit is a document operation like any other, so it is shared, undone and versioned with the
 * design; the program reloads on the canvas when it changes.
 */
@Composable
internal fun HostLogicInspector(
  document: UiBuilderDocument,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val controller = LocalHostLogicController.current
  val stored = HostLogic.of(document)?.source
  var expanded by remember { mutableStateOf(false) }
  var draft by remember(stored) { mutableStateOf(stored ?: HOST_LOGIC_TEMPLATE) }
  val status = controller?.status ?: HostLogicStatus.None
  TextButton(onClick = { expanded = !expanded }) {
    Text(
      when {
        stored == null -> "Host logic"
        status is HostLogicStatus.Failed -> "Host logic · failed"
        status == HostLogicStatus.Running -> "Host logic · running"
        else -> "Host logic · set"
      },
    )
  }
  if (!expanded) return
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      "A program that answers this design's host actions by setting its host inputs, so the " +
        "canvas behaves as the real host would.",
      style = MaterialTheme.typography.bodySmall,
    )
    Text(
      when (status) {
        HostLogicStatus.None ->
          if (stored == null) "Not set: host actions are only shown as they fire."
          else "Not running."
        HostLogicStatus.Unavailable -> "Runs in the browser editor; this editor shows it only."
        HostLogicStatus.Loading -> "Loading…"
        HostLogicStatus.Running -> "Running."
        is HostLogicStatus.Failed -> status.message
      },
      style = MaterialTheme.typography.bodySmall,
      color =
        if (status is HostLogicStatus.Failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.semantics { contentDescription = "Host logic status" },
    )
    OutlinedTextField(
      draft,
      { draft = it },
      Modifier.fillMaxWidth().heightIn(min = 160.dp).onFocusChanged {
        onTextInputFocusChanged(it.hasFocus)
      },
      label = { Text("Source (JavaScript)") },
      textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      TextButton(
        enabled = draft != stored,
        onClick = { dispatch(UiBuilderEditorEvent.SetHostLogic(draft)) },
        modifier = Modifier.semantics { contentDescription = "Save host logic" },
      ) {
        Text("Save")
      }
      if (status == HostLogicStatus.Running || status is HostLogicStatus.Failed)
        TextButton(onClick = { controller?.reset() }) { Text("Reset host") }
      if (stored != null)
        TextButton(onClick = { dispatch(UiBuilderEditorEvent.SetHostLogic(null)) }) {
          Text("Remove")
        }
    }
    val state = controller?.state ?: JsonNull
    if (stored != null && state != JsonNull)
      Text(
        "Private state: $state",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 6,
      )
  }
}
