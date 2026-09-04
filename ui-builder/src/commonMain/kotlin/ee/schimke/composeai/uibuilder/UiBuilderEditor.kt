@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import kotlin.math.roundToInt
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The inspector's width, named because two places have to agree on it.
 *
 * A default on [PropertyInspector] alone does nothing: the desktop layout passes its own modifier,
 * so widening the default for a fourth tab widened the preview and left every real editor at the
 * three-tab width. Four tabs share it, and the widest label has to stay legible.
 */
private val INSPECTOR_WIDTH = 360.dp

private val EditorColors =
  darkColorScheme(
    background = Color(0xff121316),
    surface = Color(0xff1b1c20),
    surfaceVariant = Color(0xff282a30),
    primary = Color(0xffb9c3ff),
    onPrimary = Color(0xff17215b),
    outline = Color(0xff454750),
  )

private enum class MobileEditorPanel {
  None,
  Components,
  Properties,
}

data class UiBuilderNewDesignTemplate(
  val id: String,
  val label: String,
  val supportingText: String,
)

data class UiBuilderNewDesignCatalog(
  val systemId: String,
  val label: String,
  val templates: List<UiBuilderNewDesignTemplate>,
)

@Composable
fun UiBuilderEditor(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
  onStateChanged: (UiBuilderEditorState) -> Unit = {},
  onCanvasMetrics: (Int, Int, Float) -> Unit = { _, _, _ -> },
  onCanvasBoundsChanged: (Rect) -> Unit = {},
  onDropTargetChanged: (Boolean, String) -> Unit = { _, _ -> },
  onInspectionSnapshot: ((UiBuilderInspectionSnapshot) -> Unit)? = null,
  onInspectionInvalidated: ((UiBuilderInspectionCollector) -> Unit)? = null,
  showSelectionOverlay: Boolean = true,
  actorId: String = EDITOR_ACTOR_ID,
  clientId: String = EDITOR_CLIENT_ID,
  operationIdPrefix: String = clientId,
  sessionLabel: String = "Local session",
  onReconnect: (() -> Unit)? = null,
  onSubmission: ((EditorSubmission) -> Unit)? = null,
  authoritativeGeneration: Int = 0,
  initialSelectedNodeId: String? = null,
  initialCatalogQuery: String = "",
  initialLayerQuery: String = "",
  initialInspectorMode: EditorInspectorMode = EditorInspectorMode.Properties,
  initialPreviewMode: Boolean = false,
  collaborators: List<UiBuilderCollaborator> = emptyList(),
  newDesignCatalogs: List<UiBuilderNewDesignCatalog> = emptyList(),
  onCreateDesign: ((catalogSystemId: String, designId: String, templateId: String) -> Unit)? = null,
  onHelp: (() -> Unit)? = null,
) {
  val reducer =
    remember(catalog, actorId, clientId, operationIdPrefix) {
      UiBuilderEditorReducer(catalog, actorId, clientId, operationIdPrefix)
    }
  var state by
    remember(document.id) {
      mutableStateOf(
        reducer
          .initial(
            document,
            selectedNodeId =
              initialSelectedNodeId?.takeIf(document.nodes::containsKey)
                ?: document.roots.firstOrNull(),
          )
          .copy(
            catalogQuery = initialCatalogQuery,
            layerQuery = initialLayerQuery,
            inspectorMode = initialInspectorMode,
            previewMode = initialPreviewMode,
          )
      )
    }
  LaunchedEffect(document.revision, authoritativeGeneration) {
    if (state.document != document) {
      state = reducer.reconciled(state, document, initialSelectedNodeId)
    }
  }
  var catalogDragPosition by remember { mutableStateOf<Offset?>(null) }
  var draggedComponentId by remember { mutableStateOf<String?>(null) }
  var canvasBounds by remember { mutableStateOf(Rect.Zero) }
  var textInputFocused by remember { mutableStateOf(false) }
  var mobilePanel by remember(document.id) { mutableStateOf(MobileEditorPanel.None) }
  var showNewDesign by remember(document.id) { mutableStateOf(false) }
  val editorFocusRequester = remember { FocusRequester() }
  val draggedTarget = draggedComponentId?.let { reducer.dropTarget(state, it) }
  val canvasDropHovered =
    catalogDragPosition?.let(canvasBounds::contains) == true && draggedTarget != null
  fun dispatch(event: UiBuilderEditorEvent) {
    val previous = state
    val current = reducer.reduce(previous, event)
    state = current
    reducer.acceptedSubmission(previous, current)?.let { onSubmission?.invoke(it) }
  }
  fun focusEditor() {
    textInputFocused = false
    editorFocusRequester.requestFocus()
  }
  LaunchedEffect(state) { onStateChanged(state) }
  LaunchedEffect(Unit) { editorFocusRequester.requestFocus() }
  LaunchedEffect(canvasDropHovered, draggedTarget) {
    onDropTargetChanged(
      canvasDropHovered,
      draggedTarget?.let { "${it.nodeId}.${it.slot}" } ?: "No compatible slot",
    )
  }
  // Cached for the same reason as the issues scan further down, at a smaller scale: the filter
  // lowercases and scans four strings for every node in the document, and the panel recomposes far
  // more often than either the document or the query changes.
  val treeRows =
    remember(reducer, state.document, state.layerQuery) { reducer.visibleTreeRows(state) }
  val navigator: @Composable (Modifier, Boolean) -> Unit = { modifier, closeAfterDrop ->
    EditorNavigator(
      state = state,
      catalogSystemId = catalog.benchmark.catalogSystemId,
      catalogItems = reducer.catalogItems(state.catalogQuery),
      treeRows = treeRows,
      collaborators = collaborators,
      dropTargetLabel = reducer.dropTargetLabel(state, draggedComponentId ?: "m3/text"),
      onCatalogDrag = { componentId, position ->
        if (position != null) focusEditor()
        draggedComponentId = componentId
        catalogDragPosition = position
      },
      onCatalogDrop = { componentId, position ->
        val target = reducer.dropTarget(state, componentId)
        if (canvasBounds.contains(position) && target != null) {
          dispatch(UiBuilderEditorEvent.InsertComponent(componentId, target))
          if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
        }
        draggedComponentId = null
        catalogDragPosition = null
      },
      canAddCatalogComponent = { reducer.dropTarget(state, it) != null },
      onCatalogAdd = { componentId ->
        focusEditor()
        reducer.dropTarget(state, componentId)?.let { target ->
          dispatch(UiBuilderEditorEvent.InsertComponent(componentId, target))
          if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
        }
      },
      moveTarget = { nodeId, direction -> reducer.moveTarget(state, nodeId, direction) },
      onEditorInteraction = ::focusEditor,
      onTextInputFocusChanged = { textInputFocused = it },
      dispatch = ::dispatch,
      modifier = modifier,
    )
  }
  val canvas: @Composable (Modifier, Alignment) -> Unit = { modifier, alignment ->
    PinnedDesignCanvas(
      document = state.document,
      selectedNodeId = state.selectedNodeId,
      onNodeSelected = {
        focusEditor()
        dispatch(UiBuilderEditorEvent.SelectNode(it))
      },
      onCanvasMetrics = onCanvasMetrics,
      onCanvasBounds = {
        canvasBounds = it
        onCanvasBoundsChanged(it)
      },
      dropHovered = canvasDropHovered,
      showSelectionOverlay = showSelectionOverlay && !state.previewMode,
      collaborators = collaborators,
      onInspectionSnapshot = onInspectionSnapshot,
      onInspectionInvalidated = onInspectionInvalidated,
      contentAlignment = alignment,
      modifier = modifier,
    )
  }
  // Cached against the document, because it is not cheap and depends on nothing else: it walks
  // every node and every property against the catalog, traverses the graph and looks for cycles.
  // Called inline it would run all of that on every recomposition of the inspector — which is
  // every keystroke in a property field and every frame of a drag.
  val problems = remember(reducer, state.document) { reducer.problems(state.document) }
  val inspector: @Composable (Modifier) -> Unit = { modifier ->
    PropertyInspector(
      state = state,
      fields = reducer.propertyFields(state),
      problems = problems,
      themeSettings = reducer.themeSettings(state),
      onTextInputFocusChanged = { textInputFocused = it },
      dispatch = ::dispatch,
      modifier = modifier,
    )
  }

  MaterialTheme(colorScheme = EditorColors) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val compact = maxWidth < 840.dp
      Column(
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .focusRequester(editorFocusRequester)
          .focusable()
          .onPreviewKeyEvent { event ->
            editorShortcut(
              event,
              enabled = !textInputFocused,
              previewing = state.previewMode,
              dispatch = ::dispatch,
            )
          }
      ) {
        if (compact) {
          MobileEditorToolbar(
            state = state,
            canDelete = reducer.canDeleteSelected(state),
            canDuplicate = reducer.canDuplicateSelected(state),
            canCopy = reducer.canCopySelected(state),
            canCut = reducer.canCutSelected(state),
            canPaste = reducer.canPaste(state),
            wrapCandidates = reducer.wrapCandidates(state),
            canUnwrap = reducer.canUnwrapSelected(state),
            canUndo = reducer.canUndo(state),
            canRedo = reducer.canRedo(state),
            onNewDesign =
              if (newDesignCatalogs.isNotEmpty() && onCreateDesign != null) {
                { showNewDesign = true }
              } else null,
            onReconnect = onReconnect,
            onHelp = onHelp,
            dispatch = ::dispatch,
          )
        } else {
          EditorToolbar(
            state = state,
            canDelete = reducer.canDeleteSelected(state),
            canDuplicate = reducer.canDuplicateSelected(state),
            canCopy = reducer.canCopySelected(state),
            canCut = reducer.canCutSelected(state),
            canPaste = reducer.canPaste(state),
            wrapCandidates = reducer.wrapCandidates(state),
            canUnwrap = reducer.canUnwrapSelected(state),
            canUndo = reducer.canUndo(state),
            canRedo = reducer.canRedo(state),
            sessionLabel = sessionLabel,
            collaborators = collaborators,
            onNewDesign =
              if (newDesignCatalogs.isNotEmpty() && onCreateDesign != null) {
                { showNewDesign = true }
              } else null,
            onReconnect = onReconnect,
            onHelp = onHelp,
            dispatch = ::dispatch,
          )
        }
        Box(Modifier.fillMaxSize()) {
          if (!compact) {
            Row(Modifier.fillMaxSize()) {
              navigator(Modifier.width(300.dp).fillMaxHeight(), false)
              canvas(
                Modifier.weight(1f).fillMaxHeight().background(Color(0xff0d0e11)).padding(20.dp),
                Alignment.TopStart,
              )
              inspector(Modifier.width(INSPECTOR_WIDTH).fillMaxHeight())
            }
          } else {
            canvas(
              Modifier.fillMaxSize()
                .background(Color(0xff0d0e11))
                .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 64.dp),
              Alignment.Center,
            )
            if (mobilePanel == MobileEditorPanel.Components) {
              navigator(
                Modifier.align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .fillMaxHeight(0.72f)
                  .padding(bottom = 56.dp),
                true,
              )
            }
            if (mobilePanel == MobileEditorPanel.Properties) {
              inspector(
                Modifier.align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .fillMaxHeight(0.72f)
                  .padding(bottom = 56.dp)
              )
            }
            MobilePanelDock(
              panel = mobilePanel,
              onPanelChanged = {
                mobilePanel = if (mobilePanel == it) MobileEditorPanel.None else it
              },
              modifier = Modifier.align(Alignment.BottomCenter),
            )
          }
        }
      }
      if (showNewDesign && onCreateDesign != null) {
        NewDesignDialog(
          catalogs = newDesignCatalogs,
          initialCatalogSystemId =
            document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull
              ?: newDesignCatalogs.first().systemId,
          onDismiss = { showNewDesign = false },
          onCreate = onCreateDesign,
        )
      }
    }
  }
}

