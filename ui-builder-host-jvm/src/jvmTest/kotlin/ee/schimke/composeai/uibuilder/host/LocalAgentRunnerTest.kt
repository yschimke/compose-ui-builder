package ee.schimke.composeai.uibuilder.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*

class LocalAgentRunnerTest {
  private val sessionId = "c8a44279-d005-43d4-a2a2-a7dc4a380000"
  private val result
    get() = """{"type":"result","is_error":false,"result":"Review","session_id":"$sessionId"}"""

  private fun fixture(directory: Path, body: String): Path {
    val executable = directory.resolve("fake-agent")
    Files.writeString(executable, "#!/bin/sh\n$body\n")
    assertTrue(executable.toFile().setExecutable(true))
    return executable
  }

  @Test
  fun `commands never put prompt text in argv and retain restrictions when resuming`() {
    for (harness in LocalHarness.entries) {
      val id = if (harness == LocalHarness.OpenCode) "ses_abc123" else sessionId
      val command = harness.command(Path.of("/trusted/agent"), id)
      assertEquals("/trusted/agent", command.first())
      assertTrue(id in command)
      assertFalse(command.any { "bypass" in it || "dangerously" in it })
      if (harness == LocalHarness.Codex) {
        assertTrue("read-only" in command)
        assertTrue("features.shell_tool=false" in command)
        assertTrue("features.hooks=false" in command)
        assertTrue("features.plugins=false" in command)
        assertTrue("features.apps=false" in command)
        assertTrue("features.browser_use=false" in command)
        assertTrue("features.computer_use=false" in command)
        assertTrue("features.daemon_auto_start=false" in command)
        assertTrue("--ignore-user-config" in command)
        assertTrue("approval_policy=\"never\"" in command)
      } else if (harness == LocalHarness.OpenCode) {
        assertTrue("compose-chat" in command)
        assertTrue("--session" in command)
        assertFalse("--continue" in command)
        assertFalse("--share" in command)
      } else {
        assertEquals("", command[command.indexOf("--tools") + 1])
        assertTrue("--strict-mcp-config" in command)
        assertTrue("--safe-mode" in command)
      }
      assertFailsWith<IllegalArgumentException> { harness.command(Path.of("agent"), "--dangerous") }
    }
  }

  @Test
  fun `only native agent results become replies and failures do not become conversation text`() {
    val codex =
      """{"type":"thread.started","thread_id":"$sessionId"}
{"type":"item.completed","item":{"type":"reasoning","text":"Private reasoning"}}
{"type":"item.completed","item":{"type":"agent_message","text":"Review"}}
{"type":"turn.completed"}"""
    assertEquals(LocalAgentAnswer("Review", sessionId), LocalHarness.Codex.parse(codex))
    assertEquals(LocalAgentAnswer("Review", sessionId), LocalHarness.Claude.parse(result))
    assertFails { LocalHarness.Codex.parse(codex.substringBefore("{\"type\":\"turn.completed\"}")) }
    assertFails { LocalHarness.Codex.parse("""{"type":"error","message":"sensitive"}""") }
    assertFails { LocalHarness.Claude.parse(result.replace("false", "true")) }
  }

  @Test
  fun `opencode accepts only completed private text and its own session ids`() {
    val events =
      """{"type":"step_start","sessionID":"ses_abc123"}
{"type":"reasoning","sessionID":"ses_abc123","part":{"text":"Private reasoning"}}
{"type":"text","sessionID":"ses_abc123","part":{"text":"Review"}}
{"type":"step_finish","sessionID":"ses_abc123","part":{"reason":"stop"}}"""
    assertEquals(LocalAgentAnswer("Review", "ses_abc123"), LocalHarness.OpenCode.parse(events))
    assertFails { LocalHarness.OpenCode.parse(events.replace("stop", "tool-calls")) }
    assertFails { LocalHarness.OpenCode.parse(events + "\n{\"type\":\"error\"}") }
    assertFails { LocalHarness.OpenCode.parse(events.replace("ses_abc123", "--continue")) }
    assertFails { LocalHarness.Claude.command(Path.of("/trusted/agent"), "ses_abc123") }
  }

