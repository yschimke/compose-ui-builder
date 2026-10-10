package ee.schimke.composeai.uibuilder.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ee.schimke.composeai.uibuilder.editor.*
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Opt-in local runner, owned and disposed by a desktop window or IDE design view. */
class JvmLocalAgentHost
internal constructor(
  private val storageRoot: Path,
  private val actorId: String,
  private val scope: CoroutineScope,
  private val monitoringAvailable: Boolean,
  private val context: () -> String,
  private val discover: () -> Map<LocalHarness, Path>,
) : UiBuilderAgentHost, UiBuilderLocalAgentHost, AutoCloseable {
  constructor(
    storageRoot: Path,
    actorId: String,
    scope: CoroutineScope,
    monitoringAvailable: Boolean,
    context: () -> String,
  ) : this(storageRoot, actorId, scope, monitoringAvailable, context, { discoverLocalHarnesses() })

  private var executables by mutableStateOf(discover())
  private var harness by mutableStateOf(executables.keys.firstOrNull() ?: LocalHarness.Codex)
  override val local
    get() = this

  override val monitoringHandoffAvailable = false

  override val harnesses
    get() = LocalHarness.entries.map { UiBuilderLocalHarness(it.id, it.label, it in executables) }

  override val selected
    get() = harness.id

  override var preferences by mutableStateOf(UiBuilderAgentPreferences(connectedBefore = true))
    private set

  override val agents: List<UiBuilderCollaborator> = emptyList()
  override var conversation by mutableStateOf(newChat())
    private set

  private fun newChat() = UiBuilderChatController(LocalChatHost(harness), scope, actorId)

  override fun select(id: String) {
    val next = LocalHarness.entries.firstOrNull { it.id == id } ?: return
    if (next == harness || conversation.busy) return
    conversation.stop()
    harness = next
    conversation = newChat()
  }

  override fun refresh() {
    if (conversation.busy) return
    conversation.stop()
    executables = discover()
    conversation = newChat()
  }

  override fun close() = conversation.stop()

  override fun prompt(includeSetup: Boolean, instructions: String) = buildString {
    append(UiBuilderChatController.DEFAULT_INSTRUCTIONS).append('\n')
    if (includeSetup)
      append(
        "Use your own local agent login. This is a design snapshot, not a hosted MCP session.\n"
      )
    append(instructions.take(4_000)).append('\n').append(context().take(64_000))
  }

  override fun save(preferences: UiBuilderAgentPreferences): String? {
    this.preferences = preferences
    return null
  }

  override suspend fun copy(text: String): String = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    "Copied"
  }
    .getOrDefault("Could not copy")

  override fun openSetup() {
    runCatching {
      Desktop.getDesktop()
        .browse(
          URI(
            if (harness == LocalHarness.Codex) "https://developers.openai.com/codex/cli/"
            else "https://code.claude.com/docs/en/setup"
          )
        )
    }
  }

  override fun connectVsCode() = openSetup()

  private inner class LocalChatHost(private val provider: LocalHarness) : UiBuilderChatHost {
    private val directory = storageRoot.resolve(provider.id)
    private val store = directory.resolve("chat.json")
    private var record = loadRecord()
    override val connected
      get() = provider in executables

    override val rememberConnection = false
    override val monitoringAvailable = this@JvmLocalAgentHost.monitoringAvailable
    override val failureNotice = "Agent failed. Check its local login and update the CLI."

    override fun connect() = refresh()

    override fun useKey(key: String) = Unit

    override fun rememberConnection(remember: Boolean) = Unit

    override fun disconnect() = conversation.stop()

    override fun load() = record.chat

    override fun save(session: UiBuilderChatSession): String? {
      record = record.copy(chat = session)
      return persist()
    }

    override fun clear(): String? {
      // Forget only this app's session reference. Native CLI transcripts belong to the user.
      record = LocalAgentRecord()
      return persist()
    }

    override suspend fun complete(model: String, messages: List<UiBuilderChatMessage>): String {
      val executable = requireNotNull(executables[provider])
      val resume = record.sessionId
      // A failed/canceled native turn may have changed its transcript: next time start from
      // the app's last known history, rather than silently resuming that partial turn.
      record = record.copy(sessionId = null)
      check(persist() == null) { "Cannot save local session reference" }
      val input = buildString {
        append(UiBuilderChatController.DEFAULT_INSTRUCTIONS).append('\n')
        append(
          "Use only this supplied snapshot. No tool calls, local file access or shared writes.\n"
        )
        append(preferences.instructions().take(4_000)).append('\n')
        append(context().take(64_000)).append('\n')
        val turns = if (resume == null) messages else messages.takeLast(1)
        var remaining = 48_000
        turns
          .asReversed()
          .takeWhile {
            remaining -= it.content.length
            remaining >= 0
          }
          .asReversed()
          .forEach { append(it.role).append(": ").append(it.content).append('\n') }
      }
      val result =
        LocalAgentRunner().run(provider, executable, directory.resolve("workspace"), input, resume)
      currentCoroutineContext().ensureActive()
      record = record.copy(sessionId = result.sessionId ?: resume)
      return result.text
    }

    private fun loadRecord(): LocalAgentRecord = runCatching {
      require(Files.size(store) <= 600_000)
      val saved = Json.decodeFromString<LocalAgentRecord>(Files.readString(store))
      saved.sessionId?.let { java.util.UUID.fromString(it) }
      saved.copy(
        chat =
          saved.chat.copy(
            messages =
              saved.chat.messages
                .filter { it.role in setOf("user", "assistant") }
                .takeLast(40)
                .map { it.copy(content = it.content.take(12_000)) },
            reviewedComments =
              saved.chat.reviewedComments.entries.take(500).associate { it.toPair() },
          )
      )
    }
      .getOrDefault(LocalAgentRecord())

    private fun persist(): String? = runCatching {
      Files.createDirectories(directory)
      val data = Json.encodeToString(LocalAgentRecord.serializer(), record)
      require(data.toByteArray(Charsets.UTF_8).size <= 600_000)
      val temporary = Files.createTempFile(directory, "chat-", ".tmp")
      try {
        runCatching {
          Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
        }
        Files.writeString(temporary, data)
        Files.move(temporary, store, StandardCopyOption.REPLACE_EXISTING)
      } finally {
        Files.deleteIfExists(temporary)
      }
      null
    }
      .getOrDefault("Could not save this chat.")
  }
}

@Serializable
private data class LocalAgentRecord(
  val chat: UiBuilderChatSession = UiBuilderChatSession(),
  val sessionId: String? = null,
)
