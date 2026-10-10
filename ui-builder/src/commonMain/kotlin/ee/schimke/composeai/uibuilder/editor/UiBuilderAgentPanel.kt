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
  val localChat = host.local?.conversation
  Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
    TextButton(onClick = onOpen) {
      Text(
        when {
          localChat?.monitoring == true -> "Agent · monitoring"
          localChat?.busy == true -> "Agent · working"
          host.local != null -> "Local agent"
          host.chat?.monitoring == true -> "Chat · monitoring"
          host.chat?.busy == true -> "Chat · working"
          host.chat?.host?.connected == true -> "Chat"
          active > 0 -> "Agent · $active active"
          else -> "Connect agent"
        }
      )
    }
  }
}

@Composable
internal fun AgentInvitation(
  host: UiBuilderAgentHost,
  onOpen: () -> Unit,
  onNotice: (String) -> Unit,
) {
  if (
    host.preferences.hintDismissed ||
      host.local != null ||
      host.agents?.isNotEmpty() == true ||
      host.chat?.host?.connected == true
  )
    return
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
  var tab by remember {
    mutableStateOf(
      if (host.local != null) "local" else if (host.chat != null) "browser" else "external"
    )
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Work with your agent") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (host.chat != null || host.local != null) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (host.local != null) {
              TextButton(onClick = { tab = "local" }, enabled = tab != "local") {
                Text("Local agent")
              }
            }
            if (host.chat != null) {
              TextButton(onClick = { tab = "browser" }, enabled = tab != "browser") {
                Text("Browser chat")
              }
            }
            TextButton(onClick = { tab = "external" }, enabled = tab != "external") {
              Text("External agent")
            }
          }
        }
        if (tab == "local" && host.local != null) LocalAgentContents(host.local!!)
        else if (tab == "browser" && host.chat != null) BrowserChatContents(host.chat!!)
        else AgentPromptContents(host, onNotice)
      }
    },
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
      null -> Text("Activity unavailable", style = MaterialTheme.typography.bodySmall)
      else -> {
        Text(
          if (agents.isEmpty()) "No recent activity" else "Active on this design",
          style = MaterialTheme.typography.labelMedium,
        )
        agents.forEach { agent ->
          Text(
            listOfNotNull(agent.displayName, agent.modelName?.let { "$it (reported)" })
              .joinToString(" · ")
          )
        }
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
        if (host.local != null) "Saved for this session." else "Saved on this browser.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    OutlinedTextField(
      value = prompt,
      onValueChange = { prompt = it },
      label = { Text("Prompt") },
      supportingText = { Text("Click to copy") },
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
    if (host.monitoringHandoffAvailable)
      TextButton(
        onClick = {
          host.save(draft)?.let(onNotice)
          scope.launch {
            onNotice(
              host.copy(
                host.prompt(
                  includeSetup,
                  draft.instructions() +
                    "\n" +
                    "Run on my machine or in my personal cloud environment, not on the shared design server. " +
                    "Monitor this design's comments using ui_builder_await_comments. Keep your conversation " +
                    "and last processed comment sequence in your own runtime. Read new human comments, " +
                    "draft replies and propose design suggestions; ask before posting replies, resolving " +
                    "threads or applying changes to the main design. Use a scoped, expiring grant for this " +
                    "design and stop when I ask or access expires. Do not send provider credentials or " +
                    "private conversation history to the design server.",
                )
              )
            )
          }
        }
      ) {
        Text("Copy monitoring prompt")
      }
    if (includeSetup) {
      TextButton(onClick = host::openSetup) { Text("Agent setup") }
      if (host.local == null) TextButton(onClick = host::connectVsCode) { Text("Add to VS Code") }
    }
  }
}
