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
)

data class NodeTombstone(
  val rootNodeId: String,
  val nodes: Map<String, UiBuilderNode>,
  val location: NodeLocation,
  val deletedAtRevision: Int,
)

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
)

sealed interface CommandOutcome {
  data class Accepted(
    val committedRevision: Int,
    val canonicalDocument: String,
    val idempotentReplay: Boolean,
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
  INVALID_LOCATION,
  CYCLE,
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
 * This first slice deliberately requires an exact base revision. The idempotency lookup runs before
 * that check so a transport retry still returns its original result after later commits. A rejected
 * batch never exposes the working copy used while validating earlier operations in that batch. It
 * is an executable Wave 1 collaboration slice, not a claim that the full Gate 0 concurrency,
 * compensation, retention, or persistence semantics are implemented.
 */
object CollaborationReducer {
  fun apply(
    state: CollaborationState,
    command: DesignCommand,
    propertyValidator: CollaborationPropertyValidator? = null,
  ): CommandApplication {
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
    if (command.baseRevision != state.document.revision) {
      return state.rejected(
        RejectionCode.REVISION_MISMATCH,
        "base revision ${command.baseRevision} does not match ${state.document.revision}",
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

    var working = state.copy(acceptedCommands = state.acceptedCommands)
    command.operations.forEachIndexed { index, operation ->
      try {
        working = working.applyOperation(operation, propertyValidator)
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
    val document = working.document.copy(revision = committedRevision)
    val canonicalDocument = canonicalDocument(document)
    val accepted =
      AcceptedCommand(
        command = command,
        committedRevision = committedRevision,
        canonicalDocument = canonicalDocument,
      )
    val committed =
      working.copy(
        document = document,
        acceptedCommands = state.acceptedCommands + (command.operationId to accepted),
      )
    return CommandApplication(
      committed,
      CommandOutcome.Accepted(
        committedRevision = committedRevision,
        canonicalDocument = canonicalDocument,
        idempotentReplay = false,
      ),
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

private fun CollaborationState.applyOperation(
  operation: DesignOperation,
  propertyValidator: CollaborationPropertyValidator?,
): CollaborationState =
  when (operation) {
    is DesignOperation.MoveNode -> moveNode(operation)
    is DesignOperation.DeleteNode -> deleteNode(operation.nodeId)
    is DesignOperation.RestoreNode -> restoreNode(operation.nodeId)
    is DesignOperation.SetProperty -> setProperty(operation, propertyValidator)
  }

private fun CollaborationState.moveNode(operation: DesignOperation.MoveNode): CollaborationState {
  val node = liveNode(operation.nodeId)
  if (operation.afterNodeId == operation.nodeId) {
    fail(RejectionCode.INVALID_LOCATION, "a node cannot be positioned after itself")
  }
  val subtree = document.descendants(node.id)
  val destinationParent = operation.parent?.nodeId
  if (destinationParent != null && destinationParent in subtree) {
    fail(RejectionCode.CYCLE, "moving ${node.id} below $destinationParent would create a cycle")
  }

  val oldLocation = document.locationOf(node.id)
  var detached = document.detach(node.id, oldLocation)
  detached = detached.attach(node.id, NodeLocation(operation.parent, operation.afterNodeId))
  return copy(document = detached)
}

private fun CollaborationState.deleteNode(nodeId: String): CollaborationState {
  liveNode(nodeId)
  val location = document.locationOf(nodeId)
  val subtreeIds = document.descendants(nodeId)
  val deletedNodes = document.nodes.filterKeys { it in subtreeIds }
  val detached = document.detach(nodeId, location)
  val remaining = detached.copy(nodes = detached.nodes - subtreeIds)
  return copy(
    document = remaining,
    tombstones =
      tombstones +
        (nodeId to
          NodeTombstone(
            rootNodeId = nodeId,
            nodes = deletedNodes,
            location = location,
            deletedAtRevision = document.revision + 1,
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
  val withNodes = document.copy(nodes = document.nodes + tombstone.nodes)
  val restored = withNodes.attachRestored(nodeId, tombstone.location)
  return copy(document = restored, tombstones = tombstones - nodeId)
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
 * Tombstones retain both neighbours and the former index. Restore prefers the surviving previous
 * neighbour, then the surviving next neighbour, and finally the clamped former index. This is a
 * deterministic Wave 1 fallback, not the stable position-key algorithm required for full Gate 0
 * concurrent insertion semantics.
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

private fun CollaborationState.rejected(
  code: RejectionCode,
  message: String,
  operationIndex: Int? = null,
  nodeId: String? = null,
  field: String? = null,
): CommandApplication =
  CommandApplication(this, CommandOutcome.Rejected(code, message, operationIndex, nodeId, field))

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
