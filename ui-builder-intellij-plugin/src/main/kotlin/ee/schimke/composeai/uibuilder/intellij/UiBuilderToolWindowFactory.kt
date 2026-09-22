package ee.schimke.composeai.uibuilder.intellij

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
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
    toolWindow.addComposeTab(PREVIEW_CONTENT) {
      val selection by service.activeSession.collectAsState()
      selection?.let { active ->
        OfflineUiBuilderSessionView(
          session = active.session,
          sessionLabel = "IntelliJ preview · ${project.name} · ${active.title}",
          chrome = JewelUiBuilderChrome,
          initialPanes = setOf(EditorPane.Preview),
          availablePanes = setOf(EditorPane.Preview),
          openDefaultPreview = false,
        )
      }
    }
    service.attachPreviewToolWindow(toolWindow)
    toolWindow.setTitleActions(
      listOf(OpenProjectDesignAction(project)) +
        OfflineCatalog.entries.map { catalog -> OpenUiBuilderEditorAction(project, catalog) }
    )
    service.openEditor(OfflineCatalog.M3)
  }
}

private class OpenProjectDesignAction(private val project: Project) :
  AnAction("Open checked-in design") {
  override fun actionPerformed(event: AnActionEvent) {
    val root =
      project.basePath?.let { path ->
        LocalFileSystem.getInstance().findFileByPath("$path/ui-builder/designs")
      }
    val file =
      FileChooser.chooseFile(
        FileChooserDescriptorFactory.createSingleFileDescriptor("json")
          .withTitle("Open UI Builder Design"),
        project,
        root,
      ) ?: return
    if (!isProjectDesign(project, file)) {
      Messages.showErrorDialog(
        project,
        "Choose a supported DesignDocumentV1 JSON file under ui-builder/designs.",
        "Not a UI Builder Design",
      )
      return
    }
    FileEditorManager.getInstance(project).openFile(file, true)
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
