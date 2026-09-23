package ee.schimke.composeai.uibuilder.intellij

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import ee.schimke.composeai.uibuilder.EditorPane
import ee.schimke.composeai.uibuilder.host.OfflineUiBuilderSessionView
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent
import org.jetbrains.jewel.bridge.JewelComposePanel

/** Selects the visual editor for the synthetic catalog files opened by the plugin. */
internal class UiBuilderFileEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean =
    file is UiBuilderVirtualFile || file is UiBuilderRemoteVirtualFile

  override fun createEditor(project: Project, file: VirtualFile): FileEditor {
    val service = project.getService(UiBuilderProjectService::class.java)
    val selection =
      when (file) {
        is UiBuilderVirtualFile -> service.catalogSession(file.catalog)
        is UiBuilderRemoteVirtualFile -> file.selection
        else -> error("unsupported UI Builder virtual file")
      }
    return UiBuilderFileEditor(project, file, selection)
  }

  override fun getEditorTypeId(): String = "compose-ui-builder-visual-editor"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Adds the visual editor beside the source of a recognized UI Builder design JSON file. */
internal class UiBuilderProjectFileEditorProvider : FileEditorProvider {
  override fun accept(project: Project, file: VirtualFile): Boolean = isProjectDesign(file)

  override fun createEditor(project: Project, file: VirtualFile): FileEditor {
    val service = project.getService(UiBuilderProjectService::class.java)
    return UiBuilderFileEditor(project, file, service.projectSession(file))
  }

  override fun getEditorTypeId(): String = "compose-ui-builder-project-design-editor"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

/** A Jewel Compose canvas occupying IntelliJ's main editor area. */
private class UiBuilderFileEditor(
  project: Project,
  private val file: VirtualFile,
  private val selection: UiBuilderSessionSelection,
) : UserDataHolderBase(), FileEditor {
  private val changes = PropertyChangeSupport(this)
  private val projectService =
    project.getService(UiBuilderProjectService::class.java).also { it.retain(selection) }
  private val component = JewelComposePanel {
    ProvideUiBuilderNavigationEventDispatcher {
      val session by selection.session.collectAsState()
      val status by selection.status.collectAsState()
      OfflineUiBuilderSessionView(
        session = session,
        sessionLabel =
          (if (file is UiBuilderRemoteVirtualFile) {
            "Remote design · ${file.name}"
          } else if (selection.projectFile == null) {
            "IntelliJ · ${project.name} · ${selection.catalog.displayName} · saved locally"
          } else {
            "Project design · ${selection.projectFile.presentableUrl}"
          }) + status?.let { " · $it" }.orEmpty(),
        chrome = JewelUiBuilderChrome,
        initialPanes = setOf(EditorPane.Editor),
        availablePanes = setOf(EditorPane.Editor),
        openDefaultPreview = false,
        // Match IntelliJ's GUI Designer: the containment tree and selected-component inspector
        // are visible as soon as a design opens, while the Components rail still exposes the
        // palette without taking over the source editor.
        initialLayersOpen = true,
        initialInspectorOpen = true,
      )
    }
  }

  override fun getComponent(): JComponent = component

  override fun getPreferredFocusedComponent(): JComponent = component

  override fun getName(): String = "Visual editor"

  override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

  override fun setState(state: FileEditorState) = Unit

  override fun isModified(): Boolean = false

  override fun isValid(): Boolean = file.isValid

  override fun selectNotify() {
    projectService.activate(selection)
  }

  override fun addPropertyChangeListener(listener: PropertyChangeListener) {
    changes.addPropertyChangeListener(listener)
  }

  override fun removePropertyChangeListener(listener: PropertyChangeListener) {
    changes.removePropertyChangeListener(listener)
  }

  override fun getFile(): VirtualFile = file

  override fun dispose() {
    projectService.release(selection)
  }
}
