package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.CollaborationState
import ee.schimke.composeai.uibuilder.CommandOutcome
import ee.schimke.composeai.uibuilder.ComponentDriftFinding
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.RejectionCode
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.codegen.validateDocumentForExport
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

enum class EditorPropertyControl {
  Text,
  Boolean,
  Number,
  Enum,
  Color,
  Unsupported,
}

data class EditorNumberBounds(
  val minimum: Double,
  val maximum: Double,
  val step: Double,
  val integer: Boolean,
)

data class EditorPropertyField(
  /** How many selected nodes this field edits — more than one for a multi-selection. */
  val nodeCount: Int = 1,
  /** True when the selected nodes do not agree on a value, so the control shows nothing. */
  val mixed: Boolean = false,
  /** The state variable this property is bound to, if any. */
  val boundVariable: String? = null,
  val nodeId: String,
  val name: String,
  val label: String,
  val required: Boolean,
  /**
   * True when the selection actually carries this property. The inspector opens on these and hides
   * the rest behind a search, matching what the generator writes.
   */
  val written: Boolean = false,
  val control: EditorPropertyControl,
  val value: String,
  val choices: List<String> = emptyList(),
  val numberBounds: EditorNumberBounds? = null,
  val error: String? = null,
  val notes: String? = null,
)

data class EditorPropertyLocation(val nodeId: String, val property: String)

/**
 * One layout modifier the selection can be given or have removed. Only modifiers the catalog
 * declares on the component are offered, so the reducer never refuses a row.
 */
data class EditorModifierToggle(val type: String, val label: String, val applied: Boolean)

/**
 * One editable number inside the selected node's modifier chain;
 * [UiBuilderEditorEvent.SetModifierValue] rewrites the chain around it.
 */
data class EditorModifierField(
  val type: String,
  val field: String,
  val label: String,
  val value: String,
  /** The values this field may take, or empty when it is a number. */
  val choices: List<String> = emptyList(),
  /** Position in the ordered chain; repeated modifier types remain independently editable. */
  val index: Int = 0,
)

enum class EditorComponentKind(val label: String) {
  Scaffold("Scaffolds"),
  Container("Containers"),
  Composable("Composables"),
}

/**
 * One insertable component and its variants. [kind] is what a slot accepts; [group] is the catalog
 * shelf it sits on.
 */
data class EditorCatalogItem(
  val componentId: String,
  val displayName: String,
  val kind: EditorComponentKind,
  /** The catalog family, or [EditorComponentKind.label] for a catalog that declares no groups. */
  val group: String,
  /** In catalog order. The first is what a plain Add inserts, so it is the one marked default. */
  val variants: List<EditorCatalogVariant> = emptyList(),
  /** The pack this component came from, or null for one of the catalog's own. */
  val pack: String? = null,
  /**
   * Whether the Compose export can write this component, or null where unknown. Read from the same
   * component record as [UiBuilderEditorReducer.composeExportCoverage]; false is a warning on the
   * row, not a gate on Add.
   */
  val exportsToCompose: Boolean? = null,
)

/**
 * One variant of a component: a value for the single property its catalog entry nominates (e.g.
 * `m3/card` filled / elevated / outlined).
 */
data class EditorCatalogVariant(
  val componentId: String,
  /** The property this variant sets — [ComponentCapability.variantProperty]. */
  val property: String,
  /** The value it sets, exactly as the catalog declares it. */
  val value: String,
  /** [value] as a person reads it: `filledTonal` → "Filled tonal". */
  val label: String,
  /** Whether this is what the component inserts as when nobody picks a variant. */
  val default: Boolean,
  /** The component's own [EditorCatalogItem.exportsToCompose], so a variant row dims with it. */
  val exportsToCompose: Boolean? = null,
)

/**
 * One line of the insert panel, flattened from the group → component → variant tree so a single
 * `LazyColumn` can scroll it.
 */
sealed interface EditorCatalogRow {
  /** A catalog family — "Actions", "Selection" — and how many components are under it. */
  data class Group(val name: String, val count: Int, val expanded: Boolean) : EditorCatalogRow

  /**
   * A component's row. [pinned] is part of the row's identity: the "Pinned" shelf copy and the
   * shelf copy are the same component twice, and a list keyed by id alone would drop one.
   */
  data class Component(
    val item: EditorCatalogItem,
    val expanded: Boolean,
    val pinned: Boolean = false,
  ) : EditorCatalogRow

