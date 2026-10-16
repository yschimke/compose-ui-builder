@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontRegistry
import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.canvas.UiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.boardItemCount
import ee.schimke.composeai.uibuilder.canvas.forPlatform
import ee.schimke.composeai.uibuilder.canvas.isBoard
import ee.schimke.composeai.uibuilder.canvas.matchingDevicePreset
import ee.schimke.composeai.uibuilder.canvas.withDevicePreset
import ee.schimke.composeai.uibuilder.canvas.withScreenFields
import ee.schimke.composeai.uibuilder.codegen.COMPOSE_EMITTED_CLICK_COMPONENTS
import ee.schimke.composeai.uibuilder.export.FontSettings
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.STATE_SELECTION_CONTAINER
import ee.schimke.composeai.uibuilder.export.TwoWayStateBinding
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.guidelines.LocalDesignGuidelineCheck
import ee.schimke.composeai.uibuilder.inspector.CommentsInspector
import ee.schimke.composeai.uibuilder.inspector.EventActionsInspector
import ee.schimke.composeai.uibuilder.inspector.ReviewDecisionSection
import ee.schimke.composeai.uibuilder.inspector.StateSelectionInspector
import ee.schimke.composeai.uibuilder.inspector.StateVariablesInspector
import ee.schimke.composeai.uibuilder.inspector.SuggestionRow
import ee.schimke.composeai.uibuilder.inspector.SuggestionsSection
import ee.schimke.composeai.uibuilder.reference.ReferenceImportOutcome
import ee.schimke.composeai.uibuilder.reference.ReferenceInspector
import ee.schimke.composeai.uibuilder.reference.ReferencePiece
import ee.schimke.composeai.uibuilder.reference.ReferenceWorkbench
import ee.schimke.composeai.uibuilder.renderer.sdk.SelectableGoogleMaterialIconCount
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.googleMaterialIcon
import ee.schimke.composeai.uibuilder.renderer.sdk.rememberGoogleMaterialIconVector
import ee.schimke.composeai.uibuilder.renderer.sdk.searchGoogleMaterialIcons
import ee.schimke.composeai.uibuilder.role
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal val ENTER_KEYS = setOf(Key.Enter, Key.NumPadEnter)

private data class InspectorPropertyDraftKey(val nodeId: String, val property: String)

private enum class InspectorPropertyDraftStatus {
  DIRTY,
  PENDING,
  REJECTED,
}

private data class InspectorPropertyDraft(
  val value: String,
  /** The authored value when this draft began; a change from it acknowledges a commit. */
  val sourceValue: String,
  val status: InspectorPropertyDraftStatus = InspectorPropertyDraftStatus.DIRTY,
)

@Composable
internal fun PropertyInspector(
  state: UiBuilderEditorState,
  onClose: (() -> Unit)?,
  fields: List<EditorPropertyField>,
  modifierFields: List<EditorModifierField>,
  modifierToggles: List<EditorModifierToggle>,
  stateVariables: List<String>,
  comparisonBindingProperties: Set<String>,
  bindableProperties: Set<String>,
  problems: List<EditorProblem>,
  operationHistory: List<EditorOperationEntry>,
  themeSettings: EditorThemeSettings,
  devicePresets: List<UiBuilderDevicePreset>,
  /** Whether the builder's own canvas (where the variant strip is drawn) is on screen. */
  variantsDrawn: Boolean,
  onPickReference: (suspend () -> ReferenceImportOutcome)?,
  onSnapshotDesign: (suspend () -> ReferenceImportOutcome)?,
  onFlatten: () -> Unit,
  catalogItems: List<EditorCatalogItem>,
  /** This design's own components, for a selected placement's header. */
  localComponents: List<EditorLocalComponent> = emptyList(),
  /** Publishes a component to the project library by key; null where there is no library. */
  onPublishComponent: ((String) -> Unit)? = null,
  /** Takes the library's newer version of a component, by key; null where there is no library. */
  onUpdateFromLibrary: ((String) -> Unit)? = null,
  onPlaceComponent: (String) -> Unit,
  onPromotePiece: (ReferencePiece) -> Unit,
  canPromotePiece: (ReferencePiece) -> Boolean,
  referenceStatus: String?,
  referenceWorkbench: ReferenceWorkbench? = null,
  comments: DesignCommentBoard,
  commentStatus: String?,
  review: DesignReview,
  onDecide: ((DesignReviewVerdict, String?) -> Unit)?,
  reviewStatus: String?,
  suggestionPanel: SuggestionPanel?,
  selectedThreadId: String?,
  onSelectThread: (String?) -> Unit,
  revealThreadId: String?,
  onPostComment: ((DesignCommentDraft) -> Unit)?,
  onResolveCommentThread: ((String, Boolean) -> Unit)?,
  onCopyThreadLink: ((DesignCommentThread) -> Unit)?,
  commentNotifications: CommentNotificationsState = CommentNotificationsState.Hidden,
  onToggleCommentNotifications: (() -> Unit)? = null,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  modifier: Modifier = Modifier.width(INSPECTOR_WIDTH).fillMaxHeight(),
  /** The catalog's design tokens as this design reads them, for the Theme panel. */
  designTokens: List<EditorDesignTokenRow> = emptyList(),
  /** The theme host the Theme panel edits; see [UiBuilderEditorReducer.themePanelHost]. */
  themeHost: EditorThemeHost? = null,
) {
  val node = state.selectedNodeId?.let(state.document.nodes::get)
  val propertyDrafts =
    remember(state.document.id) {
      mutableStateMapOf<InspectorPropertyDraftKey, InspectorPropertyDraft>()
    }
  LaunchedEffect(state.document.nodes.keys) {
    propertyDrafts.keys
      .filterNot { it.nodeId in state.document.nodes }
      .forEach(propertyDrafts::remove)
  }
  LaunchedEffect(state.selectedNodeId, fields.map { it.name }) {
    val selectedNodeId = state.selectedNodeId ?: return@LaunchedEffect
    val visibleProperties = fields.mapTo(mutableSetOf()) { it.name }
    propertyDrafts.keys
      .filter { it.nodeId == selectedNodeId && it.property !in visibleProperties }
      .forEach(propertyDrafts::remove)
  }
  LocalUiBuilderChrome.current.InspectorSurface(modifier) {
    Column {
      // The inspector modes are rail switches, not tabs, so the panel can stay narrow.
      DockHeading(
        title =
          when (state.inspectorMode) {
            EditorInspectorMode.Properties -> "Properties"
            EditorInspectorMode.Theme -> "Theme"
            EditorInspectorMode.Screen -> "Screen"
            EditorInspectorMode.Issues -> problemHeading(problems)
            EditorInspectorMode.Comments ->
              comments.openThreads.size.let { if (it == 0) "Talk" else "Talk · $it" }
            EditorInspectorMode.History -> "History"
          },
        supporting =
          when (state.inspectorMode) {
            EditorInspectorMode.Properties ->
              if (propertyDrafts.isEmpty()) node?.id ?: "Nothing selected"
              else
                "${propertyDrafts.size} uncommitted edit${if (propertyDrafts.size == 1) "" else "s"} retained"
            EditorInspectorMode.Theme -> "Colours, type and shape for the whole design"
            EditorInspectorMode.Screen -> "Frame, density and reference"
            EditorInspectorMode.Issues -> "What the export would refuse"
            EditorInspectorMode.Comments -> "What people and agents have said"
            EditorInspectorMode.History -> "What has been done, newest first"
          },
        onClose = onClose,
      )
      // Swatches in the property controls show the design's colours, not the editor chrome's.
      CompositionLocalProvider(
        LocalInspectorColorScheme provides inspectorColorScheme(themeSettings)
      ) {
        InspectorBody(
          state = state,
          node = node,
          fields = fields,
          modifierFields = modifierFields,
          modifierToggles = modifierToggles,
          stateVariables = stateVariables,
          comparisonBindingProperties = comparisonBindingProperties,
          bindableProperties = bindableProperties,
          problems = problems,
          operationHistory = operationHistory,
          themeSettings = themeSettings,
          devicePresets = devicePresets,
          variantsDrawn = variantsDrawn,
          onPickReference = onPickReference,
          onSnapshotDesign = onSnapshotDesign,
          onFlatten = onFlatten,
          catalogItems = catalogItems,
          localComponents = localComponents,
          onPublishComponent = onPublishComponent,
          onUpdateFromLibrary = onUpdateFromLibrary,
          onPlaceComponent = onPlaceComponent,
          onPromotePiece = onPromotePiece,
          canPromotePiece = canPromotePiece,
          referenceStatus = referenceStatus,
          referenceWorkbench = referenceWorkbench,
          comments = comments,
          commentStatus = commentStatus,
          review = review,
          onDecide = onDecide,
          reviewStatus = reviewStatus,
          suggestionPanel = suggestionPanel,
          selectedThreadId = selectedThreadId,
          onSelectThread = onSelectThread,
          revealThreadId = revealThreadId,
          onPostComment = onPostComment,
          onResolveCommentThread = onResolveCommentThread,
          onCopyThreadLink = onCopyThreadLink,
          commentNotifications = commentNotifications,
          onToggleCommentNotifications = onToggleCommentNotifications,
          onTextInputFocusChanged = onTextInputFocusChanged,
          propertyDrafts = propertyDrafts,
          dispatch = dispatch,
          designTokens = designTokens,
          themeHost = themeHost,
        )
      }
    }
  }
}

