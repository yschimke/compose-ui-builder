package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument

/** How wide the Tune card is: room for a label, a slider worth dragging and a value. */
private val TUNE_PANEL_WIDTH = 300.dp

/**
 * The design's tunables as sliders, floated over the canvas so a drag and its effect are on screen
 * together whichever dock is open. Collapses to one chip.
 *
 * Every slider move is [UiBuilderEditorEvent.SetTunedValue], which never touches the document;
 * Apply is the one edit, and Reset puts every slider back on its default.
 */
@Composable
internal fun TunePanel(
  document: UiBuilderDocument,
  tunables: List<DesignTunable>,
  values: Map<String, Double>,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(true) }
  var editing by remember { mutableStateOf<String?>(null) }
  val tuned = document.isTuned(tunables, values)
  Surface(
    modifier = modifier.width(TUNE_PANEL_WIDTH),
    shape = MaterialTheme.shapes.medium,
    tonalElevation = 3.dp,
    shadowElevation = 4.dp,
  ) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "Tune · ${tunables.size}",
          Modifier.weight(1f),
          style = MaterialTheme.typography.titleSmall,
        )
        TextButton(
          onClick = { expanded = !expanded },
          modifier = Modifier.semantics { contentDescription = "Toggle tune panel" },
        ) {
          Text(if (expanded) "Hide" else "Show")
        }
      }
      if (!expanded) return@Column
      Text(
        if (tuned) "The canvas shows tuned values. Apply keeps them."
        else "Drag to try values. The design is unchanged until you apply.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        tunables.forEach { tunable ->
          if (editing == tunable.name) {
            TunableEditor(
              tunable = tunable,
              taken = tunables.map(DesignTunable::name) - tunable.name,
              onTextInputFocusChanged = onTextInputFocusChanged,
              onDone = { edited ->
                if (edited != null) dispatch(UiBuilderEditorEvent.EditTunable(tunable.name, edited))
                editing = null
              },
            )
          } else {
            TunableSlider(
              document = document,
              tunable = tunable,
              value = values.valueOf(tunable),
              onEdit = { editing = tunable.name },
              dispatch = dispatch,
            )
          }
          HorizontalDivider()
        }
      }
      Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(
          enabled = values.isNotEmpty(),
          onClick = { dispatch(UiBuilderEditorEvent.ResetTunedValues) },
        ) {
          Text("Reset")
        }
        TextButton(
          enabled = tuned,
          onClick = { dispatch(UiBuilderEditorEvent.ApplyTunables) },
          modifier = Modifier.semantics { contentDescription = "Apply tuned values" },
        ) {
          Text("Apply")
        }
      }
    }
  }
}

