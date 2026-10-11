@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.uibuilder.CommandOutcome
import ee.schimke.composeai.uibuilder.DesignRevisionPin
import ee.schimke.composeai.uibuilder.DesignUrlSelectors
import ee.schimke.composeai.uibuilder.export.A2uiDocumentExporter
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
  visibilityLabel: String? = null,
  onManageVisibility: (() -> Unit)? = null,
  agentHost: UiBuilderAgentHost? = null,
  onOpenAgentPrompt: () -> Unit = {},
  onNotice: (String) -> Unit,
  onTakeOffline: (() -> Unit)?,
  onSyncToServer: (() -> Unit)?,
  onPublishToServer: (() -> Unit)?,
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
      Row(
        Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (visibilityLabel != null) {
          MaterialChromeTooltip(visibilityLabel, "") {
            Icon(
              visibilityIcon(visibilityLabel),
              contentDescription = visibilityLabel,
              modifier = Modifier.size(16.dp),
            )
          }
        }
        Text(
          "UI Builder",
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        catalogFormFactorLabel(state.document)?.let { FormFactorChip(it) }
      }
      ToolbarIconAction("Undo", "$COMMAND_MODIFIER+Z", UiBuilderChromeIcon.Undo, canUndo) {
        dispatch(UiBuilderEditorEvent.Undo)
      }
      ToolbarIconAction("Redo", "$COMMAND_MODIFIER+Shift+Z", UiBuilderChromeIcon.Redo, canRedo) {
        dispatch(UiBuilderEditorEvent.Redo)
      }
      if (agentHost != null) AgentToolbarAction(agentHost, onOpenAgentPrompt, compact = true)
      if (exportHost != null) ExportMenu(exportHost, showStatus = false)
      Box {
        ToolbarIconAction("More editor actions", "", UiBuilderChromeIcon.More, true) {
          expanded = true
        }
        val menuEntries = buildList {
          if (visibilityLabel != null) {
            add(UiBuilderMenuEntry.Heading(visibilityLabel))
            if (onManageVisibility != null)
              add(
                UiBuilderMenuEntry.Action("Sharing") {
                  expanded = false
                  onManageVisibility()
                }
              )
            add(UiBuilderMenuEntry.Divider)
          }
          // Widget host shapes as rows (this is already the overflow, so no nested menu), keeping
          // the rectangular frame reachable under 840dp.
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
          if (onPublishToServer != null) {
            add(
              UiBuilderMenuEntry.Action("Publish to the server") {
                expanded = false
                onPublishToServer()
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
                label = "Copy prompt for your agent",
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
      // "r0" alone reads as noise to anybody who has not met revisions, so it says what it is to
      // assistive technology and on hover, and the same words are what a test finds it by.
      MaterialChromeTooltip("Revision ${state.document.revision} · saved edits so far", "") {
        Text(
          "r${state.document.revision}",
          Modifier.semantics { contentDescription = "Revision ${state.document.revision}" },
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
}

/**
 * The compact layout's bottom dock: the wide layout's two rails as one thumb-reachable row. The
 * four panels used while editing get buttons (Issues with its count); the rest are behind More.
 */
@Composable
internal fun MobilePanelDock(
  panel: MobileEditorPanel,
  onPanelChanged: (MobileEditorPanel) -> Unit,
  modifier: Modifier = Modifier,
  bottomInset: Dp = 0.dp,
  issueCount: Int = 0,
  talkCount: Int = 0,
) {
  // The surface runs down to the screen's edge, under a phone's home indicator; the tabs sit above
  // it, where a thumb can reach them without the system taking the swipe.
  Surface(
    modifier.fillMaxWidth().height(MOBILE_DOCK_HEIGHT + bottomInset),
    tonalElevation = 6.dp,
  ) {
    Row(
      Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top).height(MOBILE_DOCK_HEIGHT),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      MobilePanelButton("Components", MobileEditorPanel.Components, panel, onPanelChanged)
      MobilePanelButton("Layers", MobileEditorPanel.Layers, panel, onPanelChanged)
      MobilePanelButton("Properties", MobileEditorPanel.Properties, panel, onPanelChanged)
      MobilePanelButton("Issues", MobileEditorPanel.Issues, panel, onPanelChanged, issueCount)
      MobileMorePanelsButton(panel, onPanelChanged, talkCount)
    }
  }
}

/** The docks a phone has no button for, labelled as the wide layout's rail labels them. */
private val MOBILE_MORE_PANELS =
  listOf(
    "Code" to MobileEditorPanel.Code,
    "Theme" to MobileEditorPanel.Theme,
    "Screen" to MobileEditorPanel.Screen,
    "Talk" to MobileEditorPanel.Comments,
    "History" to MobileEditorPanel.History,
  )

@Composable
private fun androidx.compose.foundation.layout.RowScope.MobileMorePanelsButton(
  selected: MobileEditorPanel,
  onPanelChanged: (MobileEditorPanel) -> Unit,
  talkCount: Int,
) {
  var expanded by remember { mutableStateOf(false) }
  val open = MOBILE_MORE_PANELS.firstOrNull { it.second == selected }
  Box(Modifier.weight(1f).fillMaxHeight()) {
    TextButton(
      onClick = { expanded = true },
      modifier =
        Modifier.fillMaxSize().semantics {
          contentDescription =
            if (open != null) "More panels, ${open.first} open" else "More panels"
        },
      contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
      MobilePanelLabel(open?.first ?: "More", open != null, badge = talkCount)
    }
    LocalUiBuilderChrome.current.PopupMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      entries =
        MOBILE_MORE_PANELS.map { (label, target) ->
          UiBuilderMenuEntry.Action(
            label = label,
            detail =
              if (target == MobileEditorPanel.Comments && talkCount > 0) "$talkCount open"
              else null,
            selected = target == selected,
            contentDescription =
              if (target == selected) "Close ${label.lowercase()} panel"
              else "Open ${label.lowercase()} panel",
            onClick = {
              expanded = false
              onPanelChanged(target)
            },
          )
        },
    )
  }
}

/**
 * The compact layout's view switcher: the editing canvas, then one tab per frame the wide Preview
 * pane draws, each drawn whole and live. Scrollable, since a design may target many devices.
 */
@Composable
internal fun MobileViewTabs(
  /** The tab names, the editor's first. */
  labels: List<String>,
  selectedIndex: Int,
  onSelected: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  SecondaryScrollableTabRow(
    selectedTabIndex = selectedIndex,
    modifier = modifier.fillMaxWidth(),
    edgePadding = 8.dp,
  ) {
    labels.forEachIndexed { index, label ->
      Tab(
        selected = index == selectedIndex,
        onClick = { onSelected(index) },
        text = { Text(label, maxLines = 1, softWrap = false) },
      )
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MobilePanelButton(
  label: String,
  target: MobileEditorPanel,
  selected: MobileEditorPanel,
  onPanelChanged: (MobileEditorPanel) -> Unit,
  badge: Int = 0,
) {
  TextButton(
    onClick = { onPanelChanged(target) },
    modifier =
      Modifier.weight(1f).fillMaxHeight().semantics {
        contentDescription =
          if (selected == target) "Close ${label.lowercase()} panel"
          else "Open ${label.lowercase()} panel"
      },
    // A fifth of a phone's width is under 90dp, and the default 12dp either side left "Components"
    // and "Properties" too little room, so they broke mid-word onto a second line.
    contentPadding = PaddingValues(horizontal = 4.dp),
  ) {
    MobilePanelLabel(label, selected == target, badge)
  }
}

/**
 * A dock button's word plus the wide rail's count. Always one line, shrinking to fit rather than
 * wrapping.
 */
@Composable
private fun MobilePanelLabel(label: String, selected: Boolean, badge: Int) {
  val style = MaterialTheme.typography.labelLarge
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      label,
      Modifier.weight(1f, fill = false),
      style = style,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
      maxLines = 1,
      softWrap = false,
      autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
    )
    if (badge > 0) {
      Surface(
        Modifier.padding(start = 2.dp),
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.error,
      ) {
        Text(
          badge.toString(),
          Modifier.padding(horizontal = 4.dp),
          color = MaterialTheme.colorScheme.onError,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

/**
 * The editor's top bar, in four zones: identity, undo/redo, the canvas mode, and the panels.
 * Editing verbs live in [SelectionActionBar] and status in [CanvasStatusBar].
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
  visibilityLabel: String? = null,
  onManageVisibility: (() -> Unit)? = null,
  agentHost: UiBuilderAgentHost? = null,
  onOpenAgentPrompt: () -> Unit = {},
  onNotice: (String) -> Unit,
  onTakeOffline: (() -> Unit)? = null,
  onSyncToServer: (() -> Unit)? = null,
  onPublishToServer: (() -> Unit)? = null,
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      // The row does not wrap, so between the compact breakpoint and this width the session actions
      // (New, Fork, Sharing) move into More rather than pushing the agent control and More itself
      // off the end.
      val foldSessionActions = maxWidth < TOOLBAR_SESSION_ACTIONS_MIN_WIDTH
      // Just above the compact breakpoint that is still not enough, so the viewer list folds too.
      val foldPresence = maxWidth < TOOLBAR_PRESENCE_MIN_WIDTH
      val showPresence = collaborators.isNotEmpty() || agentHost != null
      Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        DocumentIdentity(state)
        Spacer(Modifier.width(10.dp))
        ToolbarIconAction("Undo", "$COMMAND_MODIFIER+Z", UiBuilderChromeIcon.Undo, canUndo) {
          dispatch(UiBuilderEditorEvent.Undo)
        }
        ToolbarIconAction("Redo", "$COMMAND_MODIFIER+Shift+Z", UiBuilderChromeIcon.Redo, canRedo) {
          dispatch(UiBuilderEditorEvent.Redo)
        }
        Spacer(Modifier.weight(1f))
        // Only once the reference has content, since most designs never use it.
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
        // Beside the panes menu, as the other "what am I looking at" choice. Only while the native
        // pane is open, the one view a single shape choice still steers.
        state.document.wearWidgetScaffoldSize()?.let { size ->
          if (EditorPane.Native in panes) {
            WidgetHostShapeMenu(state.wearWidgetHostShape, size, dispatch)
          }
        }
        if (showPresence && !foldPresence) {
          Spacer(Modifier.width(6.dp))
          PresenceRow(collaborators)
          Spacer(Modifier.width(6.dp))
        }
        if (!foldSessionActions) {
          if (onNewDesign != null) {
            ToolbarIconAction("New design", "", UiBuilderChromeIcon.New, true, onNewDesign)
          }
          if (onForkDesign != null) {
            ToolbarIconAction("Fork this design", "", UiBuilderChromeIcon.Copy, true, onForkDesign)
          }
          if (visibilityLabel != null) VisibilityToolbarAction(visibilityLabel, onManageVisibility)
        }
        if (agentHost != null) {
          AgentToolbarAction(agentHost, onOpenAgentPrompt)
        } else if (onCopyAiPrompt != null) {
          ToolbarIconAction("Copy prompt for your agent", "", UiBuilderChromeIcon.Copy, true) {
            scope.launch { onNotice(onCopyAiPrompt()) }
          }
        }
        Box {
          ToolbarIconAction("More editor actions", "", UiBuilderChromeIcon.More, true) {
            overflowOpen = true
          }
          val menuEntries = buildList {
            if (showPresence && foldPresence) {
              add(UiBuilderMenuEntry.Heading(presenceLabel(collaborators)))
              presenceRows(collaborators).forEach { row ->
                add(UiBuilderMenuEntry.Action(row) { overflowOpen = false })
              }
              add(UiBuilderMenuEntry.Divider)
            }
            if (foldSessionActions) {
              if (visibilityLabel != null) {
                add(UiBuilderMenuEntry.Heading(visibilityLabel))
                if (onManageVisibility != null)
                  add(
                    UiBuilderMenuEntry.Action("Sharing") {
                      overflowOpen = false
                      onManageVisibility()
                    }
                  )
                add(UiBuilderMenuEntry.Divider)
              }
              if (onNewDesign != null) {
                add(
                  UiBuilderMenuEntry.Action("New design") {
                    overflowOpen = false
                    onNewDesign()
                  }
                )
              }
              if (onForkDesign != null) {
                add(
                  UiBuilderMenuEntry.Action("Fork this design") {
                    overflowOpen = false
                    onForkDesign()
                  }
                )
              }
            }
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
            if (onPublishToServer != null) {
              add(
                UiBuilderMenuEntry.Action("Publish to the server") {
                  overflowOpen = false
                  onPublishToServer()
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
}

/**
 * Below this width [EditorToolbar] folds New, Fork and Sharing into More, so the agent control and
 * More stay on screen when a hosted design supplies every action at once.
 */
internal val TOOLBAR_SESSION_ACTIONS_MIN_WIDTH = 1200.dp

/**
 * Below this width [EditorToolbar] also folds the viewer list into More: between the 840dp compact
 * breakpoint and here, folding the session actions alone still squeezes More to a sliver.
 */
internal val TOOLBAR_PRESENCE_MIN_WIDTH = 1000.dp

/**
 * The Export menu: the design as a picture, into Figma, a link or a file. Rows are
 * [exportMenuEntries], grouped by verb; each shows the host's result sentence briefly ("SVG copied"
 * or why not). The button stays enabled while a row runs.
 */
@Composable
private fun ExportMenu(host: UiBuilderExportHost, showStatus: Boolean = true) {
  val groups =
    remember(host.formats, host.supportsLinks, host.supportsShare) {
      exportMenuEntries(host.formats, host.supportsLinks, host.supportsShare)
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
                        // The menu icon set has no share glyph, and a host chrome that draws its
                        // own icons never offers Share: it is a phone browser's row.
                        is EditorExportMenuEntry.Share -> UiBuilderMenuIcon.Link
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
 * The rows of the Export menu without the popup, so a static preview can capture them. Every row
 * carries its second line (e.g. that "Copy SVG" is the Figma route).
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
              is EditorExportMenuEntry.Share -> Icons.Filled.Share
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
      else "Compose UI Builder · ${catalogFormFactorLabel(state.document) ?: catalogSystemId}",
    modifier = modifier,
  )
}

/**
 * The design's form factor as the New design form names it ("Wear app" rather than `wear-m3`), or
 * null when unknown (callers fall back to the id).
 */
internal fun catalogFormFactorLabel(document: UiBuilderDocument): String? =
  when (document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull) {
    "m3-catalog" -> "Android app"
    "wear-m3" -> "Wear app"
    "remote-m3" -> "Wear widget"
    A2uiDocumentExporter.CATALOG_SYSTEM_ID -> "A2UI surface"
    else -> null
  }

/** The design's form factor, read-only, beside the compact app bar's title. */
@Composable
private fun FormFactorChip(label: String) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.secondaryContainer,
    modifier = Modifier.semantics { contentDescription = "Form factor: $label" },
  ) {
    Text(
      label,
      Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
      maxLines = 1,
    )
  }
}

/**
 * Which host container a Wear widget is framed in, offered only on widget designs.
 *
 * The shape is the host's (the launcher draws it from `WearWidgetParams`), so switching it changes
 * the view, not the document. It answers whether a widget survives another frame: the rectangular
 * container's content box and padding differ from the squircle's.
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
 * Where a reference [piece]'s centre falls in canvas inspection pixels, converted through
 * [canvasFrameDp] (a widget's host container, not its 1280x800dp environment).
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
 * The frame the editing canvas draws this design in, in dp. A Wear widget uses its host container
 * in [hostShape] rather than the phone environment widget designs are seeded with; the same table
 * the preview panes use.
 */
internal fun UiBuilderDocument.canvasFrameDp(hostShape: WearWidgetHostShape): Pair<Float, Float> {
  wearWidgetScaffoldSize()?.hostSpec(hostShape)?.let {
    return it.frameWidthDp.toFloat() to it.frameHeightDp.toFloat()
  }
  return (environment["widthDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1280f) to
    (environment["heightDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 800f)
}

/**
 * The widget container this design's root is, or null for a non-widget design. Read from the root
 * scaffold, which fixes the frame.
 */
/**
 * This design as sent to a catalog runtime drawn in [shape]: the shape named in its environment
 * under [WearWidgetHostShape.ENVIRONMENT_KEY]. Non-widgets are sent unchanged.
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
 * Which design panes are open, as independent switches. The last open pane cannot be switched off;
 * a host with no compile lane keeps the native row disabled with the catalog's reason.
 */
@Composable
private fun WorkspacePanesMenu(
  panes: Set<EditorPane>,
  availablePanes: Set<EditorPane>,
  /**
   * The catalog's own claims, so a pane that cannot render faithfully says so where it is chosen.
   * Wasm panes are never removed: the canvas is where nodes are selected and dragged.
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
            // Cannot turn off the only pane, nor turn on one the host cannot draw.
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
  var expanded by remember { mutableStateOf(false) }
  Box {
    TextButton(onClick = { expanded = true }) { Text(presenceLabel(collaborators)) }
    androidx.compose.material3.DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      presenceRows(collaborators).forEach { row ->
        DropdownMenuItem(text = { Text(row) }, onClick = { expanded = false })
      }
    }
  }
}

private fun presenceLabel(collaborators: List<UiBuilderCollaborator>) =
  "Viewers · ${collaborators.size + 1}"

/** One line per viewer, this browser first: the presence menu's rows, wherever it is shown. */
private fun presenceRows(collaborators: List<UiBuilderCollaborator>): List<String> =
  listOf("You · browser") +
    collaborators.map { collaborator ->
      val kind =
        when (collaborator.kind) {
          UiBuilderParticipantKind.Browser -> "browser"
          UiBuilderParticipantKind.Agent -> "agent"
          UiBuilderParticipantKind.Unknown -> "participant"
        }
      "${collaborator.displayName} · $kind" +
        collaborator.modelName?.let { " · $it (reported)" }.orEmpty()
    }

/**
 * Everything that can be done to the selection, as menu rows: used by the layers tree context menu,
 * the canvas context menu and the selection overflow.
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
  /**
   * Why the selection cannot become a component, or null. The verb is always offered on a single
   * selection, with the refusal as its text.
   */
  makeComponentRefusal: String? = null,
  /** Whether to offer "Make component" at all: one node is selected. */
  canOfferMakeComponent: Boolean = false,
  /** Detach a placement, or make a body property a parameter or stop it being one. */
  componentActions: List<EditorComponentAction> = emptyList(),
  /** Opens the quick editor beside the design, or null where the selection has none. */
  onQuickEdit: (() -> Unit)? = null,
  /** Opens a comment field beside the node, for the conversation the editor sits in, or null. */
  onComment: (() -> Unit)? = null,
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
  if (onQuickEdit != null) {
    add(
      UiBuilderMenuEntry.Action(
        "Quick edit",
        icon = UiBuilderMenuIcon.QuickEdit,
        contentDescription = "Quick edit beside the design",
        shortcut = "E",
      ) {
        onDismiss()
        onQuickEdit()
      }
    )
  }
  if (onComment != null) {
    add(
      UiBuilderMenuEntry.Action(
        "Comment",
        icon = UiBuilderMenuIcon.Comment,
        contentDescription = "Comment on this node in the chat",
      ) {
        onDismiss()
        onComment()
      }
    )
  }
  // Beside Properties: both point at the selected layer. This copies a layer's address; the Export
  // menu's link is the design's.
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
  if (onOpenProperties != null || onQuickEdit != null || onComment != null || onCopyLink != null) {
    add(UiBuilderMenuEntry.Divider)
  }
  add(
    UiBuilderMenuEntry.Action(
      "Duplicate",
      icon = UiBuilderMenuIcon.Duplicate,
      enabled = canDuplicate,
      shortcut = "$COMMAND_MODIFIER+D",
    ) {
      act(UiBuilderEditorEvent.DuplicateSelected)
    }
  )
  add(
    UiBuilderMenuEntry.Action(
      "Copy",
      icon = UiBuilderMenuIcon.Copy,
      enabled = canCopy,
      shortcut = "$COMMAND_MODIFIER+C",
    ) {
      act(UiBuilderEditorEvent.CopySelected)
    }
  )
  add(
    UiBuilderMenuEntry.Action(
      "Cut",
      icon = UiBuilderMenuIcon.Cut,
      enabled = canCut,
      shortcut = "$COMMAND_MODIFIER+X",
    ) {
      act(UiBuilderEditorEvent.CutSelected)
    }
  )
  add(
    UiBuilderMenuEntry.Action(
      "Paste",
      icon = UiBuilderMenuIcon.Paste,
      enabled = canPaste,
      shortcut = "$COMMAND_MODIFIER+V",
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
  // Layout before container verbs: the chain is the node's own business, wrapping its parent's.
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
  if (
    wrapCandidates.isNotEmpty() ||
      canUnwrap ||
      canOfferMakeComponent ||
      componentActions.isNotEmpty()
  )
    add(UiBuilderMenuEntry.Divider)
  componentActions.forEach { action ->
    add(
      UiBuilderMenuEntry.Action(action.label, icon = UiBuilderMenuIcon.Wrap) { act(action.event) }
    )
  }
  // Beside the container verbs, because it is one: it turns the subtree into the body of a
  // component and leaves a placement where it was.
  if (canOfferMakeComponent) {
    add(
      UiBuilderMenuEntry.Action(
        label = "Make component",
        icon = UiBuilderMenuIcon.Wrap,
        enabled = makeComponentRefusal == null,
        contentDescription = makeComponentRefusal ?: "Make a reusable component of this layer",
      ) {
        act(UiBuilderEditorEvent.MakeComponent())
      }
    )
  }
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
 * Runs one Copy link against the host and returns the sentence to show; failures are sentences too,
 * shown where the button was.
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

private fun visibilityIcon(label: String) =
  when {
    label.startsWith("Private") -> Icons.Filled.Lock
    label.startsWith("Public") -> Icons.Filled.Public
    else -> Icons.Filled.Share
  }

@Composable
private fun VisibilityToolbarAction(label: String, onManage: (() -> Unit)?) {
  val title =
    when {
      label.startsWith("Private") -> "Private"
      label.startsWith("Public") -> "Public"
      else -> "Sharing"
    }
  MaterialChromeTooltip("Sharing · $label", "") {
    if (onManage != null) {
      TextButton(
        onClick = onManage,
        modifier = Modifier.semantics { contentDescription = "Sharing · $label" },
      ) {
        Icon(visibilityIcon(label), contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(title)
      }
    } else {
      Row(
        Modifier.padding(horizontal = 8.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(visibilityIcon(label), contentDescription = null, modifier = Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

/**
 * One strip over the canvas for a pinned revision, a stale selector or a just-copied link. The
 * revision line persists; the others expire.
 */
@Composable
internal fun EditorUrlBanner(
  revisionPin: DesignRevisionPin?,
  onGoToLatest: (() -> Unit)?,
  openingNotice: String?,
  transientNotice: String?,
  noticeAction: EditorNoticeAction? = null,
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
      if (noticeAction != null && openingNotice != null) {
        TextButton(onClick = noticeAction.onClick) { Text(noticeAction.label) }
      }
    }
  }
}

/**
 * The one action a host's [openingNotice][EditorUrlBanner] asks for (often sign-in), drawn as a
 * button beside the sentence.
 */
class EditorNoticeAction(val label: String, val onClick: () -> Unit)

/**
 * What can be done to the selection, beside it, only while there is one. Icons for verbs every tool
 * draws the same; words for wrap and unwrap. For a single layer the label is its root-to-leaf path;
 * for several, the count.
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
          // Distinct from the rail switch's "Open properties panel", so locators and screen readers
          // can tell them apart.
          modifier = Modifier.semantics { contentDescription = "Edit properties" },
        ) {
          Text("Properties")
        }
      }
      // One overflow menu: the same verbs as right-click, plus the chords.
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
 * The selection's path, root to leaf, as pressable rungs. Each rung shows the layer name and, where
 * informative, its slot. Pressing an ancestor selects it; the leaf is drawn selected. Long paths
 * elide from the root, never the leaf, and scroll to keep the leaf visible.
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

/** The line under the canvas: document revision, where a drag would land, and the session. */
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
        // Only while dragging; at rest the drop target is meaningless.
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

/** The toolbar button's label: the open panes named in enum order, not counted. */
internal fun panesLabel(panes: Set<EditorPane>): String =
  EditorPane.entries.filter { it in panes }.joinToString(" + ") { it.label }.ifEmpty { "No panes" }

/**
 * One line under each pane's name for the catalog's own caveat, e.g. that `wear-m3` canvas
 * components are browser stand-ins.
 */
internal fun EditorPane.supportingText(
  surfaces: UiBuilderPreviewSurfaces = UiBuilderPreviewSurfaces.DEFAULT
): String {
  val wasmDescription =
    if (surfaces.wasm.fidelity.isAuthoritative) "Wasm" else "Wasm stand-in, for authoring"
  return when (this) {
    EditorPane.Editor -> "Edit the design · $wasmDescription"
    // It does not edit and does not compile. "Devices" means device properties (width, height,
    // density), never a drawn handset.
    EditorPane.Preview -> "Devices, overrides and themes · not editable · $wasmDescription"
    EditorPane.Native ->
      if (surfaces.native.backend == UiBuilderPreviewSurfaces.BACKEND_ANDROID)
        "Compiled on the host · Android"
      else "Compiled on the host · the target platform"
  }
}

/**
 * Why a pane cannot be switched on. Only [EditorPane.Native] needs one, using the catalog's own
 * sentence where written.
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
 * One icon control, with label and chord in the tooltip and semantics. The contentDescription keeps
 * the `"$label ($shortcut)"` shape scripts and the accessibility tree look for.
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
 * The component-pack settings: one switch per pack. A dialog because it is a setting remembered by
 * the host. Switching a pack off hides its shelf, not placed nodes.
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
internal fun EditorShortcutsDialog(onDismiss: () -> Unit) {
  TrackEditorOverlay(true)
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Keyboard and pointer") },
    // Scrolls, because the table outgrew a short window once the zoom chords and `?` joined it,
    // and a dialog clipped at the bottom hides exactly the rows nobody has read yet.
    text = { EditorShortcutsPanel(Modifier.verticalScroll(rememberScrollState())) },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}

/**
 * The shortcut table, rendered from [EDITOR_SHORTCUTS] and [EDITOR_GESTURES] so help cannot drift
 * from the handler. Separate from the dialog so it can be previewed.
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

/**
 * The command modifier in every shortcut hint, spelled in letters. Not `⌘`: no bundled font has it,
 * Compose would fetch a symbols font that an MCP App's CSP refuses, and it would draw as a box. The
 * editor answers Ctrl and Cmd alike.
 */
internal const val COMMAND_MODIFIER = "Ctrl/Cmd"

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
  canvasShown: Boolean,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  onViewCommand: (EditorViewCommand) -> Unit,
): Boolean {
  if (!enabled || event.type != KeyEventType.KeyDown) return false
  val chord =
    EditorChord(
      key = event.key,
      command = event.isCtrlPressed || event.isMetaPressed,
      shift = event.isShiftPressed,
    )
  val match = editorShortcutFor(chord, editing, canvasShown) ?: return false
  match.event?.let(dispatch)
  match.view?.let(onViewCommand)
  // Returning true is also what keeps the browser out of it: the Wasm host prevents the default of
  // a key event Compose reports as consumed, so Ctrl/Cmd+= zooms the design rather than the page —
  // and only while this table claims it. With the canvas closed or a field focused nothing matches,
  // and the browser's own page zoom answers as it always did.
  return true
}

/**
 * The shortcut a chord resolves to, or null. Pure so precedence is testable without key events.
 * With the canvas switched off ([editing] false), only pane-opening chords are live, so nothing
 * edits invisibly.
 */
internal fun editorShortcutFor(
  chord: EditorChord,
  editing: Boolean = true,
  canvasShown: Boolean = editing,
): EditorShortcut? =
  EDITOR_SHORTCUTS.firstOrNull { it.matches(chord) }
    ?.takeIf {
      when {
        // The zoom chords need the canvas itself on screen, which the open editor pane does not
        // promise: an old revision or a suggestion under review replaces the canvas, and the
        // compact layout may be showing a preview tab. Taken then, they would rescale a canvas
        // nobody can see from a stale drawn scale, and keep the browser's page zoom from answering.
        it.view is EditorViewCommand.Zoom -> canvasShown
        // Quick insert opens on the canvas, beside the selection, so it needs one on screen too.
        it.view == EditorViewCommand.QuickInsert -> canvasShown
        else ->
          editing ||
            it.event is UiBuilderEditorEvent.TogglePane ||
            it.view == EditorViewCommand.ShowShortcuts
      }
    }

/** The part of a key press a shortcut is allowed to look at. */
internal data class EditorChord(val key: Key, val command: Boolean, val shift: Boolean)

/**
 * One chord the editor answers to. [shift] null means "does not care": `Ctrl/⌘+Z` reaches undo only
 * because redo claims the shifted spelling first.
 */
internal data class EditorShortcut(
  val chord: String,
  val description: String,
  val event: UiBuilderEditorEvent? = null,
  val keys: Set<Key>,
  val command: Boolean,
  val shift: Boolean? = null,
  /** What the chord does to the editor's window instead, for a chord that is not an [event]. */
  val view: EditorViewCommand? = null,
) {
  init {
    require((event == null) != (view == null)) { "$chord must do exactly one thing" }
  }

  fun matches(pressed: EditorChord): Boolean =
    pressed.command == command && (shift == null || shift == pressed.shift) && pressed.key in keys
}

/**
 * What a chord does to the person's view rather than to the design. Not a [UiBuilderEditorEvent]:
 * the zoom is not editor state and the shortcuts dialog is a window, so neither has a reducer.
 */
internal sealed interface EditorViewCommand {
  data class Zoom(val zoom: CanvasZoomCommand) : EditorViewCommand

  /** Opens the panel these rows are drawn in. */
  data object ShowShortcuts : EditorViewCommand

  /**
   * Opens the quick insert search beside the selection. A window like the shortcuts dialog: what it
   * inserts goes through the palette's own insert events, so it needs no reducer of its own.
   */
  data object QuickInsert : EditorViewCommand
}

/**
 * Every key chord in match order, also rendered by the shortcuts panel so help stays accurate.
 *
 * Order is behaviour: redo precedes undo, and reordering arrows precede navigating ones.
 * `editorShortcutsAreAllReachable` asserts each entry is the first match for its own chord.
 */
internal val EDITOR_SHORTCUTS: List<EditorShortcut> =
  listOf(
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+Shift+Z",
      description = "Redo",
      event = UiBuilderEditorEvent.Redo,
      keys = setOf(Key.Z),
      command = true,
      shift = true,
    ),
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+Y",
      description = "Redo",
      event = UiBuilderEditorEvent.Redo,
      keys = setOf(Key.Y),
      command = true,
    ),
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+Z",
      description = "Undo",
      event = UiBuilderEditorEvent.Undo,
      keys = setOf(Key.Z),
      command = true,
    ),
    // The shifted spelling first, so the plain one below does not eat it — the same rule redo and
    // undo follow two entries up.
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+Shift+Enter",
      description = "Show or hide the visual editor",
      event = UiBuilderEditorEvent.TogglePane(EditorPane.Editor),
      keys = setOf(Key.Enter, Key.NumPadEnter),
      command = true,
      shift = true,
    ),
    // Enter rather than P: Ctrl/⌘+P is the browser's print dialog.
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+Enter",
      description = "Show or hide the preview beside the design",
      event = UiBuilderEditorEvent.TogglePane(EditorPane.Preview),
      keys = setOf(Key.Enter, Key.NumPadEnter),
      command = true,
      shift = false,
    ),
    // A letter, not Enter: this table is read in the preview pass ahead of focused controls, which
    // must keep their own Enter.
    EditorShortcut(
      chord = "E",
      description = "Open or close the selection's quick editor",
      event = UiBuilderEditorEvent.ToggleQuickEditor,
      keys = setOf(Key.E),
      command = false,
      shift = false,
    ),
    EditorShortcut(
      chord = "Esc",
      description = "Close the quick editor",
      event = UiBuilderEditorEvent.HideQuickEditor,
      keys = setOf(Key.Escape),
      command = false,
    ),
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+D",
      description = "Duplicate the selection in place",
      event = UiBuilderEditorEvent.DuplicateSelected,
      keys = setOf(Key.D),
      command = true,
    ),
    // Reorder before plain navigation, so the modified arrows are not eaten by selection.
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+\u2191",
      description = "Move the selection earlier in its slot",
      event = UiBuilderEditorEvent.MoveSelected(EditorMoveDirection.Before),
      keys = setOf(Key.DirectionUp),
      command = true,
    ),
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+\u2193",
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
      chord = "$COMMAND_MODIFIER+C",
      description = "Copy the selection",
      event = UiBuilderEditorEvent.CopySelected,
      keys = setOf(Key.C),
      command = true,
    ),
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+X",
      description = "Cut the selection",
      event = UiBuilderEditorEvent.CutSelected,
      keys = setOf(Key.X),
      command = true,
    ),
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+V",
      description = "Paste into the selected container",
      event = UiBuilderEditorEvent.Paste,
      keys = setOf(Key.V),
      command = true,
    ),
    // The zoom chords every canvas tool and every browser already uses, so a hand that reaches
    // for them out of habit zooms the design and not the page around it. `=` rather than only
    // `+`, because `+` is the shifted `=` on most layouts and nobody holds shift to zoom in.
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+= / +",
      description = "Zoom in",
      view = EditorViewCommand.Zoom(CanvasZoomCommand.In),
      keys = setOf(Key.Equals, Key.Plus, Key.NumPadAdd),
      command = true,
    ),
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+-",
      description = "Zoom out",
      view = EditorViewCommand.Zoom(CanvasZoomCommand.Out),
      keys = setOf(Key.Minus, Key.NumPadSubtract),
      command = true,
    ),
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+0",
      description = "Zoom to fit the design in the window",
      view = EditorViewCommand.Zoom(CanvasZoomCommand.Fit),
      keys = setOf(Key.Zero, Key.NumPad0),
      command = true,
      shift = false,
    ),
    EditorShortcut(
      chord = "$COMMAND_MODIFIER+1",
      description = "Zoom to actual size (100%)",
      view = EditorViewCommand.Zoom(CanvasZoomCommand.ActualSize),
      keys = setOf(Key.One, Key.NumPad1),
      command = true,
      shift = false,
    ),
    // Bare `/`, the slash command of every editor that has one. The table is only consulted while
    // no text field has the caret, so a `/` typed into a property stays a slash. Unshifted, so `?`
    // below keeps its own chord whichever order the two are in.
    EditorShortcut(
      chord = "/",
      description = "Quick insert a component into the selection",
      view = EditorViewCommand.QuickInsert,
      keys = setOf(Key.Slash),
      command = false,
      shift = false,
    ),
    // Shift+/ is `?` on the layouts this is spelled for. Bare, like E, because the table is only
    // consulted while no text field has the caret — and it is live with the canvas closed too,
    // since a list of what the keys do is most wanted by someone who has just lost the canvas.
    EditorShortcut(
      chord = "?",
      description = "Show these shortcuts",
      view = EditorViewCommand.ShowShortcuts,
      keys = setOf(Key.Slash),
      command = false,
      shift = true,
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
 * The pointer gestures, which no chord or button can advertise; without them a selection is one
 * node.
 */
internal val EDITOR_GESTURES: List<Pair<String, String>> =
  listOf(
    "$COMMAND_MODIFIER + click a layer" to "Add one layer to the selection, or take it out",
    "Shift + click a layer" to "Extend the selection to that layer",
    "Drag a layer row" to "Drop it on the layer or the slot it should join",
    "Drag a catalog component" to "Insert it where it is dropped",
    "Hold a node, then drag" to "Move it into the slot and seam it is dropped at",
    "Click a rung above the canvas" to "Select that layer — the path is a way back up",
    "Alt/Option + hover a node" to "Measure its distance in dp from the selection",
  )

/**
 * [EditorToolbar]'s controls published to a host that draws them itself, minus what the host owns
 * (name, new, browse, export, reconnect, AI prompt). The shortcuts dialog is still drawn here.
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
        UiBuilderHostAction(
          "undo",
          "Undo",
          "toolbar",
          "Undo",
          canUndo,
          shortcut = "$COMMAND_MODIFIER+Z",
        )
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
          shortcut = "$COMMAND_MODIFIER+Shift+Z",
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
