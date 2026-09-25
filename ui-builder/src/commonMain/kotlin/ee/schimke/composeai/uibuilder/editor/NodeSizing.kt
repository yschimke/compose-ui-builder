package ee.schimke.composeai.uibuilder.editor

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** One of the two directions a node is sized in. */
enum class EditorAxis {
  Width,
  Height,
}

/**
 * How one axis of a node is sized, in the words a designer uses for it.
 *
 * Three answers and no more, because those are the three a handle, a double-click and a chip can
 * express: shrink to what is inside ([Hug]), take what the parent offers ([Fill]), or a number
 * ([Fixed]). Everything finer — a `widthIn`, an aspect ratio — stays in the modifier chain, where
 * the inspector edits it, and survives a resize untouched.
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
 * What one axis of the selected node is, and what it may become.
 *
 * [canFill] and [canFix] are the catalog's answer — a component that does not declare `width` has
 * no handle on that edge, rather than a handle whose drop the reducer would refuse.
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
 * Whether a `weight` is how this axis fills, which it is on the main axis of a row or a column.
 *
 * `fillMaxWidth` inside a `Row` takes everything left at the moment it is measured and leaves the
 * siblings after it nothing; `weight(1f)` is how Compose says "take the room that is left". A
 * collapsible layout's orientation is not known here, so it keeps the plain fill.
 */
private fun EditorAxis.fillsByWeight(scope: EditorLayoutScope?): Boolean =
  (this == EditorAxis.Width && scope == EditorLayoutScope.Row) ||
    (this == EditorAxis.Height && scope == EditorLayoutScope.Column)

private fun JsonElement.modifierType(): String? = (this as? JsonObject)?.optionalStringValue("type")

private fun JsonObject.dp(field: String): Float? =
  this[field]?.primitiveOrNull()?.doubleOrNull?.toFloat()

/**
 * How [axis] is sized by [chain], read the way the renderer applies it: the last size-deciding
 * modifier on the axis wins, which is also what Compose does with a chain that says two things.
 */
internal fun sizingOf(
  chain: List<JsonElement>,
  axis: EditorAxis,
  scope: EditorLayoutScope?,
): EditorSizing {
  var sizing: EditorSizing = EditorSizing.Hug
  chain.forEach { element ->
    val modifier = element as? JsonObject ?: return@forEach
    when (modifier.optionalStringValue("type")) {
      axis.fillType(),
      "fillMaxSize",
      "matchParentSize" -> sizing = EditorSizing.Fill
      "weight" -> if (axis.fillsByWeight(scope)) sizing = EditorSizing.Fill
      axis.fixedType() -> modifier.dp(axis.fixedField())?.let { sizing = EditorSizing.Fixed(it) }
      "size" -> modifier.dp(axis.fixedField())?.let { sizing = EditorSizing.Fixed(it) }
    }
  }
  return sizing
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
 * [chain] with [axis] re-sized to [sizing], or null when the catalog gives the component no way to
 * say it.
 *
 * Every modifier that decides this axis is taken out and at most one is put back, where the first
 * one was — so a `padding` written before a `width` keeps meaning what it meant — or at the front
 * of the chain when there was none, which is where a size reads naturally in the exported Kotlin
 * and where the outer box the handle was dragged to is the number written.
 *
 * A modifier that decides both axes is split rather than lost: resizing the width of something that
 * fills its parent leaves it filling the height, and a `size` keeps its other half as a `height`.
 *
 * A [EditorSizing.Fixed] is the *outer* box as drawn; padding that precedes the size in the chain
 * is subtracted, so the node lands at the size the handle showed.
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
