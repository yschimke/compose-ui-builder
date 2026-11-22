package ee.schimke.composeai.uibuilder.mcpapp

import ee.schimke.composeai.uibuilder.UidDesignFiles
import ee.schimke.composeai.uibuilder.local.LocalDesignFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * A design that lives on the MCP server (Claude): the editor tools' wire shapes, and the file rules
 * of [McpAppDesignSession] running over them — the revision as the etag, conflicts, read-only, and
 * following edits made elsewhere.
 */
class McpAppServerDesignBridgeTest {
  private val seedText = UidDesignFiles.encode(LocalDesignFixtures.document())
  private val seed = UidDesignFiles.decode(seedText)

  /** The server's side of the four editor tools, for one design. */
  private class FakeServer(document: String) : McpAppToolCaller {
    var document: JsonObject = Json.parseToJsonElement(document).jsonObject
    var revision = 7L
    var writable = true
    var awaitOffered = true
    val calls = mutableListOf<Pair<String, JsonObject>>()

    /** What each await answers, in turn; null means "nothing changed". */
    val awaits = ArrayDeque<Long?>()
    var onAwait: (() -> Unit)? = null

    fun externalSave(title: String) {
      document = JsonObject(document + ("title" to JsonPrimitive(title)))
      revision++
    }

    override suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
      calls += name to arguments
      assertEquals("home", arguments["designId"]?.jsonPrimitive?.content)
      return when (name) {
        McpAppServerDesignBridge.READ_TOOL ->
          buildJsonObject {
            put("designId", "home")
            put("title", "Home")
            put("revision", revision)
            put("writable", writable)
            put("document", document)
          }
        McpAppServerDesignBridge.WRITE_TOOL -> {
          val base = arguments["baseRevision"]!!.jsonPrimitive.long
          when {
            !writable -> buildJsonObject { put("outcome", "read-only") }
            base != revision ->
              buildJsonObject {
                put("outcome", "conflict")
                put("revision", revision)
              }
            else -> {
              document = arguments["document"]!!.jsonObject
              revision++
              buildJsonObject {
                put("outcome", "saved")
                put("revision", revision)
              }
            }
          }
        }
        McpAppServerDesignBridge.AWAIT_TOOL -> {
          if (!awaitOffered) error("Unknown tool '$name'")
          if (arguments["waitSeconds"]!!.jsonPrimitive.content == "0") {
            return buildJsonObject {
              put("revision", revision)
              put("changed", false)
            }
          }
          onAwait?.invoke()
          val next = if (awaits.isEmpty()) null else awaits.removeFirst()
          buildJsonObject {
            put("revision", next ?: revision)
            put("changed", next != null)
          }
        }
        else -> error("unexpected tool $name")
      }
    }
  }

  private class NoHost : McpAppBridge {
    val contexts = mutableListOf<McpAppModelContext>()

    override suspend fun read(uri: String) = error("the host owns no file here")

    override suspend fun write(uri: String, text: String, ifMatch: String?) = error("no file")

    override suspend fun subscribe(uri: String) = error("no file")

    override suspend fun unsubscribe(uri: String) = error("no file")

    override suspend fun updateModelContext(context: McpAppModelContext) {
      contexts += context
    }

    override suspend fun sendMessage(message: McpAppMessage) = Unit
  }

  private val file = McpAppServerDesignBridge.designFile("home")

  private fun opened(server: FakeServer): Pair<McpAppServerDesignBridge, McpAppDesignSession> {
    val bridge = McpAppServerDesignBridge(server, NoHost())
    val session = McpAppDesignSession(bridge, file)
    runImmediate { session.open() }
    return bridge to session
  }

  @Test
  fun `the opening tool input names a design`() {
    val params = Json.parseToJsonElement("""{"arguments":{"designId":"home"}}""").jsonObject
    assertEquals("home", McpAppServerDesignBridge.parseDesignInput(params))
    // A file input, and an empty id, are not a server design.
    assertNull(
      McpAppServerDesignBridge.parseDesignInput(
        Json.parseToJsonElement("""{"arguments":{"file":{"name":"a.uid","resourceUri":"x"}}}""")
          .jsonObject,
      ),
    )
    assertNull(
      McpAppServerDesignBridge.parseDesignInput(
        Json.parseToJsonElement("""{"arguments":{"designId":" "}}""").jsonObject,
      ),
    )
  }

  @Test
  fun `opening reads the design through the editor tool, with the revision as its etag`() {
    val server = FakeServer(seedText)
    val (_, session) = opened(server)

    assertEquals(seed, session.state.document)
    assertEquals("7", session.state.etag)
    assertTrue(session.state.writable)
    assertTrue(session.state.live, "the await tool answered, so external edits are followed")
    assertEquals(
      listOf(McpAppServerDesignBridge.AWAIT_TOOL, McpAppServerDesignBridge.READ_TOOL),
      server.calls.map { it.first },
    )
  }

  @Test
  fun `a save sends the whole document against the revision it was read at`() {
    val server = FakeServer(seedText)
    val (_, session) = opened(server)
    session.edited(seed.copy(title = "Edited"))
    runImmediate { session.save() }

    val (name, arguments) = server.calls.last()
    assertEquals(McpAppServerDesignBridge.WRITE_TOOL, name)
    assertEquals(7L, arguments["baseRevision"]!!.jsonPrimitive.long)
    assertEquals("Edited", arguments["document"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    assertEquals("8", session.state.etag)
    assertFalse(session.state.dirty)
    assertNull(session.state.notice)
  }

  @Test
  fun `a save that meets somebody else's revision is a conflict, and overwrite names theirs`() {
    val server = FakeServer(seedText)
    val (_, session) = opened(server)
    server.externalSave("Theirs")
    session.edited(seed.copy(title = "Mine"))
    runImmediate { session.save() }

    assertEquals(McpAppNotice.Conflict("8"), session.state.notice)
    assertEquals("Theirs", server.document["title"]!!.jsonPrimitive.content)

    runImmediate { session.overwrite() }
    assertEquals(8L, server.calls.last().second["baseRevision"]!!.jsonPrimitive.long)
    assertEquals("Mine", server.document["title"]!!.jsonPrimitive.content)
    assertEquals("9", session.state.etag)
  }

  @Test
  fun `a design this person may only read is opened read-only and never written`() {
    val server = FakeServer(seedText).apply { writable = false }
    val (_, session) = opened(server)
    assertEquals(McpAppNotice.ReadOnly, session.state.notice)
    session.edited(seed.copy(title = "Edited"))
    runImmediate { session.save() }
    assertTrue(server.calls.none { it.first == McpAppServerDesignBridge.WRITE_TOOL })
  }

  @Test
  fun `a save refused as read-only turns the editor read-only`() {
    val server = FakeServer(seedText)
    val (_, session) = opened(server)
    // Access was taken away after the design opened.
    server.writable = false
    session.edited(seed.copy(title = "Edited"))
    runImmediate { session.save() }

    assertEquals(McpAppNotice.ReadOnly, session.state.notice)
    assertFalse(session.state.writable)
  }

  @Test
  fun `without the await tool the design opens, but does not claim to follow edits`() {
    val server = FakeServer(seedText).apply { awaitOffered = false }
    val (_, session) = opened(server)
    assertEquals(seed, session.state.document)
    assertFalse(session.state.live)
  }

  @Test
  fun `an edit made elsewhere is followed into a clean editor, and its own save is not`() {
    val server = FakeServer(seedText)
    val (bridge, session) = opened(server)
    session.edited(seed.copy(title = "Mine"))
    runImmediate { session.save() } // revision 8, written by this editor
    // Then an agent saves revision 9; the next wait reports it, and the one after ends the loop.
    server.awaits += 9L
    var waits = 0
    server.onAwait = {
      waits++
      if (waits == 1) server.externalSave("Agent's")
      if (waits == 2) runImmediate { session.close() }
    }
    val updated = mutableListOf<String>()
    runImmediate {
      bridge.followExternalEdits(file.resourceUri) { uri ->
        updated += uri
        session.resourceUpdated(uri)
      }
    }

    assertEquals(listOf(file.resourceUri), updated)
    assertEquals("Agent's", session.state.document?.title)
    assertEquals("9", session.state.etag)
    // The wait started from the editor's own save, so that save was never reported back.
    val firstWait =
      server.calls.filter {
        it.first == McpAppServerDesignBridge.AWAIT_TOOL &&
          it.second["waitSeconds"]!!.jsonPrimitive.content != "0"
      }
    assertEquals(8L, firstWait.first().second["afterRevision"]!!.jsonPrimitive.long)
  }

  @Test
  fun `a tool error is an error, with the server's text`() {
    val transport =
      object : McpAppTransport {
        override suspend fun request(method: String, params: JsonObject): JsonElement {
          assertEquals("tools/call", method)
          return Json.parseToJsonElement(
            """{"isError":true,"content":[{"type":"text","text":"design 'home' not found"}]}""",
          )
        }

        override fun notify(method: String, params: JsonObject) = Unit
      }
    val failure =
      assertFailsWith<IllegalStateException> {
        runImmediate { transport.callServerTool("ui_builder_editor_read", buildJsonObject {}) }
      }
    assertEquals("design 'home' not found", failure.message)
  }

  @Test
  fun `a result without structured content falls back to its text block's JSON`() {
    val transport =
      object : McpAppTransport {
        override suspend fun request(method: String, params: JsonObject): JsonElement =
          Json.parseToJsonElement(
            """{"content":[{"type":"text","text":"{\"revision\":3,\"changed\":false}"}]}""",
          )

        override fun notify(method: String, params: JsonObject) = Unit
      }
    val result = runImmediate { transport.callServerTool("x", buildJsonObject {}) }
    assertEquals(3L, result["revision"]!!.jsonPrimitive.long)
    assertIs<JsonObject>(result)
  }

  @Test
  fun `a selection in a server design tells the agent to use the UI-builder tools, not a file`() {
    val context =
      McpAppSelectionContext.of(file, seed, seed.nodes.keys.first())
        .content
        .map { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
        .joinToString("\n")
    assertTrue("ui_builder_apply on design `home`" in context, context)
    assertFalse("`.uid`" in context, context)
    // A host file keeps the file instruction.
    val fileContext =
      McpAppSelectionContext.of(
          McpAppFile("home.uid", "host-resource://home"),
          seed,
          seed.nodes.keys.first(),
        )
        .content
        .joinToString("\n")
    assertTrue("design file" in fileContext, fileContext)
  }
}
