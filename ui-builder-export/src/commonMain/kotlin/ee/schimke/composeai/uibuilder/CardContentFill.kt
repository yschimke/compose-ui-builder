package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How far an `m3/card`'s content box reaches inside the card, on each axis.
 *
 * A card's content slot is a **box** in this catalog: the canvas stacks its children with box
 * alignments (`matchParentSize`, `align`), the editor offers a card child exactly the modifiers a
 * `layout/box` child gets, and every emitter writes `Card { Box { … } }` — the capability exporter
 * and the record-driven projection alike, which is what keeps the native render and the canvas one
 * picture. That box used to be `Modifier.fillMaxSize()` so the alignments had something to align
 * against; a fill against bounded constraints is a fill, though, and a `Column` child's height
 * constraint is bounded by whatever the column has left, so a card with no height ate the rest of
 * its column and every sibling after it was never laid out (compose-preview-server #483).
 *
 * So the box fills only the axes the document sized. A card with `fillMaxWidth` gets a box that
 * fills its width and wraps its height; a card with a `height` keeps a box that fills that height,
 * so a child aligned to its bottom still lands there; a card with nothing wraps both, the way
 * `Card` does. Every reader of this decision reads it here, in the module all three can see, so the
 * picture and the two Kotlins it can become cannot disagree about it.
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
