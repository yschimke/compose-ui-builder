package ee.schimke.composeai.uibuilder.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderModifierPlan
import ee.schimke.composeai.uibuilder.renderer.sdk.uiBuilderModifier
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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

/**
 * What a colour is judged as. WCAG holds text to 1.4.3 (AA) and 1.4.6 (AAA), with a lower bar for
 * large text; an icon is non-text content, held to 1.4.11's single 3:1 bar, which has no AAA.
 */
internal enum class ContrastKind {
  BodyText,
  LargeText,
  NonText,
}

/** How a contrast ratio fares against the WCAG criterion its [ContrastKind] is held to. */
internal enum class WcagGrade(val label: String) {
  AAA("AAA"),
  AA("AA"),
  /** Below AA: hard to read for many people, and an accessibility audit would flag it. */
  Low("Low"),
}

/**
 * The grade [ratio] earns as text. Large text — 18pt, or 14pt bold — needs 3:1 for AA and 4.5:1 for
 * AAA; everything else needs 4.5:1 and 7:1.
 */
internal fun wcagGrade(ratio: Double, largeText: Boolean): WcagGrade {
  val (aa, aaa) = if (largeText) 3.0 to 4.5 else 4.5 to 7.0
  return when {
    ratio >= aaa -> WcagGrade.AAA
    ratio >= aa -> WcagGrade.AA
    else -> WcagGrade.Low
  }
}

