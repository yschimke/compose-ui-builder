package ee.schimke.composeai.uibuilder.intellij

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
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
import ee.schimke.composeai.uibuilder.host.OfflineCatalog
import ee.schimke.composeai.uibuilder.host.OfflineUiBuilderSessionView
import ee.schimke.composeai.uibuilder.host.RemoteUiBuilderConnection
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.runBlocking
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.Text

/** IntelliJ Platform edge for the otherwise platform-independent offline editor host. */
class UiBuilderToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val service = project.getService(UiBuilderProjectService::class.java)
    toolWindow.addComposeTab(PREVIEW_CONTENT) {
      ProvideUiBuilderNavigationEventDispatcher {
        val selection by service.activeSession.collectAsState()
        val active = selection
        if (active == null) {
          // Opening the tool window used to open a Material 3 scratch editor as a side effect. It
          // now says what it previews and where designs come from, and opens nothing by itself.
          Text(
            "Open a .uid design, or choose Tools › Compose UI Builder, to preview it here.",
            modifier = Modifier.padding(16.dp),
          )
        } else {
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
    val actions = ActionManager.getInstance()
    toolWindow.setTitleActions(TITLE_ACTION_IDS.mapNotNull(actions::getAction))
  }
}

/** The tool window's title bar offers the same registered actions as Tools › Compose UI Builder. */
private val TITLE_ACTION_IDS =
  listOf(
    "ComposeUiBuilder.OpenProjectDesign",
    "ComposeUiBuilder.BrowseServerDesigns",
    "ComposeUiBuilder.CopyAgentPrompt",
    "ComposeUiBuilder.OpenM3Editor",
    "ComposeUiBuilder.OpenWearM3Editor",
    "ComposeUiBuilder.OpenWearWidgetEditor",
  )

/**
 * Actions registered in `plugin.xml` are created by the platform with no arguments, so each reads
 * its project from the event, and is disabled where there is none.
 */
internal abstract class UiBuilderProjectAction : AnAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    event.presentation.isEnabled = event.project != null
  }

  final override fun actionPerformed(event: AnActionEvent) {
    perform(event.project ?: return)
  }

  abstract fun perform(project: Project)
}

internal class OpenRemoteDesignAction : UiBuilderProjectAction() {
  override fun perform(project: Project) {
    val properties = PropertiesComponent.getInstance(project)
    val previous = properties.getValue(REMOTE_SERVER_PROPERTY) ?: UiBuilderSettings.defaultServer
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
        notifyUiBuilder(
          project,
          "Could not connect to $server: " +
            (failure.message ?: "the UI Builder server did not answer."),
          NotificationType.ERROR,
        )
        return
      }
    if (designs.isEmpty()) {
      notifyUiBuilder(project, "Your account on $server can open no designs.")
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
        notifyUiBuilder(
          project,
          failure.message ?: "The selected design's catalog is not packaged with this plugin.",
          NotificationType.ERROR,
        )
      }
  }
}

internal class CopyAgentPromptAction : UiBuilderProjectAction() {
  override fun perform(project: Project) {
    val active = project.getService(UiBuilderProjectService::class.java).activeSession.value
    if (active == null) {
      notifyUiBuilder(project, "Open a UI Builder design first.", NotificationType.WARNING)
      return
    }
    CopyPasteManager.getInstance().setContents(StringSelection(active.agentPrompt))
    notifyUiBuilder(project, "Agent instructions for ${active.title} copied to the clipboard.")
  }
}

internal class OpenProjectDesignAction : UiBuilderProjectAction() {
  override fun perform(project: Project) {
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
      notifyUiBuilder(
        project,
        "${file.name} is not a UI Builder design this plugin can open: choose a .uid or " +
          "DesignDocumentV1 JSON file.",
        NotificationType.ERROR,
      )
      return
    }
    FileEditorManager.getInstance(project).openFile(file, true)
  }
}

internal abstract class OpenUiBuilderEditorAction(private val catalog: OfflineCatalog) :
  UiBuilderProjectAction() {
  override fun perform(project: Project) {
    project.getService(UiBuilderProjectService::class.java).openEditor(catalog)
  }
}

internal class OpenM3EditorAction : OpenUiBuilderEditorAction(OfflineCatalog.M3)

internal class OpenWearM3EditorAction : OpenUiBuilderEditorAction(OfflineCatalog.WEAR_M3)

internal class OpenWearWidgetEditorAction : OpenUiBuilderEditorAction(OfflineCatalog.REMOTE_M3)

private const val REMOTE_SERVER_PROPERTY = "compose.ui.builder.remote.server"
