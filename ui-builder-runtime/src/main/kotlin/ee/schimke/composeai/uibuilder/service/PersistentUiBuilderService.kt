@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

public data class UiBuilderCatalogIssue(
  val code: String,
  val message: String,
  val nodeId: String? = null,
  val field: String? = null,
)

/** Exact catalog lookup and catalog-specific semantic validation. No permissive default exists. */
public interface UiBuilderCatalogExecutor {
  public fun listCatalogs(): List<CatalogCapabilityV1>

  public fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1?

  public fun validate(
    document: DesignDocumentV1,
    catalog: CatalogCapabilityV1,
  ): UiBuilderCatalogIssue?

  /**
   * The pin a document must carry for [resolve] to answer with [catalog], or null when this
   * executor cannot say. Defaulted so an executor that predates the question still compiles; a null
   * here means a client is back to guessing the digest, which is what the answer exists to end.
   */
  public fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? = null

  /**
   * Whether one property **write** carries a value of the kind the catalog means, asked of the node
   * as it will be committed.
   *
   * [validate] asks about shape — is the component declared, does it declare this property, is the
   * scalar the right JSON type, is an enumerated value in `allowedValues` — and every expensive
   * failure of yschimke/compose-preview-server#487 passed it: a colour committed as `string` and
   * refused at export, an `asset/image` whose key nothing resolved and whose render then failed the
   * whole design. This is the semantic half, and it is asked **only where a value is chosen** — a
   * `setProperty`, and each property of an `insertNode` — never document-wide, so a design
   * committed before a rule existed stays editable everywhere but the field that holds the old
   * value. The default says nothing, which is what a catalog with no value semantics means.
   *
   * A refusal is returned as `INVALID_PROPERTY` naming the node and the field, the way every other
   * property refusal in the reducer is.
   */
  public fun validateWrite(
    catalog: CatalogCapabilityV1,
    /**
     * The document the node is being written into. It is what makes an `assetKey` resolvable beyond
     * the catalog's own registry: a key pinned into `assets` by the asset lane
     * (`UiBuilderAssetPort`) is one the canvas draws, and a rule that read only the catalog would
     * refuse the picture a designer just uploaded.
     */
    document: DesignDocumentV1,
    node: DesignNodeV1,
    property: String,
  ): UiBuilderCatalogIssue? = null
}

public data class RevisionPinnedUiBuilderExport(
  val actor: AuthenticatedUiBuilderActor,
  val designId: String,
  val revision: Long,
  val documentHash: String,
  val document: DesignDocumentV1,
  val catalog: CatalogCapabilityV1,
  val format: ExportFormatV1,
)

