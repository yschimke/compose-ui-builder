package ee.schimke.composeai.uibuilder.intellij

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import ee.schimke.composeai.uibuilder.desktop.OfflineCatalog
import ee.schimke.composeai.uibuilder.desktop.OfflineUiBuilderApp
import java.nio.file.Path
import org.jetbrains.jewel.bridge.addComposeTab

/** IntelliJ Platform edge for the otherwise platform-independent offline editor host. */
class UiBuilderToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val storagePath = projectStoragePath(project)
    toolWindow.addComposeTab("Material 3") {
      OfflineUiBuilderApp(
        storagePath = storagePath.resolve(OfflineCatalog.M3.systemId),
        sessionLabel = "IntelliJ · ${project.name} · Material 3 · saved locally",
        catalogSystemId = OfflineCatalog.M3.systemId,
      )
    }
    toolWindow.addComposeTab("Wear M3") {
      OfflineUiBuilderApp(
        storagePath = storagePath.resolve(OfflineCatalog.WEAR_M3.systemId),
        sessionLabel = "IntelliJ · ${project.name} · Wear M3 · saved locally",
        catalogSystemId = OfflineCatalog.WEAR_M3.systemId,
      )
    }
  }
}

private fun projectStoragePath(project: Project): Path =
  Path.of(PathManager.getSystemPath(), "compose-ui-builder", project.locationHash)
