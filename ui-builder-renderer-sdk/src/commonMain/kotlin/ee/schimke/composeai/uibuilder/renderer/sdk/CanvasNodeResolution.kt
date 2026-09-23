package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Resolve the generic document vocabulary before a catalog adapter sees a node.
 *
 * Component arguments are substituted first, then state reads, then the catalog's canvas-only
 * property/slot projection. The authored node is never mutated and export continues to read it.
 */
fun resolveCanvasNode(
  node: UiBuilderNode,
  arguments: JsonObject,
  state: Map<String, String?>,
  mapping: CanvasAdapterMappingV1?,
): UiBuilderNode = node.withArguments(arguments).withPreviewState(state).forCanvas(mapping)

private fun UiBuilderNode.forCanvas(mapping: CanvasAdapterMappingV1?): UiBuilderNode {
  if (mapping == null) return this
  val mappedProperties = buildMap {
    putAll(mapping.defaults)
    putAll(properties)
    mapping.properties.forEach { (target, source) -> properties[source]?.let { put(target, it) } }
  }
  val mappedSlots = buildMap {
    putAll(slots)
    mapping.slots.forEach { (target, source) -> slots[source]?.let { put(target, it) } }
  }
  return copy(properties = JsonObject(mappedProperties), slots = mappedSlots)
}

private fun UiBuilderNode.withArguments(arguments: JsonObject): UiBuilderNode {
  if (arguments.isEmpty() || properties.isEmpty()) return this
  var substituted = false
  val resolved = properties.mapValues { (_, value) ->
    val binding = value as? JsonObject ?: return@mapValues value
    val key = binding.bindingKey() ?: return@mapValues value
    val argument = arguments[key] ?: return@mapValues value
    substituted = true
    argument
  }
  return if (substituted) copy(properties = JsonObject(resolved)) else this
}

private fun UiBuilderNode.withPreviewState(state: Map<String, String?>): UiBuilderNode =
  copy(
    properties =
      JsonObject(
        properties.mapValues { (_, encoded) ->
          val binding = encoded as? JsonObject ?: return@mapValues encoded
          val variable =
            (binding["variable"] as? JsonPrimitive)?.content ?: return@mapValues encoded
          when (binding.wrapperType()) {
            "state" ->
              JsonObject(binding + ("value" to (state[variable]?.let(::JsonPrimitive) ?: JsonNull)))
            "stateEquals" ->
              JsonObject(
                binding +
                  mapOf(
                    "type" to JsonPrimitive("bool"),
                    "value" to JsonPrimitive(canvasStateEquals(state[variable], binding["value"])),
                  )
              )
            else -> encoded
          }
        }
      )
  )

private fun JsonObject.bindingKey(): String? {
  if (wrapperType() != "binding") return null
  return (this["value"] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.wrapperType(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull

/** Numeric state equality follows generated Kotlin rather than string spelling. */
fun canvasStateEquals(held: String?, operand: kotlinx.serialization.json.JsonElement?): Boolean {
  val primitive = operand as? JsonPrimitive
  val expected = primitive?.contentOrNull
  if (held == expected) return true
  if (held == null || expected == null || primitive.isString) return false
  val number = held.toDoubleOrNull() ?: return false
  return number == expected.toDoubleOrNull()
}
