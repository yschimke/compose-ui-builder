package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class CatalogRuntimeProtocolTest {
  @Test
  fun `correlates render and semantic action with exact document`() {
    val endpoint = initializedEndpoint()
    val host = CatalogRuntimeHostSession(RUNTIME)
    val render = requestRender(endpoint, host, "render-1", document())
    val snapshot = snapshot(document())
    assertEquals("rendered", endpoint.rendered(render.requestId, snapshot).type)
    val command =
      assertIs<CatalogRuntimeCommand.DispatchAction>(
        endpoint.receive(
          ORIGIN,
          true,
          host.request("action-1", "dispatchAction", action("design", 1, "root", "activate")),
        )
      )
    assertEquals("root", command.action.nodeId)
    val response = endpoint.actionDispatched(command.requestId, snapshot)
    assertEquals("actionDispatched", host.accept("null", true, endpoint.encode(response))?.type)
  }

  @Test
  fun `late render cannot complete a newer request`() {
    val endpoint = initializedEndpoint()
    val host = CatalogRuntimeHostSession(RUNTIME)
    val first = requestRender(endpoint, host, "render-1", document(1))
    val second = requestRender(endpoint, host, "render-2", document(2))
    assertEquals(
      "STALE_RENDER_COMPLETION",
      endpoint.rendered(first.requestId, snapshot(document(1))).code(),
    )
    assertEquals("rendered", endpoint.rendered(second.requestId, snapshot(document(2))).type)
  }

  @Test
  fun `action cannot return a newer inspection revision`() {
    val endpoint = initializedEndpoint()
    val host = CatalogRuntimeHostSession(RUNTIME)
    val first = requestRender(endpoint, host, "render-1", document(1))
    endpoint.rendered(first.requestId, snapshot(document(1)))
    val command =
      assertIs<CatalogRuntimeCommand.DispatchAction>(
        endpoint.receive(
          ORIGIN,
          true,
          host.request("action-1", "dispatchAction", action("design", 1, "root", "activate")),
        )
      )
    requestRender(endpoint, host, "render-2", document(2))
    assertEquals(
      "STALE_ACTION_COMPLETION",
      endpoint.actionDispatched(command.requestId, snapshot(document(2))).code(),
    )
  }

  @Test
  fun `rejects malformed stale horizontal and unknown actions`() {
    val endpoint = initializedEndpoint()
    val host = CatalogRuntimeHostSession(RUNTIME)
    val render = requestRender(endpoint, host, "render-1", document())
    endpoint.rendered(render.requestId, snapshot(document()))
    fun error(id: String, payload: JsonObject) =
      assertIs<CatalogRuntimeCommand.Reply>(
          endpoint.receive(
            ORIGIN,
            true,
            if (id == "bad") encodedRequest(id, "dispatchAction", payload)
            else host.request(id, "dispatchAction", payload),
          )
        )
        .message
        .code()
    assertEquals("INVALID_ACTION", error("bad", buildJsonObject { put("kind", "activate") }))
    assertEquals("STALE_DOCUMENT", error("stale", action("design", 0, "root", "activate")))
    assertEquals("UNSUPPORTED_ACTION", error("unknown", action("design", 1, "root", "focus")))
    assertEquals(
      "INVALID_ACTION",
      error("horizontal", action("design", 1, "root", "scrollBy", 4.0, 20.0)),
    )
  }

  @Test
  fun `rejects wrong source origin runtime protocol and duplicate`() {
    val endpoint = CatalogRuntimeProtocolEndpoint(RUNTIME)
    val host = CatalogRuntimeHostSession(RUNTIME)
    val initialize = host.request("initialize", "initialize")
    assertNull(endpoint.receive(ORIGIN, false, initialize))
    endpoint.receive(ORIGIN, true, initialize)
    assertNull(endpoint.receive("https://attacker.example", true, initialize))
    val duplicate =
      assertIs<CatalogRuntimeCommand.Reply>(endpoint.receive(ORIGIN, true, initialize))
    assertEquals("DUPLICATE_REQUEST", duplicate.message.code())
    val wrong = CatalogRuntimeProtocolEndpoint("wrong")
    assertEquals(
      "PROTOCOL_MISMATCH",
      assertIs<CatalogRuntimeCommand.Reply>(wrong.receive(ORIGIN, true, initialize)).message.code(),
    )
  }

  @Test
  fun `host validates inspection before consuming correlation`() {
    val host = CatalogRuntimeHostSession(RUNTIME)
    host.request(
      "render-1",
      "renderDocument",
      buildJsonObject { put("document", documentElement(document())) },
    )
    assertNull(
      host.accept(
        "null",
        true,
        encode(response("render-1", snapshot(document()).copy(nodes = emptyList()))),
      )
    )
    assertNull(
      host.accept(
        "null",
        true,
        encode(response("render-1", snapshot(document()).copy(documentId = "other"))),
      )
    )
    val malformed =
      snapshot(document()).let { value ->
        value.copy(
          nodes = value.nodes.map { it.copy(bounds = UiBuilderPixelBounds(2_000_000f, 0f, 1f, 1f)) }
        )
      }
    assertNull(host.accept("null", true, encode(response("render-1", malformed))))
    val oversized =
      snapshot(document()).let { value ->
        value.copy(
          generation = value.generation.copy(expectedAuthoredNodeIds = List(10_001) { "node-$it" })
        )
      }
    assertNull(host.accept("null", true, encode(response("render-1", oversized))))
    assertEquals(
      "rendered",
      host.accept("null", true, encode(response("render-1", snapshot(document()))))?.type,
    )
    assertNull(host.accept("null", true, encode(response("render-1", snapshot(document())))))
  }

  @Test
  fun `host rejects empty action completion without consuming request`() {
    val host = CatalogRuntimeHostSession(RUNTIME)
    host.request("action-1", "dispatchAction", action("design", 1, "root", "activate"))
    val empty =
      CatalogRuntimeMessage(
        protocolVersion = 1,
        runtimeId = RUNTIME,
        requestId = "action-1",
        type = "actionDispatched",
      )
    assertNull(host.accept("null", true, encode(empty)))
    val valid = response("action-1", snapshot(document()), "actionDispatched")
    assertEquals("actionDispatched", host.accept("null", true, encode(valid))?.type)
  }

  @Test
  fun `host rejects wrong source origin identity protocol and correlation`() {
    val host = CatalogRuntimeHostSession(RUNTIME)
    host.request("action-1", "dispatchAction", action("design", 1, "root", "activate"))
    val valid = response("action-1", snapshot(document()), "actionDispatched")
    assertNull(host.accept("null", false, encode(valid)))
    assertNull(host.accept("https://attacker.example", true, encode(valid)))
    assertNull(host.accept("null", true, encode(valid.copy(runtimeId = "wrong"))))
    assertNull(host.accept("null", true, encode(valid.copy(protocolVersion = 2))))
    assertNull(host.accept("null", true, encode(valid.copy(requestId = "not-pending"))))
    assertEquals("actionDispatched", host.accept("null", true, encode(valid))?.type)
  }

  private fun initializedEndpoint() =
    CatalogRuntimeProtocolEndpoint(RUNTIME).also { endpoint ->
      endpoint.receive(
        ORIGIN,
        true,
        CatalogRuntimeHostSession(RUNTIME).request("initialize", "initialize"),
      )
    }

  private fun requestRender(
    endpoint: CatalogRuntimeProtocolEndpoint,
    host: CatalogRuntimeHostSession,
    requestId: String,
    document: UiBuilderDocument,
  ): CatalogRuntimeCommand.Render =
    assertIs(
      endpoint.receive(
        ORIGIN,
        true,
        host.request(
          requestId,
          "renderDocument",
          buildJsonObject { put("document", documentElement(document)) },
        ),
      )
    )

  private fun document(revision: Int = 1) =
    UiBuilderDocument(
      "compose-ui-builder-document/v1",
      "design",
      "Design",
      revision,
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

  private fun action(
    documentId: String,
    revision: Int,
    nodeId: String,
    kind: String,
    deltaX: Double? = null,
    deltaY: Double? = null,
  ) =
    RUNTIME_PROTOCOL_JSON.encodeToJsonElement(
        CatalogRuntimeAction.serializer(),
        CatalogRuntimeAction(documentId, revision, nodeId, kind, deltaX, deltaY),
      )
      .jsonObject

  private fun documentElement(document: UiBuilderDocument) =
    RUNTIME_PROTOCOL_JSON.encodeToJsonElement(UiBuilderDocument.serializer(), document)

  private fun response(
    requestId: String,
    snapshot: UiBuilderInspectionSnapshot,
    type: String = "rendered",
  ) =
    CatalogRuntimeMessage(
      protocolVersion = 1,
      runtimeId = RUNTIME,
      requestId = requestId,
      type = type,
      payload =
        buildJsonObject {
          put(
            "inspection",
            RUNTIME_PROTOCOL_JSON.encodeToJsonElement(
              UiBuilderInspectionSnapshot.serializer(),
              snapshot,
            ),
          )
        },
    )

  private fun encode(message: CatalogRuntimeMessage) =
    RUNTIME_PROTOCOL_JSON.encodeToString(CatalogRuntimeMessage.serializer(), message)

  private fun encodedRequest(requestId: String, type: String, payload: JsonObject) =
    encode(
      CatalogRuntimeMessage(
        protocolVersion = 1,
        runtimeId = RUNTIME,
        requestId = requestId,
        type = type,
        payload = payload,
      )
    )

  private fun CatalogRuntimeMessage.code() = payload.getValue("code").toString().trim('"')

  private companion object {
    const val RUNTIME = "m3-2026.09"
    const val ORIGIN = "https://preview.example"
  }
}
