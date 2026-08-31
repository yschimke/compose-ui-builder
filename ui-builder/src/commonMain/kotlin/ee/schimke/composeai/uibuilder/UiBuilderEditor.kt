@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import kotlin.math.roundToInt
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val EditorColors =
  darkColorScheme(
    background = Color(0xff121316),
    surface = Color(0xff1b1c20),
    surfaceVariant = Color(0xff282a30),
    primary = Color(0xffb9c3ff),
    onPrimary = Color(0xff17215b),
    outline = Color(0xff454750),
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
  showSelectionOverlay: Boolean = true,
) {
  val reducer = remember(catalog) { UiBuilderEditorReducer(catalog) }
  var state by
    remember(document.id) {
      mutableStateOf(reducer.initial(document, selectedNodeId = document.roots.firstOrNull()))
    }
  var catalogDragPosition by remember { mutableStateOf<Offset?>(null) }
  var draggedComponentId by remember { mutableStateOf<String?>(null) }
  var canvasBounds by remember { mutableStateOf(Rect.Zero) }
  var textInputFocused by remember { mutableStateOf(false) }
  val editorFocusRequester = remember { FocusRequester() }
  val draggedTarget = draggedComponentId?.let { reducer.dropTarget(state, it) }
  val canvasDropHovered =
    catalogDragPosition?.let(canvasBounds::contains) == true && draggedTarget != null
  fun dispatch(event: UiBuilderEditorEvent) {
    state = reducer.reduce(state, event)
  }
  LaunchedEffect(state) { onStateChanged(state) }
  LaunchedEffect(Unit) { editorFocusRequester.requestFocus() }
  LaunchedEffect(canvasDropHovered, draggedTarget) {
    onDropTargetChanged(
      canvasDropHovered,
      draggedTarget?.let { "${it.nodeId}.${it.slot}" } ?: "No compatible slot",
    )
  }

  MaterialTheme(colorScheme = EditorColors) {
    Column(
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .focusRequester(editorFocusRequester)
        .focusable()
        .onPreviewKeyEvent { event ->
          editorShortcut(event, enabled = !textInputFocused, dispatch = ::dispatch)
        }
    ) {
      EditorToolbar(
        state = state,
        canDelete = reducer.canDeleteSelected(state),
        canDuplicate = reducer.canDuplicateSelected(state),
        dispatch = ::dispatch,
      )
      Row(Modifier.fillMaxSize()) {
        EditorNavigator(
          state = state,
          catalogItems = reducer.catalogItems(state.catalogQuery),
          treeRows = reducer.treeRows(state.document),
          dropTargetLabel = reducer.dropTargetLabel(state, draggedComponentId ?: "m3/text"),
          onCatalogDrag = { componentId, position ->
            draggedComponentId = componentId
            catalogDragPosition = position
          },
          onCatalogDrop = { componentId, position ->
            val target = reducer.dropTarget(state, componentId)
            if (canvasBounds.contains(position) && target != null) {
              dispatch(UiBuilderEditorEvent.InsertComponent(componentId, target))
            }
            draggedComponentId = null
            catalogDragPosition = null
          },
          moveTarget = { nodeId, direction -> reducer.moveTarget(state, nodeId, direction) },
          onTextInputFocusChanged = { textInputFocused = it },
          dispatch = ::dispatch,
        )
        PinnedDesignCanvas(
          document = state.document,
          selectedNodeId = state.selectedNodeId,
          onNodeSelected = { dispatch(UiBuilderEditorEvent.SelectNode(it)) },
          onCanvasMetrics = onCanvasMetrics,
          onCanvasBounds = {
            canvasBounds = it
            onCanvasBoundsChanged(it)
          },
          dropHovered = canvasDropHovered,
          showSelectionOverlay = showSelectionOverlay,
          onInspectionSnapshot = onInspectionSnapshot,
          modifier =
            Modifier.weight(1f).fillMaxHeight().background(Color(0xff0d0e11)).padding(20.dp),
        )
        PropertyInspector(
          state,
          onTextInputFocusChanged = { textInputFocused = it },
          dispatch = ::dispatch,
        )
      }
    }
  }
}

