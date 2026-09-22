package ee.schimke.composeai.uibuilder.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.LightVirtualFile
import ee.schimke.composeai.uibuilder.desktop.OfflineCatalog
import ee.schimke.composeai.uibuilder.desktop.OfflineUiBuilderSession
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.toUiBuilderDocument
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class UiBuilderSessionSelection(
  val session: OfflineUiBuilderSession,
  val title: String,
  val catalog: OfflineCatalog,
  val projectFile: VirtualFile? = null,
)

/** Project lifetime shared by visual editors and the active Preview tool-window view. */
internal class UiBuilderProjectService(private val project: Project) : Disposable {
  private val catalogSessions = mutableMapOf<OfflineCatalog, UiBuilderSessionSelection>()
  private val projectSessions = mutableMapOf<String, UiBuilderSessionSelection>()
  private val files = mutableMapOf<OfflineCatalog, UiBuilderVirtualFile>()
  private val mutableActiveSession = MutableStateFlow<UiBuilderSessionSelection?>(null)
  val activeSession = mutableActiveSession.asStateFlow()
  private var previewToolWindow: ToolWindow? = null

  fun catalogSession(catalog: OfflineCatalog): UiBuilderSessionSelection =
    catalogSessions.getOrPut(catalog) {
      UiBuilderSessionSelection(
        session =
          OfflineUiBuilderSession(
            storagePath = projectStoragePath(project).resolve(catalog.systemId),
            catalogSystemId = catalog.systemId,
          ),
        title = catalog.displayName,
        catalog = catalog,
      )
    }

  fun projectSession(file: VirtualFile): UiBuilderSessionSelection =
    projectSessions.getOrPut(file.url) {
      val document =
        requireNotNull(readProjectDesign(file)) { "${file.path} is not a UI Builder design" }
      val catalog = OfflineCatalog.forSystem(document.catalogPin.systemId)
      val writer = ProjectDesignWriter(file)
      UiBuilderSessionSelection(
        session =
          OfflineUiBuilderSession.projectDocument(document.toUiBuilderDocument()) { committed ->
            writer.write(committed)
          },
        title = document.title.ifBlank { document.id },
        catalog = catalog,
        projectFile = file,
      )
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

  override fun dispose() {
    (catalogSessions.values + projectSessions.values)
      .map { it.session }
      .distinct()
      .forEach(OfflineUiBuilderSession::close)
    catalogSessions.clear()
    projectSessions.clear()
    mutableActiveSession.value = null
    previewToolWindow = null
  }
}

internal class UiBuilderVirtualFile(val catalog: OfflineCatalog) :
  LightVirtualFile("Compose UI Builder — ${catalog.displayName}")

internal val OfflineCatalog.displayName: String
  get() =
    when (this) {
      OfflineCatalog.M3 -> "Material 3"
      OfflineCatalog.WEAR_M3 -> "Wear M3"
      OfflineCatalog.REMOTE_M3 -> "Wear widgets"
    }

internal fun isProjectDesign(project: Project, file: VirtualFile): Boolean {
  if (file.isDirectory || file.extension != "json" || file.name == "index.json") return false
  val root = project.basePath?.trimEnd('/') ?: return false
  if (!file.path.startsWith("$root/ui-builder/designs/")) return false
  return readProjectDesign(file)?.catalogPin?.systemId?.let { systemId ->
    runCatching { OfflineCatalog.forSystem(systemId) }.isSuccess
  } == true
}

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
    if (application.isDispatchThread) write() else application.invokeAndWait(write)
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

private fun projectStoragePath(project: Project): Path =
  Path.of(PathManager.getSystemPath(), "compose-ui-builder", project.locationHash)
