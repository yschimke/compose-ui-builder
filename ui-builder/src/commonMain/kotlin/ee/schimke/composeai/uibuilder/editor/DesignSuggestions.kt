package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.documentsBackTo
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument

/**
 * Edits somebody — usually an agent — has proposed for this design and nobody has decided on yet.
 *
 * Suggestion mode, in the sense a document editor has one: the proposal is shown on the design, and
 * a person accepts or rejects it. Under the hood a suggestion is a short-lived **branch** of the
 * design (`docs/design/UI_BUILDER_BRANCHES.md` → Suggestions): accepting it is the branch's
 * replay-merge, rejecting it archives the branch. So nothing here is a second merge — the editor
 * only shows what the host reports and forwards the person's decision.
 *
 * Like [DesignReview] it is kept beside the design and never part of it: a suggestion waiting does
 * not move the design's revision, and only an accept does.
 */
data class DesignSuggestions(
  /** Open suggestions, newest first, as the host keeps them. */
  val open: List<DesignSuggestion> = emptyList(),
  /** What the last accept or reject on this page did, said under the list. */
  val lastOutcome: DesignSuggestionOutcome? = null,
)

data class DesignSuggestion(
  /** The suggestion's own id — a branch id the host can open like any design. */
  val suggestionId: String,
  /** What it proposes, in its author's words: the branch's name. */
  val summary: String,
  /** Who proposed it: the branch's owner. */
  val proposedBy: String,
  val displayName: String? = null,
  val kind: DesignCommentAuthorKind = DesignCommentAuthorKind.Agent,
  val createdAtEpochMillis: Long = 0,
  /** The design revision the suggestion was made against. */
  val forkRevision: Long,
  /** The proposed commands, in order. What a partial accept names. */
  val operationIds: List<String> = emptyList(),
  /**
   * The design as the suggestion would leave it — its own head — or null until the host has fetched
   * it. Without it the row still offers Accept and Reject; it just has no picture and no diff.
   */
  val document: UiBuilderDocument? = null,
) {
  /** The name to show: the display name when there is one, the account otherwise. */
  val who: String
    get() = displayName?.takeIf { it.isNotBlank() } ?: proposedBy
}

/** The answer to an accept or reject, as the editor reports it. */
sealed interface DesignSuggestionOutcome {
  val suggestionId: String
  val summary: String

  /** Every proposed command landed. [overwrites] are the last-writer-wins notices, in words. */
  data class Accepted(
    override val suggestionId: String,
    override val summary: String,
    val landed: Int,
    val atRevision: Long?,
    val overwrites: List<String> = emptyList(),
    val skipped: Int = 0,
  ) : DesignSuggestionOutcome

  data class Rejected(override val suggestionId: String, override val summary: String) :
    DesignSuggestionOutcome

  /**
   * The accept was refused and **nothing** landed — the merge is all or nothing. [reason] says
   * which command stopped it and why ("the node it edits was deleted"), so the person can accept
   * the rest or reject it.
   */
  data class Refused(
    override val suggestionId: String,
    override val summary: String,
    val reason: String,
    /** The command that stopped it, when the host named one. */
    val operationId: String? = null,
  ) : DesignSuggestionOutcome
}

/** One line for an outcome, for the panel and for a screen reader. */
fun DesignSuggestionOutcome.describe(): String =
  when (this) {
    is DesignSuggestionOutcome.Accepted ->
      buildString {
        append("Accepted “$summary”: ")
        append(if (landed == 1) "1 change" else "$landed changes")
        atRevision?.let { append(" landed at r$it") } ?: append(" landed")
        if (skipped > 0) append(", $skipped left out")
        append('.')
        if (overwrites.isNotEmpty()) {
          append(" It overwrote: ")
          append(overwrites.joinToString("; "))
          append('.')
        }
      }
    is DesignSuggestionOutcome.Rejected -> "Rejected “$summary”. The design is unchanged."
    is DesignSuggestionOutcome.Refused ->
      "Could not accept “$summary”: $reason Nothing was applied; accept part of it or reject it."
  }

/**
 * What [suggestion] changes, as a document diff — or null until its document has arrived.
 *
 * Compared against the design **at the revision the suggestion was made against** when this editor
 * can still rebuild it, so the diff is the suggestion's own change and not "the suggestion, minus
 * everything that happened on the design since". Where that revision is out of reach (a reopened
 * session, a suggestion made before this page loaded and the history since trimmed) it falls back
 * to the design as it is now, which is what accepting would be compared against anyway.
 */
fun suggestionDiff(
  state: UiBuilderEditorState,
  suggestion: DesignSuggestion,
  catalog: CapabilityCatalog,
): EditorRevisionDiff? {
  val after = suggestion.document ?: return null
  val fork = suggestion.forkRevision.toInt()
  val current = state.document.revision
  val before =
    if (fork in 0..current) state.collaboration.documentsBackTo(fork)[fork] ?: state.document
    else state.document
  return documentDiff(before, after, catalog)
}

/**
 * True when the design has moved on since [suggestion] was made, so accepting replays it onto a
 * newer design than the one it was drawn on.
 */
fun DesignSuggestion.isBehind(state: UiBuilderEditorState): Boolean =
  forkRevision < state.document.revision
