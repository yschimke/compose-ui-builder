@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import java.io.Closeable
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class UiBuilderCatalogIssue(
  val code: String,
  val message: String,
  val nodeId: String? = null,
  val field: String? = null,
)

/** Exact catalog lookup and catalog-specific semantic validation. No permissive default exists. */
interface UiBuilderCatalogExecutor {
  fun listCatalogs(): List<CatalogCapabilityV1>

  fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1?

  fun validate(document: DesignDocumentV1, catalog: CatalogCapabilityV1): UiBuilderCatalogIssue?
}

data class RevisionPinnedUiBuilderExport(
  val actor: AuthenticatedUiBuilderActor,
  val designId: String,
  val revision: Long,
  val documentHash: String,
  val document: DesignDocumentV1,
  val catalog: CatalogCapabilityV1,
  val format: ExportFormatV1,
)

/** Produces a real immutable artifact for an already authorized, revision-pinned document. */
fun interface UiBuilderExportExecutor {
  fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1
}

private class BoundedUiBuilderExportTaskRunner(maximumConcurrentExports: Int) {
  private val threadIds = AtomicLong()
  private val executor =
    ThreadPoolExecutor(
        0,
        maximumConcurrentExports,
        30,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { task ->
          Thread(task, "ui-builder-export-${threadIds.incrementAndGet()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
      )
      .apply { allowCoreThreadTimeOut(true) }

  fun execute(timeoutMillis: Long, task: () -> ExportArtifactV1): ExportArtifactV1 {
    val future = executor.submit(Callable(task))
    try {
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (failure: TimeoutException) {
      future.cancel(true)
      throw UiBuilderExportTimeoutException(failure)
    } catch (failure: ExecutionException) {
      val cause = failure.cause
      if (cause is Exception) throw cause
      throw IllegalStateException("export task failed", cause)
    }
  }
}

private class UiBuilderExportTimeoutException(cause: Throwable) :
  RuntimeException("export timed out", cause)

fun interface UiBuilderSubscriberFailureHandler {
  fun failed(failure: Throwable)
}

data class UiBuilderServiceLimits(
  val maximumDesigns: Int = 1_000,
  val maximumNodesPerDesign: Int = 10_000,
  val retainedCommittedOperations: Int = 1_024,
  val retainedOperationOutcomes: Int = 4_096,
  val retainedRevisionSnapshots: Int = 1_025,
  val retainedAuditRecords: Int = 4_096,
  val subscriberQueueCapacity: Int = 512,
  val maximumOperationsPerBatch: Int = 256,
  val maximumSubscribers: Int = 1_024,
  val maximumSubscribersPerDesign: Int = 128,
  val maximumPresenceSelections: Int = 256,
  val maximumConcurrentExports: Int = 4,
  val exportTimeoutMillis: Long = 30_000,
  val mutationBurstCapacity: Int = 512,
  val mutationRefillAmount: Int = 256,
  val mutationRefillIntervalMillis: Long = 1_000,
  val maximumMutationBuckets: Int = 16_384,
  val maximumSerializedDocumentBytes: Int = 8 * 1_024 * 1_024,
  val maximumEmbeddedAssetBytes: Int = 6 * 1_024 * 1_024,
) {
  init {
    require(maximumDesigns > 0)
    require(maximumNodesPerDesign > 0)
    require(retainedCommittedOperations > 0)
    require(retainedOperationOutcomes > 0)
    require(retainedRevisionSnapshots > 0)
    require(retainedAuditRecords > 0)
    require(subscriberQueueCapacity > 0)
    require(maximumOperationsPerBatch > 0)
    require(maximumSubscribers > 0)
    require(maximumSubscribersPerDesign > 0)
    require(maximumPresenceSelections > 0)
    require(maximumConcurrentExports > 0)
    require(exportTimeoutMillis > 0)
    require(mutationBurstCapacity > 0)
    require(mutationRefillAmount > 0)
    require(mutationRefillIntervalMillis > 0)
    require(maximumMutationBuckets > 0)
    require(maximumSerializedDocumentBytes > 0)
    require(maximumEmbeddedAssetBytes > 0)
  }
}

class UiBuilderSubscriptionRejectedException(val error: UiBuilderServiceError) :
  IllegalStateException(error.message)

/**
 * Authoritative persistent v1 service implementation.
 *
 * Every durable mutation is reduced into an immutable candidate, encoded with an explicit schema
 * and checksum, and atomically stored before it becomes visible or is broadcast. Rejections,
 * idempotent retries, and presence never advance the durable sequence. The implementation is
 * single-process by design; [UiBuilderStateStorage] documents the stronger contract needed for a
 * multi-replica deployment.
 */
class PersistentUiBuilderService(
  private val storage: UiBuilderStateStorage,
  private val catalogs: UiBuilderCatalogExecutor,
  private val exporter: UiBuilderExportExecutor,
  private val subscriberFailureHandler: UiBuilderSubscriberFailureHandler =
    UiBuilderSubscriberFailureHandler {},
  private val clock: Clock = Clock.systemUTC(),
  private val limits: UiBuilderServiceLimits = UiBuilderServiceLimits(),
) : UiBuilderServicePort, UiBuilderServiceDiagnosticsSource {
  private data class MutationBucket(var tokens: Int, var refilledAtMillis: Long)

  private data class RuntimeDesign(
    val presence: MutableMap<String, PresenceV1> = linkedMapOf(),
    val subscribers: MutableMap<Long, Subscriber> = linkedMapOf(),
  )

  private data class Subscriber(
    val actor: AuthenticatedUiBuilderActor,
    val mailbox: SubscriberMailbox,
  )

  private class SubscriberMailbox(
    private val capacity: Int,
    private val listener: (UiBuilderServiceUpdate) -> Unit,
  ) {
    private val monitor = Any()
    private val pending = ArrayDeque<UiBuilderServiceUpdate>()
    private var draining = false
    private var closed = false

    fun enqueue(update: UiBuilderServiceUpdate): Boolean =
      synchronized(monitor) {
        if (closed) return@synchronized false
        if (pending.size >= capacity) {
          closed = true
          pending.clear()
          return@synchronized false
        }
        pending.addLast(update)
        true
      }

    fun drain() {
      synchronized(monitor) {
        if (closed || draining || pending.isEmpty()) return
        draining = true
      }
      try {
        while (true) {
          val next =
            synchronized(monitor) {
              if (closed || pending.isEmpty()) {
                draining = false
                null
              } else {
                pending.removeFirst()
              }
            } ?: return
          listener(next)
        }
      } catch (failure: Throwable) {
        close()
        throw failure
      }
    }

    fun close() {
      synchronized(monitor) {
        closed = true
        pending.clear()
      }
    }
  }

  private data class LockedExecution(
    val response: UiBuilderServiceResponse,
    val mailboxes: List<SubscriberMailbox> = emptyList(),
  )

  private data class LoadedPersistence(
    val value: PersistedServiceV1,
    val format: PersistenceFormat,
  )

  private enum class PersistenceFormat(val wire: String) {
    V1("compose-preview-ui-builder-service/v1"),
    V2("compose-preview-ui-builder-service/v2"),
  }

  private val lock = ReentrantLock()
  private val loadedPersistence = loadPersistence()
  private var persisted: PersistedServiceV1 = loadedPersistence.value
  private var persistenceFormat: PersistenceFormat = loadedPersistence.format
  private val runtime = linkedMapOf<String, RuntimeDesign>()
  private var nextSubscriberId = 1L
  private val exportPermits = Semaphore(limits.maximumConcurrentExports)
  private val exportTaskRunner = BoundedUiBuilderExportTaskRunner(limits.maximumConcurrentExports)
  private val activeExports = AtomicInteger()
  private val peakExports = AtomicLong()
  private val peakSubscribers = AtomicLong()
  private val rejectedBatchLimit = AtomicLong()
  private val rejectedSubscriberLimit = AtomicLong()
  private val slowSubscribersClosed = AtomicLong()
  private val rejectedPresenceLimit = AtomicLong()
  private val rejectedExportLimit = AtomicLong()
  private val rejectedMutationRate = AtomicLong()
  private val rejectedDocumentBytes = AtomicLong()
  private val rejectedAssetBytes = AtomicLong()
  private val timedOutExports = AtomicLong()
  private val persistenceMigrations = AtomicLong()
  private val mutationBuckets = mutableMapOf<Pair<String, String>, MutationBucket>()

  override fun diagnostics(): UiBuilderServiceDiagnostics = lock.withLock {
    UiBuilderServiceDiagnostics(
      activeSubscribers = runtime.values.sumOf { it.subscribers.size },
      peakSubscribers = peakSubscribers.get(),
      rejectedBatchLimit = rejectedBatchLimit.get(),
      rejectedSubscriberLimit = rejectedSubscriberLimit.get(),
      slowSubscribersClosed = slowSubscribersClosed.get(),
      rejectedPresenceLimit = rejectedPresenceLimit.get(),
      activeExports = activeExports.get(),
      peakExports = peakExports.get(),
      rejectedExportLimit = rejectedExportLimit.get(),
      rejectedMutationRate = rejectedMutationRate.get(),
      rejectedDocumentBytes = rejectedDocumentBytes.get(),
      rejectedAssetBytes = rejectedAssetBytes.get(),
      timedOutExports = timedOutExports.get(),
      activeMutationBuckets = mutationBuckets.size,
      persistenceMigrations = persistenceMigrations.get(),
    )
  }

  init {
    validatePersisted(persisted)
    persisted.designs.forEach { (designId, _) -> runtime[designId] = RuntimeDesign() }
  }

  private fun validatePersisted(value: PersistedServiceV1) {
    require(value.designs.size <= limits.maximumDesigns) {
      "stored design count exceeds configured limit"
    }
    value.designs.forEach { (designId, design) ->
      require(designId == design.document.id) { "stored design key/id mismatch for $designId" }
      require(design.document.nodes.size <= limits.maximumNodesPerDesign) {
        "stored node count exceeds configured limit for $designId"
      }
      documentQuotaIssue(design.document)?.let {
        throw UiBuilderPersistenceException("stored design $designId exceeds configured limit: $it")
      }
      validateTopology(design.document)?.let {
        throw UiBuilderPersistenceException("invalid stored design $designId: ${it.message}")
      }
      val catalog =
        catalogs.resolve(design.document.catalogPin)
          ?: throw UiBuilderPersistenceException("catalog unavailable for stored design $designId")
      catalogs.validate(design.document, catalog)?.let {
        throw UiBuilderPersistenceException("invalid stored design $designId: ${it.message}")
      }
    }
  }

  override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
    if (call.request is UiBuilderServiceRequest.ExportDesign) return export(call)
    val execution = lock.withLock { executeLocked(call) }
    drain(execution.mailboxes)
    return execution.response
  }

  override fun subscribe(
    call: UiBuilderSubscriptionCall,
    listener: (UiBuilderServiceUpdate) -> Unit,
  ): Closeable {
    val subscriberId: Long
    val mailbox: SubscriberMailbox
    lock.withLock {
      val design =
        persisted.designs[call.designId]
          ?: throw UiBuilderSubscriptionRejectedException(notFound(call.designId))
      if (!design.allows(call.actor.actorId, DesignAccessActionV1.READ)) {
        throw UiBuilderSubscriptionRejectedException(forbidden("read", call.designId))
      }
      if (
        runtime.values.sumOf { it.subscribers.size } >= limits.maximumSubscribers ||
          runtime.getValue(call.designId).subscribers.size >= limits.maximumSubscribersPerDesign
      ) {
        rejectedSubscriberLimit.incrementAndGet()
        throw UiBuilderSubscriptionRejectedException(
          UiBuilderServiceError(ServiceErrorCodeV1.BAD_REQUEST, "subscriber limit reached")
        )
      }
      mailbox = SubscriberMailbox(limits.subscriberQueueCapacity, listener)
      subscriberId = nextSubscriberId++
      val update = catchUp(design, call.actor, call.afterSequence)
      check(mailbox.enqueue(update)) { "new subscriber mailbox rejected its initial update" }
      runtime.getValue(call.designId).subscribers[subscriberId] = Subscriber(call.actor, mailbox)
      updatePeak(peakSubscribers, runtime.values.sumOf { it.subscribers.size }.toLong())
    }
    try {
      mailbox.drain()
    } catch (failure: Throwable) {
      lock.withLock { runtime[call.designId]?.subscribers?.remove(subscriberId) }
      mailbox.close()
      throw failure
    }
    return Closeable {
      val removed = lock.withLock {
        runtime[call.designId]?.subscribers?.remove(subscriberId)?.mailbox
      }
      removed?.close()
    }
  }

  private fun executeLocked(call: UiBuilderServiceCall): LockedExecution =
    when (val request = call.request) {
      UiBuilderServiceRequest.ListCatalogs ->
        LockedExecution(UiBuilderServiceResponse.Catalogs(catalogs.listCatalogs()))
      is UiBuilderServiceRequest.CreateDesign -> create(call.actor, request.document)
      is UiBuilderServiceRequest.ListDesigns -> list(call.actor, request)
      is UiBuilderServiceRequest.OpenDesign -> open(call.actor, request.designId, revision = null)
      is UiBuilderServiceRequest.GetDesignAccess -> access(call.actor, request.designId)
      is UiBuilderServiceRequest.UpdateDesignAccess -> updateAccess(call.actor, request)
      is UiBuilderServiceRequest.ApplyOperation -> apply(call.actor, request.submission)
      is UiBuilderServiceRequest.GetSnapshot -> open(call.actor, request.designId, request.revision)
      is UiBuilderServiceRequest.GetDelta -> delta(call.actor, request)
      is UiBuilderServiceRequest.UpdatePresence -> presence(call.actor, request)
      is UiBuilderServiceRequest.ExportDesign ->
        error("export is executed outside the service lock")
    }

  private fun create(
    actor: AuthenticatedUiBuilderActor,
    requested: DesignDocumentV1,
  ): LockedExecution {
    if (persisted.designs.size >= limits.maximumDesigns) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "design limit reached")
    }
    if (requested.id.isBlank() || requested.id in persisted.designs) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "design id is blank or already exists")
    }
    if (requested.revision != 0L) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "new designs must start at revision 0")
    }
    if (requested.nodes.size > limits.maximumNodesPerDesign) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "design node limit exceeded")
    }
    val now = clock.millis()
    val document = requested.copy(createdAtEpochMillis = now, updatedAtEpochMillis = now)
    documentQuotaIssue(document, countRejection = true)?.let {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, it)
    }
    validateTopology(document)?.let {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, it.message)
    }
    val catalog =
      catalogs.resolve(document.catalogPin)
        ?: return serviceError(ServiceErrorCodeV1.CATALOG_UNAVAILABLE, "catalog pin is unavailable")
    catalogs.validate(document, catalog)?.let {
      return serviceError(it.toServiceError())
    }

    val design =
      PersistedDesignV1(
        document = document,
        lastSequence = 0,
        access = DesignAccessControlV1(0, actor.actorId),
        revisionSnapshots = listOf(RevisionStateV1(document, 0)),
        positions = derivePositions(document),
        positionSnapshots = listOf(PositionStateV1(0, derivePositions(document))),
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
      )
    commitPersisted(persisted.copy(designs = persisted.designs + (document.id to design)))
    runtime[document.id] = RuntimeDesign()
    return LockedExecution(
      UiBuilderServiceResponse.Snapshot(snapshot(design, actor, catalog, emptyList()))
    )
  }

  private fun list(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest.ListDesigns,
  ): LockedExecution {
    if (request.limit !in 1..200) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "list limit must be between 1 and 200")
    }
    val accessible =
      persisted.designs.values
        .filter { it.allows(actor.actorId, DesignAccessActionV1.READ) }
        .sortedBy { it.document.id }
    val offset =
      request.cursor?.toIntOrNull()?.takeIf { it >= 0 }
        ?: if (request.cursor == null) 0
        else return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "invalid list cursor")
    if (offset > accessible.size) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "list cursor is past the result set")
    }
    val page = accessible.drop(offset).take(request.limit)
    val next = (offset + page.size).takeIf { it < accessible.size }?.toString()
    return LockedExecution(
      UiBuilderServiceResponse.Designs(page.map { it.listItem(actor.actorId) }, next)
    )
  }

  private fun open(
    actor: AuthenticatedUiBuilderActor,
    designId: String,
    revision: Long?,
  ): LockedExecution {
    val design = persisted.designs[designId] ?: return serviceError(notFound(designId))
    if (!design.allows(actor.actorId, DesignAccessActionV1.READ)) {
      return serviceError(forbidden("read", designId))
    }
    val state =
      if (revision == null) RevisionStateV1(design.document, design.lastSequence)
      else
        design.revisionSnapshots.firstOrNull { it.document.revision == revision }
          ?: return serviceError(
            UiBuilderServiceError(
              code = ServiceErrorCodeV1.SNAPSHOT_REQUIRED,
              message = "revision $revision is no longer retained for $designId",
              currentRevision = design.document.revision,
              retainedFromSequence = design.retainedFromSequence(),
            )
          )
    val catalog =
      catalogs.resolve(state.document.catalogPin)
        ?: return serviceError(
          ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
          "catalog pin is unavailable",
        )
    return LockedExecution(
      UiBuilderServiceResponse.Snapshot(
        snapshot(
          design.copy(
            document = state.document,
            lastSequence = state.sequence,
            history = design.history.filter { it.outcome.sequence <= state.sequence },
          ),
          actor,
          catalog,
          runtime.getValue(designId).presence.values.toList(),
        )
      )
    )
  }

  private fun access(actor: AuthenticatedUiBuilderActor, designId: String): LockedExecution {
    val design = persisted.designs[designId] ?: return serviceError(notFound(designId))
    if (design.access.ownerActorId != actor.actorId) {
      return serviceError(forbidden("manage access for", designId))
    }
    return LockedExecution(UiBuilderServiceResponse.DesignAccess(designId, design.access))
  }

  private fun updateAccess(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest.UpdateDesignAccess,
  ): LockedExecution {
    val design =
      persisted.designs[request.designId] ?: return serviceError(notFound(request.designId))
    if (design.access.ownerActorId != actor.actorId) {
      return serviceError(forbidden("manage access for", request.designId))
    }
    if (request.baseAccessRevision != design.access.accessRevision) {
      return serviceError(
        UiBuilderServiceError(
          code = ServiceErrorCodeV1.ACCESS_REVISION_MISMATCH,
          message = "access revision changed for ${request.designId}",
          currentAccessRevision = design.access.accessRevision,
        )
      )
    }
    if (request.mutations.isEmpty()) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "access mutation list is empty")
    }
    var access = design.access
    val now = clock.millis()
    request.mutations.forEach { mutation ->
      when (mutation) {
        is GrantActorAccessMutationV1 -> {
          if (mutation.actorId.isBlank() || mutation.actorId == access.ownerActorId) {
            return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "invalid actor grant target")
          }
          if (mutation.role == DesignAccessRoleV1.OWNER) {
            return serviceError(
              ServiceErrorCodeV1.BAD_REQUEST,
              "ownership changes require transferOwnership",
            )
          }
          val grant =
            DesignActorGrantV1(
              mutation.actorId,
              mutation.role,
              mutation.allowedActions.distinct(),
              actor.actorId,
              now,
            )
          access =
            access.copy(
              actorGrants = access.actorGrants.filterNot { it.actorId == mutation.actorId } + grant
            )
        }
        is RevokeActorAccessMutationV1 -> {
          if (mutation.actorId == access.ownerActorId) {
            return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "the owner cannot be revoked")
          }
          access =
            access.copy(
              actorGrants = access.actorGrants.filterNot { it.actorId == mutation.actorId }
            )
        }
        is TransferDesignOwnershipMutationV1 -> {
          if (
            mutation.newOwnerActorId.isBlank() || mutation.newOwnerActorId == access.ownerActorId
          ) {
            return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "invalid new owner")
          }
          val formerOwner = access.ownerActorId
          val formerOwnerGrant =
            DesignActorGrantV1(
              actorId = formerOwner,
              role = DesignAccessRoleV1.EDITOR,
              allowedActions =
                listOf(
                  DesignAccessActionV1.READ,
                  DesignAccessActionV1.WRITE,
                  DesignAccessActionV1.EXPORT,
                ),
              grantedByActorId = actor.actorId,
              grantedAtEpochMillis = now,
            )
          access =
            access.copy(
              ownerActorId = mutation.newOwnerActorId,
              actorGrants =
                access.actorGrants.filterNot {
                  it.actorId == mutation.newOwnerActorId || it.actorId == formerOwner
                } + formerOwnerGrant,
            )
        }
        is CreateDesignShareLinkMutationV1,
        is RevokeDesignShareLinkMutationV1 ->
          return serviceError(
            ServiceErrorCodeV1.BAD_REQUEST,
            "share-link authentication is not supported by the authenticated-actor service port",
          )
      }
    }
    access = access.copy(accessRevision = access.accessRevision + 1)
    val updated = design.copy(access = access, updatedAtEpochMillis = now)
    commitPersisted(persisted.copy(designs = persisted.designs + (request.designId to updated)))

    val closed = mutableListOf<SubscriberMailbox>()
    runtime.getValue(request.designId).subscribers.entries.removeIf { (_, subscriber) ->
      val revoke = !updated.allows(subscriber.actor.actorId, DesignAccessActionV1.READ)
      if (revoke) closed += subscriber.mailbox
      revoke
    }
    closed.forEach(SubscriberMailbox::close)
    return LockedExecution(UiBuilderServiceResponse.DesignAccess(request.designId, access))
  }

  private fun apply(
    actor: AuthenticatedUiBuilderActor,
    submission: UiBuilderSubmission,
  ): LockedExecution {
    val design =
      persisted.designs[submission.designId] ?: return serviceError(notFound(submission.designId))
    if (!design.allows(actor.actorId, DesignAccessActionV1.WRITE)) {
      return serviceError(forbidden("write", submission.designId))
    }
    if (
      submission is UiBuilderSubmission.Batch &&
        submission.operations.size > limits.maximumOperationsPerBatch
    ) {
      rejectedBatchLimit.incrementAndGet()
      return LockedExecution(
        UiBuilderServiceResponse.OperationOutcome(
          rejected(
            submission.operationId,
            design.document.revision,
            RejectionCodeV1.INVALID_COMMAND,
            "atomic batch exceeds ${limits.maximumOperationsPerBatch} operations",
          )
        )
      )
    }
    val wire = submission.toProtocol(actor)
    val fingerprint = canonicalJson(json.encodeToJsonElement<DesignSubmissionV1>(wire))
    design.operationOutcomes[submission.operationId]?.let { prior ->
      if (prior.fingerprint != fingerprint) {
        return LockedExecution(
          UiBuilderServiceResponse.OperationOutcome(
            rejected(
              submission.operationId,
              design.document.revision,
              RejectionCodeV1.OPERATION_ID_REUSED,
              "operation id was already used by a different submission",
            )
          )
        )
      }
      val outcome =
        when (val original = prior.outcome) {
          is AcceptedOutcomeV1 -> original.copy(idempotentReplay = true)
          is RejectedOutcomeV1 -> original
        }
      return LockedExecution(UiBuilderServiceResponse.OperationOutcome(outcome))
    }
    val mutationCost =
      if (submission is UiBuilderSubmission.Batch) submission.operations.size.coerceAtLeast(1)
      else 1
    if (!admitMutation(actor.actorId, submission.designId, mutationCost)) {
      rejectedMutationRate.incrementAndGet()
      return LockedExecution(
        UiBuilderServiceResponse.OperationOutcome(
          rejected(
            submission.operationId,
            design.document.revision,
            RejectionCodeV1.INVALID_COMMAND,
            "mutation rate limit exceeded",
          )
        )
      )
    }
    val reduction = reduce(design, actor, wire)
    if (reduction.outcome is AcceptedOutcomeV1) {
      documentQuotaIssue(reduction.design.document, countRejection = true)?.let {
        return LockedExecution(
          UiBuilderServiceResponse.OperationOutcome(
            rejected(
              submission.operationId,
              design.document.revision,
              RejectionCodeV1.INVALID_COMMAND,
              it,
            )
          )
        )
      }
    }
    val outcomes =
      (reduction.design.operationOutcomes +
          (submission.operationId to OperationOutcomeRecordV1(fingerprint, reduction.outcome)))
        .entries
        .toList()
        .takeLast(limits.retainedOperationOutcomes)
        .associate { it.toPair() }
    val recorded =
      reduction.design.copy(
        operationOutcomes = outcomes,
        acceptedOperations = reduction.design.acceptedOperations.filterKeys { it in outcomes.keys },
      )
    val candidate = persisted.copy(designs = persisted.designs + (submission.designId to recorded))
    commitPersisted(candidate)
    if (reduction.outcome !is AcceptedOutcomeV1) {
      return LockedExecution(UiBuilderServiceResponse.OperationOutcome(reduction.outcome))
    }

    val delta =
      ServiceDeltaV1(
        designId = submission.designId,
        afterSequence = reduction.outcome.sequence - 1,
        throughSequence = reduction.outcome.sequence,
        currentRevision = reduction.outcome.committedRevision,
        retainedFromSequence = recorded.retainedFromSequence(),
        operations = listOf(CommittedOperationV1(wire, reduction.outcome)),
      )
    val mailboxes = enqueue(submission.designId, UiBuilderServiceUpdate.Delta(delta), recorded)
    return LockedExecution(
      UiBuilderServiceResponse.OperationOutcome(reduction.outcome),
      mailboxes,
    )
  }

  private fun admitMutation(actorId: String, designId: String, cost: Int): Boolean {
    val now = clock.millis()
    val key = actorId to designId
    if (key !in mutationBuckets && mutationBuckets.size >= limits.maximumMutationBuckets) {
      return false
    }
    val bucket = mutationBuckets.getOrPut(key) { MutationBucket(limits.mutationBurstCapacity, now) }
    val elapsed = (now - bucket.refilledAtMillis).coerceAtLeast(0)
    val refillPeriods = elapsed / limits.mutationRefillIntervalMillis
    if (refillPeriods > 0) {
      val refill =
        (refillPeriods * limits.mutationRefillAmount.toLong())
          .coerceAtMost(limits.mutationBurstCapacity.toLong())
          .toInt()
      bucket.tokens = (bucket.tokens + refill).coerceAtMost(limits.mutationBurstCapacity)
      bucket.refilledAtMillis += refillPeriods * limits.mutationRefillIntervalMillis
    }
    if (cost > bucket.tokens) return false
    bucket.tokens -= cost
    return true
  }

  private fun documentQuotaIssue(
    document: DesignDocumentV1,
    countRejection: Boolean = false,
  ): String? {
    val embeddedBytes =
      document.assets.values.fold(0L) { total, asset ->
        val source = asset.source
        if (source is EmbeddedAssetSourceV1) {
          (total + conservativeDecodedBase64Bytes(source.base64)).coerceAtMost(
            Int.MAX_VALUE.toLong()
          )
        } else {
          total
        }
      }
    if (embeddedBytes > limits.maximumEmbeddedAssetBytes) {
      if (countRejection) rejectedAssetBytes.incrementAndGet()
      return "embedded asset byte limit exceeded"
    }
    val serializedBytes =
      json.encodeToString(DesignDocumentV1.serializer(), document).encodeToByteArray().size
    if (serializedBytes > limits.maximumSerializedDocumentBytes) {
      if (countRejection) rejectedDocumentBytes.incrementAndGet()
      return "serialized document byte limit exceeded"
    }
    return null
  }

  private fun delta(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest.GetDelta,
  ): LockedExecution {
    val design =
      persisted.designs[request.designId] ?: return serviceError(notFound(request.designId))
    if (!design.allows(actor.actorId, DesignAccessActionV1.READ)) {
      return serviceError(forbidden("read", request.designId))
    }
    if (request.limit !in 1..1_024) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "delta limit must be between 1 and 1024")
    }
    if (
      request.afterSequence < design.retainedFromSequence() ||
        request.afterSequence > design.lastSequence
    ) {
      return serviceError(
        UiBuilderServiceError(
          code = ServiceErrorCodeV1.SNAPSHOT_REQUIRED,
          message = "requested sequence is not retained",
          currentRevision = design.document.revision,
          retainedFromSequence = design.retainedFromSequence(),
        )
      )
    }
    return LockedExecution(
      UiBuilderServiceResponse.Delta(design.deltaAfter(request.afterSequence, request.limit))
    )
  }

  private fun presence(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest.UpdatePresence,
  ): LockedExecution {
    val design =
      persisted.designs[request.designId] ?: return serviceError(notFound(request.designId))
    if (!design.allows(actor.actorId, DesignAccessActionV1.READ)) {
      return serviceError(forbidden("read", request.designId))
    }
    if (
      request.presence.clientId.isBlank() ||
        request.presence.selectedNodeIds.any { it !in design.document.nodes }
    ) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "invalid presence payload")
    }
    if (request.presence.selectedNodeIds.size > limits.maximumPresenceSelections) {
      rejectedPresenceLimit.incrementAndGet()
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "presence selection limit exceeded")
    }
    val value = request.presence.toProtocol(actor)
    runtime.getValue(request.designId).presence[actor.actorId] = value
    val mailboxes =
      enqueue(
        request.designId,
        UiBuilderServiceUpdate.Presence(PresenceUpsertV1(value)),
        design,
      )
    return LockedExecution(
      UiBuilderServiceResponse.PresenceAccepted(request.designId, actor.actorId),
      mailboxes,
    )
  }

  private suspend fun export(call: UiBuilderServiceCall): UiBuilderServiceResponse {
    if (!exportPermits.tryAcquire()) {
      rejectedExportLimit.incrementAndGet()
      return UiBuilderServiceResponse.Error(
        UiBuilderServiceError(
          ServiceErrorCodeV1.BAD_REQUEST,
          "concurrent export limit reached",
          retryable = true,
        )
      )
    }
    val currentExports = activeExports.incrementAndGet()
    updatePeak(peakExports, currentExports.toLong())
    try {
      return exportAdmitted(call)
    } finally {
      activeExports.decrementAndGet()
      exportPermits.release()
    }
  }

  private suspend fun exportAdmitted(call: UiBuilderServiceCall): UiBuilderServiceResponse {
    val request = call.request as UiBuilderServiceRequest.ExportDesign
    val pinned: RevisionPinnedUiBuilderExport
    val pinnedSequence: Long
    lock.withLock {
      val design =
        persisted.designs[request.designId]
          ?: return UiBuilderServiceResponse.Error(notFound(request.designId))
      if (!design.allows(call.actor.actorId, DesignAccessActionV1.EXPORT)) {
        return UiBuilderServiceResponse.Error(forbidden("export", request.designId))
      }
      val revision = request.revision ?: design.document.revision
      val state =
        design.revisionSnapshots.firstOrNull { it.document.revision == revision }
          ?: return UiBuilderServiceResponse.Error(
            UiBuilderServiceError(
              ServiceErrorCodeV1.SNAPSHOT_REQUIRED,
              "export revision $revision is not retained",
              currentRevision = design.document.revision,
              retainedFromSequence = design.retainedFromSequence(),
            )
          )
      val catalog =
        catalogs.resolve(state.document.catalogPin)
          ?: return UiBuilderServiceResponse.Error(
            UiBuilderServiceError(
              ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
              "catalog pin is unavailable",
            )
          )
      if (!catalog.supports(request.format)) {
        return UiBuilderServiceResponse.Error(
          UiBuilderServiceError(
            ServiceErrorCodeV1.BAD_REQUEST,
            "catalog does not support ${request.format} export",
          )
        )
      }
      documentQuotaIssue(state.document, countRejection = true)?.let {
        return UiBuilderServiceResponse.Error(
          UiBuilderServiceError(ServiceErrorCodeV1.BAD_REQUEST, it)
        )
      }
      pinned =
        RevisionPinnedUiBuilderExport(
          actor = call.actor,
          designId = request.designId,
          revision = revision,
          documentHash = documentHash(state.document),
          document = state.document,
          catalog = catalog,
          format = request.format,
        )
      pinnedSequence = state.sequence
    }
    val artifact =
      try {
        exportTaskRunner.execute(limits.exportTimeoutMillis) { exporter.export(pinned) }
      } catch (_: UiBuilderExportTimeoutException) {
        timedOutExports.incrementAndGet()
        return UiBuilderServiceResponse.Error(
          UiBuilderServiceError(
            ServiceErrorCodeV1.INTERNAL,
            "export timed out",
            retryable = true,
          )
        )
      } catch (_: RejectedExecutionException) {
        rejectedExportLimit.incrementAndGet()
        return UiBuilderServiceResponse.Error(
          UiBuilderServiceError(
            ServiceErrorCodeV1.BAD_REQUEST,
            "concurrent export worker limit reached",
            retryable = true,
          )
        )
      } catch (failure: Exception) {
        return UiBuilderServiceResponse.Error(
          UiBuilderServiceError(ServiceErrorCodeV1.INTERNAL, "export failed: ${failure.message}")
        )
      }
    val validDigest =
      try {
        artifact.contentDigest == artifactDigest(artifact)
      } catch (_: IllegalArgumentException) {
        false
      }
    if (artifact.format != pinned.format || !validDigest) {
      return UiBuilderServiceResponse.Error(
        UiBuilderServiceError(
          ServiceErrorCodeV1.INTERNAL,
          "export executor returned a mismatched format or content digest",
        )
      )
    }
    lock.withLock {
      val design =
        persisted.designs[request.designId]
          ?: return UiBuilderServiceResponse.Error(notFound(request.designId))
      val audit =
        AuditRecordV1(
          kind = AuditKindV1.EXPORT,
          actorId = call.actor.actorId,
          designId = request.designId,
          revision = pinned.revision,
          sequence = pinnedSequence,
          operationId = null,
          exportFormat = request.format,
          atEpochMillis = clock.millis(),
        )
      val updated =
        design.copy(audit = (design.audit + audit).takeLast(limits.retainedAuditRecords))
      commitPersisted(persisted.copy(designs = persisted.designs + (request.designId to updated)))
    }
    return UiBuilderServiceResponse.Export(artifact)
  }

  private fun reduce(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    submission: DesignSubmissionV1,
  ): ReductionResult {
    if (submission.designId() != design.document.id || submission.clientId().isBlank()) {
      return rejectedReduction(
        design,
        submission.operationId(),
        RejectionCodeV1.INVALID_COMMAND,
        "invalid design or client id",
      )
    }
    if (submission.baseRevision() < 0 || submission.baseRevision() > design.document.revision) {
      return rejectedReduction(
        design,
        submission.operationId(),
        RejectionCodeV1.REVISION_MISMATCH,
        "base revision is not available",
      )
    }
    return when (submission) {
      is DesignCommandV1 -> reduceBatch(design, actor, submission)
      is UndoCommandV1 -> reduceUndo(design, actor, submission)
      is RedoCommandV1 -> reduceRedo(design, actor, submission)
    }
  }

  private fun reduceBatch(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    command: DesignCommandV1,
  ): ReductionResult {
    if (command.operations.isEmpty()) {
      return rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.INVALID_COMMAND,
        "an atomic batch requires at least one mutation",
      )
    }
    if (
      command.baseRevision < design.document.revision &&
        command.operations.any { it is DeleteNodeMutationV1 || it is RestoreNodeMutationV1 }
    ) {
      return rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.REVISION_MISMATCH,
        "stale delete/restore requires the current revision",
      )
    }

    val basePositions =
      design.positionSnapshots.firstOrNull { it.revision == command.baseRevision }?.positions
        ?: return rejectedReduction(
          design,
          command.operationId,
          RejectionCodeV1.REVISION_NOT_RETAINED,
          "position state for revision ${command.baseRevision} is not retained",
        )
    var working = WorkingDesign(design.document, design.tombstones, design.positions)
    val changes = mutableListOf<ChangeRecordV1>()
    val conflicts = mutableListOf<CommandConflictV1>()
    command.operations.forEachIndexed { index, mutation ->
      val batchPositions =
        basePositions +
          changes
            .filterIsInstance<StructureChangeV1>()
            .flatMap { it.affectedNodeIds }
            .mapNotNull { nodeId -> working.positions[nodeId]?.let { nodeId to it } }
      val applied = applyMutation(working, mutation, command, design, batchPositions, index)
      if (applied.error != null) {
        return rejectedReduction(design, command.operationId, applied.error)
      }
      working = requireNotNull(applied.working)
      changes += requireNotNull(applied.change)
      conflicts += applied.conflicts
    }
    if (working.document.nodes.size > limits.maximumNodesPerDesign) {
      return rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.INVALID_DOCUMENT,
        "design node limit exceeded",
      )
    }
    validateTopology(working.document)?.let {
      return rejectedReduction(design, command.operationId, it)
    }
    val catalog =
      catalogs.resolve(working.document.catalogPin)
        ?: return rejectedReduction(
          design,
          command.operationId,
          RejectionCodeV1.INVALID_DOCUMENT,
          "catalog pin is unavailable",
        )
    catalogs.validate(working.document, catalog)?.let {
      return rejectedReduction(design, command.operationId, it.toRejection())
    }
    return accept(
      design,
      actor,
      command,
      working,
      changes,
      conflicts,
      targetOperationId = null,
      targetUndoOperationId = null,
    )
  }

  private fun reduceUndo(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    command: UndoCommandV1,
  ): ReductionResult {
    val target =
      design.acceptedOperations[command.targetOperationId]
        ?: return rejectedReduction(
          design,
          command.operationId,
          RejectionCodeV1.UNKNOWN_OPERATION,
          "unknown target operation ${command.targetOperationId}",
        )
    if (target.actorId != actor.actorId) {
      return rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.ACTOR_MISMATCH,
        "an actor may undo only its own operation",
      )
    }
    if (target.compensatedBy != null) {
      return rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.ALREADY_COMPENSATED,
        "target operation is already compensated",
      )
    }
    val compensated = compensate(design, target, undo = true)
    if (compensated.error != null) {
      return rejectedReduction(design, command.operationId, compensated.error)
    }
    return accept(
      design,
      actor,
      command,
      requireNotNull(compensated.working),
      target.changes,
      emptyList(),
      targetOperationId = target.operationId,
      targetUndoOperationId = null,
    )
  }

  private fun reduceRedo(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    command: RedoCommandV1,
  ): ReductionResult {
    val undo =
      design.acceptedOperations[command.targetUndoOperationId]
        ?: return rejectedReduction(
          design,
          command.operationId,
          RejectionCodeV1.UNKNOWN_OPERATION,
          "unknown undo operation ${command.targetUndoOperationId}",
        )
    if (undo.kind != AcceptedKindV1.UNDO || undo.actorId != actor.actorId) {
      return rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.ACTOR_MISMATCH,
        "an actor may redo only its own accepted undo",
      )
    }
    if (undo.compensatedBy != null) {
      return rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.ALREADY_COMPENSATED,
        "undo is already redone",
      )
    }
    val original =
      undo.targetOperationId?.let(design.acceptedOperations::get)
        ?: return rejectedReduction(
          design,
          command.operationId,
          RejectionCodeV1.UNKNOWN_OPERATION,
          "undo no longer has its original target",
        )
    val compensated = compensate(design, original, undo = false)
    if (compensated.error != null) {
      return rejectedReduction(design, command.operationId, compensated.error)
    }
    return accept(
      design,
      actor,
      command,
      requireNotNull(compensated.working),
      original.changes,
      emptyList(),
      targetOperationId = original.operationId,
      targetUndoOperationId = undo.operationId,
    )
  }

  private fun accept(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    submission: DesignSubmissionV1,
    working: WorkingDesign,
    changes: List<ChangeRecordV1>,
    conflicts: List<CommandConflictV1>,
    targetOperationId: String?,
    targetUndoOperationId: String?,
  ): ReductionResult {
    val revision = design.document.revision + 1
    val sequence = design.lastSequence + 1
    val now = clock.millis()
    val document = working.document.copy(revision = revision, updatedAtEpochMillis = now)
    val outcome =
      AcceptedOutcomeV1(
        submission.operationId(),
        revision,
        sequence,
        documentHash(document),
        idempotentReplay = false,
        conflicts = conflicts,
      )
    val kind =
      when (submission) {
        is DesignCommandV1 -> AcceptedKindV1.BATCH
        is UndoCommandV1 -> AcceptedKindV1.UNDO
        is RedoCommandV1 -> AcceptedKindV1.REDO
      }
    val record =
      AcceptedOperationRecordV1(
        operationId = submission.operationId(),
        actorId = actor.actorId,
        kind = kind,
        committedRevision = revision,
        activeRevision = revision,
        changes = changes,
        targetOperationId = targetOperationId,
      )
    var accepted = design.acceptedOperations + (record.operationId to record)
    if (submission is UndoCommandV1 && targetOperationId != null) {
      accepted =
        accepted +
          (targetOperationId to
            accepted.getValue(targetOperationId).copy(compensatedBy = record.operationId))
    }
    if (submission is RedoCommandV1 && targetUndoOperationId != null && targetOperationId != null) {
      accepted =
        accepted +
          (targetUndoOperationId to
            accepted.getValue(targetUndoOperationId).copy(compensatedBy = record.operationId)) +
          (targetOperationId to
            accepted
              .getValue(targetOperationId)
              .copy(
                compensatedBy = null,
                activeRevision = revision,
              ))
    }
    val committed = CommittedOperationV1(submission, outcome)
    val history = (design.history + committed).takeLast(limits.retainedCommittedOperations)
    val snapshots =
      (design.revisionSnapshots + RevisionStateV1(document, sequence)).takeLast(
        limits.retainedRevisionSnapshots
      )
    val audit =
      (design.audit +
          AuditRecordV1(
            AuditKindV1.COMMIT,
            actor.actorId,
            design.document.id,
            revision,
            sequence,
            submission.operationId(),
            null,
            now,
          ))
        .takeLast(limits.retainedAuditRecords)
    val updated =
      design.copy(
        document = document,
        lastSequence = sequence,
        history = history,
        revisionSnapshots = snapshots,
        positions = working.positions,
        positionSnapshots =
          (design.positionSnapshots + PositionStateV1(revision, working.positions)).takeLast(
            limits.retainedRevisionSnapshots
          ),
        acceptedOperations = accepted,
        tombstones = working.tombstones,
        updatedAtEpochMillis = now,
        audit = audit,
      )
    return ReductionResult(updated, outcome)
  }

  private fun applyMutation(
    working: WorkingDesign,
    mutation: DesignMutationV1,
    command: DesignCommandV1,
    original: PersistedDesignV1,
    basePositions: Map<String, StableNodePositionV1>,
    index: Int,
  ): MutationResult =
    try {
      when (mutation) {
        is InsertNodeMutationV1 -> {
          if (
            mutation.node.id.isBlank() ||
              mutation.node.id in working.document.nodes ||
              mutation.node.id in working.tombstones
          ) {
            fail(
              RejectionCodeV1.INVALID_COMMAND,
              "node id is blank or already used",
              index,
              mutation.node.id,
            )
          }
          val position =
            allocatePosition(
              mutation.location.parent,
              mutation.location,
              basePositions,
              "${command.operationId}:$index",
              mutation.node.id,
            )
          val positions = working.positions + (mutation.node.id to position)
          val withNode =
            working.document.copy(
              nodes = working.document.nodes + (mutation.node.id to mutation.node)
            )
          val document = withNode.rebuildLocation(positions, mutation.location.parent)
          MutationResult(
            WorkingDesign(document, working.tombstones, positions),
            StructureChangeV1(
              mutation.node.id,
              null,
              document.snapshot(mutation.node.id, positions),
            ),
          )
        }
        is MoveNodeMutationV1 -> {
          val before = working.document.snapshotOrFail(mutation.nodeId, working.positions, index)
          if (mutation.location.parent?.nodeId in before.nodes) {
            fail(
              RejectionCodeV1.CYCLE,
              "cannot move a node into its descendant",
              index,
              mutation.nodeId,
            )
          }
          val oldParent = working.positions.getValue(mutation.nodeId).parent
          val position =
            allocatePosition(
              mutation.location.parent,
              mutation.location,
              basePositions - mutation.nodeId,
              "${command.operationId}:$index",
              mutation.nodeId,
            )
          val positions = working.positions + (mutation.nodeId to position)
          var document = working.document.rebuildLocation(positions, oldParent)
          if (oldParent != mutation.location.parent) {
            document = document.rebuildLocation(positions, mutation.location.parent)
          }
          val conflicts =
            if (
              command.baseRevision < original.document.revision &&
                original.acceptedOperations.values.any {
                  it.committedRevision > command.baseRevision &&
                    it.changes.any { change ->
                      change is StructureChangeV1 && change.nodeId == mutation.nodeId
                    }
                }
            )
              listOf(
                CommandConflictV1(
                  ConflictCodeV1.STALE_MOVE,
                  mutation.nodeId,
                  overwrittenRevision = original.document.revision,
                )
              )
            else emptyList()
          MutationResult(
            WorkingDesign(document, working.tombstones, positions),
            StructureChangeV1(
              mutation.nodeId,
              before,
              document.snapshot(mutation.nodeId, positions),
            ),
            conflicts,
          )
        }
        is DeleteNodeMutationV1 -> {
          val before = working.document.snapshotOrFail(mutation.nodeId, working.positions, index)
          val positions = working.positions - before.nodes.keys
          val document =
            working.document
              .removeSnapshot(before)
              .rebuildLocation(positions, before.location.parent)
          MutationResult(
            WorkingDesign(
              document,
              working.tombstones + (mutation.nodeId to before),
              positions,
            ),
            StructureChangeV1(mutation.nodeId, before, null),
          )
        }
        is RestoreNodeMutationV1 -> {
          val tombstone =
            working.tombstones[mutation.nodeId]
              ?: fail(RejectionCodeV1.DELETED_NODE, "no retained tombstone", index, mutation.nodeId)
          val restored = tombstone.copy(location = mutation.location ?: tombstone.location)
          var positions = working.positions + restored.positions
          if (mutation.location != null) {
            positions =
              positions +
                (mutation.nodeId to
                  allocatePosition(
                    restored.location.parent,
                    restored.location,
                    basePositions,
                    "${command.operationId}:$index",
                    mutation.nodeId,
                  ))
          }
          val document =
            working.document
              .copy(nodes = working.document.nodes + restored.nodes)
              .rebuildLocation(positions, restored.location.parent)
          MutationResult(
            WorkingDesign(document, working.tombstones - mutation.nodeId, positions),
            StructureChangeV1(
              mutation.nodeId,
              null,
              document.snapshot(mutation.nodeId, positions),
            ),
          )
        }
        is SetPropertyMutationV1 -> {
          val node =
            working.document.nodes[mutation.nodeId]
              ?: fail(RejectionCodeV1.UNKNOWN_NODE, "unknown node", index, mutation.nodeId)
          if (mutation.property.isBlank()) {
            fail(RejectionCodeV1.INVALID_PROPERTY, "property name is blank", index, mutation.nodeId)
          }
          val beforePresent = mutation.property in node.properties
          val before = node.properties[mutation.property]
          val document =
            working.document.copy(
              nodes =
                working.document.nodes +
                  (node.id to
                    node.copy(properties = node.properties + (mutation.property to mutation.value)))
            )
          val conflicts =
            if (
              command.baseRevision < original.document.revision &&
                original.acceptedOperations.values.any {
                  it.committedRevision > command.baseRevision &&
                    it.changes.any { change ->
                      change is PropertyChangeV1 &&
                        change.nodeId == mutation.nodeId &&
                        change.property == mutation.property
                    }
                }
            )
              listOf(
                CommandConflictV1(
                  ConflictCodeV1.STALE_PROPERTY_WRITE,
                  mutation.nodeId,
                  mutation.property,
                  original.document.revision,
                )
              )
            else emptyList()
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            PropertyChangeV1(
              mutation.nodeId,
              mutation.property,
              beforePresent,
              before,
              mutation.value,
            ),
            conflicts,
          )
        }
      }
    } catch (failure: ReductionFailure) {
      MutationResult(error = failure.rejection(command.operationId, working.document.revision))
    }

  private fun compensate(
    design: PersistedDesignV1,
    target: AcceptedOperationRecordV1,
    undo: Boolean,
  ): CompensationResult {
    var working = WorkingDesign(design.document, design.tombstones, design.positions)
    val ordered = if (undo) target.changes.asReversed() else target.changes
    try {
      ordered.forEach { change ->
        when (change) {
          is PropertyChangeV1 -> {
            val node =
              working.document.nodes[change.nodeId]
                ?: fail(
                  RejectionCodeV1.UNSAFE_COMPENSATION,
                  "property node no longer exists",
                  nodeId = change.nodeId,
                )
            val expectedPresent = if (undo) true else change.beforePresent
            val expected = if (undo) change.after else change.before
            if (
              (change.property in node.properties) != expectedPresent ||
                node.properties[change.property] != expected
            ) {
              fail(
                RejectionCodeV1.UNSAFE_COMPENSATION,
                "property changed after the target operation",
                nodeId = change.nodeId,
                field = change.property,
              )
            }
            val targetPresent = if (undo) change.beforePresent else true
            val targetValue = if (undo) change.before else change.after
            val properties =
              if (targetPresent) node.properties + (change.property to requireNotNull(targetValue))
              else node.properties - change.property
            working =
              working.copy(
                document =
                  working.document.copy(
                    nodes = working.document.nodes + (node.id to node.copy(properties = properties))
                  )
              )
          }
          is StructureChangeV1 -> {
            val expected = if (undo) change.after else change.before
            val targetSnapshot = if (undo) change.before else change.after
            verifyStructuralPrecondition(
              working.document,
              working.positions,
              expected,
              change.nodeId,
            )
            var document = working.document
            var tombstones = working.tombstones
            var positions = working.positions
            if (expected != null) {
              document = document.removeSnapshot(expected)
              positions = positions - expected.nodes.keys
              document = document.rebuildLocation(positions, expected.location.parent)
              tombstones = tombstones + (change.nodeId to expected)
            }
            if (targetSnapshot != null) {
              positions = positions + targetSnapshot.positions
              document =
                document
                  .copy(nodes = document.nodes + targetSnapshot.nodes)
                  .rebuildLocation(positions, targetSnapshot.location.parent)
              tombstones = tombstones - change.nodeId
            }
            working = WorkingDesign(document, tombstones, positions)
          }
        }
      }
      validateTopology(working.document)?.let { throw ReductionFailure(it) }
      val catalog =
        catalogs.resolve(working.document.catalogPin)
          ?: fail(RejectionCodeV1.INVALID_DOCUMENT, "catalog pin is unavailable")
      catalogs.validate(working.document, catalog)?.let { throw ReductionFailure(it.toRejection()) }
      return CompensationResult(working)
    } catch (failure: ReductionFailure) {
      return CompensationResult(error = failure.value)
    }
  }

  private fun verifyStructuralPrecondition(
    document: DesignDocumentV1,
    positions: Map<String, StableNodePositionV1>,
    expected: NodeTreeSnapshotV1?,
    nodeId: String,
  ) {
    if (expected == null) {
      if (nodeId in document.nodes) {
        fail(
          RejectionCodeV1.UNSAFE_COMPENSATION,
          "node was recreated after the target operation",
          nodeId = nodeId,
        )
      }
      return
    }
    val current = document.snapshotOrNull(nodeId, positions)
    if (
      current == null || current.nodes != expected.nodes || current.positions != expected.positions
    ) {
      fail(
        RejectionCodeV1.UNSAFE_COMPENSATION,
        "node or subtree changed after the target operation",
        nodeId = nodeId,
      )
    }
  }

  private fun rejectedReduction(
    design: PersistedDesignV1,
    operationId: String,
    code: RejectionCodeV1,
    message: String,
  ): ReductionResult =
    ReductionResult(design, rejected(operationId, design.document.revision, code, message))

  private fun rejectedReduction(
    design: PersistedDesignV1,
    operationId: String,
    rejection: RejectedOutcomeV1,
  ): ReductionResult = ReductionResult(design, rejection.copy(operationId = operationId))

  private fun snapshot(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    catalog: CatalogCapabilityV1,
    presence: List<PresenceV1>,
  ): ServiceSnapshotV1 =
    ServiceSnapshotV1(
      designId = design.document.id,
      state = DesignStateV1(lastSequence = design.lastSequence, document = design.document),
      catalog = catalog,
      retainedFromSequence = design.retainedFromSequence(),
      presence = presence,
      access = design.access.takeIf { design.access.ownerActorId == actor.actorId },
    )

  private fun catchUp(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    afterSequence: Long?,
  ): UiBuilderServiceUpdate {
    val retained = design.retainedFromSequence()
    return if (
      afterSequence == null || afterSequence < retained || afterSequence > design.lastSequence
    ) {
      val catalog =
        catalogs.resolve(design.document.catalogPin)
          ?: throw UiBuilderSubscriptionRejectedException(
            UiBuilderServiceError(
              ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
              "catalog pin is unavailable",
            )
          )
      UiBuilderServiceUpdate.Snapshot(
        snapshot(
          design,
          actor,
          catalog,
          runtime.getValue(design.document.id).presence.values.toList(),
        )
      )
    } else {
      UiBuilderServiceUpdate.Delta(
        design.deltaAfter(afterSequence, limits.retainedCommittedOperations)
      )
    }
  }

  private fun enqueue(
    designId: String,
    update: UiBuilderServiceUpdate,
    design: PersistedDesignV1,
  ): List<SubscriberMailbox> {
    val accepted = mutableListOf<SubscriberMailbox>()
    runtime.getValue(designId).subscribers.entries.removeIf { (_, subscriber) ->
      if (!design.allows(subscriber.actor.actorId, DesignAccessActionV1.READ)) {
        subscriber.mailbox.close()
        true
      } else if (!subscriber.mailbox.enqueue(update)) {
        slowSubscribersClosed.incrementAndGet()
        true
      } else {
        accepted += subscriber.mailbox
        false
      }
    }
    return accepted
  }

  private fun drain(mailboxes: List<SubscriberMailbox>) {
    mailboxes.forEach { mailbox ->
      try {
        mailbox.drain()
      } catch (failure: Throwable) {
        // The mailbox closes itself. A failed observer must never turn an already durable commit
        // into an apparent failure that a caller retries.
        try {
          subscriberFailureHandler.failed(failure)
        } catch (_: Throwable) {
          // Failure reporting is observational and may not change commit acknowledgement.
        }
      }
    }
  }

  private fun commitPersisted(candidate: PersistedServiceV1) {
    storage.replace(encode(candidate, persistenceFormat))
    persisted = candidate
  }

  /**
   * Explicitly upgrades a validated v1 envelope to v2 with an envelope-level catalog-pin manifest.
   * No migration is attempted during startup. The storage must retain the exact v1 generation and
   * support explicit restore; a failed durable readback is rolled back before this method fails.
   */
  fun migratePersistenceToLatest(): UiBuilderPersistenceMigrationResult = lock.withLock {
    if (persistenceFormat == PersistenceFormat.V2) {
      val bytes = encode(persisted, PersistenceFormat.V2)
      return UiBuilderPersistenceMigrationResult(
        migrated = false,
        fromFormat = PersistenceFormat.V2.wire,
        toFormat = PersistenceFormat.V2.wire,
        persistedBytes = bytes.size,
      )
    }
    val migrationStorage =
      storage as? RecoverableUiBuilderMigrationStorage
        ?: throw UiBuilderPersistenceException(
          "persistence migration requires recoverable migration storage"
        )
    validatePersisted(persisted)
    val migratedBytes = encode(persisted, PersistenceFormat.V2)
    val preflight = decode(migratedBytes)
    check(preflight.format == PersistenceFormat.V2 && preflight.value == persisted) {
      "v2 persistence migration preflight did not round trip"
    }
    migrationStorage.replaceForMigration(migratedBytes)
    try {
      val durable = loadPersistence()
      if (durable.format != PersistenceFormat.V2 || durable.value != persisted) {
        throw UiBuilderPersistenceException("migrated persistence readback mismatch")
      }
    } catch (failure: Throwable) {
      val restored =
        try {
          migrationStorage.restoreMigrationBackup() &&
            loadPersistence().let { it.format == PersistenceFormat.V1 && it.value == persisted }
        } catch (rollbackFailure: Throwable) {
          failure.addSuppressed(rollbackFailure)
          false
        }
      if (!restored) {
        throw UiBuilderPersistenceException(
          "persistence migration failed and rollback could not be confirmed",
          failure,
        )
      }
      throw UiBuilderPersistenceException(
        "persistence migration failed; the v1 backup was restored",
        failure,
      )
    }
    persistenceFormat = PersistenceFormat.V2
    persistenceMigrations.incrementAndGet()
    UiBuilderPersistenceMigrationResult(
      migrated = true,
      fromFormat = PersistenceFormat.V1.wire,
      toFormat = PersistenceFormat.V2.wire,
      persistedBytes = migratedBytes.size,
    )
  }

  private fun serviceError(code: ServiceErrorCodeV1, message: String): LockedExecution =
    serviceError(UiBuilderServiceError(code, message))

  private fun serviceError(error: UiBuilderServiceError): LockedExecution =
    LockedExecution(UiBuilderServiceResponse.Error(error))

  private fun loadPersistence(): LoadedPersistence {
    val bytes = storage.load()
    return if (bytes == null) LoadedPersistence(PersistedServiceV1(), PersistenceFormat.V2)
    else decode(bytes)
  }

  private fun decode(bytes: ByteArray): LoadedPersistence {
    val encoded =
      try {
        bytes.decodeToString()
      } catch (failure: Exception) {
        throw UiBuilderPersistenceException("invalid UI-builder persistence UTF-8", failure)
      }
    val format =
      try {
        json.parseToJsonElement(encoded).jsonObject["format"]?.jsonPrimitive?.content
      } catch (failure: Exception) {
        throw UiBuilderPersistenceException("invalid UI-builder persistence JSON", failure)
      } ?: throw UiBuilderPersistenceException("UI-builder persistence format is missing")
    return when (format) {
      PersistenceFormat.V1.wire -> {
        val envelope = decodeEnvelope<PersistenceEnvelopeV1>(encoded)
        verifyChecksum(envelope.checksumSha256, json.encodeToJsonElement(envelope.payload))
        LoadedPersistence(envelope.payload, PersistenceFormat.V1)
      }
      PersistenceFormat.V2.wire -> {
        val envelope = decodeEnvelope<PersistenceEnvelopeV2>(encoded)
        verifyChecksum(envelope.checksumSha256, json.encodeToJsonElement(envelope.payload))
        val expectedPins = catalogPins(envelope.payload.service)
        if (envelope.payload.catalogPins != expectedPins) {
          throw UiBuilderPersistenceException(
            "UI-builder persistence catalog pin manifest mismatch"
          )
        }
        LoadedPersistence(envelope.payload.service, PersistenceFormat.V2)
      }
      else ->
        throw UiBuilderPersistenceException("unsupported UI-builder persistence format $format")
    }
  }

  private inline fun <reified T> decodeEnvelope(encoded: String): T =
    try {
      json.decodeFromString<T>(encoded)
    } catch (failure: Exception) {
      throw UiBuilderPersistenceException("invalid UI-builder persistence JSON", failure)
    }

  private fun verifyChecksum(expected: String, payload: JsonElement) {
    val actual = sha256(canonicalJson(payload).encodeToByteArray())
    if (actual != expected) {
      throw UiBuilderPersistenceException("UI-builder persistence checksum mismatch")
    }
  }

  private fun encode(value: PersistedServiceV1, format: PersistenceFormat): ByteArray {
    val encoded =
      when (format) {
        PersistenceFormat.V1 -> {
          val checksum = sha256(canonicalJson(json.encodeToJsonElement(value)).encodeToByteArray())
          json.encodeToString(PersistenceEnvelopeV1(format.wire, checksum, value))
        }
        PersistenceFormat.V2 -> {
          val payload = PersistencePayloadV2(value, catalogPins(value))
          val checksum =
            sha256(canonicalJson(json.encodeToJsonElement(payload)).encodeToByteArray())
          json.encodeToString(PersistenceEnvelopeV2(format.wire, checksum, payload))
        }
      }
    return encoded.encodeToByteArray()
  }

  private fun catalogPins(value: PersistedServiceV1): Map<String, CatalogReferenceV1> =
    value.designs.mapValues { (_, design) -> design.document.catalogPin }

  companion object {
    private val json = Json { encodeDefaults = true }
  }
}

