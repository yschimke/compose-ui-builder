package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.AcceptedCommand
import ee.schimke.composeai.uibuilder.CapabilityDocumentWriteValidator
import ee.schimke.composeai.uibuilder.CapabilityPropertyWriteValidator
import ee.schimke.composeai.uibuilder.CollaborationReducer
import ee.schimke.composeai.uibuilder.CollaborationState
import ee.schimke.composeai.uibuilder.CommandApplication
import ee.schimke.composeai.uibuilder.CommandOutcome
import ee.schimke.composeai.uibuilder.ComponentDriftState
import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID
import ee.schimke.composeai.uibuilder.RedoCommand
import ee.schimke.composeai.uibuilder.RejectionCode
import ee.schimke.composeai.uibuilder.RemoteComposeSource
import ee.schimke.composeai.uibuilder.ResolvedUiBuilderAsset
import ee.schimke.composeai.uibuilder.UndoCommand
import ee.schimke.composeai.uibuilder.canvas.UiBuilderBoard
import ee.schimke.composeai.uibuilder.canvas.boardRootId
import ee.schimke.composeai.uibuilder.canvas.decodeRemoteComposeDocument
import ee.schimke.composeai.uibuilder.canvas.selectionChain
import ee.schimke.composeai.uibuilder.canvas.topLevelNodes
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.capability.PropertyEditorControl
import ee.schimke.composeai.uibuilder.capability.SlotCapability
import ee.schimke.composeai.uibuilder.capability.accepts
import ee.schimke.composeai.uibuilder.client.toProtocolDocument
import ee.schimke.composeai.uibuilder.codegen.COMPOSE_EMITTED_CLICK_COMPONENTS
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.codegen.ComposeExportSeverity
import ee.schimke.composeai.uibuilder.codegen.bindableProperties
import ee.schimke.composeai.uibuilder.codegen.bindableTextProperties
import ee.schimke.composeai.uibuilder.componentRootOf
import ee.schimke.composeai.uibuilder.detachedComponentRoots
import ee.schimke.composeai.uibuilder.embeddedComponentRecord
import ee.schimke.composeai.uibuilder.export.A2uiDocumentExporter
import ee.schimke.composeai.uibuilder.export.InlineRemoteContentExporter
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_INLINE_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.RootSurfaceGround
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.ThemeTextStyle
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import ee.schimke.composeai.uibuilder.export.TwoWayStateBinding
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiDrawing
import ee.schimke.composeai.uibuilder.export.UiExpressions
import ee.schimke.composeai.uibuilder.export.WidgetAssetBytes
import ee.schimke.composeai.uibuilder.export.isWearScreen
import ee.schimke.composeai.uibuilder.export.isWearWidget
import ee.schimke.composeai.uibuilder.exportRecord
import ee.schimke.composeai.uibuilder.frameGeometry
import ee.schimke.composeai.uibuilder.packComponentsById
import ee.schimke.composeai.uibuilder.reference.PLACED_PIECE_WIDTH_FRACTION
import ee.schimke.composeai.uibuilder.reference.REFERENCE_FONT_SIZE_PROPERTY
import ee.schimke.composeai.uibuilder.reference.ReferenceFit
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import ee.schimke.composeai.uibuilder.reference.ReferenceMark
import ee.schimke.composeai.uibuilder.reference.ReferenceMarkupKind
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlaySettings
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.reference.ReferencePiece
import ee.schimke.composeai.uibuilder.reference.ReferenceTool
import ee.schimke.composeai.uibuilder.reference.extractSvgLayoutBoxes
import ee.schimke.composeai.uibuilder.reference.movedModifierChain
import ee.schimke.composeai.uibuilder.reference.svgTextOrNull
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSlotInspection
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import ee.schimke.composeai.uibuilder.resolveAsset
import ee.schimke.composeai.uibuilder.stillDescribing
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Max markup label length: a sentence, short enough to fit the box it was dragged out for. */
internal const val MAX_MARKUP_TEXT: Int = 160

/** A target slot in the shape the acceptance check reads, so one function answers both callers. */
private fun ParentSlot.asInspectionSlot(): UiBuilderSlotInspection =
  UiBuilderSlotInspection(
    parentNodeId = nodeId,
    slotName = slot,
    childNodeIds = emptyList(),
    measuredChildNodeIds = emptyList(),
  )

