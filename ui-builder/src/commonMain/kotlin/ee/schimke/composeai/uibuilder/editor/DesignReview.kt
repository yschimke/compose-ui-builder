package ee.schimke.composeai.uibuilder.editor

/**
 * The review side of a design as the host reports it: who approved or rejected which revision. Kept
 * beside the design like [DesignCommentBoard], so a verdict does not move the revision. A verdict
 * here wakes an agent waiting in `ui_builder_await_decision`.
 */
data class DesignReview(
  /** Oldest first, as the host keeps them. */
  val decisions: List<DesignReviewDecision> = emptyList()
) {
  /** The newest verdict on [revision], or null when nobody has given one. */
  fun latestFor(revision: Long): DesignReviewDecision? = decisions.lastOrNull {
    it.revision == revision
  }

  /** The newest verdict on any revision, for "r3 was approved; nobody has looked at r4 yet". */
  val latest: DesignReviewDecision?
    get() = decisions.lastOrNull()
}

data class DesignReviewDecision(
  val revision: Long,
  val verdict: DesignReviewVerdict,
  val decidedBy: String,
  val displayName: String? = null,
  val kind: DesignCommentAuthorKind = DesignCommentAuthorKind.Human,
  val note: String? = null,
  val decidedAtEpochMillis: Long = 0,
) {
  /** The name to show: the display name when there is one, the account otherwise. */
  val who: String
    get() = displayName?.takeIf { it.isNotBlank() } ?: decidedBy
}

enum class DesignReviewVerdict(val wire: String, val label: String) {
  Approve("approve", "Approved"),
  Reject("reject", "Changes requested");

  companion object {
    fun ofWire(wire: String): DesignReviewVerdict? = entries.firstOrNull { it.wire == wire }
  }
}
