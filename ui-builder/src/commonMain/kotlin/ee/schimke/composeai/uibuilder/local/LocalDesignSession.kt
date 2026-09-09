package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.CollaborationDocumentValidator
import ee.schimke.composeai.uibuilder.CollaborationPropertyValidator
import ee.schimke.composeai.uibuilder.CollaborationReducer
import ee.schimke.composeai.uibuilder.CollaborationState
import ee.schimke.composeai.uibuilder.CommandOutcome
import ee.schimke.composeai.uibuilder.UiBuilderDocument

/** The catalog-backed rules a locally stored design is admitted against, or none in a test. */
data class LocalDesignValidators(
  val property: CollaborationPropertyValidator? = null,
  val document: CollaborationDocumentValidator? = null,
)

/** What a caller is told about the browser's storage after a submission was applied. */
sealed interface LocalPersistence {
  /** Written, and the design is on disk as displayed. */
  data object Stored : LocalPersistence

  /** Written after dropping undo history older than the current document. */
  data class Compacted(val droppedSubmissions: Int) : LocalPersistence

  /**
   * Not written. The edit is applied and displayed; the browser refused to keep it.
   *
   * Surfaced rather than thrown, because the document in front of the operator is now newer than
   * the one in storage and that is a fact the editor should be able to say out loud.
   */
  data class Refused(val reason: String) : LocalPersistence
}

data class LocalSubmissionResult(
  val outcome: CommandOutcome,
  val sequence: Long,
  val persistence: LocalPersistence,
)

/**
 * One locally stored design, open: the reducer state, the durable sequence, and the browser key.
 *
 * This is the whole of what the server's design service does for a single-author design, minus the
 * parts that only mean something with more than one author — fan-out, retained delta windows,
 * presence. The reducer is byte-for-byte the same object the server runs, which is the point: a
 * design edited offline is not edited by a simpler set of rules, it is edited by the same ones with
 * the round trip removed.
 *
 * Not thread-safe, and does not need to be: the page is single-threaded and the editor's own drain
 * loop already serializes submissions.
 */
