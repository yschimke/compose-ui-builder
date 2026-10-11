package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiExpressions
import ee.schimke.composeai.uibuilder.renderer.sdk.uiBuilderModifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

internal data class ReductionTrace(
  var state: CollaborationState,
  val conflicts: MutableList<ConflictNotice> = mutableListOf(),
  val propertyChanges: MutableList<PropertyChange> = mutableListOf(),
  val propertyTouches: MutableSet<PropertyAddress> = linkedSetOf(),
  val modifierTouches: MutableSet<String> = linkedSetOf(),
  val modifierChanges: MutableList<ModifierChange> = mutableListOf(),
  val environmentChanges: MutableList<EnvironmentChange> = mutableListOf(),
  val environmentTouches: MutableSet<String> = linkedSetOf(),
  val moveTouches: MutableSet<String> = linkedSetOf(),
  val structuralTouches: MutableSet<String> = linkedSetOf(),
  val structuralChanges: MutableList<StructuralChange> = mutableListOf(),
  val compensationChanges: MutableList<CompensationChange> = mutableListOf(),
  val batchPositionTouches: MutableSet<String> = linkedSetOf(),
)

internal fun CollaborationState.applyOperation(
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
      propertyValidator?.validateInsert(changed.document, operation.node)?.let { issue ->
        fail(RejectionCode.INVALID_PROPERTY, issue.message, operation.node.id, issue.field)
      }
      trace.batchPositionTouches += operation.node.id
      trace.structuralTouches += operation.node.id
      val change =
        StructuralChange(
          StructuralChangeKind.INSERT,
          operation.node.id,
          setOf(operation.node.id),
          afterPosition = changed.positions.getValue(operation.node.id),
        )
      trace.structuralChanges += change
      trace.compensationChanges += CompensationChange.Structure(change)
      changed
    }
    is DesignOperation.MoveNode -> {
      val beforePosition = positions[operation.nodeId]
      moveVersions[operation.nodeId]
        ?.takeIf { it > baseRevision }
        ?.let { overwrittenRevision ->
          trace.conflicts +=
            ConflictNotice(ConflictCode.STALE_MOVE, operation.nodeId, null, overwrittenRevision)
        }
      trace.moveTouches += operation.nodeId
      val changed = moveNode(operation, basePositions, operationKey)
      trace.batchPositionTouches += operation.nodeId
      trace.structuralTouches += operation.nodeId
      val change =
        StructuralChange(
          StructuralChangeKind.MOVE,
          operation.nodeId,
          setOf(operation.nodeId),
          beforePosition = beforePosition,
          afterPosition = changed.positions.getValue(operation.nodeId),
        )
      trace.structuralChanges += change
      trace.compensationChanges += CompensationChange.Structure(change)
      changed
    }
    is DesignOperation.DeleteNode -> {
      val affected = document.descendants(operation.nodeId)
      val beforePosition = positions[operation.nodeId]
      val changed = deleteNode(operation.nodeId)
      trace.structuralTouches += affected
      trace.moveTouches += affected
      val change =
        StructuralChange(
          StructuralChangeKind.DELETE,
          operation.nodeId,
          affected,
          beforePosition = beforePosition,
        )
      trace.structuralChanges += change
      trace.compensationChanges += CompensationChange.Structure(change)
      changed
    }
    is DesignOperation.RestoreNode -> {
      val tombstone = tombstones[operation.nodeId]
      val affected = tombstone?.nodes?.keys.orEmpty()
      val changed = restoreNode(operation.nodeId)
      trace.structuralTouches += affected
      trace.moveTouches += affected
      val change =
        StructuralChange(
          StructuralChangeKind.RESTORE,
          operation.nodeId,
          affected,
          afterPosition = changed.positions[operation.nodeId],
        )
      trace.structuralChanges += change
      trace.compensationChanges += CompensationChange.Structure(change)
      changed
    }
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
      val change = PropertyChange(address, before, operation.value, propertyVersions[address])
      trace.propertyChanges += change
      trace.compensationChanges += CompensationChange.Property(change)
      changed
    }
    is DesignOperation.RemoveNodeProperty -> {
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
      val changed = removeProperty(operation, propertyValidator)
      trace.propertyTouches += address
      val change = PropertyChange(address, before, JsonNull, propertyVersions[address])
      trace.propertyChanges += change
      trace.compensationChanges += CompensationChange.Property(change)
      changed
    }
    is DesignOperation.SetStateVariable ->
      writeBehavior(
        PropertyAddress("", operation.name, PropertyTarget.StateVariable),
        operation.declaration,
        baseRevision,
        trace,
      )
    is DesignOperation.RemoveStateVariable -> {
      if (operation.name !in document.stateVariables)
        fail(
          RejectionCode.INVALID_COMMAND,
          "unknown state variable ${operation.name}",
          field = operation.name,
        )
      writeBehavior(
        PropertyAddress("", operation.name, PropertyTarget.StateVariable),
        null,
        baseRevision,
        trace,
      )
    }
    is DesignOperation.SetEventBinding ->
      writeBehavior(
        PropertyAddress(operation.nodeId, operation.event, PropertyTarget.EventBinding),
        operation.actions.takeIf { it.isNotEmpty() },
        baseRevision,
        trace,
      )
    is DesignOperation.SetModifiers -> {
      modifierVersions[operation.nodeId]
        ?.takeIf { it > baseRevision }
        ?.let { overwrittenRevision ->
          // Reported as a stale *property* write, because that is the only conflict code the wire
          // can carry for a node-and-field write, and `modifiers` is the field it names.
          trace.conflicts +=
            ConflictNotice(
              ConflictCode.STALE_PROPERTY_WRITE,
              operation.nodeId,
              "modifiers",
              overwrittenRevision,
            )
        }
      val before = document.nodes[operation.nodeId]?.modifiers ?: JsonArray(emptyList())
      val changed = setModifiers(operation)
      trace.modifierTouches += operation.nodeId
      val change =
        ModifierChange(
          operation.nodeId,
          before,
          operation.modifiers,
          modifierVersions[operation.nodeId],
        )
      trace.modifierChanges += change
      trace.compensationChanges += CompensationChange.Modifiers(change)
      changed
    }
    is DesignOperation.DeclareComponent -> {
      componentRootOf(operation.declaration)?.let { root ->
        if (root !in document.nodes)
          fail(
            RejectionCode.INVALID_DOCUMENT,
            "component ${operation.componentKey} names a body root `$root` this design does not hold",
            field = root,
          )
      }
        ?: fail(
          RejectionCode.INVALID_DOCUMENT,
          "component ${operation.componentKey} names no body root",
          field = operation.componentKey,
        )
      writeBehavior(
        PropertyAddress("", operation.componentKey, PropertyTarget.Component),
        operation.declaration,
        baseRevision,
        trace,
      )
    }
    is DesignOperation.RemoveComponent -> {
      if (operation.componentKey !in document.components)
        fail(
          RejectionCode.INVALID_COMMAND,
          "this design declares no component ${operation.componentKey}",
          field = operation.componentKey,
        )
      // The wire's own obligation: a placement whose component is gone draws nothing while
      // reporting success.
      document.nodes.values
        .firstOrNull { it.placedComponentKey() == operation.componentKey }
        ?.let { placement ->
          fail(
            RejectionCode.INVALID_DOCUMENT,
            "node ${placement.id} still places component ${operation.componentKey}",
            placement.id,
            operation.componentKey,
          )
        }
      writeBehavior(
        PropertyAddress("", operation.componentKey, PropertyTarget.Component),
        null,
        baseRevision,
        trace,
      )
    }
    is DesignOperation.SetComponentArguments -> {
      val node =
        document.nodes[operation.nodeId]
          ?: fail(RejectionCode.UNKNOWN_NODE, "unknown node ${operation.nodeId}", operation.nodeId)
      if (node.placedComponentKey() == null)
        fail(
          RejectionCode.INVALID_COMMAND,
          "${operation.nodeId} is not a component placement",
          operation.nodeId,
          "arguments",
        )
      operation.arguments.entries
        .firstOrNull { (_, value) -> value !is JsonObject }
        ?.let { (key, _) ->
          fail(
            RejectionCode.INVALID_PROPERTY,
            "argument $key is not a value",
            operation.nodeId,
            key,
          )
        }
      writeBehavior(
        PropertyAddress(operation.nodeId, "arguments", PropertyTarget.ComponentArguments),
        operation.arguments,
        baseRevision,
        trace,
      )
    }
    is DesignOperation.SetEnvironment -> {
      val before = document.environment[operation.field]
      val changed =
        copy(
          document =
            document.copy(
              // JSON null is how an optional field is cleared (a typeface reset), and a cleared
              // field is an absent one, so the document reads as if it had never been set.
              environment =
                JsonObject(
                  if (operation.value is JsonNull) document.environment - operation.field
                  else document.environment + (operation.field to operation.value)
                )
            )
        )
      val change =
        EnvironmentChange(
          operation.field,
          before,
          operation.value.takeUnless { it is JsonNull },
          environmentVersions[operation.field],
        )
      trace.environmentTouches += operation.field
      trace.environmentChanges += change
      trace.compensationChanges += CompensationChange.Environment(change)
      changed
    }
  }

