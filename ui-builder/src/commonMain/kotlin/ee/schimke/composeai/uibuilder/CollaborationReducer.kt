package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.behaviorIssue
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.canonicalJson
import kotlinx.serialization.json.JsonObject

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
    documentValidator: CollaborationDocumentValidator? = null,
  ): CommandApplication {
    val mutation = RejectedMutation.Design(command)
    state.replayRejected(mutation)?.let {
      return it
    }
    return applyUnrecorded(state, command, propertyValidator, documentValidator)
      .retainRejection(mutation)
  }

  private fun applyUnrecorded(
    state: CollaborationState,
    command: DesignCommand,
    propertyValidator: CollaborationPropertyValidator?,
    documentValidator: CollaborationDocumentValidator?,
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
        command.operations.indexOfFirst {
          it is DesignOperation.SetProperty || it is DesignOperation.RemoveNodeProperty
        }
      if (propertyOperationIndex >= 0) {
        val (propertyNodeId, propertyName) =
          when (val propertyOperation = command.operations[propertyOperationIndex]) {
            is DesignOperation.SetProperty -> propertyOperation.nodeId to propertyOperation.property
            is DesignOperation.RemoveNodeProperty ->
              propertyOperation.nodeId to propertyOperation.property
            else -> error("indexOfFirst matched a property operation")
          }
        return state.rejected(
          RejectionCode.MISSING_PROPERTY_VALIDATOR,
          "a property operation requires capability validation",
          propertyOperationIndex,
          propertyNodeId,
          propertyName,
        )
      }
    }

    val prepared = state.withStablePositions()
    try {
      prepared.document.requireValidTopology()
    } catch (failure: ReducerFailure) {
      return state.rejected(failure.code, failure.message.orEmpty(), nodeId = failure.nodeId)
    }
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
        trace.state.document.requireValidPlacement()
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
    // Once, after the command, for the reason [requireSingleRoot] gives: it is the one topology
    // rule
    // about the document rather than about a node, and a command is what commits. The half-applied
    // states inside the loop above are nobody's document.
    try {
      trace.state.document.requireSingleRoot()
    } catch (failure: ReducerFailure) {
      return state.rejected(failure.code, failure.message.orEmpty(), nodeId = failure.nodeId)
    }
    if (trace.propertyChanges.any { it.address.target != PropertyTarget.Property })
      trace.state.document.behaviorIssue()?.let { issue ->
        return state.rejected(
          RejectionCode.INVALID_DOCUMENT,
          issue.message,
          nodeId = issue.nodeId,
          field = issue.field,
        )
      }
    documentValidator?.validate(trace.state.document)?.let { issue ->
      return state.rejected(
        RejectionCode.INVALID_DOCUMENT,
        issue.message,
        nodeId = issue.nodeId,
        field = issue.field,
      )
    }

    val committedRevision = state.document.revision + 1
    val document = trace.state.document.copy(revision = committedRevision)
    val canonicalDocument = canonicalDocument(document)
    val propertyVersions =
      trace.propertyTouches.fold(trace.state.propertyVersions) { versions, address ->
        versions + (address to committedRevision)
      }
    val modifierVersions =
      trace.modifierTouches.fold(trace.state.modifierVersions) { versions, nodeId ->
        versions + (nodeId to committedRevision)
      }
    val moveVersions =
      trace.moveTouches.fold(trace.state.moveVersions) { versions, nodeId ->
        versions + (nodeId to committedRevision)
      }
    val structuralVersions =
      trace.structuralTouches.fold(trace.state.structuralVersions) { versions, nodeId ->
        versions + (nodeId to committedRevision)
      }
    val environmentVersions =
      trace.environmentTouches.fold(trace.state.environmentVersions) { versions, field ->
        versions + (field to committedRevision)
      }
    val accepted =
      AcceptedCommand(
        command = command,
        committedRevision = committedRevision,
        canonicalDocument = canonicalDocument,
        propertyChanges = trace.propertyChanges,
        modifierChanges = trace.modifierChanges,
        environmentChanges = trace.environmentChanges,
        structuralChanges = trace.structuralChanges,
        compensationChanges = trace.compensationChanges,
        conflicts = trace.conflicts,
      )
    val committed =
      trace.state.copy(
        document = document,
        acceptedCommands = state.acceptedCommands + (command.operationId to accepted),
        positionSnapshots =
          trace.state.positionSnapshots + (committedRevision to trace.state.positions),
        propertyVersions = propertyVersions,
        modifierVersions = modifierVersions,
        moveVersions = moveVersions,
        structuralVersions = structuralVersions,
        environmentVersions = environmentVersions,
        activeOperationVersions =
          trace.state.activeOperationVersions + (command.operationId to committedRevision),
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

  fun undo(
    state: CollaborationState,
    command: UndoCommand,
    documentValidator: CollaborationDocumentValidator? = null,
  ): CommandApplication {
    val mutation = RejectedMutation.Undo(command)
    state.replayRejected(mutation)?.let {
      return it
    }
    return undoUnrecorded(state, command, documentValidator).retainRejection(mutation)
  }

  private fun undoUnrecorded(
    state: CollaborationState,
    command: UndoCommand,
    documentValidator: CollaborationDocumentValidator?,
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
    val scalarOnly =
      target.propertyChanges.isNotEmpty() && target.command.operations.all { it.isPropertyWrite() }
    // Its own lane, beside the property one and for the same reason: a chain is a value on a node,
    // so undoing it is a rewind of one address rather than a structural replay.
    val modifierOnly =
      target.modifierChanges.isNotEmpty() &&
        target.command.operations.all { it is DesignOperation.SetModifiers }
    val environmentOnly =
      target.environmentChanges.isNotEmpty() &&
        target.command.operations.all { it is DesignOperation.SetEnvironment }
    val structuralOnly =
      target.structuralChanges.isNotEmpty() &&
        target.command.operations.all {
          !it.isPropertyWrite() && it !is DesignOperation.SetEnvironment
        }
    val mixed = target.propertyChanges.isNotEmpty() && target.structuralChanges.isNotEmpty()
    // A batch that mixes a modifier write with anything else has no lane here, and quietly
    // compensating half of it is worse than refusing: the editor submits modifier writes on their
    // own, so this is a client that did something else.
    if (target.modifierChanges.isNotEmpty() && !modifierOnly) {
      return state.rejected(
        RejectionCode.UNSUPPORTED_COMPENSATION,
        "a batch mixing modifiers with other writes cannot be undone",
      )
    }
    if (!scalarOnly && !modifierOnly && !environmentOnly && !structuralOnly && !mixed) {
      return state.rejected(
        RejectionCode.UNSUPPORTED_COMPENSATION,
        "operation has no compensating changes",
      )
    }
    val targetActiveRevision =
      state.activeOperationVersions[target.command.operationId] ?: target.committedRevision
    // The property and environment lanes compare against the target's own revision: both undo and
    // redo restore version stamps rather than minting them, so an operation's addresses always
    // carry the revision that operation committed at. Anything else on the address — a
    // collaborator's write — leaves a different revision and is still refused.
    if (scalarOnly) {
      target.propertyChanges.asReversed().distinctBy(PropertyChange::address).forEach { change ->
        val current = state.document.valueAt(change.address)
        val currentVersion = state.propertyVersions[change.address]
        if (current != change.afterValue || currentVersion != target.committedRevision) {
          return state.rejected(
            RejectionCode.UNSAFE_COMPENSATION,
            "property changed after ${target.command.operationId} at revision $currentVersion",
            nodeId = change.address.nodeId,
            field = change.address.property,
          )
        }
      }
    } else if (modifierOnly) {
      target.modifierChanges.asReversed().distinctBy(ModifierChange::nodeId).forEach { change ->
        val current = state.document.nodes[change.nodeId]?.modifiers
        val currentVersion = state.modifierVersions[change.nodeId]
        if (current != change.after || currentVersion != target.committedRevision) {
          return state.rejected(
            RejectionCode.UNSAFE_COMPENSATION,
            "modifiers changed after ${target.command.operationId} at revision $currentVersion",
            nodeId = change.nodeId,
            field = "modifiers",
          )
        }
      }
    } else if (environmentOnly) {
      target.environmentChanges.asReversed().distinctBy(EnvironmentChange::field).forEach { change
        ->
        val current = state.document.environment[change.field]
        val currentVersion = state.environmentVersions[change.field]
        if (current != change.after || currentVersion != target.committedRevision) {
          return state.rejected(
            RejectionCode.UNSAFE_COMPENSATION,
            "environment changed after ${target.command.operationId} at revision $currentVersion",
            field = change.field,
          )
        }
      }
    } else if (structuralOnly) {
      state
        .validateStructuralCompensation(target.structuralChanges, targetActiveRevision, undo = true)
        ?.let {
          return it
        }
    } else {
      target.propertyChanges
        .map { it.address }
        .distinct()
        .forEach { address ->
          val version = state.propertyVersions[address]
          if (version != target.committedRevision) {
            return state.rejected(
              RejectionCode.UNSAFE_COMPENSATION,
              "property changed after ${target.command.operationId} at revision $version",
              nodeId = address.nodeId,
              field = address.property,
            )
          }
        }
      state
        .validateStructuralCompensation(target.structuralChanges, targetActiveRevision, undo = true)
        ?.let {
          return it
        }
    }

    val prepared = state.withStablePositions()
    var changed = prepared
    if (scalarOnly) {
      var document = prepared.document
      target.propertyChanges.asReversed().forEach { change ->
        document = document.withValueAt(change.address, change.before)
      }
      changed = changed.copy(document = document)
    } else if (modifierOnly) {
      var document = prepared.document
      target.modifierChanges.asReversed().forEach { change ->
        val node = document.nodes.getValue(change.nodeId)
        document =
          document.copy(nodes = document.nodes + (node.id to node.copy(modifiers = change.before)))
      }
      changed = changed.copy(document = document)
    } else if (environmentOnly) {
      var environment = prepared.document.environment
      target.environmentChanges.asReversed().forEach { change ->
        environment =
          JsonObject(
            if (change.before == null) environment - change.field
            else environment + (change.field to change.before)
          )
      }
      changed = changed.copy(document = prepared.document.copy(environment = environment))
    } else if (structuralOnly) {
      try {
        target.structuralChanges.asReversed().forEach { change ->
          changed = changed.compensateStructure(change, undo = true)
        }
      } catch (failure: ReducerFailure) {
        return state.rejected(
          RejectionCode.UNSAFE_COMPENSATION,
          failure.message.orEmpty(),
          nodeId = failure.nodeId,
        )
      }
    } else {
      try {
        target.compensationChanges.asReversed().forEach { change ->
          changed = changed.compensate(change, undo = true)
        }
      } catch (failure: ReducerFailure) {
        return state.rejected(
          RejectionCode.UNSAFE_COMPENSATION,
          failure.message.orEmpty(),
          nodeId = failure.nodeId,
          field = failure.field,
        )
      }
    }
    if (target.propertyChanges.any { it.address.target != PropertyTarget.Property })
      changed.document.behaviorIssue()?.let { issue ->
        return state.rejected(
          RejectionCode.INVALID_DOCUMENT,
          issue.message,
          nodeId = issue.nodeId,
          field = issue.field,
        )
      }
    documentValidator?.validate(changed.document)?.let { issue ->
      return state.rejected(
        RejectionCode.INVALID_DOCUMENT,
        issue.message,
        nodeId = issue.nodeId,
        field = issue.field,
      )
    }
    val committedRevision = state.document.revision + 1
    val document = changed.document.copy(revision = committedRevision)
    val canonicalDocument = canonicalDocument(document)
    val record =
      AcceptedUndo(
        command,
        target,
        committedRevision,
        canonicalDocument,
        targetActiveRevision,
      )
    // An undo is a rewind, not a new write: it puts each address back to the value *and the
    // revision* it held before the target touched it. Stamping `committedRevision` here instead
    // left the address owned by the undo, so the next undo compared that against the older
    // command's `committedRevision`, saw a mismatch and refused as `UNSAFE_COMPENSATION` — which
    // is why undo worked exactly once and then silently stopped while `canUndo` stayed true.
    //
    // `asReversed()` so that a command writing one address twice restores the version from before
    // its *first* write, matching the value `before` restores.
    val propertyVersions =
      if (target.propertyChanges.isNotEmpty())
        target.propertyChanges.asReversed().fold(changed.propertyVersions) { versions, change ->
          if (change.beforeVersion == null) versions - change.address
          else versions + (change.address to change.beforeVersion)
        }
      else changed.propertyVersions
    val modifierVersions =
      if (target.modifierChanges.isNotEmpty())
        target.modifierChanges.asReversed().fold(changed.modifierVersions) { versions, change ->
          if (change.beforeVersion == null) versions - change.nodeId
          else versions + (change.nodeId to change.beforeVersion)
        }
      else changed.modifierVersions
    val environmentVersions =
      if (target.environmentChanges.isNotEmpty())
        target.environmentChanges.asReversed().fold(changed.environmentVersions) { versions, change
          ->
          if (change.beforeVersion == null) versions - change.field
          else versions + (change.field to change.beforeVersion)
        }
      else changed.environmentVersions
    val structuralVersions =
      if (target.structuralChanges.isNotEmpty())
        target.structuralChanges
          .flatMap { it.affectedNodeIds }
          .fold(changed.structuralVersions) { versions, nodeId ->
            versions + (nodeId to committedRevision)
          }
      else changed.structuralVersions
    val moveVersions =
      if (target.structuralChanges.isNotEmpty())
        target.structuralChanges
          .flatMap { it.affectedNodeIds }
          .fold(changed.moveVersions) { versions, nodeId ->
            versions + (nodeId to committedRevision)
          }
      else changed.moveVersions
    val committed =
      changed.copy(
        document = document,
        propertyVersions = propertyVersions,
        modifierVersions = modifierVersions,
        environmentVersions = environmentVersions,
        structuralVersions = structuralVersions,
        moveVersions = moveVersions,
        positionSnapshots = changed.positionSnapshots + (committedRevision to changed.positions),
        undoRecords = changed.undoRecords + (command.operationId to record),
        compensatedOperationIds = changed.compensatedOperationIds + target.command.operationId,
      )
    return CommandApplication(
      committed,
      CommandOutcome.Accepted(committedRevision, canonicalDocument, idempotentReplay = false),
    )
  }

  fun redo(
    state: CollaborationState,
    command: RedoCommand,
    documentValidator: CollaborationDocumentValidator? = null,
  ): CommandApplication {
    val mutation = RejectedMutation.Redo(command)
    state.replayRejected(mutation)?.let {
      return it
    }
    return redoUnrecorded(state, command, documentValidator).retainRejection(mutation)
  }

  private fun redoUnrecorded(
    state: CollaborationState,
    command: RedoCommand,
    documentValidator: CollaborationDocumentValidator?,
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
    val hasProperties = undo.target.propertyChanges.isNotEmpty()
    val hasModifiers = undo.target.modifierChanges.isNotEmpty()
    val hasStructure = undo.target.structuralChanges.isNotEmpty()
    val hasEnvironment = undo.target.environmentChanges.isNotEmpty()
    val scalarOnly = hasProperties && !hasStructure
    val modifierOnly = hasModifiers && !hasProperties && !hasStructure && !hasEnvironment
    if (hasModifiers && !modifierOnly) {
      return state.rejected(
        RejectionCode.UNSUPPORTED_COMPENSATION,
        "a batch mixing modifiers with other writes cannot be redone",
      )
    }
    val environmentOnly = hasEnvironment && !hasProperties && !hasStructure
    val structuralOnly = hasStructure && !hasProperties
    val mixed = hasProperties && hasStructure && !hasEnvironment
    // "Nothing has touched this address since the undo" — and since the undo *rewound* the
    // address to the revision that owned it beforehand rather than stamping its own, that is the
    // revision to expect here. Comparing against `undo.committedRevision` was the matching half of
    // the stamp that made sequential undo impossible.
    if (scalarOnly) {
      undo.target.propertyChanges.distinctBy(PropertyChange::address).forEach { change ->
        val current = state.document.valueAt(change.address)
        val currentVersion = state.propertyVersions[change.address]
        if (current != change.before || currentVersion != change.beforeVersion) {
          return state.rejected(
            RejectionCode.UNSAFE_COMPENSATION,
            "property changed after ${undo.command.operationId} at revision $currentVersion",
            nodeId = change.address.nodeId,
            field = change.address.property,
          )
        }
      }
    } else if (modifierOnly) {
      undo.target.modifierChanges.distinctBy(ModifierChange::nodeId).forEach { change ->
        val current = state.document.nodes[change.nodeId]?.modifiers
        val currentVersion = state.modifierVersions[change.nodeId]
        if (current != change.before || currentVersion != change.beforeVersion) {
          return state.rejected(
            RejectionCode.UNSAFE_COMPENSATION,
            "modifiers changed after ${undo.command.operationId} at revision $currentVersion",
            nodeId = change.nodeId,
            field = "modifiers",
          )
        }
      }
    } else if (environmentOnly) {
      undo.target.environmentChanges.distinctBy(EnvironmentChange::field).forEach { change ->
        val current = state.document.environment[change.field]
        val currentVersion = state.environmentVersions[change.field]
        if (current != change.before || currentVersion != change.beforeVersion) {
          return state.rejected(
            RejectionCode.UNSAFE_COMPENSATION,
            "environment changed after ${undo.command.operationId} at revision $currentVersion",
            field = change.field,
          )
        }
      }
    } else if (structuralOnly) {
      state
        .validateStructuralCompensation(
          undo.target.structuralChanges,
          undo.committedRevision,
          undo = false,
        )
        ?.let {
          return it
        }
    } else if (!mixed) {
      return state.rejected(
        RejectionCode.UNSUPPORTED_COMPENSATION,
        "undo record has no compensating changes",
      )
    } else {
      undo.target.propertyChanges.distinctBy(PropertyChange::address).forEach { change ->
        val address = change.address
        val version = state.propertyVersions[address]
        if (version != change.beforeVersion) {
          return state.rejected(
            RejectionCode.UNSAFE_COMPENSATION,
            "property changed after ${undo.command.operationId} at revision $version",
            nodeId = address.nodeId,
            field = address.property,
          )
        }
      }
      state
        .validateStructuralCompensation(
          undo.target.structuralChanges,
          undo.committedRevision,
          undo = false,
        )
        ?.let {
          return it
        }
    }

    val prepared = state.withStablePositions()
    var changed = prepared
    if (scalarOnly) {
      var document = prepared.document
      undo.target.propertyChanges.forEach { change ->
        document = document.withValueAt(change.address, change.afterValue)
      }
      changed = changed.copy(document = document)
    } else if (modifierOnly) {
      var document = prepared.document
      undo.target.modifierChanges.forEach { change ->
        val node = document.nodes.getValue(change.nodeId)
        document =
          document.copy(nodes = document.nodes + (node.id to node.copy(modifiers = change.after)))
      }
      changed = changed.copy(document = document)
    } else if (environmentOnly) {
      var environment = prepared.document.environment
      undo.target.environmentChanges.forEach { change ->
        val after = change.after
        environment =
          JsonObject(
            if (after == null) environment - change.field else environment + (change.field to after)
          )
      }
      changed = changed.copy(document = prepared.document.copy(environment = environment))
    } else if (structuralOnly) {
      try {
        undo.target.structuralChanges.forEach { change ->
          changed = changed.compensateStructure(change, undo = false)
        }
      } catch (failure: ReducerFailure) {
        return state.rejected(
          RejectionCode.UNSAFE_COMPENSATION,
          failure.message.orEmpty(),
          nodeId = failure.nodeId,
        )
      }
    } else {
      try {
        undo.target.compensationChanges.forEach { change ->
          changed = changed.compensate(change, undo = false)
        }
      } catch (failure: ReducerFailure) {
        return state.rejected(
          RejectionCode.UNSAFE_COMPENSATION,
          failure.message.orEmpty(),
          nodeId = failure.nodeId,
          field = failure.field,
        )
      }
    }
    if (undo.target.propertyChanges.any { it.address.target != PropertyTarget.Property })
      changed.document.behaviorIssue()?.let { issue ->
        return state.rejected(
          RejectionCode.INVALID_DOCUMENT,
          issue.message,
          nodeId = issue.nodeId,
          field = issue.field,
        )
      }
    documentValidator?.validate(changed.document)?.let { issue ->
      return state.rejected(
        RejectionCode.INVALID_DOCUMENT,
        issue.message,
        nodeId = issue.nodeId,
        field = issue.field,
      )
    }
    val committedRevision = state.document.revision + 1
    val document = changed.document.copy(revision = committedRevision)
    val canonicalDocument = canonicalDocument(document)
    // Redo is the mirror of undo: it puts the target's own revision back on every address the
    // target owned, rather than minting a new one. Minting made the *second* redo in a chain find
    // this redo's revision where it expected the earlier operation's, and refuse — the same shape
    // of failure the undo stamp caused, one lane over.
    //
    // Structural versions below keep minting `committedRevision`, and the structural guards keep
    // reading `activeOperationVersions`. That lane is untouched here.
    val propertyVersions =
      if (hasProperties)
        undo.target.propertyChanges.fold(changed.propertyVersions) { versions, change ->
          versions + (change.address to undo.target.committedRevision)
        }
      else changed.propertyVersions
    val modifierVersions =
      if (hasModifiers)
        undo.target.modifierChanges.fold(changed.modifierVersions) { versions, change ->
          versions + (change.nodeId to undo.target.committedRevision)
        }
      else changed.modifierVersions
    val environmentVersions =
      if (hasEnvironment)
        undo.target.environmentChanges.fold(changed.environmentVersions) { versions, change ->
          versions + (change.field to undo.target.committedRevision)
        }
      else changed.environmentVersions
    val structuralVersions =
      if (hasStructure)
        undo.target.structuralChanges
          .flatMap { it.affectedNodeIds }
          .fold(changed.structuralVersions) { versions, nodeId ->
            versions + (nodeId to committedRevision)
          }
      else changed.structuralVersions
    val moveVersions =
      if (hasStructure)
        undo.target.structuralChanges
          .flatMap { it.affectedNodeIds }
          .fold(changed.moveVersions) { versions, nodeId ->
            versions + (nodeId to committedRevision)
          }
      else changed.moveVersions
    val record =
      AcceptedRedo(command, undo.command.operationId, committedRevision, canonicalDocument)
    val committed =
      changed.copy(
        document = document,
        propertyVersions = propertyVersions,
        modifierVersions = modifierVersions,
        environmentVersions = environmentVersions,
        structuralVersions = structuralVersions,
        moveVersions = moveVersions,
        activeOperationVersions =
          changed.activeOperationVersions + (undo.target.command.operationId to committedRevision),
        positionSnapshots = changed.positionSnapshots + (committedRevision to changed.positions),
        undoRecords =
          changed.undoRecords +
            (undo.command.operationId to undo.copy(redoneBy = command.operationId)),
        redoRecords = changed.redoRecords + (command.operationId to record),
        compensatedOperationIds = changed.compensatedOperationIds - undo.target.command.operationId,
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
    documentValidator: CollaborationDocumentValidator? = null,
  ): CommandApplication {
    var application =
      CommandApplication(
        CollaborationState(initial),
        CommandOutcome.Accepted(initial.revision, canonicalDocument(initial), false),
      )
    commands.forEach { command ->
      application = apply(application.state, command, propertyValidator, documentValidator)
      if (application.outcome is CommandOutcome.Rejected) return application
    }
    return application
  }

  fun replayEvents(
    initial: CollaborationState,
    events: Iterable<CollaborationEvent>,
    propertyValidator: CollaborationPropertyValidator? = null,
    documentValidator: CollaborationDocumentValidator? = null,
  ): CommandApplication {
    var application =
      CommandApplication(
        initial,
        CommandOutcome.Accepted(
          initial.document.revision,
          canonicalDocument(initial.document),
          false,
        ),
      )
    events.forEachIndexed { index, event ->
      val verifiedState = application.state
      application =
        when (val mutation = event.mutation) {
          is RejectedMutation.Design ->
            apply(
              application.state,
              mutation.command,
              propertyValidator,
              documentValidator,
            )
          is RejectedMutation.Undo -> undo(application.state, mutation.command, documentValidator)
          is RejectedMutation.Redo -> redo(application.state, mutation.command, documentValidator)
        }
      if (application.outcome != event.outcome) {
        return verifiedState.rejected(
          RejectionCode.REPLAY_DIVERGENCE,
          "event $index produced ${application.outcome} instead of ${event.outcome}",
        )
      }
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
