package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.ComponentDriftFinding
import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.RedoCommand
import ee.schimke.composeai.uibuilder.RemoteComposeSource
import ee.schimke.composeai.uibuilder.UndoCommand
import ee.schimke.composeai.uibuilder.export.StateSelection
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import ee.schimke.composeai.uibuilder.reference.ReferenceMarkupKind
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlaySettings
import ee.schimke.composeai.uibuilder.reference.ReferenceTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

sealed interface UiBuilderEditorEvent {
  data class SearchCatalog(val query: String) : UiBuilderEditorEvent

  /** Opens or shuts one family heading in the insert panel. */
  data class ToggleCatalogGroup(val group: String) : UiBuilderEditorEvent

  /** Shows or hides one component's variants in the insert panel. */
  data class ToggleCatalogComponent(val componentId: String) : UiBuilderEditorEvent

  /**
   * Opens every shelf in the insert panel — the way back out of a corner of the catalog.
   *
   * It does not also close every component: the variants you opened are what you were looking at,
   * and "show me everything" is a statement about the shelves.
   */
  data object ExpandAllCatalogGroups : UiBuilderEditorEvent

  /** Show or hide one component pack's shelf in the insert panel. */
  data class TogglePack(val packId: String) : UiBuilderEditorEvent

  /** The packs the host remembered as on, applied when the design opens. */
  data class SetEnabledPacks(val packIds: Set<String>) : UiBuilderEditorEvent

  /**
   * What a read of the project's component library found, for the Issues panel to say.
   *
   * An event rather than a parameter of `problems` because the fetch is the host's — it needs a
   * design id, a base URL and a token, none of which the reducer has or should acquire.
   */
  data class SetComponentDrift(val findings: List<ComponentDriftFinding>) : UiBuilderEditorEvent

  /** Explicit recovery for a property a catalog removed: null drops it, otherwise moves it. */
  data class ResolveUndeclaredProperty(
    val nodeId: String,
    val property: String,
    val replacement: String? = null,
  ) : UiBuilderEditorEvent

  data class SelectNode(val nodeId: String) : UiBuilderEditorEvent

  /** Narrows the layers panel. Purely a view over the document; it writes nothing. */
  data class SearchLayers(val query: String) : UiBuilderEditorEvent

  /**
   * Switches one design pane on, or off.
   *
   * The only family of editor events that stays live with the authoring canvas switched off.
   * Everything else is suppressed, because the chords that select and delete would otherwise still
   * be editing a document nobody can see themselves editing — and a pane toggle is the way back.
   *
   * Switching off the last open pane is refused rather than obeyed: see [EditorPane].
   */
  data class TogglePane(val pane: EditorPane) : UiBuilderEditorEvent

  /** Shows or hides the generated-Kotlin pane under the canvas. */
  data object ToggleCodePane : UiBuilderEditorEvent

  /**
   * Opens the selection's quick editor, or closes it. Only a single selection has one: the card
   * edits one node's values.
   */
  data object ToggleQuickEditor : UiBuilderEditorEvent

  /** Opens the quick editor, for a route that is about to hand it the caret. */
  data object ShowQuickEditor : UiBuilderEditorEvent

  /** Closes the quick editor; the selection and the Properties panel are untouched. */
  data object HideQuickEditor : UiBuilderEditorEvent

  /** Shows or hides the strip of revision thumbnails under the canvas. */
  data object ToggleHistoryBar : UiBuilderEditorEvent

  /**
   * Looks at one revision, or comes back to the design with null.
   *
   * Also the way out of a comparison: picking a revision replaces both ends, because a third click
   * on a two-ended control otherwise has to guess which end the person meant.
   */
  data class ShowRevision(val revision: Int?) : UiBuilderEditorEvent

  /**
   * Adds the other end of a comparison, against whatever [ShowRevision] is looking at.
   *
   * Ignored while nothing is being looked at: there is no comparison to add an end to, and the
   * living design is not the second end — the strip's newest row is, and it names a revision.
   */
  data class CompareRevision(val revision: Int) : UiBuilderEditorEvent

  /** Sets the whole open-pane set at once. An empty set is ignored — see [EditorPane]. */
  data class ShowPanes(val panes: Set<EditorPane>) : UiBuilderEditorEvent

  /**
   * Chooses which host container a Wear widget design is framed in.
   *
   * A view over the design rather than an edit to it — see
   * [UiBuilderEditorState.wearWidgetHostShape].
   */
  data class ShowWearWidgetHostShape(val shape: WearWidgetHostShape) : UiBuilderEditorEvent

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

  /**
   * Insert a catalog component, optionally as one of its declared variants.
   *
   * [variant] rides this event rather than being a second insert-then-edit, because the two are not
   * the same thing to a collaborator: an insert followed by a property write is a filled card
   * appearing on their canvas and turning outlined a frame later, and a rejected second operation
   * would leave the wrong one there for good. One event, one batch, one revision.
   *
   * [afterNodeId] is where a canvas drop lands — the seam the marker was drawn at, which is a fact
   * about the pointer and not about the selection. Null is the long-standing Add: after whatever
   * the slot already holds. A name the slot no longer holds falls back to that, the same way a
   * stale variant does: the geometry a plan was resolved against can age while the drag is in
   * flight, and an edit that would otherwise be legal must never be refused by an out-of-date
   * neighbour.
   */
  data class InsertComponent(
    val componentId: String,
    val target: ParentSlot,
    val variant: EditorCatalogVariant? = null,
    val afterNodeId: String? = null,
  ) : UiBuilderEditorEvent

