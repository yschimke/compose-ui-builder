package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityIssueCode
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

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

  /**
   * Unset one optional property, so the node takes the component's own default again. Required
   * properties can't be unset; unsetting an absent one is a no-op.
   *
   * On the wire this is `setProperty` with `{"type": "null"}`; `RemoveNodePropertyMutationV1` is
   * the explicit spelling the published protocol does not carry yet.
   */
  @Serializable
  @SerialName("removeNodeProperty")
  data class RemoveNodeProperty(val nodeId: String, val property: String) : DesignOperation

  @Serializable
  @SerialName("setStateVariable")
  data class SetStateVariable(val name: String, val declaration: JsonObject) : DesignOperation

  @Serializable
  @SerialName("removeStateVariable")
  data class RemoveStateVariable(val name: String) : DesignOperation

  @Serializable
  @SerialName("setEventBinding")
  data class SetEventBinding(val nodeId: String, val event: String, val actions: JsonArray) :
    DesignOperation

  @Serializable
  @SerialName("setEnvironment")
  data class SetEnvironment(val field: String, val value: JsonElement) : DesignOperation

  /**
   * Replace every modifier on one node, matching `SetModifiersMutationV1`: a chain is
   * order-dependent with no per-element identity. An empty list clears the chain.
   */
  @Serializable
  @SerialName("setModifiers")
  data class SetModifiers(val nodeId: String, val modifiers: JsonArray) : DesignOperation

  /**
   * Declare, or redeclare, one reusable component (`{"name": …, "root": …}` under [componentKey]);
   * the wire's `DeclareComponentMutationV1`. A body that is an extra root when declared stops being
   * a root, so "make component" is: move the subtree to the root list, then declare it.
   */
  @Serializable
  @SerialName("declareComponent")
  data class DeclareComponent(val componentKey: String, val declaration: JsonObject) :
    DesignOperation

  /**
   * Undeclare one component. Its body, if nothing else places it, is a root again, so the command
   * that removes a component also deletes or re-places its body.
   */
  @Serializable
  @SerialName("removeComponent")
  data class RemoveComponent(val componentKey: String) : DesignOperation

  /**
   * Replace what one placement passes to its component's body (the wire's
   * `SetComponentArgumentsMutationV1`). The placement keeps its id; whole-map, so an empty
   * [arguments] passes nothing.
   */
  @Serializable
  @SerialName("setComponentArguments")
  data class SetComponentArguments(val nodeId: String, val arguments: JsonObject) : DesignOperation
}

@Serializable data class ParentSlot(val nodeId: String, val slot: String)

