package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.export.RemoteModifierVocabulary
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiExpressions
import ee.schimke.composeai.uibuilder.export.UiValueKind
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
  expressions: CanvasExpressions? = null,
): UiBuilderNode =
  node
    .withArguments(arguments)
    .let { expressions?.evaluate(it, arguments, state) ?: it }
    .withPreviewState(state)
    .forCanvas(mapping)

/**
 * Computed values (`expr`/`system`) evaluated for one frame of the canvas: at the preview state,
 * the row or placement arguments in scope, and the document's fixed time.
 *
 * Each property and each modifier field holding one is replaced by the literal it evaluates to, so
 * adapters never see an expression and draw exactly what they drew for a typed literal. An
 * expression that does not type is left in place, where the adapter ignores it as it ignores any
 * value it cannot read; the reducer has already refused it at commit.
 */
class CanvasExpressions(
  private val stateKinds: Map<String, UiValueKind>,
  private val clock: UiExpressions.Clock,
) {
  internal fun evaluate(
    node: UiBuilderNode,
    arguments: JsonObject,
    state: Map<String, String?>,
  ): UiBuilderNode {
    val computed =
      node.properties.values.any(UiExpressions::isComputed) ||
        node.modifiers.any { modifier ->
          val fields = modifier as? JsonObject
          fields?.values?.any(UiExpressions::isComputed) == true ||
            fields?.remoteCallArgs()?.values?.any(::isDynamic) == true
        }
    if (!computed) return node
    val scope =
      UiExpressions.Scope(stateKinds) { key ->
        ((arguments[key] as? JsonObject)?.get("type") as? JsonPrimitive)
          ?.contentOrNull
          ?.let(UiValueKind::fromWire)
      }
    val environment =
      UiExpressions.Environment(
        state = state,
        bindings = { key -> (arguments[key] as? JsonObject)?.get("value") as? JsonPrimitive },
        clock = clock,
      )
    fun literal(value: JsonElement): JsonObject? =
      (UiExpressions.check(value, scope) as? UiExpressions.Checked.Ok)?.let {
        UiExpressions.evaluateWrapped(it.expr, environment)
      }
    return node.copy(
      properties =
        JsonObject(
          node.properties.mapValues { (_, value) ->
            if (UiExpressions.isComputed(value)) literal(value) ?: value else value
          }
        ),
      modifiers =
        JsonArray(
          node.modifiers.map { modifier ->
            val fields = modifier as? JsonObject ?: return@map modifier
            // A Remote call's arguments are value wrappers, so they are evaluated to wrappers.
            fields.remoteCallArgs()?.let { args ->
              return@map JsonObject(
                fields +
                  ("args" to
                    JsonObject(
                      args.mapValues { (_, value) ->
                        if (isDynamic(value)) literal(value) ?: value else value
                      }
                    ))
              )
            }
            if (fields.values.none(UiExpressions::isComputed)) return@map modifier
            // Modifier fields are bare numbers rather than wrappers, so the literal's value goes
            // in.
            JsonObject(
              fields.mapValues { (_, value) ->
                if (UiExpressions.isComputed(value)) literal(value)?.get("value") ?: value
                else value
              }
            )
          }
        ),
    )
  }

  private fun JsonObject.remoteCallArgs(): JsonObject? =
    takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == RemoteModifierVocabulary.TYPE }
      ?.get("args") as? JsonObject

  /** A state read or a computed value: what the player evaluates rather than reads as written. */
  private fun isDynamic(value: JsonElement): Boolean =
    UiExpressions.isComputed(value) ||
      ((value as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "state"

  companion object {
    /** The kinds [document] declares and its `environment.fixedTime`. */
    fun of(document: UiBuilderDocument): CanvasExpressions =
      CanvasExpressions(
        stateKinds = UiExpressions.Scope.of(document).stateKinds,
        clock =
          UiExpressions.Clock.of(
            (document.environment["fixedTime"] as? JsonPrimitive)?.contentOrNull
          ),
      )
  }
}

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