class LocalDesignSession
private constructor(
  val designId: String,
  val catalogSystemId: String,
  private val store: LocalDesignStore,
  private val validators: LocalDesignValidators,
  private val clock: () -> Long,
  private val compactionThresholdBytes: Int,
  private var record: LocalDesignRecordV1,
  private var state: CollaborationState,
) {
  /**
   * Commands whose replay the reducer refused, which is a locally stored log that no longer agrees
   * with itself. Non-empty means the design opened at the last revision that did replay.
   */
  var replayRefusals: List<String> = emptyList()
    private set

  val document: UiBuilderDocument
    get() = state.document

  /**
   * The durable sequence the editor's update cursor is handed.
   *
   * Counted rather than stored per entry: one accepted submission is one durable event, so the
   * seed's sequence plus the log's length *is* the count, and a second copy of it could disagree
   * with the log after a compaction.
   */
  val sequence: Long
    get() = record.seedSequence + record.log.size

  fun submit(submission: LocalSubmissionRecordV1): LocalSubmissionResult {
    val application =
      when (submission) {
        is LocalSubmissionRecordV1.Batch ->
          CollaborationReducer.apply(
            state,
            submission.command,
            validators.property,
            validators.document,
          )
        is LocalSubmissionRecordV1.Undo ->
          CollaborationReducer.undo(state, submission.command, validators.document)
        is LocalSubmissionRecordV1.Redo ->
          CollaborationReducer.redo(state, submission.command, validators.document)
      }
    state = application.state
    val outcome = application.outcome
    if (outcome !is CommandOutcome.Accepted) {
      // A rejection is a fact about this command, not about the design: the reducer retains it so
      // a retry answers the same way, and nothing durable changed, so nothing is written.
      return LocalSubmissionResult(outcome, sequence, LocalPersistence.Stored)
    }
    // An idempotent replay is the same accepted command answered twice — the transport retried, or
    // the operator's client did. Appending it again would grow the log by an event that changes
    // nothing and would make the sequence claim a commit that never happened.
    if (outcome.idempotentReplay) {
      return LocalSubmissionResult(outcome, sequence, LocalPersistence.Stored)
    }
    record = record.copy(log = record.log + submission, updatedAtEpochMillis = clock())
    return LocalSubmissionResult(outcome, sequence, persist())
  }

  /**
   * Writes the design, compacting first if the log has outgrown its budget and again if the browser
   * refuses the write outright.
   *
   * Two chances rather than one because the budget is this code's guess at the quota and the quota
   * is the browser's fact: an origin already full of somebody else's keys refuses a record well
   * under the budget, and dropping the undo history is a better answer to that than losing the
   * edit.
   */
  private fun persist(): LocalPersistence {
    val dropped = record.log.size
    if (store.encodedSize(record) > compactionThresholdBytes) {
      record = compacted()
      return try {
        store.write(record)
        LocalPersistence.Compacted(dropped)
      } catch (failure: LocalDesignStorageException) {
        LocalPersistence.Refused(failure.message ?: "this browser refused to store the design")
      }
    }
    return try {
      store.write(record)
      LocalPersistence.Stored
    } catch (_: LocalDesignStorageException) {
      record = compacted()
      try {
        store.write(record)
        LocalPersistence.Compacted(dropped)
      } catch (retry: LocalDesignStorageException) {
        LocalPersistence.Refused(retry.message ?: "this browser refused to store the design")
      }
    }
  }

  /**
   * The design as it stands, with no history before it.
   *
   * The reducer state is deliberately *not* reset alongside: tombstones and accepted commands stay
   * in memory for this page's lifetime, so undo keeps working in the open tab and only a reload
   * settles the log down to what was written. Rebuilding the state here would take the undo stack
   * away at the moment of a save, which is the surprising half of a compaction rather than the
   * necessary half.
   */
  private fun compacted(): LocalDesignRecordV1 =
    localDesignRecord(
      document = state.document,
      catalogSystemId = catalogSystemId,
      sequence = sequence,
      nowEpochMillis = clock(),
    )

  companion object {
    /**
     * The byte budget one design's record is allowed before its log is dropped.
     *
     * `localStorage` is around five megabytes per *origin* — shared with the theme keys, the pack
     * settings and every other design in the browser — and it is charged in UTF-16 characters,
     * which is what a JSON string's length counts. One megabyte leaves room for several designs
     * beside each other and is far more log than an editing session produces: the Jetcaster fixture
     * replays in a few hundred commands.
     */
    const val DEFAULT_COMPACTION_THRESHOLD_BYTES: Int = 1_000_000

    /**
     * Opens [record] by replaying its log onto its seed.
     *
     * A refused command stops the replay and is reported through [replayRefusals] rather than
     * throwing: a log that no longer agrees with itself — a design written by a builder whose
     * reducer differed, a record edited by hand — should open at the last revision that did apply,
     * which is recoverable, instead of leaving the design unopenable, which is not.
     */
    fun open(
      record: LocalDesignRecordV1,
      store: LocalDesignStore,
      validators: LocalDesignValidators = LocalDesignValidators(),
      clock: () -> Long = { 0 },
      compactionThresholdBytes: Int = DEFAULT_COMPACTION_THRESHOLD_BYTES,
    ): LocalDesignSession {
      val session =
        LocalDesignSession(
          designId = record.designId,
          catalogSystemId = record.catalogSystemId,
          store = store,
          validators = validators,
          clock = clock,
          compactionThresholdBytes = compactionThresholdBytes,
          record = record.copy(log = emptyList()),
          state = CollaborationState(record.seed),
        )
      val refusals = mutableListOf<String>()
      val replayed = mutableListOf<LocalSubmissionRecordV1>()
      for (submission in record.log) {
        val result = session.replay(submission)
        if (result is CommandOutcome.Rejected) {
          refusals += "${result.code}: ${result.message}"
          break
        }
        replayed += submission
      }
      session.record = record.copy(log = replayed)
      session.replayRefusals = refusals
      return session
    }
  }

  /** Applies a stored command without writing anything: the log it came from is already durable. */
  private fun replay(submission: LocalSubmissionRecordV1): CommandOutcome {
    val application =
      when (submission) {
        is LocalSubmissionRecordV1.Batch ->
          CollaborationReducer.apply(
            state,
            submission.command,
            validators.property,
            validators.document,
          )
        is LocalSubmissionRecordV1.Undo ->
          CollaborationReducer.undo(state, submission.command, validators.document)
        is LocalSubmissionRecordV1.Redo ->
          CollaborationReducer.redo(state, submission.command, validators.document)
      }
    if (application.outcome is CommandOutcome.Accepted) state = application.state
    return application.outcome
  }
}
