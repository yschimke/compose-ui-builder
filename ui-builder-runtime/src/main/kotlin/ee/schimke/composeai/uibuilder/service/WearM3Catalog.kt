@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * How the builder's insert panel shelves `wear-m3`, and which property carries a component's
 * variants.
 *
 * Wear's own families rather than Material's: a watch app is a **screen** holding a **list**, so
 * those lead, and `Text inputs` — a whole shelf on the phone catalog — does not exist here at all.
 * `wear-m3/edge-button`'s `size` is a variant rather than a dimension: an edge button comes in four
 * sizes the way a card comes in four kinds, and picking one is choosing which button, not nudging a
 * number.
 *
 * Deliberately absent: `transformation` on the lazy column and `segmented` on the slider are
 * behaviours a design turns on, not kinds of component; `iconKey` is forty-six icons; and
 * `wear-m3/text.style` is fifteen type scales, which is a property of a text rather than a kind of
 * Text. The same three calls the M3 declaration makes, for the same reasons.
 */
internal fun wearComponentMenu(): JsonObject {
  val shelves =
    listOf(
      "Screens" to listOf("wear-m3/screen-scaffold"),
      "Layout" to listOf("layout/box", "layout/column", "layout/row"),
      "Lists" to
        listOf(
          "wear-m3/transforming-lazy-column",
          "wear-m3/list-header",
          "wear-m3/list-sub-header",
        ),
      "Actions" to
        listOf(
          "wear-m3/button",
          "wear-m3/text-button",
          "wear-m3/icon-button",
          "wear-m3/edge-button",
          "wear-m3/button-group",
        ),
      "Selection" to
        listOf(
          "wear-m3/checkbox-button",
          "wear-m3/switch-button",
          "wear-m3/radio-button",
          "wear-m3/slider",
          "wear-m3/stepper",
          "wear-m3/date-picker",
          "wear-m3/time-picker",
        ),
      "Containment" to
        listOf(
          "wear-m3/card",
          "wear-m3/alert-dialog",
          "wear-m3/confirmation-dialog",
          "wear-m3/open-on-phone-dialog",
        ),
      "Communication" to listOf("wear-m3/progress-indicator"),
      "Content" to listOf("wear-m3/text", "wear-m3/icon", "asset/image"),
      // The "Embedded" shelf held the three Remote Compose seams and is gone with them. It comes
      // back when they do; a shelf with nothing on it is a heading an author opens for nothing.
    )
  val variantProperties =
    mapOf(
      "wear-m3/card" to "variant",
      "wear-m3/button" to "variant",
      "wear-m3/text-button" to "variant",
      "wear-m3/icon-button" to "variant",
      "wear-m3/edge-button" to "size",
      "wear-m3/progress-indicator" to "variant",
      "wear-m3/confirmation-dialog" to "variant",
      "wear-m3/date-picker" to "type",
      "wear-m3/time-picker" to "type",
    )
  return buildJsonObject {
    putJsonArray("groupOrder") { shelves.forEach { (name, _) -> add(JsonPrimitive(name)) } }
    putJsonObject("components") {
      shelves.forEach { (name, componentIds) ->
        componentIds.forEach { componentId ->
          putJsonObject(componentId) {
            put("group", JsonPrimitive(name))
            variantProperties[componentId]?.let { put("variantProperty", JsonPrimitive(it)) }
          }
        }
      }
    }
  }
}
