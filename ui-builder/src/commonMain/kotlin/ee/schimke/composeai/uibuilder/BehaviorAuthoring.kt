package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.DesignActionV1
import ee.schimke.composeai.uibuilder.protocol.StateVariableV1
import kotlinx.serialization.json.*

internal data class BehaviorIssue(
  val message: String,
  val nodeId: String? = null,
  val field: String? = null,
)

private val behaviorJson = Json { ignoreUnknownKeys = false }

/** Check the complete result, so removing or narrowing a declaration cannot strand its readers. */
internal fun UiBuilderDocument.behaviorIssue(): BehaviorIssue? {
  val identifiers = mutableSetOf<String>()
  stateVariables.forEach { (name, encoded) ->
    val identifier = exportedStateIdentifier(name)
    if (identifier in KOTLIN_HARD_KEYWORDS || !identifiers.add(identifier)) {
      return BehaviorIssue("State $name conflicts with a Kotlin name", field = name)
    }
    val declaration =
      encoded as? JsonObject
        ?: return BehaviorIssue("State $name must be a declaration", field = name)
    if (name.isBlank()) return BehaviorIssue("State name must not be blank", field = name)
    if (
      runCatching { behaviorJson.decodeFromJsonElement(StateVariableV1.serializer(), encoded) }
        .isFailure
    ) {
      return BehaviorIssue("Invalid state declaration for $name", field = name)
    }
    declaration["initialValue"]?.let { initial ->
      if (!declaration.acceptsStateValue(initial))
        return BehaviorIssue("Initial value does not match state $name", field = name)
    }
  }
  fun readIssue(value: JsonElement, nodeId: String, field: String): BehaviorIssue? {
    when (value) {
      is JsonObject -> {
        val type = (value["type"] as? JsonPrimitive)?.content
        if (type == "state" || type == "stateEquals") {
          val name = (value["variable"] as? JsonPrimitive)?.content
          if (name !in stateVariables)
            return BehaviorIssue("$field reads undeclared state $name", nodeId, field)
        }
        value.values.forEach {
          readIssue(it, nodeId, field)?.let { issue ->
            return issue
          }
        }
      }
      is JsonArray ->
        value.forEach {
          readIssue(it, nodeId, field)?.let { issue ->
            return issue
          }
        }
      else -> Unit
    }
    return null
  }
  nodes.values.forEach { node ->
    node.properties.forEach { (field, value) ->
      readIssue(value, node.id, field)?.let {
        return it
      }
    }
    readIssue(node.modifiers, node.id, "modifiers")?.let {
      return it
    }
    node.eventBindings.forEach { (event, encoded) ->
      val field = "eventBindings.$event"
      val actions =
        encoded as? JsonArray
          ?: return BehaviorIssue("An event contains an ordered list of actions", node.id, field)
      if (event.isBlank()) return BehaviorIssue("Event name must not be blank", node.id, field)
      actions.forEach { encodedAction ->
        val action =
          encodedAction as? JsonObject ?: return BehaviorIssue("Invalid action", node.id, field)
        if (
          runCatching { behaviorJson.decodeFromJsonElement(DesignActionV1.serializer(), action) }
            .isFailure
        ) {
          return BehaviorIssue("Invalid action in $event", node.id, field)
        }
        val variable = (action["variable"] as? JsonPrimitive)?.content ?: return@forEach
        val declaration =
          stateVariables[variable] as? JsonObject
            ?: return BehaviorIssue("$event writes undeclared state $variable", node.id, field)
        val type = (action["type"] as? JsonPrimitive)?.content
        if (type == "toggle" && declaration.stateType() != "bool") {
          return BehaviorIssue("$variable is not a flag and cannot be toggled", node.id, field)
        }
        if (type == "selectOrClear" && !declaration.nullableState()) {
          return BehaviorIssue("$variable is not nullable and cannot be cleared", node.id, field)
        }
        if (type in setOf("set", "select", "selectOrClear", "setText")) {
          action["value"]?.let { value ->
            if (!declaration.acceptsStateValue(value))
              return BehaviorIssue("Action value does not match state $variable", node.id, field)
          }
        }
      }
    }
  }
  return null
}

private fun JsonObject.nullableState(): Boolean =
  (this["nullable"] as? JsonPrimitive)?.booleanOrNull ?: (this["initialValue"] is JsonNull)

private fun JsonObject.stateType(): String {
  (this["valueType"] as? JsonPrimitive)?.contentOrNull?.let {
    return it
  }
  val initial = this["initialValue"] as? JsonPrimitive
  return when {
    initial == null || initial is JsonNull -> "unknown"
    initial.isString -> "string"
    initial.booleanOrNull != null -> "bool"
    initial.intOrNull != null -> "int"
    initial.doubleOrNull != null -> "float"
    else -> "unknown"
  }
}

private fun JsonObject.acceptsStateValue(value: JsonElement): Boolean {
  if (value is JsonNull) return nullableState()
  val primitive = value as? JsonPrimitive ?: return stateType() == "unknown"
  return when (stateType()) {
    "bool" -> !primitive.isString && primitive.booleanOrNull != null
    "int" -> !primitive.isString && primitive.intOrNull != null
    "float" -> !primitive.isString && primitive.doubleOrNull?.isFinite() == true
    "string" -> primitive.isString
    else -> true
  }
}
