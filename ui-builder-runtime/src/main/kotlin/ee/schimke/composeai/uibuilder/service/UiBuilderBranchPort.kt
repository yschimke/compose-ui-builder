package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CommandConflictV1

/**
 * Design branches: a design forked at a revision, edited on its own, and replayed back onto its
 * parent through the reducer. The model and its rules are in `docs/design/UI_BUILDER_BRANCHES.md`.
 *
 * **A port beside [UiBuilderServicePort], not more variants of [UiBuilderServiceRequest].** That
 * hierarchy is sealed and the host matches it exhaustively — its grant scoping, its route table —
 * so a new variant is a compile break in every host the day it is released. A separate port is
 * additive: a host that has not wired branches yet keeps compiling, and wiring them is a choice it
 * makes (the MCP `branch_design` / `merge_branch` / `list_branches` tools).
 *
 * **A branch IS a design.** It has its own design id ([UiBuilderBranch.branchId]), and every
 * ordinary request works on it: [UiBuilderServiceRequest.OpenDesign], a subscription,
 * [UiBuilderServiceRequest.ApplyOperation] (which is how a branch is edited), an export, a
 * thumbnail. What makes it a branch is the record this port reads and writes: its parent, its fork
 * point, its name, owner and status, and the log of commands accepted on it since the fork.
 *
 * **A suggestion is a branch** of [UiBuilderBranchKind.SUGGESTION]: a few proposed commands on the
 * design's head that a person accepts or rejects, like suggestion mode in a document. There is no
 * second mechanism. Propose it with [UiBuilderBranchRequest.CreateBranch] and `kind = SUGGESTION`,
 * fill it with ordinary applies on its id, list the open ones with
 * [UiBuilderBranchRequest.ListBranches] (`kind = SUGGESTION, includeClosed = false`), accept with
 * [UiBuilderBranchRequest.MergeBranch] (all of it, or the commands named in
 * [UiBuilderBranchRequest.MergeBranch.acceptOperationIds]) and reject with
 * [UiBuilderBranchRequest.ArchiveBranch].
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
   * Fork [designId] at [revision] (its current revision when null) into a new branch.
   *
   * Needs write on the parent: a branch is a place to change the parent from. The fork revision's
   * document and position state are **pinned** in the parent for as long as the branch is open, so
   * retention cannot expire the base a merge replays from.
   *
   * [branchId] is the new branch's design id; the service mints one when it is null. Supplying one
   * makes a retried create idempotent for the same parent, fork revision, name and kind.
   *
   * [kind] is [UiBuilderBranchKind.SUGGESTION] for a proposed edit a person will accept or reject;
   * [name] is then its summary ("Make the play button bigger"). A suggestion is normally forked at
   * the head ([revision] null), so what it shows is what accepting it would change now.
   */
  public data class CreateBranch(
    val designId: String,
    val name: String,
    val revision: Long? = null,
    val branchId: String? = null,
    val kind: UiBuilderBranchKind = UiBuilderBranchKind.BRANCH,
  ) : UiBuilderBranchRequest

  /**
   * The branches of [designId], newest first. Needs read on the parent.
   *
   * [kind] narrows the list to one kind — `SUGGESTION` with `includeClosed = false` is "the
   * suggestions waiting on this design"; null lists both.
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
   * Replay the branch's log onto its parent's current revision.
   *
   * **All or nothing.** Every command is replayed through the reducer onto a working copy of the
   * parent; only when every one lands is the result committed, as one revision per command,
   * attributed to the actor that authored it on the branch. A refusal commits nothing and the
   * report says which command stopped the run and why. Then the branch is marked merged and its
   * open siblings — branches of the same parent forked at the same revision — are archived, linked
   * to it.
   *
   * [dryRun] replays and reports without writing anything, to either design.
   *
   * [skipOperationIds] leaves those logged commands out of the replay — how a merge refused at a
   * command is resolved when that command should not land (a stale delete nobody wants any more, an
   * edit superseded on the parent). The log itself is history and is never rewritten; the skip is a
   * decision made at merge time, and the report lists it. Skipping a command an undo targets
   * refuses the undo (`UNKNOWN_OPERATION`), so skip both.
   *
   * [acceptOperationIds] is the same decision said the other way round — "accept only these" — for
   * accepting part of a suggestion: every logged command it does not name is skipped, as if it were
   * in [skipOperationIds]. Null accepts the whole log. Naming a command the log does not hold is
   * refused, as it is for a skip.
   *
   * Merging a [UiBuilderBranchKind.BRANCH] archives its open sibling branches. Merging a
   * [UiBuilderBranchKind.SUGGESTION] archives nothing: two suggestions made at the same revision
   * are two proposals, not two alternatives, and accepting one says nothing about the other.
   *
   * Needs write on the parent.
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
   * The logged commands' operation ids, in log order: what [UiBuilderBranchRequest.MergeBranch]'s
   * `skipOperationIds` and `acceptOperationIds` name, so a partial accept can be asked for without
   * reading the log some other way.
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
