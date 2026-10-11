package ee.schimke.composeai.uibuilder.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * WCAG 2 relative luminance of [color]'s RGB, 0 for black to 1 for white. Alpha is ignored: a
 * translucent colour has no luminance of its own until it is composited over something, which is
 * [contrastReading]'s job, not this function's.
 *
 * The linearisation threshold is the sRGB specification's 0.04045 rather than the 0.03928 WCAG 2.x
 * copied from an old draft; the two differ only for channel values that no 8-bit colour has.
 */
internal fun relativeLuminance(color: Color): Double {
  fun linear(channel: Float): Double {
    val c = channel.toDouble()
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
  }
  return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
}

/**
 * The WCAG contrast ratio between two opaque colours, from 1 (identical) to 21 (black on white).
 */
internal fun contrastRatio(a: Color, b: Color): Double {
  val la = relativeLuminance(a)
  val lb = relativeLuminance(b)
  return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** How a contrast ratio fares against WCAG 2's text criteria (1.4.3 AA, 1.4.6 AAA). */
internal enum class WcagGrade(val label: String) {
  AAA("AAA"),
  AA("AA"),
  /** Below AA: hard to read for many people, and an accessibility audit would flag it. */
  Low("Low"),
}

/**
 * The grade [ratio] earns. Large text — 18pt, or 14pt bold — and non-text content such as icons
 * need 3:1 for AA and 4.5:1 for AAA; everything else needs 4.5:1 and 7:1.
 */
internal fun wcagGrade(ratio: Double, largeText: Boolean): WcagGrade {
  val (aa, aaa) = if (largeText) 3.0 to 4.5 else 4.5 to 7.0
  return when {
    ratio >= aaa -> WcagGrade.AAA
    ratio >= aa -> WcagGrade.AA
    else -> WcagGrade.Low
  }
}

/**
 * `4.8:1`, `21:1`. Truncated, never rounded up, so a 4.47:1 pair can never read "4.5:1 Low" — the
 * number shown is never better than the one the grade was given for.
 */
internal fun formatContrastRatio(ratio: Double): String {
  val tenths = floor(ratio * 10 + 1e-9).toLong()
  return if (tenths % 10 == 0L) "${tenths / 10}:1" else "${tenths / 10}.${tenths % 10}:1"
}

/** What the inspector's contrast chip says about one foreground colour. */
internal data class ContrastReading(
  val ratio: Double,
  val grade: WcagGrade,
  val largeText: Boolean,
  /** Where the background came from, as a phrase: "its own container", "the theme surface", … */
  val backgroundSource: String,
) {
  val label: String
    get() = "${formatContrastRatio(ratio)} ${grade.label}"

  /** The tooltip and content description: the number, against what, and what it needed. */
  val explanation: String
    get() {
      val kind = if (largeText) "large text and icons" else "body text"
      val needs = if (largeText) "3:1 for AA, 4.5:1 for AAA" else "4.5:1 for AA, 7:1 for AAA"
      val verdict =
        when (grade) {
          WcagGrade.AAA -> "Passes WCAG AAA"
          WcagGrade.AA -> "Passes WCAG AA"
          WcagGrade.Low -> "Fails WCAG AA"
        }
      return "Contrast ${formatContrastRatio(ratio)} against $backgroundSource. " +
        "$verdict for $kind ($needs)."
    }
}

/**
 * The properties a node paints its own background with. One component declares at most one of them;
 * the first present wins.
 */
private val BACKGROUND_PROPERTIES = listOf("containerColor", "background", "backgroundColor")

/**
 * The container colour the canvas draws for a component that has one when the design does not set
 * it, as the renderer's own fallbacks have it: a theme role, [TRANSPARENT_CONTAINER] for a
 * component that draws nothing behind its content, or null for one whose default this inspector
 * cannot name (Wear's components take Wear's own scheme, not the one the inspector previews), where
 * no badge is better than a wrong one.
 */
private val CONTAINER_DEFAULTS: Map<String, String?> =
  mapOf(
    "layout/scaffold" to "background",
    "m3/button" to "primary",
    "m3/card" to "surfaceContainer",
    "m3/dialog" to "surfaceContainerHigh",
    "m3/horizontal-floating-toolbar" to "surfaceContainerHighest",
    "m3/surface" to TRANSPARENT_CONTAINER,
    "m3/center-aligned-top-app-bar" to TRANSPARENT_CONTAINER,
    "wear-m3/button" to null,
    "wear-m3/card" to null,
    "wear-m3/edge-button" to null,
    "wear-m3/icon-button" to null,
    "wear-m3/screen-scaffold" to null,
    "remote-m3/widget-container-small" to null,
    "remote-m3/widget-container-large" to null,
    "remote-m3/widget-container-adaptive" to null,
  )

private const val TRANSPARENT_CONTAINER = "transparent"

/** Wrappers a colour property is written in when it holds a value rather than a binding. */
private val LITERAL_COLOR_TYPES = setOf("color", "colorToken", "string")

/**
 * Whether [property] on [componentId] is a colour things are drawn *in* — text or an icon — rather
 * than a fill. `color` means both in the catalog: the text's colour on `m3/text`, the fill on a
 * divider or a `draw/rect`, so it counts only on a text or an icon.
 */
internal fun isForegroundColorProperty(componentId: String, property: String): Boolean {
  if (property == "contentColor" || property.endsWith("ContentColor")) return true
  if (property != "color") return false
  val leaf = componentId.substringAfterLast('/')
  return leaf == "icon" || leaf.endsWith("text") || leaf.startsWith("text-")
}

/**
 * The contrast [value], set as [property] on [nodeId], makes against what it is drawn over: the
 * node's own container, else the nearest ancestor that paints one, else the theme's surface. Null
 * whenever either colour cannot be known — a foreground that is not a colour, a background bound to
 * state, a container whose default is not this inspector's to guess — because a confident number
 * about the wrong pair is worse than none.
 */
internal fun contrastReading(
  document: UiBuilderDocument,
  nodeId: String,
  property: String,
  value: String,
  scheme: ColorScheme,
): ContrastReading? {
  val node = document.nodes[nodeId] ?: return null
  if (!isForegroundColorProperty(node.componentId, property)) return null
  val rawForeground = swatchColor(value, scheme)?.takeIf { it.alpha > 0f } ?: return null
  val parents = parentIndex(document)
  val (background, source) = resolveBackground(document, parents, node, scheme) ?: return null
  val foreground = rawForeground.compositeOver(background)
  val large = node.componentId.substringAfterLast('/') == "icon" || isLargeText(node)
  val ratio = contrastRatio(foreground, background)
  return ContrastReading(ratio, wcagGrade(ratio, large), large, source)
}

private fun parentIndex(document: UiBuilderDocument): Map<String, String> = buildMap {
  document.nodes.values.forEach { parent ->
    parent.slots.values.forEach { children -> children.forEach { put(it, parent.id) } }
  }
}

/**
 * The opaque colour behind [start]'s content, and a phrase for where it came from. A translucent
 * container is composited over whatever is behind it in turn.
 */
private fun resolveBackground(
  document: UiBuilderDocument,
  parents: Map<String, String>,
  start: UiBuilderNode,
  scheme: ColorScheme,
): Pair<Color, String>? {
  val visited = mutableSetOf<String>()
  var current: UiBuilderNode? = start
  while (current != null && visited.add(current.id)) {
    when (val fill = containerFill(current, scheme)) {
      ContainerFill.Unknown -> return null
      ContainerFill.None -> Unit
      is ContainerFill.Paint -> {
        val source =
          if (current.id == start.id) "its own container (${fill.name})"
          else "${current.componentId} “${current.id}” (${fill.name})"
        if (fill.color.alpha >= 1f) return fill.color to source
        val parent = parents[current.id]?.let(document.nodes::get)
        val behind =
          if (parent == null) scheme.surface
          else resolveBackground(document, parents, parent, scheme)?.first ?: return null
        return fill.color.compositeOver(behind) to source
      }
    }
    current = parents[current.id]?.let(document.nodes::get)
  }
  return scheme.surface to "the theme surface"
}

private sealed interface ContainerFill {
  /** Draws nothing behind its content; look further out. */
  data object None : ContainerFill

  /** Draws something this inspector cannot name. */
  data object Unknown : ContainerFill

  data class Paint(val color: Color, val name: String) : ContainerFill
}

private fun containerFill(node: UiBuilderNode, scheme: ColorScheme): ContainerFill {
  val written = BACKGROUND_PROPERTIES.firstNotNullOfOrNull { node.properties[it] }
  if (written != null) {
    val encoded = written as? JsonObject ?: return ContainerFill.Unknown
    val type = (encoded["type"]?.primitiveOrNull())?.contentOrNull
    val value = encoded["value"]?.primitiveOrNull()?.contentOrNull
    if (type !in LITERAL_COLOR_TYPES || value == null) return ContainerFill.Unknown
    return paint(value, scheme) ?: ContainerFill.Unknown
  }
  if (node.componentId !in CONTAINER_DEFAULTS) return ContainerFill.None
  val default = CONTAINER_DEFAULTS[node.componentId] ?: return ContainerFill.Unknown
  return paint(default, scheme) ?: ContainerFill.Unknown
}

private fun paint(value: String, scheme: ColorScheme): ContainerFill? {
  val color = swatchColor(value, scheme) ?: return null
  return if (color.alpha == 0f) ContainerFill.None else ContainerFill.Paint(color, value)
}

/**
 * WCAG's "large scale" text: 18pt (24sp) and up, or 14pt (about 18.7sp) and bold. A typography
 * token stands in for its size — the display and headline styles are all 24sp or larger.
 */
private fun isLargeText(node: UiBuilderNode): Boolean {
  fun scalar(name: String) = (node.properties[name] as? JsonObject)?.get("value")?.primitiveOrNull()
  val size = scalar("fontSizeSp")?.floatOrNull?.takeIf { it > 0f }
  val bold = scalar("fontWeight")?.contentOrNull == "bold"
  if (size != null) return size >= 24f || (bold && size >= 18.66f)
  val style = scalar("style")?.contentOrNull.orEmpty()
  return style.startsWith("display") || style.startsWith("headline")
}
