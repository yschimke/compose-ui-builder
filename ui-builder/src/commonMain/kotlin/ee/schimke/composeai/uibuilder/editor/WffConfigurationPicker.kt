package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.WffConfiguration
import ee.schimke.composeai.uibuilder.canvas.WffConfigurationKind
import ee.schimke.composeai.uibuilder.canvas.WffConfigurationOption
import ee.schimke.composeai.uibuilder.canvas.parseArgb
import ee.schimke.composeai.uibuilder.canvas.wffConfigurations
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument

/**
 * Previews each user configuration a watch face declares: a swatch per colour theme, a switch for a
 * boolean, a menu for a list. What it picks is the editor's, not the design's: the face is drawn
 * with it, while the exported XML keeps the `defaultValue` its author wrote. Draws nothing for a
 * design with no configurations.
 */
@Composable
internal fun WffConfigurationPicker(
  document: UiBuilderDocument,
  selection: Map<String, String>,
  onSelect: (configurationId: String, optionId: String) -> Unit,
  onReset: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val configurations = remember(document) { document.wffConfigurations() }
  if (configurations.isEmpty()) return
  Row(
    modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (configuration in configurations) {
      val shown =
        selection[configuration.id]?.takeIf { configuration.option(it) != null }
          ?: configuration.defaultValue
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          configuration.displayName,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (configuration.kind) {
          WffConfigurationKind.COLOR ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              for (option in configuration.options) ThemeSwatch(
                option,
                selected = option.id == shown,
                onClick = { onSelect(configuration.id, option.id) },
              )
            }
          WffConfigurationKind.BOOLEAN ->
            Switch(
              checked = shown == "TRUE",
              onCheckedChange = { onSelect(configuration.id, if (it) "TRUE" else "FALSE") },
              modifier =
                Modifier.semantics { contentDescription = "Preview ${configuration.displayName}" },
            )
          WffConfigurationKind.LIST -> ListSelect(configuration, shown, onSelect)
        }
      }
    }
    if (selection.isNotEmpty()) TextButton(onClick = onReset) { Text("Defaults") }
  }
}

/** One colour theme: its colours as equal slices of a disc, outlined when it is the one shown. */
@Composable
private fun ThemeSwatch(option: WffConfigurationOption, selected: Boolean, onClick: () -> Unit) {
  val colors = option.colors.mapNotNull { runCatching { Color(parseArgb(it)) }.getOrNull() }
  val outline = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
  Box(
    Modifier.size(28.dp)
      .clip(CircleShape)
      .border(2.dp, outline, CircleShape)
      .padding(3.dp)
      .clip(CircleShape)
      .clickable(onClick = onClick)
      .semantics {
        contentDescription =
          "Colour theme ${option.displayName}${if (selected) ", selected" else ""}"
      },
  ) {
    Canvas(Modifier.size(22.dp)) {
      if (colors.isEmpty()) {
        drawCircle(Color.Gray)
        return@Canvas
      }
      val sweep = 360f / colors.size
      colors.forEachIndexed { index, color ->
        drawArc(
          color = color,
          startAngle = -90f + index * sweep,
          sweepAngle = sweep,
          useCenter = true,
          topLeft = Offset.Zero,
          size = Size(size.width, size.height),
        )
      }
    }
  }
}

@Composable
private fun ListSelect(
  configuration: WffConfiguration,
  shown: String,
  onSelect: (String, String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    OutlinedButton(onClick = { open = true }) {
      Text(configuration.option(shown)?.displayName ?: shown)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      for (option in configuration.options) DropdownMenuItem(
        text = { Text(option.displayName) },
        onClick = {
          open = false
          onSelect(configuration.id, option.id)
        },
      )
    }
  }
}
