package ee.schimke.composeai.uibuilder

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
    else -> null
  }
}
