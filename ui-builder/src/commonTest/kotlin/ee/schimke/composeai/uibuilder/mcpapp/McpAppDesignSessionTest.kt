package ee.schimke.composeai.uibuilder.mcpapp

import ee.schimke.composeai.uibuilder.UidDesignFiles
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.local.LocalDesignFixtures
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The MCP App host's rules for a `.uid` file the host owns, against a fake host.
 *
 * Every case here is one the issue names (compose-ui-builder#364): load, save with `ifMatch`,
 * conflict, too-large, read-only, and an external edit to a clean and to a dirty design.
 */
class McpAppDesignSessionTest {
  private val file = McpAppFile("home.uid", "host-resource://home")
  private val seedText = UidDesignFiles.encode(LocalDesignFixtures.document())
  private val seed = UidDesignFiles.decode(seedText)

  private fun edited(title: String): UiBuilderDocument = seed.copy(title = title)

  private fun session(host: FakeMcpAppHost): McpAppDesignSession =
    McpAppDesignSession(host, file).also { runImmediate { it.open() } }

  @Test
  fun `open subscribes, reads the host resource and parses the design`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText)
    val session = session(host)

    assertEquals(listOf("subscribe ${file.resourceUri}", "read ${file.resourceUri}"), host.calls)
    val state = session.state
    assertEquals(seed, state.document)
    assertEquals("v1", state.etag)
    assertTrue(state.writable)
    assertTrue(state.live)
    assertFalse(state.dirty)
    assertNull(state.notice)
    assertEquals(1, state.generation)
  }

  @Test
  fun `a file that is not a design fails with its name and never writes`() {
    val host = FakeMcpAppHost(file.resourceUri, "{\"schema\":\"something-else\"}")
    val session = session(host)

    assertNull(session.state.document)
    assertTrue(session.state.failure!!.startsWith("home.uid is not a UI Builder design"))
    runImmediate { session.save() }
    assertEquals(0, host.writes.size)
  }

  @Test
  fun `a clean design is never written, so opening and closing leaves the file alone`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText)
    val session = session(host)

    session.edited(seed)
    runImmediate { session.save() }

    assertEquals(0, host.writes.size)
    assertEquals(seedText, host.text)
  }

  @Test
  fun `save writes the whole document with ifMatch set to the last etag`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText)
    val session = session(host)

    session.edited(edited("First"))
    assertTrue(session.state.dirty)
    runImmediate { session.save() }

    assertEquals(
      listOf(FakeMcpAppHost.Write(UidDesignFiles.encode(edited("First")), "v1")),
      host.writes,
    )
    assertEquals("v2", session.state.etag)
    assertFalse(session.state.dirty)

    // The next save names the etag the first one returned.
    session.edited(edited("Second"))
    runImmediate { session.save() }
    assertEquals("v2", host.writes.last().ifMatch)
    assertEquals("v3", session.state.etag)
    // A save is not a reload: the editor, and its undo history, stay where they are.
    assertEquals(1, session.state.generation)
  }

  @Test
  fun `a conflict keeps the local design and offers reload or overwrite`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText)
    val session = session(host)
    host.externalWrite(UidDesignFiles.encode(edited("Theirs")))

    session.edited(edited("Mine"))
    runImmediate { session.save() }

    assertEquals(McpAppNotice.Conflict("v2"), session.state.notice)
    assertTrue(session.state.dirty)
    assertEquals(UidDesignFiles.encode(edited("Theirs")), host.text)

    // An autosave does not write over a conflict a person has not resolved.
    runImmediate { session.save() }
    assertEquals(1, host.writes.size)

    runImmediate { session.overwrite() }
    assertEquals("v2", host.writes.last().ifMatch)
    assertEquals(UidDesignFiles.encode(edited("Mine")), host.text)
    assertNull(session.state.notice)
    assertFalse(session.state.dirty)
  }

  @Test
  fun `reload after a conflict discards local edits and starts a new editor`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText)
    val session = session(host)
    host.externalWrite(UidDesignFiles.encode(edited("Theirs")))
    session.edited(edited("Mine"))
    runImmediate { session.save() }

    runImmediate { session.reload() }

    assertEquals("Theirs", session.state.document!!.title)
    assertEquals("v2", session.state.etag)
    assertFalse(session.state.dirty)
    assertNull(session.state.notice)
    assertEquals(2, session.state.generation)
  }

  @Test
  fun `too-large is reported with the limit and the size, and nothing is saved`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText, maxBytes = 64)
    val session = session(host)

    session.edited(edited("Too big"))
    runImmediate { session.save() }

    val notice = assertIs<McpAppNotice.TooLarge>(session.state.notice)
    assertEquals(64, notice.maxBytes)
    assertEquals(UidDesignFiles.encode(edited("Too big")).encodeToByteArray().size, notice.bytes)
    assertTrue(session.state.dirty)
    assertEquals(seedText, host.text)
  }

  @Test
  fun `a file the host does not mark writable opens read-only and is never written`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText, writable = false)
    val session = session(host)

    assertEquals(McpAppNotice.ReadOnly, session.state.notice)
    assertFalse(session.state.writable)
    session.edited(edited("Mine"))
    runImmediate { session.save() }

    assertEquals(0, host.writes.size)
    assertEquals(McpAppNotice.ReadOnly, session.state.notice)
  }

  @Test
  fun `an external edit to a clean design is adopted`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText)
    val session = session(host)

    host.externalWrite(UidDesignFiles.encode(edited("Agent edit")))
    runImmediate { session.resourceUpdated(file.resourceUri) }

    assertEquals("Agent edit", session.state.document!!.title)
    assertEquals("v2", session.state.etag)
    assertEquals(2, session.state.generation)
    assertNull(session.state.notice)
  }

  @Test
  fun `an external edit to a dirty design asks instead of reloading`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText)
    val session = session(host)
    session.edited(edited("Mine"))

    host.externalWrite(UidDesignFiles.encode(edited("Agent edit")))
    runImmediate { session.resourceUpdated(file.resourceUri) }

    assertEquals(McpAppNotice.ExternalChange("v2"), session.state.notice)
    assertEquals(seed, session.state.document)
    assertEquals(1, session.state.generation)
    assertTrue(session.state.dirty)

    // Keeping local edits means the next save meets the external version as a conflict.
    session.keepLocalChanges()
    assertNull(session.state.notice)
    runImmediate { session.save() }
    assertEquals(McpAppNotice.Conflict("v2"), session.state.notice)
  }

  @Test
  fun `the notification for this editor's own save is recognised and ignored`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText)
    val session = session(host)
    session.edited(edited("Mine"))
    runImmediate { session.save() }

    runImmediate { session.resourceUpdated(file.resourceUri) }

    assertEquals(1, session.state.generation)
    assertNull(session.state.notice)
  }

  @Test
  fun `notifications for other resources are ignored`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText)
    val session = session(host)
    val reads = host.calls.size

    runImmediate { session.resourceUpdated("host-resource://other") }

    assertEquals(reads, host.calls.size)
  }

  @Test
  fun `a host that refuses subscriptions still opens the design, not live`() {
    val host = FakeMcpAppHost(file.resourceUri, seedText, subscribable = false)
    val session = session(host)

    assertNotNull(session.state.document)
    assertFalse(session.state.live)
  }

  @Test
  fun `selecting a node sends a titled visible block and a hidden detail block`() {
    val document =
      seed.copy(
        nodes =
          seed.nodes +
            ("title" to
              UiBuilderNode(
                id = "title",
                componentId = "m3/text",
                properties =
                  JsonObject(
                    mapOf(
                      "text" to
                        JsonObject(
                          mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("Hi"))
                        )
                    )
                  ),
              ))
      )
    val root = document.nodes.getValue("root")
    val withChild =
      document.copy(
        nodes = document.nodes + ("root" to root.copy(slots = mapOf("children" to listOf("title"))))
      )
    val host = FakeMcpAppHost(file.resourceUri, UidDesignFiles.encode(withChild))
    val session = session(host)
    session.edited(session.state.document!!)

    runImmediate { session.select("title") }

    val params = host.contexts.single().toParams()
    val content = params["content"]!!.jsonArray.map { it.jsonObject }
    assertEquals(2, content.size)
    val visible = content[0]
    assertEquals(
      "Text · title",
      visible["_meta"]!!.jsonObject["openai/title"]!!.jsonPrimitive.content,
    )
    val text = visible["text"]!!.jsonPrimitive.content
    assertTrue("Selected in home.uid: Text `title` (m3/text)" in text, text)
    assertTrue("Path: Column `root` › children[0] Text `title`" in text, text)
    assertTrue("\"value\":\"Hi\"" in text, text)
    val hidden = content[1]
    assertEquals(
      "assistant",
      hidden["annotations"]!!.jsonObject["audience"]!!.jsonArray.single().jsonPrimitive.content,
    )
    val selection = params["structuredContent"]!!.jsonObject["selection"]!!.jsonObject
    assertEquals("title", selection["nodeId"]!!.jsonPrimitive.content)
    assertEquals(
      listOf("root", "title"),
      selection["path"]!!.jsonArray.map { it.jsonObject["nodeId"]!!.jsonPrimitive.content },
    )

    // The same selection again sends nothing; clearing it sends an empty context.
    runImmediate { session.select("title") }
    assertEquals(1, host.contexts.size)
    runImmediate { session.select(null) }
    assertEquals(McpAppModelContext.Empty, host.contexts.last())
  }

  @Test
  fun `a comment sends one model context with the picture and detail, then one message`() {
    val host = FakeMcpAppHost(file.resourceUri, UidDesignFiles.encode(withTitle()))
    val session = session(host)
    session.edited(session.state.document!!)

    val sent = runImmediate {
      session.comment("title", "  Make this bigger  ", McpAppImage("iVBORw0KGgo=", "image/png"))
    }

    assertTrue(sent)
    // Context first: a message sent at once starts the turn that reads it.
    assertEquals(listOf("ui/update-model-context", "ui/message"), host.conversation)

    val message = host.messages.single().toParams()
    assertEquals("user", message["role"]!!.jsonPrimitive.content)
    assertEquals(
      JsonObject(
        mapOf(
          "openai/message" to
            JsonObject(mapOf("target" to JsonPrimitive("active"), "send" to JsonPrimitive(true)))
        )
      ),
      message["_meta"],
    )
    val blocks = message["content"]!!.jsonArray.map { it.jsonObject }
    assertEquals(2, blocks.size)
    // The words, trimmed and untitled, so they are the message text.
    assertEquals("text", blocks[0]["type"]!!.jsonPrimitive.content)
    assertEquals("Make this bigger", blocks[0]["text"]!!.jsonPrimitive.content)
    assertNull(blocks[0]["_meta"])
    // Then the node, as one titled item: path, component and properties.
    val node = blocks[1]
    assertEquals("Text · title", node["_meta"]!!.jsonObject["openai/title"]!!.jsonPrimitive.content)
    val nodeText = node["text"]!!.jsonPrimitive.content
    assertTrue("Comment on home.uid: Text `title` (m3/text)" in nodeText, nodeText)
    assertTrue("Path: Column `root` › children[0] Text `title`" in nodeText, nodeText)
    assertTrue("\"value\":\"Hi\"" in nodeText, nodeText)

    val context = host.contexts.single().toParams()
    val parts = context["content"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("image", "text"), parts.map { it["type"]!!.jsonPrimitive.content })
    assertEquals("iVBORw0KGgo=", parts[0]["data"]!!.jsonPrimitive.content)
    assertEquals("image/png", parts[0]["mimeType"]!!.jsonPrimitive.content)
    assertEquals(
      "Text · title",
      parts[0]["_meta"]!!.jsonObject["openai/title"]!!.jsonPrimitive.content,
    )
    assertEquals(
      "assistant",
      parts[1]["annotations"]!!.jsonObject["audience"]!!.jsonArray.single().jsonPrimitive.content,
    )
    val hidden = parts[1]["text"]!!.jsonPrimitive.content
    assertTrue("\"Make this bigger\"" in hidden, hidden)
    assertTrue("render_preview" in hidden, hidden)
    val structured = context["structuredContent"]!!.jsonObject
    assertEquals("title", structured["comment"]!!.jsonObject["nodeId"]!!.jsonPrimitive.content)
    assertEquals("title", structured["selection"]!!.jsonObject["nodeId"]!!.jsonPrimitive.content)
    // A comment writes nothing to the file.
    assertEquals(0, host.writes.size)
  }

  @Test
  fun `a comment without a picture sends only the assistant detail as context`() {
    val host = FakeMcpAppHost(file.resourceUri, UidDesignFiles.encode(withTitle()))
    val session = session(host)
    session.edited(session.state.document!!)

    runImmediate { session.comment("title", "Why is this grey?") }

    val parts = host.contexts.single().content.map { it.jsonObject }
    assertEquals(listOf("text"), parts.map { it["type"]!!.jsonPrimitive.content })
    assertEquals(1, host.messages.size)
  }

  @Test
  fun `an empty comment, or one on a node that is gone, sends nothing`() {
    val host = FakeMcpAppHost(file.resourceUri, UidDesignFiles.encode(withTitle()))
    val session = session(host)
    session.edited(session.state.document!!)

    assertFalse(runImmediate { session.comment("title", "") })
    assertFalse(runImmediate { session.comment("title", "   \n ") })
    assertFalse(runImmediate { session.comment("no-such-node", "Hello") })

    assertTrue(host.conversation.isEmpty(), host.conversation.toString())
  }

  /** The seed with a `Text` "Hi" as the root column's only child. */
  private fun withTitle(): UiBuilderDocument {
    val title =
      UiBuilderNode(
        id = "title",
        componentId = "m3/text",
        properties =
          JsonObject(
            mapOf(
              "text" to
                JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("Hi")))
            )
          ),
      )
    val root = seed.nodes.getValue("root")
    return seed.copy(
      nodes =
        seed.nodes +
          ("title" to title) +
          ("root" to root.copy(slots = mapOf("children" to listOf("title"))))
    )
  }
}

