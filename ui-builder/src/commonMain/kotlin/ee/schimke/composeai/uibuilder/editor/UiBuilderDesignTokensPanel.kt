package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.capability.DesignTokenKind
import kotlinx.coroutines.launch
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
  /** Which scheme of a Material Theme Builder file to read: a watch is dark, a phone light. */
  preferredScheme: String = "light",
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
  DesignTokenExchange(rows, preferredScheme, onTextInputFocusChanged, dispatch)
}

/**
 * Tokens out as a DTCG file and in from DTCG or a Material Theme Builder export. Text rather than a
 * file picker, because every host — browser, desktop, IDE — has a text field and a clipboard and
 * not every one has a file chooser the editor can open.
 */
@Composable
private fun DesignTokenExchange(
  rows: List<EditorDesignTokenRow>,
  preferredScheme: String,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  var text by remember { mutableStateOf("") }
  var note by remember { mutableStateOf<String?>(null) }
  var scheme by remember(preferredScheme) { mutableStateOf(preferredScheme) }
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  TextButton(
    onClick = { open = !open },
    modifier = Modifier.semantics { contentDescription = "Import or export design tokens" },
  ) {
    Text(if (open) "Hide import and export" else "Import / export tokens")
  }
  if (!open) return
  Text(
    "Export writes the tokens this design sets as W3C DTCG JSON. Import reads DTCG or a Material " +
      "Theme Builder export, and applies what matches as one undoable edit.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  val tokens = rows.map { it.token }
  val themeBuilder = isThemeBuilderExport(text)
  val preview = text.takeIf { it.isNotBlank() }?.let { importDesignTokens(it, tokens, scheme) }
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    TextButton(
      onClick = {
        val export = exportDesignTokens(rows)
        text = export.text()
        note =
          "${export.written.size} set token${if (export.written.size == 1) "" else "s"} written" +
            if (export.skipped.isEmpty()) "."
            else "; ${export.skipped.size} unset or mixed left out (DTCG has no unset value)."
      },
      modifier = Modifier.semantics { contentDescription = "Export design tokens" },
    ) {
      Text("Export DTCG")
    }
    TextButton(
      enabled = text.isNotBlank(),
      onClick = {
        scope.launch { runCatching { clipboard.setClipEntry(plainTextClipEntry(text)) } }
      },
    ) {
      Text("Copy")
    }
  }
  OutlinedTextField(
    text,
    {
      text = it
      note = null
    },
    Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 240.dp).onFocusChanged {
      onTextInputFocusChanged(it.hasFocus)
    },
    label = { Text("Token JSON") },
    placeholder = { Text("Paste DTCG or Theme Builder JSON to import") },
    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
  )
  if (themeBuilder) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      listOf("light", "dark").forEach { choice ->
        FilterChip(
          selected = scheme == choice,
          onClick = { scheme = choice },
          label = { Text(choice.replaceFirstChar { it.uppercase() } + " scheme") },
        )
      }
    }
  }
  note?.let {
    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
  }
  preview?.let { result ->
    result.fold(
      onSuccess = { read ->
        Text(
          buildString {
            append("${read.format}: ${read.values.size} token")
            append(if (read.values.size == 1) "" else "s")
            append(" to apply.")
            if (read.unknown.isNotEmpty())
              append(" Not in this design system: ${read.unknown.joinToString()}.")
            read.invalid.forEach { (id, why) -> append(" $id $why.") }
          },
          style = MaterialTheme.typography.bodySmall,
          color =
            if (read.invalid.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
        )
        TextButton(
          enabled = read.values.isNotEmpty() && note == null,
          onClick = { dispatch(UiBuilderEditorEvent.ImportDesignTokens(read.values)) },
          modifier = Modifier.semantics { contentDescription = "Import design tokens" },
        ) {
          Text("Import ${read.values.size}")
        }
      },
      onFailure = {
        Text(
          it.message.orEmpty(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      },
    )
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
