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

  /** Opens every shelf in the insert panel; expanded components are left as they are. */
  data object ExpandAllCatalogGroups : UiBuilderEditorEvent

  /** Show or hide one component pack's shelf in the insert panel. */
  data class TogglePack(val packId: String) : UiBuilderEditorEvent

  /** The packs the host remembered as on, applied when the design opens. */
  data class SetEnabledPacks(val packIds: Set<String>) : UiBuilderEditorEvent

  /**
   * What a read of the project's component library found. An event because the fetch needs host
   * credentials the reducer does not have.
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
   * Switches one design pane on or off. The only editing event still live with the canvas off,
   * since it is the way back; switching off the last pane is refused.
   */
  data class TogglePane(val pane: EditorPane) : UiBuilderEditorEvent

  /** Shows or hides the generated-Kotlin pane under the canvas. */
  data object ToggleCodePane : UiBuilderEditorEvent

  /** Opens or closes the quick editor. Single selection only. */
  data object ToggleQuickEditor : UiBuilderEditorEvent

  /** Opens the quick editor, for a route that is about to hand it the caret. */
  data object ShowQuickEditor : UiBuilderEditorEvent

  /** Closes the quick editor; the selection and the Properties panel are untouched. */
  data object HideQuickEditor : UiBuilderEditorEvent

  /** Shows or hides the strip of revision thumbnails under the canvas. */
  data object ToggleHistoryBar : UiBuilderEditorEvent

  /**
   * Looks at one revision, or returns to the design with null. Also ends a comparison by replacing
   * both ends.
   */
  data class ShowRevision(val revision: Int?) : UiBuilderEditorEvent

  /**
   * Adds the other end of a comparison against [ShowRevision]; ignored while nothing is being
   * looked at.
   */
  data class CompareRevision(val revision: Int) : UiBuilderEditorEvent

  /** Sets the whole open-pane set at once. An empty set is ignored — see [EditorPane]. */
  data class ShowPanes(val panes: Set<EditorPane>) : UiBuilderEditorEvent

  /**
   * Chooses the Wear widget host container; a view, not an edit. See
   * [UiBuilderEditorState.wearWidgetHostShape].
   */
  data class ShowWearWidgetHostShape(val shape: WearWidgetHostShape) : UiBuilderEditorEvent

  /** Selects every row the layers filter matched. */
  data object SelectAllMatches : UiBuilderEditorEvent

  /** Add or remove one node, leaving the rest of the selection alone (ctrl/⌘-click). */
  data class ToggleNode(val nodeId: String) : UiBuilderEditorEvent

  /** Select everything between the anchor and [nodeId] in tree order (shift-click). */
  data class ExtendSelectionTo(val nodeId: String) : UiBuilderEditorEvent

  /**
   * Insert a catalog component, optionally as one of its variants, as one batch and one revision.
   *
   * [afterNodeId] is the seam a canvas drop landed on; null, or a sibling that no longer exists,
   * appends after the slot's children so a stale drag plan never refuses an otherwise legal edit.
   */
  data class InsertComponent(
    val componentId: String,
    val target: ParentSlot,
    val variant: EditorCatalogVariant? = null,
    val afterNodeId: String? = null,
  ) : UiBuilderEditorEvent

  /**
   * Add a top-level item beside the design (see [UiBuilderEditorState.addBeside]). No target:
   * whether to append to a board or wrap the design in one is decided when the command is built.
   */
  data class InsertComponentBeside(
    val componentId: String,
    val variant: EditorCatalogVariant? = null,
  ) : UiBuilderEditorEvent

  /** Flips [UiBuilderEditorState.addBeside]: does an Add fill a slot, or start an item? */
  data object ToggleAddBeside : UiBuilderEditorEvent

  /**
   * Pin or unpin a component in the insert panel. The first press materialises the catalog
   * defaults; see [UiBuilderEditorState.pinnedComponents].
   */
  data class TogglePinnedComponent(val componentId: String) : UiBuilderEditorEvent

  /** Draws the editing canvas at the design's extent or at its device frame. */
  data class SetCanvasView(val view: EditorCanvasView) : UiBuilderEditorEvent

  /** Switches one unstored variant axis of the strip on or off. */
  data class ToggleVariantAxis(val axis: EditorVariantAxis) : UiBuilderEditorEvent

  data class MoveNode(
    val nodeId: String,
    val targetNodeId: String,
    val placeAfterTarget: Boolean,
  ) : UiBuilderEditorEvent

  /**
   * Move a node into [parent] after [afterNodeId] (null = first). Unlike [MoveNode], which steps
   * within the current slot, this can cross slots and parents, as layer-panel drags need.
   */
  data class MoveNodeInto(
    val nodeId: String,
    val parent: ParentSlot,
    val afterNodeId: String? = null,
  ) : UiBuilderEditorEvent

  data class CommitProperty(val nodeId: String, val property: String, val draft: String) :
    UiBuilderEditorEvent

  /**
   * Remove an optional property, so the node falls back to whatever its runtime defaults — a theme
   * host's `themeTextStyle` back to `bodyLarge`. A required or unset property is left alone.
   */
  data class ClearProperty(val nodeId: String, val property: String) : UiBuilderEditorEvent

  /** Make a property read a state variable instead of holding a literal. */
  data class BindPropertyToState(
    val nodeId: String,
    val property: String,
    val variable: String,
    /**
     * When set, bind as a `stateEquals` comparison (a boolean) rather than a bare read; the catalog
     * decides which shape a property accepts.
     */
    val equalsValue: String? = null,
  ) : UiBuilderEditorEvent

  /** Give a bound property a literal of its own again. */
  data class UnbindProperty(val nodeId: String, val property: String) : UiBuilderEditorEvent

  /**
   * Turn the selected subtree into a reusable component, replacing it with a placement whose texts
   * become parameters, so the screen draws the same. Null [name] uses
   * [UiBuilderEditorReducer.suggestedComponentName].
   */
  data class MakeComponent(val name: String? = null) : UiBuilderEditorEvent

  /** Place one of this design's own components, as the palette's "This design" shelf does. */
  data class InsertLocalComponent(
    val componentKey: String,
    val target: ParentSlot,
    val afterNodeId: String? = null,
  ) : UiBuilderEditorEvent

  /**
   * Rename one of a component's parameters: the key its body reads and every placement passes, so
   * the export writes `InboxEmail(sender = …)` rather than whatever the parameter was first called.
   */
  data class RenameComponentParameter(val componentKey: String, val from: String, val to: String) :
    UiBuilderEditorEvent

  /** Turn a placement back into ordinary layers holding the values it passed. */
  data class DetachPlacement(val nodeId: String) : UiBuilderEditorEvent

  /** Make one property of a component's body a parameter every placement passes. */
  data class ExposeComponentParameter(val nodeId: String, val property: String) :
    UiBuilderEditorEvent

  /** Stop [parameter] being one: the body keeps the first placement's value as its own. */
  data class InlineComponentParameter(val componentKey: String, val parameter: String) :
    UiBuilderEditorEvent

  /**
   * Place a component from the project's shared library: imported — body and `source` record —
   * unless this design already imported it, in which case the version it holds is placed again.
   */
  data class InsertLibraryComponent(
    val symbol: EditorLibrarySymbol,
    val target: ParentSlot,
    val afterNodeId: String? = null,
  ) : UiBuilderEditorEvent

  /**
   * Replace the design's copy of [componentKey] with [symbol], the version the library holds now:
   * the decision a drift report leaves to the design's owner.
   */
  data class UpdateLibraryComponent(val componentKey: String, val symbol: EditorLibrarySymbol) :
    UiBuilderEditorEvent

  /**
   * Record that the project library now holds [componentKey] as [source] — what a publish answers
   * with — so the design tracks it as a reference rather than a copy, and drift is reported.
   */
  data class RecordLibrarySource(val componentKey: String, val source: EditorLibrarySource) :
    UiBuilderEditorEvent

  /** Rename a component — the composable the export writes. */
  data class RenameLocalComponent(val componentKey: String, val name: String) : UiBuilderEditorEvent

  /**
   * Replace every placement of a design-only component with [catalogComponentId] and drop the local
   * definition. Arguments become same-named properties and layout modifiers carry across.
   */
  data class ReplaceLocalComponent(val componentKey: String, val catalogComponentId: String) :
    UiBuilderEditorEvent

  /**
   * Add or remove one layout modifier. The whole chain is rewritten ([DesignOperation.SetModifiers]
   * is whole-list); additions go on the end.
   */
  data class ToggleModifier(val nodeId: String, val type: String) : UiBuilderEditorEvent

  /**
   * Size a node per axis (hug, fill or fixed dp; null leaves the axis alone) as one undo step. Only
   * the modifiers that decide each axis are rewritten — see `resizedModifierChain`.
   */
  data class ResizeNode(
    val nodeId: String,
    val width: EditorSizing? = null,
    val height: EditorSizing? = null,
  ) : UiBuilderEditorEvent

  /** Give one number inside one modifier a new value; the rest of the chain is carried through. */
  data class SetModifierValue(
    val nodeId: String,
    val type: String,
    val field: String,
    val draft: String,
    val modifierIndex: Int? = null,
  ) : UiBuilderEditorEvent

  /**
   * Drive [target] from a tunable: the one named [into], or a new one seeded from the target's own
   * value and range. See [DesignTunable].
   */
  data class TuneTarget(val target: TunableTarget, val into: String? = null) : UiBuilderEditorEvent

  /** Stop [name] driving [target]; the target draws what it holds again. */
  data class UntuneTarget(val name: String, val target: TunableTarget) : UiBuilderEditorEvent

  /**
   * Replace the tunable called [name] with [tunable] — a rename, a new range or a new default. Its
   * slider is kept, moved into the new range.
   */
  data class EditTunable(val name: String, val tunable: DesignTunable) : UiBuilderEditorEvent

  data class RemoveTunable(val name: String) : UiBuilderEditorEvent

  /** Move [name]'s slider. Live only: the canvas redraws, the document is not touched. */
  data class SetTunedValue(val name: String, val value: Double) : UiBuilderEditorEvent

  /** Every slider back to its tunable's default. */
  data object ResetTunedValues : UiBuilderEditorEvent

  /**
   * Write every tunable's current value into the targets it drives — one undoable edit for the
   * properties and one for the modifiers — and make those values the new defaults.
   */
  data object ApplyTunables : UiBuilderEditorEvent

  /**
   * Set the design token [tokenId] to [value] — written into every property it binds, as one edit —
   * or, for a null [value], reset it to the design system's default.
   */
  data class ApplyDesignToken(val tokenId: String, val value: String?) : UiBuilderEditorEvent

  /** Put a slider on the number token [tokenId]: a tunable over every property it binds. */
  data class TuneDesignToken(val tokenId: String) : UiBuilderEditorEvent

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

  /** Insert a component and its initial click handler atomically. */
  data class InsertComponentWithAction(
    val componentId: String,
    val target: ParentSlot,
    val action: EditorStateAction,
  ) : UiBuilderEditorEvent

  /**
   * Insert a `remote-compose/document` together with its bytes, so collaborators never see the
   * empty-document error state. [documentBase64] is fetched by the host; the reducer only refuses
   * what will not decode.
   */
  data class InsertRemoteComposeDocument(
    val source: RemoteComposeSource,
    val documentBase64: String,
    val target: ParentSlot,
  ) : UiBuilderEditorEvent

  /** [InsertRemoteComposeDocument], beside the design — see [InsertComponentBeside]. */
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

  /**
   * A clipboard that arrived from outside this editor — another tab, window or IDE — to paste from
   * instead of the editor's own. Changes the editor, never the document.
   */
  data class ReceiveClipboard(val clipboard: EditorClipboard) : UiBuilderEditorEvent

  data object Undo : UiBuilderEditorEvent

  data object Redo : UiBuilderEditorEvent

  /**
   * Attach an imported picture as the base reference. Layout boxes are read from it here so every
   * path into the editor gets the same boxes.
   */
  data class AttachReference(val image: ReferenceImage) : UiBuilderEditorEvent

  /** Detach everything: the picture, its alignment, every placed piece and every mark. */
  data object ClearReference : UiBuilderEditorEvent

  /** Re-aim the overlay: mode, opacity, nudge, scale, split. */
  data class UpdateReferenceSettings(val settings: ReferenceOverlaySettings) : UiBuilderEditorEvent

  /** Show the overlay again in its last mode, or hide it. */
  data object ToggleReference : UiBuilderEditorEvent

  /**
   * Take the pointer for a markup tool, or hand it back to selection. Always explicit, never
   * inferred.
   */
  data class SelectReferenceTool(val tool: ReferenceTool) : UiBuilderEditorEvent

  /** The colour the next mark is drawn in. Existing marks keep the colour they were drawn in. */
  data class SelectMarkupColor(val colorArgb: Long) : UiBuilderEditorEvent

  /** The words the next text mark or image placeholder will carry. */
  data class SetMarkupText(val text: String) : UiBuilderEditorEvent

  /** One finished stroke, from the canvas. The id and the colour are assigned here. */
  data class AddReferenceMark(val kind: ReferenceMarkupKind, val points: List<Float>) :
    UiBuilderEditorEvent

  /** Rub out one mark. */
  data class RemoveReferenceMark(val markId: String) : UiBuilderEditorEvent

  /** Rub out the most recent mark. */
  data object UndoReferenceMark : UiBuilderEditorEvent

  data object ClearReferenceMarkup : UiBuilderEditorEvent

  /** Drop a picture onto the frame as a positioned piece rather than as the base. */
  data class PlaceReferencePiece(
    val image: ReferenceImage,
    /**
     * The catalog component this picture is of, set by the capture path; it is what makes the piece
     * promotable.
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
   * Replace a captured piece with the catalog component it pictures, inserted into [target] as a
   * catalog drag would. Pieces without provenance are refused rather than guessed.
   */
  data class PromoteReferencePiece(val pieceId: String, val target: ParentSlot) :
    UiBuilderEditorEvent

  /**
   * Replace the stack with a single pre-composed picture of it; composing needs a bitmap and
   * encoder the reducer does not have.
   */
  data class FlattenReference(val image: ReferenceImage) : UiBuilderEditorEvent

  /**
   * Move, resize and set the type size of a layer as one undo step — what the reference panel's
   * Apply sends (see `alignmentFor`). Moves are written as padding, falling back to `offset` only
   * where padding cannot express them.
   */
  data class AlignNodeToReference(
    val nodeId: String,
    val moveXDp: Int = 0,
    val moveYDp: Int = 0,
    val fontSizeSp: Float? = null,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
  ) : UiBuilderEditorEvent
}

/**
 * A state write a click can perform, narrowed to what the renderer executes so the builder cannot
 * author a dead button.
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
 * Why an action's value cannot be written to its variable, or null when it can. Refusing beats the
 * encoder's silent `false` / `0` fallbacks, which would store a value nobody asked for.
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
      // The exporter declares integer variables as `Int`.
      StateKind.INTEGER -> raw.toIntOrNull() != null
      // `kotlinLiteral` emits numbers verbatim, so NaN / Infinity would not compile.
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
 * A variable's kind, from `valueType` where present. `initialValue` alone is unsafe because
 * `booleanOrNull` / `longOrNull` parse quoted content too, so `isString` is checked first.
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
 * One authored value typed against its variable. `Int` because the exporter declares integers as
 * `Int`; callers check [EditorStateAction.valueRefusal] first, so the fallbacks are unreachable.
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

/** What the editor reducer submits to the collaboration layer. */
sealed interface EditorSubmission {
  data class Batch(val command: DesignCommand) : EditorSubmission

  data class Undo(val command: UndoCommand) : EditorSubmission

  data class Redo(val command: RedoCommand) : EditorSubmission
}
