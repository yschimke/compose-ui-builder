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
import ee.schimke.composeai.uibuilder.componentDriftProblems
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import kotlinx.serialization.json.JsonArray
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
  /**
   * The state variable this property reads, when it is bound to one rather than holding a value.
   */
  val boundVariable: String? = null,
  val nodeId: String,
  val name: String,
  val label: String,
  val required: Boolean,
  /**
   * True when the selection actually carries this property.
   *
   * The difference between "this component may have a `color`" and "this text has a `color`", which
   * is exactly the difference between the catalog and the exported Kotlin: the generator writes the
   * properties a node holds and nothing else. The inspector opens on these, and offers the rest
   * behind a search, because a panel that lists every declaration a component allows is a panel
   * where the two values somebody actually set are lost among thirty they did not.
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
 * One layout modifier the selection can be given, or have taken away, in one press.
 *
 * The chain is authored as a whole by [UiBuilderEditorEvent.ToggleModifier], so a menu row is a
 * fact about the node — "this is filling its parent" — rather than a form to fill in. Only the
 * modifiers the catalog declares on that component are ever offered: a row the reducer would refuse
 * is a row that lies.
 */
data class EditorModifierToggle(val type: String, val label: String, val applied: Boolean)

/**
 * One editable number inside the selected node's modifier chain.
 *
 * The chain is authored whole, so this is not a second way to write it: the field names a modifier
 * and one of its numbers, and [UiBuilderEditorEvent.SetModifierValue] rewrites the chain around it.
 * Only numbers, because they are the values worth nudging while looking at the design — a shape or
 * a colour is a choice, and choices belong in the panel with room for them.
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
 * One insertable component, and the variants it can arrive as.
 *
 * [kind] survives beside [group] because they answer different questions and both are still asked:
 * the kind is what a slot will *accept* — a Scaffold cannot go in a Row — and the group is which
 * shelf of the catalog the component sits on. The panel groups by the second and the reference
 * inspector's picker still narrows by the first.
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
   * Whether the Compose export can write this component, or null where the panel cannot say.
   *
   * Read off the same component record the code pane and the problems panel judge a design by — see
   * [UiBuilderEditorReducer.composeExportCoverage] — so the palette and the export cannot disagree
   * about which third of the catalog is writable. Before this the only way to learn that
   * `asset/image` renders and does not export was to place one and read the refusal, one component
   * at a time (compose-preview-server#477).
   *
   * False is not "do not insert": the canvas draws every one of these, and the PNG and SVG exports
   * carry them. It is a warning drawn on the row, not a gate on the Add.
   */
  val exportsToCompose: Boolean? = null,
)

/**
 * One variant of a component: a value for the single property its catalog entry nominates.
 *
 * A variant is not a second component. `m3/card` filled, elevated and outlined are one catalog id
 * whose `variant` property selects between three Compose callables, which is exactly the shape the
 * published catalog draws as three cards under one component row — so the builder offers them the
 * same way rather than pretending they are three things to insert.
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
 * One line of the insert panel, flattened out of the group → component → variant tree.
 *
 * Flat because the panel is a `LazyColumn` and a nested tree of them cannot scroll as one list. The
 * nesting survives as [Component.indented] and as the order the rows arrive in, which is what the
 * reducer owns and the panel merely draws.
 */
sealed interface EditorCatalogRow {
  /** A catalog family — "Actions", "Selection" — and how many components are under it. */
  data class Group(val name: String, val count: Int, val expanded: Boolean) : EditorCatalogRow

  /**
   * A component's row.
   *
   * [pinned] is the copy drawn under the "Pinned" shelf, and it is part of the row's identity
   * rather than decoration: the pinned copy and the shelf copy are the same component twice, and a
   * list keyed by component id alone would treat the second as a duplicate and drop it.
   */
  data class Component(
    val item: EditorCatalogItem,
    val expanded: Boolean,
    val pinned: Boolean = false,
  ) : EditorCatalogRow

