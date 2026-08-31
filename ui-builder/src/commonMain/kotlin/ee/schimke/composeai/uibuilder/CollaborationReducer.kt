package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityIssueCode
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Candidate collaboration command schema. The stable wire DTOs move to compose-preview-contracts
 * before this API is published; the reducer remains an implementation concern of the builder.
 */
@Serializable
data class DesignCommand(
  val designId: String,
  val operationId: String,
  val actorId: String,
  val clientId: String,
  val baseRevision: Int,
  val operations: List<DesignOperation>,
)

@Serializable
sealed interface DesignOperation {
  @Serializable
  @SerialName("insertNode")
  data class InsertNode(
    val node: UiBuilderNode,
    val parent: ParentSlot? = null,
    val afterNodeId: String? = null,
  ) : DesignOperation

  @Serializable
  @SerialName("moveNode")
  data class MoveNode(
    val nodeId: String,
    val parent: ParentSlot? = null,
    val afterNodeId: String? = null,
  ) : DesignOperation

  @Serializable
  @SerialName("deleteNode")
  data class DeleteNode(val nodeId: String) : DesignOperation

  @Serializable
  @SerialName("restoreNode")
  data class RestoreNode(val nodeId: String) : DesignOperation

  @Serializable
  @SerialName("setProperty")
  data class SetProperty(val nodeId: String, val property: String, val value: JsonElement) :
    DesignOperation
}

@Serializable data class ParentSlot(val nodeId: String, val slot: String)

data class CollaborationState(
  val document: UiBuilderDocument,
  val tombstones: Map<String, NodeTombstone> = emptyMap(),
  val acceptedCommands: Map<String, AcceptedCommand> = emptyMap(),
  val positions: Map<String, StableNodePosition> = emptyMap(),
  val positionSnapshots: Map<Int, Map<String, StableNodePosition>> = emptyMap(),
  val propertyVersions: Map<PropertyAddress, Int> = emptyMap(),
  val moveVersions: Map<String, Int> = emptyMap(),
  val undoRecords: Map<String, AcceptedUndo> = emptyMap(),
  val redoRecords: Map<String, AcceptedRedo> = emptyMap(),
  val rejectedOperations: Map<String, RejectedOperation> = emptyMap(),
  val compensatedOperationIds: Set<String> = emptySet(),
)

data class NodeTombstone(
  val rootNodeId: String,
  val nodes: Map<String, UiBuilderNode>,
  val location: NodeLocation,
  val deletedAtRevision: Int,
  val positions: Map<String, StableNodePosition> = emptyMap(),
)

data class PropertyAddress(val nodeId: String, val property: String)

data class StablePositionKey(val path: List<Int>, val tieBreaker: String) :
  Comparable<StablePositionKey> {
  override fun compareTo(other: StablePositionKey): Int {
    val count = maxOf(path.size, other.path.size)
    repeat(count) { index ->
      val left = path.getOrElse(index) { 0 }
      val right = other.path.getOrElse(index) { 0 }
      if (left != right) return left.compareTo(right)
    }
    return tieBreaker.compareTo(other.tieBreaker)
  }
}

data class StableNodePosition(val parent: ParentSlot?, val key: StablePositionKey)

data class NodeLocation(
  val parent: ParentSlot? = null,
  val afterNodeId: String? = null,
  val beforeNodeId: String? = null,
  val fallbackIndex: Int = 0,
)

data class AcceptedCommand(
  val command: DesignCommand,
  val committedRevision: Int,
  val canonicalDocument: String,
  val propertyChanges: List<PropertyChange> = emptyList(),
  val conflicts: List<ConflictNotice> = emptyList(),
)

data class PropertyChange(
  val address: PropertyAddress,
  val before: JsonElement?,
  val after: JsonElement,
)

@Serializable
data class UndoCommand(
  val designId: String,
  val operationId: String,
  val actorId: String,
  val clientId: String,
  val baseRevision: Int,
  val targetOperationId: String,
)

@Serializable
data class RedoCommand(
  val designId: String,
  val operationId: String,
  val actorId: String,
  val clientId: String,
  val baseRevision: Int,
  val targetUndoOperationId: String,
)

data class AcceptedUndo(
  val command: UndoCommand,
  val target: AcceptedCommand,
  val committedRevision: Int,
  val canonicalDocument: String,
  val redoneBy: String? = null,
)

data class AcceptedRedo(
  val command: RedoCommand,
  val targetUndoOperationId: String,
  val committedRevision: Int,
  val canonicalDocument: String,
)

sealed interface RejectedMutation {
  val operationId: String

  data class Design(val command: DesignCommand) : RejectedMutation {
    override val operationId: String = command.operationId
  }

  data class Undo(val command: UndoCommand) : RejectedMutation {
    override val operationId: String = command.operationId
  }

  data class Redo(val command: RedoCommand) : RejectedMutation {
    override val operationId: String = command.operationId
  }
}

data class RejectedOperation(
  val mutation: RejectedMutation,
  val outcome: CommandOutcome.Rejected,
)

enum class ConflictCode {
  STALE_PROPERTY_WRITE,
  STALE_MOVE,
}

data class ConflictNotice(
  val code: ConflictCode,
  val nodeId: String,
  val field: String? = null,
  val overwrittenRevision: Int,
)

sealed interface CommandOutcome {
  data class Accepted(
    val committedRevision: Int,
    val canonicalDocument: String,
    val idempotentReplay: Boolean,
    val conflicts: List<ConflictNotice> = emptyList(),
  ) : CommandOutcome

  data class Rejected(
    val code: RejectionCode,
    val message: String,
    val operationIndex: Int? = null,
    val nodeId: String? = null,
    val field: String? = null,
  ) : CommandOutcome
}

