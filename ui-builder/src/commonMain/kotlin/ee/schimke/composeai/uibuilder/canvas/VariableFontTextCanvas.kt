package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontFamilies
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontRegistry
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontVariants
import ee.schimke.composeai.uibuilder.export.FontSettings
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.VariableFontText
import kotlin.math.roundToInt

/**
 * The style a variable font text's canvas stand-in draws in: [base] in the node's font, instanced
 * at the node's axes and size. Export (flexpress, JVM only) draws from outlines; the canvas sets
 * the same vendored file through the platform text stack, close but laid out as text. Bound or
 * computed axes arrive resolved, so a slider driving `wght` moves the stand-in. Until the family
 * loads, `wght` is the text weight.
 */
@Composable
internal fun variableFontTextStyle(node: UiBuilderNode, base: TextStyle): TextStyle {
  val font = VariableFontText.Font.fromWire(node.string("font")) ?: VariableFontText.Font.RobotoFlex
  val axes =
    font.axes
      .filter { it.property in node.properties }
      .map { FontSettings.Axis(it.tag, node.float(it.property, it.default)) }
  val weight = axes.firstOrNull { it.tag == "wght" }?.value
  val registry = LocalUiBuilderFontRegistry.current
  LaunchedEffect(registry, font) { registry?.request(font.family) }
  val families = LocalUiBuilderFontFamilies.current
  val variants = LocalUiBuilderFontVariants.current
  val instance =
    families[font.family]?.let { loaded ->
      if (axes.isEmpty()) loaded else variants?.variant(font.family, axes)
    }
  val sized = base.copy(fontSize = node.float("fontSizeSp").takeIf { it > 0f }?.sp ?: DEFAULT_SIZE)
  return when {
    // The axis is the weight: a synthesised bold over `wght` 800 would be two bolds.
    instance != null && weight != null ->
      sized.copy(fontFamily = instance, fontSynthesis = sized.fontSynthesis.withoutWeight())
    instance != null -> sized.copy(fontFamily = instance)
    weight != null -> sized.copy(fontWeight = FontWeight(weight.roundToInt().coerceIn(1, 1000)))
    else -> sized
  }
}

private val DEFAULT_SIZE = 32.sp
