package ee.schimke.composeai.uibuilder.renderer.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Later actions observe earlier writes even when a host applies callbacks after dispatch. */
fun canvasStateWrites(
  actions: JsonArray,
  state: Map<String, String?>,
): List<Pair<String, String?>> {
  val working = state.toMutableMap()
  return actions.mapNotNull { element ->
    (element as? JsonObject)
      ?.let { canvasStateWrite(it, working) }
      ?.also { (name, value) -> working[name] = value }
  }
}

/** Authoring a declaration resets that variable's preview value; unrelated interactions survive. */
fun reconcileCanvasState(
  state: MutableMap<String, String?>,
  before: JsonObject,
  after: JsonObject,
) {
  (before.keys - after.keys).forEach { state.remove(it) }
  after.forEach { (name, declaration) ->
    if (declaration != before[name])
      state[name] =
        ((declaration as? JsonObject)?.get("initialValue") as? JsonPrimitive)?.contentOrNull
  }
}

/** The state write one protocol action performs, or null when it performs none. */
fun canvasStateWrite(
  action: JsonObject,
  state: Map<String, String?>,
): Pair<String, String?>? {
  val variable =
    (action["variable"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
  val kind = (action["type"] as? JsonPrimitive)?.contentOrNull
  val operand = action["value"]
  if (
    kind in setOf("set", "select", "setText", "selectOrClear") &&
      operand != null &&
      operand !is JsonPrimitive
  )
    return null
  val value = (operand as? JsonPrimitive)?.contentOrNull
  return when (kind) {
    "select",
    "setText",
    "set" -> variable to value
    "selectOrClear" -> variable to if (state[variable] == value) null else value
    "toggle" -> variable to (state[variable]?.toBooleanStrictOrNull() != true).toString()
    "increment" -> canvasIncrement(state[variable], action["amount"])?.let { variable to it }
    else -> null
  }
}

/**
 * [held] plus [amount] (1 when absent), in the spelling the variable already uses: integers stay
 * integers — `"3"` and `1` make `"4"`, which `stateEquals 4` and an `Int` index still read — and
 * anything fractional on either side is added as a decimal. A missing value counts from 0, as the
 * export's `(x ?: 0) + 1` does for a nullable number; a value that is not a number writes nothing.
 */
private fun canvasIncrement(
  held: String?,
  amount: kotlinx.serialization.json.JsonElement?,
): String? {
  val step = (amount ?: JsonPrimitive(1)) as? JsonPrimitive ?: return null
  if (step.isString) return null
  val base = held ?: "0"
  val wholeBase = base.toLongOrNull()
  val wholeStep = step.contentOrNull?.toLongOrNull()
  if (wholeBase != null && wholeStep != null) return (wholeBase + wholeStep).toString()
  val sum =
    (base.toDoubleOrNull() ?: return null) + (step.contentOrNull?.toDoubleOrNull() ?: return null)
  return sum.takeIf(Double::isFinite)?.toString()
}