  /**
   * One variant under its component row. [componentName] keeps the accessible name unambiguous (a
   * Button's `text` variant vs the Text component).
   */
  data class Variant(
    val variant: EditorCatalogVariant,
    val componentName: String,
    /** Which copy of the component this variant hangs under — see [Component.pinned]. */
    val pinned: Boolean = false,
  ) : EditorCatalogRow
}

data class EditorTreeRow(
  val nodeId: String,
  val componentId: String,
  /** What the node says, when it says anything; otherwise [componentLabel]. */
  val label: String,
  /** The component's display name, always — so a content-named row still shows its type. */
  val componentLabel: String,
  val depth: Int,
  val parent: ParentSlot?,
  /**
   * Whether the row matches the layers filter itself, rather than being shown as context for a
   * matching descendant.
   */
  val matched: Boolean = true,
) {
  /** Whether [label] came from the node's own content rather than from its component. */
  val named: Boolean
    get() = label != componentLabel
}

/**
 * One line of the layers panel. A [Slot] line separates children of different slots of the same
 * parent, which decides whether a drag between them can land.
 */
sealed interface EditorLayerRow {
  /** How far in the line is drawn. A slot line sits at the depth of the children it heads. */
  val indent: Int

  data class Node(val row: EditorTreeRow, override val indent: Int) : EditorLayerRow {
    val nodeId: String
      get() = row.nodeId
  }

  data class Slot(
    val parent: ParentSlot,
    override val indent: Int,
    val childCount: Int,
    /** The slot's declared ceiling, or null where it takes any number. */
    val maxChildren: Int?,
  ) : EditorLayerRow {
    val full: Boolean
      get() = maxChildren?.let { childCount >= it } == true
  }
}

/** Why a move was refused, in the words the panel shows and the code the reducer reports. */
data class EditorMoveRefusal(val code: RejectionCode, val message: String)

/** Which way a slot's children run, as the geometry the renderer measured says they do. */
enum class UiBuilderDropAxis {
  Horizontal,
  Vertical,
}

/**
 * Where a drag hovering at a measured point would land, shared by palette drags and canvas moves.
 *
 * [children] is the target slot's children in spatial order along [axis], not document order, so
 * the marker sits where the renderer drew them.
 */
data class UiBuilderDropPlan(
  val target: ParentSlot,
  /** The child the drop lands after, or null for first in the slot. */
  val afterNodeId: String?,
  /**
   * Zero-based position among the slot's children; for a canvas move, against the layout without
   * the moved node.
   */
  val index: Int,
  /**
   * The slot's landing bounds in root pixels: the container, the child union where several slots
   * share it, or the parent's bounds before anything is measured.
   */
  val bounds: UiBuilderPixelBounds,
  /** The slot's children with their measured bounds, ordered along [axis]. */
  val children: List<Pair<String, UiBuilderPixelBounds>>,
  val axis: UiBuilderDropAxis,
)

/**
 * What [UiBuilderEditorEvent.Tidy] would do. Empty [operations] means everything in scope is
 * already on the grid.
 */
data class UiBuilderTidyPlan(
  val operations: List<DesignOperation>,
  val changedValues: Int,
)

/**
 * An empty recommended slot drawn as a dashed drop target. Editor-only: never in the document or
 * the export.
 */
data class UiBuilderSlotPlaceholder(
  val target: ParentSlot,
  val bounds: UiBuilderPixelBounds,
)

/**
 * One rung of the selection's path from a root. [label] follows the layer row's naming rule;
 * [inSlot] is set only where the parent has more than one slot or the slot is undeclared.
 */
data class UiBuilderBreadcrumbEntry(
  val nodeId: String,
  val label: String,
  val componentId: String,
  val inSlot: String? = null,
)

/**
 * How the editing canvas frames the design. [Extent] unrolls lists to the content's full height;
 * [Device] clips to the frame and pops a selected scrolling container out beside it.
 */
enum class EditorCanvasView {
  Extent,
  Device,
}

enum class EditorMoveDirection {
  Before,
  After,
}

/**
 * Where a keyboard selection step lands. [Next] and [Previous] walk the flattened layer order, not
 * the sibling list.
 */
enum class EditorSelectionMove {
  Next,
  Previous,
  Parent,
  FirstChild,
}

enum class EditorScreenTheme(val wireValue: String, val label: String) {
  Light("light", "Light"),
  Dark("dark", "Dark"),
  System("system", "System"),
}

enum class EditorLayoutDirection(val wireValue: String, val label: String) {
  Ltr("ltr", "Left to right"),
  Rtl("rtl", "Right to left"),
}

