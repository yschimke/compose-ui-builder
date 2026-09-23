package ee.schimke.composeai.uibuilder.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.Alarm
import ee.schimke.composeai.uibuilder.host.OfflineCatalog
import ee.schimke.composeai.uibuilder.host.OfflineUiBuilderSession
import ee.schimke.composeai.uibuilder.host.RemoteUiBuilderConnection
import ee.schimke.composeai.uibuilder.host.RemoteUiBuilderDesign
import ee.schimke.composeai.uibuilder.host.UiBuilderSession
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.toUiBuilderDocument
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class UiBuilderSessionSelection(
  initialSession: UiBuilderSession,
  val title: String,
  initialCatalog: OfflineCatalog,
  val projectFile: VirtualFile? = null,
  val agentPrompt: String,
) {
  var catalog: OfflineCatalog = initialCatalog
    private set

  private val mutableSession = MutableStateFlow(initialSession)
  val session = mutableSession.asStateFlow()
  val currentSession: UiBuilderSession
    get() = mutableSession.value

  private val mutableStatus = MutableStateFlow<String?>(null)
  val status = mutableStatus.asStateFlow()

  /** Visual editors currently showing this session; the Preview view borrows it and is not one. */
  internal var openEditors: Int = 0

  fun replaceSession(replacement: UiBuilderSession, replacementCatalog: OfflineCatalog = catalog) {
    val previous = mutableSession.value
    catalog = replacementCatalog
    mutableSession.value = replacement
    previous.close()
  }

  fun reportStatus(message: String?) {
    mutableStatus.value = message
  }
}

private data class ProjectDesignBinding(
  val selection: UiBuilderSessionSelection,
  val writer: ProjectDesignWriter,
  var reloadScheduled: Boolean = false,
)

/** Project lifetime shared by visual editors and the active Preview tool-window view. */
internal class UiBuilderProjectService(private val project: Project) : Disposable {
  private val catalogSessions = mutableMapOf<OfflineCatalog, UiBuilderSessionSelection>()
  private val projectSessions = mutableMapOf<String, ProjectDesignBinding>()
  private val remoteSessions = mutableMapOf<String, UiBuilderSessionSelection>()
  private val files = mutableMapOf<OfflineCatalog, UiBuilderVirtualFile>()
  private val remoteFiles = mutableMapOf<String, UiBuilderRemoteVirtualFile>()
  private val mutableActiveSession = MutableStateFlow<UiBuilderSessionSelection?>(null)
  val activeSession = mutableActiveSession.asStateFlow()
  private var previewToolWindow: ToolWindow? = null

  init {
    project.messageBus
      .connect(this)
      .subscribe(
        VirtualFileManager.VFS_CHANGES,
        object : BulkFileListener {
          override fun after(events: List<VFileEvent>) {
            events.mapNotNull { it.file }.distinctBy { it.url }.forEach(::projectFileChanged)
          }
        },
      )
  }

  fun catalogSession(catalog: OfflineCatalog): UiBuilderSessionSelection =
    catalogSessions.getOrPut(catalog) {
      UiBuilderSessionSelection(
        initialSession =
          OfflineUiBuilderSession(
            storagePath = projectStoragePath(project).resolve(catalog.systemId),
            catalogSystemId = catalog.systemId,
          ),
        title = catalog.displayName,
        initialCatalog = catalog,
        agentPrompt =
          "Open the active Compose UI Builder workspace in IntelliJ. It is an IDE-local scratch " +
            "design and is not available to external agents; ask me to save it under " +
            "ui-builder/designs first.",
      )
    }

  fun projectSession(file: VirtualFile): UiBuilderSessionSelection {
    projectSessions[file.url]?.let {
      return it.selection
    }
    val document =
      requireNotNull(readProjectDesign(file)) { "${file.path} is not a UI Builder design" }
    val catalog = OfflineCatalog.forSystem(document.catalogPin.systemId)
    val writer =
      ProjectDesignWriter(file, this) { failure ->
        projectSessions[file.url]?.selection?.reportStatus(failure?.let { "not saved: $it" })
      }
    val selection =
      UiBuilderSessionSelection(
        initialSession =
          OfflineUiBuilderSession.projectDocument(document.toUiBuilderDocument()) { committed ->
            writer.write(committed)
          },
        title = document.title.ifBlank { document.id },
        initialCatalog = catalog,
        projectFile = file,
        agentPrompt = projectAgentPrompt(file),
      )
    projectSessions[file.url] = ProjectDesignBinding(selection, writer)
    return selection
  }