  /**
   * One variant, under the component row it belongs to.
   *
   * [componentName] rides along because the row draws only the variant's own label — "Filled tonal"
   * — while the *name* a screen reader or a script asks for has to stand on its own: a Button's
   * `text` variant and the Text component would otherwise both answer to "Drag Text".
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
   * Whether the row answers the layers filter itself, rather than being shown to carry a descendant
   * that does. False rows are context: they keep the indentation meaningful and are not what
   * "select all matches" selects.
   */
  val matched: Boolean = true,
) {
  /** Whether [label] came from the node's own content rather than from its component. */
  val named: Boolean
    get() = label != componentLabel
}

/**
 * One line of the layers panel.
 *
 * The panel shows more than the tree: a [Slot] line names where a group of children sits, which is
 * the difference between "these two are siblings" and "these two are in different slots of the same
 * parent" — a difference that decides whether a drag between them can land at all.
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
 * Where a drag hovering at a measured point would land, resolved to a place in the document.
 *
 * Both drags the canvas carries answer the same question — a palette drag asks which slot and which
 * gap between that slot's children the pointer is over, and a canvas move asks the same about the
 * node it picked up — so both are answered by one plan: the slot, the child the drop lands after,
 * and the index that is, plus everything the canvas needs to draw the marker at the seam rather
 * than merely tint the slot.
 *
 * [children] is the target slot's children **in spatial order along [axis]**, not document order:
 * the marker sits where the pointer is against the layout the renderer drew, and a z-order that
 * disagrees with document order is exactly the layout the author sees.
 */
data class UiBuilderDropPlan(
  val target: ParentSlot,
  /** The child the drop lands after, or null for first in the slot. */
  val afterNodeId: String?,
  /**
   * Where the drop lands among the slot's children, zero-based against the current layout — for a
   * canvas move, against the layout **without** the node being moved, which is the list the release
   * is computed against and the one a drop-over-itself no-ops against.
   */
  val index: Int,
  /**
   * The slot's landing bounds in root pixels: the container the slot fills, or the child union
   * where several slots share the container and the unions are what tell them apart — the parent
   * node's bounds where nothing has been measured yet.
   */
  val bounds: UiBuilderPixelBounds,
  /** The slot's children with their measured bounds, ordered along [axis]. */
  val children: List<Pair<String, UiBuilderPixelBounds>>,
  val axis: UiBuilderDropAxis,
)

/**
 * What [UiBuilderEditorEvent.Tidy] would do, before it is done.
 *
 * [operations] is the whole command — every property write and every modifier chain that moves a
 * value onto the grid — and [changedValues] counts the authored numbers that move, which is the
 * sentence the toolbar says when the press lands. Empty operations mean the grid already holds
 * everything in scope, and the press should say that too rather than costing a revision.
 */
data class UiBuilderTidyPlan(
  val operations: List<DesignOperation>,
  val changedValues: Int,
)

/**
 * An empty recommended slot, as the canvas draws it: a dashed region that says "a component goes
 * here" and is a drop target until something is.
 *
 * Editor-only — never in the document, never exported, gone the moment the slot is populated — so
 * the placeholder is a fact about the *editor's* reading of a design rather than about the design.
 */
data class UiBuilderSlotPlaceholder(
  val target: ParentSlot,
  val bounds: UiBuilderPixelBounds,
)

/**
 * One rung of the selection's path from a root to the selected node. [label] follows the layer
 * row's own rule — what the node says when it says anything, its component otherwise — because a
 * breadcrumb that renames a layer the panel has been calling something else all session is a second
 * name to keep in one's head.
 *
 * [inSlot] names the slot of the rung above this one sits in, and only where saying it is
 * information: a parent that declares more than one slot, or a slot the catalog does not declare at
 * all, is exactly the distinction the layers panel draws slot lines for. A parent with a single
 * declared slot leaves it null — "children of the only children slot" says nothing the rung above
 * does not.
 */
data class UiBuilderBreadcrumbEntry(
  val nodeId: String,
  val label: String,
  val componentId: String,
  val inSlot: String? = null,
)

enum class EditorMoveDirection {
  Before,
  After,
}

