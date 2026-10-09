package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.guidelines.DEFAULT_GUIDELINE_MODEL
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineController
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineFinding
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRequest
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineResult
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineState
import ee.schimke.composeai.uibuilder.guidelines.OPENROUTER_KEYS_URL
import ee.schimke.composeai.uibuilder.guidelines.describe
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The guidelines section of the Issues panel: a model reads the design on screen against the
 * Android design guides for its platform, on the person's own OpenRouter key, and each rule it
 * judges broken is listed with the guide it comes from.
 *
 * Advisory only. Nothing here feeds the export gate, so a finding never blocks an export and the
 * panel's blocking counts are unchanged by it.
 */
@Composable
internal fun GuidelinesSection(
  controller: DesignGuidelineController,
  document: UiBuilderDocument,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val state by controller.state.collectAsState()
  val scope = rememberCoroutineScope()
  var settingsOpen by remember { mutableStateOf(false) }
  // The first item of the Issues list, which scrolls as one; no inner scroll box of its own.
  Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
    Text("Design guidelines", style = MaterialTheme.typography.labelLarge)
    val prompt by controller.prompt.collectAsState()
    val shared by controller.shared.collectAsState()
    LaunchedEffect(controller, document.id) { controller.loadShared() }
    var local: DesignGuidelineResult? = null
    when (val current = state) {
      is DesignGuidelineState.NeedsKey ->
        KeySetup(controller, current.notice, onTextInputFocusChanged)
      is DesignGuidelineState.Ready -> {
        local = current.result
        Text(
          "Checks this design against the Android design guides with ${current.model}, on your " +
            "OpenRouter key. Findings are advice; they never block an export.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        Row(
          Modifier.fillMaxWidth().padding(top = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          OutlinedButton(
            onClick = {
              scope.launch {
                controller.check(document, DesignGuidelineController.encode(document))
              }
            },
            enabled = !current.running,
          ) {
            Text(if (current.running) "Checking…" else "Check guidelines")
          }
          TextButton(onClick = { settingsOpen = !settingsOpen }) {
            Text(if (settingsOpen) "Done" else "Model & key")
          }
        }
        if (settingsOpen) {
          ModelSettings(controller, current.model, onTextInputFocusChanged)
        }
        current.notice?.let {
          Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
    // What the model is asked, readable before anybody spends a key on it.
    TextButton(
      onClick = {
        if (prompt is DesignGuidelineController.PromptView.Hidden) {
          scope.launch { controller.preview(document, DesignGuidelineController.encode(document)) }
        } else {
          controller.hidePrompt()
        }
      }
    ) {
      Text(
        if (prompt is DesignGuidelineController.PromptView.Hidden) "Show the prompt"
        else "Hide the prompt"
      )
    }
    PromptView(prompt)
    val overlay by controller.overlay.collectAsState()
    Row(
      Modifier.fillMaxWidth().padding(top = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text("Show findings on the canvas", style = MaterialTheme.typography.bodySmall)
      Switch(
        checked = overlay,
        onCheckedChange = controller::setOverlay,
        modifier = Modifier.semantics { contentDescription = "Show findings on the canvas" },
      )
    }
    // This person's latest run, or else the design's latest recorded one — an agent's, another
    // person's or the server's.
    (local ?: shared)?.let { result -> ResultSummary(result, document, dispatch) }
    HorizontalDivider(Modifier.padding(top = 8.dp))
  }
}

@Composable
private fun KeySetup(
  controller: DesignGuidelineController,
  notice: String?,
  onTextInputFocusChanged: (Boolean) -> Unit,
) {
  val uriHandler = LocalUriHandler.current
  var draft by remember { mutableStateOf("") }
  Text(
    "Check this design against the Android design guides (developer.android.com) with a model " +
      "of your choice. It runs on your own OpenRouter account: connect it below, or create a key " +
      "on openrouter.ai (Settings, then Keys) and paste it here. The key stays in this browser and is " +
      "sent only to openrouter.ai.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
  notice?.let {
    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
  }
  Row(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (controller.canSignIn) {
      OutlinedButton(onClick = controller::signIn) { Text("Connect OpenRouter") }
    }
    TextButton(onClick = { uriHandler.openUri(OPENROUTER_KEYS_URL) }) { Text("Get a key") }
  }
  OutlinedTextField(
    value = draft,
    onValueChange = { draft = it },
    singleLine = true,
    label = { Text("OpenRouter key") },
    placeholder = { Text("sk-or-…") },
    visualTransformation = PasswordVisualTransformation(),
    modifier =
      Modifier.fillMaxWidth()
        .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
        .semantics { contentDescription = "OpenRouter API key" },
  )
  TextButton(onClick = { controller.saveKey(draft) }, enabled = draft.isNotBlank()) {
    Text("Save key")
  }
}

@Composable
private fun ModelSettings(
  controller: DesignGuidelineController,
  model: String,
  onTextInputFocusChanged: (Boolean) -> Unit,
) {
  var draft by remember(model) { mutableStateOf(model) }
  Column(Modifier.fillMaxWidth()) {
    OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      singleLine = true,
      label = { Text("OpenRouter model") },
      supportingText = { Text("Default $DEFAULT_GUIDELINE_MODEL. Any OpenRouter model id works.") },
      modifier =
        Modifier.fillMaxWidth().onFocusChanged { focus ->
          onTextInputFocusChanged(focus.isFocused)
          if (!focus.isFocused && draft != model) controller.setModel(draft)
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      TextButton(onClick = { controller.setModel(draft) }) { Text("Use model") }
      TextButton(onClick = controller::forgetKey) { Text("Forget key") }
    }
  }
}

@Composable
private fun GuidelineFindingRow(
  finding: DesignGuidelineFinding,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val uriHandler = LocalUriHandler.current
  var detailsOpen by remember(finding.rule.id) { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
    Text(
      finding.reason,
      color =
        if (finding.rule.severity == "warning") MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium,
    )
    Text(
      "${finding.rule.id} · ${finding.confidencePercent}% sure" +
        finding.nodeIds.takeIf { it.isNotEmpty() }?.let { " · ${it.joinToString()}" }.orEmpty(),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      finding.nodeIds.firstOrNull()?.let { nodeId ->
        TextButton(
          onClick = {
            dispatch(UiBuilderEditorEvent.SelectNode(nodeId))
            dispatch(UiBuilderEditorEvent.ShowInspector(EditorInspectorMode.Properties))
          },
          modifier = Modifier.semantics { contentDescription = "Go to layer $nodeId" },
        ) {
          Text("Go to layer")
        }
      }
      TextButton(onClick = { detailsOpen = !detailsOpen }) {
        Text(if (detailsOpen) "Hide guideline" else "Guideline")
      }
      if (finding.rule.source.startsWith("https://")) {
        TextButton(onClick = { uriHandler.openUri(finding.rule.source) }) { Text("Open guide") }
      }
    }
    if (detailsOpen) {
      SelectionContainer {
        Column {
          Text("“${finding.rule.guidance}”", style = MaterialTheme.typography.bodySmall)
          Text(finding.rule.source, style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}

@Composable
private fun ResultSummary(
  result: DesignGuidelineResult,
  document: UiBuilderDocument,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val stale = result.revision != document.revision
  Text(
    buildString {
      append("Revision ").append(result.revision).append(" · ")
      append(result.served?.describe(result.model) ?: result.model)
      result.ranBy?.let { append(" · run by ").append(it) }
    },
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
    modifier = Modifier.padding(top = 4.dp),
  )
  Text(
    when {
      result.judged == 0 && result.unanswered.isEmpty() ->
        "No guidelines are written for this design's catalog yet."
      result.findings.isEmpty() -> "${result.judged} guideline(s) checked; none broken."
      else -> "${result.findings.size} of ${result.judged} guideline(s) look broken."
    } +
      (if (result.visualSkipped > 0)
        " ${result.visualSkipped} visual guideline(s) need a picture of the design, which this " +
          "host could not provide."
      else "") +
      (if (result.request != null && result.platform != null && !result.sourceAttached)
        " Judged from the design tree alone; this host could not export its Compose source."
      else "") +
      (if (result.unanswered.isNotEmpty())
        " The model gave no answer for ${result.unanswered.size} guideline(s); they are " +
          "unchecked, not passed."
      else "") +
      (if (stale) " Checked an earlier revision; check again to update." else ""),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
  result.findings.forEach { finding -> GuidelineFindingRow(finding, dispatch) }
}

/**
 * The prompt, as the model reads it, with where each part comes from. Everything in it is
 * selectable, and Copy hands the whole request to another tool or agent.
 */
@Composable
private fun PromptView(view: DesignGuidelineController.PromptView) {
  val uriHandler = LocalUriHandler.current
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  when (view) {
    DesignGuidelineController.PromptView.Hidden -> Unit
    DesignGuidelineController.PromptView.Loading ->
      Text(
        "Building the prompt…",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
    is DesignGuidelineController.PromptView.Failed ->
      Text(
        view.reason,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
      )
    is DesignGuidelineController.PromptView.Shown -> {
      val request = view.request
      Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Where this prompt comes from", style = MaterialTheme.typography.labelMedium)
        request.provenance.forEach { line ->
          Text(
            "• $line",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(onClick = { uriHandler.openUri(request.rules.source) }) {
            Text("Open the rule set")
          }
          TextButton(
            onClick = {
              scope.launch {
                runCatching { clipboard.setClipEntry(plainTextClipEntry(promptForCopy(request))) }
              }
            },
            modifier = Modifier.semantics { contentDescription = "Copy the prompt" },
          ) {
            Text("Copy prompt")
          }
        }
        if (request.pictures.isNotEmpty()) {
          Text("Pictures attached", style = MaterialTheme.typography.labelMedium)
          request.pictures.forEach { picture ->
            Text(
              "• ${picture.description}",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        Text(
          "Rules asked: ${request.rules.asked.size} of ${request.rules.forPlatform} " +
            "(rule set version ${request.rules.version})",
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(top = 4.dp),
        )
        Text("System prompt", style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
          Text(
            request.systemPrompt,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          )
        }
        Text(
          "User message",
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(top = 4.dp),
        )
        SelectionContainer {
          Text(
            request.userText,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          )
        }
      }
    }
  }
}

/** The request as JSON for the clipboard, with picture bytes left out to keep it pasteable. */
internal fun promptForCopy(request: DesignGuidelineRequest): String =
  PROMPT_COPY_JSON.encodeToString(
    DesignGuidelineRequest.serializer(),
    request.copy(pictures = request.pictures.map { it.copy(dataUrl = null) }),
  )

private val PROMPT_COPY_JSON = Json { prettyPrint = true }