enum class RejectionCode {
  DESIGN_MISMATCH,
  INVALID_COMMAND,
  REVISION_MISMATCH,
  OPERATION_ID_REUSED,
  UNKNOWN_NODE,
  DELETED_NODE,
  MISSING_PROPERTY_VALIDATOR,
  MALFORMED_PROPERTY,
  INVALID_PROPERTY,
  REVISION_NOT_RETAINED,
  INVALID_LOCATION,
  CYCLE,
  ACTOR_MISMATCH,
  ALREADY_COMPENSATED,
  UNSAFE_COMPENSATION,
  UNSUPPORTED_COMPENSATION,
  UNKNOWN_OPERATION,
}

data class CommandApplication(val state: CollaborationState, val outcome: CommandOutcome)

data class PropertyWriteIssue(val message: String)

fun interface CollaborationPropertyValidator {
  fun validate(
    document: UiBuilderDocument,
    nodeId: String,
    property: String,
    encodedValue: JsonObject,
  ): PropertyWriteIssue?
}

/**
 * Adapts the catalog validator without making a catalog mandatory for reducer-only replay tests.
 */
class CapabilityPropertyWriteValidator(private val validator: CapabilityValidator) :
  CollaborationPropertyValidator {
  override fun validate(
    document: UiBuilderDocument,
    nodeId: String,
    property: String,
    encodedValue: JsonObject,
  ): PropertyWriteIssue? {
    val node = document.nodes.getValue(nodeId)
    val candidate =
      document.copy(
        nodes =
          document.nodes +
            (nodeId to
              node.copy(properties = JsonObject(node.properties + (property to encodedValue))))
      )
    val relevantCodes =
      setOf(
        CapabilityIssueCode.UNKNOWN_COMPONENT,
        CapabilityIssueCode.UNKNOWN_PROPERTY,
        CapabilityIssueCode.INVALID_PROPERTY_TYPE,
        CapabilityIssueCode.INVALID_PROPERTY_VALUE,
      )
    return validator
      .validate(candidate)
      .issues
      .firstOrNull {
        it.nodeId == nodeId &&
          it.code in relevantCodes &&
          (it.field == property || it.code == CapabilityIssueCode.UNKNOWN_COMPONENT)
      }
      ?.let { PropertyWriteIssue(it.message) }
  }
}

/**
 * Immutable, server-ordered collaboration reducer.
 *
 * The idempotency lookup runs before revision checks so a transport retry returns its original
 * result after later commits. Stale scalar writes, inserts, and moves converge through the server's
 * accepted log and stable position keys; delete/restore remain strict. A rejected batch never
 * exposes its working copy. This advances the collaboration slice but does not claim full Gate 0
 * retention, persistence, or structural compensation semantics.
 */
object CollaborationReducer {
  fun apply(
    state: CollaborationState,
    command: DesignCommand,
    propertyValidator: CollaborationPropertyValidator? = null,
  ): CommandApplication {
    val mutation = RejectedMutation.Design(command)
    state.replayRejected(mutation)?.let {
      return it
    }
    return applyUnrecorded(state, command, propertyValidator).retainRejection(mutation)
  }

  private fun applyUnrecorded(
    state: CollaborationState,
    command: DesignCommand,
    propertyValidator: CollaborationPropertyValidator?,
  ): CommandApplication {
    if (command.operationId in state.undoRecords || command.operationId in state.redoRecords) {
      return state.rejected(
        RejectionCode.OPERATION_ID_REUSED,
        "operation id ${command.operationId} is already committed as compensation",
      )
    }
    val prior = state.acceptedCommands[command.operationId]
    if (prior != null) {
      if (prior.command != command) {
        return state.rejected(
          RejectionCode.OPERATION_ID_REUSED,
          "operation id ${command.operationId} was already used by a different command",
        )
      }
      return CommandApplication(
        state,
        CommandOutcome.Accepted(
          committedRevision = prior.committedRevision,
          canonicalDocument = prior.canonicalDocument,
          idempotentReplay = true,
          conflicts = prior.conflicts,
        ),
      )
    }

    if (command.designId != state.document.id) {
      return state.rejected(
        RejectionCode.DESIGN_MISMATCH,
        "command design ${command.designId} does not match ${state.document.id}",
      )
    }
    if (
      command.operationId.isBlank() ||
        command.actorId.isBlank() ||
        command.clientId.isBlank() ||
        command.operations.isEmpty()
    ) {
      return state.rejected(
        RejectionCode.INVALID_COMMAND,
        "operationId, actorId, clientId, and at least one operation are required",
      )
    }
    if (command.baseRevision > state.document.revision || command.baseRevision < 0) {
      return state.rejected(
        RejectionCode.REVISION_MISMATCH,
        "base revision ${command.baseRevision} is not available at ${state.document.revision}",
      )
    }
    val stale = command.baseRevision < state.document.revision
    if (
      stale &&
        command.operations.any {
          it is DesignOperation.DeleteNode || it is DesignOperation.RestoreNode
        }
    ) {
      return state.rejected(
        RejectionCode.REVISION_MISMATCH,
        "stale delete/restore requires the current revision ${state.document.revision}",
      )
    }
    if (propertyValidator == null) {
      val propertyOperationIndex =
        command.operations.indexOfFirst { it is DesignOperation.SetProperty }
      if (propertyOperationIndex >= 0) {
        val propertyOperation =
          command.operations[propertyOperationIndex] as DesignOperation.SetProperty
        return state.rejected(
          RejectionCode.MISSING_PROPERTY_VALIDATOR,
          "SetProperty requires capability validation",
          propertyOperationIndex,
          propertyOperation.nodeId,
          propertyOperation.property,
        )
      }
    }

    val prepared = state.withStablePositions()
    val basePositions = prepared.positionSnapshots[command.baseRevision]
    if (basePositions == null) {
      return state.rejected(
        RejectionCode.REVISION_NOT_RETAINED,
        "position snapshot for revision ${command.baseRevision} is not retained",
      )
    }
    val trace = ReductionTrace(prepared)
    command.operations.forEachIndexed { index, operation ->
      try {
        val operationPositions =
          basePositions +
            trace.batchPositionTouches.mapNotNull { nodeId ->
              trace.state.positions[nodeId]?.let { nodeId to it }
            }
        trace.state =
          trace.state.applyOperation(
            operation = operation,
            propertyValidator = propertyValidator,
            basePositions = operationPositions,
            operationKey = "${command.operationId}:$index",
            baseRevision = command.baseRevision,
            trace = trace,
          )
      } catch (failure: ReducerFailure) {
        return state.rejected(
          failure.code,
          failure.message.orEmpty(),
          index,
          failure.nodeId,
          failure.field,
        )
      }
    }

    val committedRevision = state.document.revision + 1
    val document = trace.state.document.copy(revision = committedRevision)
    val canonicalDocument = canonicalDocument(document)
    val propertyVersions =
      trace.propertyTouches.fold(trace.state.propertyVersions) { versions, address ->
        versions + (address to committedRevision)
      }
    val moveVersions =
      trace.moveTouches.fold(trace.state.moveVersions) { versions, nodeId ->
        versions + (nodeId to committedRevision)
      }
    val accepted =
      AcceptedCommand(
        command = command,
        committedRevision = committedRevision,
        canonicalDocument = canonicalDocument,
        propertyChanges = trace.propertyChanges,
        conflicts = trace.conflicts,
      )
    val committed =
      trace.state.copy(
        document = document,
        acceptedCommands = state.acceptedCommands + (command.operationId to accepted),
        positionSnapshots =
          trace.state.positionSnapshots + (committedRevision to trace.state.positions),
        propertyVersions = propertyVersions,
        moveVersions = moveVersions,
      )
    return CommandApplication(
      committed,
      CommandOutcome.Accepted(
        committedRevision = committedRevision,
        canonicalDocument = canonicalDocument,
        idempotentReplay = false,
        conflicts = trace.conflicts,
      ),
    )
  }

