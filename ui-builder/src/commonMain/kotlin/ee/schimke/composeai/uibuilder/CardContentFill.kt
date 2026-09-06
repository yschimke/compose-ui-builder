package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonObject

/**
 * How far an `m3/card`'s content box reaches inside the card, on each axis.
 *
 * The card draws its content slot in a `Box`, because a card's children are stacked with box
 * alignments (`matchParentSize`, `align`), and that box used to be `Modifier.fillMaxSize()` so
 * those alignments had something to align against. A fill against bounded constraints is a fill,
 * though, and a `Column` child's height constraint is bounded by whatever the column has left — so
 * a card with no height ate the rest of its column and every sibling after it was never laid out
 * (compose-preview-server #483). Real `Card` wraps its content, and the workaround people found was
 * to pin every card to a height it should have taken from its text.
 *
 * So the box now fills only the axes the document sized. A card with `fillMaxWidth` gets a box that
 * fills its width and wraps its height; a card with a `height` keeps a box that fills that height,
 * so a child aligned to its bottom still lands there; a card with nothing wraps both, the way
 * `Card` does. The canvas and `CapabilityComposeCodeExporter` read this one decision, so the
 * picture and the Kotlin it writes cannot disagree about it.
 */
internal data class CardContentFill(val width: Boolean, val height: Boolean)

internal fun UiBuilderNode.cardContentFill(): CardContentFill {
  var width = false
  var height = false
  modifiers.forEach { element ->
    when (val plan = (element as? JsonObject)?.let(::uiBuilderModifier)) {
      UiBuilderModifierPlan.FillMaxSize,
      UiBuilderModifierPlan.MatchParentSize -> {
        width = true
        height = true
      }
      UiBuilderModifierPlan.FillMaxWidth -> width = true
      UiBuilderModifierPlan.FillMaxHeight -> height = true
      is UiBuilderModifierPlan.Width -> width = true
      is UiBuilderModifierPlan.Height -> height = true
      is UiBuilderModifierPlan.Size -> {
        if (plan.widthDp != null) width = true
        if (plan.heightDp != null) height = true
      }
      // A ratio derives one axis from the other, so a card carrying one is sized once either is;
      // and a weight is a share of the parent's axis, which this cannot tell from the node alone —
      // both keep the fill the card always had.
      is UiBuilderModifierPlan.AspectRatio,
      is UiBuilderModifierPlan.Weight -> {
        width = true
        height = true
      }
      else -> Unit
    }
  }
  // The properties that said this before the modifier vocabulary existed, still honoured by both
  // readers of this decision.
  if ("weight" in properties || "sizeDp" in properties) {
    width = true
    height = true
  }
  return CardContentFill(width = width, height = height)
}