/** Whichever inspector the rail has chosen, drawn under [PropertyInspector]'s heading. */
@Composable
private fun InspectorBody(
  state: UiBuilderEditorState,
  node: UiBuilderNode?,
  fields: List<EditorPropertyField>,
  modifierFields: List<EditorModifierField>,
  modifierToggles: List<EditorModifierToggle>,
  stateVariables: List<String>,
  comparisonBindingProperties: Set<String>,
  bindableProperties: Set<String>,
  problems: List<EditorProblem>,
  operationHistory: List<EditorOperationEntry>,
  themeSettings: EditorThemeSettings,
  devicePresets: List<UiBuilderDevicePreset>,
  /** Whether the builder's own canvas (where the variant strip is drawn) is on screen. */
  variantsDrawn: Boolean,
  onPickReference: (suspend () -> ReferenceImportOutcome)?,
  onSnapshotDesign: (suspend () -> ReferenceImportOutcome)?,
  onFlatten: () -> Unit,
  catalogItems: List<EditorCatalogItem>,
  /** This design's own components, for a selected placement's header. */
  localComponents: List<EditorLocalComponent> = emptyList(),
  /** Publishes a component to the project library by key; null where there is no library. */
  onPublishComponent: ((String) -> Unit)? = null,
  /** Takes the library's newer version of a component, by key; null where there is no library. */
  onUpdateFromLibrary: ((String) -> Unit)? = null,
  onPlaceComponent: (String) -> Unit,
  onPromotePiece: (ReferencePiece) -> Unit,
  canPromotePiece: (ReferencePiece) -> Boolean,
  referenceStatus: String?,
  referenceWorkbench: ReferenceWorkbench? = null,
  comments: DesignCommentBoard,
  commentStatus: String?,
  review: DesignReview,
  onDecide: ((DesignReviewVerdict, String?) -> Unit)?,
  reviewStatus: String?,
  suggestionPanel: SuggestionPanel?,
  selectedThreadId: String?,
  onSelectThread: (String?) -> Unit,
  revealThreadId: String?,
  onPostComment: ((DesignCommentDraft) -> Unit)?,
  onResolveCommentThread: ((String, Boolean) -> Unit)?,
  onCopyThreadLink: ((DesignCommentThread) -> Unit)?,
  commentNotifications: CommentNotificationsState = CommentNotificationsState.Hidden,
  onToggleCommentNotifications: (() -> Unit)? = null,
  onTextInputFocusChanged: (Boolean) -> Unit,
  propertyDrafts: MutableMap<InspectorPropertyDraftKey, InspectorPropertyDraft>,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  designTokens: List<EditorDesignTokenRow>,
  themeHost: EditorThemeHost?,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
    if (state.inspectorMode == EditorInspectorMode.Issues) {
      // One scrolling list: the guidelines section is its first item rather than a box of its
      // own above it, so nothing is squeezed into a short inner scroll that nobody notices.
      val guidelines = LocalDesignGuidelineCheck.current
      ProblemsInspector(
        problems,
        dispatch,
        header =
          guidelines?.let {
            { GuidelinesSection(it, state.document, onTextInputFocusChanged, dispatch) }
          },
      )
      return@Column
    }
    if (state.inspectorMode == EditorInspectorMode.History) {
      OperationHistoryInspector(operationHistory) { nodeId ->
        dispatch(UiBuilderEditorEvent.SelectNode(nodeId))
      }
      return@Column
    }
    if (state.inspectorMode == EditorInspectorMode.Comments) {
      // Scrolled so many threads are not clipped. The state is hoisted so a `#thread=` link can
      // scroll one thread into view.
      val commentScroll = rememberScrollState()
      Column(Modifier.verticalScroll(commentScroll)) {
        // Suggestions above the verdict: a proposal is decided before its revision is reviewed.
        // Null when the host keeps no suggestions.
        suggestionPanel?.let { panel ->
          SuggestionsSection(
            rows = panel.rows,
            shownSuggestionId = panel.shownSuggestionId,
            outcome = panel.outcome,
            hostStatus = panel.status,
            onShow = panel.onShow,
            onAccept = panel.onAccept,
            onReject = panel.onReject,
          )
        }
        if (onDecide != null || review.decisions.isNotEmpty()) {
          ReviewDecisionSection(
            review = review,
            revision = state.document.revision.toLong(),
            onDecide = onDecide,
            hostStatus = reviewStatus,
            onTextInputFocusChanged = onTextInputFocusChanged,
          )
        }
        CommentsInspector(
          board = comments,
          reference = state.reference,
          selectedNodeId = state.selectedNodeId,
          nodeLabel = { nodeId -> state.document.nodes[nodeId]?.componentId ?: nodeId },
          selectedThreadId = selectedThreadId,
          onSelectThread = onSelectThread,
          revealThreadId = revealThreadId,
          scrollState = commentScroll,
          onPost = onPostComment,
          onResolve = onResolveCommentThread,
          onCopyLink = onCopyThreadLink,
          hostStatus = commentStatus,
          onTextInputFocusChanged = onTextInputFocusChanged,
          notifications = commentNotifications,
          onToggleNotifications = onToggleCommentNotifications,
        )
      }
      return@Column
    }
    if (state.inspectorMode == EditorInspectorMode.Theme) {
      ThemePanel(
        state = state,
        settings = themeSettings,
        host = themeHost,
        designTokens = designTokens,
        onTextInputFocusChanged = onTextInputFocusChanged,
        dispatch = dispatch,
      )
      return@Column
    }
    if (state.inspectorMode == EditorInspectorMode.Screen) {
      // Scrolled, so the last control is never clipped.
      Column(Modifier.verticalScroll(rememberScrollState())) {
        if (UiBuilderBuildFeatures.remoteCompose) {
          StateVariablesInspector(state.document, onTextInputFocusChanged, dispatch)
          HorizontalDivider(Modifier.padding(vertical = 10.dp))
        }
        ScreenEnvironmentInspector(
          document = state.document,
          devicePresets = devicePresets,
          catalogFrameSizes = state.catalogFrameSizes,
          platform = state.platform,
          variantAxes = state.variantAxes,
          variantsDrawn = variantsDrawn,
          onTextInputFocusChanged = onTextInputFocusChanged,
          dispatch = dispatch,
        )
        HorizontalDivider(
          Modifier.padding(vertical = 14.dp),
          color = MaterialTheme.colorScheme.outline,
        )
        ReferenceInspector(
          reference = state.reference,
          themeSettings = themeSettings,
          onPickReference = onPickReference,
          onSnapshotDesign = onSnapshotDesign,
          onFlatten = onFlatten,
          catalogItems = catalogItems,
          onPlaceComponent = onPlaceComponent,
          onPromotePiece = onPromotePiece,
          canPromotePiece = canPromotePiece,
          hostStatus = referenceStatus,
          dispatch = dispatch,
          workbench = referenceWorkbench,
        )
      }
      return@Column
    }
    if (node == null) {
      LocalUiBuilderChrome.current.InspectorMessage(
        "Select a layer on the canvas or in the tree.",
        Modifier.padding(top = 16.dp),
      )
      return@Column
    }
    val placed = node.placementKey()?.let { key -> localComponents.firstOrNull { it.key == key } }
    if (placed != null) {
      LocalComponentHeader(
        placed,
        node.id,
        onTextInputFocusChanged,
        onPublishComponent,
        onUpdateFromLibrary,
        dispatch,
      )
    } else {
      LocalUiBuilderChrome.current.InspectorNodeIdentity(node.componentId, node.id)
    }
    // Properties revealed for this node since selection. Local: revealing shows a control and
    // writes nothing until it is used.
    var revealed by remember(node.id) { mutableStateOf(emptySet<String>()) }
    var propertyQuery by remember(node.id) { mutableStateOf("") }
    var addingProperty by remember(node.id) { mutableStateOf(false) }
    // Opens on what the export would write, plus bound properties and any a problem names. A theme
    // host's typefaces and default text style are shown in the Theme panel instead; `fields` still
    // keeps them for validation.
    val themePanelOwned = ownsThemePanelProperties(fields)
    val listedFields = inspectorPropertyFields(fields)
    fun shown(field: EditorPropertyField): Boolean =
      field.written ||
        field.required ||
        field.boundVariable != null ||
        field.error != null ||
        field.name in revealed
    // The rows inside a list or object property come and go with its heading: revealing the
    // property shows them all, and none is offered, or shown, on its own.
    val shownStructures =
      listedFields
        .filter { it.structure?.depth == 0 && shown(it) }
        .mapNotNull { it.structure?.property }
        .toSet()
    val shownFields =
      listedFields
        .filter { it.name != SHOW_BY_STATE }
        .filter { field ->
          val structure = field.structure
          if (structure == null || structure.depth == 0) shown(field)
          else structure.property in shownStructures
        }
    val shownNames = shownFields.map { it.name }.toSet()
    fun matches(field: EditorPropertyField): Boolean =
      propertyQuery.isBlank() ||
        field.label.contains(propertyQuery, ignoreCase = true) ||
        field.name.contains(propertyQuery, ignoreCase = true)
    val visibleFields = shownFields.filter(::matches)
    // Everything the component allows and this node has not been given. Offered, never listed: a
    // search reaches it in one word, and until then it is thirty controls nobody asked for.
    val addableFields =
      listedFields
        .filterNot {
          it.name in shownNames || it.name == SHOW_BY_STATE || (it.structure?.depth ?: 0) > 0
        }
        .filter(::matches)
    // Open the drawer whenever a search is running, so typing a property's name finds it whether
    // or not the node already has one.
    val addOpen = addingProperty || propertyQuery.isNotBlank()
    if (fields.isNotEmpty()) {
      Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.weight(1f)) {
          SearchField(
            value = propertyQuery,
            placeholder = "Search properties",
            searchLabel = "Property search",
            onFocusChanged = onTextInputFocusChanged,
            onValueChange = { propertyQuery = it },
          )
        }
        ToolbarIconAction(
          label = if (addOpen) "Close add property" else "Add property",
          shortcut = "",
          icon = if (addOpen) UiBuilderChromeIcon.Close else UiBuilderChromeIcon.Add,
          enabled = true,
        ) {
          addingProperty = !addOpen
          if (!addingProperty) propertyQuery = ""
        }
      }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
      if (themePanelOwned) {
        item(key = "theme-panel-pointer") {
          ThemePanelPointer {
            dispatch(UiBuilderEditorEvent.ShowInspector(EditorInspectorMode.Theme))
          }
        }
      }
      itemsIndexed(visibleFields, key = { _, field -> field.name }) { _, field ->
        // A plain number on one node can be handed to a tunable; see [DesignTunable].
        val tuneTarget =
          TunableTarget.Property(node.id, field.name).takeIf {
            field.control == EditorPropertyControl.Number &&
              field.structure == null &&
              field.nodeCount == 1 &&
              field.boundVariable == null &&
              state.document.canTune(it)
          }
        val structure = field.structure
        Row(
          // A row inside a list or object sits under the row it belongs to.
          Modifier.padding(start = (STRUCTURE_INDENT_DP * (structure?.depth ?: 0)).dp),
          verticalAlignment = Alignment.Top,
        ) {
          Box(Modifier.weight(1f)) {
            PropertyControl(
              field = field,
              stateVariables =
                if (field.name in bindableProperties) stateVariables else emptyList(),
              needsComparison = { variable ->
                val declaration = state.document.stateVariables[variable] as? JsonObject
                val valueType = (declaration?.get("valueType") as? JsonPrimitive)?.content
                val booleanState =
                  valueType == "bool" ||
                    (valueType == null &&
                      (declaration?.get("initialValue") as? JsonPrimitive)?.booleanOrNull != null)
                val nullableState =
                  (declaration?.get("nullable") as? JsonPrimitive)?.booleanOrNull == true ||
                    declaration?.get("initialValue") is JsonNull
                field.name in comparisonBindingProperties && (!booleanState || nullableState)
              },
              onTextInputFocusChanged = onTextInputFocusChanged,
              draft = propertyDrafts[InspectorPropertyDraftKey(field.nodeId, field.name)],
              onDraftChange = { draft ->
                val key = InspectorPropertyDraftKey(field.nodeId, field.name)
                if (draft == null) propertyDrafts.remove(key) else propertyDrafts[key] = draft
              },
              onBind = { variable, equalsValue ->
                dispatch(
                  UiBuilderEditorEvent.BindPropertyToState(
                    node.id,
                    field.name,
                    variable,
                    equalsValue,
                  )
                )
              },
              onUnbind = { dispatch(UiBuilderEditorEvent.UnbindProperty(node.id, field.name)) },
              onFormula = { formula ->
                dispatch(UiBuilderEditorEvent.BindPropertyToFormula(node.id, field.name, formula))
              },
              commit = { value ->
                dispatch(
                  when {
                    // One scalar inside a list or object: the property is written whole.
                    structure != null ->
                      UiBuilderEditorEvent.CommitPropertyPath(
                        node.id,
                        structure.property,
                        structure.path,
                        value,
                      )
                    // A font setting with nothing left in it is no setting: unset, not `""`.
                    value.isBlank() && field.name in FontSettings.PROPERTIES ->
                      UiBuilderEditorEvent.ClearProperty(node.id, field.name)
                    else -> UiBuilderEditorEvent.CommitProperty(node.id, field.name, value)
                  }
                )
              },
              onListEdit = { edit ->
                if (structure != null) {
                  dispatch(
                    UiBuilderEditorEvent.EditPropertyList(
                      node.id,
                      structure.property,
                      structure.path,
                      edit,
                    )
                  )
                }
              },
              textTypeface = { FontSettings.textTypeface(state.document, field.nodeId) },
              contrastOf = { scheme ->
                contrastReading(state.document, field.nodeId, field.name, field.value, scheme)
              },
              recentColours = state.recentColours,
            )
          }
          if (tuneTarget != null) TuneFieldButton(tuneTarget, state.tunables, dispatch)
          val row = structure?.row
          if (structure != null && row != null && field.nodeCount == 1) {
            ListRowActions(row, field.label) { edit ->
              dispatch(
                UiBuilderEditorEvent.EditPropertyList(
                  node.id,
                  structure.property,
                  // The row's own list: the path without the row's index.
                  structure.path.dropLast(1),
                  edit,
                )
              )
            }
          }
        }
      }
      if (fields.isEmpty()) {
        item {
          LocalUiBuilderChrome.current.InspectorMessage("This component has no catalog properties.")
        }
      } else if (visibleFields.isEmpty() && !addOpen) {
        item {
          LocalUiBuilderChrome.current.InspectorMessage(
            "Nothing is set on this layer. Add a property to give it one."
          )
        }
      }
      if (addOpen) {
        item {
          HorizontalDivider(Modifier.padding(vertical = 12.dp))
          LocalUiBuilderChrome.current.InspectorSection(
            title =
              if (propertyQuery.isBlank()) "Add a property"
              else "Add a property · ${addableFields.size} match",
            supporting =
              "The catalog allows these. Adding one shows its control; the export writes it once it has a value.",
          )
        }
        itemsIndexed(addableFields, key = { _, field -> "add:${field.name}" }) { _, field ->
          AddPropertyRow(field) {
            revealed = revealed + field.name
            addingProperty = false
            propertyQuery = ""
          }
        }
        if (addableFields.isEmpty()) {
          item {
            LocalUiBuilderChrome.current.InspectorMessage(
              "Every property this component declares is already here.",
              Modifier.padding(top = 6.dp),
            )
          }
        }
      }
      if (
        UiBuilderBuildFeatures.remoteCompose &&
          node.componentId == STATE_SELECTION_CONTAINER &&
          fields.any { it.name == SHOW_BY_STATE }
      ) {
        item { StateSelectionInspector(state.document, node, onTextInputFocusChanged, dispatch) }
      }
      if (
        UiBuilderBuildFeatures.remoteCompose &&
          (node.componentId in COMPOSE_EMITTED_CLICK_COMPONENTS ||
            node.eventBindings.isNotEmpty() ||
            // A Wear or Remote selection row, whose change event the catalog adapters run.
            TwoWayStateBinding.changeEvent(node.componentId, node.properties.keys) != null)
      ) {
        item { EventActionsInspector(state.document, node, onTextInputFocusChanged, dispatch) }
      }
      if (UiBuilderBuildFeatures.remoteCompose) {
        if (node.modifiers.isNotEmpty() || modifierToggles.isNotEmpty()) {
          item {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Text("Layout", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
              var addModifier by remember(node.id) { mutableStateOf(false) }
              val available = modifierToggles.filterNot { it.applied }
              Box {
                TextButton(onClick = { addModifier = true }, enabled = available.isNotEmpty()) {
                  Text("Add modifier")
                }
                TrackEditorOverlay(addModifier)
                DropdownMenu(expanded = addModifier, onDismissRequest = { addModifier = false }) {
                  available.forEach { item ->
                    DropdownMenuItem(
                      text = { Text(item.label) },
                      onClick = {
                        addModifier = false
                        dispatch(UiBuilderEditorEvent.ToggleModifier(node.id, item.type))
                      },
                    )
                  }
                }
              }
            }
          }
          itemsIndexed(node.modifiers) { index, modifier ->
            val type = (modifier as? JsonObject)?.get("type")?.jsonPrimitive?.content.orEmpty()
            val editable = modifierFields.filter { it.index == index }
            val label =
              modifierToggles
                .firstOrNull { it.type == type }
                ?.label
                ?.removePrefix("Add ")
                ?.replaceFirstChar { it.uppercase() }
                ?: type.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar {
                  it.uppercase()
                }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
              Text(label, style = MaterialTheme.typography.labelMedium)
              editable.forEach { field ->
                key(node.id, index, field.field) {
                  val tuneTarget =
                    TunableTarget.Modifier(node.id, index, field.type, field.field).takeIf {
                      field.control == EditorPropertyControl.Number && state.document.canTune(it)
                    }
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                      HoverEditorRow(
                        label = field.label,
                        value = field.value,
                        control = field.control,
                        choices = field.choices,
                        focused = false,
                        onFocusHandled = {},
                        onTextInputFocusChanged = onTextInputFocusChanged,
                      ) { value ->
                        dispatch(
                          UiBuilderEditorEvent.SetModifierValue(
                            node.id,
                            field.type,
                            field.field,
                            value,
                            index,
                          )
                        )
                      }
                    }
                    if (tuneTarget != null) TuneFieldButton(tuneTarget, state.tunables, dispatch)
                  }
                }
              }
              if (editable.isEmpty() && type !in modifierToggles.map { it.type }) {
                Text(
                  modifier.toString(),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 3,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
      } else {
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
    }
  }
}

