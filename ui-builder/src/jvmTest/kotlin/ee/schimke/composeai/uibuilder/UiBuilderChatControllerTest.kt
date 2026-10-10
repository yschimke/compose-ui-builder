package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.*
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class UiBuilderChatControllerTest {
  private class Host : UiBuilderChatHost {
    override var connected = true
    override var rememberConnection = false
    override val monitoringAvailable = true
    var stored = UiBuilderChatSession()
    val requests = mutableListOf<List<UiBuilderChatMessage>>()
    val answers = mutableListOf<CompletableDeferred<String>>()

    override fun connect() {}

    override fun useKey(key: String) {
      connected = key.isNotBlank()
    }

    override fun rememberConnection(remember: Boolean) {
      rememberConnection = remember
    }

    override fun disconnect() {
      connected = false
    }

    override fun load() = stored

    override fun save(session: UiBuilderChatSession): String? {
      stored = session
      return null
    }

    override fun clear(): String? {
      stored = UiBuilderChatSession()
      return null
    }

    override suspend fun complete(model: String, messages: List<UiBuilderChatMessage>): String {
      requests += messages
      val answer = CompletableDeferred<String>()
      answers += answer
      return answer.await()
    }
  }

  private fun board(vararg ids: String, sequence: Long = 1) =
    DesignCommentBoard(
      sequence,
      listOf(
        DesignCommentThread(
          "thread",
          comments = ids.map { DesignComment(it, "reviewer", body = "Review $it") },
        )
      ),
    )

  @Test
  fun `per thread cursors survive more than five hundred comments and stale snapshots`() =
    runBlocking {
      val host =
        Host().apply { stored = UiBuilderChatSession(reviewedComments = mapOf("thread" to "c500")) }
      val chat = UiBuilderChatController(host, this)
      val old = board(*(1..500).map { "c$it" }.toTypedArray(), sequence = 1)
      val current =
        old.copy(
          sequence = 2,
          threads =
            old.threads +
              DesignCommentThread(
                "other",
                comments = listOf(DesignComment("new", "reviewer", body = "Only new concern")),
              ),
        )
      chat.comments(current, true)
      chat.comments(old, true)
      chat.monitor(true)
      yield()
      assertEquals(1, host.requests.size)
      assertTrue(host.requests.single().last().content.contains("Only new concern"))
      assertFalse(host.requests.single().last().content.contains("Review c1"))
      host.answers.single().complete("Review")
      yield()
      yield()
      assertEquals(1, host.requests.size)
      assertEquals(mapOf("thread" to "c500", "other" to "new"), host.stored.reviewedComments)
      chat.stop()
    }

  @Test
  fun `reopening restores history without reconnecting or starting monitoring`() = runBlocking {
    val host =
      Host().apply {
        stored =
          UiBuilderChatSession(messages = listOf(UiBuilderChatMessage("assistant", "Saved reply")))
        connected = false
      }
    val chat = UiBuilderChatController(host, this)
    chat.comments(board("one"), true)
    yield()
    assertEquals("Saved reply", chat.session.messages.single().content)
    assertFalse(chat.monitoring)
    assertTrue(host.requests.isEmpty())
    chat.send("Question")
    assertTrue(host.requests.isEmpty())
  }

  @Test
  fun `comment bursts serialize and acknowledgement updates do not review twice`() = runBlocking {
    val host = Host()
    val chat = UiBuilderChatController(host, this)
    chat.comments(board("one"), true)
    chat.monitor(true)
    yield()
    chat.comments(board("one", "two", sequence = 2), true)
    chat.send("Cannot overlap")
    yield()
    assertEquals(1, host.requests.size)
    host.answers[0].complete("First review")
    yield()
    yield()
    assertEquals(2, host.requests.size)
    assertTrue(host.requests[1].last().content.contains("Review two"))
    assertFalse(host.requests[1].last().content.contains("Review one"))
    host.answers[1].complete("Second review")
    yield()
    chat.comments(board("one", "two", sequence = 3), true)
    yield()
    assertEquals(2, host.requests.size)
    assertEquals(mapOf("thread" to "two"), host.stored.reviewedComments)
    chat.stop()
  }

  @Test
  fun `stop prevents a late response from repopulating cleared history`() = runBlocking {
    val host = Host()
    val chat = UiBuilderChatController(host, this)
    chat.send("Question")
    yield()
    chat.clear()
    host.answers.single().complete("Late reply")
    yield()
    assertTrue(chat.session.messages.isEmpty())
    assertTrue(host.stored.messages.isEmpty())
    assertFalse(chat.busy)
  }

  @Test
  fun `failure pauses monitoring without consuming the comments or exposing exception text`() =
    runBlocking {
      val host = Host()
      val chat = UiBuilderChatController(host, this)
      chat.comments(board("one"), true)
      chat.monitor(true)
      yield()
      host.answers
        .single()
        .completeExceptionally(IllegalStateException("sensitive-provider-response"))
      yield()
      assertFalse(chat.monitoring)
      assertFalse(chat.busy)
      assertTrue(host.stored.reviewedComments.isEmpty())
      assertFalse(chat.notice.orEmpty().contains("sensitive-provider-response"))
    }

  @Test
  fun `agent comments never wake monitoring and a dropped feed stops it`() = runBlocking {
    val host = Host()
    val chat = UiBuilderChatController(host, this)
    val agentBoard =
      DesignCommentBoard(
        1,
        listOf(
          DesignCommentThread(
            "thread",
            comments =
              listOf(
                DesignComment(
                  "agent-comment",
                  "agent:x",
                  kind = DesignCommentAuthorKind.Agent,
                  body = "Reply",
                )
              ),
          )
        ),
      )
    chat.comments(agentBoard, true)
    chat.monitor(true)
    yield()
    assertTrue(host.requests.isEmpty())
    assertTrue(chat.monitoring)
    chat.comments(agentBoard, false)
    assertFalse(chat.monitoring)
  }

  @Test
  fun `automatic reviews pause at the budget and catch up only when reenabled`() = runBlocking {
    val host = Host()
    val chat = UiBuilderChatController(host, this)
    chat.comments(board(*(1..101).map { "c$it" }.toTypedArray()), true)
    chat.monitor(true)
    repeat(5) { index ->
      yield()
      host.answers[index].complete("Review $index")
      yield()
    }
    yield()
    assertEquals(5, host.requests.size)
    assertFalse(chat.monitoring)
    assertEquals(mapOf("thread" to "c100"), host.stored.reviewedComments)
    chat.monitor(true)
    yield()
    assertEquals(6, host.requests.size)
    assertTrue(host.requests.last().last().content.contains("Review c101"))
    chat.stop()
  }

  @Test
  fun `posting my own reply does not consume an automatic review`() = runBlocking {
    val host = Host()
    val chat = UiBuilderChatController(host, this, actorId = "viewer")
    val comments =
      listOf(
        DesignComment("other", "reviewer", body = "A concern"),
        DesignComment("mine", "viewer", body = "My reply"),
      )
    chat.comments(
      DesignCommentBoard(1, listOf(DesignCommentThread("thread", comments = comments))),
      true,
    )
    chat.monitor(true)
    yield()
    assertEquals(1, host.requests.size)
    assertTrue(host.requests.single().last().content.contains("A concern"))
    assertFalse(host.requests.single().last().content.contains("My reply"))
    host.answers.single().complete("Review")
    yield()
    yield()
    chat.comments(
      DesignCommentBoard(
        2,
        listOf(
          DesignCommentThread(
            "thread",
            comments = comments + DesignComment("mine2", "viewer", body = "Another reply"),
          )
        ),
      ),
      true,
    )
    yield()
    assertEquals(1, host.requests.size)
    assertTrue(chat.monitoring)
    chat.stop()
  }

  @Test
  fun `resolving and reopening a retained thread does not review its comments again`() =
    runBlocking {
      val host = Host()
      val chat = UiBuilderChatController(host, this)
      val current = board("one")
      chat.comments(current, true)
      chat.monitor(true)
      yield()
      host.answers.single().complete("Review")
      yield()
      yield()
      chat.comments(
        current.copy(sequence = 2, threads = current.threads.map { it.copy(resolved = true) }),
        true,
      )
      chat.comments(current.copy(sequence = 3), true)
      yield()
      assertEquals(1, host.requests.size)
      assertEquals("one", host.stored.reviewedComments["thread"])
      chat.stop()
    }

  @Test
  fun `context includes design text and instructions but excludes asset bytes and source URLs`() {
    val document =
      UiBuilderDocument(
        schema = "compose-ui-builder/v1",
        id = "design",
        title = "Design",
        catalogPin = JsonObject(emptyMap()),
        revision = 7,
        environment = JsonObject(emptyMap()),
        stateVariables = JsonObject(emptyMap()),
        roots = listOf("node"),
        nodes =
          mapOf(
            "node" to
              UiBuilderNode(
                "node",
                "Text",
                properties =
                  JsonObject(
                    mapOf(
                      "text" to JsonPrimitive("Hello"),
                      "documentBase64" to JsonPrimitive("private-binary-data"),
                      "documentUrl" to JsonPrimitive("https://host/?token=private-token"),
                    )
                  ),
              )
          ),
        assets = JsonObject(mapOf("secret" to JsonPrimitive("private-asset"))),
      )
    val context = agentChatContext(document, "node", "Keep it concise", board("one"))
    assertTrue(context.contains("Hello"))
    assertTrue(context.contains("Keep it concise"))
    assertTrue(context.contains("Review one"))
    assertFalse(context.contains("private-"))
    assertFalse(context.contains("token="))
  }
}
