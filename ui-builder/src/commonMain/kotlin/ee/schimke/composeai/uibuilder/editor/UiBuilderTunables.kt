package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Where a tunable's value lands: one numeric property of a node, or one numeric field of one of its
 * modifiers. See [`UI_BUILDER_TUNABLES.md`](../../../../../../docs/design/UI_BUILDER_TUNABLES.md).
 */
sealed interface TunableTarget {
  val nodeId: String

  /** What the Tune panel calls this target, beside the node it is on. */
  val label: String

  /** A plain numeric property: `{"type": "float" | "int", "value": …}`. */
  data class Property(override val nodeId: String, val property: String) : TunableTarget {
    override val label: String
      get() = property.humanLabel()
  }

  /**
   * One numeric field of the modifier at [index] in the node's chain, which must still be a [type]:
   * a chain that changed underneath the link leaves it unresolved rather than tuning whichever
   * modifier moved into that place.
   */
  data class Modifier(
    override val nodeId: String,
    val index: Int,
    val type: String,
    val field: String,
  ) : TunableTarget {
    override val label: String
      get() {
        val fieldLabel =
          // `this.field`: a bare `field` in a getter is the backing field.
          MODIFIER_FIELDS[type]?.firstOrNull { it.name == this.field }?.label
            ?: this.field.humanLabel()
        return "${type.humanLabel()} $fieldLabel"
      }
  }
}

/**
 * A named number an author drags to try a design at different values: a range, a default, and the
 * properties it drives.
 *
 * A tunable is a way of *looking*, like a variant axis: while one is linked, the canvas and the
 * preview panes draw its value in place of what each target holds, but the document is not touched
 * until the author applies it. So dragging costs no revision, sends nothing to collaborators and
 * leaves undo alone, and an export is always of the stored design.
 */
data class DesignTunable(
  val name: String,
  val minimum: Double,
  val maximum: Double,
  val default: Double,
  /** Whether the value moves in whole steps, which every `int` target needs. */
  val integer: Boolean = false,
  val targets: List<TunableTarget> = emptyList(),
) {
  init {
    require(name.isNotBlank()) { "a tunable needs a name" }
    require(minimum.isFinite() && maximum.isFinite() && minimum < maximum) {
      "tunable $name needs a minimum below its maximum"
    }
    require(default.isFinite() && default in minimum..maximum) {
      "tunable $name's default must lie in its range"
    }
  }

  /**
   * The step a slider value lands on: whole numbers for an integer tunable or a range of 20 or
   * more, which is every dp range worth dragging; tenths below that; hundredths under 2, which is
   * an alpha or a weight. A slider reports a `Float`, so without this a drag writes
   * `254.11764526367188` into the design.
   */
  val resolution: Double
    get() =
      when {
        integer || maximum - minimum >= 20 -> 1.0
        maximum - minimum >= 2 -> 0.1
        else -> 0.01
      }

  /** [value] moved into range and onto the nearest [resolution] step. */
  fun coerce(value: Double): Double {
    if (!value.isFinite()) return default
    val stepped = round(value / resolution) * resolution
    // Rounding the step back out of binary noise: 0.1 * 3 is 0.30000000000000004.
    val tidy = round(stepped * 100) / 100
    return tidy.coerceIn(minimum, maximum)
  }

  /** How many discrete stops a slider over this range has; 0 means continuous. */
  val sliderSteps: Int
    get() {
      if (!integer) return 0
      val stops = (maximum - minimum).toInt() - 1
      // A slider with thousands of ticks draws a solid bar; past this it is continuous and the
      // value is rounded instead.
      return if (stops in 1..MAX_SLIDER_TICKS) stops else 0
    }
}

private const val MAX_SLIDER_TICKS = 100

/** The most tunables a design is offered: a handful of knobs, not a second inspector. */
const val MAX_DESIGN_TUNABLES: Int = 8

/** The value [tunable] is drawn at: the dragged one when there is one, else its default. */
fun Map<String, Double>.valueOf(tunable: DesignTunable): Double =
  this[tunable.name]?.let(tunable::coerce) ?: tunable.default

