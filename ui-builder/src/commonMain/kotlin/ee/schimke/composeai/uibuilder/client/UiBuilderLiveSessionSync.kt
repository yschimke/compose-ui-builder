package ee.schimke.composeai.uibuilder.client

/** What the live session should do with an authoritative snapshot it has just been handed. */
enum class SnapshotDisposition {
  /** Display it: nothing local is waiting to be sent, so the authoritative state is the truth. */
  DISPLAY,

  /**
   * Hold it: the operator has edits this snapshot cannot know about yet.
   *
   * Displaying it would rebuild the editor from a document that is missing them, which is the
   * canvas visibly dropping work that was accepted a moment later. It is released by
   * [UiBuilderLiveSessionSync.releaseDeferredSnapshot] once the queue has drained.
   */
  DEFER,

  /**
   * Drop it: a newer snapshot has already been seen.
   *
   * Several snapshot fetches can be in flight at once and they do not answer in order, so an older
   * answer overtaking a newer one is ordinary rather than exceptional. Applying it would roll the
   * canvas backwards.
   */
  STALE,
}

/**
 * The ordering rules a live UI-builder session applies to its own edits and the server's answers.
 *
 * Two facts about the browser session make this necessary. Edits are applied locally the moment
 * they are made but committed by a round trip, so a burst of them — twenty list items added as fast
 * as they can be clicked — leaves many uncommitted at once; and each of those round trips is an
 * independent `fetch`, so their answers arrive in whatever order the network settles on.
 *
 * Left alone, both go wrong. A command carrying the revision it was *dispatched* at names a base
 * the server has already moved past, and the insertion anchor it points at — the node the previous
 * edit added — does not exist at that base, so the server refuses it. And a snapshot answering an
 * early edit, landing after one answering a later edit, rebuilds the canvas from the older
 * document. Both look the same from the operator's chair: items appear, then most of them vanish.
 *
 * So this holds the two pieces of state that fix it: [baseRevision], which is the newest revision
 * the server has actually confirmed rather than whatever was on screen when a button was pressed,
 * and the count of submissions still waiting, which decides whether an arriving snapshot may be
 * shown yet. It owns no document and performs no I/O; the session hands it sequences and revisions
 * and does what it says.
 */
class UiBuilderLiveSessionSync {
  private var highestSequence: Long = -1
  private var deferredSnapshot = false

  /**
   * The revision every submission should claim as its base, or null before the first snapshot.
   *
   * Read when the request is built rather than when the edit was made, which is the whole point:
   * the second of two quick edits has to name the revision the first one produced.
   */
  var baseRevision: Int? = null
    private set

  /** How many submissions are queued or in flight. */
  var pendingSubmissions: Int = 0
    private set

  /** Records an edit that is now queued for submission. */
  fun enqueueSubmission() {
    pendingSubmissions += 1
  }

  /** Records a queued submission as finished, however it turned out. */
  fun completeSubmission() {
    check(pendingSubmissions > 0) { "no submission is pending" }
    pendingSubmissions -= 1
  }

  /**
   * Records a snapshot the server sent and says what to do with it.
   *
   * A snapshot that is not [SnapshotDisposition.STALE] advances [baseRevision] whether or not it is
   * displayed, because what the server has committed and what the canvas is showing are different
   * questions during a burst — the next command needs the former.
   */
  fun receiveSnapshot(sequence: Long, revision: Int): SnapshotDisposition {
    if (sequence < highestSequence) return SnapshotDisposition.STALE
    highestSequence = sequence
    baseRevision = revision
    if (pendingSubmissions > 0) {
      deferredSnapshot = true
      return SnapshotDisposition.DEFER
    }
    deferredSnapshot = false
    return SnapshotDisposition.DISPLAY
  }

  /**
   * True when a snapshot was held back and should be displayed now.
   *
   * Called once the queue has drained. Answers false when nothing was held, so the caller does not
   * rebuild the canvas from a document it is already showing.
   */
  fun releaseDeferredSnapshot(): Boolean {
    if (pendingSubmissions > 0) return false
    val release = deferredSnapshot
    deferredSnapshot = false
    return release
  }
}