/**
 * Where a keyboard selection step lands.
 *
 * [Next] and [Previous] walk the **flattened tree order** the layers panel shows, rather than the
 * sibling list, so the selection moves the way the panel reads — down past a container's children
 * instead of jumping over them.
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
   * Device ids this design is also exported as, beside the one frame the fields above describe.
   *
   * A design is authored at one size and lives at several, and until this existed the answer to
   * "which devices does this screen claim to work on?" was whatever each exporter guessed from the
   * catalog it happened to be generating for. The frame stays a single choice — it is the canvas
   * somebody approved — and this is the set beside it.
   *
   * Ids rather than geometry, matching `DesignEnvironmentV1.exportDevices`: an id is what a
   * `@Preview(device = …)` resolves, so a design cannot name a frame no renderer produces. Empty
   * means it exports at its own frame alone, which is what every design written before this said.
   */
  val exportDevices: List<String> = emptyList(),
  /**
   * The family every type role renders in, or null for the platform default.
   *
   * A name, not a font: the host resolves it against what it has loaded
   * ([ee.schimke.composeai.uibuilder.LocalUiBuilderFontFamilies]), and a name nothing resolves
   * still renders — in the default face. Null rather than a "default" sentinel because the protocol
   * spells "no family" as a reset, and a design that never chose one must read the same as one that
   * chose and cleared it.
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

/**
 * The dp floor a screen frame may sit at. 180 rather than a rounder number because that is the
 * smallest frame the render lane's device catalog offers (`id:wearos_square`, 180 × 180) — a floor
 * above it would reject half the Wear presets the Screen inspector lists, for a frame the renderer
 * produces happily.
 */
private const val MIN_SCREEN_DP = 180

private const val MAX_SCREEN_DP = 3840

/**
 * A widget's frame is launcher-owned, not a screen. The published small widget host is only 216 ×
 * 76dp, so applying a screen floor to it makes unrelated environment writes (including the
 * export-device picker) impossible. The runtime's document validation likewise requires only
 * positive dimensions.
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
 * Where an Add beside lands, and the operations that have to precede it.
 *
 * A null [target] means the document's root, which is what an empty design gets: `InsertNode` with
 * no parent is the root list.
 */
internal data class BesideDestination(
  val target: ParentSlot?,
  val afterNodeId: String?,
  val prelude: List<DesignOperation>,
)

/**
 * A detached copy of one subtree, held outside the document.
 *
 * Detached on purpose: the nodes are snapshotted at copy time rather than referenced by id, so a
 * cut works (its source is gone by the time you paste), and so copying, deleting the original and
 * pasting behaves the way every editor has taught people it does. Referencing the source by id
 * would make those two cases silently paste nothing.
 *
 * It is not the system clipboard. Nothing here reaches outside the editor session, so a paste
 * cannot import a subtree from another origin — which also means copy between two designs works
 * only inside one editor.
 */
data class EditorClipboard(
  /** The copied subtrees' roots, in tree order — a selection can hold more than one. */
  val rootNodeIds: List<String>,
  val nodes: Map<String, UiBuilderNode>,
  /** Where a *cut* took these from; null for a copy, which was never removed from anywhere. */
  val origin: EditorClipboardOrigin? = null,
) {
  val rootComponentIds: List<String>
    get() = rootNodeIds.map { nodes.getValue(it).componentId }
}

/**
 * The place a cut subtree came out of.
 *
 * Cut-then-paste is how a node is moved, and a move that lands somewhere else is not a move. Cut
 * selects the parent, and a paste into a parent goes to the end of its slot — so cutting a card
 * from the middle of a list and pasting it straight back sent it to the bottom.
 *
 * [afterNodeId] is the sibling the subtree sat behind, or null when it was first in the slot —
 * which is the one position "paste at the end" can never reach, and so the reason this records a
 * position rather than a neighbour to select.
 */
data class EditorClipboardOrigin(val parent: ParentSlot, val afterNodeId: String?)