data class ScreenEnvironmentSettings(
  val widthDp: Int,
  val heightDp: Int,
  val density: Double,
  val fontScale: Double,
  val locale: String,
  val theme: EditorScreenTheme,
  val layoutDirection: EditorLayoutDirection,
  /**
   * Extra device ids this design is exported as, beside its own frame. Ids rather than geometry,
   * matching `DesignEnvironmentV1.exportDevices`, so a design cannot name a frame no renderer
   * produces.
   */
  val exportDevices: List<String> = emptyList(),
  /**
   * The family every type role renders in, or null for the platform default. Resolved by the host
   * against [ee.schimke.composeai.uibuilder.LocalUiBuilderFontFamilies]; null rather than a
   * sentinel because the protocol spells "no family" as a reset.
   */
  val typeface: String? = null,
)

fun UiBuilderDocument.screenEnvironmentSettings(): ScreenEnvironmentSettings =
  ScreenEnvironmentSettings(
    widthDp = environment["widthDp"]?.primitiveOrNull()?.content?.toIntOrNull() ?: 1280,
    heightDp = environment["heightDp"]?.primitiveOrNull()?.content?.toIntOrNull() ?: 800,
    density = environment["density"]?.primitiveOrNull()?.doubleOrNull ?: 1.0,
    fontScale = environment["fontScale"]?.primitiveOrNull()?.doubleOrNull ?: 1.0,
    locale = environment["locale"]?.primitiveOrNull()?.content.orEmpty().ifBlank { "en-US" },
    theme =
      EditorScreenTheme.entries.firstOrNull {
        it.wireValue == environment["theme"]?.primitiveOrNull()?.content
      } ?: EditorScreenTheme.System,
    layoutDirection =
      EditorLayoutDirection.entries.firstOrNull {
        it.wireValue == environment["layoutDirection"]?.primitiveOrNull()?.content
      } ?: EditorLayoutDirection.Ltr,
    exportDevices =
      (environment["exportDevices"] as? JsonArray)
        ?.mapNotNull { it.primitiveOrNull()?.content }
        .orEmpty(),
    typeface =
      environment["typeface"]?.primitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() },
  )

/** The smallest frame the render lane's device catalog offers (`id:wearos_square`). */
private const val MIN_SCREEN_DP = 180

private const val MAX_SCREEN_DP = 3840

/**
 * A widget's frame is launcher-owned (the small host is 216 × 76dp), so widgets skip the screen
 * floor.
 */
fun UiBuilderDocument.screenEnvironmentValidationError(
  settings: ScreenEnvironmentSettings
): String? =
  settings.validationError(minimumDp = if (wearWidgetScaffoldSize() == null) MIN_SCREEN_DP else 1)

fun ScreenEnvironmentSettings.validationError(minimumDp: Int = MIN_SCREEN_DP): String? =
  when {
    widthDp !in minimumDp..MAX_SCREEN_DP ->
      "Width must be between $minimumDp and $MAX_SCREEN_DP dp."
    heightDp !in minimumDp..MAX_SCREEN_DP ->
      "Height must be between $minimumDp and $MAX_SCREEN_DP dp."
    !density.isFinite() || density !in 0.5..4.0 -> "Density must be between 0.5 and 4.0."
    !fontScale.isFinite() || fontScale !in 0.5..3.0 -> "Font scale must be between 0.5 and 3.0."
    locale.length !in 2..64 || !Regex("[A-Za-z]{2,8}([_-][A-Za-z0-9]{1,8})*").matches(locale) ->
      "Locale must be a BCP 47-style tag such as en-US."
    else -> null
  }

/**
 * Where an Add beside lands, and the operations that must precede it. A null [target] means the
 * document's root.
 */
internal data class BesideDestination(
  val target: ParentSlot?,
  val afterNodeId: String?,
  val prelude: List<DesignOperation>,
)

/**
 * A detached snapshot of subtrees, held inside the editor session (not the system clipboard).
 * Snapshotting rather than referencing ids keeps cut-then-paste and copy-delete-paste working.
 */
data class EditorClipboard(
  /** The copied subtrees' roots, in tree order — a selection can hold more than one. */
  val rootNodeIds: List<String>,
  val nodes: Map<String, UiBuilderNode>,
  /** Where a *cut* took these from; null for a copy, which was never removed from anywhere. */
  val origin: EditorClipboardOrigin? = null,
  /**
   * Declarations of the components copied placements use, so pasting into a design without them
   * brings them along.
   */
  val components: Map<String, JsonObject> = emptyMap(),
) {
  val rootComponentIds: List<String>
    get() = rootNodeIds.map { nodes.getValue(it).componentId }
}

