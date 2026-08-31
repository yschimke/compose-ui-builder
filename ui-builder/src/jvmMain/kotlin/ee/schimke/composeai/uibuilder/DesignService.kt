package ee.schimke.composeai.uibuilder

import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Resolves the catalog validators pinned by a design before any mutation is admitted. */
fun interface DesignValidationProvider {
  fun validators(document: UiBuilderDocument): DesignValidators
}

data class DesignValidators(
  val property: CollaborationPropertyValidator? = null,
  val document: CollaborationDocumentValidator? = null,
)

sealed interface DesignServiceUpdate {
  val designId: String
  val revision: Int
  val sequence: Long
  val documentHash: String

  /** Replacement state sent when a client's requested sequence is no longer retained. */
  data class Snapshot(
    val document: UiBuilderDocument,
    val lastSequence: Long,
    val retainedFromSequence: Long,
    override val documentHash: String = sha256Hex(canonicalDocument(document)),
  ) : DesignServiceUpdate {
    override val designId: String = document.id
    override val revision: Int = document.revision
    override val sequence: Long = lastSequence
  }

  /** One server-ordered accepted mutation. Rejected mutations are returned only to their caller. */
  data class Committed(
    override val designId: String,
    override val revision: Int,
    override val sequence: Long,
    override val documentHash: String,
    val event: CollaborationEvent,
  ) : DesignServiceUpdate
}

sealed interface CreateDesignResult {
  data class Created(val snapshot: DesignServiceUpdate.Snapshot) : CreateDesignResult

  data class Rejected(val message: String) : CreateDesignResult
}

data class DesignSubmission(
  val application: CommandApplication,
  val update: DesignServiceUpdate.Committed? = null,
)

/**
 * One authoritative, process-local design reducer shared by HTTP, WebSocket, and MCP adapters.
 *
 * The service serializes every design under one lock, retains a bounded delta window, and invokes
 * subscribers outside the lock. It is deliberately transport-free. Durable storage is supplied by
 * the next [DesignStore] layer; this implementation establishes the service/fanout contract without
 * pretending process memory survives restart.
 */
