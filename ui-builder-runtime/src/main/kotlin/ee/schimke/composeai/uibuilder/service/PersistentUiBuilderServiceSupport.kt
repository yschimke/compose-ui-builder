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
 * A GitHub login is case-insensitive, and the host signs its session over `login.lowercase()`, so
 * the actor that arrives is always lowercase. A grant stored whatever the sharer typed: sharing
 * with `github:AshleyIngram` was accepted, stored, shown back in the access record — and matched
 * nobody, indistinguishable from either end from never having shared at all.
 *
 * Folding on the way in fixes new grants; comparing canonically fixes the ones already stored, so a
 * grant written mis-cased before this starts working rather than staying quietly broken.
 *
 * Only `github:` folds. Case is meaningful in an `agent:<fingerprint>`, and folding one would make
 * two distinct agents equal.
 */
internal fun canonicalActorId(actorId: String): String =
  if (actorId.startsWith(GITHUB_ACTOR_PREFIX))
    GITHUB_ACTOR_PREFIX + actorId.removePrefix(GITHUB_ACTOR_PREFIX).lowercase()
  else actorId

internal fun sameActor(left: String, right: String): Boolean =
  canonicalActorId(left) == canonicalActorId(right)

/**
 * The grants that actually govern: one per actor, the last one written.
 *
 * Folding on the way in only fixes grants written since it started working. A record persisted
 * before that can hold `github:Alice` AND `github:alice`, because the replacement it went through
 * compared exact strings — a mixed-case EDITOR share followed by a lowercase VIEWER downgrade left
 * both. Asking `any` of that list is a UNION of the two, so the downgrade did not take away WRITE;
 * and [listItem] answering with `firstOrNull` picked whichever came first, so the role the reader
 * was *shown* could disagree with the one being enforced.
 *
 * Latest wins, read off [DesignActorGrantV1.grantedAtEpochMillis] rather than off list position:
 * the grant carries when it was made, so the rule is a fact about the grants rather than about how
 * they happen to be ordered. Position breaks a tie, because a mutation appends after filtering the
 * actor out and the newest entry is therefore last.
 *
 * Read-time rather than a migration, so a record is correct the first time it is consulted rather
 * than the first time it is rewritten — and the access-mutation path starts from this, so the next
 * grant change on a design also heals what is stored.
 */
