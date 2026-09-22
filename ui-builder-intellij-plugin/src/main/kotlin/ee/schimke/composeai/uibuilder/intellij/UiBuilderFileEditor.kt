package ee.schimke.composeai.uibuilder.intellij

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import ee.schimke.composeai.uibuilder.EditorPane
import ee.schimke.composeai.uibuilder.desktop.OfflineUiBuilderSessionView
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent
import org.jetbrains.jewel.bridge.JewelComposePanel

/** Selects the visual editor for the synthetic catalog files opened by the plugin. */
internal class UiBuilderFileEditorProvider : FileEditorProvider {
  override fun accept(project: Project, file: VirtualFile): Boolean = file is UiBuilderVirtualFile

  override fun createEditor(project: Project, file: VirtualFile): FileEditor {
    require(file is UiBuilderVirtualFile)
    val service = project.getService(UiBuilderProjectService::class.java)
    return UiBuilderFileEditor(project, file, service.catalogSession(file.catalog))
  }

  override fun getEditorTypeId(): String = "compose-ui-builder-visual-editor"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Adds the visual editor beside JSON for checked-in files under `ui-builder/designs`. */
internal class UiBuilderProjectFileEditorProvider : FileEditorProvider {
  override fun accept(project: Project, file: VirtualFile): Boolean = isProjectDesign(project, file)

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
  private val projectService = project.getService(UiBuilderProjectService::class.java)
  private val component = JewelComposePanel {
    OfflineUiBuilderSessionView(
      session = selection.session,
      sessionLabel =
        if (selection.projectFile == null) {
          "IntelliJ · ${project.name} · ${selection.catalog.displayName} · saved locally"
        } else {
          "Project design · ${selection.projectFile.presentableUrl}"
        },
      chrome = JewelUiBuilderChrome,
      initialPanes = setOf(EditorPane.Editor),
      availablePanes = setOf(EditorPane.Editor),
      openDefaultPreview = false,
    )
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

  override fun dispose() = Unit
}
