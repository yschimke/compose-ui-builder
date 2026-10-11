@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.protocol.*
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.encodeToJsonElement

private const val GITHUB_ACTOR_PREFIX = "github:"

/**
 * A GitHub login is case-insensitive and the host signs sessions over `login.lowercase()`, so
 * `github:` actor ids are folded on the way in and compared canonically (fixing mis-cased stored
 * grants). Only `github:` folds: case is meaningful in `agent:<fingerprint>`.
 */
internal fun canonicalActorId(actorId: String): String =
  if (actorId.startsWith(GITHUB_ACTOR_PREFIX))
    GITHUB_ACTOR_PREFIX + actorId.removePrefix(GITHUB_ACTOR_PREFIX).lowercase()
  else actorId

internal fun sameActor(left: String, right: String): Boolean =
  canonicalActorId(left) == canonicalActorId(right)

/**
 * The grants that actually govern: one per actor, the latest by
 * [DesignActorGrantV1.grantedAtEpochMillis] (position breaks ties).
 *
 * Older records can hold both `github:Alice` and `github:alice`; without collapsing, `any` would
 * union their roles and [listItem] could show a different role from the one enforced. Applied at
 * read time, and the access-mutation path starts from this, so the next change heals storage.
 */
internal fun DesignAccessControlV1.effectiveGrants(): List<DesignActorGrantV1> {
  if (actorGrants.size < 2) return actorGrants
  // Runs on every authorization check, so only build a second list when there is something to
  // collapse.
  val canonical = actorGrants.mapTo(HashSet(actorGrants.size), { canonicalActorId(it.actorId) })
  if (canonical.size == actorGrants.size) return actorGrants
  return actorGrants
    .groupingBy { canonicalActorId(it.actorId) }
    .reduce { _, kept, next ->
      if (next.grantedAtEpochMillis >= kept.grantedAtEpochMillis) next else kept
    }
    .values
    .toList()
}

/** [effectiveGrants] applied, for anywhere an access record is read rather than asked. */
internal fun DesignAccessControlV1.collapsed(): DesignAccessControlV1 =
  effectiveGrants().let { if (it.size == actorGrants.size) this else copy(actorGrants = it) }

internal fun DesignAccessControlV1.allows(actorId: String, action: DesignAccessActionV1): Boolean =
  allowsPersonally(actorId, action) ||
    effectiveGrants().any {
      it.actorId == UiBuilderPublicAccess.ANYONE_ACTOR_ID && action in it.allowedActions
    }

/**
 * [allows] without the [UiBuilderPublicAccess.ANYONE_ACTOR_ID] grant: "is this design one of mine",
 * which is what a listing asks (otherwise every public design lists for everyone).
 */
internal fun DesignAccessControlV1.allowsPersonally(
  actorId: String,
  action: DesignAccessActionV1,
): Boolean =
  sameActor(actorId, ownerActorId) ||
    effectiveGrants().any { sameActor(it.actorId, actorId) && action in it.allowedActions }

internal fun DesignAccessControlV1.allowsPersonally(
  actor: AuthenticatedUiBuilderActor,
  action: DesignAccessActionV1,
): Boolean = actor.accessIdentities.any { allowsPersonally(it, action) }

internal fun DesignAccessControlV1.allows(
  actor: AuthenticatedUiBuilderActor,
  action: DesignAccessActionV1,
): Boolean = actor.accessIdentities.any { allows(it, action) }

internal fun PersistedDesignV1.allows(actorId: String, action: DesignAccessActionV1): Boolean =
  access.allows(actorId, action)

/**
 * The same question asked of a whole identity: an actor may act, or the human it acts for may. The
 * one place delegation is honoured — a delegate reaches exactly its principal's designs and
 * actions.
 */
internal fun PersistedDesignV1.allows(
  actor: AuthenticatedUiBuilderActor,
  action: DesignAccessActionV1,
): Boolean = actor.accessIdentities.any { allows(it, action) }

/**
 * True when this actor owns the design itself. Owner-only actions (managing access, transferring
 * ownership, and delete bar [deletableBy]'s one exception) are never delegated: an actor acting for
 * the owner may read, write and export through [allows] like any editor, but these stay with the
 * owner's own credential.
 */
internal fun DesignAccessControlV1.ownedBy(actor: AuthenticatedUiBuilderActor): Boolean =
  sameActor(actor.actorId, ownerActorId)

/** True when this actor acts for the design's owner (and so edits it, without owning it). */
private fun DesignAccessControlV1.delegatedByOwner(actor: AuthenticatedUiBuilderActor): Boolean =
  actor.onBehalfOfActorId?.let { sameActor(it, ownerActorId) } == true

internal fun PersistedDesignV1.ownedBy(actor: AuthenticatedUiBuilderActor): Boolean =
  access.ownedBy(actor)

/**
 * True when this actor may delete the design: its owner, or the delegate that created it acting for
 * that same owner. The one owner-only action a delegate shares, and only on its own creations, so
 * an agent can clean up the probes it made without reaching the rest of its principal's designs.
 */
internal fun DesignAccessControlV1.deletableBy(
  actor: AuthenticatedUiBuilderActor,
  createdByDelegateActorId: String?,
): Boolean =
  ownedBy(actor) ||
    (delegatedByOwner(actor) &&
      createdByDelegateActorId != null &&
      sameActor(actor.actorId, createdByDelegateActorId))

internal fun PersistedDesignV1.deletableBy(actor: AuthenticatedUiBuilderActor): Boolean =
  access.deletableBy(actor, createdByDelegateActorId)

internal fun PersistedDesignV1.listItem(actor: AuthenticatedUiBuilderActor): DesignListItemV1 =
  DesignListItemV1(
    document.id,
    document.title,
    document.revision,
    access.accessRevision,
    document.catalogPin,
    createdAtEpochMillis,
    updatedAtEpochMillis,
    access.ownerActorId,
    access.requesterAccess(actor),
  )

/**
 * The list row for a design the store could not read, built from the header its quarantine kept, so
 * it is listed titled and owned rather than as a bare id.
 */
