@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID
import ee.schimke.composeai.uibuilder.RemoteComposeSource
import ee.schimke.composeai.uibuilder.canvas.UiBuilderBoard
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvas.boardRootId
import ee.schimke.composeai.uibuilder.canvas.renderDensity
import ee.schimke.composeai.uibuilder.export.UiBuilderComponentPacks
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.filterRemoteComposeSources
import ee.schimke.composeai.uibuilder.humanizeSourceSlug
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

/**
 * The two questions the left panel answers: what can I add, and what is already here.
 *
 * "Components" rather than "Insert", which reads better on a rail: it is the word this editor
 * already uses for the panel, for its heading and in the accessibility name every script that
 * drives the editor looks for — a rail that renamed the panel would be a silent break for all
 * three.
 */
internal enum class NavigatorTab(val label: String) {
  Insert("Components"),
  Layers("Layers"),
}

/**
 * The left panel: insert something, or find something already inserted.
 *
 * One tab at a time rather than the three stacked scroll windows this used to be — a 240 dp catalog
 * above a 180 dp palette above whatever height was left for the layers. Every one of them was too
 * short to use on the design it was describing, and none of them could borrow the space the other
 * two were wasting. Tabs give each list the whole panel, and the tab strip says which question is
 * being asked.
 */
@Composable
internal fun EditorNavigator(
  state: UiBuilderEditorState,
  /** The verbs a layer answers to, for the tree's context menu. */
  selectionMenu: (() -> Unit) -> List<UiBuilderMenuEntry>,
  tab: NavigatorTab,
  onClose: (() -> Unit)?,
  catalogSystemId: String,
  catalogRows: List<EditorCatalogRow>,
  totalCatalogComponents: Int,
  /** The components at the top of the panel — see [UiBuilderEditorReducer.pinnedComponents]. */
  pinnedComponents: Set<String> = emptySet(),
  /** The packs the catalog carries, for the palette's own summary row. */
  packs: UiBuilderComponentPacks = UiBuilderComponentPacks.NONE,
  /** Opens the pack settings, or null where there is nothing to switch. */
  onManagePacks: (() -> Unit)? = null,
  thumbnailOf: (String, EditorCatalogVariant?) -> UiBuilderDocument?,
  layerRows: List<EditorLayerRow>,
  collaborators: List<UiBuilderCollaborator>,
  onOpenProperties: () -> Unit,
  dropTarget: ParentSlot?,
  /** What [dropTarget] is called out loud — a layer's name and its slot, not an id. */
  dropTargetLabel: String? = null,
  onCatalogDrag: (String, EditorCatalogVariant?, Offset?) -> Unit,
  onCatalogDrop: (String, EditorCatalogVariant?, Offset) -> Unit,
  canAddCatalogComponent: (String) -> Boolean,
  /**
   * Why an Add beside would refuse *this component*, or null.
   *
   * Separate from [besideRefusal], which is the document's answer and belongs on the destination
   * line: this one is about the thing being added, so it belongs on that thing's row. Without it a
   * Wear scaffold on a design that already has a board was a disabled Add and no reason anywhere —
   * the row knew why and did not say.
   */
  catalogAddRefusal: (String) -> String? = { null },
  /** Why an Add beside would refuse, or null — see `UiBuilderEditorReducer.besideRefusal`. */
  besideRefusal: String? = null,
  onCatalogAdd: (String, EditorCatalogVariant?) -> Unit,
  remoteComposeSources: List<RemoteComposeSource>,
  pendingRemoteComposeSource: RemoteComposeSource?,
  remoteComposeFailure: String?,
  resolveRemoteComposeThumbnail: (suspend (RemoteComposeSource) -> ImageBitmap?)?,
  onAddRemoteComposeSource: (RemoteComposeSource) -> Unit,
  onRemoteComposeDrag: (RemoteComposeSource, ImageBitmap?, Offset?) -> Unit,
  onRemoteComposeDrop: (RemoteComposeSource, Offset) -> Unit,
  moveRefusal: (String, ParentSlot) -> EditorMoveRefusal?,
  onEditorInteraction: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  modifier: Modifier = Modifier.width(NAVIGATOR_WIDTH).fillMaxHeight(),
) {
  LocalUiBuilderChrome.current.NavigatorSurface(modifier) {
    Column(Modifier.fillMaxSize()) {
      DockHeading(
        title =
          when (tab) {
            NavigatorTab.Insert -> "$catalogSystemId components"
            NavigatorTab.Layers -> "Layers · ${state.document.nodes.size}"
          },
        onClose = onClose,
      )
      when (tab) {
        NavigatorTab.Insert ->
          InsertPanel(
            state = state,
            catalogRows = catalogRows,
            totalCatalogComponents = totalCatalogComponents,
            pinnedComponents = pinnedComponents,
            packs = packs,
            onManagePacks = onManagePacks,
            thumbnailOf = thumbnailOf,
            dropTarget = dropTarget,
            dropTargetLabel = dropTargetLabel,
            onCatalogDrag = onCatalogDrag,
            onCatalogDrop = onCatalogDrop,
            canAddCatalogComponent = canAddCatalogComponent,
            catalogAddRefusal = catalogAddRefusal,
            besideRefusal = besideRefusal,
            onCatalogAdd = onCatalogAdd,
            remoteComposeSources = remoteComposeSources,
            pendingRemoteComposeSource = pendingRemoteComposeSource,
            remoteComposeFailure = remoteComposeFailure,
            resolveRemoteComposeThumbnail = resolveRemoteComposeThumbnail,
            onAddRemoteComposeSource = onAddRemoteComposeSource,
            onRemoteComposeDrag = onRemoteComposeDrag,
            onRemoteComposeDrop = onRemoteComposeDrop,
            onTextInputFocusChanged = onTextInputFocusChanged,
            dispatch = dispatch,
          )
        NavigatorTab.Layers ->
          LayersPanel(
            state = state,
            layerRows = layerRows,
            selectionMenu = selectionMenu,
            collaborators = collaborators,
            dropTarget = dropTarget,
            moveRefusal = moveRefusal,
            onEditorInteraction = onEditorInteraction,
            onOpenProperties = onOpenProperties,
            onTextInputFocusChanged = onTextInputFocusChanged,
            dispatch = dispatch,
          )
      }
    }
  }
}

/**
 * Everything that can be put on the canvas, in one list that owns the whole panel.
 *
 * The catalog and the Remote Compose palette share a search field and a scroll, because they answer
 * one question — "what can I put here?" — and a typed name has to narrow both or it narrows
 * neither.
 */
