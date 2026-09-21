package ee.schimke.composeai.uibuilder.intellij

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import ee.schimke.composeai.uibuilder.desktop.OfflineUiBuilderApp
import java.nio.file.Path
import org.jetbrains.jewel.bridge.addComposeTab

/** IntelliJ Platform edge for the otherwise platform-independent offline editor host. */
class UiBuilderToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val storagePath = projectStoragePath(project)
    toolWindow.addComposeTab("Builder") {
      OfflineUiBuilderApp(
        storagePath = storagePath,
        sessionLabel = "IntelliJ · ${project.name} · saved locally",
      )
    }
  }
}

private fun projectStoragePath(project: Project): Path =
  Path.of(PathManager.getSystemPath(), "compose-ui-builder", project.locationHash)
