package ee.schimke.composeai.uibuilder.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.LightVirtualFile
import ee.schimke.composeai.uibuilder.desktop.OfflineCatalog
import ee.schimke.composeai.uibuilder.desktop.OfflineUiBuilderSession
import ee.schimke.composeai.uibuilder.desktop.RemoteUiBuilderConnection
import ee.schimke.composeai.uibuilder.desktop.RemoteUiBuilderDesign
import ee.schimke.composeai.uibuilder.desktop.UiBuilderSession
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
    val writer = ProjectDesignWriter(file)
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
        binding.selection.reportStatus("external JSON is not a valid DesignDocumentV1")
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

/** Recognizes a design by its declared document shape, not by where its JSON file is stored. */
internal fun isProjectDesign(file: VirtualFile): Boolean {
  if (file.isDirectory || file.extension != "json") return false
  return readProjectDesign(file)?.let { document ->
    document.schema in supportedProjectDesignSchemas &&
      runCatching { OfflineCatalog.forSystem(document.catalogPin.systemId) }.isSuccess
  } == true
}

/** The document declarations this offline v1 editor can safely load and write back. */
private val supportedProjectDesignSchemas =
  setOf(
    "compose-ui-builder-document/v1",
    "compose-ui-builder-document/v1-candidate",
  )

private fun readProjectDesign(file: VirtualFile): DesignDocumentV1? = runCatching {
  val text = file.inputStream.reader().use { it.readText() }
  projectDesignJson.decodeFromString(
    DesignDocumentV1.serializer(),
    text,
  )
}
  .getOrNull()

private class ProjectDesignWriter(private val file: VirtualFile) {
  private var expectedModificationStamp = file.modificationStamp
  @Volatile
  var isWriting: Boolean = false
    private set

  fun matches(modificationStamp: Long): Boolean = expectedModificationStamp == modificationStamp

  fun adopt(modificationStamp: Long) {
    expectedModificationStamp = modificationStamp
  }

  fun write(document: DesignDocumentV1) {
    val bytes = projectDesignJson.encodeToString(document).encodeToByteArray()
    val write = {
      WriteAction.run<RuntimeException> {
        check(!FileDocumentManager.getInstance().isFileModified(file)) {
          "${file.name} has unsaved JSON changes; save or revert them and reopen the visual editor"
        }
        check(file.modificationStamp == expectedModificationStamp) {
          "${file.name} changed outside the visual editor; reopen it before editing"
        }
        file.setBinaryContent(bytes)
        expectedModificationStamp = file.modificationStamp
      }
    }
    val application = ApplicationManager.getApplication()
    isWriting = true
    try {
      if (application.isDispatchThread) write() else application.invokeAndWait(write)
    } finally {
      isWriting = false
    }
  }
}

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
design. Read and edit that JSON file directly and preserve its schema, id and catalog pin. IntelliJ
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