@Composable
private fun InsertPanel(
  state: UiBuilderEditorState,
  catalogRows: List<EditorCatalogRow>,
  /**
   * Every component the catalog has, which is what the All row counts — not what survived a filter.
   */
  totalCatalogComponents: Int,
  /** The components at the top of the panel — see [UiBuilderEditorReducer.pinnedComponents]. */
  pinnedComponents: Set<String> = emptySet(),
  /** The packs the catalog carries, for the palette's own summary row. */
  packs: UiBuilderComponentPacks = UiBuilderComponentPacks.NONE,
  /** Opens the pack settings, or null where there is nothing to switch. */
  onManagePacks: (() -> Unit)? = null,
  /** The document a row's picture draws, from the reducer that would perform the insert. */
  thumbnailOf: (String, EditorCatalogVariant?) -> UiBuilderDocument?,
  dropTarget: ParentSlot?,
  /** What [dropTarget] is called out loud — a layer's name and its slot, not an id. */
  dropTargetLabel: String? = null,
  onCatalogDrag: (String, EditorCatalogVariant?, Offset?) -> Unit,
  onCatalogDrop: (String, EditorCatalogVariant?, Offset) -> Unit,
  canAddCatalogComponent: (String) -> Boolean,
  /**
   * Why an Add beside would refuse *this component*, or null.
   *
   * Separate from [besideRefusal], which is the document's answer and belongs on the destination
   * line: this one is about the thing being added, so it belongs on that thing's row. Without it a
   * Wear scaffold on a design that already has a board was a disabled Add and no reason anywhere —
   * the row knew why and did not say.
   */
  catalogAddRefusal: (String) -> String? = { null },
  /** Why an Add beside would refuse, or null — see `UiBuilderEditorReducer.besideRefusal`. */
  besideRefusal: String? = null,
  onCatalogAdd: (String, EditorCatalogVariant?) -> Unit,
  remoteComposeSources: List<RemoteComposeSource>,
  pendingRemoteComposeSource: RemoteComposeSource?,
  remoteComposeFailure: String?,
  resolveRemoteComposeThumbnail: (suspend (RemoteComposeSource) -> ImageBitmap?)?,
  onAddRemoteComposeSource: (RemoteComposeSource) -> Unit,
  onRemoteComposeDrag: (RemoteComposeSource, ImageBitmap?, Offset?) -> Unit,
  onRemoteComposeDrop: (RemoteComposeSource, Offset) -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val visibleSources =
    remember(remoteComposeSources, state.catalogQuery) {
      filterRemoteComposeSources(remoteComposeSources, state.catalogQuery)
    }
  Column(Modifier.fillMaxSize()) {
    SearchField(
      state.catalogQuery,
      placeholder = "Search components",
      onFocusChanged = onTextInputFocusChanged,
    ) {
      dispatch(UiBuilderEditorEvent.SearchCatalog(it))
    }
    // Where an Add would land, said before it is pressed rather than after it is refused. The
    // beginner's question about this panel is not what the components are called.
    // Where an Add beside would land, in the same voice as the line above it: a board says how many
    // items it already holds, and a design that has to be wrapped says that is what will happen.
    val boardRootId = state.document.boardRootId
    val besideDestination =
      when {
        besideRefusal != null -> null
        boardRootId != null -> {
          val held =
            state.document.nodes[boardRootId]?.slots?.get(UiBuilderBoard.SLOT).orEmpty().size
          "Adds beside $held item(s) on the board"
        }
        state.document.roots.isEmpty() -> "Adds as this design's first item"
        else -> "Adds beside the design, on a new board"
      }
    val destination =
      when {
        state.addBeside -> besideDestination ?: besideRefusal.orEmpty()
        dropTarget != null ->
          "Adds into ${dropTargetLabel ?: "${dropTarget.nodeId}.${dropTarget.slot}"}"
        else -> "Select a layer that can hold a component"
      }
    LocalUiBuilderChrome.current.ComponentBrowserDestination(
      destination,
      available = if (state.addBeside) besideDestination != null else dropTarget != null,
    )
    LocalUiBuilderChrome.current.ComponentBrowserAddBeside(state.addBeside) {
      dispatch(UiBuilderEditorEvent.ToggleAddBeside)
    }
    if (!packs.isEmpty && onManagePacks != null) {
      val on = packs.packs.count { it.id in state.enabledPacks }
      LocalUiBuilderChrome.current.ComponentBrowserPacksSummary(
        label =
          when {
            on == 0 ->
              "${packs.packs.size} component ${if (packs.packs.size == 1) "pack" else "packs"} off"
            else -> "$on of ${packs.packs.size} component packs on"
          },
        onManage = onManagePacks,
      )
    }
    // The palette is a visual chooser. A grid gives each component's rendered stem enough room to
    // be recognised, while full-width shelf headings keep the catalog's component families clear.
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 118.dp),
      modifier = Modifier.fillMaxWidth().weight(1f),
      state = rememberLazyGridState(),
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        LocalUiBuilderChrome.current.ComponentBrowserAllRow(totalCatalogComponents) {
          if (state.catalogQuery.isNotBlank()) dispatch(UiBuilderEditorEvent.SearchCatalog(""))
          dispatch(UiBuilderEditorEvent.ExpandAllCatalogGroups)
        }
      }
      gridItems(
        catalogRows,
        key = EditorCatalogRow::catalogRowKey,
        span = { row ->
          if (row is EditorCatalogRow.Group) GridItemSpan(maxLineSpan) else GridItemSpan(1)
        },
      ) { row ->
        when (row) {
          is EditorCatalogRow.Group ->
            LocalUiBuilderChrome.current.ComponentBrowserGroupRow(
              name = row.name,
              count = row.count,
              expanded = row.expanded,
              onToggle = { dispatch(UiBuilderEditorEvent.ToggleCatalogGroup(row.name)) },
            )
          is EditorCatalogRow.Component ->
            CatalogComponentTile(
              item = row.item,
              thumbnail = thumbnailOf(row.item.componentId, null),
              expanded = row.expanded,
              onDrag = { onCatalogDrag(row.item.componentId, null, it) },
              onDrop = { onCatalogDrop(row.item.componentId, null, it) },
              canAdd = canAddCatalogComponent(row.item.componentId),
              refusal = catalogAddRefusal(row.item.componentId),
              onAdd = { onCatalogAdd(row.item.componentId, null) },
              onToggleVariants = {
                dispatch(UiBuilderEditorEvent.ToggleCatalogComponent(row.item.componentId))
              },
              pinned = row.item.componentId in pinnedComponents,
              onTogglePinned = {
                dispatch(UiBuilderEditorEvent.TogglePinnedComponent(row.item.componentId))
              },
            )
          is EditorCatalogRow.Variant ->
            CatalogVariantTile(
              variant = row.variant,
              thumbnail = thumbnailOf(row.variant.componentId, row.variant),
              componentName = row.componentName,
              onDrag = { onCatalogDrag(row.variant.componentId, row.variant, it) },
              onDrop = { onCatalogDrop(row.variant.componentId, row.variant, it) },
              canAdd = canAddCatalogComponent(row.variant.componentId),
              refusal = catalogAddRefusal(row.variant.componentId),
              onAdd = { onCatalogAdd(row.variant.componentId, row.variant) },
            )
        }
      }
      if (catalogRows.isEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          EmptyPanelNote("No component matches “${state.catalogQuery}”.")
        }
      }
      if (remoteComposeSources.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outline)
          PanelHeading(
            "Remote Compose documents",
            remoteComposeFailure
              ?: pendingRemoteComposeSource?.let { "Fetching ${it.label}…" }
              ?: "${visibleSources.size} of ${remoteComposeSources.size} published",
          )
        }
        gridItemsIndexed(visibleSources, key = { _, source -> source.id }) { index, source ->
          if (index == 0 || visibleSources[index - 1].group != source.group) {
            GroupHeading(source.group)
          }
          RemoteComposeSourceRow(
            source = source,
            resolveThumbnail = resolveRemoteComposeThumbnail,
            canDrag = pendingRemoteComposeSource == null,
            // Enabled off the same question the insert will ask, so a row that cannot land is
            // visibly unavailable rather than pressable and then refused.
            canAdd =
              pendingRemoteComposeSource == null &&
                canAddCatalogComponent(REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID),
            onAdd = { onAddRemoteComposeSource(source) },
            onDrag = { thumbnail, position -> onRemoteComposeDrag(source, thumbnail, position) },
            onDrop = { onRemoteComposeDrop(source, it) },
          )
        }
      }
    }
  }
}

