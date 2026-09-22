package ee.schimke.composeai.uibuilder.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import ee.schimke.composeai.uibuilder.desktop.OfflineCatalog
import ee.schimke.composeai.uibuilder.desktop.OfflineUiBuilderSession
import java.nio.file.Path

/** Project lifetime shared by the editor-area canvas and its Preview tool-window views. */
internal class UiBuilderProjectService(private val project: Project) : Disposable {
  private val sessions = mutableMapOf<OfflineCatalog, OfflineUiBuilderSession>()
  private val files = mutableMapOf<OfflineCatalog, UiBuilderVirtualFile>()

  fun session(catalog: OfflineCatalog): OfflineUiBuilderSession =
    sessions.getOrPut(catalog) {
      OfflineUiBuilderSession(
        storagePath = projectStoragePath(project).resolve(catalog.systemId),
        catalogSystemId = catalog.systemId,
      )
    }

  fun openEditor(catalog: OfflineCatalog) {
    val file = files.getOrPut(catalog) { UiBuilderVirtualFile(catalog) }
    FileEditorManager.getInstance(project).openFile(file, true)
  }

  override fun dispose() {
    sessions.values.forEach(OfflineUiBuilderSession::close)
    sessions.clear()
  }
}

internal class UiBuilderVirtualFile(val catalog: OfflineCatalog) :
  LightVirtualFile("Compose UI Builder — ${catalog.displayName}")

internal val OfflineCatalog.displayName: String
  get() =
    when (this) {
      OfflineCatalog.M3 -> "Material 3"
      OfflineCatalog.WEAR_M3 -> "Wear M3"
    }

private fun projectStoragePath(project: Project): Path =
  Path.of(PathManager.getSystemPath(), "compose-ui-builder", project.locationHash)
