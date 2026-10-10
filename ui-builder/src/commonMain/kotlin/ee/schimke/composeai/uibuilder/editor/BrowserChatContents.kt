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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun BrowserChatContents(chat: UiBuilderChatController) {
  val host = chat.host
  var keyDraft by remember(chat) { mutableStateOf("") }
  var messageDraft by remember(chat) { mutableStateOf("") }
  var settings by remember(chat) { mutableStateOf(false) }
  Column(
    Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("OpenRouter", style = MaterialTheme.typography.labelLarge)
    Text(
      "Sends this design and open comments to OpenRouter. Replies stay private.",
      style = MaterialTheme.typography.bodySmall,
    )
    if (!host.connected) {
      OutlinedButton(onClick = host::connect) { Text("Connect OpenRouter") }
      OutlinedTextField(
        value = keyDraft,
        onValueChange = { keyDraft = it },
        label = { Text("OpenRouter key") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      TextButton(
        onClick = {
          host.useKey(keyDraft)
          keyDraft = ""
        },
        enabled = keyDraft.isNotBlank(),
      ) {
        Text("Use key")
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Checkbox(checked = host.rememberConnection, onCheckedChange = host::rememberConnection)
      Text("Remember on this browser", style = MaterialTheme.typography.bodySmall)
    }
    Text(
      if (host.rememberConnection) "Key saved on this browser."
      else "Disconnects on reload or close.",
      style = MaterialTheme.typography.bodySmall,
    )
    host.connectionNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(onClick = { settings = !settings }) { Text("Settings") }
      if (host.connected) TextButton(onClick = chat::disconnect) { Text("Disconnect") }
    }
    if (settings) {
      OutlinedTextField(
        value = chat.session.model,
        onValueChange = chat::model,
        enabled = !chat.busy,
        label = { Text("OpenRouter model") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        "Edit instructions in External agent → Customize.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    if (host.monitoringAvailable) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Switch(
          modifier = Modifier.semantics { contentDescription = "Monitor comments while open" },
          checked = chat.monitoring,
          onCheckedChange = chat::monitor,
          enabled = host.connected,
        )
        Text("Monitor comments while open", style = MaterialTheme.typography.bodySmall)
      }
      Text(
        "Uses your credits. Pauses after five reviews.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    if (chat.session.messages.isEmpty()) {
      Text("Ask about this design.")
    }
    chat.session.messages.forEach { message ->
      Surface(
        color =
          if (message.role == "user") MaterialTheme.colorScheme.surfaceContainerHigh
          else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
      ) {
        Column(
          Modifier.fillMaxWidth().padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            if (message.role == "user") "You" else "Assistant",
            style = MaterialTheme.typography.labelSmall,
          )
          SelectionContainer { Text(message.content, style = MaterialTheme.typography.bodySmall) }
        }
      }
    }
    chat.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (chat.busy) Text("Thinking…", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
      value = messageDraft,
      onValueChange = { messageDraft = it.take(UiBuilderChatController.MAX_MESSAGE_CHARS) },
      label = { Text("Message") },
      modifier = Modifier.fillMaxWidth(),
      minLines = 2,
      maxLines = 5,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        onClick = {
          chat.send(messageDraft)
          messageDraft = ""
        },
        enabled = host.connected && !chat.busy && messageDraft.isNotBlank(),
      ) {
        Text("Send")
      }
      if (chat.busy || chat.monitoring) TextButton(onClick = chat::stop) { Text("Stop") }
      TextButton(onClick = chat::clear) { Text("Clear chat") }
    }
  }
}
