package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The reference overlay is editor state, and this is the test that says so: every case here also
 * asserts that the document did not move. A reference that reached the document would be exported,
 * replayed and pushed to every collaborator, which is the one thing it must never be.
 */
class ReferenceOverlayStateTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val initial = reducer.initial(document)

  private fun raster(id: String = "raster") =
    ReferenceImage(id = id, name = "Mock", mediaType = "image/png", base64 = "AAAA")

  private fun vector(svg: String) =
    ReferenceImage(
      id = "vector",
      name = "Frames",
      mediaType = ReferenceImage.SVG_MEDIA_TYPE,
      base64 = Base64.Default.encode(svg.encodeToByteArray()),
    )

  @Test
  fun `attaching a reference never touches the document`() {
    val attached = reducer.reduce(initial, UiBuilderEditorEvent.AttachReference(raster()))
    assertEquals(document, attached.document)
    assertEquals(initial.operationSequence, attached.operationSequence)
    assertNull(reducer.acceptedSubmission(initial, attached))
    assertTrue(attached.reference.attached)
  }

  @Test
  fun `an attached SVG carries its own layout boxes`() {
    val attached =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.AttachReference(
          vector(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
                 <rect data-name="Card" width="8" height="4"/>
               </svg>"""
          )
        ),
      )
    assertEquals(listOf("Card"), attached.reference.layoutBoxes.map { it.name })
    assertTrue(ReferenceDiffMode.Boxes in attached.reference.availableModes)
  }

  @Test
  fun `a raster attachment does not offer a mode with nothing to draw`() {
    val attached = reducer.reduce(initial, UiBuilderEditorEvent.AttachReference(raster()))
    assertFalse(ReferenceDiffMode.Boxes in attached.reference.availableModes)
  }

  @Test
  fun `toggling hides the overlay and keeps the picture and the mode`() {
    val attached =
      reducer.reduce(initial, UiBuilderEditorEvent.AttachReference(raster())).let {
        reducer.reduce(
          it,
          UiBuilderEditorEvent.UpdateReferenceSettings(
            it.reference.settings.copy(mode = ReferenceDiffMode.Difference)
          ),
        )
      }
    val hidden = reducer.reduce(attached, UiBuilderEditorEvent.ToggleReference)
    assertFalse(hidden.reference.settings.visible)
    assertTrue(hidden.reference.attached)
    assertEquals(ReferenceDiffMode.Difference, hidden.reference.settings.mode)
    assertFalse(hidden.reference.drawing)
    assertTrue(reducer.reduce(hidden, UiBuilderEditorEvent.ToggleReference).reference.drawing)
  }

  @Test
  fun `settings are clamped rather than trusted`() {
    val attached = reducer.reduce(initial, UiBuilderEditorEvent.AttachReference(raster()))
    val absurd =
      reducer.reduce(
        attached,
        UiBuilderEditorEvent.UpdateReferenceSettings(
          attached.reference.settings.copy(
            opacityPercent = 4000,
            scalePercent = -10,
            offsetXDp = Float.NaN,
          )
        ),
      )
    assertEquals(100, absurd.reference.settings.opacityPercent)
    assertEquals(
      ReferenceOverlaySettings.MIN_SCALE_PERCENT,
      absurd.reference.settings.scalePercent,
    )
    assertEquals(0f, absurd.reference.settings.offsetXDp)
  }

  @Test
  fun `every mark is individually removable and each gets its own id`() {
    var state = reducer.reduce(initial, UiBuilderEditorEvent.AttachReference(raster()))
    val points = listOf(0.1f, 0.1f, 0.2f, 0.2f)
    state =
      reducer.reduce(state, UiBuilderEditorEvent.AddReferenceMark(ReferenceMarkupKind.Pen, points))
    state =
      reducer.reduce(state, UiBuilderEditorEvent.AddReferenceMark(ReferenceMarkupKind.Pen, points))
    assertEquals(2, state.reference.marks.size)
    // Identical strokes, distinct marks: rubbing one out must not take the other with it.
    assertEquals(2, state.reference.marks.map { it.id }.toSet().size)
    val first = state.reference.marks.first().id
    val removed = reducer.reduce(state, UiBuilderEditorEvent.RemoveReferenceMark(first))
    assertEquals(1, removed.reference.marks.size)
    assertTrue(removed.reference.marks.none { it.id == first })
    assertEquals(document, removed.document)
  }

  @Test
  fun `only the kinds that draw words carry the label`() {
    var state = reducer.reduce(initial, UiBuilderEditorEvent.SetMarkupText("Too tight"))
    val points = listOf(0f, 0f, 0.5f, 0.5f)
    state =
      reducer.reduce(state, UiBuilderEditorEvent.AddReferenceMark(ReferenceMarkupKind.Text, points))
    state =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.AddReferenceMark(ReferenceMarkupKind.Rectangle, points),
      )
    assertEquals("Too tight", state.reference.marks.first().text)
    assertNull(state.reference.marks.last().text)
  }

  @Test
  fun `a malformed stroke is dropped rather than stored undrawable`() {
    val state =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.AddReferenceMark(ReferenceMarkupKind.Pen, listOf(0.1f, 0.2f)),
      )
    assertTrue(state.reference.marks.isEmpty())
  }

  @Test
  fun `a placed piece lands in hand and can be moved and removed`() {
    val placed = reducer.reduce(initial, UiBuilderEditorEvent.PlaceReferencePiece(raster("piece")))
    val piece = placed.reference.pieces.single()
    assertEquals(piece.id, placed.reference.selectedPieceId)
    assertEquals(ReferenceTool.MovePiece, placed.reference.tool)
    val moved =
      reducer.reduce(placed, UiBuilderEditorEvent.MoveReferencePiece(piece.id, 0.1f, 0.2f))
    val movedPiece = moved.reference.pieces.single()
    assertEquals(piece.left + 0.1f, movedPiece.left, 1e-5f)
    assertEquals(piece.width, movedPiece.width, 1e-5f)
    val removed = reducer.reduce(moved, UiBuilderEditorEvent.RemoveReferencePiece(piece.id))
    assertTrue(removed.reference.pieces.isEmpty())
    assertNull(removed.reference.selectedPieceId)
    assertEquals(document, removed.document)
  }

  @Test
  fun `a piece dragged off the frame keeps a sliver in reach`() {
    val placed = reducer.reduce(initial, UiBuilderEditorEvent.PlaceReferencePiece(raster("piece")))
    val piece = placed.reference.pieces.single()
    val flung =
      reducer
        .reduce(placed, UiBuilderEditorEvent.MoveReferencePiece(piece.id, -50f, -50f))
        .reference
        .pieces
        .single()
    assertTrue(flung.right > 0f)
    assertTrue(flung.bottom > 0f)
  }

  @Test
  fun `scaling a piece keeps it centred where it was`() {
    val placed = reducer.reduce(initial, UiBuilderEditorEvent.PlaceReferencePiece(raster("piece")))
    val piece = placed.reference.pieces.single()
    val centreX = (piece.left + piece.right) / 2f
    val scaled =
      reducer
        .reduce(placed, UiBuilderEditorEvent.ScaleReferencePiece(piece.id, 2f))
        .reference
        .pieces
        .single()
    assertEquals(centreX, (scaled.left + scaled.right) / 2f, 1e-5f)
    assertTrue(scaled.width > piece.width)
  }

  @Test
  fun `flattening replaces the stack with the picture of it`() {
    var state = reducer.reduce(initial, UiBuilderEditorEvent.AttachReference(raster()))
    state = reducer.reduce(state, UiBuilderEditorEvent.PlaceReferencePiece(raster("piece")))
    state =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.AddReferenceMark(
          ReferenceMarkupKind.Arrow,
          listOf(0f, 0f, 1f, 1f),
        ),
      )
    val flattened =
      reducer.reduce(state, UiBuilderEditorEvent.FlattenReference(raster("flattened")))
    assertEquals("flattened", flattened.reference.image?.id)
    // The pieces and marks are in the picture now; keeping them would draw each of them twice.
    assertTrue(flattened.reference.pieces.isEmpty())
    assertTrue(flattened.reference.marks.isEmpty())
    assertEquals(ReferenceTool.None, flattened.reference.tool)
    assertEquals(document, flattened.document)
  }

  @Test
  fun `clearing takes the pieces and the marks with the picture`() {
    var state = reducer.reduce(initial, UiBuilderEditorEvent.AttachReference(raster()))
    state = reducer.reduce(state, UiBuilderEditorEvent.PlaceReferencePiece(raster("piece")))
    state =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.AddReferenceMark(ReferenceMarkupKind.Pen, listOf(0f, 0f, 1f, 1f)),
      )
    val cleared = reducer.reduce(state, UiBuilderEditorEvent.ClearReference)
    assertFalse(cleared.reference.hasContent)
    assertEquals(document, cleared.document)
    assertNull(reducer.acceptedSubmission(state, cleared))
  }

  @Test
  fun `a new picture keeps how you were working and forgets where the last one sat`() {
    var state = reducer.reduce(initial, UiBuilderEditorEvent.AttachReference(raster("first")))
    state =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.UpdateReferenceSettings(
          state.reference.settings.copy(mode = ReferenceDiffMode.Difference, offsetXDp = 24f)
        ),
      )
    val replaced = reducer.reduce(state, UiBuilderEditorEvent.AttachReference(raster("second")))
    assertEquals(ReferenceDiffMode.Difference, replaced.reference.settings.mode)
    assertEquals(0f, replaced.reference.settings.offsetXDp)
  }

  @Test
  fun `an erase mark is stored like any other and is undoable`() {
    var state = reducer.reduce(initial, UiBuilderEditorEvent.AttachReference(raster()))
    state = reducer.reduce(state, UiBuilderEditorEvent.SelectMarkupColor(0xFF112233))
    state =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.AddReferenceMark(ReferenceMarkupKind.Fill, listOf(0f, 0f, 0.5f, 0.5f)),
      )
    assertEquals(0xFF112233, state.reference.marks.single().colorArgb)
    assertEquals(ReferenceMarkupKind.Fill, state.reference.marks.single().kind)
    val undone = reducer.reduce(state, UiBuilderEditorEvent.UndoReferenceMark)
    assertTrue(undone.reference.marks.isEmpty())
  }

  @Test
  fun `a markup label is bounded`() {
    val state =
      reducer.reduce(initial, UiBuilderEditorEvent.SetMarkupText("x".repeat(MAX_MARKUP_TEXT * 2)))
    assertEquals(MAX_MARKUP_TEXT, state.reference.markupText.length)
  }

  @Test
  fun `a reference never becomes a submission`() {
    var state = reducer.reduce(initial, UiBuilderEditorEvent.AttachReference(raster()))
    state = reducer.reduce(state, UiBuilderEditorEvent.SelectReferenceTool(ReferenceTool.Pen))
    state =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.AddReferenceMark(ReferenceMarkupKind.Pen, listOf(0f, 0f, 1f, 1f)),
      )
    assertNull(reducer.acceptedSubmission(initial, state))
    assertNotNull(state.reference.image)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
