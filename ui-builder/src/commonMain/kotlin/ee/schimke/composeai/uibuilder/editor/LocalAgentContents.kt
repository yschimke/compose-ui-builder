package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class UiBuilderLocalHarness(val id: String, val name: String, val installed: Boolean)

/** Local executable discovery and session ownership belong to the desktop host. */
interface UiBuilderLocalAgentHost {
  val harnesses: List<UiBuilderLocalHarness>
  val selected: String
  val conversation: UiBuilderChatController

  fun select(id: String)

  fun refresh()

  fun openSetup()
}

@Composable
internal fun LocalAgentContents(host: UiBuilderLocalAgentHost) {
  val chat = host.conversation
  var draft by remember(chat) { mutableStateOf("") }
  Column(
    Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      "Uses your local login. Sends design and comments to your agent's provider.",
      style = MaterialTheme.typography.bodySmall,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      host.harnesses.forEach { harness ->
        FilterChip(
          selected = host.selected == harness.id,
          onClick = { host.select(harness.id) },
          enabled = !chat.busy,
          label = { Text(harness.name + if (!harness.installed) " · not found" else "") },
        )
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(onClick = host::refresh, enabled = !chat.busy) { Text("Refresh") }
      TextButton(onClick = host::openSetup) { Text("Agent setup") }
    }
    Text("Private advice and drafts.", style = MaterialTheme.typography.bodySmall)
    if (chat.host.monitoringAvailable) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
          checked = chat.monitoring,
          onCheckedChange = chat::monitor,
          enabled = chat.host.connected,
        )
        Text("Monitor comments while open", style = MaterialTheme.typography.bodySmall)
      }
      Text(
        "Uses your credits. Pauses after five reviews.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    chat.session.messages.forEach { message ->
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
      ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
          Text(
            if (message.role == "user") "You" else "Agent",
            style = MaterialTheme.typography.labelSmall,
          )
          SelectionContainer { Text(message.content, style = MaterialTheme.typography.bodySmall) }
        }
      }
    }
    chat.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (chat.busy) Text("Working…", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
      value = draft,
      onValueChange = { draft = it.take(UiBuilderChatController.MAX_MESSAGE_CHARS) },
      label = { Text("Message") },
      modifier = Modifier.fillMaxWidth(),
      minLines = 2,
      maxLines = 5,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        onClick = {
          chat.send(draft)
          draft = ""
        },
        enabled = chat.host.connected && !chat.busy && draft.isNotBlank(),
      ) {
        Text("Send")
      }
      if (chat.busy || chat.monitoring) TextButton(onClick = chat::stop) { Text("Stop") }
      TextButton(onClick = chat::clear) { Text("New session") }
    }
  }
}
