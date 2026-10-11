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
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.wear.compose.foundation.LocalReduceMotion
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID
import ee.schimke.composeai.uibuilder.RemoteComposeSource
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderContentMissing
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderInlineDialogs
import ee.schimke.composeai.uibuilder.canvas.UiBuilderBoard
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvas.boardRootId
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderComponentPacks
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.filterRemoteComposeSources
import ee.schimke.composeai.uibuilder.humanizeSourceSlug
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The left panel's two questions: what can I add, and what is already here. "Components" is also
 * the panel's heading and the accessibility name scripts look for.
 */
internal enum class NavigatorTab(val label: String) {
  Insert("Components"),
  Layers("Layers"),
}

/** The left panel: one tab at a time, so each list gets the whole panel. */
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
  /** This design's own components, for the palette's first shelf. */
  localPalette: EditorLocalComponentPalette = EditorLocalComponentPalette.NONE,
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
   * Why an Add beside would refuse this component, or null. Shown on the component's row, unlike
   * [besideRefusal], which is the document's answer on the destination line.
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
            localPalette = localPalette,
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
 * Everything that can be put on the canvas, in one list. The catalog and the Remote Compose palette
 * share a search field and scroll, so a typed name narrows both.
 */
@Composable
private fun InsertPanel(
  state: UiBuilderEditorState,
  catalogRows: List<EditorCatalogRow>,
  /** Every component the catalog has (the All row's count), regardless of filters. */
  totalCatalogComponents: Int,
  /** The components at the top of the panel — see [UiBuilderEditorReducer.pinnedComponents]. */
  pinnedComponents: Set<String> = emptySet(),
  /** The packs the catalog carries, for the palette's own summary row. */
  packs: UiBuilderComponentPacks = UiBuilderComponentPacks.NONE,
  /** Opens the pack settings, or null where there is nothing to switch. */
  onManagePacks: (() -> Unit)? = null,
  /** The document a row's picture draws, from the reducer that would perform the insert. */
  thumbnailOf: (String, EditorCatalogVariant?) -> UiBuilderDocument?,
  localPalette: EditorLocalComponentPalette = EditorLocalComponentPalette.NONE,
  dropTarget: ParentSlot?,
  /** What [dropTarget] is called out loud — a layer's name and its slot, not an id. */
  dropTargetLabel: String? = null,
  onCatalogDrag: (String, EditorCatalogVariant?, Offset?) -> Unit,
  onCatalogDrop: (String, EditorCatalogVariant?, Offset) -> Unit,
  canAddCatalogComponent: (String) -> Boolean,
  /** Why an Add beside would refuse this component, or null; see the overload above. */
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
    // Where an Add (or Add beside) would land, said before it is pressed: a board says how many
    // items it holds, and a design that must be wrapped says so.
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
      // The design's own components first: they are what this screen is made of, and the reason
      // to make one was to place it again.
      val local =
        localPalette.components.filter {
          state.catalogQuery.isBlank() || it.name.contains(state.catalogQuery, ignoreCase = true)
        }
      if (local.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          PanelHeading(
            "This design",
            "${local.size} ${if (local.size == 1) "component" else "components"}",
          )
        }
        gridItems(local, key = { "local:${it.key}" }) { component ->
          LocalComponentTile(
            component = component,
            thumbnail = localPalette.preview(component.key),
            target = localPalette.target(component.key),
            onAdd = { target ->
              dispatch(UiBuilderEditorEvent.InsertLocalComponent(component.key, target))
            },
          )
        }
      }
      // Then what the project shares: imported on a press, with the source a later read checks
      // for drift.
      val shared =
        localPalette.library.filter {
          state.catalogQuery.isBlank() || it.title.contains(state.catalogQuery, ignoreCase = true)
        }
      val addShared = localPalette.onAddLibrary
      if (shared.isNotEmpty() && addShared != null) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          PanelHeading(
            "Project library",
            "${shared.size} shared ${if (shared.size == 1) "component" else "components"}",
          )
        }
        gridItems(shared, key = { "library:${it.system}/${it.componentId}" }) { component ->
          LibraryComponentTile(component) { addShared(component) }
        }
      }
      gridItems(
        catalogRows,
        key = EditorCatalogRow::catalogRowKey,
        span = { row ->
          if (row is EditorCatalogRow.Group) GridItemSpan(maxLineSpan) else GridItemSpan(1)
        },
        // A heading scrolled off is reused for a heading and a tile for a tile, rather than a
        // thumbnail's whole rendered subtree being torn down to build a one-line heading.
        contentType = { row -> row::class },
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
  // Each layer row's vertical root-pixel range. A plain map, not snapshot state, since it is
  // written every scroll and only read by the drag callback.
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
              // Every selected node is highlighted, not just the anchor.
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
                  // Sent even when it will be refused, so the reducer reports why.
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
 * The inspector mode a dock stands for, or null. One way on purpose: the mode is document state
 * that travels to collaborators; the open dock is not.
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
 * The strip of panel switches flanking the canvas: each icon toggles its panel, giving the space
 * back to the design when closed.
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
 * A plain heading over a reducer-defined group (the Remote Compose palette's source groups). Not
 * collapsible: the groups are short.
 */
@Composable
private fun GroupHeading(group: String) {
  LocalUiBuilderChrome.current.GroupHeading(humanizeSourceSlug(group).uppercase())
}

/**
 * One published Remote Compose document, addable or draggable into a compatible slot. The thumbnail
 * is the grip; a drop captures the slot before fetching, and the reducer revalidates it afterwards.
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
  CatalogDragTile(dragKey = item.componentId, onDrag = onDrag, onDrop = onDrop) {
    LocalUiBuilderChrome.current.ComponentBrowserTile(
      model =
        UiBuilderCatalogTileModel(
          title = item.displayName,
          // The id is what the Layers panel and the export call it, which is not what somebody
          // choosing a component is looking for; only a refusal earns the line under the name.
          supporting = refusal,
          supportingIsError = refusal != null,
          unexportable = unexportable,
          pinned = pinned,
          variantCount = item.variants.size,
          variantsExpanded = expanded,
          canAdd = canAdd,
          addContentDescription =
            if (refusal == null) "Add ${item.displayName}"
            else "Add ${item.displayName} — $refusal",
          onAdd = onAdd,
          onTogglePinned = onTogglePinned,
          onToggleVariants = onToggleVariants,
        )
    ) {
      CatalogThumbnail(
        document = thumbnail,
        componentId = item.componentId,
        label = item.displayName,
        size = DpSize(104.dp, 72.dp),
        container = item.kind == EditorComponentKind.Container,
      )
    }
  }
}

/** What the palette needs to offer a design's own components, from the reducer. */
data class EditorLocalComponentPalette(
  val components: List<EditorLocalComponent>,
  /** The document a component's tile draws: one placement of it, passing what the last one did. */
  val preview: (String) -> UiBuilderDocument?,
  /** Where an Add of a component would land, or null where nothing selected can hold it. */
  val target: (String) -> ParentSlot?,
  /** What the project's shared library publishes, for the shelf after the design's own. */
  val library: List<EditorLibraryComponent> = emptyList(),
  /** Fetches and places one library component; null where this host serves no library. */
  val onAddLibrary: ((EditorLibraryComponent) -> Unit)? = null,
) {
  companion object {
    val NONE = EditorLocalComponentPalette(emptyList(), { null }, { null })
  }
}

/** One component the project publishes, added — imported, with its source — on a press. */
@Composable
private fun LibraryComponentTile(component: EditorLibraryComponent, onAdd: () -> Unit) {
  LocalUiBuilderChrome.current.ComponentBrowserTile(
    model =
      UiBuilderCatalogTileModel(
        title = component.title,
        supporting = component.description ?: component.paletteId,
        supportingIsError = false,
        unexportable = false,
        pinned = false,
        variantCount = 0,
        variantsExpanded = false,
        canAdd = true,
        addContentDescription = "Add ${component.title} from the project library",
        onAdd = onAdd,
        onTogglePinned = {},
        onToggleVariants = {},
      )
  ) {
    CatalogThumbnail(
      document = null,
      componentId = COMPONENT_INSTANCE_ID,
      label = component.title,
      size = DpSize(104.dp, 72.dp),
      container = true,
    )
  }
}

/** One of the design's own components on the palette, added where the selection can hold it. */
@Composable
private fun LocalComponentTile(
  component: EditorLocalComponent,
  thumbnail: UiBuilderDocument?,
  target: ParentSlot?,
  onAdd: (ParentSlot) -> Unit,
) {
  val refusal = if (target == null) "Select a layer that can hold it" else null
  LocalUiBuilderChrome.current.ComponentBrowserTile(
    model =
      UiBuilderCatalogTileModel(
        title = component.name,
        // Placements and parameters are what tell two of a design's components apart at a glance;
        // a refusal takes the line when there is one.
        supporting =
          refusal
            ?: component.publishedAs?.let { "Published as ${it.displayName}" }
            ?: "${component.placements} placed · ${component.parameters.size} parameters",
        supportingIsError = refusal != null,
        unexportable = false,
        pinned = false,
        variantCount = 0,
        variantsExpanded = false,
        canAdd = target != null,
        addContentDescription =
          if (refusal == null) "Add ${component.name}" else "Add ${component.name} — $refusal",
        onAdd = { target?.let(onAdd) },
        onTogglePinned = {},
        onToggleVariants = {},
      )
  ) {
    CatalogThumbnail(
      document = thumbnail,
      componentId = component.rootComponentId ?: COMPONENT_INSTANCE_ID,
      label = component.name,
      size = DpSize(104.dp, 72.dp),
      container = true,
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
  CatalogDragTile(
    dragKey = "${variant.componentId}#${variant.value}",
    onDrag = onDrag,
    onDrop = onDrop,
  ) {
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
        label = qualified,
        size = DpSize(96.dp, 64.dp),
        // A variant is a property value of its component, and the list does not carry the
        // component's kind down to it; an empty variant keeps the container sketch it always had.
        container = true,
      )
    }
  }
}

/**
 * A palette tile that is its own grip: press anywhere on it and drag. Its buttons still win presses
 * until the drag passes the slop. While carried, the tile stays in place, faded.
 */
@Composable
private fun CatalogDragTile(
  dragKey: String,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
  content: @Composable () -> Unit,
) {
  var carried by remember(dragKey) { mutableStateOf(false) }
  Box(
    Modifier.catalogDrag(
        dragKey = dragKey,
        onDrag = { position ->
          carried = position != null
          onDrag(position)
        },
        onDrop = { position ->
          carried = false
          onDrop(position)
        },
      )
      .graphicsLayer { alpha = if (carried) 0.35f else 1f }
  ) {
    content()
  }
}

/**
 * A palette row's picture: the component itself, inserted into an empty frame and shrunk.
 *
 * **Rendered, not baked**: draws [UiBuilderEditorReducer.previewDocument], produced by the same
 * `InsertComponent` the row's Add dispatches, so it cannot disagree with Add and needs no committed
 * PNGs.
 *
 * Laid out at [PREVIEW_FRAME_WIDTH_DP] and shrunk with `graphicsLayer` (a draw-time transform),
 * never magnified past its own size. **Unframed**: only the component's own box is drawn (with room
 * for its shadow), so the list reads as the parts themselves. The drag belongs to the tile
 * ([CatalogDragTile]).
 */
@Composable
internal fun CatalogThumbnail(
  document: UiBuilderDocument?,
  componentId: String,
  label: String,
  size: DpSize,
  /**
   * Whether the component holds children. Only then is an empty measurement drawn as a container
   * schematic; otherwise it has no picture.
   */
  container: Boolean,
  /** Told what the thumbnail ended up drawing. For tests that sweep a whole catalog. */
  onOutcome: ((CatalogThumbnailOutcome) -> Unit)? = null,
) {
  // A component the frame could not hold, a root-only scaffold, has no picture. Better absent than
  // faked, and the same absence every other pictureless tile shows.
  if (document == null) {
    onOutcome?.let { report -> SideEffect { report(CatalogThumbnailOutcome.NoDocument) } }
    NoPictureThumbnail(label, size)
    return
  }
  // Set when the component draws its "content needed" stand-in (empty document error, "add a URL"):
  // useful on the canvas, misleading on a shelf.
  var needsContent by remember(componentId) { mutableStateOf(false) }
  if (needsContent) {
    onOutcome?.let { report -> SideEffect { report(CatalogThumbnailOutcome.NeedsContent) } }
    NoPictureThumbnail(label, size)
    return
  }
  val density = LocalDensity.current
  // The panel's brightness, not the catalog's pinned dark: a component drawn in the opposite
  // theme to the list it sits on is a dark block on a light panel — a frame by another name.
  val panelDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  // Whether the component filled the default frame and is redrawn in a roomy one; see
  // [PREVIEW_ROOMY_FRAME]. Saveable so a tile scrolled back does not jump.
  var roomy by rememberSaveable(componentId, label) { mutableStateOf(false) }
  val wear = LocalUiBuilderCatalogPlatform.current == UiBuilderCatalogPlatform.WEAR.wireValue
  val roomyFrame = if (wear) PREVIEW_ROOMY_WEAR_FRAME else PREVIEW_ROOMY_FRAME
  val frame = if (roomy) roomyFrame else PreviewFrameSize.Default
  val themed =
    remember(document, panelDark, frame) {
      document
        .copy(
          environment =
            JsonObject(
              document.environment + ("theme" to JsonPrimitive(if (panelDark) "dark" else "light"))
            )
        )
        .let { if (frame == PreviewFrameSize.Default) it else it.inPreviewFrame(frame) }
    }
  // A miniature gets a square tile rather than the landscape one: what needs the roomy frame is a
  // picker or a dialog, taller than it is wide, and in a 72 dp high tile it would be a smudge.
  val tile = if (roomy) DpSize(size.width, size.width) else size
  // The whole frame inside the tile until measured, so the roomy-frame check is not measured
  // clipped.
  val fallbackScale = minOf(tile.width.value / frame.widthDp, tile.height.value / frame.heightDp)
  val fullFrame =
    with(density) { Size(frame.widthDp.dp.toPx(), frame.heightDp.dp.toPx()) } * fallbackScale
  var contentBounds by remember(document.id, frame) { mutableStateOf<UiBuilderPixelBounds?>(null) }
  // Measured with no size (an empty Box/Column/Row lays out at 0x0).
  var drewNothing by remember(document.id) { mutableStateOf(false) }
  val tileSize = with(density) { Size(tile.width.toPx(), tile.height.toPx()) }
  // A Wear miniature is the whole watch: the frame drawn whole on black, cut to the circle, since
  // Wear screens are designed against a black round face.
  val watchFace = roomy && wear
  val transform =
    if (watchFace) {
      ThumbnailContentTransform(
        fallbackScale,
        Offset(
          (tileSize.width - fullFrame.width) / 2f,
          (tileSize.height - fullFrame.height) / 2f,
        ),
      )
    } else {
      thumbnailContentTransform(
        contentBounds = contentBounds,
        tileSize = tileSize,
        fallbackScale = fallbackScale,
        margin = with(density) { THUMBNAIL_MARGIN.toPx() },
        maxScale = 1f,
      )
    }
  // The component's own box in the tile, plus room for a shadow: what is kept of the frame.
  val shadowRoom = with(density) { THUMBNAIL_SHADOW_ROOM.toPx() }
  val visible =
    if (watchFace && contentBounds != null) {
      Rect(transform.translation, fullFrame)
    } else {
      contentBounds?.let { bounds ->
        Rect(
          left = bounds.x * transform.scale + transform.translation.x - shadowRoom,
          top = bounds.y * transform.scale + transform.translation.y - shadowRoom,
          right = bounds.right * transform.scale + transform.translation.x + shadowRoom,
          bottom = bounds.bottom * transform.scale + transform.translation.y + shadowRoom,
        )
      }
    }
  Box(Modifier.size(tile), contentAlignment = Alignment.TopStart) {
    Box(
      Modifier.matchParentSize().drawWithContent {
        // Nothing until measured once.
        val keep = visible ?: return@drawWithContent
        clipRect(keep.left, keep.top, keep.right, keep.bottom) {
          this@drawWithContent.drawContent()
        }
      }
    ) {
      Box(
        // Pinned top-start before sizing: an oversize `requiredSize` is centred by default, which
        // the transform below does not expect.
        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
          .requiredSize(frame.widthDp.dp, frame.heightDp.dp)
          .graphicsLayer {
            // Top-start origin so the post-transform inspection can be mapped back to source-frame
            // bounds.
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = transform.scale
            scaleY = transform.scale
            translationX = transform.translation.x
            translationY = transform.translation.y
          }
          .then(if (watchFace) Modifier.clip(CircleShape).background(Color.Black) else Modifier)
          // A picture of a component is not the component: clear its semantics so screen readers
          // and role queries ignore it. The row's own name is set on the overlay below.
          .clearAndSetSemantics {}
      ) {
        val inspection: (UiBuilderInspectionSnapshot) -> Unit = { snapshot ->
          // Asked of the first snapshot, laid out whole under the fallback transform.
          if (!roomy && contentBounds == null && thumbnailNeedsRoomyFrame(snapshot, fullFrame)) {
            roomy = true
          }
          val next = thumbnailContentBounds(snapshot, transform.scale)
          // Bounds read back are a fraction off each pass; only a real change moves them, avoiding
          // a re-layout loop.
          if (!sameThumbnailBounds(next, contentBounds)) contentBounds = next
          val empty =
            next == null &&
              snapshot.nodes.any { it.nodeId == PREVIEW_FRAME_CELL_ID && it.bounds != null }
          if (empty != drewNothing) drewNothing = empty
        }
        // Always in-process: the catalog's pinned runtime is a sandboxed Wasm iframe, far too heavy
        // per tile. Reduce motion so Wear components draw their settled state rather than animating
        // (not `MotionDurationScale`, which tiles share with the editor).
        CompositionLocalProvider(
          LocalUiBuilderContentMissing provides { needsContent = true },
          LocalReduceMotion provides true,
          // A Dialog drawn for real opens a window: one per palette tile, floating over the whole
          // editor with its scrim. Its stand-in draws the same surface inside the tile.
          LocalUiBuilderInlineDialogs provides true,
        ) {
          UiBuilderSurface(
            document = themed,
            editorOverlay = false,
            onInspectionSnapshot = inspection,
          )
        }
      }
    }
    onOutcome?.let { report ->
      SideEffect {
        report(
          when {
            drewNothing -> CatalogThumbnailOutcome.DrewNothing
            roomy -> CatalogThumbnailOutcome.Miniature
            else -> CatalogThumbnailOutcome.Drawn
          }
        )
      }
    }
    if (drewNothing) {
      if (container) {
        EmptyContainerSchematic(emptyContainerSchematic(componentId), Modifier.matchParentSize())
      } else {
        NoPictureGlyph(Modifier.matchParentSize())
      }
    }
    // On top of the picture: a real Switch in a thumbnail would take the press. This sees every
    // press first, consumes none, and the tile's drag gets them.
    Box(
      Modifier.matchParentSize()
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
        .semantics { contentDescription = "Drag $label" }
    )
  }
}

/** What a [CatalogThumbnail] drew, as its latest composition saw it. */
internal enum class CatalogThumbnailOutcome {
  /** The component, in the default frame. */
  Drawn,
  /** The component filled the default frame, so it is drawn in the roomy one and scaled down. */
  Miniature,
  /** Laid out, and nothing in it had a size: an empty container. */
  DrewNothing,
  /** It drew its "fill me in" stand-in, so the tile shows no picture. */
  NeedsContent,
  /** No thumbnail document could be built: a root-only component the frame cannot hold. */
  NoDocument,
}

/**
 * Whether a component measured in the default frame was squeezed and needs the roomy one: filling
 * the frame's height is the sign; filling only the width is normal and escalates only when also
 * most of the frame tall.
 */
internal fun thumbnailNeedsRoomyFrame(
  snapshot: UiBuilderInspectionSnapshot,
  /** The frame cell's size in the snapshot when nothing clips it: the frame at the tile's scale. */
  fullFrame: Size,
): Boolean {
  // Fractions of the frame cell from the same snapshot, and only for a whole cell: bounds are
  // clipped to the screen, so a half-scrolled tile would look full.
  val frame =
    snapshot.nodes.singleOrNull { it.nodeId == PREVIEW_FRAME_CELL_ID }?.bounds ?: return false
  if (abs(frame.width - fullFrame.width) > 1f || abs(frame.height - fullFrame.height) > 1f) {
    return false
  }
  val content =
    snapshot.nodes
      .filter { it.nodeId != PREVIEW_FRAME_CELL_ID }
      .mapNotNull { it.bounds }
      .filter { it.width > 0f && it.height > 0f }
  if (content.isEmpty()) return false
  val width = (content.maxOf { it.right } - content.minOf { it.x }) / frame.width
  val height = (content.maxOf { it.bottom } - content.minOf { it.y }) / frame.height
  val fillsWidth = width >= 0.99f
  val fillsHeight = height >= 0.99f
  return fillsHeight || (fillsWidth && height >= 0.75f)
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

/**
 * How far past its own box a thumbnail is drawn: enough for a shadow, not enough to read as a tile.
 */
private val THUMBNAIL_SHADOW_ROOM = 4.dp

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
 * Which sketch an empty container draws, read from its id (shared across catalogs). Order matters:
 * `flow-column` is a column, `lazy-grid` a grid.
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
 * A container with nothing in it, drawn as the arrangement it would give its children: a dashed
 * outline and solid placeholder blocks in the outline colour.
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
 * Fit the component's source-frame bounds into a palette tile without magnifying it. Bounds are
 * source pixels, so [translation] is a graphics-layer pixel value too.
 */
internal fun thumbnailContentTransform(
  contentBounds: UiBuilderPixelBounds?,
  tileSize: Size,
  fallbackScale: Float,
  /**
   * Clearance on every side, in tile pixels, so a pill-shaped component does not read as the tile's
   * own shape.
   */
  margin: Float = 0f,
  /** The largest magnification allowed — see [CatalogThumbnail] for why the palette uses 1. */
  maxScale: Float = 2f,
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
  val scale =
    minOf(usableWidth / contentBounds.width, usableHeight / contentBounds.height, maxScale)
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
 * Convert the inspection collector's post-transform root pixels back into source-frame pixels,
 * using the frame entry as origin so scrolling and placement do not matter.
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
  // Clipped to the frame: a lazy row's next item laid out past the edge would otherwise shrink the
  // whole row.
  val frameWidth = frame.width / scale
  val frameHeight = frame.height / scale
  val left = componentBounds.minOf { (it.x - frame.x) / scale }.coerceIn(0f, frameWidth)
  val top = componentBounds.minOf { (it.y - frame.y) / scale }.coerceIn(0f, frameHeight)
  val right = componentBounds.maxOf { (it.right - frame.x) / scale }.coerceIn(0f, frameWidth)
  val bottom = componentBounds.maxOf { (it.bottom - frame.y) / scale }.coerceIn(0f, frameHeight)
  if (right <= left || bottom <= top) return null
  return UiBuilderPixelBounds(left, top, right - left, bottom - top)
}

/**
 * A palette tile with no picture: a quiet glyph at picture size so the grid does not jump, with the
 * name under it. One look for every reason there is no picture.
 */
@Composable
private fun NoPictureThumbnail(label: String, size: DpSize) {
  Box(Modifier.size(size).semantics { contentDescription = "Drag $label" }) {
    NoPictureGlyph(Modifier.matchParentSize())
  }
}

@Composable
private fun NoPictureGlyph(modifier: Modifier) {
  Box(modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
    Icon(
      Icons.Filled.Widgets,
      contentDescription = null,
      modifier = Modifier.size(28.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
    )
  }
}

/**
 * The drag a palette row starts: report the pointer and on release drop there or withdraw. A
 * modifier so catalog tiles and Remote Compose rows share one implementation.
 *
 * A mouse drag picks up in any direction. A touch picks up on a sideways move or after a long
 * press, leaving vertical swipes to scroll the list. The origin is the element's unclipped root
 * position, so half-scrolled tiles drop at the pointer.
 */
private fun Modifier.catalogDrag(
  dragKey: String,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
): Modifier = composed {
  var dragOrigin by remember { mutableStateOf(Offset.Zero) }
  val currentOnDrag = rememberUpdatedState(onDrag)
  val currentOnDrop = rememberUpdatedState(onDrop)
  Modifier.onGloballyPositioned { dragOrigin = it.positionInRoot() }
    .pointerInput(dragKey) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = awaitCatalogPickUp(down, viewConfiguration.longPressTimeoutMillis)
        if (start == null) return@awaitEachGesture
        var lastPosition = dragOrigin + start.position
        var travelled = 0f
        currentOnDrag.value(lastPosition)
        val landed =
          try {
            drag(start.id) { change ->
              change.consume()
              travelled += (change.position - change.previousPosition).getDistance()
              lastPosition = dragOrigin + change.position
              currentOnDrag.value(lastPosition)
            }
          } catch (cancelled: CancellationException) {
            currentOnDrag.value(null)
            throw cancelled
          }
        // Below the threshold it was a press, not a drag, so the insert is withdrawn rather than
        // landed wherever the pointer happened to rest — on a phone that is on top of the canvas.
        if (landed && travelled > CATALOG_DROP_THRESHOLD_PX) currentOnDrop.value(lastPosition)
        else currentOnDrag.value(null)
      }
    }
}

/** How far a carried component has to travel before its release is a drop. */
private const val CATALOG_DROP_THRESHOLD_PX = 8f

/**
 * Wait for [down] to become a pick-up per [catalogDrag]'s rules and return the starting change, or
 * null for a tap, scroll or nothing.
 */
private suspend fun AwaitPointerEventScope.awaitCatalogPickUp(
  down: PointerInputChange,
  longPressMillis: Long,
): PointerInputChange? {
  if (down.type != PointerType.Touch) {
    // A mouse or a stylus: the first move past the slop, any direction.
    return awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
  }
  var sideways: PointerInputChange? = null
  val held =
    withTimeoutOrNull(longPressMillis) {
      // Sideways is accepted; up or down is left unconsumed, so the list takes it and this ends.
      sideways =
        awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
          if (abs(overSlop.x) >= abs(overSlop.y)) change.consume()
        }
      false
    } ?: true
  if (!held) return sideways
  // Held without moving: picked up where the finger is, and carried from the next move on.
  val current = currentEvent.changes.firstOrNull { it.id == down.id } ?: down
  return if (current.pressed) current else null
}

/**
 * A stable `LazyColumn` key per row. The row kind is part of it, since groups, components and
 * variants can share names or ids.
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
 * Where a dragged layer would land and how the panel shows it. [marker] is a row, not a coordinate,
 * so it survives scrolling between resolve and draw.
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
 * Where a layer released at [y] would go, or null. Resolved against measured row bounds (mixed node
 * and slot lines, scrolling). A node line's top half lands before it, bottom half after, in that
 * row's slot; a slot line lands first in that slot, the only way into an empty slot.
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
 * The slot a group of children sits in: not selectable or draggable, but a drop target (the only
 * one an empty slot has).
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
        else if (isCatalogTarget)
          Modifier.background(LocalUiBuilderEditorPalette.current.dropTarget)
        else Modifier
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
      // Show contents and capacity, since "full" is the commonest refusal.
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
  // The handle's window position, converting pointer offsets into the panel-wide coordinate space
  // the rows report in.
  var handleOrigin by remember { mutableStateOf(Offset.Zero) }
  // Where the right-click landed inside this row, and null while no menu is open.
  var menuAt by remember(row.nodeId) { mutableStateOf<Offset?>(null) }
  // Kept through the close animation, so the menu leaves from where it was rather than from x=0.
  var menuShownAt by remember(row.nodeId) { mutableStateOf(Offset.Zero) }
  val density = LocalDensity.current
  val background =
    when {
      dragged -> LocalUiBuilderEditorPalette.current.layerDragged
      selected -> LocalUiBuilderEditorPalette.current.layerSelected
      else -> Color.Transparent
    }
  val marker = landing?.markerColor()
  Row(
    modifier
      .fillMaxWidth()
      .height(34.dp)
      .background(background)
      .drawBehind {
        // A line at the landing edge rather than a highlight, since before and after are different
        // answers.
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
        menuShownAt = position
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
        offset = DpOffset(with(density) { menuShownAt.x.toDp() }, 0.dp),
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
        // Not `clickable`: it cannot see modifier keys (ctrl/⌘/shift-click). Selects on release,
        // not press, so scrolling on touch does not select; `waitForUpOrCancellation` returns null
        // when an ancestor claims the gesture.
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
        // Restores what dropping `clickable` lost: focusability, key activation (Enter/Space) and
        // the accessibility action.
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