/**
 * Where a cut subtree came from, so pasting it back restores its position instead of appending.
 * [afterNodeId] is null when it was first in the slot.
 */
data class EditorClipboardOrigin(val parent: ParentSlot, val afterNodeId: String?)

data class UiBuilderEditorState(
  val collaboration: CollaborationState,
  /**
   * The selection in the order it was built; the last entry is the anchor a range extends from and
   * the inspector shows.
   */
  val selection: List<String> = emptyList(),
  val clipboard: EditorClipboard? = null,
  /**
   * The pinned catalog's platform, carried so the Screen dock can pick device families without a
   * catalog.
   */
  val platform: UiBuilderCatalogPlatform = UiBuilderCatalogPlatform.DEFAULT,
  val catalogQuery: String = "",
  /**
   * Collapsed insert-panel groups. Tracking what is closed means newly added groups arrive open.
   */
  val collapsedCatalogGroups: Set<String> = emptySet(),
  /** Insert-panel components showing their variants; collapsed by default. */
  val expandedCatalogComponents: Set<String> = emptySet(),
  /**
   * Component packs whose shelves the insert panel shows. Off by default; components of a disabled
   * pack still validate and render.
   */
  val enabledPacks: Set<String> = emptySet(),
  /**
   * Components pinned to the top of the insert panel, or null when the reader has never chosen and
   * the catalog's [CapabilityCatalog.pinnedComponents] apply. Editor state, remembered per catalog
   * by the host.
   */
  val pinnedComponents: Set<String>? = null,
  /**
   * The last component-library drift read for this design's imports; empty means not asked. Kept on
   * the state because [problems] must stay a pure function of the document.
   */
  val componentDrift: List<ComponentDriftFinding> = emptyList(),
  val layerQuery: String = "",
  val operationSequence: Int = 0,
  val lastOutcome: CommandOutcome? = null,
  val selectionBeforeOperations: Map<String, String?> = emptyMap(),
  val selectionAfterOperations: Map<String, String?> = emptyMap(),
  val propertyErrors: Map<EditorPropertyLocation, String> = emptyMap(),
  val inspectorMode: EditorInspectorMode = EditorInspectorMode.Properties,
  /**
   * Whether the generated Kotlin is shown under the canvas. Off by default: it costs a generator
   * run per change.
   */
  val codePaneVisible: Boolean = false,
  /**
   * Whether the selection's quick editor card is open. Closed again whenever the selection moves;
   * see [UiBuilderEditorEvent.ToggleQuickEditor].
   */
  val quickEditorOpen: Boolean = false,
  /**
   * Whether the revision thumbnail strip is drawn. View-only and off by default because each
   * thumbnail costs a rebuilt document.
   */
  val historyBarVisible: Boolean = false,
  /**
   * The older revision being looked at read-only, or null. The document is untouched and the
   * editing surface is not composed while set; undo is how a design moves back.
   */
  val revisionPeek: Int? = null,
  /**
   * The other end of a comparison with [revisionPeek], or null. Ordering is resolved when the diff
   * is computed.
   */
  val revisionCompare: Int? = null,
  /** Which design panes the workspace draws. Never empty; see [EditorPane]. */
  val panes: Set<EditorPane> = setOf(EditorPane.Editor),
  /**
   * Which host container a Wear widget is framed in. Editor state because the launcher, not the
   * widget, owns the frame; it takes no revision and does not reach the export.
   */
  val wearWidgetHostShape: WearWidgetHostShape = WearWidgetHostShape.Default,
  /**
   * The reference picture and how it is drawn. Not part of the design, but durable: the host loads
   * and persists it by watching this field.
   */
  val reference: ReferenceOverlayState = ReferenceOverlayState(),
  /**
   * Whether the next Add starts a top-level item beside the design instead of filling a slot. A
   * tool mode: not stored, shared or undone.
   */
  val addBeside: Boolean = false,
  /**
   * Unstored axes the variant strip draws. Devices are not here: they are the document's
   * `exportDevices`.
   */
  val variantAxes: Set<EditorVariantAxis> = emptySet(),
  /**
   * Whether the canvas draws the design at its whole extent or at its device frame. State rather
   * than view-local because it changes what selection does.
   */
  val canvasView: EditorCanvasView = EditorCanvasView.Extent,
) {
  /** A reference update, which never touches the document and so never becomes a submission. */
  internal fun withReference(reference: ReferenceOverlayState): UiBuilderEditorState =
    copy(reference = reference)

  /**
   * Whether the authoring canvas is on screen. Selection overlay and editing chords are gated on
   * this so they never edit invisibly.
   */
  val editing: Boolean
    get() = EditorPane.Editor in panes

  /**
   * The anchor: the most recently selected node, which every single-selection question asks about.
   */
  val selectedNodeId: String?
    get() = selection.lastOrNull()

  val document: UiBuilderDocument
    get() = collaboration.document

  val canUndo: Boolean
    get() = undoTargetOperationId() != null

  val canRedo: Boolean
    get() = redoTargetUndoId() != null
}

