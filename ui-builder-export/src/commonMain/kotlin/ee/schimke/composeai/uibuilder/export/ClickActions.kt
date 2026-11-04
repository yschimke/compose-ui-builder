package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The ordered actions a click on a node runs.
 *
 * The builder's interaction model is `eventBindings.click`. Some catalog components
 * (`wear-m3/button` and `wear-m3/card`, and `m3/button`) also declare an `onClickAction` property:
 * one action, in the same shape as an `eventBindings` entry, written as the builder's `object`
 * wrapper. Rather than a second interaction model, that property is read here as one more click
 * action, after the bound ones — so the canvas SDK and the Wear exporter run exactly the same list.
 */
object ClickActions {
  const val PROPERTY: String = "onClickAction"

  /** [node]'s click actions: `eventBindings.click`, then its `onClickAction` when it holds one. */
  fun of(node: UiBuilderNode): JsonArray {
    val bound = (node.eventBindings[TwoWayStateBinding.CLICK] as? JsonArray).orEmpty()
    val property = propertyAction(node.properties[PROPERTY]) ?: return JsonArray(bound)
    return JsonArray(bound + property)
  }

  /** Whether a click on [node] does anything: a bound click or an `onClickAction`. */
  fun handles(node: UiBuilderNode): Boolean =
    node.eventBindings[TwoWayStateBinding.CLICK] != null ||
      propertyAction(node.properties[PROPERTY]) != null

  /**
   * The plain action an `onClickAction` value holds, or null for none (absent, a `null` wrapper, or
   * an object with no `type`). The `object` wrapper's typed fields are lowered to their values, so
   * `{"type":"object","fields":{"type":{"type":"string","value":"toggle"},…}}` reads as
   * `{"type":"toggle",…}`.
   */
  fun propertyAction(value: JsonElement?): JsonObject? {
    val wrapper = value as? JsonObject ?: return null
    if (wrapper.typeName() != "object") return null
    val fields = wrapper["fields"] as? JsonObject ?: return null
    val action = JsonObject(fields.mapValues { (_, member) -> lower(member) })
    return action.takeIf { (it["type"] as? JsonPrimitive)?.isString == true }
  }

  private fun lower(value: JsonElement): JsonElement {
    val wrapper = value as? JsonObject ?: return value
    return when (wrapper.typeName()) {
      "object" ->
        (wrapper["fields"] as? JsonObject)?.let { fields ->
          JsonObject(fields.mapValues { (_, member) -> lower(member) })
        } ?: JsonNull
      "list" -> (wrapper["values"] as? JsonArray)?.let { JsonArray(it.map(::lower)) } ?: JsonNull
      "null" -> JsonNull
      // A literal wrapper (`string`, `number`, `boolean`, …) carries its value; anything else, a
      // `state` read for one, is an action's own JSON and stays as it is.
      else -> if ("value" in wrapper && wrapper.size == 2) wrapper.getValue("value") else wrapper
    }
  }

  private fun JsonObject.typeName(): String? =
    (this["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