internal fun StoredDesignHeaderV3.quarantinedListItem(
  designId: String,
  actor: AuthenticatedUiBuilderActor,
): DesignListItemV1 =
  DesignListItemV1(
    designId,
    title,
    revision,
    access.accessRevision,
    catalogPin,
    createdAtEpochMillis,
    updatedAtEpochMillis,
    access.ownerActorId,
    access.requesterAccess(actor),
  )

/**
 * What the asking actor may do here, reported under the actor's own id but resolved through its
 * principal when it has one.
 */
private fun DesignAccessControlV1.requesterAccess(
  actor: AuthenticatedUiBuilderActor
): DesignActorAccessV1 =
  if (ownedBy(actor))
    DesignActorAccessV1(actor.actorId, DesignAccessRoleV1.OWNER, DesignAccessActionV1.entries)
  else if (delegatedByOwner(actor))
    DesignActorAccessV1(actor.actorId, DesignAccessRoleV1.EDITOR, DesignAccessActionV1.entries)
  else {
    // A reader who reached the design only because it is public is described by the public grant.
    val grant =
      actor.accessIdentities.firstNotNullOfOrNull { identity ->
        effectiveGrants().firstOrNull { sameActor(it.actorId, identity) }
      } ?: effectiveGrants().first { it.actorId == UiBuilderPublicAccess.ANYONE_ACTOR_ID }
    DesignActorAccessV1(actor.actorId, grant.role, grant.allowedActions)
  }

internal fun PersistedDesignV1.retainedFromSequence(): Long =
  history.firstOrNull()?.outcome?.sequence?.minus(1) ?: lastSequence

/**
 * The oldest sequence a whole revision is still retained for, which is not [retainedFromSequence]
 * (the operation log's floor, right for deltas). A `SNAPSHOT_REQUIRED` must quote this floor, or a
 * client could loop asking for a missing revision.
 */
internal fun PersistedDesignV1.retainedSnapshotFromSequence(): Long {
  // The floor is the start of the unbroken run ending at the head: a pinned branch fork point can
  // sit below a gap (`UI_BUILDER_BRANCHES.md`).
  var index = revisionSnapshots.lastIndex
  if (index < 0) return lastSequence
  while (
    index > 0 &&
      revisionSnapshots[index - 1].document.revision ==
        revisionSnapshots[index].document.revision - 1
  ) {
    index--
  }
  return revisionSnapshots[index].sequence
}

internal fun PersistedDesignV1.deltaAfter(afterSequence: Long, limit: Int): ServiceDeltaV1 {
  val available = history.filter { it.outcome.sequence > afterSequence }
  val page = available.take(limit)
  return ServiceDeltaV1(
    designId = document.id,
    afterSequence = afterSequence,
    throughSequence = page.lastOrNull()?.outcome?.sequence ?: afterSequence,
    currentRevision = document.revision,
    retainedFromSequence = retainedFromSequence(),
    operations = page,
    hasMore = page.size < available.size,
  )
}

internal fun UiBuilderSubmission.toProtocol(
  actor: AuthenticatedUiBuilderActor
): DesignSubmissionV1 =
  when (this) {
    is UiBuilderSubmission.Batch ->
      DesignCommandV1.Builder(
          designId,
          operationId,
          actor.actorId,
          clientId,
          baseRevision,
          operations,
        )
        .also { it.stalenessBaseRevision = stalenessBaseRevision }
        .build()
    is UiBuilderSubmission.Undo ->
      UndoCommandV1(
        designId,
        operationId,
        actor.actorId,
        clientId,
        baseRevision,
        targetOperationId,
      )
    is UiBuilderSubmission.Redo ->
      RedoCommandV1(
        designId,
        operationId,
        actor.actorId,
        clientId,
        baseRevision,
        targetUndoOperationId,
      )
  }

internal fun DesignSubmissionV1.designId(): String =
  when (this) {
    is DesignCommandV1 -> designId
    is UndoCommandV1 -> designId
    is RedoCommandV1 -> designId
  }

internal fun DesignSubmissionV1.operationId(): String =
  when (this) {
    is DesignCommandV1 -> operationId
    is UndoCommandV1 -> operationId
    is RedoCommandV1 -> operationId
  }

internal fun DesignSubmissionV1.actorId(): String =
  when (this) {
    is DesignCommandV1 -> actorId
    is UndoCommandV1 -> actorId
    is RedoCommandV1 -> actorId
  }

internal fun DesignSubmissionV1.clientId(): String =
  when (this) {
    is DesignCommandV1 -> clientId
    is UndoCommandV1 -> clientId
    is RedoCommandV1 -> clientId
  }

internal fun DesignSubmissionV1.baseRevision(): Long =
  when (this) {
    is DesignCommandV1 -> baseRevision
    is UndoCommandV1 -> baseRevision
    is RedoCommandV1 -> baseRevision
  }

internal fun UiBuilderPresence.toProtocol(actor: AuthenticatedUiBuilderActor): PresenceV1 =
  PresenceV1(
    actor.actorId,
    clientId,
    displayName,
    colorArgbHex,
    selectedNodeIds,
    pointerX?.let { PointerV1(it, requireNotNull(pointerY)) },
    observedRevision,
  )

internal fun DesignDocumentV1.snapshotOrFail(
  nodeId: String,
  positions: Map<String, StableNodePositionV1>,
  index: Int,
): NodeTreeSnapshotV1 =
  snapshotOrNull(nodeId, positions)
    ?: fail(RejectionCodeV1.UNKNOWN_NODE, "unknown node", index, nodeId)

internal fun DesignDocumentV1.snapshot(
  nodeId: String,
  positions: Map<String, StableNodePositionV1>,
): NodeTreeSnapshotV1 = requireNotNull(snapshotOrNull(nodeId, positions))

internal fun DesignDocumentV1.snapshotOrNull(
  nodeId: String,
  positions: Map<String, StableNodePositionV1>,
): NodeTreeSnapshotV1? {
  if (nodeId !in nodes) return null
  val collected = linkedMapOf<String, DesignNodeV1>()
  fun visit(id: String) {
    val node = nodes.getValue(id)
    collected[id] = node
    node.slots.values.flatten().forEach(::visit)
  }
  visit(nodeId)
  return NodeTreeSnapshotV1(
    nodeId,
    collected,
    locationOf(nodeId),
    positions.filterKeys { it in collected },
  )
}

