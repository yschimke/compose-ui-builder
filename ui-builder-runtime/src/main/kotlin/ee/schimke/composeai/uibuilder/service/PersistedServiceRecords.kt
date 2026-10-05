@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PersistedServiceV1(val designs: Map<String, PersistedDesignV1> = emptyMap())

@Serializable
internal data class PersistedDesignV1(
  val document: DesignDocumentV1,
  val lastSequence: Long,
  val access: DesignAccessControlV1,
  val history: List<CommittedOperationV1> = emptyList(),
  val revisionSnapshots: List<RevisionStateV1>,
  val operationOutcomes: Map<String, OperationOutcomeRecordV1> = emptyMap(),
  val acceptedOperations: Map<String, AcceptedOperationRecordV1> = emptyMap(),
  /**
   * See [ConflictTouchRecordV1]. Defaulted empty so older state files load; coverage starts from
   * the next commit.
   */
  val conflictTouches: List<ConflictTouchRecordV1> = emptyList(),
  val tombstones: Map<String, NodeTreeSnapshotV1> = emptyMap(),
  val positions: Map<String, StableNodePositionV1>,
  val positionSnapshots: List<PositionStateV1>,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
  val audit: List<AuditRecordV1> = emptyList(),
  /**
   * Present only for a branch (`UI_BUILDER_BRANCHES.md`); never written otherwise, keeping ordinary
   * designs' bytes unchanged.
   */
  @EncodeDefault(EncodeDefault.Mode.NEVER) val branch: DesignBranchRecordV1? = null,
  /**
   * A branch's accepted commands since its fork, as submitted: what a merge replays. Not bounded by
   * the history window (a merge needs all of it) but by `MAXIMUM_BRANCH_COMMANDS`.
   */
  @EncodeDefault(EncodeDefault.Mode.NEVER) val branchLog: List<DesignSubmissionV1> = emptyList(),
)

@Serializable
internal enum class DesignBranchStatusV1 {
  OPEN,
  MERGED,
  ARCHIVED,
}

/**
 * What a branch is for. A [SUGGESTION] is a short-lived branch proposed for a person to accept
 * (merge) or reject (archive); it is not an alternative to its siblings, so merging one never
 * archives another. See `UI_BUILDER_BRANCHES.md` → Suggestions.
 */
@Serializable
internal enum class DesignBranchKindV1 {
  BRANCH,
  SUGGESTION,
}

/** What makes a design a branch: where it forked from, and what has become of it. */
@Serializable
internal data class DesignBranchRecordV1(
  val parentDesignId: String,
  val name: String,
  val ownerActorId: String,
  val status: DesignBranchStatusV1,
  val forkRevision: Long,
  val forkDocumentHash: String,
  /**
   * The parent's creation time, distinguishing it from a later design created under the same id.
   */
  val parentCreatedAtEpochMillis: Long,
  val createdAtEpochMillis: Long,
  val closedAtEpochMillis: Long? = null,
  val closedByActorId: String? = null,
  val mergedAtParentRevision: Long? = null,
  val supersededByBranchId: String? = null,
  /** Never written for an ordinary branch, so pre-suggestion bytes are unchanged. */
  @EncodeDefault(EncodeDefault.Mode.NEVER) val kind: DesignBranchKindV1 = DesignBranchKindV1.BRANCH,
)

@Serializable
internal data class RevisionStateV1(val document: DesignDocumentV1, val sequence: Long)

@Serializable
internal data class PositionStateV1(
  val revision: Long,
  val positions: Map<String, StableNodePositionV1>,
)

@Serializable
internal data class StableNodePositionV1(
  val parent: ParentSlotV1? = null,
  val key: StablePositionKeyV1,
)

@Serializable
internal data class StablePositionKeyV1(
  val path: List<Int>,
  val tieBreaker: String,
) : Comparable<StablePositionKeyV1> {
  override fun compareTo(other: StablePositionKeyV1): Int {
    val shared = minOf(path.size, other.path.size)
    repeat(shared) { index ->
      path[index]
        .compareTo(other.path[index])
        .takeIf { it != 0 }
        ?.let {
          return it
        }
    }
    return path.size.compareTo(other.path.size).takeIf { it != 0 }
      ?: tieBreaker.compareTo(other.tieBreaker)
  }
}

@Serializable
internal data class OperationOutcomeRecordV1(
  val fingerprint: String,
  val outcome: CommandOutcomeV1,
)

@Serializable
internal enum class AcceptedKindV1 {
  BATCH,
  UNDO,
  REDO,
}

@Serializable
internal data class AcceptedOperationRecordV1(
  val operationId: String,
  val actorId: String,
  val kind: AcceptedKindV1,
  val committedRevision: Long,
  val activeRevision: Long,
  val changes: List<ChangeRecordV1>,
  val targetOperationId: String? = null,
  val compensatedBy: String? = null,
)