private fun CollaborationState.writeBehavior(
  address: PropertyAddress,
  value: JsonElement?,
  baseRevision: Int,
  trace: ReductionTrace,
): CollaborationState {
  if (address.property.isBlank())
    fail(RejectionCode.INVALID_COMMAND, "name must not be blank", field = address.property)
  val before = document.valueAt(address)
  val changed = copy(document = document.withValueAt(address, value))
  propertyVersions[address]
    ?.takeIf { it > baseRevision }
    ?.let { revision ->
      trace.conflicts +=
        ConflictNotice(
          ConflictCode.STALE_PROPERTY_WRITE,
          address.nodeId,
          address.property,
          revision,
        )
    }
  val change = PropertyChange(address, before, value ?: JsonNull, propertyVersions[address])
  trace.propertyTouches += address
  trace.propertyChanges += change
  trace.compensationChanges += CompensationChange.Property(change)
  return changed
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
    fail(RejectionCode.UNKNOWN_NODE, "no tombstone exists for $nodeId", nodeId)
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

/**
 * Replace one node's modifier chain. Every entry must be one the renderer can apply
 * ([uiBuilderModifier] answers null otherwise), so the write is refused rather than silently losing
 * layout. Whether the component may carry it is checked by the document validator after the batch.
 */
private fun CollaborationState.setModifiers(
  operation: DesignOperation.SetModifiers
): CollaborationState {
  val node = liveNode(operation.nodeId)
  operation.modifiers.forEachIndexed { index, element ->
    val modifier =
      element as? JsonObject
        ?: fail(
          RejectionCode.MALFORMED_PROPERTY,
          "modifier at index $index must be a typed object",
          node.id,
          "modifiers[$index]",
        )
    val type = (modifier["type"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
    if (type.isNullOrBlank()) {
      fail(
        RejectionCode.MALFORMED_PROPERTY,
        "modifier at index $index has no string type",
        node.id,
        "modifiers[$index]",
      )
    }
    if (uiBuilderModifier(modifier) == null) {
      fail(
        RejectionCode.INVALID_PROPERTY,
        "modifier $type is not one this renderer can apply",
        node.id,
        "modifiers[$index]",
      )
    }
  }
  return copy(
    document =
      document.copy(
        nodes = document.nodes + (node.id to node.copy(modifiers = operation.modifiers))
      )
  )
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

/** A write to one property address — a set or an unset — which undo rewinds as a scalar lane. */
internal fun DesignOperation.isPropertyWrite(): Boolean =
  this is DesignOperation.SetProperty ||
    this is DesignOperation.RemoveNodeProperty ||
    this is DesignOperation.SetStateVariable ||
    this is DesignOperation.RemoveStateVariable ||
    this is DesignOperation.SetEventBinding ||
    this is DesignOperation.DeclareComponent ||
    this is DesignOperation.RemoveComponent ||
    this is DesignOperation.SetComponentArguments

private fun CollaborationState.removeProperty(
  operation: DesignOperation.RemoveNodeProperty,
  propertyValidator: CollaborationPropertyValidator?,
): CollaborationState {
  if (operation.property.isBlank()) {
    fail(RejectionCode.INVALID_COMMAND, "property name must be non-empty")
  }
  val node = liveNode(operation.nodeId)
  requireNotNull(propertyValidator).validateRemove(document, node, operation.property)?.let {
    fail(RejectionCode.INVALID_PROPERTY, it.message, node.id, operation.property)
  }
  val changed =
    node.copy(
      properties = kotlinx.serialization.json.JsonObject(node.properties - operation.property)
    )
  return copy(document = document.copy(nodes = document.nodes + (node.id to changed)))
}

internal fun CollaborationState.validateStructuralCompensation(
  changes: List<StructuralChange>,
  expectedRevision: Int,
  undo: Boolean,
): CommandApplication? {
  changes
    .flatMap { it.affectedNodeIds }
    .distinct()
    .forEach { nodeId ->
      val version = structuralVersions[nodeId]
      if (version != expectedRevision) {
        return rejected(
          RejectionCode.UNSAFE_COMPENSATION,
          "structure changed after revision $expectedRevision at revision $version",
          nodeId = nodeId,
        )
      }
    }
  changes.forEach { change ->
    val destructive =
      (undo &&
        (change.kind == StructuralChangeKind.INSERT ||
          change.kind == StructuralChangeKind.RESTORE)) ||
        (!undo && change.kind == StructuralChangeKind.DELETE)
    if (destructive) {
      propertyVersions.entries
        .firstOrNull { (address, version) ->
          address.nodeId in change.affectedNodeIds && version > expectedRevision
        }
        ?.let { (address, version) ->
          return rejected(
            RejectionCode.UNSAFE_COMPENSATION,
            "property changed after revision $expectedRevision at revision $version",
            nodeId = address.nodeId,
            field = address.property,
          )
        }
    }
  }
  return null
}

internal fun CollaborationState.compensate(
  change: CompensationChange,
  undo: Boolean,
): CollaborationState =
  when (change) {
    is CompensationChange.Property -> compensateProperty(change.change, undo)
    is CompensationChange.Modifiers -> compensateModifiers(change.change, undo)
    is CompensationChange.Environment -> compensateEnvironment(change.change, undo)
    is CompensationChange.Structure -> compensateStructure(change.change, undo)
  }

private fun CollaborationState.compensateEnvironment(
  change: EnvironmentChange,
  undo: Boolean,
): CollaborationState {
  val value = if (undo) change.before else change.after
  val environment =
    if (value == null || value is JsonNull) document.environment - change.field
    else document.environment + (change.field to value)
  return copy(document = document.copy(environment = JsonObject(environment)))
}

private fun CollaborationState.compensateProperty(
  change: PropertyChange,
  undo: Boolean,
): CollaborationState {
  val expected = if (undo) change.afterValue else change.before
  if (document.valueAt(change.address) != expected) {
    fail(
      RejectionCode.UNSAFE_COMPENSATION,
      "${change.address.property} no longer has its compensable value",
      change.address.nodeId,
      change.address.property,
    )
  }
  return copy(
    document = document.withValueAt(change.address, if (undo) change.before else change.afterValue)
  )
}

private fun CollaborationState.compensateModifiers(
  change: ModifierChange,
  undo: Boolean,
): CollaborationState {
  val node = liveNode(change.nodeId)
  val expected = if (undo) change.after else change.before
  if (node.modifiers != expected) {
    fail(
      RejectionCode.UNSAFE_COMPENSATION,
      "the modifiers on ${change.nodeId} no longer have their compensable value",
      change.nodeId,
      "modifiers",
    )
  }
  val restored = if (undo) change.before else change.after
  return copy(
    document = document.copy(nodes = document.nodes + (node.id to node.copy(modifiers = restored)))
  )
}

internal fun CollaborationState.compensateStructure(
  change: StructuralChange,
  undo: Boolean,
): CollaborationState =
  when (change.kind) {
    StructuralChangeKind.INSERT ->
      if (undo) deleteNode(change.nodeId) else restoreNode(change.nodeId)
    StructuralChangeKind.MOVE ->
      relocateStable(
        change.nodeId,
        (if (undo) change.beforePosition else change.afterPosition)
          ?: fail(RejectionCode.INVALID_LOCATION, "move position was not retained", change.nodeId),
      )
    StructuralChangeKind.DELETE ->
      if (undo) restoreNode(change.nodeId) else deleteNode(change.nodeId)
    StructuralChangeKind.RESTORE ->
      if (undo) deleteNode(change.nodeId) else restoreNode(change.nodeId)
  }

private fun CollaborationState.relocateStable(
  nodeId: String,
  destination: StableNodePosition,
): CollaborationState {
  liveNode(nodeId)
  val destinationParent = destination.parent
  if (destinationParent != null) {
    // The slot need not already be there; see `validateDestination`.
    liveNode(destinationParent.nodeId)
    if (destinationParent.nodeId in document.descendants(nodeId)) {
      fail(RejectionCode.CYCLE, "restoring the move would create a cycle", nodeId)
    }
  }
  val currentPosition =
    positions[nodeId]
      ?: fail(RejectionCode.INVALID_LOCATION, "position for $nodeId was not retained", nodeId)
  val oldParent = currentPosition.parent
  var changed = copy(positions = positions + (nodeId to destination)).rebuildLocation(oldParent)
  changed = changed.rebuildLocation(destinationParent)
  return changed
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

internal fun propertyWrapperIssue(type: String, encodedValue: JsonObject): String? =
  when (type) {
    "object" -> {
      val fields = encodedValue["fields"] as? JsonObject
      if (encodedValue.keys != setOf("type", "fields") || fields == null)
        "object wrapper must contain exactly type and a fields object"
      else
        fields.entries.firstNotNullOfOrNull { (name, value) ->
          val nested = value as? JsonObject
          val nestedType = nested?.nonEmptyString("type")
          if (nested == null || nestedType == null) "object field $name must be a typed value"
          else propertyWrapperIssue(nestedType, nested)?.let { "object field $name $it" }
        }
    }
    // A typed adapter's list property, nesting wrappers like `object`. Which element shape it holds
    // is the catalog's question (`PropertyValueShapes.issue`, through the property validator).
    "list" -> {
      val values = encodedValue["values"] as? JsonArray
      if (encodedValue.keys != setOf("type", "values") || values == null)
        "list wrapper must contain exactly type and a values array"
      else
        values.withIndex().firstNotNullOfOrNull { (index, value) ->
          val nested = value as? JsonObject
          val nestedType = nested?.nonEmptyString("type")
          if (nested == null || nestedType == null) "list value $index must be a typed value"
          else propertyWrapperIssue(nestedType, nested)?.let { "list value $index $it" }
        }
    }
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
    // A component body reading one of its placement's arguments. Which key, and whether a
    // placement passes it, is the argument-binding inspection's question over the whole document.
    "binding" ->
      if (
        encodedValue.keys == setOf("type", "value") && encodedValue.nonEmptyString("value") != null
      )
        null
      else "binding wrapper must contain exactly type and a non-empty value"
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
    // A computed value: its tree's shape here, its types against the document's state at the
    // catalog check, which is where the declarations are.
    UiExpressions.EXPR,
    UiExpressions.SYSTEM -> UiExpressions.shapeIssue(encodedValue)
    // A subset of `PropertyValueKinds.WRAPPER_TYPES`. `WrapperVocabularyTest` holds the union
    // against the corpus.
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
  fail(RejectionCode.UNKNOWN_NODE, "unknown node: $nodeId", nodeId)
}

private fun CollaborationState.isDeleted(nodeId: String): Boolean =
  tombstones.values.any { nodeId in it.nodes }

internal fun CollaborationState.withStablePositions(): CollaborationState {
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
  record(null, document.roots + document.detachedComponentRoots().sorted())
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
    structuralVersions =
      document.nodes.keys.associateWith { document.revision } + structuralVersions,
  )
}

private fun CollaborationState.validateDestination(
  parent: ParentSlot?,
  afterNodeId: String?,
  basePositions: Map<String, StableNodePosition>,
  movingNodeId: String?,
) {
  // The parent must exist, but the slot need not: a slot with no children is an absent key, and
  // requiring it made empty slots unreachable for wire-replayed documents. Which slot names are
  // valid is the document validator's question.
  if (parent != null) {
    liveNode(parent.nodeId)
  }
  if (movingNodeId != null && afterNodeId == movingNodeId) {
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
  // A detached component body keeps the parentless position it was declared from, but it is not a
  // root: rebuilding the root list for some other node must not hand it back to the screen.
  val detached = if (parent == null) document.detachedComponentRoots() else emptySet()
  val children =
    positions.entries
      .filter { (nodeId, position) ->
        position.parent == parent && nodeId in document.nodes && nodeId !in detached
      }
      .sortedWith(compareBy({ it.value.key }, { it.key }))
      .map { it.key }
  if (parent == null) return copy(document = document.copy(roots = children))
  val parentNode =
    document.nodes[parent.nodeId]
      ?: fail(RejectionCode.UNKNOWN_NODE, "unknown parent: ${parent.nodeId}", parent.nodeId)
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

/**
 * A document has at most one root, every node is placed exactly once, and nothing cycles.
 *
 * A second root can't be undone without deleting a whole subtree and is refused by every export, so
 * it is bounded here. Zero roots is legal: a design starts empty and the first insert creates the
 * root.
 *
 * For callers holding a whole document; the reducer asks the two halves separately (see
 * [requireSingleRoot]).
 */
internal fun UiBuilderDocument.requireValidTopology() {
  requireSingleRoot()
  requireValidPlacement()
  requireKnownComponents()
}

/**
 * At most one root, asked on its own. Separate because a half-applied command may legitimately
 * break it (wrapping a root in a board inserts beside it, then moves it inside), so the reducer
 * checks it once after the command, as `PersistentUiBuilderService` does.
 */
internal fun UiBuilderDocument.requireSingleRoot() {
  if (roots.size > 1) {
    fail(RejectionCode.INVALID_DOCUMENT, "a design has at most one root; found ${roots.size}")
  }
}

/**
 * Every placement names a component the document declares. Checked once per command like
 * [requireSingleRoot], since making a component passes through intermediate states.
 */
internal fun UiBuilderDocument.requireKnownComponents() {
  nodes.values.forEach { node ->
    val key = node.component?.let { (it["componentKey"] as? JsonPrimitive)?.contentOrNull }
    if (node.component != null && key !in components)
      fail(RejectionCode.INVALID_DOCUMENT, "unknown component $key", node.id)
  }
}

/**
 * Every node placed exactly once, every placed node known, and nothing cycling. Node-level rules,
 * so they hold after each operation.
 */
internal fun UiBuilderDocument.requireValidPlacement() {
  val locations = mutableMapOf<String, Int>()
  fun record(nodeId: String) {
    if (nodeId !in nodes) {
      fail(RejectionCode.UNKNOWN_NODE, "unknown child node: $nodeId", nodeId)
    }
    locations[nodeId] = locations.getOrElse(nodeId) { 0 } + 1
  }
  roots.forEach(::record)
  nodes.values.forEach { node -> node.slots.values.flatten().forEach(::record) }
  val componentRoots = components.mapValues { (key, definition) ->
    ((definition as? JsonObject)?.get("root") as? JsonPrimitive)?.takeIf { it.isString }?.content
      ?: fail(RejectionCode.INVALID_DOCUMENT, "component $key names no body root")
  }
  componentRoots.values.forEach { root ->
    // A declaration owns its detached body once. Existing declarations over a screen subtree
    // remain valid, and placing that component does not create a second structural parent.
    if (root !in locations) record(root)
  }
  nodes.keys.sorted().forEach { nodeId ->
    val count = locations[nodeId] ?: 0
    if (count != 1) {
      fail(
        RejectionCode.INVALID_LOCATION,
        "$nodeId must have exactly one root or parent location, found $count",
        nodeId,
      )
    }
  }
  val visiting = mutableSetOf<String>()
  val visited = mutableSetOf<String>()
  fun visit(nodeId: String) {
    if (nodeId in visiting) fail(RejectionCode.CYCLE, "cycle at $nodeId", nodeId)
    if (!visited.add(nodeId)) return
    if (visiting.size >= 128)
      fail(RejectionCode.INVALID_DOCUMENT, "nesting exceeds 128 levels", nodeId)
    visiting += nodeId
    val node = nodes.getValue(nodeId)
    node.slots.values.flatten().forEach(::visit)
    // Unknown components are [requireKnownComponents]'s question, asked after the command.
    node.component?.let { placement ->
      val key = (placement["componentKey"] as? JsonPrimitive)?.contentOrNull
      componentRoots[key]?.let(::visit)
    }
    visiting -= nodeId
  }
  roots.forEach(::visit)
  componentRoots.values.forEach(::visit)
  if (visited.size != nodes.size) fail(RejectionCode.CYCLE, "unreachable cycle in design")
}

internal fun UiBuilderNode.placedComponentKey(): String? =
  (component?.get("componentKey") as? JsonPrimitive)?.contentOrNull

private fun UiBuilderDocument.descendants(rootId: String): Set<String> {
  val found = linkedSetOf<String>()
  fun visit(nodeId: String) {
    if (!found.add(nodeId)) fail(RejectionCode.CYCLE, "cycle or duplicate child at $nodeId")
    val node =
      nodes[nodeId] ?: fail(RejectionCode.UNKNOWN_NODE, "unknown child node: $nodeId", nodeId)
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
      ?: fail(
        RejectionCode.UNKNOWN_NODE,
        "unknown parent: ${location.parent.nodeId}",
        location.parent.nodeId,
      )
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
      ?: fail(
        RejectionCode.UNKNOWN_NODE,
        "unknown parent: ${location.parent.nodeId}",
        location.parent.nodeId,
      )
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
      ?: fail(
        RejectionCode.UNKNOWN_NODE,
        "unknown parent: ${location.parent.nodeId}",
        location.parent.nodeId,
      )
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

internal fun CollaborationState.replayRejected(mutation: RejectedMutation): CommandApplication? {
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

internal fun CommandApplication.retainRejection(mutation: RejectedMutation): CommandApplication {
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

internal fun CollaborationState.rejected(
  code: RejectionCode,
  message: String,
  operationIndex: Int? = null,
  nodeId: String? = null,
  field: String? = null,
): CommandApplication =
  CommandApplication(this, CommandOutcome.Rejected(code, message, operationIndex, nodeId, field))

internal fun CollaborationState.validateCompensationEnvelope(
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

internal class ReducerFailure(
  val code: RejectionCode,
  message: String,
  val nodeId: String? = null,
  val field: String? = null,
) : IllegalArgumentException(message)

internal fun fail(
  code: RejectionCode,
  message: String,
  nodeId: String? = null,
  field: String? = null,
): Nothing = throw ReducerFailure(code, message, nodeId, field)

/**
 * The document at every revision from [oldest] up to the current one, newest first.
 *
 * Rewinds by applying the recorded compensating changes in reverse ([compensate]), so no
 * per-revision copies are stored. One walk covers the whole range.
 *
 * **Read-only**: version stamps are not rewound, since nothing submits from here. Stops early
 * rather than approximating at a revision the record doesn't cover or a compensation that refuses.
 */
internal fun CollaborationState.documentsBackTo(oldest: Int): Map<Int, UiBuilderDocument> {
  val documents = linkedMapOf(document.revision to document)
  if (oldest >= document.revision) return documents
  val inverses = inverseByRevision()
  var state = withStablePositions()
  for (target in document.revision downTo oldest + 1) {
    val inverse = inverses[target] ?: break
    state =
      try {
        inverse(state)
      } catch (failure: ReducerFailure) {
        break
      }
    documents[target - 1] = state.document.copy(revision = target - 1)
  }
  return documents
}

/** The document as it stood at [revision], or null where [documentsBackTo] cannot reach it. */
internal fun CollaborationState.documentAtRevision(revision: Int): UiBuilderDocument? =
  if (revision > document.revision) null else documentsBackTo(revision)[revision]

/**
 * The oldest revision [documentsBackTo] can reach: the bottom of the unbroken run of recorded
 * mutations back from the current revision (a client that opened from a snapshot lacks earlier
 * ones).
 */
internal fun CollaborationState.reconstructableFromRevision(): Int {
  val recorded = inverseByRevision().keys
  var oldest = document.revision
  while (oldest > 0 && oldest in recorded) oldest--
  return oldest
}

/**
 * Every recorded mutation as the change that takes the document back over it, keyed by the revision
 * it committed. All three kinds: an undo is inverted by replaying its target forwards (a redo), and
 * a redo by taking it back again.
 */
private fun CollaborationState.inverseByRevision():
  Map<Int, (CollaborationState) -> CollaborationState> {
  val inverses = mutableMapOf<Int, (CollaborationState) -> CollaborationState>()
  acceptedCommands.values.forEach { accepted ->
    inverses[accepted.committedRevision] = { state ->
      accepted.compensationChanges.asReversed().fold(state) { carried, change ->
        carried.compensate(change, undo = true)
      }
    }
  }
  undoRecords.values.forEach { undone ->
    inverses[undone.committedRevision] = { state ->
      undone.target.compensationChanges.fold(state) { carried, change ->
        carried.compensate(change, undo = false)
      }
    }
  }
  redoRecords.values.forEach { redone ->
    inverses[redone.committedRevision] = { state ->
      val target =
        undoRecords[redone.targetUndoOperationId]?.target
          ?: fail(
            RejectionCode.UNKNOWN_OPERATION,
            "redo ${redone.command.operationId} has no undo record to reverse",
          )
      target.compensationChanges.asReversed().fold(state) { carried, change ->
        carried.compensate(change, undo = true)
      }
    }
  }
  return inverses
}