@Composable
private fun EditorToolbar(
  state: UiBuilderEditorState,
  canDelete: Boolean,
  canDuplicate: Boolean,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
    Row(
      Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 18.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Compose UI Builder", fontWeight = FontWeight.Bold)
      Text(
        "${state.document.title} · ${state.document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull.orEmpty()}",
        Modifier.padding(start = 14.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
      )
      Spacer(Modifier.weight(1f))
      EditorAction(
        label = "Undo",
        shortcut = "Ctrl/⌘+Z",
        enabled = state.canUndo,
        onClick = { dispatch(UiBuilderEditorEvent.Undo) },
      )
      EditorAction(
        label = "Redo",
        shortcut = "Ctrl/⌘+Shift+Z",
        enabled = state.canRedo,
        onClick = { dispatch(UiBuilderEditorEvent.Redo) },
      )
      EditorAction(
        label = "Duplicate",
        shortcut = "Ctrl/⌘+D",
        enabled = canDuplicate,
        onClick = { dispatch(UiBuilderEditorEvent.DuplicateSelected) },
      )
      EditorAction(
        label = "Delete",
        shortcut = "Delete/Backspace",
        enabled = canDelete,
        onClick = { dispatch(UiBuilderEditorEvent.DeleteSelected) },
      )
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
          "Local session",
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

private fun editorShortcut(
  event: KeyEvent,
  enabled: Boolean,
  dispatch: (UiBuilderEditorEvent) -> Unit,
): Boolean {
  if (!enabled || event.type != KeyEventType.KeyDown) return false
  val command = event.isCtrlPressed || event.isMetaPressed
  val editorEvent =
    when {
      command && event.key == Key.Z && event.isShiftPressed -> UiBuilderEditorEvent.Redo
      command && event.key == Key.Y -> UiBuilderEditorEvent.Redo
      command && event.key == Key.Z -> UiBuilderEditorEvent.Undo
      command && event.key == Key.D -> UiBuilderEditorEvent.DuplicateSelected
      !command && event.key in setOf(Key.Delete, Key.Backspace) ->
        UiBuilderEditorEvent.DeleteSelected
      else -> null
    } ?: return false
  dispatch(editorEvent)
  return true
}

@Composable
private fun EditorNavigator(
  state: UiBuilderEditorState,
  catalogItems: List<EditorCatalogItem>,
  treeRows: List<EditorTreeRow>,
  dropTargetLabel: String,
  onCatalogDrag: (String, Offset?) -> Unit,
  onCatalogDrop: (String, Offset) -> Unit,
  moveTarget: (String, EditorMoveDirection) -> UiBuilderEditorEvent.MoveNode?,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  Surface(Modifier.width(300.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
    Column {
      PanelHeading("M3 component catalog", "Drop target: $dropTargetLabel")
      SearchField(
        state.catalogQuery,
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
          )
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline)
      PanelHeading("Layers", "Drag vertically to reorder")
      LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(treeRows, key = { _, row -> row.nodeId }) { _, row ->
          LayerRow(
            row = row,
            selected = row.nodeId == state.selectedNodeId,
            onSelect = { dispatch(UiBuilderEditorEvent.SelectNode(row.nodeId)) },
            onMove = { direction -> moveTarget(row.nodeId, direction)?.let(dispatch) },
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
  onInspectionSnapshot: ((UiBuilderInspectionSnapshot) -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val sourceWidth =
    document.environment["widthDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1280f
  val sourceHeight =
    document.environment["heightDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 800f
  val density = LocalDensity.current
  BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.TopStart) {
    val scale = minOf(maxWidth.value / sourceWidth, maxHeight.value / sourceHeight).coerceAtMost(1f)
    Surface(
      Modifier.align(Alignment.TopStart)
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
      UiBuilderSurface(
        document = document,
        editorOverlay = showSelectionOverlay,
        selectedNodeId = selectedNodeId,
        onNodeSelected = onNodeSelected,
        onInspectionSnapshot = onInspectionSnapshot,
      )
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
            "Search components",
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
) {
  var dragDistance by remember { mutableFloatStateOf(0f) }
  var rowOrigin by remember { mutableStateOf(Offset.Zero) }
  var lastPosition by remember { mutableStateOf(Offset.Zero) }
  Row(
    Modifier.fillMaxWidth()
      .height(42.dp)
      .onGloballyPositioned { rowOrigin = it.boundsInRoot().topLeft }
      .pointerInput(item.componentId) {
        detectDragGestures(
          onDragStart = {
            dragDistance = 0f
            lastPosition = rowOrigin + it
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
            lastPosition = rowOrigin + change.position
            onDrag(lastPosition)
          },
        )
      }
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Filled.DragIndicator,
      contentDescription = "Drag ${item.displayName}",
      Modifier.size(18.dp),
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
  }
}

@Composable
private fun LayerRow(
  row: EditorTreeRow,
  selected: Boolean,
  onSelect: () -> Unit,
  onMove: (EditorMoveDirection) -> Unit,
) {
  var verticalDrag by remember { mutableFloatStateOf(0f) }
  val background = if (selected) Color(0xff30385a) else Color.Transparent
  Row(
    Modifier.fillMaxWidth()
      .height(34.dp)
      .background(background)
      .clickable(onClick = onSelect)
      .pointerInput(row.nodeId) {
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
      }
      .padding(start = (8 + row.depth * 12).dp, end = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Filled.DragIndicator,
      contentDescription = "Reorder ${row.nodeId}",
      Modifier.size(16.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      row.label,
      Modifier.padding(start = 5.dp).weight(1f),
      style = MaterialTheme.typography.bodySmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      row.nodeId,
      Modifier.width(92.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun PropertyInspector(
  state: UiBuilderEditorState,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val node = state.selectedNodeId?.let(state.document.nodes::get)
  Surface(Modifier.width(300.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.padding(16.dp)) {
      Text("Properties", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
      if (node.componentId == "m3/text") {
        val current =
          node.properties["text"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty()
        var draft by remember(node.id, current) { mutableStateOf(current) }
        Text("Text", style = MaterialTheme.typography.labelLarge)
        BasicTextField(
          value = draft,
          onValueChange = { draft = it },
          modifier =
            Modifier.fillMaxWidth()
              .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
              .semantics { contentDescription = "Text property" }
              .padding(top = 7.dp)
              .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
              .padding(10.dp),
          textStyle =
            MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        )
        Button(
          onClick = { dispatch(UiBuilderEditorEvent.SetText(node.id, draft)) },
          modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
        ) {
          Text("Apply text")
        }
        TextButton(
          onClick = { dispatch(UiBuilderEditorEvent.SetText(node.id, "Edited in Compose")) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Use sample text")
        }
      } else {
        node.properties.forEach { (name, value) ->
          Text(name, style = MaterialTheme.typography.labelLarge)
          Text(
            value.toString(),
            Modifier.padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (node.properties.isEmpty()) {
          Text(
            "This component has no authored properties.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      Spacer(Modifier.weight(1f))
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