  /**
   * Add a top-level item beside the design rather than into the selection — see
   * [UiBuilderEditorState.addBeside].
   *
   * It names no target, and cannot: where the item lands depends on whether the design already has
   * a board to append into or has to be wrapped in one, and both of those are decided from the
   * document at the moment the command is built. [InsertComponent] names a target because the
   * insert panel showed the author that target before they pressed it; there is nothing equivalent
   * to show here, because "beside everything else" is the whole of the destination.
   */
  data class InsertComponentBeside(
    val componentId: String,
    val variant: EditorCatalogVariant? = null,
  ) : UiBuilderEditorEvent

  /** Flips [UiBuilderEditorState.addBeside]: does an Add fill a slot, or start an item? */
  data object ToggleAddBeside : UiBuilderEditorEvent

  /**
   * Pin a component to the top of the insert panel, or take it off.
   *
   * The first press materialises the catalog's defaults and then flips the one pressed — see
   * [UiBuilderEditorState.pinnedComponents] for why "not chosen" and "chose none" are different.
   */
  data class TogglePinnedComponent(val componentId: String) : UiBuilderEditorEvent

  /** Switches one unstored variant axis of the strip on or off. */
  data class ToggleVariantAxis(val axis: EditorVariantAxis) : UiBuilderEditorEvent

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

  /**
   * Put one layout modifier on a node, or take it back off.
   *
   * The whole chain is rewritten either way, because [DesignOperation.SetModifiers] is whole-list:
   * a chain is order-dependent and its elements have no identity to address. Added modifiers go on
   * the end, which is the order somebody reading the exported Kotlin will see them in.
   */
  data class ToggleModifier(val nodeId: String, val type: String) : UiBuilderEditorEvent

  /**
   * Size a node: each axis to hug its content, fill its parent, or a fixed number of dp, and a null
   * axis left as it is.
   *
   * What a resize handle, a double-click on one and the size chips all send. Both axes in one event
   * so a corner drag is one edit and one undo step. The reducer rewrites only the modifiers that
   * decide each axis — see `resizedModifierChain` — so a padding or a clip survives it.
   */
  data class ResizeNode(
    val nodeId: String,
    val width: EditorSizing? = null,
    val height: EditorSizing? = null,
  ) : UiBuilderEditorEvent

  /**
   * Give one number inside one modifier a new value.
   *
   * The whole chain is rewritten, because that is the only shape the wire has; everything else on
   * the node — including the rest of that modifier — is carried through untouched.
   */
  data class SetModifierValue(
    val nodeId: String,
    val type: String,
    val field: String,
    val draft: String,
    val modifierIndex: Int? = null,
  ) : UiBuilderEditorEvent

  data class SetStateVariable(val name: String, val declaration: JsonObject) : UiBuilderEditorEvent

  data class RemoveStateVariable(val name: String) : UiBuilderEditorEvent

  data class SetStateSelection(val nodeId: String, val selection: StateSelection?) :
    UiBuilderEditorEvent

  data class SetEventBinding(val nodeId: String, val event: String, val actions: JsonArray) :
    UiBuilderEditorEvent

  data class AppendAction(
    val nodeId: String,
    val event: String,
    val action: EditorStateAction,
    val index: Int? = null,
  ) : UiBuilderEditorEvent

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
   * Insert the node and its initial handler atomically. Existing handlers can also be edited with
   * [SetEventBinding].
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

  /**
   * The same insert, beside the design — see [InsertComponentBeside], whose reason for naming no
   * target this shares: where a top-level item lands is decided from the document when the command
   * is built, and for this one that is after a fetch has come back.
   */
  data class InsertRemoteComposeDocumentBeside(
    val source: RemoteComposeSource,
    val documentBase64: String,
  ) : UiBuilderEditorEvent

  data object CopySelected : UiBuilderEditorEvent

  /**
   * Snap every authored dp value in scope — the selection's subtree, else the design — to the 4dp
   * grid, as one command. See [UiBuilderEditorReducer.tidyPlan].
   */
  data object Tidy : UiBuilderEditorEvent

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

  /** Open another design in this project; it writes no local state. */
  data class Navigate(val pageKey: String, override val variable: String = "") : EditorStateAction

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
internal fun EditorStateAction.valueRefusal(declaration: JsonObject?): String? {
  val raw =
    when (this) {
      is EditorStateAction.Navigate -> return null
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
internal enum class StateKind {
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
internal fun declaredStateKind(declaration: JsonObject?): StateKind {
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
internal fun declaredNullable(declaration: JsonObject?): Boolean =
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
internal fun typedStateValue(raw: String, declaration: JsonObject?): JsonPrimitive =
  when (declaredStateKind(declaration)) {
    StateKind.BOOLEAN -> JsonPrimitive(raw.toBooleanStrictOrNull() ?: false)
    StateKind.INTEGER -> JsonPrimitive(raw.toIntOrNull() ?: 0)
    StateKind.DECIMAL -> JsonPrimitive(raw.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0)
    StateKind.STRING -> JsonPrimitive(raw)
  }

internal fun EditorStateAction.encoded(declaration: JsonObject?): JsonObject {
  fun typed(raw: String): JsonPrimitive = typedStateValue(raw, declaration)
  return when (this) {
    is EditorStateAction.Navigate ->
      JsonObject(
        mapOf("type" to JsonPrimitive("navigatePage"), "pageKey" to JsonPrimitive(pageKey))
      )
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

/** Pure editor interaction reducer. Every document mutation delegates to CollaborationReducer. */
sealed interface EditorSubmission {
  data class Batch(val command: DesignCommand) : EditorSubmission

  data class Undo(val command: UndoCommand) : EditorSubmission

  data class Redo(val command: RedoCommand) : EditorSubmission
}
