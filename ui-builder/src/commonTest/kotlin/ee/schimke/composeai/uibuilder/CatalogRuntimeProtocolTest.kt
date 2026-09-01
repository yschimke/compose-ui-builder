package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
  fun `input is explicitly unsupported`() {
    val endpoint = CatalogRuntimeProtocolEndpoint("m3-2026.09")
    val host = CatalogRuntimeHostSession("m3-2026.09")
    endpoint.receive("https://preview.example", true, host.request("request-1", "initialize"))
    val response =
      assertIs<CatalogRuntimeCommand.Reply>(
          endpoint.receive(
            "https://preview.example",
            true,
            host.request("request-2", "dispatchInput", buildJsonObject { put("kind", "pointer") }),
          )
        )
        .message
    assertEquals("error", response.type)
    assertEquals("UNSUPPORTED_INPUT", response.payload.getValue("code").toString().trim('"'))
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
}