/** What the code pane shows: the exported Kotlin, or the reasons there is none. */
sealed interface EditorGeneratedCode {
  data class Source(val kotlin: String) : EditorGeneratedCode

  data class Refused(val reasons: List<String>) : EditorGeneratedCode
}

/**
 * One of the workspace's independently toggled design panes, drawn in declaration order. At least
 * one is always on.
 *
 * [Editor] and [Preview] are the same Wasm renderer with and without the authoring overlay; only
 * [Native] leaves the browser. What each may conclude is in
 * [`UI_BUILDER_PREVIEW_FIDELITY.md`](../../../../../../docs/design/UI_BUILDER_PREVIEW_FIDELITY.md).
 */
enum class EditorPane(
  /** What the toolbar calls it, joined with the other open panes — so: short. */
  val label: String,
  /** What the menu row calls it, where there is room for the whole name. */
  val title: String,
) {
  /** The authoring canvas: this browser's Compose, editable, and where a node is selected. */
  Editor("Editor", "Visual editor"),

  /**
   * The same renderer without the editing overlay, so wired interactions respond, across every
   * device and axis the design claims.
   */
  Preview("Preview", "Preview"),

  /** The design as the target platform draws it, compiled and played by the host. */
  Native("Native", "Native"),
}

enum class EditorInspectorMode {
  Properties,
  Theme,
  Screen,
  Issues,
  /**
   * The discussion about this design. See [DesignCommentBoard] for why it is not in the document.
   */
  Comments,
  /** What has been done to this design, and which of it undo would take back. */
  History,
}

/**
 * Where one accepted change stands in the history; [NextUndo] and [NextRedo] name what the toolbar
 * buttons will act on.
 */
enum class EditorOperationStanding {
  /** In the document, with newer changes of yours above it. */
  Applied,
  /** In the document, and the next undo is this one. */
  NextUndo,
  /** Taken back, and the next redo puts this one back. */
  NextRedo,
  /** Taken back by an undo that has since been redone past. */
  Undone,
}

/** One value an operation moved. Null [before] / [after] mean added / cleared. */
data class EditorOperationChange(
  val label: String,
  val before: String?,
  val after: String?,
)

/**
 * One accepted change, summarised, with what it did to each value. [mine] matters because undo only
 * walks this editor's commands.
 */
data class EditorOperationEntry(
  val operationId: String,
  val revision: Int,
  /** What happened, in one line: "Set text on Episode title". */
  val summary: String,
  /** The node it happened to, for selecting it — null where the change was the screen's. */
  val nodeId: String?,
  val actorId: String,
  val mine: Boolean,
  val standing: EditorOperationStanding,
  val changes: List<EditorOperationChange>,
)

/**
 * One thing standing between the document and an export, read from [validateDocumentForExport] so
 * the panel can never disagree with the gate that actually refuses.
 */
data class EditorProblem(
  val code: String,
  val message: String,
  val nodeId: String? = null,
  val componentId: String? = null,
  /**
   * Whether this stops an export. Defaults true because everything from the export gate is a
   * refusal; advisories must set false.
   */
  val blocking: Boolean = true,
  /** Catalog property carried by a degraded design, when this is its recovery row. */
  val propertyName: String? = null,
  /** Declared properties the stale value can be explicitly moved to. */
  val replacementProperties: List<String> = emptyList(),
)

data class EditorThemeSettings(
  val primaryColor: String = "#FFD0BCFF",
  val backgroundColor: String = "#FF111318",
  val surfaceColor: String = "#FF1D1F25",
  val contentColor: String = "#FFE3E2E9",
  val typeScale: Float = 1f,
  val cornerRadiusDp: Float = 16f,
)
