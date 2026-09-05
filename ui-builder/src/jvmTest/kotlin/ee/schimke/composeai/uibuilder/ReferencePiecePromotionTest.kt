package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Building a placed piece into the component it is a picture of.
 *
 * This is the one crossing from the reference half back into the design, so these cases care about
 * two things in equal measure: that a piece with provenance produces exactly the node a catalog
 * insertion would, and that a piece without provenance produces nothing at all. The second is what
 * keeps "build this for real" from being a guess.
 */
class ReferencePiecePromotionTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val initial = reducer.initial(document, selectedNodeId = "discover-grid")

  private fun image(id: String = "captured") =
    ReferenceImage(id = id, name = "m3/text", mediaType = "image/png", base64 = "AAAA")

  private fun placed(componentId: String?): UiBuilderEditorState =
    reducer.reduce(
      initial,
      UiBuilderEditorEvent.PlaceReferencePiece(image(), componentId = componentId),
    )

  @Test
  fun `a captured piece remembers the component it is a picture of`() {
    val state = placed("m3/text")
    assertEquals("m3/text", state.reference.pieces.single().componentId)
  }

  @Test
  fun `an imported piece remembers nothing, because it is nothing in particular`() {
    val state = placed(null)
    assertNull(state.reference.pieces.single().componentId)
  }

  @Test
  fun `promoting builds the component and takes the picture away`() {
    val state = placed("m3/text")
    val piece = state.reference.pieces.single()
    val target = reducer.dropTarget(state, "m3/text")
    assertNotNull(target)
    val promoted =
      reducer.reduce(state, UiBuilderEditorEvent.PromoteReferencePiece(piece.id, target))
    assertTrue(promoted.document.nodes.size > document.nodes.size)
    val inserted = promoted.document.nodes.values.first { it.id !in document.nodes }
    assertEquals("m3/text", inserted.componentId)
    assertTrue(
      promoted.document.nodes.getValue(target.nodeId).slots[target.slot]!!.contains(inserted.id)
    )
    // The picture is gone: the real thing is standing where it was.
    assertTrue(promoted.reference.pieces.isEmpty())
    assertNull(promoted.reference.selectedPieceId)
  }

  @Test
  fun `promoting is a real document operation, so it submits and undoes`() {
    val state = placed("m3/text")
    val piece = state.reference.pieces.single()
    val target = requireNotNull(reducer.dropTarget(state, "m3/text"))
    val promoted =
      reducer.reduce(state, UiBuilderEditorEvent.PromoteReferencePiece(piece.id, target))
    assertNotNull(reducer.acceptedSubmission(state, promoted))
    assertTrue(promoted.canUndo)
  }

  @Test
  fun `a piece with no provenance is refused rather than guessed at`() {
    val state = placed(null)
    val piece = state.reference.pieces.single()
    val target = requireNotNull(reducer.dropTarget(state, "m3/text"))
    val refused =
      reducer.reduce(state, UiBuilderEditorEvent.PromoteReferencePiece(piece.id, target))
    assertEquals(document, refused.document)
    // And the picture survives the refusal, so nothing is lost by trying.
    assertEquals(1, refused.reference.pieces.size)
  }

  @Test
  fun `a slot that does not accept the component is refused`() {
    val state = placed("m3/text")
    val piece = state.reference.pieces.single()
    val refused =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.PromoteReferencePiece(
          piece.id,
          ParentSlot("no-such-node", "children"),
        ),
      )
    assertEquals(document, refused.document)
    assertEquals(1, refused.reference.pieces.size)
  }

  @Test
  fun `the deepest accepting slot under the point wins`() {
    // Two real slots of the fixture, both of which accept a text node, drawn as if nested: the
    // grid covers the pane and the row sits inside it. The smaller is the more specific answer.
    val outer = slot("main-content", "children", 0f, 0f, 400f, 400f)
    val inner = slot("discover-grid", "items", 20f, 20f, 100f, 100f)
    val target =
      reducer.promotionTarget(
        state = initial,
        componentId = "m3/text",
        slots = listOf(outer, inner),
        pointX = 50f,
        pointY = 50f,
      )
    assertEquals(ParentSlot("discover-grid", "items"), target)
  }

  @Test
  fun `a point outside every slot falls through to the selection`() {
    val target =
      reducer.promotionTarget(
        state = initial,
        componentId = "m3/text",
        slots = listOf(slot("main-content", "children", 0f, 0f, 40f, 40f)),
        pointX = 500f,
        pointY = 500f,
      )
    assertEquals(reducer.dropTarget(initial, "m3/text"), target)
  }

  @Test
  fun `a point over nothing falls back to the selection's own slot`() {
    val target =
      reducer.promotionTarget(
        state = initial,
        componentId = "m3/text",
        slots = emptyList(),
        pointX = 10f,
        pointY = 10f,
      )
    assertEquals(reducer.dropTarget(initial, "m3/text"), target)
  }

  @Test
  fun `a slot no node in this document declares is not a hit`() {
    val target =
      reducer.promotionTarget(
        state = initial,
        componentId = "m3/text",
        slots = listOf(slot("no-such-node", "children", 0f, 0f, 100f, 100f)),
        pointX = 5f,
        pointY = 5f,
      )
    // Falls through to the selection rather than naming a parent that does not exist.
    assertEquals(reducer.dropTarget(initial, "m3/text"), target)
    assertFalse(target == ParentSlot("no-such-node", "children"))
  }

  private fun slot(
    parentNodeId: String,
    slotName: String,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
  ) =
    UiBuilderSlotInspection(
      parentNodeId = parentNodeId,
      slotName = slotName,
      childNodeIds = emptyList(),
      measuredChildNodeIds = emptyList(),
      bounds = UiBuilderPixelBounds(x, y, width, height),
    )

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