private fun DesignDocumentV1.locationOf(nodeId: String): NodeLocationV1 {
  fun inList(values: List<String>, parent: ParentSlotV1?): NodeLocationV1? {
    val index = values.indexOf(nodeId)
    if (index < 0) return null
    return NodeLocationV1(
      parent,
      afterNodeId = values.getOrNull(index - 1),
      beforeNodeId = values.getOrNull(index + 1),
    )
  }
  inList(roots, null)?.let {
    return it
  }
  nodes.values.forEach { parent ->
    parent.slots.forEach { (slot, children) ->
      inList(children, ParentSlotV1(parent.id, slot))?.let {
        return it
      }
    }
  }
  error("node $nodeId has no placement")
}

internal fun DesignDocumentV1.removeSnapshot(snapshot: NodeTreeSnapshotV1): DesignDocumentV1 {
  val withoutPlacement = removePlacement(snapshot.rootNodeId)
  return withoutPlacement.copy(nodes = withoutPlacement.nodes - snapshot.nodes.keys)
}

private fun DesignDocumentV1.removePlacement(nodeId: String): DesignDocumentV1 {
  if (nodeId in roots) return copy(roots = roots - nodeId)
  nodes.values.forEach { parent ->
    parent.slots.forEach { (slot, children) ->
      if (nodeId in children) {
        val changed = parent.copy(slots = parent.slots + (slot to (children - nodeId)))
        return copy(nodes = nodes + (parent.id to changed))
      }
    }
  }
  fail(RejectionCodeV1.INVALID_LOCATION, "node has no placement", nodeId = nodeId)
}

internal fun derivePositions(document: DesignDocumentV1): Map<String, StableNodePositionV1> {
  val result = linkedMapOf<String, StableNodePositionV1>()
  fun add(values: List<String>, parent: ParentSlotV1?) {
    values.forEachIndexed { index, nodeId ->
      result[nodeId] =
        StableNodePositionV1(
          parent,
          StablePositionKeyV1(listOf((index + 1) * POSITION_STEP), "initial:$nodeId"),
        )
    }
  }
  add(document.roots + document.detachedComponentRoots().sorted(), null)
  document.nodes.values
    .sortedBy { it.id }
    .forEach { parent ->
      parent.slots.toSortedMap().forEach { (slot, children) ->
        add(children, ParentSlotV1(parent.id, slot))
      }
    }
  return result
}

internal fun allocatePosition(
  parent: ParentSlotV1?,
  location: NodeLocationV1,
  basePositions: Map<String, StableNodePositionV1>,
  operationKey: String,
  nodeId: String,
): StableNodePositionV1 {
  if (location.parent != parent) {
    fail(RejectionCodeV1.INVALID_LOCATION, "location parent mismatch", nodeId = nodeId)
  }
  val siblings =
    basePositions
      .filter { (id, position) -> id != nodeId && position.parent == parent }
      .toList()
      .sortedWith(compareBy<Pair<String, StableNodePositionV1>>({ it.second.key }, { it.first }))
  fun anchor(id: String?): Pair<String, StableNodePositionV1>? {
    if (id == null) return null
    return siblings.firstOrNull { it.first == id }
      ?: fail(
        RejectionCodeV1.INVALID_LOCATION,
        "stable location anchor is unavailable in the requested parent",
        nodeId = id,
      )
  }
  val after = anchor(location.afterNodeId)
  val before = anchor(location.beforeNodeId)
  if (after != null && before != null && after.second.key >= before.second.key) {
    fail(RejectionCodeV1.INVALID_LOCATION, "location anchors are reversed", nodeId = nodeId)
  }
  val left =
    when {
      after != null -> after.second.key
      before != null -> siblings.getOrNull(siblings.indexOf(before) - 1)?.second?.key
      else -> siblings.lastOrNull()?.second?.key
    }
  val right =
    when {
      before != null -> before.second.key
      after != null -> siblings.getOrNull(siblings.indexOf(after) + 1)?.second?.key
      else -> null
    }
  return StableNodePositionV1(
    parent,
    StablePositionKeyV1(
      between(left?.path, right?.path) + stableKeySuffix(operationKey) + stableKeySuffix(nodeId),
      "$operationKey:$nodeId",
    ),
  )
}

private fun between(left: List<Int>?, right: List<Int>?): List<Int> {
  if (right == null) return left.orEmpty() + POSITION_MIDPOINT
  val result = mutableListOf<Int>()
  var index = 0
  while (true) {
    val low = left?.getOrNull(index) ?: 0
    val high = right.getOrNull(index) ?: POSITION_MAX
    if (high - low > 1) {
      result += low + ((high - low) / 2)
      return result
    }
    result += low
    index++
  }
}

private fun stableKeySuffix(value: String): List<Int> = value.map { it.code + 2 }.let { it + 1 }

internal fun DesignDocumentV1.rebuildLocation(
  positions: Map<String, StableNodePositionV1>,
  parent: ParentSlotV1?,
): DesignDocumentV1 {
  // A detached component body keeps the parentless position it was declared from, but it is not a
  // root: rebuilding the root list for some other node must not hand it back to the screen.
  val detached = if (parent == null) detachedComponentRoots() else emptySet()
  val children =
    positions
      .filter { (id, position) -> id in nodes && position.parent == parent && id !in detached }
      .toList()
      .sortedWith(compareBy<Pair<String, StableNodePositionV1>>({ it.second.key }, { it.first }))
      .map { it.first }
  if (parent == null) return copy(roots = children)
  val parentNode =
    nodes[parent.nodeId]
      ?: fail(RejectionCodeV1.INVALID_LOCATION, "unknown parent", nodeId = parent.nodeId)
  return copy(
    nodes =
      nodes +
        (parentNode.id to parentNode.copy(slots = parentNode.slots + (parent.slot to children)))
  )
}

/**
 * Write one component declaration, keeping the root list honest about which subtree is the screen.
 *
 * A body declared while an extra root leaves the root list (it is now a definition); a body that
 * loses its declaration and is placed nowhere returns to it. Both directions read the document
 * alone, so compensations agree with forward writes. Mirrors `UiBuilderDocument.withComponent`.
 */