/** The document as a tree, filtered, with the whole panel to be a tree in. */
@Composable
private fun LayersPanel(
  state: UiBuilderEditorState,
  layerRows: List<EditorLayerRow>,
  selectionMenu: (() -> Unit) -> List<UiBuilderMenuEntry>,
  collaborators: List<UiBuilderCollaborator>,
  dropTarget: ParentSlot?,
  moveRefusal: (String, ParentSlot) -> EditorMoveRefusal?,
  onEditorInteraction: () -> Unit,
  onOpenProperties: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val matches = layerRows.count { it is EditorLayerRow.Node && it.row.matched }
  // Where each layer row sits vertically, in root pixels, kept in a plain map rather than snapshot
  // state: it is written from layout on every scroll and every relayout, and a recomposition per
  // frame of scrolling is a price the panel does not need to pay. The drag reads it from a
  // callback, which is the only place it is ever read.
  val rowBounds = remember(layerRows) { mutableMapOf<Int, ClosedFloatingPointRange<Float>>() }
  var draggedLayer by remember { mutableStateOf<String?>(null) }
  var landing by remember { mutableStateOf<LayerLanding?>(null) }
  Column(Modifier.fillMaxSize()) {
    SearchField(
      state.layerQuery,
      // Reusing the catalog's field meant reusing its placeholder, so an empty layers filter
      // invited you to search components. Two fields, two things to look for.
      placeholder = "Filter layers",
      onFocusChanged = onTextInputFocusChanged,
    ) {
      dispatch(UiBuilderEditorEvent.SearchLayers(it))
    }
    Row(
      Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        when {
          draggedLayer != null ->
            landing?.refusal?.message
              ?: landing?.let { "Drop into ${it.target.nodeId}.${it.target.slot}" }
              ?: "Drag over a layer or a slot"
          state.layerQuery.isNotBlank() -> "$matches of ${state.document.nodes.size} match"
          else -> "Drag a row onto a layer or a slot"
        },
        Modifier.weight(1f),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 2,
      )
      // The multi-node inspector is only as reachable as the selection is. Filtering to every text
      // on the screen and then taking all of them is what makes restyling a screen one edit.
      if (state.layerQuery.isNotBlank() && matches > 0) {
        TextButton(
          onClick = {
            onEditorInteraction()
            dispatch(UiBuilderEditorEvent.SelectAllMatches)
          }
        ) {
          Text("Select all $matches")
        }
      }
    }
    LazyColumn(Modifier.fillMaxSize()) {
      itemsIndexed(layerRows, key = { _, row -> row.layerKey() }) { index, row ->
        val recordBounds = Modifier.onGloballyPositioned {
          val bounds = it.boundsInRoot()
          rowBounds[index] = bounds.top..bounds.bottom
        }
        when (row) {
          is EditorLayerRow.Slot ->
            SlotRow(
              row = row,
              modifier = recordBounds,
              // The slot a catalog drop would land in, so the answer the panel gives in words is
              // also given in the tree, next to the children it would join.
              isCatalogTarget = row.parent == dropTarget,
              landing = landing?.takeIf { it.marker == LayerLandingMarker.Into(index) },
            )
          is EditorLayerRow.Node ->
            LayerRow(
              row = row.row,
              indent = row.indent,
              modifier = recordBounds,
              selectionMenu = selectionMenu,
              // Every selected node is highlighted, not just the anchor — a selection you cannot
              // see is one you cannot trust before pressing Delete.
              selected = row.nodeId in state.selection,
              dragged = row.nodeId == draggedLayer,
              landing =
                landing?.takeIf {
                  it.marker == LayerLandingMarker.Above(index) ||
                    it.marker == LayerLandingMarker.Below(index)
                },
              collaborators = collaborators.filter { row.nodeId in it.selectedNodeIds },
              onSelect = { gesture, showProperties ->
                onEditorInteraction()
                dispatch(
                  when (gesture) {
                    LayerSelectionGesture.Replace -> UiBuilderEditorEvent.SelectNode(row.nodeId)
                    LayerSelectionGesture.Toggle -> UiBuilderEditorEvent.ToggleNode(row.nodeId)
                    LayerSelectionGesture.Range ->
                      UiBuilderEditorEvent.ExtendSelectionTo(row.nodeId)
                  }
                )
                if (showProperties) onOpenProperties()
              },
              onDragTo = { y ->
                draggedLayer = row.nodeId
                landing =
                  layerLanding(
                    nodeId = row.nodeId,
                    y = y,
                    rows = layerRows,
                    bounds = rowBounds,
                    document = state.document,
                    refusalOf = { target -> moveRefusal(row.nodeId, target) },
                  )
              },
              onDrop = {
                val drop = landing
                draggedLayer = null
                landing = null
                if (drop != null) {
                  onEditorInteraction()
                  // Sent even when it will be refused: the reducer owns that answer and reports it
                  // through the same channel as every other refused edit, which is how the
                  // operator learns *why* a slot would not take the layer rather than watching the
                  // gesture evaporate.
                  dispatch(
                    UiBuilderEditorEvent.MoveNodeInto(row.nodeId, drop.target, drop.afterNodeId)
                  )
                }
              },
              onDragCancel = {
                draggedLayer = null
                landing = null
              },
            )
        }
      }
      if (layerRows.isEmpty()) {
        item { EmptyPanelNote("No layer matches “${state.layerQuery}”.") }
      }
    }
  }
}

