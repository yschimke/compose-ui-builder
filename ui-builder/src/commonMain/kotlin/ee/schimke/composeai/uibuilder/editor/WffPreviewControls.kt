package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.WffComplicationPick
import ee.schimke.composeai.uibuilder.canvas.WffComplicationSample
import ee.schimke.composeai.uibuilder.canvas.WffSimulatedState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import kotlin.math.roundToInt

/** Quick complication override in the Watch Face Preview pane for checking fallback layouts. */
internal enum class WffPreviewComplicationPreset(val label: String) {
  Default("Default"),
  Empty("Empty"),
  NoData("No data"),
}

/**
 * Complication picks for the Preview pane: [WffPreviewComplicationPreset.Default] keeps the
 * editor's per-slot [basePicks], while [WffPreviewComplicationPreset.Empty] and
 * [WffPreviewComplicationPreset.NoData] force every slot in [this] to that state.
 */
internal fun UiBuilderDocument.wffComplicationPresetPicks(
  preset: WffPreviewComplicationPreset,
  basePicks: Map<String, WffComplicationPick>,
): Map<String, WffComplicationPick> {
  if (preset == WffPreviewComplicationPreset.Default) return basePicks
  val forcedType =
    when (preset) {
      WffPreviewComplicationPreset.Empty -> WffComplicationSample.EMPTY
      WffPreviewComplicationPreset.NoData -> WffComplicationSample.NO_DATA
      WffPreviewComplicationPreset.Default -> return basePicks
    }
  return nodes.values
    .filter { it.componentId == WatchFaceXmlExporter.COMPLICATION_SLOT }
    .associate { slot -> slot.id to WffComplicationPick(type = forcedType) }
}

internal const val WFF_PREVIEW_VARIANT_CONTROLS_TAG = "wff-preview-variant-controls"

/**
 * The Watch Face Format Preview pane's variant bar: picks which axis to fan out across (colour
 * styles by default, or any layout/list/boolean configuration, flavors, or Interactive & Ambient),
 * whether each pane is Interactive, Ambient, or both, a quick complication fallback preset, and
 * simulated watch state (battery, steps, heart rate, 24h, weather).
 */
@Composable
internal fun WffPreviewVariantControls(
  document: UiBuilderDocument,
  fanOut: WffPreviewFanOut,
  onFanOutChange: (WffPreviewFanOut) -> Unit,
  ambientMode: WffPreviewAmbientMode,
  onAmbientModeChange: (WffPreviewAmbientMode) -> Unit,
  complicationPreset: WffPreviewComplicationPreset,
  onComplicationPresetChange: (WffPreviewComplicationPreset) -> Unit,
  simulated: WffSimulatedState,
  onSimulatedChange: (WffSimulatedState) -> Unit,
  modifier: Modifier = Modifier,
) {
  val choices = remember(document) { document.wffPreviewFanOutChoices() }
  if (choices.isEmpty()) return
  val activeChoice = choices.firstOrNull { it.fanOut == fanOut } ?: choices.first()
  val hasComplications =
    remember(document.nodes) {
      document.nodes.values.any { it.componentId == WatchFaceXmlExporter.COMPLICATION_SLOT }
    }
  val scroll = rememberScrollState()
  Row(
    modifier
      .testTag(WFF_PREVIEW_VARIANT_CONTROLS_TAG)
      .horizontalScroll(scroll)
      .padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (choices.size > 1) {
      FanOutSelector(
        choices = choices,
        active = activeChoice,
        onSelect = onFanOutChange,
      )
    }
    if (activeChoice.fanOut != WffPreviewFanOut.Ambient) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        for (mode in WffPreviewAmbientMode.entries) {
          FilterChip(
            selected = ambientMode == mode,
            onClick = { onAmbientModeChange(mode) },
            label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
            modifier =
              Modifier.semantics { contentDescription = "Watch face preview mode: ${mode.label}" },
          )
        }
      }
    }
    if (hasComplications) {
      ComplicationPresetSelector(
        current = complicationPreset,
        onSelect = onComplicationPresetChange,
      )
    }
    WatchStateControl(simulated = simulated, onSimulatedChange = onSimulatedChange)
  }
}