internal fun DesignDocumentV1.withComponent(
  key: String,
  declaration: DesignComponentV1?,
): DesignDocumentV1 {
  val before = components[key]?.root
  val after = declaration?.root
  val declared =
    copy(
      components = if (declaration == null) components - key else components + (key to declaration)
    )
  var roots = declared.roots
  if (after != null && after in roots && roots.size > 1) roots = roots - after
  if (
    before != null &&
      before != after &&
      before in nodes &&
      before !in roots &&
      declared.components.values.none { it.root == before } &&
      nodes.values.none { node -> node.slots.values.any { before in it } }
  )
    roots = roots + before
  return declared.copy(roots = roots)
}

/** The bodies no root list or slot holds: the subtrees only a placement draws. */
internal fun DesignDocumentV1.detachedComponentRoots(): Set<String> {
  val placed = roots.toSet() + nodes.values.flatMap { node -> node.slots.values.flatten() }
  return components.values.map { it.root }.filterTo(mutableSetOf()) { it !in placed }
}

/** What a conflict and a compensation failure call the chain, since the wire has no name for it. */
internal const val MODIFIERS_FIELD = "modifiers"

/** The field a placement's `setComponentArguments` write is reported under in a conflict. */
internal const val COMPONENT_ARGUMENTS_FIELD = "arguments"

private const val PREDICATE_FIELD = "predicate"

/**
 * How a rejection or conflict names one event's binding: the wire's `field` is one string, so the
 * map and event are spelled together.
 */
internal fun eventBindingField(event: String): String = "eventBindings.$event"

/** Where a document reads a state variable it should not, and under which field. */
internal data class StateUsageIssue(val nodeId: String, val field: String, val message: String)

/**
 * The first place [declarations] would leave a design saying something it cannot mean.
 *
 * One whole-document scan for all three state mutations (removal stranding a reader, redefinition
 * narrowing one, binding an undeclared name). Also refuses `toggle` on a non-flag and
 * `selectOrClear` on a non-nullable, which the exporter could not perform.
 */
internal fun DesignDocumentV1.stateUsageIssue(
  declarations: Map<String, StateVariableV1>
): StateUsageIssue? {
  nodes.values.forEach { node ->
    node.properties.forEach { (property, value) ->
      value.stateReads().forEach { variable ->
        if (variable !in declarations) {
          return StateUsageIssue(
            node.id,
            property,
            "property $property reads undeclared state variable $variable",
          )
        }
      }
    }
    node.predicate?.stateReads()?.forEach { variable ->
      if (variable !in declarations) {
        return StateUsageIssue(
          node.id,
          PREDICATE_FIELD,
          "predicate reads undeclared state variable $variable",
        )
      }
    }
    node.eventBindings.forEach { (event, actions) ->
      actions.forEach { action ->
        val variable = action.stateWrite() ?: return@forEach
        val declaration =
          declarations[variable]
            ?: return StateUsageIssue(
              node.id,
              eventBindingField(event),
              "$event writes undeclared state variable $variable",
            )
        if (action is ToggleActionV1 && !declaration.isFlag()) {
          return StateUsageIssue(
            node.id,
            eventBindingField(event),
            "$event toggles state variable $variable, which is not a flag",
          )
        }
        if (action is SelectOrClearActionV1 && !declaration.isNullable()) {
          return StateUsageIssue(
            node.id,
            eventBindingField(event),
            "$event clears state variable $variable, which is not nullable",
          )
        }
      }
    }
  }
  return null
}

/**
 * Whether a declaration holds a boolean, and whether it may hold null — read as the browser reducer
 * reads them, `initialValue` fallback included, so editor and server agree.
 */
