@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontFamilies
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontRegistry
import ee.schimke.composeai.uibuilder.TypefaceAxis
import ee.schimke.composeai.uibuilder.TypefaceInfo
import ee.schimke.composeai.uibuilder.export.FontSettings

/**
 * What the face a text is set in offers its [FontSettings] property, above the property's own text
 * field: one slider per variation axis over the axis's real range, or a chip per layout feature.
 *
 * The face is read from its file ([TypefaceInfo]), so the list is the face's and not a guess:
 * Roboto Flex shows its thirteen axes, Lobster Two shows none and says it is static, and a text in
 * the platform's own face says that face cannot be inspected. A setting the face does not have is
 * listed as ignored rather than hidden, so nothing the design carries is invisible here.
 */
@Composable
internal fun FontSettingsEditor(
  property: String,
  value: String,
  typeface: FontSettings.TextTypeface?,
  commit: (String) -> Unit,
) {
  val family = typeface?.family
  val registry = LocalUiBuilderFontRegistry.current
  LaunchedEffect(registry, family) { family?.let { registry?.request(it) } }
  val info = family?.let { registry?.typefaces?.get(it) }
  val face = family?.let { LocalUiBuilderFontFamilies.current[it] }
  Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
    Text(
      when {
        family == null -> "Set in the platform's own face, which cannot be inspected"
        info == null -> "Reading $family…"
        property == FontSettings.VARIATION_PROPERTY && !info.isVariable ->
          "$family is a static face: it has no variation axes"
        property == FontSettings.VARIATION_PROPERTY -> "$family · ${info.axes.size} axes"
        else -> "$family · ${info.features.size} features"
      },
      style = MaterialTheme.typography.labelMedium,
      fontFamily = face,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (family == null) {
      Text(
        "Choose a typeface for this text's role on the theme host to see its axes and features. " +
          "Features still apply here; axes need a face the editor has loaded.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    when (property) {
      FontSettings.VARIATION_PROPERTY -> AxisSliders(info, family, value, commit)
      FontSettings.FEATURE_PROPERTY -> FeatureChips(info, family, value, commit)
    }
  }
}

@Composable
private fun AxisSliders(
  info: TypefaceInfo?,
  family: String?,
  value: String,
  commit: (String) -> Unit,
) {
  val set = FontSettings.parseVariations(value)
  val axes = info?.axes.orEmpty()
  fun write(next: List<FontSettings.Axis>) = commit(FontSettings.formatVariations(next))
  @Composable
  fun slider(axis: TypefaceAxis) {
    val current = set.firstOrNull { it.tag == axis.tag }
    AxisSlider(
      axis = axis,
      current = current?.value,
      onCommit = { coordinate ->
        write(
          if (current == null) set + FontSettings.Axis(axis.tag, coordinate)
          else set.map { if (it.tag == axis.tag) it.copy(value = coordinate) else it }
        )
      },
      onReset = { write(set.filterNot { it.tag == axis.tag }) },
    )
  }
  axes.filterNot { it.hidden }.forEach { slider(it) }
  // The font flags these as its own machinery — Roboto Flex's parametric axes — so they come after
  // the registered ones, but they are real axes with real ranges and a design may set them.
  val hidden = axes.filter { it.hidden }
  if (hidden.isNotEmpty()) {
    Text(
      "Parametric axes (${hidden.size}) — the font marks these as advanced",
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.padding(top = 8.dp),
    )
    hidden.forEach { slider(it) }
  }
  set
    .filter { setting -> info != null && axes.none { it.tag == setting.tag } }
    .forEach { Note("'${it.tag}' is not an axis of $family — ignored") }
  set
    .mapNotNull { setting ->
      axes
        .firstOrNull { it.tag == setting.tag }
        ?.takeIf { setting.value !in it.min..it.max }
        ?.let { setting to it }
    }
    .forEach { (setting, axis) ->
      Note(
        "'${setting.tag}' ${FontSettings.number(setting.value)} is outside " +
          "${FontSettings.number(axis.min)} to ${FontSettings.number(axis.max)}; the face clamps it"
      )
    }
}

@Composable
private fun AxisSlider(
  axis: TypefaceAxis,
  current: Float?,
  onCommit: (Float) -> Unit,
  onReset: () -> Unit,
) {
  val shown = current ?: axis.default
  var dragging by remember(axis.tag, shown) { mutableStateOf<Float?>(null) }
  val label = FontSettings.AXIS_NAMES[axis.tag] ?: axis.name
  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        "$label · ${axis.tag}  ${FontSettings.number(dragging ?: shown)}" +
          (if (current == null) " (default)" else ""),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(1f),
      )
      if (current != null) TextButton(onClick = onReset) { Text("Reset") }
    }
    Slider(
      value = (dragging ?: shown).coerceIn(axis.min, axis.max),
      onValueChange = { dragging = it },
      onValueChangeFinished = { dragging?.let { onCommit(FontSettings.number(it).toFloat()) } },
      valueRange = axis.min..axis.max,
      modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${axis.tag} axis" },
    )
    Text(
      "${FontSettings.number(axis.min)} to ${FontSettings.number(axis.max)}, default " +
        FontSettings.number(axis.default),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun FeatureChips(
  info: TypefaceInfo?,
  family: String?,
  value: String,
  commit: (String) -> Unit,
) {
  val set = FontSettings.parseFeatures(value)
  val features = info?.features.orEmpty()
  val optional = features.filterNot { it in FontSettings.REQUIRED_FEATURES }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    optional.forEach { tag ->
      val setting = set.firstOrNull { it.tag == tag }
      FilterChip(
        selected = setting?.enabled == true,
        onClick = {
          // Unset → on → off → unset: off is worth a state of its own, because `liga` and
          // `kern` are on by default and turning one off is the reason to name it.
          val next =
            when {
              setting == null -> set + FontSettings.Feature(tag, 1)
              setting.enabled -> set.map { if (it.tag == tag) it.copy(value = 0) else it }
              else -> set.filterNot { it.tag == tag }
            }
          commit(FontSettings.formatFeatures(next))
        },
        label = {
          Text(
            tag + (if (setting?.enabled == false) " off" else ""),
            style = MaterialTheme.typography.labelMedium,
          )
        },
        modifier = Modifier.semantics { contentDescription = FontSettings.featureName(tag) },
      )
    }
  }
  val required = features.filter { it in FontSettings.REQUIRED_FEATURES }
  if (required.isNotEmpty()) Note("Always applied: ${required.joinToString(", ")}")
  set
    .filter { setting -> info != null && setting.tag !in features }
    .forEach { Note("'${it.tag}' is not a feature of $family — ignored") }
}

@Composable
private fun Note(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontFamily = FontFamily.Default,
  )
}
