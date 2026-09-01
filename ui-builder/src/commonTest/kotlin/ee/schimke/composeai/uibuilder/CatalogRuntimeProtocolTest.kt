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
  fun `initializes renders and correlates measured response on locked origin`() {
    val endpoint = CatalogRuntimeProtocolEndpoint("m3-2026.09")
    val host = CatalogRuntimeHostSession("m3-2026.09")
    val initialize = host.request("request-1", "initialize")
    val initialized =
      assertIs<CatalogRuntimeCommand.Reply>(
          endpoint.receive("https://preview.example", true, initialize)
        )
        .message
    assertEquals(
      "initialized",
      host.accept("null", true, endpoint.encode(initialized))?.type,
    )

    val document = document()
    val render =
      host.request(
        "request-2",
        "renderDocument",
        buildJsonObject {
          put(
            "document",
            RUNTIME_PROTOCOL_JSON.encodeToJsonElement(UiBuilderDocument.serializer(), document),
          )
        },
      )
    val command =
      assertIs<CatalogRuntimeCommand.Render>(
        endpoint.receive("https://preview.example", true, render)
      )
    assertEquals(document, command.document)
    val response =
      endpoint.rendered(command.requestId, UiBuilderInspectionCollector(document).snapshot())
    assertEquals("rendered", host.accept("null", true, endpoint.encode(response))?.type)
    assertNull(host.accept("null", true, endpoint.encode(response)), "request id is single use")
  }

  @Test
  fun `rejects wrong source origin runtime and protocol`() {
    val endpoint = CatalogRuntimeProtocolEndpoint("m3-2026.09")
    val host = CatalogRuntimeHostSession("m3-2026.09")
    val initialize = host.request("request-1", "initialize")
    assertNull(endpoint.receive("https://preview.example", false, initialize))
    endpoint.receive("https://preview.example", true, initialize)
    assertNull(
      endpoint.receive("https://attacker.example", true, host.request("request-2", "dispatchInput"))
    )
    assertNull(host.accept("https://preview.example", true, "{}"))
    assertNull(host.accept("null", false, "{}"))

    val wrong = CatalogRuntimeProtocolEndpoint("another-runtime")
    val error =
      assertIs<CatalogRuntimeCommand.Reply>(
        wrong.receive("https://preview.example", true, initialize)
      )
    assertEquals("PROTOCOL_MISMATCH", error.message.payload.getValue("code").toString().trim('"'))
  }

  @Test
  fun `accepts correlated pointer and wheel input for the active revision`() {
    val endpoint = CatalogRuntimeProtocolEndpoint("m3-2026.09")
    val host = CatalogRuntimeHostSession("m3-2026.09")
    endpoint.receive("https://preview.example", true, host.request("request-1", "initialize"))
    render(endpoint, host, "request-2")

    val pointer =
      input(
        CatalogRuntimeInput(
          documentRevision = 1,
          kind = "pointer",
          phase = "down",
          x = 80.5,
          y = 40.25,
          pointerId = 1,
          button = 0,
          buttons = 1,
        )
      )
    val pointerCommand =
      assertIs<CatalogRuntimeCommand.DispatchInput>(
        endpoint.receive(
          "https://preview.example",
          true,
          host.request("request-3", "dispatchInput", pointer),
        )
      )
    assertEquals("pointer", pointerCommand.input.kind)
    val pointerResponse =
      endpoint.inputDispatched(
        pointerCommand.requestId,
        UiBuilderInspectionCollector(document()).snapshot(),
      )
    assertEquals(
      "inputDispatched",
      host.accept("null", true, endpoint.encode(pointerResponse))?.type,
    )

    val wheelCommand =
      assertIs<CatalogRuntimeCommand.DispatchInput>(
        endpoint.receive(
          "https://preview.example",
          true,
          host.request(
            "request-4",
            "dispatchInput",
            input(
              CatalogRuntimeInput(
                documentRevision = 1,
                kind = "wheel",
                x = 900.0,
                y = 400.0,
                deltaMode = 0,
                deltaX = 0.0,
                deltaY = 320.0,
              )
            ),
          ),
        )
      )
    assertEquals(320.0, wheelCommand.input.deltaY)
  }

  @Test
  fun `rejects input until the requested document has a measured render`() {
    val endpoint = CatalogRuntimeProtocolEndpoint("m3-2026.09")
    val host = CatalogRuntimeHostSession("m3-2026.09")
    endpoint.receive("https://preview.example", true, host.request("request-1", "initialize"))
    val command = requestRender(endpoint, host, "request-2")
    val input =
      input(
        CatalogRuntimeInput(
          documentRevision = 1,
          kind = "wheel",
          x = 1.0,
          y = 1.0,
          deltaMode = 0,
          deltaX = 0.0,
          deltaY = 1.0,
        )
      )
    val beforeRender =
      assertIs<CatalogRuntimeCommand.Reply>(
        endpoint.receive(
          "https://preview.example",
          true,
          host.request("request-3", "dispatchInput", input),
        )
      )
    assertEquals("NO_DOCUMENT", beforeRender.message.payload["code"].toString().trim('"'))

    endpoint.rendered(command.requestId, UiBuilderInspectionCollector(document()).snapshot())
    assertIs<CatalogRuntimeCommand.DispatchInput>(
      endpoint.receive(
        "https://preview.example",
        true,
        host.request("request-4", "dispatchInput", input),
      )
    )
  }

  @Test
  fun `rejects malformed stale unsupported duplicate and wrong-origin input`() {
    val endpoint = CatalogRuntimeProtocolEndpoint("m3-2026.09")
    val host = CatalogRuntimeHostSession("m3-2026.09")
    endpoint.receive("https://preview.example", true, host.request("request-1", "initialize"))
    render(endpoint, host, "request-2")

    fun error(requestId: String, payload: JsonObject): String {
      val reply =
        assertIs<CatalogRuntimeCommand.Reply>(
          endpoint.receive(
            "https://preview.example",
            true,
            host.request(requestId, "dispatchInput", payload),
          )
        )
      return reply.message.payload.getValue("code").toString().trim('"')
    }

    assertEquals(
      "INVALID_INPUT",
      error(
        "request-3",
        buildJsonObject {
          put("documentRevision", 1)
          put("kind", "pointer")
          put("x", -1)
          put("y", 10)
        },
      ),
    )
    assertEquals(
      "STALE_DOCUMENT",
      error(
        "request-4",
        input(
          CatalogRuntimeInput(
            documentRevision = 0,
            kind = "wheel",
            x = 1.0,
            y = 1.0,
            deltaMode = 0,
            deltaX = 0.0,
            deltaY = 1.0,
          )
        ),
      ),
    )
    assertEquals(
      "UNSUPPORTED_INPUT",
      error(
        "request-5",
        input(CatalogRuntimeInput(1, "keyboard", x = 1.0, y = 1.0)),
      ),
    )
    assertEquals(
      "INVALID_INPUT",
      error(
        "request-5b",
        input(
          CatalogRuntimeInput(
            documentRevision = 1,
            kind = "wheel",
            x = 1.0,
            y = 1.0,
            deltaMode = 1,
            deltaX = 0.0,
            deltaY = 1.0,
          )
        ),
      ),
    )

    val accepted =
      host.request(
        "request-6",
        "dispatchInput",
        input(CatalogRuntimeInput(1, "keyboard", x = 1.0, y = 1.0)),
      )
    endpoint.receive("https://preview.example", true, accepted)
    val duplicate =
      assertIs<CatalogRuntimeCommand.Reply>(
        endpoint.receive("https://preview.example", true, accepted)
      )
    assertEquals("DUPLICATE_REQUEST", duplicate.message.payload["code"].toString().trim('"'))
    assertNull(
      endpoint.receive(
        "https://attacker.example",
        true,
        host.request(
          "request-7",
          "dispatchInput",
          input(CatalogRuntimeInput(1, "keyboard", x = 1.0, y = 1.0)),
        ),
      )
    )
  }

  @Test
  fun `render rejects a document pinned to another runtime`() {
    val endpoint = CatalogRuntimeProtocolEndpoint("m3-2026.09")
    val host = CatalogRuntimeHostSession("m3-2026.09")
    endpoint.receive("https://preview.example", true, host.request("request-1", "initialize"))
    val other =
      document().copy(catalogPin = buildJsonObject { put("nativeRuntimeId", "another-runtime") })
    val response =
      assertIs<CatalogRuntimeCommand.Reply>(
          endpoint.receive(
            "https://preview.example",
            true,
            host.request(
              "request-2",
              "renderDocument",
              buildJsonObject {
                put(
                  "document",
                  RUNTIME_PROTOCOL_JSON.encodeToJsonElement(
                    UiBuilderDocument.serializer(),
                    other,
                  ),
                )
              },
            ),
          )
        )
        .message
    assertEquals("RUNTIME_PIN_MISMATCH", response.payload.getValue("code").toString().trim('"'))
  }

  @Test
  fun `host ignores wrong source origin identity protocol and correlation without consuming request`() {
    val host = CatalogRuntimeHostSession("m3-2026.09")
    host.request("request-1", "dispatchInput")
    val valid =
      CatalogRuntimeMessage(
        protocolVersion = 1,
        runtimeId = "m3-2026.09",
        requestId = "request-1",
        type = "inputDispatched",
      )
    fun encoded(message: CatalogRuntimeMessage) =
      RUNTIME_PROTOCOL_JSON.encodeToString(CatalogRuntimeMessage.serializer(), message)

    assertNull(host.accept("null", false, encoded(valid)))
    assertNull(host.accept("https://attacker.example", true, encoded(valid)))
    assertNull(host.accept("null", true, encoded(valid.copy(runtimeId = "wrong"))))
    assertNull(host.accept("null", true, encoded(valid.copy(protocolVersion = 2))))
    assertNull(host.accept("null", true, encoded(valid.copy(requestId = "not-pending"))))
    assertEquals("inputDispatched", host.accept("null", true, encoded(valid))?.type)
    assertNull(host.accept("null", true, encoded(valid)), "correlation is consumed exactly once")
  }

  private fun document() =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "design",
      title = "Design",
      revision = 1,
      catalogPin = buildJsonObject { put("nativeRuntimeId", "m3-2026.09") },
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = emptyList(),
      nodes = emptyMap(),
    )

  private fun input(value: CatalogRuntimeInput): JsonObject =
    RUNTIME_PROTOCOL_JSON.encodeToJsonElement(CatalogRuntimeInput.serializer(), value).jsonObject

  private fun render(
    endpoint: CatalogRuntimeProtocolEndpoint,
    host: CatalogRuntimeHostSession,
    requestId: String,
  ) {
    val command = requestRender(endpoint, host, requestId)
    endpoint.rendered(command.requestId, UiBuilderInspectionCollector(document()).snapshot())
  }

  private fun requestRender(
    endpoint: CatalogRuntimeProtocolEndpoint,
    host: CatalogRuntimeHostSession,
    requestId: String,
  ): CatalogRuntimeCommand.Render =
    assertIs(
      endpoint.receive(
        "https://preview.example",
        true,
        host.request(
          requestId,
          "renderDocument",
          buildJsonObject {
            put(
              "document",
              RUNTIME_PROTOCOL_JSON.encodeToJsonElement(UiBuilderDocument.serializer(), document()),
            )
          },
        ),
      )
    )
}
