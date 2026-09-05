package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.capability.PropertyEditorControl
import ee.schimke.composeai.uibuilder.capability.SlotCapability
import ee.schimke.composeai.uibuilder.client.toProtocolDocument
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
  val control: EditorPropertyControl,
  val value: String,
  val choices: List<String> = emptyList(),
  val numberBounds: EditorNumberBounds? = null,
  val error: String? = null,
  val notes: String? = null,
)

data class EditorPropertyLocation(val nodeId: String, val property: String)

enum class EditorComponentKind(val label: String) {
  Scaffold("Scaffolds"),
  Container("Containers"),
  Composable("Composables"),
}

data class EditorCatalogItem(
  val componentId: String,
  val displayName: String,
  val kind: EditorComponentKind,
)

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
  )

/**
 * The dp floor a screen frame may sit at. 180 rather than a rounder number because that is the
 * smallest frame the render lane's device catalog offers (`id:wearos_square`, 180 × 180) — a floor
 * above it would reject half the Wear presets the Screen inspector lists, for a frame the renderer
 * produces happily.
 */
private const val MIN_SCREEN_DP = 180

private const val MAX_SCREEN_DP = 3840

fun ScreenEnvironmentSettings.validationError(): String? =
  when {
    widthDp !in MIN_SCREEN_DP..MAX_SCREEN_DP ->
      "Width must be between $MIN_SCREEN_DP and $MAX_SCREEN_DP dp."
    heightDp !in MIN_SCREEN_DP..MAX_SCREEN_DP ->
      "Height must be between $MIN_SCREEN_DP and $MAX_SCREEN_DP dp."
    !density.isFinite() || density !in 0.5..4.0 -> "Density must be between 0.5 and 4.0."
    !fontScale.isFinite() || fontScale !in 0.5..3.0 -> "Font scale must be between 0.5 and 3.0."
    locale.length !in 2..64 || !Regex("[A-Za-z]{2,8}([_-][A-Za-z0-9]{1,8})*").matches(locale) ->
      "Locale must be a BCP 47-style tag such as en-US."
    else -> null
  }

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
  val catalogQuery: String = "",
  val layerQuery: String = "",
  /**
   * Whether taps on the canvas drive the screen instead of selecting layers.
   *
   * The renderer has always been interactive — a click binding writes state and the composition
   * reacts — but in the editor a full-size selection overlay sits on top of it and swallows every
   * tap. So a screen wired to react could not be made to react by the person who wired it.
   */
  val previewMode: Boolean = false,
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
   * Which renderer draws the design. [EditorPreviewSurface.Wasm] unless a host offers another — the
   * editor's own canvas is the one that always exists.
   */
  val previewSurface: EditorPreviewSurface = EditorPreviewSurface.Wasm,
  /**
   * The reference picture attached to this design, and how it is being drawn.
   *
   * Editor state rather than document state: it is scaffolding for the person doing the work, not
   * part of the design, so it takes no revision, submits no operation and reaches no export. It is
   * still durable — the host loads it when the design opens and persists it when it changes, by
   * watching this field — which is why it lives here and not in a composable's `remember`.
   */
  val reference: ReferenceOverlayState = ReferenceOverlayState(),
) {
  /** A reference update, which never touches the document and so never becomes a submission. */
  internal fun withReference(reference: ReferenceOverlayState): UiBuilderEditorState =
    copy(reference = reference)

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
 * Which renderer draws the design.
 *
 * Not a toggle between "canvas" and "extra pane", because the two are alternatives rather than a
 * base and an addition: a Compose Multiplatform project that targets Wasm is best previewed in the
 * browser, and a project that targets only Android or desktop has no browser renderer to fall back
 * on — the host's is the *only* one. [Both] is the deliberate third case, for the times a Wasm
 * project wants to see what the same design looks like off the browser.
 */
enum class EditorPreviewSurface {
  /** The editor's own Compose/Wasm canvas. Immediate, and costs the host nothing. */
  Wasm,

  /** The host compiles the design and renders it with real Compose. */
  Native,

  /** Both at once, for comparing them. */
  Both,
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
}

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
)

data class EditorThemeSettings(
  val primaryColor: String = "#FFD0BCFF",
  val backgroundColor: String = "#FF111318",
  val surfaceColor: String = "#FF1D1F25",
  val contentColor: String = "#FFE3E2E9",
  val typeScale: Float = 1f,
  val cornerRadiusDp: Float = 16f,
)

sealed interface UiBuilderEditorEvent {
  data class SearchCatalog(val query: String) : UiBuilderEditorEvent

  data class SelectNode(val nodeId: String) : UiBuilderEditorEvent

  /** Narrows the layers panel. Purely a view over the document; it writes nothing. */
  data class SearchLayers(val query: String) : UiBuilderEditorEvent

  /**
   * Hands the canvas to the screen and back.
   *
   * The only editor event that stays live while previewing. Everything else is suppressed, because
   * the chords that select and delete would otherwise still be editing a document nobody can see
   * themselves editing.
   */
  data object TogglePreview : UiBuilderEditorEvent

  /** Shows or hides the generated-Kotlin pane under the canvas. */
  data object ToggleCodePane : UiBuilderEditorEvent

  /** Chooses which renderer draws the design. */
  data class ShowPreviewSurface(val surface: EditorPreviewSurface) : UiBuilderEditorEvent

  /**
   * Selects every row the layers filter matched.
   *
   * The multi-node property editor is only as reachable as the selection is: restyling every text
   * on a screen meant finding each of them by eye in a hundred-row tree. Filter, then take all of
   * them in one press.
   */
  data object SelectAllMatches : UiBuilderEditorEvent

  /** Add or remove one node, leaving the rest of the selection alone (ctrl/⌘-click). */
  data class ToggleNode(val nodeId: String) : UiBuilderEditorEvent

  /** Select everything between the anchor and [nodeId] in tree order (shift-click). */
  data class ExtendSelectionTo(val nodeId: String) : UiBuilderEditorEvent

  data class InsertComponent(val componentId: String, val target: ParentSlot) : UiBuilderEditorEvent

  data class MoveNode(
    val nodeId: String,
    val targetNodeId: String,
    val placeAfterTarget: Boolean,
  ) : UiBuilderEditorEvent

  /**
   * Move a node to a named place — the slot it lands in, and the child it lands after.
   *
   * [MoveNode] is a step within the slot a node already sits in, which is all the keyboard needs
   * and all a one-step drag could mean. It cannot answer the layers panel's drag, where the row
   * released under the pointer is often in a different slot, or in a different parent entirely —
   * and where a screen whose every node is an only child in its slot has no in-slot step to take,
   * so every drag was a no-op.
   *
   * A null [afterNodeId] means first in the slot.
   */
  data class MoveNodeInto(
    val nodeId: String,
    val parent: ParentSlot,
    val afterNodeId: String? = null,
  ) : UiBuilderEditorEvent

  data class CommitProperty(val nodeId: String, val property: String, val draft: String) :
    UiBuilderEditorEvent

  /** Make a property read a state variable instead of holding a literal. */
  data class BindPropertyToState(
    val nodeId: String,
    val property: String,
    val variable: String,
    /**
     * When set, bind as a comparison rather than a bare read.
     *
     * The catalog decides which shape a property accepts: a bare read yields the variable's value,
     * so it suits a property typed like the variable, while `stateEquals` yields a boolean, which
     * is what a `selected` or `visible` flag needs. Binding a boolean property to a bare read of a
     * string variable is refused, correctly, by the catalog.
     */
    val equalsValue: String? = null,
  ) : UiBuilderEditorEvent

  /** Give a bound property a literal of its own again. */
  data class UnbindProperty(val nodeId: String, val property: String) : UiBuilderEditorEvent

  data class UpdateEnvironment(val settings: ScreenEnvironmentSettings) : UiBuilderEditorEvent

  data class ShowInspector(val mode: EditorInspectorMode) : UiBuilderEditorEvent

  data class ApplyTheme(val settings: EditorThemeSettings) : UiBuilderEditorEvent

  data object DeleteSelected : UiBuilderEditorEvent

  data object DuplicateSelected : UiBuilderEditorEvent

  /** Move the selection without touching the document. */
  data class SelectRelative(val move: EditorSelectionMove) : UiBuilderEditorEvent

  /** Reorder the selected node among its siblings. */
  data class MoveSelected(val direction: EditorMoveDirection) : UiBuilderEditorEvent

  /** Put the selection inside a new container of [componentId], where it already sits. */
  data class WrapSelection(val componentId: String) : UiBuilderEditorEvent

  /** Lift the selected container's children out and delete it. */
  data object UnwrapSelection : UiBuilderEditorEvent

  /**
   * Insert a component already wired to write state when it is clicked.
   *
   * Insertion is the only moment a client can put an event binding on a node: the wire's mutation
   * set reaches properties and never `eventBindings`, while `InsertNode` carries a whole node. The
   * same shape of limit as declaring state at creation, and the same fix — `setEventBinding` on the
   * wire — after which a handler can be added to a node that already exists.
   */
  data class InsertComponentWithAction(
    val componentId: String,
    val target: ParentSlot,
    val action: EditorStateAction,
  ) : UiBuilderEditorEvent

  /**
   * Insert a `remote-compose/document` already holding one of the catalog's published documents.
   *
   * Separate from [InsertComponent] for the same reason as [InsertComponentWithAction]: the wire's
   * mutation set reaches properties one at a time, but the bytes and the node have to arrive
   * together — a `remote-compose/document` with no `documentBase64` renders as its own error
   * diagnostic, and collaborators would see that intermediate state on the canvas.
   *
   * [documentBase64] is resolved by the host, not by the reducer: the bytes come over the network
   * and this reducer is pure. The reducer's job is to refuse what will not decode.
   */
  data class InsertRemoteComposeDocument(
    val source: RemoteComposeSource,
    val documentBase64: String,
    val target: ParentSlot,
  ) : UiBuilderEditorEvent

  data object CopySelected : UiBuilderEditorEvent

  data object CutSelected : UiBuilderEditorEvent

  /** Paste the clipboard into the selected node's first accepting slot, or beside it. */
  data object Paste : UiBuilderEditorEvent

  data object Undo : UiBuilderEditorEvent

  data object Redo : UiBuilderEditorEvent

  /**
   * Attach an imported picture as the base reference, replacing whatever was there.
   *
   * Only the image crosses; the layout boxes are read out of it here, so that every path into the
   * editor — a paste, a file, a snapshot, a design reopened from the server — gets the same boxes
   * from the same reader rather than depending on the host to have run one.
   */
  data class AttachReference(val image: ReferenceImage) : UiBuilderEditorEvent

