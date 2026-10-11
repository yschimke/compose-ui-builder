package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.export.UiExpressions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Later actions observe earlier writes even when a host applies callbacks after dispatch. A formula
 * value or amount needs [scope], as [canvasStateWrite] does; without one it writes nothing.
 */
fun canvasStateWrites(
  actions: JsonArray,
  state: Map<String, String?>,
  scope: UiExpressions.Scope? = null,
  clock: UiExpressions.Clock = UiExpressions.Clock.DEFAULT,
): List<Pair<String, String?>> {
  val working = state.toMutableMap()
  return actions.mapNotNull { element ->
    (element as? JsonObject)
      ?.let { canvasStateWrite(it, working, scope, clock) }
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

/**
 * The state write one protocol action performs, or null. Formula values are evaluated against
 * [state] as it stands (after earlier writes) and [clock]; without a [scope] a formula writes
 * nothing.
 */
fun canvasStateWrite(
  action: JsonObject,
  state: Map<String, String?>,
  scope: UiExpressions.Scope? = null,
  clock: UiExpressions.Clock = UiExpressions.Clock.DEFAULT,
): Pair<String, String?>? {
  val variable =
    (action["variable"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
  val kind = (action["type"] as? JsonPrimitive)?.contentOrNull
  val operand = action["value"]?.let { evaluated(it, state, scope, clock) ?: return null }
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
    "increment" -> {
      val amount = action["amount"]?.let { evaluated(it, state, scope, clock) ?: return null }
      canvasIncrement(state[variable], amount)?.let { variable to it }
    }
    else -> null
  }
}

/**
 * [value] itself, or the literal a formula evaluates to now; null when a formula cannot be read.
 */
private fun evaluated(
  value: JsonElement,
  state: Map<String, String?>,
  scope: UiExpressions.Scope?,
  clock: UiExpressions.Clock,
): JsonElement? {
  if (!UiExpressions.isComputed(value)) return value
  val checked = UiExpressions.check(value, scope ?: return null) as? UiExpressions.Checked.Ok
  val expr = checked?.expr ?: return null
  val result = UiExpressions.evaluate(expr, UiExpressions.Environment(state = state, clock = clock))
  return when (result) {
    is Boolean -> JsonPrimitive(result)
    is Number -> JsonPrimitive(result)
    else -> JsonPrimitive(result.toString())
  }
}

/**
 * [held] plus [amount] (1 when absent), keeping the variable's spelling: integers stay `Int`s
 * (wrapping like the exported `Int`), fractions add as decimals, a missing value counts from 0, and
 * a non-number writes nothing.
 */
private fun canvasIncrement(
  held: String?,
  amount: kotlinx.serialization.json.JsonElement?,
): String? {
  val step = (amount ?: JsonPrimitive(1)) as? JsonPrimitive ?: return null
  if (step.isString) return null
  val base = held ?: "0"
  // `Int` arithmetic, so a counter at Int.MAX_VALUE wraps as the exported `count += 1` does.
  val wholeBase = base.toIntOrNull()
  val wholeStep = step.contentOrNull?.toIntOrNull()
  if (wholeBase != null && wholeStep != null) return (wholeBase + wholeStep).toString()
  val sum =
    (base.toDoubleOrNull() ?: return null) + (step.contentOrNull?.toDoubleOrNull() ?: return null)
  return sum.takeIf(Double::isFinite)?.toString()
}
