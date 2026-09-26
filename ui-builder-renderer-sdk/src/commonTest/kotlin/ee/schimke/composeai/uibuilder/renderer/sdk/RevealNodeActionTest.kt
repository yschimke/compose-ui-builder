package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `revealNode`: the editor's selection, scrolled into a runtime's device frame.
 *
 * The editor's device view draws the design at its frame, where a list's later rows are scrolled
 * away and — lazily — not composed at all. Selecting one from the layers panel has to bring it on
 * screen, and the runtime is the only thing that can scroll its own lists. So the action names the
 * node and nothing else: the runtime walks the document for the containers holding it and the index
 * of each, because the node itself has no box and no entry to be found by.
 */
class RevealNodeActionTest {
  @Test
  fun `a runtime announces that it reveals nodes, and what else its catalog declared`() {
    val endpoint = CatalogRuntimeProtocolEndpoint(RUNTIME, capabilities = setOf("horizontalUnroll"))
    val reply =
      assertIs<CatalogRuntimeCommand.Reply>(
        endpoint.receive(ORIGIN, true, host().request("initialize", "initialize"))
      )

    assertEquals(
      listOf("horizontalUnroll", "revealNode"),
      reply.message.payload.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content },
    )
    // What an editor that predates the list already reads, unchanged.
    assertEquals(
      "semantic-actions",
      reply.message.payload.getValue("interaction").jsonPrimitive.content,
    )
  }

  @Test
  fun `revealNode is accepted as an action naming only a node`() {
    val endpoint = CatalogRuntimeProtocolEndpoint(RUNTIME)
    val host = host()
    endpoint.receive(ORIGIN, true, host.request("initialize", "initialize"))
    val render =
      assertIs<CatalogRuntimeCommand.Render>(
        endpoint.receive(ORIGIN, true, host.request("render", "renderDocument", render(list())))
      )
    endpoint.rendered(render.requestId, UiBuilderInspectionCollector(list()).snapshot())

    val command =
      endpoint.receive(ORIGIN, true, host.request("reveal", "dispatchAction", reveal("row-9")))

    assertEquals("row-9", assertIs<CatalogRuntimeCommand.DispatchAction>(command).action.nodeId)
  }

  @Test
  fun `a reveal scrolls every composed container holding the node, outermost first`() {
    val document = nested()
    val scrolled = mutableListOf<Pair<String, Int>>()
    val controller = UiBuilderSemanticActionController()
    controller.install(
      mapOf(
        "outer" to UiBuilderSemanticActionEntry(scrollToItem = { scrolled += "outer" to it }),
        "inner" to UiBuilderSemanticActionEntry(scrollToItem = { scrolled += "inner" to it }),
      ),
      UiBuilderPixelBounds(0f, 0f, 100f, 100f),
      document.itemAncestryIndex(),
    )

    val result =
      controller.dispatch(
        CatalogRuntimeAction(document.id, document.revision, "chip-3", REVEAL_NODE_ACTION),
        // Never measured: the whole point is that the node is not on screen.
        UiBuilderInspectionCollector(document).snapshot(),
      )

    assertEquals(UiBuilderSemanticActionResult.Applied, result)
    assertEquals(listOf("outer" to 4, "inner" to 2), scrolled)
  }

  @Test
  fun `a reveal with no container able to scroll says so rather than pretending`() {
    val document = nested()
    val controller = UiBuilderSemanticActionController()
    controller.install(
      emptyMap(),
      UiBuilderPixelBounds(0f, 0f, 100f, 100f),
      document.itemAncestryIndex(),
    )

    val result =
      controller.dispatch(
        CatalogRuntimeAction(document.id, document.revision, "chip-3", REVEAL_NODE_ACTION),
        UiBuilderInspectionCollector(document).snapshot(),
      )

    assertEquals(
      "ACTION_NOT_AVAILABLE",
      assertIs<UiBuilderSemanticActionResult.Rejected>(result).code,
    )
  }

  @Test
  fun `a document marked for a sideways unroll reads as one`() {
    val document = list()
    assertTrue(!document.unrolledHorizontally)
    val marked =
      document.copy(
        environment =
          JsonObject(
            document.environment + (UI_BUILDER_UNROLLED_AXIS_KEY to JsonPrimitive("horizontal"))
          )
      )
    assertTrue(marked.unrolledHorizontally)
  }

  private fun host() = CatalogRuntimeHostSession(RUNTIME)

  private fun reveal(nodeId: String) =
    RUNTIME_PROTOCOL_JSON.encodeToJsonElement(
        CatalogRuntimeAction.serializer(),
        CatalogRuntimeAction("list", 1, nodeId, REVEAL_NODE_ACTION),
      )
      .jsonObject

  private fun render(document: UiBuilderDocument) = buildJsonObject {
    put(
      "document",
      RUNTIME_PROTOCOL_JSON.encodeToJsonElement(UiBuilderDocument.serializer(), document),
    )
    put(
      "surface",
      RUNTIME_PROTOCOL_JSON.encodeToJsonElement(
        UiBuilderRendererSurfaceV2.serializer(),
        UiBuilderRendererSurfaceV2(UiBuilderRendererSurfaceModeV2.DEVICE, 320f, 320f, 1f, "frame"),
      ),
    )
  }

  /** A list of ten rows. */
  private fun list(): UiBuilderDocument {
    val rows = (0 until 10).map { "row-$it" }
    return document(
      "list",
      listOf("list"),
      mapOf("list" to UiBuilderNode("list", "layout/lazy-column", slots = mapOf("items" to rows))) +
        rows.associateWith { UiBuilderNode(it, "m3/text") },
    )
  }

  /** An outer list whose fifth item is a row of chips; `chip-3` is the third chip. */
  private fun nested(): UiBuilderDocument {
    val items = (0 until 4).map { "item-$it" } + "inner"
    val chips = (1..3).map { "chip-$it" }
    return document(
      "nested",
      listOf("outer"),
      mapOf(
        "outer" to UiBuilderNode("outer", "layout/lazy-column", slots = mapOf("items" to items)),
        "inner" to UiBuilderNode("inner", "layout/lazy-row", slots = mapOf("items" to chips)),
      ) + (items - "inner" + chips).associateWith { UiBuilderNode(it, "m3/text") },
    )
  }

  private fun document(id: String, roots: List<String>, nodes: Map<String, UiBuilderNode>) =
    UiBuilderDocument(
      "compose-ui-builder-document/v1",
      id,
      id,
      1,
      buildJsonObject { put("nativeRuntimeId", RUNTIME) },
      JsonObject(emptyMap()),
      JsonObject(emptyMap()),
      roots,
      nodes,
    )

  private companion object {
    const val RUNTIME = "m3-2026.09"
    const val ORIGIN = "https://preview.example"
  }
}
