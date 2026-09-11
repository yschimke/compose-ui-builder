package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.*

/** One atomic property shared by browser edits, persisted documents and MCP setProperty. */
const val SHOW_BY_STATE = "showByState"
const val STATE_SELECTION_CONTAINER = "layout/box"

data class StateSelection(
  val selector: JsonObject,
  val cases: Map<String, JsonPrimitive>,
  val fallback: String? = null,
) {
  fun encode(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("fields") {
      put("selector", selector)
      putJsonObject("cases") {
        put("type", "object")
        putJsonObject("fields") {
          cases.forEach { (id, value) -> put(id, selectionLiteral(value)) }
        }
      }
      fallback?.let { put("fallback", selectionLiteral(JsonPrimitive(it))) }
    }
  }

  fun selectedNode(state: Map<String, String?>, declarations: JsonObject): String? {
    val variable = (selector["variable"] as? JsonPrimitive)?.contentOrNull
    val value =
      if (selector["type"] == JsonPrimitive("state")) state[variable]
      else (selector["value"] as? JsonPrimitive)?.contentOrNull
    val kind =
      if (selector["type"] == JsonPrimitive("state"))
        (declarations[variable] as? JsonObject)?.let { declaration ->
          (declaration["valueType"] as? JsonPrimitive)?.contentOrNull
            ?: (declaration["initialValue"] as? JsonPrimitive)
              ?.takeUnless { it is JsonNull }
              ?.let { selectionLiteral(it)["type"]?.jsonPrimitive?.content }
        }
      else (selector["type"] as? JsonPrimitive)?.content
    return cases.entries
      .firstOrNull { (_, match) ->
        if (value == null) false
        else if (!match.isString && match.booleanOrNull == null)
          if (kind != "float") value.toIntOrNull() == match.intOrNull
          else value.toFloatOrNull() == match.floatOrNull
        else value == match.content
      }
      ?.key ?: fallback
  }
}

fun selectionLiteral(value: JsonPrimitive): JsonObject = buildJsonObject {
  put(
    "type",
    when {
      value.isString -> "string"
      value.booleanOrNull != null -> "bool"
      value.intOrNull != null -> "int"
      else -> "float"
    },
  )
  put("value", value)
}

/** Malformed configurations are diagnosed separately; they never make all children visible. */
fun UiBuilderNode.stateSelection(): StateSelection? {
  val encoded = properties[SHOW_BY_STATE] as? JsonObject ?: return null
  if (encoded["type"] != JsonPrimitive("object") || encoded.keys != setOf("type", "fields"))
    return null
  val fields = encoded["fields"] as? JsonObject ?: return null
  if (!setOf("selector", "cases", "fallback").containsAll(fields.keys)) return null
  val selector = fields["selector"] as? JsonObject ?: return null
  val caseObject = fields["cases"] as? JsonObject ?: return null
  if (caseObject["type"] != JsonPrimitive("object") || caseObject.keys != setOf("type", "fields"))
    return null
  val cases = caseObject["fields"] as? JsonObject ?: return null
  val values = cases.mapValues { (_, value) ->
    val literal = value as? JsonObject ?: return null
    val scalar = (literal["value"] as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return null
    val kind = (literal["type"] as? JsonPrimitive)?.content ?: return null
    if (literal.keys != setOf("type", "value") || !selectionScalarMatches(scalar, kind)) return null
    scalar
  }
  val fallback =
    fields["fallback"]?.let {
      val literal = it as? JsonObject ?: return null
      if (literal.keys != setOf("type", "value") || literal["type"] != JsonPrimitive("string"))
        return null
      (literal["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    }
  val selectorKind = (selector["type"] as? JsonPrimitive)?.content ?: return null
  if (selectorKind == "state") {
    if (
      selector.keys != setOf("type", "variable") ||
        (selector["variable"] as? JsonPrimitive)?.isString != true
    )
      return null
  } else {
    if (
      selector.keys != setOf("type", "value") ||
        !selectionScalarMatches(selector["value"] as? JsonPrimitive, selectorKind)
    )
      return null
  }
  return StateSelection(selector, values, fallback)
}

/** Located by the caller at node.properties.showByState, identical in browser and service. */
fun stateSelectionIssue(node: UiBuilderNode, declarations: JsonObject): String? {
  if (SHOW_BY_STATE !in node.properties) return null
  if (node.componentId != STATE_SELECTION_CONTAINER) return "Show by state requires a Box container"
  val selection =
    node.stateSelection() ?: return "Show by state needs a selector and a cases object"
  val children = node.slots["children"].orEmpty().toSet()
  if (selection.cases.isEmpty()) return "Add at least one case to Show by state"
  if (selection.fallback in selection.cases) return "A child cannot be both a case and the fallback"
  val used = selection.cases.keys + listOfNotNull(selection.fallback)
  if (used != children) return "Every child must have exactly one case or be the fallback"
  val selectorType = selection.selector["type"] as? JsonPrimitive
  val kind =
    if (selectorType?.content == "state") {
      val variable = (selection.selector["variable"] as? JsonPrimitive)?.contentOrNull
      val declaration =
        declarations[variable] as? JsonObject ?: return "Selector state is not declared"
      (declaration["valueType"] as? JsonPrimitive)?.contentOrNull
        ?: (declaration["initialValue"] as? JsonPrimitive)?.let {
          selectionLiteral(it)["type"]?.jsonPrimitive?.content
        }
    } else selectorType?.content
  if (kind !in setOf("bool", "int", "float", "string"))
    return "Selector must be scalar state or a scalar literal"
  if (selectorType?.content != "state" && selection.selector["value"] !is JsonPrimitive)
    return "Selector must contain a scalar value"
  val canonical = mutableSetOf<Any>()
  selection.cases.forEach { (id, value) ->
    val valid = selectionScalarMatches(value, kind)
    if (!valid) return "Case $id does not match the selector's $kind type"
    val key: Any =
      when (kind) {
        "int" -> value.intOrNull!!
        "float" -> value.floatOrNull!!.let { if (it == 0f) 0f else it }
        else -> value
      }
    if (!canonical.add(key)) return "Case $id duplicates another value"
  }
  return null
}

private fun selectionScalarMatches(value: JsonPrimitive?, kind: String?): Boolean =
  value != null &&
    value !is JsonNull &&
    when (kind) {
      "string" -> value.isString
      "bool" -> !value.isString && value.booleanOrNull != null
      "int" -> !value.isString && value.intOrNull != null
      "float" -> !value.isString && value.floatOrNull?.isFinite() == true
      else -> false
    }
