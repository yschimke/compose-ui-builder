@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.CommandOutcome
import ee.schimke.composeai.uibuilder.DesignRevisionPin
import ee.schimke.composeai.uibuilder.DesignUrlSelectors
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.UiBuilderComponentPacks
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderPreviewSurfaces
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.hostSpec
import ee.schimke.composeai.uibuilder.reference.ReferencePiece
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun MobileEditorToolbar(
  state: UiBuilderEditorState,
  canDelete: Boolean,
  canDuplicate: Boolean,
  canCopy: Boolean,
  canCut: Boolean,
  canPaste: Boolean,
  wrapCandidates: List<EditorCatalogItem>,
  canUnwrap: Boolean,
  canUndo: Boolean,
  canRedo: Boolean,
  onNewDesign: (() -> Unit)?,
  onBrowseDesigns: (() -> Unit)? = null,
  onForkDesign: (() -> Unit)? = null,
  onReconnect: (() -> Unit)?,
  onHelp: (() -> Unit)?,
  onCopyAiPrompt: (suspend () -> String)?,
  onNotice: (String) -> Unit,
  onTakeOffline: (() -> Unit)?,
  onSyncToServer: (() -> Unit)?,
  exportHost: UiBuilderExportHost?,
  onComponentPacks: (() -> Unit)? = null,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  LocalUiBuilderChrome.current.EditorToolbar(Modifier) {
    Row(
      Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("UI Builder", Modifier.weight(1f), fontWeight = FontWeight.Bold)
      EditorAction("Undo", "Ctrl/⌘+Z", canUndo) { dispatch(UiBuilderEditorEvent.Undo) }
      EditorAction("Redo", "Ctrl/⌘+Shift+Z", canRedo) { dispatch(UiBuilderEditorEvent.Redo) }
      if (exportHost != null) ExportMenu(exportHost, showStatus = false)
      Box {
        TextButton(
          onClick = { expanded = true },
          modifier = Modifier.semantics { contentDescription = "More editor actions" },
        ) {
          Text("More")
        }
        val menuEntries = buildList {
          // The host container shapes, on a widget design. Rows rather than the wide toolbar's
          // menu-inside-a-menu, because this is already the overflow: a second dropdown off one
          // row is a worse thing to hit on a narrow screen than two rows that read as a pair. The
          // wide toolbar's control is the same choice, and without these the whole rectangular
          // frame — canvas and native render — would be unreachable under 840dp.
          state.document.wearWidgetScaffoldSize()?.let { size ->
            WearWidgetHostShape.entries.forEach { option ->
              val spec = size.hostSpec(option)
              add(
                UiBuilderMenuEntry.Action(
                  label =
                    "${option.label} container · ${spec.frameWidthDp}×${spec.frameHeightDp}dp",
                  selected = option == state.wearWidgetHostShape,
                  reserveIconSpace = true,
                  compactLeadingIcon = true,
                  onClick = {
                    expanded = false
                    dispatch(UiBuilderEditorEvent.ShowWearWidgetHostShape(option))
                  },
                )
              )
            }
          }
          if (onNewDesign != null) {
            add(
              UiBuilderMenuEntry.Action("New design") {
                expanded = false
                onNewDesign()
              }
            )
          }
          if (onBrowseDesigns != null) {
            add(
              UiBuilderMenuEntry.Action("My designs") {
                expanded = false
                onBrowseDesigns()
              }
            )
          }
          if (onForkDesign != null) {
            add(
              UiBuilderMenuEntry.Action("Fork this design") {
                expanded = false
                onForkDesign()
              }
            )
          }
          add(
            UiBuilderMenuEntry.Action("Duplicate", enabled = canDuplicate) {
              expanded = false
              dispatch(UiBuilderEditorEvent.DuplicateSelected)
            }
          )
          add(
            UiBuilderMenuEntry.Action("Copy", enabled = canCopy) {
              expanded = false
              dispatch(UiBuilderEditorEvent.CopySelected)
            }
          )
          add(
            UiBuilderMenuEntry.Action("Cut", enabled = canCut) {
              expanded = false
              dispatch(UiBuilderEditorEvent.CutSelected)
            }
          )
          add(
            UiBuilderMenuEntry.Action("Paste", enabled = canPaste) {
              expanded = false
              dispatch(UiBuilderEditorEvent.Paste)
            }
          )
          add(
            UiBuilderMenuEntry.Action("Delete", enabled = canDelete) {
              expanded = false
              dispatch(UiBuilderEditorEvent.DeleteSelected)
            }
          )
          if (onReconnect != null) {
            add(
              UiBuilderMenuEntry.Action("Reconnect") {
                expanded = false
                onReconnect()
              }
            )
          }
          if (onTakeOffline != null) {
            add(
              UiBuilderMenuEntry.Action("Keep in this browser") {
                expanded = false
                onTakeOffline()
              }
            )
          }
          if (onSyncToServer != null) {
            add(
              UiBuilderMenuEntry.Action("Sync to the server") {
                expanded = false
                onSyncToServer()
              }
            )
          }
          if (onComponentPacks != null) {
            add(
              UiBuilderMenuEntry.Action("Component packs…") {
                expanded = false
                onComponentPacks()
              }
            )
          }
          if (onCopyAiPrompt != null) {
            add(
              UiBuilderMenuEntry.Action(
                label = "Copy OpenCode AI prompt",
                icon = UiBuilderMenuIcon.Copy,
                onClick = {
                  expanded = false
                  scope.launch { onNotice(onCopyAiPrompt()) }
                },
              )
            )
          }
          if (onHelp != null) {
            add(
              UiBuilderMenuEntry.Action("Help") {
                expanded = false
                onHelp()
              }
            )
          }
        }
        LocalUiBuilderChrome.current.PopupMenu(
          expanded = expanded,
          onDismissRequest = { expanded = false },
          entries = menuEntries,
        )
      }
      Text("r${state.document.revision}", style = MaterialTheme.typography.labelMedium)
    }
  }
}

@Composable
internal fun MobilePanelDock(
  panel: MobileEditorPanel,
  onPanelChanged: (MobileEditorPanel) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier.fillMaxWidth().height(56.dp), tonalElevation = 6.dp) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
      MobilePanelButton("Components", MobileEditorPanel.Components, panel, onPanelChanged)
      MobilePanelButton("Layers", MobileEditorPanel.Layers, panel, onPanelChanged)
      MobilePanelButton("Properties", MobileEditorPanel.Properties, panel, onPanelChanged)
      MobilePanelButton("Code", MobileEditorPanel.Code, panel, onPanelChanged)
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MobilePanelButton(
  label: String,
  target: MobileEditorPanel,
  selected: MobileEditorPanel,
  onPanelChanged: (MobileEditorPanel) -> Unit,
) {
  TextButton(
    onClick = { onPanelChanged(target) },
    modifier =
      Modifier.weight(1f).fillMaxHeight().semantics {
        contentDescription =
          if (selected == target) "Close ${label.lowercase()} panel"
          else "Open ${label.lowercase()} panel"
      },
  ) {
    Text(label, fontWeight = if (selected == target) FontWeight.Bold else FontWeight.Normal)
  }
}