private fun StateVariableV1.isFlag(): Boolean =
  when (valueType) {
    StateValueTypeV1.BOOLEAN -> true
    null -> (initialValue as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != null
    else -> false
  }

private fun StateVariableV1.isNullable(): Boolean = nullable ?: (initialValue is JsonNull)

/** Every state variable a value reads, including through the two values that nest others. */
private fun UiValueV1.stateReads(): List<String> =
  when (this) {
    is StateValueV1 -> listOf(variable)
    is StateEqualsValueV1 -> listOf(variable)
    is ListValueV1 -> values.flatMap(UiValueV1::stateReads)
    is ObjectValueV1 -> fields.values.flatMap(UiValueV1::stateReads)
    else -> emptyList()
  }

private fun DesignPredicateV1.stateReads(): List<String> =
  when (this) {
    is StateEqualsPredicateV1 -> listOf(variable)
    is StateTruthyPredicateV1 -> listOf(variable)
    is AllPredicateV1 -> predicates.flatMap(DesignPredicateV1::stateReads)
    is AnyPredicateV1 -> predicates.flatMap(DesignPredicateV1::stateReads)
    is NotPredicateV1 -> predicate.stateReads()
  }

/** The variable an action writes, or null for the one action that writes no state at all. */
private fun DesignActionV1.stateWrite(): String? =
  when (this) {
    is SelectActionV1 -> variable
    is SelectOrClearActionV1 -> variable
    is SetTextActionV1 -> variable
    is SetValueActionV1 -> variable
    is ToggleActionV1 -> variable
    is IncrementActionV1 -> variable
    else -> null
  }

/**
 * The conflicts a state write carries when someone else wrote the same variable first, as
 * `STALE_PROPERTY_WRITE` (the wire has no state-specific code).
 */
internal fun staleStateWrites(
  original: PersistedDesignV1,
  staleness: StalenessWindow,
  name: String,
): List<CommandConflictV1> =
  if (staleness.stale(original, touchKey("v", name)))
    listOf(
      CommandConflictV1(
        ConflictCodeV1.STALE_PROPERTY_WRITE,
        null,
        name,
        staleness.through,
      )
    )
  else emptyList()

/** The component analogue of [staleStateWrites] — the same question, keyed on the component. */
internal fun staleComponentWrites(
  original: PersistedDesignV1,
  staleness: StalenessWindow,
  componentKey: String,
): List<CommandConflictV1> =
  if (staleness.stale(original, touchKey("c", componentKey)))
    listOf(
      CommandConflictV1(
        ConflictCodeV1.STALE_PROPERTY_WRITE,
        null,
        componentKey,
        staleness.through,
      )
    )
  else emptyList()

private const val POSITION_STEP = 1_024
private const val POSITION_MIDPOINT = 512
private const val POSITION_MAX = 65_536

private fun DesignDocumentV1.insertPlacement(
  nodeId: String,
  location: NodeLocationV1,
): DesignDocumentV1 {
  val parentReference = location.parent
  if (parentReference == null) {
    return copy(roots = roots.insertAtAnchors(nodeId, location))
  }
  val parent =
    nodes[parentReference.nodeId]
      ?: fail(RejectionCodeV1.INVALID_LOCATION, "unknown parent", nodeId = parentReference.nodeId)
  val children = parent.slots[parentReference.slot].orEmpty().insertAtAnchors(nodeId, location)
  val changed = parent.copy(slots = parent.slots + (parentReference.slot to children))
  return copy(nodes = nodes + (parent.id to changed))
}

private fun List<String>.insertAtAnchors(nodeId: String, location: NodeLocationV1): List<String> {
  val after = location.afterNodeId?.let(::indexOf)?.takeIf { it >= 0 }
  val before = location.beforeNodeId?.let(::indexOf)?.takeIf { it >= 0 }
  if (
    location.afterNodeId != null && location.beforeNodeId != null && after == null && before == null
  ) {
    fail(
      RejectionCodeV1.INVALID_LOCATION,
      "neither stable location anchor is available",
      nodeId = nodeId,
    )
  }
  if (after != null && before != null && after >= before) {
    fail(RejectionCodeV1.INVALID_LOCATION, "location anchors are reversed", nodeId = nodeId)
  }
  val insertion = before ?: after?.plus(1) ?: size
  return toMutableList().apply { add(insertion, nodeId) }
}

internal fun validateTopology(document: DesignDocumentV1): RejectedOutcomeV1? {
  // At most one root, checked on the way in: export requires exactly one, and otherwise a design
  // would only fail when exported. Zero is legal (the empty document a create starts from).
  if (document.roots.size > 1) {
    return rejected(
      "",
      document.revision,
      RejectionCodeV1.INVALID_DOCUMENT,
      "a design has at most one root; found ${document.roots.size}",
    )
  }
  if (document.nodes.any { (key, node) -> key != node.id }) {
    return rejected(
      "",
      document.revision,
      RejectionCodeV1.INVALID_DOCUMENT,
      "node map key/id mismatch",
    )
  }
  val placements = linkedMapOf<String, Int>()
  document.roots.forEach { placements[it] = (placements[it] ?: 0) + 1 }
  document.nodes.values.forEach { parent ->
    parent.slots.values.flatten().forEach { child ->
      placements[child] = (placements[child] ?: 0) + 1
    }
  }
  // A definition owns a detached body once; calls do not add structural placements.
  // Existing declarations may also name a subtree already placed in the screen.
  document.components.values.forEach { placements.putIfAbsent(it.root, 1) }
  val unknown = placements.keys - document.nodes.keys
  if (unknown.isNotEmpty()) {
    return rejected(
      "",
      document.revision,
      RejectionCodeV1.INVALID_DOCUMENT,
      "unknown placed node ${unknown.first()}",
    )
  }
  val badPlacement = document.nodes.keys.firstOrNull { placements[it] != 1 }
  if (badPlacement != null) {
    return rejected(
      "",
      document.revision,
      RejectionCodeV1.INVALID_DOCUMENT,
      "node must have exactly one placement",
      nodeId = badPlacement,
    )
  }
  val visiting = mutableSetOf<String>()
  val visited = mutableSetOf<String>()
  fun visit(id: String): Boolean {
    if (id in visited) return true
    if (!visiting.add(id) || visiting.size > 128) return false
    val node = document.nodes.getValue(id)
    node.slots.values.flatten().forEach { if (!visit(it)) return false }
    node.component?.componentKey?.let { key ->
      document.components[key]?.root?.let { if (!visit(it)) return false }
    }
    visiting.remove(id)
    visited += id
    return true
  }
  if (
    (document.roots + document.components.values.map { it.root }).any { !visit(it) } ||
      visited != document.nodes.keys
  ) {
    return rejected(
      "",
      document.revision,
      RejectionCodeV1.CYCLE,
      "design topology contains a cycle",
    )
  }
  return null
}

/**
 * A throwable's message with Java exception class names taken out, so a render failure reaches the
 * client as the sentence the code wrote rather than a stack-trace class name.
 */
internal fun Throwable.clientMessage(): String {
  val message = message?.takeIf { it.isNotBlank() } ?: return "the exporter threw without a message"
  return EXCEPTION_CLASS_PREFIX.replace(message, "").trim().ifEmpty { "the exporter threw" }
}

private val EXCEPTION_CLASS_PREFIX =
  Regex("""\b(?:[A-Za-z_$][\w$]*\.)*[A-Z][\w$]*(?:Exception|Error)\b:?\s*""")

internal fun UiBuilderCatalogIssue.toRejection(): RejectedOutcomeV1 =
  rejected("", 0, RejectionCodeV1.INVALID_DOCUMENT, message, nodeId = nodeId, field = field)

internal fun UiBuilderCatalogIssue.toServiceError(): UiBuilderServiceError =
  UiBuilderServiceError(ServiceErrorCodeV1.BAD_REQUEST, "$code: $message")

internal fun CatalogCapabilityV1.supports(format: ExportFormatV1): Boolean =
  when (format) {
    ExportFormatV1.COMPOSE -> exportCapabilities.composeCode
    ExportFormatV1.SVG -> exportCapabilities.svg
    ExportFormatV1.PNG -> exportCapabilities.png
    // No catalog here sets this, so a BUNDLE export is refused as BAD_REQUEST until the server can
    // write one. Remote formats are optional in the staged contracts.
    ExportFormatV1.BUNDLE -> exportCapabilities.bundle
    // The capability decides (none set here). Explicit branches so a new format fails compilation
    // rather than silently reading as unsupported. A2UI JSON is not gated by the Remote Compose
    // flag.
    ExportFormatV1.JSON ->
      (UiBuilderBuildFeatures.remoteCompose || platform == A2UI_PLATFORM) &&
        exportCapabilities.remoteJson
    ExportFormatV1.RC -> UiBuilderBuildFeatures.remoteCompose && exportCapabilities.remoteDocument
  }

internal data class EnvironmentValidationIssue(
  val field: EnvironmentFieldV1,
  val message: String,
)

internal fun validateEnvironment(environment: DesignEnvironmentV1): EnvironmentValidationIssue? =
  with(environment) {
    val zoom = browserZoomPercent
    val time = fixedTime
    val face = typeface
    when {
      environment.widthDp <= 0 ->
        EnvironmentValidationIssue(
          EnvironmentFieldV1.WIDTH_DP,
          "environment.widthDp must be positive",
        )
      environment.heightDp <= 0 ->
        EnvironmentValidationIssue(
          EnvironmentFieldV1.HEIGHT_DP,
          "environment.heightDp must be positive",
        )
      !environment.density.isFinite() || environment.density <= 0.0 ->
        EnvironmentValidationIssue(
          EnvironmentFieldV1.DENSITY,
          "environment.density must be finite and positive",
        )
      environment.locale.isBlank() ->
        EnvironmentValidationIssue(
          EnvironmentFieldV1.LOCALE,
          "environment.locale must not be blank",
        )
      !environment.fontScale.isFinite() || environment.fontScale <= 0.0 ->
        EnvironmentValidationIssue(
          EnvironmentFieldV1.FONT_SCALE,
          "environment.fontScale must be finite and positive",
        )
      zoom != null && zoom <= 0 ->
        EnvironmentValidationIssue(
          EnvironmentFieldV1.BROWSER_ZOOM_PERCENT,
          "environment.browserZoomPercent must be positive when set",
        )
      time != null && time.isBlank() ->
        EnvironmentValidationIssue(
          EnvironmentFieldV1.FIXED_TIME,
          "environment.fixedTime must not be blank when set",
        )
      // A blank family is not "the default" (reset is); it would be indistinguishable from an
      // unresolved one.
      face != null && face.isBlank() ->
        EnvironmentValidationIssue(
          EnvironmentFieldV1.TYPEFACE,
          "environment.typeface must not be blank when set",
        )
      else -> null
    }
  }

internal fun DesignEnvironmentV1.applyChange(change: EnvironmentChangeV1): DesignEnvironmentV1 =
  when (change) {
    is SetWidthDpEnvironmentChangeV1 -> copy(widthDp = change.value)
    is SetHeightDpEnvironmentChangeV1 -> copy(heightDp = change.value)
    is SetDensityEnvironmentChangeV1 -> copy(density = change.value)
    is SetThemeEnvironmentChangeV1 -> copy(theme = change.value)
    is SetLocaleEnvironmentChangeV1 -> copy(locale = change.value)
    is SetFontScaleEnvironmentChangeV1 -> copy(fontScale = change.value)
    is SetLayoutDirectionEnvironmentChangeV1 -> copy(layoutDirection = change.value)
    is SetDynamicColorEnvironmentChangeV1 -> copy(dynamicColor = change.value)
    ResetDynamicColorEnvironmentChangeV1 -> copy(dynamicColor = null)
    is SetWindowPostureEnvironmentChangeV1 -> copy(windowPosture = change.value)
    ResetWindowPostureEnvironmentChangeV1 -> copy(windowPosture = null)
    is SetBrowserZoomPercentEnvironmentChangeV1 -> copy(browserZoomPercent = change.value)
    ResetBrowserZoomPercentEnvironmentChangeV1 -> copy(browserZoomPercent = null)
    is SetFixedTimeEnvironmentChangeV1 -> copy(fixedTime = change.value)
    ResetFixedTimeEnvironmentChangeV1 -> copy(fixedTime = null)
    is SetAnimationsEnvironmentChangeV1 -> copy(animations = change.value)
    ResetAnimationsEnvironmentChangeV1 -> copy(animations = null)
    is SetNetworkAccessEnvironmentChangeV1 -> copy(networkAccess = change.value)
    ResetNetworkAccessEnvironmentChangeV1 -> copy(networkAccess = null)
    is SetBackgroundEnvironmentChangeV1 -> copy(background = change.value)
    ResetBackgroundEnvironmentChangeV1 -> copy(background = null)
    is SetTypefaceEnvironmentChangeV1 -> copy(typeface = change.value)
    ResetTypefaceEnvironmentChangeV1 -> copy(typeface = null)
    // The whole set per change, so concurrent add/remove cannot interleave. Reset is the empty set,
    // meaning "export at its own frame alone".
    is SetExportDevicesEnvironmentChangeV1 -> copy(exportDevices = change.value)
    ResetExportDevicesEnvironmentChangeV1 -> copy(exportDevices = emptyList())
    is SetRemoteProfileEnvironmentChangeV1 -> copy(remoteProfile = change.value)
    ResetRemoteProfileEnvironmentChangeV1 -> copy(remoteProfile = null)
  }

internal fun DesignEnvironmentV1.value(field: EnvironmentFieldV1): Any? =
  when (field) {
    EnvironmentFieldV1.WIDTH_DP -> widthDp
    EnvironmentFieldV1.HEIGHT_DP -> heightDp
    EnvironmentFieldV1.DENSITY -> density
    EnvironmentFieldV1.THEME -> theme
    EnvironmentFieldV1.DYNAMIC_COLOR -> dynamicColor
    EnvironmentFieldV1.LOCALE -> locale
    EnvironmentFieldV1.FONT_SCALE -> fontScale
    EnvironmentFieldV1.LAYOUT_DIRECTION -> layoutDirection
    EnvironmentFieldV1.WINDOW_POSTURE -> windowPosture
    EnvironmentFieldV1.BROWSER_ZOOM_PERCENT -> browserZoomPercent
    EnvironmentFieldV1.FIXED_TIME -> fixedTime
    EnvironmentFieldV1.ANIMATIONS -> animations
    EnvironmentFieldV1.NETWORK_ACCESS -> networkAccess
    EnvironmentFieldV1.BACKGROUND -> background
    EnvironmentFieldV1.TYPEFACE -> typeface
    EnvironmentFieldV1.EXPORT_DEVICES -> exportDevices
    EnvironmentFieldV1.REMOTE_PROFILE -> remoteProfile
  }

internal fun DesignEnvironmentV1.copyFieldsFrom(
  source: DesignEnvironmentV1,
  fields: List<EnvironmentFieldV1>,
): DesignEnvironmentV1 =
  fields.fold(this) { environment, field ->
    when (field) {
      EnvironmentFieldV1.WIDTH_DP -> environment.copy(widthDp = source.widthDp)
      EnvironmentFieldV1.HEIGHT_DP -> environment.copy(heightDp = source.heightDp)
      EnvironmentFieldV1.DENSITY -> environment.copy(density = source.density)
      EnvironmentFieldV1.THEME -> environment.copy(theme = source.theme)
      EnvironmentFieldV1.DYNAMIC_COLOR -> environment.copy(dynamicColor = source.dynamicColor)
      EnvironmentFieldV1.LOCALE -> environment.copy(locale = source.locale)
      EnvironmentFieldV1.FONT_SCALE -> environment.copy(fontScale = source.fontScale)
      EnvironmentFieldV1.LAYOUT_DIRECTION ->
        environment.copy(layoutDirection = source.layoutDirection)
      EnvironmentFieldV1.WINDOW_POSTURE -> environment.copy(windowPosture = source.windowPosture)
      EnvironmentFieldV1.BROWSER_ZOOM_PERCENT ->
        environment.copy(browserZoomPercent = source.browserZoomPercent)
      EnvironmentFieldV1.FIXED_TIME -> environment.copy(fixedTime = source.fixedTime)
      EnvironmentFieldV1.ANIMATIONS -> environment.copy(animations = source.animations)
      EnvironmentFieldV1.NETWORK_ACCESS -> environment.copy(networkAccess = source.networkAccess)
      EnvironmentFieldV1.BACKGROUND -> environment.copy(background = source.background)
      EnvironmentFieldV1.TYPEFACE -> environment.copy(typeface = source.typeface)
      EnvironmentFieldV1.EXPORT_DEVICES -> environment.copy(exportDevices = source.exportDevices)
      EnvironmentFieldV1.REMOTE_PROFILE -> environment.copy(remoteProfile = source.remoteProfile)
    }
  }

/** Longer than any title a listing can show, shorter than anything that is really a document. */
internal const val MAXIMUM_TITLE_LENGTH = 200

internal fun notFound(designId: String): UiBuilderServiceError =
  UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "design $designId was not found")