  @Test
  fun `opencode isolates configuration while retaining native login storage`() = runBlocking {
    if (System.getProperty("os.name").startsWith("Windows")) return@runBlocking
    val directory = Files.createTempDirectory("opencode-input")
    try {
      val executable =
        fixture(
          directory,
          """
cat > prompt.txt
printf '%s' "${'$'}OPENCODE_CONFIG_CONTENT" > config.json
printf '%s' "${'$'}XDG_CONFIG_HOME" > config-home.txt
printf '%s' "${'$'}OPENCODE_PERMISSION" > permission.json
printf '%s\n' '{"type":"text","sessionID":"ses_abc123","part":{"text":"Review"}}' '{"type":"step_finish","sessionID":"ses_abc123","part":{"reason":"stop"}}'
""",
        )
      assertEquals(
        "Review",
        LocalAgentRunner().run(LocalHarness.OpenCode, executable, directory, "Snapshot", null).text,
      )
      assertEquals("Snapshot", Files.readString(directory.resolve("prompt.txt")))
      assertEquals(
        directory.resolve("config").toString(),
        Files.readString(directory.resolve("config-home.txt")),
      )
      assertEquals("{\"*\":\"deny\"}", Files.readString(directory.resolve("permission.json")))
      assertTrue(
        Files.readString(directory.resolve("config.json")).contains("\"share\":\"disabled\"")
      )
      assertTrue(
        localAgentEnvironment(
            mapOf("OPENCODE_CONFIG_CONTENT" to "untrusted", "ANTHROPIC_API_KEY" to "secret"),
            LocalHarness.OpenCode,
          )
          .isEmpty()
      )
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `discovery ignores the current directory and environment excludes design host secrets`() {
    val directory = Files.createTempDirectory("agent-discovery")
    try {
      val executable = directory.resolve("codex")
      Files.writeString(executable, "unused")
      executable.toFile().setExecutable(true)
      assertEquals(
        executable,
        discoverLocalHarnesses(directory.toString(), directory, false)[LocalHarness.Codex],
      )
      assertTrue(discoverLocalHarnesses(".:", directory, false).isEmpty())
      assertEquals(
        mapOf("HOME" to "/home/user", "ANTHROPIC_API_KEY" to "local-login"),
        localAgentEnvironment(
          mapOf(
            "HOME" to "/home/user",
            "ANTHROPIC_API_KEY" to "local-login",
            "OPENAI_API_KEY" to "other-provider-login",
            "COMPOSE_PREVIEW_TOKEN" to "secret",
            "GH_TOKEN" to "secret",
            "BASH_ENV" to "bad-hook",
            "CLAUDE_CODE_SKIP_PERMISSIONS" to "1",
          ),
          LocalHarness.Claude,
        ),
      )
      assertEquals(
        mapOf("CODEX_HOME" to "/home/user/.codex"),
        localAgentEnvironment(
          mapOf("CODEX_HOME" to "/home/user/.codex", "ANTHROPIC_API_KEY" to "other-provider-login"),
          LocalHarness.Codex,
        ),
      )
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `stdin stays data even when it contains shell syntax`() = runBlocking {
    if (System.getProperty("os.name").startsWith("Windows")) return@runBlocking
    val directory = Files.createTempDirectory("agent-input")
    try {
      val executable = fixture(directory, "cat > prompt.txt\nprintf '%s' '$result'")
      val prompt = "Review \$(touch injected) and `touch injected`\n--dangerously-skip-permissions"
      val answer = LocalAgentRunner().run(LocalHarness.Claude, executable, directory, prompt, null)
      assertEquals("Review", answer.text)
      assertEquals(prompt, Files.readString(directory.resolve("prompt.txt")))
      assertFalse(Files.exists(directory.resolve("injected")))
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `timeout and cancellation terminate the child process tree`() = runBlocking {
    if (System.getProperty("os.name").startsWith("Windows")) return@runBlocking
    val directory = Files.createTempDirectory("agent-cancel")
    try {
      val executable = fixture(directory, "cat >/dev/null\nsleep 60 &\necho \$! > child.pid\nwait")
      val running = async {
        LocalAgentRunner().run(LocalHarness.Claude, executable, directory, "hello", null)
      }
      withTimeout(5_000) { while (!Files.exists(directory.resolve("child.pid"))) delay(10) }
      val pid = Files.readString(directory.resolve("child.pid")).trim().toLong()
      running.cancelAndJoin()
      withTimeout(5_000) {
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10)
      }
      assertFailsWith<TimeoutCancellationException> {
        LocalAgentRunner(100).run(LocalHarness.Claude, executable, directory, "hello", null)
      }
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `oversized output and nonzero exit fail without showing provider diagnostics`() =
    runBlocking {
      if (System.getProperty("os.name").startsWith("Windows")) return@runBlocking
      val directory = Files.createTempDirectory("agent-output")
      try {
        val large = fixture(directory, "cat >/dev/null\nhead -c 1000001 /dev/zero")
        assertFails {
          LocalAgentRunner(5_000).run(LocalHarness.Claude, large, directory, "hello", null)
        }
        val failed = fixture(directory, "cat >/dev/null\necho sensitive-provider-error >&2\nexit 1")
        val failure = assertFails {
          LocalAgentRunner().run(LocalHarness.Claude, failed, directory, "hello", null)
        }
        assertFalse(failure.message.orEmpty().contains("sensitive-provider-error"))
        val unsupported =
          fixture(
            directory,
            "cat >/dev/null\necho 'unknown field features.new_flag sensitive-token' >&2\nexit 1",
          )
        val versionFailure =
          assertFailsWith<UnsupportedLocalAgentException> {
            LocalAgentRunner().run(LocalHarness.Codex, unsupported, directory, "hello", null)
          }
        assertFalse(versionFailure.message.orEmpty().contains("sensitive-token"))
      } finally {
        directory.toFile().deleteRecursively()
      }
    }
}
