package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontFamilies
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontRegistry
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontVariants
import ee.schimke.composeai.uibuilder.export.FontSettings
import ee.schimke.composeai.uibuilder.export.UiBuilderNode

/**
 * The nearest theme host's string properties, by name: `m3/surface`, a Wear screen or a widget
 * container. A text reads it to know which typeface it is set in ([FontSettings.familyFor]).
 */
internal val LocalThemeHostProperties = staticCompositionLocalOf<(String) -> String?> { { null } }

/** The document's own typeface (`environment.typeface`), the Material 3 roles' fallback. */
internal val LocalDocumentTypeface = staticCompositionLocalOf<String?> { null }

/** A text node's font settings, resolved against the face it is drawn in. */
internal class ResolvedFontSettings(
  /** The style with the settings applied. */
  val style: TextStyle,
  /** Whether the node sets `wght`, which then decides the weight rather than `fontWeight`. */
  val setsWeight: Boolean,
)

/**
 * [style] with [node]'s [FontSettings] applied: its features as the style's `fontFeatureSettings`,
 * and its axes as an instance of the family the text is set in.
 *
 * The axes need the font file, so they apply only where the family is one the host loaded — any
 * theme typeface, the document's, and Wear's Roboto Flex. A text in the platform's own face draws
 * its features and not its axes, which is what the inspector says about it. A setting the face
 * lacks is dropped by the text stack, as CSS drops it.
 */
@Composable
internal fun resolveFontSettings(
  node: UiBuilderNode,
  style: TextStyle,
  wear: Boolean,
): ResolvedFontSettings {
  val features =
    FontSettings.formatFeatures(
        FontSettings.parseFeatures(node.textProperty(FontSettings.FEATURE_PROPERTY))
      )
      .ifEmpty { null }
  val axes = FontSettings.parseVariations(node.textProperty(FontSettings.VARIATION_PROPERTY))
  var weightAxis = false
  var resolved = if (features == null) style else style.copy(fontFeatureSettings = features)
  if (axes.isNotEmpty()) {
    val family =
      FontSettings.familyFor(
        node.textProperty("style"),
        LocalThemeHostProperties.current,
        LocalDocumentTypeface.current,
        wear,
      )
    val registry = LocalUiBuilderFontRegistry.current
    LaunchedEffect(registry, family) { family?.let { registry?.request(it) } }
    // Read through the families first: in the editor that is the registry's snapshot map, so the
    // text recomposes once the family arrives; in a production render it loads the family.
    val families = LocalUiBuilderFontFamilies.current
    val variants = LocalUiBuilderFontVariants.current
    val instance = family?.takeIf { families[it] != null }?.let { variants?.variant(it, axes) }
    if (family != null && instance != null) {
      weightAxis = axes.any { it.tag == "wght" } && variants?.hasAxis(family, "wght") == true
      resolved = resolved.copy(fontFamily = instance)
      // The axis is the weight now; a synthesised bold over a `wght` 800 would be two bolds. Style
      // synthesis stays, so an italic `fontStyle` on a face with no italic still slants.
      if (weightAxis) resolved = resolved.copy(fontSynthesis = FontSynthesis.Style)
    }
  }
  // Only a face that has a `wght` axis takes its weight from it: a static face, one not loaded, or
  // the platform's own ignores the axis, so the authored `fontWeight` still has to apply.
  return ResolvedFontSettings(resolved, setsWeight = weightAxis)
}

private fun UiBuilderNode.textProperty(name: String): String? =
  string(name).takeIf { it.isNotBlank() }