  /** Detach everything: the picture, its alignment, every placed piece and every mark. */
  data object ClearReference : UiBuilderEditorEvent

  /** Re-aim the overlay: mode, opacity, nudge, scale, split. */
  data class UpdateReferenceSettings(val settings: ReferenceOverlaySettings) : UiBuilderEditorEvent

  /**
   * Show the overlay again in the mode it was last drawn in, or hide it.
   *
   * A toggle rather than a mode picker because that is the gesture the work actually needs: an
   * overlay is put up, looked through, and taken down again several times per adjustment, and
   * re-choosing the mode each time is the friction that makes people leave it off.
   */
  data object ToggleReference : UiBuilderEditorEvent

  /**
   * Take the pointer for a markup tool, or hand it back to selection.
   *
   * Explicit, never inferred. An editor where a drag sometimes moves a node and sometimes draws on
   * it is one nobody trusts, so the canvas only stops selecting when the operator says so.
   */
  data class SelectReferenceTool(val tool: ReferenceTool) : UiBuilderEditorEvent

  /** The colour the next mark is drawn in. Existing marks keep the colour they were drawn in. */
  data class SelectMarkupColor(val colorArgb: Long) : UiBuilderEditorEvent

  /** The words the next text mark or image placeholder will carry. */
  data class SetMarkupText(val text: String) : UiBuilderEditorEvent

  /** One finished stroke, from the canvas. The id and the colour are assigned here. */
  data class AddReferenceMark(val kind: ReferenceMarkupKind, val points: List<Float>) :
    UiBuilderEditorEvent

  /** Rub out one mark. Every mark is individually removable; that is what makes markup usable. */
  data class RemoveReferenceMark(val markId: String) : UiBuilderEditorEvent

  /** Rub out the most recent mark — the gesture that follows a stroke that went wrong. */
  data object UndoReferenceMark : UiBuilderEditorEvent

  data object ClearReferenceMarkup : UiBuilderEditorEvent

  /**
   * Drop a picture onto the frame as a positioned piece rather than as the base.
   *
   * This is "copy a component out of Figma and put it where it should go": the base reference asks
   * whether the whole screen is right, and a piece asks whether *this* belongs *there*, which is a
   * question nothing stretched across the frame can pose.
   */
  data class PlaceReferencePiece(
    val image: ReferenceImage,
    /**
     * The catalog component this picture is *of*, when it is a picture of one.
     *
     * Set by the capture path and null for an imported or pasted file. It is what makes the piece
     * promotable later, and the reason a captured piece is not simply another image.
     */
    val componentId: String? = null,
  ) : UiBuilderEditorEvent

  data class SelectReferencePiece(val pieceId: String) : UiBuilderEditorEvent

  /** Drag, in fractions of the frame. Comes from the canvas, one pointer sample at a time. */
  data class MoveReferencePiece(val pieceId: String, val dx: Float, val dy: Float) :
    UiBuilderEditorEvent

  /** Resize about the piece's own centre, so resizing does not also move it. */
  data class ScaleReferencePiece(val pieceId: String, val factor: Float) : UiBuilderEditorEvent

  data class RemoveReferencePiece(val pieceId: String) : UiBuilderEditorEvent

  /**
   * Turn a placed piece into the component it is a picture of.
   *
   * The one crossing from the reference half back into the design, and the reason a captured piece
   * records what it was a picture of. It needs no agent and makes no guess: the piece names a
   * catalog component, [target] is the slot the caller hit-tested under it, and the result is the
   * same insertion a drag from the catalog performs — after which the picture is removed, because
   * the real thing is now standing where it was.
   *
   * A piece with no provenance — a screenshot region, a Figma export — is refused here rather than
   * approximated. Deciding which component *that* is, is a judgement, and this reducer does not
   * make judgements.
   */
  data class PromoteReferencePiece(val pieceId: String, val target: ParentSlot) :
    UiBuilderEditorEvent

  /**
   * Replace the whole stack with a single picture of it — the annotated composite becomes the new
   * base, and the pieces and marks that made it are gone.
   *
   * The picture arrives already composed, because composing it needs a bitmap, a frame size and a
   * PNG encoder, none of which belong in a pure reducer.
   */
  data class FlattenReference(val image: ReferenceImage) : UiBuilderEditorEvent
}

/**
 * A state write a click can perform, narrowed to what the renderer executes.
 *
 * The protocol declares `increment` and `navigatePage` as well; this renderer writes nothing for
 * them, so offering them would author a control that does nothing when pressed. Narrower than the
 * wire on purpose — a builder should not be able to draw a dead button.
 */
sealed interface EditorStateAction {
  val variable: String

  /** Flip a flag. */
  data class Toggle(override val variable: String) : EditorStateAction

  /** Write a value. */
  data class Set(override val variable: String, val value: String) : EditorStateAction

  /** Write a value, or clear it when it is already selected. */
  data class SelectOrClear(override val variable: String, val value: String) : EditorStateAction
}

/**
 * The wire form of one action, with its value typed against the variable it writes.
 *
 * The renderer keeps preview state as strings and compares them as strings, so a quoted `"true"`
 * works there and hid this: the Compose exporter declares each variable from its `initialValue`, so
 * a flag is a real Kotlin `Boolean` and assigning it a quoted string does not compile. The action
 * carries the declaration's own kind rather than whatever the editor typed.
 */
/**
 * Why an action's value cannot be written to its variable, or null when it can.
 *
 * The encoder types a value against the declaration, and its fallbacks are `false`, `0` and `0.0` —
 * so `Set("expanded", "yes")` inserted a control that wrote `false`. Storing a different value than
 * the one asked for is worse than refusing: the design looks authored and does something else. The
 * encoder keeps its fallbacks; nothing reaches it that needs them.
 */
private fun EditorStateAction.valueRefusal(declaration: JsonObject?): String? {
  val raw =
    when (this) {
      is EditorStateAction.Toggle -> return null
      is EditorStateAction.Set -> value
      is EditorStateAction.SelectOrClear -> value
    }
  val kind = declaredStateKind(declaration)
  val parses =
    when (kind) {
      StateKind.BOOLEAN -> raw.toBooleanStrictOrNull() != null
      // `toIntOrNull`, because the exporter declares an integer variable as `Int`. A value past
      // that range parses as a Long and then emits a literal the generated Kotlin cannot hold.
      StateKind.INTEGER -> raw.toIntOrNull() != null
      // Finite, and for the same reason the integer case is bounded to `Int`: `kotlinLiteral`
      // emits a JSON number verbatim, so `NaN` and `Infinity` — which `toDoubleOrNull` accepts —
      // would export as bare identifiers the generated Kotlin never declares. The property editor
      // already refuses them; the state editor was the way past it.
      StateKind.DECIMAL -> raw.toDoubleOrNull()?.isFinite() == true
      StateKind.STRING -> true
    }
  return if (parses) null
  else "`$raw` is not a ${kind.name.lowercase()} value for state variable `$variable`"
}

/** What a state variable holds, as the document declares it. */
private enum class StateKind {
  BOOLEAN,
  INTEGER,
  DECIMAL,
  STRING,
}

/**
 * A variable's kind, from `valueType` where the declaration carries it.
 *
 * `initialValue` alone is not a safe classifier: `booleanOrNull` and `longOrNull` on a
 * `JsonPrimitive` parse its content whether or not it was quoted, so a text variable initialised to
 * `"true"` or `"1"` reads as a flag or a number and the value written back would not match the
 * Kotlin type the exporter declares. `isString` settles it wherever `valueType` is absent.
 */
private fun declaredStateKind(declaration: JsonObject?): StateKind {
  when (declaration?.get("valueType")?.primitiveOrNull()?.contentOrNull) {
    "bool" -> return StateKind.BOOLEAN
    "int" -> return StateKind.INTEGER
    "float" -> return StateKind.DECIMAL
    "string" -> return StateKind.STRING
  }
  val initial = declaration?.get("initialValue") as? JsonPrimitive ?: return StateKind.STRING
  return when {
    initial.isString -> StateKind.STRING
    initial.booleanOrNull != null -> StateKind.BOOLEAN
    initial.longOrNull != null -> StateKind.INTEGER
    initial.doubleOrNull != null -> StateKind.DECIMAL
    else -> StateKind.STRING
  }
}

/** A variable the document declares nullable, and so the only kind `selectOrClear` may clear. */
private fun declaredNullable(declaration: JsonObject?): Boolean =
  declaration?.get("nullable")?.primitiveOrNull()?.booleanOrNull
    ?: (declaration?.get("initialValue") is JsonNull)

/**
 * One authored value, typed against the variable it will be written to or compared with.
 *
 * `Int`, not `Long`, for a whole number: the exporter declares an integer variable as `Int`, so a
 * value past that range would be authored here and then emitted as a literal the generated Kotlin
 * cannot hold. Callers check with [EditorStateAction.valueRefusal] first, so the fallbacks are
 * unreachable rather than load-bearing.
 */
private fun typedStateValue(raw: String, declaration: JsonObject?): JsonPrimitive =
  when (declaredStateKind(declaration)) {
    StateKind.BOOLEAN -> JsonPrimitive(raw.toBooleanStrictOrNull() ?: false)
    StateKind.INTEGER -> JsonPrimitive(raw.toIntOrNull() ?: 0)
    StateKind.DECIMAL -> JsonPrimitive(raw.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0)
    StateKind.STRING -> JsonPrimitive(raw)
  }

private fun EditorStateAction.encoded(declaration: JsonObject?): JsonObject {
  fun typed(raw: String): JsonPrimitive = typedStateValue(raw, declaration)
  return when (this) {
    is EditorStateAction.Toggle ->
      JsonObject(mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive(variable)))
    is EditorStateAction.Set ->
      JsonObject(
        mapOf(
          "type" to JsonPrimitive("set"),
          "variable" to JsonPrimitive(variable),
          "value" to typed(value),
        )
      )
    is EditorStateAction.SelectOrClear ->
      JsonObject(
        mapOf(
          "type" to JsonPrimitive("selectOrClear"),
          "variable" to JsonPrimitive(variable),
          "value" to typed(value),
        )
      )
  }
}

/**
 * The ceiling on a markup label.
 *
 * 160 characters: a sentence about what is wrong, not a paragraph. Longer than this and the label
 * stops fitting the box it was dragged out for, which is the point at which the annotation stops
 * pointing at anything.
 */
