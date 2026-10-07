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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.guidelines.DEFAULT_GUIDELINE_MODEL
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineController
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineFinding
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineState
import ee.schimke.composeai.uibuilder.guidelines.OPENROUTER_KEYS_URL
import kotlinx.coroutines.launch

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
  // Bounded and scrolled on its own, so a long list of findings stays reachable and the export
  // problems under it keep their room.
  Column(
    Modifier.fillMaxWidth()
      .heightIn(max = GUIDELINES_MAX_HEIGHT)
      .verticalScroll(rememberScrollState())
      .padding(bottom = 12.dp)
  ) {
    Text("Design guidelines", style = MaterialTheme.typography.labelLarge)
    when (val current = state) {
      is DesignGuidelineState.NeedsKey ->
        KeySetup(controller, current.notice, onTextInputFocusChanged)
      is DesignGuidelineState.Ready -> {
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
        current.result?.let { result ->
          val stale = result.revision != document.revision
          Text(
            when {
              result.platform == null -> "No guidelines are written for this design's catalog yet."
              result.findings.isEmpty() -> "${result.judged} guideline(s) checked; none broken."
              else -> "${result.findings.size} of ${result.judged} guideline(s) look broken."
            } +
              (if (result.visualSkipped > 0)
                " ${result.visualSkipped} visual guideline(s) need a picture of the design, " +
                  "which this host could not provide."
              else "") +
              (if (result.platform != null && !result.sourceAttached)
                " Judged from the design tree alone; this host could not export its Compose " +
                  "source."
              else "") +
              (if (result.unanswered.isNotEmpty())
                " The model gave no answer for ${result.unanswered.size} guideline(s); they " +
                  "are unchecked, not passed."
              else "") +
              (if (stale) " Checked an earlier revision; check again to update." else ""),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
          )
          result.findings.forEach { finding -> GuidelineFindingRow(finding, dispatch) }
        }
      }
    }
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
      "at openrouter.ai → Settings → Keys and paste it here. The key stays in this browser and is " +
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

/** How tall the guidelines section may grow before it scrolls. */
private val GUIDELINES_MAX_HEIGHT = 320.dp
