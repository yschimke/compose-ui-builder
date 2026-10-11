package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** One of the two directions a node is sized in. */
enum class EditorAxis {
  Width,
  Height,
}

/**
 * How one axis of a node is sized: [Hug] its content, [Fill] the parent's offer, or [Fixed]. Finer
 * constraints (`widthIn`, aspect ratio) stay in the modifier chain and survive a resize.
 */
sealed interface EditorSizing {
  /** No size modifier on this axis: the node is as big as its content. */
  data object Hug : EditorSizing

  /** `fillMaxWidth`/`fillMaxHeight`, or a `weight` on a row's or column's main axis. */
  data object Fill : EditorSizing

  /** `width`/`height` in dp, as the node's outer box is drawn. */
  data class Fixed(val dp: Float) : EditorSizing
}

/**
 * One axis of the selected node and what it may become. [canFill]/[canFix] come from the catalog,
 * so an undeclared modifier means no handle rather than a refused drop.
 */
data class EditorAxisSizing(
  val axis: EditorAxis,
  val current: EditorSizing,
  val canFill: Boolean,
  val canFix: Boolean,
) {
  /** Whether anything on this axis can be changed at all. */
  val resizable: Boolean
    get() = canFill || canFix || current != EditorSizing.Hug
}

/** Both axes of the selected node. */
data class EditorNodeSizing(
  val nodeId: String,
  val width: EditorAxisSizing,
  val height: EditorAxisSizing,
) {
  operator fun get(axis: EditorAxis): EditorAxisSizing =
    when (axis) {
      EditorAxis.Width -> width
      EditorAxis.Height -> height
    }
}

/** The modifier a fixed size is written as, per axis. */
private fun EditorAxis.fixedType(): String =
  when (this) {
    EditorAxis.Width -> "width"
    EditorAxis.Height -> "height"
  }

private fun EditorAxis.fixedField(): String =
  when (this) {
    EditorAxis.Width -> "widthDp"
    EditorAxis.Height -> "heightDp"
  }

private fun EditorAxis.fillType(): String =
  when (this) {
    EditorAxis.Width -> "fillMaxWidth"
    EditorAxis.Height -> "fillMaxHeight"
  }

private fun EditorAxis.other(): EditorAxis =
  when (this) {
    EditorAxis.Width -> EditorAxis.Height
    EditorAxis.Height -> EditorAxis.Width
  }

/**
 * Whether this axis fills by `weight`: the main axis of a row or column, where `fillMaxWidth` would
 * starve later siblings. Collapsible layouts keep plain fill (orientation unknown here).
 */
private fun EditorAxis.fillsByWeight(scope: EditorLayoutScope?): Boolean =
  (this == EditorAxis.Width && scope == EditorLayoutScope.Row) ||
    (this == EditorAxis.Height && scope == EditorLayoutScope.Column)

private fun JsonElement.modifierType(): String? = (this as? JsonObject)?.optionalStringValue("type")

private fun JsonObject.dp(field: String): Float? =
  this[field]?.primitiveOrNull()?.doubleOrNull?.toFloat()

/**
 * How [axis] is sized by [chain], as Compose applies it: the first size-deciding modifier wins
 * (`fillMaxWidth().width(64.dp)` still fills), and `weight` beats them all.
 */
internal fun sizingOf(
  chain: List<JsonElement>,
  axis: EditorAxis,
  scope: EditorLayoutScope?,
): EditorSizing {
  // `weight(fill = false)` allots a share without forcing the child to take it, so it decides
  // nothing about the size and the chain below is read as usual.
  if (
    axis.fillsByWeight(scope) &&
      chain.any {
        it.modifierType() == "weight" &&
          (it as JsonObject)["fill"]?.primitiveOrNull()?.booleanOrNull != false
      }
  ) {
    return EditorSizing.Fill
  }
  chain.forEach { element ->
    val modifier = element as? JsonObject ?: return@forEach
    when (modifier.optionalStringValue("type")) {
      axis.fillType(),
      "fillMaxSize",
      "matchParentSize" -> return EditorSizing.Fill
      axis.fixedType(),
      "size" ->
        modifier.dp(axis.fixedField())?.let {
          return EditorSizing.Fixed(it)
        }
    }
  }
  return EditorSizing.Hug
}

/**
 * How much [chain]'s `scale` modifiers magnify the node per axis. Handle pixels are divided by it
 * before becoming dp, since the canvas measures after transforms.
 */
internal fun drawnScale(chain: List<JsonElement>, axis: EditorAxis): Float =
  chain.fold(1f) { product, element ->
    val modifier = element as? JsonObject
    if (modifier?.optionalStringValue("type") != "scale") product
    else {
      val factor =
        modifier.dp(if (axis == EditorAxis.Width) "scaleX" else "scaleY")?.takeIf { it > 0f } ?: 1f
      product * factor
    }
  }

/**
 * The padding [chain] puts between a node's box and content, as physical (left, top, right, bottom)
 * drawn dp: every `padding`, magnified by any earlier `scale`; in RTL `startDp` is on the right.
 */
internal fun paddingInsets(chain: List<JsonElement>, rtl: Boolean = false): List<Float> {
  val sums = FloatArray(4)
  var scaleX = 1f
  var scaleY = 1f
  chain.forEach { element ->
    val modifier = element as? JsonObject ?: return@forEach
    when (modifier.optionalStringValue("type")) {
      "scale" -> {
        scaleX *= modifier.dp("scaleX")?.takeIf { it > 0f } ?: 1f
        scaleY *= modifier.dp("scaleY")?.takeIf { it > 0f } ?: 1f
      }
      "padding" -> {
        val start = (modifier.dp("startDp") ?: 0f) * scaleX
        val end = (modifier.dp("endDp") ?: 0f) * scaleX
        sums[0] += if (rtl) end else start
        sums[1] += (modifier.dp("topDp") ?: 0f) * scaleY
        sums[2] += if (rtl) start else end
        sums[3] += (modifier.dp("bottomDp") ?: 0f) * scaleY
      }
    }
  }
  return sums.toList()
}

