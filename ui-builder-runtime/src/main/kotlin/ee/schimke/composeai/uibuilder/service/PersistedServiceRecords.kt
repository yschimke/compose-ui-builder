@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
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
   * The conflict history [acceptedOperations] used to serve — see [ConflictTouchRecordV1].
   *
   * Defaulted empty, so a state file written before this existed loads. Such a design answers
   * "nobody wrote this" until enough operations land to refill the window, which is the behaviour
   * it already had; the coverage this field restores begins from the next commit.
   */
  val conflictTouches: List<ConflictTouchRecordV1> = emptyList(),
  val tombstones: Map<String, NodeTreeSnapshotV1> = emptyMap(),
  val positions: Map<String, StableNodePositionV1>,
  val positionSnapshots: List<PositionStateV1>,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
  val audit: List<AuditRecordV1> = emptyList(),
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
 * What one accepted operation TOUCHED, with none of what it wrote.
 *
 * Conflict detection asks one question of history — "did anyone write this same thing after the
 * revision my client last saw?" — and every check that asks it used to scan
 * [PersistedDesignV1.acceptedOperations]. That map is bounded by
 * [UiBuilderServiceLimits.retainedUndoBytes], because the records in it are enormous:
 * `StructureChangeV1` carries whole node subtrees on both sides, ~430 KB apiece on the design that
 * motivated the budget. A submission is accepted whenever its `baseRevision` still has a POSITION
 * snapshot (see the `REVISION_NOT_RETAINED` refusal in `reduceCommand`), and that set is bounded by
 * a count. Two different bounds over the same window: on a design with large records the byte
 * budget could prune to eight operations while thirty-two-plus revisions stayed acceptable, and a
 * client submitting against one of the uncovered ones got every staleness check answering "nobody
 * wrote this" from a history that had simply been thrown away. Not a missing conflict notice — a
 * silent overwrite reported as clean.
 *
 * Splitting the question from the payload is what fixes it. A touch record is a revision and a set
 * of short keys, so retaining one per accepted operation for the whole acceptance window costs
 * kilobytes where retaining the operations themselves cost megabytes. [conflictTouches] is pruned
 * against the oldest retained position snapshot rather than by a count of its own, which is the
 * invariant stated directly: history covers exactly what the service will accept.
 *
 * The keys are opaque and internal — see `touchKeys`. They are `\u0000`-separated rather than
 * joined on a printable character because node ids, property names and event names are all
 * caller-supplied, and any printable separator is one a caller can put inside a segment to make two
 * different touches collide.
 */
@Serializable
internal data class ConflictTouchRecordV1(
  val committedRevision: Long,
  val keys: Set<String>,
)

@Serializable internal sealed interface ChangeRecordV1

/**
 * The bounded evidence needed to validate an explicit catalog rollback.
 *
 * The candidate itself already lives in retained snapshots. Keeping whole before/after documents
 * here would duplicate every byte inside the undo budget even though generic undo deliberately
 * refuses this record.
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
   * False when the operation unset the property — a `setProperty` whose value was `null`. [after]
   * is then the null value as submitted, kept so the record still says what was asked for.
   * Defaulted, because every record written before the rule existed set a value.
   */
  val afterPresent: Boolean = true,
) : ChangeRecordV1

/**
 * One node's whole modifier chain, before and after.
 *
 * Node-granular rather than field-granular, because the chain is one value:
 * `SetModifiersMutationV1` writes it whole, its elements are order-dependent and have no identity
 * to address, and two writers editing it are editing the same thing.
 */
@Serializable
@SerialName("modifiers")
internal data class ModifierChangeV1(
  val nodeId: String,
  val before: List<DesignModifierV1>,
  val after: List<DesignModifierV1>,
) : ChangeRecordV1

/**
 * One state variable's declaration, before and after.
 *
 * `before == null` is a declaration this operation introduced and `after == null` one it removed,
 * so the same record compensates a `setStateVariable` and a `removeStateVariable` without a second
 * type or a flag saying which it was.
 */
@Serializable
@SerialName("stateVariable")
internal data class StateVariableChangeV1(
  val name: String,
  val before: StateVariableV1?,
  val after: StateVariableV1?,
) : ChangeRecordV1

/**
 * One event's actions on one node, before and after.
 *
 * Null on either side is the absent binding rather than an empty list, which is the same
 * distinction the mutation makes: `actions: []` means unbind, and a document that stored it as an
 * empty list would carry two spellings of "nothing is bound here".
 */
@Serializable
@SerialName("eventBinding")
internal data class EventBindingChangeV1(
  val nodeId: String,
  val event: String,
  val before: List<DesignActionV1>?,
  val after: List<DesignActionV1>?,
) : ChangeRecordV1

/**
 * One component declaration, before and after.
 *
 * `before == null` is a declaration this operation introduced and `after == null` one it removed,
 * so the same record compensates a `declareComponent` and a `removeComponent` — exactly as
 * [StateVariableChangeV1] does for the pair beside it, and for the same reason: a second type or a
 * flag saying which it was would be a fact the two nulls already carry.
 */
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