/**
 * What a theme host's property list shows instead of its typefaces and default text style: one line
 * and the way to the Theme panel.
 */
@Composable
private fun ThemePanelPointer(onOpenTheme: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "Typefaces and the default text style are set in Theme.",
      Modifier.weight(1f),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
      onClick = onOpenTheme,
      modifier = Modifier.semantics { contentDescription = "Open Theme panel" },
    ) {
      Text("Open Theme")
    }
  }
}

/** One property the node does not have yet, and the press that puts its control on the panel. */
@Composable
private fun AddPropertyRow(field: EditorPropertyField, onAdd: () -> Unit) {
  LocalUiBuilderChrome.current.InspectorAddPropertyRow(
    field.label,
    field.control.name.lowercase(),
    onAdd,
  )
}

@Composable
private fun PropertyControl(
  field: EditorPropertyField,
  stateVariables: List<String>,
  needsComparison: (String) -> Boolean,
  onTextInputFocusChanged: (Boolean) -> Unit,
  draft: InspectorPropertyDraft?,
  onDraftChange: (InspectorPropertyDraft?) -> Unit,
  onBind: (String, String?) -> Unit,
  onUnbind: () -> Unit,
  onFormula: (String) -> Unit,
  commit: (String) -> Unit,
  /** The face a text field's font settings apply to; read only for those two properties. */
  textTypeface: () -> FontSettings.TextTypeface? = { null },
  /** How a colour property contrasts with what it is drawn over; read only for colour fields. */
  contrastOf: (ColorScheme) -> ContrastReading? = { null },
  /**
   * The colours last committed to any colour property this session; read only for colour fields.
   */
  recentColours: List<String> = emptyList(),
  /** A change to the rows of a list property; read only for [EditorPropertyControl.List]. */
  onListEdit: (EditorListEdit) -> Unit = {},
) {
  val bound = field.boundVariable
  val formula = field.boundFormula
  LocalUiBuilderChrome.current.InspectorProperty(
    UiBuilderInspectorPropertyModel(
      label =
        field.label +
          (if (field.required) " *" else "") +
          (if (field.nodeCount > 1) " · ${field.nodeCount} selected" else "") +
          (if (field.mixed) " · mixed" else ""),
      // A bound property replaces its literal editor and its supporting diagnostics with the
      // binding row, matching the existing inspector behavior.
      notes = field.notes?.takeIf { bound == null && formula == null },
      error = field.error?.takeIf { bound == null },
      supporting = field.supporting?.takeIf { bound == null && formula == null },
    )
  ) {
    if (formula != null) {
      FormulaEditor(field, formula, onTextInputFocusChanged, onFormula, onUnbind)
    } else if (bound != null) {
      // No literal control for a bound property (edits would be refused); show the binding and how
      // to unbind.
      StateBindingRow(bound, onUnbind)
    } else {
      if (stateVariables.isNotEmpty() && field.control != EditorPropertyControl.Unsupported) {
        StateBindMenu(field, stateVariables, needsComparison, onTextInputFocusChanged, onBind)
      }
      if (field.formulaAllowed) {
        FormulaEditor(field, null, onTextInputFocusChanged, onFormula, onUnbind = null)
      }
      // A text's axes and features are offered from the face, with the property's text field below
      // for a whole value.
      if (field.name in FontSettings.PROPERTIES && !field.mixed) {
        FontSettingsEditor(field.name, field.value, textTypeface(), commit)
      }
      when (field.control) {
        EditorPropertyControl.Boolean -> {
          val checked = field.value.toBooleanStrictOrNull() ?: false
          LocalUiBuilderChrome.current.InspectorBooleanProperty(field.label, checked) {
            commit(it.toString())
          }
        }
        EditorPropertyControl.Enum ->
          if (field.name in ICON_KEY_PROPERTIES)
            GoogleIconPropertyControl(field, onTextInputFocusChanged, commit)
          else EnumPropertyControl(field, commit)
        EditorPropertyControl.Number ->
          DraftPropertyControl(
            field,
            onTextInputFocusChanged,
            draft,
            onDraftChange,
            commit,
            showSteppers = true,
          )
        EditorPropertyControl.Color ->
          ColorPropertyControl(field, commit, contrastOf, recentColours) {
            DraftPropertyControl(
              field,
              onTextInputFocusChanged,
              draft,
              onDraftChange,
              commit,
              showSteppers = false,
            )
          }
        EditorPropertyControl.Text ->
          DraftPropertyControl(
            field,
            onTextInputFocusChanged,
            draft,
            onDraftChange,
            commit,
            showSteppers = false,
          )
        EditorPropertyControl.List -> ListHeaderControl(field, onListEdit, commit)
        // The heading of an object's fields, which follow as their own rows.
        EditorPropertyControl.Object -> LocalUiBuilderChrome.current.InspectorMessage(field.value)
        EditorPropertyControl.Unsupported ->
          LocalUiBuilderChrome.current.InspectorMessage(field.value.ifEmpty { "Not set" })
      }
    }
  }
}