@Composable
private fun NewDesignDialog(
  catalogs: List<UiBuilderNewDesignCatalog>,
  initialCatalogSystemId: String,
  onDismiss: (() -> Unit)?,
  onCreate: (catalogSystemId: String, designId: String, templateId: String) -> Unit,
) {
  val initialCatalog =
    catalogs.firstOrNull { it.systemId == initialCatalogSystemId } ?: catalogs.first()
  var selectedCatalogId by remember { mutableStateOf(initialCatalog.systemId) }
  var selectedTemplateId by remember {
    mutableStateOf(initialCatalog.templates.firstOrNull()?.id.orEmpty())
  }
  var designId by remember { mutableStateOf("") }
  val selectedCatalog = catalogs.first { it.systemId == selectedCatalogId }
  val selectedTemplate =
    selectedCatalog.templates.firstOrNull { it.id == selectedTemplateId }
      ?: selectedCatalog.templates.first()
  val designIdValid = designId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))

  AlertDialog(
    onDismissRequest = { onDismiss?.invoke() },
    title = { Text("Create a new design") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Catalog", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          catalogs.forEach { catalog ->
            FilterChip(
              selected = catalog.systemId == selectedCatalogId,
              onClick = {
                selectedCatalogId = catalog.systemId
                selectedTemplateId = catalog.templates.first().id
              },
              label = { Text(catalog.label) },
            )
          }
        }
        Text("Starting point", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          selectedCatalog.templates.forEach { template ->
            FilterChip(
              selected = template.id == selectedTemplate.id,
              onClick = { selectedTemplateId = template.id },
              label = { Text(template.label) },
            )
          }
        }
        Text(
          selectedTemplate.supportingText,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        Text("Design ID", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
          value = designId,
          onValueChange = { designId = it },
          modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Design ID" },
          placeholder = { Text("my-widget") },
          supportingText = {
            Text(
              if (designId.isEmpty() || designIdValid) {
                "Letters, numbers, dots, underscores, and hyphens"
              } else {
                "Start with a letter or number and use only path-safe characters"
              }
            )
          },
          isError = designId.isNotEmpty() && !designIdValid,
          singleLine = true,
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onCreate(selectedCatalog.systemId, designId, selectedTemplate.id) },
        enabled = designIdValid,
      ) {
        Text("Create")
      }
    },
    dismissButton = { if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
fun UiBuilderNewDesignScreen(
  catalogs: List<UiBuilderNewDesignCatalog>,
  initialCatalogSystemId: String,
  onCreate: (catalogSystemId: String, designId: String, templateId: String) -> Unit,
) {
  require(catalogs.isNotEmpty()) { "new design screen requires at least one catalog" }
  MaterialTheme(colorScheme = EditorColors) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    NewDesignDialog(
      catalogs = catalogs,
      initialCatalogSystemId = initialCatalogSystemId,
      onDismiss = null,
      onCreate = onCreate,
    )
  }
}

