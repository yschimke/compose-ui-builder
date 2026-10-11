package ee.schimke.composeai.uibuilder.client

/** What the live session should do with an authoritative snapshot it has just been handed. */
enum class SnapshotDisposition {
  /** Display it: nothing local is waiting to be sent, so the authoritative state is the truth. */
  DISPLAY,

  /**
   * Hold it: the operator has edits this snapshot cannot know about yet. Showing it would drop
   * accepted work from the canvas; [UiBuilderLiveSessionSync.releaseDeferredSnapshot] releases it
   * once the queue drains.
   */
  DEFER,

  /** Drop it: a newer snapshot has already been seen (concurrent fetches answer out of order). */
  STALE,
}

/**
 * The ordering rules a live UI-builder session applies to its own edits and the server's answers.
 *
 * Edits apply locally at once but commit by round trip, so bursts leave many uncommitted, and their
 * answers arrive in any order. Without this, a command would claim a stale base (its insertion
 * anchor not yet existing there) and be refused, and an older snapshot could overwrite a newer one.
 *
 * Holds [baseRevision] (the newest revision the server confirmed) and the count of pending
 * submissions, which decides whether an arriving snapshot may be shown. No document, no I/O.
 */
class UiBuilderLiveSessionSync {
  private var highestSequence: Long = -1
  private var deferredSnapshot = false

  /**
   * The revision every submission should claim as its base, or null before the first snapshot. Read
   * when the request is built, so the second of two quick edits names the revision the first
   * produced.
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
   * Records a snapshot the server sent and says what to do with it. Any
   * non-[SnapshotDisposition.STALE] snapshot advances [baseRevision], displayed or not.
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
   * The revision an edit carried across a fork should claim, or null while [designId] has not
   * opened yet. [openedDesignId] is the on-screen document's id: the copy re-enters the session
   * still showing the server's document until its own first snapshot.
   */
  fun carriedEditBase(designId: String, openedDesignId: String?): Int? = baseRevision?.takeIf {
    openedDesignId == designId
  }

  /**
   * True when a snapshot was held back and should be displayed now. Called once the queue drains;
   * false when nothing was held.
   */
  fun releaseDeferredSnapshot(): Boolean {
    if (pendingSubmissions > 0) return false
    val release = deferredSnapshot
    deferredSnapshot = false
    return release
  }
}