/** How far each level of a list or object property is indented under the row it belongs to. */
private const val STRUCTURE_INDENT_DP = 12

/**
 * A list property's heading: how many rows it holds, and the press that adds one seeded from the
 * element shape (an object's field defaults, or an empty scalar). Its rows follow as their own
 * fields, each with [ListRowActions].
 */
@Composable
private fun ListHeaderControl(
  field: EditorPropertyField,
  onListEdit: (EditorListEdit) -> Unit,
  commit: (String) -> Unit,
) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      field.value,
      Modifier.weight(1f),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!field.mixed) {
      TextButton(
        onClick = { onListEdit(EditorListEdit.Insert()) },
        modifier = Modifier.semantics { contentDescription = "Add an item to ${field.label}" },
      ) {
        Text("Add item")
      }
      // A nullable list offers its null back: the heading's one choice.
      field.choices.firstOrNull()?.let { none ->
        TextButton(
          onClick = { commit(none) },
          modifier = Modifier.semantics { contentDescription = "Set ${field.label} to $none" },
        ) {
          Text(none)
        }
      }
    }
  }
}

/** Move one list row up or down, or remove it: each one undoable edit of the whole property. */
@Composable
private fun ListRowActions(row: EditorListRow, label: String, onEdit: (EditorListEdit) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    TextButton(
      onClick = { onEdit(EditorListEdit.Move(row.index, row.index - 1)) },
      enabled = row.index > 0,
      modifier = Modifier.semantics { contentDescription = "Move $label up" },
    ) {
      Text("\u2191")
    }
    TextButton(
      onClick = { onEdit(EditorListEdit.Move(row.index, row.index + 1)) },
      enabled = row.index < row.count - 1,
      modifier = Modifier.semantics { contentDescription = "Move $label down" },
    ) {
      Text("\u2193")
    }
    ToolbarIconAction(
      label = "Remove $label",
      shortcut = "",
      icon = UiBuilderChromeIcon.Remove,
      enabled = true,
    ) {
      onEdit(EditorListEdit.Remove(row.index))
    }
  }
}

/**
 * What a bound property shows instead of a literal control (which the reducer would refuse): what
 * it is bound to, and the unbind action.
 */
@Composable
private fun StateBindingRow(variable: String, onUnbind: () -> Unit) {
  LocalUiBuilderChrome.current.InspectorBinding(variable, onUnbind)
}

/**
 * A property computed by a formula, or the way to give it one. Shown and edited as typed text
 * (`time.secondOfHour * 6`); the reducer parses and type-checks it, reporting the column on
 * failure. The canvas shows its value at the preview state and fixed time.
 */
@Composable
private fun FormulaEditor(
  field: EditorPropertyField,
  current: String?,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onFormula: (String) -> Unit,
  onUnbind: (() -> Unit)?,
) {
  var open by remember(field.nodeId, field.name) { mutableStateOf(current != null) }
  var text by remember(field.nodeId, field.name, current) { mutableStateOf(current.orEmpty()) }
  if (!open) {
    LocalUiBuilderChrome.current.InspectorAction(
      UiBuilderInspectorActionModel(
        label = "Formula",
        contentDescription = "Compute ${field.label} with a formula",
        compactLabel = true,
        onClick = { open = true },
      )
    )
    return
  }
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.weight(1f)) {
      SearchField(
        text,
        placeholder = "e.g. time.secondOfHour * 6",
        searchLabel = "${field.label} formula",
        onFocusChanged = onTextInputFocusChanged,
      ) {
        text = it
      }
    }
    LocalUiBuilderChrome.current.InspectorAction(
      UiBuilderInspectorActionModel(
        label = "Apply",
        enabled = text.isNotBlank() && text != current,
        primary = true,
        onClick = { onFormula(text) },
      )
    )
    if (onUnbind != null) {
      LocalUiBuilderChrome.current.InspectorAction(
        UiBuilderInspectorActionModel(
          label = "Clear",
          contentDescription = "Give ${field.label} a value of its own again",
          onClick = onUnbind,
        )
      )
    }
  }
}

/**
 * Binding a property to a declared state variable: a bare read, or `stateEquals` where the
 * property's type cannot take the variable's value (asking for the comparison value only after a
 * variable is chosen).
 */
@Composable
private fun StateBindMenu(
  field: EditorPropertyField,
  stateVariables: List<String>,
  needsComparison: (String) -> Boolean,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onBind: (String, String?) -> Unit,
) {
  var open by remember(field.nodeId, field.name) { mutableStateOf(false) }
  var pending by remember(field.nodeId, field.name) { mutableStateOf<String?>(null) }
  var comparison by remember(field.nodeId, field.name) { mutableStateOf("") }
  Box {
    LocalUiBuilderChrome.current.InspectorAction(
      UiBuilderInspectorActionModel(
        label = "Bind to state",
        contentDescription = "Bind ${field.label} to state",
        compactLabel = true,
        onClick = { open = true },
      )
    )
    LocalUiBuilderChrome.current.PopupMenu(
      expanded = open,
      onDismissRequest = { open = false },
      entries =
        stateVariables.map { variable ->
          UiBuilderMenuEntry.Action(variable) {
            open = false
            if (needsComparison(variable)) pending = variable else onBind(variable, null)
          }
        },
    )
  }
  val variable = pending
  if (variable != null) {
    Text(
      "$variable equals",
      Modifier.padding(top = 4.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f)) {
        SearchField(
          comparison,
          placeholder = "Value to compare",
          searchLabel = "${field.label} state comparison",
          onFocusChanged = onTextInputFocusChanged,
        ) {
          comparison = it
        }
      }
      LocalUiBuilderChrome.current.InspectorAction(
        UiBuilderInspectorActionModel(
          label = "Bind",
          enabled = comparison.isNotBlank(),
          primary = true,
          onClick = {
            onBind(variable, comparison)
            pending = null
            comparison = ""
          },
        )
      )
    }
  }
}

