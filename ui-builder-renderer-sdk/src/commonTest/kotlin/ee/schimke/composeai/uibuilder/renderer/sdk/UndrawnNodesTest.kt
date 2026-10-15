package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceV2
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A canvas that draws a diagnostic or a stand-in says so: the runtime's `rendered` reply lists the
 * node, the editor's host records it, and a runtime that drew everything is told apart from one
 * that does not report.
 */
class UndrawnNodesTest {
  @AfterTest fun reset() = UiBuilderUndrawnNodes.clear()

  @Test
  fun `a rendered reply lists what the runtime did not draw, and the host records it`() {
    val document = document()
    val key = undrawnGenerationKey(document.id, document.revision)
    val canvas = UiBuilderUndrawnNode("canvas", "draw/canvas", UiBuilderUndrawnNode.UNSUPPORTED)
    UiBuilderUndrawnNodes.record(key, canvas)

    val (endpoint, host) = session()
    val render = render(endpoint, host, document)
    val reply = endpoint.rendered(render.requestId, snapshot(document))
    assertEquals(listOf(canvas), undrawnNodesOf(reply))

    // The host runs in another process: it learns of the node only from the reply.
    UiBuilderUndrawnNodes.clear()
    assertNotNull(host.accept("null", true, endpoint.encode(reply)))
    assertEquals(listOf(canvas), UiBuilderUndrawnNodes.forGeneration(key))
  }

  @Test
  fun `a runtime that drew everything reports an empty list, not nothing`() {
    val document = document()
    val (endpoint, host) = session()
    val render = render(endpoint, host, document)

    val reply = endpoint.rendered(render.requestId, snapshot(document))

    assertEquals(emptyList(), undrawnNodesOf(reply))
    val older = reply.copy(payload = JsonObject(reply.payload - UNDRAWN_PAYLOAD_KEY))
    assertNull(undrawnNodesOf(older), "a runtime predating the key has not said anything")
  }

  @Test
  fun `recording is idempotent, so recomposition does not churn the version`() {
    val key = undrawnGenerationKey("design", 1)
    val node = UiBuilderUndrawnNode("a", "x/y", UiBuilderUndrawnNode.STAND_IN)
    UiBuilderUndrawnNodes.record(key, node)
    val version = UiBuilderUndrawnNodes.version

    UiBuilderUndrawnNodes.record(key, node)

    assertEquals(version, UiBuilderUndrawnNodes.version)
    assertEquals(
      emptyList(),
      UiBuilderUndrawnNodes.forGeneration(undrawnGenerationKey("design", 2)),
    )
  }

  @Test
  fun `a runtime's later list replaces its earlier one, so a node drawn again is not reported`() {
    val document = document()
    val key = undrawnGenerationKey(document.id, document.revision)
    val (endpoint, host) = session()
    val render = render(endpoint, host, document)
    UiBuilderUndrawnNodes.record(
      key,
      UiBuilderUndrawnNode("canvas", "draw/canvas", UiBuilderUndrawnNode.UNSUPPORTED),
    )
    val first = endpoint.rendered(render.requestId, snapshot(document))
    UiBuilderUndrawnNodes.clear()
    host.accept("null", true, endpoint.encode(first))
    assertEquals(1, UiBuilderUndrawnNodes.forGeneration(key).size)

    // The runtime's state changed and the canvas drew everything; its next update says so.
    val update =
      first.copy(
        type = INSPECTION_UPDATED,
        payload =
          JsonObject(
            first.payload +
              (UNDRAWN_PAYLOAD_KEY to kotlinx.serialization.json.JsonArray(emptyList()))
          ),
      )
    assertNotNull(host.accept("null", true, endpoint.encode(update)))
    assertEquals(emptyList(), UiBuilderUndrawnNodes.forGeneration(key))
  }

  @Test
  fun `a forgotten node is no longer reported`() {
    val key = undrawnGenerationKey("design", 1)
    UiBuilderUndrawnNodes.record(
      key,
      UiBuilderUndrawnNode("a", "x/y", UiBuilderUndrawnNode.STAND_IN),
    )

    UiBuilderUndrawnNodes.forget(key, "a")

    assertEquals(emptyList(), UiBuilderUndrawnNodes.forGeneration(key))
  }

  @Test
  fun `the listener hears a generation's whole list`() {
    val heard = mutableListOf<Pair<String, List<UiBuilderUndrawnNode>>>()
    UiBuilderUndrawnNodes.listener = { key, nodes -> heard += key to nodes }
    try {
      val node = UiBuilderUndrawnNode("a", "x/y", UiBuilderUndrawnNode.UNSUPPORTED)
      UiBuilderUndrawnNodes.record("design@1", node)
      assertEquals(listOf("design@1" to listOf(node)), heard)
    } finally {
      UiBuilderUndrawnNodes.listener = null
    }
  }

  private fun session(): Pair<CatalogRuntimeProtocolEndpoint, CatalogRuntimeHostSession> {
    val host = CatalogRuntimeHostSession(RUNTIME)
    val endpoint = CatalogRuntimeProtocolEndpoint(RUNTIME)
    endpoint.receive(ORIGIN, true, host.request("initialize", "initialize"))
    return endpoint to host
  }

  private fun render(
    endpoint: CatalogRuntimeProtocolEndpoint,
    host: CatalogRuntimeHostSession,
    document: UiBuilderDocument,
  ): CatalogRuntimeCommand.Render =
    assertIs(
      endpoint.receive(
        ORIGIN,
        true,
        host.request(
          "render-1",
          "renderDocument",
          buildJsonObject {
            put(
              "document",
              RUNTIME_PROTOCOL_JSON.encodeToJsonElement(UiBuilderDocument.serializer(), document),
            )
            put(
              "surface",
              RUNTIME_PROTOCOL_JSON.encodeToJsonElement(
                UiBuilderRendererSurfaceV2.serializer(),
                UiBuilderRendererSurfaceV2(
                  mode = UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED,
                  widthDp = 192f,
                  heightDp = 354f,
                  density = 2f,
                  surfaceId = "editor",
                ),
              ),
            )
          },
        ),
      )
    )

  private fun document() =
    UiBuilderDocument(
      "compose-ui-builder-document/v1",
      "design",
      "Design",
      1,
      buildJsonObject { put("nativeRuntimeId", RUNTIME) },
      JsonObject(emptyMap()),
      JsonObject(emptyMap()),
      listOf("root"),
      mapOf("root" to UiBuilderNode("root", "layout/box")),
    )

  private fun snapshot(document: UiBuilderDocument): UiBuilderInspectionSnapshot {
    val collector = UiBuilderInspectionCollector(document)
    collector.recordNodeBounds("root", 0f, 0f, 100f, 100f)
    return collector.snapshot()
  }

  private companion object {
    const val RUNTIME = "m3-2026.09"
    const val ORIGIN = "https://preview.example"
  }
}