@Composable
private fun FanOutSelector(
  choices: List<WffPreviewFanOutChoice>,
  active: WffPreviewFanOutChoice,
  onSelect: (WffPreviewFanOut) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "Compare",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box {
      OutlinedButton(
        onClick = { open = true },
        modifier =
          Modifier.semantics { contentDescription = "Compare watch face by: ${active.label}" },
      ) {
        Text(active.label)
      }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (choice in choices) {
          DropdownMenuItem(
            text = { Text(choice.label) },
            onClick = {
              open = false
              onSelect(choice.fanOut)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun ComplicationPresetSelector(
  current: WffPreviewComplicationPreset,
  onSelect: (WffPreviewComplicationPreset) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "Complications",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box {
      OutlinedButton(
        onClick = { open = true },
        modifier =
          Modifier.semantics {
            contentDescription = "Preview complication state: ${current.label}"
          },
      ) {
        Text(current.label)
      }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (preset in WffPreviewComplicationPreset.entries) {
          DropdownMenuItem(
            text = { Text(preset.label) },
            onClick = {
              open = false
              onSelect(preset)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun WatchStateControl(
  simulated: WffSimulatedState,
  onSimulatedChange: (WffSimulatedState) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  val modified = simulated != WffSimulatedState()
  Box {
    OutlinedButton(
      onClick = { open = true },
      modifier = Modifier.semantics { contentDescription = "Watch state simulation" },
    ) {
      Text(if (modified) "Watch state *" else "Watch state")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      Column(
        Modifier.width(260.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            "Watch state",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
          )
          if (modified) {
            TextButton(onClick = { onSimulatedChange(WffSimulatedState()) }) { Text("Reset") }
          }
        }
        SimulatedSliderRow(
          label = "Battery",
          valueText = "${simulated.batteryPercent}%",
          value = simulated.batteryPercent.toFloat(),
          range = 0f..100f,
          onChange = { onSimulatedChange(simulated.copy(batteryPercent = it.roundToInt())) },
        )
        SimulatedSliderRow(
          label = "Steps",
          valueText = "${simulated.stepCount}",
          value = simulated.stepCount.toFloat(),
          range = 0f..15000f,
          onChange = {
            val rounded = ((it / 100f).roundToInt() * 100).coerceIn(0, 15000)
            onSimulatedChange(simulated.copy(stepCount = rounded))
          },
        )
        SimulatedSliderRow(
          label = "Heart rate",
          valueText = "${simulated.heartRate} bpm",
          value = simulated.heartRate.toFloat(),
          range = 40f..200f,
          onChange = { onSimulatedChange(simulated.copy(heartRate = it.roundToInt())) },
        )
        SimulatedSliderRow(
          label = "Weather",
          valueText = "${simulated.weatherTemperature}°C",
          value = simulated.weatherTemperature.toFloat(),
          range = -20f..45f,
          onChange = { onSimulatedChange(simulated.copy(weatherTemperature = it.roundToInt())) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("24-hour clock", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
          Switch(
            checked = simulated.is24Hour,
            onCheckedChange = { onSimulatedChange(simulated.copy(is24Hour = it)) },
            modifier = Modifier.semantics { contentDescription = "Simulate 24-hour clock" },
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Charging", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
          Switch(
            checked = simulated.batteryCharging,
            onCheckedChange = { onSimulatedChange(simulated.copy(batteryCharging = it)) },
            modifier = Modifier.semantics { contentDescription = "Simulate battery charging" },
          )
        }
      }
    }
  }
}

@Composable
private fun SimulatedSliderRow(
  label: String,
  valueText: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  onChange: (Float) -> Unit,
) {
  Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
      Text(
        valueText,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Slider(
      value = value.coerceIn(range.start, range.endInclusive),
      onValueChange = onChange,
      valueRange = range,
      modifier = Modifier.semantics { contentDescription = "Simulate $label" },
    )
  }
}
