package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun AgentToolbarAction(host: UiBuilderAgentHost, onOpen: () -> Unit) {
  val active = host.agents?.size ?: 0
  Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
    TextButton(onClick = onOpen) {
      Text(if (active > 0) "Agent · $active active" else "Connect agent")
    }
  }
}

@Composable
internal fun AgentInvitation(
  host: UiBuilderAgentHost,
  onOpen: () -> Unit,
  onNotice: (String) -> Unit,
) {
  if (host.preferences.hintDismissed || host.agents?.isNotEmpty() == true) return
  Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        "Bring your agent into this design",
        Modifier.weight(1f).padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall,
      )
      TextButton(onClick = onOpen) { Text("Connect") }
      TextButton(
        onClick = { host.save(host.preferences.copy(hintDismissed = true))?.let(onNotice) }
      ) {
        Text("Dismiss")
      }
    }
  }
}

@Composable
internal fun AgentPromptDialog(
  host: UiBuilderAgentHost,
  onDismiss: () -> Unit,
  onNotice: (String) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Work with your agent") },
    text = { AgentPromptContents(host, onNotice) },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
  )
}

/** Also rendered directly by the preview: popup content is not captured by static previews. */
@Composable
internal fun AgentPromptContents(host: UiBuilderAgentHost, onNotice: (String) -> Unit) {
  var draft by remember(host.preferences) { mutableStateOf(host.preferences) }
  val includeSetup = !draft.connectedBefore
  var customizePrompt by remember { mutableStateOf(false) }
  val generatedPrompt = host.prompt(includeSetup, draft.instructions())
  var prompt by remember(generatedPrompt) { mutableStateOf(generatedPrompt) }
  val promptInteraction = remember { MutableInteractionSource() }
  val scope = rememberCoroutineScope()
  val copyPrompt by
    rememberUpdatedState<() -> Unit> {
      val text = prompt
      host.save(draft)?.let(onNotice)
      scope.launch { onNotice(host.copy(text)) }
    }
  LaunchedEffect(promptInteraction) {
    promptInteraction.interactions.collect { interaction ->
      if (interaction is PressInteraction.Release) copyPrompt()
    }
  }
  Column(
    Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("You · browser", style = MaterialTheme.typography.labelMedium)
    host.viewers.forEach { viewer ->
      Text(
        "${viewer.displayName} · ${if (viewer.kind == UiBuilderParticipantKind.Browser) "browser" else "participant"}",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    when (val agents = host.agents) {
      null ->
        Text("Agent presence unavailable on this host", style = MaterialTheme.typography.bodySmall)
      else -> {
        Text(
          if (agents.isEmpty()) "No recent agent activity" else "Active on this design",
          style = MaterialTheme.typography.labelMedium,
        )
        agents.forEach { agent ->
          Text(
            listOfNotNull(agent.displayName, agent.modelName?.let { "$it (reported)" })
              .joinToString(" · ")
          )
        }
        Text(
          "Agents appear while using this design and for 30 seconds afterward.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        Modifier.weight(1f)
          .heightIn(min = 48.dp)
          .toggleable(
            value = includeSetup,
            role = Role.Checkbox,
            onValueChange = { draft = draft.copy(connectedBefore = !it) },
          ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Checkbox(checked = includeSetup, onCheckedChange = null)
        Text("Include setup", style = MaterialTheme.typography.bodySmall)
      }
      Row(
        Modifier.weight(1f)
          .heightIn(min = 48.dp)
          .toggleable(
            value = customizePrompt,
            role = Role.Checkbox,
            onValueChange = { customizePrompt = it },
          ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Checkbox(checked = customizePrompt, onCheckedChange = null)
        Text("Customize", style = MaterialTheme.typography.bodySmall)
      }
    }
    if (customizePrompt) {
      OutlinedTextField(
        value = draft.generalInstructions,
        onValueChange = { draft = draft.copy(generalInstructions = it) },
        label = { Text("Instructions for all designs") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
      )
      OutlinedTextField(
        value = draft.documentInstructions,
        onValueChange = { draft = draft.copy(documentInstructions = it) },
        label = { Text("Instructions for this design") },
        placeholder = { Text("Leave blank to use general instructions") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
      )
      Text(
        "Saved in this browser. Design instructions override your general instructions.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    OutlinedTextField(
      value = prompt,
      onValueChange = { prompt = it },
      label = { Text("Prompt") },
      supportingText = { Text("Click to copy, or edit and use Copy prompt.") },
      modifier = Modifier.fillMaxWidth(),
      minLines = 5,
      maxLines = 6,
      textStyle = MaterialTheme.typography.bodySmall,
      interactionSource = promptInteraction,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(onClick = { copyPrompt() }) { Text("Copy prompt") }
      if (customizePrompt) {
        TextButton(onClick = { onNotice(host.save(draft) ?: "Prompt preferences saved") }) {
          Text("Save preferences")
        }
      }
    }
    if (includeSetup) {
      TextButton(onClick = host::openSetup) { Text("Set up Claude, Codex, Antigravity or Other") }
      TextButton(onClick = host::connectVsCode) { Text("Add MCP connection to VS Code") }
      Text(
        "VS Code will ask you to review the connection. Then paste the prompt into your agent.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}
