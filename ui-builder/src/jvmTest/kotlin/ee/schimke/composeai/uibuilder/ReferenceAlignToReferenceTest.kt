package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorSubmission
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import ee.schimke.composeai.uibuilder.reference.movedModifierChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The crossing a match makes back into the design: one edit that moves, sizes and re-types a layer
 * — and, unlike everything else the reference does, that *is* a document change, so it submits and
 * undoes as one step.
 */
class ReferenceAlignToReferenceTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val textId = "podcast-card-android-title"
  private val initial = reducer.initial(document, selectedNodeId = textId)

  private fun padding(chain: List<JsonElement>): JsonObject? =
    chain.map { it.jsonObject }.firstOrNull { it["type"]?.jsonPrimitive?.content == "padding" }

  private fun JsonObject.edge(name: String) = getValue(name).jsonPrimitive.double

  @Test
  fun `a move, a resize of the type and nothing else land as one undoable batch`() {
    val before = document.nodes.getValue(textId)
    val beforeStart = padding(before.modifiers)?.edge("startDp") ?: 0.0
    val aligned =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.AlignNodeToReference(
          textId,
          moveXDp = 6,
          moveYDp = 2,
          fontSizeSp = 18f,
        ),
      )
    val node = aligned.document.nodes.getValue(textId)
    val pad = assertNotNull(padding(node.modifiers))
    assertEquals(beforeStart + 6, pad.edge("startDp"))
    assertEquals(
      18.0,
      node.properties.getValue("fontSizeSp").let {
        (it as? JsonObject)?.get("value")?.jsonPrimitive?.double ?: it.jsonPrimitive.double
      },
    )
    // One submission carrying both operations, so it is one undo step.
    val submission = assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(initial, aligned))
    assertEquals(2, submission.command.operations.size)
    assertTrue(aligned.canUndo)
  }

  @Test
  fun `the reference itself is untouched by applying a match`() {
    val withReference =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.AttachReference(
          ReferenceImage("mock", "mock@2x.png", "image/png", "AAAA", 720, 1600)
        ),
      )
    val aligned =
      reducer.reduce(withReference, UiBuilderEditorEvent.AlignNodeToReference(textId, moveXDp = 4))
    assertEquals(withReference.reference, aligned.reference)
  }

  @Test
  fun `a type size the catalog cannot hold is refused whole`() {
    // A layout column declares no fontSizeSp, so nothing of the batch lands — not even the move.
    val columnId = document.nodes.values.first { it.componentId == "layout/column" }.id
    val refused =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = columnId),
        UiBuilderEditorEvent.AlignNodeToReference(columnId, moveXDp = 4, fontSizeSp = 20f),
      )
    assertEquals(document, refused.document)
  }

  @Test
  fun `moving right grows the start and gives back the end`() {
    val chain =
      movedModifierChain(listOf(pad(8, 0, 8, 0)), dx = 4, dy = 0, declared = setOf("padding"))
    val moved = assertNotNull(padding(assertNotNull(chain)))
    assertEquals(12.0, moved.edge("startDp"))
    assertEquals(4.0, moved.edge("endDp"))
  }

  @Test
  fun `moving left past the padding spills into an offset`() {
    val chain =
      movedModifierChain(
        listOf(pad(3, 0, 0, 0)),
        dx = -5,
        dy = 0,
        declared = setOf("padding", "offset"),
      )!!
    val moved = assertNotNull(padding(chain))
    assertEquals(0.0, moved.edge("startDp"))
    assertEquals(3.0, moved.edge("endDp"))
    val offset =
      chain.map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "offset" }
    assertEquals(-2.0, offset.edge("xDp"))
  }

  @Test
  fun `a new padding goes outermost, and moving back keeps the box the size it became`() {
    val size = buildJsonObject {
      put("type", "size")
      put("widthDp", 40)
      put("heightDp", 40)
    }
    val moved = movedModifierChain(listOf(size), dx = 0, dy = 6, declared = setOf("padding"))!!
    assertEquals("padding", moved.first().jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals(size, moved.last())
    // Up again: the top gives its 6 dp to the bottom, so the neighbours below do not jump.
    val back = padding(movedModifierChain(moved, dx = 0, dy = -6, declared = setOf("padding"))!!)!!
    assertEquals(0.0, back.edge("topDp"))
    assertEquals(6.0, back.edge("bottomDp"))
  }

  @Test
  fun `a move spends the trailing edge first, and a move back hands it over again`() {
    val chain =
      movedModifierChain(listOf(pad(0, 0, 4, 0)), dx = 4, dy = 0, declared = setOf("padding"))!!
    val moved = padding(chain)!!
    assertEquals(4.0, moved.edge("startDp"))
    assertEquals(0.0, moved.edge("endDp"))
    val gone =
      movedModifierChain(
        listOf(pad(2, 0, 0, 0)),
        dx = -2,
        dy = 0,
        declared = setOf("padding"),
      )!!
    // Start gives its 2 dp to the end, so the padding is still there — on the other side.
    assertEquals(2.0, padding(gone)!!.edge("endDp"))
  }

  @Test
  fun `a component that can neither pad nor offset cannot be moved`() {
    assertNull(movedModifierChain(emptyList(), dx = 1, dy = 0, declared = setOf("size")))
    assertTrue(movedModifierChain(emptyList(), 0, 0, emptySet())!!.isEmpty())
  }

  private fun pad(start: Int, top: Int, end: Int, bottom: Int) = buildJsonObject {
    put("type", "padding")
    put("startDp", start)
    put("topDp", top)
    put("endDp", end)
    put("bottomDp", bottom)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