internal fun forbidden(action: String, designId: String): UiBuilderServiceError =
  UiBuilderServiceError(ServiceErrorCodeV1.FORBIDDEN, "actor may not $action design $designId")

/**
 * The exact bytes a document's identity hash is taken over. Storage provenance is deliberately
 * excluded so moving a document does not change its content identity.
 */
internal fun documentCanonicalBytes(document: DesignDocumentV1): ByteArray =
  canonicalJson(PersistentUiBuilderServiceJson.json.encodeToJsonElement(document.copy(home = null)))
    .encodeToByteArray()

internal fun documentHash(document: DesignDocumentV1): String =
  sha256(documentCanonicalBytes(document))

/**
 * The document bytes charged to retained revision snapshots. Unlike [documentCanonicalBytes],
 * includes [DesignDocumentV1.home], which every retained snapshot stores.
 */
internal fun documentRetentionBytes(document: DesignDocumentV1): Int =
  canonicalJson(PersistentUiBuilderServiceJson.json.encodeToJsonElement(document))
    .encodeToByteArray()
    .size

/**
 * The newest entries of [this] that fit in [budgetBytes], keeping at least [minimumEntries], plus
 * whatever those entries [pin].
 *
 * Walks newest-first and stops at the first entry over budget, so cost is bounded by what is kept.
 * Insertion order is age order (re-adding a key keeps its position).
 *
 * [pin] exists because an undo is appended while its target keeps its old position: without
 * pinning, undoing the oldest retained record could evict its own target and make an immediate redo
 * fail with `UNKNOWN_OPERATION`. Pins are followed to a fixpoint (redo → undo → original).
 *
 * Bytes are UTF-8 as stored, keys included. Returns [this] unchanged when everything fits.
 */