private fun updatePeak(peak: AtomicLong, candidate: Long) {
  var observed = peak.get()
  while (candidate > observed && !peak.compareAndSet(observed, candidate)) observed = peak.get()
}

private fun conservativeDecodedBase64Bytes(encoded: String): Long {
  val encodedCharacters = encoded.length
  if (encodedCharacters == 0) return 0
  val completeGroups = encodedCharacters / 4
  val remainderBytes =
    when (encodedCharacters % 4) {
      0 -> 0
      2 -> 1
      3 -> 2
      else -> 3
    }
  val padding =
    if (encodedCharacters % 4 == 0) {
      when {
        encoded.endsWith("==") -> 2
        encoded.endsWith('=') -> 1
        else -> 0
      }
    } else {
      0
    }
  return completeGroups.toLong() * 3 + remainderBytes - padding
}

private data class WorkingDesign(
  val document: DesignDocumentV1,
  val tombstones: Map<String, NodeTreeSnapshotV1>,
  val positions: Map<String, StableNodePositionV1>,
)

private data class MutationResult(
  val working: WorkingDesign? = null,
  val change: ChangeRecordV1? = null,
  val conflicts: List<CommandConflictV1> = emptyList(),
  val error: RejectedOutcomeV1? = null,
)

private data class CompensationResult(
  val working: WorkingDesign? = null,
  val error: RejectedOutcomeV1? = null,
)