@Composable
private fun TunableSlider(
  document: UiBuilderDocument,
  tunable: DesignTunable,
  value: Double,
  onEdit: () -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  Column(Modifier.padding(vertical = 4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        tunable.name,
        Modifier.weight(1f),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        formatTunableValue(value),
        style = MaterialTheme.typography.labelLarge,
        color =
          if (value != tunable.default) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurface,
      )
      Box {
        TextButton(
          onClick = { menuOpen = true },
          modifier = Modifier.semantics { contentDescription = "Tunable ${tunable.name} options" },
        ) {
          Text("⋯")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          DropdownMenuItem(
            text = { Text("Edit range and default") },
            onClick = {
              menuOpen = false
              onEdit()
            },
          )
          DropdownMenuItem(
            text = { Text("Back to default (${formatTunableValue(tunable.default)})") },
            onClick = {
              menuOpen = false
              dispatch(UiBuilderEditorEvent.SetTunedValue(tunable.name, tunable.default))
            },
          )
          // A token's targets are the token's, re-read as the design changes; unlinking one by hand
          // would only last until the next edit.
          tunable.targets
            .filter { tunable.token == null }
            .forEach { target ->
              DropdownMenuItem(
                text = { Text("Unlink ${target.label} · ${target.nodeId}") },
                onClick = {
                  menuOpen = false
                  dispatch(UiBuilderEditorEvent.UntuneTarget(tunable.name, target))
                },
              )
            }
          DropdownMenuItem(
            text = { Text("Remove tunable") },
            onClick = {
              menuOpen = false
              dispatch(UiBuilderEditorEvent.RemoveTunable(tunable.name))
            },
          )
        }
      }
    }
    Slider(
      value = value.toFloat(),
      onValueChange = { dispatch(UiBuilderEditorEvent.SetTunedValue(tunable.name, it.toDouble())) },
      valueRange = tunable.minimum.toFloat()..tunable.maximum.toFloat(),
      steps = tunable.sliderSteps,
      modifier = Modifier.semantics { contentDescription = "Tune ${tunable.name}" },
    )
    Row {
      Text(
        formatTunableValue(tunable.minimum),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        when (val count = tunable.targets.size) {
          0 ->
            if (tunable.token != null) "token ${tunable.token} · nothing uses it yet"
            else "drives nothing — tune a property to link it"
          1 -> "drives ${tunable.targets.single().describe(document)}"
          else ->
            if (tunable.token != null) "token ${tunable.token} · $count values"
            else "drives $count values"
        },
        Modifier.weight(1f).padding(horizontal = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        formatTunableValue(tunable.maximum),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** The tunable's name, range and default as text fields; [onDone] with null cancels. */
@Composable
private fun TunableEditor(
  tunable: DesignTunable,
  taken: List<String>,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onDone: (DesignTunable?) -> Unit,
) {
  var name by remember(tunable) { mutableStateOf(tunable.name) }
  var minimum by remember(tunable) { mutableStateOf(formatTunableValue(tunable.minimum)) }
  var maximum by remember(tunable) { mutableStateOf(formatTunableValue(tunable.maximum)) }
  var default by remember(tunable) { mutableStateOf(formatTunableValue(tunable.default)) }
  val edited = tunable.edited(name, minimum, maximum, default, taken)
  Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    TuneField("Name", name, onTextInputFocusChanged) { name = it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      TuneField("Min", minimum, onTextInputFocusChanged, Modifier.weight(1f)) { minimum = it }
      TuneField("Max", maximum, onTextInputFocusChanged, Modifier.weight(1f)) { maximum = it }
      TuneField("Default", default, onTextInputFocusChanged, Modifier.weight(1f)) { default = it }
    }
    edited.exceptionOrNull()?.message?.let {
      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
      TextButton(onClick = { onDone(null) }) { Text("Cancel") }
      TextButton(enabled = edited.isSuccess, onClick = { onDone(edited.getOrNull()) }) {
        Text("Save")
      }
    }
  }
}

@Composable
private fun TuneField(
  label: String,
  value: String,
  onTextInputFocusChanged: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  onChange: (String) -> Unit,
) {
  OutlinedTextField(
    value,
    onChange,
    modifier.fillMaxWidth().onFocusChanged { onTextInputFocusChanged(it.hasFocus) },
    label = { Text(label) },
    singleLine = true,
    textStyle = MaterialTheme.typography.bodySmall,
  )
}

/** [tunable] with the typed fields, or why they do not make one. */
internal fun DesignTunable.edited(
  name: String,
  minimum: String,
  maximum: String,
  default: String,
  taken: Collection<String>,
): Result<DesignTunable> = runCatching {
  val trimmed = name.trim()
  require(trimmed.isNotEmpty()) { "Give it a name" }
  require(trimmed !in taken) { "Another tunable is called $trimmed" }
  val low = requireNotNull(minimum.trim().toDoubleOrNull()) { "Min must be a number" }
  val high = requireNotNull(maximum.trim().toDoubleOrNull()) { "Max must be a number" }
  val start = requireNotNull(default.trim().toDoubleOrNull()) { "Default must be a number" }
  require(low < high) { "Min must be below max" }
  require(start in low..high) { "Default must lie between min and max" }
  require(!integer || (low % 1.0 == 0.0 && high % 1.0 == 0.0 && start % 1.0 == 0.0)) {
    "This tunable drives whole numbers"
  }
  copy(name = trimmed, minimum = low, maximum = high, default = start)
}

/**
 * The Tune control beside one numeric field in the inspector: start a tunable from it, link it to
 * an existing one, or stop tuning it.
 */
@Composable
internal fun TuneFieldButton(
  target: TunableTarget,
  tunables: List<DesignTunable>,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  val linked = tunables.firstOrNull { target in it.targets }
  Box {
    TextButton(
      onClick = { open = true },
      modifier = Modifier.semantics { contentDescription = "Tune ${target.label}" },
    ) {
      Text(
        linked?.name?.let { "≈ $it" } ?: "Tune",
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(56.dp),
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      if (tunables.size < MAX_DESIGN_TUNABLES) {
        DropdownMenuItem(
          text = { Text("New tunable from this value") },
          onClick = {
            open = false
            dispatch(UiBuilderEditorEvent.TuneTarget(target))
          },
        )
      }
      tunables
        .filter { it !== linked && it.token == null }
        .forEach { tunable ->
          DropdownMenuItem(
            text = { Text("Drive from ${tunable.name}") },
            onClick = {
              open = false
              dispatch(UiBuilderEditorEvent.TuneTarget(target, into = tunable.name))
            },
          )
        }
      if (linked != null && linked.token == null) {
        DropdownMenuItem(
          text = { Text("Stop tuning") },
          onClick = {
            open = false
            dispatch(UiBuilderEditorEvent.UntuneTarget(linked.name, target))
          },
        )
      }
    }
  }
}

/** A target as the panel names it: the field, and the component it sits on. */
private fun TunableTarget.describe(document: UiBuilderDocument): String {
  val component = document.nodes[nodeId]?.componentId?.substringAfterLast('/') ?: nodeId
  return "$label on $component"
}