internal inline fun <reified V> Map<String, V>.retainNewestWithinBytes(
  budgetBytes: Long,
  minimumEntries: Int,
  noinline pin: (V) -> String? = { null },
): Map<String, V> {
  if (size <= minimumEntries) return this
  val ordered = entries.toList()
  var total = 0L
  var kept = 0
  var index = ordered.lastIndex
  while (index >= 0) {
    val entry = ordered[index]
    total +=
      PersistentUiBuilderServiceJson.json.encodeToString(entry.value).utf8Size() +
        entry.key.utf8Size()
    if (total > budgetBytes && kept >= minimumEntries) break
    kept++
    index--
  }
  if (kept >= ordered.size) return this
  val window = ordered.subList(ordered.size - kept, ordered.size)
  val retained = window.associateTo(LinkedHashMap(window.size)) { it.key to it.value }
  // To a fixpoint: a redo record pins the undo it compensates, and that undo pins the original.
  var frontier: Collection<V> = window.map { it.value }
  while (frontier.isNotEmpty()) {
    val next = mutableListOf<V>()
    for (value in frontier) {
      val pinned = pin(value) ?: continue
      if (pinned in retained) continue
      val target = this[pinned] ?: continue
      retained[pinned] = target
      next += target
    }
    frontier = next
  }
  if (retained.size >= ordered.size) return this
  // Rebuilt in the original order rather than in `retained`'s, so age order — which is what makes
  // the front of this map the oldest entries on the next pass — survives the pinning.
  return ordered.filter { it.key in retained }.associate { it.key to it.value }
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

/** The touches [this] records — see [ConflictTouchRecordV1]. */
internal fun ChangeRecordV1.touchKeys(): List<String> =
  when (this) {
    is CatalogUpgradeChangeRecordV1 -> listOf(touchKey("catalog"))
    // Node-and-property granular, matching what a `setProperty` conflict is about: two actors
    // writing different properties of the same node do not conflict.
    is PropertyChangeV1 -> listOf(touchKey("p", nodeId, property))
    // Node granular, because the chain is one value — see [ModifierChangeV1].
    is ModifierChangeV1 -> listOf(touchKey("m", nodeId))
    // Node granular too: a placement's arguments are one map — see [ComponentArgumentsChangeV1].
    is ComponentArgumentsChangeV1 -> listOf(touchKey("a", nodeId))
    is StateVariableChangeV1 -> listOf(touchKey("v", name))
    // Key granular, like a state variable: two actors declaring different components do not
    // conflict, and two declaring the same one are writing the same thing.
    is ComponentChangeV1 -> listOf(touchKey("c", componentKey))
    is EventBindingChangeV1 -> listOf(touchKey("b", nodeId, event))
    is EnvironmentChangeRecordV1 -> fields.map { touchKey("e", it.name) }
    // `nodeId` rather than `affectedNodeIds`: the move check asks about the node that moved, and
    // widening it to the whole subtree would report a conflict for every descendant carried along.
    is StructureChangeV1 -> listOf(touchKey("s", nodeId))
  }

internal fun touchKey(kind: String, vararg parts: String): String =
  parts.joinToString("\u0000", prefix = "${kind}\u0000")

/**
 * Which earlier commits a command's staleness checks treat as concurrent (`STALE_*`).
 *
 * A write of key `k` is stale when the newest commit that wrote `k` after [since] is at or before
 * [through] and was not made by [run]. For a live edit the window is `(baseRevision, head]`.
 *
 * Replays resolve positions along a base chain, which would hide concurrent edits after the first
 * command, so they widen the window to where the run started:
 * - A branch merge passes `(forkRevision, parentHeadBeforeMerge]`; the branch's own writes land
 *   past [through] and are not re-reported.
 * - A Sync replays one request per command; later commands carry
 *   `DesignCommandV1.stalenessBaseRevision`, giving `(stalenessBaseRevision, head]`, and [run]
 *   excludes the run's own writes. Concurrent writes between its commands are still reported.
 *
 * The touch log is pruned against the oldest retained position snapshot. Branch forks are pinned; a
 * Sync start is not, but the retained touches always cover at least `(baseRevision, head]`.
 */
internal data class StalenessWindow(
  val since: Long,
  val through: Long,
  val run: ReplayRunIdentity? = null,
) {
  /** The newest concurrent revision that wrote [key], or null if the author saw the last write. */
  fun overwritten(design: PersistedDesignV1, key: String): Long? {
    val newest = design.lastTouchSince(since, key) ?: return null
    if (run != null && run.wrote(newest)) return null
    return newest.committedRevision.takeIf { it <= through }
  }

  fun stale(design: PersistedDesignV1, key: String): Boolean = overwritten(design, key) != null
}

/**
 * One offline run as the service can recognise it across requests: one actor's commands from the
 * same starting revision. Recorded on every batch's [ConflictTouchRecordV1] (a run's first command
 * uses its [DesignCommandV1.baseRevision]); matched only for commands carrying
 * `stalenessBaseRevision`, so live edits are judged as before.
 */
internal data class ReplayRunIdentity(
  val actorId: String,
  val clientId: String,
  val startRevision: Long,
) {
  fun wrote(touch: ConflictTouchRecordV1): Boolean =
    touch.actorId == actorId &&
      touch.clientId == clientId &&
      touch.authorSawRevision == startRevision
}

/**
 * The revision this command's author had seen: [DesignCommandV1.stalenessBaseRevision] when older
 * than the base, otherwise the base. Out-of-range values are read as absent rather than refused.
 */
internal fun DesignCommandV1.authorSawRevision(): Long =
  stalenessBaseRevision?.takeIf { it in 0 until baseRevision } ?: baseRevision

/** The window [this] command's `STALE_*` checks read, absent a window the caller imposes. */
internal fun DesignCommandV1.stalenessWindow(
  actor: AuthenticatedUiBuilderActor,
  head: Long,
): StalenessWindow {
  val seen = authorSawRevision()
  return if (seen == baseRevision) StalenessWindow(baseRevision, head)
  else StalenessWindow(seen, head, ReplayRunIdentity(actor.actorId, clientId, seen))
}

/**
 * The submission as its idempotency fingerprint reads it. An unset staleness base is omitted, so
 * older recorded operations still match retries.
 */
internal fun submissionFingerprint(json: Json, submission: DesignSubmissionV1): String {
  val element = json.encodeToJsonElement(DesignSubmissionV1.serializer(), submission)
  val canonical =
    if (element is JsonObject && element["stalenessBaseRevision"] is JsonNull)
      JsonObject(element - "stalenessBaseRevision")
    else element
  return canonicalJson(canonical)
}

/** The newest touch that wrote [key] after [baseRevision], or null if none did. */
internal fun PersistedDesignV1.lastTouchSince(
  baseRevision: Long,
  key: String,
): ConflictTouchRecordV1? =
  conflictTouches
    .asSequence()
    .filter { it.committedRevision > baseRevision && key in it.keys }
    .maxByOrNull(ConflictTouchRecordV1::committedRevision)

internal fun UiBuilderServiceLimits.retainedRevisionsFor(documentBytes: Int): Int {
  if (documentBytes <= 0) return retainedRevisionSnapshots
  val floor = minimumRetainedRevisionSnapshots.coerceAtMost(retainedRevisionSnapshots)
  val affordable = (retainedRevisionBytes / documentBytes).coerceAtMost(Int.MAX_VALUE.toLong())
  return affordable.toInt().coerceIn(floor, retainedRevisionSnapshots)
}

internal fun artifactDigest(artifact: ExportArtifactV1): String =
  sha256(
    when (artifact.encoding) {
      ExportEncodingV1.UTF8 -> artifact.content.encodeToByteArray()
      ExportEncodingV1.BASE64 -> Base64.getDecoder().decode(artifact.content)
    }
  )

internal object PersistentUiBuilderServiceJson {
  val json: Json = Json { encodeDefaults = true }
}

/** For what an operator reads, not what is stored: indented, never used to compute bytes. */
internal object PersistentUiBuilderServiceAdminJson {
  val json: Json = Json {
    encodeDefaults = true
    prettyPrint = true
  }
}

internal fun sha256(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun canonicalJson(element: JsonElement): String =
  when (element) {
    is JsonObject ->
      element.entries
        .sortedBy { it.key }
        .joinToString(",", "{", "}") { (key, value) ->
          "${JsonPrimitive(key)}:${canonicalJson(value)}"
        }
    is JsonArray -> element.joinToString(",", "[", "]", transform = ::canonicalJson)
    is JsonPrimitive -> element.toString()
  }