data class UiBuilderEditorState(
  val collaboration: CollaborationState,
  /**
   * The selection, in the order it was built up.
   *
   * A list rather than a set because the **last entry is the anchor** — the node a range extends
   * from, and the one whose properties the inspector shows. Order is what makes shift-click mean
   * "from there to here" rather than "from some member of a set to here".
   */
  val selection: List<String> = emptyList(),
  val clipboard: EditorClipboard? = null,
  /**
   * What the pinned catalog authors for, carried so the inspector can ask without holding a
   * catalog.
   *
   * The Screen dock needs it to decide which device families to open its menus on — a mobile design
   * has no use for a watch frame — and the dock is three composables below anything that has a
   * `CapabilityCatalog`. One field on the state beats threading a parameter through all three.
   */
  val platform: UiBuilderCatalogPlatform = UiBuilderCatalogPlatform.DEFAULT,
  val catalogQuery: String = "",
  /**
   * The insert panel's collapsed groups, by name.
   *
   * Collapsed rather than expanded, so the panel's default is every group open — which is the list
   * this panel has always shown, now with headings you can shut. A set of what is *closed* also
   * means a group added to the catalog arrives open rather than hidden behind a twisty nobody knows
   * to press.
   */
  val collapsedCatalogGroups: Set<String> = emptySet(),
  /**
   * The insert panel's expanded components, by id — the ones showing their variants.
   *
   * The opposite default to [collapsedCatalogGroups], and deliberately: a group is a heading over
   * rows you came to read, while a component's variants are a detail you go looking for. Opening
   * all of them at once is the wall of rows the grouping exists to prevent.
   */
  val expandedCatalogComponents: Set<String> = emptySet(),
  /**
   * The component packs whose shelves the insert panel shows, by pack id.
   *
   * Off by default, and a set of what is *on*: a pack is another catalog's components offered
   * beside this one's, and a Material 3 screen that opens with a conference app's forty composables
   * on its palette is a palette nobody asked for. Switching one on is a settings decision the host
   * remembers per catalog; the components of a pack that is off are still in the catalog, so a
   * design that already holds one keeps validating and rendering it.
   */
  val enabledPacks: Set<String> = emptySet(),
  /**
   * The components the reader has pinned to the top of the insert panel, or null while they have
   * never said — in which case the catalog's own [CapabilityCatalog.pinnedComponents] answer.
   *
   * Null rather than an empty set because the two mean different things: "I have not chosen" is how
   * a catalog's defaults reach a new reader and keep reaching them when the catalog changes its
   * mind, while "I chose none" is a choice. The first star materialises the defaults and flips one,
   * which is what makes unstarring a catalog default possible at all.
   *
   * Editor state rather than document state, like [enabledPacks]: the same design opened by a
   * collaborator shows their palette, not yours. The host remembers it per catalog.
   */
  val pinnedComponents: Set<String>? = null,
  /**
   * What a read of the project's component library last said about the components this design
   * imported, or empty when nobody has asked.
   *
   * Held on the state rather than computed with the rest of the Issues panel because it is not a
   * fact about the document: answering it means reading another host, and [problems] is a pure
   * function of the document precisely so the panel can cache it against the document alone. So the
   * host fetches, dispatches [UiBuilderEditorEvent.SetComponentDrift], and the panel appends
   * [componentDriftProblems] of this to what the document itself says.
   *
   * Empty is "not asked", not "nothing drifted" — the panel shows neither, which is the same row
   * count either way and the reason the distinction costs nothing here.
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
   * Whether the Kotlin the export would write is shown under the canvas.
   *
   * Off by default: it costs a generator run per document change, and a designer who never opens it
   * should not pay for one. On, it is the answer to "what did that edit do to the code", which is
   * the question the canvas alone cannot answer.
   */
  val codePaneVisible: Boolean = false,
  /**
   * Whether the strip of revision thumbnails is drawn under the canvas.
   *
   * Off by default and view-only, like [codePaneVisible]: it costs a rebuilt document and a
   * composed picture per revision, which a session nobody is reviewing should not pay for. It
   * travels to nobody and takes no revision — see [revisionPeek] for why looking at an old revision
   * is not the same as being at one.
   */
  val historyBarVisible: Boolean = false,
  /**
   * The older revision being *looked at*, or null while the canvas is showing the design as it is.
   *
   * Looking is not going back. The document is untouched, nothing is submitted, and the editing
   * surface is not composed at all while this is set — a peek draws the rebuilt document read-only,
   * because a canvas that accepted a drop at revision 12 of a design that is at revision 40 would
   * be editing a picture. Undo is how a design *moves* back, and it has guards this does not need.
   */
  val revisionPeek: Int? = null,
  /**
   * The other end of a comparison, or null when one revision is being looked at on its own.
   *
   * Always paired with [revisionPeek]: two revisions is a diff, one is a peek, and neither is the
   * canvas. Which of the two is older is worked out when the diff is computed rather than by the
   * order they were picked, so comparing forwards and backwards give the same answer.
   */
  val revisionCompare: Int? = null,
  /**
   * Which design panes the workspace draws, each switched on or off on its own.
   *
   * [EditorPane.Editor] alone unless a host or a catalog asks for more: the authoring canvas is the
   * one pane that always exists and the one this tool is for. Never empty — see [EditorPane].
   */
  val panes: Set<EditorPane> = setOf(EditorPane.Editor),
  /**
   * Which host container a Wear widget design is framed in, on the canvas and in the native render.
   *
   * Editor state rather than document state, for the reason the reference picture below is: the
   * frame belongs to the **host**, not to the widget — the launcher draws it from
   * `WearWidgetParams`, and the same `WearWidgetDocument` appears inside every shape the platform
   * ships. So switching it takes no revision, submits no operation and reaches no export; a design
   * saved while the rectangular frame is showing reopens exactly as it was, and the generated file
   * previews every shape regardless of which one was last viewed.
   *
   * Ignored by every design whose root is not a widget container, which is why nothing gates on it
   * except the control that offers it.
   */
  val wearWidgetHostShape: WearWidgetHostShape = WearWidgetHostShape.Default,
  /**
   * The reference picture attached to this design, and how it is being drawn.
   *
   * Editor state rather than document state: it is scaffolding for the person doing the work, not
   * part of the design, so it takes no revision, submits no operation and reaches no export. It is
   * still durable — the host loads it when the design opens and persists it when it changes, by
   * watching this field — which is why it lives here and not in a composable's `remember`.
   */
  val reference: ReferenceOverlayState = ReferenceOverlayState(),
  /**
   * Whether the next Add starts a top-level item beside the design instead of filling a slot.
   *
   * A tool mode, not a property of the design: nothing about it is stored, shared with a
   * collaborator or undone, and reopening the design opens it off. What it *produces* — a board
   * node holding the items — is in the document for everyone to see, which is the whole reason the
   * mode itself does not have to be
   * ([`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../../../../../docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md)).
   */
  val addBeside: Boolean = false,
  /**
   * The unstored axes the variant strip draws the design on, beside its own frame.
   *
   * Devices are not here: they are `exportDevices` in the document, because the export already
   * writes them as `@Preview(device = …)` and the strip must show the set that ships. These three
   * have no stored home — `DesignEnvironmentV1` is closed to this repository — and rather than
   * smuggle them through a field that means something else they are what they honestly are, a way
   * of looking.
   */
  val variantAxes: Set<EditorVariantAxis> = emptySet(),
) {
  /** A reference update, which never touches the document and so never becomes a submission. */
  internal fun withReference(reference: ReferenceOverlayState): UiBuilderEditorState =
    copy(reference = reference)

  /**
   * Whether the authoring canvas is on screen at all.
   *
   * The selection overlay, the hover editor and every editing chord are gated on this rather than
   * on a mode flag of their own: with the editor pane switched off there is nothing on screen to
   * show what a Delete or an arrow just did, so those chords would edit invisibly and surprise
   * later. It is the same rule the old Design/Preview switch enforced, asked of the pane that
   * actually draws the overlay.
   */
  val editing: Boolean
    get() = EditorPane.Editor in panes

  /**
   * The anchor: the most recently selected node.
   *
   * Every single-selection question in the editor — which node the inspector edits, where an insert
   * lands — is asked of this rather than of the whole selection, so widening selection did not have
   * to touch those call sites.
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

/**
 * What the code pane has to show: the export's Kotlin, or the reasons there is none.
 *
 * Two cases rather than a nullable string, because "no code" is never nothing to say — the refusals
 * are the actionable half, and a blank pane would hide them behind the problems tab.
 */
sealed interface EditorGeneratedCode {
  data class Source(val kotlin: String) : EditorGeneratedCode

  data class Refused(val reasons: List<String>) : EditorGeneratedCode
}

/**
 * One of the workspace's design panes, each switched on or off on its own.
 *
 * These used to be a ladder — one, two or three panes, chosen as a single value in a fixed order —
 * and a ladder cannot say "the preview on its own". It also put the *compiled* surface on the
 * second rung, so asking for a second look at a design cost a host round trip whether or not the
 * target platform was the question anyone had. These are three independent answers to "what am I
 * looking at", drawn left to right in declaration order.
 *
 * Only [Native] leaves this browser. [Editor] and [Preview] are the same Wasm renderer with and
 * without the authoring overlay on top, which is why switching [Preview] on is free and instant.
 *
 * At least one is always on. A workspace with no panes is a blank window, so the reducer refuses to
 * switch off the last one standing and [WorkspacePanesMenu] draws that row disabled rather than
 * letting somebody find out by pressing it.
 *
 * The three are a **fidelity ladder** — mock components, real components, real platform — and which
 * rung you are on decides what you may conclude from what you see. Which of them is allowed to lie
 * about what, and what a disagreement between two of them means, is
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
   * The same renderer with the editor taken off it, across the design's devices and configurations.
   *
   * Not a second opinion about fidelity — it is the same pixels the canvas draws — but about
   * *interaction*: no selection overlay swallowing taps, so a screen wired to react can be made to
   * react by the person who wired it, and every device and axis the design claims side by side.
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
 * Where one accepted change stands in the history right now.
 *
 * Undo and redo each act on one particular change, and until the history was drawn nothing said
 * which. [NextUndo] and [NextRedo] are those two, and they are the reason this exists: the toolbar
 * has a button that takes something back without saying what.
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

/**
 * One value an operation moved, as the two ends of the move.
 *
 * [before] and [after] are null for the absences at either end — a property that did not exist
 * before, and one the change removed — so "added" and "cleared" are readable off the pair rather
 * than needing a kind of their own.
 */
data class EditorOperationChange(
  val label: String,
  val before: String?,
  val after: String?,
)

/**
 * One accepted change to the design, said in words, with what it did to each value.
 *
 * Built from what the collaboration state already keeps: every accepted command carries its own
 * operations and the before/after of every property, modifier chain, environment field and
 * structural move it made. Nothing new is recorded to draw this — the history was always there,
 * with nothing looking at it.
 *
 * [mine] is the distinction the panel is for. Undo walks *this editor's* commands, so a
 * collaborator's change sits in the list, is not undoable by you, and is very often the reason the
 * design does not look like what your own last change left behind.
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
 * One thing standing between the current document and an export.
 *
 * Read straight out of [validateDocumentForExport], which is the fail-closed gate the code and SVG
 * projections already run. Surfacing that function rather than a second set of rules is the whole
 * point: a panel with its own opinion would eventually disagree with the thing that actually
 * refuses, and the disagreement would be discovered at export time — which is exactly the moment
 * this exists to avoid.
 */
data class EditorProblem(
  val code: String,
  val message: String,
  val nodeId: String? = null,
  val componentId: String? = null,
  /**
   * Whether this actually stops an export, as against something the panel reports and the export
   * runs through anyway.
   *
   * Defaulted true because everything read out of the export gate is a refusal by construction. The
   * panel's copy — "what the Compose export gate refuses", and an empty list reading "nothing is
   * blocking a Compose export" — is a claim about every row it shows, so an advisory listed among
   * them makes that claim untrue and tells somebody their export will fail when it will not.
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
