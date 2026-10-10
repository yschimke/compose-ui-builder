package ee.schimke.composeai.uibuilder.host

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class LocalHarness(val id: String, val label: String) {
  Codex("codex", "Codex"),
  Claude("claude", "Claude Code"),
  OpenCode("opencode", "OpenCode");

  fun command(executable: Path, sessionId: String?): List<String> {
    require(executable.isAbsolute)
    sessionId?.let { validateSessionId(it) }
    return when (this) {
      Codex ->
        buildList {
          add(executable.toString())
          addAll(
            listOf(
              "exec",
              "--json",
              "--skip-git-repo-check",
              "--ignore-user-config",
              "--ignore-rules",
              "--strict-config",
              "--sandbox",
              "read-only",
            )
          )
          // Ignore user/project integrations. Read-only is a second boundary if a tool is exposed.
          addAll(listOf("-c", "approval_policy=\"never\"", "-c", "web_search=\"disabled\""))
          for (feature in
            listOf(
              "shell_tool",
              "unified_exec",
              "view_image",
              "code_mode",
              "code_mode_host",
              "code_mode_only",
              "hooks",
              "multi_agent",
              "multi_agent_v2",
              "apps",
              "plugins",
              "remote_plugin",
              "in_app_browser",
              "browser_annotation_api",
              "browser_use",
              "browser_use_external",
              "browser_use_full_cdp_access",
              "computer_use",
              "image_generation",
              "skill_mcp_dependency_install",
              "skill_search",
              "workspace_dependencies",
              "daemon_auto_start",
              "in_app_chat",
              "in_app_dictation",
              "in_app_voice",
              "in_app_local_automation",
              "in_app_updates",
              "goals",
              "realtime_conversation",
            )) {
            addAll(listOf("-c", "features.$feature=false"))
          }
          if (sessionId != null) addAll(listOf("resume", sessionId))
          add("-")
        }
      OpenCode ->
        buildList {
          add(executable.toString())
          addAll(listOf("run", "--format", "json", "--agent", "compose-chat"))
          if (sessionId != null) addAll(listOf("--session", sessionId))
        }
      Claude ->
        buildList {
          add(executable.toString())
          addAll(
            listOf(
              "--print",
              "--output-format",
              "json",
              "--safe-mode",
              "--restricted",
              "--tools",
              "",
              "--disable-slash-commands",
              "--strict-mcp-config",
              "--mcp-config",
              "{\"mcpServers\":{}}",
              "--setting-sources",
              "",
              "--permission-mode",
              "plan",
              "--permission-prompts",
              "none",
              "--max-budget-usd",
              "1",
            )
          )
          if (sessionId != null) addAll(listOf("--resume", sessionId))
        }
    }
  }

  fun validateSessionId(id: String) {
    if (this == OpenCode) require(id.matches(Regex("ses_[A-Za-z0-9]{1,100}")))
    else UUID.fromString(id)
  }

  fun parse(output: String): LocalAgentAnswer {
    var sessionId: String? = null
    var answer: String? = null
    var completed = false
    if (this == Claude) {
      val result = Json.parseToJsonElement(output).jsonObject
      require(result["type"]?.jsonPrimitive?.contentOrNull == "result")
      require(result["is_error"]?.jsonPrimitive?.booleanOrNull == false)
      sessionId = result.string("session_id")
      answer = result.string("result")
      completed = true
    } else if (this == OpenCode) {
      val parts = mutableListOf<String>()
      for (line in output.lineSequence().filter { it.isNotBlank() }) {
        val event = Json.parseToJsonElement(line).jsonObject
        event.string("sessionID")?.let {
          validateSessionId(it)
          require(sessionId == null || sessionId == it)
          sessionId = it
        }
        when (event.string("type")) {
          "text" -> event["part"]?.jsonObject?.string("text")?.let(parts::add)
          "step_finish" -> completed = event["part"]?.jsonObject?.string("reason") == "stop"
          "tool_use",
          "error" -> error("Local agent failed")
        }
      }
      require(sessionId != null)
      answer = parts.joinToString("\n")
    } else {
      for (line in output.lineSequence().filter { it.isNotBlank() }) {
        val event = Json.parseToJsonElement(line).jsonObject
        when (event.string("type")) {
          "thread.started" -> sessionId = event.string("thread_id")
          "item.completed" -> {
            val item = event["item"]?.jsonObject
            if (item?.string("type") == "agent_message") answer = item.string("text")
          }
          "turn.completed" -> completed = true
          "error",
          "turn.failed" -> error("Local agent failed")
        }
      }
    }
    require(completed && !answer.isNullOrBlank()) { "Local agent returned no answer" }
    sessionId?.let { validateSessionId(it) }
    return LocalAgentAnswer(answer, sessionId)
  }
}

private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull

internal data class LocalAgentAnswer(val text: String, val sessionId: String?)

/** Absolute executable paths only; never launch a shell or consult the design's directory. */
internal fun discoverLocalHarnesses(
  path: String = System.getenv("PATH").orEmpty(),
  home: Path = Path.of(System.getProperty("user.home")),
  windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
): Map<LocalHarness, Path> {
  val directories =
    path
      .split(java.io.File.pathSeparator)
      .filter { it.isNotBlank() }
      .mapNotNull { runCatching { Path.of(it) }.getOrNull() }
      .filter { it.isAbsolute } +
      listOf(
        home.resolve(".local/bin"),
        home.resolve(".cargo/bin"),
        home.resolve(".opencode/bin"),
        Path.of("/opt/homebrew/bin"),
        Path.of("/usr/local/bin"),
      )
  return LocalHarness.entries
    .mapNotNull { harness ->
      directories
        .asSequence()
        .map { it.resolve(harness.id + if (windows) ".exe" else "") }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?.let { harness to it.toAbsolutePath().normalize() }
    }
    .toMap()
}