/**
 * The editor's top bar: what is being edited, history, what the canvas is for, and which panels are
 * open.
 *
 * Four zones in that order, because this row used to be eighteen text buttons of equal weight.
 * `Duplicate` and `Cut` sat beside `Help` and `Reconnect`, most of them greyed most of the time,
 * and the one control that changes what the canvas *is* — `Preview` — was indistinguishable from
 * the rest. Editing verbs moved to [SelectionActionBar], where they sit beside the thing they act
 * on and are only present when there is one; the document's revision and the session moved to
 * [CanvasStatusBar], where a status line belongs. What is left here is global: identity, undo, the
 * canvas mode, and the panels.
 */
@Composable
internal fun EditorToolbar(
  state: UiBuilderEditorState,
  canUndo: Boolean,
  canRedo: Boolean,
  collaborators: List<UiBuilderCollaborator>,
  onNewDesign: (() -> Unit)?,
  /**
   * Leaves for the host's index of every design this account may open; null where there is none.
   */
  onBrowseDesigns: (() -> Unit)? = null,
  onForkDesign: (() -> Unit)? = null,
  onReconnect: (() -> Unit)?,
  onHelp: (() -> Unit)?,
  onCopyAiPrompt: (suspend () -> String)?,
  onNotice: (String) -> Unit,
  onTakeOffline: (() -> Unit)? = null,
  onSyncToServer: (() -> Unit)? = null,
  /** Copies, links and downloads the render, or null where the host cannot; hides the menu. */
  exportHost: UiBuilderExportHost?,
  /** Snaps the design's authored dp values onto the 4dp grid, with the outcome as a sentence. */
  onTidy: (() -> Unit)? = null,
  /** Opens the component-pack settings, or null where the catalog offers no pack. */
  onComponentPacks: (() -> Unit)? = null,
  /** Which design panes are open — see [EditorPane]. */
  panes: Set<EditorPane> = setOf(EditorPane.Editor),
  /** Which panes this host keeps inside this workspace rather than in another IDE view. */
  availablePanes: Set<EditorPane> = EditorPane.entries.toSet(),
  /** What this design's catalog says each renderer's picture of it is worth. */
  previewSurfaces: UiBuilderPreviewSurfaces = UiBuilderPreviewSurfaces.DEFAULT,
  /** Whether the host can compile and draw this design at all. */
  nativeAvailable: Boolean = false,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var showShortcuts by remember { mutableStateOf(false) }
  var overflowOpen by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  if (showShortcuts) {
    EditorShortcutsDialog(onDismiss = { showShortcuts = false })
  }
  Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
    Row(
      Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      DocumentIdentity(state)
      Spacer(Modifier.width(10.dp))
      ToolbarIconAction("Undo", "Ctrl/⌘+Z", UiBuilderChromeIcon.Undo, canUndo) {
        dispatch(UiBuilderEditorEvent.Undo)
      }
      ToolbarIconAction("Redo", "Ctrl/⌘+Shift+Z", UiBuilderChromeIcon.Redo, canRedo) {
        dispatch(UiBuilderEditorEvent.Redo)
      }
      Spacer(Modifier.weight(1f))
      // Only once there is something to hide — a picture, a placed piece or a mark. An
      // always-present control for a feature most designs never use is exactly the crowding the
      // rest of this change is undoing.
      if (state.reference.hasContent) {
        ToolbarToggleAction(
          label = if (state.reference.settings.visible) "Hide reference" else "Show reference",
          icon =
            if (state.reference.settings.visible) UiBuilderChromeIcon.Show
            else UiBuilderChromeIcon.Hide,
          checked = state.reference.settings.visible,
        ) {
          dispatch(UiBuilderEditorEvent.ToggleReference)
        }
      }
      ToolbarToggleAction(
        label = if (state.codePaneVisible) "Code · hide" else "Code",
        icon = UiBuilderChromeIcon.Code,
        checked = state.codePaneVisible,
      ) {
        dispatch(UiBuilderEditorEvent.ToggleCodePane)
      }
      // Beside Code, because they are the two answers to "how do I get this out": the Kotlin the
      // design is, and the picture it draws. Absent where the host cannot render one.
      if (exportHost != null) ExportMenu(exportHost)
      if (availablePanes.size > 1) {
        WorkspacePanesMenu(panes, availablePanes, previewSurfaces, nativeAvailable, dispatch)
      }
      // Beside the panes menu, because they are the two "what am I looking at" choices: which panes
      // are open, and which host frame the design is drawn inside.
      state.document.wearWidgetScaffoldSize()?.let { size ->
        WidgetHostShapeMenu(state.wearWidgetHostShape, size, dispatch)
      }
      if (collaborators.isNotEmpty()) {
        Spacer(Modifier.width(6.dp))
        PresenceRow(collaborators)
        Spacer(Modifier.width(6.dp))
      }
      if (onNewDesign != null) {
        ToolbarIconAction("New design", "", UiBuilderChromeIcon.New, true, onNewDesign)
      }
      if (onForkDesign != null) {
        ToolbarIconAction("Fork this design", "", UiBuilderChromeIcon.Copy, true, onForkDesign)
      }
      if (onCopyAiPrompt != null) {
        ToolbarIconAction("Copy OpenCode AI prompt", "", UiBuilderChromeIcon.Copy, true) {
          scope.launch { onNotice(onCopyAiPrompt()) }
        }
      }
      Box {
        ToolbarIconAction("More editor actions", "", UiBuilderChromeIcon.More, true) {
          overflowOpen = true
        }
        val menuEntries = buildList {
          if (onBrowseDesigns != null) {
            add(
              UiBuilderMenuEntry.Action("My designs", icon = UiBuilderMenuIcon.Folder) {
                overflowOpen = false
                onBrowseDesigns()
              }
            )
          }
          add(
            UiBuilderMenuEntry.Action(
              "Keyboard shortcuts",
              icon = UiBuilderMenuIcon.Keyboard,
            ) {
              overflowOpen = false
              showShortcuts = true
            }
          )
          if (onTidy != null) {
            add(
              UiBuilderMenuEntry.Action(
                "Tidy to the 4dp grid",
                icon = UiBuilderMenuIcon.Tidy,
              ) {
                overflowOpen = false
                onTidy.invoke()
              }
            )
          }
          if (onReconnect != null) {
            add(
              UiBuilderMenuEntry.Action("Reconnect", icon = UiBuilderMenuIcon.Refresh) {
                overflowOpen = false
                onReconnect()
              }
            )
          }
          if (onTakeOffline != null) {
            add(
              UiBuilderMenuEntry.Action("Keep in this browser") {
                overflowOpen = false
                onTakeOffline()
              }
            )
          }
          if (onSyncToServer != null) {
            add(
              UiBuilderMenuEntry.Action("Sync to the server") {
                overflowOpen = false
                onSyncToServer()
              }
            )
          }
          if (onComponentPacks != null) {
            add(
              UiBuilderMenuEntry.Action(
                "Component packs…",
                icon = UiBuilderMenuIcon.Components,
              ) {
                overflowOpen = false
                onComponentPacks()
              }
            )
          }
          if (onHelp != null) {
            add(
              UiBuilderMenuEntry.Action("Help", icon = UiBuilderMenuIcon.Help) {
                overflowOpen = false
                onHelp()
              }
            )
          }
        }
        LocalUiBuilderChrome.current.PopupMenu(
          expanded = overflowOpen,
          onDismissRequest = { overflowOpen = false },
          entries = menuEntries,
        )
      }
    }
  }
}