private data class ReductionResult(
  val design: PersistedDesignV1,
  val outcome: CommandOutcomeV1,
)

@Serializable
private data class PersistenceEnvelopeV1(
  val format: String,
  val checksumSha256: String,
  val payload: PersistedServiceV1,
)

@Serializable
private data class PersistenceEnvelopeV2(
  val format: String,
  val checksumSha256: String,
  val payload: PersistencePayloadV2,
)

@Serializable
private data class PersistencePayloadV2(
  val service: PersistedServiceV1,
  /** Redundant by design: startup fails if a design pin and the envelope manifest ever diverge. */
  val catalogPins: Map<String, CatalogReferenceV1>,
)

@Serializable
private data class PersistedServiceV1(val designs: Map<String, PersistedDesignV1> = emptyMap())

@Serializable
private data class PersistedDesignV1(
  val document: DesignDocumentV1,
  val lastSequence: Long,
  val access: DesignAccessControlV1,
  val history: List<CommittedOperationV1> = emptyList(),
  val revisionSnapshots: List<RevisionStateV1>,
  val operationOutcomes: Map<String, OperationOutcomeRecordV1> = emptyMap(),
  val acceptedOperations: Map<String, AcceptedOperationRecordV1> = emptyMap(),
  val tombstones: Map<String, NodeTreeSnapshotV1> = emptyMap(),
  val positions: Map<String, StableNodePositionV1>,
  val positionSnapshots: List<PositionStateV1>,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
  val audit: List<AuditRecordV1> = emptyList(),
)

