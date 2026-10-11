package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.canonicalDocumentHash
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.editor.EditorSubmission
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CommandConflictV1
import ee.schimke.composeai.uibuilder.protocol.CommandOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.replay.ReplayAnswer
import ee.schimke.composeai.uibuilder.replay.replayCommandLog

/**
 * Taking a server design into this browser, and bringing it home again; see
 * [`UI_BUILDER_DESIGN_PORTABILITY.md`](../../../../../../../../docs/design/UI_BUILDER_DESIGN_PORTABILITY.md).
 * The only merge is `CollaborationReducer`: a design comes home as the commands authored while
 * away, replayed at the revisions they were authored against, like a very slow collaborator.
 */

/**
 * The record for a design taken offline: the server's document as the seed, and the fork point.
 * [document] and [documentDigest] (the server's `documentHash`) come from the same answer so the
 * fork point matches a revision this browser actually held.
 */
fun localCheckoutRecord(
  document: UiBuilderDocument,
  documentDigest: String,
  catalogSystemId: String,
  sequence: Long,
  server: String,
  nowEpochMillis: Long,
): LocalDesignRecordV1 =
  localDesignRecord(
      document = document,
      catalogSystemId = catalogSystemId,
      sequence = sequence,
      nowEpochMillis = nowEpochMillis,
    )
    .copy(
      origin =
        LocalDesignOriginV1(
          server = server,
          designId = document.id,
          revision = document.revision,
          sequence = sequence,
          documentDigest = documentDigest,
          takenAtEpochMillis = nowEpochMillis,
        )
    )

/**
 * A server design copied into this browser under [newDesignId], for a caller who may read but not
 * write it. A new id, [LocalDesignRecordV1.copiedFrom] set, [LocalDesignRecordV1.origin] null and
 * the source's `home` dropped, so it can never look like a checkout that syncs back.
 */
fun localCopyRecord(
  document: UiBuilderDocument,
  documentDigest: String,
  catalogSystemId: String,
  newDesignId: String,
  server: String,
  nowEpochMillis: Long,
): LocalDesignRecordV1 {
  require(newDesignId != document.id) { "a browser copy needs an id of its own" }
  return localDesignRecord(
      document = document.copy(id = newDesignId, home = null),
      catalogSystemId = catalogSystemId,
      sequence = 0,
      nowEpochMillis = nowEpochMillis,
    )
    .copy(
      copiedFrom =
        LocalDesignCopyV1(
          server = server,
          designId = document.id,
          revision = document.revision,
          documentDigest = documentDigest,
          copiedAtEpochMillis = nowEpochMillis,
        )
    )
}

/** One offline command that landed on the server, and what the server said about it. */
data class LocalSyncLanded(
  val operationId: String,
  val revision: Long,
  /** True when the server had this command already: a resumed sync, not news. */
  val idempotentReplay: Boolean,
  val conflicts: List<CommandConflictV1>,
)

/** The command the replay stopped at, and how much of the log is still in this browser. */
data class LocalSyncRefusal(
  val operationId: String,
  val code: String,
  val message: String,
  val remaining: Int,
)

data class LocalSyncReport(
  val designId: String,
  val forkRevision: Int,
  val landed: List<LocalSyncLanded>,
  val refusal: LocalSyncRefusal?,
) {
  val conflicts: List<CommandConflictV1>
    get() = landed.flatMap { it.conflicts }

  val complete: Boolean
    get() = refusal == null

  /** One sentence for the status line: what landed, what it overwrote, and where it stopped. */
  fun summary(): String {
    val committed = landed.count { !it.idempotentReplay }
    val replayed = landed.size - committed
    return buildString {
      append("$committed of ${landed.size + (refusal?.let { 1 + it.remaining } ?: 0)} edits landed")
      if (replayed > 0) append(", $replayed already there")
      if (conflicts.isNotEmpty()) append(", ${conflicts.size} overwrote a newer edit")
      refusal?.let { append("; stopped at ${it.operationId}: ${it.code} — ${it.message}") }
    }
  }
}

sealed interface LocalSyncResult {
  /**
   * This design has no fork point (created in this browser), so there's nothing to merge into;
   * publishing it is a create.
   */
  data object NotLinked : LocalSyncResult

  /** The server no longer retains the fork point: the flight was longer than its retention. */
  data class ForkPointGone(val revision: Int, val retainedFromSequence: Long?) : LocalSyncResult

  /** The server's revision at the fork point is not the document this copy forked from. */
  data class ForkPointDisagrees(
    val revision: Int,
    val expectedDigest: String,
    val actualDigest: String,
  ) : LocalSyncResult

  /** The server could not be asked, so nothing was sent and nothing was lost. */
  data class Unreachable(val message: String) : LocalSyncResult

  /** The server answered the fork-point question with a refusal. */
  data class Refused(val code: String, val message: String) : LocalSyncResult

  /** The log was replayed. [report] says how much of it landed and what it cost. */
  data class Replayed(val report: LocalSyncReport) : LocalSyncResult
}

/**
 * Replays a locally stored design's log onto the server it was taken from, via the same
 * `applyOperation` and forward bridge as the live editor; only each command's claimed base revision
 * differs.
 */