internal const val MAX_MARKUP_TEXT: Int = 160

/** A target slot in the shape the acceptance check reads, so one function answers both callers. */
private fun ParentSlot.asInspectionSlot(): UiBuilderSlotInspection =
  UiBuilderSlotInspection(
    parentNodeId = nodeId,
    slotName = slot,
    childNodeIds = emptyList(),
    measuredChildNodeIds = emptyList(),
  )

/** Pure editor interaction reducer. Every document mutation delegates to CollaborationReducer. */
sealed interface EditorSubmission {
  data class Batch(val command: DesignCommand) : EditorSubmission

  data class Undo(val command: UndoCommand) : EditorSubmission

  data class Redo(val command: RedoCommand) : EditorSubmission
}

class UiBuilderEditorReducer(
  private val catalog: CapabilityCatalog,
  private val actorId: String = EDITOR_ACTOR_ID,
  private val clientId: String = EDITOR_CLIENT_ID,
  private val operationIdPrefix: String = clientId,
) {
  private val capabilityValidator = CapabilityValidator(catalog)
  private val validator = CapabilityPropertyWriteValidator(capabilityValidator)
  private val documentValidator = CapabilityDocumentWriteValidator(capabilityValidator)

  fun initial(document: UiBuilderDocument, selectedNodeId: String? = null): UiBuilderEditorState =
    UiBuilderEditorState(
      collaboration = CollaborationState(document),
      selection = listOfNotNull(selectedNodeId?.takeIf(document.nodes::containsKey)),
    )

  /**
   * This editor's state carried onto a new authoritative document.
   *
   * Every accepted local edit and every remote delta arrives as a new document and rebuilds the
   * editor from it, so anything not carried across is lost on the next keystroke anyone in the
   * session makes. The multi-selection and the clipboard are editing intent rather than document
   * content — a copy has to stay pasteable after a collaborator moves something — so they survive,
   * minus whatever the new document no longer holds.
   *
   * Selection keeps its order, which is what keeps its anchor: the last entry is the node the
   * inspector edits and the one a range extends from.
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
      layerQuery = state.layerQuery,
      previewMode = state.previewMode,
      codePaneVisible = state.codePaneVisible,
      previewSurface = state.previewSurface,
      operationSequence = state.operationSequence,
      inspectorMode = state.inspectorMode,
    )
  }

  fun reduce(state: UiBuilderEditorState, event: UiBuilderEditorEvent): UiBuilderEditorState =
    when (event) {
      is UiBuilderEditorEvent.SearchCatalog -> state.copy(catalogQuery = event.query)
      is UiBuilderEditorEvent.SearchLayers -> state.copy(layerQuery = event.query)
      is UiBuilderEditorEvent.TogglePreview -> state.copy(previewMode = !state.previewMode)
      is UiBuilderEditorEvent.ToggleCodePane -> state.copy(codePaneVisible = !state.codePaneVisible)
      is UiBuilderEditorEvent.ShowPreviewSurface -> state.copy(previewSurface = event.surface)
      is UiBuilderEditorEvent.SelectAllMatches -> selectAllMatches(state)
      is UiBuilderEditorEvent.SelectNode ->
        if (event.nodeId in state.document.nodes) state.copy(selection = listOf(event.nodeId))
        else state
      is UiBuilderEditorEvent.InsertComponent -> insert(state, event.componentId, event.target)
      is UiBuilderEditorEvent.MoveNode -> move(state, event)
      is UiBuilderEditorEvent.MoveNodeInto -> moveInto(state, event)
      is UiBuilderEditorEvent.CommitProperty ->
        commitProperty(state, event.nodeId, event.property, event.draft)
      is UiBuilderEditorEvent.BindPropertyToState ->
        bindPropertyToState(state, event.nodeId, event.property, event.variable, event.equalsValue)
      is UiBuilderEditorEvent.UnbindProperty -> unbindProperty(state, event.nodeId, event.property)
      is UiBuilderEditorEvent.UpdateEnvironment -> updateEnvironment(state, event.settings)
      is UiBuilderEditorEvent.ShowInspector -> state.copy(inspectorMode = event.mode)
      is UiBuilderEditorEvent.ApplyTheme -> applyTheme(state, event.settings)
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
      UiBuilderEditorEvent.CopySelected -> copySelected(state)
      UiBuilderEditorEvent.CutSelected -> cutSelected(state)
      UiBuilderEditorEvent.Paste -> paste(state)
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
   * Whether the whole selection can go.
   *
   * Checked **cumulatively**, not one node at a time against the original document. Three children
   * of a slot with `min = 1` can lose two; asking of each separately says yes three times and the
   * third delete is rejected halfway through — a partial delete the user did not ask for and cannot
   * undo in one step.
   *
   * The selection is reduced to its top-most nodes first: a node selected alongside its own
   * ancestor is deleted by the ancestor's removal, and counting it separately would over-count what
   * each slot loses.
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
   * Whether the whole selection can be duplicated.
   *
   * Cumulative for the same reason delete is: duplicating two siblings adds two to their slot, and
   * a slot with one space left can take one of them. Checking each against the original document
   * says yes twice and the second insert is rejected, leaving half a duplicate.
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
   * Whether [paste] would land somewhere.
   *
   * Asked of the *clipboard's* component rather than the selection's, because that is what has to
   * be accepted: pasting a `m3/text` beside a full `Row` is a different question from duplicating
   * the `Row`. A false here is why the paste affordance is disabled rather than offered and
   * refused.
   */
  fun canPaste(state: UiBuilderEditorState): Boolean = pasteDestination(state) != null

  /**
   * Where the clipboard would land, or null when it would not.
   *
   * Every root has to be accepted by one destination, not merely one of them: a paste that placed
   * two of three copied nodes would leave the user reconstructing which one went missing. Room is
   * checked against the whole batch for the same reason — a slot with one space left cannot take
   * three.
   */
  private fun pasteDestination(state: UiBuilderEditorState): ParentSlot? {
    val clipboard = state.clipboard?.takeIf { it.rootNodeIds.isNotEmpty() } ?: return null
    val capabilities = clipboard.rootComponentIds.map { catalog.componentsById[it] ?: return null }
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
   * The state variables this document declares, for a binding picker.
   *
   * Only declared ones. A property bound to a name nothing declares reads as null at render time
   * and generates nothing at export, so offering free text here would let the builder produce a
   * screen that silently shows blanks.
   */
  fun stateVariableNames(state: UiBuilderEditorState): List<String> =
    state.document.stateVariables.keys.sorted()

  /**
   * Whether a binding on [propertyName] has to be a comparison rather than a bare read.
   *
   * A boolean property cannot take the value of a string variable, so the catalog refuses a bare
   * read there and accepts `stateEquals`. Asking this up front lets the inspector offer the shape
   * that will be accepted, instead of offering one and relaying the catalog's refusal.
   */
  /**
   * Whether a binding on [propertyName] would be accepted at all.
   *
   * Answered by building the value the bind would write and putting it through the same validator
   * the bind itself uses, rather than by reasoning about types a second time. The catalog refuses a
   * state read on plenty of properties — `m3/text.text` is `string` and takes a literal, not a
   * reference — and a menu that offers a binding the reducer will refuse is a menu that lies. The
   * wrap menu is built the same way and for the same reason.
   */
  fun canBindToState(
    state: UiBuilderEditorState,
    nodeId: String,
    propertyName: String,
  ): Boolean {
    val variable = state.document.stateVariables.keys.firstOrNull() ?: return false
    if (state.document.nodes[nodeId] == null) return false
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
    return validator.validate(state.document, nodeId, propertyName, candidate) == null
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

  fun catalogItems(query: String): List<EditorCatalogItem> {
    val needle = query.trim().lowercase()
    return catalog.components
      .asSequence()
      .map {
        EditorCatalogItem(
          componentId = it.componentId,
          displayName = it.displayName,
          kind = it.editorKind(),
        )
      }
      .filter {
        needle.isEmpty() ||
          it.displayName.lowercase().contains(needle) ||
          it.componentId.lowercase().contains(needle) ||
          it.kind.label.lowercase().contains(needle)
      }
      .sortedWith(compareBy(EditorCatalogItem::kind, EditorCatalogItem::displayName))
      .toList()
  }

  fun treeRows(document: UiBuilderDocument): List<EditorTreeRow> {
    val rows = mutableListOf<EditorTreeRow>()
    val seen = mutableSetOf<String>()
    fun visit(nodeId: String, depth: Int, parent: ParentSlot?) {
      // A reference to a node that is not there, and a reference to one already on this path, are
      // both things `validateDocumentForExport` reports — `UNKNOWN_ROOT`, `UNKNOWN_CHILD`,
      // `GRAPH_CYCLE`. The navigator is built before the inspector, so `getValue` and an unbounded
      // recursion took the whole editor down before the Issues panel could name any of them: the
      // one document that most needs the panel was the one that could not show it.
      val node = document.nodes[nodeId] ?: return
      if (!seen.add(nodeId)) return
      val capability = catalog.componentsById[node.componentId]
      val componentLabel = capability?.displayName ?: node.componentId
      rows +=
        EditorTreeRow(
          nodeId = nodeId,
          componentId = node.componentId,
          label = capability?.let(node::contentLabel) ?: componentLabel,
          componentLabel = componentLabel,
          depth = depth,
          parent = parent,
        )
      node.slots.forEach { (slot, children) ->
        children.forEach { visit(it, depth + 1, ParentSlot(nodeId, slot)) }
      }
    }
    document.roots.forEach { visit(it, 0, null) }
    return rows
  }

  /**
   * The layers panel's rows, narrowed by [UiBuilderEditorState.layerQuery].
   *
   * A row survives the filter when it matches **or when one of its descendants does**, because a
   * tree filtered to bare matches loses the indentation that made it a tree: a row three levels
   * deep would sit flush against an unrelated root. Ancestors come through as context, with
   * [EditorTreeRow.matched] false, so nothing selects them by accident.
   *
   * Matching is over everything the panel and the document call the node — what it says, what its
   * component is called, its own id and its component id — because those are the four things a
   * person types when looking for one.
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
    // A row is kept when it matched, or when it is an ancestor of one.
    //
    // Ancestors come from parent pointers built in one forward pass: on a pre-order walk the most
    // recent row at depth d-1 is the parent of a row at depth d. The previous version scanned
    // *backwards over every preceding row* for each match, which is quadratic — one root with
    // 9,999 matching children walked some 50 million rows, synchronously in the Wasm UI, on every
    // keystroke in the filter field. The service permits 10,000-node designs.
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
   * The layers panel's lines: the rows of [visibleTreeRows], with the slot each group of children
   * sits in named above it.
   *
   * Indentation alone said a Scaffold's app bar and its screen content were siblings, which they
   * are not: they are the only children of two different slots, and nothing in the panel said so.
   * That reading is what made the drag look broken — dropping one onto the other is a move between
   * slots, and it is refused for reasons the panel had no way to show.
   *
   * A slot line is drawn when it is a choice or a destination:
   * - the parent declares more than one slot, so which one a child is in is information; or
   * - the slot is empty, and is therefore the one place a drop can land that no node row names; or
   * - the document put children under a name the catalog does not declare (a dynamic slot).
   *
   * A container with one slot and something in it draws none, because the indentation already says
   * everything the slot name would. Under a filter, empty slots are left out: nothing there matches
   * and the panel is answering a search.
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
        // The slot line sits at the depth of the children it heads rather than between them and
        // their parent. A tree that indented twice per level ran out of width on the fourth
        // container, and the panel is 300dp wide.
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
   * The inspector's fields for the current selection.
   *
   * For more than one node it shows the properties **every** selected component declares, so
   * editing six texts' style is one edit rather than six. A property only some of them have is left
   * out rather than shown and silently applied to a subset — the inspector would otherwise claim to
   * be editing the selection while editing part of it.
   *
   * Where the nodes disagree the field is [EditorPropertyField.mixed] and its value is blank, so
   * the control shows nothing rather than one node's value standing in for all of them.
   */
  fun propertyFields(state: UiBuilderEditorState): List<EditorPropertyField> {
    val nodes = state.selection.mapNotNull(state.document.nodes::get)
    val node = nodes.lastOrNull() ?: return emptyList()
    val component = catalog.componentsById[node.componentId] ?: return emptyList()
    // The other selected components, for deciding what the selection genuinely shares. A name in
    // common is not enough: two components can both declare `style` and allow disjoint values, and
    // the field is built from the anchor's declaration alone. Offering it would put a dropdown in
    // front of the user whose every choice `commitProperty` then rejects for the other nodes —
    // that per-node validation is what keeps the document right, and an unusable control is
    // exactly what this inspector exists to stop showing.
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
            // Membership, not equality, for the same reason `literalDefault` uses membership: a
            // property declared ["boolean", "string"] is how the catalog spells "a flag or the name
            // of a state variable". Under equality those fell through to `Unsupported`, so
            // unbinding `m3/filter-chip.selected` wrote a real boolean the inspector then refused
            // to edit — two rules in this file disagreeing about the same declaration.
            declaredControl == PropertyEditorControl.BOOLEAN || "boolean" in typeNames ->
              EditorPropertyControl.Boolean
            (declaredControl == PropertyEditorControl.NUMBER ||
              typeNames == setOf("number") ||
              typeNames == setOf("integer")) && numberBounds != null -> EditorPropertyControl.Number
            typeNames == setOf("string") -> EditorPropertyControl.Text
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
          nodes.map { (it.properties[property.name] as? JsonObject)?.get("value") }.distinct()
        val mixed = encodedValues.size > 1
        EditorPropertyField(
            nodeCount = nodes.size,
            mixed = mixed,
            boundVariable = boundVariables.singleOrNull(),
            nodeId = node.id,
            name = property.name,
            label = property.name.humanLabel(),
            required = property.required,
            control = control,
            value = if (mixed) "" else value?.primitiveOrNull()?.content ?: "",
            choices =
              property.allowedValues.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } +
                property.editor?.suggestedValues.orEmpty(),
            numberBounds = numberBounds,
            error = state.propertyErrors[EditorPropertyLocation(node.id, property.name)],
            notes = property.notes,
          )
          .let(::listOf)
      }
  }

  /**
   * One numeric edge of an object-valued property, as its own number field.
   *
   * Four fields rather than one composite control: the edges are independent numbers with their own
   * bounds, and a padding edited edge by edge reuses the number control's parsing, its bounds
   * message and its behaviour across a multi-selection instead of growing a second copy of all
   * three. The field is addressed as `property.edge`, and [commitProperty] merges it back into the
   * whole value, because the wire carries one `padding` and not four numbers.
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
      control = EditorPropertyControl.Number,
      value = if (mixed) "" else values.singleOrNull() ?: edge.minimum.format(),
      numberBounds = EditorNumberBounds(edge.minimum, edge.maximum, 1.0, integer = false),
      error = state.propertyErrors[EditorPropertyLocation(anchor.id, property.name)],
      notes = property.notes,
    )
  }

  /**
   * Everything the Compose export gate would refuse, against the document as it stands.
   *
   * The editor validates each write as it happens, so a rejected edit never lands — but nothing was
   * checking the document as a whole. A node can become unreachable when its parent is deleted, a
   * required property can be missing on a node nobody touched, and a catalog pin can drift; none of
   * those is a rejected write, and all of them are a failed export. Until now the first anyone knew
   * was the export refusing.
   *
   * Read from [CapabilityComposeCodeExporter.diagnose] rather than from `validateDocumentForExport`
   * alone, because those are two different questions and only the wider one is the promise this
   * panel makes. A design can satisfy every structural rule and still hold a component the exporter
   * has no emitter for, or a modifier the catalog does not allow on it — and against the narrower
   * gate the panel said nothing was blocking an export right up until the export refused.
   *
   * Errors only. A warning is something the export notes and proceeds through, so listing it beside
   * the things that stop the build would make the panel's one claim untrue in the other direction.
   *
   * A node id that no longer exists is dropped rather than offered as something to select.
   *
   * Takes the document rather than the state because it reads nothing else, which is what lets the
   * caller cache it against the document alone.
   */
  fun problems(document: UiBuilderDocument): List<EditorProblem> =
    (CapabilityComposeCodeExporter.diagnose(document, catalog)
        .filter { it.severity == ComposeExportSeverity.ERROR }
        .map { diagnostic ->
          EditorProblem(
            code = diagnostic.code,
            message = diagnostic.message,
            nodeId = diagnostic.nodeId?.takeIf(document.nodes::containsKey),
            componentId = diagnostic.componentId,
          )
        } +
        // The same refusals the server's export will produce, from the same code: the projection
        // and the generator that the export runs, against the record the export reads. Before
        // this the panel judged a design with `CapabilityComposeCodeExporter`, which has an
        // emitter for every catalog id, so it stayed silent about the components the record does
        // not back and the export refuses with `NO_COMPONENT_RECORD`.
        //
        // Appended rather than replacing: the capability diagnostics still answer questions the
        // generator does not ask — catalog pin drift, a modifier the catalog disallows on a
        // component — and dropping them to unify the source would narrow the panel's promise.
        exportRefusals(document))
      .distinctBy { it.code to it.message }

  /**
   * The Kotlin the Compose export would write for [document], or why it would not.
   *
   * The same call the problems panel makes, asked for its other half. Before this the editor could
   * show a designer what was blocking an export but never what an export would produce, so the only
   * way to read the code your edits were writing was to run the export and download the artifact.
   *
   * Total for the same reason [exportRefusals] is: a malformed property makes `toProtocolDocument`
   * fail its decode, and a pane that propagated that would take the editor down over exactly the
   * document whose code someone is trying to read.
   */
  fun generatedCode(document: UiBuilderDocument): EditorGeneratedCode = runCatching {
    // A Wear widget is not a screen: it ships as a `WearWidgetDocument` of Remote Compose, against
    // no component record, so the Compose gate below can only ever refuse it. Asked first rather
    // than as a fallback, because "refused, and also here is different code" would be two answers
    // to one question.
    if (document.isWearWidget()) {
      return@runCatching when (val widget = WearWidgetCodeExporter.export(document)) {
        is WearWidgetCodeExporter.Result.Emitted -> EditorGeneratedCode.Source(widget.source)
        is WearWidgetCodeExporter.Result.Refused -> EditorGeneratedCode.Refused(widget.reasons)
      }
    }
    when (
      val outcome =
        ScreenExportGate.export(document.toProtocolDocument(), embeddedComponentRecord())
    ) {
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
   * What the Compose export would refuse, as editor problems.
   *
   * `nodeId` is deliberately null: a refusal names the component and the reason in its text, and
   * the generator reports against the *projected* screen rather than the document's node ids. A
   * guessed id would offer the designer a node to select that is not the one at fault.
   */
  private fun exportRefusals(document: UiBuilderDocument): List<EditorProblem> =
  // Total, because the panel's contract is to *report* rather than throw. A malformed property
  // makes `toProtocolDocument` fail its decode, and a panel that propagated that would take the
  // editor down over the one document whose problems a designer most needs listed. The capability
  // diagnostics above already name that document's real fault.
  runCatching {
    ScreenExportGate.refusals(document.toProtocolDocument(), embeddedComponentRecord())
  }
    .getOrElse { emptyList() }
    .map {
      EditorProblem(
        code = "COMPOSE_EXPORT_REFUSED",
        message = it,
        nodeId = null,
        componentId = null,
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

  private fun insert(
    state: UiBuilderEditorState,
    componentId: String,
    target: ParentSlot,
    action: EditorStateAction? = null,
  ): UiBuilderEditorState {
    val component = catalog.componentsById[componentId] ?: return state
    val sequence = state.operationSequence + 1
    val resolvedTarget = findDestination(state.document, state.selectedNodeId, component)
    if (resolvedTarget == null || resolvedTarget != target) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_LOCATION,
        "${component.displayName} has no compatible selected slot",
      )
    }
    return insertAt(state, component, target, action)
  }

  /**
   * Insert [component] into [target], with no opinion about the selection.
   *
   * Split out of [insert] because the selection check there is a guard on one *route in* rather
   * than a rule about insertion: a drag from the catalog lands where the selection implies, and a
   * client asking for any other slot is asking for something the editor never offered. A promoted
   * reference piece arrives by a different route — its slot comes from a hit test this reducer ran
   * against the catalog itself — and re-deriving it from the selection would refuse a slot that is
   * demonstrably legal, because the selection is wherever the operator last clicked.
   */
  private fun insertAt(
    state: UiBuilderEditorState,
    component: ComponentCapability,
    target: ParentSlot,
    action: EditorStateAction? = null,
  ): UiBuilderEditorState {
    val componentId = component.componentId
    val sequence = state.operationSequence + 1
    val nodeId = "editor-${componentId.replace('/', '-')}-${sequence.toString().padStart(3, '0')}"
    val operations = mutableListOf<DesignOperation>()
    val defaultError =
      component.appendDefaultSubtree(
        catalog = catalog,
        document = state.document,
        nodeId = nodeId,
        parent = target,
        afterNodeId = state.document.children(target).lastOrNull(),
        operations = operations,
      )
    if (defaultError != null) {
      return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, defaultError)
    }
    if (action != null) {
      if (action.variable !in state.document.stateVariables) {
        return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "This design declares no state variable `${action.variable}`",
        )
      }
      // Bound to `click` on the inserted root, and `click` alone. It is the one event this
      // renderer applies to any node — `actionModifier` makes anything carrying a click binding
      // clickable — while every other event name is implemented per component and the catalog
      // declares none of them, so offering one would be a guess.
      val declaration = state.document.stateVariables[action.variable] as? JsonObject
      // `selectOrClear` writes null when the value is already selected, and the exporter declares
      // each variable from its own `nullable`. Against a non-nullable one the generated assignment
      // would not compile, so the design never gets to hold that action.
      if (action is EditorStateAction.SelectOrClear && !declaredNullable(declaration)) {
        return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "State variable `${action.variable}` is not nullable, so it cannot be cleared",
        )
      }
      // `toggle` is `!x`, which needs a boolean. The renderer coerces whatever it finds to a
      // boolean string and carries on, so the preview looks like it works; the exporter refuses
      // and emits a `TODO` that throws on the first press. Refusing here keeps the design from
      // holding an action only one of its two consumers can perform.
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
      val bindings = JsonObject(mapOf("click" to JsonArray(listOf(action.encoded(declaration)))))
      val index = operations.indexOfFirst { it is DesignOperation.InsertNode }
      val root = operations[index] as DesignOperation.InsertNode
      operations[index] = root.copy(node = root.node.copy(eventBindings = bindings))
    }
    return state.apply(sequence, operations, selectionAfter = nodeId)
  }

  /**
   * Insert `remote-compose/document` carrying [documentBase64].
   *
   * The bytes are decoded before the operation is built. The renderer decodes them too — it has to,
   * it is what plays them — but a document that reaches the canvas undecodable is already saved,
   * shared with every collaborator, and shown as an error box where a component should be. Refusing
   * here is what keeps "the fetch returned an HTML error page" from becoming a design revision.
   *
   * The decoded document is then dropped rather than kept: [UiBuilderDocument] holds JSON, the
   * player owns the parsed form, and a second in-memory copy would only be a second thing to
   * invalidate.
   */
  private fun insertRemoteComposeDocument(
    state: UiBuilderEditorState,
    source: RemoteComposeSource,
    documentBase64: String,
    target: ParentSlot,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val component =
      catalog.componentsById[REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID]
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_LOCATION,
          "This catalog does not offer $REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID",
        )
    val resolvedTarget = findDestination(state.document, state.selectedNodeId, component)
    if (resolvedTarget == null || resolvedTarget != target) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_LOCATION,
        "${component.displayName} has no compatible selected slot",
      )
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
    val operations = mutableListOf<DesignOperation>()
    val defaultError =
      component.appendDefaultSubtree(
        catalog = catalog,
        document = state.document,
        nodeId = nodeId,
        parent = target,
        afterNodeId = state.document.children(target).lastOrNull(),
        operations = operations,
      )
    if (defaultError != null) {
      return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, defaultError)
    }
    val index = operations.indexOfFirst { it is DesignOperation.InsertNode }
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
    // The drop that changes nothing is the commonest drop of all — a row picked up and released
    // over itself — and it must not cost an operation, an undo step or a revision every
    // collaborator has to take.
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
   * Why [target] will not take [nodeId], or null when it will.
   *
   * The panel asks this while a row is being dragged, so a slot that cannot take what is over it
   * says so before the release rather than swallowing the gesture; the reducer asks it again on the
   * release, because the drag is not the only thing that can send a move.
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
    // The slot it is leaving has a floor as well as a ceiling. A Scaffold's `content` holds
    // exactly one child, so dragging that child out empties a slot the document requires — the
    // document validator refuses it, correctly, and the panel would have shown the drop as
    // landable right up to the release.
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
    // Every selected node that declares this property, so editing six texts' style is one edit.
    // The edited node is always included even when it is not in the selection, which is what
    // happens when the inspector is driven by something other than a click.
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
      val existingType = existingValue?.get("type")?.primitiveOrNull()?.contentOrNull
      val encoded =
        if (edgeName == null) literal(existingType ?: field.defaultEncodedType(), value)
        else
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
      // Validated per node rather than once for the anchor: the same value can be legal on one
      // component and not another, and a rejected edit must reject the whole batch rather than
      // apply to the nodes that happened to come first.
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
   * Attach an import as the base, reading its structure once.
   *
   * The boxes are extracted here rather than by the caller so that every route in — a paste, a
   * file, a snapshot, a design reopened with a reference already stored — passes through the same
   * reader. A raster import simply has none, and the panel then does not offer the mode.
   *
   * Alignment is deliberately *not* carried over from the previous attachment, except for the two
   * fields that describe how the operator is working rather than where the picture sits: a
   * different picture is a different picture, and inheriting the last one's nudge would silently
   * misalign it, but being thrown out of difference mode on every import is friction with no
   * purpose.
   */
  private fun attached(state: UiBuilderEditorState, image: ReferenceImage): ReferenceOverlayState =
    state.reference.copy(
      image = image,
      settings =
        ReferenceOverlaySettings(
          mode = state.reference.settings.mode,
          visible = true,
          alwaysShowBoxes = state.reference.settings.alwaysShowBoxes,
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
   * Drop a piece in the middle of the frame at a size that can be seen and grabbed.
   *
   * Centred at a fixed fraction rather than at the picture's natural pixel size, because the
   * reducer has no frame in pixels to compare it against — and because a component copied out of a
   * design tool arrives at that tool's resolution, which is rarely this frame's. Its aspect ratio
   * is honoured where the importer knew it.
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
   * The slot a piece would be built into, from the layout the canvas actually produced.
   *
   * Position rather than selection, because a piece's whole claim is *where it is*: it was dragged
   * over the row it belongs in, and the selection is wherever the operator last clicked. The
   * deepest accepting slot under [point] wins — deepest because a slot inside another slot is the
   * more specific answer, and the outer one is still reachable by moving the piece somewhere the
   * inner one does not cover.
   *
   * Falls back to [dropTarget] when nothing under the point accepts the component, so promoting
   * still does something sensible for a piece parked over empty canvas.
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
        .minByOrNull { slot -> slot.bounds!!.width * slot.bounds!!.height }
    return hit?.let { ParentSlot(it.parentNodeId, it.slotName) }
      ?: findDestination(state.document, state.selectedNodeId, component)
  }

  /** Whether the node owning [slot] declares it, accepts [component] there, and has room. */
  private fun acceptsComponent(
    document: UiBuilderDocument,
    slot: UiBuilderSlotInspection,
    component: ComponentCapability,
  ): Boolean {
    val parent = document.nodes[slot.parentNodeId] ?: return false
    val capability = catalog.componentsById[parent.componentId] ?: return false
    val declared = capability.slot(slot.slotName) ?: return false
    return declared.accepts(component) &&
      declared.hasRoom(parent.slots[slot.slotName].orEmpty().size)
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
    settings.validationError()?.let { message ->
      return state.rejected(
        state.operationSequence + 1,
        RejectionCode.INVALID_DOCUMENT,
        message,
      )
    }
    val values =
      linkedMapOf(
        "widthDp" to JsonPrimitive(settings.widthDp),
        "heightDp" to JsonPrimitive(settings.heightDp),
        "density" to JsonPrimitive(settings.density),
        "fontScale" to JsonPrimitive(settings.fontScale),
        "locale" to JsonPrimitive(settings.locale),
        "theme" to JsonPrimitive(settings.theme.wireValue),
        "layoutDirection" to JsonPrimitive(settings.layoutDirection.wireValue),
      )
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
   * Reorder the selection among its siblings.
   *
   * Reordering was reachable only by dragging a layer row. That leaves anyone who cannot or would
   * rather not drag — a trackpad, a screen reader, a keyboard — unable to change the one thing
   * layout is mostly made of, so the same [moveTarget] the drag uses is now on the keyboard too.
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
   * Point a property at a state variable.
   *
   * This rides `SetProperty` rather than needing an operation of its own: `StateValueV1` is a
   * `UiValueV1`, so `{"type": "state", "variable": …}` is a property value the wire already
   * carries, the server already validates, and the renderer already resolves. It is how the
   * fixture's search field is wired.
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
      // The operand is compared against the variable in the exported Kotlin, so it has to be the
      // same type the variable is declared as. Encoding it as a string regardless produced
      // `expanded == "true"` against a `Boolean` and `selectedDay == "1"` against an `Int` —
      // neither compiles. The fourth place in this file where a declaration and the literal
      // written against it had to be made to agree.
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
   * Give a bound property a literal again.
   *
   * Needed because a bound property refuses a typed literal — `commitProperty` will not rewrite a
   * state read into a value — so without this a binding is a one-way door.
   */
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
    if (current !in STATE_VALUE_TYPES) return state
    // Deliberately not `defaultEncodedValue`: for some properties the catalog default *is* a state
    // binding — a text field's `value` defaults to one — so unbinding to the default would leave
    // the property bound, which is a no-op for exactly the properties most likely to be bound.
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
   * The containers that could hold the current selection where it stands.
   *
   * Offered rather than a single "Group", because this builder wraps in a **real component** from
   * the catalog — a `Column`, a `Card`, a `Surface` — not in an inert frame. Which ones are legal
   * depends on both ends: the parent slot has to accept the container, and the container needs a
   * slot that accepts every selected node. Asking both here means the menu lists what will work
   * instead of offering everything and refusing most of it.
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
      .map {
        EditorCatalogItem(
          componentId = it.componentId,
          displayName = it.displayName,
          kind = it.editorKind(),
        )
      }
      .sortedWith(compareBy(EditorCatalogItem::kind, EditorCatalogItem::displayName))
  }

  /**
   * The one slot every selected node shares, or null when they do not share one.
   *
   * Wrapping a selection spread across two parents has no answer: the container can only live in
   * one place, so the other nodes would have to move somewhere the document never put them. A
   * refusal is better than picking a parent on the user's behalf.
   *
   * Roots are excluded for the same reason a root cannot be deleted freely — a screen body is not a
   * slot, so there is nothing to insert the container into.
   */
  private fun wrappableParent(
    state: UiBuilderEditorState,
    targets: List<String>,
  ): ParentSlot? = targets.map { state.document.location(it) }.distinct().singleOrNull()

  /**
   * Whether the selection is one unbroken run of siblings.
   *
   * Wrapping `A` and `C` out of `A, B, C` has no faithful answer. The container takes the place of
   * the first node it swallows, so `B` — which nobody selected and nobody moved — comes out after
   * the container rather than between the two nodes it was between. Silently reordering a screen
   * around a node the user did not touch is worse than not offering the wrap, and the same reason
   * [wrappableParent] refuses a selection spread across two parents.
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
      )
    if (defaultError != null) {
      return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, defaultError)
    }
    // A container with a required slot arrives holding a placeholder child — wrapping one text in
    // a Card produced a Card containing `New text` *and* the text, which unwrap could not undo.
    // The placeholders are removed once the real children are in, never before: the slot has a
    // minimum, and emptying it first would make the batch invalid halfway through.
    val placeholders =
      operations.filterIsInstance<DesignOperation.InsertNode>().filter {
        it.parent == ParentSlot(containerId, slot.name)
      }
    // Tree order, not click order. `selectionRoots` preserves the order nodes were selected in —
    // shift-clicking D then B stores C, D, B — and moving them in that order writes it into the
    // container, silently reordering the screen the selection came from.
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
   * Whether the selected node's children can be lifted into its own parent.
   *
   * Wrap without unwrap is a one-way door, and the door is worse here than for a binding: a
   * container added by mistake cannot be deleted either, because deleting it takes the children
   * with it.
   *
   * The parent slot has to accept every child and have room for all of them at once — the container
   * leaves and its children arrive, so occupancy changes by `children - 1`.
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
   * The components that can be inserted already wired to a click action.
   *
   * Two limits, not one. Where the node may go, and whether the Compose export emits a handler for
   * it: `click` is universal in the *renderer* — `actionModifier` makes anything carrying a click
   * binding clickable — but the exporter emits one only for the components whose emitter takes an
   * `onClick`, and reports `UNEMITTED_EVENT` for the rest. Offering the others built a control that
   * worked in the preview and silently lost its interaction on export, which is the divergence this
   * builder exists to not have.
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
    // No operation and no sequence bump: copying changes the editor, not the document, so it must
    // not become an undo step. Undoing a copy would otherwise "undo" whatever real edit preceded
    // it, which is the kind of surprise that makes people stop trusting undo.
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
    // Beside the selection when the paste landed in the slot the selection already sits in, and at
    // the end of the slot when it landed inside the selected container. `findDestination` falls
    // back to the parent slot for a leaf — copying a card in a list and pasting sent the copy to
    // the bottom of the list, which is never where you were looking.
    val selectedLocation = state.selectedNodeId?.let(state.document::location)
    // A cut pasted back into the slot it came from returns to its own position, because that is
    // what makes cut-then-paste a move rather than a send-to-the-bottom. The anchor has to still
    // be there — the document may have been edited between the two — and `null` is a position in
    // its own right, meaning the subtree was first in the slot.
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
    return state.apply(sequence, operations, selectionAfter = pastedIds.last()).let { pasted ->
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

  private fun findDestination(
    document: UiBuilderDocument,
    selectedNodeId: String?,
    inserted: ComponentCapability,
  ): ParentSlot? {
    val selected = selectedNodeId?.let(document.nodes::get)
    val selectedCapability = selected?.let { catalog.componentsById[it.componentId] }
    selectedCapability
      ?.let { capability ->
        capability.slots +
          selected.slots.keys
            .filterNot(capability.slotsByName::containsKey)
            .mapNotNull(capability::slot)
      }
      ?.firstOrNull { slot ->
        slot.accepts(inserted) && slot.hasRoom(selected.slots[slot.name].orEmpty().size)
      }
      ?.let {
        return ParentSlot(selected.id, it.name)
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
}

internal const val THEME_PRIMARY = "themePrimaryColor"
internal const val THEME_BACKGROUND = "themeBackgroundColor"
internal const val THEME_SURFACE = "themeSurfaceColor"
internal const val THEME_CONTENT = "themeContentColor"
internal const val THEME_TYPE_SCALE = "themeTypeScale"
internal const val THEME_CORNER_RADIUS = "themeCornerRadiusDp"
/**
 * The property-value shapes that read a state variable rather than holding a value.
 *
 * `stateEquals` is one too — the fixture's filter chips use it for `selected` — so treating only
 * `state` as a binding would report a chip as unbound and refuse to unbind it.
 */
private val STATE_VALUE_TYPES = setOf("state", "stateEquals")

private val THEME_PROPERTIES =
  setOf(
    THEME_PRIMARY,
    THEME_BACKGROUND,
    THEME_SURFACE,
    THEME_CONTENT,
    THEME_TYPE_SCALE,
    THEME_CORNER_RADIUS,
  )

/** Whether this design is a Wear widget, which generates through a different emitter entirely. */
internal fun UiBuilderDocument.isWearWidget(): Boolean {
  val root = roots.singleOrNull()?.let(nodes::get) ?: return false
  return WearWidgetScaffoldSize.entries.any { it.componentId == root.componentId }
}

private fun UiBuilderDocument.themeHost(): UiBuilderNode? =
  roots.asSequence().mapNotNull(nodes::get).firstOrNull { it.componentId == "m3/surface" }

private fun UiBuilderNode.stringValue(name: String, fallback: String): String =
  properties[name]?.jsonObject?.get("value")?.primitiveOrNull()?.content ?: fallback

private fun UiBuilderNode.floatValue(name: String, fallback: Float): Float =
  properties[name]?.jsonObject?.get("value")?.primitiveOrNull()?.doubleOrNull?.toFloat() ?: fallback

private fun String.isArgbColor(): Boolean =
  startsWith("#") &&
    length in setOf(7, 9) &&
    drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

const val EDITOR_ACTOR_ID = "wasm-editor"
const val EDITOR_CLIENT_ID = "interactive-canvas"

private fun UiBuilderEditorState.undoTargetOperationId(actorId: String = EDITOR_ACTOR_ID): String? =
  collaboration.acceptedCommands.values
    .asSequence()
    .filter { it.command.actorId == actorId }
    .filter { it.command.operationId !in collaboration.compensatedOperationIds }
    .maxByOrNull(AcceptedCommand::committedRevision)
    ?.command
    ?.operationId

private fun UiBuilderEditorState.redoTargetUndoId(actorId: String = EDITOR_ACTOR_ID): String? =
  collaboration.undoRecords.values
    .asSequence()
    .filter { it.command.actorId == actorId && it.redoneBy == null }
    .maxByOrNull(AcceptedUndo::committedRevision)
    ?.command
    ?.operationId

private fun ComponentCapability.editorKind(): EditorComponentKind =
  when (role) {
    "Scaffold" -> EditorComponentKind.Scaffold
    "Container" -> EditorComponentKind.Container
    else -> EditorComponentKind.Composable
  }

private fun SlotCapability.accepts(component: ComponentCapability): Boolean =
  "AnyContent" in acceptedTraits ||
    component.role in acceptedRoles ||
    component.traits.any(acceptedTraits::contains)

private fun SlotCapability.hasRoom(childCount: Int): Boolean =
  cardinality.max?.let { childCount < it } ?: true

private fun ComponentCapability.slot(name: String): SlotCapability? =
  slotsByName[name]
    ?: dynamicSlots?.let {
      SlotCapability(
        name = name,
        cardinality = it.cardinality,
        ordered = it.ordered,
        acceptedRoles = it.acceptedRoles,
        acceptedTraits = it.acceptedTraits,
      )
    }

private fun ComponentCapability.defaultNode(
  nodeId: String,
  document: UiBuilderDocument,
): UiBuilderNode =
  UiBuilderNode(
    id = nodeId,
    componentId = componentId,
    properties =
      JsonObject(
        properties.filter(PropertyCapability::required).associate { property ->
          property.name to property.defaultEncodedValue(nodeId, document)
        }
      ),
    modifiers = JsonArray(emptyList()),
    slots = slots.associate { it.name to emptyList() },
  )

/**
 * A neutral literal for this property: the first allowed value, or an empty one of its own type.
 *
 * Typed rather than always the empty string, because `""` is not a boolean and a validator that
 * refuses it would make unbinding a boolean impossible.
 */
private fun PropertyCapability.literalDefault(): JsonObject {
  allowedValues.firstOrNull()?.let {
    return it.asLiteral(name)
  }
  val types = typeNames() - "null"
  // Membership, not equality. A property declared ["boolean", "string"] — which is how the catalog
  // spells "a flag or the name of a state variable" — would otherwise unbind to `""`, and an empty
  // string on such a property reads as a variable name rather than as off.
  return when {
    "boolean" in types -> literal("bool", JsonPrimitive(false))
    "number" in types || "integer" in types -> literal("float", JsonPrimitive(0))
    else -> JsonPrimitive("").asLiteral(name)
  }
}

private fun PropertyCapability.defaultEncodedValue(
  nodeId: String,
  document: UiBuilderDocument,
): JsonObject {
  allowedValues.firstOrNull()?.let { value ->
    return value.asLiteral(name)
  }
  return when (name) {
    "text" -> literal("string", JsonPrimitive("New text"))
    "assetKey" -> literal("assetKey", JsonPrimitive("editor.placeholder"))
    "iconKey" -> literal("enum", JsonPrimitive("addCircle"))
    "layoutMode" -> literal("enum", JsonPrimitive("adaptive"))
    "mainPaneVisible",
    "supportingPaneVisible" -> literal("bool", JsonPrimitive(true))
    "columns" ->
      JsonObject(
        mapOf(
          "type" to JsonPrimitive("adaptiveGrid"),
          "minimumCellWidthDp" to JsonPrimitive(362),
        )
      )
    "scrollStateKey" -> literal("string", JsonPrimitive("$nodeId-scroll"))
    "itemWidthDp" -> literal("float", JsonPrimitive(128.0))
    "expanded",
    "selected",
    "visible" -> literal("bool", JsonPrimitive(false))
    "value" ->
      JsonObject(
        mapOf(
          "type" to JsonPrimitive("state"),
          "variable" to JsonPrimitive(document.stateVariables.keys.firstOrNull() ?: "value"),
        )
      )
    "startColor" -> literal("color", JsonPrimitive("#00000000"))
    "endColor" -> literal("color", JsonPrimitive("#FF000000"))
    else -> JsonPrimitive("").asLiteral(name)
  }
}

private fun ComponentCapability.appendDefaultSubtree(
  catalog: CapabilityCatalog,
  document: UiBuilderDocument,
  nodeId: String,
  parent: ParentSlot,
  afterNodeId: String?,
  operations: MutableList<DesignOperation>,
  componentPath: Set<String> = emptySet(),
  depth: Int = 0,
): String? {
  if (depth >= 32) return "required-slot defaults exceed the maximum depth of 32"
  if (componentId in componentPath) {
    return "required-slot default cycle: ${(componentPath + componentId).joinToString(" -> ")}"
  }
  operations += DesignOperation.InsertNode(defaultNode(nodeId, document), parent, afterNodeId)
  slots
    .filter { it.cardinality.min > 0 }
    .forEach { slot ->
      repeat(slot.cardinality.min) { index ->
        val child =
          defaultChildFor(slot, catalog)
            ?: return "catalog has no safe required-slot default for $componentId.${slot.name}"
        val childId = "$nodeId-${slot.name}-${index + 1}"
        val error =
          child.appendDefaultSubtree(
            catalog = catalog,
            document = document,
            nodeId = childId,
            parent = ParentSlot(nodeId, slot.name),
            afterNodeId = if (index == 0) null else "$nodeId-${slot.name}-$index",
            operations = operations,
            componentPath = componentPath + componentId,
            depth = depth + 1,
          )
        if (error != null) return error
      }
    }
  return null
}

private fun defaultChildFor(
  slot: SlotCapability,
  catalog: CapabilityCatalog,
): ComponentCapability? {
  val preferredId =
    when {
      "IconContent" in slot.acceptedTraits -> "m3/icon"
      "SearchInput" in slot.acceptedTraits -> "m3/search-input-field"
      "TextContent" in slot.acceptedTraits || "Leaf" in slot.acceptedRoles -> "m3/text"
      else -> "layout/box"
    }
  return catalog.componentsById[preferredId]?.takeIf(slot::accepts)
}

/**
 * Every node reachable from [nodeId], detached from this document.
 *
 * A map rather than a node, because a subtree's children live in the document's flat `nodes` and
 * would be lost by copying the root alone.
 */
/**
 * What this node says, for a layer row — or null when it says nothing.
 *
 * A panel of twelve rows all reading "Text" cannot be scanned, which is why every design tool names
 * a text layer after its content. The property is found **through the catalog** rather than from a
 * list of names this file guesses at.
 *
 * The signal is `required`. A component's required free-text property is the thing it exists to
 * carry — `m3/text` requires `text` — while its optional ones are configuration and plumbing that
 * happen to be strings: `m3/card` declares `shape` and `stableKey`, and a card named after its
 * stable key is worse than one named "Card". That distinction is the catalog's own, which is why
 * this reads it rather than keeping a list of blessed property names.
 *
 * `contentDescription` is the one name-by-name exception, and it earns it: it is defined as the
 * node's human-readable name, so an image that has one is better named by it than by "Image".
 *
 * Free-text means a lone `string` with no `allowedValues` — an enum is a setting, and a colour is
 * not something anyone recognises a layer by.
 */
private val IDENTITY_PROPERTY_SUFFIXES = listOf("Key", "Id", "Base64")

private fun UiBuilderNode.contentLabel(capability: ComponentCapability): String? {
  fun freeText(property: PropertyCapability) =
    property.allowedValues.isEmpty() &&
      property.typeNames() - "null" == setOf("string") &&
      !property.name.endsWith("Color", ignoreCase = true) &&
      // `required` is necessary and not sufficient, which the first cut of this got wrong twice.
      // A component can require a string it needs in order to work rather than one a person would
      // recognise it by, and in this catalog five of the six do: `asset/image` requires `assetKey`,
      // the three lazy containers require `scrollStateKey`, and `remote-compose/document` requires
      // `documentBase64` — so the layers panel offered an asset key, a scroll key, and a base64
      // blob as layer names. Only `m3/text.text` was content.
      //
      // The name carries the kind, the same way it does for a `…Dp` dimension: a key, an id or a
      // payload is plumbing whatever its type. Excluding them by suffix leaves `text` and any
      // future genuine content property alone, and an image falls through to its
      // `contentDescription`, which is the thing that was always the better name for it.
      IDENTITY_PROPERTY_SUFFIXES.none { property.name.endsWith(it, ignoreCase = true) }

  fun valueOf(name: String) =
    (properties[name] as? JsonObject)
      ?.get("value")
      ?.primitiveOrNull()
      ?.contentOrNull
      ?.trim()
      ?.takeIf(String::isNotEmpty)

  val required = capability.properties.filter { it.required && freeText(it) }
  return (required.firstNotNullOfOrNull { valueOf(it.name) }
      ?: capability.properties
        .firstOrNull { it.name == "contentDescription" && freeText(it) }
        ?.let { valueOf(it.name) })
    // One line in a layer row. A paragraph pasted into a Text would otherwise push the type column
    // off the panel, so it is cut here rather than relying on the row to ellipsize it.
    ?.let { if (it.length <= 40) it else it.take(39).trimEnd() + "…" }
}

/**
 * The selected nodes that are not inside another selected node, in selection order.
 *
 * An ancestor carries its descendants, whether it is being deleted or copied, so a descendant
 * selected alongside its ancestor is not a separate target. Emitting one for a delete would target
 * a node that no longer exists and would over-count what its slot loses; copying one would put the
 * same subtree on the clipboard twice and paste it twice.
 */
private fun UiBuilderEditorState.selectionRoots(): List<String> {
  val present = selection.filter(document.nodes::containsKey)
  return present.filterNot { nodeId ->
    generateSequence(document.location(nodeId)?.nodeId) { document.location(it)?.nodeId }
      .any { it in present }
  }
}

private fun UiBuilderDocument.clip(nodeIds: List<String>): EditorClipboard {
  val collected = linkedMapOf<String, UiBuilderNode>()
  fun visit(id: String) {
    val node = nodes[id] ?: return
    if (collected.put(id, node) != null) return
    node.slots.values.flatten().forEach(::visit)
  }
  // Tree order, not the order they were clicked. `paste` walks `rootNodeIds` and lays them down
  // in sequence, so a selection built by shift-clicking D and then B would arrive as C, D, B and
  // silently reorder what was copied.
  val ordered = nodeIds.sortedBy(treeIndex())
  ordered.forEach(::visit)
  return EditorClipboard(rootNodeIds = ordered, nodes = collected)
}

/**
 * The same clipboard, told where the cut took its roots from.
 *
 * Read before the delete is applied, off the document the subtrees are still in. The run's first
 * root in tree order carries the position, and the sibling behind it has to be one nothing is
 * cutting — cutting `B, C` out of `A, B, C` puts the run back after `A`, not after the `B` that
 * left with it.
 */
private fun EditorClipboard.withOriginOf(
  document: UiBuilderDocument,
  roots: List<String>,
): EditorClipboard {
  val first = rootNodeIds.firstOrNull() ?: return this
  val parent = document.location(first) ?: return this
  val siblings = document.children(parent)
  val before = siblings.take(siblings.indexOf(first).coerceAtLeast(0))
  return copy(origin = EditorClipboardOrigin(parent, before.lastOrNull { it !in roots }))
}

/** Position in the flattened tree, for the operations whose result is an order. */
private fun UiBuilderDocument.treeIndex(): (String) -> Int {
  val order = mutableMapOf<String, Int>()
  fun visit(nodeId: String) {
    order[nodeId] = order.size
    nodes[nodeId]?.slots?.values?.flatten()?.forEach(::visit)
  }
  roots.forEach(::visit)
  return { order[it] ?: Int.MAX_VALUE }
}

/**
 * An id [preferred] that this document does not already hold.
 *
 * Pasting twice from one clipboard would otherwise produce the same id twice, and the second insert
 * would be rejected as a duplicate — so the paste that looks identical to the first silently fails.
 *
 * [client] because the sequence is *local*. Two people wrapping a selection in a Row as their own
 * first operation both reach sequence 1 and both propose `editor-layout-row-001`, for different
 * selections. Operation ids are already qualified; node ids were not, so the server accepted one
 * and rejected the other as a duplicate insertion — losing that person's edit, in the one situation
 * this editor exists to handle well.
 *
 * The qualifier is the **operation id prefix**, not `clientId`. `clientId` defaults to a constant
 * and the live host reads it from configuration, so two browsers can carry the same one; the
 * operation prefix is `clientId` plus a per-page nonce, which is precisely what already makes
 * operation ids unique between clients. Long ids are the price, and they are generated names shown
 * beside a human label rather than read on their own.
 */
private fun UiBuilderDocument.freshNodeId(
  preferred: String,
  client: String,
  sequence: Int,
): String {
  val qualifier = client.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "c" }
  val numbered = "$preferred-$qualifier-${sequence.toString().padStart(3, '0')}"
  if (numbered !in nodes) return numbered
  var suffix = 2
  while ("$numbered-$suffix" in nodes) suffix++
  return "$numbered-$suffix"
}

