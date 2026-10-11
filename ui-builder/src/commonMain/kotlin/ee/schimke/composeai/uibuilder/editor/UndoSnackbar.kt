package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.CommandOutcome

/**
 * How long the undo offer stays up after a destructive edit. Longer than Material's short duration
 * (4s): the person has to notice that something went, read what, and reach for the action.
 */
internal const val UNDO_SNACKBAR_MILLIS = 5_000L

/**
 * The sentence the undo snackbar shows after [event] took [before] to [after], or null when the
 * event was not a destructive edit that landed.
 *
 * Only delete, cut and unwrap qualify. Each one removes something from the canvas, which is the
 * case undo exists for and the case a person is least sure what just happened: a property edit is
 * still on screen to look at, a deleted subtree is not. A refused edit, a selection change or a
 * delete with nothing selected leaves the document alone and offers nothing to undo.
 *
 * Labels are read from [before], because the nodes they name no longer exist in [after]. [label]
 * names one node in that state; [UiBuilderEditorReducer.nodeKindLabel] is the editor's answer.
 */
internal fun undoSnackbarMessage(
  event: UiBuilderEditorEvent,
  before: UiBuilderEditorState,
  after: UiBuilderEditorState,
  label: (nodeId: String) -> String,
): String? {
  val verb =
    when (event) {
      UiBuilderEditorEvent.DeleteSelected -> "Deleted"
      UiBuilderEditorEvent.CutSelected -> "Cut"
      UiBuilderEditorEvent.UnwrapSelection -> "Unwrapped"
      else -> return null
    }
  // Accepted *by this event*: lastOutcome survives events that form no command, so a stale
  // Accepted from an earlier edit must not pass for this one.
  if (after.operationSequence == before.operationSequence) return null
  if (after.lastOutcome !is CommandOutcome.Accepted) return null
  // What the event took off the canvas, as the reducer chose it: the selection's roots for delete
  // and cut (a descendant selected with its ancestor went with it and is not counted twice), the
  // one container for unwrap, whose children stay.
  val removed =
    if (event == UiBuilderEditorEvent.UnwrapSelection)
      listOfNotNull(before.selection.singleOrNull())
    else before.selectionRoots()
  if (removed.isEmpty() || removed.any(after.document.nodes::containsKey)) return null
  return if (removed.size == 1) "$verb ${label(removed.single())}"
  else "$verb ${removed.size} items"
}

/**
 * Whether an edit should take a pending undo offer down: any change to the document. After it, the
 * action would undo that later change rather than the delete the snackbar names — and an undo by
 * keyboard or toolbar has already done what the snackbar offered.
 */
internal fun undoSnackbarInvalidatedBy(
  before: UiBuilderEditorState,
  after: UiBuilderEditorState,
): Boolean = after.document.revision != before.document.revision