/** Only login, runtime and network settings reach a trusted harness; design-host tokens do not. */
internal fun localAgentEnvironment(
  environment: Map<String, String>,
  harness: LocalHarness,
): Map<String, String> {
  val names =
    setOf(
      "PATH",
      "HOME",
      "USER",
      "LOGNAME",
      "USERPROFILE",
      "APPDATA",
      "LOCALAPPDATA",
      "SYSTEMROOT",
      "WINDIR",
      "TEMP",
      "TMP",
      "TMPDIR",
      "LANG",
      "XDG_CONFIG_HOME",
      "XDG_DATA_HOME",
      "XDG_STATE_HOME",
      "XDG_RUNTIME_DIR",
      "DBUS_SESSION_BUS_ADDRESS",
      "HTTP_PROXY",
      "HTTPS_PROXY",
      "ALL_PROXY",
      "NO_PROXY",
      "SSL_CERT_FILE",
      "SSL_CERT_DIR",
      "NODE_EXTRA_CA_CERTS",
    )
  val login =
    when (harness) {
      LocalHarness.Codex -> setOf("CODEX_HOME", "OPENAI_API_KEY")
      LocalHarness.OpenCode -> emptySet()
      LocalHarness.Claude ->
        setOf(
          "CLAUDE_CONFIG_DIR",
          "ANTHROPIC_API_KEY",
          "ANTHROPIC_AUTH_TOKEN",
          "CLAUDE_CODE_OAUTH_TOKEN",
        )
    }
  return environment.filterKeys { it.uppercase() in names + login || it.startsWith("LC_") }
}

internal class UnsupportedLocalAgentException :
  IllegalStateException("Unsupported local CLI version")

internal class LocalAgentRunner(private val timeoutMillis: Long = 120_000) {
  suspend fun run(
    harness: LocalHarness,
    executable: Path,
    directory: Path,
    prompt: String,
    sessionId: String?,
  ): LocalAgentAnswer =
    withTimeout(timeoutMillis) {
      withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val builder =
          ProcessBuilder(harness.command(executable, sessionId)).directory(directory.toFile())
        val environment = localAgentEnvironment(builder.environment(), harness)
        builder.environment().clear()
        builder.environment().putAll(environment)
        if (!System.getProperty("os.name").startsWith("Windows")) {
          builder.environment()["PATH"] =
            (environment["PATH"].orEmpty().split(java.io.File.pathSeparator) +
                listOf("/opt/homebrew/bin", "/usr/local/bin"))
              .filter { it.isNotBlank() }
              .distinct()
              .joinToString(java.io.File.pathSeparator)
        }
        if (harness == LocalHarness.OpenCode) {
          // Keep the CLI's own data/auth store, but isolate executable user/project config.
          val config = directory.resolve("config").toAbsolutePath()
          Files.createDirectories(config)
          builder
            .environment()
            .putAll(
              mapOf(
                "XDG_CONFIG_HOME" to config.toString(),
                "OPENCODE_CONFIG_DIR" to config.resolve("opencode").toString(),
                "OPENCODE_DISABLE_PROJECT_CONFIG" to "true",
                "OPENCODE_DISABLE_AUTOUPDATE" to "true",
                "OPENCODE_PERMISSION" to "{\"*\":\"deny\"}",
                "OPENCODE_CONFIG_CONTENT" to
                  """{"autoupdate":false,"share":"disabled","permission":{"*":"deny"},"agent":{"compose-chat":{"mode":"primary","description":"Private design advice","permission":{"*":"deny"},"steps":1}}}""",
              )
            )
        }
        val process = builder.start()
        try {
          coroutineScope {
            // Register cancellation before writing or reading pipes, all of which can block.
            val exit =
              async(start = CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<Int> { continuation ->
                  continuation.invokeOnCancellation { kill(process) }
                  process.onExit().thenAccept {
                    if (continuation.isActive) continuation.resume(it.exitValue())
                  }
                }
              }
            val output = async(Dispatchers.IO) { boundedOutput(process.inputStream) }
            val errors = async(Dispatchers.IO) { boundedOutput(process.errorStream) }
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(prompt) }
            val status = exit.await()
            val text = output.await()
            val diagnostics = errors.await()
            if (status != 0) {
              val unsupported =
                listOf(
                    "unexpected argument",
                    "unknown field",
                    "unknown config",
                    "unrecognized option",
                  )
                  .any { diagnostics.contains(it, ignoreCase = true) }
              if (unsupported) throw UnsupportedLocalAgentException()
              error("Local agent failed")
            }
            ensureActive()
            harness.parse(text)
          }
        } finally {
          kill(process)
          process.inputStream.close()
          process.errorStream.close()
          process.outputStream.close()
        }
      }
    }

  private fun boundedOutput(input: java.io.InputStream): String {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      require(output.size() + count <= 1_000_000) { "Local agent output too large" }
      output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8)
  }

  private fun kill(process: Process) {
    runCatching {
      process.descendants().use { children ->
        val descendants = children.toList().asReversed()
        descendants.forEach { it.destroyForcibly() }
        // Let a waiting parent reap its children before killing it; otherwise a container's
        // init may retain zombies. The grace period is bounded even for an unresponsive CLI.
        if (descendants.isNotEmpty())
          process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
      }
    }
    process.destroyForcibly()
  }
}