  fun undo(state: CollaborationState, command: UndoCommand): CommandApplication {
    val mutation = RejectedMutation.Undo(command)
    state.replayRejected(mutation)?.let {
      return it
    }
    return undoUnrecorded(state, command).retainRejection(mutation)
  }

  private fun undoUnrecorded(
    state: CollaborationState,
    command: UndoCommand,
  ): CommandApplication {
    state.undoRecords[command.operationId]?.let { prior ->
      if (prior.command != command) {
        return state.rejected(
          RejectionCode.OPERATION_ID_REUSED,
          "operation id ${command.operationId} was already used by a different undo",
        )
      }
      return CommandApplication(
        state,
        CommandOutcome.Accepted(
          prior.committedRevision,
          prior.canonicalDocument,
          idempotentReplay = true,
        ),
      )
    }
    if (command.operationId in state.acceptedCommands || command.operationId in state.redoRecords) {
      return state.rejected(
        RejectionCode.OPERATION_ID_REUSED,
        "operation id ${command.operationId} is already committed",
      )
    }
    state
      .validateCompensationEnvelope(
        command.designId,
        command.operationId,
        command.actorId,
        command.clientId,
        command.baseRevision,
      )
      ?.let {
        return it
      }
    val target =
      state.acceptedCommands[command.targetOperationId]
        ?: return state.rejected(
          RejectionCode.UNKNOWN_OPERATION,
          "unknown target operation ${command.targetOperationId}",
        )
    if (target.command.actorId != command.actorId) {
      return state.rejected(
        RejectionCode.ACTOR_MISMATCH,
        "actor ${command.actorId} cannot undo ${target.command.actorId}'s operation",
      )
    }
    if (target.command.operationId in state.compensatedOperationIds) {
      return state.rejected(
        RejectionCode.ALREADY_COMPENSATED,
        "operation ${target.command.operationId} is already compensated",
      )
    }
    if (
      target.propertyChanges.isEmpty() ||
        target.command.operations.any { it !is DesignOperation.SetProperty }
    ) {
      return state.rejected(
        RejectionCode.UNSUPPORTED_COMPENSATION,
        "this slice compensates scalar-only batches; structural compensation remains pending",
      )
    }
    target.propertyChanges.asReversed().distinctBy(PropertyChange::address).forEach { change ->
      val current =
        state.document.nodes[change.address.nodeId]?.properties?.get(change.address.property)
      val currentVersion = state.propertyVersions[change.address]
      if (current != change.after || currentVersion != target.committedRevision) {
        return state.rejected(
          RejectionCode.UNSAFE_COMPENSATION,
          "property changed after ${target.command.operationId} at revision $currentVersion",
          nodeId = change.address.nodeId,
          field = change.address.property,
        )
      }
    }

    val prepared = state.withStablePositions()
    var document = prepared.document
    target.propertyChanges.asReversed().forEach { change ->
      val node = document.nodes.getValue(change.address.nodeId)
      val properties =
        if (change.before == null) node.properties - change.address.property
        else node.properties + (change.address.property to change.before)
      document =
        document.copy(
          nodes = document.nodes + (node.id to node.copy(properties = JsonObject(properties)))
        )
    }
    val committedRevision = state.document.revision + 1
    document = document.copy(revision = committedRevision)
    val canonicalDocument = canonicalDocument(document)
    val record = AcceptedUndo(command, target, committedRevision, canonicalDocument)
    val propertyVersions =
      target.propertyChanges.fold(prepared.propertyVersions) { versions, change ->
        versions + (change.address to committedRevision)
      }
    val committed =
      prepared.copy(
        document = document,
        propertyVersions = propertyVersions,
        positionSnapshots = prepared.positionSnapshots + (committedRevision to prepared.positions),
        undoRecords = prepared.undoRecords + (command.operationId to record),
        compensatedOperationIds = prepared.compensatedOperationIds + target.command.operationId,
      )
    return CommandApplication(
      committed,
      CommandOutcome.Accepted(committedRevision, canonicalDocument, idempotentReplay = false),
    )
  }

