package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.editor.DesignCommentBoard
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*

class JvmLocalAgentHostTest {
  @Test
  fun `native resume belongs to app history and new session clears its reference`() = runBlocking {
    if (System.getProperty("os.name").startsWith("Windows")) return@runBlocking
    val directory = Files.createTempDirectory("local-host")
    val id = "c8a44279-d005-43d4-a2a2-a7dc4a380000"
    val cli = directory.resolve("claude")
    Files.writeString(
      cli,
      """#!/bin/sh
printf '%s\n' "${'$'}@" > arguments.txt
cat > prompt.txt
printf '%s' '{"type":"result","is_error":false,"result":"Private review","session_id":"$id"}'
""",
    )
    cli.toFile().setExecutable(true)
    try {
      val storage = directory.resolve("history")
      fun host() =
        JvmLocalAgentHost(
          storage,
          "viewer",
          this,
          false,
          { "Latest design snapshot" },
          { mapOf(LocalHarness.Claude to cli) },
        )
      val first = host()
      assertNull(first.chat) // Local chat is never accidentally presented as OpenRouter chat.
      assertEquals("claude", first.selected)
      assertTrue(first.conversation.session.messages.isEmpty())
      first.conversation.send("Review this")
      withTimeout(5_000) { while (first.conversation.busy) delay(10) }
      assertEquals("Private review", first.conversation.session.messages.last().content)
      val workspace = storage.resolve("claude/workspace")
      assertFalse(Files.readString(workspace.resolve("arguments.txt")).contains("--resume"))
      assertTrue(
        Files.readString(workspace.resolve("prompt.txt")).contains("Latest design snapshot")
      )
      first.close()
      val otherProvider =
        JvmLocalAgentHost(
          storage,
          "viewer",
          this,
          true,
          { "Snapshot" },
          { mapOf(LocalHarness.Codex to cli, LocalHarness.Claude to cli) },
        )
      assertEquals("codex", otherProvider.selected)
      assertTrue(otherProvider.conversation.session.messages.isEmpty())
      otherProvider.conversation.comments(DesignCommentBoard(), true)
      otherProvider.conversation.monitor(true)
      assertTrue(otherProvider.conversation.monitoring)
      otherProvider.select("claude")
      assertEquals(2, otherProvider.conversation.session.messages.size)
      assertFalse(otherProvider.conversation.monitoring)
      otherProvider.close()
      val reopened = host()
      assertEquals(2, reopened.conversation.session.messages.size)
      assertFalse(reopened.conversation.monitoring)
      reopened.conversation.send("Follow up")
      withTimeout(5_000) { while (reopened.conversation.busy) delay(10) }
      assertTrue(Files.readString(workspace.resolve("arguments.txt")).contains("--resume\n$id"))
      assertFalse(Files.readString(workspace.resolve("prompt.txt")).contains("Review this"))
      reopened.conversation.clear()
      reopened.conversation.send("New conversation")
      withTimeout(5_000) { while (reopened.conversation.busy) delay(10) }
      assertFalse(Files.readString(workspace.resolve("arguments.txt")).contains("--resume"))
      assertFalse(Files.readString(storage.resolve("claude/chat.json")).contains("Review this"))
      reopened.close()
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `failed native turns are never resumed after reopening`() = runBlocking {
    if (System.getProperty("os.name").startsWith("Windows")) return@runBlocking
    val directory = Files.createTempDirectory("local-failure")
    val id = "c8a44279-d005-43d4-a2a2-a7dc4a380000"
    val cli = directory.resolve("claude")
    fun writeCli(failed: Boolean) {
      Files.writeString(
        cli,
        """#!/bin/sh
printf '%s\n' "${'$'}@" > arguments.txt
cat > prompt.txt
printf '%s' '{"type":"result","is_error":$failed,"result":"${if (failed) "sensitive-error" else "Review"}","session_id":"$id"}'
""",
      )
      cli.toFile().setExecutable(true)
    }
    val storage = directory.resolve("history")
    fun host() =
      JvmLocalAgentHost(
        storage,
        "viewer",
        this,
        false,
        { "Snapshot" },
        { mapOf(LocalHarness.Claude to cli) },
      )
    try {
      writeCli(false)
      val first = host()
      first.conversation.send("First")
      withTimeout(5_000) { while (first.conversation.busy) delay(10) }
      writeCli(true)
      first.conversation.send("Failed turn")
      withTimeout(5_000) { while (first.conversation.busy) delay(10) }
      assertFalse(first.conversation.notice.orEmpty().contains("sensitive-error"))
      assertFalse(Files.readString(storage.resolve("claude/chat.json")).contains(id))
      first.close()
      writeCli(false)
      val restored = host()
      restored.conversation.send("Retry")
      withTimeout(5_000) { while (restored.conversation.busy) delay(10) }
      assertFalse(
        Files.readString(storage.resolve("claude/workspace/arguments.txt")).contains("--resume")
      )
      assertTrue(Files.readString(storage.resolve("claude/workspace/prompt.txt")).contains("First"))
      restored.close()
    } finally {
      directory.toFile().deleteRecursively()
    }
  }
}