class UiBuilderEditorReducer(
  private val catalog: CapabilityCatalog,
  private val actorId: String = EDITOR_ACTOR_ID,
  private val clientId: String = EDITOR_CLIENT_ID,
  private val operationIdPrefix: String = clientId,
  /**
   * This catalog's component record as served by its host, or null for the embedded `m3-catalog`
   * record.
   */
  private val catalogRecord: ComponentRecordFile? = null,
) {
  private val capabilityValidator = CapabilityValidator(catalog)

  /**
   * The record the code pane and problems panel generate from: [catalogRecord] (which replaces, not
   * joins, the embedded one) plus pack components, so the browser never resolves an id the export
   * refuses.
   */
  private val exportRecord by lazy {
    catalog.exportRecord(catalogRecord ?: embeddedComponentRecord())
  }

  /** Pack components a record-free (Wear screen) design may hold; see [packComponentsById]. */
  private val packComponents by lazy { catalog.packComponentsById() }

  /**
   * Catalog ids the Compose export can write a call site for, judged by [exportRecord] as
   * `ScreenGenerator` does; null where this cannot be said (e.g. `wear-m3`/`remote-m3`, which
   * export through `RecordFreeExport`).
   */
  private val composeExportCoverage: Set<String>? by lazy {
    val record = exportRecord ?: return@lazy null
    if (catalogRecord == null && record.module != catalog.benchmark.catalogSystemId) {
      return@lazy null
    }
    record.components.filter { it.code?.call != null }.flatMapTo(mutableSetOf()) { it.componentIds }
  }

  private val validator = CapabilityPropertyWriteValidator(capabilityValidator)
  private val documentValidator = CapabilityDocumentWriteValidator(capabilityValidator)

  fun initial(document: UiBuilderDocument, selectedNodeId: String? = null): UiBuilderEditorState =
    UiBuilderEditorState(
      collaboration = CollaborationState(document),
      selection = listOfNotNull(selectedNodeId?.takeIf(document.nodes::containsKey)),
      platform = catalog.platform,
      catalogFrameSizes = catalog.frameGeometry.sizes,
    )

  /**
   * This editor's state carried onto a new authoritative document. Selection and clipboard survive,
   * minus nodes the new document no longer holds.
   */
  fun reconciled(
    state: UiBuilderEditorState,
    document: UiBuilderDocument,
    fallbackSelectedNodeId: String? = null,
  ): UiBuilderEditorState {
    val rebuilt =
      initial(
        document,
        selectedNodeId =
          state.selectedNodeId?.takeIf(document.nodes::containsKey)
            ?: fallbackSelectedNodeId?.takeIf(document.nodes::containsKey)
            ?: document.roots.firstOrNull(),
      )
    val survivingSelection = state.selection.filter(document.nodes::containsKey)
    return rebuilt.copy(
      selection = survivingSelection.ifEmpty { rebuilt.selection },
      clipboard = state.clipboard,
      catalogQuery = state.catalogQuery,
      collapsedCatalogGroups = state.collapsedCatalogGroups,
      expandedCatalogComponents = state.expandedCatalogComponents,
      enabledPacks = state.enabledPacks,
      // A reader's pins are a fact about their palette, not about the design arriving, so they
      // survive a rebuild for the same reason the pack switches do.
      pinnedComponents = state.pinnedComponents,
      // Carried: an insert is answered by an authoritative document that would otherwise empty it.
      recentComponents = state.recentComponents,
      // Drop only rows this document has answered: every edit rebuilds the state, so dropping all
      // would lose drift rows, and keeping all would report drift the edit just fixed.
      componentDrift = state.componentDrift.stillDescribing(document),
      layerQuery = state.layerQuery,
      codePaneVisible = state.codePaneVisible,
      // Only while it is still about the same node: a collaborator who deletes the selection moves
      // it to a fallback, and the card must not follow onto a node nobody asked to edit.
      quickEditorOpen = state.quickEditorOpen && rebuilt.selectedNodeId == state.selectedNodeId,
      // The strip survives, but not its peek: the rebuilt collaboration state cannot redraw the old
      // revision.
      historyBarVisible = state.historyBarVisible,
      panes = state.panes,
      wearWidgetHostShape = state.wearWidgetHostShape,
      operationSequence = state.operationSequence,
      inspectorMode = state.inspectorMode,
      // Tool modes survive like selection and clipboard: every edit and collaborator delta rebuilds
      // the editor, so anything not carried is lost on the next keystroke.
      addBeside = state.addBeside,
      variantAxes = state.variantAxes,
      canvasView = state.canvasView,
      // Tool state like the axes above, and only for the same design: a node id in another design
      // is a different node that happens to share a name.
      tunables =
        if (document.id == state.document.id)
          state.tunables.resolvedIn(document).withTokenTargets(document, catalog)
        else emptyList(),
      tunedValues = if (document.id == state.document.id) state.tunedValues else emptyMap(),
    )
  }

  /**
   * The state after [event]. Any document-changing event also ends a revision peek, so edits never
   * land invisibly behind an old revision.
   */
  fun reduce(state: UiBuilderEditorState, event: UiBuilderEditorEvent): UiBuilderEditorState {
    val reduced = reduceEvent(state, event)
    // The quick editor is about one node; a new selection closes it rather than moving it.
    val next =
      recordRecentComponent(
        state,
        event,
        if (reduced.quickEditorOpen && reduced.selectedNodeId != state.selectedNodeId) {
          reduced.copy(quickEditorOpen = false)
        } else {
          reduced
        },
      )
    val moved = next.document.revision != state.document.revision
    val peeked =
      if (moved && next.revisionPeek != null) next.copy(revisionPeek = null, revisionCompare = null)
      else next
    // A token's slider covers every node of the components it binds, including one this event
    // just inserted; see [withTokenTargets].
    return if (peeked.document === state.document) peeked
    else peeked.copy(tunables = peeked.tunables.withTokenTargets(peeked.document, catalog))
  }

  /**
   * [next] with the component [event] inserted moved to the front of the "Recently used" shelf,
   * when it was a catalog insert the document accepted. A refused insert placed nothing, so it is
   * not something the reader was using; the sequence check keeps a stale Accepted from an earlier
   * command from counting. Every catalog route — Add, Add beside, a drop on the canvas — arrives as
   * one of these three events, so recording here rather than at each call site cannot miss one.
   */
  private fun recordRecentComponent(
    state: UiBuilderEditorState,
    event: UiBuilderEditorEvent,
    next: UiBuilderEditorState,
  ): UiBuilderEditorState {
    val componentId =
      when (event) {
        is UiBuilderEditorEvent.InsertComponent -> event.componentId
        is UiBuilderEditorEvent.InsertComponentBeside -> event.componentId
        is UiBuilderEditorEvent.InsertComponentWithAction -> event.componentId
        else -> return next
      }
    val accepted =
      next.operationSequence != state.operationSequence &&
        next.lastOutcome is CommandOutcome.Accepted
    return if (accepted) {
      next.copy(recentComponents = next.recentComponents.withRecentComponent(componentId))
    } else {
      next
    }
  }

  private fun reduceEvent(
    state: UiBuilderEditorState,
    event: UiBuilderEditorEvent,
  ): UiBuilderEditorState =
    when (event) {
      is UiBuilderEditorEvent.SearchCatalog -> state.copy(catalogQuery = event.query)
      is UiBuilderEditorEvent.ToggleCatalogGroup ->
        state.copy(collapsedCatalogGroups = state.collapsedCatalogGroups.toggled(event.group))
      is UiBuilderEditorEvent.ExpandAllCatalogGroups ->
        state.copy(collapsedCatalogGroups = emptySet())
      is UiBuilderEditorEvent.TogglePack ->
        if (catalog.componentPacks[event.packId] == null) state
        else state.copy(enabledPacks = state.enabledPacks.toggled(event.packId))
      is UiBuilderEditorEvent.SetEnabledPacks ->
        state.copy(
          enabledPacks =
            event.packIds.filterTo(mutableSetOf()) { catalog.componentPacks[it] != null }
        )
      // Filtered against the current document on the way in: the host does not track edits since
      // its fetch.
      is UiBuilderEditorEvent.SetComponentDrift ->
        state.copy(componentDrift = event.findings.stillDescribing(state.document))
      is UiBuilderEditorEvent.ResolveUndeclaredProperty -> resolveUndeclaredProperty(state, event)
      is UiBuilderEditorEvent.ToggleCatalogComponent ->
        state.copy(
          expandedCatalogComponents = state.expandedCatalogComponents.toggled(event.componentId)
        )
      is UiBuilderEditorEvent.SearchLayers -> state.copy(layerQuery = event.query)
      is UiBuilderEditorEvent.TogglePane ->
        state.copy(
          panes =
            if (event.pane !in state.panes) state.panes + event.pane
            // The last pane standing stays: the alternative is an empty workspace, which is not a
            // view of the design and not a state anything on screen could get you out of.
            else if (state.panes.size > 1) state.panes - event.pane else state.panes
        )
      is UiBuilderEditorEvent.ToggleCodePane -> state.copy(codePaneVisible = !state.codePaneVisible)
      is UiBuilderEditorEvent.ToggleQuickEditor ->
        state.copy(quickEditorOpen = !state.quickEditorOpen && state.selection.size == 1)
      is UiBuilderEditorEvent.ShowQuickEditor ->
        state.copy(quickEditorOpen = state.selection.size == 1)
      is UiBuilderEditorEvent.HideQuickEditor -> state.copy(quickEditorOpen = false)
      is UiBuilderEditorEvent.ToggleHistoryBar ->
        // Shutting the strip ends whatever it was showing. A peek that outlived the control it was
        // started from is a canvas stuck at an old revision with nothing on screen saying why.
        state.copy(
          historyBarVisible = !state.historyBarVisible,
          revisionPeek = null,
          revisionCompare = null,
        )
      is UiBuilderEditorEvent.ShowRevision ->
        state.copy(revisionPeek = event.revision, revisionCompare = null)
      is UiBuilderEditorEvent.CompareRevision ->
        if (state.revisionPeek == null || state.revisionPeek == event.revision) state
        else state.copy(revisionCompare = event.revision)
      is UiBuilderEditorEvent.ShowPanes ->
        if (event.panes.isEmpty()) state else state.copy(panes = event.panes)
      is UiBuilderEditorEvent.ShowWearWidgetHostShape ->
        state.copy(wearWidgetHostShape = event.shape)
      is UiBuilderEditorEvent.SelectAllMatches -> selectAllMatches(state)
      is UiBuilderEditorEvent.SelectNode ->
        if (event.nodeId in state.document.nodes) state.copy(selection = listOf(event.nodeId))
        else state
      is UiBuilderEditorEvent.InsertComponent ->
        insert(
          state,
          event.componentId,
          event.target,
          variant = event.variant,
          afterNodeId = event.afterNodeId,
        )
      is UiBuilderEditorEvent.InsertComponentBeside ->
        insertBeside(state, event.componentId, variant = event.variant)
      UiBuilderEditorEvent.ToggleAddBeside -> state.copy(addBeside = !state.addBeside)
      is UiBuilderEditorEvent.SetCanvasView -> state.copy(canvasView = event.view)
      is UiBuilderEditorEvent.TogglePinnedComponent ->
        state.copy(pinnedComponents = pinnedComponents(state).toggled(event.componentId))
      is UiBuilderEditorEvent.ToggleVariantAxis ->
        state.copy(
          variantAxes =
            if (event.axis in state.variantAxes) state.variantAxes - event.axis
            else state.variantAxes + event.axis
        )
      is UiBuilderEditorEvent.MoveNode -> move(state, event)
      is UiBuilderEditorEvent.MoveNodeInto -> moveInto(state, event)
      is UiBuilderEditorEvent.CommitProperty ->
        commitProperty(state, event.nodeId, event.property, event.draft)
      is UiBuilderEditorEvent.BindPropertyToState ->
        bindPropertyToState(state, event.nodeId, event.property, event.variable, event.equalsValue)
      is UiBuilderEditorEvent.BindPropertyToFormula ->
        bindPropertyToFormula(state, event.nodeId, event.property, event.formula)
      is UiBuilderEditorEvent.UnbindProperty -> unbindProperty(state, event.nodeId, event.property)
      is UiBuilderEditorEvent.ClearProperty -> clearProperty(state, event.nodeId, event.property)
      is UiBuilderEditorEvent.MakeComponent -> makeComponent(state, event.name)
      is UiBuilderEditorEvent.InsertLocalComponent ->
        insertLocalComponent(state, event.componentKey, event.target, event.afterNodeId)
      is UiBuilderEditorEvent.RenameComponentParameter ->
        renameComponentParameter(state, event.componentKey, event.from, event.to)
      is UiBuilderEditorEvent.DetachPlacement -> detachPlacement(state, event.nodeId)
      is UiBuilderEditorEvent.ExposeComponentParameter ->
        exposeComponentParameter(state, event.nodeId, event.property)
      is UiBuilderEditorEvent.InlineComponentParameter ->
        inlineComponentParameter(state, event.componentKey, event.parameter)
      is UiBuilderEditorEvent.InsertLibraryComponent ->
        insertLibraryComponent(state, event.symbol, event.target, event.afterNodeId)
      is UiBuilderEditorEvent.RenameLocalComponent ->
        renameLocalComponent(state, event.componentKey, event.name)
      is UiBuilderEditorEvent.UpdateLibraryComponent ->
        updateLibraryComponent(state, event.componentKey, event.symbol)
      is UiBuilderEditorEvent.RecordLibrarySource ->
        recordLibrarySource(state, event.componentKey, event.source)
      is UiBuilderEditorEvent.ReplaceLocalComponent ->
        replaceLocalComponent(state, event.componentKey, event.catalogComponentId)
      is UiBuilderEditorEvent.TuneTarget -> tuneTarget(state, event.target, event.into)
      is UiBuilderEditorEvent.UntuneTarget ->
        state.copy(
          tunables =
            state.tunables.map {
              if (it.name == event.name) it.copy(targets = it.targets - event.target) else it
            }
        )
      is UiBuilderEditorEvent.EditTunable -> editTunable(state, event.name, event.tunable)
      is UiBuilderEditorEvent.RemoveTunable ->
        state.copy(
          tunables = state.tunables.filterNot { it.name == event.name },
          tunedValues = state.tunedValues - event.name,
        )
      is UiBuilderEditorEvent.SetTunedValue -> {
        val tunable = state.tunables.firstOrNull { it.name == event.name }
        if (tunable == null) state
        else
          state.copy(tunedValues = state.tunedValues + (event.name to tunable.coerce(event.value)))
      }
      UiBuilderEditorEvent.ResetTunedValues -> state.copy(tunedValues = emptyMap())
      UiBuilderEditorEvent.ApplyTunables -> applyTunables(state)
      is UiBuilderEditorEvent.ApplyDesignToken ->
        applyDesignToken(state, event.tokenId, event.value)
      is UiBuilderEditorEvent.TuneDesignToken -> tuneDesignToken(state, event.tokenId)
      is UiBuilderEditorEvent.ImportDesignTokens -> importDesignTokens(state, event.values)
      is UiBuilderEditorEvent.SetStateVariable ->
        state.apply(
          state.operationSequence + 1,
          listOf(DesignOperation.SetStateVariable(event.name, event.declaration)),
          selectionAfter = state.selectedNodeId,
        )
      is UiBuilderEditorEvent.RemoveStateVariable ->
        state.apply(
          state.operationSequence + 1,
          listOf(DesignOperation.RemoveStateVariable(event.name)),
          selectionAfter = state.selectedNodeId,
        )
      is UiBuilderEditorEvent.SetStateSelection ->
        state.apply(
          state.operationSequence + 1,
          listOf(
            event.selection?.let {
              DesignOperation.SetProperty(event.nodeId, SHOW_BY_STATE, it.encode())
            } ?: DesignOperation.RemoveNodeProperty(event.nodeId, SHOW_BY_STATE)
          ),
          selectionAfter = event.nodeId,
        )
      is UiBuilderEditorEvent.SetEventBinding ->
        state.apply(
          state.operationSequence + 1,
          listOf(DesignOperation.SetEventBinding(event.nodeId, event.event, event.actions)),
          selectionAfter = event.nodeId,
        )
      is UiBuilderEditorEvent.AppendAction -> appendAction(state, event)
      is UiBuilderEditorEvent.UpdateEnvironment -> updateEnvironment(state, event.settings)
      is UiBuilderEditorEvent.ToggleModifier -> toggleModifier(state, event.nodeId, event.type)
      is UiBuilderEditorEvent.ResizeNode ->
        resizeNode(state, event.nodeId, event.width, event.height)
      is UiBuilderEditorEvent.AlignNodeToReference -> alignNodeToReference(state, event)
      is UiBuilderEditorEvent.SetModifierValue ->
        setModifierValue(
          state,
          event.nodeId,
          event.type,
          event.field,
          event.draft,
          event.modifierIndex,
        )
      is UiBuilderEditorEvent.ShowInspector -> state.copy(inspectorMode = event.mode)
      is UiBuilderEditorEvent.ApplyTheme -> applyTheme(state, event.settings)
      is UiBuilderEditorEvent.ApplyColorScheme -> applyColorScheme(state, event.roles)
      UiBuilderEditorEvent.DeleteSelected -> deleteSelected(state)
      UiBuilderEditorEvent.DuplicateSelected -> duplicateSelected(state)
      is UiBuilderEditorEvent.ToggleNode -> toggleNode(state, event.nodeId)
      is UiBuilderEditorEvent.ExtendSelectionTo -> extendSelection(state, event.nodeId)
      is UiBuilderEditorEvent.SelectRelative -> selectRelative(state, event.move)
      is UiBuilderEditorEvent.MoveSelected -> moveSelected(state, event.direction)
      is UiBuilderEditorEvent.WrapSelection -> wrapSelection(state, event.componentId)
      UiBuilderEditorEvent.UnwrapSelection -> unwrapSelection(state)
      is UiBuilderEditorEvent.InsertComponentWithAction ->
        insert(state, event.componentId, event.target, event.action)
      is UiBuilderEditorEvent.InsertRemoteComposeDocument ->
        insertRemoteComposeDocument(state, event.source, event.documentBase64, event.target)
      is UiBuilderEditorEvent.InsertRemoteComposeDocumentBeside ->
        insertRemoteComposeDocument(state, event.source, event.documentBase64, target = null)
      UiBuilderEditorEvent.CopySelected -> copySelected(state)
      UiBuilderEditorEvent.Tidy -> tidy(state)
      UiBuilderEditorEvent.CutSelected -> cutSelected(state)
      UiBuilderEditorEvent.Paste -> paste(state)
      is UiBuilderEditorEvent.ReceiveClipboard -> state.copy(clipboard = event.clipboard)
      UiBuilderEditorEvent.Undo -> undo(state)
      UiBuilderEditorEvent.Redo -> redo(state)
      is UiBuilderEditorEvent.AttachReference -> state.withReference(attached(state, event.image))
      UiBuilderEditorEvent.ClearReference ->
        state.withReference(ReferenceOverlayState(mintedIds = state.reference.mintedIds))
      is UiBuilderEditorEvent.UpdateReferenceSettings ->
        state.withReference(state.reference.copy(settings = event.settings.sanitized()))
      UiBuilderEditorEvent.ToggleReference ->
        if (!state.reference.hasContent) state
        else
          state.withReference(
            state.reference.copy(
              settings = state.reference.settings.copy(visible = !state.reference.settings.visible)
            )
          )
      is UiBuilderEditorEvent.SelectReferenceTool ->
        state.withReference(state.reference.copy(tool = event.tool))
      is UiBuilderEditorEvent.SelectMarkupColor ->
        state.withReference(state.reference.copy(markupColorArgb = event.colorArgb))
      is UiBuilderEditorEvent.SetMarkupText ->
        state.withReference(state.reference.copy(markupText = event.text.take(MAX_MARKUP_TEXT)))
      is UiBuilderEditorEvent.AddReferenceMark -> addMark(state, event.kind, event.points)
      is UiBuilderEditorEvent.RemoveReferenceMark ->
        state.withReference(
          state.reference.copy(marks = state.reference.marks.filterNot { it.id == event.markId })
        )
      UiBuilderEditorEvent.UndoReferenceMark ->
        state.withReference(state.reference.copy(marks = state.reference.marks.dropLast(1)))
      UiBuilderEditorEvent.ClearReferenceMarkup ->
        state.withReference(state.reference.copy(marks = emptyList()))
      is UiBuilderEditorEvent.PlaceReferencePiece ->
        placePiece(state, event.image, event.componentId)
      is UiBuilderEditorEvent.SelectReferencePiece ->
        state.withReference(state.reference.copy(selectedPieceId = event.pieceId))
      is UiBuilderEditorEvent.MoveReferencePiece ->
        state.withReference(
          state.reference.mapPiece(event.pieceId) { it.movedBy(event.dx, event.dy) }
        )
      is UiBuilderEditorEvent.ScaleReferencePiece ->
        state.withReference(state.reference.mapPiece(event.pieceId) { it.scaledBy(event.factor) })
      is UiBuilderEditorEvent.PromoteReferencePiece ->
        promotePiece(state, event.pieceId, event.target)
      is UiBuilderEditorEvent.RemoveReferencePiece ->
        state.withReference(
          state.reference.copy(
            pieces = state.reference.pieces.filterNot { it.id == event.pieceId },
            selectedPieceId = state.reference.selectedPieceId.takeIf { it != event.pieceId },
          )
        )
      is UiBuilderEditorEvent.FlattenReference ->
        state.withReference(
          attached(state, event.image)
            .copy(
              // The pieces and the marks are *in* the flattened picture now. Keeping them would
              // draw every one of them twice, and the second copy would no longer be removable.
              pieces = emptyList(),
              marks = emptyList(),
              selectedPieceId = null,
              tool = ReferenceTool.None,
            )
        )
    }

  fun acceptedSubmission(
    previous: UiBuilderEditorState,
    current: UiBuilderEditorState,
  ): EditorSubmission? {
    if (current.operationSequence == previous.operationSequence) return null
    if (current.lastOutcome !is CommandOutcome.Accepted) return null
    current.collaboration.acceptedCommands.keys
      .firstOrNull { it !in previous.collaboration.acceptedCommands }
      ?.let {
        return EditorSubmission.Batch(current.collaboration.acceptedCommands.getValue(it).command)
      }
    current.collaboration.undoRecords.keys
      .firstOrNull { it !in previous.collaboration.undoRecords }
      ?.let {
        return EditorSubmission.Undo(current.collaboration.undoRecords.getValue(it).command)
      }
    current.collaboration.redoRecords.keys
      .firstOrNull { it !in previous.collaboration.redoRecords }
      ?.let {
        return EditorSubmission.Redo(current.collaboration.redoRecords.getValue(it).command)
      }
    return null
  }

  /**
   * Whether the whole selection can go, checked cumulatively per slot (so `min = 1` slots are not
   * over-deleted) over its top-most nodes.
   */
  fun canDeleteSelected(state: UiBuilderEditorState): Boolean {
    val targets = state.selectionRoots()
    if (targets.isEmpty()) return false
    val document = state.document
    val rootsRemoved = targets.count { document.location(it) == null }
    if (rootsRemoved > 0 && document.roots.size - rootsRemoved < 1) return false
    return targets
      .mapNotNull(document::location)
      .groupingBy { it }
      .eachCount()
      .all { (parent, removed) ->
        val parentNode = document.nodes[parent.nodeId] ?: return@all false
        val minimum =
          catalog.componentsById[parentNode.componentId]?.slot(parent.slot)?.cardinality?.min
            ?: return@all false
        parentNode.slots[parent.slot].orEmpty().size - removed >= minimum
      }
  }

  fun canUndo(state: UiBuilderEditorState): Boolean = state.undoTargetOperationId(actorId) != null

  fun canRedo(state: UiBuilderEditorState): Boolean = state.redoTargetUndoId(actorId) != null

  /**
   * Everybody's changes to this design, newest first, from the collaboration state. Undo walks only
   * this editor's commands, so its target is often not the newest entry.
   */
  fun operationHistory(state: UiBuilderEditorState): List<EditorOperationEntry> {
    val collaboration = state.collaboration
    val undoTarget = state.undoTargetOperationId(actorId)
    val redoTarget =
      state.redoTargetUndoId(actorId)?.let {
        collaboration.undoRecords[it]?.target?.command?.operationId
      }
    return collaboration.acceptedCommands.values
      .sortedByDescending(AcceptedCommand::committedRevision)
      .map { accepted ->
        val undone = accepted.command.operationId in collaboration.compensatedOperationIds
        EditorOperationEntry(
          operationId = accepted.command.operationId,
          revision = accepted.committedRevision,
          summary = summarise(state, accepted),
          nodeId = accepted.subjectNodeId(),
          actorId = accepted.command.actorId,
          mine = accepted.command.actorId == actorId,
          standing =
            when {
              undone && accepted.command.operationId == redoTarget ->
                EditorOperationStanding.NextRedo
              undone -> EditorOperationStanding.Undone
              accepted.command.operationId == undoTarget -> EditorOperationStanding.NextUndo
              else -> EditorOperationStanding.Applied
            },
          changes = accepted.describeChanges(),
        )
      }
  }

  /**
   * The command in one line, from its operations (the intent). One kind of operation is named;
   * several are counted.
   */
  private fun summarise(state: UiBuilderEditorState, accepted: AcceptedCommand): String {
    val operations = accepted.command.operations
    if (operations.isEmpty()) return "No change"
    fun label(nodeId: String) = nodeLabel(state, nodeId)
    val summaries = operations.map { operation ->
      when (operation) {
        is DesignOperation.InsertNode ->
          "Added ${componentLabel(operation.node.componentId)}" +
            (operation.parent?.let { " to ${label(it.nodeId)}" } ?: "")
        is DesignOperation.MoveNode -> "Moved ${label(operation.nodeId)}"
        is DesignOperation.DeleteNode -> "Deleted ${label(operation.nodeId)}"
        is DesignOperation.RestoreNode -> "Restored ${label(operation.nodeId)}"
        is DesignOperation.SetProperty -> "Set ${operation.property} on ${label(operation.nodeId)}"
        is DesignOperation.RemoveNodeProperty ->
          "Cleared ${operation.property} on ${label(operation.nodeId)}"
        is DesignOperation.SetEnvironment -> "Set ${operation.field} on the screen"
        is DesignOperation.SetStateVariable -> "Set state ${operation.name}"
        is DesignOperation.RemoveStateVariable -> "Removed state ${operation.name}"
        is DesignOperation.SetEventBinding ->
          "Changed ${operation.event} actions on ${label(operation.nodeId)}"
        is DesignOperation.SetModifiers -> "Changed the layout of ${label(operation.nodeId)}"
        is DesignOperation.SetComponentArguments -> "Changed ${label(operation.nodeId)}"
        is DesignOperation.DeclareComponent ->
          "Declared component ${componentDeclarationName(operation.declaration) ?: operation.componentKey}"
        is DesignOperation.RemoveComponent -> "Removed component ${operation.componentKey}"
      }
    }
    return summaries.distinct().singleOrNull() ?: "${operations.size} changes"
  }

  /** The node's layers-panel name, or the bare id for a node that no longer exists. */
  internal fun nodeLabel(state: UiBuilderEditorState, nodeId: String): String {
    val node = state.document.nodes[nodeId] ?: return nodeId
    node.placementKey()?.let {
      return componentDeclarationName(state.document.components[it]) ?: it
    }
    val capability = catalog.componentsById[node.componentId] ?: return nodeId
    return node.contentLabel(capability) ?: capability.displayName
  }

  /**
   * What kind of thing this node is — `Button`, `Card`, or a local component's own name — for a
   * sentence that names it in passing. Unlike [nodeLabel] it never reads the node's content:
   * "Deleted Episode 140: Lorem ipsum dolor" is a quote, not a description of what went.
   */
  internal fun nodeKindLabel(state: UiBuilderEditorState, nodeId: String): String {
    val node = state.document.nodes[nodeId] ?: return nodeId
    node.placementKey()?.let {
      return componentDeclarationName(state.document.components[it]) ?: it
    }
    return componentLabel(node.componentId)
  }

  private fun componentLabel(componentId: String): String =
    catalog.componentsById[componentId]?.displayName ?: componentId

  /**
   * Whether the whole selection can be duplicated, checked cumulatively per slot like delete so a
   * nearly full slot does not end up with half a duplicate.
   */
  fun canDuplicateSelected(state: UiBuilderEditorState): Boolean {
    val targets = state.selectionRoots()
    if (targets.isEmpty()) return false
    val document = state.document
    return targets
      .mapNotNull(document::location)
      .groupingBy { it }
      .eachCount()
      .all { (parent, added) -> hasRoomForAll(document, parent, added) }
  }

  fun canCopySelected(state: UiBuilderEditorState): Boolean =
    state.selectedNodeId?.let(state.document.nodes::containsKey) == true

  /** Cut is a copy the document has to survive, so it needs delete's cardinality check too. */
  fun canCutSelected(state: UiBuilderEditorState): Boolean =
    canCopySelected(state) && canDeleteSelected(state)

  /**
   * Whether [paste] would land somewhere, judged by the clipboard's component rather than the
   * selection's.
   */
  fun canPaste(state: UiBuilderEditorState): Boolean = pasteDestination(state) != null

  /**
   * Where the clipboard would land, or null. Every root must fit one destination, with room checked
   * for the whole batch, so a paste never lands partially.
   */
  private fun pasteDestination(state: UiBuilderEditorState): ParentSlot? {
    val clipboard = state.clipboard?.takeIf { it.rootNodeIds.isNotEmpty() } ?: return null
    // Every copied node, not just roots: the clipboard may come from another catalog, and the
    // validator would otherwise refuse only after Paste was pressed.
    if (
      clipboard.nodes.values.any {
        it.componentId != COMPONENT_INSTANCE_ID && it.componentId !in catalog.componentsById
      }
    )
      return null
    val capabilities =
      clipboard.rootNodeIds.map { clipboard.capabilityOf(it, state.document) ?: return null }
    val destination =
      capabilities.map { findDestination(state.document, state.selectedNodeId, it) }.distinct()
    val single = destination.singleOrNull() ?: return null
    return single.takeIf { hasRoomForAll(state.document, it, clipboard.rootNodeIds.size) }
  }

  private fun hasRoomForAll(
    document: UiBuilderDocument,
    parent: ParentSlot,
    count: Int,
  ): Boolean {
    val parentNode = document.nodes[parent.nodeId] ?: return false
    val slot = catalog.componentsById[parentNode.componentId]?.slot(parent.slot) ?: return false
    val maximum = slot.cardinality.max ?: return true
    return parentNode.slots[parent.slot].orEmpty().size + count <= maximum
  }

  /**
   * The state variables this document declares, for a binding picker. Only declared ones: an
   * undeclared name renders as null and exports nothing.
   */
  fun stateVariableNames(state: UiBuilderEditorState): List<String> =
    state.document.stateVariables.keys.sorted()

  /**
   * Whether a binding on [propertyName] must be a comparison (`stateEquals`) rather than a bare
   * read, e.g. a boolean property against a string variable.
   */
  /**
   * Whether a binding on [propertyName] would be accepted, by validating the value the bind would
   * write.
   */
  fun canBindToState(
    state: UiBuilderEditorState,
    nodeId: String,
    propertyName: String,
  ): Boolean {
    if (state.document.nodes[nodeId] == null) return false
    return state.document.stateVariables.keys.any { variable ->
      val candidate =
        if (bindingNeedsComparison(state, nodeId, propertyName))
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("stateEquals"),
              "variable" to JsonPrimitive(variable),
              "value" to JsonPrimitive("probe"),
            )
          )
        else
          JsonObject(mapOf("type" to JsonPrimitive("state"), "variable" to JsonPrimitive(variable)))
      validator.validate(state.document, nodeId, propertyName, candidate) == null
    }
  }

  /**
   * Whether [propertyName] may hold a computed value: needs a wire contract that can save one, a
   * Remote Compose catalog, and a property accepting a probe expression.
   */
  fun canBindToFormula(
    state: UiBuilderEditorState,
    nodeId: String,
    propertyName: String,
  ): Boolean {
    if (!UiExpressions.wireSupported) return false
    if (catalog.platform != UiBuilderCatalogPlatform.REMOTE_COMPOSE) return false
    if (state.document.nodes[nodeId] == null) return false
    return FORMULA_PROBES.any { probe ->
      validator.validate(state.document, nodeId, propertyName, probe) == null
    }
  }

  fun bindingNeedsComparison(
    state: UiBuilderEditorState,
    nodeId: String,
    propertyName: String,
  ): Boolean {
    val node = state.document.nodes[nodeId] ?: return false
    val property =
      catalog.componentsById[node.componentId]?.propertiesByName?.get(propertyName) ?: return false
    return "boolean" in (property.typeNames() - "null")
  }

  /**
   * A one-component document as the palette's Add would produce it (same `InsertComponent`), or
   * null where the component cannot stand alone. Cached per component and variant.
   */
  fun previewDocument(
    componentId: String,
    variant: EditorCatalogVariant? = null,
  ): UiBuilderDocument? {
    val key = componentId to variant?.value
    // `getOrPut` treats a stored null as absent, so misses are cached explicitly.
    if (key in previewDocuments) return previewDocuments[key]
    return previewDocuments.getOrPut(key) {
      val state = initial(previewFrame, selectedNodeId = PREVIEW_FRAME_CELL_ID)
      val target = dropTarget(state, componentId)
      val inserted = target?.let {
        reduce(state, UiBuilderEditorEvent.InsertComponent(componentId, it, variant))
      }
      inserted?.takeIf { it.lastOutcome is CommandOutcome.Accepted }?.document?.centeredInFrame()
    }
  }

  /**
   * The inserted component centred in the thumbnail frame via the catalog's `align` modifier, where
   * declared.
   */
  private fun UiBuilderDocument.centeredInFrame(): UiBuilderDocument {
    val cell = nodes[PREVIEW_FRAME_CELL_ID] ?: return this
    val childId = cell.slots[FRAME_SLOT]?.singleOrNull() ?: return this
    val child = nodes[childId] ?: return this
    if (
      "align" !in (catalog.componentsById[child.componentId]?.modifierCapabilities ?: return this)
    )
      return this
    val centered =
      child.copy(
        modifiers =
          JsonArray(
            listOf(
              JsonObject(
                mapOf(
                  "type" to JsonPrimitive("align"),
                  "alignment" to JsonPrimitive("center"),
                )
              )
            ) + child.modifiers
          )
      )
    return copy(nodes = nodes + (childId to centered))
  }

  private val previewDocuments = mutableMapOf<Pair<String, String?>, UiBuilderDocument?>()

  /**
   * The container thumbnails and drag ghosts insert into: `layout/box` if present, else the
   * catalog's first unbounded list container, preferring `…/box` or `…/column`.
   */
  private val frameComponentId: String by lazy {
    if (FRAME_BOX in catalog.componentsById) return@lazy FRAME_BOX
    val lists =
      catalog.components.filter { component ->
        component.slots.any { it.name == FRAME_SLOT && it.cardinality.max == null }
      }
    (lists.firstOrNull { it.componentId.substringAfterLast('/').lowercase() in FRAME_PREFERRED }
        ?: lists.firstOrNull())
      ?.componentId ?: FRAME_BOX
  }

  private val frameModifiers: List<String> by lazy {
    catalog.componentsById[frameComponentId]?.modifierCapabilities.orEmpty()
  }

  internal fun debugPreviewFrame(): UiBuilderDocument = previewFrame

  /**
   * The empty frame a thumbnail's component is inserted into. A bare box (the menu row paints its
   * own ground), sized generously and scaled down so the thumbnail is a shrunken component.
   */
  private val previewFrame: UiBuilderDocument by lazy {
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "catalog-thumbnail-frame",
      title = "Catalog thumbnail",
      revision = 0,
      catalogPin =
        catalog.benchmark.let { benchmark ->
          JsonObject(
            mapOf(
              "systemId" to JsonPrimitive(benchmark.catalogSystemId),
              "catalogRevision" to JsonPrimitive(benchmark.catalogRevision),
              "capabilityDigest" to JsonPrimitive(benchmark.catalogRevision),
              "nativeRuntimeId" to JsonPrimitive(benchmark.nativeRuntimeId),
            )
          )
        },
      environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(PREVIEW_FRAME_WIDTH_DP),
            "heightDp" to JsonPrimitive(PREVIEW_FRAME_HEIGHT_DP),
            // No `density`/`fontScale`, deliberately: pinning 1.0 made a thumbnail lay out at a
            // fraction of size. `UiBuilderSurface` falls back to `LocalDensity`.
            "theme" to JsonPrimitive("dark"),
            "dynamicColor" to JsonPrimitive(false),
            "locale" to JsonPrimitive("en-US"),
            "layoutDirection" to JsonPrimitive("ltr"),
            "windowPosture" to JsonPrimitive("flat"),
            "browserZoomPercent" to JsonPrimitive(100),
            // Fixed, and no network: a thumbnail that ticked would make every scroll a diff.
            "fixedTime" to JsonPrimitive("2024-05-16T12:00:00Z"),
            "animations" to JsonPrimitive("settled"),
            "networkAccess" to JsonPrimitive(false),
          )
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(PREVIEW_FRAME_CELL_ID),
      nodes =
        mapOf(
          PREVIEW_FRAME_CELL_ID to
            UiBuilderNode(
              id = PREVIEW_FRAME_CELL_ID,
              componentId = frameComponentId,
              // No properties: the validator refuses unknown properties, which would refuse every
              // thumbnail.
              properties = JsonObject(emptyMap()),
              // Fixed size only where the container takes a `size` modifier; otherwise (A2UI) the
              // cell wraps its component.
              modifiers =
                JsonArray(
                  if ("size" !in frameModifiers) emptyList()
                  else
                    listOf(
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("size"),
                          "widthDp" to JsonPrimitive(PREVIEW_FRAME_WIDTH_DP),
                          "heightDp" to JsonPrimitive(PREVIEW_FRAME_HEIGHT_DP),
                        )
                      )
                    )
                ),
              slots = mapOf(FRAME_SLOT to emptyList()),
            )
        ),
    )
  }

  /**
   * The component carried beside a drag, at the size it would land: no fixed cell or centring, and
   * no density (the canvas scales the ghost). Null is only a guard; the canvas then shows a named
   * chip.
   */
  fun dragGhostDocument(
    state: UiBuilderEditorState,
    componentId: String,
    variant: EditorCatalogVariant? = null,
  ): UiBuilderDocument? {
    val frame = ghostFrame(state)
    val ghostState = initial(frame, selectedNodeId = DRAG_GHOST_CELL_ID)
    val target = dropTarget(ghostState, componentId) ?: return null
    val inserted =
      reduce(ghostState, UiBuilderEditorEvent.InsertComponent(componentId, target, variant))
    val document = inserted.takeIf { it.lastOutcome is CommandOutcome.Accepted }?.document
    return if (frameComponentId == FRAME_BOX) document else document?.withoutFrameCell()
  }

  /**
   * The ghost re-rooted at its component, dropping the frame cell unless it is an invisible
   * `layout/box` (an A2UI list container would draw a placeholder the drop never places).
   */
  private fun UiBuilderDocument.withoutFrameCell(): UiBuilderDocument {
    val childId = nodes[DRAG_GHOST_CELL_ID]?.slots?.get(FRAME_SLOT)?.singleOrNull() ?: return this
    return copy(roots = listOf(childId), nodes = nodes - DRAG_GHOST_CELL_ID)
  }

  /**
   * The subtree a canvas move is carrying, rooted at the node, in the design's theme and
   * unconstrained so the canvas can size it to the hovered slot.
   */
  fun nodeGhostDocument(state: UiBuilderEditorState, nodeId: String): UiBuilderDocument? {
    val document = state.document
    val subtree = document.subtreeOf(nodeId)
    if (nodeId !in document.nodes) return null
    val nodes = subtree.mapNotNull(document.nodes::get).associateBy(UiBuilderNode::id)
    if (nodes.isEmpty()) return null
    return document.copy(
      id = "drag-ghost-$nodeId",
      revision = 0,
      environment = ghostEnvironment(document),
      roots = listOf(nodeId),
      nodes = nodes,
    )
  }

  /** The design's own environment, with the density taken out — see [dragGhostDocument]. */
  private fun ghostEnvironment(document: UiBuilderDocument): JsonObject =
    JsonObject(document.environment.toMap() - "density")

  /**
   * The unconstrained `layout/box` cell a drag ghost renders into, wrapping its child so the canvas
   * can cap the size.
   */
  private fun ghostFrame(state: UiBuilderEditorState): UiBuilderDocument =
    ghostFrames.getOrPut(ghostEnvironment(state.document).toString()) {
      previewFrame.copy(
        id = "drag-ghost-frame",
        environment = ghostEnvironment(state.document),
        roots = listOf(DRAG_GHOST_CELL_ID),
        nodes =
          mapOf(
            DRAG_GHOST_CELL_ID to
              UiBuilderNode(
                id = DRAG_GHOST_CELL_ID,
                componentId = frameComponentId,
                properties = JsonObject(emptyMap()),
                modifiers = JsonArray(emptyList()),
                slots = mapOf(FRAME_SLOT to emptyList()),
              )
          ),
      )
    }

  private val ghostFrames = mutableMapOf<String, UiBuilderDocument>()

  fun catalogItems(query: String): List<EditorCatalogItem> {
    val needle = query.trim().lowercase()
    return catalog.components
      .asSequence()
      .map { it.editorCatalogItem() }
      .filter { it.matches(needle) }
      .sortedWith(compareBy(EditorCatalogItem::kind, EditorCatalogItem::displayName))
      .toList()
  }

  /**
   * The components pinned at the top of the insert panel: the reader's choice, or the catalog's.
   */
  fun pinnedComponents(state: UiBuilderEditorState): Set<String> =
    state.pinnedComponents ?: catalog.pinnedComponents

  /** Whether [componentId] has nowhere to go: root-only components once the design has a root. */
  fun placeableNowhere(state: UiBuilderEditorState, componentId: String): Boolean =
    componentId in RecordFreeExport.ROOT_ONLY_COMPONENT_IDS && state.document.roots.isNotEmpty()

  /** How many components the list offers this design, which is what its All row counts. */
  fun listedComponentCount(state: UiBuilderEditorState): Int =
    catalog.paletteComponents.count { !placeableNowhere(state, it.componentId) }

  /**
   * The insert panel's rows: catalog families (from [ComponentMenu], falling back to kind
   * headings), components, and variants of open components. A search opens every surviving group
   * without forgetting what was collapsed.
   */
  fun catalogRows(state: UiBuilderEditorState): List<EditorCatalogRow> {
    val needle = state.catalogQuery.trim().lowercase()
    val filtering = needle.isNotEmpty()
    val items =
      catalog.paletteComponents
        .map { it.editorCatalogItem() }
        // A disabled pack is hidden from search too; otherwise the switch would not hide anything.
        .filter { it.pack == null || it.pack in state.enabledPacks }
        .filter { !placeableNowhere(state, it.componentId) }
        .filter { it.matches(needle) }
        .sortedWith(compareBy({ it.searchRank(needle) }, EditorCatalogItem::displayName))
    // Declared shelves, then kind-label fallbacks in kind order. `distinct` because the lists
    // overlap ("Scaffolds") and the later entry would push the shelf to the end.
    val order =
      (catalog.componentMenu.groupOrder +
          EditorComponentKind.entries.map(EditorComponentKind::label))
        .distinct()
        .withIndex()
        .associate { (index, name) -> name to index }
    val rows = mutableListOf<EditorCatalogRow>()
    // Recently placed first, only while browsing and only from [items], so anything no longer
    // addable drops off the shelf.
    if (!filtering && state.recentComponents.isNotEmpty()) {
      val byId = items.associateBy(EditorCatalogItem::componentId)
      val recentItems = state.recentComponents.mapNotNull(byId::get)
      if (recentItems.isNotEmpty()) {
        val expanded = RECENT_SHELF_ID !in state.collapsedCatalogGroups
        rows +=
          EditorCatalogRow.Group(RECENT_SHELF, recentItems.size, expanded, id = RECENT_SHELF_ID)
        if (expanded) {
          recentItems.forEach { item ->
            val open =
              item.variants.isNotEmpty() && item.componentId in state.expandedCatalogComponents
            rows += EditorCatalogRow.Component(item, open, recent = true)
            if (open) {
              item.variants.forEach {
                rows += EditorCatalogRow.Variant(it, item.displayName, recent = true)
              }
            }
          }
        }
      }
    }
    // Pins only while browsing (a search would list them twice), and never from a disabled pack.
    val pinned = pinnedComponents(state)
    if (!filtering && pinned.isNotEmpty()) {
      val pinnedItems = items.filter { it.componentId in pinned }
      if (pinnedItems.isNotEmpty()) {
        val expanded = "Pinned" !in state.collapsedCatalogGroups
        rows += EditorCatalogRow.Group("Pinned", pinnedItems.size, expanded)
        if (expanded) {
          pinnedItems.forEach { item ->
            val open =
              item.variants.isNotEmpty() && item.componentId in state.expandedCatalogComponents
            rows += EditorCatalogRow.Component(item, open, pinned = true)
            if (open) {
              item.variants.forEach {
                rows += EditorCatalogRow.Variant(it, item.displayName, pinned = true)
              }
            }
          }
        }
      }
    }
    items
      .groupBy(EditorCatalogItem::group)
      .entries
      // Declared order first, then alphabetical, so unordered groups land predictably.
      .sortedWith(
        if (filtering) {
          compareBy({ (_, groupItems) -> groupItems.minOf { it.searchRank(needle) } }, { it.key })
        } else {
          compareBy({ order[it.key] ?: Int.MAX_VALUE }, { it.key })
        }
      )
      .forEach { (group, groupItems) ->
        val expanded = filtering || group !in state.collapsedCatalogGroups
        rows += EditorCatalogRow.Group(group, groupItems.size, expanded)
        if (!expanded) return@forEach
        groupItems.forEach { item ->
          val open =
            item.variants.isNotEmpty() &&
              (filtering || item.componentId in state.expandedCatalogComponents)
          rows += EditorCatalogRow.Component(item, open)
          if (open) {
            item.variants.forEach { rows += EditorCatalogRow.Variant(it, item.displayName) }
          }
        }
      }
    return rows
  }

  private fun ComponentCapability.editorCatalogItem(): EditorCatalogItem {
    val kind = editorKind()
    val exportsToCompose = composeExportCoverage?.let { componentId in it }
    return EditorCatalogItem(
      componentId = componentId,
      displayName = displayName,
      kind = kind,
      group = catalog.componentMenu.groupOf(componentId) ?: kind.label,
      pack = catalog.componentPacks.packOf(componentId)?.id,
      exportsToCompose = exportsToCompose,
      variants =
        menuVariantValues(catalog.componentMenu).mapIndexed { index, value ->
          EditorCatalogVariant(
            componentId = componentId,
            property = catalog.componentMenu.variantPropertyOf(componentId).orEmpty(),
            value = value,
            label = variantLabel(value),
            // The catalog's first allowed value is what `defaultEncodedValue` writes on a plain
            // insert, so it is the default here by the same rule rather than by a second opinion.
            default = index == 0,
            exportsToCompose = exportsToCompose,
          )
        },
    )
  }

  fun treeRows(document: UiBuilderDocument): List<EditorTreeRow> {
    val rows = mutableListOf<EditorTreeRow>()
    val seen = mutableSetOf<String>()
    fun visit(nodeId: String, depth: Int, parent: ParentSlot?) {
      // Missing and cyclic references are what the Issues panel reports, so they must not crash the
      // navigator, which is built before it.
      val node = document.nodes[nodeId] ?: return
      if (!seen.add(nodeId)) return
      val capability = catalog.componentsById[node.componentId]
      // A placement is called what it places; a body root says whose body it is, since it sits in
      // the tree beside the screen rather than in it.
      val placedName =
        node.placementKey()?.let { componentDeclarationName(document.components[it]) ?: it }
      val definedName =
        document.components.values
          .firstOrNull { componentRootOf(it) == nodeId }
          ?.let(::componentDeclarationName)
      val componentLabel =
        when {
          placedName != null -> "Component"
          definedName != null -> "Component · ${capability?.displayName ?: node.componentId}"
          else -> capability?.displayName ?: node.componentId
        }
      rows +=
        EditorTreeRow(
          nodeId = nodeId,
          componentId = node.componentId,
          label =
            placedName ?: definedName ?: capability?.let(node::contentLabel) ?: componentLabel,
          componentLabel = componentLabel,
          depth = depth,
          parent = parent,
        )
      node.slots.forEach { (slot, children) ->
        children.forEach { visit(it, depth + 1, ParentSlot(nodeId, slot)) }
      }
    }
    document.roots.forEach { visit(it, 0, null) }
    // Each component's body after the screen, so its layout can be selected and edited — an edit
    // there is an edit to every placement.
    document.detachedComponentRoots().sorted().forEach { visit(it, 0, null) }
    return rows
  }

  /**
   * The layers panel's rows, narrowed by [UiBuilderEditorState.layerQuery]. Ancestors of matches
   * stay as context with [EditorTreeRow.matched] false. Matches text, component name, node id and
   * component id.
   */
  fun visibleTreeRows(state: UiBuilderEditorState): List<EditorTreeRow> {
    val rows = treeRows(state.document)
    val needle = state.layerQuery.trim().lowercase()
    if (needle.isEmpty()) return rows
    fun EditorTreeRow.matches(): Boolean =
      label.lowercase().contains(needle) ||
        componentLabel.lowercase().contains(needle) ||
        nodeId.lowercase().contains(needle) ||
        componentId.lowercase().contains(needle)
    val matched = rows.map { it.matches() }
    // Keep matched rows and their ancestors. Parents come from one pre-order pass (the latest row
    // at depth d-1 parents a row at depth d), keeping this linear for 10,000-node designs on every
    // filter keystroke.
    val parent = IntArray(rows.size) { -1 }
    val deepest = rows.maxOfOrNull(EditorTreeRow::depth) ?: -1
    val ancestorAtDepth = IntArray(deepest + 1) { -1 }
    rows.forEachIndexed { index, row ->
      parent[index] = if (row.depth == 0) -1 else ancestorAtDepth[row.depth - 1]
      ancestorAtDepth[row.depth] = index
    }
    val keep = BooleanArray(rows.size)
    rows.indices.forEach { index ->
      if (!matched[index]) return@forEach
      keep[index] = true
      // Stop at the first ancestor already kept: whatever marked it walked its own chain to the
      // root, so everything above is kept too. That is what keeps the total linear.
      var above = parent[index]
      while (above >= 0 && !keep[above]) {
        keep[above] = true
        above = parent[above]
      }
    }
    return rows.indices
      .filter { keep[it] }
      .map { index -> rows[index].copy(matched = matched[index]) }
  }

  /**
   * The layers panel's lines: [visibleTreeRows] plus slot headings where informative (several
   * slots, an empty drop-target slot, or an undeclared one). Empty slots are omitted under a
   * filter.
   */
  fun layerRows(state: UiBuilderEditorState): List<EditorLayerRow> {
    val rows = visibleTreeRows(state)
    val byId = rows.associateBy(EditorTreeRow::nodeId)
    val filtering = state.layerQuery.isNotBlank()
    val lines = mutableListOf<EditorLayerRow>()
    val seen = mutableSetOf<String>()
    fun visit(row: EditorTreeRow, indent: Int) {
      if (!seen.add(row.nodeId)) return
      lines += EditorLayerRow.Node(row, indent)
      val node = state.document.nodes[row.nodeId] ?: return
      val capability = catalog.componentsById[node.componentId]
      val declared = capability?.slots.orEmpty().map(SlotCapability::name)
      // Declared order first — a Scaffold reads top bar, snackbar, content the way the catalog
      // lists it, not the order the document happened to fill them in.
      val slotNames = declared + node.slots.keys.filterNot(declared::contains)
      slotNames.forEach { slotName ->
        val children = node.slots[slotName].orEmpty()
        val kept = children.mapNotNull(byId::get)
        val named = declared.size > 1 || children.isEmpty() || slotName !in declared
        if (!named) {
          kept.forEach { visit(it, indent + 1) }
          return@forEach
        }
        // The slot line sits at its children's depth so the 300dp panel is not indented twice per
        // level.
        if (filtering && kept.isEmpty()) return@forEach
        val slot = capability?.slot(slotName)
        lines +=
          EditorLayerRow.Slot(
            parent = ParentSlot(node.id, slotName),
            indent = indent + 1,
            childCount = children.size,
            maxChildren = slot?.cardinality?.max,
          )
        kept.forEach { visit(it, indent + 1) }
      }
    }
    rows.filter { it.parent == null }.forEach { visit(it, 0) }
    return lines
  }

  private fun selectAllMatches(state: UiBuilderEditorState): UiBuilderEditorState {
    val matches = visibleTreeRows(state).filter(EditorTreeRow::matched).map(EditorTreeRow::nodeId)
    return if (matches.isEmpty()) state else state.copy(selection = matches)
  }

  /**
   * The free text [nodeId] shows when editable in place on the canvas, or null for bound or
   * choice-valued text.
   */
  fun inlineText(state: UiBuilderEditorState, nodeId: String): String? {
    if (nodeId !in state.document.nodes) return null
    val field =
      propertyFields(state.copy(selection = listOf(nodeId))).firstOrNull { it.name == "text" }
        ?: return null
    // A computed property is bound too: its inline editor would open empty and replace the formula.
    if (
      field.control != EditorPropertyControl.Text ||
        field.boundVariable != null ||
        field.boundFormula != null
    )
      return null
    return field.value
  }

  /**
   * The inspector's fields for the selection. For several nodes, only properties every selected
   * component declares, with disagreeing values marked [EditorPropertyField.mixed].
   */
  fun propertyFields(state: UiBuilderEditorState): List<EditorPropertyField> {
    val anchor = state.selectedNodeId?.let(state.document.nodes::get)
    // A placement has no catalog properties: what it sets is the arguments its body reads.
    if (anchor?.componentId == COMPONENT_INSTANCE_ID)
      return if (state.selection.size == 1) placementFields(state, anchor) else emptyList()
    val owner = anchor?.let { state.document.owningComponent(it.id) }
    val fields = catalogPropertyFields(state)
    if (anchor == null || owner == null) return fields
    // A body property that reads a parameter shows which one, rather than the parameter's name as
    // though it were the text: each placement sets it.
    val ownerName = componentDeclarationName(state.document.components[owner]) ?: owner
    return fields.map { field ->
      val parameter = anchor.properties[field.name]?.bindingKey() ?: return@map field
      field.copy(
        value = "",
        supporting = "Parameter `$parameter` of $ownerName — each placement sets it",
      )
    }
  }

  private fun catalogPropertyFields(state: UiBuilderEditorState): List<EditorPropertyField> {
    val nodes = state.selection.mapNotNull(state.document.nodes::get)
    val node = nodes.lastOrNull() ?: return emptyList()
    val component = catalog.componentsById[node.componentId] ?: return emptyList()
    // The other selected components: a shared name is not enough, since disjoint allowed values
    // would make a control whose every choice `commitProperty` rejects.
    val others = nodes.dropLast(1).mapNotNull { catalog.componentsById[it.componentId] }
    fun sharedByAll(property: PropertyCapability): Boolean = others.all { other ->
      val theirs = other.propertiesByName[property.name] ?: return@all false
      theirs.allowedValues == property.allowedValues && theirs.typeNames() == property.typeNames()
    }
    return component.properties
      .filterNot { it.name in THEME_PROPERTIES }
      .filter { nodes.size == 1 || sharedByAll(it) }
      .flatMap { property ->
        val objectEdges =
          property.editor?.objectKind?.let { kind ->
            EDITOR_OBJECT_VALUE_EDGES[kind]?.let { kind to it }
          }
        if (objectEdges != null) {
          val (kind, edges) = objectEdges
          return@flatMap edges.map { edge ->
            objectEdgeField(state, nodes, node, property, kind, edge)
          }
        }
        val encoded = node.properties[property.name] as? JsonObject
        val value = encoded?.get("value")
        val typeNames = property.typeNames() - "null"
        val declaredControl = property.editor?.control
        val numberBounds = property.numberBounds(typeNames)
        val control =
          when {
            declaredControl == PropertyEditorControl.COLOR -> EditorPropertyControl.Color
            property.allowedValues.isNotEmpty() || declaredControl == PropertyEditorControl.ENUM ->
              EditorPropertyControl.Enum
            declaredControl == PropertyEditorControl.TEXT -> EditorPropertyControl.Text
            // Membership, not equality, like `literalDefault`: ["boolean", "string"] means "a flag
            // or a state variable name".
            declaredControl == PropertyEditorControl.BOOLEAN || "boolean" in typeNames ->
              EditorPropertyControl.Boolean
            (declaredControl == PropertyEditorControl.NUMBER ||
              typeNames.authoredTypes() == setOf("number") ||
              typeNames.authoredTypes() == setOf("integer")) && numberBounds != null ->
              EditorPropertyControl.Number
            typeNames.authoredTypes() == setOf("string") -> EditorPropertyControl.Text
            else -> EditorPropertyControl.Unsupported
          }
        val boundVariables =
          nodes
            .map { other ->
              (other.properties[property.name] as? JsonObject)
                ?.takeIf { it["type"]?.primitiveOrNull()?.contentOrNull in STATE_VALUE_TYPES }
                ?.get("variable")
                ?.primitiveOrNull()
                ?.contentOrNull
            }
            .distinct()
        val encodedValues =
          nodes
            .map {
              if (control == EditorPropertyControl.Unsupported) it.properties[property.name]
              else (it.properties[property.name] as? JsonObject)?.get("value")
            }
            .distinct()
        val mixed = encodedValues.size > 1
        val loopRows = node.componentId == "layout/for-each" && property.name == "data"
        val summary =
          when {
            loopRows && encoded?.get("type") == JsonPrimitive("list") ->
              (encoded["values"] as? JsonArray)?.size?.let {
                "$it ${if (it == 1) "row" else "rows"}"
              }
            else -> null
          }
        EditorPropertyField(
            nodeCount = nodes.size,
            mixed = mixed,
            boundVariable = boundVariables.singleOrNull(),
            nodeId = node.id,
            name = property.name,
            label = if (loopRows) "Rows" else property.name.humanLabel(),
            required = property.required,
            written = nodes.any { property.name in it.properties },
            control = control,
            value = if (mixed) "" else summary ?: value?.primitiveOrNull()?.content ?: "",
            choices =
              property.allowedValues.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } +
                property.editor?.suggestedValues.orEmpty(),
            numberBounds = numberBounds,
            error = state.propertyErrors[EditorPropertyLocation(node.id, property.name)],
            notes = if (loopRows) "Each row fills the same layout template." else property.notes,
            boundFormula =
              nodes
                .map { other ->
                  other.properties[property.name]
                    ?.takeIf(UiExpressions::isComputed)
                    ?.let(UiExpressions::format)
                }
                .distinct()
                .singleOrNull(),
            formulaAllowed = nodes.size == 1 && canBindToFormula(state, node.id, property.name),
          )
          .let(::listOf)
      }
  }

  /**
   * How the selected node is sized on each axis and what each axis may become. Single selection
   * only; null when nothing is selected or the component declares no size modifiers.
   */
  fun sizing(state: UiBuilderEditorState): EditorNodeSizing? {
    val nodeId = state.selection.singleOrNull() ?: return null
    val node = state.document.nodes[nodeId] ?: return null
    // A root is sized by the frame, not by a modifier anybody can drag.
    if (state.document.location(nodeId) == null) return null
    val declared = catalog.componentsById[node.componentId]?.modifierCapabilities.orEmpty().toSet()
    return nodeSizing(nodeId, node.modifiers, declared, state.document.scopeOf(nodeId)).takeIf {
      it.width.resizable || it.height.resizable
    }
  }

  /**
   * Re-size one axis of a node and submit the resulting chain, refused with the component's reason
   * where the needed modifier is undeclared (as [toggleModifier] does).
   */
  private fun resizeNode(
    state: UiBuilderEditorState,
    nodeId: String,
    width: EditorSizing?,
    height: EditorSizing?,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val node = state.document.nodes[nodeId] ?: return state
    val declared = catalog.componentsById[node.componentId]?.modifierCapabilities.orEmpty().toSet()
    val scope = state.document.scopeOf(nodeId)
    var chain: List<JsonElement> = node.modifiers
    for ((axis, sizing) in listOf(EditorAxis.Width to width, EditorAxis.Height to height)) {
      if (sizing == null) continue
      chain =
        resizedModifierChain(chain, axis, sizing, declared, scope)
          ?: return state.rejected(
            sequence,
            RejectionCode.INVALID_PROPERTY,
            "${node.componentId} cannot be sized to ${sizing.label()} on its " +
              axis.name.lowercase(),
            nodeId,
            "modifiers",
          )
    }
    if (chain == node.modifiers.toList()) return state
    return state.apply(
      sequence,
      listOf(DesignOperation.SetModifiers(nodeId, JsonArray(chain))),
      selectionAfter = nodeId,
    )
  }

  /**
   * Apply a reference match as one batch (see [UiBuilderEditorEvent.AlignNodeToReference]), refused
   * whole if any part cannot be written.
   */
  private fun alignNodeToReference(
    state: UiBuilderEditorState,
    event: UiBuilderEditorEvent.AlignNodeToReference,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val nodeId = event.nodeId
    val node = state.document.nodes[nodeId] ?: return state
    val declared = catalog.componentsById[node.componentId]?.modifierCapabilities.orEmpty().toSet()
    fun refuse(message: String, property: String = "modifiers") =
      state.rejected(sequence, RejectionCode.INVALID_PROPERTY, message, nodeId, property)

    var chain: List<JsonElement> = node.modifiers.toList()
    if (event.moveXDp != 0 || event.moveYDp != 0) {
      chain =
        movedModifierChain(chain, event.moveXDp, event.moveYDp, declared)
          ?: return refuse(
            "${node.componentId} declares neither padding nor offset, so it cannot be moved"
          )
    }
    val scope = state.document.scopeOf(nodeId)
    event.widthDp?.let { width ->
      chain =
        resizedModifierChain(
          chain,
          EditorAxis.Width,
          EditorSizing.Fixed(width.toFloat()),
          declared,
          scope,
        ) ?: return refuse("${node.componentId} cannot be given a fixed width")
    }
    event.heightDp?.let { height ->
      chain =
        resizedModifierChain(
          chain,
          EditorAxis.Height,
          EditorSizing.Fixed(height.toFloat()),
          declared,
          scope,
        ) ?: return refuse("${node.componentId} cannot be given a fixed height")
    }
    val operations = mutableListOf<DesignOperation>()
    if (chain != node.modifiers.toList()) {
      operations += DesignOperation.SetModifiers(nodeId, JsonArray(chain))
    }
    event.fontSizeSp?.let { size ->
      val property =
        catalog.componentsById[node.componentId]
          ?.propertiesByName
          ?.get(REFERENCE_FONT_SIZE_PROPERTY)
          ?: return refuse(
            "${node.componentId} has no $REFERENCE_FONT_SIZE_PROPERTY",
            REFERENCE_FONT_SIZE_PROPERTY,
          )
      if (node.properties[REFERENCE_FONT_SIZE_PROPERTY]?.bindingKey() != null) {
        return refuse(
          "The type size is a component parameter; set it on each placement",
          REFERENCE_FONT_SIZE_PROPERTY,
        )
      }
      val field =
        propertyFields(state.copy(selection = listOf(nodeId))).firstOrNull {
          it.name == REFERENCE_FONT_SIZE_PROPERTY
        }
          ?: return refuse(
            "${node.componentId} has no $REFERENCE_FONT_SIZE_PROPERTY",
            REFERENCE_FONT_SIZE_PROPERTY,
          )
      val draft = if (size == floor(size)) size.toInt().toString() else size.toString()
      val parsed = field.parseDraft(draft)
      if (parsed is PropertyDraft.Invalid)
        return refuse(parsed.message, REFERENCE_FONT_SIZE_PROPERTY)
      val existingType =
        property.canonicalWrapper(
          (node.properties[REFERENCE_FONT_SIZE_PROPERTY] as? JsonObject)
            ?.get("type")
            ?.primitiveOrNull()
            ?.contentOrNull
        )
      val encoded =
        literal(existingType ?: field.defaultEncodedType(), (parsed as PropertyDraft.Valid).value)
      validator.validate(state.document, nodeId, REFERENCE_FONT_SIZE_PROPERTY, encoded)?.let {
        return refuse(it.message, REFERENCE_FONT_SIZE_PROPERTY)
      }
      operations += DesignOperation.SetProperty(nodeId, REFERENCE_FONT_SIZE_PROPERTY, encoded)
    }
    if (operations.isEmpty()) return state
    return state.apply(sequence, operations, selectionAfter = nodeId)
  }

  /**
   * The layout modifiers the selection can be given and whether it has them. Single selection only,
   * catalog-declared modifiers only.
   */
  fun modifierToggles(state: UiBuilderEditorState): List<EditorModifierToggle> {
    val nodeId = state.selection.singleOrNull() ?: return emptyList()
    val node = state.document.nodes[nodeId] ?: return emptyList()
    val declared = catalog.componentsById[node.componentId]?.modifierCapabilities.orEmpty().toSet()
    val present = node.modifierKeys()
    val scope = state.document.scopeOf(nodeId)
    return MENU_MODIFIERS.filter { it.type in declared && it.offeredIn(scope) }
      .map { EditorModifierToggle(it.key, it.label, it.key in present) }
  }

  /**
   * Add or remove one modifier and submit the resulting chain. Refused up front where the catalog
   * does not declare the modifier, so the message names the modifier rather than the document.
   */
  private fun toggleModifier(
    state: UiBuilderEditorState,
    nodeId: String,
    type: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val node = state.document.nodes[nodeId] ?: return state
    // [type] is the menu key: the modifier type, or `remoteCall:<name>` for a Remote call.
    val menuModifier = MENU_MODIFIERS.firstOrNull { it.key == type } ?: return state
    val declared = catalog.componentsById[node.componentId]?.modifierCapabilities.orEmpty()
    if (menuModifier.type !in declared) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "Modifier $type is not declared by ${node.componentId}",
        nodeId,
        "modifiers",
      )
    }
    // The parent decides whether the modifier means anything: `weight` outside a row or column
    // cannot render or compile.
    if (!menuModifier.offeredIn(state.document.scopeOf(nodeId))) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "Modifier $type needs a parent that provides its scope",
        nodeId,
        "modifiers",
      )
    }
    val kept = node.modifiers.filter { (it as? JsonObject)?.let(::modifierKey) != type }
    val chain =
      if (type in node.modifierKeys()) JsonArray(kept) else JsonArray(kept + menuModifier.build())
    return state.apply(
      sequence,
      listOf(DesignOperation.SetModifiers(nodeId, chain)),
      selectionAfter = nodeId,
    )
  }

  /**
   * The numeric fields of the selected node's modifier chain, in application order. Single
   * selection; modifiers this build does not know are left out rather than offered for editing and
   * rewritten away.
   */
  fun modifierFields(state: UiBuilderEditorState): List<EditorModifierField> {
    val nodeId = state.selection.singleOrNull() ?: return emptyList()
    val node = state.document.nodes[nodeId] ?: return emptyList()
    return node.modifiers.flatMapIndexed { index, element ->
      val modifier = element as? JsonObject ?: return@flatMapIndexed emptyList()
      val type = modifierKey(modifier) ?: return@flatMapIndexed emptyList()
      val remote = type.startsWith(REMOTE_CALL_KEY_PREFIX)
      val fields = if (remote) remoteCallFields(modifier) else MODIFIER_FIELDS[type].orEmpty()
      fields.map { field ->
        // A Remote call's arguments are values, so the number is inside its wrapper.
        val raw =
          if (remote)
            (modifier["args"] as? JsonObject)
              ?.get(field.name)
              ?.let { it as? JsonObject }
              ?.get("value")
          else modifier[field.name]
        EditorModifierField(
          type = type,
          field = field.name,
          label = field.label,
          value = raw?.primitiveOrNull()?.content.orEmpty(),
          choices = field.choices,
          index = index,
        )
      }
    }
  }

  /**
   * Write one value into one modifier, refusing drafts the field cannot hold. Field kinds come from
   * [MODIFIER_FIELDS].
   */
  private fun setModifierValue(
    state: UiBuilderEditorState,
    nodeId: String,
    type: String,
    field: String,
    draft: String,
    modifierIndex: Int?,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val node = state.document.nodes[nodeId] ?: return state
    val target =
      modifierIndex?.let { node.modifiers.getOrNull(it) as? JsonObject }
        ?: node.modifiers.firstNotNullOfOrNull {
          (it as? JsonObject)?.takeIf { m -> modifierKey(m) == type }
        }
    val remote = type.startsWith(REMOTE_CALL_KEY_PREFIX)
    val definition =
      (if (remote) target?.let(::remoteCallFields).orEmpty() else MODIFIER_FIELDS[type].orEmpty())
        .firstOrNull { it.name == field }
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "Unknown modifier field $type.$field",
          nodeId,
          "modifiers",
        )
    if (
      modifierIndex != null &&
        (node.modifiers.getOrNull(modifierIndex) as? JsonObject)?.let(::modifierKey) != type
    ) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "The modifier chain changed; select the modifier again",
        nodeId,
        "modifiers",
      )
    }
    val choices = definition.choices
    val value =
      if (choices.isEmpty()) {
        val number =
          draft.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: return state.rejected(
              sequence,
              RejectionCode.INVALID_PROPERTY,
              "${field.humanLabel()} must be a number",
              nodeId,
              "modifiers",
            )
        // Whole numbers stay whole. A padding typed as 24 that reads back as 24.0 is the same
        // layout and a different document, and every diff of it says something changed.
        if (number == floor(number) && abs(number) < Long.MAX_VALUE.toDouble())
          JsonPrimitive(number.toLong())
        else JsonPrimitive(number)
      } else {
        val choice = draft.trim()
        if (choice !in choices) {
          return state.rejected(
            sequence,
            RejectionCode.INVALID_PROPERTY,
            "${field.humanLabel()} must be one of ${choices.joinToString(", ")}",
            nodeId,
            "modifiers",
          )
        }
        JsonPrimitive(choice)
      }
    // A Remote call's int argument is written in an `int` wrapper, so it has to be whole.
    if (
      remote &&
        remoteCallArgumentType(target?.optionalStringValue("name").orEmpty(), field) == "int" &&
        value.content.toLongOrNull() == null
    ) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "${field.humanLabel()} must be a whole number",
        nodeId,
        "modifiers",
      )
    }
    var written = false
    val chain =
      JsonArray(
        node.modifiers.mapIndexed { index, element ->
          val modifier = element as? JsonObject ?: return@mapIndexed element
          if (
            written ||
              modifierKey(modifier) != type ||
              (modifierIndex != null && index != modifierIndex)
          )
            return@mapIndexed element
          written = true
          if (remote) {
            // Into the call's arguments, in the wrapper its parameter kind takes.
            val name = modifier.optionalStringValue("name").orEmpty()
            val wrapper = remoteCallArgumentType(name, field) ?: "float"
            val typed = if (wrapper == "bool") JsonPrimitive(value.content.toBoolean()) else value
            val args = (modifier["args"] as? JsonObject).orEmpty()
            JsonObject(
              modifier +
                ("args" to
                  JsonObject(
                    args +
                      (field to
                        JsonObject(mapOf("type" to JsonPrimitive(wrapper), "value" to typed)))
                  ))
            )
          } else JsonObject(modifier + (field to value))
        }
      )
    if (!written) return state
    return state.apply(
      sequence,
      listOf(DesignOperation.SetModifiers(nodeId, chain)),
      selectionAfter = nodeId,
    )
  }

  /**
   * One numeric edge of an object-valued property (e.g. padding), addressed as `property.edge` and
   * merged back by [commitProperty] because the wire carries the whole value.
   */
  private fun objectEdgeField(
    state: UiBuilderEditorState,
    nodes: List<UiBuilderNode>,
    anchor: UiBuilderNode,
    property: PropertyCapability,
    kind: String,
    edge: EditorObjectEdge,
  ): EditorPropertyField {
    val values =
      nodes
        .map { other ->
          (other.properties[property.name] as? JsonObject)
            ?.takeIf { it["type"]?.primitiveOrNull()?.contentOrNull == kind }
            ?.get(edge.field)
            ?.primitiveOrNull()
            ?.contentOrNull
        }
        .distinct()
    val mixed = values.size > 1
    return EditorPropertyField(
      nodeCount = nodes.size,
      mixed = mixed,
      nodeId = anchor.id,
      name = "${property.name}.${edge.field}",
      label = "${property.name.humanLabel()} · ${edge.label}",
      // An edge is never required on its own: the value is required or absent as a whole.
      required = false,
      // Written as a whole, too: the edges of a padding nobody set are not four properties the
      // export writes, they are one it does not.
      written = nodes.any { property.name in it.properties },
      control = EditorPropertyControl.Number,
      value = if (mixed) "" else values.singleOrNull() ?: edge.minimum.format(),
      numberBounds = EditorNumberBounds(edge.minimum, edge.maximum, 1.0, integer = false),
      error = state.propertyErrors[EditorPropertyLocation(anchor.id, property.name)],
      notes = property.notes,
    )
  }

  /**
   * Everything the Compose export would refuse beyond per-write validation: unreachable nodes,
   * missing required properties, pin drift, unemittable components.
   *
   * From [CapabilityComposeCodeExporter.diagnose] plus the real export's refusals; errors only,
   * dropping nodes that no longer exist. Takes only the document so callers can cache on it.
   */
  fun problems(
    document: UiBuilderDocument,
    assetBytes: (contentDigest: String) -> ByteArray? = { null },
  ): List<EditorProblem> {
    val export = exportOutcome(document, assetBytes)
    return (CapabilityComposeCodeExporter.diagnose(document, catalog)
        // These codes describe `CapabilityComposeCodeExporter`, which does not write the export;
        // drop them once the generator that runs has answered.
        .filterNot { it.code in GENERATOR_OWNED_CODES && export?.answersForTheEmitter == true }
        .filter { it.severity == ComposeExportSeverity.ERROR && it.code != "UNKNOWN_PROPERTY" }
        .map { diagnostic ->
          EditorProblem(
            code = diagnostic.code,
            message = diagnostic.message,
            nodeId = diagnostic.nodeId?.takeIf(document.nodes::containsKey),
            componentId = diagnostic.componentId,
          )
        } +
        // The server export's refusals, appended since capability diagnostics answer different
        // questions.
        export?.refusals.orEmpty() +
        undeclaredPropertyProblems(document) +
        // Not a refusal: the property commits but changes nothing visible (see #485). Same notice
        // the served export attaches.
        listOfNotNull(
          RootSurfaceGround.diagnose(document)?.let { notice ->
            EditorProblem(
              code = RootSurfaceGround.CODE,
              message = notice.message,
              nodeId = notice.nodeId,
              componentId = "m3/surface",
              // A notice, not a blocking error.
              blocking = false,
            )
          }
        ))
      .distinctBy { it.code to it.message }
  }

  private fun undeclaredPropertyProblems(document: UiBuilderDocument): List<EditorProblem> =
    document.nodes.values.sortedBy(UiBuilderNode::id).flatMap { node ->
      val capability = catalog.componentsById[node.componentId] ?: return@flatMap emptyList()
      val declared = capability.propertiesByName.keys
      node.properties.keys
        .filter { it !in declared }
        .sorted()
        .map { property ->
          EditorProblem(
            code = "PROPERTY_NOT_DECLARED",
            message =
              "Catalog ${catalog.benchmark.catalogSystemId} no longer declares property " +
                "$property on ${node.componentId}. Its value is preserved until you drop or map it.",
            nodeId = node.id,
            componentId = node.componentId,
            blocking = false,
            propertyName = property,
            replacementProperties = compatibleReplacements(document, node, property),
          )
        }
    }

  private fun compatibleReplacements(
    document: UiBuilderDocument,
    node: UiBuilderNode,
    property: String,
  ): List<String> {
    val capability = catalog.componentsById[node.componentId] ?: return emptyList()
    return (capability.propertiesByName.keys - node.properties.keys).filter { replacement ->
      val value = mappedPropertyValue(node, property, replacement) ?: return@filter false
      val candidateNode =
        node.copy(properties = JsonObject(node.properties - property + (replacement to value)))
      capabilityValidator
        .validate(document.copy(nodes = document.nodes + (node.id to candidateNode)))
        .issues
        .none { it.nodeId == node.id && it.field == replacement }
    }
  }

  private fun mappedPropertyValue(
    node: UiBuilderNode,
    property: String,
    replacement: String,
  ): JsonObject? {
    val encoded = node.properties[property] as? JsonObject ?: return null
    val value = encoded["value"] ?: return null
    val replacementCapability =
      catalog.componentsById[node.componentId]?.propertiesByName?.get(replacement) ?: return null
    return when (value) {
      is JsonPrimitive,
      JsonNull -> value.asLiteral(replacementCapability)
      else -> encoded
    }
  }

  private fun resolveUndeclaredProperty(
    state: UiBuilderEditorState,
    event: UiBuilderEditorEvent.ResolveUndeclaredProperty,
  ): UiBuilderEditorState {
    val node = state.document.nodes[event.nodeId] ?: return state
    val capability = catalog.componentsById[node.componentId] ?: return state
    if (event.property in capability.propertiesByName) return state
    val operations = mutableListOf<DesignOperation>()
    event.replacement?.let { replacement ->
      if (
        replacement !in capability.propertiesByName ||
          replacement in node.properties ||
          replacement !in compatibleReplacements(state.document, node, event.property)
      )
        return state
      val value = mappedPropertyValue(node, event.property, replacement) ?: return state
      operations += DesignOperation.SetProperty(node.id, replacement, value)
    }
    operations += DesignOperation.RemoveNodeProperty(node.id, event.property)
    return state.apply(
      state.operationSequence + 1,
      operations,
      selectionAfter = node.id,
    )
  }

  /**
   * The Kotlin the Compose export would write for [document], or why not. Total, like
   * [exportRefusals], so a malformed property cannot take the editor down.
   */
  fun generatedCode(
    document: UiBuilderDocument,
    assetBytes: (contentDigest: String) -> ByteArray? = { null },
  ): EditorGeneratedCode =
    screenCode(document, assetBytes).withRemoteContent(document).withA2uiMessages(document)

  /**
   * An A2UI design's streamed messages appended under its Kotlin as line comments, both from
   * `A2uiDocumentExporter.lower`.
   */
  private fun EditorGeneratedCode.withA2uiMessages(
    document: UiBuilderDocument
  ): EditorGeneratedCode {
    if (catalog.platform != UiBuilderCatalogPlatform.A2UI || this !is EditorGeneratedCode.Source) {
      return this
    }
    val exported =
      runCatching { A2uiDocumentExporter.export(document) }.getOrNull()
        as? A2uiDocumentExporter.Result.Emitted ?: return this
    val messages =
      exported.messages.joinToString("\n") { message ->
        a2uiMessageJson.encodeToString(JsonObject.serializer(), message).lines().joinToString(
          "\n"
        ) {
          "// $it"
        }
      }
    return EditorGeneratedCode.Source(
      kotlin.trimEnd() +
        "\n\n// A2UI messages — what an agent streams to draw this surface, one per line on the " +
        "wire:\n" +
        messages +
        "\n"
    )
  }

  /**
   * The `@RemoteComposable` bodies of the design's inline remote content joined to [screenCode]:
   * appended when the screen generates, replacing a refusal (kept as a header comment) when it does
   * not.
   */
  private fun EditorGeneratedCode.withRemoteContent(
    document: UiBuilderDocument
  ): EditorGeneratedCode {
    val hosts =
      document.nodes.values
        .filter { it.componentId == REMOTE_COMPOSE_INLINE_COMPONENT_ID }
        .map { it.id }
        .sorted()
    if (hosts.isEmpty()) return this
    val bodies = hosts.map { InlineRemoteContentExporter.export(document, it) }
    val emitted = bodies.filterIsInstance<InlineRemoteContentExporter.Result.Emitted>()
    val refusedBodies =
      bodies.filterIsInstance<InlineRemoteContentExporter.Result.Refused>().flatMap { it.reasons }
    if (emitted.isEmpty()) {
      return EditorGeneratedCode.Refused(
        (this as? EditorGeneratedCode.Refused)?.reasons.orEmpty() + refusedBodies
      )
    }
    val header =
      when (this) {
        is EditorGeneratedCode.Source -> listOf(kotlin)
        is EditorGeneratedCode.Refused ->
          listOf(
            (reasons + refusedBodies).joinToString("\n") {
              "// The screen around this content is not generated: ${it.replace("\n", " ")}"
            }
          )
      }
    return EditorGeneratedCode.Source(
      (header + emitted.map { it.source }).joinToString("\n\n").trimEnd() + "\n"
    )
  }

  private fun screenCode(
    document: UiBuilderDocument,
    assetBytes: (contentDigest: String) -> ByteArray?,
  ): EditorGeneratedCode = runCatching {
    // Wear widgets and Wear screens have no component record, so ask `RecordFreeExport` first — the
    // same call the server's export makes. No package: the pane is pasted into a file that has one.
    RecordFreeExport.generate(
        document,
        catalog.platform,
        packComponents = packComponents,
        assets = document.widgetAssetBytes(assetBytes),
      )
      ?.let { recordFree ->
        return@runCatching when (recordFree) {
          is RecordFreeExport.Generated.Emitted -> EditorGeneratedCode.Source(recordFree.source)
          is RecordFreeExport.Generated.Refused -> EditorGeneratedCode.Refused(recordFree.reasons)
        }
      }
    when (val outcome = ScreenExportGate.export(document.toProtocolDocument(), exportRecord)) {
      is ScreenExportGate.Outcome.Emitted -> EditorGeneratedCode.Source(outcome.source)
      is ScreenExportGate.Outcome.Refused -> EditorGeneratedCode.Refused(outcome.reasons)
    }
  }
    .getOrElse { failure ->
      EditorGeneratedCode.Refused(
        listOf(
          "this design could not be read as a protocol document" +
            (failure.message?.let { ": $it" } ?: "")
        )
      )
    }

  /**
   * What the export would refuse and which generator says so, or null when the design cannot be
   * read. Asks the generator that actually writes the design; `nodeId` is null because refusals
   * refer to the projected screen.
   */
  private fun exportOutcome(
    document: UiBuilderDocument,
    assetBytes: (contentDigest: String) -> ByteArray?,
  ): ExportOutcome? =
  // Total: a malformed property fails `toProtocolDocument`, and the panel must report rather than
  // throw. The capability diagnostics already name that fault.
  runCatching {
    when (
      val recordFree =
        RecordFreeExport.generate(
          document,
          catalog.platform,
          packComponents = packComponents,
          assets = document.widgetAssetBytes(assetBytes),
        )
    ) {
      is RecordFreeExport.Generated.Refused ->
        ExportOutcome(dedicated = true, reasons = recordFree.reasons)
      // It generates; the record gate would still refuse it, which is why these designs have their
      // own emitter.
      is RecordFreeExport.Generated.Emitted ->
        ExportOutcome(dedicated = true, reasons = emptyList())
      null ->
        ExportOutcome(
          dedicated = false,
          reasons = ScreenExportGate.refusals(document.toProtocolDocument(), exportRecord),
        )
    }
  }
    .getOrNull()

  /**
   * Which generator writes a design, and what it refused.
   *
   * @param dedicated whether a `RecordFreeExport` emitter owns the design rather than the
   *   record-driven `ScreenGenerator`.
   */
  private class ExportOutcome(val dedicated: Boolean, val reasons: List<String>) {
    /**
     * Whether this replaces the capability exporter's "no symbol"/"no emitter" answer: always for a
     * dedicated emitter, for the record path only when it generates.
     */
    val answersForTheEmitter: Boolean
      get() = dedicated || reasons.isEmpty()

    val refusals: List<EditorProblem>
      get() = reasons.map {
        EditorProblem(
          code = "COMPOSE_EXPORT_REFUSED",
          message = it,
          nodeId = null,
          componentId = null,
        )
      }
  }

  /**
   * The theme host the Theme panel edits, or null. A Material 3 design's is its root `m3/surface`
   * ([themeHost]); Wear screens and Remote widgets are their own hosts, preferring the one holding
   * the selection, else the first.
   */
  fun themePanelHost(state: UiBuilderEditorState): EditorThemeHost? {
    val document = state.document
    val root = document.themeHost()
    fun declared(node: UiBuilderNode): Set<String>? =
      catalog.componentsById[node.componentId]?.propertiesByName?.keys?.takeIf {
        ThemeTextStyle.PROPERTY in it || THEME_PRIMARY in it
      }
    fun isHost(node: UiBuilderNode): Boolean =
      declared(node) != null && (node.componentId != "m3/surface" || node.id == root?.id)
    val node =
      document.selectionChain(state.selectedNodeId).mapNotNull(document.nodes::get).firstOrNull {
        isHost(it)
      } ?: root?.takeIf(::isHost) ?: document.topLevelNodes.firstOrNull(::isHost) ?: return null
    val properties = declared(node).orEmpty()
    return EditorThemeHost(
      nodeId = node.id,
      componentId = node.componentId,
      colorProperties = ThemeSchemes.hostColorProperties(properties),
      wearScale = node.componentId != "m3/surface",
      scaleAndShape = THEME_TYPE_SCALE in properties && THEME_CORNER_RADIUS in properties,
      typographyProperties =
        properties.filterTo(mutableSetOf()) {
          it in ThemeTypefaces.PROPERTIES || it == ThemeTextStyle.PROPERTY
        },
    )
  }

  fun themeSettings(state: UiBuilderEditorState): EditorThemeSettings {
    val host = state.document.themeHost() ?: return EditorThemeSettings()
    return EditorThemeSettings(
      primaryColor = host.stringValue(THEME_PRIMARY, "#FFD0BCFF"),
      backgroundColor = host.stringValue(THEME_BACKGROUND, "#FF111318"),
      surfaceColor = host.stringValue(THEME_SURFACE, "#FF1D1F25"),
      contentColor = host.stringValue(THEME_CONTENT, "#FFE3E2E9"),
      typeScale = host.floatValue(THEME_TYPE_SCALE, 1f),
      cornerRadiusDp = host.floatValue(THEME_CORNER_RADIUS, 16f),
    )
  }

  fun dropTarget(state: UiBuilderEditorState, componentId: String): ParentSlot? {
    val component = catalog.componentsById[componentId] ?: return null
    return findDestination(state.document, state.selectedNodeId, component)
  }

  fun dropTargetLabel(state: UiBuilderEditorState, componentId: String = "m3/text"): String =
    dropTarget(state, componentId)?.let { "${it.nodeId}.${it.slot}" } ?: "No compatible slot"

  fun moveTarget(
    state: UiBuilderEditorState,
    nodeId: String,
    direction: EditorMoveDirection,
  ): UiBuilderEditorEvent.MoveNode? {
    val parent = state.document.location(nodeId) ?: return null
    val siblings = state.document.children(parent)
    val index = siblings.indexOf(nodeId)
    val targetIndex =
      when (direction) {
        EditorMoveDirection.Before -> index - 1
        EditorMoveDirection.After -> index + 1
      }
    val target = siblings.getOrNull(targetIndex) ?: return null
    return UiBuilderEditorEvent.MoveNode(
      nodeId = nodeId,
      targetNodeId = target,
      placeAfterTarget = direction == EditorMoveDirection.After,
    )
  }

  /** The selection's path from a root, root first. Stops at a cycle or a missing ancestor. */
  fun selectionPath(state: UiBuilderEditorState): List<UiBuilderBreadcrumbEntry> {
    val selected = state.selectedNodeId ?: return emptyList()
    val document = state.document
    val path = ArrayDeque<UiBuilderBreadcrumbEntry>()
    val visited = mutableSetOf<String>()
    var current: String? = selected
    while (current != null && visited.add(current)) {
      val node = document.nodes[current] ?: break
      val capability = catalog.componentsById[node.componentId]
      // A placement is called what it places, here as in the layers panel.
      val componentLabel =
        node.placementKey()?.let { componentDeclarationName(document.components[it]) ?: it }
          ?: capability?.displayName
          ?: node.componentId
      val location = document.location(current)
      val parentCapability =
        location?.let { document.nodes[it.nodeId] }?.let { catalog.componentsById[it.componentId] }
      // The slot is named only where the name is information — the layers panel's own rule for
      // drawing a slot line, applied to a line above the canvas.
      val inSlot =
        location?.slot?.takeIf {
          parentCapability == null ||
            parentCapability.slot(it) == null ||
            parentCapability.slots.size > 1
        }
      path.addFirst(
        UiBuilderBreadcrumbEntry(
          nodeId = current,
          label = capability?.let(node::contentLabel) ?: componentLabel,
          componentId = node.componentId,
          inSlot = inSlot,
        )
      )
      current = location?.nodeId
    }
    return path.toList()
  }

  private fun insert(
    state: UiBuilderEditorState,
    componentId: String,
    target: ParentSlot,
    action: EditorStateAction? = null,
    variant: EditorCatalogVariant? = null,
    afterNodeId: String? = null,
  ): UiBuilderEditorState {
    val component = catalog.componentsById[componentId] ?: return state
    val sequence = state.operationSequence + 1
    if (!acceptsComponent(state.document, target, component)) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_LOCATION,
        "${component.displayName} cannot be inserted into ${target.nodeId}.${target.slot}",
      )
    }
    return insertAt(
      state,
      component,
      target,
      action,
      component.variantProperties(variant),
      afterNodeId,
    )
  }

  /**
   * Why an Add beside cannot happen right now, or null when it can. Asked before the press so the
   * insert panel can say where the next Add lands.
   */
  fun besideRefusal(state: UiBuilderEditorState, componentId: String? = null): String? {
    val document = state.document
    // An empty design first: the component simply becomes the root, which suits root-only
    // components too.
    if (document.roots.isEmpty()) return null
    // Then the component: a root-only component must never become one item of a board.
    if (componentId != null && componentId in RecordFreeExport.ROOT_ONLY_COMPONENT_IDS) {
      return "A ${catalog.componentsById[componentId]?.displayName ?: componentId} is exported as " +
        "the whole design, so it cannot be one item of a board"
    }
    if (document.boardRootId != null) return null
    // Both record-free emitters route on the root component id, so wrapping a Wear design would
    // silently change how it exports.
    if (document.isWearScreen() || document.isWearWidget()) {
      return "A Wear screen or widget is exported as itself, so it cannot become one item of a board"
    }
    return null
  }

  /**
   * Where an Add beside lands and its prelude, shared by the palette and the Remote Compose panel (
   * [`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../../../../../docs/design/UI_BUILDER_CANVAS_FRAMES
   * _VARIANTS.md)): append into a board, become the root of an empty design, or wrap the root in a
   * new board. Prelude and insert are one command, so they undo together.
   */
  private fun besideDestination(state: UiBuilderEditorState, sequence: Int): BesideDestination {
    val document = state.document
    val existingBoard = document.boardRootId
    if (existingBoard != null) {
      val target = ParentSlot(existingBoard, UiBuilderBoard.SLOT)
      return BesideDestination(target, document.children(target).lastOrNull(), emptyList())
    }
    val existingRoot =
      document.roots.singleOrNull() ?: return BesideDestination(null, null, emptyList())
    val boardId = "editor-board-${sequence.toString().padStart(3, '0')}"
    return BesideDestination(
      target = ParentSlot(boardId, UiBuilderBoard.SLOT),
      afterNodeId = existingRoot,
      prelude =
        listOf(
          DesignOperation.InsertNode(UiBuilderBoard.node(boardId)),
          DesignOperation.MoveNode(existingRoot, ParentSlot(boardId, UiBuilderBoard.SLOT)),
        ),
    )
  }

  /**
   * Add a top-level item beside the design as one command (see [besideDestination]). Two roots
   * mid-command are legal because `requireSingleRoot` runs per command.
   */
  private fun insertBeside(
    state: UiBuilderEditorState,
    componentId: String,
    variant: EditorCatalogVariant? = null,
  ): UiBuilderEditorState {
    val component = catalog.componentsById[componentId] ?: return state
    val sequence = state.operationSequence + 1
    besideRefusal(state, componentId)?.let {
      return state.rejected(sequence, RejectionCode.INVALID_LOCATION, it)
    }
    val destination = besideDestination(state, sequence)
    val operations = destination.prelude.toMutableList()
    val nodeId = "editor-${componentId.replace('/', '-')}-${sequence.toString().padStart(3, '0')}"
    val defaultError =
      component.appendDefaultSubtree(
        catalog = catalog,
        document = state.document,
        nodeId = nodeId,
        parent = destination.target,
        afterNodeId = destination.afterNodeId,
        operations = operations,
        presetProperties = component.variantProperties(variant),
      )
    if (defaultError != null) {
      return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, defaultError)
    }
    return state.apply(sequence, operations, selectionAfter = nodeId)
  }

  /**
   * [variant] as encoded properties via [asLiteral]. A variant no longer declared or allowed
   * encodes to nothing, so a stale row never refuses an insert.
   */
  private fun ComponentCapability.variantProperties(
    variant: EditorCatalogVariant?
  ): Map<String, JsonObject> {
    if (variant == null || variant.componentId != componentId) return emptyMap()
    val property = propertiesByName[variant.property] ?: return emptyMap()
    val value = property.allowedValues.firstOrNull { it.contentOrNullSafe() == variant.value }
    return value?.let { mapOf(property.name to it.asLiteral(property)) }.orEmpty()
  }

  private fun appendAction(
    state: UiBuilderEditorState,
    event: UiBuilderEditorEvent.AppendAction,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val declaration =
      if (event.action is EditorStateAction.Navigate) null
      else
        state.document.stateVariables[event.action.variable] as? JsonObject
          ?: return state.rejected(
            sequence,
            RejectionCode.INVALID_PROPERTY,
            "Unknown state ${event.action.variable}",
          )
    // `= x + 40` is a formula, typed by the collaboration reducer against what `x` holds.
    val formula =
      event.action.formulaText()?.let { text ->
        try {
          UiExpressions.parseFormula(text, state.document.stateVariables.keys)
        } catch (failure: UiExpressions.FormulaError) {
          return state.rejected(
            sequence,
            RejectionCode.INVALID_PROPERTY,
            failure.message ?: "Not a formula",
          )
        }
      }
    if (formula == null) {
      event.action.valueRefusal(declaration)?.let {
        return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, it)
      }
    }
    val node = state.document.nodes[event.nodeId] ?: return state
    val actions = (node.eventBindings[event.event] as? JsonArray).orEmpty().toMutableList()
    val encoded = event.action.encoded(declaration, formula)
    if (event.index == null) actions += encoded
    else if (event.index in actions.indices) actions[event.index] = encoded
    else
      return state.rejected(sequence, RejectionCode.INVALID_COMMAND, "That action no longer exists")
    return state.apply(
      sequence,
      listOf(DesignOperation.SetEventBinding(event.nodeId, event.event, JsonArray(actions))),
      selectionAfter = event.nodeId,
    )
  }

  /**
   * Insert [component] into [target] regardless of the selection; for a promoted reference piece,
   * which arrives with a hit-tested slot.
   */
  private fun insertAt(
    state: UiBuilderEditorState,
    component: ComponentCapability,
    target: ParentSlot,
    action: EditorStateAction? = null,
    /** Encoded values written over the inserted root's own defaults — see [variantProperties]. */
    presetProperties: Map<String, JsonObject> = emptyMap(),
    /**
     * Insert after this child of [target], or append when null. A neighbour removed concurrently
     * falls back to appending.
     */
    requestedAfter: String? = null,
  ): UiBuilderEditorState {
    val componentId = component.componentId
    val sequence = state.operationSequence + 1
    val nodeId = "editor-${componentId.replace('/', '-')}-${sequence.toString().padStart(3, '0')}"
    val slotChildren = state.document.children(target)
    val afterNodeId = requestedAfter?.takeIf { it in slotChildren } ?: slotChildren.lastOrNull()
    val operations = mutableListOf<DesignOperation>()
    val defaultError =
      component.appendDefaultSubtree(
        catalog = catalog,
        document = state.document,
        nodeId = nodeId,
        parent = target,
        afterNodeId = afterNodeId,
        operations = operations,
        presetProperties = presetProperties,
      )
    if (defaultError != null) {
      return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, defaultError)
    }
    if (action != null) {
      if (
        action !is EditorStateAction.Navigate && action.variable !in state.document.stateVariables
      ) {
        return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "This design declares no state variable `${action.variable}`",
        )
      }
      // Bound to the inserted root's own change event when it has one (e.g. `checkedChange`), else
      // `click`, which `actionModifier` applies to any node.
      val declaration = state.document.stateVariables[action.variable] as? JsonObject
      // `selectOrClear` writes null, which would not compile against a non-nullable variable.
      if (action is EditorStateAction.SelectOrClear && !declaredNullable(declaration)) {
        return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "State variable `${action.variable}` is not nullable, so it cannot be cleared",
        )
      }
      // `toggle` needs a boolean: the renderer coerces silently but the exporter refuses, so refuse
      // here to keep preview and export consistent.
      if (
        action is EditorStateAction.Toggle && declaredStateKind(declaration) != StateKind.BOOLEAN
      ) {
        return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "State variable `${action.variable}` is not a flag, so it cannot be toggled",
        )
      }
      action.valueRefusal(declaration)?.let { why ->
        return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, why)
      }
      val index = operations.indexOfFirst { it is DesignOperation.InsertNode }
      val root = operations[index] as DesignOperation.InsertNode
      // A control with a change callback takes the action under that callback's name —
      // `checkedChange` on a checkbox — and everything else under `click` (TwoWayStateBinding).
      val event =
        TwoWayStateBinding.changeEvent(componentId, root.node.properties.keys)
          ?: TwoWayStateBinding.CLICK
      val bindings = JsonObject(mapOf(event to JsonArray(listOf(action.encoded(declaration)))))
      operations[index] = root.copy(node = root.node.copy(eventBindings = bindings))
    }
    return state.apply(sequence, operations, selectionAfter = nodeId)
  }

  /**
   * Insert `remote-compose/document` carrying [documentBase64], decoded first so an undecodable
   * fetch (e.g. an HTML error page) is refused instead of becoming a shared revision.
   *
   * @param target the slot to fill, or null for an Add beside; see [besideDestination].
   */
  private fun insertRemoteComposeDocument(
    state: UiBuilderEditorState,
    source: RemoteComposeSource,
    documentBase64: String,
    target: ParentSlot?,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val component =
      catalog.componentsById[REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID]
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_LOCATION,
          "This catalog does not offer $REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID",
        )
    // Add beside: a top-level item needs no compatible slot, and a played document is the kind of
    // asset a board holds.
    if (target == null) {
      besideRefusal(state, REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID)?.let {
        return state.rejected(sequence, RejectionCode.INVALID_LOCATION, it)
      }
    } else {
      if (!acceptsComponent(state.document, target, component)) {
        return state.rejected(
          sequence,
          RejectionCode.INVALID_LOCATION,
          "${component.displayName} cannot be inserted into ${target.nodeId}.${target.slot}",
        )
      }
    }
    decodeRemoteComposeDocument(documentBase64).exceptionOrNull()?.let { failure ->
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "${source.id} did not decode as a Remote Compose document: ${failure.message}",
      )
    }
    val nodeId =
      "editor-${REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID.replace('/', '-')}-" +
        sequence.toString().padStart(3, '0')
    val destination =
      if (target == null) besideDestination(state, sequence)
      else BesideDestination(target, state.document.children(target).lastOrNull(), emptyList())
    val operations = destination.prelude.toMutableList()
    val defaultError =
      component.appendDefaultSubtree(
        catalog = catalog,
        document = state.document,
        nodeId = nodeId,
        parent = destination.target,
        afterNodeId = destination.afterNodeId,
        operations = operations,
      )
    if (defaultError != null) {
      return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, defaultError)
    }
    // By id, not the first insert: a beside insert may be preceded by the board's own `InsertNode`.
    val index = operations.indexOfFirst { it is DesignOperation.InsertNode && it.node.id == nodeId }
    val root = operations[index] as DesignOperation.InsertNode
    operations[index] =
      root.copy(
        node =
          root.node.copy(
            properties =
              JsonObject(
                root.node.properties +
                  ("documentBase64" to literal("string", JsonPrimitive(documentBase64)))
              )
          )
      )
    return state.apply(sequence, operations, selectionAfter = nodeId)
  }

  private fun move(
    state: UiBuilderEditorState,
    event: UiBuilderEditorEvent.MoveNode,
  ): UiBuilderEditorState {
    val location = state.document.location(event.nodeId) ?: return state
    val siblings = state.document.children(location)
    val nodeIndex = siblings.indexOf(event.nodeId)
    val targetIndex = siblings.indexOf(event.targetNodeId)
    if (nodeIndex < 0 || targetIndex < 0 || event.nodeId == event.targetNodeId) return state
    val afterNodeId =
      if (event.placeAfterTarget) event.targetNodeId else siblings.getOrNull(targetIndex - 1)
    return state.apply(
      state.operationSequence + 1,
      listOf(DesignOperation.MoveNode(event.nodeId, location, afterNodeId)),
      selectionAfter = event.nodeId,
    )
  }

  private fun moveInto(
    state: UiBuilderEditorState,
    event: UiBuilderEditorEvent.MoveNodeInto,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val refusal = moveRefusal(state, event.nodeId, event.parent)
    if (refusal != null) {
      return state.rejected(sequence, refusal.code, refusal.message, event.nodeId)
    }
    val siblings = state.document.children(event.parent)
    val after = event.afterNodeId?.takeIf { it != event.nodeId }
    // A row released over itself is the commonest drop; it must not cost an operation, undo step or
    // revision.
    val settled = siblings.filterNot { it == event.nodeId }
    val landing = if (after == null) 0 else settled.indexOf(after) + 1
    if (state.document.location(event.nodeId) == event.parent) {
      if (siblings.indexOf(event.nodeId) == landing) return state
    }
    return state.apply(
      sequence,
      listOf(DesignOperation.MoveNode(event.nodeId, event.parent, after)),
      selectionAfter = event.nodeId,
    )
  }

  /**
   * Why [target] will not take [nodeId], or null when it will. Asked while dragging (so the slot
   * refuses before release) and again by the reducer on release.
   */
  fun moveRefusal(
    state: UiBuilderEditorState,
    nodeId: String,
    target: ParentSlot,
  ): EditorMoveRefusal? {
    val node = state.document.nodes[nodeId]
    val component = node?.let { catalog.componentsById[it.componentId] }
    if (component == null) {
      return EditorMoveRefusal(RejectionCode.UNKNOWN_NODE, "This catalog no longer offers $nodeId")
    }
    val parent = state.document.nodes[target.nodeId]
    val parentCapability = parent?.let { catalog.componentsById[it.componentId] }
    val declared = parentCapability?.slot(target.slot)
    if (parent == null || declared == null) {
      return EditorMoveRefusal(
        RejectionCode.INVALID_LOCATION,
        "${target.nodeId} has no ${target.slot} slot",
      )
    }
    // A node cannot land inside itself. The collaboration reducer refuses this too, and would
    // refuse it loudly; asking here means the panel can grey the row out instead.
    if (target.nodeId == nodeId || target.nodeId in state.document.subtreeOf(nodeId)) {
      return EditorMoveRefusal(
        RejectionCode.CYCLE,
        "${component.displayName} cannot go inside itself",
      )
    }
    if (!declared.accepts(component)) {
      return EditorMoveRefusal(
        RejectionCode.INVALID_LOCATION,
        "${component.displayName} does not belong in ${target.nodeId}.${target.slot}",
      )
    }
    // The leaving slot has a floor too: dragging out a Scaffold `content`'s only child would empty
    // a required slot.
    val origin = state.document.location(nodeId)
    if (origin != null && origin != target) {
      val originSlot =
        state.document.nodes[origin.nodeId]
          ?.let { catalog.componentsById[it.componentId] }
          ?.slot(origin.slot)
      val remaining = state.document.children(origin).count { it != nodeId }
      if (originSlot != null && remaining < originSlot.cardinality.min) {
        return EditorMoveRefusal(
          RejectionCode.INVALID_LOCATION,
          "${origin.nodeId}.${origin.slot} cannot be left empty",
        )
      }
    }
    // Room is counted without the node itself, so reordering inside a full slot stays legal.
    val occupants = state.document.children(target).count { it != nodeId }
    if (!declared.hasRoom(occupants)) {
      return EditorMoveRefusal(
        RejectionCode.INVALID_LOCATION,
        "${target.nodeId}.${target.slot} holds " +
          "${declared.cardinality.max} ${if (declared.cardinality.max == 1) "child" else "children"}",
      )
    }
    return null
  }

  private fun commitProperty(
    state: UiBuilderEditorState,
    nodeId: String,
    propertyName: String,
    draft: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val node = state.document.nodes[nodeId] ?: return state
    if (node.componentId == COMPONENT_INSTANCE_ID)
      return setComponentArgument(state, node, propertyName, draft)
    node.properties[propertyName]?.bindingKey()?.let { parameter ->
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "This is the component's `$parameter` parameter; set it on each placement",
        nodeId,
        propertyName,
      )
    }
    // `contentPadding.topDp` addresses one edge of an object-valued property; the wire still
    // carries the whole value, so everything below works on the base name.
    val baseName = propertyName.substringBefore('.')
    val edgeName = propertyName.substringAfter('.', missingDelimiterValue = "").ifEmpty { null }
    val property = catalog.componentsById[node.componentId]?.propertiesByName?.get(baseName)
    if (property == null) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "Property $baseName is not declared by ${node.componentId}",
        nodeId,
        baseName,
      )
    }
    val field =
      propertyFields(state.copy(selection = listOf(nodeId))).firstOrNull { it.name == propertyName }
        ?: return state
    val parsed = field.parseDraft(draft)
    if (parsed is PropertyDraft.Invalid) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        parsed.message,
        nodeId,
        baseName,
      )
    }
    val value = (parsed as PropertyDraft.Valid).value
    // Every selected node declaring this property, so a multi-selection is one edit. The edited
    // node is always included, even outside the selection.
    val targets =
      (state.selection.filter { it != nodeId } + nodeId).mapNotNull { id ->
        state.document.nodes[id]?.takeIf {
          catalog.componentsById[it.componentId]?.propertiesByName?.containsKey(baseName) == true
        }
      }
    val operations = mutableListOf<DesignOperation>()
    targets.forEach { target ->
      // Each node keeps its own encoded type. Two nodes can hold the same property as a literal and
      // as a token, and rewriting one to the other's shape would change more than was asked.
      val existingValue = target.properties[baseName] as? JsonObject
      val existingType =
        property.canonicalWrapper(existingValue?.get("type")?.primitiveOrNull()?.contentOrNull)
      val encoded =
        if (edgeName == null) {
          // A colour's wrapper follows the value: a theme role is `colorToken`, a literal is
          // `color`, as the export reads them.
          val type =
            if (field.control == EditorPropertyControl.Color) colourWrapper(value)
            else existingType ?: field.defaultEncodedType()
          literal(type, value)
        } else
          objectValueWithEdge(
            kind = property.editor?.objectKind ?: existingType.orEmpty(),
            existing = existingValue,
            edgeName = edgeName,
            edgeValue = value,
          )
            ?: return state.rejected(
              sequence,
              RejectionCode.INVALID_PROPERTY,
              "${field.label} cannot be safely edited from its catalog metadata",
              target.id,
              baseName,
            )
      // Validated per node: a value can be legal on one component and not another, and one
      // rejection rejects the whole batch.
      validator.validate(state.document, target.id, baseName, encoded)?.let { issue ->
        return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          issue.message,
          target.id,
          baseName,
        )
      }
      operations += DesignOperation.SetProperty(target.id, baseName, encoded)
    }
    if (operations.isEmpty()) return state
    return state
      .apply(sequence, operations, selectionAfter = nodeId)
      // One edit across a selection must not collapse that selection to the node whose field was
      // typed in, or the next edit would silently apply to one node.
      .let { edited ->
        if (targets.size > 1) edited.copy(selection = targets.map(UiBuilderNode::id)) else edited
      }
  }

  /**
   * Attach an import as the base, extracting its boxes once so every route uses the same reader.
   * Only mode and visibility carry over from the previous attachment.
   */
  private fun attached(state: UiBuilderEditorState, image: ReferenceImage): ReferenceOverlayState =
    state.reference.copy(
      image = image,
      settings =
        ReferenceOverlaySettings(
          mode = state.reference.settings.mode,
          visible = true,
          alwaysShowBoxes = state.reference.settings.alwaysShowBoxes,
          // The fit is a fact about the picture, so a new picture chooses its own.
          fit = state.referenceFacts(image)?.recommendedFit ?: ReferenceFit.Contain,
        ),
      layoutBoxes = image.svgTextOrNull()?.let(::extractSvgLayoutBoxes).orEmpty(),
    )

  private fun addMark(
    state: UiBuilderEditorState,
    kind: ReferenceMarkupKind,
    points: List<Float>,
  ): UiBuilderEditorState {
    val minted = state.reference.mintedIds + 1
    val mark =
      ReferenceMark(
        id = "mark-$minted",
        kind = kind,
        points = points,
        colorArgb = state.reference.markupColorArgb,
        // Only the kinds that draw words carry them, so rubbing out a box does not silently take a
        // label that was never on it.
        text =
          state.reference.markupText.takeIf {
            it.isNotBlank() &&
              (kind == ReferenceMarkupKind.Text || kind == ReferenceMarkupKind.ImagePlaceholder)
          },
      )
    if (!mark.drawable) return state
    return state.withReference(
      state.reference.copy(marks = state.reference.marks + mark, mintedIds = minted)
    )
  }

  /**
   * Drop a piece centred at a fixed fraction of the frame, keeping its aspect ratio where known.
   * Not its pixel size: the reducer has no frame in pixels, and imports arrive at another tool's
   * resolution.
   */
  private fun placePiece(
    state: UiBuilderEditorState,
    image: ReferenceImage,
    componentId: String? = null,
  ): UiBuilderEditorState {
    val minted = state.reference.mintedIds + 1
    val aspect =
      if (image.widthPx > 0 && image.heightPx > 0) {
        image.heightPx.toFloat() / image.widthPx.toFloat()
      } else 1f
    val width = PLACED_PIECE_WIDTH_FRACTION
    val height = (width * aspect).coerceIn(ReferencePiece.MIN_PIECE_FRACTION, 1f)
    val piece =
      ReferencePiece(
        id = "piece-$minted",
        image = image,
        left = (1f - width) / 2f,
        top = (1f - height) / 2f,
        right = (1f + width) / 2f,
        bottom = (1f + height) / 2f,
        componentId = componentId,
      )
    return state.withReference(
      state.reference.copy(
        pieces = state.reference.pieces + piece,
        selectedPieceId = piece.id,
        // In hand immediately: a component dropped in the middle of the frame is never where it
        // belongs, and the next thing anyone does is drag it.
        tool = ReferenceTool.MovePiece,
        mintedIds = minted,
        settings = state.reference.settings.copy(visible = true),
      )
    )
  }

  /**
   * The slot a piece would be built into: the deepest accepting slot under [point] in the rendered
   * layout (not the selection), falling back to [dropTarget].
   */
  fun promotionTarget(
    state: UiBuilderEditorState,
    componentId: String,
    slots: List<UiBuilderSlotInspection>,
    pointX: Float,
    pointY: Float,
  ): ParentSlot? {
    val component = catalog.componentsById[componentId] ?: return null
    val hit =
      slots
        .filter { slot ->
          val bounds = slot.bounds ?: return@filter false
          pointX >= bounds.x &&
            pointX <= bounds.right &&
            pointY >= bounds.y &&
            pointY <= bounds.bottom
        }
        .filter { slot -> acceptsComponent(state.document, slot, component) }
        .minByOrNull { slot -> requireNotNull(slot.bounds).let { it.width * it.height } }
    return hit?.let { ParentSlot(it.parentNodeId, it.slotName) }
      ?: findDestination(state.document, state.selectedNodeId, component)
  }

  /**
   * The compatible slot physically under a catalog drag, without a selection fallback. Empty or
   * unmaterialized slots use their parent's bounds; the smallest matching region wins.
   */
  fun catalogDropTarget(
    state: UiBuilderEditorState,
    componentId: String,
    slots: List<UiBuilderSlotInspection>,
    nodeBounds: Map<String, UiBuilderPixelBounds>,
    pointX: Float,
    pointY: Float,
  ): ParentSlot? = catalogDropPlan(state, componentId, slots, nodeBounds, pointX, pointY)?.target

  /**
   * Where a catalog drag at a point would land: the slot ([catalogDropTarget]) and the seam between
   * its children.
   */
  fun catalogDropPlan(
    state: UiBuilderEditorState,
    componentId: String,
    slots: List<UiBuilderSlotInspection>,
    nodeBounds: Map<String, UiBuilderPixelBounds>,
    pointX: Float,
    pointY: Float,
  ): UiBuilderDropPlan? {
    val component = catalog.componentsById[componentId] ?: return null
    return dropSlotUnderPoint(state, pointX, pointY, slots, nodeBounds) {
        acceptsComponent(state.document, it, component)
      }
      ?.let { (target, bounds) -> dropPlan(state, target, bounds, nodeBounds, pointX, pointY) }
  }

  /**
   * Where a canvas move of [nodeId] at a point would land, or null while no legal slot is under the
   * pointer. Legality is [moveRefusal], as in the layers panel.
   */
  fun canvasMovePlan(
    state: UiBuilderEditorState,
    nodeId: String,
    slots: List<UiBuilderSlotInspection>,
    nodeBounds: Map<String, UiBuilderPixelBounds>,
    pointX: Float,
    pointY: Float,
  ): UiBuilderDropPlan? {
    if (nodeId !in state.document.nodes) return null
    return dropSlotUnderPoint(state, pointX, pointY, slots, nodeBounds) {
        moveRefusal(state, nodeId, it) == null
      }
      ?.let { (target, bounds) ->
        dropPlan(state, target, bounds, nodeBounds, pointX, pointY, exclude = nodeId)
      }
  }

  /**
   * The smallest slot region containing the point that answers to [legal], or null. Containment is
   * checked first because it is cheaper.
   *
   * A slot's region is the container it fills, except where a parent declares several slots: then
   * child unions distinguish them, and unmaterialized slots fall back to the parent's box.
   */
  private fun dropSlotUnderPoint(
    state: UiBuilderEditorState,
    pointX: Float,
    pointY: Float,
    slots: List<UiBuilderSlotInspection>,
    nodeBounds: Map<String, UiBuilderPixelBounds>,
    legal: (ParentSlot) -> Boolean,
  ): Pair<ParentSlot, UiBuilderPixelBounds>? {
    val inspectedSlots = slots.associateBy { it.parentNodeId to it.slotName }
    return state.document.nodes.values
      .flatMap { parent ->
        val capability = catalog.componentsById[parent.componentId] ?: return@flatMap emptyList()
        slotRegions(parent, capability, inspectedSlots, nodeBounds[parent.id]).mapNotNull {
          (slotName, bounds) ->
          val target = ParentSlot(parent.id, slotName)
          if (
            pointX < bounds.x ||
              pointX > bounds.right ||
              pointY < bounds.y ||
              pointY > bounds.bottom
          ) {
            return@mapNotNull null
          }
          target to bounds
        }
      }
      .filter { (target, _) -> legal(target) }
      .minWithOrNull(
        compareBy(
          { (_, bounds) -> bounds.width * bounds.height },
          // Equal regions are one region nested in the other: the deeper slot is the more
          // specific answer, and which candidate arrives first must not decide.
          { (target, _) -> state.document.slotDepth(target.nodeId) },
          // Same depth: sibling slots of one parent, so the catalog's recommended slot wins (an
          // empty Scaffold's content).
          { (target, _) -> if (isRecommended(state, target)) 0 else 1 },
        )
      )
  }

  /**
   * The hit-test region of each of [parent]'s declared slots: a populated slot's child union, or
   * for an empty slot the container minus its populated siblings' strips.
   */
  private fun slotRegions(
    parent: UiBuilderNode,
    capability: ComponentCapability,
    inspectedSlots: Map<Pair<String, String>, UiBuilderSlotInspection>,
    container: UiBuilderPixelBounds?,
  ): Map<String, UiBuilderPixelBounds> {
    val populated =
      capability.slots.mapNotNull { declared ->
        val children = parent.slots[declared.name].orEmpty()
        if (children.isEmpty()) return@mapNotNull null
        inspectedSlots[parent.id to declared.name]?.bounds
      }
    return capability.slots
      .mapNotNull { declared ->
        val children = parent.slots[declared.name].orEmpty()
        val union = inspectedSlots[parent.id to declared.name]?.bounds
        val bounds =
          when {
            children.isNotEmpty() -> union
            capability.slots.size == 1 -> container ?: union
            container != null -> emptySlotRegion(container, populated)
            else -> union
          } ?: return@mapNotNull null
        declared.name to bounds
      }
      .toMap()
  }

  /**
   * The part of [container] an empty slot keeps once edge-hugging sibling strips (bars, rails) are
   * removed. Other shapes are left alone so the region never shrinks to nothing.
   */
  private fun emptySlotRegion(
    container: UiBuilderPixelBounds,
    populated: List<UiBuilderPixelBounds>,
  ): UiBuilderPixelBounds {
    var left = container.x
    var top = container.y
    var right = container.right
    var bottom = container.bottom
    populated.forEach { strip ->
      val spansWidth = strip.width >= (right - left) * 0.9f
      val spansHeight = strip.height >= (bottom - top) * 0.9f
      // A strip hugs its nearer edge, not the one its centre is on, so tall content below a top bar
      // still counts as top.
      val topGap = strip.y - top
      val bottomGap = bottom - strip.bottom
      val leftGap = strip.x - left
      val rightGap = right - strip.right
      when {
        spansWidth && topGap <= bottomGap -> top = maxOf(top, strip.bottom)
        spansWidth -> bottom = minOf(bottom, strip.y)
        spansHeight && leftGap <= rightGap -> left = maxOf(left, strip.right)
        spansHeight -> right = minOf(right, strip.x)
      }
    }
    return UiBuilderPixelBounds(
      x = left,
      y = top,
      width = (right - left).coerceAtLeast(0f),
      height = (bottom - top).coerceAtLeast(0f),
    )
  }

  /**
   * The empty recommended slots the canvas draws an editor-only placeholder at, one per component;
   * the rest stay droppable via [slotRegions].
   */
  fun slotPlaceholders(
    state: UiBuilderEditorState,
    slots: List<UiBuilderSlotInspection>,
    nodeBounds: Map<String, UiBuilderPixelBounds>,
  ): List<UiBuilderSlotPlaceholder> {
    val inspectedSlots = slots.associateBy { it.parentNodeId to it.slotName }
    return state.document.nodes.values.flatMap { parent ->
      val capability = catalog.componentsById[parent.componentId] ?: return@flatMap emptyList()
      val recommended = catalog.recommendedSlot(parent.componentId) ?: return@flatMap emptyList()
      if (parent.slots[recommended].orEmpty().isNotEmpty()) return@flatMap emptyList()
      slotRegions(parent, capability, inspectedSlots, nodeBounds[parent.id])
        .filterKeys { it == recommended }
        .map { (slotName, bounds) ->
          UiBuilderSlotPlaceholder(ParentSlot(parent.id, slotName), bounds)
        }
    }
  }

  /** Whether [target] names the slot its catalog recommends for the component that owns it. */
  private fun isRecommended(state: UiBuilderEditorState, target: ParentSlot): Boolean {
    val parent =
      catalog.componentsById[state.document.nodes[target.nodeId]?.componentId ?: return false]
        ?: return false
    return catalog.recommendedSlot(parent.componentId) == target.slot
  }

  /** How many ancestors [nodeId] has — the depth the equal-region tie-break prefers. */
  private fun UiBuilderDocument.slotDepth(nodeId: String): Int {
    var depth = 0
    var current: String? = nodeId
    val visited = mutableSetOf<String>()
    while (current != null && visited.add(current)) {
      depth += 1
      current = location(current)?.nodeId
    }
    return depth
  }

  /** The grid the tidy command snaps authored dp values to. */
  private val TIDY_GRID_DP = 4

  /**
   * The literal with its dp value snapped to the grid, or null. Only plain numbers move, and
   * on-grid values are untouched (including int vs decimal spelling).
   */
  private fun JsonObject.snappedDpLiteral(): JsonObject? {
    val snapped = snappedDpNumber(get("value") ?: return null) ?: return null
    return JsonObject(this + ("value" to snapped))
  }

  private fun snappedDpNumber(value: JsonElement): JsonElement? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.doubleOrNull ?: return null
    val snapped = (number / TIDY_GRID_DP).roundToInt() * TIDY_GRID_DP.toDouble()
    if (snapped == number) return null
    return if (primitive.content.contains('.') || primitive.content.contains('e')) {
      JsonPrimitive(snapped)
    } else {
      JsonPrimitive(snapped.toInt())
    }
  }

  /**
   * The seam of [target] the pointer sits against. Children are ordered along the measured axis
   * (disjoint on one coordinate, overlapping on the other); tangled or single-child slots use the
   * slot's own shape. Lands before the first child whose centre is past the pointer, else after the
   * last.
   */
  private fun dropPlan(
    state: UiBuilderEditorState,
    target: ParentSlot,
    bounds: UiBuilderPixelBounds,
    nodeBounds: Map<String, UiBuilderPixelBounds>,
    pointX: Float,
    pointY: Float,
    /** A child to ignore: the node a canvas move picked up, which must not be its own neighbour. */
    exclude: String? = null,
  ): UiBuilderDropPlan {
    val measured =
      state.document
        .children(target)
        .filter { it != exclude }
        .mapNotNull { child -> nodeBounds[child]?.let { child to it } }
    val axis = dropAxis(measured.map(Pair<String, UiBuilderPixelBounds>::second), bounds)
    val ordered = measured.sortedBy { (_, childBounds) ->
      if (axis == UiBuilderDropAxis.Horizontal) childBounds.x else childBounds.y
    }
    val pointer = if (axis == UiBuilderDropAxis.Horizontal) pointX else pointY
    val index =
      ordered
        .indexOfFirst { (_, childBounds) ->
          val centre =
            if (axis == UiBuilderDropAxis.Horizontal) childBounds.x + childBounds.width / 2f
            else childBounds.y + childBounds.height / 2f
          pointer < centre
        }
        .let { if (it < 0) ordered.size else it }
    return UiBuilderDropPlan(
      target = target,
      afterNodeId = ordered.getOrNull(index - 1)?.first,
      index = index,
      bounds = bounds,
      children = ordered,
      axis = axis,
    )
  }

  /** How a slot's children run, read off the geometry rather than trusted to the document. */
  private fun dropAxis(
    children: List<UiBuilderPixelBounds>,
    slot: UiBuilderPixelBounds,
  ): UiBuilderDropAxis {
    if (children.size >= 2) {
      val disjointX =
        children.sortedBy(UiBuilderPixelBounds::x).zipWithNext { above, below ->
          above.right <= below.x + 0.5f
        }
      val disjointY =
        children.sortedBy(UiBuilderPixelBounds::y).zipWithNext { above, below ->
          above.bottom <= below.y + 0.5f
        }
      return when {
        disjointX.all { it } && !disjointY.all { it } -> UiBuilderDropAxis.Horizontal
        disjointY.all { it } && !disjointX.all { it } -> UiBuilderDropAxis.Vertical
        else ->
          if (slot.width >= slot.height) UiBuilderDropAxis.Horizontal
          else UiBuilderDropAxis.Vertical
      }
    }
    return if (slot.width >= slot.height) UiBuilderDropAxis.Horizontal
    else UiBuilderDropAxis.Vertical
  }

  /** Whether the node owning [slot] declares it, accepts [component] there, and has room. */
  private fun acceptsComponent(
    document: UiBuilderDocument,
    slot: UiBuilderSlotInspection,
    component: ComponentCapability,
  ): Boolean {
    return acceptsComponent(document, ParentSlot(slot.parentNodeId, slot.slotName), component)
  }

  /**
   * Whether [target] is a real, compatible slot in [document]. Validated here rather than against
   * the selection, since a canvas drop resolves its target from pointer geometry.
   */
  private fun acceptsComponent(
    document: UiBuilderDocument,
    target: ParentSlot,
    component: ComponentCapability,
  ): Boolean {
    val parent = document.nodes[target.nodeId] ?: return false
    val capability = catalog.componentsById[parent.componentId] ?: return false
    val declared = capability.slot(target.slot) ?: return false
    return declared.accepts(component) && declared.hasRoom(parent.slots[target.slot].orEmpty().size)
  }

  private fun promotePiece(
    state: UiBuilderEditorState,
    pieceId: String,
    target: ParentSlot,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val piece =
      state.reference.pieces.firstOrNull { it.id == pieceId }
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_LOCATION,
          "no such reference piece",
        )
    val componentId =
      piece.componentId
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_LOCATION,
          "This piece is a picture, not a component — nothing here says what to build.",
        )
    val component =
      catalog.componentsById[componentId]
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_LOCATION,
          "This catalog no longer offers $componentId",
        )
    if (!acceptsComponent(state.document, target.asInspectionSlot(), component)) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_LOCATION,
        "${component.displayName} does not belong in ${target.nodeId}.${target.slot}",
      )
    }
    val inserted = insertAt(state, component, target)
    // Only when the insertion was actually accepted: a refused promote must leave the piece where
    // it is, or the operator loses the picture *and* gets no component.
    if (inserted.lastOutcome !is CommandOutcome.Accepted) return inserted
    return inserted.withReference(
      inserted.reference.copy(
        pieces = inserted.reference.pieces.filterNot { it.id == pieceId },
        selectedPieceId = inserted.reference.selectedPieceId.takeIf { it != pieceId },
      )
    )
  }

  private fun updateEnvironment(
    state: UiBuilderEditorState,
    settings: ScreenEnvironmentSettings,
  ): UiBuilderEditorState {
    state.document.screenEnvironmentValidationError(settings)?.let { message ->
      return state.rejected(
        state.operationSequence + 1,
        RejectionCode.INVALID_DOCUMENT,
        message,
      )
    }
    val values =
      linkedMapOf<String, JsonElement>(
        "widthDp" to JsonPrimitive(settings.widthDp),
        "heightDp" to JsonPrimitive(settings.heightDp),
        "density" to JsonPrimitive(settings.density),
        "fontScale" to JsonPrimitive(settings.fontScale),
        "locale" to JsonPrimitive(settings.locale),
        "theme" to JsonPrimitive(settings.theme.wireValue),
        "layoutDirection" to JsonPrimitive(settings.layoutDirection.wireValue),
      )
    // Only when changed: an absent key and an empty array are different JSON but the same answer,
    // and writing it would turn single-field edits into two-field ones.
    if (settings.exportDevices != state.document.screenEnvironmentSettings().exportDevices) {
      values["exportDevices"] = JsonArray(settings.exportDevices.map(::JsonPrimitive))
    }
    // Same only-when-changed rule; a cleared family is written as JSON null, which the bridge sends
    // as the reset.
    val typeface = settings.typeface?.takeIf { it.isNotBlank() }
    if (typeface != state.document.screenEnvironmentSettings().typeface) {
      values["typeface"] = typeface?.let(::JsonPrimitive) ?: JsonNull
    }
    val operations = values.mapNotNull { (field, value) ->
      DesignOperation.SetEnvironment(field, value).takeIf {
        state.document.environment[field] != value
      }
    }
    if (operations.isEmpty()) return state
    return state.apply(
      state.operationSequence + 1,
      operations,
      selectionAfter = state.selectedNodeId,
    )
  }

  private fun applyTheme(
    state: UiBuilderEditorState,
    settings: EditorThemeSettings,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val host =
      state.document.themeHost()
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_DOCUMENT,
          "A root Material surface is required to host design theme settings",
        )
    val colors =
      listOf(
        THEME_PRIMARY to settings.primaryColor,
        THEME_BACKGROUND to settings.backgroundColor,
        THEME_SURFACE to settings.surfaceColor,
        THEME_CONTENT to settings.contentColor,
      )
    colors
      .firstOrNull { !it.second.isArgbColor() }
      ?.let { invalid ->
        return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "${invalid.first} must be #RRGGBB or #AARRGGBB; received '${invalid.second}'",
        )
      }
    if (settings.typeScale !in 0.75f..1.5f) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "Type scale must be between 0.75 and 1.5",
      )
    }
    if (settings.cornerRadiusDp !in 0f..48f) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "Corner radius must be between 0 and 48dp",
      )
    }
    val operations =
      colors.map { (property, value) ->
        DesignOperation.SetProperty(host.id, property, literal("color", JsonPrimitive(value)))
      } +
        DesignOperation.SetProperty(
          host.id,
          THEME_TYPE_SCALE,
          literal("float", JsonPrimitive(settings.typeScale)),
        ) +
        DesignOperation.SetProperty(
          host.id,
          THEME_CORNER_RADIUS,
          literal("float", JsonPrimitive(settings.cornerRadiusDp)),
        )
    return state.apply(sequence, operations, selectionAfter = state.selectedNodeId)
  }

  /**
   * [roles] onto the theme host's colour properties and the catalog's `color.*` tokens, as one
   * edit. Tokens first, so the host's write (same value) is the one kept on overlap.
   */
  private fun applyColorScheme(
    state: UiBuilderEditorState,
    roles: Map<String, String>,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    roles.entries
      .firstOrNull { !it.value.isArgbColor() }
      ?.let { (role, value) ->
        return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "$role must be #RRGGBB or #AARRGGBB; received '$value'",
        )
      }
    val host = themePanelHost(state)
    val tokenValues = ThemeSchemes.tokenValues(roles, catalog.designTokens)
    val tokens = catalog.designTokens.associateBy { it.id }
    val operations = mutableListOf<DesignOperation>()
    tokenValues.forEach { (id, value) ->
      val token = tokens.getValue(id)
      token.parse(value).onSuccess { operations += token.writes(state.document, catalog, it) }
    }
    if (host != null) {
      ThemeSchemes.hostWrites(roles, host.colorProperties).forEach { (property, value) ->
        operations +=
          DesignOperation.SetProperty(host.nodeId, property, literal("color", JsonPrimitive(value)))
      }
    }
    // One write per property: the last, as applying them in turn would leave it.
    val last =
      operations.asReversed().distinctBy { operation ->
        when (operation) {
          is DesignOperation.SetProperty -> operation.nodeId to operation.property
          is DesignOperation.RemoveNodeProperty -> operation.nodeId to operation.property
          else -> operation
        }
      }
    if (last.isEmpty()) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_DOCUMENT,
        "Nothing in this design holds a theme colour: add a theme host or use a catalog with colour tokens",
      )
    }
    return state.apply(sequence, last.asReversed(), selectionAfter = state.selectedNodeId)
  }

  /**
   * Where [UiBuilderEditorEvent.Tidy] would move values onto the grid, and how many. Pure, so the
   * toolbar can count before building the command.
   */
  fun tidyPlan(state: UiBuilderEditorState): UiBuilderTidyPlan {
    val document = state.document
    // The selection's subtree when there is one — "tidy this card" means the card and everything
    // in it — and the whole design otherwise.
    val scope =
      state.selectedNodeId?.let { selected -> document.subtreeOf(selected) } ?: document.nodes.keys
    val operations = mutableListOf<DesignOperation>()
    var changedValues = 0
    scope.sorted().forEach { nodeId ->
      val node = document.nodes[nodeId] ?: return@forEach
      val capability = catalog.componentsById[node.componentId]
      // Only catalog-declared dp numbers; bindings, colours and enums have no place on a grid.
      capability
        ?.properties
        ?.filter { it.name.endsWith("Dp") }
        ?.forEach { property ->
          val current = node.properties[property.name] as? JsonObject ?: return@forEach
          val snapped = current.snappedDpLiteral() ?: return@forEach
          changedValues += 1
          operations += DesignOperation.SetProperty(nodeId, property.name, snapped)
        }
      // The layout modifiers carry the other half of the authored numbers: a size's width and
      // height, a padding's four edges, an offset's pair.
      var modifiersChanged = false
      val snappedModifiers =
        JsonArray(
          node.modifiers.map { modifier ->
            if (modifier !is JsonObject) return@map modifier
            var touched = 0
            val rebuilt =
              JsonObject(
                modifier.mapValues { (field, value) ->
                  if (!field.endsWith("Dp")) return@mapValues value
                  val snapped = snappedDpNumber(value) ?: return@mapValues value
                  if (snapped != value) touched += 1
                  snapped
                }
              )
            changedValues += touched
            if (touched > 0) modifiersChanged = true
            rebuilt
          }
        )
      if (modifiersChanged) operations += DesignOperation.SetModifiers(nodeId, snappedModifiers)
    }
    return UiBuilderTidyPlan(operations, changedValues)
  }

  private fun tidy(state: UiBuilderEditorState): UiBuilderEditorState {
    val plan = tidyPlan(state)
    // Nothing off the grid, nothing to do: no operation, no revision, no undo step, and no
    // collaborator watching an empty command arrive.
    if (plan.operations.isEmpty()) return state
    return state.apply(
      state.operationSequence + 1,
      plan.operations,
      selectionAfter = state.selectedNodeId,
    )
  }

  private fun deleteSelected(state: UiBuilderEditorState): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val targets = state.selectionRoots()
    if (targets.isEmpty()) return state
    if (!canDeleteSelected(state)) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_DOCUMENT,
        if (targets.size == 1) "Deleting ${targets.single()} would violate root or slot cardinality"
        else "Deleting these ${targets.size} nodes would violate root or slot cardinality",
      )
    }
    val anchor = targets.last()
    val selectionAfter =
      state.document.location(anchor)?.nodeId ?: state.document.roots.firstOrNull { it !in targets }
    // One apply for the whole selection, so a multi-node delete is one undo step rather than
    // several the user has to unwind one at a time.
    return state.apply(
      sequence = sequence,
      operations = targets.map(DesignOperation::DeleteNode),
      selectionAfter = selectionAfter,
    )
  }

  private fun duplicateSelected(state: UiBuilderEditorState): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val targets = state.selectionRoots()
    if (targets.isEmpty()) return state
    if (!canDuplicateSelected(state)) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_DOCUMENT,
        if (targets.size == 1) "Duplicating ${targets.single()} would exceed slot cardinality"
        else "Duplicating these ${targets.size} nodes would exceed slot cardinality",
      )
    }
    val operations = mutableListOf<DesignOperation>()
    val copies = mutableListOf<String>()
    // Every id this batch allocates, so two copies in one duplicate cannot collide with each other
    // or with anything the document already holds.
    val taken = state.document.takenIdentities()
    targets.forEach { nodeId ->
      val copyId =
        freshCopyId(state.document.freshNodeId("$nodeId-copy", operationIdPrefix, sequence), taken)
      state.document.nodes.appendDuplicateSubtree(
        sourceNodeId = nodeId,
        copyNodeId = copyId,
        parent = state.document.location(nodeId),
        // Beside its own original, so a duplicated group stays interleaved with the group it came
        // from rather than piling up at the end of the slot.
        afterNodeId = nodeId,
        operations = operations,
        taken = taken,
      )
      copies += copyId
    }
    return state.apply(sequence, operations, selectionAfter = copies.last()).let { duplicated ->
      // The copies are what you want to move next, so they are what stays selected.
      if (duplicated.selection == listOf(copies.last())) duplicated.copy(selection = copies)
      else duplicated
    }
  }

  private fun toggleNode(state: UiBuilderEditorState, nodeId: String): UiBuilderEditorState {
    if (nodeId !in state.document.nodes) return state
    // Re-adding moves it to the end so it becomes the anchor, which is what a click means even
    // when the node was already in the selection.
    val selection =
      if (nodeId in state.selection) state.selection - nodeId else state.selection + nodeId
    return state.copy(selection = selection)
  }

  private fun extendSelection(state: UiBuilderEditorState, nodeId: String): UiBuilderEditorState {
    if (nodeId !in state.document.nodes) return state
    val anchor = state.selectedNodeId ?: return state.copy(selection = listOf(nodeId))
    val order = visibleTreeRows(state).map(EditorTreeRow::nodeId)
    val from = order.indexOf(anchor)
    val to = order.indexOf(nodeId)
    if (from < 0 || to < 0) return state.copy(selection = listOf(nodeId))
    // Tree order, not document order: a range is what the user swept over in the panel.
    val range = order.subList(minOf(from, to), maxOf(from, to) + 1)
    // The clicked node ends up last so it becomes the anchor a further shift-click extends from.
    return state.copy(selection = (range - nodeId) + nodeId)
  }

  private fun selectRelative(
    state: UiBuilderEditorState,
    move: EditorSelectionMove,
  ): UiBuilderEditorState {
    // The visible rows, not the whole tree: an arrow press should land where the panel shows the
    // next row, and while a filter is on that is not the same list.
    val rows = visibleTreeRows(state)
    if (rows.isEmpty()) return state
    val index = rows.indexOfFirst { it.nodeId == state.selectedNodeId }
    // Nothing selected yet: any step starts at the top, which is what a first arrow press means.
    if (index < 0) return state.copy(selection = listOf(rows.first().nodeId))
    val row = rows[index]
    val target =
      when (move) {
        EditorSelectionMove.Next -> rows.getOrNull(index + 1)?.nodeId
        EditorSelectionMove.Previous -> rows.getOrNull(index - 1)?.nodeId
        EditorSelectionMove.Parent -> row.parent?.nodeId
        // The next row is the first child exactly when it is one level deeper; a sibling or an
        // uncle is not something "go into this container" should select.
        EditorSelectionMove.FirstChild ->
          rows.getOrNull(index + 1)?.takeIf { it.depth == row.depth + 1 }?.nodeId
      }
    return target?.let { state.copy(selection = listOf(it)) } ?: state
  }

  /**
   * Reorder the selection among its siblings via the same [moveTarget] as layer drag, for keyboard
   * and screen-reader users.
   */
  private fun moveSelected(
    state: UiBuilderEditorState,
    direction: EditorMoveDirection,
  ): UiBuilderEditorState {
    val nodeId = state.selectedNodeId?.takeIf(state.document.nodes::containsKey) ?: return state
    val move = moveTarget(state, nodeId, direction) ?: return state
    return move(state, move)
  }

  /**
   * Link [target] to the tunable named [into], or to a new tunable seeded from the target's value
   * with a range inside the catalog's bounds. A target has one tunable at a time, so linking moves
   * it.
   */
  private fun tuneTarget(
    state: UiBuilderEditorState,
    target: TunableTarget,
    into: String?,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val fieldName =
      when (target) {
        is TunableTarget.Property -> target.property
        is TunableTarget.Modifier -> "modifiers"
      }
    fun refused(message: String) =
      state.rejected(sequence, RejectionCode.INVALID_PROPERTY, message, target.nodeId, fieldName)
    val field =
      (target as? TunableTarget.Property)?.let { property ->
        propertyFields(state.copy(selection = listOf(property.nodeId))).firstOrNull {
          it.name == property.property
        }
      }
    if (
      !state.document.canTune(target) ||
        (target is TunableTarget.Property &&
          (field?.control != EditorPropertyControl.Number || '.' in target.property))
    ) {
      return refused("Only a plain number can be tuned")
    }
    val others = state.tunables.map { it.copy(targets = it.targets - target) }
    // An `int` target only takes whole numbers, so a tunable it joins must move in whole steps too;
    // otherwise the slider shows 0.5 while the target is drawn, and written, as 1.
    val wholeTarget =
      field?.numberBounds?.integer == true ||
        ((target as? TunableTarget.Property)
          ?.let { state.document.nodes[it.nodeId]?.properties?.get(it.property) as? JsonObject }
          ?.get("type") == JsonPrimitive("int"))
    if (into != null) {
      val joined =
        others.firstOrNull { it.name == into } ?: return refused("There is no tunable called $into")
      val whole =
        if (!wholeTarget || joined.integer) joined
        else {
          val low = ceil(joined.minimum)
          val high = floor(joined.maximum)
          if (low >= high) return refused("$into has no whole numbers for ${target.label} to take")
          joined.copy(
            integer = true,
            minimum = low,
            maximum = high,
            default = round(joined.default).coerceIn(low, high),
          )
        }
      return state.copy(
        tunables =
          others.map { if (it.name == into) whole.copy(targets = it.targets + target) else it },
        tunedValues =
          state.tunedValues[into]?.let { state.tunedValues + (into to whole.coerce(it)) }
            ?: state.tunedValues,
      )
    }
    if (others.size >= MAX_DESIGN_TUNABLES) {
      return refused("A design has at most $MAX_DESIGN_TUNABLES tunables")
    }
    val bounds =
      when (target) {
        is TunableTarget.Property -> field?.numberBounds
        is TunableTarget.Modifier -> MODIFIER_TUNING_BOUNDS[target.type]
      }
    val current =
      state.document.heldValue(target)
        ?: field?.value?.toDoubleOrNull()
        ?: bounds?.minimum?.coerceAtLeast(0.0)
        ?: 0.0
    val (minimum, maximum) =
      if (target is TunableTarget.Modifier && bounds != null) bounds.minimum to bounds.maximum
      else suggestedTunableRange(current, bounds)
    val label =
      when (target) {
        is TunableTarget.Property -> field?.label ?: target.label
        is TunableTarget.Modifier -> target.label
      }
    val tunable =
      DesignTunable(
        name = freshTunableName(label, others.map(DesignTunable::name)),
        minimum = minimum,
        maximum = maximum,
        default = current.coerceIn(minimum, maximum),
        integer = bounds?.integer == true,
        targets = listOf(target),
      )
    return state.copy(tunables = others + tunable)
  }

  /**
   * Replace the tunable [name] with [edited], keeping its slider position (clamped) under the new
   * name. Renaming onto another tunable's name is refused.
   */
  private fun editTunable(
    state: UiBuilderEditorState,
    name: String,
    edited: DesignTunable,
  ): UiBuilderEditorState {
    if (state.tunables.none { it.name == name }) return state
    if (edited.name != name && state.tunables.any { it.name == edited.name }) {
      return state.rejected(
        state.operationSequence + 1,
        RejectionCode.INVALID_PROPERTY,
        "There is already a tunable called ${edited.name}",
      )
    }
    val slider = state.tunedValues[name]
    return state.copy(
      tunables = state.tunables.map { if (it.name == name) edited else it },
      tunedValues =
        (state.tunedValues - name).let { values ->
          if (slider == null) values else values + (edited.name to edited.coerce(slider))
        },
    )
  }

  /**
   * Write every tunable's current value into its targets, one lane per event: property writes, then
   * modifier writes, since undo refuses batches mixing them (the editor dispatches both together).
   * Once nothing is left, values become defaults. A refused command leaves the sliders as they
   * were.
   */
  private fun applyTunables(state: UiBuilderEditorState): UiBuilderEditorState {
    val settled = state.tunables.map { it.copy(default = state.tunedValues.valueOf(it)) }
    val writes = state.document.tunableWrites(state.tunables, state.tunedValues)
    val propertyWrites = writes.flatMap { (nodeId, tuned) ->
      val held = state.document.nodes.getValue(nodeId)
      tuned.properties
        .filter { (property, value) -> held.properties[property] != value }
        .map { (property, value) -> DesignOperation.SetProperty(nodeId, property, value) }
    }
    val modifierWrites = writes.mapNotNull { (nodeId, tuned) ->
      DesignOperation.SetModifiers(nodeId, tuned.modifiers).takeIf {
        tuned.modifiers != state.document.nodes.getValue(nodeId).modifiers
      }
    }
    val lane = propertyWrites.ifEmpty { modifierWrites }
    if (lane.isEmpty()) return state.copy(tunables = settled, tunedValues = emptyMap())
    val applied = state.apply(state.operationSequence + 1, lane, state.selectedNodeId)
    if (applied.lastOutcome !is CommandOutcome.Accepted) return applied
    val remaining = applied.document.tunableWrites(state.tunables, state.tunedValues)
    return if (remaining.isEmpty()) applied.copy(tunables = settled, tunedValues = emptyMap())
    else applied
  }

  /** The catalog's design tokens as this design reads them, for the Theme panel. */
  fun designTokenRows(state: UiBuilderEditorState): List<EditorDesignTokenRow> =
    catalog.designTokens.map { token ->
      EditorDesignTokenRow(
        token = token,
        value = token.valueIn(state.document, catalog),
        targetCount = token.targets(state.document, catalog).size,
      )
    }

  /**
   * Write [value] into every property the token binds as one command, so one undo reverts it. Null
   * resets; see [DesignToken.writes].
   */
  private fun applyDesignToken(
    state: UiBuilderEditorState,
    tokenId: String,
    value: String?,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val token = catalog.designTokens.firstOrNull { it.id == tokenId } ?: return state
    fun refused(message: String) =
      state.rejected(sequence, RejectionCode.INVALID_PROPERTY, message, field = tokenId)
    val parsed = value?.let {
      token.parse(it).getOrElse { why ->
        return refused(why.message.orEmpty())
      }
    }
    if (token.targets(state.document, catalog).isEmpty()) {
      return refused(
        "${token.label} has nowhere to land: this design has no " +
          token.bindings.joinToString(" or ") { it.componentId.substringAfterLast('/') }
      )
    }
    val operations = token.writes(state.document, catalog, parsed)
    if (operations.isEmpty()) return state
    return state.apply(sequence, operations, state.selectedNodeId)
  }

  /**
   * Apply every imported token value as one command. Each token's writes see the earlier tokens'
   * effects, so the later one wins on overlap; any invalid value refuses the whole import by name.
   */
  private fun importDesignTokens(
    state: UiBuilderEditorState,
    values: Map<String, String>,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val tokens = catalog.designTokens.associateBy { it.id }
    var document = state.document
    val operations = mutableListOf<DesignOperation>()
    values.forEach { (id, value) ->
      val token = tokens[id] ?: return@forEach
      val parsed =
        token.parse(value).getOrElse { why ->
          return state.rejected(
            sequence,
            RejectionCode.INVALID_PROPERTY,
            why.message.orEmpty(),
            field = id,
          )
        }
      val writes = token.writes(document, catalog, parsed)
      operations += writes
      document =
        writes.filterIsInstance<DesignOperation.SetProperty>().fold(document) { held, write ->
          val node = held.nodes.getValue(write.nodeId)
          held.copy(
            nodes =
              held.nodes +
                (write.nodeId to
                  node.copy(
                    properties = JsonObject(node.properties + (write.property to write.value))
                  ))
          )
        }
    }
    // One write per property: the later token's, as applying them in turn would leave it.
    val last =
      operations.asReversed().distinctBy { operation ->
        when (operation) {
          is DesignOperation.SetProperty -> operation.nodeId to operation.property
          is DesignOperation.RemoveNodeProperty -> operation.nodeId to operation.property
          else -> operation
        }
      }
    if (last.isEmpty()) return state
    return state.apply(sequence, last.asReversed(), state.selectedNodeId)
  }

  /** A tunable over a number token, or the one already driving it; see [DesignToken.tunable]. */
  private fun tuneDesignToken(state: UiBuilderEditorState, tokenId: String): UiBuilderEditorState {
    if (state.tunables.any { it.token == tokenId }) return state
    val sequence = state.operationSequence + 1
    val token = catalog.designTokens.firstOrNull { it.id == tokenId } ?: return state
    if (state.tunables.size >= MAX_DESIGN_TUNABLES) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "A design has at most $MAX_DESIGN_TUNABLES tunables",
        field = tokenId,
      )
    }
    val tunable =
      token.tunable(
        state.document,
        catalog,
        freshTunableName(token.label, state.tunables.map(DesignTunable::name)),
      )
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "Only a number token with a range can be tuned",
          field = tokenId,
        )
    return state.copy(tunables = state.tunables + tunable)
  }

  /**
   * Point a property at a state variable, via `SetProperty`: `{"type": "state", "variable": …}` is
   * a `UiValueV1` the wire, server and renderer already handle.
   */
  private fun bindPropertyToState(
    state: UiBuilderEditorState,
    nodeId: String,
    propertyName: String,
    variable: String,
    equalsValue: String?,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val node = state.document.nodes[nodeId] ?: return state
    if (
      catalog.componentsById[node.componentId]?.propertiesByName?.containsKey(propertyName) != true
    ) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "Property $propertyName is not declared by ${node.componentId}",
        nodeId,
        propertyName,
      )
    }
    if (variable !in state.document.stateVariables) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "This design declares no state variable `$variable`",
        nodeId,
        propertyName,
      )
    }
    val declaration = state.document.stateVariables[variable] as? JsonObject
    if (equalsValue != null) {
      // The operand must have the variable's declared type, or the exported comparison will not
      // compile.
      EditorStateAction.Set(variable, equalsValue).valueRefusal(declaration)?.let { why ->
        return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, why, nodeId, propertyName)
      }
    }
    val encoded =
      if (equalsValue == null)
        JsonObject(mapOf("type" to JsonPrimitive("state"), "variable" to JsonPrimitive(variable)))
      else
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("stateEquals"),
            "variable" to JsonPrimitive(variable),
            "value" to typedStateValue(equalsValue, declaration),
          )
        )
    validator.validate(state.document, nodeId, propertyName, encoded)?.let { issue ->
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        issue.message,
        nodeId,
        propertyName,
      )
    }
    return state.apply(
      sequence,
      listOf(DesignOperation.SetProperty(nodeId, propertyName, encoded)),
      selectionAfter = nodeId,
    )
  }

  /**
   * Set a property to a formula the player evaluates (`time.secondOfHour * 6`, `count + 1`). Parsed
   * against the design's state variables and validated like any write, so a type mismatch is
   * refused here rather than at export.
   */
  private fun bindPropertyToFormula(
    state: UiBuilderEditorState,
    nodeId: String,
    propertyName: String,
    formula: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    if (state.document.nodes[nodeId] == null) return state
    val encoded =
      try {
        UiExpressions.parseFormula(formula, state.document.stateVariables.keys)
      } catch (failure: UiExpressions.FormulaError) {
        return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          failure.message.orEmpty(),
          nodeId,
          propertyName,
        )
      }
    val checked =
      UiExpressions.check(
        encoded,
        UiDrawing.expressionScope(state.document, nodeId),
        propertyName,
      )
    if (checked is UiExpressions.Checked.Issue) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        checked.message,
        nodeId,
        propertyName,
      )
    }
    validator.validate(state.document, nodeId, propertyName, encoded)?.let { issue ->
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        issue.message,
        nodeId,
        propertyName,
      )
    }
    return state.apply(
      sequence,
      listOf(DesignOperation.SetProperty(nodeId, propertyName, encoded)),
      selectionAfter = nodeId,
    )
  }

  /**
   * Give a bound property a literal again; `commitProperty` will not overwrite a binding with a
   * literal.
   */
  private fun clearProperty(
    state: UiBuilderEditorState,
    nodeId: String,
    propertyName: String,
  ): UiBuilderEditorState {
    val node = state.document.nodes[nodeId] ?: return state
    if (propertyName !in node.properties) return state
    val property =
      catalog.componentsById[node.componentId]?.propertiesByName?.get(propertyName) ?: return state
    if (property.required) return state
    return state.apply(
      state.operationSequence + 1,
      listOf(DesignOperation.RemoveNodeProperty(nodeId, propertyName)),
      selectionAfter = nodeId,
    )
  }

  private fun unbindProperty(
    state: UiBuilderEditorState,
    nodeId: String,
    propertyName: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val node = state.document.nodes[nodeId] ?: return state
    val property =
      catalog.componentsById[node.componentId]?.propertiesByName?.get(propertyName) ?: return state
    val current =
      (node.properties[propertyName] as? JsonObject)?.get("type")?.primitiveOrNull()?.contentOrNull
    if (current !in STATE_VALUE_TYPES && current !in COMPUTED_VALUE_TYPES) return state
    // Not `defaultEncodedValue`: some catalog defaults are themselves state bindings.
    val encoded = property.literalDefault()
    validator.validate(state.document, nodeId, propertyName, encoded)?.let { issue ->
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        issue.message,
        nodeId,
        propertyName,
      )
    }
    return state.apply(
      sequence,
      listOf(DesignOperation.SetProperty(nodeId, propertyName, encoded)),
      selectionAfter = nodeId,
    )
  }

  /**
   * The catalog containers that could wrap the selection in place: the parent slot must accept the
   * container and the container every selected node.
   */
  fun wrapCandidates(state: UiBuilderEditorState): List<EditorCatalogItem> {
    val targets = state.selectionRoots()
    if (targets.isEmpty()) return emptyList()
    val parent = wrappableParent(state, targets) ?: return emptyList()
    if (!state.adjacentInParent(targets, parent)) return emptyList()
    val children =
      targets
        .mapNotNull { state.document.nodes[it]?.componentId }
        .mapNotNull(catalog.componentsById::get)
    if (children.size != targets.size) return emptyList()
    return catalog.components
      .filter { container -> wrapSlotOf(container, children, parent, state) != null }
      .map { it.editorCatalogItem() }
      .sortedWith(compareBy(EditorCatalogItem::kind, EditorCatalogItem::displayName))
  }

  /** The one non-root slot every selected node shares, or null. */
  private fun wrappableParent(
    state: UiBuilderEditorState,
    targets: List<String>,
  ): ParentSlot? = targets.map { state.document.location(it) }.distinct().singleOrNull()

  /**
   * Whether the selection is one unbroken run of siblings; wrapping `A` and `C` out of `A, B, C`
   * would silently reorder `B`.
   */
  private fun UiBuilderEditorState.adjacentInParent(
    targets: List<String>,
    parent: ParentSlot,
  ): Boolean {
    val siblings = document.children(parent)
    val positions = targets.map(siblings::indexOf)
    if (positions.any { it < 0 }) return false
    return (positions.max() - positions.min()) == targets.size - 1
  }

  /** The container slot that could take [children], given the container lands in [parent]. */
  private fun wrapSlotOf(
    container: ComponentCapability,
    children: List<ComponentCapability>,
    parent: ParentSlot,
    state: UiBuilderEditorState,
  ): SlotCapability? {
    val parentNode = state.document.nodes[parent.nodeId] ?: return null
    val parentSlot =
      catalog.componentsById[parentNode.componentId]?.slot(parent.slot) ?: return null
    // The container replaces the children it swallows, so the slot's occupancy is unchanged but
    // for the one node it gains — checked against the max the same way an insert is.
    if (!parentSlot.accepts(container)) return null
    val occupancy = parentNode.slots[parent.slot].orEmpty().size - children.size + 1
    if (parentSlot.cardinality.max?.let { occupancy > it } == true) return null
    return container.slots.firstOrNull { slot ->
      children.all { slot.accepts(it) } && slot.hasRoom(children.size - 1)
    }
  }

  private fun wrapSelection(
    state: UiBuilderEditorState,
    componentId: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val targets = state.selectionRoots()
    if (targets.isEmpty()) return state
    val container = catalog.componentsById[componentId] ?: return state
    val parent = wrappableParent(state, targets)
    if (parent == null) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_LOCATION,
        "These nodes are in different places, so there is nowhere to put one container",
      )
    }
    if (!state.adjacentInParent(targets, parent)) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_LOCATION,
        "These nodes are not next to each other, so wrapping them would move what sits between",
      )
    }
    val children =
      targets
        .mapNotNull { state.document.nodes[it]?.componentId }
        .mapNotNull(catalog.componentsById::get)
    val slot = wrapSlotOf(container, children, parent, state)
    if (children.size != targets.size || slot == null) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_LOCATION,
        "${container.displayName} has no slot that accepts this selection",
      )
    }
    val containerId =
      state.document.freshNodeId(
        "editor-${componentId.replace('/', '-')}",
        operationIdPrefix,
        sequence,
      )
    val operations = mutableListOf<DesignOperation>()
    val defaultError =
      container.appendDefaultSubtree(
        catalog = catalog,
        document = state.document,
        nodeId = containerId,
        parent = parent,
        // Where the first wrapped node was, so the container takes the selection's place rather
        // than appearing at the end of the slot and reordering the screen.
        afterNodeId = state.document.children(parent).takeWhile { it !in targets }.lastOrNull(),
        operations = operations,
        // The wrapped nodes are the content; deeper seeded children would be left hanging.
        seedStarterContent = false,
      )
    if (defaultError != null) {
      return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, defaultError)
    }
    // Remove a required slot's placeholder child, but only after the real children are in, so the
    // slot minimum holds mid-batch.
    val placeholders =
      operations.filterIsInstance<DesignOperation.InsertNode>().filter {
        it.parent == ParentSlot(containerId, slot.name)
      }
    // Tree order, not selection order, so the wrapped content is not reordered.
    val ordered = targets.sortedBy(state.document.treeIndex())
    var after: String? = null
    ordered.forEach { nodeId ->
      operations +=
        DesignOperation.MoveNode(
          nodeId = nodeId,
          parent = ParentSlot(containerId, slot.name),
          afterNodeId = after,
        )
      after = nodeId
    }
    placeholders.forEach { operations += DesignOperation.DeleteNode(it.node.id) }
    // One apply: inserting a container and leaving the children outside it is not a state the
    // document should be able to rest in, and undo should not have to be pressed twice.
    return state.apply(sequence, operations, selectionAfter = containerId)
  }

  /**
   * Whether the selected node's children can be lifted into its parent, which must accept all of
   * them and have room for `children - 1` more.
   */
  fun canUnwrapSelected(state: UiBuilderEditorState): Boolean = unwrapPlan(state) != null

  private fun unwrapPlan(state: UiBuilderEditorState): Pair<String, List<String>>? {
    val nodeId =
      state.selection.singleOrNull()?.takeIf(state.document.nodes::containsKey) ?: return null
    val node = state.document.nodes.getValue(nodeId)
    val parent = state.document.location(nodeId) ?: return null
    val children = node.slots.values.flatten()
    if (children.isEmpty()) return null
    val parentNode = state.document.nodes[parent.nodeId] ?: return null
    val parentSlot =
      catalog.componentsById[parentNode.componentId]?.slot(parent.slot) ?: return null
    val capabilities =
      children
        .mapNotNull { state.document.nodes[it]?.componentId }
        .mapNotNull(catalog.componentsById::get)
    if (capabilities.size != children.size) return null
    if (!capabilities.all(parentSlot::accepts)) return null
    val occupancy = parentNode.slots[parent.slot].orEmpty().size - 1 + children.size
    if (parentSlot.cardinality.max?.let { occupancy > it } == true) return null
    return nodeId to children
  }

  private fun unwrapSelection(state: UiBuilderEditorState): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val nodeId = state.selection.singleOrNull() ?: return state
    val plan = unwrapPlan(state)
    if (plan == null) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_LOCATION,
        "`$nodeId` cannot be unwrapped here: its parent does not accept these children",
      )
    }
    val (containerId, children) = plan
    val parent = state.document.location(containerId) ?: return state
    val operations = mutableListOf<DesignOperation>()
    // Children move out first, then the emptied container goes. The other order would delete them
    // along with it — a delete takes its subtree — so this is not a stylistic ordering.
    var after: String? = containerId
    children.forEach { childId ->
      operations += DesignOperation.MoveNode(nodeId = childId, parent = parent, afterNodeId = after)
      after = childId
    }
    operations += DesignOperation.DeleteNode(containerId)
    // The lifted children are what you were working on, so they are what stays selected.
    return state.apply(sequence, operations, selectionAfter = children.last()).let { unwrapped ->
      if (unwrapped.selection == listOf(children.last())) unwrapped.copy(selection = children)
      else unwrapped
    }
  }

  /**
   * Components insertable already wired to a click action: placeable here, with an emitter taking
   * `onClick` so the interaction survives export (`UNEMITTED_EVENT`).
   */
  fun actionInsertCandidates(state: UiBuilderEditorState): List<EditorCatalogItem> =
    if (state.document.stateVariables.isEmpty()) emptyList()
    else
      catalogItems("").filter {
        it.componentId in COMPOSE_EMITTED_CLICK_COMPONENTS &&
          dropTarget(state, it.componentId) != null
      }

  private fun copySelected(state: UiBuilderEditorState): UiBuilderEditorState {
    val roots = state.selectionRoots()
    if (roots.isEmpty()) return state
    // No operation or sequence bump: copying changes the editor, not the document, so it is not an
    // undo step.
    return state.copy(clipboard = state.document.clip(roots))
  }

  private fun cutSelected(state: UiBuilderEditorState): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val roots = state.selectionRoots()
    if (roots.isEmpty()) return state
    if (!canDeleteSelected(state)) {
      // The clipboard is deliberately left alone on a rejected cut. Taking the copy anyway would
      // leave the editor claiming it holds something the user can see is still in the document.
      return state.rejected(
        sequence,
        RejectionCode.INVALID_DOCUMENT,
        if (roots.size == 1) "Cutting ${roots.single()} would violate root or slot cardinality"
        else "Cutting these ${roots.size} nodes would violate root or slot cardinality",
      )
    }
    val clipboard = state.document.clip(roots).withOriginOf(state.document, roots)
    val anchor = roots.last()
    val selectionAfter =
      state.document.location(anchor)?.nodeId ?: state.document.roots.firstOrNull { it !in roots }
    // One apply, so cut is one undo step rather than a copy the user cannot see followed by a
    // delete they can.
    return state
      .apply(
        sequence = sequence,
        operations = roots.map(DesignOperation::DeleteNode),
        selectionAfter = selectionAfter,
      )
      .copy(clipboard = clipboard)
  }

  private fun paste(state: UiBuilderEditorState): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val clipboard = state.clipboard?.takeIf { it.rootNodeIds.isNotEmpty() } ?: return state
    val destination =
      pasteDestination(state)
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_DOCUMENT,
          "Nothing here accepts ${clipboard.rootComponentIds.distinct().joinToString(", ")}; " +
            "select a container with room for ${clipboard.rootNodeIds.size}",
        )
    val operations = mutableListOf<DesignOperation>()
    val pastedIds = mutableListOf<String>()
    // Beside the selection when pasting into its own slot, at the end when pasting inside the
    // selected container.
    val selectedLocation = state.selectedNodeId?.let(state.document::location)
    // A cut pasted back into its origin slot returns to its position (if its anchor still exists),
    // making cut-then-paste a move. A null anchor means it was first.
    val origin =
      clipboard.origin?.takeIf {
        it.parent == destination &&
          (it.afterNodeId == null || it.afterNodeId in state.document.children(destination))
      }
    var after =
      when {
        origin != null -> origin.afterNodeId
        destination == selectedLocation -> state.selectedNodeId
        else -> state.document.nodes[destination.nodeId]?.slots?.get(destination.slot)?.lastOrNull()
      }
    val taken = state.document.takenIdentities()
    // Components first: a pasted placement has to name a component this design defines, and one
    // copied out of another design brings its definition along.
    val keys = pasteComponents(state.document, clipboard, sequence, operations, taken)
    clipboard.rootNodeIds.forEachIndexed { index, rootId ->
      // Numbered per root as well as per paste, so two roots in one batch cannot collide with each
      // other the way two pastes of one root would collide without `freshNodeId`.
      val pasteId =
        freshCopyId(
          state.document.freshNodeId("$rootId-paste-$index", operationIdPrefix, sequence),
          taken,
        )
      // The clipboard's own nodes are the source, not the document's — the subtree it names may
      // have been cut, or edited since, and a paste has to reproduce what was copied either way.
      clipboard.nodes.appendDuplicateSubtree(
        sourceNodeId = rootId,
        copyNodeId = pasteId,
        parent = destination,
        afterNodeId = after,
        operations = operations,
        taken = taken,
      )
      // Each lands after the previous, so a multi-node paste keeps the order it was copied in.
      after = pasteId
      pastedIds += pasteId
    }
    val remapped = operations.map { it.withComponentKeys(keys) }
    return state.apply(sequence, remapped, selectionAfter = pastedIds.last()).let { pasted ->
      // The whole paste is selected, so it can be moved or deleted as the unit it arrived as.
      if (pasted.selection == listOf(pastedIds.last())) pasted.copy(selection = pastedIds)
      else pasted
    }
  }

  private fun undo(state: UiBuilderEditorState): UiBuilderEditorState {
    val targetOperationId = state.undoTargetOperationId(actorId) ?: return state
    val sequence = state.operationSequence + 1
    val application =
      CollaborationReducer.undo(
        state.collaboration,
        UndoCommand(
          designId = state.document.id,
          operationId = "$operationIdPrefix-editor-undo-${sequence.toString().padStart(4, '0')}",
          actorId = actorId,
          clientId = clientId,
          baseRevision = state.document.revision,
          targetOperationId = targetOperationId,
        ),
        documentValidator,
      )
    val selectedAfter =
      state.selectionBeforeOperations[targetOperationId]?.takeIf(
        application.state.document.nodes::containsKey
      ) ?: application.state.document.roots.firstOrNull()
    return state.withApplication(application, sequence, selectedAfter)
  }

  private fun redo(state: UiBuilderEditorState): UiBuilderEditorState {
    val targetUndoId = state.redoTargetUndoId(actorId) ?: return state
    val targetOperationId =
      state.collaboration.undoRecords.getValue(targetUndoId).target.command.operationId
    val sequence = state.operationSequence + 1
    val application =
      CollaborationReducer.redo(
        state.collaboration,
        RedoCommand(
          designId = state.document.id,
          operationId = "$operationIdPrefix-editor-redo-${sequence.toString().padStart(4, '0')}",
          actorId = actorId,
          clientId = clientId,
          baseRevision = state.document.revision,
          targetUndoOperationId = targetUndoId,
        ),
        documentValidator,
      )
    val selectedAfter =
      state.selectionAfterOperations[targetOperationId]?.takeIf(
        application.state.document.nodes::containsKey
      ) ?: application.state.document.roots.firstOrNull()
    return state.withApplication(application, sequence, selectedAfter)
  }

  /**
   * The components this design defines, for the palette's "This design" shelf and the inspector.
   */
  fun localComponents(state: UiBuilderEditorState): List<EditorLocalComponent> =
    state.document.localComponents(catalog).map { component ->
      if (newerInLibrary(state, component.key)) component.copy(newerInLibrary = true) else component
    }

  /** What a component's palette tile draws: one placement of it, as a design of its own. */
  fun localComponentPreview(state: UiBuilderEditorState, componentKey: String): UiBuilderDocument? {
    val document = state.document
    if (componentKey !in document.components) return null
    val placement =
      UiBuilderNode(
        id = "palette-preview-$componentKey",
        componentId = COMPONENT_INSTANCE_ID,
        component =
          JsonObject(
            mapOf(
              "componentKey" to JsonPrimitive(componentKey),
              "arguments" to starterArguments(document, componentKey),
            )
          ),
      )
    return document.copy(
      roots = listOf(placement.id),
      nodes = document.nodes + (placement.id to placement),
    )
  }

  /** The name [UiBuilderEditorEvent.MakeComponent] uses when it is given none. */
  fun suggestedComponentName(state: UiBuilderEditorState): String? {
    val node = state.selectedNodeId?.let(state.document.nodes::get) ?: return null
    return state.document.suggestedComponentName(node, catalog)
  }

  /**
   * Why the selection cannot become a component, or null; asked before the press so the menu can
   * say why.
   */
  fun makeComponentRefusal(state: UiBuilderEditorState): String? =
    planComponent(state, name = null, sequence = state.operationSequence + 1).refusal

  /** Where the palette's "This design" shelf would put a placement of [componentKey]. */
  fun localComponentTarget(state: UiBuilderEditorState, componentKey: String): ParentSlot? {
    val capability = state.document.placedCapability(componentKey, catalog) ?: return null
    val target = findDestination(state.document, state.selectedNodeId, capability) ?: return null
    // Into its own body is a component containing itself, which draws nothing and exports nothing.
    return target.takeUnless { it.nodeId in state.document.componentBody(componentKey) }
  }

  private data class ComponentPlan(
    val refusal: String? = null,
    val operations: List<DesignOperation> = emptyList(),
    val placementId: String? = null,
  )

  private fun planComponent(
    state: UiBuilderEditorState,
    name: String?,
    sequence: Int,
  ): ComponentPlan {
    fun refuse(reason: String) = ComponentPlan(refusal = reason)
    val document = state.document
    val nodeId =
      state.selection.singleOrNull() ?: return refuse("Select one node to make a component of it")
    val node = document.nodes[nodeId] ?: return refuse("Select one node to make a component of it")
    if (
      node.componentId == COMPONENT_INSTANCE_ID ||
        document.components.values.any { componentRootOf(it) == nodeId }
    )
      return refuse("This is already a component")
    val parent =
      document.location(nodeId)
        ?: return refuse("The root is the whole screen; make a component of a part of it")
    // In tree order, so parameters are numbered the way the body reads top to bottom.
    val body = mutableListOf<UiBuilderNode>()
    fun collect(id: String) {
      val child = document.nodes[id] ?: return
      body += child
      child.slots.values.flatten().forEach(::collect)
    }
    collect(nodeId)
    // Each of these is a refusal the export makes of a body by name; refusing here says it at the
    // moment the author can still choose a different subtree, rather than at the first export.
    body.forEach { member ->
      val label = componentLabel(member.componentId)
      if (member.eventBindings.isNotEmpty())
        return refuse(
          "$label handles ${member.eventBindings.keys.first()}; a component draws from its " +
            "arguments alone, so put the action on each place it is used"
        )
      if (
        member.properties.values.any {
          (it as? JsonObject)?.get("type")?.primitiveOrNull()?.contentOrNull in STATE_VALUE_TYPES
        } || SHOW_BY_STATE in member.properties
      )
        return refuse(
          "$label reads screen state, which a component's own function cannot see; unbind it first"
        )
      if (member.componentId.endsWith("scaffold"))
        return refuse("A scaffold is a whole screen, so it cannot be a component's body")
    }
    // The body root draws inside the placement's Box, so a modifier that speaks to the parent it
    // used to sit in — a Row's weight, a Box's alignment — would silently stop meaning anything.
    node.modifiers
      .mapNotNull { (it as? JsonObject)?.get("type")?.primitiveOrNull()?.contentOrNull }
      .firstOrNull { it in PARENT_SCOPED_MODIFIERS }
      ?.let {
        return refuse(
          "Take `$it` off ${componentLabel(node.componentId)} first: it belongs to the slot"
        )
      }
    val functionName =
      componentFunctionName(name ?: document.suggestedComponentName(node, catalog))
        ?: return refuse("`$name` is not a name a composable can have")
    if (document.components.values.any { componentDeclarationName(it) == functionName })
      return refuse("This design already has a component called $functionName")
    val key = document.freshComponentKey(functionName)
    // Every text the body shows becomes a parameter, holding what it shows now, so the screen draws
    // what it drew and the next placement can say something else.
    val taken = mutableSetOf<String>()
    val arguments = linkedMapOf<String, JsonElement>()
    val bindings = mutableListOf<DesignOperation>()
    body.forEach { member ->
      bindableTextProperties(member.componentId).sorted().forEach { property ->
        val literal =
          (member.properties[property] as? JsonObject)?.takeIf {
            it["type"]?.primitiveOrNull()?.contentOrNull == "string"
          } ?: return@forEach
        val parameter =
          parameterNameFor(property, literal["value"]?.primitiveOrNull()?.contentOrNull, taken)
        taken += parameter
        arguments[parameter] = literal
        bindings += DesignOperation.SetProperty(member.id, property, binding(parameter))
      }
    }
    val placementId =
      document.freshNodeId("editor-${key}", operationIdPrefix, sequence).let { id ->
        // `freshNodeId` asks the document, and the placement is minted before anything is in it.
        if (id == nodeId) "$id-placement" else id
      }
    val placement =
      UiBuilderNode(
        id = placementId,
        componentId = COMPONENT_INSTANCE_ID,
        modifiers = JsonArray(emptyList()),
        component =
          JsonObject(
            mapOf(
              "componentKey" to JsonPrimitive(key),
              "arguments" to JsonObject(arguments),
            )
          ),
      )
    val operations =
      listOf(
        // The placement takes the subtree's place before the subtree leaves it, so the anchor is
        // the subtree itself and the screen's order is unchanged.
        DesignOperation.InsertNode(placement, parent, afterNodeId = nodeId),
        // Out to the root list, where the declaration finds it a second root and makes it a body.
        DesignOperation.MoveNode(nodeId, parent = null),
        DesignOperation.DeclareComponent(
          key,
          JsonObject(mapOf("name" to JsonPrimitive(functionName), "root" to JsonPrimitive(nodeId))),
        ),
      ) + bindings
    return ComponentPlan(operations = operations, placementId = placementId)
  }

  private fun makeComponent(state: UiBuilderEditorState, name: String?): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val plan = planComponent(state, name?.takeIf(String::isNotBlank), sequence)
    plan.refusal?.let {
      return state.rejected(sequence, RejectionCode.INVALID_LOCATION, it, state.selectedNodeId)
    }
    return state.apply(sequence, plan.operations, selectionAfter = plan.placementId)
  }

  /**
   * Where a fetched library component would land, or null. Asked after the fetch, since only then
   * is its body known.
   */
  fun libraryComponentTarget(
    state: UiBuilderEditorState,
    symbol: EditorLibrarySymbol,
  ): ParentSlot? {
    val root = componentRootOf(symbol.declaration)?.let(symbol.nodes::get) ?: return null
    val capability = catalog.componentsById[root.componentId] ?: return null
    return findDestination(state.document, state.selectedNodeId, capability)
  }

  private fun insertLibraryComponent(
    state: UiBuilderEditorState,
    symbol: EditorLibrarySymbol,
    target: ParentSlot,
    afterNodeId: String?,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val document = state.document
    val wanted = symbol.component
    val bodyRoot =
      componentRootOf(symbol.declaration)?.takeIf { it in symbol.nodes }
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_DOCUMENT,
          "${wanted.title} was published without the body it names",
        )
    symbol.nodes.values
      .firstOrNull {
        it.componentId != COMPONENT_INSTANCE_ID && it.componentId !in catalog.componentsById
      }
      ?.let {
        return state.rejected(
          sequence,
          RejectionCode.INVALID_DOCUMENT,
          "${wanted.title} uses ${it.componentId}, which this design's catalog does not have",
        )
      }
    val capability =
      catalog.componentsById[symbol.nodes.getValue(bodyRoot).componentId]
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_DOCUMENT,
          "${wanted.title} draws nothing here",
        )
    if (!acceptsComponent(document, target, capability))
      return state.rejected(
        sequence,
        RejectionCode.INVALID_LOCATION,
        "${wanted.title} cannot be inserted into ${target.nodeId}.${target.slot}",
      )
    val operations = mutableListOf<DesignOperation>()
    // Imported once: a design that already holds this symbol places the version it holds — a newer
    // one in the library is drift for the Issues panel to report, never a silent redraw.
    val existing =
      document.components.entries
        .firstOrNull { (_, declaration) ->
          componentSource(declaration)?.let { (system, id, _) ->
            system == wanted.system && id == wanted.componentId
          } == true
        }
        ?.key
    val key =
      existing
        ?: run {
          var fresh = wanted.componentId
          var suffix = 2
          while (fresh in document.components) fresh = "${wanted.componentId}-${suffix++}"
          val taken = document.takenIdentities()
          val copyRoot =
            freshCopyId(
              document.freshNodeId("$bodyRoot-import", operationIdPrefix, sequence),
              taken,
            )
          symbol.nodes.appendDuplicateSubtree(
            sourceNodeId = bodyRoot,
            copyNodeId = copyRoot,
            parent = null,
            afterNodeId = null,
            operations = operations,
            taken = taken,
          )
          operations +=
            DesignOperation.DeclareComponent(
              fresh,
              JsonObject(
                symbol.declaration +
                  ("root" to JsonPrimitive(copyRoot)) +
                  ("source" to
                    JsonObject(
                      mapOf(
                        "system" to JsonPrimitive(wanted.system),
                        "componentId" to JsonPrimitive(wanted.componentId),
                        "digest" to JsonPrimitive(symbol.digest),
                      )
                    ))
              ),
            )
          fresh
        }
    // What the first placement passes: what the last one did, or each text parameter's own name.
    val arguments =
      if (existing != null) starterArguments(document, existing)
      else {
        val reads = linkedMapOf<String, JsonElement>()
        symbol.nodes.values
          .sortedBy { it.id }
          .forEach { node ->
            node.properties.forEach { (property, value) ->
              val parameter = value.bindingKey() ?: return@forEach
              val declared =
                catalog.componentsById[node.componentId]?.propertiesByName?.get(property)
              if (parameter !in reads && declared?.typeNames()?.authoredTypes() == setOf("string"))
                reads[parameter] = literal("string", JsonPrimitive(parameter.humanLabel()))
            }
          }
        JsonObject(reads)
      }
    val slotChildren = document.children(target)
    val after = afterNodeId?.takeIf { it in slotChildren } ?: slotChildren.lastOrNull()
    val nodeId = document.freshNodeId("editor-$key", operationIdPrefix, sequence)
    operations +=
      DesignOperation.InsertNode(
        UiBuilderNode(
          id = nodeId,
          componentId = COMPONENT_INSTANCE_ID,
          component =
            JsonObject(mapOf("componentKey" to JsonPrimitive(key), "arguments" to arguments)),
        ),
        target,
        after,
      )
    return state.apply(sequence, operations, selectionAfter = nodeId)
  }

  /**
   * Replace an imported component with a newer library version in place. Placements keep position,
   * layout and still-read arguments; newly read text parameters start as their own name.
   */
  private fun updateLibraryComponent(
    state: UiBuilderEditorState,
    componentKey: String,
    symbol: EditorLibrarySymbol,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val document = state.document
    val wanted = symbol.component
    val declaration =
      document.components[componentKey] as? JsonObject
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_DOCUMENT,
          "This design defines no component `$componentKey`",
        )
    val recorded = componentSource(declaration)
    if (
      recorded == null || recorded.first != wanted.system || recorded.second != wanted.componentId
    )
      return state.rejected(
        sequence,
        RejectionCode.INVALID_DOCUMENT,
        "${componentDeclarationName(declaration) ?: componentKey} was not imported from " +
          "${wanted.title}",
      )
    if (recorded.third == symbol.digest) return state
    val oldRoot = componentRootOf(declaration) ?: return state
    val bodyRoot =
      componentRootOf(symbol.declaration)?.takeIf { it in symbol.nodes }
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_DOCUMENT,
          "${wanted.title} was published without the body it names",
        )
    symbol.nodes.values
      .firstOrNull { it.componentId !in catalog.componentsById }
      ?.let {
        return state.rejected(
          sequence,
          RejectionCode.INVALID_DOCUMENT,
          "${wanted.title} now uses ${it.componentId}, which this design's catalog does not have",
        )
      }
    val capability = catalog.componentsById.getValue(symbol.nodes.getValue(bodyRoot).componentId)
    // Every placement has to be able to hold the new version where it already sits, or the update
    // would leave a slot holding something it refuses.
    document.nodes.values
      .filter { it.placementKey() == componentKey }
      .forEach { placement ->
        val parent = document.location(placement.id) ?: return@forEach
        val accepts =
          document.nodes[parent.nodeId]
            ?.let { catalog.componentsById[it.componentId]?.slot(parent.slot) }
            ?.accepts(capability) == true
        if (!accepts)
          return state.rejected(
            sequence,
            RejectionCode.INVALID_LOCATION,
            "The new ${wanted.title} cannot sit where ${placement.id} does",
            placement.id,
          )
      }

    val operations = mutableListOf<DesignOperation>()
    val taken = document.takenIdentities()
    val copyRoot =
      freshCopyId(document.freshNodeId("$bodyRoot-import", operationIdPrefix, sequence), taken)
    symbol.nodes.appendDuplicateSubtree(
      sourceNodeId = bodyRoot,
      copyNodeId = copyRoot,
      parent = null,
      afterNodeId = null,
      operations = operations,
      taken = taken,
    )
    // The library's name, unless another component here already goes by it.
    val libraryName = componentDeclarationName(symbol.declaration)
    val name =
      libraryName?.takeIf { wanted ->
        document.components.none { (key, other) ->
          key != componentKey && componentDeclarationName(other) == wanted
        }
      } ?: componentDeclarationName(declaration)
    operations +=
      DesignOperation.DeclareComponent(
        componentKey,
        withLibrarySource(
          JsonObject(
            symbol.declaration +
              ("root" to JsonPrimitive(copyRoot)) +
              listOfNotNull(name?.let { "name" to JsonPrimitive(it) })
          ),
          EditorLibrarySource(wanted.system, wanted.componentId, symbol.digest),
        ),
      )
    // The old body, which the new declaration handed back to the root list.
    operations += DesignOperation.DeleteNode(oldRoot)

    val reads = linkedMapOf<String, JsonElement?>()
    symbol.nodes.values
      .sortedBy { it.id }
      .forEach { node ->
        node.properties.forEach { (property, value) ->
          val parameter = value.bindingKey() ?: return@forEach
          if (parameter in reads) return@forEach
          val declared = catalog.componentsById[node.componentId]?.propertiesByName?.get(property)
          reads[parameter] =
            if (declared?.typeNames()?.authoredTypes() == setOf("string"))
              literal("string", JsonPrimitive(parameter.humanLabel()))
            else null
        }
      }
    val selectionAfter =
      rewritePlacements(state, componentKey, operations) { arguments ->
        val kept = arguments.filterKeys { it in reads }
        val added =
          reads.entries
            .filter { (key, starter) -> key !in kept && starter != null }
            .associate { (key, starter) -> key to starter!! }
        JsonObject(kept + added).takeIf { it != arguments }
      }
    return state.apply(sequence, operations, selectionAfter = selectionAfter)
  }

  /** Whether the library holds a newer version of [componentKey] than this design imported. */
  fun newerInLibrary(state: UiBuilderEditorState, componentKey: String): Boolean =
    // Only findings still about the copy this design holds: taking the new version locally changes
    // the recorded digest before the host has read the drift report again.
    state.componentDrift.stillDescribing(state.document).any {
      it.componentKey == componentKey && it.state == ComponentDriftState.DRIFTED
    }

  private fun insertLocalComponent(
    state: UiBuilderEditorState,
    componentKey: String,
    target: ParentSlot,
    afterNodeId: String?,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val document = state.document
    val name = componentDeclarationName(document.components[componentKey]) ?: componentKey
    val capability =
      document.placedCapability(componentKey, catalog)
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_DOCUMENT,
          "This design defines no component `$componentKey`",
        )
    if (target.nodeId in document.componentBody(componentKey))
      return state.rejected(
        sequence,
        RejectionCode.CYCLE,
        "$name cannot be placed inside itself",
        target.nodeId,
      )
    if (!acceptsComponent(document, target, capability))
      return state.rejected(
        sequence,
        RejectionCode.INVALID_LOCATION,
        "$name cannot be inserted into ${target.nodeId}.${target.slot}",
      )
    val slotChildren = document.children(target)
    val after = afterNodeId?.takeIf { it in slotChildren } ?: slotChildren.lastOrNull()
    val nodeId = document.freshNodeId("editor-$componentKey", operationIdPrefix, sequence)
    val placement =
      UiBuilderNode(
        id = nodeId,
        componentId = COMPONENT_INSTANCE_ID,
        component =
          JsonObject(
            mapOf(
              "componentKey" to JsonPrimitive(componentKey),
              "arguments" to starterArguments(document, componentKey),
            )
          ),
      )
    return state.apply(
      sequence,
      listOf(DesignOperation.InsertNode(placement, target, after)),
      selectionAfter = nodeId,
    )
  }

  /**
   * A new placement's arguments: those of the last placement in the design, else each text
   * parameter's own name so it draws something visible.
   */
  private fun starterArguments(document: UiBuilderDocument, componentKey: String): JsonObject {
    document.nodes.values
      .lastOrNull { it.placementKey() == componentKey }
      ?.let {
        return it.placementArguments()
      }
    return JsonObject(
      document
        .bodyBindings(componentKey)
        .mapNotNull { (key, read) ->
          val (bodyNodeId, property) = read
          val componentId = document.nodes[bodyNodeId]?.componentId ?: return@mapNotNull null
          val declared = catalog.componentsById[componentId]?.propertiesByName?.get(property)
          if (declared?.typeNames()?.authoredTypes() != setOf("string")) return@mapNotNull null
          key to literal("string", JsonPrimitive(key.humanLabel()))
        }
        .toMap()
    )
  }

  private fun renameLocalComponent(
    state: UiBuilderEditorState,
    componentKey: String,
    name: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val declaration = state.document.components[componentKey] as? JsonObject ?: return state
    val functionName =
      componentFunctionName(name)
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "`$name` is not a name a composable can have",
        )
    if (componentDeclarationName(declaration) == functionName) return state
    if (state.document.components.values.any { componentDeclarationName(it) == functionName })
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "This design already has a component called $functionName",
      )
    return state.apply(
      sequence,
      listOf(
        DesignOperation.DeclareComponent(
          componentKey,
          JsonObject(declaration + ("name" to JsonPrimitive(functionName))),
        )
      ),
      selectionAfter = state.selectedNodeId,
    )
  }

  private fun recordLibrarySource(
    state: UiBuilderEditorState,
    componentKey: String,
    source: EditorLibrarySource,
  ): UiBuilderEditorState {
    val declaration = state.document.components[componentKey] as? JsonObject ?: return state
    val recorded = withLibrarySource(declaration, source)
    if (recorded == declaration) return state
    return state.apply(
      state.operationSequence + 1,
      listOf(DesignOperation.DeclareComponent(componentKey, recorded)),
      selectionAfter = state.selectedNodeId,
    )
  }

  /** What a publish of [componentKey] would send, or null with [libraryPublicationRefusal]. */
  fun libraryPublication(state: UiBuilderEditorState, componentKey: String) =
    state.document.libraryPublication(componentKey)

  /** Why [componentKey] cannot be published as it stands, or null when it can. */
  fun libraryPublicationRefusal(state: UiBuilderEditorState, componentKey: String) =
    state.document.libraryPublicationRefusal(componentKey)

  private fun renameComponentParameter(
    state: UiBuilderEditorState,
    componentKey: String,
    from: String,
    to: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val document = state.document
    val name = to.trim()
    if (name == from) return state
    val parameters = document.bodyBindings(componentKey)
    if (from !in parameters) return state
    componentParameterRefusal(name, parameters.keys)?.let {
      return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, it)
    }
    val operations = mutableListOf<DesignOperation>()
    // Placements first, so by the time the body reads the new key every placement passes it.
    val selectionAfter =
      rewritePlacements(state, componentKey, operations) { arguments ->
        if (from !in arguments) null
        else JsonObject(arguments.entries.associate { (k, v) -> (if (k == from) name else k) to v })
      }
    document.componentBody(componentKey).sorted().forEach { nodeId ->
      document.nodes[nodeId]?.properties?.forEach { (property, value) ->
        if (value.bindingKey() == from)
          operations += DesignOperation.SetProperty(nodeId, property, binding(name))
      }
    }
    return state.apply(sequence, operations, selectionAfter = selectionAfter)
  }

  /**
   * Rewrite the arguments of every placement of [componentKey] that [rewrite] changes (null:
   * unchanged), keeping placement ids. Returns the selection afterwards.
   */
  private fun rewritePlacements(
    state: UiBuilderEditorState,
    componentKey: String,
    operations: MutableList<DesignOperation>,
    rewrite: (JsonObject) -> JsonObject?,
  ): String? {
    state.document.nodes.values
      .filter { it.placementKey() == componentKey }
      .sortedBy { it.id }
      .forEach { placement ->
        val rewritten = rewrite(placement.placementArguments()) ?: return@forEach
        operations += DesignOperation.SetComponentArguments(placement.id, rewritten)
      }
    return state.selectedNodeId
  }

  /**
   * The parameter-related context-menu verbs for the selection: detach a placement, or make a body
   * property a parameter, or stop one being one.
   */
  fun componentActions(state: UiBuilderEditorState): List<EditorComponentAction> {
    val nodeId = state.selection.singleOrNull() ?: return emptyList()
    val document = state.document
    val node = document.nodes[nodeId] ?: return emptyList()
    if (node.placementKey() != null && document.location(nodeId) != null)
      return listOf(
        EditorComponentAction(
          "Detach instance",
          UiBuilderEditorEvent.DetachPlacement(nodeId),
        )
      )
    val owner = document.owningComponent(nodeId) ?: return emptyList()
    val ownerName = componentDeclarationName(document.components[owner]) ?: owner
    val capability = catalog.componentsById[node.componentId]
    return bindableProperties(node.componentId).sorted().mapNotNull { property ->
      val value = node.properties[property] ?: return@mapNotNull null
      val label = capability?.propertiesByName?.get(property)?.name?.humanLabel() ?: property
      val parameter = value.bindingKey()
      if (parameter != null)
        EditorComponentAction(
          "Stop $label being a parameter",
          UiBuilderEditorEvent.InlineComponentParameter(owner, parameter),
        )
      else
        EditorComponentAction(
          "Make $label a parameter of $ownerName",
          UiBuilderEditorEvent.ExposeComponentParameter(nodeId, property),
        )
    }
  }

  /**
   * Make one literal body property a parameter: the body reads it by key and every placement passes
   * its current value, so nothing on screen changes.
   */
  private fun exposeComponentParameter(
    state: UiBuilderEditorState,
    nodeId: String,
    property: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val document = state.document
    val node = document.nodes[nodeId] ?: return state
    val owner =
      document.owningComponent(nodeId)
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "Only a component's own layers have parameters",
          nodeId,
          property,
        )
    if (property !in bindableProperties(node.componentId))
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "The export cannot pass $property as a parameter",
        nodeId,
        property,
      )
    val value =
      (node.properties[property] as? JsonObject)?.takeIf { it.bindingKey() == null }
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "$property has no value of its own to make a parameter of",
          nodeId,
          property,
        )
    val existing = document.bodyBindings(owner).keys
    val name = parameterNameFor(property, null, existing)
    val operations = mutableListOf<DesignOperation>()
    rewritePlacements(state, owner, operations) { JsonObject(it + (name to value)) }
    operations += DesignOperation.SetProperty(nodeId, property, binding(name))
    return state.apply(sequence, operations, selectionAfter = nodeId)
  }

  /**
   * Stop [parameter] being one: body properties reading it take the first placement's value, and
   * placements stop passing it.
   */
  private fun inlineComponentParameter(
    state: UiBuilderEditorState,
    componentKey: String,
    parameter: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val document = state.document
    val order = document.treeIndex()
    val value =
      document.nodes.values
        .filter { it.placementKey() == componentKey }
        .sortedBy { order(it.id) }
        .firstNotNullOfOrNull { it.placementArguments()[parameter] as? JsonObject }
        ?.takeIf { it.bindingKey() == null }
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "No placement passes `$parameter` a value to keep",
        )
    val operations = mutableListOf<DesignOperation>()
    // The body stops reading it first, so no placement is left passing a key nothing reads.
    document.componentBody(componentKey).sorted().forEach { nodeId ->
      document.nodes[nodeId]?.properties?.forEach { (property, read) ->
        if (read.bindingKey() == parameter)
          operations += DesignOperation.SetProperty(nodeId, property, value)
      }
    }
    val selectionAfter =
      rewritePlacements(state, componentKey, operations) { arguments ->
        if (parameter !in arguments) null else JsonObject(arguments - parameter)
      }
    return state.apply(sequence, operations, selectionAfter = selectionAfter)
  }

  /**
   * Turn a placement back into ordinary layers: a copy of the component's body holding the passed
   * values. The component and its other placements stay.
   */
  private fun detachPlacement(state: UiBuilderEditorState, nodeId: String): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val document = state.document
    val placement = document.nodes[nodeId] ?: return state
    val key = placement.placementKey() ?: return state
    val bodyRoot = componentRootOf(document.components[key]) ?: return state
    val parent =
      document.location(nodeId)
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_LOCATION,
          "This placement sits nowhere",
        )
    val arguments = placement.placementArguments()
    val taken = document.takenIdentities()
    val copyRoot =
      freshCopyId(document.freshNodeId("$bodyRoot-detached", operationIdPrefix, sequence), taken)
    val copied = mutableListOf<DesignOperation>()
    document.nodes.appendDuplicateSubtree(
      sourceNodeId = bodyRoot,
      copyNodeId = copyRoot,
      parent = parent,
      afterNodeId = nodeId,
      operations = copied,
      taken = taken,
    )
    fun resolved(values: JsonObject): JsonObject =
      JsonObject(
        values
          .mapNotNull { (name, value) ->
            val key = value.bindingKey() ?: return@mapNotNull name to value
            arguments[key]?.let { name to it }
          }
          .toMap()
      )
    val operations =
      copied.map { operation ->
        if (operation !is DesignOperation.InsertNode) return@map operation
        var node = operation.node.copy(properties = resolved(operation.node.properties))
        // A nested placement passes on what this one passed it.
        node.component?.let { component ->
          node =
            node.copy(
              component =
                JsonObject(component + ("arguments" to resolved(node.placementArguments())))
            )
        }
        // The placement's own layout belongs to the call site, and now to the copy's root.
        if (node.id == copyRoot)
          node =
            node.copy(
              modifiers = kotlinx.serialization.json.JsonArray(placement.modifiers + node.modifiers)
            )
        operation.copy(node = node)
      } + DesignOperation.DeleteNode(nodeId)
    return state.apply(sequence, operations, selectionAfter = copyRoot)
  }

  /**
   * A selected placement's argument fields, built from the body properties they feed so each gets
   * the matching editor.
   */
  private fun placementFields(
    state: UiBuilderEditorState,
    placement: UiBuilderNode,
  ): List<EditorPropertyField> {
    val key = placement.placementKey() ?: return emptyList()
    val arguments = placement.placementArguments()
    return state.document.bodyBindings(key).mapNotNull { (argument, read) ->
      val (bodyNodeId, property) = read
      bodyFieldFor(state, bodyNodeId, property, arguments[argument])
        ?.copy(
          nodeId = placement.id,
          name = argument,
          label = argument.humanLabel(),
          required = true,
          written = argument in arguments,
          boundVariable = null,
          error = state.propertyErrors[EditorPropertyLocation(placement.id, argument)],
          notes = null,
        )
    }
  }

  /** The inspector field of [property] on [bodyNodeId], as if it held [value]. */
  private fun bodyFieldFor(
    state: UiBuilderEditorState,
    bodyNodeId: String,
    property: String,
    value: JsonElement?,
  ): EditorPropertyField? {
    val body = state.document.nodes[bodyNodeId] ?: return null
    val probe =
      body.copy(
        properties =
          JsonObject(
            if (value == null) body.properties - property else body.properties + (property to value)
          )
      )
    val probed =
      state.copy(
        collaboration =
          state.collaboration.copy(
            document = state.document.copy(nodes = state.document.nodes + (probe.id to probe))
          ),
        selection = listOf(bodyNodeId),
      )
    return catalogPropertyFields(probed).firstOrNull { it.name == property }
  }

  /**
   * Set one argument of a placement, keeping its id (which comments, selection and reviews hold).
   */
  private fun setComponentArgument(
    state: UiBuilderEditorState,
    placement: UiBuilderNode,
    argument: String,
    draft: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val key = placement.placementKey() ?: return state
    val (bodyNodeId, property) = state.document.bodyBindings(key)[argument] ?: return state
    val current = placement.placementArguments()[argument] as? JsonObject
    val field = bodyFieldFor(state, bodyNodeId, property, current) ?: return state
    val parsed = field.parseDraft(draft)
    if (parsed is PropertyDraft.Invalid)
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        parsed.message,
        placement.id,
        argument,
      )
    val value = (parsed as PropertyDraft.Valid).value
    val declared =
      catalog.componentsById[state.document.nodes[bodyNodeId]?.componentId]
        ?.propertiesByName
        ?.get(property)
    val type =
      if (field.control == EditorPropertyControl.Color) colourWrapper(value)
      else
        declared?.canonicalWrapper(current?.get("type")?.primitiveOrNull()?.contentOrNull)
          ?: field.defaultEncodedType()
    val encoded = literal(type, value)
    if (encoded == current) return state
    validator.validate(state.document, bodyNodeId, property, encoded)?.let { issue ->
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        issue.message,
        placement.id,
        argument,
      )
    }
    return state
      .apply(
        sequence,
        listOf(
          DesignOperation.SetComponentArguments(
            placement.id,
            JsonObject(placement.placementArguments() + (argument to encoded)),
          )
        ),
        selectionAfter = placement.id,
      )
      .let { edited ->
        if (edited.lastOutcome is CommandOutcome.Accepted)
          edited.copy(
            propertyErrors = edited.propertyErrors - EditorPropertyLocation(placement.id, argument)
          )
        else edited
      }
  }

  /**
   * Swap every placement of a design-only component for the catalog component that now ships it:
   * the graduation step of [`UI_BUILDER_REPETITION_AND_COMPONENTS.md`](../../../../../../docs/desig
   * n/UI_BUILDER_REPETITION_AND_COMPONENTS.md) §3.
   */
  private fun replaceLocalComponent(
    state: UiBuilderEditorState,
    componentKey: String,
    catalogComponentId: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val document = state.document
    val replacement =
      catalog.componentsById[catalogComponentId]
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "The catalog has no component `$catalogComponentId`",
        )
    val bodyRoot =
      componentRootOf(document.components[componentKey])
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_DOCUMENT,
          "This design defines no component `$componentKey`",
        )
    val body = document.componentBody(componentKey)
    val order = document.treeIndex()
    val placements =
      document.nodes.values.filter { it.placementKey() == componentKey }.sortedBy { order(it.id) }
    // A placement inside the body being dropped goes with it; only the ones that survive are
    // swapped.
    val survivors = placements.filterNot { it.id in body }
    val operations = mutableListOf<DesignOperation>()
    var firstReplacement: String? = null
    survivors.forEachIndexed { index, placement ->
      val parent =
        document.location(placement.id)
          ?: return state.rejected(
            sequence,
            RejectionCode.INVALID_LOCATION,
            "Placement ${placement.id} sits nowhere",
          )
      // The swap leaves the slot's occupancy as it was, so only the slot's acceptance is asked.
      val accepts =
        document.nodes[parent.nodeId]
          ?.let { catalog.componentsById[it.componentId]?.slot(parent.slot) }
          ?.accepts(replacement) == true
      if (!accepts)
        return state.rejected(
          sequence,
          RejectionCode.INVALID_LOCATION,
          "${replacement.displayName} cannot sit where ${placement.id} does",
          placement.id,
        )
      val nodeId =
        document.freshNodeId(
          "editor-${catalogComponentId.replace('/', '-')}-$index",
          operationIdPrefix,
          sequence,
        )
      if (firstReplacement == null) firstReplacement = nodeId
      val presets =
        placement
          .placementArguments()
          .entries
          .filter { (name, value) -> name in replacement.propertiesByName && value is JsonObject }
          .associate { (name, value) -> name to value as JsonObject }
      val inserted = mutableListOf<DesignOperation>()
      replacement
        .appendDefaultSubtree(
          catalog = catalog,
          document = document,
          nodeId = nodeId,
          parent = parent,
          afterNodeId = placement.id,
          operations = inserted,
          presetProperties = presets,
        )
        ?.let { error ->
          return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, error, placement.id)
        }
      // The placement's layout is the call site's, and it carries across to the call.
      operations += inserted.map { operation ->
        if (operation is DesignOperation.InsertNode && operation.node.id == nodeId)
          operation.copy(node = operation.node.copy(modifiers = placement.modifiers))
        else operation
      }
      operations += DesignOperation.DeleteNode(placement.id)
    }
    // With nothing left placing it, the definition goes, and its body — which the undeclaration
    // hands back to the root list — goes with it.
    operations += DesignOperation.RemoveComponent(componentKey)
    operations += DesignOperation.DeleteNode(bodyRoot)
    return state.apply(sequence, operations, selectionAfter = firstReplacement)
  }

  /**
   * What a clipboard root draws as a capability: its own, or for a placement its body root's,
   * looked up in the clipboard first.
   */
  private fun EditorClipboard.capabilityOf(
    nodeId: String,
    document: UiBuilderDocument,
  ): ComponentCapability? {
    var node = nodes[nodeId] ?: return null
    repeat(16) {
      val key = node.placementKey() ?: return catalog.componentsById[node.componentId]
      val declaration = components[key] ?: document.components[key] ?: return null
      val root = componentRootOf(declaration) ?: return null
      node = nodes[root] ?: document.nodes[root] ?: return null
    }
    return null
  }

  /**
   * Bring the clipboard's components into [document] and return each one's key here. A same-named
   * component is reused; anything else is imported fresh under its own key where free.
   */
  private fun pasteComponents(
    document: UiBuilderDocument,
    clipboard: EditorClipboard,
    sequence: Int,
    operations: MutableList<DesignOperation>,
    taken: MutableSet<String>,
  ): Map<String, String> {
    val keys = linkedMapOf<String, String>()
    val declared = document.components.keys.toMutableSet()
    clipboard.components.forEach { (key, declaration) ->
      val name = componentDeclarationName(declaration)
      val existing =
        key.takeIf { componentDeclarationName(document.components[it]) == name }
          ?: document.components.entries
            .firstOrNull { componentDeclarationName(it.value) == name }
            ?.key
      if (existing != null) {
        keys[key] = existing
        return@forEach
      }
      var fresh = key
      var suffix = 2
      while (fresh in declared) fresh = "$key-${suffix++}"
      declared += fresh
      keys[key] = fresh
      val bodyRoot = componentRootOf(declaration) ?: return@forEach
      val copyRoot =
        freshCopyId(document.freshNodeId("$bodyRoot-paste", operationIdPrefix, sequence), taken)
      clipboard.nodes.appendDuplicateSubtree(
        sourceNodeId = bodyRoot,
        copyNodeId = copyRoot,
        parent = null,
        afterNodeId = null,
        operations = operations,
        taken = taken,
      )
      operations +=
        DesignOperation.DeclareComponent(
          fresh,
          JsonObject(declaration + ("root" to JsonPrimitive(copyRoot))),
        )
    }
    return keys
  }

  private fun DesignOperation.withComponentKeys(keys: Map<String, String>): DesignOperation {
    if (this !is DesignOperation.InsertNode) return this
    val key = node.placementKey() ?: return this
    val renamed = keys[key]?.takeIf { it != key } ?: return this
    return copy(
      node =
        node.copy(
          component =
            JsonObject(
              (node.component ?: JsonObject(emptyMap())) +
                ("componentKey" to JsonPrimitive(renamed))
            )
        )
    )
  }

  private fun UiBuilderEditorState.apply(
    sequence: Int,
    operations: List<DesignOperation>,
    selectionAfter: String?,
  ): UiBuilderEditorState {
    val operationId = "$operationIdPrefix-editor-operation-${sequence.toString().padStart(4, '0')}"
    val command =
      DesignCommand(
        designId = document.id,
        operationId = operationId,
        actorId = actorId,
        clientId = clientId,
        baseRevision = document.revision,
        operations = operations,
      )
    val application =
      CollaborationReducer.apply(collaboration, command, validator, documentValidator)
    return withApplication(
      application = application,
      sequence = sequence,
      selectionAfter = selectionAfter,
      acceptedOperationId = operationId,
    )
  }

  private fun UiBuilderEditorState.withApplication(
    application: CommandApplication,
    sequence: Int,
    selectionAfter: String?,
    acceptedOperationId: String? = null,
  ): UiBuilderEditorState {
    val accepted = application.outcome is CommandOutcome.Accepted
    return copy(
      collaboration = application.state,
      selection =
        if (accepted) listOfNotNull(selectionAfter)
        else selection.filter(document.nodes::containsKey),
      operationSequence = sequence,
      lastOutcome = application.outcome,
      propertyErrors =
        if (accepted) {
          val touched =
            application.state.acceptedCommands.values
              .maxByOrNull(AcceptedCommand::committedRevision)
              ?.command
              ?.operations
              ?.filterIsInstance<DesignOperation.SetProperty>()
              .orEmpty()
              .map { EditorPropertyLocation(it.nodeId, it.property) }
              .toSet()
          propertyErrors - touched
        } else propertyErrors,
      selectionBeforeOperations =
        if (accepted && acceptedOperationId != null)
          selectionBeforeOperations + (acceptedOperationId to selectedNodeId)
        else selectionBeforeOperations,
      selectionAfterOperations =
        if (accepted && acceptedOperationId != null)
          selectionAfterOperations + (acceptedOperationId to selectionAfter)
        else selectionAfterOperations,
    )
  }

  private fun UiBuilderEditorState.rejected(
    sequence: Int,
    code: RejectionCode,
    message: String,
    nodeId: String? = null,
    field: String? = null,
  ): UiBuilderEditorState =
    copy(
      operationSequence = sequence,
      lastOutcome =
        CommandOutcome.Rejected(code = code, message = message, nodeId = nodeId, field = field),
      propertyErrors =
        if (nodeId != null && field != null)
          propertyErrors + (EditorPropertyLocation(nodeId, field) to message)
        else propertyErrors,
    )

  /**
   * Where an insert of [inserted] lands with [selectedNodeId] selected: the first accepting slot
   * with room on the selected node, else the first below it in document order, else the selected
   * node's own slot.
   */
  private fun findDestination(
    document: UiBuilderDocument,
    selectedNodeId: String?,
    inserted: ComponentCapability,
  ): ParentSlot? {
    // Root-only components have no destination in a slot on any path (Add, drag):
    // `RecordFreeExport` routes on the root component id.
    if (inserted.componentId in RecordFreeExport.ROOT_ONLY_COMPONENT_IDS) return null
    val selected = selectedNodeId?.let(document.nodes::get)
    if (selected != null) {
      firstAcceptingSlotBelow(document, selected, inserted)?.let {
        return it
      }
    }

    val selectedParent = selectedNodeId?.let(document::location)
    if (selectedParent != null) {
      val parent = document.nodes.getValue(selectedParent.nodeId)
      val slot = catalog.componentsById[parent.componentId]?.slot(selectedParent.slot)
      if (
        slot?.accepts(inserted) == true &&
          slot.hasRoom(parent.slots[selectedParent.slot].orEmpty().size)
      )
        return selectedParent
    }
    return null
  }

  private fun firstAcceptingSlotBelow(
    document: UiBuilderDocument,
    node: UiBuilderNode,
    inserted: ComponentCapability,
  ): ParentSlot? {
    val capability = catalog.componentsById[node.componentId] ?: return null
    val slots =
      capability.slots +
        node.slots.keys.filterNot(capability.slotsByName::containsKey).mapNotNull(capability::slot)
    slots
      .firstOrNull { slot ->
        slot.accepts(inserted) && slot.hasRoom(node.slots[slot.name].orEmpty().size)
      }
      ?.let {
        return ParentSlot(node.id, it.name)
      }
    // Depth-first in slot order, and the document's own topology guarantees no node is visited
    // twice: every node sits in exactly one slot, and no slot leads back to an ancestor.
    return slots
      .asSequence()
      .flatMap { slot -> node.slots[slot.name].orEmpty().asSequence() }
      .mapNotNull(document.nodes::get)
      .firstNotNullOfOrNull { child -> firstAcceptingSlotBelow(document, child, inserted) }
  }
}

/**
 * The picture bytes a widget export inlines: embedded bytes, or those the editor fetched for an
 * uploaded picture.
 */
internal fun UiBuilderDocument.widgetAssetBytes(
  fetched: (contentDigest: String) -> ByteArray?
): WidgetAssetBytes = WidgetAssetBytes { assetKey ->
  val bytes =
    when (val asset = resolveAsset(assetKey)) {
      is ResolvedUiBuilderAsset.Embedded -> asset.bytes
      is ResolvedUiBuilderAsset.Uploaded -> fetched(asset.contentDigest)
      else -> null
    }
  bytes?.takeIf { it.isNotEmpty() }?.let { kotlin.io.encoding.Base64.Default.encode(it) }
}

/** The Code pane's rendering of an A2UI message: indented, because a person reads it there. */
private val a2uiMessageJson = kotlinx.serialization.json.Json { prettyPrint = true }

/**
 * Diagnostics about `CapabilityComposeCodeExporter`'s own reach (symbol, call emitter) rather than
 * the design; see [UiBuilderEditorReducer.problems].
 */
private val GENERATOR_OWNED_CODES = setOf("MISSING_CODE_CAPABILITY", "UNSUPPORTED_CODE_COMPONENT")
