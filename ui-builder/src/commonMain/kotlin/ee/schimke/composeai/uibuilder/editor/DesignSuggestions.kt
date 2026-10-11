package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.documentsBackTo
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument

/**
 * Edits someone (usually an agent) has proposed for this design, awaiting a decision.
 *
 * Each suggestion is a short-lived branch of the design (`docs/design/UI_BUILDER_BRANCHES.md` →
 * Suggestions): accepting is the branch's replay-merge, rejecting archives it. The editor only
 * shows what the host reports and forwards the decision. Kept beside the design like
 * [DesignReview]; only an accept moves the revision.
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
   * The design as the suggestion would leave it, or null until fetched (the row still offers Accept
   * and Reject).
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
   * The accept was refused and nothing landed (all or nothing). [reason] names the blocking command
   * and why.
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
 * What [suggestion] changes as a document diff, or null until its document arrives. Compared
 * against the design at the suggestion's base revision when rebuildable, so later design edits are
 * not counted; otherwise against the current design.
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
 * newer design.
 */
fun DesignSuggestion.isBehind(state: UiBuilderEditorState): Boolean =
  forkRevision < state.document.revision