  fun redo(state: CollaborationState, command: RedoCommand): CommandApplication {
    val mutation = RejectedMutation.Redo(command)
    state.replayRejected(mutation)?.let {
      return it
    }
    return redoUnrecorded(state, command).retainRejection(mutation)
  }

  private fun redoUnrecorded(
    state: CollaborationState,
    command: RedoCommand,
  ): CommandApplication {
    state.redoRecords[command.operationId]?.let { prior ->
      if (prior.command != command) {
        return state.rejected(
          RejectionCode.OPERATION_ID_REUSED,
          "operation id ${command.operationId} was already used by a different redo",
        )
      }
      return CommandApplication(
        state,
        CommandOutcome.Accepted(
          prior.committedRevision,
          prior.canonicalDocument,
          idempotentReplay = true,
        ),
      )
    }
    if (command.operationId in state.acceptedCommands || command.operationId in state.undoRecords) {
      return state.rejected(
        RejectionCode.OPERATION_ID_REUSED,
        "operation id ${command.operationId} is already committed",
      )
    }
    state
      .validateCompensationEnvelope(
        command.designId,
        command.operationId,
        command.actorId,
        command.clientId,
        command.baseRevision,
      )
      ?.let {
        return it
      }
    val undo =
      state.undoRecords[command.targetUndoOperationId]
        ?: return state.rejected(
          RejectionCode.UNKNOWN_OPERATION,
          "unknown undo operation ${command.targetUndoOperationId}",
        )
    if (undo.command.actorId != command.actorId) {
      return state.rejected(
        RejectionCode.ACTOR_MISMATCH,
        "actor ${command.actorId} cannot redo ${undo.command.actorId}'s undo",
      )
    }
    if (undo.redoneBy != null) {
      return state.rejected(
        RejectionCode.ALREADY_COMPENSATED,
        "undo ${undo.command.operationId} was already redone",
      )
    }
    undo.target.propertyChanges.distinctBy(PropertyChange::address).forEach { change ->
      val current =
        state.document.nodes[change.address.nodeId]?.properties?.get(change.address.property)
      val currentVersion = state.propertyVersions[change.address]
      if (current != change.before || currentVersion != undo.committedRevision) {
        return state.rejected(
          RejectionCode.UNSAFE_COMPENSATION,
          "property changed after ${undo.command.operationId} at revision $currentVersion",
          nodeId = change.address.nodeId,
          field = change.address.property,
        )
      }
    }

    val prepared = state.withStablePositions()
    var document = prepared.document
    undo.target.propertyChanges.forEach { change ->
      val node = document.nodes.getValue(change.address.nodeId)
      document =
        document.copy(
          nodes =
            document.nodes +
              (node.id to
                node.copy(
                  properties =
                    JsonObject(node.properties + (change.address.property to change.after))
                ))
        )
    }
    val committedRevision = state.document.revision + 1
    document = document.copy(revision = committedRevision)
    val canonicalDocument = canonicalDocument(document)
    val propertyVersions =
      undo.target.propertyChanges.fold(prepared.propertyVersions) { versions, change ->
        versions + (change.address to committedRevision)
      }
    val record =
      AcceptedRedo(command, undo.command.operationId, committedRevision, canonicalDocument)
    val committed =
      prepared.copy(
        document = document,
        propertyVersions = propertyVersions,
        positionSnapshots = prepared.positionSnapshots + (committedRevision to prepared.positions),
        undoRecords =
          prepared.undoRecords +
            (undo.command.operationId to undo.copy(redoneBy = command.operationId)),
        redoRecords = prepared.redoRecords + (command.operationId to record),
      )
    return CommandApplication(
      committed,
      CommandOutcome.Accepted(committedRevision, canonicalDocument, idempotentReplay = false),
    )
  }

  fun replay(
    initial: UiBuilderDocument,
    commands: Iterable<DesignCommand>,
    propertyValidator: CollaborationPropertyValidator? = null,
  ): CommandApplication {
    var application =
      CommandApplication(
        CollaborationState(initial),
        CommandOutcome.Accepted(initial.revision, canonicalDocument(initial), false),
      )
    commands.forEach { command ->
      application = apply(application.state, command, propertyValidator)
      if (application.outcome is CommandOutcome.Rejected) return application
    }
    return application
  }
}

fun canonicalDocument(document: UiBuilderDocument): String {
  // Keep this independent of platform crypto. A persistence/API layer may hash these canonical
  // bytes; reducer tests can compare the bytes directly on JVM and Wasm.
  val json = kotlinx.serialization.json.Json { encodeDefaults = true }
  return canonicalJson(json.encodeToJsonElement(UiBuilderDocument.serializer(), document))
}

private data class ReductionTrace(
  var state: CollaborationState,
  val conflicts: MutableList<ConflictNotice> = mutableListOf(),
  val propertyChanges: MutableList<PropertyChange> = mutableListOf(),
  val propertyTouches: MutableSet<PropertyAddress> = linkedSetOf(),
  val moveTouches: MutableSet<String> = linkedSetOf(),
  val batchPositionTouches: MutableSet<String> = linkedSetOf(),
)