@Serializable private data class RevisionStateV1(val document: DesignDocumentV1, val sequence: Long)

@Serializable
private data class PositionStateV1(
  val revision: Long,
  val positions: Map<String, StableNodePositionV1>,
)

@Serializable
private data class StableNodePositionV1(
  val parent: ParentSlotV1? = null,
  val key: StablePositionKeyV1,
)

@Serializable
private data class StablePositionKeyV1(
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
private data class OperationOutcomeRecordV1(
  val fingerprint: String,
  val outcome: CommandOutcomeV1,
)

@Serializable
private enum class AcceptedKindV1 {
  BATCH,
  UNDO,
  REDO,
}

@Serializable
private data class AcceptedOperationRecordV1(
  val operationId: String,
  val actorId: String,
  val kind: AcceptedKindV1,
  val committedRevision: Long,
  val activeRevision: Long,
  val changes: List<ChangeRecordV1>,
  val targetOperationId: String? = null,
  val compensatedBy: String? = null,
)

@Serializable private sealed interface ChangeRecordV1

@Serializable
@SerialName("property")
private data class PropertyChangeV1(
  val nodeId: String,
  val property: String,
  val beforePresent: Boolean,
  val before: UiValueV1?,
  val after: UiValueV1,
) : ChangeRecordV1

@Serializable
@SerialName("structure")
private data class StructureChangeV1(
  val nodeId: String,
  val before: NodeTreeSnapshotV1?,
  val after: NodeTreeSnapshotV1?,
) : ChangeRecordV1 {
  val affectedNodeIds: Set<String>
    get() = before?.nodes.orEmpty().keys + after?.nodes.orEmpty().keys
}

@Serializable
private data class NodeTreeSnapshotV1(
  val rootNodeId: String,
  val nodes: Map<String, DesignNodeV1>,
  val location: NodeLocationV1,
  val positions: Map<String, StableNodePositionV1>,
)

@Serializable
private enum class AuditKindV1 {
  COMMIT,
  EXPORT,
}

@Serializable
private data class AuditRecordV1(
  val kind: AuditKindV1,
  val actorId: String,
  val designId: String,
  val revision: Long,
  val sequence: Long,
  val operationId: String?,
  val exportFormat: ExportFormatV1?,
  val atEpochMillis: Long,
)

private class ReductionFailure(val value: RejectedOutcomeV1) :
  IllegalArgumentException(value.message) {
  fun rejection(operationId: String, revision: Long): RejectedOutcomeV1 =
    value.copy(operationId = operationId, currentRevision = revision)
}

private fun fail(
  code: RejectionCodeV1,
  message: String,
  operationIndex: Int? = null,
  nodeId: String? = null,
  field: String? = null,
): Nothing = throw ReductionFailure(rejected("", 0, code, message, operationIndex, nodeId, field))

private fun rejected(
  operationId: String,
  revision: Long,
  code: RejectionCodeV1,
  message: String,
  operationIndex: Int? = null,
  nodeId: String? = null,
  field: String? = null,
): RejectedOutcomeV1 =
  RejectedOutcomeV1(operationId, revision, code, message, operationIndex, nodeId, field)

private fun PersistedDesignV1.allows(actorId: String, action: DesignAccessActionV1): Boolean =
  actorId == access.ownerActorId ||
    access.actorGrants.any { it.actorId == actorId && action in it.allowedActions }

private fun PersistedDesignV1.listItem(actorId: String): DesignListItemV1 {
  val requester =
    if (actorId == access.ownerActorId)
      DesignActorAccessV1(actorId, DesignAccessRoleV1.OWNER, DesignAccessActionV1.entries)
    else {
      val grant = access.actorGrants.first { it.actorId == actorId }
      DesignActorAccessV1(actorId, grant.role, grant.allowedActions)
    }
  return DesignListItemV1(
    document.id,
    document.title,
    document.revision,
    access.accessRevision,
    document.catalogPin,
    createdAtEpochMillis,
    updatedAtEpochMillis,
    access.ownerActorId,
    requester,
  )
}

private fun PersistedDesignV1.retainedFromSequence(): Long =
  history.firstOrNull()?.outcome?.sequence?.minus(1) ?: lastSequence

private fun PersistedDesignV1.deltaAfter(afterSequence: Long, limit: Int): ServiceDeltaV1 {
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

private fun UiBuilderSubmission.toProtocol(actor: AuthenticatedUiBuilderActor): DesignSubmissionV1 =
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

private fun DesignSubmissionV1.designId(): String =
  when (this) {
    is DesignCommandV1 -> designId
    is UndoCommandV1 -> designId
    is RedoCommandV1 -> designId
  }

private fun DesignSubmissionV1.operationId(): String =
  when (this) {
    is DesignCommandV1 -> operationId
    is UndoCommandV1 -> operationId
    is RedoCommandV1 -> operationId
  }

private fun DesignSubmissionV1.clientId(): String =
  when (this) {
    is DesignCommandV1 -> clientId
    is UndoCommandV1 -> clientId
    is RedoCommandV1 -> clientId
  }

private fun DesignSubmissionV1.baseRevision(): Long =
  when (this) {
    is DesignCommandV1 -> baseRevision
    is UndoCommandV1 -> baseRevision
    is RedoCommandV1 -> baseRevision
  }

private fun UiBuilderPresence.toProtocol(actor: AuthenticatedUiBuilderActor): PresenceV1 =
  PresenceV1(
    actor.actorId,
    clientId,
    displayName,
    colorArgbHex,
    selectedNodeIds,
    pointerX?.let { PointerV1(it, requireNotNull(pointerY)) },
    observedRevision,
  )

private fun DesignDocumentV1.snapshotOrFail(
  nodeId: String,
  positions: Map<String, StableNodePositionV1>,
  index: Int,
): NodeTreeSnapshotV1 =
  snapshotOrNull(nodeId, positions)
    ?: fail(RejectionCodeV1.UNKNOWN_NODE, "unknown node", index, nodeId)

private fun DesignDocumentV1.snapshot(
  nodeId: String,
  positions: Map<String, StableNodePositionV1>,
): NodeTreeSnapshotV1 = requireNotNull(snapshotOrNull(nodeId, positions))

private fun DesignDocumentV1.snapshotOrNull(
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

private fun DesignDocumentV1.removeSnapshot(snapshot: NodeTreeSnapshotV1): DesignDocumentV1 {
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

private fun derivePositions(document: DesignDocumentV1): Map<String, StableNodePositionV1> {
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

private fun allocatePosition(
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

private fun DesignDocumentV1.rebuildLocation(
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

private fun validateTopology(document: DesignDocumentV1): RejectedOutcomeV1? {
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
    if (!visiting.add(id)) return false
    if (id in visited) return true
    document.nodes.getValue(id).slots.values.flatten().forEach { if (!visit(it)) return false }
    visiting.remove(id)
    visited += id
    return true
  }
  if (document.roots.any { !visit(it) } || visited != document.nodes.keys) {
    return rejected(
      "",
      document.revision,
      RejectionCodeV1.CYCLE,
      "design topology contains a cycle",
    )
  }
  return null
}

private fun UiBuilderCatalogIssue.toRejection(): RejectedOutcomeV1 =
  rejected("", 0, RejectionCodeV1.INVALID_DOCUMENT, message, nodeId = nodeId, field = field)

private fun UiBuilderCatalogIssue.toServiceError(): UiBuilderServiceError =
  UiBuilderServiceError(ServiceErrorCodeV1.BAD_REQUEST, "$code: $message")

private fun CatalogCapabilityV1.supports(format: ExportFormatV1): Boolean =
  when (format) {
    ExportFormatV1.COMPOSE -> exportCapabilities.composeCode
    ExportFormatV1.SVG -> exportCapabilities.svg
    ExportFormatV1.PNG -> exportCapabilities.png
  }

private fun notFound(designId: String): UiBuilderServiceError =
  UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "design $designId was not found")

private fun forbidden(action: String, designId: String): UiBuilderServiceError =
  UiBuilderServiceError(ServiceErrorCodeV1.FORBIDDEN, "actor may not $action design $designId")

private fun documentHash(document: DesignDocumentV1): String =
  sha256(
    canonicalJson(PersistentUiBuilderServiceJson.json.encodeToJsonElement(document))
      .encodeToByteArray()
  )

private fun artifactDigest(artifact: ExportArtifactV1): String =
  sha256(
    when (artifact.encoding) {
      ExportEncodingV1.UTF8 -> artifact.content.encodeToByteArray()
      ExportEncodingV1.BASE64 -> Base64.getDecoder().decode(artifact.content)
    }
  )

private object PersistentUiBuilderServiceJson {
  val json: Json = Json { encodeDefaults = true }
}

private fun sha256(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun canonicalJson(element: JsonElement): String =
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