class InMemoryDesignService(
  private val validationProvider: DesignValidationProvider = DesignValidationProvider {
    DesignValidators()
  },
  private val retainedCommittedUpdates: Int = 1_024,
  private val initialDocumentSink: (UiBuilderDocument) -> Unit = {},
  private val eventSink: (String, CollaborationEvent) -> Unit = { _, _ -> },
) {
  private data class DesignEntry(
    var state: CollaborationState,
    var lastSequence: Long = 0,
    val history: ArrayDeque<DesignServiceUpdate.Committed> = ArrayDeque(),
    val subscribers: MutableMap<Long, (DesignServiceUpdate) -> Unit> = linkedMapOf(),
  )

  private val lock = ReentrantLock()
  private val designs = linkedMapOf<String, DesignEntry>()
  private var nextSubscriberId = 1L

  init {
    require(retainedCommittedUpdates > 0) { "retainedCommittedUpdates must be positive" }
  }

  fun create(document: UiBuilderDocument): CreateDesignResult = lock.withLock {
    if (document.id.isBlank()) return@withLock CreateDesignResult.Rejected("design id is blank")
    if (document.id in designs) {
      return@withLock CreateDesignResult.Rejected("design ${document.id} already exists")
    }
    if (document.revision < 0) {
      return@withLock CreateDesignResult.Rejected("design revision must not be negative")
    }
    validateTopology(document)?.let {
      return@withLock CreateDesignResult.Rejected(it)
    }
    validationProvider.validators(document).document?.validate(document)?.let { issue ->
      return@withLock CreateDesignResult.Rejected(issue.message)
    }
    val state = CollaborationState(document)
    initialDocumentSink(document)
    designs[document.id] = DesignEntry(state)
    CreateDesignResult.Created(snapshot(designs.getValue(document.id)))
  }

  /**
   * Restores one store snapshot and its verified event tail without writing them back to the sink.
   */
  fun restore(recovery: StoredDesignRecovery): DesignServiceUpdate.Snapshot = lock.withLock {
    val initial = recovery.initialDocument
    require(initial.id !in designs) { "design ${initial.id} already exists" }
    validateTopology(initial)?.let { throw DesignStoreCorruptionException(it) }
    val validators = validationProvider.validators(initial)
    validators.document?.validate(initial)?.let { issue ->
      throw DesignStoreCorruptionException(issue.message)
    }
    var state = CollaborationState(initial)
    var lastSequence = recovery.snapshotLastSequence
    if (lastSequence < 0) {
      throw DesignStoreCorruptionException("negative snapshot sequence for ${initial.id}")
    }
    val history = ArrayDeque<DesignServiceUpdate.Committed>()
    recovery.events.forEachIndexed { index, event ->
      val application =
        when (val mutation = event.mutation) {
          is RejectedMutation.Design ->
            CollaborationReducer.apply(
              state,
              mutation.command,
              validators.property,
              validators.document,
            )
          is RejectedMutation.Undo ->
            CollaborationReducer.undo(state, mutation.command, validators.document)
          is RejectedMutation.Redo ->
            CollaborationReducer.redo(state, mutation.command, validators.document)
        }
      if (application.outcome != event.outcome) {
        throw DesignStoreCorruptionException(
          "stored event $index for ${initial.id} diverges: " +
            "${application.outcome} != ${event.outcome}"
        )
      }
      state = application.state
      val accepted = event.outcome as? CommandOutcome.Accepted
      if (accepted != null && !accepted.idempotentReplay) {
        lastSequence = nextSequence(lastSequence, initial.id)
        history +=
          DesignServiceUpdate.Committed(
            designId = initial.id,
            revision = accepted.committedRevision,
            sequence = lastSequence,
            documentHash = sha256Hex(accepted.canonicalDocument),
            event = event,
          )
        while (history.size > retainedCommittedUpdates) history.removeFirst()
      }
    }
    val entry = DesignEntry(state, lastSequence, history)
    designs[initial.id] = entry
    snapshot(entry)
  }

  fun list(): List<DesignServiceUpdate.Snapshot> = lock.withLock { designs.values.map(::snapshot) }

  fun open(designId: String): DesignServiceUpdate.Snapshot? = lock.withLock {
    designs[designId]?.let(::snapshot)
  }

  fun apply(command: DesignCommand): DesignSubmission =
    submit(command.designId, RejectedMutation.Design(command)) { state, validators ->
      CollaborationReducer.apply(state, command, validators.property, validators.document)
    }

  fun undo(command: UndoCommand): DesignSubmission =
    submit(command.designId, RejectedMutation.Undo(command)) { state, validators ->
      CollaborationReducer.undo(state, command, validators.document)
    }

  fun redo(command: RedoCommand): DesignSubmission =
    submit(command.designId, RejectedMutation.Redo(command)) { state, validators ->
      CollaborationReducer.redo(state, command, validators.document)
    }

  /**
   * Subscribes atomically with catch-up. A retained sequence receives only later commits; an old or
   * future sequence receives a replacement snapshot. The callback is never invoked under the
   * reducer lock.
   */
  fun subscribe(
    designId: String,
    afterSequence: Long?,
    listener: (DesignServiceUpdate) -> Unit,
  ): Closeable? {
    val initial: List<DesignServiceUpdate>
    val subscriberId: Long
    lock.withLock {
      val entry = designs[designId] ?: return null
      subscriberId = nextSubscriberId++
      initial = catchUp(entry, afterSequence)
      entry.subscribers[subscriberId] = listener
    }
    initial.forEach(listener)
    return Closeable { lock.withLock { designs[designId]?.subscribers?.remove(subscriberId) } }
  }

  private fun submit(
    designId: String,
    mutation: RejectedMutation,
    reduce: (CollaborationState, DesignValidators) -> CommandApplication,
  ): DesignSubmission {
    val listeners: List<(DesignServiceUpdate) -> Unit>
    val submission: DesignSubmission
    lock.withLock {
      val entry = designs[designId]
      if (entry == null) {
        val missing =
          CommandOutcome.Rejected(RejectionCode.DESIGN_MISMATCH, "unknown design $designId")
        return DesignSubmission(
          CommandApplication(
            CollaborationState(missingDocument(designId)),
            missing,
          )
        )
      }
      val validators = validationProvider.validators(entry.state.document)
      val application = reduce(entry.state, validators)
      val accepted = application.outcome as? CommandOutcome.Accepted
      val stateChanged = application.state != entry.state
      if (accepted == null) {
        if (stateChanged) {
          eventSink(designId, CollaborationEvent(mutation, application.outcome))
          entry.state = application.state
        }
        return DesignSubmission(application)
      }
      if (accepted.idempotentReplay) {
        return DesignSubmission(application)
      }
      val event = CollaborationEvent(mutation, accepted)
      val sequence = nextSequence(entry.lastSequence, designId)
      eventSink(designId, event)
      val update =
        DesignServiceUpdate.Committed(
          designId = designId,
          revision = accepted.committedRevision,
          sequence = sequence,
          documentHash = sha256Hex(accepted.canonicalDocument),
          event = event,
        )
      entry.state = application.state
      entry.lastSequence = sequence
      entry.history += update
      while (entry.history.size > retainedCommittedUpdates) entry.history.removeFirst()
      listeners = entry.subscribers.values.toList()
      submission = DesignSubmission(application, update)
    }
    listeners.forEach { it(submission.update!!) }
    return submission
  }

  private fun catchUp(
    entry: DesignEntry,
    afterSequence: Long?,
  ): List<DesignServiceUpdate> {
    val earliestRetainedCursor =
      entry.history.firstOrNull()?.sequence?.minus(1) ?: entry.lastSequence
    return when {
      afterSequence == null ||
        afterSequence < earliestRetainedCursor ||
        afterSequence > entry.lastSequence -> listOf(snapshot(entry))
      else -> entry.history.filter { it.sequence > afterSequence }
    }
  }

  private fun retainedFromSequence(entry: DesignEntry): Long =
    entry.history.firstOrNull()?.sequence
      ?: if (entry.lastSequence == Long.MAX_VALUE) Long.MAX_VALUE else entry.lastSequence + 1

  private fun snapshot(entry: DesignEntry) =
    DesignServiceUpdate.Snapshot(
      document = entry.state.document,
      lastSequence = entry.lastSequence,
      retainedFromSequence = retainedFromSequence(entry),
    )

  private fun nextSequence(current: Long, designId: String): Long {
    if (current == Long.MAX_VALUE) {
      throw DesignStoreCorruptionException("accepted sequence exhausted for $designId")
    }
    return current + 1
  }

  private fun missingDocument(designId: String) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = designId,
      title = "Missing design",
      revision = 0,
      catalogPin = kotlinx.serialization.json.JsonObject(emptyMap()),
      environment = kotlinx.serialization.json.JsonObject(emptyMap()),
      stateVariables = kotlinx.serialization.json.JsonObject(emptyMap()),
      roots = emptyList(),
      nodes = emptyMap(),
    )
}