/**
 * The properties that identify an *instance* rather than name content.
 *
 * Taken from what the Compose exporter does with them: `stableKey`, falling back to
 * `scrollStateKey`, becomes a lazy item's `key(…)`, and two siblings sharing one is a runtime
 * failure in Compose rather than a cosmetic clash. `assetKey` and `iconKey` look similar and are
 * the opposite — they name catalog content that every copy should keep — which is why this is the
 * exporter's list and not a suffix rule.
 */
private val INSTANCE_IDENTITY_PROPERTIES = setOf("stableKey", "scrollStateKey")

/**
 * Every string a copy's identity must not collide with: the node ids, and the identity keys already
 * in use.
 *
 * The ids alone are not enough. A copy takes its own node id as its `stableKey`, and that key is an
 * arbitrary string a sibling may already hold — duplicating `card` next to a node keyed
 * `card-copy-001` produced two siblings under one key, which the exporter turns into two
 * `key("card-copy-001")` groups and Compose rejects at runtime. The values are read with a safe
 * cast because a document arriving over the wire may hold anything here.
 */
private fun UiBuilderDocument.takenIdentities(): MutableSet<String> =
  (nodes.keys +
      nodes.values.flatMap { node ->
        INSTANCE_IDENTITY_PROPERTIES.mapNotNull { name ->
          ((node.properties[name] as? JsonObject)?.get("value") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        }
      })
    .toMutableSet()

/** An id nothing in the document and nothing already allocated in this batch is using. */
private fun freshCopyId(preferred: String, taken: MutableSet<String>): String {
  var candidate = preferred
  var suffix = 2
  while (!taken.add(candidate)) {
    candidate = "$preferred-$suffix"
    suffix++
  }
  return candidate
}

private fun Map<String, UiBuilderNode>.appendDuplicateSubtree(
  sourceNodeId: String,
  copyNodeId: String,
  parent: ParentSlot?,
  afterNodeId: String?,
  operations: MutableList<DesignOperation>,
  taken: MutableSet<String>,
) {
  val source = getValue(sourceNodeId)
  operations +=
    DesignOperation.InsertNode(
      node =
        source.copy(
          id = copyNodeId,
          // A copy is a new instance, so it gets a new identity. Cloning `stableKey` put two
          // children in one lazy slot under the same `key(…)`, which Compose refuses at runtime,
          // and cloning `scrollStateKey` made two scroll containers share a position.
          properties = source.properties.withFreshInstanceIdentity(copyNodeId),
          slots = source.slots.mapValues { emptyList() },
        ),
      parent = parent,
      afterNodeId = afterNodeId,
    )
  source.slots.forEach { (slot, children) ->
    var previousCopyId: String? = null
    children.forEach { childId ->
      // Allocated, not concatenated. A subtree holding a sibling `x-y` and a grandchild `y` under
      // `x` produced one id twice, and a concatenation can land on an existing node even when the
      // root it hangs from is fresh — either way the collaboration reducer rejects the whole paste
      // as a duplicate.
      val childCopyId = freshCopyId("$copyNodeId-${childId.replace('/', '-')}", taken)
      appendDuplicateSubtree(
        sourceNodeId = childId,
        copyNodeId = childCopyId,
        parent = ParentSlot(copyNodeId, slot),
        afterNodeId = previousCopyId,
        operations = operations,
        taken = taken,
      )
      previousCopyId = childCopyId
    }
  }
}

private fun JsonObject.withFreshInstanceIdentity(copyNodeId: String): JsonObject {
  val present = INSTANCE_IDENTITY_PROPERTIES.filter { it in this }
  if (present.isEmpty()) return this
  return JsonObject(
    toMap() +
      present.associateWith { name ->
        val existing = this[name] as? JsonObject
        literal(
          existing?.get("type")?.primitiveOrNull()?.contentOrNull ?: "string",
          JsonPrimitive(copyNodeId),
        )
      }
  )
}

private fun JsonElement.asLiteral(propertyName: String): JsonObject {
  if (this is JsonNull) return literal("string", JsonPrimitive(""))
  val primitive = jsonPrimitive
  val type =
    when {
      primitive.booleanOrNull != null -> "bool"
      primitive.doubleOrNull != null -> "float"
      propertyName.endsWith("Color") -> "color"
      propertyName in setOf("style", "layoutMode", "iconKey") -> "enum"
      else -> "string"
    }
  return literal(type, primitive)
}

private fun literal(type: String, value: JsonElement): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

/** One numeric edge of an object-valued property the editor knows how to author. */
internal data class EditorObjectEdge(
  val field: String,
  val label: String,
  val minimum: Double,
  val maximum: Double,
)

/**
 * The object-valued shapes the inspector can author, and the numeric edges each is made of.
 *
 * Closed on purpose. A property the catalog declares as `"object"` and this map does not name stays
 * unsupported, which is the safe answer: `itemSpans` is an arbitrary map of child id to span and
 * there is no honest four-field control for it.
 */
internal val EDITOR_OBJECT_VALUE_EDGES: Map<String, List<EditorObjectEdge>> =
  mapOf(
    "padding" to
      listOf(
        EditorObjectEdge("startDp", "start", 0.0, MAXIMUM_AUTHORED_EDGE_DP),
        EditorObjectEdge("topDp", "top", 0.0, MAXIMUM_AUTHORED_EDGE_DP),
        EditorObjectEdge("endDp", "end", 0.0, MAXIMUM_AUTHORED_EDGE_DP),
        EditorObjectEdge("bottomDp", "bottom", 0.0, MAXIMUM_AUTHORED_EDGE_DP),
      ),
    "adaptiveGrid" to
      listOf(
        EditorObjectEdge("minimumCellWidthDp", "minimum cell width", 1.0, MAXIMUM_AUTHORED_EDGE_DP)
      ),
  )

private const val MAXIMUM_AUTHORED_EDGE_DP = 4096.0

/**
 * The whole object value with one edge replaced, or null when the shape is not one this editor
 * authors.
 *
 * Every edge is written, not just the one that changed: the wire carries a `padding` as four
 * numbers and a value missing one of them is a different value, so an edge absent from the document
 * takes its own minimum rather than disappearing.
 */
private fun objectValueWithEdge(
  kind: String,
  existing: JsonObject?,
  edgeName: String,
  edgeValue: JsonElement,
): JsonObject? {
  val edges = EDITOR_OBJECT_VALUE_EDGES[kind] ?: return null
  if (edges.none { it.field == edgeName }) return null
  val carried = existing?.takeIf { it["type"]?.primitiveOrNull()?.contentOrNull == kind }
  return JsonObject(
    buildMap {
      put("type", JsonPrimitive(kind))
      edges.forEach { edge ->
        put(
          edge.field,
          if (edge.field == edgeName) edgeValue
          else carried?.get(edge.field) ?: JsonPrimitive(edge.minimum),
        )
      }
    }
  )
}

private sealed interface PropertyDraft {
  data class Valid(val value: JsonElement) : PropertyDraft

  data class Invalid(val message: String) : PropertyDraft
}

private fun EditorPropertyField.parseDraft(draft: String): PropertyDraft {
  return when (control) {
    EditorPropertyControl.Text -> PropertyDraft.Valid(JsonPrimitive(draft))
    EditorPropertyControl.Boolean ->
      draft.toBooleanStrictOrNull()?.let { PropertyDraft.Valid(JsonPrimitive(it)) }
        ?: PropertyDraft.Invalid("$label must be true or false")
    EditorPropertyControl.Enum ->
      if (draft in choices) PropertyDraft.Valid(JsonPrimitive(draft))
      else PropertyDraft.Invalid("$label must be one of ${choices.joinToString()}")
    EditorPropertyControl.Number -> {
      val bounds = numberBounds ?: return PropertyDraft.Invalid("$label has no safe editor range")
      val number = draft.toDoubleOrNull()
      when {
        number == null || !number.isFinite() -> PropertyDraft.Invalid("$label must be a number")
        bounds.integer && number % 1.0 != 0.0 ->
          PropertyDraft.Invalid("$label must be a whole number")
        number < bounds.minimum || number > bounds.maximum ->
          PropertyDraft.Invalid(
            "$label must be ${bounds.minimum.format()}..${bounds.maximum.format()}"
          )
        bounds.integer -> PropertyDraft.Valid(JsonPrimitive(number.toLong()))
        else -> PropertyDraft.Valid(JsonPrimitive(number))
      }
    }
    EditorPropertyControl.Color -> {
      val color = draft.trim()
      if (color.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) || color in choices)
        PropertyDraft.Valid(JsonPrimitive(color))
      else
        PropertyDraft.Invalid("$label must be #RRGGBB, #AARRGGBB, or a listed Material color token")
    }
    EditorPropertyControl.Unsupported ->
      PropertyDraft.Invalid("$label cannot be safely edited from its catalog metadata")
  }
}