/**
 * What one accepted operation touched, without what it wrote.
 *
 * Staleness checks used to scan [PersistedDesignV1.acceptedOperations], which is pruned by a byte
 * budget ([UiBuilderServiceLimits.retainedUndoBytes]) while acceptance is bounded by retained
 * position snapshots. With large records the two windows diverged and stale submissions were
 * silently reported clean. Touch records are small enough to keep for the whole acceptance window,
 * and are pruned against the oldest retained position snapshot.
 *
 * Keys (see `touchKeys`) are `\u0000`-separated because every segment is caller-supplied and a
 * printable separator could be used to make two touches collide.
 */
@Serializable
internal data class ConflictTouchRecordV1(
  val committedRevision: Long,
  val keys: Set<String>,
  /**
   * The writing batch's [ReplayRunIdentity], letting a Sync tell its own earlier writes from a
   * concurrent edit (see [StalenessWindow]). Absent for undo, redo and older records, which
   * therefore match no run and are always reported.
   */
  @EncodeDefault(EncodeDefault.Mode.NEVER) val actorId: String? = null,
  @EncodeDefault(EncodeDefault.Mode.NEVER) val clientId: String? = null,
  /** The revision the batch's author had seen — `DesignCommandV1.authorSawRevision`. */
  @EncodeDefault(EncodeDefault.Mode.NEVER) val authorSawRevision: Long? = null,
)

@Serializable internal sealed interface ChangeRecordV1

/**
 * The bounded evidence needed to validate an explicit catalog rollback; the documents themselves
 * live in retained snapshots.
 */
@Serializable
@SerialName("catalogUpgrade")
internal data class CatalogUpgradeChangeRecordV1(
  val sourceCatalogPin: CatalogReferenceV1,
  val targetCatalogPin: CatalogReferenceV1,
  val sourceDocumentHash: String,
  val targetDocumentHash: String,
) : ChangeRecordV1

@Serializable
@SerialName("property")
internal data class PropertyChangeV1(
  val nodeId: String,
  val property: String,
  val beforePresent: Boolean,
  val before: UiValueV1?,
  val after: UiValueV1,
  /**
   * False when the operation unset the property (a `setProperty` with `null`); defaulted for older
   * records.
   */
  val afterPresent: Boolean = true,
) : ChangeRecordV1

/**
 * One node's whole modifier chain, before and after; the chain is written whole and has no
 * addressable elements.
 */
@Serializable
@SerialName("modifiers")
internal data class ModifierChangeV1(
  val nodeId: String,
  val before: List<DesignModifierV1>,
  val after: List<DesignModifierV1>,
) : ChangeRecordV1

/**
 * One placement's whole `arguments` map, before and after, node-granular like [ModifierChangeV1].
 */
@Serializable
@SerialName("componentArguments")
internal data class ComponentArgumentsChangeV1(
  val nodeId: String,
  val before: Map<String, UiValueV1>,
  val after: Map<String, UiValueV1>,
) : ChangeRecordV1

/** One state variable's declaration, before and after; a null side means introduced or removed. */
@Serializable
@SerialName("stateVariable")
internal data class StateVariableChangeV1(
  val name: String,
  val before: StateVariableV1?,
  val after: StateVariableV1?,
) : ChangeRecordV1

/**
 * One event's actions on one node, before and after. Null is the absent binding, never an empty
 * list.
 */
@Serializable
@SerialName("eventBinding")
internal data class EventBindingChangeV1(
  val nodeId: String,
  val event: String,
  val before: List<DesignActionV1>?,
  val after: List<DesignActionV1>?,
) : ChangeRecordV1

/** One component declaration, before and after; a null side means declared or removed. */
@Serializable
@SerialName("component")
internal data class ComponentChangeV1(
  val componentKey: String,
  val before: DesignComponentV1?,
  val after: DesignComponentV1?,
) : ChangeRecordV1

@Serializable
@SerialName("environment")
internal data class EnvironmentChangeRecordV1(
  val fields: List<EnvironmentFieldV1>,
  val before: DesignEnvironmentV1,
  val after: DesignEnvironmentV1,
) : ChangeRecordV1

@Serializable
@SerialName("structure")
internal data class StructureChangeV1(
  val nodeId: String,
  val before: NodeTreeSnapshotV1?,
  val after: NodeTreeSnapshotV1?,
) : ChangeRecordV1 {
  val affectedNodeIds: Set<String>
    get() = before?.nodes.orEmpty().keys + after?.nodes.orEmpty().keys
}

@Serializable
internal data class NodeTreeSnapshotV1(
  val rootNodeId: String,
  val nodes: Map<String, DesignNodeV1>,
  val location: NodeLocationV1,
  val positions: Map<String, StableNodePositionV1>,
)

@Serializable
internal enum class AuditKindV1 {
  COMMIT,
  EXPORT,
}

@Serializable
internal data class AuditRecordV1(
  val kind: AuditKindV1,
  val actorId: String,
  val designId: String,
  val revision: Long,
  val sequence: Long,
  val operationId: String?,
  val exportFormat: ExportFormatV1?,
  val atEpochMillis: Long,
)