/** How much ancestors' `scale` modifiers magnify [nodeId], per axis. */
internal fun UiBuilderDocument.ancestorScale(nodeId: String, axis: EditorAxis): Float {
  var product = 1f
  var current = location(nodeId)?.nodeId
  val seen = mutableSetOf<String>()
  while (current != null && seen.add(current)) {
    product *= drawnScale(nodes[current]?.modifiers.orEmpty(), axis)
    current = location(current)?.nodeId
  }
  return product
}

/** The horizontal (or vertical) padding written *before* [index] in [chain], in dp. */
private fun paddingBefore(chain: List<JsonElement>, index: Int, axis: EditorAxis): Float =
  chain
    .take(index)
    .sumOf { element ->
      val modifier = element as? JsonObject
      if (modifier?.optionalStringValue("type") != "padding") 0.0
      else
        when (axis) {
          EditorAxis.Width -> (modifier.dp("startDp") ?: 0f) + (modifier.dp("endDp") ?: 0f)
          EditorAxis.Height -> (modifier.dp("topDp") ?: 0f) + (modifier.dp("bottomDp") ?: 0f)
        }.toDouble()
    }
    .toFloat()

/**
 * [chain] with [axis] re-sized to [sizing], or null when the catalog gives no way to express it.
 *
 * Every modifier deciding this axis is removed and at most one put back where the first was (or at
 * the front), so modifier order keeps its meaning. Two-axis modifiers are split (`size` keeps its
 * other half as `height`; fill keeps the other axis). [EditorSizing.Fixed] is the drawn outer box,
 * so preceding padding is subtracted.
 */
internal fun resizedModifierChain(
  chain: List<JsonElement>,
  axis: EditorAxis,
  sizing: EditorSizing,
  declared: Set<String>,
  scope: EditorLayoutScope?,
): List<JsonElement>? {
  val other = axis.other()
  val byWeight = axis.fillsByWeight(scope) && "weight" in declared
  fun decidesAxis(type: String?): Boolean =
    type == axis.fillType() ||
      type == axis.fixedType() ||
      type == "size" ||
      type == "fillMaxSize" ||
      type == "matchParentSize" ||
      (type == "weight" && axis.fillsByWeight(scope))
  val firstIndex = chain.indexOfFirst { decidesAxis(it.modifierType()) }
  val rebuilt = mutableListOf<JsonElement>()
  var insertAt = if (firstIndex >= 0) -1 else 0
  chain.forEachIndexed { index, element ->
    val modifier = element as? JsonObject
    val type = modifier?.optionalStringValue("type")
    if (modifier == null || !decidesAxis(type)) {
      rebuilt += element
      return@forEachIndexed
    }
    if (insertAt < 0) insertAt = rebuilt.size
    // What this modifier said about the *other* axis, kept.
    when (type) {
      "fillMaxSize",
      "matchParentSize" -> {
        if (other.fillType() !in declared) return null
        rebuilt += fillModifier(other)
      }
      "size" -> {
        val kept = modifier.dp(other.fixedField())
        if (kept != null) {
          if (other.fixedType() !in declared) return null
          rebuilt += fixedModifier(other, kept)
        }
      }
    }
  }
  if (insertAt < 0) insertAt = 0
  val replacement: JsonObject? =
    when (sizing) {
      EditorSizing.Hug -> null
      EditorSizing.Fill ->
        when {
          byWeight ->
            buildJsonObject {
              put("type", "weight")
              put("weight", 1)
            }
          axis.fillType() in declared -> fillModifier(axis)
          else -> return null
        }
      is EditorSizing.Fixed -> {
        if (axis.fixedType() !in declared) return null
        val inner = sizing.dp - paddingBefore(rebuilt, insertAt, axis)
        fixedModifier(axis, inner.coerceAtLeast(0f))
      }
    }
  if (replacement != null) rebuilt.add(insertAt.coerceAtMost(rebuilt.size), replacement)
  return rebuilt
}

private fun fillModifier(axis: EditorAxis): JsonObject = buildJsonObject {
  put("type", axis.fillType())
}

/** Whole dp: a dragged edge is never meant to land at 143.71dp. */
private fun fixedModifier(axis: EditorAxis, dp: Float): JsonObject = buildJsonObject {
  put("type", axis.fixedType())
  put(axis.fixedField(), dp.roundToInt())
}

/** Both axes of [chain], and what the catalog lets each become. */
internal fun nodeSizing(
  nodeId: String,
  chain: List<JsonElement>,
  declared: Set<String>,
  scope: EditorLayoutScope?,
): EditorNodeSizing {
  fun axis(axis: EditorAxis) =
    EditorAxisSizing(
      axis = axis,
      current = sizingOf(chain, axis, scope),
      canFill = axis.fillType() in declared || (axis.fillsByWeight(scope) && "weight" in declared),
      canFix = axis.fixedType() in declared,
    )
  return EditorNodeSizing(nodeId, axis(EditorAxis.Width), axis(EditorAxis.Height))
}

/** `Hug`, `Fill` or `120dp`: what a chip or a handle's readout calls one axis. */
internal fun EditorSizing.label(): String =
  when (this) {
    EditorSizing.Hug -> "Hug"
    EditorSizing.Fill -> "Fill"
    is EditorSizing.Fixed -> "${dp.roundToInt()}dp"
  }
