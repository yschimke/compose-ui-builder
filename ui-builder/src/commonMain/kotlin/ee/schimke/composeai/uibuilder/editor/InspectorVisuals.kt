package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design's colours as the property inspector previews them: the same four roles the theme tab
 * edits laid over the Material baseline, light or dark after the background — the recipe the canvas
 * uses — so a swatch shows what the design will draw rather than the editor's own chrome.
 */
internal val LocalInspectorColorScheme = staticCompositionLocalOf<ColorScheme?> { null }

internal fun inspectorColorScheme(theme: EditorThemeSettings): ColorScheme {
  val primary = parseHexColor(theme.primaryColor)
  val background = parseHexColor(theme.backgroundColor)
  val surface = parseHexColor(theme.surfaceColor)
  val content = parseHexColor(theme.contentColor)
  val base =
    if (background != null && background.luminance() > 0.5f) lightColorScheme()
    else darkColorScheme()
  return base.copy(
    primary = primary ?: base.primary,
    background = background ?: base.background,
    onBackground = content ?: base.onBackground,
    surface = surface ?: base.surface,
    surfaceContainer = surface ?: base.surfaceContainer,
    surfaceContainerLow = surface ?: base.surfaceContainerLow,
    surfaceContainerHigh = surface ?: base.surfaceContainerHigh,
    surfaceContainerHighest = surface ?: base.surfaceContainerHighest,
    onSurface = content ?: base.onSurface,
    onSurfaceVariant = content ?: base.onSurfaceVariant,
  )
}

/** `#RRGGBB` or `#AARRGGBB`, as the property field accepts them; null for anything else. */
internal fun parseHexColor(value: String): Color? {
  val hex = value.trim().removePrefix("#").takeIf { value.trim().startsWith("#") } ?: return null
  val argb =
    when (hex.length) {
      6 -> hex.toLongOrNull(16)?.let { it or 0xFF000000 }
      8 -> hex.toLongOrNull(16)
      else -> null
    } ?: return null
  return Color(argb.toInt())
}

/** The colour a property value names — a literal or a theme role — in [scheme]; null if neither. */
internal fun swatchColor(value: String, scheme: ColorScheme): Color? =
  parseHexColor(value)
    ?: when (value) {
      "background" -> scheme.background
      "surface" -> scheme.surface
      "surfaceContainer" -> scheme.surfaceContainer
      "surfaceContainerLow" -> scheme.surfaceContainerLow
      "surfaceContainerHigh" -> scheme.surfaceContainerHigh
      "surfaceContainerHighest" -> scheme.surfaceContainerHighest
      "primary" -> scheme.primary
      "onPrimary" -> scheme.onPrimary
      "secondary" -> scheme.secondary
      "onSecondary" -> scheme.onSecondary
      "tertiary" -> scheme.tertiary
      "onTertiary" -> scheme.onTertiary
      "onSurface" -> scheme.onSurface
      "onSurfaceVariant" -> scheme.onSurfaceVariant
      "outlineVariant" -> scheme.outlineVariant
      "transparent" -> Color.Transparent
      else -> null
    }

/**
 * Literal colours offered beside the theme roles: black and white, greys, and one of each hue — a
 * start rather than a palette, since the hex field takes any value.
 */
internal val INSPECTOR_PRESET_COLORS =
  listOf(
    "#FF000000",
    "#FF5F6368",
    "#FFBDC1C6",
    "#FFFFFFFF",
    "#FFD93025",
    "#FFF29900",
    "#FFFDD663",
    "#FF1E8E3E",
    "#FF12B5CB",
    "#FF1A73E8",
    "#FF9334E6",
    "#FFE52592",
  )

/**
 * One colour as a round swatch with an outline, so white on a light panel and transparent both read
 * as a colour. Transparent draws a diagonal slash through an empty well.
 */