@Composable
private fun DraftPropertyControl(
  field: EditorPropertyField,
  onTextInputFocusChanged: (Boolean) -> Unit,
  draft: InspectorPropertyDraft?,
  onDraftChange: (InspectorPropertyDraft?) -> Unit,
  commit: (String) -> Unit,
  showSteppers: Boolean,
) {
  val value = draft?.value ?: field.value
  val dirty = value != field.value
  val multiline = field.name == "text"
  val valid = !showSteppers || value.toDoubleOrNull() != null
  fun update(next: String) {
    onDraftChange(
      if (next == field.value) null
      else
        InspectorPropertyDraft(
          next,
          draft?.sourceValue ?: field.value,
          InspectorPropertyDraftStatus.DIRTY,
        )
    )
  }
  fun submit(next: String = value) {
    if (next == field.value) {
      onDraftChange(null)
      return
    }
    if (!showSteppers || next.toDoubleOrNull() != null) {
      onDraftChange(InspectorPropertyDraft(next, field.value, InspectorPropertyDraftStatus.PENDING))
      commit(next)
    }
  }
  LaunchedEffect(field.value, draft?.status) {
    if (draft?.status == InspectorPropertyDraftStatus.PENDING) {
      if (field.value != draft.sourceValue) onDraftChange(null)
      else onDraftChange(draft.copy(status = InspectorPropertyDraftStatus.REJECTED))
    }
  }
  // Field and Apply on one line, with Apply only once the value has been edited.
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    LocalUiBuilderChrome.current.InspectorTextField(
      UiBuilderInspectorTextFieldModel(
        value = value,
        label = field.label,
        multiline = multiline,
        submitEnabled = dirty && valid,
        modifier = Modifier.weight(1f),
        onFocusChanged = onTextInputFocusChanged,
        onValueChange = ::update,
        onSubmit = { submit() },
      )
    )
    if (dirty) {
      LocalUiBuilderChrome.current.InspectorAction(
        UiBuilderInspectorActionModel(
          label = "Apply",
          contentDescription = "Apply ${field.label.lowercase()}",
          enabled = valid && draft?.status != InspectorPropertyDraftStatus.PENDING,
          primary = true,
          horizontalPaddingDp = 10,
          modifier = Modifier.padding(start = 4.dp, top = 7.dp),
          onClick = { submit() },
        )
      )
    }
  }
  if (dirty) {
    val status =
      when {
        !valid -> "Invalid number · edit retained"
        draft?.status == InspectorPropertyDraftStatus.PENDING -> "Applying edit…"
        draft?.status == InspectorPropertyDraftStatus.REJECTED ->
          "Edit was not applied · value retained"
        multiline -> "Uncommitted edit retained · $COMMAND_MODIFIER+Enter applies"
        else -> "Uncommitted edit retained · Enter applies"
      }
    Text(
      status,
      Modifier.semantics {
        contentDescription = "${field.label}: $status"
        liveRegion = LiveRegionMode.Polite
      },
      color =
        if (!valid || draft?.status == InspectorPropertyDraftStatus.REJECTED)
          MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
  if (showSteppers) {
    val bounds = field.numberBounds
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      LocalUiBuilderChrome.current.InspectorAction(
        UiBuilderInspectorActionModel(
          label = "−",
          horizontalPaddingDp = 12,
          onClick = {
            val current = value.toDoubleOrNull() ?: bounds?.minimum ?: 0.0
            val next =
              (current - (bounds?.step ?: 1.0))
                .coerceIn(bounds!!.minimum, bounds.maximum)
                .editorNumber(bounds.integer)
            submit(next)
          },
        )
      )
      Text(
        bounds
          ?.let { "${it.minimum.editorNumber(it.integer)}…${it.maximum.editorNumber(it.integer)}" }
          .orEmpty(),
        Modifier.weight(1f),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      LocalUiBuilderChrome.current.InspectorAction(
        UiBuilderInspectorActionModel(
          label = "+",
          horizontalPaddingDp = 12,
          onClick = {
            val current = value.toDoubleOrNull() ?: bounds?.minimum ?: 0.0
            val next =
              (current + (bounds?.step ?: 1.0))
                .coerceIn(bounds!!.minimum, bounds.maximum)
                .editorNumber(bounds.integer)
            submit(next)
          },
        )
      )
    }
  }
  if (field.name == "text") {
    LocalUiBuilderChrome.current.InspectorAction(
      UiBuilderInspectorActionModel(
        label = "Use sample text",
        horizontalPaddingDp = 10,
        onClick = { submit("Edited in Compose") },
      )
    )
  }
}

internal enum class ProblemAudience {
  AUTHOR,
  CATALOG_OR_TOOLING,
}

internal data class EditorProblemGroup(
  val code: String,
  val title: String,
  val problems: List<EditorProblem>,
  val blocking: Boolean,
  val rootCause: Boolean,
  val audience: ProblemAudience,
) {
  val nodeId: String?
    get() = problems.first().nodeId

  val componentId: String?
    get() = problems.first().componentId
}

private val DOWNSTREAM_PROBLEM_CODES = setOf("COMPOSE_EXPORT_REFUSED")
private val TOOLING_PROBLEM_CODES =
  setOf("CATALOG_UNAVAILABLE", "CATALOG_PIN_MISMATCH", "COMPONENT_RECORD_UNAVAILABLE")

private fun problemGroupKey(problem: EditorProblem): String =
  listOf(
      problem.code,
      problem.nodeId.orEmpty(),
      problem.componentId.orEmpty(),
      problem.propertyName.orEmpty(),
      problem.blocking.toString(),
    )
    .joinToString("\u0000")

/** Groups exact locations while keeping every precise diagnostic available in technical details. */
internal fun triageProblems(problems: List<EditorProblem>): List<EditorProblemGroup> {
  val structuralLocations =
    problems
      .filter { it.blocking && it.code !in DOWNSTREAM_PROBLEM_CODES }
      .map { it.nodeId }
      .toSet()
  return problems
    .groupBy(::problemGroupKey)
    .values
    .map { occurrences ->
      val first = occurrences.first()
      val downstream =
        first.blocking &&
          first.code in DOWNSTREAM_PROBLEM_CODES &&
          structuralLocations.any { it == null || first.nodeId == null || it == first.nodeId }
      EditorProblemGroup(
        code = first.code,
        title = problemTitle(first.code),
        problems = occurrences,
        blocking = first.blocking,
        rootCause = first.blocking && !downstream,
        audience =
          if (first.code in TOOLING_PROBLEM_CODES) ProblemAudience.CATALOG_OR_TOOLING
          else ProblemAudience.AUTHOR,
      )
    }
    .sortedWith(
      compareBy<EditorProblemGroup>(
          { !it.blocking },
          { !it.rootCause },
          { it.audience != ProblemAudience.AUTHOR },
          { it.title },
          { it.nodeId.orEmpty() },
        )
        .thenBy { it.code }
    )
}

private fun problemTitle(code: String): String =
  when (code) {
    "SLOT_CARDINALITY" -> "This slot has the wrong number of layers"
    "UNKNOWN_CHILD" -> "A layer is in an unsupported slot"
    "UNKNOWN_COMPONENT" -> "This component is not in the pinned catalog"
    "MISSING_REQUIRED_PROPERTY" -> "A required property is missing"
    "PROPERTY_NOT_DECLARED" -> "This property is no longer in the catalog"
    "COMPOSE_EXPORT_REFUSED" -> "Compose could not generate this part of the design"
    else -> code.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
  }

internal fun problemBadgeCount(problems: List<EditorProblem>): Int {
  val groups = triageProblems(problems)
  return groups.count { it.rootCause }.takeIf { it > 0 } ?: groups.count { !it.blocking }
}

internal fun problemHeading(problems: List<EditorProblem>): String {
  val groups = triageProblems(problems)
  val roots = groups.count { it.rootCause }
  val advisories = groups.count { !it.blocking }
  return when {
    roots > 0 -> "Issues · $roots root cause${if (roots == 1) "" else "s"}"
    advisories > 0 -> "Issues · $advisories ${if (advisories == 1) "advisory" else "advisories"}"
    else -> "Issues"
  }
}

/** Designer-facing triage over the exact diagnostics produced by the export gate. */
@Composable
internal fun ProblemsInspector(
  problems: List<EditorProblem>,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  header: (@Composable () -> Unit)? = null,
) {
  val groups = triageProblems(problems)
  LazyColumn(Modifier.fillMaxWidth()) {
    header?.let { item(key = "header") { it() } }
    if (problems.isEmpty()) {
      item(key = "empty") {
        Text(
          "Nothing is blocking a Compose export of this design.",
          Modifier.padding(top = 16.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      return@LazyColumn
    }
    val rootCount = groups.count { it.rootCause }
    val downstreamCount = groups.count { it.blocking && !it.rootCause }
    val advisoryCount = groups.count { !it.blocking }
    item(key = "summary") {
      Text(
        buildList {
            if (rootCount > 0)
              add("$rootCount blocking root cause${if (rootCount == 1) "" else "s"}")
            if (downstreamCount > 0)
              add("$downstreamCount downstream group${if (downstreamCount == 1) "" else "s"}")
            if (advisoryCount > 0)
              add("$advisoryCount ${if (advisoryCount == 1) "advisory" else "advisories"}")
            if (problems.size != groups.size) add("${problems.size} total occurrences")
          }
          .joinToString(" · "),
        Modifier.padding(bottom = 10.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    itemsIndexed(
      groups,
      key = { _, group -> problemGroupKey(group.problems.first()) },
    ) { _, group ->
      ProblemGroupRow(group, dispatch)
    }
  }
}

@Composable
private fun ProblemGroupRow(
  group: EditorProblemGroup,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val problem = group.problems.first()
  var replacementsOpen by remember(problem.nodeId, problem.propertyName) { mutableStateOf(false) }
  var detailsOpen by
    remember(group.code, problem.nodeId, problem.propertyName) { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
    Text(
      group.title,
      color =
        if (group.blocking) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium,
    )
    Text(
      when (group.audience) {
        ProblemAudience.AUTHOR -> "You can fix this design here."
        ProblemAudience.CATALOG_OR_TOOLING -> "A catalog or tooling owner needs to fix this."
      },
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    val where = listOfNotNull(group.nodeId, group.componentId).joinToString(" · ").ifEmpty { null }
    if (where != null) {
      Text(
        where,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Row(
      Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (problem.nodeId != null) {
        TextButton(
          onClick = {
            dispatch(UiBuilderEditorEvent.SelectNode(problem.nodeId))
            dispatch(UiBuilderEditorEvent.ShowInspector(EditorInspectorMode.Properties))
          },
          modifier = Modifier.semantics { contentDescription = "Go to layer ${problem.nodeId}" },
        ) {
          Text("Go to layer")
        }
      }
      TextButton(onClick = { detailsOpen = !detailsOpen }) {
        Text(
          if (detailsOpen) "Hide details"
          else if (group.problems.size == 1) "Technical details"
          else "${group.problems.size} occurrences"
        )
      }
    }
    if (detailsOpen) {
      SelectionContainer {
        Column {
          Text(group.code, style = MaterialTheme.typography.labelSmall)
          group.problems.forEach { occurrence ->
            Text(occurrence.message, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
    if (problem.nodeId != null && problem.propertyName != null) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
          onClick = {
            dispatch(
              UiBuilderEditorEvent.ResolveUndeclaredProperty(
                problem.nodeId,
                problem.propertyName,
              )
            )
          }
        ) {
          Text("Drop")
        }
        if (problem.replacementProperties.isNotEmpty()) {
          Box {
            TextButton(onClick = { replacementsOpen = true }) { Text("Map to…") }
            TrackEditorOverlay(replacementsOpen)
            DropdownMenu(
              expanded = replacementsOpen,
              onDismissRequest = { replacementsOpen = false },
            ) {
              problem.replacementProperties.forEach { replacement ->
                DropdownMenuItem(
                  text = { Text(replacement) },
                  onClick = {
                    replacementsOpen = false
                    dispatch(
                      UiBuilderEditorEvent.ResolveUndeclaredProperty(
                        problem.nodeId,
                        problem.propertyName,
                        replacement,
                      )
                    )
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * What has been done to this design, newest first, marking the entries undo and redo would act on.
 * Other people's changes sit between them unmarked, so the reader can see what undo will really
 * take back.
 *
 * Read-only on purpose: jumping to a row raises the harder question of what happens to others'
 * later changes.
 */
@Composable
private fun OperationHistoryInspector(
  entries: List<EditorOperationEntry>,
  onSelectNode: (String) -> Unit,
) {
  if (entries.isEmpty()) {
    Text(
      "Nothing has been changed in this session yet.",
      Modifier.padding(top = 16.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }
  Text(
    "Undo and redo act on the marked entries, which are your own changes.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
  // Selectable for the same reason the issues are: a value somebody is comparing against is a value
  // they want to paste somewhere. A tap still selects the node underneath.
  SelectionContainer {
    LazyColumn(Modifier.fillMaxWidth().padding(top = 10.dp)) {
      items(entries, key = EditorOperationEntry::operationId) { entry ->
        OperationHistoryRow(entry, onSelectNode)
      }
    }
  }
}

@Composable
private fun OperationHistoryRow(entry: EditorOperationEntry, onSelectNode: (String) -> Unit) {
  val marked =
    entry.standing == EditorOperationStanding.NextUndo ||
      entry.standing == EditorOperationStanding.NextRedo
  // Undone entries are drawn faded, not removed: they are what redo would restore.
  val faded = entry.standing == EditorOperationStanding.Undone
  Column(
    Modifier.fillMaxWidth()
      .padding(bottom = 4.dp)
      .let { base ->
        if (marked)
          base
            .background(
              MaterialTheme.colorScheme.surfaceVariant,
              RoundedCornerShape(6.dp),
            )
            .padding(8.dp)
        else base.padding(vertical = 4.dp)
      }
      .let { base -> entry.nodeId?.let { id -> base.clickable { onSelectNode(id) } } ?: base }
  ) {
    entry.standing.marker()?.let { marker ->
      Text(
        marker,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Text(
      entry.summary,
      color =
        if (faded) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.bodySmall,
    )
    entry.changes.forEach { change ->
      Text(
        change.readable(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Text(
      listOfNotNull(
          "Revision ${entry.revision}",
          if (entry.mine) "you" else entry.actorId,
          if (faded) "undone" else null,
        )
        .joinToString(" · "),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

/**
 * One change as a line: the value now and what it was. No arrow glyph (the browser build has none);
 * a missing end is said in words rather than a dash.
 */
internal fun EditorOperationChange.readable(): String =
  when {
    after != null && before != null -> "$label  $after  \u00b7  was $before"
    after != null -> "$label  $after"
    before != null -> "$label  was $before"
    else -> label
  }

/** What the two entries the toolbar is aimed at say about themselves, and nothing for the rest. */
private fun EditorOperationStanding.marker(): String? =
  when (this) {
    EditorOperationStanding.NextUndo -> "Undo takes this back"
    EditorOperationStanding.NextRedo -> "Redo puts this back"
    EditorOperationStanding.Applied,
    EditorOperationStanding.Undone -> null
  }

@Composable
private fun ScreenEnvironmentInspector(
  document: UiBuilderDocument,
  devicePresets: List<UiBuilderDevicePreset>,
  /** The sizes the catalog declares, listed ahead of the devices — see [UiBuilderFrameGeometry]. */
  catalogFrameSizes: List<UiBuilderFrameGeometry.FrameSize>,
  /** The design's catalog platform, which decides which device families the pickers open on. */
  platform: UiBuilderCatalogPlatform,
  /** The unstored axes the strip is drawing — see [UiBuilderEditorState.variantAxes]. */
  variantAxes: Set<EditorVariantAxis>,
  /**
   * Whether the surface drawing the variant strip is on screen. False on the host's renderer, where
   * the comparison chips go quiet but the device picker stays live (it still feeds the export).
   */
  variantsDrawn: Boolean,
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

  // "Frame", not "Screen environment": a measuring surface (width, density, theme), which a device
  // is only one way to fill. See
  // [`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../../../../../docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md).
  Text("Frame", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
  Text(
    "What the design is measured in. Applies to the complete render, never an individual component.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
  HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
  // On a board the frame still applies (items are laid out at that width, density and theme); only
  // the heading's claim changes.
  if (document.isBoard) {
    Text(
      "A board of ${document.boardItemCount} items",
      style = MaterialTheme.typography.labelLarge,
    )
    Text(
      "This design holds several top-level items rather than one screen, so its frame is a canvas " +
        "to lay them out in rather than a device it runs on.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
  }
  // The catalog's own sizes lead the frame menu and are all it opens on (`forPlatform`); they never
  // reach the export-device picker below, which writes `@Preview(device = …)` tokens a size is not.
  val framePresets =
    UiBuilderFrameGeometry(sizes = catalogFrameSizes).sizePresets(current.density) + devicePresets
  if (framePresets.isNotEmpty()) {
    // Explains the three device-related controls: the one frame, other devices to view it on, and
    // non-device ways of looking.
    Text(
      "One frame to build in, any number to check against.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.padding(bottom = 8.dp),
    )
    DevicePresetPicker(
      presets = framePresets,
      selected = current.matchingDevicePreset(framePresets),
      platform = platform,
      onPick = { preset ->
        // Width, height and density in one dispatch, so `updateEnvironment` makes them one undoable
        // command.
        val applied = current.withDevicePreset(preset)
        width = applied.widthDp.toString()
        height = applied.heightDp.toString()
        density = applied.density.toString()
        validationError = document.screenEnvironmentValidationError(applied)
        if (validationError == null) dispatch(UiBuilderEditorEvent.UpdateEnvironment(applied))
      },
    )
    ExportDevicePicker(
      presets = devicePresets,
      selected = current.exportDevices,
      platform = platform,
      drawn = variantsDrawn,
      onToggle = { id ->
        // The whole set per edit, matching the protocol change and for its reason: a toggle that
        // sent an add or a remove would let two people's ideas of the set drift apart between them.
        val next =
          if (id in current.exportDevices) current.exportDevices - id
          else current.exportDevices + id
        dispatch(UiBuilderEditorEvent.UpdateEnvironment(current.copy(exportDevices = next)))
      },
    )
  }
  VariantAxisPicker(document, variantAxes, variantsDrawn) {
    dispatch(UiBuilderEditorEvent.ToggleVariantAxis(it))
  }
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
  LocalUiBuilderChrome.current.InspectorChoiceRow(
    "Theme",
    EditorScreenTheme.entries.map { option ->
      UiBuilderInspectorChoiceModel(
        label = option.label,
        contentDescription = "${option.label} theme",
        selected = theme == option,
        onClick = { theme = option },
      )
    },
  )
  LocalUiBuilderChrome.current.InspectorChoiceRow(
    "Layout direction",
    EditorLayoutDirection.entries.map { option ->
      UiBuilderInspectorChoiceModel(
        label = option.label,
        contentDescription = "${option.label} layout direction",
        selected = layoutDirection == option,
        onClick = { layoutDirection = option },
      )
    },
  )
  validationError?.let {
    SelectionContainer {
      Text(
        it,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
  LocalUiBuilderChrome.current.InspectorAction(
    UiBuilderInspectorActionModel(
      label = "Apply screen settings",
      primary = true,
      filled = true,
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      onClick = {
        // `current.withScreenFields`, never a fresh `ScreenEnvironmentSettings`, so fields owned by
        // other writers (e.g. `exportDevices`) are not reset.
        val parsed =
          current.withScreenFields(
            widthDp = width.toIntOrNull() ?: Int.MIN_VALUE,
            heightDp = height.toIntOrNull() ?: Int.MIN_VALUE,
            density = density.toDoubleOrNull() ?: Double.NaN,
            fontScale = fontScale.toDoubleOrNull() ?: Double.NaN,
            locale = locale.trim(),
            theme = theme,
            layoutDirection = layoutDirection,
          )
        validationError = document.screenEnvironmentValidationError(parsed)
        if (validationError == null) dispatch(UiBuilderEditorEvent.UpdateEnvironment(parsed))
      },
    )
  )
  // Clarifies that Apply covers only these fields; the device menus commit as you pick.
  Text(
    "Applies the fields above. The device menus commit as you pick them.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
    modifier = Modifier.padding(top = 4.dp),
  )
  TypefacePicker(
    selected = current.typeface,
    onTextInputFocusChanged = onTextInputFocusChanged,
    onPick = { family ->
      // From `current`, like the device menus: this control owns one field and commits it alone.
      dispatch(UiBuilderEditorEvent.UpdateEnvironment(current.copy(typeface = family)))
    },
  )
}

/**
 * The design's typeface, as a menu whose options are drawn in their own family. Commits on pick.
 * Families come from the host ([LocalUiBuilderFontRegistry]); a family the document names but the
 * host does not ship is still listed, drawn in its face if fetchable by name, else marked.
 */
@Composable
private fun TypefacePicker(
  selected: String?,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onPick: (String?) -> Unit,
) {
  Text(
    "Typeface",
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 16.dp),
  )
  Text(
    "Every text style in the design is drawn in this family. Commits as you pick it.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
  FontFamilyPicker(
    selected = selected,
    contentDescription = "Typeface",
    onTextInputFocusChanged = onTextInputFocusChanged,
    modifier = Modifier.padding(top = 6.dp),
    onPick = onPick,
  )
}

/**
 * The frame menu, grouped by device family, each entry with the render lane's geometry. Backed by
 * every device the catalog knows; only the design's own platform families open by default, the rest
 * one row away.
 */
@Composable
private fun DevicePresetPicker(
  presets: List<UiBuilderDevicePreset>,
  selected: UiBuilderDevicePreset?,
  /** The design's platform, which decides which device families open by default. */
  platform: UiBuilderCatalogPlatform,
  onPick: (UiBuilderDevicePreset) -> Unit,
) {
  var showAll by remember { mutableStateOf(false) }
  var expanded by remember { mutableStateOf(false) }
  // "Set frame from": a pick only writes width, height and density; the design does not become that
  // device, so non-matching frames read "Custom size".
  Text("Set frame from", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
  val shown = if (showAll) presets else presets.forPlatform(platform, listOfNotNull(selected?.id))
  Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)) {
    LocalUiBuilderChrome.current.InspectorAction(
      UiBuilderInspectorActionModel(
        // A hand-typed frame is a legitimate state, not an error — name it rather than showing a
        // device the canvas is not actually at.
        label = selected?.label ?: "Custom size",
        contentDescription = "Device preset",
        primary = true,
        filled = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = true },
      )
    )
    LocalUiBuilderChrome.current.PopupMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      entries =
        buildList {
          // The host serves every renderable family; the design's platform decides what opens, Show
          // all devices reveals the rest, and the frame in use is never hidden.
          shown.groupBy(UiBuilderDevicePreset::group).forEach { (group, devices) ->
            add(UiBuilderMenuEntry.Heading(group))
            devices.forEach { preset ->
              add(
                UiBuilderMenuEntry.Action(
                  label = preset.label,
                  detail = preset.summary,
                  detailStyle = UiBuilderMenuDetailStyle.Body,
                  emphasized = preset.id == selected?.id,
                  onClick = {
                    expanded = false
                    onPick(preset)
                  },
                )
              )
            }
          }
          if (!showAll && presets.size > shown.size) {
            add(UiBuilderMenuEntry.Divider)
            add(
              UiBuilderMenuEntry.Action("Show all devices (${presets.size - shown.size} more)") {
                // Stays open: revealing the rest precedes a choice.
                showAll = true
              }
            )
          }
        },
    )
  }
}

/**
 * The devices a design is exported as, beside its frame device. A multi-select because a screen
 * claims several devices. Ticks report the set; none is valid ("exports at its own frame alone").
 */
/**
 * The unstored axes the variant strip draws, as chips beside the export devices. Unlike those
 * devices (stored, exported), these are editor state that resets on reopen; see
 * [`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../../../../../docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md).
 */
@Composable
private fun VariantAxisPicker(
  document: UiBuilderDocument,
  selected: Set<EditorVariantAxis>,
  drawn: Boolean,
  onToggle: (EditorVariantAxis) -> Unit,
) {
  LocalUiBuilderChrome.current.InspectorToggleRow(
    label = "Also compare",
    supporting =
      if (drawn) null
      else
        "The comparison strip is drawn on the builder's own canvas. This design is being " +
          "previewed on the host's renderer, which draws one frame.",
    choices =
      EditorVariantAxis.entries.map { axis ->
        UiBuilderInspectorChoiceModel(
          label = axis.labelFor(document),
          contentDescription = "Compare ${axis.labelFor(document)}",
          selected = axis in selected,
          enabled = drawn,
          onClick = { onToggle(axis) },
        )
      },
  )
}

@Composable
private fun ExportDevicePicker(
  presets: List<UiBuilderDevicePreset>,
  selected: List<String>,
  /** The design's own platform, which decides which device families open by default. */
  platform: UiBuilderCatalogPlatform,
  /** Whether the strip that draws these devices is on screen — see the sentence below. */
  drawn: Boolean,
  onToggle: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  var showAll by remember { mutableStateOf(false) }
  // The set is both what the export writes as `@Preview(device = …)` and what the workspace draws
  // beside the design. An id with no preset here is exported but not drawn (see `variantPanes`), so
  // it is not counted as shown.
  val undrawable = selected.count { id -> presets.none { it.id == id } }
  // One fixed heading; whether the strip is drawn is said in a sentence below it.
  Text(
    "Also previewed at",
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.Bold,
  )
  Text(
    when {
      !drawn ->
        "Written into the export as @Preview(device = …). Not drawn here: this design is on the " +
          "host's renderer, which draws one frame. Switch to the builder's canvas to see them."
      undrawable > 0 ->
        "Drawn beside the design, and written into the export. $undrawable of them names a device " +
          "this host cannot draw, so it is exported without a pane."
      else -> "Drawn beside the design in the preview pane, and written into the export."
    },
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
  val shown = if (showAll) presets else presets.forPlatform(platform, selected)
  Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)) {
    LocalUiBuilderChrome.current.InspectorAction(
      UiBuilderInspectorActionModel(
        // Naming the devices while there are few enough to read beats a count: "Pixel 6, Pixel
        // Fold" is the answer, where "2 devices" is a prompt to go and look.
        label =
          when {
            selected.isEmpty() -> "This frame only"
            selected.size <= 2 ->
              selected.joinToString(", ") { id -> presets.firstOrNull { it.id == id }?.label ?: id }
            else -> "${selected.size} devices"
          },
        contentDescription = "Export devices",
        primary = true,
        filled = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = true },
      )
    )
    LocalUiBuilderChrome.current.PopupMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      entries =
        buildList {
          // A ticked device is never hidden, because a tick you cannot find to clear is worse than
          // a long menu — and that is the state a design arriving from MCP naming a TV can be in.
          shown.groupBy(UiBuilderDevicePreset::group).forEach { (group, devices) ->
            add(UiBuilderMenuEntry.Heading(group))
            devices.forEach { preset ->
              val checked = preset.id in selected
              add(
                UiBuilderMenuEntry.Action(
                  label = preset.label,
                  detail = preset.summary,
                  detailStyle = UiBuilderMenuDetailStyle.Body,
                  selected = checked,
                  selectionIndicator = UiBuilderMenuSelectionIndicator.Checkbox,
                  emphasized = checked,
                  // The menu stays open: picking a set one item at a time through a menu that
                  // closes after each is the interaction this control exists to avoid.
                  onClick = { onToggle(preset.id) },
                )
              )
            }
          }
          if (!showAll && presets.size > shown.size) {
            add(UiBuilderMenuEntry.Divider)
            add(
              UiBuilderMenuEntry.Action("Show all devices (${presets.size - shown.size} more)") {
                showAll = true
              }
            )
          }
        },
    )
  }
}

/**
 * An enum property as a dropdown whose options preview their meaning (typography in its style, a
 * weight in its weight, alignments as pictures). The current value is ticked and shown the same
 * way.
 */
@Composable
private fun EnumPropertyControl(field: EditorPropertyField, commit: (String) -> Unit) {
  var expanded by remember(field.nodeId, field.name) { mutableStateOf(false) }
  Box(Modifier.fillMaxWidth()) {
    LocalUiBuilderChrome.current.InspectorAction(
      UiBuilderInspectorActionModel(
        label = field.value.ifEmpty { "Choose…" },
        contentDescription = "${field.label} property",
        primary = true,
        filled = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = true },
      )
    )
    TrackEditorOverlay(expanded)
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      field.choices.forEach { choice ->
        val selected = choice == field.value
        DropdownMenuItem(
          text = { EnumOptionLabel(field.name, choice) },
          leadingIcon =
            if (hasEnumGlyph(field.name)) {
              { EnumOptionGlyph(field.name, choice) }
            } else null,
          trailingIcon =
            if (selected) {
              {
                Icon(Icons.Filled.Check, contentDescription = "Current value", Modifier.size(18.dp))
              }
            } else null,
          modifier = Modifier.semantics { this.selected = selected },
          onClick = {
            expanded = false
            commit(choice)
          },
        )
      }
    }
  }
}

/** An option's name, drawn in the style it would give the text when the property is a look. */
@Composable
private fun EnumOptionLabel(property: String, option: String) {
  val style = enumOptionTextStyle(property, option)
  if (style != null) Text(option, style = style, maxLines = 1) else Text(option)
}

/**
 * A colour property: a swatch beside the hex/token [field], then the theme roles and literal
 * colours, each one press to apply. Roles stay roles so the design follows its theme.
 */
@Composable
private fun ColorPropertyControl(
  field: EditorPropertyField,
  commit: (String) -> Unit,
  contrastOf: (ColorScheme) -> ContrastReading?,
  recentColours: List<String>,
  textField: @Composable () -> Unit,
) {
  val scheme = LocalInspectorColorScheme.current ?: MaterialTheme.colorScheme
  // Only for one node with one value: a multi-selection's colours sit over different backgrounds,
  // and a single ratio for them all would be true of at most one.
  val contrast = if (field.nodeCount == 1 && !field.mixed) contrastOf(scheme) else null
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      ColorSwatch(
        swatchColor(field.value, scheme),
        size = 28.dp,
        selected = false,
        modifier = Modifier.semantics { contentDescription = "${field.label} swatch" },
      )
      Spacer(Modifier.width(8.dp))
      Box(Modifier.weight(1f)) { textField() }
    }
    if (contrast != null) ContrastChip(contrast)
    // A role this field does not list (another catalog's, or one only a different property takes)
    // would be refused, so it is not offered; a literal is drawable on any colour property.
    val recent = recentColours.filter {
      (it.startsWith("#") || it in field.choices) && swatchColor(it, scheme) != null
    }
    if (recent.isNotEmpty()) {
      Text(
        "Recent colours",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      RecentColourRow(recent, field, scheme, commit)
    }
    val tokens = field.choices.filter { swatchColor(it, scheme) != null }
    if (tokens.isNotEmpty()) {
      Text(
        "Theme colours",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      ThemeRoleChips(tokens, field, scheme, commit)
    }
    Text(
      "Colours",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SwatchRow(INSPECTOR_PRESET_COLORS, field, scheme, commit)
  }
}

/**
 * A text or icon colour's WCAG contrast against what it is drawn over ("4.8:1 AA"), loud only when
 * it fails; the tooltip names the background and the grade needed.
 */
@Composable
private fun ContrastChip(reading: ContrastReading) {
  val low = reading.grade == WcagGrade.Low
  val colors = MaterialTheme.colorScheme
  val container = if (low) colors.errorContainer else colors.surfaceContainerHighest
  val content = if (low) colors.onErrorContainer else colors.onSurfaceVariant
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
    tooltip = { PlainTooltip { Text(reading.explanation) } },
    state = rememberTooltipState(),
  ) {
    Text(
      reading.label,
      Modifier.background(container, RoundedCornerShape(6.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp)
        .clearAndSetSemantics { contentDescription = reading.explanation },
      color = content,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = if (low) FontWeight.Bold else null,
    )
  }
}

/**
 * The design's theme roles by name with a small swatch: roles are picked for meaning, and many
 * light-theme roles look alike.
 */
@Composable
private fun ThemeRoleChips(
  roles: List<String>,
  field: EditorPropertyField,
  scheme: androidx.compose.material3.ColorScheme,
  commit: (String) -> Unit,
) {
  // Dense: sixteen roles at the 48dp touch floor is a column taller than the rest of the
  // inspector. The chips are still 32dp tall, which is Material's own chip height.
  CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      roles.forEach { role ->
        val selected = role == field.value
        FilterChip(
          selected = selected,
          onClick = { commit(role) },
          label = { Text(role, style = MaterialTheme.typography.labelMedium) },
          leadingIcon = { ColorSwatch(swatchColor(role, scheme), size = 14.dp, selected = false) },
          modifier =
            Modifier.semantics {
              contentDescription = "Use $role for ${field.label.lowercase()}"
              this.selected = selected
            },
        )
      }
    }
  }
}

/**
 * The colours last committed, as swatches like the presets: matching a colour used elsewhere is one
 * press, not retyping a hex. A role has no name on a bare swatch, so each one names its value in a
 * tooltip and to a screen reader; the labels say "recent" so they stay distinct from the preset or
 * role offering the same value further down.
 */
@Composable
private fun RecentColourRow(
  values: List<String>,
  field: EditorPropertyField,
  scheme: androidx.compose.material3.ColorScheme,
  commit: (String) -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    values.forEach { value ->
      val selected = value.equals(field.value, ignoreCase = true)
      TooltipBox(
        positionProvider =
          TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(value) } },
        state = rememberTooltipState(),
      ) {
        ColorSwatch(
          swatchColor(value, scheme),
          size = 22.dp,
          selected = selected,
          modifier =
            Modifier.clickable { commit(value) }
              .semantics {
                contentDescription = "Use recent $value for ${field.label.lowercase()}"
                this.selected = selected
              },
        )
      }
    }
  }
}

@Composable
private fun SwatchRow(
  values: List<String>,
  field: EditorPropertyField,
  scheme: androidx.compose.material3.ColorScheme,
  commit: (String) -> Unit,
) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    values.forEach { value ->
      val selected = value.equals(field.value, ignoreCase = true)
      ColorSwatch(
        swatchColor(value, scheme),
        size = 22.dp,
        selected = selected,
        modifier =
          Modifier.clickable { commit(value) }
            .semantics {
              contentDescription = "Use $value for ${field.label.lowercase()}"
              this.selected = selected
            },
      )
    }
  }
}

/**
 * Properties holding Material icon keys, which get the searchable picker instead of a dropdown
 * (eleven thousand keys): `iconKey` on `m3/icon`/`wear-m3/icon`, and `imageVector` on
 * `remote-m3/remote-icon`.
 */
internal val ICON_KEY_PROPERTIES: Set<String> = setOf("iconKey", "imageVector")

@Composable
internal fun GoogleIconPropertyControl(
  field: EditorPropertyField,
  onTextInputFocusChanged: (Boolean) -> Unit,
  commit: (String) -> Unit,
  initiallyExpanded: Boolean = false,
  initialQuery: String = "",
) {
  var expanded by remember(field.nodeId, field.name) { mutableStateOf(initiallyExpanded) }
  var query by remember(field.nodeId, field.name) { mutableStateOf(initialQuery) }
  val current = googleMaterialIcon(field.value)
  val matchingIcons = remember(query) { searchGoogleMaterialIcons(query, limit = 80) }
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
    current?.let { GoogleIconGlyph(it.key) }
    Text(current?.label ?: "Choose Google icon", Modifier.padding(start = 8.dp))
  }
  TrackEditorOverlay(expanded)
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
          // Report focus so editor chords (gated on `textInputFocused`) do not fire while typing.
          .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
          .padding(10.dp),
      singleLine = true,
      textStyle =
        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    )
    Text(
      if (query.isBlank()) "Search $SelectableGoogleMaterialIconCount icons — showing 80"
      else "${matchingIcons.size}${if (matchingIcons.size == 80) "+" else ""} matches",
      Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
    matchingIcons.forEach { icon ->
      DropdownMenuItem(
        text = { Text(icon.label) },
        leadingIcon = { GoogleIconGlyph(icon.key) },
        onClick = {
          expanded = false
          commit(icon.key)
        },
      )
    }
  }
}

/** A picker icon at 20dp, holding its slot while the browser fetches the vector. */
@Composable
private fun GoogleIconGlyph(key: String) {
  val vector = rememberGoogleMaterialIconVector(key)
  if (vector == null) Box(Modifier.size(20.dp)) else Icon(vector, null, Modifier.size(20.dp))
}

private fun Double.editorNumber(integer: Boolean): String =
  if (integer || this % 1.0 == 0.0) toLong().toString() else toString()

@Composable
private fun EnvironmentTextField(
  label: String,
  value: String,
  modifier: Modifier,
  onFocusChanged: (Boolean) -> Unit,
  onValueChange: (String) -> Unit,
) {
  LocalUiBuilderChrome.current.InspectorValueField(
    UiBuilderInspectorValueFieldModel(
      label = label,
      value = value,
      style = UiBuilderInspectorValueFieldStyle.Screen,
      modifier = modifier,
      onFocusChanged = onFocusChanged,
      onValueChange = onValueChange,
    )
  )
}

/**
 * One string property's literal, or empty. Deliberately a local copy rather than a shared helper on
 * the node type, since a property is not always a literal; used where emptiness is the signal (a
 * Lottie element with a URL and no animation yet).
 */
internal fun UiBuilderNode.propertyText(name: String): String =
  (properties[name] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty()

/**
 * What a selected placement is: the component it places, renamable in place, and the swap to an app
 * catalog component of the same name once one ships.
 */
@Composable
private fun LocalComponentHeader(
  component: EditorLocalComponent,
  nodeId: String,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onPublish: ((String) -> Unit)?,
  onUpdateFromLibrary: ((String) -> Unit)?,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var draft by remember(component.key, component.name) { mutableStateOf(component.name) }
  Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
    Text("Component", style = MaterialTheme.typography.labelMedium)
    OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      singleLine = true,
      label = { Text("Name") },
      supportingText = {
        Text(
          "${component.placements} placed · exported as @Composable fun ${component.name}",
          style = MaterialTheme.typography.bodySmall,
        )
      },
      modifier =
        Modifier.fillMaxWidth()
          .onFocusChanged { focus ->
            onTextInputFocusChanged(focus.isFocused)
            if (!focus.isFocused && draft != component.name)
              dispatch(UiBuilderEditorEvent.RenameLocalComponent(component.key, draft))
          }
          .onPreviewKeyEvent { event ->
            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
              dispatch(UiBuilderEditorEvent.RenameLocalComponent(component.key, draft))
              true
            } else false
          },
    )
    if (component.parameters.isNotEmpty()) {
      Text(
        "Parameters",
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp),
      )
      component.parameters.forEach { parameter ->
        ParameterNameField(component.key, parameter, onTextInputFocusChanged, dispatch)
      }
    }
    val source = component.source
    if (component.newerInLibrary && source != null && onUpdateFromLibrary != null) {
      Text(
        "The project library has a newer ${source.componentId}. Taking it replaces this design's " +
          "copy; every placement keeps its place and its content.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
      TextButton(onClick = { onUpdateFromLibrary(component.key) }) {
        Text("Take the library version")
      }
    }
    if (onPublish != null) {
      Text(
        if (source == null)
          "Only this design has ${component.name}. Publishing puts it in the project library, " +
            "for every design in the project to place."
        else "In the project library as ${source.componentId}.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
      TextButton(onClick = { onPublish(component.key) }) {
        Text(if (source == null) "Publish to project library" else "Publish update to library")
      }
    }
    component.publishedAs?.let { published ->
      Text(
        "${published.displayName} is in the catalog now. Replacing swaps every placement of " +
          "${component.name} for it and drops this design's copy.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
      TextButton(
        onClick = {
          dispatch(UiBuilderEditorEvent.ReplaceLocalComponent(component.key, published.componentId))
        }
      ) {
        Text("Replace with ${published.displayName}")
      }
    }
    Text(
      nodeId,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** One parameter's name, renamed for the body and every placement when the edit is committed. */
@Composable
private fun ParameterNameField(
  componentKey: String,
  parameter: String,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var draft by remember(componentKey, parameter) { mutableStateOf(parameter) }
  fun commit() {
    if (draft != parameter)
      dispatch(UiBuilderEditorEvent.RenameComponentParameter(componentKey, parameter, draft))
  }
  OutlinedTextField(
    value = draft,
    onValueChange = { draft = it },
    singleLine = true,
    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    modifier =
      Modifier.fillMaxWidth()
        .padding(top = 4.dp)
        .semantics { contentDescription = "Parameter $parameter name" }
        .onFocusChanged { focus ->
          onTextInputFocusChanged(focus.isFocused)
          if (!focus.isFocused) commit()
        }
        .onPreviewKeyEvent { event ->
          if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
            commit()
            true
          } else false
        },
  )
}

/** The suggestions section's inputs, gathered so the inspector passes one thing down. */
internal data class SuggestionPanel(
  val rows: List<SuggestionRow>,
  val shownSuggestionId: String?,
  val outcome: DesignSuggestionOutcome?,
  val status: String?,
  val onShow: (String?) -> Unit,
  val onAccept: ((String) -> Unit)?,
  val onReject: ((String) -> Unit)?,
)