@Composable
private fun MobileEditorToolbar(
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
  onReconnect: (() -> Unit)?,
  onHelp: (() -> Unit)?,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
    Row(
      Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("UI Builder", Modifier.weight(1f), fontWeight = FontWeight.Bold)
      EditorAction("Undo", "Ctrl/⌘+Z", canUndo) { dispatch(UiBuilderEditorEvent.Undo) }
      EditorAction("Redo", "Ctrl/⌘+Shift+Z", canRedo) { dispatch(UiBuilderEditorEvent.Redo) }
      Box {
        TextButton(
          onClick = { expanded = true },
          modifier = Modifier.semantics { contentDescription = "More editor actions" },
        ) {
          Text("More")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          if (onNewDesign != null) {
            DropdownMenuItem(
              text = { Text("New design") },
              onClick = {
                expanded = false
                onNewDesign()
              },
            )
          }
          DropdownMenuItem(
            text = { Text("Duplicate") },
            enabled = canDuplicate,
            onClick = {
              expanded = false
              dispatch(UiBuilderEditorEvent.DuplicateSelected)
            },
          )
          DropdownMenuItem(
            text = { Text("Copy") },
            enabled = canCopy,
            onClick = {
              expanded = false
              dispatch(UiBuilderEditorEvent.CopySelected)
            },
          )
          DropdownMenuItem(
            text = { Text("Cut") },
            enabled = canCut,
            onClick = {
              expanded = false
              dispatch(UiBuilderEditorEvent.CutSelected)
            },
          )
          DropdownMenuItem(
            text = { Text("Paste") },
            enabled = canPaste,
            onClick = {
              expanded = false
              dispatch(UiBuilderEditorEvent.Paste)
            },
          )
          DropdownMenuItem(
            text = { Text("Delete") },
            enabled = canDelete,
            onClick = {
              expanded = false
              dispatch(UiBuilderEditorEvent.DeleteSelected)
            },
          )
          if (onReconnect != null) {
            DropdownMenuItem(
              text = { Text("Reconnect") },
              onClick = {
                expanded = false
                onReconnect()
              },
            )
          }
          if (onHelp != null) {
            DropdownMenuItem(
              text = { Text("Help") },
              onClick = {
                expanded = false
                onHelp()
              },
            )
          }
        }
      }
      Text("r${state.document.revision}", style = MaterialTheme.typography.labelMedium)
    }
  }
}

@Composable
private fun MobilePanelDock(
  panel: MobileEditorPanel,
  onPanelChanged: (MobileEditorPanel) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier.fillMaxWidth().height(56.dp), tonalElevation = 6.dp) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
      MobilePanelButton("Components", MobileEditorPanel.Components, panel, onPanelChanged)
      MobilePanelButton("Properties", MobileEditorPanel.Properties, panel, onPanelChanged)
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

