package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How far an `m3/card`'s content box reaches inside the card, on each axis.
 *
 * A card's content slot is a box (canvas and every emitter write `Card { Box { … } }`). The box
 * fills only the axes the document sized: filling unconditionally would make a card in a `Column`
 * eat the remaining height. A card with `fillMaxWidth` fills width and wraps height; one with a
 * `height` fills that height; one with neither wraps both, like `Card`. Shared so canvas and both
 * exporters agree.
 */
data class CardContentFill(val width: Boolean, val height: Boolean)

fun UiBuilderNode.cardContentFill(): CardContentFill {
  var width = false
  var height = false
  modifiers.forEach { element ->
    val modifier = element as? JsonObject ?: return@forEach
    when (modifier["type"]?.jsonPrimitive?.contentOrNull) {
      "fillMaxSize",
      "matchParentSize" -> {
        width = true
        height = true
      }
      "fillMaxWidth",
      "width" -> width = true
      "fillMaxHeight",
      "height" -> height = true
      "size" -> {
        if (modifier.numberOrNull("widthDp") != null) width = true
        if (modifier.numberOrNull("heightDp") != null) height = true
      }
      // A ratio derives one axis from the other, so a card carrying one is sized once either is;
      // and a weight is a share of the parent's axis, which this cannot tell from the node alone —
      // both keep the fill the card always had.
      "aspectRatio",
      "weight" -> {
        width = true
        height = true
      }
      else -> Unit
    }
  }
  // The properties that said this before the modifier vocabulary existed, still honoured by every
  // reader of this decision.
  if ("weight" in properties || "sizeDp" in properties) {
    width = true
    height = true
  }
  return CardContentFill(width = width, height = height)
}

private fun JsonObject.numberOrNull(name: String): Double? =
  (this[name] as? JsonPrimitive)?.doubleOrNull
