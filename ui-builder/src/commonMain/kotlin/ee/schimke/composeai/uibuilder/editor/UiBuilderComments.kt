package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.reference.ReferenceMark
import ee.schimke.composeai.uibuilder.reference.RestoredReference

/**
 * The discussion about a design, as the editor sees it.
 *
 * A separate model from the design document, and deliberately so — for the reasons the server's
 * `ServeUiBuilderCommentStore` states in full: a comment must never reach the Compose export, the
 * wire has no mutation that could carry it, and a reply typed into a review thread must not advance
 * the design's revision and invalidate everybody's optimistic state.
 *
 * These types mirror the host's stored shapes without sharing a declaration with them, the way
 * [RestoredReference] mirrors the reference record: `:ui-builder` cannot depend on `:server`, the
 * host's copy is the authority, and a tolerant client is what lets the payload gain a field without
 * blanking somebody's panel mid-release.
 */
data class DesignCommentBoard(
  /**
   * Rises on every accepted write, and the only thing a watcher has to remember.
   *
   * The editor keeps it so a reconnect resumes rather than replays, and so a panel can tell "the
   * host answered with what I already had" from "somebody said something".
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
 * Who a comment is from, in the pieces the thread UI draws.
 *
 * [name] is what the poster chose to be called, [account] is the account the host says posted it,
 * and [agent] draws the agent badge. The name is the poster's own label and the account is not, so
 * the two are shown side by side whenever they say different things.
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
 * - `github:yschimke` named "Yuri" is `Yuri · @yschimke`; named "yschimke", or unnamed, is just
 *   `@yschimke`.
 * - `agent:<fingerprint>` is marked as an agent; the fingerprint means nothing to a reader, so the
 *   badge stands in for the account.
 * - Any other id is shown as it is, shortened when it is long, and dropped when the name repeats
 *   it.
 * - An id the host has replaced with a `collaborator-` pseudonym, which it does for a reader who
 *   reaches the design only through its public link, shows the display name as sent and no account:
 *   the pseudonym is not anybody's account.
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
 * Who said it — a person or an agent.
 *
 * Cosmetic, and only ever cosmetic: it decides an icon and nothing else. The host cannot tell a
 * designer's browser from an agent's MCP session by the credential alone, so it is declared rather
 * than derived, and nothing is permitted or refused on the strength of it.
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
 * Where a thread is pinned.
 *
 * [markId] is the link between comments and markup, and the reason the two features belong
 * together: a stroke somebody drew on the reference is *the* thing a review sentence is usually
 * about, and "this arrow, why?" is not a sentence you can write without the arrow. [nodeId] pins to
 * the design itself, which survives the reference being replaced. [x] and [y] are frame fractions —
 * the same coordinate space [ReferenceMark.points] uses — so a pin lands in the same place when the
 * operator switches the frame from a phone to a tablet.
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
   * Where to draw the pin, in frame fractions, or null when it cannot be drawn.
   *
   * An explicit point wins over a mark, because it is what the author aimed at. A mark that has
   * since been rubbed out leaves the thread without a point rather than at the origin: a pin in the
   * top-left corner claiming to be about a stroke nobody can see is worse than no pin.
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
 * One thing the editor wants said, handed to the host to send.
 *
 * The editor mints no ids and no timestamps: the host assigns the author from the authenticated
 * actor and the id from its own counter, so a draft carries only what a person actually typed.
 */
data class DesignCommentDraft(
  /** The thread to reply into, or null to start one where [anchor] says. */
  val threadId: String? = null,
  val anchor: DesignCommentAnchor? = null,
  val body: String,
)
