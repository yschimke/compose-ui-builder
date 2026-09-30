package ee.schimke.composeai.uibuilder.mcpapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The wire half of the bridge: the exact JSON the MCP Apps host and the OpenAI extensions spec
 * (`openai/mcp-extensions` `docs/spec.md`) expect, and the answers they give.
 */
class McpAppJsonRpcBridgeTest {
  private class RecordingTransport(private val answer: (String, JsonObject) -> String) :
    McpAppTransport {
    val requests = mutableListOf<Pair<String, JsonObject>>()
    val notifications = mutableListOf<Pair<String, JsonObject>>()

    override suspend fun request(method: String, params: JsonObject): JsonElement {
      requests += method to params
      return Json.parseToJsonElement(answer(method, params))
    }

    override fun notify(method: String, params: JsonObject) {
      notifications += method to params
    }
  }

  private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `initialize sends the handshake, then initialized, and reads the host capabilities`() {
    val transport = RecordingTransport { _, _ ->
      """{"protocolVersion":"2026-01-26","hostInfo":{"name":"chatgpt","version":"1"},
         "hostCapabilities":{"experimental":{"openai/resource":{}},"updateModelContext":{"text":{}}}}"""
    }
    val host = runImmediate { McpAppJsonRpcBridge(transport).initialize("3.70.0") }

    assertEquals(McpAppHost("chatgpt", fileResources = true, modelContext = true), host)
    val (method, params) = transport.requests.single()
    assertEquals("ui/initialize", method)
    assertEquals(
      json(
        """{"protocolVersion":"2026-01-26",
            "appInfo":{"name":"compose-ui-builder","version":"3.70.0"},
            "appCapabilities":{"availableDisplayModes":["fullscreen","inline"]}}"""
      ),
      params,
    )
    assertEquals(listOf("ui/notifications/initialized"), transport.notifications.map { it.first })
  }

  @Test
  fun `a host on another protocol version is refused`() {
    val transport = RecordingTransport { _, _ -> """{"protocolVersion":"2025-01-01"}""" }
    assertFailsWith<IllegalArgumentException> {
      runImmediate { McpAppJsonRpcBridge(transport).initialize("dev") }
    }
    assertTrue(transport.notifications.isEmpty())
  }

  @Test
  fun `read asks for the text representation and returns the etag and writable flag`() {
    val transport = RecordingTransport { _, _ ->
      """{"contents":[{"uri":"host-resource://a","text":"{}",
          "_meta":{"openai/resource":{"etag":"version-1","writable":true}}}]}"""
    }
    val contents = runImmediate { McpAppJsonRpcBridge(transport).read("host-resource://a") }

    assertEquals(McpAppFileContents("{}", "version-1", writable = true), contents)
    assertEquals(
      "resources/read" to
        json(
          """{"uri":"host-resource://a","_meta":{"openai/resource":{"representation":"text"}}}"""
        ),
      transport.requests.single(),
    )
  }

  @Test
  fun `a read without metadata is read-only, and a blob is decoded`() {
    val transport = RecordingTransport { _, _ ->
      // "{}\n" as base64: a host that ignored the representation hint.
      """{"contents":[{"uri":"host-resource://a","blob":"e30K"}]}"""
    }
    val contents = runImmediate { McpAppJsonRpcBridge(transport).read("host-resource://a") }

    assertEquals("{}\n", contents.text)
    assertNull(contents.etag)
    assertFalse(contents.writable)
  }

  @Test
  fun `write sends text and ifMatch and maps all three outcomes`() {
    var answer = """{"outcome":"saved","etag":"version-2"}"""
    val transport = RecordingTransport { _, _ -> answer }
    val bridge = McpAppJsonRpcBridge(transport)

    assertEquals(
      McpAppWriteOutcome.Saved("version-2"),
      runImmediate { bridge.write("host-resource://a", "text", "version-1") },
    )
    assertEquals(
      "openai/resources/write" to
        json("""{"uri":"host-resource://a","text":"text","ifMatch":"version-1"}"""),
      transport.requests.last(),
    )

    answer = """{"outcome":"conflict","etag":"version-3"}"""
    assertEquals(
      McpAppWriteOutcome.Conflict("version-3"),
      runImmediate { bridge.write("host-resource://a", "text", null) },
    )
    // No etag to match: the write omits ifMatch rather than sending an empty one.
    assertEquals(
      json("""{"uri":"host-resource://a","text":"text"}"""),
      transport.requests.last().second,
    )

    answer = """{"outcome":"too-large","maxBytes":1048576}"""
    assertEquals(
      McpAppWriteOutcome.TooLarge(1_048_576),
      runImmediate { bridge.write("host-resource://a", "text", "version-3") },
    )
  }

  @Test
  fun `tool input and resource updates reach their listeners`() {
    val bridge = McpAppJsonRpcBridge(RecordingTransport { _, _ -> "{}" })
    val files = mutableListOf<McpAppFile>()
    val updates = mutableListOf<String>()
    bridge.onFile(files::add)
    bridge.onResourceUpdated(updates::add)

    bridge.handleNotification(
      "ui/notifications/tool-input",
      json("""{"arguments":{"file":{"name":"home.uid","resourceUri":"host-resource://home"}}}"""),
    )
    bridge.handleNotification("ui/notifications/tool-input", json("""{"arguments":{}}"""))
    bridge.handleNotification(
      "notifications/resources/updated",
      json("""{"uri":"host-resource://home"}"""),
    )
    bridge.handleNotification("ui/notifications/host-context-changed", json("{}"))

    assertEquals(listOf(McpAppFile("home.uid", "host-resource://home")), files)
    assertEquals(listOf("host-resource://home"), updates)
  }

  @Test
  fun `subscribe and model context use the standard methods`() {
    val transport = RecordingTransport { _, _ -> "{}" }
    val bridge = McpAppJsonRpcBridge(transport)

    runImmediate { bridge.subscribe("host-resource://a") }
    runImmediate { bridge.updateModelContext(McpAppModelContext.Empty) }

    assertEquals(
      listOf(
        "resources/subscribe" to json("""{"uri":"host-resource://a"}"""),
        "ui/update-model-context" to json("""{"content":[]}"""),
      ),
      transport.requests,
    )
  }
}