private fun CollaborationState.applyOperation(
  operation: DesignOperation,
  propertyValidator: CollaborationPropertyValidator?,
  basePositions: Map<String, StableNodePosition>,
  operationKey: String,
  baseRevision: Int,
  trace: ReductionTrace,
): CollaborationState =
  when (operation) {
    is DesignOperation.InsertNode -> {
      val changed = insertNode(operation, basePositions, operationKey)
      trace.batchPositionTouches += operation.node.id
      changed
    }
    is DesignOperation.MoveNode -> {
      moveVersions[operation.nodeId]
        ?.takeIf { it > baseRevision }
        ?.let { overwrittenRevision ->
          trace.conflicts +=
            ConflictNotice(ConflictCode.STALE_MOVE, operation.nodeId, null, overwrittenRevision)
        }
      trace.moveTouches += operation.nodeId
      val changed = moveNode(operation, basePositions, operationKey)
      trace.batchPositionTouches += operation.nodeId
      changed
    }
    is DesignOperation.DeleteNode -> deleteNode(operation.nodeId)
    is DesignOperation.RestoreNode -> restoreNode(operation.nodeId)
    is DesignOperation.SetProperty -> {
      val address = PropertyAddress(operation.nodeId, operation.property)
      propertyVersions[address]
        ?.takeIf { it > baseRevision }
        ?.let { overwrittenRevision ->
          trace.conflicts +=
            ConflictNotice(
              ConflictCode.STALE_PROPERTY_WRITE,
              operation.nodeId,
              operation.property,
              overwrittenRevision,
            )
        }
      val before = document.nodes[operation.nodeId]?.properties?.get(operation.property)
      val changed = setProperty(operation, propertyValidator)
      trace.propertyTouches += address
      trace.propertyChanges += PropertyChange(address, before, operation.value)
      changed
    }
  }

private fun CollaborationState.insertNode(
  operation: DesignOperation.InsertNode,
  basePositions: Map<String, StableNodePosition>,
  operationKey: String,
): CollaborationState {
  if (operation.node.slots.values.any { it.isNotEmpty() }) {
    fail(
      RejectionCode.INVALID_LOCATION,
      "InsertNode accepts one detached node; insert children with later operations",
      operation.node.id,
    )
  }
  if (operation.node.id in document.nodes || isDeleted(operation.node.id)) {
    fail(
      RejectionCode.INVALID_LOCATION,
      "node already exists or is tombstoned: ${operation.node.id}",
    )
  }
  validateDestination(operation.parent, operation.afterNodeId, basePositions, movingNodeId = null)
  val position =
    allocatePosition(
      operation.parent,
      operation.afterNodeId,
      basePositions,
      operationKey,
      operation.node.id,
    )
  val withNode =
    copy(
      document = document.copy(nodes = document.nodes + (operation.node.id to operation.node)),
      positions = positions + (operation.node.id to position),
    )
  return withNode.rebuildLocation(operation.parent)
}

private fun CollaborationState.moveNode(
  operation: DesignOperation.MoveNode,
  basePositions: Map<String, StableNodePosition>,
  operationKey: String,
): CollaborationState {
  val node = liveNode(operation.nodeId)
  if (operation.afterNodeId == operation.nodeId) {
    fail(RejectionCode.INVALID_LOCATION, "a node cannot be positioned after itself")
  }
  val subtree = document.descendants(node.id)
  val destinationParent = operation.parent?.nodeId
  if (destinationParent != null && destinationParent in subtree) {
    fail(RejectionCode.CYCLE, "moving ${node.id} below $destinationParent would create a cycle")
  }
  validateDestination(operation.parent, operation.afterNodeId, basePositions, node.id)
  val oldParent = positions.getValue(node.id).parent
  val position =
    allocatePosition(operation.parent, operation.afterNodeId, basePositions, operationKey, node.id)
  var changed = copy(positions = positions + (node.id to position)).rebuildLocation(oldParent)
  changed = changed.rebuildLocation(operation.parent)
  return changed
}

private fun CollaborationState.deleteNode(nodeId: String): CollaborationState {
  liveNode(nodeId)
  val location = document.locationOf(nodeId)
  val subtreeIds = document.descendants(nodeId)
  val deletedNodes = document.nodes.filterKeys { it in subtreeIds }
  val deletedPositions = positions.filterKeys { it in subtreeIds }
  val detached = document.detach(nodeId, location)
  val remaining = detached.copy(nodes = detached.nodes - subtreeIds)
  return copy(
    document = remaining,
    positions = positions - subtreeIds,
    tombstones =
      tombstones +
        (nodeId to
          NodeTombstone(
            rootNodeId = nodeId,
            nodes = deletedNodes,
            location = location,
            deletedAtRevision = document.revision + 1,
            positions = deletedPositions,
          )),
  )
}

private fun CollaborationState.restoreNode(nodeId: String): CollaborationState {
  val tombstone = tombstones[nodeId]
  if (tombstone == null) {
    if (isDeleted(nodeId)) fail(RejectionCode.DELETED_NODE, "$nodeId is part of a deleted subtree")
    fail(RejectionCode.UNKNOWN_NODE, "no tombstone exists for $nodeId")
  }
  val collisions = tombstone.nodes.keys.intersect(document.nodes.keys)
  if (collisions.isNotEmpty()) {
    fail(RejectionCode.INVALID_LOCATION, "restore would duplicate nodes: ${collisions.sorted()}")
  }
  val rootPosition = tombstone.positions[nodeId]
  if (rootPosition == null) {
    val withNodes = document.copy(nodes = document.nodes + tombstone.nodes)
    val restored = withNodes.attachRestored(nodeId, tombstone.location)
    return copy(document = restored, tombstones = tombstones - nodeId).withStablePositions()
  }
  val restored =
    copy(
      document = document.copy(nodes = document.nodes + tombstone.nodes),
      positions = positions + tombstone.positions,
      tombstones = tombstones - nodeId,
    )
  return restored.rebuildLocation(rootPosition.parent)
}

