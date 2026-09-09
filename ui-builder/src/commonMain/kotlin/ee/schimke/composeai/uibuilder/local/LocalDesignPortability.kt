package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.EditorSubmission
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.canonicalDocumentHash
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CommandConflictV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1

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

    // base(c₁) is the fork point, and base(cₖ) is the revision this run's predecessor landed at.
    //
    // Not the server's current revision, which would make every offline edit newer than the
    // concurrent edits it is racing — so it would win silently, and the conflict notice that makes
    // the merge legible would be thrown away. And not the fork point throughout, which would make
    // the run concurrent with itself: a move of a node an earlier command inserted resolves its
    // anchor against the position snapshot retained at the fork, where that node does not exist.
    //
    // Because each base comes from what the server already answered rather than from what is
    // current, re-running an interrupted sync from the top reproduces identical commands — so the
    // landed prefix answers as an idempotent replay and the run continues from where it stopped.
    var base = origin.revision
    val landed = mutableListOf<LocalSyncLanded>()
    record.log.forEachIndexed { index, submission ->
      val remaining = record.log.size - index - 1
      val wire = submission.toEditorSubmission().toProtocolSubmission(actorId, clientId, base)
      val answer =
        try {
          execute(ApplyOperationRequestV1(wire))
        } catch (failure: Exception) {
          return LocalSyncResult.Replayed(
            report(
              record,
              origin,
              landed,
              LocalSyncRefusal(
                wire.submissionOperationId(),
                "UNREACHABLE",
                failure.message ?: "the server could not be reached",
                remaining,
              ),
            )
          )
        }
      when (answer) {
        is UiBuilderHttpResult.Response -> {
          val outcome = (answer.response as? OperationOutcomeResponseV1)?.outcome
          when (outcome) {
            is AcceptedOutcomeV1 -> {
              landed +=
                LocalSyncLanded(
                  operationId = outcome.operationId,
                  revision = outcome.committedRevision,
                  idempotentReplay = outcome.idempotentReplay,
                  conflicts = outcome.conflicts,
                )
              val committed = outcome.committedRevision
              if (committed !in 0..Int.MAX_VALUE.toLong()) {
                return LocalSyncResult.Replayed(
                  report(
                    record,
                    origin,
                    landed,
                    LocalSyncRefusal(
                      outcome.operationId,
                      "REVISION_OVERFLOW",
                      "the server committed revision $committed, which this page cannot count",
                      remaining,
                    ),
                  )
                )
              }
              base = committed.toInt()
            }
            is RejectedOutcomeV1 ->
              return LocalSyncResult.Replayed(
                report(
                  record,
                  origin,
                  landed,
                  LocalSyncRefusal(
                    outcome.operationId,
                    outcome.code.name,
                    outcome.message,
                    remaining,
                  ),
                )
              )
            else ->
              return LocalSyncResult.Replayed(
                report(
                  record,
                  origin,
                  landed,
                  LocalSyncRefusal(
                    wire.submissionOperationId(),
                    "UNEXPECTED_RESPONSE",
                    "the server answered an edit with ${answer.response::class.simpleName}",
                    remaining,
                  ),
                )
              )
          }
        }
        is UiBuilderHttpResult.SnapshotRequired,
        is UiBuilderHttpResult.ServiceError -> {
          val error =
            when (answer) {
              is UiBuilderHttpResult.SnapshotRequired -> answer.error
              is UiBuilderHttpResult.ServiceError -> answer.error
              else -> error("unreachable")
            }
          return LocalSyncResult.Replayed(
            report(
              record,
              origin,
              landed,
              LocalSyncRefusal(
                wire.submissionOperationId(),
                error.code.name,
                error.message,
                remaining,
              ),
            )
          )
        }
      }
    }
    return LocalSyncResult.Replayed(report(record, origin, landed, refusal = null))
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

  private fun report(
    record: LocalDesignRecordV1,
    origin: LocalDesignOriginV1,
    landed: List<LocalSyncLanded>,
    refusal: LocalSyncRefusal?,
  ) = LocalSyncReport(record.designId, origin.revision, landed.toList(), refusal)
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