/** Durable facade used by transports; every acknowledged result is forced to [store] first. */
class PersistentDesignService(
  private val store: DesignStore,
  validationProvider: DesignValidationProvider = DesignValidationProvider { DesignValidators() },
  retainedCommittedUpdates: Int = 1_024,
) {
  private val delegate =
    InMemoryDesignService(
      validationProvider = validationProvider,
      retainedCommittedUpdates = retainedCommittedUpdates,
      initialDocumentSink = store::create,
      eventSink = store::append,
    )

  init {
    store.listDesignIds().forEach { designId ->
      delegate.restore(
        store.load(designId)
          ?: throw DesignStoreCorruptionException("listed design $designId cannot be loaded")
      )
    }
  }

  fun create(document: UiBuilderDocument): CreateDesignResult = delegate.create(document)

  fun list(): List<DesignServiceUpdate.Snapshot> = delegate.list()

  fun open(designId: String): DesignServiceUpdate.Snapshot? = delegate.open(designId)

  fun apply(command: DesignCommand): DesignSubmission = delegate.apply(command)

  fun undo(command: UndoCommand): DesignSubmission = delegate.undo(command)

  fun redo(command: RedoCommand): DesignSubmission = delegate.redo(command)

  fun subscribe(
    designId: String,
    afterSequence: Long?,
    listener: (DesignServiceUpdate) -> Unit,
  ): Closeable? = delegate.subscribe(designId, afterSequence, listener)
}

private fun validateTopology(document: UiBuilderDocument): String? {
  val locations = linkedMapOf<String, String>()
  document.roots.forEach { root ->
    if (root !in document.nodes) return "root $root does not exist"
    if (locations.put(root, "root") != null) return "node $root has multiple parents"
  }
  document.nodes.forEach { (parentId, node) ->
    node.slots.forEach { (slot, children) ->
      children.forEach { child ->
        if (child !in document.nodes) return "child $child does not exist"
        val location = "$parentId.$slot"
        val prior = locations.put(child, location)
        if (prior != null) return "node $child has multiple parents: $prior and $location"
      }
    }
  }
  val visiting = mutableSetOf<String>()
  val visited = mutableSetOf<String>()
  fun visit(id: String): Boolean {
    if (!visiting.add(id)) return false
    if (id in visited) {
      visiting.remove(id)
      return true
    }
    document.nodes.getValue(id).slots.values.flatten().forEach { child ->
      if (!visit(child)) return false
    }
    visiting.remove(id)
    visited += id
    return true
  }
  document.roots.forEach { if (!visit(it)) return "design contains a cycle at $it" }
  val unreachable = document.nodes.keys - visited
  if (unreachable.isNotEmpty())
    return "nodes are unreachable: ${unreachable.sorted().joinToString()}"
  return null
}