/** An in-memory host file with etags, a write limit and a switch for `writable`. */
internal class FakeMcpAppHost(
  private val uri: String,
  initialText: String,
  private val writable: Boolean = true,
  private val maxBytes: Long = Long.MAX_VALUE,
  private val subscribable: Boolean = true,
) : McpAppBridge {
  data class Write(val text: String, val ifMatch: String?)

  var text: String = initialText
    private set

  private var version = 1
  val calls = mutableListOf<String>()
  val writes = mutableListOf<Write>()
  val contexts = mutableListOf<McpAppModelContext>()
  val messages = mutableListOf<McpAppMessage>()

  /** Every `ui/update-model-context` and `ui/message`, in the order the app sent them. */
  val conversation = mutableListOf<String>()

  private val etag: String
    get() = "v$version"

  /** Someone else — an agent, another editor — changes the file. */
  fun externalWrite(newText: String) {
    text = newText
    version++
  }

  override suspend fun read(uri: String): McpAppFileContents {
    calls += "read $uri"
    check(uri == this.uri)
    return McpAppFileContents(text, etag, writable)
  }

  override suspend fun write(uri: String, text: String, ifMatch: String?): McpAppWriteOutcome {
    calls += "write $uri"
    check(uri == this.uri)
    check(writable) { "wrote a file the host did not mark writable" }
    writes += Write(text, ifMatch)
    if (text.encodeToByteArray().size > maxBytes) return McpAppWriteOutcome.TooLarge(maxBytes)
    if (ifMatch != null && ifMatch != etag) return McpAppWriteOutcome.Conflict(etag)
    this.text = text
    version++
    return McpAppWriteOutcome.Saved(etag)
  }

  override suspend fun subscribe(uri: String) {
    calls += "subscribe $uri"
    check(subscribable) { "Method not found: resources/subscribe" }
  }

  override suspend fun unsubscribe(uri: String) {
    calls += "unsubscribe $uri"
  }

  override suspend fun updateModelContext(context: McpAppModelContext) {
    contexts += context
    conversation += "ui/update-model-context"
  }

  override suspend fun sendMessage(message: McpAppMessage) {
    messages += message
    conversation += "ui/message"
  }
}

internal fun <T> runImmediate(block: suspend () -> T): T {
  var completed: Result<T>? = null
  block.startCoroutine(
    object : Continuation<T> {
      override val context = EmptyCoroutineContext

      override fun resumeWith(result: Result<T>) {
        completed = result
      }
    }
  )
  return completed?.getOrThrow() ?: error("the fake host suspended unexpectedly")
}
