package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.capability.DesignTokenKind
import kotlinx.serialization.json.contentOrNull

/**
 * The catalog's design tokens, grouped as the theme's and the components'. Each row says what the
 * design holds — the design system's default, a value, or a mix — takes a new one, resets it, and
 * for a number puts a slider on it in the Tune card.
 */
@Composable
internal fun DesignTokensSection(
  rows: List<EditorDesignTokenRow>,
  tunables: List<DesignTunable>,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  if (rows.isEmpty()) return
  LocalUiBuilderChrome.current.InspectorFormHeader(
    "Design tokens",
    "The values this design system lets a design re-skin. Unset draws the system's own.",
  )
  val (themed, components) = rows.partition { it.token.themed }
  listOf("Theme" to themed, "Components" to components).forEach { (heading, group) ->
    if (group.isEmpty()) return@forEach
    Text(
      heading,
      Modifier.padding(top = 12.dp, bottom = 4.dp),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
    )
    group.forEach { row ->
      DesignTokenRow(
        row = row,
        tuned = tunables.any { it.token == row.token.id },
        onTextInputFocusChanged = onTextInputFocusChanged,
        dispatch = dispatch,
      )
      HorizontalDivider()
    }
  }
}

@Composable
private fun DesignTokenRow(
  row: EditorDesignTokenRow,
  tuned: Boolean,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val token = row.token
  val shown =
    when (val value = row.value) {
      is DesignTokenValue.Set ->
        value.value.contentOrNull?.let {
          if (token.kind == DesignTokenKind.Number)
            it.toDoubleOrNull()?.let(::formatTunableValue) ?: it
          else it
        }
      else -> null
    }
  var draft by remember(token.id, shown) { mutableStateOf(shown.orEmpty()) }
  val parsed = draft.takeIf { it.isNotBlank() }?.let(token::parse)
  Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (token.kind == DesignTokenKind.Color) {
        TokenSwatch(shown)
        Box(Modifier.size(8.dp))
      }
      Text(token.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      Text(
        when (row.value) {
          DesignTokenValue.Unset ->
            token.default?.contentOrNull?.let { "default · $it" } ?: "system default"
          DesignTokenValue.Mixed -> "mixed"
          is DesignTokenValue.Set -> shown.orEmpty()
        },
        style = MaterialTheme.typography.labelMedium,
        color =
          if (row.value is DesignTokenValue.Set) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      when (row.targetCount) {
        0 -> "Nothing in this design uses it yet."
        1 -> "Writes 1 property."
        else -> "Writes ${row.targetCount} properties."
      } + (token.notes?.let { " $it" } ?: ""),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        draft,
        { draft = it },
        Modifier.weight(1f).onFocusChanged { onTextInputFocusChanged(it.hasFocus) },
        singleLine = true,
        isError = parsed?.isFailure == true,
        textStyle = MaterialTheme.typography.bodySmall,
        placeholder = {
          Text(
            when (token.kind) {
              DesignTokenKind.Number ->
                "${formatTunableValue(token.minimum ?: 0.0)}–${formatTunableValue(token.maximum ?: 0.0)}"
              DesignTokenKind.Color -> "#RRGGBB or a role"
            }
          )
        },
      )
    }
    parsed?.exceptionOrNull()?.message?.let {
      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
      if (token.kind == DesignTokenKind.Number && row.targetCount > 0) {
        TextButton(
          enabled = !tuned,
          onClick = { dispatch(UiBuilderEditorEvent.TuneDesignToken(token.id)) },
          modifier = Modifier.semantics { contentDescription = "Tune token ${token.label}" },
        ) {
          Text(if (tuned) "Tuning" else "Tune")
        }
      }
      TextButton(
        enabled = row.value !is DesignTokenValue.Unset,
        onClick = { dispatch(UiBuilderEditorEvent.ApplyDesignToken(token.id, null)) },
        modifier = Modifier.semantics { contentDescription = "Reset token ${token.label}" },
      ) {
        Text("Reset")
      }
      TextButton(
        enabled = parsed?.isSuccess == true && row.targetCount > 0 && draft.trim() != shown,
        onClick = { dispatch(UiBuilderEditorEvent.ApplyDesignToken(token.id, draft)) },
        modifier = Modifier.semantics { contentDescription = "Apply token ${token.label}" },
      ) {
        Text("Apply")
      }
    }
  }
}

/** A dot of the token's literal colour; a role or nothing draws an outlined, empty dot. */
@Composable
private fun TokenSwatch(value: String?) {
  val colour = value?.let(::parseHexColour)
  Box(
    Modifier.size(16.dp)
      .then(if (colour != null) Modifier.background(colour, CircleShape) else Modifier)
      .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
  )
}

private fun parseHexColour(value: String): Color? {
  val hex = value.removePrefix("#").takeIf { value.startsWith("#") } ?: return null
  val argb =
    when (hex.length) {
      6 -> hex.toLongOrNull(16)?.or(0xFF000000)
      8 -> hex.toLongOrNull(16)
      else -> null
    } ?: return null
  return Color(argb.toInt())
}