@Composable
internal fun ColorSwatch(
  color: Color?,
  size: Dp,
  selected: Boolean,
  modifier: Modifier = Modifier,
) {
  val outline = MaterialTheme.colorScheme.outline
  val ring = if (selected) MaterialTheme.colorScheme.primary else outline.copy(alpha = 0.6f)
  Canvas(
    modifier
      .size(size)
      .border(if (selected) 2.dp else 1.dp, ring, CircleShape)
      .background(color?.takeIf { it.alpha > 0f } ?: Color.Transparent, CircleShape)
  ) {
    if (color == null || color.alpha == 0f) {
      drawLine(
        outline,
        Offset(this.size.width * 0.2f, this.size.height * 0.8f),
        Offset(this.size.width * 0.8f, this.size.height * 0.2f),
        strokeWidth = 1.5.dp.toPx(),
      )
    }
  }
}

/**
 * How an enum option of [property] looks when set, for the options whose meaning is a look —
 * typography, weight, italics, decoration. Null keeps the plain label.
 */
@Composable
internal fun enumOptionTextStyle(property: String, option: String): TextStyle? {
  val base = MaterialTheme.typography.bodyMedium
  return when (property) {
    "style" -> typographyToken(option)?.let { it.copy(fontSize = it.fontSize.capped(24.sp)) }
    "fontWeight" ->
      when (option) {
        "normal" -> base.copy(fontWeight = FontWeight.Normal)
        "medium" -> base.copy(fontWeight = FontWeight.Medium)
        "semiBold" -> base.copy(fontWeight = FontWeight.SemiBold)
        "bold" -> base.copy(fontWeight = FontWeight.Bold)
        else -> null
      }
    "fontStyle" -> if (option == "italic") base.copy(fontStyle = FontStyle.Italic) else base
    "textDecoration" ->
      when (option) {
        "underline" -> base.copy(textDecoration = TextDecoration.Underline)
        "lineThrough" -> base.copy(textDecoration = TextDecoration.LineThrough)
        else -> base
      }
    else -> null
  }
}

@Composable
private fun typographyToken(name: String): TextStyle? =
  MaterialTheme.typography.let { t ->
    when (name) {
      "displayLarge" -> t.displayLarge
      "displayMedium" -> t.displayMedium
      "displaySmall" -> t.displaySmall
      "headlineLarge" -> t.headlineLarge
      "headlineMedium" -> t.headlineMedium
      "headlineSmall" -> t.headlineSmall
      "titleLarge" -> t.titleLarge
      "titleMedium" -> t.titleMedium
      "titleSmall" -> t.titleSmall
      "bodyLarge" -> t.bodyLarge
      "bodyMedium" -> t.bodyMedium
      "bodySmall" -> t.bodySmall
      "labelLarge" -> t.labelLarge
      "labelMedium" -> t.labelMedium
      "labelSmall" -> t.labelSmall
      else -> null
    }
  }

private fun TextUnit.capped(max: TextUnit): TextUnit =
  if (isSp && max.isSp && value > max.value) max else this

/** Whether [EnumOptionGlyph] draws something for this property. */
internal fun hasEnumGlyph(property: String): Boolean = property in GLYPH_PROPERTIES

private val GLYPH_PROPERTIES =
  setOf(
    "textAlign",
    "horizontalAlignment",
    "verticalAlignment",
    "alignment",
    "horizontalArrangement",
    "verticalArrangement",
    "theme",
  )

/**
 * A small picture of what an option does: lines aligned the way text would be, a dot where content
 * sits in its box, blocks spaced as an arrangement spaces children, a light or dark page for a
 * theme. Drawn rather than taken from an icon set so every option of every property has one.
 */
@Composable
internal fun EnumOptionGlyph(property: String, option: String, modifier: Modifier = Modifier) {
  val ink = MaterialTheme.colorScheme.onSurface
  val faint = MaterialTheme.colorScheme.outlineVariant
  Canvas(modifier.size(20.dp)) {
    when (property) {
      "textAlign",
      "horizontalAlignment" -> alignedLines(option, ink)
      "verticalAlignment" -> verticalBlock(option, ink, faint)
      "alignment" -> alignmentGrid(option, ink, faint)
      "horizontalArrangement" -> arrangedBlocks(option, ink, horizontal = true)
      "verticalArrangement" -> arrangedBlocks(option, ink, horizontal = false)
      "theme" -> themePage(option, ink)
    }
  }
}

