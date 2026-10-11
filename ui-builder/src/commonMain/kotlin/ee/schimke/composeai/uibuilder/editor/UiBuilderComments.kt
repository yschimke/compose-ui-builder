package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.reference.ReferenceMark
import ee.schimke.composeai.uibuilder.reference.RestoredReference

/**
 * The discussion about a design, as the editor sees it.
 *
 * Separate from the design document (see the server's `ServeUiBuilderCommentStore`): comments must
 * never reach the export or advance the design's revision. These types mirror the host's stored
 * shapes without sharing a declaration (`:ui-builder` cannot depend on `:server`), and decode
 * tolerantly so new fields do not break a panel mid-release.
 */
data class DesignCommentBoard(
  /**
   * Rises on every accepted write, so a reconnect resumes rather than replays and a panel can tell
   * new activity from an echo.
   */
  val sequence: Long = 0,
  val threads: List<DesignCommentThread> = emptyList(),
) {
  val openThreads: List<DesignCommentThread>
    get() = threads.filterNot { it.resolved }

  /** Threads with somewhere to draw them: a pin needs a point on the frame or a node to sit on. */
  fun pinned(marks: List<ReferenceMark>): List<DesignCommentThread> = threads.filter {
    it.anchor?.pointOn(marks) != null || it.anchor?.nodeId != null
  }

  fun thread(threadId: String?): DesignCommentThread? = threadId?.let { id ->
    threads.firstOrNull { it.id == id }
  }
}

data class DesignCommentThread(
  val id: String,
  val anchor: DesignCommentAnchor? = null,
  val resolved: Boolean = false,
  val resolvedBy: String? = null,
  val comments: List<DesignComment> = emptyList(),
  val updatedAtEpochMillis: Long = 0,
) {
  /** The line the panel shows as the thread's title: the question, not the last word on it. */
  val opening: DesignComment?
    get() = comments.firstOrNull()
}

data class DesignComment(
  val id: String,
  val authorId: String,
  val displayName: String = "",
  val kind: DesignCommentAuthorKind = DesignCommentAuthorKind.Human,
  val body: String,
  val createdAtEpochMillis: Long = 0,
) {
  /** What to show beside the words, as one line: see [commentAuthorLabel]. */
  val author: String
    get() = commentAuthorLabel(this).text
}

/**
 * Who a comment is from: [name] is the poster's chosen label, [account] the account the host says
 * posted it, and [agent] draws the badge. Both are shown when they differ.
 */
data class CommentAuthorLabel(
  val name: String,
  /** The account behind [name] — `@login`, or a shortened raw id — or null when it adds nothing. */
  val account: String? = null,
  val agent: Boolean = false,
) {
  /** The whole label on one line, e.g. `Yuri · @yschimke` or `Review agent · agent`. */
  val text: String
    get() = listOfNotNull(name, account, AGENT_BADGE.takeIf { agent }).joinToString(" · ")
}

/**
 * The label for [comment]'s author: the display name with the posting account next to it.
 *
 * - `github:yschimke` named "Yuri" is `Yuri · @yschimke`; named "yschimke", or unnamed, is
 *   `@yschimke`.
 * - `agent:<fingerprint>` shows the agent badge in place of the account.
 * - Any other id is shown as is, shortened when long, and dropped when the name repeats it.
 * - A `collaborator-` pseudonym (public-link readers) shows the display name and no account.
 */
fun commentAuthorLabel(comment: DesignComment): CommentAuthorLabel {
  val id = comment.authorId.trim()
  val name = comment.displayName.trim()
  val agent = comment.kind == DesignCommentAuthorKind.Agent || id.startsWith(AGENT_ID_PREFIX)
  if (id.isEmpty() || id.startsWith(PSEUDONYM_ID_PREFIX)) {
    return CommentAuthorLabel(name.ifEmpty { UNNAMED_AUTHOR }, agent = agent)
  }
  if (id.startsWith(AGENT_ID_PREFIX)) {
    return CommentAuthorLabel(name.ifEmpty { "Agent" }, agent = true)
  }
  val login = id.removePrefix(GITHUB_ID_PREFIX).takeIf { id.startsWith(GITHUB_ID_PREFIX) }
  val account =
    if (!login.isNullOrEmpty()) "@$login"
    else if (id.length > MAX_RAW_ID_LENGTH) id.take(MAX_RAW_ID_LENGTH - 1) + "…" else id
  val repeatsAccount =
    name.isEmpty() ||
      name.equals(account, ignoreCase = true) ||
      name.equals(id, ignoreCase = true) ||
      (!login.isNullOrEmpty() && name.equals(login, ignoreCase = true))
  return if (repeatsAccount) CommentAuthorLabel(account, agent = agent)
  else CommentAuthorLabel(name, account, agent)
}

private const val GITHUB_ID_PREFIX = "github:"
private const val AGENT_ID_PREFIX = "agent:"

/** The host's stand-in for somebody else's id when the reader sees the design only publicly. */
private const val PSEUDONYM_ID_PREFIX = "collaborator-"

private const val UNNAMED_AUTHOR = "Collaborator"
private const val AGENT_BADGE = "agent"
private const val MAX_RAW_ID_LENGTH = 24

/**
 * Who said it, a person or an agent. Purely cosmetic: declared by the poster, not derived, and
 * never used to permit or refuse anything.
 */
enum class DesignCommentAuthorKind(val wireValue: String, val badge: String) {
  Human("human", "person"),
  Agent("agent", "agent");

  companion object {
    fun ofWire(value: String): DesignCommentAuthorKind =
      entries.firstOrNull { it.wireValue == value } ?: Human
  }
}

/**
 * Where a thread is pinned. [markId] ties it to a reference mark (the stroke a review is about);
 * [nodeId] pins to the design and survives reference replacement; [x]/[y] are frame fractions, like
 * [ReferenceMark.points], so pins stay put across frame changes.
 */
data class DesignCommentAnchor(
  val markId: String? = null,
  val nodeId: String? = null,
  val x: Float? = null,
  val y: Float? = null,
) {
  val isEmpty: Boolean
    get() = markId == null && nodeId == null && x == null && y == null

  /**
   * Where to draw the pin in frame fractions, or null. An explicit point wins over a mark; a mark
   * since erased leaves no pin rather than one at the origin.
   */
  fun pointOn(marks: List<ReferenceMark>): Pair<Float, Float>? {
    if (x != null && y != null) return x to y
    val mark = markId?.let { id -> marks.firstOrNull { it.id == id } } ?: return null
    if (mark.points.size < 2) return null
    // The centroid of the stroke's extremes rather than its first point: an arrow's tail is
    // deliberately away from what it points at, and a box's first corner is not its middle.
    val xs = mark.points.filterIndexed { index, _ -> index % 2 == 0 }
    val ys = mark.points.filterIndexed { index, _ -> index % 2 == 1 }
    return ((xs.min() + xs.max()) / 2f) to ((ys.min() + ys.max()) / 2f)
  }
}

/**
 * One comment for the host to send. Only what was typed: the host assigns author, id and timestamp.
 */
data class DesignCommentDraft(
  /** The thread to reply into, or null to start one where [anchor] says. */
  val threadId: String? = null,
  val anchor: DesignCommentAnchor? = null,
  val body: String,
)