/** Whether any tunable is drawn at something other than what its targets hold. */
fun UiBuilderDocument.isTuned(tunables: List<DesignTunable>, values: Map<String, Double>): Boolean =
  tunables.any { tunable ->
    val value = values.valueOf(tunable)
    tunable.targets.any { target -> heldValue(target)?.let { it != value } ?: false }
  }

/**
 * The number [target] holds in this document, or null when it resolves to nothing tunable: the node
 * is gone, the property is a binding, or the modifier moved.
 */
fun UiBuilderDocument.heldValue(target: TunableTarget): Double? {
  val node = nodes[target.nodeId] ?: return null
  return when (target) {
    is TunableTarget.Property -> {
      val encoded = node.properties[target.property] as? JsonObject ?: return null
      if (encoded.wrapper() !in NUMERIC_WRAPPERS) return null
      encoded["value"].number()
    }
    is TunableTarget.Modifier -> node.modifierAt(target)?.get(target.field).number()
  }
}

/** Whether [target] is something a tunable can drive in this document. */
fun UiBuilderDocument.canTune(target: TunableTarget): Boolean {
  val node = nodes[target.nodeId] ?: return false
  return when (target) {
    is TunableTarget.Property -> {
      // `contentPadding.topDp` is one edge of an object value, written whole; not a number here.
      if ('.' in target.property) return false
      val encoded = node.properties[target.property] ?: return true
      (encoded as? JsonObject)?.wrapper() in NUMERIC_WRAPPERS
    }
    is TunableTarget.Modifier -> {
      val modifier = node.modifierAt(target) ?: return false
      MODIFIER_FIELDS[target.type].orEmpty().any {
        it.name == target.field && it.choices.isEmpty()
      } && (modifier[target.field] == null || modifier[target.field].number() != null)
    }
  }
}

/**
 * This document with every linked target holding its tunable's current value — what the canvas and
 * the preview panes draw while a tunable is linked. Targets that no longer resolve are left alone,
 * never failing the frame. The same instance comes back when nothing changes, so a design with no
 * tunables costs the canvas nothing.
 */
fun UiBuilderDocument.tuned(
  tunables: List<DesignTunable>,
  values: Map<String, Double>,
): UiBuilderDocument {
  if (tunables.none { it.targets.isNotEmpty() }) return this
  val writes = tunableWrites(tunables, values)
  if (writes.isEmpty()) return this
  return copy(nodes = nodes + writes)
}

/** The nodes [tuned] rewrites, keyed by id; also what applying the tunables commits. */
internal fun UiBuilderDocument.tunableWrites(
  tunables: List<DesignTunable>,
  values: Map<String, Double>,
): Map<String, ee.schimke.composeai.uibuilder.export.UiBuilderNode> {
  val rewritten = linkedMapOf<String, ee.schimke.composeai.uibuilder.export.UiBuilderNode>()
  tunables.forEach { tunable ->
    val value = values.valueOf(tunable)
    tunable.targets.forEach { target ->
      if (!canTune(target)) return@forEach
      val node = rewritten[target.nodeId] ?: nodes.getValue(target.nodeId)
      rewritten[target.nodeId] =
        when (target) {
          is TunableTarget.Property -> {
            val existing = node.properties[target.property] as? JsonObject
            val wrapper = existing?.wrapper() ?: if (tunable.integer) "int" else "float"
            val encoded =
              JsonObject(
                (existing ?: JsonObject(emptyMap())) +
                  mapOf(
                    "type" to JsonPrimitive(wrapper),
                    // As the inspector writes them: an `int` whole, a `float` as a double.
                    "value" to
                      if (wrapper == "int") JsonPrimitive(round(value).toLong())
                      else JsonPrimitive(value),
                  )
              )
            node.copy(properties = JsonObject(node.properties + (target.property to encoded)))
          }
          is TunableTarget.Modifier ->
            node.copy(
              modifiers =
                JsonArray(
                  node.modifiers.mapIndexed { index, element ->
                    val modifier = element as? JsonObject
                    if (index != target.index || modifier == null) element
                    else JsonObject(modifier + (target.field to modifierPrimitive(value)))
                  }
                )
            )
        }
    }
  }
  return rewritten.filter { (id, node) -> nodes[id] != node }
}

