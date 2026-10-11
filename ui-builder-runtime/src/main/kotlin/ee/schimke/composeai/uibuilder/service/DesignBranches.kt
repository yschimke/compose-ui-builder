package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignSubmissionV1
import ee.schimke.composeai.uibuilder.protocol.RedoCommandV1
import ee.schimke.composeai.uibuilder.protocol.UndoCommandV1

/*
 * The pure half of design branches: retention pinning, the rebase a merge applies to each logged
 * command, and the record-to-port mapping. The half that needs the service's lock and state lives
 * in
 * `PersistentUiBuilderService`. The model is `docs/design/UI_BUILDER_BRANCHES.md`.
 */

/**
 * Commands a branch may accumulate before it must be merged or abandoned. Bounds the merge's
 * critical section, the parent revisions it consumes, and the never-pruned log's bytes.
 */
internal const val MAXIMUM_BRANCH_COMMANDS: Int = 1_024

internal const val MAXIMUM_BRANCH_NAME_LENGTH: Int = 200

internal const val BRANCH_WHOLE_DOCUMENT_REFUSAL: String =
  "a branch carries only operations its merge can replay; a whole-document change (an asset " +
    "upload, a restore, a catalog upgrade, a document replacement or a home move) belongs on the " +
    "design itself"

/**
 * Keeps the newest [count] entries, as `takeLast` does, and any older entry whose revision is
 * [pinned] — an open branch's fork point. Order is preserved, so the result stays ascending and the
 * first entry is still the oldest revision a submission may name.
 */
internal inline fun <T> List<T>.takeLastPinned(
  count: Int,
  pinned: Set<Long>,
  revisionOf: (T) -> Long,
): List<T> {
  if (size <= count) return this
  val kept = takeLast(count)
  if (pinned.isEmpty()) return kept
  return dropLast(count).filter { revisionOf(it) in pinned } + kept
}

internal fun List<RevisionStateV1>.retainedRevisions(
  count: Int,
  pinned: Set<Long>,
): List<RevisionStateV1> = takeLastPinned(count, pinned) { it.document.revision }

internal fun List<PositionStateV1>.retainedPositions(
  count: Int,
  pinned: Set<Long>,
): List<PositionStateV1> = takeLastPinned(count, pinned) { it.revision }

/**
 * Why [submission] may not land on this branch, or null. A closed branch takes no edits (its log is
 * the record), and whole-document changes cannot be logged (they are hash-bound to the branch's own
 * document).
 */
internal fun PersistedDesignV1.branchWriteRefusal(
  branch: DesignBranchRecordV1,
  submission: UiBuilderSubmission,
): String? =
  when {
    branch.status != DesignBranchStatusV1.OPEN ->
      "branch ${document.id} is ${branch.status.name.lowercase()} and takes no more edits; " +
        "branch ${branch.parentDesignId} again to keep exploring"
    submission is UiBuilderSubmission.Batch &&
      submission.operations.any { it is CatalogUpgradeMutationV1 } -> BRANCH_WHOLE_DOCUMENT_REFUSAL
    branchLog.size >= MAXIMUM_BRANCH_COMMANDS ->
      "branch ${document.id} holds $MAXIMUM_BRANCH_COMMANDS commands, the most a merge replays; " +
        "merge it, or archive it and branch again"
    else -> null
  }

/**
 * A logged branch command as the merge submits it to the parent: the same command, the same
 * operation id and author, addressed to [designId] at the base the replay chain gives it.
 */
internal fun DesignSubmissionV1.rebasedOnto(
  designId: String,
  baseRevision: Long,
): DesignSubmissionV1 =
  when (this) {
    // The staleness base is dropped: it names a revision of the branch, which means nothing on the
    // parent, and the merge passes the fork window for every command instead.
    is DesignCommandV1 ->
      newBuilder()
        .also {
          it.designId = designId
          it.baseRevision = baseRevision
          it.stalenessBaseRevision = null
        }
        .build()
    is UndoCommandV1 -> copy(designId = designId, baseRevision = baseRevision)
    is RedoCommandV1 -> copy(designId = designId, baseRevision = baseRevision)
  }

internal fun DesignBranchKindV1.toPort(): UiBuilderBranchKind =
  when (this) {
    DesignBranchKindV1.BRANCH -> UiBuilderBranchKind.BRANCH
    DesignBranchKindV1.SUGGESTION -> UiBuilderBranchKind.SUGGESTION
  }

internal fun UiBuilderBranchKind.toRecord(): DesignBranchKindV1 =
  when (this) {
    UiBuilderBranchKind.BRANCH -> DesignBranchKindV1.BRANCH
    UiBuilderBranchKind.SUGGESTION -> DesignBranchKindV1.SUGGESTION
  }

internal fun DesignBranchStatusV1.toPort(): UiBuilderBranchStatus =
  when (this) {
    DesignBranchStatusV1.OPEN -> UiBuilderBranchStatus.OPEN
    DesignBranchStatusV1.MERGED -> UiBuilderBranchStatus.MERGED
    DesignBranchStatusV1.ARCHIVED -> UiBuilderBranchStatus.ARCHIVED
  }

/**
 * This branch design as the port reports it. Only meaningful when [PersistedDesignV1.branch] is
 * set.
 */
internal fun PersistedDesignV1.branchView(): UiBuilderBranch {
  val record = requireNotNull(branch) { "design ${document.id} is not a branch" }
  return UiBuilderBranch(
    branchId = document.id,
    parentDesignId = record.parentDesignId,
    name = record.name,
    ownerActorId = record.ownerActorId,
    status = record.status.toPort(),
    forkRevision = record.forkRevision,
    forkDocumentHash = record.forkDocumentHash,
    headRevision = document.revision,
    commandCount = branchLog.size,
    createdAtEpochMillis = record.createdAtEpochMillis,
    closedAtEpochMillis = record.closedAtEpochMillis,
    closedByActorId = record.closedByActorId,
    mergedAtParentRevision = record.mergedAtParentRevision,
    supersededByBranchId = record.supersededByBranchId,
    kind = record.kind.toPort(),
    operationIds = branchLog.map { it.operationId() },
  )
}
