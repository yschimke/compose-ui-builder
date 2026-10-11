package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.CollaborationDocumentValidator
import ee.schimke.composeai.uibuilder.CollaborationPropertyValidator
import ee.schimke.composeai.uibuilder.CollaborationReducer
import ee.schimke.composeai.uibuilder.CollaborationState
import ee.schimke.composeai.uibuilder.CommandOutcome
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1

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
   * Not written: the edit is applied and displayed, but the browser refused to keep it. Surfaced so
   * the editor can say storage is behind.
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
 * The same reducer the server runs, minus multi-author concerns (fan-out, delta windows, presence).
 *
 * Not thread-safe; the page is single-threaded and the editor's drain loop serializes submissions.
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
   * Commands whose replay the reducer refused; non-empty means the design opened at the last
   * revision that replayed.
   */
  var replayRefusals: List<String> = emptyList()
    private set

  /**
   * True once another tab of this browser has written this design since this one opened it. From
   * then on edits are displayed but **not written**, since each write replaces the whole record and
   * would silently discard the other tab's work. A reload picks up the other tab's version.
   */
  var changedElsewhere: Boolean = false
    private set

  /** See [changedElsewhere]. Called when the browser reports another tab's write. */
  fun markChangedElsewhere() {
    changedElsewhere = true
  }

  val document: UiBuilderDocument
    get() = state.document

  /**
   * [document] as the protocol carries it, with this record's creation and last-change times, so
   * snapshots and hashes agree with the hosted service.
   */
  val protocolDocument: DesignDocumentV1
    get() =
      state.document
        .toDesignDocumentV1()
        .copy(
          createdAtEpochMillis = record.createdAtEpochMillis,
          updatedAtEpochMillis = record.updatedAtEpochMillis,
        )

  val updatedAtEpochMillis: Long
    get() = record.updatedAtEpochMillis

  /**
   * The durable sequence the editor's update cursor is handed: the seed's sequence plus the log's
   * length (one accepted submission per event), never stored separately.
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
    // An idempotent replay changes nothing; appending it would grow the log and advance the
    // sequence falsely.
    if (outcome.idempotentReplay) {
      return LocalSubmissionResult(outcome, sequence, LocalPersistence.Stored)
    }
    record = record.copy(log = record.log + submission, updatedAtEpochMillis = clock())
    return LocalSubmissionResult(outcome, sequence, persist())
  }

  /**
   * Writes the design, compacting first if the log exceeds its budget, and again if the browser
   * refuses the write (the budget only guesses the quota); dropping undo history beats losing the
   * edit.
   */
  private fun persist(): LocalPersistence {
    if (changedElsewhere) return LocalPersistence.Refused(CHANGED_ELSEWHERE)
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
   * The design as it stands, with no history. The in-memory reducer state is not reset, so undo
   * keeps working in the open tab; only a reload sees the compacted log.
   */
  private fun compacted(): LocalDesignRecordV1 =
    localDesignRecord(
        document = state.document,
        catalogSystemId = catalogSystemId,
        sequence = sequence,
        nowEpochMillis = clock(),
      )
      // A compaction drops history, not identity: the design was created when it was created.
      .copy(createdAtEpochMillis = record.createdAtEpochMillis)

  companion object {
    /**
     * The byte budget one design's record is allowed before its log is dropped. `localStorage` is
     * ~5 MB per origin, shared, and counted in UTF-16 characters; 1 MB leaves room for several
     * designs and far exceeds a session's log.
     */
    const val DEFAULT_COMPACTION_THRESHOLD_BYTES: Int = 1_000_000

    /** The [LocalPersistence.Refused] reason once [changedElsewhere] is set. */
    const val CHANGED_ELSEWHERE: String =
      "this design was changed in another tab, so edits here are no longer saved — reload to " +
        "pick up that tab's changes"

    /**
     * Opens [record] by replaying its log onto its seed. A refused command stops the replay and is
     * reported in [replayRefusals], so the design opens at the last revision that applied instead
     * of failing.
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