private fun EditorPropertyField.defaultEncodedType(): String =
  when (control) {
    EditorPropertyControl.Text -> "string"
    EditorPropertyControl.Boolean -> "bool"
    EditorPropertyControl.Number -> if (numberBounds?.integer == true) "int" else "float"
    EditorPropertyControl.Enum -> if (name == "style") "typographyToken" else "enum"
    EditorPropertyControl.Color -> "color"
    EditorPropertyControl.Unsupported -> "string"
  }

private fun PropertyCapability.typeNames(): Set<String> =
  when (jsonType) {
    is JsonArray -> jsonType.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
    else -> setOf(jsonType.jsonPrimitive.content)
  }

private fun PropertyCapability.numberBounds(typeNames: Set<String>): EditorNumberBounds? {
  if (typeNames != setOf("number") && typeNames != setOf("integer")) return null
  val editor = editor
  val minimum = editor?.minimum ?: return null
  val maximum = editor.maximum ?: return null
  val step = editor.step ?: if (typeNames == setOf("integer")) 1.0 else 0.1
  if (!minimum.isFinite() || !maximum.isFinite() || !step.isFinite()) return null
  if (minimum > maximum || step <= 0.0) return null
  return EditorNumberBounds(minimum, maximum, step, typeNames == setOf("integer"))
}