/** What a filtered list says when it has filtered everything away. */
@Composable
private fun EmptyPanelNote(text: String) {
  Text(
    text,
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
}

/** A dock panel's title bar: what this panel is, and the way back to the whole canvas. */
@Composable
internal fun DockHeading(title: String, onClose: (() -> Unit)?, supporting: String? = null) {
  LocalUiBuilderChrome.current.DockHeading(title, supporting, onClose)
}

/** The right-hand docks, in the order the rail lists them. */
internal enum class EditorDock(val label: String) {
  Properties("Properties"),
  Theme("Theme"),
  Screen("Screen"),
  Issues("Issues"),
  Comments("Talk"),
  History("History"),
  Code("Code"),
}

/**
 * The inspector mode a dock stands for, or null for the one that is not an inspector.
 *
 * The mapping is one way on purpose: [EditorInspectorMode] is document state that survives a reload
 * and travels to a collaborator, and which dock is open is not.
 */
internal fun EditorDock.inspectorMode(): EditorInspectorMode? =
  when (this) {
    EditorDock.Properties -> EditorInspectorMode.Properties
    EditorDock.Theme -> EditorInspectorMode.Theme
    EditorDock.Screen -> EditorInspectorMode.Screen
    EditorDock.Issues -> EditorInspectorMode.Issues
    EditorDock.Comments -> EditorInspectorMode.Comments
    EditorDock.History -> EditorInspectorMode.History
    EditorDock.Code -> null
  }

/**
 * The strip of panel switches that flanks the canvas.
 *
 * Every panel in this editor used to be nailed open: 300 dp of catalog on the left and 360 dp of
 * inspector on the right, on every screen, whether or not the design being drawn was 400 dp wide.
 * The canvas — the thing the editor is for — got whatever was left. A rail makes each panel a
 * switch: the icon says the panel exists, pressing it opens the panel, pressing it again gives the
 * space back to the design.
 */
@Composable
internal fun EditorRail(items: List<EditorRailItem>, modifier: Modifier = Modifier) {
  LocalUiBuilderChrome.current.EditorRail(
    items.map { UiBuilderRailItemModel(it.label, it.icon, it.selected, it.badge, it.onClick) },
    modifier,
  )
}

/** [EditorRail] in the editor, or the same switches published to a host's own chrome. */
@Composable
internal fun HostOrOwnRail(
  hostChrome: UiBuilderHostChrome?,
  group: String,
  items: List<EditorRailItem>,
  modifier: Modifier = Modifier,
) {
  if (hostChrome == null) {
    EditorRail(items, modifier)
  } else {
    PublishHostChrome(
      hostChrome,
      group,
      items.map {
        HostChromeEntry(
          UiBuilderHostAction(
            id = it.id,
            label = it.label,
            group = group,
            icon = it.icon.name,
            checked = it.selected,
            badge = it.badge,
          ),
          it.onClick,
        )
      },
    )
  }
}

/** One switch on an [EditorRail]. */
internal data class EditorRailItem(
  val id: String,
  val label: String,
  val icon: UiBuilderChromeIcon,
  val selected: Boolean,
  val badge: Int = 0,
  val onClick: () -> Unit,
)

internal fun EditorDock.icon(): UiBuilderChromeIcon =
  when (this) {
    EditorDock.Properties -> UiBuilderChromeIcon.Properties
    EditorDock.Theme -> UiBuilderChromeIcon.Theme
    EditorDock.Screen -> UiBuilderChromeIcon.Screen
    EditorDock.Issues -> UiBuilderChromeIcon.Issues
    EditorDock.Comments -> UiBuilderChromeIcon.Comments
    EditorDock.History -> UiBuilderChromeIcon.History
    EditorDock.Code -> UiBuilderChromeIcon.Code
  }

internal fun NavigatorTab.icon(): UiBuilderChromeIcon =
  when (this) {
    NavigatorTab.Insert -> UiBuilderChromeIcon.Components
    NavigatorTab.Layers -> UiBuilderChromeIcon.Layers
  }

@Composable
private fun PanelHeading(title: String, supporting: String) {
  LocalUiBuilderChrome.current.PanelHeading(title, supporting)
}

@Composable
internal fun SearchField(
  value: String,
  placeholder: String,
  /** What the accessibility tree — and every script that drives this editor — calls the box. */
  searchLabel: String = "Component catalog search",
  onFocusChanged: (Boolean) -> Unit,
  onValueChange: (String) -> Unit,
) {
  LocalUiBuilderChrome.current.SearchField(
    value = value,
    placeholder = placeholder,
    searchLabel = searchLabel,
    onFocusChanged = onFocusChanged,
    onValueChange = onValueChange,
  )
}

/**
 * The heading over a run of rows grouped by something the reducer decided — the Remote Compose
 * palette's source groups.
 *
 * A label rather than a control, unlike [CatalogGroupRow]: nothing collapses here, because the list
 * it heads is short and a twisty that hides four rows is a twisty nobody presses.
 */
@Composable
private fun GroupHeading(group: String) {
  LocalUiBuilderChrome.current.GroupHeading(humanizeSourceSlug(group).uppercase())
}

/**
 * One published Remote Compose document, addable or draggable into a compatible slot.
 *
 * The thumbnail is the grip, like [CatalogRow]. A drop captures the exact slot immediately, then
 * fetches the document bytes; the reducer revalidates that captured slot when the fetch completes.
 */
@Composable
private fun RemoteComposeSourceRow(
  source: RemoteComposeSource,
  resolveThumbnail: (suspend (RemoteComposeSource) -> ImageBitmap?)?,
  canDrag: Boolean,
  canAdd: Boolean,
  onAdd: () -> Unit,
  onDrag: (ImageBitmap?, Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
) {
  var thumbnail by remember(source.id) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(source.id, resolveThumbnail) {
    thumbnail =
      try {
        resolveThumbnail?.invoke(source)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        null
      }
  }
  Row(
    Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val dragModifier =
      if (canDrag) {
        Modifier.catalogDrag(
            dragKey = source.id,
            onDrag = { onDrag(thumbnail, it) },
            onDrop = onDrop,
          )
          .semantics { contentDescription = "Drag ${source.label}" }
      } else {
        Modifier
      }
    Surface(
      Modifier.size(COMPONENT_THUMBNAIL_SIZE).then(dragModifier),
      shape = RoundedCornerShape(4.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
      if (thumbnail != null) {
        Image(
          bitmap = thumbnail!!,
          contentDescription = null,
          modifier = Modifier.fillMaxSize().padding(2.dp).clearAndSetSemantics {},
          contentScale = ContentScale.Fit,
        )
      } else {
        Icon(
          Icons.Filled.Widgets,
          contentDescription = null,
          modifier = Modifier.padding(8.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Text(
      source.label,
      Modifier.padding(start = 6.dp).weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    TextButton(onClick = onAdd, enabled = canAdd) {
      Text("Add", Modifier.semantics { contentDescription = "Add ${source.label}" })
    }
  }
}

/** One draggable stem component in the visual palette. Variants stay behind its disclosure. */
@Composable
private fun CatalogComponentTile(
  item: EditorCatalogItem,
  thumbnail: UiBuilderDocument?,
  expanded: Boolean,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
  canAdd: Boolean,
  refusal: String?,
  onAdd: () -> Unit,
  onToggleVariants: () -> Unit,
  pinned: Boolean,
  onTogglePinned: () -> Unit,
) {
  val unexportable = item.exportsToCompose == false
  LocalUiBuilderChrome.current.ComponentBrowserTile(
    model =
      UiBuilderCatalogTileModel(
        title = item.displayName,
        supporting = refusal ?: item.componentId,
        supportingIsError = refusal != null,
        unexportable = unexportable,
        pinned = pinned,
        variantCount = item.variants.size,
        variantsExpanded = expanded,
        canAdd = canAdd,
        addContentDescription =
          if (refusal == null) "Add ${item.displayName}" else "Add ${item.displayName} — $refusal",
        onAdd = onAdd,
        onTogglePinned = onTogglePinned,
        onToggleVariants = onToggleVariants,
      )
  ) {
    CatalogThumbnail(
      document = thumbnail,
      componentId = item.componentId,
      dragKey = item.componentId,
      label = item.displayName,
      size = DpSize(104.dp, 72.dp),
      onDrag = onDrag,
      onDrop = onDrop,
    )
  }
}

/** A concrete variant, revealed only after its [CatalogComponentTile] stem is expanded. */
@Composable
private fun CatalogVariantTile(
  variant: EditorCatalogVariant,
  thumbnail: UiBuilderDocument?,
  componentName: String,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
  canAdd: Boolean,
  refusal: String?,
  onAdd: () -> Unit,
) {
  val label = variant.label
  val qualified = "$label $componentName"
  val unexportable = variant.exportsToCompose == false
  LocalUiBuilderChrome.current.ComponentBrowserTile(
    model =
      UiBuilderCatalogTileModel(
        title = label,
        supporting = null,
        variant = true,
        defaultVariant = variant.default,
        unexportable = unexportable,
        canAdd = canAdd,
        addContentDescription =
          if (refusal == null) "Add $qualified" else "Add $qualified — $refusal",
        onAdd = onAdd,
      )
  ) {
    CatalogThumbnail(
      document = thumbnail,
      componentId = variant.componentId,
      dragKey = "${variant.componentId}#${variant.value}",
      label = qualified,
      size = DpSize(96.dp, 64.dp),
      onDrag = onDrag,
      onDrop = onDrop,
    )
  }
}

/**
 * A palette row's picture: the component itself, inserted into an empty frame and shrunk.
 *
 * **Rendered, not baked.** The catalog's tree shows a prebaked PNG per row because that page has
 * the pixels on disk; the builder has something better — the renderer that is about to draw the
 * thing for real. So the row draws [UiBuilderEditorReducer.previewDocument], which is the result of
 * the same `InsertComponent` the row's Add dispatches. A thumbnail therefore cannot disagree with
 * what pressing Add does, no generator task has to be re-run when a default changes, no PNGs are
 * committed, and a catalog nobody has baked artwork for still gets pictures.
 *
 * Drawn at [PREVIEW_FRAME_WIDTH_DP] and scaled down rather than laid out at 44 dp, which is the
 * difference between a shrunken component and a component squeezed until its text wraps to nothing.
 * `graphicsLayer` rather than `scale`, so the shrink is a draw-time transform over a subtree that
 * laid itself out at a sensible size.
 *
 * It is also the **grip**: you drag the picture of the thing you are placing, which is both more
 * obvious than a dot-grid handle and how the row affords two things in the width of one.
 */
@Composable
private fun CatalogThumbnail(
  document: UiBuilderDocument?,
  componentId: String,
  dragKey: String,
  label: String,
  size: DpSize,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
) {
  // A component the frame could not hold keeps the handle it always had. A picture that could not
  // be drawn is better absent than faked.
  if (document == null) {
    CatalogDragHandle(dragKey, label, onDrag, onDrop)
    return
  }
  val density = LocalDensity.current
  val renderer = LocalUiBuilderCanvasRenderer.current
  val fallbackScale = size.width.value / PREVIEW_FRAME_WIDTH_DP
  var contentBounds by remember(document.id) { mutableStateOf<UiBuilderPixelBounds?>(null) }
  // Measured, and nothing in it has a size: an empty Box, Column or Row lays out at 0x0, so its
  // picture was a blank tile indistinguishable from one that failed to draw.
  var drewNothing by remember(document.id) { mutableStateOf(false) }
  val transform =
    thumbnailContentTransform(
      contentBounds = contentBounds,
      tileSize = with(density) { Size(size.width.toPx(), size.height.toPx()) },
      fallbackScale = fallbackScale,
      margin = with(density) { THUMBNAIL_MARGIN.toPx() },
    )
  Box(
    Modifier.size(size)
      .clip(RoundedCornerShape(4.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.TopStart,
  ) {
    Box(
      // Pinned to the tile's top start before it is sized. A `requiredSize` larger than its
      // constraints is centred by default, which put the frame's origin at (-36, -28) in a 104x72
      // tile while the transform below assumes (0, 0): every thumbnail drew shifted up and left, so
      // a Button read as "utton" with its top cut off.
      Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
        .requiredSize(PREVIEW_FRAME_WIDTH_DP.dp, PREVIEW_FRAME_HEIGHT_DP.dp)
        .graphicsLayer {
          // Top-start is intentional. The inspection snapshot below is in post-transform root
          // pixels; a fixed origin lets it recover the component's source-frame bounds without
          // guessing how Compose centred a 176dp child in a 44dp tile.
          transformOrigin = TransformOrigin(0f, 0f)
          scaleX = transform.scale
          scaleY = transform.scale
          translationX = transform.translation.x
          translationY = transform.translation.y
        }
        // A picture of a Switch is not a Switch. Without this the row would publish every semantics
        // node inside the thumbnail — so a screen reader would read a palette row as a switch it
        // could toggle, and `getByRole("button", …)` would match forty pictures of buttons that are
        // not on the canvas. The row's own name is set on the overlay below.
        .clearAndSetSemantics {}
    ) {
      val inspection: (UiBuilderInspectionSnapshot) -> Unit = { snapshot ->
        val next = thumbnailContentBounds(snapshot, transform.scale)
        // Measured under the transform these bounds produce, so each pass reads them back a
        // fraction of a pixel off and would re-transform forever. Only a real change moves it.
        if (!sameThumbnailBounds(next, contentBounds)) contentBounds = next
        val empty =
          next == null &&
            snapshot.nodes.any { it.nodeId == PREVIEW_FRAME_CELL_ID && it.bounds != null }
        if (empty != drewNothing) drewNothing = empty
      }
      if (renderer == null) {
        UiBuilderSurface(
          document = document,
          editorOverlay = false,
          onInspectionSnapshot = inspection,
        )
      } else {
        renderer(
          document,
          UiBuilderCanvasSurface(
            PREVIEW_FRAME_WIDTH_DP.toFloat(),
            PREVIEW_FRAME_HEIGHT_DP.toFloat(),
            document.renderDensity(density).density,
            UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED,
          ),
          null,
          false,
          {},
          { snapshots -> inspection(snapshots.editor) },
        )
      }
    }
    if (drewNothing) {
      EmptyContainerSchematic(emptyContainerSchematic(componentId), Modifier.matchParentSize())
    }
    // The gesture sits ON TOP of the picture rather than under it. A Switch drawn in a thumbnail is
    // a real Switch and would eat the press that was meant to start a drag; a later sibling wins
    // the hit test, so the whole tile drags however interactive the thing inside it happens to be.
    Box(
      Modifier.matchParentSize().catalogDrag(dragKey, onDrag, onDrop).semantics {
        contentDescription = "Drag $label"
      }
    )
  }
}

/** Whether two measurements of a thumbnail's content differ by less than half a source pixel. */
internal fun sameThumbnailBounds(a: UiBuilderPixelBounds?, b: UiBuilderPixelBounds?): Boolean {
  if (a == null || b == null) return a == b
  return abs(a.x - b.x) < 0.5f &&
    abs(a.y - b.y) < 0.5f &&
    abs(a.width - b.width) < 0.5f &&
    abs(a.height - b.height) < 0.5f
}

/** Space a thumbnail keeps between its component and the tile's edge. */
private val THUMBNAIL_MARGIN = 6.dp

/** How an empty container's thumbnail sketches the children it would arrange. */
internal enum class ContainerSchematic {
  /** Children one above another: a column, a list. */
  Stacked,
  /** Children side by side: a row, a flow row. */
  SideBySide,
  /** Children in cells: a grid. */
  Grid,
  /** Children layered in one place, or a region with no arrangement of its own: a box, a pane. */
  Layered,
}

/**
 * Which sketch an empty container draws, read from its id because that is the one thing every
 * catalog's layout components share. Checked in this order so `flow-column` is a column and
 * `lazy-grid` a grid rather than whichever word came first.
 */
internal fun emptyContainerSchematic(componentId: String): ContainerSchematic {
  val name = componentId.substringAfterLast('/').lowercase()
  return when {
    "grid" in name -> ContainerSchematic.Grid
    "column" in name || "list" in name -> ContainerSchematic.Stacked
    "row" in name -> ContainerSchematic.SideBySide
    else -> ContainerSchematic.Layered
  }
}

/**
 * A container with nothing in it, drawn as the arrangement it would give its children.
 *
 * A dashed outline for the container and solid blocks for placeholder children, in the outline
 * colour so it reads as a diagram and never as a rendered component.
 */
@Composable
private fun EmptyContainerSchematic(schematic: ContainerSchematic, modifier: Modifier) {
  val outline = MaterialTheme.colorScheme.outline
  val block = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
  Canvas(modifier.padding(THUMBNAIL_MARGIN + 4.dp).clearAndSetSemantics {}) {
    val stroke = 1.dp.toPx()
    val radius = CornerRadius(3.dp.toPx())
    drawRoundRect(
      color = outline,
      cornerRadius = radius,
      style =
        Stroke(
          width = stroke,
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        ),
    )
    val gap = 3.dp.toPx()
    val inner = Rect(Offset(gap * 2, gap * 2), Size(size.width - gap * 4, size.height - gap * 4))
    fun cell(x: Float, y: Float, w: Float, h: Float) =
      drawRoundRect(block, Offset(x, y), Size(w.coerceAtLeast(1f), h.coerceAtLeast(1f)), radius)
    when (schematic) {
      ContainerSchematic.Stacked -> {
        val h = (inner.height - gap * 2) / 3
        repeat(3) { cell(inner.left, inner.top + it * (h + gap), inner.width, h) }
      }
      ContainerSchematic.SideBySide -> {
        val w = (inner.width - gap * 2) / 3
        repeat(3) { cell(inner.left + it * (w + gap), inner.top, w, inner.height) }
      }
      ContainerSchematic.Grid -> {
        val w = (inner.width - gap) / 2
        val h = (inner.height - gap) / 2
        repeat(4) {
          cell(inner.left + (it % 2) * (w + gap), inner.top + (it / 2) * (h + gap), w, h)
        }
      }
      ContainerSchematic.Layered -> {
        cell(inner.left, inner.top, inner.width * 0.62f, inner.height * 0.62f)
        cell(
          inner.left + inner.width * 0.38f,
          inner.top + inner.height * 0.38f,
          inner.width * 0.62f,
          inner.height * 0.62f,
        )
      }
    }
  }
}

/** The transformed source frame that fills a palette thumbnail around its actual component. */
internal data class ThumbnailContentTransform(val scale: Float, val translation: Offset)

/**
 * Fit the component's source-frame bounds into a palette tile without magnifying it into an
 * artefact. Bounds are source pixels; [translation] is therefore also a graphics-layer pixel value.
 */
internal fun thumbnailContentTransform(
  contentBounds: UiBuilderPixelBounds?,
  tileSize: Size,
  fallbackScale: Float,
  /**
   * Kept clear on every side, in tile pixels. Without it a component wider than the tile was scaled
   * to touch both edges, and a pill-shaped Button or a FAB read as the tile's own shape rather than
   * a thing sitting in it.
   */
  margin: Float = 0f,
): ThumbnailContentTransform {
  if (
    contentBounds == null ||
      contentBounds.width <= 0f ||
      contentBounds.height <= 0f ||
      !contentBounds.width.isFinite() ||
      !contentBounds.height.isFinite()
  ) {
    return ThumbnailContentTransform(fallbackScale, Offset.Zero)
  }
  // A 24dp icon is useful at roughly twice its authored size; beyond that it stops reading as the
  // component and starts reading as a clipped pixel crop.
  val usableWidth = (tileSize.width - 2 * margin).coerceAtLeast(1f)
  val usableHeight = (tileSize.height - 2 * margin).coerceAtLeast(1f)
  val scale = minOf(usableWidth / contentBounds.width, usableHeight / contentBounds.height, 2f)
  val horizontalInset = (tileSize.width - contentBounds.width * scale) / 2f
  val verticalInset = (tileSize.height - contentBounds.height * scale) / 2f
  return ThumbnailContentTransform(
    scale = scale,
    translation =
      Offset(
        x = -contentBounds.x * scale + horizontalInset,
        y = -contentBounds.y * scale + verticalInset,
      ),
  )
}

/**
 * Convert the inspection collector's post-transform root pixels back into source-frame pixels. The
 * frame entry provides the origin, so this stays correct when the palette scrolls or the tile
 * itself is placed anywhere in the editor.
 */
internal fun thumbnailContentBounds(
  snapshot: UiBuilderInspectionSnapshot,
  scale: Float,
): UiBuilderPixelBounds? {
  if (scale <= 0f || !scale.isFinite()) return null
  val frame =
    snapshot.nodes.singleOrNull { it.nodeId == PREVIEW_FRAME_CELL_ID }?.bounds ?: return null
  val componentBounds =
    snapshot.nodes
      .asSequence()
      .filter { it.nodeId != PREVIEW_FRAME_CELL_ID }
      .mapNotNull { it.bounds }
      .filter { it.width > 0f && it.height > 0f }
      .toList()
  if (componentBounds.isEmpty()) return null
  val left = componentBounds.minOf { (it.x - frame.x) / scale }
  val top = componentBounds.minOf { (it.y - frame.y) / scale }
  val right = componentBounds.maxOf { (it.right - frame.x) / scale }
  val bottom = componentBounds.maxOf { (it.bottom - frame.y) / scale }
  return UiBuilderPixelBounds(left, top, right - left, bottom - top)
}

/**
 * The grip a palette row is dragged onto the canvas by, where it has no picture to drag instead.
 */
@Composable
private fun CatalogDragHandle(
  dragKey: String,
  label: String,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
) {
  Icon(
    Icons.Filled.DragIndicator,
    contentDescription = "Drag $label",
    modifier = Modifier.size(18.dp).catalogDrag(dragKey, onDrag, onDrop),
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * The drag a palette row starts: report where the pointer is, and on release either drop there or
 * withdraw.
 *
 * A modifier rather than a composable, because two different things carry this gesture — the
 * thumbnail and the fallback handle — and two copies of nine lines of drag bookkeeping is two
 * chances for a drop threshold to drift apart.
 *
 * The origin is captured from layout rather than taken from the drag's own coordinates: the events
 * arrive local to this element, and the canvas needs them in the root's space to hit-test a slot.
 */
private fun Modifier.catalogDrag(
  dragKey: String,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
): Modifier = composed {
  var dragDistance by remember { mutableFloatStateOf(0f) }
  var dragOrigin by remember { mutableStateOf(Offset.Zero) }
  var lastPosition by remember { mutableStateOf(Offset.Zero) }
  val currentOnDrag = rememberUpdatedState(onDrag)
  val currentOnDrop = rememberUpdatedState(onDrop)
  Modifier.onGloballyPositioned { dragOrigin = it.boundsInRoot().topLeft }
    .pointerInput(dragKey) {
      detectDragGestures(
        onDragStart = {
          dragDistance = 0f
          lastPosition = dragOrigin + it
          currentOnDrag.value(lastPosition)
        },
        onDragEnd = {
          // Below the threshold it was a press, not a drag, so the insert is withdrawn rather
          // than landed wherever the pointer happened to rest.
          if (dragDistance > 8f) currentOnDrop.value(lastPosition) else currentOnDrag.value(null)
          dragDistance = 0f
        },
        onDragCancel = {
          dragDistance = 0f
          currentOnDrag.value(null)
        },
        onDrag = { change, amount ->
          change.consume()
          dragDistance += amount.getDistance()
          lastPosition = dragOrigin + change.position
          currentOnDrag.value(lastPosition)
        },
      )
    }
}

/**
 * A stable identity per row, for the `LazyColumn`.
 *
 * A group and a component can share a name and a component and its variant share an id, so the row
 * kind is part of the key — without it, expanding a group would reuse the group row's slot for the
 * first component under it and the list would animate the wrong things.
 */
private fun EditorCatalogRow.catalogRowKey(): String =
  when (this) {
    is EditorCatalogRow.Group -> "group:$name"
    // The pinned copy is the same component twice, so which copy is part of the key: a list that
    // saw two rows with one key would drop one of them rather than draw the component twice.
    is EditorCatalogRow.Component ->
      if (pinned) "pinned:${item.componentId}" else "component:${item.componentId}"
    is EditorCatalogRow.Variant ->
      (if (pinned) "pinned-variant:" else "variant:") + "${variant.componentId}#${variant.value}"
  }

/**
 * Where a dragged layer would land, and what the panel draws to say so.
 *
 * [marker] is the row the indicator is drawn on rather than a coordinate, because the indicator has
 * to survive the list scrolling under the pointer between the frame that resolved it and the frame
 * that draws it.
 */
private data class LayerLanding(
  val target: ParentSlot,
  val afterNodeId: String?,
  val marker: LayerLandingMarker,
  val refusal: EditorMoveRefusal?,
)

private sealed interface LayerLandingMarker {
  /** Between the row above and row [index]. */
  data class Above(val index: Int) : LayerLandingMarker

  /** Between row [index] and the row below. */
  data class Below(val index: Int) : LayerLandingMarker

  /** Inside the slot row [index] names, as its first child. */
  data class Into(val index: Int) : LayerLandingMarker
}

/**
 * The place a layer released at [y] would go, or null when the pointer is over nothing that can
 * take it.
 *
 * Resolved against the rows' measured bounds rather than a row height times an index: the panel
 * mixes node lines and slot lines, and the list scrolls. A node line splits in half — the top half
 * lands the drag before it, the bottom half after it, both in *that row's* slot, which is what
 * makes a drag between slots expressible at all. A slot line lands it first in that slot, which is
 * the only way into a slot that is still empty.
 */
private fun layerLanding(
  nodeId: String,
  y: Float,
  rows: List<EditorLayerRow>,
  bounds: Map<Int, ClosedFloatingPointRange<Float>>,
  document: UiBuilderDocument,
  refusalOf: (ParentSlot) -> EditorMoveRefusal?,
): LayerLanding? {
  val index = rows.indices.firstOrNull { bounds[it]?.contains(y) == true } ?: return null
  return when (val row = rows[index]) {
    is EditorLayerRow.Slot ->
      LayerLanding(row.parent, null, LayerLandingMarker.Into(index), refusalOf(row.parent))
    is EditorLayerRow.Node -> {
      // A root has no slot to be dropped beside. Dragging one is not refused with a message,
      // because there is nothing here to say no *to* — the pointer is simply over nothing.
      val target = row.row.parent ?: return null
      if (row.nodeId == nodeId) return null
      val span = bounds.getValue(index)
      val after =
        if (y > (span.start + span.endInclusive) / 2f) row.nodeId
        else document.childrenOf(target).takeWhile { it != row.nodeId }.lastOrNull()
      LayerLanding(
        target = target,
        afterNodeId = after,
        marker =
          if (after == row.nodeId) LayerLandingMarker.Below(index)
          else LayerLandingMarker.Above(index),
        refusal = refusalOf(target),
      )
    }
  }
}

private fun UiBuilderDocument.childrenOf(parent: ParentSlot): List<String> =
  nodes[parent.nodeId]?.slots?.get(parent.slot).orEmpty()

private fun EditorLayerRow.layerKey(): String =
  when (this) {
    is EditorLayerRow.Node -> "node:$nodeId"
    is EditorLayerRow.Slot -> "slot:${parent.nodeId}.${parent.slot}"
  }

/** The colour a landing indicator is drawn in: the accent when it will land, the error when not. */
@Composable
private fun LayerLanding.markerColor(): Color =
  if (refusal == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

/**
 * The slot a group of children sits in.
 *
 * Not selectable and not draggable: a slot is not a node, it is the place one goes. It is a drop
 * target, though, and the only one an empty slot has.
 */
@Composable
private fun SlotRow(
  row: EditorLayerRow.Slot,
  isCatalogTarget: Boolean,
  landing: LayerLanding?,
  modifier: Modifier = Modifier,
) {
  val accent = landing?.markerColor()
  Row(
    modifier
      .fillMaxWidth()
      .height(26.dp)
      .then(
        if (accent != null) Modifier.background(accent.copy(alpha = 0.22f))
        else if (isCatalogTarget) Modifier.background(Color(0xff26304a)) else Modifier
      )
      .padding(start = (8 + row.indent * 12).dp, end = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      row.parent.slot,
      Modifier.weight(1f).semantics {
        contentDescription =
          "Slot ${row.parent.slot} of ${row.parent.nodeId}, ${row.childCount} of " +
            (row.maxChildren?.toString() ?: "any")
      },
      color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      // What is in the slot and what it will take, because "full" is the commonest reason a drop
      // is refused and the panel should have said so before the drop.
      when {
        row.childCount == 0 -> "empty"
        row.maxChildren != null -> "${row.childCount}/${row.maxChildren}"
        else -> row.childCount.toString()
      },
      color =
        if (row.full) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
  }
}

@Composable
private fun LayerRow(
  row: EditorTreeRow,
  indent: Int,
  selected: Boolean,
  dragged: Boolean,
  landing: LayerLanding?,
  collaborators: List<UiBuilderCollaborator>,
  selectionMenu: (() -> Unit) -> List<UiBuilderMenuEntry>,
  /** `false` for a context click, whose menu is the next interaction instead. */
  onSelect: (LayerSelectionGesture, Boolean) -> Unit,
  onDragTo: (Float) -> Unit,
  onDrop: () -> Unit,
  onDragCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Where the handle sits in the window, so the pointer's offset inside it can be turned into the
  // one coordinate the whole panel shares. A drag leaves the handle immediately, and every row it
  // then passes over reports its own bounds in that same space.
  var handleOrigin by remember { mutableStateOf(Offset.Zero) }
  // Where the right-click landed inside this row, and null while no menu is open.
  var menuAt by remember(row.nodeId) { mutableStateOf<Offset?>(null) }
  val density = LocalDensity.current
  val background =
    when {
      dragged -> Color(0xff3b4468)
      selected -> Color(0xff30385a)
      else -> Color.Transparent
    }
  val marker = landing?.markerColor()
  Row(
    modifier
      .fillMaxWidth()
      .height(34.dp)
      .background(background)
      .drawBehind {
        // Drawn as a line at the edge the layer would land on rather than as a highlight over the
        // row, because "before this one" and "after this one" are different answers and a
        // highlight cannot tell them apart.
        if (marker == null) return@drawBehind
        val above = landing.marker is LayerLandingMarker.Above
        drawRect(
          color = marker,
          topLeft = Offset(0f, if (above) 0f else size.height - 3f),
          size = Size(size.width, 3f),
        )
      }
      .onSecondaryClick(row.nodeId) { position ->
        // Selecting first, and only when it is not already part of the selection: a right-click on
        // one of six selected layers must not collapse the selection it is about to act on.
        if (!selected) onSelect(LayerSelectionGesture.Replace, false)
        menuAt = position
      }
      .padding(start = (8 + indent * 12).dp, end = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // A zero-size anchor, so the menu opens where the pointer is rather than off the row's start.
    Box {
      LocalUiBuilderChrome.current.PopupMenu(
        expanded = menuAt != null,
        onDismissRequest = { menuAt = null },
        entries = selectionMenu { menuAt = null },
        offset = DpOffset(with(density) { (menuAt?.x ?: 0f).toDp() }, 0.dp),
      )
    }
    // A 16dp icon in a 26dp target. The icon is the affordance; the box is what a pointer actually
    // has to hit, and the difference is most of why the drag read as broken.
    Box(
      Modifier.size(26.dp)
        .onGloballyPositioned { handleOrigin = it.boundsInRoot().topLeft }
        .pointerInput(row.nodeId) {
          detectDragGestures(
            onDragStart = { onDragTo(handleOrigin.y + it.y) },
            onDragEnd = onDrop,
            onDragCancel = onDragCancel,
            onDrag = { change, _ ->
              change.consume()
              onDragTo(handleOrigin.y + change.position.y)
            },
          )
        },
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.Filled.DragIndicator,
        contentDescription = "Reorder ${row.nodeId}",
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
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
              },
              true,
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
              },
              true,
            )
            true
          } else false
        }
        .focusable()
        .semantics {
          contentDescription = "Select ${row.nodeId}"
          this.selected = selected
          onClick(label = "Select") {
            onSelect(LayerSelectionGesture.Replace, true)
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