/**
 * The Export menu: the design as a picture, out of the builder and into Figma, a link or a file.
 *
 * One button, because the catalog viewer's preview page has one row and this toolbar has no room
 * for six; the rows are [exportMenuEntries], grouped by verb. Each row hands its work to the host
 * and shows the sentence the host answers with beside the button for a moment — "SVG copied", or
 * why it was not — since a clipboard write that says nothing is indistinguishable from one that
 * failed. The button stays enabled while a row runs: a second press while an export renders is a
 * second export, which is harmless, and a disabled button reads as a broken one.
 */
@Composable
private fun ExportMenu(host: UiBuilderExportHost, showStatus: Boolean = true) {
  val groups =
    remember(host.formats, host.supportsLinks) {
      exportMenuEntries(host.formats, host.supportsLinks)
    }
  if (groups.isEmpty()) return
  var open by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf<String?>(null) }
  var statusGeneration by remember { mutableStateOf(0) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(statusGeneration) {
    if (status == null) return@LaunchedEffect
    delay(EXPORT_STATUS_MILLIS)
    status = null
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    val shown = status
    if (showStatus && shown != null) {
      Text(
        shown,
        Modifier.widthIn(max = 260.dp).semantics { contentDescription = "Export status" },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Box {
      ToolbarIconAction("Export", "", UiBuilderChromeIcon.Export, true) { open = true }
      LocalUiBuilderChrome.current.PopupMenu(
        expanded = open,
        onDismissRequest = { open = false },
        entries =
          groups.flatMapIndexed { index, group ->
            buildList {
              if (index > 0) add(UiBuilderMenuEntry.Divider)
              group.forEach { entry ->
                add(
                  UiBuilderMenuEntry.Action(
                    label = entry.label,
                    detail = entry.detail,
                    detailStyle = UiBuilderMenuDetailStyle.Body,
                    icon =
                      when (entry) {
                        is EditorExportMenuEntry.CopyPicture -> UiBuilderMenuIcon.Copy
                        is EditorExportMenuEntry.CopyLink -> UiBuilderMenuIcon.Link
                        is EditorExportMenuEntry.Download -> UiBuilderMenuIcon.Download
                      },
                    onClick = {
                      open = false
                      scope.launch {
                        status =
                          try {
                            host.perform(entry)
                          } catch (failure: Exception) {
                            "${entry.label} failed: ${failure.message ?: "unknown error"}"
                          }
                        statusGeneration++
                      }
                    },
                  )
                )
              }
            }
          },
      )
    }
  }
}

/**
 * The rows of the Export menu, without the popup around them.
 *
 * Separate from [ExportMenu] so a preview can draw them: a `DropdownMenu` is a popup window, which
 * a static render does not capture, and rows nobody can diff are rows that drift. The verb groups
 * are divided, and every row carries its second line, because "Copy SVG" alone does not say that it
 * is the Figma route.
 */
@Composable
internal fun ExportMenuRows(
  groups: List<List<EditorExportMenuEntry>>,
  onPick: (EditorExportMenuEntry) -> Unit,
) {
  groups.forEachIndexed { index, group ->
    if (index > 0) HorizontalDivider()
    group.forEach { entry ->
      DropdownMenuItem(
        text = {
          Column {
            Text(entry.label)
            Text(
              entry.detail,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        },
        leadingIcon = {
          Icon(
            when (entry) {
              is EditorExportMenuEntry.CopyPicture -> Icons.Filled.ContentCopy
              is EditorExportMenuEntry.CopyLink -> Icons.Filled.Link
              is EditorExportMenuEntry.Download -> Icons.Filled.Download
            },
            contentDescription = null,
          )
        },
        modifier = Modifier.semantics { contentDescription = entry.label },
        onClick = { onPick(entry) },
      )
    }
  }
}

/** How long an export's answer stays beside the button. */
internal const val EXPORT_STATUS_MILLIS = 4_000L

/** The file, the way a design tool names one: a mark, the title, and what it is pinned to. */
@Composable
private fun DocumentIdentity(state: UiBuilderEditorState, modifier: Modifier = Modifier) {
  val catalogSystemId =
    state.document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull.orEmpty()
  LocalUiBuilderChrome.current.DocumentIdentity(
    title = state.document.title,
    supporting =
      if (catalogSystemId.isEmpty()) "Compose UI Builder"
      else "Compose UI Builder · $catalogSystemId",
    modifier = modifier,
  )
}

/**
 * Which host container a Wear widget is framed in, as a menu of the shapes the platform ships.
 *
 * Offered only on a widget design, and that is not a cosmetic gate: on anything else the choice
 * would change nothing, and a control that does nothing is worse than no control.
 *
 * The shape is the **host's**, not the design's — the launcher draws the frame from
 * `WearWidgetParams`, and the same `WearWidgetDocument` appears inside each one. So this switches a
 * view rather than editing anything: no revision, no operation, nothing in the export. What it buys
 * a designer is the answer to "does my widget survive the other frame", which for the rectangular
 * container is a real question — its content box and padding both differ from the squircle's, so a
 * layout that just fits in one can clip in the other.
 *
 * A menu rather than a segmented pair, matching [WorkspacePanesMenu] beside it: each position wants
 * a sentence, and there is room for a third shape here if the round container's per-diameter
 * footprint is ever worth drawing.
 */
@Composable
private fun WidgetHostShapeMenu(
  shape: WearWidgetHostShape,
  size: WearWidgetScaffoldSize,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { open = true },
      modifier = Modifier.semantics { contentDescription = "Host container (${shape.label})" },
    ) {
      Icon(Icons.Filled.Dashboard, contentDescription = null, modifier = Modifier.size(18.dp))
      Text(shape.label, Modifier.padding(start = 6.dp))
      Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    LocalUiBuilderChrome.current.PopupMenu(
      expanded = open,
      onDismissRequest = { open = false },
      entries =
        WearWidgetHostShape.entries.map { option ->
          val spec = size.hostSpec(option)
          UiBuilderMenuEntry.Action(
            label = option.label,
            // The footprint, because that is what the choice actually changes and a designer
            // comparing two frames wants the numbers rather than an adjective.
            detail =
              "${spec.frameWidthDp}×${spec.frameHeightDp}dp frame · " +
                "${spec.contentWidthDp}×${spec.contentHeightDp}dp content",
            detailStyle = UiBuilderMenuDetailStyle.Body,
            selected = option == shape,
            reserveIconSpace = true,
            compactLeadingIcon = true,
            onClick = {
              dispatch(UiBuilderEditorEvent.ShowWearWidgetHostShape(option))
              open = false
            },
          )
        },
    )
  }
}

/**
 * Where a reference [piece]'s centre falls, in the render pixels the canvas inspection reports.
 *
 * A piece is placed in fractions of the frame the canvas draws, so it converts through that same
 * frame — [canvasFrameDp], which for a Wear widget is its host container rather than the 1280x800dp
 * environment widget designs carry. Converting through the environment put a piece laid over a
 * widget's slot hundreds of dp outside it, and promoting it fell back to the current selection.
 */
internal fun UiBuilderDocument.referencePieceCentrePx(
  piece: ReferencePiece,
  hostShape: WearWidgetHostShape,
): Pair<Float, Float> {
  val (widthDp, heightDp) = canvasFrameDp(hostShape)
  val scale = screenEnvironmentSettings().density.toFloat()
  return (piece.left + piece.right) / 2f * widthDp * scale to
    (piece.top + piece.bottom) / 2f * heightDp * scale
}

/**
 * The frame the editing canvas draws [this] design in, as width and height in dp.
 *
 * A Wear widget is framed by its host container in [hostShape], not by the environment. Widget
 * designs are seeded with the phone fixture's environment — 1280x800dp — so framing by it drew a
 * 216x124dp widget as a tile in the middle of an empty tablet, with "Fit" fitting the tablet. The
 * host frame comes from the same table the preview panes beside the canvas are drawn at.
 */
internal fun UiBuilderDocument.canvasFrameDp(hostShape: WearWidgetHostShape): Pair<Float, Float> {
  wearWidgetScaffoldSize()?.hostSpec(hostShape)?.let {
    return it.frameWidthDp.toFloat() to it.frameHeightDp.toFloat()
  }
  return (environment["widthDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1280f) to
    (environment["heightDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 800f)
}

/**
 * The widget container this design's root is, or null when it is not a widget design at all.
 *
 * Read from the root rather than from the catalog, because the frame follows the scaffold the
 * design was created with and nothing else can change it.
 */
/**
 * This design as a catalog runtime surface drawn in [shape] is sent it: the shape named in its
 * environment under [WearWidgetHostShape.ENVIRONMENT_KEY], so the runtime frames the widget the way
 * the pane does. Anything that is not a Wear widget is sent unchanged.
 */
internal fun UiBuilderDocument.withWearWidgetHostShape(
  shape: WearWidgetHostShape
): UiBuilderDocument =
  if (wearWidgetScaffoldSize() == null) this
  else
    copy(
      environment =
        JsonObject(environment + (WearWidgetHostShape.ENVIRONMENT_KEY to JsonPrimitive(shape.id)))
    )

internal fun UiBuilderDocument.wearWidgetScaffoldSize(): WearWidgetScaffoldSize? {
  val rootId = roots.singleOrNull() ?: return null
  val componentId = nodes[rootId]?.componentId ?: return null
  // An adaptive widget is edited at Large, the size where every slot shows. Its preview panes draw
  // it at both sizes (`wearWidgetPreviewPanes`).
  if (componentId == AdaptiveWearWidget.COMPONENT_ID) return WearWidgetScaffoldSize.Large
  return WearWidgetScaffoldSize.entries.firstOrNull { it.componentId == componentId }
}

/**
 * Which design panes are open, as three switches rather than a rung on a ladder.
 *
 * It used to be one value — "1 pane", "2 panes", "3 panes" — which is a control that can only count
 * and cannot say what it is counting. You could not ask for the preview without the editor, you
 * could not ask for the native render without the preview, and the second rung was the one that
 * cost a compile. Each pane is now its own row and its own answer.
 *
 * The last open pane's row is disabled: switching it off would leave a blank workspace, and a
 * control whose only outcome is nothing is worse than no control. A host with no compile lane keeps
 * the native row too, disabled and carrying the catalog's own sentence about why — a row that
 * vanishes teaches nobody that the pane exists.
 */
@Composable
private fun WorkspacePanesMenu(
  panes: Set<EditorPane>,
  availablePanes: Set<EditorPane>,
  /**
   * The catalog's own claims, so a pane that cannot tell the truth says so where it is chosen.
   *
   * The Wasm panes are never *removed* on such a catalog: the browser canvas is what a node is
   * selected and dragged on, and an editor with no canvas is not an editor. What they lose is the
   * word "immediate" standing alone as their whole description.
   */
  surfaces: UiBuilderPreviewSurfaces = UiBuilderPreviewSurfaces.DEFAULT,
  /** Whether the host can draw the native pane at all. */
  nativeAvailable: Boolean = false,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  val label = panesLabel(panes)
  Box {
    TextButton(
      onClick = { open = true },
      modifier = Modifier.semantics { contentDescription = "Workspace panes ($label)" },
    ) {
      Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
      Text(label, Modifier.padding(start = 6.dp))
      Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    LocalUiBuilderChrome.current.PopupMenu(
      expanded = open,
      onDismissRequest = { open = false },
      entries =
        EditorPane.entries
          .filter { it in availablePanes }
          .map { pane ->
            val shown = pane in panes
            val available = pane != EditorPane.Native || nativeAvailable
            // Off it may not go while it is the only thing on screen; on it may not go where the
            // host
            // cannot draw it.
            val enabled = available && !(shown && panes.size == 1)
            UiBuilderMenuEntry.Action(
              label = pane.title,
              detail =
                if (available) pane.supportingText(surfaces) else pane.unavailableText(surfaces),
              selected = shown,
              reserveIconSpace = true,
              enabled = enabled,
              onClick = {
                open = false
                dispatch(UiBuilderEditorEvent.TogglePane(pane))
              },
            )
          },
    )
  }
}

/** Who else is in the document, as the avatar stack every collaborative tool puts here. */
@Composable
private fun PresenceRow(collaborators: List<UiBuilderCollaborator>) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    collaborators.take(4).forEach { collaborator ->
      Surface(
        Modifier.size(28.dp).clearAndSetSemantics {},
        shape = RoundedCornerShape(14.dp),
        color = collaborator.colorArgbHex.toPresenceColor(),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            collaborator.displayName.firstOrNull()?.uppercase().orEmpty(),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}

/**
 * Everything that can be done to the current selection, as menu rows.
 *
 * One list, three places: the layers tree's context menu, the canvas's, and the overflow beside the
 * selection label. The verbs used to exist only as a row of icon buttons above the canvas — always
 * present, mostly greyed, and nowhere near the layer they act on. A context menu puts them under
 * the pointer that is already on the thing, which is where every other design tool keeps them.
 */
internal fun editorSelectionMenuEntries(
  /** The layout modifiers this selection can be given or have taken away; empty for many nodes. */
  modifierToggles: List<EditorModifierToggle>,
  onToggleModifier: (String) -> Unit,
  canDuplicate: Boolean,
  canCopy: Boolean,
  canCut: Boolean,
  canPaste: Boolean,
  canDelete: Boolean,
  wrapCandidates: List<EditorCatalogItem>,
  canUnwrap: Boolean,
  onOpenProperties: (() -> Unit)?,
  /** Copies a link that opens this design on this layer, or null where nothing is selected. */
  onCopyLink: (() -> Unit)? = null,
  onDismiss: () -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
): List<UiBuilderMenuEntry> = buildList {
  fun act(event: UiBuilderEditorEvent) {
    onDismiss()
    dispatch(event)
  }
  if (onOpenProperties != null) {
    add(
      UiBuilderMenuEntry.Action("Properties", icon = UiBuilderMenuIcon.Properties) {
        onDismiss()
        onOpenProperties()
      }
    )
  }
  // Beside Properties rather than among the clipboard verbs, because both of these are ways of
  // *pointing at* the selected layer while Copy and Cut are ways of moving it. The Export menu's
  // Copy link is the design's address; this one is a layer's, which is the thing somebody pastes
  // when they mean "this button, here".
  if (onCopyLink != null) {
    add(
      UiBuilderMenuEntry.Action(
        label = "Copy link",
        icon = UiBuilderMenuIcon.Link,
        contentDescription = "Copy link to this layer",
        onClick = {
          onDismiss()
          onCopyLink()
        },
      )
    )
  }
  if (onOpenProperties != null || onCopyLink != null) add(UiBuilderMenuEntry.Divider)
  add(
    UiBuilderMenuEntry.Action(
      "Duplicate",
      icon = UiBuilderMenuIcon.Duplicate,
      enabled = canDuplicate,
      shortcut = "Ctrl/⌘+D",
    ) {
      act(UiBuilderEditorEvent.DuplicateSelected)
    }
  )
  add(
    UiBuilderMenuEntry.Action(
      "Copy",
      icon = UiBuilderMenuIcon.Copy,
      enabled = canCopy,
      shortcut = "Ctrl/⌘+C",
    ) {
      act(UiBuilderEditorEvent.CopySelected)
    }
  )
  add(
    UiBuilderMenuEntry.Action(
      "Cut",
      icon = UiBuilderMenuIcon.Cut,
      enabled = canCut,
      shortcut = "Ctrl/⌘+X",
    ) {
      act(UiBuilderEditorEvent.CutSelected)
    }
  )
  add(
    UiBuilderMenuEntry.Action(
      "Paste",
      icon = UiBuilderMenuIcon.Paste,
      enabled = canPaste,
      shortcut = "Ctrl/⌘+V",
    ) {
      act(UiBuilderEditorEvent.Paste)
    }
  )
  add(
    UiBuilderMenuEntry.Action(
      "Delete",
      icon = UiBuilderMenuIcon.Delete,
      enabled = canDelete,
      shortcut = "Delete",
    ) {
      act(UiBuilderEditorEvent.DeleteSelected)
    }
  )
  // Layout before the container verbs, because it is what a right-click on a laid-out node is
  // usually for: the chain is the node's own business, and wrapping is its parent's.
  if (modifierToggles.isNotEmpty()) {
    add(UiBuilderMenuEntry.Divider)
    modifierToggles.forEach { toggle ->
      add(
        UiBuilderMenuEntry.Action(
          label = toggle.label,
          // The tick says what is already true. A menu of layout verbs with no state is one people
          // press twice to find out what it did.
          selected = toggle.applied,
          reserveIconSpace = true,
          contentDescription =
            if (toggle.applied) "Remove ${toggle.label}" else "Apply ${toggle.label}",
          onClick = {
            onDismiss()
            onToggleModifier(toggle.type)
          },
        )
      )
    }
  }
  if (wrapCandidates.isNotEmpty() || canUnwrap) add(UiBuilderMenuEntry.Divider)
  // Behind one row rather than inline: the containers a selection can be wrapped in run to thirty
  // on this catalog, and a menu whose last verb is thirty rows below the first is not a menu.
  if (wrapCandidates.isNotEmpty()) {
    add(
      UiBuilderMenuEntry.Action(
        label = "Wrap in…",
        icon = UiBuilderMenuIcon.Wrap,
        // Only what will work: the candidates are computed from both ends, so every row here is a
        // promise rather than a guess.
        children =
          wrapCandidates.map { candidate ->
            UiBuilderMenuEntry.Action(candidate.displayName) {
              act(UiBuilderEditorEvent.WrapSelection(candidate.componentId))
            }
          },
        onClick = {},
      )
    )
  }
  if (canUnwrap) {
    add(UiBuilderMenuEntry.Action("Unwrap") { act(UiBuilderEditorEvent.UnwrapSelection) })
  }
}

/**
 * Runs one Copy link against the host and hands back the sentence to show.
 *
 * A failure is a sentence too, for the reason the export host gives: the affordance is a button in
 * a menu, and a clipboard the browser refused should be reported where the button was rather than
 * swallowed into a console nobody has open.
 */
internal suspend fun copyLinkSentence(
  copy: suspend (DesignUrlSelectors) -> String,
  selectors: DesignUrlSelectors,
): String =
  try {
    copy(selectors)
  } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
    throw cancelled
  } catch (failure: Exception) {
    "Copy link failed: ${failure.message ?: "unknown error"}"
  }

/**
 * What a pinned revision, a stale selector or a just-copied link has to say, over the canvas.
 *
 * One strip rather than three, because all three are the same kind of sentence — something about
 * *this opening of this design* that the canvas cannot show — and a page that grows a new bar per
 * kind of news is a page whose design moves under the reader. The revision line is the only one
 * that persists; the rest expire, which is why they are drawn after it rather than instead of it.
 */
@Composable
internal fun EditorUrlBanner(
  revisionPin: DesignRevisionPin?,
  onGoToLatest: (() -> Unit)?,
  openingNotice: String?,
  transientNotice: String?,
) {
  val pinned = revisionPin?.pinned == true
  val message =
    when {
      pinned -> "Showing revision ${revisionPin.requested} · read-only"
      revisionPin != null ->
        "Revision ${revisionPin.requested} is not available — showing the latest design."
      else -> null
    }
  if (message == null && openingNotice == null && transientNotice == null) return
  Surface(
    Modifier.fillMaxWidth(),
    color =
      if (pinned) MaterialTheme.colorScheme.secondaryContainer
      else MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        if (pinned) Icons.Filled.History else Icons.Filled.Link,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
      )
      Text(
        listOfNotNull(message, openingNotice, transientNotice).joinToString("  ·  "),
        Modifier.weight(1f).semantics { contentDescription = "Design link notice" },
        style = MaterialTheme.typography.labelMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (revisionPin != null && onGoToLatest != null) {
        TextButton(
          onClick = onGoToLatest,
          modifier = Modifier.semantics { contentDescription = "Go to latest revision" },
        ) {
          Text("Go to latest")
        }
      }
    }
  }
}

/**
 * What can be done to the selection, beside the selection, only while there is one.
 *
 * These seven verbs used to live in the top bar, where they were greyed out for the whole of every
 * session that never selected anything — which is what an empty document is. Here they name their
 * subject: the bar says what is selected and then what can be done to it, and it is absent entirely
 * when the answer is "nothing".
 *
 * Icons for the six that every tool draws the same way, words for the two that no icon conveys —
 * wrapping a selection in a container, and taking it back out.
 *
 * The label is the selection's path, read root to leaf, when the selection is a single layer: each
 * rung is a press away from being the selection, which is the question "which component is this,
 * inside what" answered in the order the layers panel draws it. Multi-select keeps the count — a
 * path is a fact about one layer, and a count about several.
 */
@Composable
internal fun SelectionActionBar(
  selectionLabel: String,
  breadcrumbs: List<UiBuilderBreadcrumbEntry>,
  onBreadcrumbSelected: (String) -> Unit,
  onOpenProperties: (() -> Unit)?,
  /** The same rows the context menus carry; the bar holds no second copy of the verbs. */
  selectionMenu: (() -> Unit) -> List<UiBuilderMenuEntry>,
  modifier: Modifier = Modifier,
) {
  var menuOpen by remember { mutableStateOf(false) }
  Surface(
    modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
  ) {
    Row(
      Modifier.fillMaxWidth().height(48.dp).padding(start = 18.dp, end = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      if (breadcrumbs.size >= 2) {
        // Horizontally scrollable rather than elided: a deep path that loses its middle to an
        // ellipsis loses the rungs the reader would have pressed. The verbs to the right stay put.
        SelectionBreadcrumbs(breadcrumbs, onBreadcrumbSelected, Modifier.weight(1f))
      } else {
        Text(
          selectionLabel,
          Modifier.weight(1f),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (onOpenProperties != null) {
        TextButton(
          onClick = onOpenProperties,
          // Not "Open properties panel", which is the rail switch's name: two controls answering
          // to one name is a locator that resolves to both and a screen reader that cannot say
          // which is which.
          modifier = Modifier.semantics { contentDescription = "Edit properties" },
        ) {
          Text("Properties")
        }
      }
      // One control where seven icons were. Everything they did is now a right-click away on the
      // layer itself, in the tree or on the canvas; this is the same menu for anyone who reaches
      // for a button instead, and it is where the chords are written down.
      Box {
        ToolbarIconAction("Selection actions", "", UiBuilderChromeIcon.More, true) {
          menuOpen = true
        }
        LocalUiBuilderChrome.current.PopupMenu(
          expanded = menuOpen,
          onDismissRequest = { menuOpen = false },
          entries = selectionMenu { menuOpen = false },
        )
      }
    }
  }
}

/**
 * The selection's path, read root to leaf, as pressable rungs.
 *
 * A rung carries the layer's name and, where saying it is information, the slot it sits in from the
 * rung above — the same distinction the layers panel draws slot lines for. The leaf is the
 * selection and is drawn selected rather than pressable-elsewhere; pressing an ancestor re-roots
 * the selection there, which is the only verb a path can honestly offer.
 *
 * A path longer than the bar is *elided from the root*, never from the leaf: the thing the reader
 * needs to see is where they are, and a bar that shows `Surface › Supporting pane scaffold › …` and
 * cuts the selection off is answering the question nobody asked. The rungs that remain scroll when
 * even they do not fit, and the scroll follows the selection so the leaf is the end it rests at.
 */
@Composable
private fun SelectionBreadcrumbs(
  entries: List<UiBuilderBreadcrumbEntry>,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Four rungs and a leading ellipsis is the shape a path takes in every tool that has one: the
  // leaf, the two or three things it is inside, and a way to know there is more behind.
  val shown =
    if (entries.size > MAX_BREADCRUMB_RUNGS + 1) entries.takeLast(MAX_BREADCRUMB_RUNGS) else entries
  val hidden = entries.size - shown.size
  val scrollState = rememberScrollState()
  // The leaf is the end that must be on screen: a path that scrolls back to its root the moment
  // the selection moves is a path that hides the selection again.
  LaunchedEffect(entries) { scrollState.scrollTo(scrollState.maxValue) }
  Row(
    modifier.horizontalScroll(scrollState),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    if (hidden > 0) {
      Text(
        "…",
        Modifier.padding(horizontal = 2.dp).semantics {
          contentDescription = "$hidden hidden ancestors"
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.outline,
      )
      Text(
        "›",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    shown.forEachIndexed { index, entry ->
      if (index > 0) {
        // A glyph rather than an icon: the separator is punctuation, not a control, and the code
        // the toolbar's own labels already spell this way.
        Text(
          "›",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.outline,
        )
        entry.inSlot?.let { slot ->
          Text(
            slot,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
          )
          Text(
            "›",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline,
          )
        }
      }
      val selected = index == shown.lastIndex
      Text(
        entry.label,
        Modifier
          // A crumb is text with a press, not a button: the affordance is the chevron between
          // rungs, and a filled chip per rung would turn a path into a row of pills.
          .clip(RoundedCornerShape(6.dp))
          .clickable(enabled = !selected) { onSelect(entry.nodeId) }
          .padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color =
          if (selected) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** How many rungs a breadcrumb shows before it elides the ones above them. */
private const val MAX_BREADCRUMB_RUNGS = 4

/** What the status bar and the host hear about a drag: the slot, and the seam inside it. */
internal fun dropPlanLabel(plan: UiBuilderDropPlan?): String =
  when (plan) {
    null -> "No compatible slot"
    else -> "${plan.target.nodeId}.${plan.target.slot} · position ${plan.index + 1}"
  }

/**
 * The line under the canvas: what the document is at, where a drag would land, and the session.
 *
 * Every one of these was in the top bar, competing with controls. None of them is a control — they
 * are the answers to "is this saved", "did that land" and "what happens if I let go", which is the
 * bottom of the window in every tool that has them.
 */
@Composable
internal fun CanvasStatusBar(
  state: UiBuilderEditorState,
  sessionLabel: String,
  dropTargetLabel: String,
  dragging: Boolean,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
  ) {
    // Selectable, because the bar is where a rejection and the live-session status land, and both
    // are sentences a person needs in a bug report rather than retyped off a screenshot.
    SelectionContainer {
      Row(
        Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        StatusText("Revision ${state.document.revision}")
        StatusText("${state.document.nodes.size} nodes")
        if (state.selection.size > 1) StatusText("${state.selection.size} selected")
        // Only while something is being dragged. The drop target is the answer to a question nobody
        // is asking with both hands still: it read "No compatible slot" at rest, which is a warning
        // about nothing.
        if (dragging) {
          StatusText("Drop target: $dropTargetLabel", color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        val outcome = state.lastOutcome
        if (outcome is CommandOutcome.Rejected) {
          StatusText("${outcome.code}: ${outcome.message}", color = MaterialTheme.colorScheme.error)
        }
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = LocalUiBuilderEditorPalette.current.sessionBadge,
        ) {
          Text(
            sessionLabel,
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            color = LocalUiBuilderEditorPalette.current.onSessionBadge,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }
  }
}

@Composable
private fun StatusText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
  Text(text, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
}

/**
 * What the toolbar button says: the open panes, in the enum's own order.
 *
 * Named rather than counted. "2 panes" answers a question nobody asked — the question is *which*
 * two, and on a workspace where the second one might be a compile that is not a detail.
 */
internal fun panesLabel(panes: Set<EditorPane>): String =
  EditorPane.entries.filter { it in panes }.joinToString(" + ") { it.label }.ifEmpty { "No panes" }

/**
 * One line under each pane's name, which is where a catalog's own caveat belongs.
 *
 * "Drawn in this browser" is a complete description on `m3-catalog`, where the canvas draws the
 * same Material 3 the export names. On `wear-m3` it is the least interesting true thing about it,
 * and the interesting one — those are stand-ins for a library no browser can link — is exactly what
 * somebody choosing a pane needs to read.
 */
internal fun EditorPane.supportingText(
  surfaces: UiBuilderPreviewSurfaces = UiBuilderPreviewSurfaces.DEFAULT
): String {
  val wasmDescription =
    if (surfaces.wasm.fidelity.isAuthoritative) "Wasm" else "Wasm stand-in, for authoring"
  return when (this) {
    EditorPane.Editor -> "Edit the design · $wasmDescription"
    // What it varies, and the two claims that matter: it does not edit, and it does not compile.
    // The second is why it is worth switching on at all rather than waiting for the native one.
    //
    // "Devices" here means device *properties* — a width, a height and a density written over the
    // design's environment — never a picture of a handset. Nothing in this pane draws a bezel, a
    // notch or a rounded corner, and a mock that is not photoreal is worse than none: it invites a
    // judgement about a screen from a drawing of a phone that is not the phone.
    EditorPane.Preview -> "Devices, overrides and themes · not editable · $wasmDescription"
    EditorPane.Native ->
      if (surfaces.native.backend == UiBuilderPreviewSurfaces.BACKEND_ANDROID)
        "Compiled on the host · Android"
      else "Compiled on the host · the target platform"
  }
}

/**
 * Why a pane cannot be switched on, said where it is refused.
 *
 * Only [EditorPane.Native] ever needs one — the other two are this browser drawing what it already
 * has — and it is the catalog's own sentence wherever the catalog wrote one, so the refusal is an
 * explanation rather than a greyed row.
 */
internal fun EditorPane.unavailableText(
  surfaces: UiBuilderPreviewSurfaces = UiBuilderPreviewSurfaces.DEFAULT
): String =
  when {
    this != EditorPane.Native -> supportingText(surfaces)
    surfaces.native.reason.isNotEmpty() -> "Unavailable: ${surfaces.native.reason}"
    else -> "Unavailable: this host has no compile lane for the design"
  }

/**
 * One icon control, with the label and its chord in the tooltip and in the semantics.
 *
 * The contentDescription keeps the `"$label ($shortcut)"` shape the text buttons had, because it is
 * what the accessibility tree and every script that drives this editor look for.
 */
@Composable
internal fun ToolbarIconAction(
  label: String,
  shortcut: String,
  icon: UiBuilderChromeIcon,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  LocalUiBuilderChrome.current.ToolbarAction(
    UiBuilderToolbarActionModel(label, shortcut, icon, enabled, onClick)
  )
}

/** [ToolbarIconAction] for a control that is on or off, and says which by staying lit. */
@Composable
internal fun ToolbarToggleAction(
  label: String,
  icon: UiBuilderChromeIcon,
  checked: Boolean,
  onClick: () -> Unit,
) {
  LocalUiBuilderChrome.current.ToolbarToggle(
    UiBuilderToolbarToggleModel(label, icon, checked, onClick)
  )
}

@Composable
private fun EditorAction(
  label: String,
  shortcut: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  TextButton(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.semantics { contentDescription = "$label ($shortcut)" },
  ) {
    Text(label)
  }
}

/**
 * The component-pack settings: one switch per pack the catalog carries.
 *
 * A dialog rather than a panel because it is a *setting* — a decision about what the palette
 * offers, made once per catalog and remembered by the host — and not a thing to look at while
 * designing. The components of a pack that is off stay in the catalog: switching a pack off after
 * dropping one of its components hides the shelf, not the node.
 */
@Composable
internal fun ComponentPacksDialog(
  packs: UiBuilderComponentPacks,
  enabledPacks: Set<String>,
  onToggle: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  TrackEditorOverlay(true)
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Component packs") },
    text = { ComponentPacksPanel(packs, enabledPacks, onToggle) },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
  )
}

/** The switch list, separate from the dialog so it can be previewed on its own. */
@Composable
internal fun ComponentPacksPanel(
  packs: UiBuilderComponentPacks,
  enabledPacks: Set<String>,
  onToggle: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier.width(460.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      "Other catalogs' components, offered on a shelf of their own. A pack component is drawn on " +
        "the canvas as a named placeholder and rendered as itself by the host's native preview, " +
        "which compiles the design against that catalog's bundle.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    packs.packs.forEach { pack ->
      val enabled = pack.id in enabledPacks
      Row(
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { onToggle(pack.id) }
          .padding(horizontal = 8.dp, vertical = 6.dp)
          .semantics { contentDescription = "${pack.label} pack" },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(pack.label, style = MaterialTheme.typography.bodyLarge)
          Text(
            "${pack.componentIds.size} components · ${pack.platform.label}" +
              (pack.nativeCatalog?.let { " · renders against $it" } ?: ""),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
          )
          if (pack.notes.isNotEmpty()) {
            Text(
              pack.notes,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        Switch(checked = enabled, onCheckedChange = { onToggle(pack.id) })
      }
    }
  }
}

@Composable
private fun EditorShortcutsDialog(onDismiss: () -> Unit) {
  TrackEditorOverlay(true)
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Keyboard and pointer") },
    text = { EditorShortcutsPanel() },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}

/**
 * The shortcut table, rendered from [EDITOR_SHORTCUTS] and [EDITOR_GESTURES] rather than retyped.
 *
 * Separate from the dialog so it can be previewed on its own: a help surface that drifts from the
 * handler is worse than no help surface, and the only way to keep it honest is for both to read the
 * same list and for a render to show what the list currently says.
 */
@Composable
internal fun EditorShortcutsPanel(modifier: Modifier = Modifier) {
  Column(modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Keys", style = MaterialTheme.typography.labelLarge)
    EDITOR_SHORTCUTS.forEach { shortcut -> EditorShortcutRow(shortcut.chord, shortcut.description) }
    Spacer(Modifier.height(10.dp))
    Text("Pointer", style = MaterialTheme.typography.labelLarge)
    EDITOR_GESTURES.forEach { (gesture, description) -> EditorShortcutRow(gesture, description) }
  }
}

@Composable
private fun EditorShortcutRow(chord: String, description: String) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Surface(
      Modifier.width(178.dp),
      shape = RoundedCornerShape(6.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
      Text(
        chord,
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
      )
    }
    Text(
      description,
      Modifier.padding(start = 12.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

/** How a click on a layer row changes the selection. */
internal enum class LayerSelectionGesture {
  Replace,
  Toggle,
  Range,
}

internal fun editorShortcut(
  event: KeyEvent,
  enabled: Boolean,
  editing: Boolean,
  dispatch: (UiBuilderEditorEvent) -> Unit,
): Boolean {
  if (!enabled || event.type != KeyEventType.KeyDown) return false
  val chord =
    EditorChord(
      key = event.key,
      command = event.isCtrlPressed || event.isMetaPressed,
      shift = event.isShiftPressed,
    )
  val match = editorShortcutFor(chord, editing) ?: return false
  dispatch(match.event)
  return true
}

/**
 * The shortcut a chord resolves to, or null when none does.
 *
 * Pure, so the table's precedence and the suppression below can be tested without synthesising a
 * key event — which on this target is more machinery than the rule being tested.
 *
 * With the authoring canvas switched off, only the chords that open a pane are live. There is then
 * no selection overlay on screen to show what a Delete or an arrow just did, so those chords would
 * edit invisibly and surprise later — and a pane toggle is the way back to seeing them.
 */
internal fun editorShortcutFor(chord: EditorChord, editing: Boolean = true): EditorShortcut? =
  EDITOR_SHORTCUTS.firstOrNull { it.matches(chord) }
    ?.takeIf { editing || it.event is UiBuilderEditorEvent.TogglePane }

/** The part of a key press a shortcut is allowed to look at. */
internal data class EditorChord(val key: Key, val command: Boolean, val shift: Boolean)

/**
 * One chord the editor answers to.
 *
 * [shift] is `null` for "does not care", which is not the same as `false`: `Ctrl/⌘+Z` fires whether
 * or not shift is down, and only reaches the undo entry because the redo entry above it claims the
 * shifted spelling first.
 */
internal data class EditorShortcut(
  val chord: String,
  val description: String,
  val event: UiBuilderEditorEvent,
  val keys: Set<Key>,
  val command: Boolean,
  val shift: Boolean? = null,
) {
  fun matches(pressed: EditorChord): Boolean =
    pressed.command == command && (shift == null || shift == pressed.shift) && pressed.key in keys
}

/**
 * Every key chord the editor answers to, in the order it tries them, and the list the shortcuts
 * panel renders.
 *
 * One table rather than a `when` plus a hand-written help sheet, because the second of those is
 * wrong within two commits. Most of what this editor learned to do — extending a selection,
 * reordering, wrapping, the clipboard — arrived with no visible affordance at all: reorder is a
 * chord and a drag gesture and appears on no button, and arrow-key navigation appears nowhere. A
 * capability nobody can find is one the tool does not have.
 *
 * Order is behaviour: the redo entry has to precede undo, and the reordering arrows have to precede
 * the navigating ones or a modified arrow is eaten by selection. `editorShortcutsAreAllReachable`
 * asserts every entry is the first match for its own chord, so a reordering that shadows one fails
 * rather than quietly dropping a row the panel still advertises.
 */
internal val EDITOR_SHORTCUTS: List<EditorShortcut> =
  listOf(
    EditorShortcut(
      chord = "Ctrl/\u2318+Shift+Z",
      description = "Redo",
      event = UiBuilderEditorEvent.Redo,
      keys = setOf(Key.Z),
      command = true,
      shift = true,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+Y",
      description = "Redo",
      event = UiBuilderEditorEvent.Redo,
      keys = setOf(Key.Y),
      command = true,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+Z",
      description = "Undo",
      event = UiBuilderEditorEvent.Undo,
      keys = setOf(Key.Z),
      command = true,
    ),
    // The shifted spelling first, so the plain one below does not eat it — the same rule redo and
    // undo follow two entries up.
    EditorShortcut(
      chord = "Ctrl/\u2318+Shift+Enter",
      description = "Show or hide the visual editor",
      event = UiBuilderEditorEvent.TogglePane(EditorPane.Editor),
      keys = setOf(Key.Enter, Key.NumPadEnter),
      command = true,
      shift = true,
    ),
    // Enter rather than P. The builder ships in a browser, and Ctrl/\u2318+P is the print dialog:
    // a chord whose worst case is a print preview over the design is not a chord worth having,
    // and whether Compose consumes it before the browser sees it is not something to find out in
    // production. Ctrl/\u2318+Enter is unclaimed, and "run it" is already what it means everywhere
    // else.
    EditorShortcut(
      chord = "Ctrl/\u2318+Enter",
      description = "Show or hide the preview beside the design",
      event = UiBuilderEditorEvent.TogglePane(EditorPane.Preview),
      keys = setOf(Key.Enter, Key.NumPadEnter),
      command = true,
      shift = false,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+D",
      description = "Duplicate the selection in place",
      event = UiBuilderEditorEvent.DuplicateSelected,
      keys = setOf(Key.D),
      command = true,
    ),
    // Reorder before plain navigation, so the modified arrows are not eaten by selection.
    EditorShortcut(
      chord = "Ctrl/\u2318+\u2191",
      description = "Move the selection earlier in its slot",
      event = UiBuilderEditorEvent.MoveSelected(EditorMoveDirection.Before),
      keys = setOf(Key.DirectionUp),
      command = true,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+\u2193",
      description = "Move the selection later in its slot",
      event = UiBuilderEditorEvent.MoveSelected(EditorMoveDirection.After),
      keys = setOf(Key.DirectionDown),
      command = true,
    ),
    EditorShortcut(
      chord = "\u2193",
      description = "Select the next layer",
      event = UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Next),
      keys = setOf(Key.DirectionDown),
      command = false,
    ),
    EditorShortcut(
      chord = "\u2191",
      description = "Select the previous layer",
      event = UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Previous),
      keys = setOf(Key.DirectionUp),
      command = false,
    ),
    EditorShortcut(
      chord = "\u2190",
      description = "Select the parent",
      event = UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Parent),
      keys = setOf(Key.DirectionLeft),
      command = false,
    ),
    EditorShortcut(
      chord = "\u2192",
      description = "Select the first child",
      event = UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.FirstChild),
      keys = setOf(Key.DirectionRight),
      command = false,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+C",
      description = "Copy the selection",
      event = UiBuilderEditorEvent.CopySelected,
      keys = setOf(Key.C),
      command = true,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+X",
      description = "Cut the selection",
      event = UiBuilderEditorEvent.CutSelected,
      keys = setOf(Key.X),
      command = true,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+V",
      description = "Paste into the selected container",
      event = UiBuilderEditorEvent.Paste,
      keys = setOf(Key.V),
      command = true,
    ),
    EditorShortcut(
      chord = "Delete / Backspace",
      description = "Delete the selection",
      event = UiBuilderEditorEvent.DeleteSelected,
      keys = setOf(Key.Delete, Key.Backspace),
      command = false,
    ),
  )

/**
 * The pointer gestures, which no chord and no button can advertise.
 *
 * They are the least discoverable thing in the editor and the most load-bearing: without them a
 * selection is one node, and every batch operation this editor gained is unreachable.
 */
internal val EDITOR_GESTURES: List<Pair<String, String>> =
  listOf(
    "Ctrl/\u2318 + click a layer" to "Add one layer to the selection, or take it out",
    "Shift + click a layer" to "Extend the selection to that layer",
    "Drag a layer row" to "Drop it on the layer or the slot it should join",
    "Drag a catalog component" to "Insert it where it is dropped",
    "Hold a node, then drag" to "Move it into the slot and seam it is dropped at",
    "Click a rung above the canvas" to "Select that layer — the path is a way back up",
  )

/**
 * [EditorToolbar]'s controls, published to a host that draws them itself.
 *
 * The same verbs as the desktop toolbar, less the ones a host editor already owns: the document's
 * name is the host's tab title, and new, browse, export, reconnect and the AI prompt belong to
 * hosts with a server. The shortcuts dialog is still drawn here, because it describes this canvas.
 */
@Composable
internal fun HostChromeToolbar(
  chrome: UiBuilderHostChrome,
  state: UiBuilderEditorState,
  canUndo: Boolean,
  canRedo: Boolean,
  onTidy: () -> Unit,
  onComponentPacks: (() -> Unit)?,
  onHelp: (() -> Unit)?,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var showShortcuts by remember { mutableStateOf(false) }
  if (showShortcuts) {
    EditorShortcutsDialog(onDismiss = { showShortcuts = false })
  }
  fun entry(action: UiBuilderHostAction, onInvoke: () -> Unit) = HostChromeEntry(action, onInvoke)
  val entries = buildList {
    add(
      entry(
        UiBuilderHostAction("undo", "Undo", "toolbar", "Undo", canUndo, shortcut = "Ctrl/⌘+Z")
      ) {
        dispatch(UiBuilderEditorEvent.Undo)
      }
    )
    add(
      entry(
        UiBuilderHostAction(
          "redo",
          "Redo",
          "toolbar",
          "Redo",
          canRedo,
          shortcut = "Ctrl/⌘+Shift+Z",
        )
      ) {
        dispatch(UiBuilderEditorEvent.Redo)
      }
    )
    if (state.reference.hasContent) {
      val visible = state.reference.settings.visible
      add(
        entry(
          UiBuilderHostAction(
            "reference",
            "Reference image",
            "toolbar",
            if (visible) "Show" else "Hide",
            checked = visible,
          )
        ) {
          dispatch(UiBuilderEditorEvent.ToggleReference)
        }
      )
    }
    add(
      entry(
        UiBuilderHostAction(
          "overflow.tidy",
          "Tidy to the 4dp grid",
          "overflow",
          "Fit",
        ),
        onTidy,
      )
    )
    add(
      entry(UiBuilderHostAction("overflow.shortcuts", "Keyboard shortcuts", "overflow", "More")) {
        showShortcuts = true
      }
    )
    if (onComponentPacks != null) {
      add(
        entry(
          UiBuilderHostAction("overflow.packs", "Component packs…", "overflow", "Components"),
          onComponentPacks,
        )
      )
    }
    if (onHelp != null) {
      add(entry(UiBuilderHostAction("overflow.help", "Help", "overflow", "More"), onHelp))
    }
  }
  PublishHostChrome(chrome, "toolbar", entries)
}