private fun String.humanLabel(): String =
  replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

private fun Double.format(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

private fun UiBuilderDocument.location(nodeId: String): ParentSlot? {
  nodes.values.forEach { parent ->
    parent.slots.forEach { (slot, children) ->
      if (nodeId in children) return ParentSlot(parent.id, slot)
    }
  }
  return null
}

/**
 * The primitive a wire value holds, or null where it holds an array or an object.
 *
 * `jsonPrimitive` throws on anything else, and everything read through these accessors came off the
 * wire: a property encoded as `{"type": "string", "value": []}` is exactly what
 * `INVALID_PROPERTY_TYPE` reports, so the inspector must be able to draw the document that holds
 * one rather than take the editor down before the Issues panel can name it.
 */
private fun JsonElement?.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

/**
 * [nodeId] and everything under it.
 *
 * Guarded against a cycle in the document rather than trusting it not to hold one: this is asked
 * about a document that is being edited, and a cycle is one of the things the inspector reports
 * rather than one the editor may assume away.
 */
private fun UiBuilderDocument.subtreeOf(nodeId: String): Set<String> {
  val collected = mutableSetOf<String>()
  val pending = ArrayDeque(listOf(nodeId))
  while (pending.isNotEmpty()) {
    val next = pending.removeFirst()
    if (!collected.add(next)) continue
    nodes[next]?.slots?.values?.forEach(pending::addAll)
  }
  return collected
}

private fun UiBuilderDocument.children(parent: ParentSlot?): List<String> =
  if (parent == null) roots else nodes.getValue(parent.nodeId).slots[parent.slot].orEmpty()