  fun openRemoteDesign(connection: RemoteUiBuilderConnection, design: RemoteUiBuilderDesign) {
    val key = "${connection.serverOrigin}|${design.designId}"
    val selection =
      remoteSessions.getOrPut(key) {
        UiBuilderSessionSelection(
          initialSession = connection.openDesign(design),
          title = design.title.ifBlank { design.designId },
          initialCatalog = OfflineCatalog.forSystem(design.catalogSystemId),
          agentPrompt = remoteAgentPrompt(connection, design),
        )
      }
    val file =
      remoteFiles.getOrPut(key) {
        UiBuilderRemoteVirtualFile(selection, connection.serverOrigin.toString())
      }
    FileEditorManager.getInstance(project).openFile(file, true)
    activate(selection)
  }

  fun openEditor(catalog: OfflineCatalog) {
    val file = files.getOrPut(catalog) { UiBuilderVirtualFile(catalog) }
    FileEditorManager.getInstance(project).openFile(file, true)
    activate(catalogSession(catalog))
  }

  fun attachPreviewToolWindow(toolWindow: ToolWindow) {
    previewToolWindow = toolWindow
  }

  fun activate(selection: UiBuilderSessionSelection) {
    mutableActiveSession.value = selection
    previewToolWindow?.contentManager?.findContent(PREVIEW_CONTENT)?.let { content ->
      previewToolWindow?.contentManager?.setSelectedContent(content, false)
    }
  }

  /** Called on the EDT when a visual editor starts showing [selection]. */
  fun retain(selection: UiBuilderSessionSelection) {
    selection.openEditors++
  }

  /**
   * Called on the EDT when a visual editor stops showing [selection].
   *
   * The last editor to close a design closes its session, so a closed tab stops holding a remote
   * socket or a project-file writer until the project closes. Reopening builds a fresh session from
   * the same source: a catalog scratch design from its local store, a project design from its file,
   * a remote design from the server.
   */
  fun release(selection: UiBuilderSessionSelection) {
    if (--selection.openEditors > 0) return
    projectSessions.values.filter { it.selection === selection }.forEach { it.writer.flush() }
    catalogSessions.values.remove(selection)
    projectSessions.values.removeIf { it.selection === selection }
    remoteSessions.entries
      .filter { it.value === selection }
      .forEach { (key, _) ->
        remoteSessions.remove(key)
        remoteFiles.remove(key)
      }
    if (mutableActiveSession.value === selection) mutableActiveSession.value = null
    selection.currentSession.close()
  }

  private fun projectFileChanged(file: VirtualFile) {
    val binding = projectSessions[file.url] ?: return
    if (binding.writer.isWriting || binding.writer.matches(file.modificationStamp)) return
    if (binding.reloadScheduled) return
    binding.reloadScheduled = true
    ApplicationManager.getApplication().invokeLater {
      binding.reloadScheduled = false
      if (project.isDisposed || !file.isValid) return@invokeLater
      if (binding.writer.matches(file.modificationStamp)) return@invokeLater
      val document = readProjectDesign(file)
      if (document == null) {
        binding.selection.reportStatus("external design source is not a valid DesignDocumentV1")
        return@invokeLater
      }
      val catalog = runCatching {
        OfflineCatalog.forSystem(document.catalogPin.systemId)
      }
        .getOrNull()
      if (catalog == null) {
        binding.selection.reportStatus("external JSON names a catalog this plugin cannot open")
        return@invokeLater
      }
      // The file on disk is now the design. An edit still waiting to be written was made against
      // the version it replaced, and writing it would silently undo the external change.
      binding.writer.discardPending()
      binding.writer.adopt(file.modificationStamp)
      val replacement =
        OfflineUiBuilderSession.projectDocument(document.toUiBuilderDocument()) { committed ->
          binding.writer.write(committed)
        }
      binding.selection.reportStatus(null)
      binding.selection.replaceSession(replacement, catalog)
    }
  }