internal fun DesignAccessControlV1.effectiveGrants(): List<DesignActorGrantV1> {
  if (actorGrants.size < 2) return actorGrants
  // Every record written since the fold started working already has one grant per actor, and this
  // runs on every authorization check — so establish that there is something to collapse before
  // building a second list. A record with no duplicates is returned as it is.
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

/**
 * [effectiveGrants] applied, for anywhere an access record is read rather than asked a question.
 */
internal fun DesignAccessControlV1.collapsed(): DesignAccessControlV1 =
  effectiveGrants().let { if (it.size == actorGrants.size) this else copy(actorGrants = it) }

internal fun DesignAccessControlV1.allows(actorId: String, action: DesignAccessActionV1): Boolean =
  allowsPersonally(actorId, action) ||
    effectiveGrants().any {
      it.actorId == UiBuilderPublicAccess.ANYONE_ACTOR_ID && action in it.allowedActions
    }

/**
 * [allows] without the [UiBuilderPublicAccess.ANYONE_ACTOR_ID] grant: the owner, or a grant naming
 * this actor. It is the question "is this design one of *mine*", which is what a listing asks — a
 * public design is readable by everyone, and listing every public design to everyone would turn
 * each person's file manager into the whole server's.
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
 * The same question asked of a whole identity: an actor may act, or the human it acts for may.
 *
 * This is the one place delegation is honoured, and it is deliberately a *widening of who* rather
 * than a widening of what — a delegate reaches exactly the designs its principal reaches, with
 * exactly the actions the design granted the principal. An actor with no principal
 * ([AuthenticatedUiBuilderActor.onBehalfOfActorId] null) asks precisely the question it always did.
 */
internal fun PersistedDesignV1.allows(
  actor: AuthenticatedUiBuilderActor,
  action: DesignAccessActionV1,
): Boolean = actor.accessIdentities.any { allows(it, action) }

/** True when this actor owns the design outright, or acts for the human who does. */
internal fun DesignAccessControlV1.ownedBy(actor: AuthenticatedUiBuilderActor): Boolean =
  actor.accessIdentities.any { sameActor(it, ownerActorId) }

internal fun PersistedDesignV1.ownedBy(actor: AuthenticatedUiBuilderActor): Boolean =
  access.ownedBy(actor)

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
 * The list row for a design the store could not read, built from the header its quarantine kept.
 *
 * The same nine fields a served design reports, from the record that describes it — so a
 * quarantined design is listed as what it is, titled and owned, rather than as a bare id an
 * operator has to decode.
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
 * What the asking actor is told it may do here, reported under the *actor's own* id — the caller
 * asked about itself, and being told about an id it does not use would be an answer to a question
 * nobody asked. What it may do is resolved through its principal when it has one, which is what put
 * the design in front of it.
 */
private fun DesignAccessControlV1.requesterAccess(
  actor: AuthenticatedUiBuilderActor
): DesignActorAccessV1 =
  if (ownedBy(actor))
    DesignActorAccessV1(actor.actorId, DesignAccessRoleV1.OWNER, DesignAccessActionV1.entries)
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
 * The oldest sequence a whole revision is still retained for, which is not [retainedFromSequence].
 *
 * That one is the operation log's floor (`retainedCommittedOperations`), and it is the right answer
 * for a delta: it says how far back the *changes* go. A `SNAPSHOT_REQUIRED` raised because a
 * revision's document is gone must answer with the snapshot floor instead. The two used to be
 * within one of each other, so quoting either was harmless; retaining fewer revisions than
 * operations makes the difference real, and a client told a floor 900 sequences below what is
 * actually retained would ask again for a revision that is still missing and loop.
 */
internal fun PersistedDesignV1.retainedSnapshotFromSequence(): Long =
  revisionSnapshots.firstOrNull()?.sequence ?: lastSequence

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
      DesignCommandV1(designId, operationId, actor.actorId, clientId, baseRevision, operations)
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
  add(document.roots, null)
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
  val children =
    positions
      .filter { (id, position) -> id in nodes && position.parent == parent }
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

/** What a conflict and a compensation failure call the chain, since the wire has no name for it. */
internal const val MODIFIERS_FIELD = "modifiers"

private const val PREDICATE_FIELD = "predicate"

/**
 * How a rejection or a conflict names one event's binding.
 *
 * The wire's `field` is one string and has no separate place for an event, so the binding map and
 * the event are spelled together — the same bargain `modifiers` makes by naming the whole chain.
 */
internal fun eventBindingField(event: String): String = "eventBindings.$event"

/** Where a document reads a state variable it should not, and under which field. */
internal data class StateUsageIssue(val nodeId: String, val field: String, val message: String)

/**
 * The first place [declarations] would leave a design saying something it cannot mean.
 *
 * One scan for all three state mutations, because they fail the same way from different directions:
 * a removal can strand a reader, a redefinition can narrow one out from under its reader, and a new
 * binding can name a variable nobody declared. Reading the whole document each time rather than the
 * delta is deliberate — the delta is what a caller would have to get right, and getting it wrong is
 * silent.
 *
 * Undeclared is not the only failure. `toggle` is `!x` and `selectOrClear` writes null, so a
 * variable that is not a flag and one that is not nullable are refusals too: the renderer coerces
 * and carries on, the exporter emits a `TODO` that throws on the first press, and a design only one
 * of its two consumers can perform is worse than a rejected command.
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
 * Whether a declaration holds a boolean, and whether it may hold null.
 *
 * Read the way the browser reducer reads them, `initialValue` fallback included: a declaration that
 * names no `valueType` still has a type, and one whose initial value is null is nullable whether or
 * not it says so. Two readings of the same document would mean a design the editor lets an author
 * build and the server then refuses to save.
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
 * The conflicts a state write carries when someone else wrote the same variable first.
 *
 * `STALE_PROPERTY_WRITE` because the wire has no state-specific code and the variable's name is the
 * field it names — the same reading the modifier lane takes for a chain.
 */
internal fun staleStateWrites(
  original: PersistedDesignV1,
  command: DesignCommandV1,
  name: String,
): List<CommandConflictV1> =
  if (
    command.baseRevision < original.document.revision &&
      original.touchedSince(command.baseRevision, touchKey("v", name))
  )
    listOf(
      CommandConflictV1(
        ConflictCodeV1.STALE_PROPERTY_WRITE,
        null,
        name,
        original.document.revision,
      )
    )
  else emptyList()

/** The component analogue of [staleStateWrites] — the same question, keyed on the component. */
internal fun staleComponentWrites(
  original: PersistedDesignV1,
  command: DesignCommandV1,
  componentKey: String,
): List<CommandConflictV1> =
  if (
    command.baseRevision < original.document.revision &&
      original.touchedSince(command.baseRevision, touchKey("c", componentKey))
  )
    listOf(
      CommandConflictV1(
        ConflictCodeV1.STALE_PROPERTY_WRITE,
        null,
        componentKey,
        original.document.revision,
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
  // At most one root, checked on the way in rather than only on the way out. Export requires
  // exactly
  // one — `validateDocumentForExport`'s `ROOT_CARDINALITY`, and `ScreenDocumentProjection` again —
  // while every placement rule below is satisfied by two disjoint trees under two roots. Without
  // this a design could be created, persisted, loaded and edited and only refuse when somebody
  // asked for Kotlin out of it (yschimke/compose-preview-server#429).
  //
  // Zero roots stays legal: that is the empty document `create_design` takes and the first
  // parentless insert fills. Two is the count nothing but deleting a whole subtree can undo.
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
 * A throwable's message with Java exception class names taken out of it.
 *
 * The render daemon reports a failed composition as `IllegalStateException: unsupported asset
 * 'avatar-lain' on d-m1-photo`, the render host prefixes `render failed: `, and the export lane
 * used to forward the lot to the client under the `internal` code. A design the renderer cannot
 * draw is an ordinary state; a stack-trace class name in a user-facing error is not
 * (yschimke/compose-preview-server#484). The class names are dropped and the sentence the code
 * actually wrote is kept.
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
    // Defaults to false in the contract, and no catalog here sets it, so a BUNDLE export is
    // refused as BAD_REQUEST at the gate above until the server can actually write one
    // (yschimke/compose-preview-server#528). Remote formats are optional in the staged contracts.
    ExportFormatV1.BUNDLE -> exportCapabilities.bundle
    // Added by compose-preview-contracts 2.17.0, and read the same way: the capability decides,
    // and no catalog here sets either, so both are refused at this gate until something can write
    // one. Wired rather than folded into an `else`, so the next format added still fails this
    // compile instead of silently reading as unsupported — which is what this `when` is for.
    ExportFormatV1.JSON -> UiBuilderBuildFeatures.remoteCompose && exportCapabilities.remoteJson
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
      // A blank family is not "the default" — reset is. Letting one through would store a document
      // whose typeface is set to nothing, which the renderer cannot distinguish from a family it
      // failed to resolve.
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
    // The whole set per change, which is what the protocol offers: an add and a remove would each
    // be a mutation a client could interleave, and the set is read as a set by everything that
    // consumes it. Reset is the empty set rather than a null, because "exports at its own frame
    // alone" is a real answer and not an absent one.
    is SetExportDevicesEnvironmentChangeV1 -> copy(exportDevices = change.value)
    ResetExportDevicesEnvironmentChangeV1 -> copy(exportDevices = emptyList())
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
    }
  }

/** Longer than any title a listing can show, shorter than anything that is really a document. */
internal const val MAXIMUM_TITLE_LENGTH = 200

internal fun notFound(designId: String): UiBuilderServiceError =
  UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "design $designId was not found")

internal fun forbidden(action: String, designId: String): UiBuilderServiceError =
  UiBuilderServiceError(ServiceErrorCodeV1.FORBIDDEN, "actor may not $action design $designId")

/**
 * The exact bytes a document's hash is taken over, which are also the bytes retaining it costs.
 *
 * Kept as one function so the retention budget is measured on the same canonical form the hash is,
 * and so a commit that needs both pays for the serialization once.
 */
internal fun documentCanonicalBytes(document: DesignDocumentV1): ByteArray =
  canonicalJson(PersistentUiBuilderServiceJson.json.encodeToJsonElement(document))
    .encodeToByteArray()

internal fun documentHash(document: DesignDocumentV1): String =
  sha256(documentCanonicalBytes(document))

/**
 * How many revisions of a document costing [documentBytes] this design may retain.
 *
 * The budget divided by the cost of one, held between the floor and the ceiling. A document large
 * enough to make the division zero still retains the floor, deliberately: see
 * [UiBuilderServiceLimits.minimumRetainedRevisionSnapshots].
 */
/**
 * The newest entries of [this] that fit in [budgetBytes], keeping at least [minimumEntries], plus
 * whatever those entries [pin].
 *
 * Walks from the newest backwards and stops at the first entry that would exceed the budget, so a
 * commit serializes at most the budget rather than the whole map — the cost is bounded by what is
 * kept, not by what has accumulated. Insertion order is age order for both maps this is used on:
 * `acceptedOperations` and `tombstones` are built by `+`, and re-adding an existing key (an undo
 * marking its target compensated) keeps that key's original position, which is what makes the
 * oldest entries the ones at the front.
 *
 * **That last property is exactly why [pin] exists.** An undo is a new record at the back; the
 * operation it compensates keeps its ORIGINAL position at the front. So undoing the oldest of eight
 * retained records adds a ninth at the back and pushes its own target out of the window — and
 * `reduceRedo` resolves through `acceptedOperations`, so an immediate redo of a just-accepted undo
 * answered `UNKNOWN_OPERATION`. Losing undo depth at the far end of history is the degradation the
 * budget is willing to pay for; an operation becoming non-redoable the instant it is undone is not.
 * Entries reachable from a kept entry through [pin] are therefore retained regardless of position,
 * to a fixpoint, because a redo record names an undo which names the original.
 *
 * Bytes are measured as the UTF-8 the state file actually receives, key included. `String.length`
 * counted UTF-16 code units, so a design whose text is three-byte characters kept about three times
 * the budget it was told to keep, and the keys — an operation id apiece — were not counted at all.
 *
 * Returns [this] unchanged when everything fits, so the common case allocates nothing.
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

/** Whether anyone wrote [key] after [baseRevision] — the question every staleness check asks. */
internal fun PersistedDesignV1.touchedSince(baseRevision: Long, key: String): Boolean =
  conflictTouches.any {
    it.committedRevision > baseRevision && key in it.keys
  }

/** The newest revision that wrote [key] after [baseRevision], or null if none did. */
internal fun PersistedDesignV1.lastTouchSince(baseRevision: Long, key: String): Long? =
  conflictTouches
    .asSequence()
    .filter { it.committedRevision > baseRevision && key in it.keys }
    .maxOfOrNull(ConflictTouchRecordV1::committedRevision)

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

/**
 * For what an operator reads, not for what is stored: indented, and never used to compute bytes.
 */
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
