package ee.schimke.composeai.uibuilder.intellij

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import ee.schimke.composeai.uibuilder.EditorPane
import ee.schimke.composeai.uibuilder.desktop.OfflineCatalog
import ee.schimke.composeai.uibuilder.desktop.OfflineUiBuilderSessionView
import ee.schimke.composeai.uibuilder.desktop.RemoteUiBuilderConnection
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.runBlocking
import org.jetbrains.jewel.bridge.addComposeTab

/** IntelliJ Platform edge for the otherwise platform-independent offline editor host. */
class UiBuilderToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val service = project.getService(UiBuilderProjectService::class.java)
    toolWindow.addComposeTab(PREVIEW_CONTENT) {
      ProvideUiBuilderNavigationEventDispatcher {
        val selection by service.activeSession.collectAsState()
        selection?.let { active ->
          val session by active.session.collectAsState()
          val status by active.status.collectAsState()
          OfflineUiBuilderSessionView(
            session = session,
            sessionLabel =
              "IntelliJ preview · ${project.name} · ${active.title}" +
                status?.let { " · $it" }.orEmpty(),
            chrome = JewelUiBuilderChrome,
            initialPanes = setOf(EditorPane.Preview),
            availablePanes = setOf(EditorPane.Preview),
            openDefaultPreview = false,
          )
        }
      }
    }
    service.attachPreviewToolWindow(toolWindow)
    toolWindow.setTitleActions(
      listOf(
        OpenProjectDesignAction(project),
        OpenRemoteDesignAction(project),
        CopyAgentPromptAction(project),
      ) + OfflineCatalog.entries.map { catalog -> OpenUiBuilderEditorAction(project, catalog) }
    )
    service.openEditor(OfflineCatalog.M3)
  }
}

private class OpenRemoteDesignAction(private val project: Project) :
  AnAction("Browse server designs") {
  override fun actionPerformed(event: AnActionEvent) {
    val properties = PropertiesComponent.getInstance(project)
    val previous = properties.getValue(REMOTE_SERVER_PROPERTY, "https://preview.coo.ee")
    val server =
      Messages.showInputDialog(
          project,
          "Compose preview server URL. Your browser will open to approve a short-lived grant.",
          "Connect to UI Builder Server",
          null,
          previous,
          null,
        )
        ?.trim()
        ?.takeIf { it.isNotEmpty() } ?: return
    properties.setValue(REMOTE_SERVER_PROPERTY, server)

    val result = runCatching {
      ProgressManager.getInstance()
        .runProcessWithProgressSynchronously(
          ThrowableComputable {
            runBlocking {
              val connection = RemoteUiBuilderConnection.connect(server)
              connection to connection.listDesigns()
            }
          },
          "Connecting to Compose UI Builder",
          true,
          project,
        )
    }
    val (connection, designs) =
      result.getOrElse { failure ->
        Messages.showErrorDialog(
          project,
          failure.message ?: "Could not connect to the UI Builder server.",
          "UI Builder Connection Failed",
        )
        return
      }
    if (designs.isEmpty()) {
      Messages.showInfoMessage(project, "This account can open no designs.", "UI Builder")
      return
    }
    val labels = designs.map { design ->
      "${design.title.ifBlank { design.designId }}  ·  ${design.catalogSystemId}  ·  ${design.designId}"
    }
    val selected =
      Messages.showChooseDialog(
        project,
        "Choose a design to open as a live collaborative editor.",
        "Open Remote UI Builder Design",
        null,
        labels.toTypedArray(),
        labels.first(),
      )
    if (selected < 0) return
    runCatching {
      project
        .getService(UiBuilderProjectService::class.java)
        .openRemoteDesign(connection, designs[selected])
    }
      .onFailure { failure ->
        Messages.showErrorDialog(
          project,
          failure.message ?: "The selected catalog is not packaged with this plugin.",
          "Cannot Open UI Builder Design",
        )
      }
  }
}

private class CopyAgentPromptAction(private val project: Project) :
  AnAction("Copy active design for an agent") {
  override fun actionPerformed(event: AnActionEvent) {
    val active = project.getService(UiBuilderProjectService::class.java).activeSession.value
    if (active == null) {
      Messages.showInfoMessage(project, "Open a UI Builder design first.", "UI Builder")
      return
    }
    CopyPasteManager.getInstance().setContents(StringSelection(active.agentPrompt))
    Messages.showInfoMessage(project, "Agent instructions copied to the clipboard.", "UI Builder")
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
        FileChooserDescriptorFactory.createSingleFileDescriptor()
          .withExtensionFilter("UI Builder designs", "uid", "json")
          .withTitle("Open UI Builder Design"),
        project,
        root,
      ) ?: return
    if (!isProjectDesign(file)) {
      Messages.showErrorDialog(
        project,
        "Choose a supported UI Builder .uid or DesignDocumentV1 JSON file.",
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

private const val REMOTE_SERVER_PROPERTY = "compose.ui.builder.remote.server"