  override fun dispose() {
    projectSessions.values.forEach { binding -> runCatching { binding.writer.flush() } }
    (catalogSessions.values + projectSessions.values.map { it.selection } + remoteSessions.values)
      .map { it.currentSession }
      .distinct()
      .forEach(UiBuilderSession::close)
    catalogSessions.clear()
    projectSessions.clear()
    remoteSessions.clear()
    remoteFiles.clear()
    mutableActiveSession.value = null
    previewToolWindow = null
  }
}

internal class UiBuilderVirtualFile(val catalog: OfflineCatalog) :
  LightVirtualFile("Compose UI Builder — ${catalog.displayName}")

internal class UiBuilderRemoteVirtualFile(
  val selection: UiBuilderSessionSelection,
  server: String,
) : LightVirtualFile("Compose UI Builder — ${selection.title} · ${java.net.URI(server).host}")

internal val OfflineCatalog.displayName: String
  get() =
    when (this) {
      OfflineCatalog.M3 -> "Material 3"
      OfflineCatalog.WEAR_M3 -> "Wear M3"
      OfflineCatalog.REMOTE_M3 -> "Wear widgets"
    }

/**
 * Recognizes a visual-editor design by its declared document schema and supported catalog.
 *
 * Only files that pass [isUiBuilderDesignFile]'s header check are decoded, and each decode is
 * cached against the file's modification stamp, so an ordinary JSON file never pays for a parse and
 * a design pays once per saved version however often IntelliJ asks.
 */
internal fun isProjectDesign(file: VirtualFile): Boolean {
  if (!isUiBuilderDesignFile(file)) return false
  return cachedByStamp(file, projectDesignKey) { candidate ->
    readProjectDesign(candidate)?.let { document ->
      document.schema in supportedProjectDesignSchemas &&
        OfflineCatalog.entries.any { it.systemId == document.catalogPin.systemId }
    }
  } == true
}

/**
 * Identifies source files that belong to the UI Builder document family for JSON Schema support.
 *
 * A `.uid` file is one by extension. A `.json` file is one when the first
 * [DESIGN_HEADER_SNIFF_BYTES] declare a UI Builder schema; nothing past that window is read.
 */
internal fun isUiBuilderDesignFile(file: VirtualFile): Boolean {
  if (file.isDirectory || file.extension !in supportedProjectDesignExtensions) return false
  return file.extension == UI_BUILDER_DESIGN_EXTENSION ||
    cachedByStamp(file, designSchemaKey, ::readDesignSchema) in supportedProjectDesignSchemas
}

private class StampedValue<T : Any>(val modificationStamp: Long, val value: T?)

private val designSchemaKey = Key.create<StampedValue<String>>("uiBuilder.schema")

/** The verdict only, not the document: a decoded design is not worth holding on every file. */
private val projectDesignKey = Key.create<StampedValue<Boolean>>("uiBuilder.isProjectDesign")

private fun <T : Any> cachedByStamp(
  file: VirtualFile,
  key: Key<StampedValue<T>>,
  read: (VirtualFile) -> T?,
): T? {
  val stamp = file.modificationStamp
  file.getUserData(key)?.let { cached ->
    if (cached.modificationStamp == stamp) return cached.value
  }
  return read(file).also { file.putUserData(key, StampedValue(stamp, it)) }
}

private fun readDesignSchema(file: VirtualFile): String? = runCatching {
  file.inputStream.use { input ->
    String(input.readNBytes(DESIGN_HEADER_SNIFF_BYTES), Charsets.UTF_8)
  }
}
  .getOrNull()
  ?.let(::sniffDesignSchema)

/** The document declarations this offline v1 editor can safely load and write back. */
private val supportedProjectDesignSchemas =
  setOf(
    "compose-ui-builder-document/v1",
    "compose-ui-builder-document/v1-candidate",
  )

private const val UI_BUILDER_DESIGN_EXTENSION = "uid"
private val supportedProjectDesignExtensions = setOf("json", UI_BUILDER_DESIGN_EXTENSION)