/** Produces a real immutable artifact for an already authorized, revision-pinned document. */
public fun interface UiBuilderExportExecutor {
  public fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1
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

public fun interface UiBuilderSubscriberFailureHandler {
  public fun failed(failure: Throwable)
}

public data class UiBuilderServiceLimits(
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
  val presenceTtlMillis: Long = 30_000,
  /**
   * Ceiling on one uploaded asset ([UiBuilderAssetPort.putAsset]). A megabyte is a generous
   * photograph at the sizes a phone screen draws one; it is also what every render lane carries
   * inline to the daemon on each export, so this bounds a request as much as a file.
   */
  val maximumAssetBytes: Int = 1_024 * 1_024,
  /** How many keys one design's `assets` map may hold. */
  val maximumAssetsPerDesign: Int = 64,
) {
  init {
    require(maximumDesigns > 0)
    require(maximumAssetBytes > 0)
    require(maximumAssetsPerDesign > 0)
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
    require(presenceTtlMillis > 0)
  }
}

public class UiBuilderSubscriptionRejectedException(public val error: UiBuilderServiceError) :
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
public class PersistentUiBuilderService(
  private val storage: UiBuilderStateStorage,
  private val catalogs: UiBuilderCatalogExecutor,
  private val exporter: UiBuilderExportExecutor,
  private val subscriberFailureHandler: UiBuilderSubscriberFailureHandler =
    UiBuilderSubscriberFailureHandler {},
  private val clock: Clock = Clock.systemUTC(),
  private val limits: UiBuilderServiceLimits = UiBuilderServiceLimits(),
  /**
   * Where uploaded asset bytes go. Null on a host with nowhere to keep them, which makes [putAsset]
   * refuse and leaves every other lane exactly as it was.
   */
  private val assets: UiBuilderAssetStore? = null,
) :
  UiBuilderServicePort, UiBuilderServiceDiagnosticsSource, UiBuilderAdminPort, UiBuilderAssetPort {
  private data class MutationBucket(var tokens: Int, var refilledAtMillis: Long)

  private data class RuntimeDesign(
    val presence: MutableMap<String, RuntimePresence> = linkedMapOf(),
    val subscribers: MutableMap<Long, Subscriber> = linkedMapOf(),
  )

  private data class RuntimePresence(val value: PresenceV1, val lastSeenAtMillis: Long)

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
      unusableDesigns = unusableDesigns.size,
    )
  }

  init {
    persisted.designs.forEach { (designId, _) -> runtime[designId] = RuntimeDesign() }
  }

  /**
   * Why one stored design cannot be served, held rather than thrown.
   *
   * A design the current catalog or limits no longer accept used to be fatal: the check ran in
   * `init`, so the service could not be constructed, so **the server did not start** — over one
   * design, in a store that may hold a thousand. And it is not a rare shape. A catalog revision
   * moves and every design pinned to the old one stops resolving; an operator stops serving a
   * catalog, or tightens a node limit, and everything authored against it is unloadable. The blast
   * radius of a content change had no relationship to its cause.
   *
   * So the check moved to the point of use. Every design loads, one that cannot be served is
   * recorded here with the reason, and any request naming it is answered with that reason. A design
   * nobody asks for costs nothing, the rest of the store works, and `diagnostics()` counts them so
   * this is visible without opening one.
   *
   * What it must not become is a one-way door. A quarantined design is still the operator's
   * content, and the rule that invalidated it is as likely to be wrong as the document; so
   * `adminDesignDocument` reads the stored document out regardless of whether it can be served, and
   * retiring it with `adminDeleteDesign` is a choice rather than the only move left.
   *
   * The line this does **not** cross is integrity. A state file whose checksum does not match, that
   * is truncated, or that declares a format this build cannot read is still refused by the storage
   * layer before any of this runs, and `restoreBackup` is the recovery. Trusting a file that failed
   * those checks would be worse than not starting; carrying a design the catalog outgrew is not.
   */
  private data class UnusableDesign(val code: ServiceErrorCodeV1, val reason: String)

  /**
   * Computed once at load and then maintained, rather than fixed for the life of the process.
   *
   * A frozen set would make quarantine a one-way door: a design repaired through
   * [adminRepairDesign] would go on being refused until a restart, and a *deleted* one would leave
   * its entry behind — so its id would answer with a stale catalog error instead of "not found",
   * and re-creating a design under that id could never be served. Both are the same mistake this
   * whole mechanism exists to undo, one scope smaller.
   *
   * Written under [lock] with every other state change; concurrent because [execute] reads it
   * before taking the lock, and export runs outside it entirely.
   */
  private val unusableDesigns: MutableMap<String, UnusableDesign> =
    ConcurrentHashMap(
      persisted.designs
        .mapNotNull { (designId, design) ->
          unusableReason(designId, design)?.let { designId to it }
        }
        .toMap()
    )

  private fun unusableReason(designId: String, design: PersistedDesignV1): UnusableDesign? {
    fun internal(reason: String) = UnusableDesign(ServiceErrorCodeV1.INTERNAL, reason)
    if (designId != design.document.id) {
      return internal("stored design key/id mismatch for $designId")
    }
    if (design.document.nodes.size > limits.maximumNodesPerDesign) {
      return internal("stored node count exceeds configured limit for $designId")
    }
    documentQuotaIssue(design.document)?.let {
      return internal("stored design $designId exceeds configured limit: $it")
    }
    validateTopology(design.document)?.let {
      return internal("invalid stored design $designId: ${it.message}")
    }
    // The most likely reason by far, and the one worth its own code: the pin names a catalog
    // revision this deployment no longer serves. Nothing is wrong with the document.
    val catalog =
      catalogs.resolve(design.document.catalogPin)
        ?: return UnusableDesign(
          ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
          "catalog unavailable for stored design $designId",
        )
    catalogs.validate(design.document, catalog)?.let {
      return internal("invalid stored design $designId: ${it.message}")
    }
    return null
  }

  /**
   * The design a request is about, or null for the requests that are about none of them.
   *
   * Listing is deliberately in the second group: a design that cannot be served still appears, so
   * an operator can see that it is there. Hiding it would make it unfindable as well as unusable.
   */
  private fun UiBuilderServiceRequest.designId(): String? =
    when (this) {
      is UiBuilderServiceRequest.OpenDesign -> designId
      is UiBuilderServiceRequest.GetDesignAccess -> designId
      is UiBuilderServiceRequest.UpdateDesignAccess -> designId
      is UiBuilderServiceRequest.PreviewCatalogUpgrade -> designId
      is UiBuilderServiceRequest.ApplyOperation -> submission.designId
      is UiBuilderServiceRequest.GetSnapshot -> designId
      is UiBuilderServiceRequest.GetDelta -> designId
      is UiBuilderServiceRequest.UpdatePresence -> designId
      is UiBuilderServiceRequest.ExportDesign -> designId
      is UiBuilderServiceRequest.RenameDesign -> designId
      is UiBuilderServiceRequest.DeleteDesign -> designId
      UiBuilderServiceRequest.ListCatalogs,
      is UiBuilderServiceRequest.CreateDesign,
      is UiBuilderServiceRequest.ListDesigns -> null
    }

  override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
    call.request.designId()?.let { designId ->
      unusableDesigns[designId]?.let {
        return UiBuilderServiceResponse.Error(UiBuilderServiceError(it.code, it.reason))
      }
    }
    if (call.request is UiBuilderServiceRequest.ExportDesign) return export(call)
    val execution = lock.withLock { executeLocked(call) }
    drain(execution.mailboxes)
    return execution.response
  }

  override suspend fun putAsset(write: UiBuilderAssetWrite): UiBuilderServiceResponse {
    unusableDesigns[write.designId]?.let {
      return UiBuilderServiceResponse.Error(UiBuilderServiceError(it.code, it.reason))
    }
    val execution = lock.withLock { putAssetLocked(write) }
    drain(execution.mailboxes)
    return execution.response
  }

  /**
   * An asset write is a commit without an operation.
   *
   * It moves the revision and the sequence exactly as an accepted batch does — the document's bytes
   * changed, so its hash, its retained snapshot and every `baseRevision` a client quotes must move
   * with it — but no `CommittedOperationV1` can describe it, because the released mutation set has
   * no asset write. So the delta log is cut here: `history` is emptied, which makes
   * `retainedFromSequence` this sequence, and a subscriber behind it is caught up with a whole
   * snapshot instead of a delta it could not replay. Live subscribers get that snapshot now. It is
   * not undoable, for the same reason: undo compensates an operation record, and there is none.
   * Re-pointing the key, or deleting the node that names it, is how it is taken back.
   */
  private fun putAssetLocked(write: UiBuilderAssetWrite): LockedExecution {
    val store =
      assets
        ?: return serviceError(
          ServiceErrorCodeV1.BAD_REQUEST,
          "this host keeps no asset store, so a design cannot hold an uploaded image",
        )
    val design = persisted.designs[write.designId] ?: return serviceError(notFound(write.designId))
    if (!design.allows(write.actor, DesignAccessActionV1.WRITE)) {
      return serviceError(forbidden("write", write.designId))
    }
    if (!UiBuilderAssetKeys.isValid(write.assetKey)) {
      return serviceError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "asset key '${write.assetKey}' is malformed: ${UiBuilderAssetKeys.RULE}",
      )
    }
    if (write.bytes.isEmpty()) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "asset bytes are empty")
    }
    if (write.bytes.size > limits.maximumAssetBytes) {
      rejectedAssetBytes.incrementAndGet()
      return serviceError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "asset is ${write.bytes.size} bytes; this host stores at most " +
          "${limits.maximumAssetBytes} bytes per asset",
      )
    }
    val image =
      UiBuilderImageBytes.sniff(write.bytes)
        ?: return serviceError(
          ServiceErrorCodeV1.BAD_REQUEST,
          "asset bytes are not a ${UiBuilderImageBytes.KNOWN} image",
        )
    val digest = UiBuilderAssetDigests.of(write.bytes)
    val binding =
      AssetBindingV1(
        mediaType = image.mediaType,
        contentDigest = digest,
        source = UploadedAssetSourceV1(storageKey = digest),
        widthPx = image.widthPx,
        heightPx = image.heightPx,
      )
    val operationId = "asset:${write.assetKey}:$digest"
    val current = design.document
    if (current.assets[write.assetKey] == binding) {
      return LockedExecution(
        UiBuilderServiceResponse.OperationOutcome(
          AcceptedOutcomeV1(
            operationId,
            current.revision,
            design.lastSequence,
            documentHash(current),
            idempotentReplay = true,
            documentUpdatedAtEpochMillis = current.updatedAtEpochMillis,
          )
        )
      )
    }
    if (write.assetKey !in current.assets && current.assets.size >= limits.maximumAssetsPerDesign) {
      return serviceError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "a design may hold at most ${limits.maximumAssetsPerDesign} assets",
      )
    }
    val catalog =
      catalogs.resolve(current.catalogPin)
        ?: return serviceError(ServiceErrorCodeV1.CATALOG_UNAVAILABLE, "catalog pin is unavailable")
    val revision = current.revision + 1
    val sequence = design.lastSequence + 1
    val now = clock.millis()
    val document =
      current.copy(
        assets = current.assets + (write.assetKey to binding),
        revision = revision,
        updatedAtEpochMillis = now,
      )
    documentQuotaIssue(document, countRejection = true)?.let {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, it)
    }
    try {
      store.write(digest, write.bytes)
    } catch (failure: IOException) {
      return serviceError(
        ServiceErrorCodeV1.INTERNAL,
        "asset bytes could not be stored: ${failure.message}",
      )
    }
    val outcome =
      AcceptedOutcomeV1(
        operationId,
        revision,
        sequence,
        documentHash(document),
        idempotentReplay = false,
        documentUpdatedAtEpochMillis = now,
      )
    val updated =
      design.copy(
        document = document,
        lastSequence = sequence,
        history = emptyList(),
        revisionSnapshots =
          (design.revisionSnapshots + RevisionStateV1(document, sequence)).takeLast(
            limits.retainedRevisionSnapshots
          ),
        positionSnapshots =
          (design.positionSnapshots + PositionStateV1(revision, design.positions)).takeLast(
            limits.retainedRevisionSnapshots
          ),
        updatedAtEpochMillis = now,
        audit =
          (design.audit +
              AuditRecordV1(
                AuditKindV1.COMMIT,
                write.actor.actorId,
                current.id,
                revision,
                sequence,
                operationId,
                null,
                now,
              ))
            .takeLast(limits.retainedAuditRecords),
      )
    commitPersisted(persisted.copy(designs = persisted.designs + (write.designId to updated)))
    // One snapshot for every subscriber, so it carries no access list: `snapshot` includes one
    // for the owner, and the writer being the owner must not show it to the viewers.
    val broadcast =
      snapshot(updated, write.actor, catalog, activePresence(write.designId)).copy(access = null)
    val mailboxes = enqueue(write.designId, UiBuilderServiceUpdate.Snapshot(broadcast), updated)
    return LockedExecution(UiBuilderServiceResponse.OperationOutcome(outcome), mailboxes)
  }

  override suspend fun readAsset(read: UiBuilderAssetRead): UiBuilderAssetReadResult {
    val binding = lock.withLock {
      val design =
        persisted.designs[read.designId]
          ?: return UiBuilderAssetReadResult.Failed(notFound(read.designId))
      if (!design.allows(read.actor, DesignAccessActionV1.READ)) {
        return UiBuilderAssetReadResult.Failed(forbidden("read", read.designId))
      }
      design.document.assets[read.assetKey]
        ?: return UiBuilderAssetReadResult.Failed(
          UiBuilderServiceError(
            ServiceErrorCodeV1.NOT_FOUND,
            "design ${read.designId} has no asset '${read.assetKey}'",
          )
        )
    }
    val bytes =
      when (val source = binding.source) {
        is EmbeddedAssetSourceV1 ->
          try {
            Base64.getDecoder().decode(source.base64)
          } catch (_: IllegalArgumentException) {
            null
          }
        is UploadedAssetSourceV1 -> assets?.read(source.storageKey)
        is CatalogAssetSourceV1 -> null
      }
        ?: return UiBuilderAssetReadResult.Failed(
          UiBuilderServiceError(
            ServiceErrorCodeV1.NOT_FOUND,
            "the bytes behind asset '${read.assetKey}' are not on this host",
          )
        )
    return UiBuilderAssetReadResult.Found(binding, bytes)
  }

  override fun subscribe(
    call: UiBuilderSubscriptionCall,
    listener: (UiBuilderServiceUpdate) -> Unit,
  ): Closeable {
    val subscriberId: Long
    val mailbox: SubscriberMailbox
    lock.withLock {
      unusableDesigns[call.designId]?.let {
        throw UiBuilderSubscriptionRejectedException(UiBuilderServiceError(it.code, it.reason))
      }
      val design =
        persisted.designs[call.designId]
          ?: throw UiBuilderSubscriptionRejectedException(notFound(call.designId))
      if (!design.allows(call.actor, DesignAccessActionV1.READ)) {
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
      UiBuilderServiceRequest.ListCatalogs -> {
        val listed = catalogs.listCatalogs()
        LockedExecution(
          UiBuilderServiceResponse.Catalogs(
            listed,
            pins =
              listed
                .mapNotNull { catalog ->
                  catalogs.reference(catalog)?.let { catalog.benchmark.catalogSystemId to it }
                }
                .toMap(),
          )
        )
      }
      is UiBuilderServiceRequest.CreateDesign -> create(call.actor, request.document)
      is UiBuilderServiceRequest.ListDesigns -> list(call.actor, request)
      is UiBuilderServiceRequest.OpenDesign -> open(call.actor, request.designId, revision = null)
      is UiBuilderServiceRequest.GetDesignAccess -> access(call.actor, request.designId)
      is UiBuilderServiceRequest.UpdateDesignAccess -> updateAccess(call.actor, request)
      is UiBuilderServiceRequest.PreviewCatalogUpgrade ->
        serviceError(
          ServiceErrorCodeV1.BAD_REQUEST,
          "catalog upgrade preview is not configured by this runtime",
        )
      is UiBuilderServiceRequest.ApplyOperation -> apply(call.actor, request.submission)
      is UiBuilderServiceRequest.GetSnapshot -> open(call.actor, request.designId, request.revision)
      is UiBuilderServiceRequest.GetDelta -> delta(call.actor, request)
      is UiBuilderServiceRequest.UpdatePresence -> presence(call.actor, request)
      is UiBuilderServiceRequest.ExportDesign ->
        error("export is executed outside the service lock")
      is UiBuilderServiceRequest.RenameDesign -> rename(call.actor, request)
      is UiBuilderServiceRequest.DeleteDesign -> delete(call.actor, request.designId)
    }

  /**
   * See [UiBuilderServiceRequest.RenameDesign] for why this is not a mutation. The current
   * revision's retained snapshot is renamed with the live document, so reading the design *at* its
   * current revision agrees with reading it plainly; earlier revisions keep the title they had,
   * which is what a historical read is for.
   *
   * Nothing is pushed to subscribers: the protocol client discards a snapshot that does not advance
   * its sequence cursor, and a rename advances nothing. An open editor sees the new title when it
   * next opens the design; a listing sees it at once.
   */
  private fun rename(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest.RenameDesign,
  ): LockedExecution {
    val design =
      persisted.designs[request.designId] ?: return serviceError(notFound(request.designId))
    if (!design.allows(actor, DesignAccessActionV1.WRITE)) {
      return serviceError(forbidden("write", request.designId))
    }
    val title = request.title.trim()
    if (title.isEmpty()) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "design title is blank")
    }
    if (title.length > MAXIMUM_TITLE_LENGTH) {
      return serviceError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "design title exceeds $MAXIMUM_TITLE_LENGTH characters",
      )
    }
    val now = clock.millis()
    val document = design.document.copy(title = title, updatedAtEpochMillis = now)
    val updated =
      design.copy(
        document = document,
        revisionSnapshots =
          design.revisionSnapshots.map { retained ->
            if (retained.document.revision == document.revision)
              retained.copy(document = retained.document.copy(title = title))
            else retained
          },
        updatedAtEpochMillis = now,
      )
    commitPersisted(persisted.copy(designs = persisted.designs + (request.designId to updated)))
    return LockedExecution(UiBuilderServiceResponse.DesignRenamed(updated.listItem(actor)))
  }

  /**
   * See [UiBuilderServiceRequest.DeleteDesign] for who may. The removal itself is the operator's
   * [adminDeleteDesign], reached through an ownership check rather than an admin token; the two
   * share [removeLocked] so they cannot disagree about what "gone" means.
   */
  private fun delete(actor: AuthenticatedUiBuilderActor, designId: String): LockedExecution {
    val design = persisted.designs[designId] ?: return serviceError(notFound(designId))
    if (!design.ownedBy(actor)) {
      return serviceError(forbidden("delete", designId))
    }
    // Closed under the lock, as [updateAccess] closes the streams of an actor it revoked: a
    // subscriber learns the design is gone only after the removal is durable, which
    // [removeLocked] guarantees by committing first.
    removeLocked(designId).forEach(SubscriberMailbox::close)
    return LockedExecution(UiBuilderServiceResponse.DesignDeleted(designId))
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
    validateEnvironment(document.environment)?.let {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, it.message)
    }
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
        // Owned by the human when the caller is acting for one. An agent's grant is a
        // short-lived delegation of *their* authority, so a design it creates has to outlive the
        // grant in the hands of the person who approved it — the alternative is what this fixes: a
        // design owned by an id that stops existing in an hour, which its own approver is then
        // refused when they open the link the agent sent them.
        access = DesignAccessControlV1(0, actor.onBehalfOfActorId ?: actor.actorId),
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
        .filter { it.allows(actor, DesignAccessActionV1.READ) }
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
    return LockedExecution(UiBuilderServiceResponse.Designs(page.map { it.listItem(actor) }, next))
  }

  private fun open(
    actor: AuthenticatedUiBuilderActor,
    designId: String,
    revision: Long?,
  ): LockedExecution {
    val design = persisted.designs[designId] ?: return serviceError(notFound(designId))
    if (!design.allows(actor, DesignAccessActionV1.READ)) {
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
          activePresence(designId),
        )
      )
    )
  }

  private fun access(actor: AuthenticatedUiBuilderActor, designId: String): LockedExecution {
    val design = persisted.designs[designId] ?: return serviceError(notFound(designId))
    if (!design.ownedBy(actor)) {
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
    if (!design.ownedBy(actor)) {
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
      val revoke = !updated.allows(subscriber.actor, DesignAccessActionV1.READ)
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
    if (!design.allows(actor, DesignAccessActionV1.WRITE)) {
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
    if (!design.allows(actor, DesignAccessActionV1.READ)) {
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
    if (!design.allows(actor, DesignAccessActionV1.READ)) {
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
    val expiredActors = expirePresence(request.designId)
    runtime.getValue(request.designId).presence[actor.actorId] =
      RuntimePresence(value, clock.millis())
    val mailboxes =
      (expiredActors.flatMap { expiredActorId ->
          enqueue(
            request.designId,
            UiBuilderServiceUpdate.Presence(PresenceLeaveV1(expiredActorId)),
            design,
          )
        } +
          enqueue(
            request.designId,
            UiBuilderServiceUpdate.Presence(PresenceUpsertV1(value)),
            design,
          ))
        .distinct()
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
      if (!design.allows(call.actor, DesignAccessActionV1.EXPORT)) {
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
          UiBuilderServiceError(
            ServiceErrorCodeV1.INTERNAL,
            "export failed: ${failure.clientMessage()}",
          )
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
    val environmentChanges =
      command.operations.flatMapIndexed { operationIndex, mutation ->
        if (mutation is UpdateEnvironmentMutationV1)
          mutation.changes.map { operationIndex to it.field }
        else emptyList()
      }
    val duplicateEnvironmentField =
      environmentChanges.groupBy { it.second }.entries.firstOrNull { it.value.size > 1 }
    if (duplicateEnvironmentField != null) {
      val duplicate = duplicateEnvironmentField.value[1]
      return rejectedReduction(
        design,
        command.operationId,
        rejected(
          command.operationId,
          design.document.revision,
          RejectionCodeV1.INVALID_COMMAND,
          "environment field ${duplicate.second} is changed more than once",
          operationIndex = duplicate.first,
          environmentField = duplicate.second,
        ),
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
    // Resolved before the first mutation rather than after the last, because a write is checked
    // against the catalog as it is applied (`UiBuilderCatalogExecutor.validateWrite`), not only as
    // a whole document afterwards.
    val catalog =
      catalogs.resolve(design.document.catalogPin)
        ?: return rejectedReduction(
          design,
          command.operationId,
          RejectionCodeV1.INVALID_DOCUMENT,
          "catalog pin is unavailable",
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
      val applied =
        applyMutation(
          working,
          mutation,
          command,
          design,
          basePositions = batchPositions,
          index,
          catalog,
        )
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
        documentUpdatedAtEpochMillis = now,
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
    catalog: CatalogCapabilityV1,
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
          // An insert is a write of every property at once, and it is how an `asset/image` with a
          // key nothing resolves arrived (#484): checked here, per property, the way a
          // `setProperty` of the same value would be.
          mutation.node.properties.keys.forEach { property ->
            catalogs.validateWrite(catalog, working.document, mutation.node, property)?.let {
              fail(RejectionCodeV1.INVALID_PROPERTY, it.message, index, mutation.node.id, it.field)
            }
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
        is SetPropertyMutationV1 ->
          writeProperty(
            working,
            mutation.nodeId,
            mutation.property,
            mutation.value,
            command,
            original,
            index,
            catalog,
          )
        // The explicit spelling of the null write (compose-preview-contracts 2.10.0, #480): the
        // same path, so the change record is the same `afterPresent = false` either way.
        is RemoveNodePropertyMutationV1 ->
          writeProperty(
            working,
            mutation.nodeId,
            mutation.property,
            NullValueV1,
            command,
            original,
            index,
            catalog,
          )
        is UpdateEnvironmentMutationV1 -> {
          if (mutation.changes.isEmpty()) {
            fail(
              RejectionCodeV1.INVALID_COMMAND,
              "environment change list is empty",
              operationIndex = index,
            )
          }
          val fields = mutation.changes.map(EnvironmentChangeV1::field)
          val duplicate = fields.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
          if (duplicate != null) {
            fail(
              RejectionCodeV1.INVALID_COMMAND,
              "environment field ${duplicate.key} is changed more than once",
              operationIndex = index,
              environmentField = duplicate.key,
            )
          }
          val before = working.document.environment
          val after = mutation.changes.fold(before, DesignEnvironmentV1::applyChange)
          validateEnvironment(after)?.let { issue ->
            fail(
              RejectionCodeV1.INVALID_DOCUMENT,
              issue.message,
              operationIndex = index,
              environmentField = issue.field,
            )
          }
          val conflicts = fields.mapNotNull { environmentField ->
            val overwrittenRevision =
              original.acceptedOperations.values
                .asSequence()
                .filter { it.committedRevision > command.baseRevision }
                .filter { accepted ->
                  accepted.changes.any { change ->
                    change is EnvironmentChangeRecordV1 && environmentField in change.fields
                  }
                }
                .maxOfOrNull(AcceptedOperationRecordV1::committedRevision)
            overwrittenRevision?.let {
              CommandConflictV1(
                code = ConflictCodeV1.STALE_ENVIRONMENT_WRITE,
                nodeId = null,
                overwrittenRevision = it,
                environmentField = environmentField,
              )
            }
          }
          val document = working.document.copy(environment = after)
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            EnvironmentChangeRecordV1(fields.distinct(), before, after),
            conflicts,
          )
        }
        is CatalogUpgradeMutationV1 ->
          fail(
            RejectionCodeV1.INVALID_COMMAND,
            "catalog upgrades require the preview/apply service path",
            operationIndex = index,
          )
        is SetModifiersMutationV1 -> {
          val node =
            working.document.nodes[mutation.nodeId]
              ?: fail(RejectionCodeV1.UNKNOWN_NODE, "unknown node", index, mutation.nodeId)
          val before = node.modifiers
          val document =
            working.document.copy(
              nodes =
                working.document.nodes + (node.id to node.copy(modifiers = mutation.modifiers))
            )
          // The same staleness question the property lane asks, one field over: another actor
          // rewriting this node's chain since the base revision is a conflict rather than a
          // refusal, because the chain is a value and the last writer wins.
          val conflicts =
            if (
              command.baseRevision < original.document.revision &&
                original.acceptedOperations.values.any {
                  it.committedRevision > command.baseRevision &&
                    it.changes.any { change ->
                      change is ModifierChangeV1 && change.nodeId == mutation.nodeId
                    }
                }
            )
              listOf(
                CommandConflictV1(
                  // `STALE_PROPERTY_WRITE` because the wire has no modifier-specific code, and
                  // `modifiers` is the field it names — the same reading the browser reducer
                  // reports its own stale chain writes under.
                  ConflictCodeV1.STALE_PROPERTY_WRITE,
                  mutation.nodeId,
                  MODIFIERS_FIELD,
                  original.document.revision,
                )
              )
            else emptyList()
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            ModifierChangeV1(mutation.nodeId, before, mutation.modifiers),
            conflicts,
          )
        }
        is SetStateVariableMutationV1 -> {
          if (mutation.name.isBlank()) {
            fail(
              RejectionCodeV1.INVALID_COMMAND,
              "state variable name is blank",
              operationIndex = index,
            )
          }
          val before = working.document.stateVariables[mutation.name]
          val declarations =
            working.document.stateVariables + (mutation.name to mutation.declaration)
          // A redefinition is a write against everything already reading the variable. Narrowing
          // one — dropping its nullability out from under a `selectOrClear`, or changing what it
          // holds out from under a `toggle` — leaves a design whose renderer coerces and whose
          // exporter emits a `TODO` that throws on the first press. Refused whole rather than
          // applied and then discovered.
          working.document.stateUsageIssue(declarations)?.let { issue ->
            fail(
              RejectionCodeV1.INVALID_DOCUMENT,
              issue.message,
              operationIndex = index,
              nodeId = issue.nodeId,
              field = issue.field,
            )
          }
          val document = working.document.copy(stateVariables = declarations)
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            StateVariableChangeV1(mutation.name, before, mutation.declaration),
            staleStateWrites(original, command, mutation.name),
          )
        }
        is RemoveStateVariableMutationV1 -> {
          val before =
            working.document.stateVariables[mutation.name]
              ?: fail(
                RejectionCodeV1.INVALID_COMMAND,
                "this design declares no state variable ${mutation.name}",
                operationIndex = index,
                field = mutation.name,
              )
          val declarations = working.document.stateVariables - mutation.name
          // The obligation the contract states on this type: a property, a predicate or an action
          // still naming the variable makes the removal a rejection rather than a write. A design
          // that keeps the reference renders blank instead of failing, which is the worse of the
          // two ways to be wrong.
          working.document.stateUsageIssue(declarations)?.let { issue ->
            fail(
              RejectionCodeV1.INVALID_DOCUMENT,
              issue.message,
              operationIndex = index,
              nodeId = issue.nodeId,
              field = issue.field,
            )
          }
          val document = working.document.copy(stateVariables = declarations)
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            StateVariableChangeV1(mutation.name, before, null),
            staleStateWrites(original, command, mutation.name),
          )
        }
        is SetEventBindingMutationV1 -> {
          val node =
            working.document.nodes[mutation.nodeId]
              ?: fail(RejectionCodeV1.UNKNOWN_NODE, "unknown node", index, mutation.nodeId)
          if (mutation.event.isBlank()) {
            fail(
              RejectionCodeV1.INVALID_COMMAND,
              "event name is blank",
              operationIndex = index,
              nodeId = mutation.nodeId,
            )
          }
          val before = node.eventBindings[mutation.event]
          // `actions` has no default on the wire, so an unbind arrives as a present empty list
          // rather than an absent field. Taken as the instruction it is: the event loses its
          // binding, and the document carries no empty list to mean the same thing twice.
          val after = mutation.actions.takeIf { it.isNotEmpty() }
          val bindings =
            if (after == null) node.eventBindings - mutation.event
            else node.eventBindings + (mutation.event to after)
          val document =
            working.document.copy(
              nodes = working.document.nodes + (node.id to node.copy(eventBindings = bindings))
            )
          document.stateUsageIssue(document.stateVariables)?.let { issue ->
            fail(
              RejectionCodeV1.INVALID_DOCUMENT,
              issue.message,
              operationIndex = index,
              nodeId = issue.nodeId,
              field = issue.field,
            )
          }
          // The same staleness question the property and modifier lanes ask, one field over: a
          // binding is a value, and the last writer wins.
          val conflicts =
            if (
              command.baseRevision < original.document.revision &&
                original.acceptedOperations.values.any {
                  it.committedRevision > command.baseRevision &&
                    it.changes.any { change ->
                      change is EventBindingChangeV1 &&
                        change.nodeId == mutation.nodeId &&
                        change.event == mutation.event
                    }
                }
            )
              listOf(
                CommandConflictV1(
                  ConflictCodeV1.STALE_PROPERTY_WRITE,
                  mutation.nodeId,
                  eventBindingField(mutation.event),
                  original.document.revision,
                )
              )
            else emptyList()
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            EventBindingChangeV1(mutation.nodeId, mutation.event, before, after),
            conflicts,
          )
        }
      }
    } catch (failure: ReductionFailure) {
      MutationResult(error = failure.rejection(command.operationId, working.document.revision))
    }

  /**
   * One property write, for a `setProperty` and for the explicit `removeNodeProperty` alike.
   *
   * A [NullValueV1] value unsets the property (#480); `RemoveNodePropertyMutationV1` says the same
   * thing in its own words and arrives here with that value, so there is one removal path and the
   * two spellings cannot drift.
   */
  private fun writeProperty(
    working: WorkingDesign,
    nodeId: String,
    property: String,
    value: UiValueV1,
    command: DesignCommandV1,
    original: PersistedDesignV1,
    index: Int,
    catalog: CatalogCapabilityV1,
  ): MutationResult {
    val node =
      working.document.nodes[nodeId]
        ?: fail(RejectionCodeV1.UNKNOWN_NODE, "unknown node", index, nodeId)
    if (property.isBlank()) {
      fail(RejectionCodeV1.INVALID_PROPERTY, "property name is blank", index, nodeId)
    }
    val beforePresent = property in node.properties
    val before = node.properties[property]
    // A null value unsets the property rather than storing a null. A stored null is what
    // the catalog validator refuses ("does not match its catalog JSON type"), and what the
    // Compose export refuses again; an *absent* property is the state every node starts in
    // and the one whose default the renderer applies. Whether the property may be absent is
    // not decided here: the catalog validation the whole batch passes through afterwards
    // refuses an unset required property with its usual located message. An unset is not a
    // write of a value either, so the catalog's value rules are asked only of a set.
    val afterPresent = value !is NullValueV1
    val written =
      if (afterPresent) {
        node.copy(properties = node.properties + (property to value)).also {
          catalogs.validateWrite(catalog, working.document, it, property)?.let { issue ->
            fail(
              RejectionCodeV1.INVALID_PROPERTY,
              issue.message,
              index,
              nodeId,
              issue.field,
            )
          }
        }
      } else node.copy(properties = node.properties - property)
    val document = working.document.copy(nodes = working.document.nodes + (node.id to written))
    val conflicts =
      if (
        command.baseRevision < original.document.revision &&
          original.acceptedOperations.values.any {
            it.committedRevision > command.baseRevision &&
              it.changes.any { change ->
                change is PropertyChangeV1 && change.nodeId == nodeId && change.property == property
              }
          }
      )
        listOf(
          CommandConflictV1(
            ConflictCodeV1.STALE_PROPERTY_WRITE,
            nodeId,
            property,
            original.document.revision,
          )
        )
      else emptyList()
    return MutationResult(
      WorkingDesign(document, working.tombstones, working.positions),
      PropertyChangeV1(
        nodeId,
        property,
        beforePresent,
        before,
        value,
        afterPresent,
      ),
      conflicts,
    )
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
            val expectedPresent = if (undo) change.afterPresent else change.beforePresent
            val expected = if (undo) change.after.takeIf { change.afterPresent } else change.before
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
            val targetPresent = if (undo) change.beforePresent else change.afterPresent
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
          is ModifierChangeV1 -> {
            val node =
              working.document.nodes[change.nodeId]
                ?: fail(
                  RejectionCodeV1.UNSAFE_COMPENSATION,
                  "modifier node no longer exists",
                  nodeId = change.nodeId,
                )
            val expected = if (undo) change.after else change.before
            if (node.modifiers != expected) {
              fail(
                RejectionCodeV1.UNSAFE_COMPENSATION,
                "modifiers changed after the target operation",
                nodeId = change.nodeId,
                field = MODIFIERS_FIELD,
              )
            }
            working =
              working.copy(
                document =
                  working.document.copy(
                    nodes =
                      working.document.nodes +
                        (node.id to
                          node.copy(modifiers = if (undo) change.before else change.after))
                  )
              )
          }
          is StateVariableChangeV1 -> {
            val expected = if (undo) change.after else change.before
            if (working.document.stateVariables[change.name] != expected) {
              fail(
                RejectionCodeV1.UNSAFE_COMPENSATION,
                "state variable changed after the target operation",
                field = change.name,
              )
            }
            val target = if (undo) change.before else change.after
            val declarations =
              if (target == null) working.document.stateVariables - change.name
              else working.document.stateVariables + (change.name to target)
            // Putting a declaration back is only safe if nothing has since come to read it — a
            // removal undone is harmless, but a re-declaration undone would strand whatever was
            // written against it in the meantime.
            val document = working.document.copy(stateVariables = declarations)
            document.stateUsageIssue(declarations)?.let { issue ->
              fail(
                RejectionCodeV1.UNSAFE_COMPENSATION,
                issue.message,
                nodeId = issue.nodeId,
                field = issue.field,
              )
            }
            working = working.copy(document = document)
          }
          is EventBindingChangeV1 -> {
            val node =
              working.document.nodes[change.nodeId]
                ?: fail(
                  RejectionCodeV1.UNSAFE_COMPENSATION,
                  "event binding node no longer exists",
                  nodeId = change.nodeId,
                )
            val expected = if (undo) change.after else change.before
            if (node.eventBindings[change.event] != expected) {
              fail(
                RejectionCodeV1.UNSAFE_COMPENSATION,
                "event binding changed after the target operation",
                nodeId = change.nodeId,
                field = eventBindingField(change.event),
              )
            }
            val target = if (undo) change.before else change.after
            val bindings =
              if (target == null) node.eventBindings - change.event
              else node.eventBindings + (change.event to target)
            val document =
              working.document.copy(
                nodes = working.document.nodes + (node.id to node.copy(eventBindings = bindings))
              )
            // Restoring a binding whose variable has since been removed would put back exactly the
            // reference `removeStateVariable` refuses to leave behind.
            document.stateUsageIssue(document.stateVariables)?.let { issue ->
              fail(
                RejectionCodeV1.UNSAFE_COMPENSATION,
                issue.message,
                nodeId = issue.nodeId,
                field = issue.field,
              )
            }
            working = working.copy(document = document)
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
          is EnvironmentChangeRecordV1 -> {
            val expected = if (undo) change.after else change.before
            val target = if (undo) change.before else change.after
            val current = working.document.environment
            val mismatch = change.fields.firstOrNull { current.value(it) != expected.value(it) }
            if (mismatch != null) {
              fail(
                RejectionCodeV1.UNSAFE_COMPENSATION,
                "environment field changed after the target operation",
                environmentField = mismatch,
              )
            }
            working =
              working.copy(
                document =
                  working.document.copy(environment = current.copyFieldsFrom(target, change.fields))
              )
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
      access = design.access.takeIf { design.ownedBy(actor) },
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
          activePresence(design.document.id),
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
      if (!design.allows(subscriber.actor, DesignAccessActionV1.READ)) {
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

  private fun activePresence(designId: String): List<PresenceV1> {
    expirePresence(designId)
    return runtime.getValue(designId).presence.values.map(RuntimePresence::value)
  }

  private fun expirePresence(designId: String): List<String> {
    val cutoff = clock.millis() - limits.presenceTtlMillis
    val presence = runtime.getValue(designId).presence
    val expired = presence.filterValues { it.lastSeenAtMillis <= cutoff }.keys.toList()
    expired.forEach(presence::remove)
    return expired
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

  override fun adminListDesigns(): List<UiBuilderAdminDesignSummary> = lock.withLock {
    persisted.designs.values
      .sortedWith(compareBy({ it.createdAtEpochMillis }, { it.document.id }))
      .map { design ->
        UiBuilderAdminDesignSummary(
          designId = design.document.id,
          title = design.document.title,
          revision = design.document.revision,
          catalogPin = design.document.catalogPin,
          ownerActorId = design.access.ownerActorId,
          collaborators =
            design.access.actorGrants.count { it.actorId != design.access.ownerActorId },
          createdAtEpochMillis = design.createdAtEpochMillis,
          updatedAtEpochMillis = design.updatedAtEpochMillis,
          activeSubscribers = runtime[design.document.id]?.subscribers?.size ?: 0,
        )
      }
  }

  override fun adminUnusableDesigns(): Map<String, String> =
    unusableDesigns.mapValues { (_, unusable) ->
      unusable.reason
    }

  /**
   * The stored document, read straight out of the loaded state and rendered as JSON.
   *
   * Pretty-printed rather than canonical on purpose: the caller is a person about to edit it, not a
   * checksum. Nothing here consults [catalogs] or [limits], which is the whole point — a design
   * held back because one of those changed is the design most likely to need copying out.
   */
  override fun adminDesignDocument(designId: String): String? = lock.withLock {
    val document = persisted.designs[designId]?.document ?: return null
    PersistentUiBuilderServiceAdminJson.json.encodeToString(DesignDocumentV1.serializer(), document)
  }

  /**
   * Replace a quarantined design's document with a repaired one, or say why the repair is not one.
   *
   * The other half of [adminDesignDocument] and the whole point of holding a design back rather
   * than refusing to start: a rule changed under a stored document, so the operator edits the
   * document to satisfy the rule and puts it back. The candidate is checked against exactly the
   * conditions that quarantined the original, so a repair that does not repair is refused with the
   * remaining reason instead of being stored and quarantined again.
   *
   * Deliberately restricted to a design that is currently unusable. A design the host serves has
   * live editors, a revision history and subscribers reading a sequence, and replacing its document
   * underneath them is a mutation — [UiBuilderServiceRequest.ApplyOperation] is how that is done,
   * with authorization and a sequence. A quarantined design has none of those by construction: it
   * refuses every request that names it, so nothing is watching and its history describes a
   * document this build could not load anyway. That history is therefore replaced rather than
   * extended, and the sequence continues upward so no client can mistake the repaired design for
   * the old one.
   */
  override fun adminRepairDesign(designId: String, documentJson: String): UiBuilderAdminRepair =
    lock.withLock {
      val existing = persisted.designs[designId] ?: return UiBuilderAdminRepair.NotFound(designId)
      val unusable =
        unusableDesigns[designId]
          ?: return UiBuilderAdminRepair.Rejected(
            "design $designId is served normally; repair is only for a design this host cannot serve"
          )
      val candidate =
        try {
          PersistentUiBuilderServiceAdminJson.json.decodeFromString(
            DesignDocumentV1.serializer(),
            documentJson,
          )
        } catch (failure: Exception) {
          return UiBuilderAdminRepair.Rejected(
            "repaired document is not a readable design: ${failure.message}"
          )
        }
      if (candidate.id != designId) {
        return UiBuilderAdminRepair.Rejected("repaired document is ${candidate.id}, not $designId")
      }
      validateEnvironment(candidate.environment)?.let {
        return UiBuilderAdminRepair.Rejected(it.message)
      }
      val now = clock.millis()
      val sequence = existing.lastSequence + 1
      val document =
        candidate.copy(
          revision = existing.document.revision + 1,
          createdAtEpochMillis = existing.document.createdAtEpochMillis,
          updatedAtEpochMillis = now,
        )
      // The same question the original failed, asked of the replacement. Answering it here is what
      // keeps the store's invariant — everything loaded is either servable or named as not — true
      // after a write as well as after a load.
      unusableReason(designId, existing.copy(document = document))?.let {
        return UiBuilderAdminRepair.Rejected(it.reason)
      }
      val positions = derivePositions(document)
      val repaired =
        existing.copy(
          document = document,
          lastSequence = sequence,
          history = emptyList(),
          revisionSnapshots = listOf(RevisionStateV1(document, sequence)),
          operationOutcomes = emptyMap(),
          acceptedOperations = emptyMap(),
          tombstones = emptyMap(),
          positions = positions,
          positionSnapshots = listOf(PositionStateV1(document.revision, positions)),
          updatedAtEpochMillis = now,
        )
      commitPersisted(persisted.copy(designs = persisted.designs + (designId to repaired)))
      unusableDesigns.remove(designId)
      runtime.getOrPut(designId) { RuntimeDesign() }
      UiBuilderAdminRepair.Repaired(designId, document.revision, unusable.reason)
    }

  override fun adminDeleteDesign(designId: String): Boolean {
    val closed: List<SubscriberMailbox> = lock.withLock {
      if (designId !in persisted.designs) return false
      removeLocked(designId)
    }
    closed.forEach(SubscriberMailbox::close)
    return true
  }

  /**
   * Remove a design that exists, under the lock, and hand back the streams that were open on it for
   * the caller to close once it is safe to.
   */
  private fun removeLocked(designId: String): List<SubscriberMailbox> {
    // Durable first: a subscriber whose stream closes has lost the design, not merely the
    // connection, and must not observe that before the removal is on disk.
    commitPersisted(persisted.copy(designs = persisted.designs - designId))
    // The design is gone, so its quarantine goes with it: leaving the entry would answer this id
    // with a catalog error rather than "not found", and would follow a re-created design here.
    unusableDesigns.remove(designId)
    val removed = runtime.remove(designId)
    mutationBuckets.keys.removeIf { (_, bucketDesignId) -> bucketDesignId == designId }
    return removed?.subscribers?.values?.map { it.mailbox }.orEmpty()
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
  public fun migratePersistenceToLatest(): UiBuilderPersistenceMigrationResult = lock.withLock {
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
    // Deliberately not gated on every stored design being servable. This rewrites the envelope
    // format and does not reinterpret a single document, so a design the current catalog cannot
    // serve is no reason to refuse — and refusing would put back, in an operator's one recovery
    // path, exactly the trap that moving validation off startup removed: one design pinned to a
    // withdrawn catalog and the migration can never be run. What this step does need is proved
    // below and is about the bytes: the preflight round trip, the durable readback, and the
    // rollback if either disagrees.
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
    val root =
      try {
        json.parseToJsonElement(encoded).jsonObject
      } catch (failure: Exception) {
        throw UiBuilderPersistenceException("invalid UI-builder persistence JSON", failure)
      }
    val format =
      (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw UiBuilderPersistenceException("UI-builder persistence format is missing")
    // The checksum covers the payload AS STORED — this parsed tree — and never a re-encode of the
    // decoded value. Re-encoding checksums the CURRENT model rather than the bytes on disk, which
    // gets both halves of the job wrong. It cannot see corruption that still decodes (the whole
    // point of the checksum), and it fails on a file that is perfectly intact whenever the model
    // has merely grown: `encodeDefaults = true` emits every field a data class declares, so one new
    // property with a default re-encodes to a JSON tree the stored checksum was never taken over.
    // That is not hypothetical — `DesignEnvironmentV1.typeface` (compose-ai-contracts 2.8.0, server
    // 3.4.0) turned every existing deployment's state file into an unreadable one, and the server
    // exits at construction when persistence fails to load, so the release crash-looped instead of
    // starting. The stored tree, by contrast, is exactly what the writer checksummed: a field the
    // model gained since is simply absent from it, and `decode` fills the default in afterwards.
    val storedPayload =
      root["payload"]
        ?: throw UiBuilderPersistenceException("UI-builder persistence payload is missing")
    return when (format) {
      PersistenceFormat.V1.wire -> {
        val envelope = decodeEnvelope<PersistenceEnvelopeV1>(encoded)
        verifyChecksum(envelope.checksumSha256, storedPayload)
        LoadedPersistence(envelope.payload, PersistenceFormat.V1)
      }
      PersistenceFormat.V2.wire -> {
        val envelope = decodeEnvelope<PersistenceEnvelopeV2>(encoded)
        verifyChecksum(envelope.checksumSha256, storedPayload)
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

  // The write side of the pairing [decode] documents: the tree checksummed here is the tree
  // serialized on the next line, so the number on disk always describes the bytes beside it.
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

  public companion object {
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
private data class ModifierChangeV1(
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
private data class StateVariableChangeV1(
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
private data class EventBindingChangeV1(
  val nodeId: String,
  val event: String,
  val before: List<DesignActionV1>?,
  val after: List<DesignActionV1>?,
) : ChangeRecordV1

@Serializable
@SerialName("environment")
private data class EnvironmentChangeRecordV1(
  val fields: List<EnvironmentFieldV1>,
  val before: DesignEnvironmentV1,
  val after: DesignEnvironmentV1,
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
  environmentField: EnvironmentFieldV1? = null,
): Nothing =
  throw ReductionFailure(
    rejected("", 0, code, message, operationIndex, nodeId, field, environmentField)
  )

private fun rejected(
  operationId: String,
  revision: Long,
  code: RejectionCodeV1,
  message: String,
  operationIndex: Int? = null,
  nodeId: String? = null,
  field: String? = null,
  environmentField: EnvironmentFieldV1? = null,
): RejectedOutcomeV1 =
  RejectedOutcomeV1(
    operationId,
    revision,
    code,
    message,
    operationIndex,
    nodeId,
    field,
    environmentField,
  )

private fun PersistedDesignV1.allows(actorId: String, action: DesignAccessActionV1): Boolean =
  actorId == access.ownerActorId ||
    access.actorGrants.any { it.actorId == actorId && action in it.allowedActions }

/**
 * The same question asked of a whole identity: an actor may act, or the human it acts for may.
 *
 * This is the one place delegation is honoured, and it is deliberately a *widening of who* rather
 * than a widening of what — a delegate reaches exactly the designs its principal reaches, with
 * exactly the actions the design granted the principal. An actor with no principal
 * ([AuthenticatedUiBuilderActor.onBehalfOfActorId] null) asks precisely the question it always did.
 */
private fun PersistedDesignV1.allows(
  actor: AuthenticatedUiBuilderActor,
  action: DesignAccessActionV1,
): Boolean = actor.accessIdentities.any { allows(it, action) }

/** True when this actor owns the design outright, or acts for the human who does. */
private fun PersistedDesignV1.ownedBy(actor: AuthenticatedUiBuilderActor): Boolean =
  access.ownerActorId in actor.accessIdentities

private fun PersistedDesignV1.listItem(actor: AuthenticatedUiBuilderActor): DesignListItemV1 {
  // Reported under the *actor's own* id — the caller asked what it may do here, and being told
  // about an id it does not use would be an answer to a question nobody asked. What it may do is
  // resolved through its principal when it has one, which is what put this design in the listing.
  val actorId = actor.actorId
  val requester =
    if (ownedBy(actor))
      DesignActorAccessV1(actorId, DesignAccessRoleV1.OWNER, DesignAccessActionV1.entries)
    else {
      val grant =
        actor.accessIdentities.firstNotNullOf { identity ->
          access.actorGrants.firstOrNull { it.actorId == identity }
        }
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

/** What a conflict and a compensation failure call the chain, since the wire has no name for it. */
private const val MODIFIERS_FIELD = "modifiers"

private const val PREDICATE_FIELD = "predicate"

/**
 * How a rejection or a conflict names one event's binding.
 *
 * The wire's `field` is one string and has no separate place for an event, so the binding map and
 * the event are spelled together — the same bargain `modifiers` makes by naming the whole chain.
 */
private fun eventBindingField(event: String): String = "eventBindings.$event"

/** Where a document reads a state variable it should not, and under which field. */
private data class StateUsageIssue(val nodeId: String, val field: String, val message: String)

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
private fun DesignDocumentV1.stateUsageIssue(
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
    else -> emptyList()
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
private fun staleStateWrites(
  original: PersistedDesignV1,
  command: DesignCommandV1,
  name: String,
): List<CommandConflictV1> =
  if (
    command.baseRevision < original.document.revision &&
      original.acceptedOperations.values.any {
        it.committedRevision > command.baseRevision &&
          it.changes.any { change -> change is StateVariableChangeV1 && change.name == name }
      }
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

private data class EnvironmentValidationIssue(
  val field: EnvironmentFieldV1,
  val message: String,
)

private fun validateEnvironment(environment: DesignEnvironmentV1): EnvironmentValidationIssue? =
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

private fun DesignEnvironmentV1.applyChange(change: EnvironmentChangeV1): DesignEnvironmentV1 =
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

private fun DesignEnvironmentV1.value(field: EnvironmentFieldV1): Any? =
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

private fun DesignEnvironmentV1.copyFieldsFrom(
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
private const val MAXIMUM_TITLE_LENGTH = 200

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

/**
 * For what an operator reads, not for what is stored: indented, and never used to compute bytes.
 */
private object PersistentUiBuilderServiceAdminJson {
  val json: Json = Json {
    encodeDefaults = true
    prettyPrint = true
  }
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
