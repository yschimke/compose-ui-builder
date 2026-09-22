package ee.schimke.composeai.uibuilder.intellij

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import ee.schimke.composeai.uibuilder.EditorPane
import ee.schimke.composeai.uibuilder.desktop.OfflineCatalog
import ee.schimke.composeai.uibuilder.desktop.OfflineUiBuilderSessionView
import org.jetbrains.jewel.bridge.addComposeTab

/** IntelliJ Platform edge for the otherwise platform-independent offline editor host. */
class UiBuilderToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val service = project.getService(UiBuilderProjectService::class.java)
    OfflineCatalog.entries.forEach { catalog ->
      toolWindow.addComposeTab(catalog.displayName) {
        OfflineUiBuilderSessionView(
          session = service.session(catalog),
          sessionLabel =
            "IntelliJ preview · ${project.name} · ${catalog.displayName} · saved locally",
          chrome = JewelUiBuilderChrome,
          initialPanes = setOf(EditorPane.Preview),
          availablePanes = setOf(EditorPane.Preview),
          openDefaultPreview = false,
        )
      }
    }
    service.attachPreviewToolWindow(toolWindow)
    toolWindow.setTitleActions(
      OfflineCatalog.entries.map { catalog -> OpenUiBuilderEditorAction(project, catalog) }
    )
    service.openEditor(OfflineCatalog.M3)
  }
}

private class OpenUiBuilderEditorAction(
  private val project: Project,
  private val catalog: OfflineCatalog,
) : AnAction("Open ${catalog.displayName} editor") {
  override fun actionPerformed(event: AnActionEvent) {
    project.getService(UiBuilderProjectService::class.java).openEditor(catalog)
  }
}