private fun readProjectDesign(file: VirtualFile): DesignDocumentV1? = runCatching {
  val text = file.inputStream.reader().use { it.readText() }
  projectDesignJson.decodeFromString(
    DesignDocumentV1.serializer(),
    text,
  )
}
  .getOrNull()

/**
 * Writes a project design back to its file, coalescing a burst of edits into one write.
 *
 * The session commits one document per accepted operation, and dragging a slider or typing into a
 * text property is dozens of those a second. Each used to be a synchronous write action on the EDT
 * — a VFS event, a document reload and a VCS status change per keystroke. Now the latest committed
 * document waits [WRITE_DELAY_MS] and only the last one is written; closing the editor or the
 * project flushes whatever is pending, so nothing accepted is lost.
 *
 * The write can still be refused — unsaved source edits, or a file changed outside the editor — and
 * because it no longer happens inside the session's commit, the answer goes to [onResult] (null for
 * a successful write) rather than out of the session as an exception.
 */
private class ProjectDesignWriter(
  private val file: VirtualFile,
  parent: Disposable,
  private val onResult: (failure: String?) -> Unit,
) {
  private var expectedModificationStamp = file.modificationStamp
  private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
  @Volatile private var pending: DesignDocumentV1? = null

  @Volatile
  var isWriting: Boolean = false
    private set

  fun matches(modificationStamp: Long): Boolean = expectedModificationStamp == modificationStamp

  fun adopt(modificationStamp: Long) {
    expectedModificationStamp = modificationStamp
  }

  /** Schedules [document] to be written; a later call before the write replaces it. */
  fun write(document: DesignDocumentV1) {
    pending = document
    alarm.cancelAllRequests()
    alarm.addRequest(::flush, WRITE_DELAY_MS)
  }

  /** Drops a scheduled write without writing it. */
  fun discardPending() {
    alarm.cancelAllRequests()
    pending = null
  }

  /** Writes the pending document now, on the EDT. Nothing pending is a no-op. */
  fun flush() {
    alarm.cancelAllRequests()
    val document = pending ?: return
    pending = null
    val bytes = projectDesignJson.encodeToString(document).encodeToByteArray()
    isWriting = true
    val failure =
      try {
        WriteAction.run<RuntimeException> {
          check(!FileDocumentManager.getInstance().isFileModified(file)) {
            "${file.name} has unsaved source changes; save or revert them and reopen the visual " +
              "editor"
          }
          check(file.modificationStamp == expectedModificationStamp) {
            "${file.name} changed outside the visual editor; reopen it before editing"
          }
          file.setBinaryContent(bytes)
          expectedModificationStamp = file.modificationStamp
        }
        null
      } catch (refused: Exception) {
        refused.message ?: refused::class.simpleName
      } finally {
        isWriting = false
      }
    onResult(failure)
  }
}

private const val WRITE_DELAY_MS = 300

private val projectDesignJson = Json {
  classDiscriminator = "type"
  encodeDefaults = true
  explicitNulls = true
  ignoreUnknownKeys = true
  prettyPrint = true
  prettyPrintIndent = "  "
}

internal const val PREVIEW_CONTENT = "Preview"

private fun projectAgentPrompt(file: VirtualFile): String =
  """Work on the active Compose UI Builder design stored at `${file.path}`.
Read the `compose-ui-builder` skill first. This is a checked-in DesignDocumentV1, not a live server
design. Read and edit that design source directly and preserve its schema, id and catalog pin. IntelliJ
automatically adopts each valid saved version in the open visual editor and Preview."""

private fun remoteAgentPrompt(
  connection: RemoteUiBuilderConnection,
  design: RemoteUiBuilderDesign,
): String =
  """Work on Compose UI Builder design `${design.designId}` at ${connection.serverOrigin}.
Read the `compose-ui-builder` skill first, connect to ${connection.mcpEndpoint}, request your own
short-lived ui-builder-read, ui-builder-write and ui-builder-export grant, then open design
`${design.designId}`. Do not ask for or reuse the IDE's bearer token."""

private fun projectStoragePath(project: Project): Path =
  Path.of(PathManager.getSystemPath(), "compose-ui-builder", project.locationHash)
