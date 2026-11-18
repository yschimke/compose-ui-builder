package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.WffComplicationPick
import ee.schimke.composeai.uibuilder.canvas.WffComplicationSample
import ee.schimke.composeai.uibuilder.canvas.string
import ee.schimke.composeai.uibuilder.canvas.wffSlotSample
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.math.roundToLong

/**
 * The selected complication slot's preview data: which provider fills it, as which of the slot's
 * types, and for a ranged or goal type the value. Editor state only: what the canvas and the
 * preview draw the slot with, never written to the document or the exported XML.
 */
@Composable
internal fun WffComplicationPicker(
  slot: UiBuilderNode,
  picks: Map<String, WffComplicationPick>,
  onPick: (WffComplicationPick?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val supported = slot.string("supportedTypes").split(' ').filter { it.isNotBlank() }
  val pick = picks[slot.id] ?: WffComplicationPick()
  val sample =
    wffSlotSample(
      slot.id,
      slot.string("defaultSystemProvider"),
      slot.string("defaultSystemProviderType"),
      supported,
      picks,
    )
  val provider = pick.provider ?: slot.string("defaultSystemProvider")
  Surface(
    modifier.width(260.dp),
    shape = MaterialTheme.shapes.medium,
    tonalElevation = 2.dp,
    shadowElevation = 4.dp,
  ) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "Complication preview",
          Modifier.weight(1f),
          style = MaterialTheme.typography.titleSmall,
        )
        if (picks.containsKey(slot.id))
          TextButton(
            onClick = { onPick(null) },
            modifier = Modifier.semantics { contentDescription = "Reset complication preview" },
          ) {
            Text("Reset")
          }
      }
      Text(
        "Sample data on the canvas only; not exported.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      PickerMenu(
        label = "Provider",
        current =
          WffComplicationSample.PROVIDERS.firstOrNull { it.first == provider }?.second
            ?: provider.ifEmpty { "Sample" },
        options = WffComplicationSample.PROVIDERS,
        onChoose = { onPick(pick.copy(provider = it, value = null)) },
      )
      PickerMenu(
        label = "Type",
        current = typeLabel(sample.type),
        options =
          (supported + WffComplicationSample.NO_DATA + WffComplicationSample.EMPTY).distinct().map {
            it to typeLabel(it)
          },
        onChoose = { onPick(pick.copy(type = it, value = null)) },
      )
      val range =
        when {
          sample.rangedValue != null -> sample.rangedMin to sample.rangedMax
          sample.goalValue != null -> 0.0 to (sample.goalTarget ?: 100.0) * 1.2
          else -> null
        }
      val value = sample.rangedValue ?: sample.goalValue
      if (range != null && value != null && range.second > range.first) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            if (sample.rangedValue != null) "Value" else "Progress",
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
          )
          Text(value.roundToLong().toString(), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
          value = value.toFloat(),
          onValueChange = { onPick(pick.copy(type = sample.type, value = it.toDouble())) },
          valueRange = range.first.toFloat()..range.second.toFloat(),
          modifier = Modifier.semantics { contentDescription = "Complication value" },
        )
      }
    }
  }
}

@Composable
private fun PickerMenu(
  label: String,
  current: String,
  options: List<Pair<String, String>>,
  onChoose: (String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
    Box {
      OutlinedButton(
        onClick = { open = true },
        modifier = Modifier.semantics { contentDescription = "Complication $label: $current" },
      ) {
        Text(current, maxLines = 1)
      }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        options.forEach { (value, text) ->
          DropdownMenuItem(
            text = { Text(text) },
            onClick = {
              open = false
              onChoose(value)
            },
          )
        }
      }
    }
  }
}

/** `RANGED_VALUE` → "Ranged value". */
private fun typeLabel(type: String): String =
  type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