/**
 * The tunables with every target that no longer resolves in [document] dropped, so a deleted node
 * or a rewritten modifier chain does not leave a dead row in the panel.
 */
fun List<DesignTunable>.resolvedIn(document: UiBuilderDocument): List<DesignTunable> =
  map { tunable ->
    val live = tunable.targets.filter(document::canTune)
    if (live.size == tunable.targets.size) tunable else tunable.copy(targets = live)
  }

/** A starting range for a tunable made from a field holding [current], within [bounds]. */
fun suggestedTunableRange(current: Double, bounds: EditorNumberBounds?): Pair<Double, Double> {
  val floorValue = bounds?.minimum ?: 0.0
  val ceiling = bounds?.maximum ?: Double.MAX_VALUE
  // Wide enough to see a difference, narrow enough that a drag is not all one end of the range:
  // a 16 dp padding gets 0..48 rather than 0..10000.
  // Most numbers here are dp, so a value at zero still gets room to show something. A range the
  // catalog bounds tightly (an alpha, a weight in 0..1) is clamped back to it below.
  val span = maxOf(abs(current) * 2, if (bounds?.integer == true) 10.0 else 24.0)
  val low = maxOf(floorValue, if (current >= 0) 0.0 else floor(current - span))
  // Whole ends, so a 254.1 value gets 0..763 rather than 0..762.35.
  val high = minOf(ceiling, ceil(current + span)).let { if (it <= low) low + 1 else it }
  return low to high
}

/** A tunable name derived from [label] that [taken] does not already use. */
fun freshTunableName(label: String, taken: Collection<String>): String {
  val base = label.trim().ifEmpty { "Value" }
  if (base !in taken) return base
  var index = 2
  while ("$base $index" in taken) index++
  return "$base $index"
}

/**
 * The ranges a modifier field is tuned over where its meaning bounds it; every other field gets a
 * range around its value, from zero for one that is not negative.
 */
internal val MODIFIER_TUNING_BOUNDS: Map<String, EditorNumberBounds> =
  mapOf(
    "alpha" to EditorNumberBounds(minimum = 0.0, maximum = 1.0, step = 0.01, integer = false),
    "rotate" to EditorNumberBounds(minimum = -180.0, maximum = 180.0, step = 1.0, integer = false),
  )

private val NUMERIC_WRAPPERS = setOf("float", "int", "dp", "sp")

private fun JsonObject.wrapper(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.number(): Double? {
  val primitive = this as? JsonPrimitive ?: return null
  if (primitive.isString || primitive.booleanOrNull != null) return null
  return primitive.doubleOrNull?.takeIf { it.isFinite() }
}

private fun ee.schimke.composeai.uibuilder.export.UiBuilderNode.modifierAt(
  target: TunableTarget.Modifier
): JsonObject? =
  (modifiers.getOrNull(target.index) as? JsonObject)?.takeIf { it.wrapper() == target.type }

/** Whole numbers stay whole, as [UiBuilderEditorReducer]'s own modifier writes keep them. */
private fun modifierPrimitive(value: Double): JsonPrimitive =
  if (value == floor(value) && abs(value) < Long.MAX_VALUE.toDouble()) JsonPrimitive(value.toLong())
  else JsonPrimitive(value)

/** [value] as a short label: whole numbers without a point, others to two places. */
fun formatTunableValue(value: Double): String {
  if (value == floor(value) && abs(value) < 1e12) return value.toLong().toString()
  val hundredths = round(value * 100).toLong()
  val sign = if (hundredths < 0) "-" else ""
  val magnitude = abs(hundredths)
  val fraction = (magnitude % 100).toString().padStart(2, '0').trimEnd('0')
  return "$sign${magnitude / 100}" + if (fraction.isEmpty()) "" else ".$fraction"
}