class LocalDesignSyncBack(
  private val actorId: String,
  private val clientId: String,
  private val execute: suspend (UiBuilderRequestV1) -> UiBuilderHttpResult,
) {
  suspend fun sync(record: LocalDesignRecordV1): LocalSyncResult {
    val origin = record.origin ?: return LocalSyncResult.NotLinked
    verifyForkPoint(origin)?.let {
      return it
    }

    // The base chain, stop-at-first-refusal and per-command report come from the shared
    // `replayCommandLog` (also used by server-side branch merges). Each command is its own request;
    // bases come from the server's answers, so re-running an interrupted sync reproduces identical
    // commands and the landed prefix replays idempotently.
    val replay =
      replayCommandLog(
        forkRevision = origin.revision.toLong(),
        log = record.log,
        maximumRevision = Int.MAX_VALUE.toLong(),
      ) { submission, base ->
        // Every command past the first tells the service it was written against the fork, so
        // `STALE_*` checks read from the fork and overwrites of server edits are reported on the
        // command that makes them.
        val wire =
          submission
            .toEditorSubmission()
            .toProtocolSubmission(
              actorId,
              clientId,
              base.toInt(),
              stalenessBaseRevision = origin.revision,
            )
        val answer =
          try {
            execute(ApplyOperationRequestV1(wire))
          } catch (failure: Exception) {
            return@replayCommandLog ReplayAnswer.Refused(
              wire.submissionOperationId(),
              "UNREACHABLE",
              failure.message ?: "the server could not be reached",
            )
          }
        when (answer) {
          is UiBuilderHttpResult.Response ->
            when (val outcome = (answer.response as? OperationOutcomeResponseV1)?.outcome) {
              is CommandOutcomeV1 -> ReplayAnswer.of(outcome)
              else ->
                ReplayAnswer.Refused(
                  wire.submissionOperationId(),
                  "UNEXPECTED_RESPONSE",
                  "the server answered an edit with ${answer.response::class.simpleName}",
                )
            }
          is UiBuilderHttpResult.SnapshotRequired ->
            ReplayAnswer.Refused(
              wire.submissionOperationId(),
              answer.error.code.name,
              answer.error.message,
            )
          is UiBuilderHttpResult.ServiceError ->
            ReplayAnswer.Refused(
              wire.submissionOperationId(),
              answer.error.code.name,
              answer.error.message,
            )
        }
      }
    return LocalSyncResult.Replayed(
      LocalSyncReport(
        designId = record.designId,
        forkRevision = origin.revision,
        landed =
          replay.landed.map {
            LocalSyncLanded(
              operationId = it.operationId,
              revision = it.committedRevision,
              idempotentReplay = it.idempotentReplay,
              conflicts = it.conflicts,
            )
          },
        refusal =
          replay.stop?.let { LocalSyncRefusal(it.operationId, it.code, it.message, it.remaining) },
      )
    )
  }

  /**
   * That the server's revision [LocalDesignOriginV1.revision] is still the one this copy forked
   * from. It may be past retention (no common ancestor left) or a different document (the fork
   * point belongs to another history); both refuse the merge.
   */
  private suspend fun verifyForkPoint(origin: LocalDesignOriginV1): LocalSyncResult? {
    val answer =
      try {
        execute(GetSnapshotRequestV1(origin.designId, origin.revision.toLong()))
      } catch (failure: Exception) {
        return LocalSyncResult.Unreachable(failure.message ?: "the server could not be reached")
      }
    return when (answer) {
      is UiBuilderHttpResult.SnapshotRequired ->
        LocalSyncResult.ForkPointGone(origin.revision, answer.error.retainedFromSequence)
      is UiBuilderHttpResult.ServiceError ->
        LocalSyncResult.Refused(answer.error.code.name, answer.error.message)
      is UiBuilderHttpResult.Response -> {
        val document: DesignDocumentV1? =
          (answer.response as? SnapshotResponseV1)?.snapshot?.state?.document
        if (document == null) {
          LocalSyncResult.Refused(
            "UNEXPECTED_RESPONSE",
            "the server answered the fork point with ${answer.response::class.simpleName}",
          )
        } else {
          val digest = document.canonicalDocumentHash()
          if (digest == origin.documentDigest) null
          else LocalSyncResult.ForkPointDisagrees(origin.revision, origin.documentDigest, digest)
        }
      }
    }
  }
}

/**
 * The stored command as the editor's own submission, so sync can use the live editor's forward
 * bridge.
 */
private fun ee.schimke.composeai.uibuilder.protocol.DesignSubmissionV1.submissionOperationId():
  String =
  when (this) {
    is ee.schimke.composeai.uibuilder.protocol.DesignCommandV1 -> operationId
    is ee.schimke.composeai.uibuilder.protocol.UndoCommandV1 -> operationId
    is ee.schimke.composeai.uibuilder.protocol.RedoCommandV1 -> operationId
  }

internal fun LocalSubmissionRecordV1.toEditorSubmission(): EditorSubmission =
  when (this) {
    is LocalSubmissionRecordV1.Batch -> EditorSubmission.Batch(command)
    is LocalSubmissionRecordV1.Undo -> EditorSubmission.Undo(command)
    is LocalSubmissionRecordV1.Redo -> EditorSubmission.Redo(command)
  }