/** The grade [ratio] earns as [kind]: non-text content only ever passes (AA) or fails at 3:1. */
internal fun wcagGrade(ratio: Double, kind: ContrastKind): WcagGrade =
  when (kind) {
    ContrastKind.BodyText -> wcagGrade(ratio, largeText = false)
    ContrastKind.LargeText -> wcagGrade(ratio, largeText = true)
    ContrastKind.NonText -> if (ratio >= 3.0) WcagGrade.AA else WcagGrade.Low
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
  val kind: ContrastKind,
  /** Where the background came from, as a phrase: "its own container", "the theme surface", … */
  val backgroundSource: String,
) {
  val label: String
    get() = "${formatContrastRatio(ratio)} ${grade.label}"

  /** The tooltip and content description: the number, against what, and what it needed. */
  val explanation: String
    get() {
      val (what, needs) =
        when (kind) {
          ContrastKind.BodyText -> "body text" to "4.5:1 for AA, 7:1 for AAA"
          ContrastKind.LargeText -> "large text" to "3:1 for AA, 4.5:1 for AAA"
          ContrastKind.NonText -> "icons and other non-text content" to "3:1"
        }
      val verdict =
        when (grade) {
          WcagGrade.AAA -> "Passes WCAG AAA"
          WcagGrade.AA -> "Passes WCAG AA"
          WcagGrade.Low -> "Fails WCAG AA"
        }
      return "Contrast ${formatContrastRatio(ratio)} against $backgroundSource. " +
        "$verdict for $what (needs $needs)."
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
 * cannot name, where no badge is better than a wrong one. `m3/button` is not here: its container
 * follows its style; see [buttonFill]. Only the packaged floor is listed — every other catalog is
 * declined wholesale by [isFloorComponent].
 */
private val CONTAINER_DEFAULTS: Map<String, String?> =
  mapOf(
    "layout/scaffold" to "background",
    "m3/card" to "surfaceContainer",
    "m3/dialog" to "surfaceContainerHigh",
    "m3/horizontal-floating-toolbar" to "surfaceContainerHighest",
    "m3/surface" to TRANSPARENT_CONTAINER,
    "m3/center-aligned-top-app-bar" to TRANSPARENT_CONTAINER,
  )

/**
 * Whether [componentId] is one of the packaged floor's (the `m3/` and `layout/` namespaces), whose
 * drawing the inspector's preview scheme describes.
 *
 * A component from any other catalog — a Wear screen, a widget host, a third party's card — paints
 * with that catalog's own scheme and defaults, which this inspector cannot see. It is declined
 * rather than named: listing those catalogs' ids here would be the builder learning catalog facts
 * the catalogs should publish (`.github/scripts/catalog-id-ratchet.sh`), and a component it does
 * not know is exactly the case where no badge beats a wrong one.
 */
private fun isFloorComponent(componentId: String) =
  componentId.startsWith("m3/") || componentId.startsWith("layout/")

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
 * node's own painting — its container, then its `background` modifiers — else the nearest
 * ancestor's, else the theme's surface. Null whenever either colour cannot be known — a foreground
 * that is not a colour, a background bound to state, a container whose default is not this
 * inspector's to guess, an `alpha` modifier fading the whole subtree — because a confident number
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
  val kind =
    when {
      node.componentId.substringAfterLast('/') == "icon" -> ContrastKind.NonText
      isLargeText(node) -> ContrastKind.LargeText
      else -> ContrastKind.BodyText
    }
  val ratio = contrastRatio(foreground, background)
  return ContrastReading(ratio, wcagGrade(ratio, kind), kind, source)
}

private fun parentIndex(document: UiBuilderDocument): Map<String, String> = buildMap {
  document.nodes.values.forEach { parent ->
    parent.slots.values.forEach { children -> children.forEach { put(it, parent.id) } }
  }
}

/**
 * The opaque colour behind [start]'s content, and a phrase for where it came from.
 *
 * Each node paints, bottom to top, its `background` modifiers in chain order and then its own
 * container. Walking outwards from [start], the topmost layer that is opaque ends the search; a
 * translucent one is composited over whatever the walk finds beneath it.
 */
private fun resolveBackground(
  document: UiBuilderDocument,
  parents: Map<String, String>,
  start: UiBuilderNode,
  scheme: ColorScheme,
): Pair<Color, String>? {
  // Translucent layers found so far, topmost first, and the phrase for the topmost of them.
  val pending = mutableListOf<Color>()
  var source: String? = null
  fun finish(base: Color, baseSource: String): Pair<Color, String> =
    pending.foldRight(base) { layer, behind -> layer.compositeOver(behind) } to
      (source ?: baseSource)
  val visited = mutableSetOf<String>()
  var current: UiBuilderNode? = start
  while (current != null && visited.add(current.id)) {
    val layers = paintedLayers(current, scheme) ?: return null
    for (layer in layers) {
      val phrase =
        if (current.id == start.id) "its own ${layer.what} (${layer.name})"
        else "${current.componentId} “${current.id}” (${layer.name})"
      if (layer.color.alpha >= 1f) return finish(layer.color, phrase)
      pending += layer.color
      if (source == null) source = phrase
    }
    current = parents[current.id]?.let(document.nodes::get)
  }
  return finish(scheme.surface, "the theme surface")
}

/** One colour a node paints behind its content. */
private class Layer(val color: Color, val name: String, val what: String)

/**
 * What [node] paints behind its content, topmost first; empty when it paints nothing, and null when
 * it paints something this inspector cannot name.
 */
private fun paintedLayers(node: UiBuilderNode, scheme: ColorScheme): List<Layer>? {
  // A `background` slot is brushes drawn by child nodes: not a colour to read.
  if (node.slots["background"].orEmpty().isNotEmpty()) return null
  val modifierLayers = mutableListOf<Layer>()
  for (element in node.modifiers) {
    val value = element as? JsonObject ?: return null
    when (val plan = uiBuilderModifier(value)) {
      is UiBuilderModifierPlan.Background -> {
        val color = swatchColor(plan.color, scheme) ?: return null
        if (color.alpha > 0f) modifierLayers += Layer(color, plan.color, "background modifier")
      }
      // Fades the background and the text together, over whatever is behind: the pair on screen
      // is not the pair written down.
      is UiBuilderModifierPlan.Alpha -> if (plan.alpha < 1f) return null
      null ->
        // A modifier the canvas does not understand may still be a `background`; it draws
        // nothing, but a typo'd one is no reason to pass judgement on the rest.
        if ((value["type"]?.primitiveOrNull())?.contentOrNull == "background") return null
      else -> Unit
    }
  }
  val container =
    when (val fill = containerFill(node, scheme)) {
      ContainerFill.Unknown -> return null
      ContainerFill.None -> emptyList()
      is ContainerFill.Paint -> listOf(Layer(fill.color, fill.name, "container"))
    }
  // The container is drawn inside the modifier chain, so on top of every modifier background.
  return container + modifierLayers.asReversed()
}

private sealed interface ContainerFill {
  /** Draws nothing behind its content; look further out. */
  data object None : ContainerFill

  /** Draws something this inspector cannot name. */
  data object Unknown : ContainerFill

  data class Paint(val color: Color, val name: String) : ContainerFill
}

private fun UiBuilderNode.scalar(name: String) =
  (properties[name] as? JsonObject)?.get("value")?.primitiveOrNull()

private fun containerFill(node: UiBuilderNode, scheme: ColorScheme): ContainerFill {
  if (!isFloorComponent(node.componentId)) return ContainerFill.Unknown
  if (node.componentId == "m3/button") return buttonFill(node, scheme)
  val written = writtenContainer(node, scheme)
  if (written != null) return surfaceTint(node, written, scheme)
  if (node.componentId !in CONTAINER_DEFAULTS) return ContainerFill.None
  val default = CONTAINER_DEFAULTS[node.componentId] ?: return ContainerFill.Unknown
  return paint(default, scheme) ?: ContainerFill.Unknown
}

/** The container the design writes, or null when it writes none. */
private fun writtenContainer(node: UiBuilderNode, scheme: ColorScheme): ContainerFill? {
  val written = BACKGROUND_PROPERTIES.firstNotNullOfOrNull { node.properties[it] } ?: return null
  val encoded = written as? JsonObject ?: return ContainerFill.Unknown
  val type = (encoded["type"]?.primitiveOrNull())?.contentOrNull
  val value = encoded["value"]?.primitiveOrNull()?.contentOrNull
  if (type !in LITERAL_COLOR_TYPES || value == null) return ContainerFill.Unknown
  return paint(value, scheme) ?: ContainerFill.Unknown
}

/**
 * A Material `Surface` given a tonal elevation tints a `surface` container towards the primary, by
 * an amount this inspector does not compute.
 */
private fun surfaceTint(node: UiBuilderNode, fill: ContainerFill, scheme: ColorScheme) =
  if (
    node.componentId == "m3/surface" &&
      (node.scalar("tonalElevationDp")?.floatOrNull ?: 0f) > 0f &&
      fill is ContainerFill.Paint &&
      fill.color == scheme.surface
  )
    ContainerFill.Unknown
  else fill

/**
 * `m3/button`'s container, which follows its style as the canvas's `BuilderButton` draws it: a
 * `text` button has none whatever `containerColor` says, `filled` and `fab` draw `containerColor`
 * or `primary`, and a tonal button draws Material's own tonal container, which the inspector's
 * preview scheme does not carry. A disabled button draws Material's disabled colours.
 */
private fun buttonFill(node: UiBuilderNode, scheme: ColorScheme): ContainerFill {
  if (node.scalar("enabled")?.booleanOrNull == false) return ContainerFill.Unknown
  if ("enabled" in node.properties && node.scalar("enabled")?.booleanOrNull == null) {
    return ContainerFill.Unknown
  }
  return when (node.scalar("style")?.contentOrNull.orEmpty()) {
    "text" -> ContainerFill.None
    "",
    "filled",
    "fab" -> writtenContainer(node, scheme) ?: paint("primary", scheme) ?: ContainerFill.Unknown
    else -> ContainerFill.Unknown
  }
}

private fun paint(value: String, scheme: ColorScheme): ContainerFill? {
  val color = swatchColor(value, scheme) ?: return null
  return if (color.alpha == 0f) ContainerFill.None else ContainerFill.Paint(color, value)
}

/** Material 3's baseline type scale, which the canvas's `m3/text` styles are drawn in. */
private val BASELINE_TYPOGRAPHY = Typography()

/** The baseline size and weight a Material 3 style token names; null for anything else. */
private fun typographyToken(token: String): Pair<Float, Int>? {
  val style =
    with(BASELINE_TYPOGRAPHY) {
      when (token) {
        "displayLarge" -> displayLarge
        "displayMedium" -> displayMedium
        "displaySmall" -> displaySmall
        "headlineLarge" -> headlineLarge
        "headlineMedium" -> headlineMedium
        "headlineSmall" -> headlineSmall
        "titleLarge" -> titleLarge
        "titleMedium" -> titleMedium
        "titleSmall" -> titleSmall
        "bodyLarge" -> bodyLarge
        "bodyMedium" -> bodyMedium
        "bodySmall" -> bodySmall
        "labelLarge" -> labelLarge
        "labelMedium" -> labelMedium
        "labelSmall" -> labelSmall
        else -> return null
      }
    }
  return style.fontSize.value to (style.fontWeight?.weight ?: 400)
}

/**
 * WCAG's "large scale" text: 18pt (24sp) and up, or 14pt (about 18.66sp) and bold. The size is the
 * written `fontSizeSp`, else the style token's; the weight is the written `fontWeight`, else the
 * token's. Text whose size cannot be told is judged as body text — the stricter bar, so the chip
 * can be too cautious but never too kind.
 */
private fun isLargeText(node: UiBuilderNode): Boolean {
  val token = typographyToken(node.scalar("style")?.contentOrNull.orEmpty())
  val size =
    node.scalar("fontSizeSp")?.floatOrNull?.takeIf { it > 0f } ?: token?.first ?: return false
  val bold =
    when (node.scalar("fontWeight")?.contentOrNull) {
      "bold" -> true
      null,
      "" -> (token?.second ?: 400) >= 700
      else -> false
    }
  return size >= 24f || (bold && size >= 18.66f)
}
