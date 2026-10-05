package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CommandConflictV1

/**
 * Design branches: a design forked at a revision, edited on its own, and replayed onto its parent
 * through the reducer. See `docs/design/UI_BUILDER_BRANCHES.md`.
 *
 * A separate port rather than new [UiBuilderServiceRequest] variants, because hosts match that
 * sealed hierarchy exhaustively and a new variant would break them.
 *
 * A branch is itself a design with its own id ([UiBuilderBranch.branchId]); it is opened, edited
 * ([UiBuilderServiceRequest.ApplyOperation]) and exported like any other. A suggestion is a branch
 * of [UiBuilderBranchKind.SUGGESTION], accepted with [UiBuilderBranchRequest.MergeBranch] and
 * rejected with [UiBuilderBranchRequest.ArchiveBranch].
 */
public interface UiBuilderBranchPort {
  public suspend fun executeBranch(call: UiBuilderBranchCall): UiBuilderBranchResponse
}

/** One branch invocation with its independently authenticated principal. */
public data class UiBuilderBranchCall(
  val actor: AuthenticatedUiBuilderActor,
  val request: UiBuilderBranchRequest,
)

public sealed interface UiBuilderBranchRequest {
  /**
   * Fork [designId] at [revision] (the head when null). Needs write on the parent. The fork
   * revision stays pinned in the parent while the branch is open, so retention cannot expire a
   * merge's base.
   *
   * [branchId] is minted when null; supplying one makes a retried create idempotent. For a
   * [UiBuilderBranchKind.SUGGESTION], [name] is its summary.
   */
  public data class CreateBranch(
    val designId: String,
    val name: String,
    val revision: Long? = null,
    val branchId: String? = null,
    val kind: UiBuilderBranchKind = UiBuilderBranchKind.BRANCH,
  ) : UiBuilderBranchRequest

  /**
   * The branches of [designId], newest first, optionally narrowed by [kind]. Needs read on the
   * parent.
   */
  public data class ListBranches(
    val designId: String,
    val includeClosed: Boolean = true,
    val kind: UiBuilderBranchKind? = null,
  ) : UiBuilderBranchRequest

  /** One branch's record. Needs read on the branch. */
  public data class GetBranch(val branchId: String) : UiBuilderBranchRequest

  /**
   * Close an open branch without merging it. Its design, log and record are kept; only the pin on
   * the parent's fork revision is released and further edits are refused. Needs write on the
   * parent, or to be the branch's owner.
   */
  public data class ArchiveBranch(val branchId: String) : UiBuilderBranchRequest

  /**
   * Replay the branch's log onto its parent's current revision, all or nothing: one parent revision
   * per command, attributed to its branch author. A refusal commits nothing and the report names
   * the command. Needs write on the parent.
   *
   * [dryRun] reports without writing. [skipOperationIds] leaves logged commands out (skipping a
   * command an undo targets refuses the undo, so skip both); [acceptOperationIds] names the only
   * ones to keep. Unknown ids are refused.
   *
   * Merging a [UiBuilderBranchKind.BRANCH] archives its open siblings forked at the same revision;
   * merging a suggestion archives nothing.
   */
  public data class MergeBranch(
    val branchId: String,
    val dryRun: Boolean = false,
    val skipOperationIds: Set<String> = emptySet(),
    val acceptOperationIds: Set<String>? = null,
  ) : UiBuilderBranchRequest
}

public sealed interface UiBuilderBranchResponse {
  public data class Branch(val branch: UiBuilderBranch) : UiBuilderBranchResponse

  public data class Branches(val designId: String, val branches: List<UiBuilderBranch>) :
    UiBuilderBranchResponse

  public data class Merge(val report: UiBuilderBranchMergeReport) : UiBuilderBranchResponse

  public data class Error(val error: UiBuilderServiceError) : UiBuilderBranchResponse
}

/** What a branch is for. See [UiBuilderBranchPort]. */
public enum class UiBuilderBranchKind {
  /** An alternative explored away from the design, merged back or abandoned. */
  BRANCH,

  /**
   * A short-lived proposal shown on the design for a person to accept (merge) or reject (archive).
   */
  SUGGESTION,
}

public enum class UiBuilderBranchStatus {
  OPEN,
  MERGED,
  ARCHIVED,
}

/** A branch as the port reports it. */
public data class UiBuilderBranch(
  /** The branch's own design id: open, edit, export and subscribe to it by this. */
  val branchId: String,
  val parentDesignId: String,
  val name: String,
  /** Who created the branch — the principal when the creator acts for one. */
  val ownerActorId: String,
  val status: UiBuilderBranchStatus,
  /** The parent revision the branch forked at. */
  val forkRevision: Long,
  /** The parent's document hash at [forkRevision], as the service computes it. */
  val forkDocumentHash: String,
  /** The branch design's current revision. */
  val headRevision: Long,
  /** Commands accepted on the branch since the fork: what a merge would replay. */
  val commandCount: Int,
  val createdAtEpochMillis: Long,
  val closedAtEpochMillis: Long? = null,
  /** Who merged or archived it. */
  val closedByActorId: String? = null,
  /** For a merged branch: the parent revision its last command landed at. */
  val mergedAtParentRevision: Long? = null,
  /** For a sibling archived by a merge: the branch whose merge archived it. */
  val supersededByBranchId: String? = null,
  val kind: UiBuilderBranchKind = UiBuilderBranchKind.BRANCH,
  /**
   * The logged commands' operation ids, in log order, as `skipOperationIds` / `acceptOperationIds`
   * name them.
   */
  val operationIds: List<String> = emptyList(),
)

/**
 * What a merge did, or would do: one entry per command that was attempted.
 *
 * The same shape and the same rules as the browser's Sync report
 * (`ee.schimke.composeai.uibuilder.replay.replayCommandLog`), because it is the same replay.
 */
public data class UiBuilderBranchMergeReport(
  val branchId: String,
  val parentDesignId: String,
  val dryRun: Boolean,
  /** True when every command landed — and, unless [dryRun], the parent now carries them. */
  val merged: Boolean,
  val forkRevision: Long,
  /** The parent's revision the replay started from. */
  val parentRevisionBefore: Long,
  /** The parent's revision after the merge; equal to [parentRevisionBefore] when nothing landed. */
  val parentRevisionAfter: Long,
  val commands: List<UiBuilderBranchMergeCommand>,
  /** Commands after the refused one, never attempted. Zero for a complete replay. */
  val remaining: Int,
  /** Open siblings the merge archived, or (dry run) would archive. */
  val archivedSiblingIds: List<String> = emptyList(),
  /** Logged commands left out at the caller's request, in log order. */
  val skippedOperationIds: List<String> = emptyList(),
)

public enum class UiBuilderBranchMergeCommandStatus {
  /** Landed (or, dry run, would land) at [UiBuilderBranchMergeCommand.committedRevision]. */
  APPLIED,
  /** Refused by the reducer; the run stopped here. */
  REFUSED,
}

public data class UiBuilderBranchMergeCommand(
  val operationId: String,
  /** Who authored the command on the branch, and who it is attributed to on the parent. */
  val actorId: String,
  val status: UiBuilderBranchMergeCommandStatus,
  /** The parent revision the base chain gave this command. */
  val baseRevision: Long,
  val committedRevision: Long? = null,
  /** Last-writer-wins notices: what this command overwrote on the parent. */
  val conflicts: List<CommandConflictV1> = emptyList(),
  /** The reducer's rejection code for a [UiBuilderBranchMergeCommandStatus.REFUSED] command. */
  val code: String? = null,
  val message: String? = null,
  val nodeId: String? = null,
  val field: String? = null,
)
