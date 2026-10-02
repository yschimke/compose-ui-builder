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
 * Taking a server design into this browser, and bringing it home again.
 *
 * The rules, and why they are these rules, are in
 * [`UI_BUILDER_DESIGN_PORTABILITY.md`](../../../../../../../../docs/design/UI_BUILDER_DESIGN_PORTABILITY.md).
 * The short version is that there is one merge in this system and it is `CollaborationReducer`: a
 * design that comes home comes home as the commands authored while it was away, replayed at the
 * revisions they were authored against. Syncing back after a flight is a collaborator whose round
 * trip took eight hours instead of eight milliseconds, and that one is already solved.
 */

/**
 * The record for a design taken offline: the server's document as the seed, and the fork point.
 *
 * [document] and [documentDigest] come from the same server answer on purpose. The digest is the
 * server's own `documentHash` for that revision, and computing it here from a document fetched a
 * moment later would record a fork point for a revision this browser never held.
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
 * A server design copied into this browser under [newDesignId], for a caller who may read it but
 * not write it.
 *
 * A new id, not the source's: the copy is a different design from the moment it is made, and
 * keeping the id would make it look like a checkout that could sync back. For the same reason it
 * records [LocalDesignRecordV1.copiedFrom] and leaves [LocalDesignRecordV1.origin] null, and drops
 * the source's `home` — this copy's home is this browser.
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

  /**
   * One sentence for the status line: what landed, what it overwrote, and where it stopped.
   *
   * A sync is not a silent merge — an author who took a design on a plane and brought it back is
   * owed the list of things their edits won against, because nobody else will tell them.
   */
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
   * This design has no fork point, so there is nothing to merge it into.
   *
   * A design created in this browser has no common ancestor on any server, and inventing one —
   * treating an empty template as the base — would be a document merge with a fabricated base.
   * Publishing it is a create, through the route that refuses to replace.
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
 * Replays a locally stored design's log onto the server it was taken from.
 *
 * No new endpoint and no new document format: this drives the same `applyOperation` the live editor
 * drives, through the same forward bridge, with one thing done differently — the base revision each
 * command claims.
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

    // The base chain, the stop-at-first-refusal rule and the per-command report are the shared
    // replay's (`replayCommandLog`), the same one a server-side branch merge runs, so the two
    // cannot disagree about what bringing a log home means. What is particular to this lane is the
    // transport: each command is its own request and commits as it lands. Because each base comes
    // from what the server already answered rather than from what is current, re-running an
    // interrupted sync from the top reproduces identical commands — so the landed prefix answers as
    // an idempotent replay and the run continues from where it stopped.
    val replay =
      replayCommandLog(
        forkRevision = origin.revision.toLong(),
        log = record.log,
        maximumRevision = Int.MAX_VALUE.toLong(),
      ) { submission, base ->
        // Every command of the run was written against the fork, whatever base the chain gives it,
        // so each one past the first tells the service so: its `STALE_*` checks then read from the
        // fork, and a server edit made since is reported on whichever command overwrites it — not
        // only on the first. The fork is this record's, so a re-run sends identical commands.
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
   * from.
   *
   * Two ways it is not, and they need different answers. The revision may be past the server's
   * retention, in which case there is no common ancestor left and the merge cannot be attempted —
   * the flight was longer than the design's history. Or the revision may be retained and be a
   * *different* document, which means this record's fork point belongs to another design's history;
   * replaying onto it would produce a plausible document nobody authored.
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
 * The stored command as the editor's own submission.
 *
 * The two are the same three shapes around the same three reducer commands, which is what lets the
 * sync send its log through the forward bridge the live editor uses rather than a second one
 * written for the return journey.
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