private fun CollaborationState.setProperty(
  operation: DesignOperation.SetProperty,
  propertyValidator: CollaborationPropertyValidator?,
): CollaborationState {
  if (operation.property.isBlank()) {
    fail(RejectionCode.INVALID_COMMAND, "property name must be non-empty")
  }
  val node = liveNode(operation.nodeId)
  val encodedValue =
    operation.value as? JsonObject
      ?: fail(
        RejectionCode.MALFORMED_PROPERTY,
        "property ${operation.property} must be a typed object with a non-empty string type",
        node.id,
        operation.property,
      )
  val valueType =
    (encodedValue["type"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
  if (valueType.isNullOrBlank()) {
    fail(
      RejectionCode.MALFORMED_PROPERTY,
      "property ${operation.property} must be a typed object with a non-empty string type",
      node.id,
      operation.property,
    )
  }
  val wrapperIssue = propertyWrapperIssue(valueType, encodedValue)
  if (wrapperIssue != null) {
    fail(
      RejectionCode.MALFORMED_PROPERTY,
      "property ${operation.property} $wrapperIssue",
      node.id,
      operation.property,
    )
  }
  requireNotNull(propertyValidator)
    .validate(document, node.id, operation.property, encodedValue)
    ?.let { issue ->
      fail(
        RejectionCode.INVALID_PROPERTY,
        issue.message,
        node.id,
        operation.property,
      )
    }
  val changed =
    node.copy(
      properties =
        kotlinx.serialization.json.JsonObject(
          node.properties + (operation.property to encodedValue)
        )
    )
  return copy(document = document.copy(nodes = document.nodes + (node.id to changed)))
}

private val literalPropertyTypes =
  setOf(
    "assetKey",
    "bool",
    "color",
    "colorToken",
    "enum",
    "float",
    "insets",
    "int",
    "shapeToken",
    "string",
    "typographyToken",
  )

private fun propertyWrapperIssue(type: String, encodedValue: JsonObject): String? =
  when (type) {
    in literalPropertyTypes ->
      if (encodedValue.keys == setOf("type", "value")) null
      else "literal wrapper must contain exactly type and value"
    "state" ->
      if (
        encodedValue.keys == setOf("type", "variable") &&
          encodedValue.nonEmptyString("variable") != null
      )
        null
      else "state wrapper must contain exactly type and a non-empty variable"
    "stateEquals" ->
      if (
        encodedValue.keys == setOf("type", "variable", "value") &&
          encodedValue.nonEmptyString("variable") != null
      )
        null
      else "stateEquals wrapper must contain exactly type, variable, and value"
    "padding" -> {
      val fields = setOf("type", "startDp", "topDp", "endDp", "bottomDp")
      if (encodedValue.keys == fields && fields.minus("type").all(encodedValue::hasNumber)) null
      else "padding wrapper must contain exactly type and four numeric edge values"
    }
    "adaptiveGrid" ->
      if (
        encodedValue.keys == setOf("type", "minimumCellWidthDp") &&
          encodedValue.hasNumber("minimumCellWidthDp")
      )
        null
      else "adaptiveGrid wrapper must contain exactly type and numeric minimumCellWidthDp"
    else -> "uses unsupported wrapper type $type"
  }

private fun JsonObject.nonEmptyString(name: String): String? =
  (get(name) as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isString)
    ?.contentOrNull
    ?.takeIf(String::isNotBlank)

private fun JsonObject.hasNumber(name: String): Boolean =
  (get(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull != null

private fun CollaborationState.liveNode(nodeId: String): UiBuilderNode {
  document.nodes[nodeId]?.let {
    return it
  }
  if (isDeleted(nodeId)) fail(RejectionCode.DELETED_NODE, "$nodeId is deleted")
  fail(RejectionCode.UNKNOWN_NODE, "unknown node: $nodeId")
}

private fun CollaborationState.isDeleted(nodeId: String): Boolean =
  tombstones.values.any { nodeId in it.nodes }

private fun CollaborationState.withStablePositions(): CollaborationState {
  if (positions.isNotEmpty() || document.nodes.isEmpty()) {
    return if (document.revision in positionSnapshots) this
    else copy(positionSnapshots = positionSnapshots + (document.revision to positions))
  }
  val derived = linkedMapOf<String, StableNodePosition>()
  fun record(parent: ParentSlot?, children: List<String>) {
    children.forEachIndexed { index, nodeId ->
      derived[nodeId] =
        StableNodePosition(
          parent = parent,
          key = StablePositionKey(listOf((index + 1) * POSITION_STEP), "initial:$nodeId"),
        )
    }
  }
  record(null, document.roots)
  document.nodes.values.forEach { parent ->
    parent.slots.forEach { (slot, children) -> record(ParentSlot(parent.id, slot), children) }
  }
  val initialPropertyVersions =
    document.nodes.values
      .flatMap { node -> node.properties.keys.map { PropertyAddress(node.id, it) } }
      .associateWith { document.revision }
  return copy(
    positions = derived,
    positionSnapshots = positionSnapshots + (document.revision to derived),
    propertyVersions = initialPropertyVersions + propertyVersions,
    moveVersions = document.nodes.keys.associateWith { document.revision } + moveVersions,
  )
}

private fun CollaborationState.validateDestination(
  parent: ParentSlot?,
  afterNodeId: String?,
  basePositions: Map<String, StableNodePosition>,
  movingNodeId: String?,
) {
  if (parent != null) {
    val parentNode = liveNode(parent.nodeId)
    if (parent.slot !in parentNode.slots) {
      fail(RejectionCode.INVALID_LOCATION, "unknown slot ${parent.slot} on ${parent.nodeId}")
    }
  }
  if (afterNodeId == movingNodeId) {
    fail(RejectionCode.INVALID_LOCATION, "a node cannot be positioned after itself")
  }
  if (afterNodeId != null) {
    val currentAnchor = positions[afterNodeId]
    val baseAnchor = basePositions[afterNodeId]
    if (currentAnchor?.parent != parent || baseAnchor?.parent != parent) {
      fail(
        RejectionCode.INVALID_LOCATION,
        "insertion anchor $afterNodeId is not retained in the destination",
      )
    }
  }
}

private fun CollaborationState.allocatePosition(
  parent: ParentSlot?,
  afterNodeId: String?,
  basePositions: Map<String, StableNodePosition>,
  operationKey: String,
  nodeId: String,
): StableNodePosition {
  val siblings =
    basePositions.entries
      .filter { (id, position) -> position.parent == parent && id != nodeId }
      .sortedBy { it.value.key }
  val left = afterNodeId?.let { basePositions.getValue(it).key }
  val right =
    if (left == null) siblings.firstOrNull()?.value?.key
    else siblings.firstOrNull { it.value.key > left }?.value?.key
  val positionIdentity = "${operationKey.length}:$operationKey:${nodeId.length}:$nodeId"
  return StableNodePosition(
    parent = parent,
    key =
      StablePositionKey(
        between(left?.path, right?.path) + stableKeySuffix(operationKey) + stableKeySuffix(nodeId),
        positionIdentity,
      ),
  )
}

private fun CollaborationState.rebuildLocation(parent: ParentSlot?): CollaborationState {
  val children =
    positions.entries
      .filter { (nodeId, position) -> position.parent == parent && nodeId in document.nodes }
      .sortedWith(compareBy({ it.value.key }, { it.key }))
      .map { it.key }
  if (parent == null) return copy(document = document.copy(roots = children))
  val parentNode =
    document.nodes[parent.nodeId]
      ?: fail(RejectionCode.UNKNOWN_NODE, "unknown parent: ${parent.nodeId}")
  if (parent.slot !in parentNode.slots) {
    fail(RejectionCode.INVALID_LOCATION, "unknown slot ${parent.slot} on ${parent.nodeId}")
  }
  val changedParent = parentNode.copy(slots = parentNode.slots + (parent.slot to children))
  return copy(document = document.copy(nodes = document.nodes + (parent.nodeId to changedParent)))
}

private fun between(left: List<Int>?, right: List<Int>?): List<Int> {
  if (right == null) return left.orEmpty() + POSITION_STEP / 2
  val result = mutableListOf<Int>()
  var index = 0
  while (true) {
    val lower = left?.getOrNull(index) ?: 0
    val upper = right.getOrNull(index) ?: POSITION_STEP
    if (lower == upper) {
      result += lower
      index++
      continue
    }
    if (upper - lower > 1) {
      result += lower + (upper - lower) / 2
      return result
    }
    result += lower
    result += left?.drop(index + 1).orEmpty()
    result += POSITION_STEP / 2
    return result
  }
}

private fun stableKeySuffix(value: String): List<Int> =
  value.map { character -> character.code + 2 } + 1

private const val POSITION_STEP = 1024

private fun UiBuilderDocument.descendants(rootId: String): Set<String> {
  val found = linkedSetOf<String>()
  fun visit(nodeId: String) {
    if (!found.add(nodeId)) fail(RejectionCode.CYCLE, "cycle or duplicate child at $nodeId")
    val node = nodes[nodeId] ?: fail(RejectionCode.UNKNOWN_NODE, "unknown child node: $nodeId")
    node.slots.values.flatten().forEach(::visit)
  }
  visit(rootId)
  return found
}

private fun UiBuilderDocument.locationOf(nodeId: String): NodeLocation {
  val locations = mutableListOf<NodeLocation>()
  roots
    .indexOf(nodeId)
    .takeIf { it >= 0 }
    ?.let { index ->
      locations +=
        NodeLocation(
          afterNodeId = roots.getOrNull(index - 1),
          beforeNodeId = roots.getOrNull(index + 1),
          fallbackIndex = index,
        )
    }
  nodes.forEach { (parentId, parent) ->
    parent.slots.forEach { (slot, children) ->
      children
        .indexOf(nodeId)
        .takeIf { it >= 0 }
        ?.let { index ->
          locations +=
            NodeLocation(
              parent = ParentSlot(parentId, slot),
              afterNodeId = children.getOrNull(index - 1),
              beforeNodeId = children.getOrNull(index + 1),
              fallbackIndex = index,
            )
        }
    }
  }
  if (locations.size != 1) {
    fail(
      RejectionCode.INVALID_LOCATION,
      "$nodeId must have exactly one location, found ${locations.size}",
    )
  }
  return locations.single()
}

private fun UiBuilderDocument.attachRestored(
  nodeId: String,
  location: NodeLocation,
): UiBuilderDocument {
  if (location.parent == null) {
    val changed = roots.toMutableList()
    changed.insertAtRetainedLocation(nodeId, location)
    return copy(roots = changed)
  }
  val parent =
    nodes[location.parent.nodeId]
      ?: fail(RejectionCode.UNKNOWN_NODE, "unknown parent: ${location.parent.nodeId}")
  val changedChildren =
    parent.slots[location.parent.slot]?.toMutableList()
      ?: fail(
        RejectionCode.INVALID_LOCATION,
        "unknown slot ${location.parent.slot} on ${location.parent.nodeId}",
      )
  changedChildren.insertAtRetainedLocation(nodeId, location)
  val changedParent =
    parent.copy(slots = parent.slots + (location.parent.slot to changedChildren.toList()))
  return copy(nodes = nodes + (changedParent.id to changedParent))
}

/**
 * Legacy Wave 1 tombstones without retained position metadata use both neighbours and the former
 * index. Current tombstones restore their stable position key; this fallback keeps pre-key state
 * deterministic during the incubating schema transition.
 */
private fun MutableList<String>.insertAtRetainedLocation(value: String, location: NodeLocation) {
  location.afterNodeId?.let { anchor ->
    indexOf(anchor)
      .takeIf { it >= 0 }
      ?.let { index ->
        add(index + 1, value)
        return
      }
  }
  location.beforeNodeId?.let { anchor ->
    indexOf(anchor)
      .takeIf { it >= 0 }
      ?.let { index ->
        add(index, value)
        return
      }
  }
  add(location.fallbackIndex.coerceIn(0, size), value)
}

private fun UiBuilderDocument.detach(nodeId: String, location: NodeLocation): UiBuilderDocument {
  if (location.parent == null) return copy(roots = roots - nodeId)
  val parent =
    nodes[location.parent.nodeId]
      ?: fail(RejectionCode.UNKNOWN_NODE, "unknown parent: ${location.parent.nodeId}")
  val children = parent.slots[location.parent.slot].orEmpty()
  if (nodeId !in children) fail(RejectionCode.INVALID_LOCATION, "$nodeId is not in the named slot")
  val changed = parent.copy(slots = parent.slots + (location.parent.slot to (children - nodeId)))
  return copy(nodes = nodes + (changed.id to changed))
}

private fun UiBuilderDocument.attach(nodeId: String, location: NodeLocation): UiBuilderDocument {
  if (location.parent == null) {
    val changed = roots.toMutableList()
    changed.insertAfterChecked(nodeId, location.afterNodeId, "roots")
    return copy(roots = changed)
  }
  val parent =
    nodes[location.parent.nodeId]
      ?: fail(RejectionCode.UNKNOWN_NODE, "unknown parent: ${location.parent.nodeId}")
  if (location.parent.slot.isBlank()) fail(RejectionCode.INVALID_LOCATION, "slot must be non-empty")
  val changedChildren =
    parent.slots[location.parent.slot]?.toMutableList()
      ?: fail(
        RejectionCode.INVALID_LOCATION,
        "unknown slot ${location.parent.slot} on ${location.parent.nodeId}",
      )
  changedChildren.insertAfterChecked(nodeId, location.afterNodeId, location.parent.slot)
  val changedParent =
    parent.copy(slots = parent.slots + (location.parent.slot to changedChildren.toList()))
  return copy(nodes = nodes + (changedParent.id to changedParent))
}

private fun MutableList<String>.insertAfterChecked(value: String, anchor: String?, label: String) {
  if (anchor == null) {
    add(0, value)
    return
  }
  val anchorIndex = indexOf(anchor)
  if (anchorIndex < 0)
    fail(RejectionCode.INVALID_LOCATION, "unknown insertion anchor $anchor in $label")
  add(anchorIndex + 1, value)
}

private fun CollaborationState.replayRejected(mutation: RejectedMutation): CommandApplication? {
  if (mutation.operationId.isBlank()) return null
  val prior = rejectedOperations[mutation.operationId] ?: return null
  if (prior.mutation != mutation) {
    return rejected(
      RejectionCode.OPERATION_ID_REUSED,
      "operation id ${mutation.operationId} was already used by a rejected command",
    )
  }
  return CommandApplication(this, prior.outcome)
}

private fun CommandApplication.retainRejection(mutation: RejectedMutation): CommandApplication {
  val rejection = outcome as? CommandOutcome.Rejected ?: return this
  if (mutation.operationId.isBlank()) return this
  if (
    mutation.operationId in state.acceptedCommands ||
      mutation.operationId in state.undoRecords ||
      mutation.operationId in state.redoRecords
  ) {
    return this
  }
  return copy(
    state =
      state.copy(
        rejectedOperations =
          state.rejectedOperations +
            (mutation.operationId to RejectedOperation(mutation, rejection))
      )
  )
}

private fun CollaborationState.rejected(
  code: RejectionCode,
  message: String,
  operationIndex: Int? = null,
  nodeId: String? = null,
  field: String? = null,
): CommandApplication =
  CommandApplication(this, CommandOutcome.Rejected(code, message, operationIndex, nodeId, field))

private fun CollaborationState.validateCompensationEnvelope(
  designId: String,
  operationId: String,
  actorId: String,
  clientId: String,
  baseRevision: Int,
): CommandApplication? {
  if (designId != document.id) {
    return rejected(
      RejectionCode.DESIGN_MISMATCH,
      "command design $designId does not match ${document.id}",
    )
  }
  if (operationId.isBlank() || actorId.isBlank() || clientId.isBlank()) {
    return rejected(
      RejectionCode.INVALID_COMMAND,
      "operationId, actorId, and clientId are required",
    )
  }
  if (baseRevision != document.revision) {
    return rejected(
      RejectionCode.REVISION_MISMATCH,
      "compensation base revision $baseRevision does not match ${document.revision}",
    )
  }
  return null
}

private class ReducerFailure(
  val code: RejectionCode,
  message: String,
  val nodeId: String? = null,
  val field: String? = null,
) : IllegalArgumentException(message)

private fun fail(
  code: RejectionCode,
  message: String,
  nodeId: String? = null,
  field: String? = null,
): Nothing = throw ReducerFailure(code, message, nodeId, field)