private fun DrawScope.alignedLines(option: String, ink: Color) {
  val stroke = size.height / 10f
  val widths = listOf(1f, 0.6f, 0.85f, 0.5f)
  widths.forEachIndexed { index, fraction ->
    val width =
      if (option == "justify" && index < widths.lastIndex) size.width else size.width * fraction
    val left =
      when (option) {
        "center" -> (size.width - width) / 2f
        "end" -> size.width - width
        else -> 0f
      }
    val y = size.height * (0.2f + index * 0.2f)
    drawLine(ink, Offset(left, y), Offset(left + width, y), strokeWidth = stroke)
  }
}

private fun DrawScope.verticalBlock(option: String, ink: Color, faint: Color) {
  drawRect(faint, size = size, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
  val block = Size(size.width * 0.5f, size.height * 0.3f)
  val top =
    when (option) {
      "top" -> size.height * 0.1f
      "bottom" -> size.height * 0.9f - block.height
      else -> (size.height - block.height) / 2f
    }
  drawRect(ink, Offset((size.width - block.width) / 2f, top), block)
}

private fun DrawScope.alignmentGrid(option: String, ink: Color, faint: Color) {
  val rows = listOf("top", "center", "bottom")
  val columns = listOf("Start", "Center", "End")
  val wanted =
    if (option == "center") 1 to 1
    else {
      val row = rows.indexOfFirst { option.startsWith(it) }
      val column = columns.indexOfFirst { option.endsWith(it) }
      row to column
    }
  for (row in 0..2) {
    for (column in 0..2) {
      val center = Offset(size.width * (0.2f + column * 0.3f), size.height * (0.2f + row * 0.3f))
      val chosen = wanted.first == row && wanted.second == column
      drawCircle(
        if (chosen) ink else faint,
        radius = size.minDimension * (if (chosen) 0.13f else 0.07f),
        center = center,
      )
    }
  }
}

private fun DrawScope.arrangedBlocks(option: String, ink: Color, horizontal: Boolean) {
  val length = if (horizontal) size.width else size.height
  val block = length * 0.18f
  val count = 3
  val free = length - block * count
  val starts: List<Float> =
    when (option) {
      "end",
      "bottom" -> List(count) { free + it * block }
      "center" -> List(count) { free / 2f + it * block }
      "spaceBetween" -> List(count) { it * (block + free / (count - 1)) }
      "spaceAround" -> {
        val gap = free / count
        List(count) { gap / 2f + it * (block + gap) }
      }
      "spaceEvenly" -> {
        val gap = free / (count + 1)
        List(count) { gap + it * (block + gap) }
      }
      else -> List(count) { it * block }
    }
  starts.forEach { start ->
    if (horizontal) {
      drawRoundRect(
        ink,
        Offset(start, size.height * 0.3f),
        Size(block * 0.85f, size.height * 0.4f),
        CornerRadius(1.dp.toPx()),
      )
    } else {
      drawRoundRect(
        ink,
        Offset(size.width * 0.3f, start),
        Size(size.width * 0.4f, block * 0.85f),
        CornerRadius(1.dp.toPx()),
      )
    }
  }
}

private fun DrawScope.themePage(option: String, ink: Color) {
  val radius = CornerRadius(3.dp.toPx())
  when (option) {
    "light" -> drawRoundRect(Color.White, cornerRadius = radius)
    "dark" -> drawRoundRect(Color(0xFF1D1F25), cornerRadius = radius)
    else -> {
      drawRoundRect(Color.White, cornerRadius = radius)
      drawRoundRect(
        Color(0xFF1D1F25),
        topLeft = Offset(size.width / 2f, 0f),
        size = Size(size.width / 2f, size.height),
      )
    }
  }
  drawRoundRect(
    ink,
    cornerRadius = radius,
    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
  )
}