@Composable
private fun EditorToolbar(
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
  sessionLabel: String,
  collaborators: List<UiBuilderCollaborator>,
  onNewDesign: (() -> Unit)?,
  onReconnect: (() -> Unit)?,
  onHelp: (() -> Unit)?,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var showShortcuts by remember { mutableStateOf(false) }
  if (showShortcuts) {
    EditorShortcutsDialog(onDismiss = { showShortcuts = false })
  }
  Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
    Row(
      Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 18.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Compose UI Builder", fontWeight = FontWeight.Bold)
      // Yields rather than pushes. The toolbar has gained a control in most of the last few
      // changes, and an unconstrained title crowds them out of the row on the narrowest window
      // that still calls itself a desktop.
      Text(
        "${state.document.title} · ${state.document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull.orEmpty()}",
        Modifier.padding(start = 14.dp).weight(1f, fill = false),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.weight(1f))
      collaborators.take(4).forEach { collaborator ->
        Surface(
          Modifier.padding(start = 4.dp).size(28.dp).clearAndSetSemantics {},
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
      if (onNewDesign != null) {
        EditorAction(label = "New design", shortcut = "", enabled = true, onClick = onNewDesign)
      }
      EditorAction(
        label = "Undo",
        shortcut = "Ctrl/⌘+Z",
        enabled = canUndo,
        onClick = { dispatch(UiBuilderEditorEvent.Undo) },
      )
      EditorAction(
        label = "Redo",
        shortcut = "Ctrl/⌘+Shift+Z",
        enabled = canRedo,
        onClick = { dispatch(UiBuilderEditorEvent.Redo) },
      )
      EditorAction(
        label = "Duplicate",
        shortcut = "Ctrl/⌘+D",
        enabled = canDuplicate,
        onClick = { dispatch(UiBuilderEditorEvent.DuplicateSelected) },
      )
      if (wrapCandidates.isNotEmpty()) {
        var wrapOpen by remember { mutableStateOf(false) }
        Box {
          EditorAction(
            label = "Wrap",
            shortcut = "",
            enabled = true,
            onClick = { wrapOpen = true },
          )
          DropdownMenu(expanded = wrapOpen, onDismissRequest = { wrapOpen = false }) {
            // Only what will work. The list is computed from both ends — the parent slot has to
            // accept the container and the container needs a slot that accepts every selected
            // node — so this menu is a promise rather than a guess.
            wrapCandidates.forEach { candidate ->
              DropdownMenuItem(
                text = { Text(candidate.displayName) },
                onClick = {
                  wrapOpen = false
                  dispatch(UiBuilderEditorEvent.WrapSelection(candidate.componentId))
                },
              )
            }
          }
        }
      }
      EditorAction(
        label = "Unwrap",
        shortcut = "",
        enabled = canUnwrap,
        onClick = { dispatch(UiBuilderEditorEvent.UnwrapSelection) },
      )
      EditorAction(
        label = "Copy",
        shortcut = "Ctrl/⌘+C",
        enabled = canCopy,
        onClick = { dispatch(UiBuilderEditorEvent.CopySelected) },
      )
      EditorAction(
        label = "Cut",
        shortcut = "Ctrl/⌘+X",
        enabled = canCut,
        onClick = { dispatch(UiBuilderEditorEvent.CutSelected) },
      )
      EditorAction(
        label = "Paste",
        shortcut = "Ctrl/⌘+V",
        enabled = canPaste,
        onClick = { dispatch(UiBuilderEditorEvent.Paste) },
      )
      EditorAction(
        label = "Delete",
        shortcut = "Delete/Backspace",
        enabled = canDelete,
        onClick = { dispatch(UiBuilderEditorEvent.DeleteSelected) },
      )
      // The one control that changes what the canvas is for, so it says which side it is on rather
      // than what it will do — a button reading "Preview" while you are previewing is a coin toss.
      EditorAction(
        label = if (state.previewMode) "Previewing · exit" else "Preview",
        shortcut = "Ctrl/\u2318+Enter",
        enabled = true,
        onClick = { dispatch(UiBuilderEditorEvent.TogglePreview) },
      )
      EditorAction(
        label = "Shortcuts",
        shortcut = "",
        enabled = true,
        onClick = { showShortcuts = true },
      )
      if (onReconnect != null) {
        EditorAction(label = "Reconnect", shortcut = "", enabled = true, onClick = onReconnect)
      }
      if (onHelp != null) {
        EditorAction(label = "Help", shortcut = "", enabled = true, onClick = onHelp)
      }
      Text(
        "Revision ${state.document.revision}  ·  ${state.document.nodes.size} nodes",
        Modifier.padding(start = 8.dp),
        style = MaterialTheme.typography.labelLarge,
      )
      Surface(
        Modifier.padding(start = 14.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xff214c37),
      ) {
        Text(
          sessionLabel,
          Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          color = Color(0xffa8f2c6),
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
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

@Composable
private fun EditorShortcutsDialog(onDismiss: () -> Unit) {
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
private enum class LayerSelectionGesture {
  Replace,
  Toggle,
  Range,
}

private fun editorShortcut(
  event: KeyEvent,
  enabled: Boolean,
  previewing: Boolean,
  dispatch: (UiBuilderEditorEvent) -> Unit,
): Boolean {
  if (!enabled || event.type != KeyEventType.KeyDown) return false
  val chord =
    EditorChord(
      key = event.key,
      command = event.isCtrlPressed || event.isMetaPressed,
      shift = event.isShiftPressed,
    )
  val match = editorShortcutFor(chord, previewing) ?: return false
  dispatch(match.event)
  return true
}

/**
 * The shortcut a chord resolves to, or null when none does.
 *
 * Pure, so the table's precedence and the preview suppression can be tested without synthesising a
 * key event — which on this target is more machinery than the rule being tested.
 *
 * While the canvas belongs to the screen, only the chord that hands it back is live. With the
 * selection overlay gone there is nothing on screen to show what a Delete or an arrow just did, so
 * those chords would edit invisibly and surprise later.
 */
internal fun editorShortcutFor(chord: EditorChord, previewing: Boolean = false): EditorShortcut? =
  EDITOR_SHORTCUTS.firstOrNull { it.matches(chord) }
    ?.takeIf { !previewing || it.event == UiBuilderEditorEvent.TogglePreview }

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
    // Enter rather than P. The builder ships in a browser, and Ctrl/\u2318+P is the print dialog:
    // a chord whose worst case is a print preview over the design is not a chord worth having,
    // and whether Compose consumes it before the browser sees it is not something to find out in
    // production. Ctrl/\u2318+Enter is unclaimed, and "run it" is already what it means everywhere
    // else.
    EditorShortcut(
      chord = "Ctrl/\u2318+Enter",
      description = "Hand the canvas to the screen, and back",
      event = UiBuilderEditorEvent.TogglePreview,
      keys = setOf(Key.Enter, Key.NumPadEnter),
      command = true,
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
    "Drag a layer row" to "Reorder within the slot",
    "Drag a catalog component" to "Insert it where it is dropped",
  )

@Composable
private fun EditorNavigator(
  state: UiBuilderEditorState,
  catalogSystemId: String,
  catalogItems: List<EditorCatalogItem>,
  treeRows: List<EditorTreeRow>,
  collaborators: List<UiBuilderCollaborator>,
  dropTargetLabel: String,
  onCatalogDrag: (String, Offset?) -> Unit,
  onCatalogDrop: (String, Offset) -> Unit,
  canAddCatalogComponent: (String) -> Boolean,
  onCatalogAdd: (String) -> Unit,
  moveTarget: (String, EditorMoveDirection) -> UiBuilderEditorEvent.MoveNode?,
  onEditorInteraction: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  modifier: Modifier = Modifier.width(300.dp).fillMaxHeight(),
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column {
      PanelHeading(
        "$catalogSystemId component catalog",
        "Drop target: $dropTargetLabel",
      )
      SearchField(
        state.catalogQuery,
        placeholder = "Search components",
        onFocusChanged = onTextInputFocusChanged,
      ) {
        dispatch(UiBuilderEditorEvent.SearchCatalog(it))
      }
      LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
        itemsIndexed(catalogItems, key = { _, item -> item.componentId }) { index, item ->
          if (index == 0 || catalogItems[index - 1].kind != item.kind) KindHeading(item.kind)
          CatalogRow(
            item = item,
            onDrag = { onCatalogDrag(item.componentId, it) },
            onDrop = { onCatalogDrop(item.componentId, it) },
            canAdd = canAddCatalogComponent(item.componentId),
            onAdd = { onCatalogAdd(item.componentId) },
          )
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline)
      val matches = treeRows.count(EditorTreeRow::matched)
      PanelHeading(
        "Layers",
        if (state.layerQuery.isBlank()) "Drag vertically to reorder"
        else "$matches of ${state.document.nodes.size} match",
      )
      SearchField(
        state.layerQuery,
        // Reusing the catalog's field meant reusing its placeholder, so an empty layers filter
        // invited you to search components. Two fields, two things to look for.
        placeholder = "Filter layers",
        onFocusChanged = onTextInputFocusChanged,
      ) {
        dispatch(UiBuilderEditorEvent.SearchLayers(it))
      }
      // The multi-node inspector is only as reachable as the selection is. Filtering to every text
      // on the screen and then taking all of them is what makes restyling a screen one edit.
      if (state.layerQuery.isNotBlank() && matches > 0) {
        TextButton(
          onClick = {
            onEditorInteraction()
            dispatch(UiBuilderEditorEvent.SelectAllMatches)
          },
          modifier = Modifier.padding(horizontal = 8.dp),
        ) {
          Text("Select all $matches")
        }
      }
      LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(treeRows, key = { _, row -> row.nodeId }) { _, row ->
          LayerRow(
            row = row,
            // Every selected node is highlighted, not just the anchor — a selection you cannot see
            // is one you cannot trust before pressing Delete.
            selected = row.nodeId in state.selection,
            collaborators = collaborators.filter { row.nodeId in it.selectedNodeIds },
            onSelect = { gesture ->
              onEditorInteraction()
              dispatch(
                when (gesture) {
                  LayerSelectionGesture.Replace -> UiBuilderEditorEvent.SelectNode(row.nodeId)
                  LayerSelectionGesture.Toggle -> UiBuilderEditorEvent.ToggleNode(row.nodeId)
                  LayerSelectionGesture.Range -> UiBuilderEditorEvent.ExtendSelectionTo(row.nodeId)
                }
              )
            },
            onMove = { direction ->
              onEditorInteraction()
              moveTarget(row.nodeId, direction)?.let(dispatch)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun PinnedDesignCanvas(
  document: UiBuilderDocument,
  selectedNodeId: String?,
  onNodeSelected: (String) -> Unit,
  onCanvasMetrics: (Int, Int, Float) -> Unit,
  onCanvasBounds: (Rect) -> Unit,
  dropHovered: Boolean,
  showSelectionOverlay: Boolean,
  collaborators: List<UiBuilderCollaborator>,
  onInspectionSnapshot: ((UiBuilderInspectionSnapshot) -> Unit)?,
  onInspectionInvalidated: ((UiBuilderInspectionCollector) -> Unit)?,
  contentAlignment: Alignment = Alignment.TopStart,
  modifier: Modifier = Modifier,
) {
  val sourceWidth =
    document.environment["widthDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1280f
  val sourceHeight =
    document.environment["heightDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 800f
  val density = LocalDensity.current
  var inspection by
    remember(document.id, document.revision) { mutableStateOf<UiBuilderInspectionSnapshot?>(null) }
  BoxWithConstraints(modifier.clipToBounds(), contentAlignment = contentAlignment) {
    val scale = minOf(maxWidth.value / sourceWidth, maxHeight.value / sourceHeight).coerceAtMost(1f)
    Surface(
      Modifier.align(contentAlignment)
        .wrapContentSize(Alignment.TopStart, unbounded = true)
        .requiredSize(sourceWidth.dp, sourceHeight.dp)
        .onSizeChanged { size ->
          val measuredWidth = with(density) { size.width.toDp().value.roundToInt() }
          val measuredHeight = with(density) { size.height.toDp().value.roundToInt() }
          onCanvasMetrics(measuredWidth, measuredHeight, scale)
        }
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
          transformOrigin = TransformOrigin(0f, 0f)
          compositingStrategy = CompositingStrategy.Offscreen
        }
        .onGloballyPositioned { onCanvasBounds(it.boundsInRoot()) }
        .then(
          if (dropHovered) Modifier.border(4.dp, MaterialTheme.colorScheme.primary) else Modifier
        ),
      shape = RoundedCornerShape(0.dp),
      shadowElevation = 0.dp,
    ) {
      Box(Modifier.fillMaxSize()) {
        UiBuilderSurface(
          document = document,
          editorOverlay = showSelectionOverlay,
          selectedNodeId = selectedNodeId,
          onNodeSelected = onNodeSelected,
          onInspectionSnapshot = { snapshot ->
            inspection = snapshot
            onInspectionSnapshot?.invoke(snapshot)
          },
          onInspectionInvalidated = onInspectionInvalidated,
        )
        RemotePresenceOverlay(collaborators, inspection)
      }
    }
  }
}

@Composable
private fun RemotePresenceOverlay(
  collaborators: List<UiBuilderCollaborator>,
  inspection: UiBuilderInspectionSnapshot?,
) {
  if (collaborators.isEmpty()) return
  val boundsByNode = inspection?.nodes?.associate { it.nodeId to it.bounds }.orEmpty()
  Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
    collaborators.forEach { collaborator ->
      val color = collaborator.colorArgbHex.toPresenceColor()
      collaborator.selectedNodeIds.forEach { nodeId ->
        val bounds = boundsByNode[nodeId] ?: return@forEach
        drawRect(
          color = color,
          topLeft = Offset(bounds.x, bounds.y),
          size = androidx.compose.ui.geometry.Size(bounds.width, bounds.height),
          style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
        )
      }
    }
  }
}

@Composable
private fun PanelHeading(title: String, supporting: String) {
  Row(
    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      Text(
        supporting,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SearchField(
  value: String,
  placeholder: String,
  onFocusChanged: (Boolean) -> Unit,
  onValueChange: (String) -> Unit,
) {
  Surface(
    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp, vertical = 5.dp),
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Row(
      Modifier.padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(18.dp))
      Box(Modifier.weight(1f)) {
        if (value.isEmpty()) {
          Text(
            placeholder,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        BasicTextField(
          value = value,
          onValueChange = onValueChange,
          modifier =
            Modifier.fillMaxWidth()
              .onFocusChanged { onFocusChanged(it.isFocused) }
              .semantics { contentDescription = "Component catalog search" },
          textStyle =
            MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
          singleLine = true,
        )
      }
    }
  }
}

@Composable
private fun KindHeading(kind: EditorComponentKind) {
  Text(
    kind.label.uppercase(),
    Modifier.fillMaxWidth()
      .background(Color(0xff202126))
      .padding(horizontal = 14.dp, vertical = 5.dp),
    color = MaterialTheme.colorScheme.primary,
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
  )
}

@Composable
private fun CatalogRow(
  item: EditorCatalogItem,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
  canAdd: Boolean,
  onAdd: () -> Unit,
) {
  var dragDistance by remember { mutableFloatStateOf(0f) }
  var dragOrigin by remember { mutableStateOf(Offset.Zero) }
  var lastPosition by remember { mutableStateOf(Offset.Zero) }
  Row(
    Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Filled.DragIndicator,
      contentDescription = "Drag ${item.displayName}",
      modifier =
        Modifier.size(18.dp)
          .onGloballyPositioned { dragOrigin = it.boundsInRoot().topLeft }
          .pointerInput(item.componentId) {
            detectDragGestures(
              onDragStart = {
                dragDistance = 0f
                lastPosition = dragOrigin + it
                onDrag(lastPosition)
              },
              onDragEnd = {
                if (dragDistance > 8f) onDrop(lastPosition) else onDrag(null)
                dragDistance = 0f
              },
              onDragCancel = {
                dragDistance = 0f
                onDrag(null)
              },
              onDrag = { change, amount ->
                change.consume()
                dragDistance += amount.getDistance()
                lastPosition = dragOrigin + change.position
                onDrag(lastPosition)
              },
            )
          },
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.padding(start = 8.dp).weight(1f)) {
      Text(item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
      Text(
        item.componentId,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
      )
    }
    TextButton(
      onClick = onAdd,
      enabled = canAdd,
    ) {
      Text(
        "Add",
        Modifier.semantics { contentDescription = "Add ${item.displayName}" },
      )
    }
  }
}

@Composable
private fun LayerRow(
  row: EditorTreeRow,
  selected: Boolean,
  collaborators: List<UiBuilderCollaborator>,
  onSelect: (LayerSelectionGesture) -> Unit,
  onMove: (EditorMoveDirection) -> Unit,
) {
  var verticalDrag by remember { mutableFloatStateOf(0f) }
  val background = if (selected) Color(0xff30385a) else Color.Transparent
  Row(
    Modifier.fillMaxWidth()
      .height(34.dp)
      .background(background)
      .padding(start = (8 + row.depth * 12).dp, end = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Filled.DragIndicator,
      contentDescription = "Reorder ${row.nodeId}",
      modifier =
        Modifier.size(16.dp).pointerInput(row.nodeId) {
          detectDragGestures(
            onDragStart = { verticalDrag = 0f },
            onDragEnd = {
              when {
                verticalDrag > 12f -> onMove(EditorMoveDirection.After)
                verticalDrag < -12f -> onMove(EditorMoveDirection.Before)
              }
              verticalDrag = 0f
            },
            onDragCancel = { verticalDrag = 0f },
            onDrag = { change, amount ->
              change.consume()
              verticalDrag += amount.y
            },
          )
        },
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
      Modifier.fillMaxHeight()
        .weight(1f)
        // Not `clickable`: it cannot see which modifier keys are down, and ctrl/⌘-click and
        // shift-click are how a selection is built up in every tool people arrive from.
        //
        // On the release, not the press. Every attempt to scroll this list on a touch screen
        // begins with a press, and selecting there meant scrolling the layers panel changed the
        // selection. `waitForUpOrCancellation` returns null once an ancestor claims the gesture,
        // which is the cancellation `clickable` gave for free and this had to get back.
        .pointerInput(row.nodeId) {
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val modifiers = currentEvent.keyboardModifiers
            if (waitForUpOrCancellation() == null) return@awaitEachGesture
            onSelect(
              when {
                modifiers.isShiftPressed -> LayerSelectionGesture.Range
                modifiers.isCtrlPressed || modifiers.isMetaPressed -> LayerSelectionGesture.Toggle
                else -> LayerSelectionGesture.Replace
              }
            )
          }
        }
        // Dropping `clickable` also dropped the activation action, the focusability and the key
        // handling it supplied, so a screen reader could find a layer and not select it, and the
        // keyboard could neither reach one nor activate it. The pointer path keeps the modifier
        // keys; these restore the rest. `focusable` and a semantics action are not enough on their
        // own: they expose focus and an accessibility action, and leave Enter and Space inert.
        .onKeyEvent { event ->
          if (
            event.type == KeyEventType.KeyUp &&
              (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar)
          ) {
            onSelect(
              when {
                event.isShiftPressed -> LayerSelectionGesture.Range
                event.isCtrlPressed || event.isMetaPressed -> LayerSelectionGesture.Toggle
                else -> LayerSelectionGesture.Replace
              }
            )
            true
          } else false
        }
        .focusable()
        .semantics {
          contentDescription = "Select ${row.nodeId}"
          this.selected = selected
          onClick(label = "Select") {
            onSelect(LayerSelectionGesture.Replace)
            true
          }
        },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        row.label,
        Modifier.padding(start = 5.dp).weight(1f),
        // A row kept only to carry a matching descendant is context, and reads as context. Without
        // this a filter looks like it matched the ancestors too.
        color =
          if (row.matched) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      collaborators.take(3).forEach { collaborator ->
        Box(
          Modifier.padding(end = 3.dp)
            .size(8.dp)
            .background(collaborator.colorArgbHex.toPresenceColor(), RoundedCornerShape(4.dp))
            .clearAndSetSemantics {}
        )
      }
      // The type when the row is named after its content, the id otherwise. A content-named row
      // would otherwise stop saying what it is, and an unnamed one already says that in `label`.
      Text(
        if (row.named) row.componentLabel else row.nodeId,
        Modifier.width(92.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private fun String.toPresenceColor(): Color {
  val hex = removePrefix("#")
  val argb = hex.takeIf { it.length == 8 }?.toULongOrNull(16) ?: return Color(0xff7788aa)
  return Color(argb.toInt())
}

@Composable
private fun PropertyInspector(
  state: UiBuilderEditorState,
  fields: List<EditorPropertyField>,
  problems: List<EditorProblem>,
  themeSettings: EditorThemeSettings,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  modifier: Modifier = Modifier.width(INSPECTOR_WIDTH).fillMaxHeight(),
) {
  val node = state.selectedNodeId?.let(state.document.nodes::get)
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.padding(16.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // "Layer", not "Properties". It is the odd label out beside Theme, Screen and Issues, it
        // is the one a fourth tab leaves no room for, and it is the word the panel beside it
        // already uses for the same thing.
        InspectorModeButton(
          "Layer",
          EditorInspectorMode.Properties,
          state,
          dispatch,
          Modifier.weight(1f),
        )
        InspectorModeButton(
          "Theme",
          EditorInspectorMode.Theme,
          state,
          dispatch,
          Modifier.weight(1f),
        )
        InspectorModeButton(
          "Screen",
          EditorInspectorMode.Screen,
          state,
          dispatch,
          Modifier.weight(1f),
        )
        InspectorModeButton(
          if (problems.isEmpty()) "Issues" else "Issues ${problems.size}",
          EditorInspectorMode.Issues,
          state,
          dispatch,
          Modifier.weight(1f),
        )
      }
      HorizontalDivider(Modifier.padding(vertical = 6.dp))
      if (state.inspectorMode == EditorInspectorMode.Issues) {
        ProblemsInspector(problems, dispatch)
        return@Column
      }
      if (state.inspectorMode == EditorInspectorMode.Theme) {
        ThemeBuilder(themeSettings, onTextInputFocusChanged, dispatch)
        return@Column
      }
      if (state.inspectorMode == EditorInspectorMode.Screen) {
        ScreenEnvironmentInspector(
          document = state.document,
          onTextInputFocusChanged = onTextInputFocusChanged,
          dispatch = dispatch,
        )
        return@Column
      }
      if (node == null) {
        Text(
          "Select a layer on the canvas or in the tree.",
          Modifier.padding(top = 16.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return@Column
      }
      Text(
        node.componentId,
        Modifier.padding(top = 8.dp),
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        node.id,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
      HorizontalDivider(
        Modifier.padding(vertical = 14.dp),
        color = MaterialTheme.colorScheme.outline,
      )
      LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        itemsIndexed(fields, key = { _, field -> field.name }) { _, field ->
          PropertyControl(
            field = field,
            onTextInputFocusChanged = onTextInputFocusChanged,
            commit = { value ->
              dispatch(UiBuilderEditorEvent.CommitProperty(node.id, field.name, value))
            },
          )
        }
        if (fields.isEmpty()) {
          item {
            Text(
              "This component has no catalog properties.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        if (node.modifiers.isNotEmpty()) {
          item {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Modifiers", style = MaterialTheme.typography.labelLarge)
            Text(
              "Shown from the document. Modifier parameter editing waits for an authoritative modifier operation.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.labelSmall,
            )
          }
          itemsIndexed(node.modifiers) { _, modifier ->
            Text(
              modifier.toString(),
              Modifier.padding(top = 6.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
      val outcome = state.lastOutcome
      if (outcome != null) {
        Text(
          when (outcome) {
            is CommandOutcome.Accepted ->
              "Operation accepted at revision ${outcome.committedRevision}"
            is CommandOutcome.Rejected -> "${outcome.code}: ${outcome.message}"
          },
          color =
            if (outcome is CommandOutcome.Accepted) Color(0xffa8f2c6)
            else MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
  }
}

@Composable
private fun PropertyControl(
  field: EditorPropertyField,
  onTextInputFocusChanged: (Boolean) -> Unit,
  commit: (String) -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
    Text(
      field.label +
        (if (field.required) " *" else "") +
        // Say what an edit will hit. A control that silently spans six nodes is one people stop
        // trusting the first time it changes something they were not looking at.
        (if (field.nodeCount > 1) " · ${field.nodeCount} selected" else "") +
        (if (field.mixed) " · mixed" else ""),
      style = MaterialTheme.typography.labelLarge,
    )
    when (field.control) {
      EditorPropertyControl.Boolean -> {
        val checked = field.value.toBooleanStrictOrNull() ?: false
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(if (checked) "On" else "Off", style = MaterialTheme.typography.bodySmall)
          Switch(
            checked = checked,
            onCheckedChange = { commit(it.toString()) },
            modifier = Modifier.semantics { contentDescription = "${field.label} property" },
          )
        }
      }
      EditorPropertyControl.Enum ->
        if (field.name == "iconKey")
          GoogleIconPropertyControl(field, onTextInputFocusChanged, commit)
        else EnumPropertyControl(field, commit)
      EditorPropertyControl.Number ->
        DraftPropertyControl(field, onTextInputFocusChanged, commit, showSteppers = true)
      EditorPropertyControl.Text,
      EditorPropertyControl.Color ->
        DraftPropertyControl(field, onTextInputFocusChanged, commit, showSteppers = false)
      EditorPropertyControl.Unsupported ->
        Text(
          field.value.ifEmpty { "Not set" },
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
    }
    field.notes?.let {
      Text(
        it,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    field.error?.let {
      Text(
        it,
        Modifier.semantics { contentDescription = "${field.label} validation error" },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

@Composable
private fun DraftPropertyControl(
  field: EditorPropertyField,
  onTextInputFocusChanged: (Boolean) -> Unit,
  commit: (String) -> Unit,
  showSteppers: Boolean,
) {
  var draft by remember(field.nodeId, field.name, field.value) { mutableStateOf(field.value) }
  BasicTextField(
    value = draft,
    onValueChange = { draft = it },
    modifier =
      Modifier.fillMaxWidth()
        .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
        .semantics { contentDescription = "${field.label} property" }
        .padding(top = 7.dp)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
        .padding(10.dp),
    textStyle =
      MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    singleLine = field.name != "text",
  )
  if (showSteppers) {
    val bounds = field.numberBounds
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(
        onClick = {
          val current = draft.toDoubleOrNull() ?: bounds?.minimum ?: 0.0
          draft =
            (current - (bounds?.step ?: 1.0))
              .coerceIn(bounds!!.minimum, bounds.maximum)
              .editorNumber(bounds.integer)
          commit(draft)
        }
      ) {
        Text("−")
      }
      Text(
        bounds
          ?.let { "${it.minimum.editorNumber(it.integer)}…${it.maximum.editorNumber(it.integer)}" }
          .orEmpty(),
        Modifier.padding(top = 14.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
      TextButton(
        onClick = {
          val current = draft.toDoubleOrNull() ?: bounds?.minimum ?: 0.0
          draft =
            (current + (bounds?.step ?: 1.0))
              .coerceIn(bounds!!.minimum, bounds.maximum)
              .editorNumber(bounds.integer)
          commit(draft)
        }
      ) {
        Text("+")
      }
    }
  }
  Button(
    onClick = { commit(draft) },
    modifier = Modifier.padding(top = 7.dp).fillMaxWidth(),
  ) {
    Text("Apply ${field.label.lowercase()}")
  }
  if (field.name == "text") {
    TextButton(onClick = { commit("Edited in Compose") }, modifier = Modifier.fillMaxWidth()) {
      Text("Use sample text")
    }
  }
}

/**
 * What the export gate would refuse, listed where a person is already looking.
 *
 * Each row selects its node, because a message naming an id nobody can find is only half an answer.
 * Rows without a node — a catalog pin mismatch, an environment field — are not selectable and say
 * so by not reacting.
 */
@Composable
private fun ProblemsInspector(
  problems: List<EditorProblem>,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  if (problems.isEmpty()) {
    Text(
      "Nothing is blocking a Compose export of this design.",
      Modifier.padding(top = 16.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }
  Text(
    "These are what the Compose export gate refuses, checked against the whole document rather " +
      "than the last edit.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
  LazyColumn(Modifier.fillMaxWidth().padding(top = 10.dp)) {
    itemsIndexed(problems) { _, problem ->
      Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).let { base ->
          problem.nodeId?.let { id ->
            base.clickable { dispatch(UiBuilderEditorEvent.SelectNode(id)) }
          } ?: base
        }
      ) {
        Text(
          problem.code,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.labelMedium,
        )
        Text(problem.message, style = MaterialTheme.typography.bodySmall)
        val where =
          listOfNotNull(problem.nodeId, problem.componentId).joinToString(" · ").ifEmpty { null }
        if (where != null) {
          Text(
            where,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }
  }
}

@Composable
private fun InspectorModeButton(
  label: String,
  mode: EditorInspectorMode,
  state: UiBuilderEditorState,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val selected = state.inspectorMode == mode
  Surface(
    modifier
      .height(28.dp)
      .semantics {
        contentDescription = if (mode == EditorInspectorMode.Theme) "$label inspector" else label
      }
      .clickable { dispatch(UiBuilderEditorEvent.ShowInspector(mode)) },
    shape = RoundedCornerShape(9.dp),
    color =
      if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Text(
      label,
      Modifier.padding(vertical = 6.dp),
      color =
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
      // A size down from labelLarge: four tabs share the width three used to, and a clipped tab
      // label is worse than a smaller one.
      style = MaterialTheme.typography.labelMedium,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      maxLines = 1,
    )
  }
}

@Composable
private fun ScreenEnvironmentInspector(
  document: UiBuilderDocument,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val current = document.screenEnvironmentSettings()
  var width by remember(document.id, current) { mutableStateOf(current.widthDp.toString()) }
  var height by remember(document.id, current) { mutableStateOf(current.heightDp.toString()) }
  var density by remember(document.id, current) { mutableStateOf(current.density.toString()) }
  var fontScale by remember(document.id, current) { mutableStateOf(current.fontScale.toString()) }
  var locale by remember(document.id, current) { mutableStateOf(current.locale) }
  var theme by remember(document.id, current) { mutableStateOf(current.theme) }
  var layoutDirection by remember(document.id, current) { mutableStateOf(current.layoutDirection) }
  var validationError by remember(document.id, current) { mutableStateOf<String?>(null) }

  Text(
    "Screen environment",
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.Bold,
  )
  Text(
    "Applies to the complete render, never an individual component.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
  HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    EnvironmentTextField(
      label = "Width (dp)",
      value = width,
      modifier = Modifier.weight(1f),
      onFocusChanged = onTextInputFocusChanged,
      onValueChange = { width = it },
    )
    EnvironmentTextField(
      label = "Height (dp)",
      value = height,
      modifier = Modifier.weight(1f),
      onFocusChanged = onTextInputFocusChanged,
      onValueChange = { height = it },
    )
  }
  Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    EnvironmentTextField(
      label = "Density",
      value = density,
      modifier = Modifier.weight(1f),
      onFocusChanged = onTextInputFocusChanged,
      onValueChange = { density = it },
    )
    EnvironmentTextField(
      label = "Font scale",
      value = fontScale,
      modifier = Modifier.weight(1f),
      onFocusChanged = onTextInputFocusChanged,
      onValueChange = { fontScale = it },
    )
  }
  EnvironmentTextField(
    label = "Locale",
    value = locale,
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    onFocusChanged = onTextInputFocusChanged,
    onValueChange = { locale = it },
  )
  EnvironmentChoiceHeading("Theme")
  Row(Modifier.fillMaxWidth()) {
    EditorScreenTheme.entries.forEach { option ->
      TextButton(
        onClick = { theme = option },
        modifier = Modifier.weight(1f).semantics { contentDescription = "${option.label} theme" },
      ) {
        Text(
          option.label,
          fontWeight = if (theme == option) FontWeight.Bold else FontWeight.Normal,
          color =
            if (theme == option) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
  EnvironmentChoiceHeading("Layout direction")
  Row(Modifier.fillMaxWidth()) {
    EditorLayoutDirection.entries.forEach { option ->
      TextButton(
        onClick = { layoutDirection = option },
        modifier =
          Modifier.weight(1f).semantics { contentDescription = "${option.label} layout direction" },
      ) {
        Text(
          option.label,
          fontWeight = if (layoutDirection == option) FontWeight.Bold else FontWeight.Normal,
          color =
            if (layoutDirection == option) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
  validationError?.let {
    Text(
      it,
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
    )
  }
  Button(
    onClick = {
      val parsed =
        ScreenEnvironmentSettings(
          widthDp = width.toIntOrNull() ?: Int.MIN_VALUE,
          heightDp = height.toIntOrNull() ?: Int.MIN_VALUE,
          density = density.toDoubleOrNull() ?: Double.NaN,
          fontScale = fontScale.toDoubleOrNull() ?: Double.NaN,
          locale = locale.trim(),
          theme = theme,
          layoutDirection = layoutDirection,
        )
      validationError = parsed.validationError()
      if (validationError == null) dispatch(UiBuilderEditorEvent.UpdateEnvironment(parsed))
    },
    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
  ) {
    Text("Apply screen settings")
  }
}

@Composable
private fun EnumPropertyControl(field: EditorPropertyField, commit: (String) -> Unit) {
  var expanded by remember(field.nodeId, field.name) { mutableStateOf(false) }
  Box(Modifier.fillMaxWidth()) {
    Button(
      onClick = { expanded = true },
      modifier =
        Modifier.fillMaxWidth().semantics { contentDescription = "${field.label} property" },
    ) {
      Text(field.value.ifEmpty { "Choose…" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      field.choices.forEach { choice ->
        DropdownMenuItem(
          text = { Text(choice) },
          onClick = {
            expanded = false
            commit(choice)
          },
        )
      }
    }
  }
}

@Composable
private fun GoogleIconPropertyControl(
  field: EditorPropertyField,
  onTextInputFocusChanged: (Boolean) -> Unit,
  commit: (String) -> Unit,
) {
  var expanded by remember(field.nodeId, field.name) { mutableStateOf(false) }
  var query by remember(field.nodeId, field.name) { mutableStateOf("") }
  val current = googleMaterialIcon(field.value)
  Text(
    "Google Material Icons catalog",
    Modifier.padding(top = 7.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
  Button(
    onClick = { expanded = true },
    modifier =
      Modifier.padding(top = 7.dp).fillMaxWidth().semantics {
        contentDescription = "Choose Google icon"
      },
  ) {
    current?.let { Icon(it.imageVector, null, Modifier.size(20.dp)) }
    Text(current?.label ?: "Choose Google icon", Modifier.padding(start = 8.dp))
  }
  DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
    Text(
      "Google Material Icons",
      Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Bold,
    )
    BasicTextField(
      value = query,
      onValueChange = { query = it },
      modifier =
        Modifier.width(280.dp)
          .padding(10.dp)
          .semantics { contentDescription = "Google icon search" }
          // The one text field in the editor that never reported focus. Every editor chord is
          // gated on `textInputFocused`, so while someone typed an icon name here Backspace still
          // meant delete-the-selection and Ctrl/⌘+V still meant paste-a-subtree.
          .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
          .padding(10.dp),
      singleLine = true,
      textStyle =
        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    )
    GoogleMaterialIcons.filter {
        query.isBlank() ||
          it.label.contains(query, ignoreCase = true) ||
          it.key.contains(query, ignoreCase = true)
      }
      .forEach { icon ->
        DropdownMenuItem(
          text = { Text(icon.label) },
          leadingIcon = { Icon(icon.imageVector, null, Modifier.size(20.dp)) },
          onClick = {
            expanded = false
            commit(icon.key)
          },
        )
      }
  }
}

private fun Double.editorNumber(integer: Boolean): String =
  if (integer || this % 1.0 == 0.0) toLong().toString() else toString()

@Composable
private fun EnvironmentChoiceHeading(label: String) {
  Text(
    label,
    Modifier.padding(top = 8.dp),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun EnvironmentTextField(
  label: String,
  value: String,
  modifier: Modifier,
  onFocusChanged: (Boolean) -> Unit,
  onValueChange: (String) -> Unit,
) {
  Column(modifier) {
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier =
        Modifier.fillMaxWidth()
          .onFocusChanged { onFocusChanged(it.isFocused) }
          .semantics { contentDescription = label }
          .padding(top = 3.dp)
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
          .padding(horizontal = 8.dp, vertical = 7.dp),
      textStyle =
        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
      singleLine = true,
    )
  }
}

@Composable
private fun ThemeBuilder(
  settings: EditorThemeSettings,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var primary by remember(settings) { mutableStateOf(settings.primaryColor) }
  var background by remember(settings) { mutableStateOf(settings.backgroundColor) }
  var surface by remember(settings) { mutableStateOf(settings.surfaceColor) }
  var content by remember(settings) { mutableStateOf(settings.contentColor) }
  var typeScale by remember(settings) { mutableStateOf(settings.typeScale.toString()) }
  var cornerRadius by remember(settings) { mutableStateOf(settings.cornerRadiusDp.toString()) }

  Text("Theme builder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
  Text(
    "Design-wide colours, typography and shapes",
    Modifier.padding(top = 3.dp, bottom = 14.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
  ThemeField("Primary colour", primary, onTextInputFocusChanged) { primary = it }
  ThemeField("Background colour", background, onTextInputFocusChanged) { background = it }
  ThemeField("Surface colour", surface, onTextInputFocusChanged) { surface = it }
  ThemeField("Content colour", content, onTextInputFocusChanged) { content = it }
  ThemeField("Type scale (0.75–1.5)", typeScale, onTextInputFocusChanged) { typeScale = it }
  ThemeField("Corner radius (0–48dp)", cornerRadius, onTextInputFocusChanged) { cornerRadius = it }
  Button(
    onClick = {
      dispatch(
        UiBuilderEditorEvent.ApplyTheme(
          EditorThemeSettings(
            primaryColor = primary,
            backgroundColor = background,
            surfaceColor = surface,
            contentColor = content,
            typeScale = typeScale.toFloatOrNull() ?: Float.NaN,
            cornerRadiusDp = cornerRadius.toFloatOrNull() ?: Float.NaN,
          )
        )
      )
    },
    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
  ) {
    Text("Apply theme")
  }
}

@Composable
private fun ThemeField(
  label: String,
  value: String,
  onFocusChanged: (Boolean) -> Unit,
  onValueChange: (String) -> Unit,
) {
  Text(label, style = MaterialTheme.typography.labelMedium)
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    modifier =
      Modifier.fillMaxWidth()
        .padding(top = 4.dp, bottom = 10.dp)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
        .onFocusChanged { onFocusChanged(it.isFocused) }
        .semantics { contentDescription = label }
        .padding(horizontal = 10.dp, vertical = 8.dp),
    singleLine = true,
    textStyle =
      MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
  )
}