data class CollaborationState(
  val document: UiBuilderDocument,
  val tombstones: Map<String, NodeTombstone> = emptyMap(),
  val acceptedCommands: Map<String, AcceptedCommand> = emptyMap(),
  val positions: Map<String, StableNodePosition> = emptyMap(),
  val positionSnapshots: Map<Int, Map<String, StableNodePosition>> = emptyMap(),
  val propertyVersions: Map<PropertyAddress, Int> = emptyMap(),
  /**
   * Which revision last wrote each node's modifier chain, keyed by node — the chain is one value.
   */
  val modifierVersions: Map<String, Int> = emptyMap(),
  val moveVersions: Map<String, Int> = emptyMap(),
  val structuralVersions: Map<String, Int> = emptyMap(),
  val environmentVersions: Map<String, Int> = emptyMap(),
  val activeOperationVersions: Map<String, Int> = emptyMap(),
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

/** Separate namespaces prevent an event named `text` from conflicting with a text property. */
enum class PropertyTarget {
  Property,
  StateVariable,
  EventBinding,
  /** A `components` entry; the address's node id is empty and its property is the key. */
  Component,
  /** A placement's whole `component.arguments` map; the property is always `arguments`. */
  ComponentArguments,
}

data class PropertyAddress(
  val nodeId: String,
  val property: String,
  val target: PropertyTarget = PropertyTarget.Property,
)

internal fun UiBuilderDocument.valueAt(address: PropertyAddress): JsonElement? =
  when (address.target) {
    PropertyTarget.Property -> nodes[address.nodeId]?.properties?.get(address.property)
    PropertyTarget.EventBinding -> nodes[address.nodeId]?.eventBindings?.get(address.property)
    PropertyTarget.StateVariable -> stateVariables[address.property]
    PropertyTarget.Component -> components[address.property]
    PropertyTarget.ComponentArguments -> nodes[address.nodeId]?.component?.get("arguments")
  }

internal fun UiBuilderDocument.withValueAt(
  address: PropertyAddress,
  value: JsonElement?,
): UiBuilderDocument {
  fun JsonObject.updated() =
    JsonObject(if (value == null) this - address.property else this + (address.property to value))
  if (address.target == PropertyTarget.StateVariable)
    return copy(stateVariables = stateVariables.updated())
  if (address.target == PropertyTarget.Component) return withComponent(address.property, value)
  val node =
    nodes[address.nodeId]
      ?: fail(RejectionCode.UNKNOWN_NODE, "unknown node ${address.nodeId}", address.nodeId)
  val changed =
    when (address.target) {
      PropertyTarget.Property -> node.copy(properties = node.properties.updated())
      PropertyTarget.EventBinding -> node.copy(eventBindings = node.eventBindings.updated())
      PropertyTarget.ComponentArguments -> {
        val placement =
          node.component
            ?: fail(RejectionCode.INVALID_COMMAND, "${node.id} places no component", node.id)
        node.copy(
          component =
            JsonObject(
              if (value == null) placement - "arguments" else placement + ("arguments" to value)
            )
        )
      }
      PropertyTarget.StateVariable,
      PropertyTarget.Component -> error("handled above")
    }
  return copy(nodes = nodes + (node.id to changed))
}

/**
 * Write one component declaration, keeping the root list consistent: a body declared as an extra
 * root leaves it; a body undeclared and placed nowhere returns to it. Derived from the document
 * alone, so undo gets the same answer.
 */
internal fun UiBuilderDocument.withComponent(key: String, value: JsonElement?): UiBuilderDocument {
  val before = componentRootOf(components[key])
  val after = componentRootOf(value)
  val declared =
    copy(
      components = JsonObject(if (value == null) components - key else components + (key to value))
    )
  var roots = declared.roots
  if (after != null && after in roots && roots.size > 1) roots = roots - after
  if (
    before != null &&
      before != after &&
      before in nodes &&
      before !in roots &&
      declared.components.values.none { componentRootOf(it) == before } &&
      nodes.values.none { node -> node.slots.values.any { before in it } }
  )
    roots = roots + before
  return declared.copy(roots = roots)
}

internal fun componentRootOf(declaration: JsonElement?): String? =
  ((declaration as? JsonObject)?.get("root") as? kotlinx.serialization.json.JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content

/** The bodies no root list or slot holds: the subtrees only a placement draws. */
internal fun UiBuilderDocument.detachedComponentRoots(): Set<String> {
  val placed = roots.toSet() + nodes.values.flatMap { node -> node.slots.values.flatten() }
  return components.values
    .mapNotNullTo(mutableSetOf()) { componentRootOf(it) }
    .filterTo(mutableSetOf()) { it !in placed }
}

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
  val modifierChanges: List<ModifierChange> = emptyList(),
  val environmentChanges: List<EnvironmentChange> = emptyList(),
  val structuralChanges: List<StructuralChange> = emptyList(),
  val compensationChanges: List<CompensationChange> = emptyList(),
  val conflicts: List<ConflictNotice> = emptyList(),
)

data class PropertyChange(
  val address: PropertyAddress,
  val before: JsonElement?,
  /**
   * The value written, or [JsonNull] for a [DesignOperation.RemoveNodeProperty] (properties are
   * always typed objects, so bare null is unambiguous). Read through [afterValue].
   */
  val after: JsonElement,
  /**
   * The revision that owned [address] before this command wrote it, or null if nothing had. Undo
   * restores it rather than stamping its own revision (see [CollaborationReducer.undo]), so a
   * second undo isn't refused as `UNSAFE_COMPENSATION`.
   */
  val beforeVersion: Int? = null,
) {
  /** The property's value once this change applied — null when the change removed it. */
  val afterValue: JsonElement?
    get() = after.takeUnless { it is JsonNull }
}

data class EnvironmentChange(
  val field: String,
  val before: JsonElement?,
  /** Null when the command cleared [field]: absent, as [before] is for a field never set. */
  val after: JsonElement?,
  /** The revision that owned [field] before this command wrote it — see [PropertyChange]. */
  val beforeVersion: Int? = null,
)

/**
 * One node's whole modifier chain, before and after; node-granular because the chain is one value.
 */
data class ModifierChange(
  val nodeId: String,
  val before: JsonArray,
  val after: JsonArray,
  /**
   * The revision that owned this node's chain before this command wrote it — see [PropertyChange].
   */
  val beforeVersion: Int? = null,
)

enum class StructuralChangeKind {
  INSERT,
  MOVE,
  DELETE,
  RESTORE,
}

data class StructuralChange(
  val kind: StructuralChangeKind,
  val nodeId: String,
  val affectedNodeIds: Set<String>,
  val beforePosition: StableNodePosition? = null,
  val afterPosition: StableNodePosition? = null,
)

sealed interface CompensationChange {
  data class Property(val change: PropertyChange) : CompensationChange

  data class Modifiers(val change: ModifierChange) : CompensationChange

  data class Environment(val change: EnvironmentChange) : CompensationChange

  data class Structure(val change: StructuralChange) : CompensationChange
}

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
  val targetActiveRevision: Int,
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

data class CollaborationEvent(
  val mutation: RejectedMutation,
  val outcome: CommandOutcome,
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
  INVALID_DOCUMENT,
  REVISION_NOT_RETAINED,
  INVALID_LOCATION,
  CYCLE,
  ACTOR_MISMATCH,
  ALREADY_COMPENSATED,
  UNSAFE_COMPENSATION,
  UNSUPPORTED_COMPENSATION,
  UNKNOWN_OPERATION,
  REPLAY_DIVERGENCE,
}

data class CommandApplication(val state: CollaborationState, val outcome: CommandOutcome)

data class PropertyWriteIssue(val message: String, val field: String? = null)

data class DocumentWriteIssue(
  val message: String,
  val nodeId: String? = null,
  val field: String? = null,
)

fun interface CollaborationPropertyValidator {
  fun validate(
    document: UiBuilderDocument,
    nodeId: String,
    property: String,
    encodedValue: JsonObject,
  ): PropertyWriteIssue?

  /**
   * The write-time rules for a node that arrives whole, asked of every property at once. Defaults
   * to nothing for reducer-only tests; the catalog-backed validator uses
   * `CapabilityValidator.insertIssue`. [PropertyWriteIssue.field] locates the rejection.
   */
  fun validateInsert(document: UiBuilderDocument, node: UiBuilderNode): PropertyWriteIssue? = null

  /**
   * Whether [property] may be taken off [node] — refused for a required one by the catalog-backed
   * validator; nothing to say by default.
   */
  fun validateRemove(
    document: UiBuilderDocument,
    node: UiBuilderNode,
    property: String,
  ): PropertyWriteIssue? = null
}

fun interface CollaborationDocumentValidator {
  fun validate(document: UiBuilderDocument): DocumentWriteIssue?
}

class CapabilityDocumentWriteValidator(private val validator: CapabilityValidator) :
  CollaborationDocumentValidator {
  override fun validate(document: UiBuilderDocument): DocumentWriteIssue? =
    validator.validate(document).issues.firstOrNull()?.let {
      DocumentWriteIssue(it.message, it.nodeId, it.field)
    }
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
    // The wrapper question first: a `string` on an `allowedValues` property also passes `jsonType`.
    // See `CapabilityValidator.writeWrapperIssue`.
    validator.writeWrapperIssue(node, property, encodedValue, document.assets.keys)?.let {
      return PropertyWriteIssue(it.message, property)
    }
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
      ?.let { PropertyWriteIssue(it.message, property) }
  }

  override fun validateInsert(
    document: UiBuilderDocument,
    node: UiBuilderNode,
  ): PropertyWriteIssue? =
    validator.insertIssue(node, document.assets.keys)?.let {
      PropertyWriteIssue(it.message, it.field)
    }

  override fun validateRemove(
    document: UiBuilderDocument,
    node: UiBuilderNode,
    property: String,
  ): PropertyWriteIssue? =
    validator.removeIssue(node, property)?.let { PropertyWriteIssue(it.message, property) }
}
