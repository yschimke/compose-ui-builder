package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Presence names are reported by the agent; a browser key is readiness, not remote presence. */
internal fun agentToolbarLabel(host: UiBuilderAgentHost): String {
  host.local?.let { local ->
    val name = local.harnesses.firstOrNull { it.id == local.selected }?.name ?: "Local agent"
    return when {
      local.conversation.busy -> "$name · working"
      local.conversation.monitoring -> "$name · monitoring"
      !local.conversation.host.connected -> "Set up $name"
      else -> name
    }
  }
  val agents = host.agents.orEmpty()
  val chat = host.chat
  val browserReady = chat?.host?.connected == true
  val browserActive = chat?.busy == true || chat?.monitoring == true
  val primary = if (browserActive || agents.isEmpty()) null else agents.first()
  val name =
    primary?.displayName?.trim()?.ifBlank { "Agent" } ?: if (browserReady) "OpenRouter" else null
  if (name == null) return if (host.agents == null) "Agents" else "Connect agent"
  val others =
    agents.size - (if (primary != null) 1 else 0) + (if (primary != null && browserReady) 1 else 0)
  val count = if (others > 0) " +$others" else ""
  val state =
    if (primary != null) ""
    else
      when {
        chat?.busy == true -> " · working"
        chat?.monitoring == true -> " · monitoring"
        else -> ""
      }
  return name + count + state
}

internal fun agentToolbarDescription(host: UiBuilderAgentHost): String = buildList {
  host.local?.let { local ->
    val name = local.harnesses.firstOrNull { it.id == local.selected }?.name ?: "Local agent"
    add(name + if (local.conversation.host.connected) " · installed locally" else " · not found")
  }
  host.agents?.forEach { agent ->
    add(listOfNotNull(agent.displayName, agent.modelName).joinToString(" · ") + " (reported)")
  }
  if (host.chat?.host?.connected == true) {
    add(
      "OpenRouter · " +
        host.chat!!.session.model +
        when {
          host.chat!!.busy -> " · working"
          host.chat!!.monitoring -> " · monitoring"
          else -> " · browser chat ready"
        }
    )
  }
  if (host.agents == null) add("Agent activity unavailable")
}
  .joinToString("; ")
  .ifBlank { "Connect an agent or browser chat" }

@Composable
internal fun AgentToolbarAction(
  host: UiBuilderAgentHost,
  onOpen: () -> Unit,
  compact: Boolean = false,
) {
  val description = agentToolbarDescription(host)
  MaterialChromeTooltip(description, "") {
    Surface(
      color = MaterialTheme.colorScheme.primaryContainer,
      shape = MaterialTheme.shapes.small,
    ) {
      TextButton(
        onClick = onOpen,
        modifier = Modifier.semantics { contentDescription = description },
      ) {
        Text(
          agentToolbarLabel(host),
          modifier = Modifier.widthIn(max = if (compact) 108.dp else 180.dp),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
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
      when {
        host.local != null -> "local"
        host.chat?.busy == true || host.chat?.monitoring == true -> "browser"
        host.agents?.isNotEmpty() == true -> "external"
        host.chat != null -> "browser"
        else -> "external"
      }
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
