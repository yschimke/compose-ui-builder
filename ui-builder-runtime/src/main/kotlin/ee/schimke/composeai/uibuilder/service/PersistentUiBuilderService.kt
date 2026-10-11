@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.replay.ReplayAnswer
import ee.schimke.composeai.uibuilder.replay.replayCommandLog
import java.io.Closeable
import java.io.IOException
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
   * executor cannot say. Defaulted for source compatibility with older executors.
   */
  public fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? = null

  /**
   * Whether one property write carries a value of the kind the catalog means — the semantic half of
   * [validate]. Asked only where a value is chosen (`setProperty`, `insertNode`), never
   * document-wide, so older designs stay editable. Refusals are `INVALID_PROPERTY`.
   */
  public fun validateWrite(
    catalog: CatalogCapabilityV1,
    /**
     * The document the node is being written into, so an `assetKey` pinned into its `assets` by the
     * asset lane resolves as well as the catalog's registry.
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
  /**
   * The most whole-document revisions one design may retain; each is a full copy of the document
   * and position map. See `docs/design/UI_BUILDER_STATE_STORAGE.md`.
   */
  val retainedRevisionSnapshots: Int = 128,
  /**
   * The byte budget retained revisions share, which binds first for large documents. Effective
   * depth is this budget divided by the canonical document size, clamped between
   * [minimumRetainedRevisionSnapshots] and [retainedRevisionSnapshots].
   */
  val retainedRevisionBytes: Long = 2L * 1_024 * 1_024,
  /**
   * The depth [retainedRevisionBytes] may never cut below, since rebasing and undo need retained
   * state. Never raises [retainedRevisionSnapshots].
   */
  val minimumRetainedRevisionSnapshots: Int = 32,
  /**
   * The byte budget one design's undo state may hold, applied to `acceptedOperations` and
   * `tombstones` separately. A count cannot bound these: `StructureChangeV1` records carry whole
   * subtrees and vary by orders of magnitude. Walked newest-first, so each commit's work is
   * proportional to the budget.
   */
  val retainedUndoBytes: Long = 4L * 1_024 * 1_024,
  /**
   * Undo steps kept regardless of [retainedUndoBytes]. Pruning further degrades gracefully: undo
   * answers `UNKNOWN_OPERATION` and restore answers `DELETED_NODE`.
   */
  val minimumRetainedUndoOperations: Int = 8,
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
   * Ceiling on one uploaded asset ([UiBuilderAssetPort.putAsset]). Every render lane carries assets
   * inline on each export, so this bounds requests too.
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
    require(retainedRevisionBytes > 0)
    require(minimumRetainedRevisionSnapshots > 0)
    require(retainedUndoBytes > 0)
    require(minimumRetainedUndoOperations > 0)
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
  private val designStore: UiBuilderDesignStateStore,
  private val catalogs: UiBuilderCatalogExecutor,
  private val exporter: UiBuilderExportExecutor,
  private val subscriberFailureHandler: UiBuilderSubscriberFailureHandler,
  private val clock: Clock,
  private val limits: UiBuilderServiceLimits,
  /**
   * Where uploaded asset bytes go. Null on a host with nowhere to keep them, which makes [putAsset]
   * refuse and leaves every other lane exactly as it was.
   */
  private val assets: UiBuilderAssetStore?,
  /**
   * Resolves icon outlines so a design carries its own picture. Null leaves the registry empty.
   * Filled here rather than in the editor because MCP writes and `CreateDesign` also add icons.
   */
  private val iconOutlines: IconOutlineResolver?,
) :
  UiBuilderServicePort,
  UiBuilderServiceDiagnosticsSource,
  UiBuilderAdminPort,
  UiBuilderAssetPort,
  UiBuilderBranchPort {

  /**
   * The constructor published before icon outlines existed; defaults live here to keep exactly the
   * two JVM descriptors 3.24 published.
   */
  public constructor(
    designStore: UiBuilderDesignStateStore,
    catalogs: UiBuilderCatalogExecutor,
    exporter: UiBuilderExportExecutor,
    subscriberFailureHandler: UiBuilderSubscriberFailureHandler =
      UiBuilderSubscriberFailureHandler {},
    clock: Clock = Clock.systemUTC(),
    limits: UiBuilderServiceLimits = UiBuilderServiceLimits(),
    assets: UiBuilderAssetStore? = null,
  ) : this(
    designStore,
    catalogs,
    exporter,
    subscriberFailureHandler,
    clock,
    limits,
    assets,
    iconOutlines = null,
  )

  /**
   * The single-file storage this service was built on, for hosts without a state directory and for
   * `migratePersistenceToLatest`. Hosts with a directory use [UiBuilderDesignStateStore].
   */
  public constructor(
    storage: UiBuilderStateStorage,
    catalogs: UiBuilderCatalogExecutor,
    exporter: UiBuilderExportExecutor,
    subscriberFailureHandler: UiBuilderSubscriberFailureHandler =
      UiBuilderSubscriberFailureHandler {},
    clock: Clock = Clock.systemUTC(),
    limits: UiBuilderServiceLimits = UiBuilderServiceLimits(),
    assets: UiBuilderAssetStore? = null,
  ) : this(
    UiBuilderDesignStateStore(LegacyStateStorageDesignStore(storage)),
    catalogs,
    exporter,
    subscriberFailureHandler,
    clock,
    limits,
    assets,
  )

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

  private sealed interface WholeDocumentCandidate {
    data class Ready(val document: DesignDocumentV1) : WholeDocumentCandidate

    data class Refused(val message: String) : WholeDocumentCandidate
  }

  private val lock = ReentrantLock()
  private val store: UiBuilderDesignStore = designStore.store
  private val loadedPersistence = store.load()
  private var persisted: PersistedServiceV1 =
    PersistedServiceV1(
      loadedPersistence.designs.mapValues { (_, design) ->
        // Filled first: `rePinned` validates before it moves a pin, and a newly required property
        // the design lacks is exactly what would make it refuse.
        design.withNewlyRequiredFilled().rePinned()
      }
    )
  private val rePin: RePinOutcome = persistRePins()
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
    val degradedDesigns = degradedDesigns()
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
      degradedDesigns = degradedDesigns.size,
      rePinnedDesigns = rePin.designs,
      rePinPersistenceFailure = rePin.failure,
      storageBytes = storageUsage?.bytes ?: 0,
      storageMaximumBytes = storageUsage?.maximumBytes ?: 0,
    )
  }

  /**
   * What the durable storage holds against its ceiling, or null when it reports neither. Never
   * allowed to fail a status route.
   */
  private val storageUsage: UiBuilderStorageUsage?
    get() = runCatching { store.usage() }.getOrNull()

  init {
    persisted.designs.forEach { (designId, _) -> runtime[designId] = RuntimeDesign() }
  }

  /**
   * Why one stored design cannot be served, held rather than thrown so one bad design never stops
   * startup. Owners can still delete it and `adminDesignDocument` can read it out. Integrity
   * failures (checksum, truncation, format) are still refused by the storage layer.
   */
  private data class UnusableDesign(
    val code: ServiceErrorCodeV1,
    val reason: String,
    /**
     * True when the store could not read this design (only retirement recovers), rather than the
     * catalog refusing a readable document (which can be downloaded and repaired).
     */
    val storeQuarantine: Boolean = false,
    /**
     * True when the document is sound and the catalog moved. Such a design may still ask for a
     * catalog upgrade preview in [execute] — its only repair.
     */
    val catalogFault: Boolean = false,
  )

  /**
   * Maintained after load, so a repaired or deleted design stops being refused without a restart.
   * Written under [lock]; concurrent because [execute] and exports read it outside the lock.
   */
  private val unusableDesigns: MutableMap<String, UnusableDesign> =
    ConcurrentHashMap(
      persisted.designs
        .mapNotNull { (designId, design) ->
          unusableReason(designId, design)?.let { designId to it }
        }
        .toMap() +
        // A design whose own files could not be read, reported like any other unusable design.
        loadedPersistence.quarantined.mapValues { (_, record) ->
          UnusableDesign(
            ServiceErrorCodeV1.INTERNAL,
            "stored design cannot be read: ${record.reason}",
            storeQuarantine = true,
          )
        }
    )

  /**
   * Designs the store could not read, with the header it still holds, so [delete] and [list] can
   * treat them as their owner's. Without a header they stay the operator's. Written under [lock]
   * only.
   */
  private val quarantinedDesigns: MutableMap<String, StoredQuarantineV3> =
    ConcurrentHashMap(loadedPersistence.quarantined)

  /**
   * The same design pinned to the catalog reference this deployment serves, since
   * `--ui-builder-published-catalogs` changes references and the browser compares pins. In memory
   * only — see [persistRePins]. Safe because the pin must resolve to a served catalog with the same
   * `systemId` and the document must validate against it.
   */
  private fun PersistedDesignV1.rePinned(): PersistedDesignV1 {
    val pin = document.catalogPin
    val catalog = catalogs.resolve(pin) ?: return this
    val served = catalogs.reference(catalog) ?: return this
    if (served == pin || served.systemId != pin.systemId) return this
    // Same probe as `unusableReason`, and for the same reason: a design the catalog merely
    // outgrew a property of still fits the catalog well enough for its pin to be rewritten.
    if (
      catalogs.validate(
        document.withoutProperties(undeclaredProperties(document, catalog)),
        catalog,
      ) != null
    )
      return this
    return copy(document = document.copy(catalogPin = served))
  }

  /**
   * The design with newly required properties filled by [withRequiredPlaceholders], when that is
   * the only thing wrong with it — otherwise the editor could never open it to fix it. Any other
   * defect returns it untouched. Written through by [persistRePins].
   */
  private fun PersistedDesignV1.withNewlyRequiredFilled(): PersistedDesignV1 {
    val catalog = catalogs.resolve(document.catalogPin) ?: return this
    fun probe(candidate: DesignDocumentV1) =
      candidate.withoutProperties(undeclaredProperties(candidate, catalog))
    if (catalogs.validate(probe(document), catalog) == null) return this
    val (filled, changed) = document.withRequiredPlaceholders(catalog)
    if (changed.isEmpty()) return this
    if (catalogs.validate(probe(filled), catalog) != null) return this
    return copy(document = filled)
  }

  /** What the startup re-pin did, for the health surface. */
  private data class RePinOutcome(val designs: Int = 0, val failure: String? = null)

  /**
   * Writes re-pinned designs through to the store once at startup so stored pins converge even for
   * designs nobody edits. Not fatal: failures surface via [diagnostics]. The count is what was
   * attempted.
   */
  private fun persistRePins(): RePinOutcome {
    // Identity, not equality: `rePinned` returns the receiver untouched when it changes nothing, so
    // a design that moved is the one that is no longer the same object the store handed over.
    val changed =
      persisted.designs
        .filter { (designId, design) -> loadedPersistence.designs[designId] !== design }
        .mapValues { (designId, design) -> loadedPersistence.designs[designId] to design }
    if (changed.isEmpty()) return RePinOutcome()
    return try {
      store.commitAll(changed)
      RePinOutcome(designs = changed.size)
    } catch (failure: Exception) {
      // The class, never the message: `/status.json` is unauthenticated on `--public` hosts and the
      // message carries a design id and state path.
      RePinOutcome(designs = changed.size, failure = failure::class.java.simpleName)
    }
  }

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
          catalogFault = true,
        )
    // Judged on the probe: an undeclared property is a warning; a real defect (unknown component)
    // stays fatal.
    val probe = design.document.withoutProperties(undeclaredProperties(design.document, catalog))
    catalogs.validate(probe, catalog)?.let {
      return UnusableDesign(
        ServiceErrorCodeV1.INTERNAL,
        "invalid stored design $designId: ${it.message}",
        catalogFault = true,
      )
    }
    return null
  }

  /**
   * The design a request is about, or null. Listing is in the null group so unusable designs still
   * appear to operators.
   */
  private fun UiBuilderServiceRequest.designId(): String? =
    when (this) {
      is UiBuilderServiceRequest.OpenDesign -> designId
      is UiBuilderServiceRequest.GetDesignAccess -> designId
      is UiBuilderServiceRequest.GetDesignActions -> designId
      is UiBuilderServiceRequest.UpdateDesignAccess -> designId
      is UiBuilderServiceRequest.PreviewCatalogUpgrade -> designId
      is UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade -> designId
      is UiBuilderServiceRequest.ApplyOperation -> submission.designId
      is UiBuilderServiceRequest.GetSnapshot -> designId
      is UiBuilderServiceRequest.GetDelta -> designId
      is UiBuilderServiceRequest.UpdatePresence -> designId
      is UiBuilderServiceRequest.ExportDesign -> designId
      is UiBuilderServiceRequest.RenameDesign -> designId
      is UiBuilderServiceRequest.DeleteDesign -> designId
      is UiBuilderServiceRequest.ListRevisions -> designId
      is UiBuilderServiceRequest.RestoreRevision -> designId
      is UiBuilderServiceRequest.MoveDesignHome -> designId
      is UiBuilderServiceRequest.ReplaceDesignDocument -> designId
      UiBuilderServiceRequest.ListCatalogs,
      is UiBuilderServiceRequest.ExportDocument,
      is UiBuilderServiceRequest.CreateDesign,
      is UiBuilderServiceRequest.ListDesigns -> null
    }

  override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
    call.request.designId()?.let { designId ->
      unusableDesigns[designId]?.let { unusable ->
        // Three exceptions to the unusable-design gate:
        // - a catalog upgrade preview, the only repair a catalog-outgrown design has;
        // - delete by the owner, however broken the document ([delete] checks ownership);
        // - rename by a writer, for designs whose files loaded (an unreadable one has no document).
        val previewing =
          (call.request is UiBuilderServiceRequest.PreviewCatalogUpgrade ||
            call.request is UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade) &&
            unusable.catalogFault
        val recovering =
          call.request is UiBuilderServiceRequest.ApplyOperation &&
            call.request.submission.isCatalogUpgradeOnly() &&
            unusable.catalogFault
        val deleting = call.request is UiBuilderServiceRequest.DeleteDesign
        val renaming =
          call.request is UiBuilderServiceRequest.RenameDesign && !unusable.storeQuarantine
        val replacing =
          call.request is UiBuilderServiceRequest.ReplaceDesignDocument && !unusable.storeQuarantine
        if (!previewing && !recovering && !deleting && !renaming && !replacing) {
          return UiBuilderServiceResponse.Error(
            UiBuilderServiceError(unusable.code, unusable.reason)
          )
        }
      }
    }
    if (
      call.request is UiBuilderServiceRequest.ExportDesign ||
        call.request is UiBuilderServiceRequest.ExportDocument
    )
      return export(call)
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
   * An asset write is a commit without an operation: `history` is cut and subscribers get a whole
   * snapshot. Not undoable.
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
    if (design.branch != null) {
      // An upload is a commit without an operation, so a branch log could not carry it and a merge
      // would silently drop it. Refused rather than lost; see `UI_BUILDER_BRANCHES.md`.
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, BRANCH_WHOLE_DOCUMENT_REFUSAL)
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
    val canonical = documentCanonicalBytes(document)
    val retained = limits.retainedRevisionsFor(documentRetentionBytes(document))
    val outcome =
      AcceptedOutcomeV1(
        operationId,
        revision,
        sequence,
        sha256(canonical),
        idempotentReplay = false,
        documentUpdatedAtEpochMillis = now,
      )
    val updated =
      design.copy(
        document = document,
        lastSequence = sequence,
        history = emptyList(),
        revisionSnapshots =
          (design.revisionSnapshots + RevisionStateV1(document, sequence)).retainedRevisions(
            retained,
            pinnedRevisions(write.designId),
          ),
        positionSnapshots =
          (design.positionSnapshots + PositionStateV1(revision, design.positions))
            .retainedPositions(retained, pinnedRevisions(write.designId)),
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
    commitDesign(write.designId, updated)
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
      is UiBuilderServiceRequest.GetDesignActions -> actions(call.actor, request.designId)
      is UiBuilderServiceRequest.UpdateDesignAccess -> updateAccess(call.actor, request)
      is UiBuilderServiceRequest.PreviewCatalogUpgrade -> previewUpgrade(call.actor, request)
      is UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade ->
        previewCurrentUpgrade(call.actor, request.designId)
      is UiBuilderServiceRequest.ApplyOperation -> apply(call.actor, request.submission)
      is UiBuilderServiceRequest.GetSnapshot -> open(call.actor, request.designId, request.revision)
      is UiBuilderServiceRequest.GetDelta -> delta(call.actor, request)
      is UiBuilderServiceRequest.UpdatePresence -> presence(call.actor, request)
      is UiBuilderServiceRequest.ExportDesign,
      is UiBuilderServiceRequest.ExportDocument ->
        error("export is executed outside the service lock")
      is UiBuilderServiceRequest.RenameDesign -> rename(call.actor, request)
      is UiBuilderServiceRequest.DeleteDesign -> delete(call.actor, request.designId)
      is UiBuilderServiceRequest.ListRevisions -> revisions(call.actor, request.designId)
      is UiBuilderServiceRequest.RestoreRevision -> restore(call.actor, request)
      is UiBuilderServiceRequest.MoveDesignHome -> moveHome(call.actor, request)
      is UiBuilderServiceRequest.ReplaceDesignDocument -> replaceDocument(call.actor, request)
    }

  /** See [UiBuilderServiceRequest.ListRevisions]. */
  private fun revisions(actor: AuthenticatedUiBuilderActor, designId: String): LockedExecution {
    val design = persisted.designs[designId] ?: return serviceError(notFound(designId))
    if (!design.allows(actor, DesignAccessActionV1.READ)) {
      return serviceError(forbidden("read", designId))
    }
    // The commit audit first, then the log: an asset upload clears the log but its author is only
    // in the audit.
    val authors =
      design.audit
        .filter { it.kind == AuditKindV1.COMMIT }
        .associate { it.sequence to it.actorId } +
        design.history.associate { it.outcome.sequence to it.submission.actorId() }
    return LockedExecution(
      UiBuilderServiceResponse.Revisions(
        designId,
        design.document.revision,
        design.revisionSnapshots
          .sortedByDescending { it.document.revision }
          .map { state ->
            UiBuilderRevisionSummary(
              revision = state.document.revision,
              sequence = state.sequence,
              updatedAtEpochMillis = state.document.updatedAtEpochMillis,
              actorId = authors[state.sequence],
            )
          },
      )
    )
  }

  /**
   * See [UiBuilderServiceRequest.RestoreRevision]. Built as a wire operation and handed to [apply],
   * so it gets the same checks, idempotency, limits, history and broadcast.
   */
  private fun restore(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest.RestoreRevision,
  ): LockedExecution {
    val design =
      persisted.designs[request.designId] ?: return serviceError(notFound(request.designId))
    if (!design.allows(actor, DesignAccessActionV1.READ)) {
      return serviceError(notFound(request.designId))
    }
    // A retry of a committed restore: rebuilding would fingerprint differently, so replay the
    // recorded outcome — only to a writer, and only if the record is this actor's restore of this
    // revision. Any other reuse of the id is refused as in [apply].
    design.operationOutcomes[request.operationId]?.let { prior ->
      if (!design.allows(actor, DesignAccessActionV1.WRITE)) {
        return serviceError(forbidden("write", request.designId))
      }
      if (!prior.isRestoreOf(request.revision, actor)) {
        return LockedExecution(
          UiBuilderServiceResponse.OperationOutcome(
            rejected(
              request.operationId,
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
    val target =
      design.revisionSnapshots.firstOrNull { it.document.revision == request.revision }?.document
        ?: return serviceError(
          UiBuilderServiceError(
            code = ServiceErrorCodeV1.SNAPSHOT_REQUIRED,
            message = "revision ${request.revision} is no longer retained for ${request.designId}",
            currentRevision = design.document.revision,
            retainedFromSequence = design.retainedSnapshotFromSequence(),
          )
        )
    return apply(
      actor,
      UiBuilderSubmission.Batch(
        designId = request.designId,
        operationId = request.operationId,
        clientId = RESTORE_CLIENT_ID,
        baseRevision = request.baseRevision,
        operations =
          listOf(
            CatalogUpgradeMutationV1(
              sourceCatalogPin = design.document.catalogPin,
              targetCatalogPin = target.catalogPin,
              sourceDocumentHash = documentHash(design.document),
              targetDocumentHash = documentHash(target),
              previewDigest = RESTORE_DIGEST_PREFIX + request.revision,
            )
          ),
      ),
    )
  }

  /** See [UiBuilderServiceRequest.MoveDesignHome]. */
  private fun moveHome(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest.MoveDesignHome,
  ): LockedExecution {
    val fingerprint =
      wholeDocumentFingerprint(
        kind = "moveHome",
        actor = actor,
        designId = request.designId,
        baseRevision = request.baseRevision,
        payload =
          buildJsonObject {
            put(
              "sourceHome",
              request.sourceHome?.let { json.encodeToJsonElement(DesignHomeV1.serializer(), it) }
                ?: JsonNull,
            )
            put(
              "targetHome",
              json.encodeToJsonElement(DesignHomeV1.serializer(), request.targetHome),
            )
          },
      )
    return replaceWholeDocument(
      actor = actor,
      designId = request.designId,
      operationId = request.operationId,
      baseRevision = request.baseRevision,
      fingerprint = fingerprint,
      replacesContent = false,
    ) { current ->
      when {
        current.home != request.sourceHome ->
          WholeDocumentCandidate.Refused("design home no longer matches the stated source home")
        request.targetHome is DesignHomeV1.Server && request.targetHome.designId != current.id ->
          WholeDocumentCandidate.Refused(
            "target server home design id does not match the stored design"
          )
        request.targetHome is DesignHomeV1.Server && request.targetHome.url.isBlank() ->
          WholeDocumentCandidate.Refused("target server home URL is blank")
        request.targetHome is DesignHomeV1.Repo && request.targetHome.path.isBlank() ->
          WholeDocumentCandidate.Refused("target repo home path is blank")
        current.home == request.targetHome ->
          WholeDocumentCandidate.Refused("design is already at the requested home")
        else -> WholeDocumentCandidate.Ready(current.copy(home = request.targetHome))
      }
    }
  }

  /** See [UiBuilderServiceRequest.ReplaceDesignDocument]. */
  private fun replaceDocument(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest.ReplaceDesignDocument,
  ): LockedExecution {
    val fingerprint =
      wholeDocumentFingerprint(
        kind = "replaceDocument",
        actor = actor,
        designId = request.designId,
        baseRevision = request.baseRevision,
        payload = json.encodeToJsonElement(DesignDocumentV1.serializer(), request.document),
      )
    return replaceWholeDocument(
      actor = actor,
      designId = request.designId,
      operationId = request.operationId,
      baseRevision = request.baseRevision,
      fingerprint = fingerprint,
      replacesContent = true,
      repairsUnusableDesign = true,
    ) { current ->
      when {
        request.document.id != request.designId ->
          WholeDocumentCandidate.Refused(
            "replacement document id does not match the requested design"
          )
        request.document.home != current.home ->
          WholeDocumentCandidate.Refused(
            "replacement document home does not match the stored design"
          )
        else -> WholeDocumentCandidate.Ready(request.document)
      }
    }
  }

  /**
   * Commit a complete document rather than a v1 operation batch. The outcome is durable and
   * idempotent. Both kinds cut the delta window; content replacement also cuts conflict and undo
   * state, a provenance-only home move preserves them.
   */
  private fun replaceWholeDocument(
    actor: AuthenticatedUiBuilderActor,
    designId: String,
    operationId: String,
    baseRevision: Long,
    fingerprint: String,
    replacesContent: Boolean,
    repairsUnusableDesign: Boolean = false,
    candidateFor: (DesignDocumentV1) -> WholeDocumentCandidate,
  ): LockedExecution {
    val design = persisted.designs[designId] ?: return serviceError(notFound(designId))
    if (!design.allows(actor, DesignAccessActionV1.WRITE)) {
      return serviceError(forbidden("write", designId))
    }
    if (design.branch != null) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, BRANCH_WHOLE_DOCUMENT_REFUSAL)
    }
    if (operationId.isBlank()) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "operation id is blank")
    }
    design.operationOutcomes[operationId]?.let { prior ->
      if (prior.fingerprint != fingerprint) {
        return LockedExecution(
          UiBuilderServiceResponse.OperationOutcome(
            rejected(
              operationId,
              design.document.revision,
              RejectionCodeV1.OPERATION_ID_REUSED,
              "operation id was already used by a different submission",
            )
          )
        )
      }
      val replay =
        when (val original = prior.outcome) {
          is AcceptedOutcomeV1 -> original.copy(idempotentReplay = true)
          is RejectedOutcomeV1 -> original
        }
      return LockedExecution(UiBuilderServiceResponse.OperationOutcome(replay))
    }
    // Admitted before recording: a durable rejection spends budget like an accepted change. The
    // rate-limit refusal itself is unrecorded so the operation id can retry.
    if (!admitMutation(actor.actorId, designId, 1)) {
      rejectedMutationRate.incrementAndGet()
      return LockedExecution(
        UiBuilderServiceResponse.OperationOutcome(
          rejected(
            operationId,
            design.document.revision,
            RejectionCodeV1.INVALID_COMMAND,
            "mutation rate limit exceeded",
          )
        )
      )
    }
    if (baseRevision != design.document.revision) {
      return recordWholeDocumentRejection(
        designId,
        design,
        operationId,
        fingerprint,
        RejectionCodeV1.REVISION_MISMATCH,
        "base revision $baseRevision does not match current revision ${design.document.revision}",
      )
    }
    val supplied =
      when (val candidate = candidateFor(design.document)) {
        is WholeDocumentCandidate.Ready -> candidate.document
        is WholeDocumentCandidate.Refused ->
          return recordWholeDocumentRejection(
            designId,
            design,
            operationId,
            fingerprint,
            RejectionCodeV1.INVALID_COMMAND,
            candidate.message,
          )
      }
    val revision = design.document.revision + 1
    val sequence = design.lastSequence + 1
    val now = clock.millis()
    var document =
      supplied.copy(
        id = designId,
        revision = revision,
        createdAtEpochMillis = design.document.createdAtEpochMillis,
        updatedAtEpochMillis = now,
      )
    val validationIssue =
      when {
        document.nodes.size > limits.maximumNodesPerDesign -> "design node limit exceeded"
        document.assets.size > limits.maximumAssetsPerDesign -> "design asset limit exceeded"
        else ->
          validateEnvironment(document.environment)?.message
            ?: documentQuotaIssue(document, countRejection = true)
            ?: validateTopology(document)?.message
      }
    if (validationIssue != null) {
      return recordWholeDocumentRejection(
        designId,
        design,
        operationId,
        fingerprint,
        RejectionCodeV1.INVALID_COMMAND,
        validationIssue,
      )
    }
    val catalog =
      catalogs.resolve(document.catalogPin)
        ?: return recordWholeDocumentRejection(
          designId,
          design,
          operationId,
          fingerprint,
          RejectionCodeV1.INVALID_COMMAND,
          "catalog pin is unavailable",
        )
    // A home move authors no content, so it uses the edit-time tolerance for undeclared stored
    // properties; replacement content stays strict.
    val validationProbe =
      if (replacesContent) document
      else document.withoutProperties(undeclaredProperties(design.document, catalog))
    catalogs.validate(validationProbe, catalog)?.let { issue ->
      return recordWholeDocumentRejection(
        designId,
        design,
        operationId,
        fingerprint,
        RejectionCodeV1.INVALID_COMMAND,
        issue.message,
      )
    }
    // Replacement content answers to the same value rules as an insert (`validateWrite`).
    if (replacesContent) {
      document.nodes.values.forEach { node ->
        node.properties.keys.forEach { property ->
          catalogs.validateWrite(catalog, document, node, property)?.let { issue ->
            return recordWholeDocumentRejection(
              designId,
              design,
              operationId,
              fingerprint,
              RejectionCodeV1.INVALID_PROPERTY,
              issue.message,
              nodeId = issue.nodeId ?: node.id,
              field = issue.field ?: property,
            )
          }
        }
      }
    }
    document = withIconOutlines(document)
    documentQuotaIssue(document, countRejection = true)?.let { issue ->
      return recordWholeDocumentRejection(
        designId,
        design,
        operationId,
        fingerprint,
        RejectionCodeV1.INVALID_COMMAND,
        issue,
      )
    }
    val canonical = documentCanonicalBytes(document)
    val outcome =
      AcceptedOutcomeV1(
        operationId,
        revision,
        sequence,
        sha256(canonical),
        idempotentReplay = false,
        documentUpdatedAtEpochMillis = now,
      )
    val outcomes = retainedOutcomeRecords(design, operationId, fingerprint, outcome)
    val retained = limits.retainedRevisionsFor(documentRetentionBytes(document))
    // A home move keeps positions: re-deriving would replace allocated structural keys and make
    // later compensation look unsafe.
    val positions = if (replacesContent) derivePositions(document) else design.positions
    val updated =
      design.copy(
        document = document,
        lastSequence = sequence,
        // The v1 deltas cannot express this, so cut only the catch-up log (disconnected clients get
        // a snapshot); undo records, conflict touches and tombstones survive.
        history = emptyList(),
        // An open branch's fork revision keeps its document so the branch can be compared; its
        // position state does not survive a content replacement, so merges across it are refused
        // with
        // `REVISION_NOT_RETAINED`.
        revisionSnapshots =
          (design.revisionSnapshots + RevisionStateV1(document, sequence)).retainedRevisions(
            retained,
            pinnedRevisions(designId),
          ),
        positions = positions,
        positionSnapshots =
          if (replacesContent) listOf(PositionStateV1(revision, positions))
          else
            (design.positionSnapshots + PositionStateV1(revision, positions)).retainedPositions(
              retained,
              pinnedRevisions(designId),
            ),
        updatedAtEpochMillis = now,
        audit =
          (design.audit +
              AuditRecordV1(
                AuditKindV1.COMMIT,
                actor.actorId,
                designId,
                revision,
                sequence,
                operationId,
                null,
                now,
              ))
            .takeLast(limits.retainedAuditRecords),
        operationOutcomes = outcomes,
        acceptedOperations =
          if (replacesContent) emptyMap()
          else
            design.acceptedOperations
              .filterKeys { it in outcomes.keys }
              .retainNewestWithinBytes(
                limits.retainedUndoBytes,
                limits.minimumRetainedUndoOperations,
                AcceptedOperationRecordV1::targetOperationId,
              ),
        conflictTouches = if (replacesContent) emptyList() else design.conflictTouches,
        tombstones = if (replacesContent) emptyMap() else design.tombstones,
      )
    if (repairsUnusableDesign) {
      unusableReason(designId, updated)?.let { issue ->
        return recordWholeDocumentRejection(
          designId,
          design,
          operationId,
          fingerprint,
          RejectionCodeV1.INVALID_COMMAND,
          issue.reason,
        )
      }
    }
    commitDesign(designId, updated)
    if (repairsUnusableDesign) unusableDesigns.remove(designId)
    val broadcast = snapshot(updated, actor, catalog, activePresence(designId)).copy(access = null)
    val mailboxes = enqueue(designId, UiBuilderServiceUpdate.Snapshot(broadcast), updated)
    return LockedExecution(UiBuilderServiceResponse.OperationOutcome(outcome), mailboxes)
  }

  private fun recordWholeDocumentRejection(
    designId: String,
    design: PersistedDesignV1,
    operationId: String,
    fingerprint: String,
    code: RejectionCodeV1,
    message: String,
    nodeId: String? = null,
    field: String? = null,
  ): LockedExecution {
    val outcome =
      rejected(operationId, design.document.revision, code, message, nodeId = nodeId, field = field)
    val outcomes = retainedOutcomeRecords(design, operationId, fingerprint, outcome)
    commitDesign(
      designId,
      design.copy(
        operationOutcomes = outcomes,
        // The same invariant the operation path keeps: an undo record whose outcome aged out of
        // the window is dropped with it, so a reused operation id cannot address an old mutation.
        acceptedOperations =
          design.acceptedOperations
            .filterKeys { it in outcomes.keys }
            .retainNewestWithinBytes(
              limits.retainedUndoBytes,
              limits.minimumRetainedUndoOperations,
              AcceptedOperationRecordV1::targetOperationId,
            ),
      ),
    )
    return LockedExecution(UiBuilderServiceResponse.OperationOutcome(outcome))
  }

  private fun retainedOutcomeRecords(
    design: PersistedDesignV1,
    operationId: String,
    fingerprint: String,
    outcome: CommandOutcomeV1,
  ): Map<String, OperationOutcomeRecordV1> =
    (design.operationOutcomes + (operationId to OperationOutcomeRecordV1(fingerprint, outcome)))
      .entries
      .toList()
      .takeLast(limits.retainedOperationOutcomes)
      .associate { it.toPair() }

  private fun wholeDocumentFingerprint(
    kind: String,
    actor: AuthenticatedUiBuilderActor,
    designId: String,
    baseRevision: Long,
    payload: kotlinx.serialization.json.JsonElement,
  ): String =
    canonicalJson(
      buildJsonObject {
        put("kind", kind)
        put("actorId", actor.actorId)
        put("onBehalfOfActorId", actor.onBehalfOfActorId?.let(::JsonPrimitive) ?: JsonNull)
        put("designId", designId)
        put("baseRevision", baseRevision)
        put("payload", payload)
      }
    )

  /**
   * See [UiBuilderServiceRequest.RenameDesign]. The current revision's snapshot is renamed too;
   * earlier revisions keep their title. Nothing is pushed: a rename does not advance the sequence,
   * so clients would discard the snapshot.
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
    commitDesign(request.designId, updated)
    return LockedExecution(UiBuilderServiceResponse.DesignRenamed(updated.listItem(actor)))
  }

  /**
   * See [UiBuilderServiceRequest.DeleteDesign]; shares [removeLocked] with [adminDeleteDesign].
   * Owner only, or the delegate that created the design ([deletableBy]). Unusable designs are
   * deletable the same way; an unreadable one is checked against its
   * quarantine header, and without one stays the operator's to retire.
   */
  private fun delete(actor: AuthenticatedUiBuilderActor, designId: String): LockedExecution {
    val design = persisted.designs[designId]
    if (design != null) {
      if (!design.deletableBy(actor)) {
        return serviceError(forbidden("delete", designId))
      }
      // Closed under the lock, after [removeLocked] has made the removal durable.
      removeLocked(designId).forEach(SubscriberMailbox::close)
      return LockedExecution(UiBuilderServiceResponse.DesignDeleted(designId))
    }
    val quarantined = quarantinedDesigns[designId]
    if (quarantined != null) {
      val header =
        quarantined.header
          ?: return serviceError(
            UiBuilderServiceError(
              ServiceErrorCodeV1.INTERNAL,
              "stored design cannot be read: ${quarantined.reason}",
            )
          )
      if (!header.access.deletableBy(actor, header.createdByDelegateActorId)) {
        return serviceError(forbidden("delete", designId))
      }
      store.remove(designId)
      // A stale entry would answer this id with the reason rather than "not found", even for a
      // re-created design.
      unusableDesigns.remove(designId)
      quarantinedDesigns.remove(designId)
      return LockedExecution(UiBuilderServiceResponse.DesignDeleted(designId))
    }
    return serviceError(notFound(designId))
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
    // A quarantined design occupies this id's directory on disk; creating over it would corrupt
    // both. Asked of the store by place, since a quarantine may be reported under a different
    // name.
    val holder =
      store.quarantineHolding(requested.id)
        ?: requested.id.takeIf { unusableDesigns[it]?.storeQuarantine == true }
    if (holder != null) {
      val reason = unusableDesigns[holder]?.reason ?: "the stored design could not be read"
      val named = if (holder == requested.id) "" else " (reported as $holder)"
      return serviceError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "design ${requested.id} is quarantined$named and must be retired before the id is reused: " +
          reason,
      )
    }
    if (requested.revision != 0L) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "new designs must start at revision 0")
    }
    if (requested.nodes.size > limits.maximumNodesPerDesign) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "design node limit exceeded")
    }
    val now = clock.millis()
    validateEnvironment(requested.environment)?.let {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, it.message)
    }
    // Validate before resolving outlines: resolution may fetch a large font under the shared lock.
    // The quota is re-checked below on what gets stored.
    val candidate = requested.copy(createdAtEpochMillis = now, updatedAtEpochMillis = now)
    documentQuotaIssue(candidate, countRejection = true)?.let {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, it)
    }
    validateTopology(candidate)?.let {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, it.message)
    }
    val catalog =
      catalogs.resolve(candidate.catalogPin)
        ?: return serviceError(ServiceErrorCodeV1.CATALOG_UNAVAILABLE, "catalog pin is unavailable")
    catalogs.validate(candidate, catalog)?.let {
      return serviceError(it.toServiceError())
    }
    // Outlines are stored, so re-check the quota with them included.
    val document = withIconOutlines(candidate)
    documentQuotaIssue(document, countRejection = true)?.let {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, it)
    }

    val design =
      PersistedDesignV1(
        // The same `document` the snapshot stores, so an immediate export or `GetSnapshot(revision
        // = 0)` sees the icons.
        document = document,
        lastSequence = 0,
        // Owned by the human an agent acts for: the agent's grant is short-lived, and the design
        // must outlive it.
        access =
          DesignAccessControlV1(0, canonicalActorId(actor.onBehalfOfActorId ?: actor.actorId)),
        revisionSnapshots = listOf(RevisionStateV1(document, 0)),
        positions = derivePositions(document),
        positionSnapshots = listOf(PositionStateV1(0, derivePositions(document))),
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
        // Lets that delegate delete what it made; see [deletableBy].
        createdByDelegateActorId =
          actor.actorId.takeIf { actor.onBehalfOfActorId != null }?.let(::canonicalActorId),
      )
    commitDesign(document.id, design)
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
    // Items are built lazily, and only for the page: the mapping reads access records, and a host
    // walking a thousand designs ten pages at a time must not pay for all of them ten times.
    val candidates = buildList {
      persisted.designs.values
        // Personally: a design that is readable only because it is public is not one of this
        // actor's designs, and listing it would list every public design to everyone.
        .filter { it.access.allowsPersonally(actor, DesignAccessActionV1.READ) }
        // A branch is listed under its parent (`UiBuilderBranchRequest.ListBranches`), not beside
        // it: five alternatives of one screen are one design being explored, not six designs.
        .filter { it.branch == null }
        .forEach { add(it.document.id to { it.listItem(actor) }) }
      // Quarantined designs with a known owner are listed so the owner can delete them. Reported
      // under the header's id, never a collision-renamed key.
      quarantinedDesigns.forEach { (designId, record) ->
        val header = record.header ?: return@forEach
        if (designId != record.designId || designId in persisted.designs) return@forEach
        if (!header.access.allowsPersonally(actor, DesignAccessActionV1.READ)) return@forEach
        add(designId to { header.quarantinedListItem(designId, actor) })
      }
    }
      .sortedBy { it.first }
    val offset =
      request.cursor?.toIntOrNull()?.takeIf { it >= 0 }
        ?: if (request.cursor == null) 0
        else return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "invalid list cursor")
    if (offset > candidates.size) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "list cursor is past the result set")
    }
    val page = candidates.drop(offset).take(request.limit)
    val next = (offset + page.size).takeIf { it < candidates.size }?.toString()
    return LockedExecution(UiBuilderServiceResponse.Designs(page.map { it.second() }, next))
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
              retainedFromSequence = design.retainedSnapshotFromSequence(),
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
    return LockedExecution(
      UiBuilderServiceResponse.DesignAccess(designId, design.access.collapsed())
    )
  }

  /**
   * What [actor] may do here, or a not-found indistinguishable from a missing design. It only
   * describes the caller's own reach, so it is safe for grantees.
   */
  private fun actions(actor: AuthenticatedUiBuilderActor, designId: String): LockedExecution {
    val design = persisted.designs[designId] ?: return serviceError(notFound(designId))
    if (!design.allows(actor, DesignAccessActionV1.READ)) {
      return serviceError(notFound(designId))
    }
    val actions =
      if (design.ownedBy(actor)) DesignAccessActionV1.entries
      else DesignAccessActionV1.entries.filter { design.allows(actor, it) }
    return LockedExecution(UiBuilderServiceResponse.DesignActions(designId, actions))
  }

  /**
   * What moving [request]'s design to another catalog would cost, without moving it. Read access
   * suffices. A candidate that still does not validate is BLOCKED (an answer), not an error.
   */
  private fun previewUpgrade(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest.PreviewCatalogUpgrade,
  ): LockedExecution {
    val design =
      persisted.designs[request.designId] ?: return serviceError(notFound(request.designId))
    if (!design.allows(actor, DesignAccessActionV1.READ)) {
      return serviceError(notFound(request.designId))
    }
    if (design.document.revision != request.baseRevision) {
      // The candidate is only meaningful against the document it was computed from, and an apply
      // quotes this revision back. Stale in, stale out, so it is refused rather than answered.
      return serviceError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "design ${request.designId} is at revision ${design.document.revision}",
      )
    }
    if (design.document.catalogPin != request.sourceCatalogPin) {
      return serviceError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "design ${request.designId} is not pinned to the stated source catalog",
      )
    }
    if (request.sourceCatalogPin.systemId != request.targetCatalogPin.systemId) {
      return serviceError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "a catalog upgrade cannot change the catalog system id",
      )
    }
    val target =
      catalogs.resolve(request.targetCatalogPin)
        ?: return serviceError(
          ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
          "target catalog is not served by this runtime",
        )
    return LockedExecution(
      UiBuilderServiceResponse.CatalogUpgradePreview(
        catalogUpgradePreview(design.document, target, request.targetCatalogPin)
      )
    )
  }

  /**
   * The browser recovery question, answered without asking the browser to manufacture either pin.
   */
  private fun previewCurrentUpgrade(
    actor: AuthenticatedUiBuilderActor,
    designId: String,
  ): LockedExecution {
    val design = persisted.designs[designId] ?: return serviceError(notFound(designId))
    if (!design.allows(actor, DesignAccessActionV1.READ)) return serviceError(notFound(designId))
    val systemId = design.document.catalogPin.systemId
    val target =
      catalogs.listCatalogs().singleOrNull { it.benchmark.catalogSystemId == systemId }
        ?: return serviceError(
          ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
          "catalog $systemId is not served by this runtime",
        )
    val targetPin =
      catalogs.reference(target)
        ?: return serviceError(
          ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
          "catalog $systemId has no exact served reference",
        )
    if (targetPin == design.document.catalogPin) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "design $designId already uses this pin")
    }
    return LockedExecution(
      UiBuilderServiceResponse.CatalogUpgradePreview(
        catalogUpgradePreview(design.document, target, targetPin)
      )
    )
  }

  private fun catalogUpgradePreview(
    source: DesignDocumentV1,
    target: CatalogCapabilityV1,
    targetPin: CatalogReferenceV1,
  ): CatalogUpgradePreviewV1 {
    val outcome = planCatalogUpgrade(source, target, targetPin)
    // Validated by the same validator every write goes through rather than a second opinion here:
    // a candidate this runtime would refuse to store is not a move worth offering.
    val refusal =
      catalogs.validate(outcome.candidate, target)?.let {
        CatalogUpgradeIssueV1(
          CatalogUpgradeIssueSeverityV1.ERROR,
          it.code,
          it.nodeId?.let { node -> "/nodes/$node" } ?: "/",
          it.message,
        )
      }
    val issues = outcome.issues + listOfNotNull(refusal)
    val blocked = issues.any { it.severity == CatalogUpgradeIssueSeverityV1.ERROR }
    val candidateHash = documentHash(outcome.candidate)
    return CatalogUpgradePreviewV1(
      designId = source.id,
      baseRevision = source.revision,
      sourceCatalogPin = source.catalogPin,
      targetCatalogPin = targetPin,
      sourceDocumentHash = documentHash(source),
      status =
        if (blocked) CatalogUpgradePreviewStatusV1.BLOCKED else CatalogUpgradePreviewStatusV1.READY,
      // Names this preview, not its document: two plans can reach the same document by different
      // routes, and an apply must match the plan that was looked at.
      previewDigest =
        sha256(
          listOf(
              candidateHash,
              documentHash(source),
              targetPin.systemId,
              targetPin.catalogRevision,
              targetPin.capabilityDigest,
              issues.joinToString(",") { "${it.severity}:${it.code}@${it.path}" },
            )
            .joinToString("\n")
            .encodeToByteArray()
        ),
      candidateDocument = outcome.candidate,
      candidateDocumentHash = candidateHash,
      changes = outcome.changes,
      issues = issues,
    )
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
    // Collapsed first, so a mutation lands on the record that governs rather than on a list that
    // still carries a legacy case-variant beside it — which also heals what is stored.
    var access = design.access.collapsed()
    val now = clock.millis()
    request.mutations.forEach { mutation ->
      when (mutation) {
        is GrantActorAccessMutationV1 -> {
          val target = canonicalActorId(mutation.actorId)
          if (target.isBlank() || sameActor(target, access.ownerActorId)) {
            return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "invalid actor grant target")
          }
          if (mutation.role == DesignAccessRoleV1.OWNER) {
            return serviceError(
              ServiceErrorCodeV1.BAD_REQUEST,
              "ownership changes require transferOwnership",
            )
          }
          // Everyone may be let in to look, never to change: a public design that anyone could
          // write would be a design with no owner in any sense that matters.
          if (
            target == UiBuilderPublicAccess.ANYONE_ACTOR_ID &&
              (mutation.role != DesignAccessRoleV1.VIEWER ||
                mutation.allowedActions.any { it !in UiBuilderPublicAccess.PUBLIC_ACTIONS })
          ) {
            return serviceError(
              ServiceErrorCodeV1.BAD_REQUEST,
              "a public design may only be shared as a viewer that reads and exports",
            )
          }
          val grant =
            DesignActorGrantV1(
              target,
              mutation.role,
              mutation.allowedActions.distinct(),
              canonicalActorId(actor.actorId),
              now,
            )
          access =
            access.copy(
              actorGrants = access.actorGrants.filterNot { sameActor(it.actorId, target) } + grant
            )
        }
        is RevokeActorAccessMutationV1 -> {
          if (sameActor(mutation.actorId, access.ownerActorId)) {
            return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "the owner cannot be revoked")
          }
          access =
            access.copy(
              actorGrants = access.actorGrants.filterNot { sameActor(it.actorId, mutation.actorId) }
            )
        }
        is TransferDesignOwnershipMutationV1 -> {
          val newOwner = canonicalActorId(mutation.newOwnerActorId)
          if (newOwner.isBlank() || sameActor(newOwner, access.ownerActorId)) {
            return serviceError(ServiceErrorCodeV1.BAD_REQUEST, "invalid new owner")
          }
          val formerOwner = access.ownerActorId
          val formerOwnerGrant =
            DesignActorGrantV1(
              actorId = canonicalActorId(formerOwner),
              role = DesignAccessRoleV1.EDITOR,
              allowedActions =
                listOf(
                  DesignAccessActionV1.READ,
                  DesignAccessActionV1.WRITE,
                  DesignAccessActionV1.EXPORT,
                ),
              grantedByActorId = canonicalActorId(actor.actorId),
              grantedAtEpochMillis = now,
            )
          access =
            access.copy(
              ownerActorId = newOwner,
              actorGrants =
                access.actorGrants.filterNot {
                  sameActor(it.actorId, newOwner) || sameActor(it.actorId, formerOwner)
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
    commitDesign(request.designId, updated)
    // A branch's access IS its parent's: whoever may read or write the design may read or write
    // the alternatives being explored for it, and somebody removed from it loses them too.
    val branches =
      persisted.designs.filterValues { it.branch?.parentDesignId == request.designId }.keys
    branches.forEach { branchId ->
      commitDesign(branchId, persisted.designs.getValue(branchId).copy(access = access))
    }

    val closed = mutableListOf<SubscriberMailbox>()
    (listOf(request.designId) + branches).forEach { designId ->
      runtime[designId]?.subscribers?.entries?.removeIf { (_, subscriber) ->
        val revoke = !updated.allows(subscriber.actor, DesignAccessActionV1.READ)
        if (revoke) closed += subscriber.mailbox
        revoke
      }
    }
    closed.forEach(SubscriberMailbox::close)
    return LockedExecution(UiBuilderServiceResponse.DesignAccess(request.designId, access))
  }

  /**
   * The design with every named icon's outline present and stale ones dropped. Run on the reduced
   * design so it applies to every write. Never fails a write: without a resolver or with a cold
   * cache, existing entries are kept.
   */
  private fun withIconOutlines(design: PersistedDesignV1): PersistedDesignV1 {
    val document = withIconOutlines(design.document)
    return if (document === design.document) design else design.copy(document = document)
  }

  private fun withIconOutlines(document: DesignDocumentV1): DesignDocumentV1 {
    val resolver = iconOutlines ?: return document
    val wanted = IconOutlineAssets.drawnBy(document.nodes.values)
    if (wanted.isEmpty()) return document
    val assets =
      IconOutlineAssets.withOutlines(
        document.assets,
        wanted,
        limits.maximumAssetsPerDesign,
        resolver::pathData,
      )
    return if (assets === document.assets) document else document.copy(assets = assets)
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
    val fingerprint = submissionFingerprint(json, wire)
    if (design.branch != null && submission.operationId !in design.operationOutcomes) {
      design.branchWriteRefusal(design.branch, submission)?.let { message ->
        return LockedExecution(
          UiBuilderServiceResponse.OperationOutcome(
            rejected(
              submission.operationId,
              design.document.revision,
              RejectionCodeV1.INVALID_COMMAND,
              message,
            )
          )
        )
      }
    }
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
    val reduction =
      when (val reduced = reduceAndRecord(design, actor, wire, fingerprint)) {
        is Reduced.Unrecorded ->
          return LockedExecution(UiBuilderServiceResponse.OperationOutcome(reduced.outcome))
        is Reduced.Recorded -> reduced
      }
    val recorded =
      if (design.branch != null && reduction.outcome is AcceptedOutcomeV1)
        reduction.design.copy(branchLog = reduction.design.branchLog + wire)
      else reduction.design
    commitDesign(submission.designId, recorded)
    if (reduction.outcome !is AcceptedOutcomeV1) {
      return LockedExecution(UiBuilderServiceResponse.OperationOutcome(reduction.outcome))
    }
    if (submission.isCatalogUpgradeOnly()) {
      // The write was validated against the target catalog, so the design is repaired; clear the
      // boot-time entry without a restart.
      unusableDesigns.remove(submission.designId)
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

  /** What [reduceAndRecord] produced: a design to commit, or an answer that records nothing. */
  private sealed interface Reduced {
    data class Recorded(val design: PersistedDesignV1, val outcome: CommandOutcomeV1) : Reduced

    data class Unrecorded(val outcome: RejectedOutcomeV1) : Reduced
  }

  /**
   * One submission through the reducer, with its outcome recorded and undo/tombstone budgets
   * applied — [apply] short of committing and broadcasting. Shared with branch merge.
   */
  private fun reduceAndRecord(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    wire: DesignSubmissionV1,
    fingerprint: String,
    staleness: StalenessWindow? = null,
  ): Reduced {
    val operationId = wire.operationId()
    val reduction = reduce(design, actor, wire, staleness)
    if (reduction.outcome is AcceptedOutcomeV1) {
      documentQuotaIssue(reduction.design.document, countRejection = true)?.let {
        return Reduced.Unrecorded(
          rejected(operationId, design.document.revision, RejectionCodeV1.INVALID_COMMAND, it)
        )
      }
    }
    val outcomes =
      (reduction.design.operationOutcomes +
          (operationId to OperationOutcomeRecordV1(fingerprint, reduction.outcome)))
        .entries
        .toList()
        .takeLast(limits.retainedOperationOutcomes)
        .associate { it.toPair() }
    val recorded =
      reduction.design.copy(
        operationOutcomes = outcomes,
        // The byte budget is the load-bearing bound; the subset keeps `acceptedOperations` within
        // the retained outcomes.
        acceptedOperations =
          reduction.design.acceptedOperations
            .filterKeys { it in outcomes.keys }
            .retainNewestWithinBytes(
              limits.retainedUndoBytes,
              limits.minimumRetainedUndoOperations,
              AcceptedOperationRecordV1::targetOperationId,
            ),
        tombstones =
          reduction.design.tombstones.retainNewestWithinBytes(
            limits.retainedUndoBytes,
            limits.minimumRetainedUndoOperations,
          ),
      )
    return Reduced.Recorded(recorded, reduction.outcome)
  }

  // ------------------------------------------------------------------------------------ branches

  private data class BranchExecution(
    val response: UiBuilderBranchResponse,
    val mailboxes: List<SubscriberMailbox> = emptyList(),
  )

  private fun branchError(error: UiBuilderServiceError): BranchExecution =
    BranchExecution(UiBuilderBranchResponse.Error(error))

  private fun branchError(code: ServiceErrorCodeV1, message: String): BranchExecution =
    branchError(UiBuilderServiceError(code, message))

  /**
   * The revisions of [designId] an open branch forked at, kept by retention until the branch is
   * archived or merged. Derived from branch records so they cannot disagree.
   */
  private fun pinnedRevisions(designId: String): Set<Long> =
    persisted.designs.values.mapNotNullTo(mutableSetOf()) { design ->
      design.branch
        ?.takeIf { it.parentDesignId == designId && it.status == DesignBranchStatusV1.OPEN }
        ?.forkRevision
    }

  override suspend fun executeBranch(call: UiBuilderBranchCall): UiBuilderBranchResponse {
    val named =
      when (val request = call.request) {
        is UiBuilderBranchRequest.CreateBranch -> request.designId
        is UiBuilderBranchRequest.ListBranches -> request.designId
        is UiBuilderBranchRequest.GetBranch -> request.branchId
        is UiBuilderBranchRequest.ArchiveBranch -> request.branchId
        is UiBuilderBranchRequest.MergeBranch -> request.branchId
      }
    unusableDesigns[named]?.let {
      return UiBuilderBranchResponse.Error(UiBuilderServiceError(it.code, it.reason))
    }
    val execution = lock.withLock {
      when (val request = call.request) {
        is UiBuilderBranchRequest.CreateBranch -> createBranch(call.actor, request)
        is UiBuilderBranchRequest.ListBranches -> listBranches(call.actor, request)
        is UiBuilderBranchRequest.GetBranch -> getBranch(call.actor, request.branchId)
        is UiBuilderBranchRequest.ArchiveBranch -> archiveBranch(call.actor, request.branchId)
        is UiBuilderBranchRequest.MergeBranch -> mergeBranch(call.actor, request)
      }
    }
    drain(execution.mailboxes)
    return execution.response
  }

  /** See [UiBuilderBranchRequest.CreateBranch]. */
  private fun createBranch(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderBranchRequest.CreateBranch,
  ): BranchExecution {
    val parent =
      persisted.designs[request.designId] ?: return branchError(notFound(request.designId))
    if (!parent.allows(actor, DesignAccessActionV1.READ)) {
      return branchError(notFound(request.designId))
    }
    if (!parent.allows(actor, DesignAccessActionV1.WRITE)) {
      return branchError(forbidden("branch", request.designId))
    }
    if (parent.branch != null) {
      return branchError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "design ${request.designId} is itself a branch; branch ${parent.branch.parentDesignId} " +
          "instead",
      )
    }
    val name = request.name.trim()
    if (name.isEmpty() || name.length > MAXIMUM_BRANCH_NAME_LENGTH) {
      return branchError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "a branch name is 1 to $MAXIMUM_BRANCH_NAME_LENGTH characters",
      )
    }
    val revision = request.revision ?: parent.document.revision
    val forkDocument =
      if (revision == parent.document.revision) parent.document
      else parent.revisionSnapshots.firstOrNull { it.document.revision == revision }?.document
    // The merge reduces against the parent's position state at the fork, so a revision without one
    // cannot be branched from.
    val forkPositions = parent.positionSnapshots.firstOrNull { it.revision == revision }?.positions
    if (forkDocument == null || forkPositions == null) {
      return branchError(
        UiBuilderServiceError(
          code = ServiceErrorCodeV1.SNAPSHOT_REQUIRED,
          message = "revision $revision of ${request.designId} is not retained to branch from",
          currentRevision = parent.document.revision,
          retainedFromSequence = parent.retainedSnapshotFromSequence(),
        )
      )
    }
    val branchId = request.branchId?.trim() ?: mintBranchId(request.designId)
    if (branchId.isEmpty()) {
      return branchError(ServiceErrorCodeV1.BAD_REQUEST, "branch id is blank")
    }
    persisted.designs[branchId]?.let { existing ->
      val existingBranch = existing.branch
      // A retried create: the same branch, asked for again by somebody who may see it.
      if (
        existingBranch != null &&
          existingBranch.parentDesignId == request.designId &&
          existingBranch.forkRevision == revision &&
          existingBranch.name == name &&
          existingBranch.kind == request.kind.toRecord() &&
          existing.allows(actor, DesignAccessActionV1.READ)
      ) {
        return BranchExecution(UiBuilderBranchResponse.Branch(existing.branchView()))
      }
      return branchError(ServiceErrorCodeV1.BAD_REQUEST, "design id $branchId already exists")
    }
    if (persisted.designs.size >= limits.maximumDesigns) {
      return branchError(ServiceErrorCodeV1.BAD_REQUEST, "design limit reached")
    }
    if (store.quarantineHolding(branchId) != null || branchId in unusableDesigns) {
      return branchError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "design $branchId is quarantined and must be retired before the id is reused",
      )
    }
    val now = clock.millis()
    // The fork's document under the branch id, at the fork revision (branch revisions continue the
    // parent's numbering). No home: a branch returns to the parent only by merge.
    val document =
      forkDocument.copy(
        id = branchId,
        home = null,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
      )
    val design =
      PersistedDesignV1(
        document = document,
        lastSequence = 0,
        // Inherited, and kept in step by `updateAccess`: see `UI_BUILDER_BRANCHES.md`.
        access = parent.access,
        revisionSnapshots = listOf(RevisionStateV1(document, 0)),
        positions = forkPositions,
        positionSnapshots = listOf(PositionStateV1(revision, forkPositions)),
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
        branch =
          DesignBranchRecordV1(
            parentDesignId = request.designId,
            name = name,
            ownerActorId = canonicalActorId(actor.onBehalfOfActorId ?: actor.actorId),
            status = DesignBranchStatusV1.OPEN,
            forkRevision = revision,
            forkDocumentHash = documentHash(forkDocument),
            parentCreatedAtEpochMillis = parent.createdAtEpochMillis,
            createdAtEpochMillis = now,
            kind = request.kind.toRecord(),
          ),
      )
    commitDesign(branchId, design)
    runtime[branchId] = RuntimeDesign()
    return BranchExecution(UiBuilderBranchResponse.Branch(design.branchView()))
  }

  private fun mintBranchId(parentDesignId: String): String {
    while (true) {
      val candidate =
        "$parentDesignId-branch-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
      if (candidate !in persisted.designs && store.quarantineHolding(candidate) == null) {
        return candidate
      }
    }
  }

  /** See [UiBuilderBranchRequest.ListBranches]. */
  private fun listBranches(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderBranchRequest.ListBranches,
  ): BranchExecution {
    val parent =
      persisted.designs[request.designId] ?: return branchError(notFound(request.designId))
    if (!parent.allows(actor, DesignAccessActionV1.READ)) {
      return branchError(notFound(request.designId))
    }
    val branches =
      persisted.designs.values
        .filter { it.branch?.parentDesignId == request.designId }
        .filter { request.includeClosed || it.branch?.status == DesignBranchStatusV1.OPEN }
        .filter { design ->
          request.kind.let { it == null || design.branch?.kind == it.toRecord() }
        }
        .sortedWith(
          compareByDescending<PersistedDesignV1> { it.branch?.createdAtEpochMillis }
            .thenBy { it.document.id }
        )
        .map { it.branchView() }
    return BranchExecution(UiBuilderBranchResponse.Branches(request.designId, branches))
  }

  /** See [UiBuilderBranchRequest.GetBranch]. */
  private fun getBranch(actor: AuthenticatedUiBuilderActor, branchId: String): BranchExecution {
    val design = persisted.designs[branchId]
    if (design?.branch == null || !design.allows(actor, DesignAccessActionV1.READ)) {
      return branchError(ServiceErrorCodeV1.NOT_FOUND, "branch $branchId was not found")
    }
    return BranchExecution(UiBuilderBranchResponse.Branch(design.branchView()))
  }

  /** See [UiBuilderBranchRequest.ArchiveBranch]. */
  private fun archiveBranch(actor: AuthenticatedUiBuilderActor, branchId: String): BranchExecution {
    val design = persisted.designs[branchId]
    val record = design?.branch
    if (design == null || record == null || !design.allows(actor, DesignAccessActionV1.READ)) {
      return branchError(ServiceErrorCodeV1.NOT_FOUND, "branch $branchId was not found")
    }
    val ownsBranch = actor.accessIdentities.any { sameActor(it, record.ownerActorId) }
    if (!ownsBranch && !design.allows(actor, DesignAccessActionV1.WRITE)) {
      return branchError(forbidden("archive", branchId))
    }
    when (record.status) {
      DesignBranchStatusV1.ARCHIVED ->
        return BranchExecution(UiBuilderBranchResponse.Branch(design.branchView()))
      DesignBranchStatusV1.MERGED ->
        return branchError(ServiceErrorCodeV1.BAD_REQUEST, "branch $branchId is already merged")
      DesignBranchStatusV1.OPEN -> Unit
    }
    val now = clock.millis()
    val archived =
      design.copy(
        branch =
          record.copy(
            status = DesignBranchStatusV1.ARCHIVED,
            closedAtEpochMillis = now,
            closedByActorId = actor.actorId,
          )
      )
    commitDesign(branchId, archived)
    return BranchExecution(UiBuilderBranchResponse.Branch(archived.branchView()))
  }

  /**
   * See [UiBuilderBranchRequest.MergeBranch]. Replays via [replayCommandLog] through
   * [reduceAndRecord] over a working copy of the parent; nothing commits until the whole log lands,
   * so a refusal (or a dry run) changes nothing.
   */
  private fun mergeBranch(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderBranchRequest.MergeBranch,
  ): BranchExecution {
    val branchDesign = persisted.designs[request.branchId]
    val record = branchDesign?.branch
    if (
      branchDesign == null ||
        record == null ||
        !branchDesign.allows(actor, DesignAccessActionV1.READ)
    ) {
      return branchError(ServiceErrorCodeV1.NOT_FOUND, "branch ${request.branchId} was not found")
    }
    val parentId = record.parentDesignId
    unusableDesigns[parentId]?.let {
      return branchError(UiBuilderServiceError(it.code, it.reason))
    }
    val parent = persisted.designs[parentId]
    if (parent == null || parent.createdAtEpochMillis != record.parentCreatedAtEpochMillis) {
      return branchError(
        ServiceErrorCodeV1.NOT_FOUND,
        "design $parentId, which branch ${request.branchId} forked from, no longer exists",
      )
    }
    if (!parent.allows(actor, DesignAccessActionV1.WRITE)) {
      return branchError(forbidden("merge into", parentId))
    }
    if (record.status != DesignBranchStatusV1.OPEN) {
      return branchError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "branch ${request.branchId} is ${record.status.name.lowercase()}",
      )
    }
    if (!request.dryRun && !admitMutation(actor.actorId, parentId, 1)) {
      rejectedMutationRate.incrementAndGet()
      return branchError(
        UiBuilderServiceError(
          ServiceErrorCodeV1.BAD_REQUEST,
          "mutation rate limit exceeded",
          retryable = true,
        )
      )
    }

    val loggedIds = branchDesign.branchLog.map { it.operationId() }
    (request.skipOperationIds + request.acceptOperationIds.orEmpty() - loggedIds.toSet())
      .firstOrNull()
      ?.let { unknown ->
        return branchError(
          ServiceErrorCodeV1.BAD_REQUEST,
          "operation $unknown is not in branch ${request.branchId}'s log",
        )
      }
    if (request.acceptOperationIds?.isEmpty() == true) {
      return branchError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "accepting none of branch ${request.branchId}'s commands is a reject: archive it instead",
      )
    }
    // "Accept only these" is a skip of everything else: one decision, said whichever way round is
    // shorter for the caller, and reported as the skip it is.
    val skipped =
      request.skipOperationIds +
        request.acceptOperationIds
          ?.let { accepted -> loggedIds.filterNot { it in accepted } }
          .orEmpty()
    val log = branchDesign.branchLog.filter { it.operationId() !in skipped }

    var working: PersistedDesignV1 = parent
    val committed = mutableListOf<CommittedOperationV1>()
    val authors = branchDesign.branchLog.associate { it.operationId() to it.actorId() }
    // Positions come from the base chain, staleness from the fork: every replayed command reports
    // overwriting a parent edit made since the fork.
    val staleness = StalenessWindow(since = record.forkRevision, through = parent.document.revision)
    val replay =
      replayCommandLog(record.forkRevision, log) { command, base ->
        val rebased = command.rebasedOnto(parentId, base)
        val operationId = rebased.operationId()
        if (operationId in working.operationOutcomes) {
          return@replayCommandLog ReplayAnswer.Refused(
            operationId,
            RejectionCodeV1.OPERATION_ID_REUSED.name,
            "operation id $operationId is already in $parentId's history",
          )
        }
        val fingerprint = submissionFingerprint(json, rebased)
        // Attributed to the branch author, not the merging actor, so per-author undo and conflict
        // notices work. Ids come from the service-written branch log, never a request.
        val author = AuthenticatedUiBuilderActor(rebased.actorId())
        when (val reduced = reduceAndRecord(working, author, rebased, fingerprint, staleness)) {
          is Reduced.Unrecorded -> ReplayAnswer.of(reduced.outcome)
          is Reduced.Recorded -> {
            val outcome = reduced.outcome
            if (outcome is AcceptedOutcomeV1) {
              working = reduced.design
              committed += CommittedOperationV1(rebased, outcome)
            }
            ReplayAnswer.of(outcome)
          }
        }
      }

    val merged = replay.complete
    // A suggestion is a proposal, not an alternative: accepting one archives nothing, and merging a
    // branch never archives a suggestion that happens to share its fork point.
    val siblings =
      if (!merged || record.kind != DesignBranchKindV1.BRANCH) emptyList()
      else
        persisted.designs.values
          .filter { design ->
            val sibling = design.branch
            design.document.id != request.branchId &&
              sibling != null &&
              sibling.parentDesignId == parentId &&
              sibling.forkRevision == record.forkRevision &&
              sibling.kind == DesignBranchKindV1.BRANCH &&
              sibling.status == DesignBranchStatusV1.OPEN
          }
          .map { it.document.id }
          .sorted()
    val commands =
      replay.landed.map { landed ->
        UiBuilderBranchMergeCommand(
          operationId = landed.operationId,
          actorId = authors[landed.operationId].orEmpty(),
          status = UiBuilderBranchMergeCommandStatus.APPLIED,
          baseRevision = landed.baseRevision,
          committedRevision = landed.committedRevision,
          conflicts = landed.conflicts,
        )
      } +
        listOfNotNull(
          replay.stop?.let { stop ->
            UiBuilderBranchMergeCommand(
              operationId = stop.operationId,
              actorId = authors[stop.operationId].orEmpty(),
              status = UiBuilderBranchMergeCommandStatus.REFUSED,
              baseRevision = stop.baseRevision,
              code = stop.code,
              message = stop.message,
              nodeId = stop.nodeId,
              field = stop.field,
            )
          }
        )
    val report =
      UiBuilderBranchMergeReport(
        branchId = request.branchId,
        parentDesignId = parentId,
        dryRun = request.dryRun,
        merged = merged,
        forkRevision = record.forkRevision,
        parentRevisionBefore = parent.document.revision,
        parentRevisionAfter = if (merged) working.document.revision else parent.document.revision,
        commands = commands,
        remaining = replay.stop?.remaining ?: 0,
        archivedSiblingIds = siblings,
        skippedOperationIds = loggedIds.filter { it in skipped },
      )
    if (!merged || request.dryRun) {
      return BranchExecution(UiBuilderBranchResponse.Merge(report))
    }

    val now = clock.millis()
    commitDesign(parentId, working)
    commitDesign(
      request.branchId,
      branchDesign.copy(
        branch =
          record.copy(
            status = DesignBranchStatusV1.MERGED,
            closedAtEpochMillis = now,
            closedByActorId = actor.actorId,
            mergedAtParentRevision = working.document.revision,
          )
      ),
    )
    siblings.forEach { siblingId ->
      val sibling = persisted.designs.getValue(siblingId)
      commitDesign(
        siblingId,
        sibling.copy(
          branch =
            requireNotNull(sibling.branch)
              .copy(
                status = DesignBranchStatusV1.ARCHIVED,
                closedAtEpochMillis = now,
                closedByActorId = actor.actorId,
                supersededByBranchId = request.branchId,
              )
        ),
      )
    }
    val mailboxes =
      when {
        committed.isEmpty() -> emptyList()
        committed.size <= limits.retainedCommittedOperations ->
          enqueue(
            parentId,
            UiBuilderServiceUpdate.Delta(
              ServiceDeltaV1(
                designId = parentId,
                afterSequence = parent.lastSequence,
                throughSequence = working.lastSequence,
                currentRevision = working.document.revision,
                retainedFromSequence = working.retainedFromSequence(),
                operations = committed,
              )
            ),
            working,
          )
        else ->
          catalogs.resolve(working.document.catalogPin)?.let { catalog ->
            enqueue(
              parentId,
              UiBuilderServiceUpdate.Snapshot(
                snapshot(working, actor, catalog, activePresence(parentId)).copy(access = null)
              ),
              working,
            )
          } ?: emptyList()
      }
    return BranchExecution(UiBuilderBranchResponse.Merge(report), mailboxes)
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
      return if (call.request is UiBuilderServiceRequest.ExportDocument)
        exportDocumentAdmitted(call)
      else exportAdmitted(call)
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
              retainedFromSequence = design.retainedSnapshotFromSequence(),
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
    val outcome = executeExport(pinned)
    if (outcome !is UiBuilderServiceResponse.Export) return outcome
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
      commitDesign(request.designId, updated)
    }
    return outcome
  }

  private fun executeExport(pinned: RevisionPinnedUiBuilderExport): UiBuilderServiceResponse {
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
    return UiBuilderServiceResponse.Export(artifact)
  }

  private fun exportDocumentAdmitted(call: UiBuilderServiceCall): UiBuilderServiceResponse {
    val request = call.request as UiBuilderServiceRequest.ExportDocument
    val document = request.document
    fun invalid(message: String) =
      UiBuilderServiceResponse.Error(UiBuilderServiceError(ServiceErrorCodeV1.BAD_REQUEST, message))
    if (!UiBuilderBuildFeatures.remoteCompose)
      return invalid("Remote Compose authoring is disabled in this build")
    if (
      request.format != ExportFormatV1.PNG && request.format !in RemoteDocumentExportSupport.formats
    ) {
      return invalid("supplied documents support only PNG, Remote JSON and RC export")
    }
    if (document.id.isBlank() || document.revision < 0)
      return invalid("invalid document id or revision")
    if (document.nodes.size > limits.maximumNodesPerDesign)
      return invalid("design node limit exceeded")
    documentQuotaIssue(document, countRejection = true)?.let {
      return invalid(it)
    }
    validateEnvironment(document.environment)?.let {
      return invalid(it.message)
    }
    validateTopology(document)?.let {
      return invalid(it.message)
    }
    val catalog =
      catalogs.resolve(document.catalogPin)
        ?: return UiBuilderServiceResponse.Error(
          UiBuilderServiceError(
            ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
            "catalog pin is unavailable",
          )
        )
    if (!catalog.supports(request.format))
      return invalid("catalog does not support ${request.format} export")
    // Exported without properties the catalog no longer declares; the stored document keeps them,
    // but the catalog gives them no meaning an exporter could write.
    val exported = document.withoutProperties(undeclaredProperties(document, catalog))
    catalogs.validate(exported, catalog)?.let {
      return UiBuilderServiceResponse.Error(it.toServiceError())
    }
    // The exporter receives only supplied content. No store lookup, asset resolution, audit write,
    // collaboration notification or revision allocation happens for this operation.
    return executeExport(
      RevisionPinnedUiBuilderExport(
        actor = call.actor,
        designId = document.id,
        revision = document.revision,
        documentHash = documentHash(document),
        document = exported,
        catalog = catalog,
        format = request.format,
      )
    )
  }

  /**
   * [staleness] is which earlier commits the command's `STALE_*` checks treat as concurrent.
   * Default: everything after the author's base revision. A branch merge passes the
   * fork-to-parent-head window instead; see [StalenessWindow].
   */
  private fun reduce(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    submission: DesignSubmissionV1,
    staleness: StalenessWindow? = null,
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
      is DesignCommandV1 ->
        reduceBatch(
          design,
          actor,
          submission,
          staleness ?: submission.stalenessWindow(actor, design.document.revision),
        )
      is UndoCommandV1 -> reduceUndo(design, actor, submission)
      is RedoCommandV1 -> reduceRedo(design, actor, submission)
    }
  }

  private fun reduceBatch(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    command: DesignCommandV1,
    staleness: StalenessWindow,
  ): ReductionResult {
    if (command.operations.isEmpty()) {
      return rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.INVALID_COMMAND,
        "an atomic batch requires at least one mutation",
      )
    }
    val catalogUpgrades = command.operations.filterIsInstance<CatalogUpgradeMutationV1>()
    if (catalogUpgrades.isNotEmpty()) {
      if (command.operations.size != 1) {
        return rejectedReduction(
          design,
          command.operationId,
          RejectionCodeV1.INVALID_COMMAND,
          "a catalog upgrade must be the only mutation in its batch",
        )
      }
      return reduceCatalogUpgrade(design, actor, command, catalogUpgrades.single())
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
    // Resolved first: each write is validated against the catalog as it is applied.
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
          actor,
          basePositions = batchPositions,
          index,
          catalog,
          staleness,
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
    // Tolerate only undeclared values this design already carried; new or rewritten undeclared
    // properties stay in the probe and are rejected by `validate`.
    val tolerated = undeclaredProperties(design.document, catalog)
    catalogs.validate(working.document.withoutProperties(tolerated), catalog)?.let {
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

  private fun reduceCatalogUpgrade(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    command: DesignCommandV1,
    mutation: CatalogUpgradeMutationV1,
  ): ReductionResult {
    val source = design.document
    fun reject(message: String) =
      rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.INVALID_COMMAND,
        message,
      )
    if (command.baseRevision != source.revision) {
      return rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.REVISION_MISMATCH,
        "a catalog upgrade requires the current revision",
      )
    }
    if (mutation.previewDigest.startsWith(RESTORE_DIGEST_PREFIX)) {
      return reduceRestore(design, actor, command, mutation)
    }
    if (mutation.sourceCatalogPin != source.catalogPin) {
      return reject("catalog upgrade source pin does not match the stored design")
    }
    if (mutation.sourceCatalogPin.systemId != mutation.targetCatalogPin.systemId) {
      return reject("a catalog upgrade cannot change the catalog system id")
    }
    if (mutation.sourceDocumentHash != documentHash(source)) {
      return reject("catalog upgrade source document hash does not match the stored design")
    }
    val target =
      catalogs.resolve(mutation.targetCatalogPin)
        ?: return rejectedReduction(
          design,
          command.operationId,
          RejectionCodeV1.INVALID_DOCUMENT,
          "target catalog is not served by this runtime",
        )
    val preview = catalogUpgradePreview(source, target, mutation.targetCatalogPin)
    if (preview.status != CatalogUpgradePreviewStatusV1.READY) {
      return reject("catalog upgrade preview is blocked")
    }
    if (
      preview.sourceDocumentHash != mutation.sourceDocumentHash ||
        preview.candidateDocumentHash != mutation.targetDocumentHash ||
        preview.previewDigest != mutation.previewDigest
    ) {
      return reject("catalog upgrade no longer matches its preview")
    }
    val candidate =
      preview.candidateDocument ?: return reject("catalog upgrade produced no document")
    val compensationTarget = mutation.compensatesCatalogUpgradeOperationId
    if (compensationTarget != null) {
      val original =
        design.acceptedOperations[compensationTarget]
          ?: return reject("catalog upgrade rollback names an unknown operation")
      val originalChange =
        original.changes.singleOrNull() as? CatalogUpgradeChangeRecordV1
          ?: return reject("catalog upgrade rollback target is not a catalog upgrade")
      if (original.compensatedBy != null) {
        return reject("catalog upgrade rollback target is already compensated")
      }
      if (
        originalChange.sourceCatalogPin != mutation.targetCatalogPin ||
          originalChange.targetCatalogPin != mutation.sourceCatalogPin ||
          originalChange.sourceDocumentHash != mutation.targetDocumentHash ||
          originalChange.targetDocumentHash != mutation.sourceDocumentHash
      ) {
        return reject("catalog upgrade rollback does not reverse its target operation")
      }
    }
    return accept(
      design,
      actor,
      command,
      WorkingDesign(candidate, design.tombstones, design.positions),
      listOf(
        CatalogUpgradeChangeRecordV1(
          sourceCatalogPin = mutation.sourceCatalogPin,
          targetCatalogPin = mutation.targetCatalogPin,
          sourceDocumentHash = mutation.sourceDocumentHash,
          targetDocumentHash = mutation.targetDocumentHash,
        )
      ),
      emptyList(),
      targetOperationId = compensationTarget,
      targetUndoOperationId = null,
    )
  }

  /**
   * A restore, recorded as a whole-document replacement ([RESTORE_DIGEST_PREFIX] plus revision).
   * The retained revision is looked up again and its hash must match, so only a revision this
   * design really had can be restored.
   */
  private fun reduceRestore(
    design: PersistedDesignV1,
    actor: AuthenticatedUiBuilderActor,
    command: DesignCommandV1,
    mutation: CatalogUpgradeMutationV1,
  ): ReductionResult {
    val source = design.document
    fun reject(message: String) =
      rejectedReduction(design, command.operationId, RejectionCodeV1.INVALID_COMMAND, message)
    val revision =
      mutation.previewDigest.removePrefix(RESTORE_DIGEST_PREFIX).toLongOrNull()
        ?: return reject("restore names no revision")
    val retained =
      design.revisionSnapshots.firstOrNull { it.document.revision == revision }?.document
        ?: return reject("revision $revision is no longer retained")
    if (
      mutation.sourceDocumentHash != documentHash(source) ||
        mutation.targetDocumentHash != documentHash(retained) ||
        mutation.sourceCatalogPin != source.catalogPin ||
        mutation.targetCatalogPin != retained.catalogPin ||
        mutation.compensatesCatalogUpgradeOperationId != null
    ) {
      return reject("restore does not match the design it would replace")
    }
    if (revision == source.revision) return reject("revision $revision is already current")
    catalogs.resolve(retained.catalogPin)
      ?: return rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.INVALID_DOCUMENT,
        "revision $revision's catalog is not served by this runtime",
      )
    // Content from the past, identity and clock from now: [accept] stamps the new revision.
    val restored =
      retained.copy(
        title = source.title,
        revision = source.revision,
        createdAtEpochMillis = source.createdAtEpochMillis,
        updatedAtEpochMillis = source.updatedAtEpochMillis,
      )
    return accept(
      design,
      actor,
      command,
      // Positions are derived afresh, as for a created design; restored nodes leave the tombstones.
      WorkingDesign(restored, design.tombstones - restored.nodes.keys, derivePositions(restored)),
      listOf(
        CatalogUpgradeChangeRecordV1(
          sourceCatalogPin = mutation.sourceCatalogPin,
          targetCatalogPin = mutation.targetCatalogPin,
          sourceDocumentHash = mutation.sourceDocumentHash,
          targetDocumentHash = mutation.targetDocumentHash,
        )
      ),
      emptyList(),
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
    if (target.changes.any { it is CatalogUpgradeChangeRecordV1 }) {
      return rejectedReduction(
        design,
        command.operationId,
        RejectionCodeV1.INVALID_COMMAND,
        "catalog upgrades are reversed by a previewed catalog rollback",
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
    // Outlines resolve here, on the accepted candidate, because everything below (canonical bytes,
    // hash, retained snapshot) must describe the same document.
    val document =
      withIconOutlines(working.document).copy(revision = revision, updatedAtEpochMillis = now)
    val canonical = documentCanonicalBytes(document)
    val retained = limits.retainedRevisionsFor(documentRetentionBytes(document))
    val outcome =
      AcceptedOutcomeV1(
        submission.operationId(),
        revision,
        sequence,
        sha256(canonical),
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
    if (submission is DesignCommandV1 && targetOperationId != null) {
      accepted =
        accepted +
          (targetOperationId to
            accepted.getValue(targetOperationId).copy(compensatedBy = record.operationId))
    }
    val committed = CommittedOperationV1(submission, outcome)
    val history = (design.history + committed).takeLast(limits.retainedCommittedOperations)
    // An open branch's fork point is pinned: it stays retained however far the design moves on, so
    // the merge that replays from it is never refused for the age of its base alone.
    val pinned = pinnedRevisions(design.document.id)
    val snapshots =
      (design.revisionSnapshots + RevisionStateV1(document, sequence)).retainedRevisions(
        retained,
        pinned,
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
    val positionSnapshots =
      (design.positionSnapshots + PositionStateV1(revision, working.positions)).retainedPositions(
        retained,
        pinned,
      )
    val updated =
      design.copy(
        document = document,
        lastSequence = sequence,
        history = history,
        revisionSnapshots = snapshots,
        positions = working.positions,
        positionSnapshots = positionSnapshots,
        acceptedOperations = accepted,
        // Pruned against the oldest revision a submission can still name (the oldest retained
        // position snapshot), so "nobody wrote this" never means "we no longer know".
        conflictTouches =
          (design.conflictTouches +
              ConflictTouchRecordV1(
                revision,
                changes.flatMap(ChangeRecordV1::touchKeys).toSet(),
                actorId = actor.actorId.takeIf { submission is DesignCommandV1 },
                clientId = (submission as? DesignCommandV1)?.clientId,
                authorSawRevision = (submission as? DesignCommandV1)?.authorSawRevision(),
              ))
            .filter { it.committedRevision >= positionSnapshots.first().revision },
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
    actor: AuthenticatedUiBuilderActor,
    basePositions: Map<String, StableNodePositionV1>,
    index: Int,
    catalog: CatalogCapabilityV1,
    staleness: StalenessWindow,
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
          // A node arrives with empty slots; children are placed by their own `location`. A
          // non-empty slot list is refused by name since resending can never succeed.
          mutation.node.slots.forEach { (slot, children) ->
            children.firstOrNull()?.let { child ->
              fail(
                RejectionCodeV1.INVALID_COMMAND,
                "slot `$slot` of `${mutation.node.id}` names `$child`, which " +
                  (if (child in working.document.nodes) "is already placed elsewhere"
                  else "does not exist") +
                  ": insert `${mutation.node.id}` with its slots empty, then insert or move each " +
                  "child with a location naming that slot",
                index,
                mutation.node.id,
                "slots.$slot",
              )
            }
          }
          // An insert writes every property, so each is checked as a `setProperty` would be.
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
            if (staleness.stale(original, touchKey("s", mutation.nodeId)))
              listOf(
                CommandConflictV1(
                  ConflictCodeV1.STALE_MOVE,
                  mutation.nodeId,
                  overwrittenRevision = staleness.through,
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
            staleness,
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
            staleness,
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
              staleness.overwritten(original, touchKey("e", environmentField.name))
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
          // Another actor rewriting this node's chain since the base revision is a conflict, not a
          // refusal: the chain is a value and the last writer wins.
          val conflicts =
            if (staleness.stale(original, touchKey("m", mutation.nodeId)))
              listOf(
                CommandConflictV1(
                  // The wire has no modifier-specific code; the browser reducer uses the same one.
                  ConflictCodeV1.STALE_PROPERTY_WRITE,
                  mutation.nodeId,
                  MODIFIERS_FIELD,
                  staleness.through,
                )
              )
            else emptyList()
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            ModifierChangeV1(mutation.nodeId, before, mutation.modifiers),
            conflicts,
          )
        }
        is SetComponentArgumentsMutationV1 -> {
          val node =
            working.document.nodes[mutation.nodeId]
              ?: fail(RejectionCodeV1.UNKNOWN_NODE, "unknown node", index, mutation.nodeId)
          // The contract's obligation: only an instance passes arguments, and a node that is not
          // one has nowhere to keep them.
          val placement =
            node.component
              ?: fail(
                RejectionCodeV1.INVALID_COMMAND,
                "${mutation.nodeId} is not a component placement",
                operationIndex = index,
                nodeId = mutation.nodeId,
                field = COMPONENT_ARGUMENTS_FIELD,
              )
          val before = placement.arguments
          val document =
            working.document.copy(
              nodes =
                working.document.nodes +
                  (node.id to node.copy(component = placement.copy(arguments = mutation.arguments)))
            )
          // The same staleness question the modifier lane asks: the map is one value, and the last
          // writer wins with a conflict rather than a refusal.
          val conflicts =
            if (staleness.stale(original, touchKey("a", mutation.nodeId)))
              listOf(
                CommandConflictV1(
                  ConflictCodeV1.STALE_PROPERTY_WRITE,
                  mutation.nodeId,
                  COMPONENT_ARGUMENTS_FIELD,
                  staleness.through,
                )
              )
            else emptyList()
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            ComponentArgumentsChangeV1(mutation.nodeId, before, mutation.arguments),
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
          // Redefining a variable that is read elsewhere could narrow it under a `selectOrClear` or
          // `toggle`; refused whole.
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
            staleStateWrites(original, staleness, mutation.name),
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
          // A variable still referenced cannot be removed: the design would render blank silently.
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
            staleStateWrites(original, staleness, mutation.name),
          )
        }
        is DeclareComponentMutationV1 -> {
          if (mutation.componentKey.isBlank()) {
            fail(
              RejectionCodeV1.INVALID_COMMAND,
              "component key is blank",
              operationIndex = index,
            )
          }
          // The body must exist before the declaration names it. In an import batch, the body's
          // `insertNode`s precede the declaration, so the root is already in `working.document`.
          if (mutation.declaration.root !in working.document.nodes) {
            fail(
              RejectionCodeV1.INVALID_DOCUMENT,
              "component ${mutation.componentKey} names a body root " +
                "`${mutation.declaration.root}` this design does not hold",
              operationIndex = index,
              field = mutation.declaration.root,
            )
          }
          val before = working.document.components[mutation.componentKey]
          val document = working.document.withComponent(mutation.componentKey, mutation.declaration)
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            ComponentChangeV1(mutation.componentKey, before, mutation.declaration),
            staleComponentWrites(original, staleness, mutation.componentKey),
          )
        }
        is RemoveComponentMutationV1 -> {
          val before =
            working.document.components[mutation.componentKey]
              ?: fail(
                RejectionCodeV1.INVALID_COMMAND,
                "this design declares no component ${mutation.componentKey}",
                operationIndex = index,
                field = mutation.componentKey,
              )
          // A placement may still name the key and would draw nothing. The body is not checked: an
          // unnamed body is a legitimate intermediate state.
          working.document.nodes.values
            .firstOrNull { it.component?.componentKey == mutation.componentKey }
            ?.let { placement ->
              fail(
                RejectionCodeV1.INVALID_DOCUMENT,
                "node ${placement.id} still places component ${mutation.componentKey}",
                operationIndex = index,
                nodeId = placement.id,
                field = mutation.componentKey,
              )
            }
          val document = working.document.withComponent(mutation.componentKey, null)
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            ComponentChangeV1(mutation.componentKey, before, null),
            staleComponentWrites(original, staleness, mutation.componentKey),
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
          // `actions` has no wire default, so unbind arrives as an empty list; store it as absent.
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
          val readableSiblingDesigns =
            persisted.designs
              .filterValues { candidate ->
                candidate.document.catalogPin.systemId == document.catalogPin.systemId &&
                  candidate.allows(actor, DesignAccessActionV1.READ)
              }
              .keys
          mutation.actions
            .filterIsInstance<NavigatePageActionV1>()
            .firstOrNull { it.pageKey !in readableSiblingDesigns }
            ?.let { action ->
              fail(
                RejectionCodeV1.INVALID_DOCUMENT,
                "${mutation.event} navigates to unknown design ${action.pageKey}",
                operationIndex = index,
                nodeId = mutation.nodeId,
                field = eventBindingField(mutation.event),
              )
            }
          // The same staleness question the property and modifier lanes ask, one field over: a
          // binding is a value, and the last writer wins.
          val conflicts =
            if (staleness.stale(original, touchKey("b", mutation.nodeId, mutation.event)))
              listOf(
                CommandConflictV1(
                  ConflictCodeV1.STALE_PROPERTY_WRITE,
                  mutation.nodeId,
                  eventBindingField(mutation.event),
                  staleness.through,
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
   * One property write, for `setProperty` and `removeNodeProperty` alike: a [NullValueV1] value
   * unsets the property, so there is one removal path.
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
    staleness: StalenessWindow,
  ): MutationResult {
    val node =
      working.document.nodes[nodeId]
        ?: fail(RejectionCodeV1.UNKNOWN_NODE, "unknown node", index, nodeId)
    if (property.isBlank()) {
      fail(RejectionCodeV1.INVALID_PROPERTY, "property name is blank", index, nodeId)
    }
    val beforePresent = property in node.properties
    val before = node.properties[property]
    // A null value unsets rather than stores a null (which validation and export refuse).
    // Required-ness is checked by the batch's catalog validation; value rules apply only to a set.
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
      if (staleness.stale(original, touchKey("p", nodeId, property)))
        listOf(
          CommandConflictV1(
            ConflictCodeV1.STALE_PROPERTY_WRITE,
            nodeId,
            property,
            staleness.through,
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
          is ComponentArgumentsChangeV1 -> {
            val node =
              working.document.nodes[change.nodeId]
                ?: fail(
                  RejectionCodeV1.UNSAFE_COMPENSATION,
                  "placement no longer exists",
                  nodeId = change.nodeId,
                )
            val placement =
              node.component
                ?: fail(
                  RejectionCodeV1.UNSAFE_COMPENSATION,
                  "node no longer places a component",
                  nodeId = change.nodeId,
                )
            val expected = if (undo) change.after else change.before
            if (placement.arguments != expected) {
              fail(
                RejectionCodeV1.UNSAFE_COMPENSATION,
                "arguments changed after the target operation",
                nodeId = change.nodeId,
                field = COMPONENT_ARGUMENTS_FIELD,
              )
            }
            val target = if (undo) change.before else change.after
            working =
              working.copy(
                document =
                  working.document.copy(
                    nodes =
                      working.document.nodes +
                        (node.id to node.copy(component = placement.copy(arguments = target)))
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
            // Re-declaring is only safe if nothing has since come to read it.
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
          is ComponentChangeV1 -> {
            val expected = if (undo) change.after else change.before
            if (working.document.components[change.componentKey] != expected) {
              fail(
                RejectionCodeV1.UNSAFE_COMPENSATION,
                "component declaration changed after the target operation",
                field = change.componentKey,
              )
            }
            val target = if (undo) change.before else change.after
            // Taking a declaration out is checked on the finished compensation below (undoing "make
            // component" removes the declaration before the placement). Putting one back must still
            // name a body this document holds.
            if (target != null && target.root !in working.document.nodes) {
              fail(
                RejectionCodeV1.UNSAFE_COMPENSATION,
                "component ${change.componentKey} names a body root `${target.root}` " +
                  "this design no longer holds",
                field = target.root,
              )
            }
            working =
              working.copy(document = working.document.withComponent(change.componentKey, target))
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
          is CatalogUpgradeChangeRecordV1 ->
            fail(
              RejectionCodeV1.UNSAFE_COMPENSATION,
              "catalog upgrades require a previewed catalog rollback",
            )
        }
      }
      // Undoing a `declareComponent` that has since been instantiated would leave a placement
      // drawing nothing; mirrors the state-variable check.
      working.document.nodes.values
        .firstOrNull { node ->
          node.component?.componentKey?.let { it !in working.document.components } == true
        }
        ?.let { placement ->
          fail(
            RejectionCodeV1.UNSAFE_COMPENSATION,
            "node ${placement.id} places component ${placement.component?.componentKey}",
            nodeId = placement.id,
            field = placement.component?.componentKey,
          )
        }
      validateTopology(working.document)?.let { throw ReductionFailure(it) }
      val catalog =
        catalogs.resolve(working.document.catalogPin)
          ?: fail(RejectionCodeV1.INVALID_DOCUMENT, "catalog pin is unavailable")
      // Same tolerance as `reduceBatch`, so edits made before the catalog moved can still be
      // undone.
      val tolerated = undeclaredProperties(design.document, catalog)
      catalogs.validate(working.document.withoutProperties(tolerated), catalog)?.let {
        throw ReductionFailure(it.toRejection())
      }
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
      access = design.access.collapsed().takeIf { design.ownedBy(actor) },
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

  override fun adminDesignSummary(designId: String): UiBuilderAdminDesignSummary? = lock.withLock {
    persisted.designs[designId]?.let(::adminSummaryOf)
  }

  override fun adminListDesigns(): List<UiBuilderAdminDesignSummary> = lock.withLock {
    persisted.designs.values
      .sortedWith(compareBy({ it.createdAtEpochMillis }, { it.document.id }))
      .map(::adminSummaryOf)
  }

  /** One stored design as the operator's view of it. Callers hold [lock]. */
  private fun adminSummaryOf(design: PersistedDesignV1): UiBuilderAdminDesignSummary =
    UiBuilderAdminDesignSummary(
      designId = design.document.id,
      title = design.document.title,
      revision = design.document.revision,
      catalogPin = design.document.catalogPin,
      ownerActorId = design.access.ownerActorId,
      collaborators =
        design.access.effectiveGrants().count {
          !sameActor(it.actorId, design.access.ownerActorId)
        },
      createdAtEpochMillis = design.createdAtEpochMillis,
      updatedAtEpochMillis = design.updatedAtEpochMillis,
      activeSubscribers = runtime[design.document.id]?.subscribers?.size ?: 0,
    )

  override fun adminUnreadableDesigns(): Set<String> =
    unusableDesigns.filterValues { it.storeQuarantine }.keys.toSet()

  override fun adminUnusableDesigns(): Map<String, String> =
    unusableDesigns.mapValues { (_, unusable) ->
      unusable.reason
    }

  override fun adminDegradedDesigns(): Map<String, String> = lock.withLock { degradedDesigns() }

  private fun degradedDesigns(): Map<String, String> =
    persisted.designs.entries
      .asSequence()
      .filter { (designId, _) -> designId !in unusableDesigns }
      .mapNotNull { (designId, design) ->
        val catalog = catalogs.resolve(design.document.catalogPin) ?: return@mapNotNull null
        val stale = undeclaredProperties(design.document, catalog)
        if (stale.isEmpty()) return@mapNotNull null
        val details =
          stale.entries
            .sortedBy { it.key }
            .joinToString("; ") { (nodeId, properties) ->
              "node `$nodeId`: ${properties.keys.sorted().joinToString(", ") { "`$it`" }}"
            }
        designId to "catalog no longer declares properties on $details"
      }
      .toMap()

  /**
   * The stored document as pretty JSON, for a person to edit. Consults neither [catalogs] nor
   * [limits], so designs held back by them can still be copied out.
   */
  override fun adminDesignDocument(designId: String): String? = lock.withLock {
    val document = persisted.designs[designId]?.document ?: return null
    PersistentUiBuilderServiceAdminJson.json.encodeToString(DesignDocumentV1.serializer(), document)
  }

  /**
   * Replace a quarantined design's document with a repaired one, checked against the conditions
   * that quarantined it. History is replaced and the sequence continues upward.
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
      // The same check the original failed: everything loaded is either servable or named as not.
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
      commitDesign(designId, repaired)
      unusableDesigns.remove(designId)
      runtime.getOrPut(designId) { RuntimeDesign() }
      UiBuilderAdminRepair.Repaired(designId, document.revision, unusable.reason)
    }

  override fun adminDeleteDesign(designId: String): Boolean {
    val closed: List<SubscriberMailbox> = lock.withLock {
      // An unreadable design is not in the map but is still on disk; retiring it must keep working.
      if (designId !in persisted.designs) {
        if (unusableDesigns[designId]?.storeQuarantine != true) return false
        store.remove(designId)
        unusableDesigns.remove(designId)
        quarantinedDesigns.remove(designId)
        return@withLock emptyList()
      }
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
    removeDesign(designId)
    // The design is gone, so its quarantine goes with it: leaving the entry would answer this id
    // with a catalog error rather than "not found", and would follow a re-created design here.
    unusableDesigns.remove(designId)
    // Never populated for a design that loaded, by the store's own invariant — cleared so the
    // invariant survives whatever wrote both.
    quarantinedDesigns.remove(designId)
    val removed = runtime.remove(designId)
    mutationBuckets.keys.removeIf { (_, bucketDesignId) -> bucketDesignId == designId }
    return removed?.subscribers?.values?.map { it.mailbox }.orEmpty()
  }

  /**
   * Stores one design's new value, and only that design's. The store gets before and after so it
   * writes only what differs, keeping an edit's cost proportional to that design.
   */
  private fun commitDesign(designId: String, updated: PersistedDesignV1) {
    store.commit(designId, persisted.designs[designId], updated)
    persisted = persisted.copy(designs = persisted.designs + (designId to updated))
  }

  private fun removeDesign(designId: String) {
    store.remove(designId)
    persisted = persisted.copy(designs = persisted.designs - designId)
  }

  /**
   * Explicitly upgrades a validated v1 envelope to v2 with an envelope-level catalog-pin manifest.
   * No migration is attempted during startup. The storage must retain the exact v1 generation and
   * support explicit restore; a failed durable readback is rolled back before this method fails.
   *
   * The per-design store migrates on open, so on such a host this answers "already at the latest".
   */
  public fun migratePersistenceToLatest(): UiBuilderPersistenceMigrationResult = lock.withLock {
    val legacy =
      store as? LegacyStateStorageDesignStore
        ?: return UiBuilderPersistenceMigrationResult(
          migrated = false,
          fromFormat = FileUiBuilderDesignStore.STORE_FORMAT,
          toFormat = FileUiBuilderDesignStore.STORE_FORMAT,
          persistedBytes = (store.usage()?.bytes ?: 0L).toInt(),
        )
    val result = legacy.migrateToLatest()
    if (result.migrated) persistenceMigrations.incrementAndGet()
    result
  }

  private fun serviceError(code: ServiceErrorCodeV1, message: String): LockedExecution =
    serviceError(UiBuilderServiceError(code, message))

  private fun serviceError(error: UiBuilderServiceError): LockedExecution =
    LockedExecution(UiBuilderServiceResponse.Error(error))

  public companion object {
    private val json = Json { encodeDefaults = true }
  }
}

private fun UiBuilderSubmission.isCatalogUpgradeOnly(): Boolean =
  this is UiBuilderSubmission.Batch && operations.singleOrNull() is CatalogUpgradeMutationV1

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

/**
 * Whether this recorded submission is [actor]'s restore of [revision] — the only record a
 * [UiBuilderServiceRequest.RestoreRevision] retry may replay. Read off the fingerprint.
 */
private fun OperationOutcomeRecordV1.isRestoreOf(
  revision: Long,
  actor: AuthenticatedUiBuilderActor,
): Boolean = runCatching {
  val command = Json.parseToJsonElement(fingerprint).jsonObject
  val digest =
    command["operations"]
      ?.jsonArray
      ?.singleOrNull()
      ?.jsonObject
      ?.get("previewDigest")
      ?.jsonPrimitive
      ?.content
  command["actorId"]?.jsonPrimitive?.content == actor.actorId &&
    command["clientId"]?.jsonPrimitive?.content == RESTORE_CLIENT_ID &&
    digest == RESTORE_DIGEST_PREFIX + revision
}
  .getOrDefault(false)

/** The client id a restore is recorded under; nothing else submits as it. */
private const val RESTORE_CLIENT_ID = "history-restore"

/**
 * Marks a whole-document replacement as a restore of the revision that follows it, rather than a
 * previewed catalog upgrade; see `reduceRestore`.
 */
private const val RESTORE_DIGEST_PREFIX = "restore-revision:"

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

private class ReductionFailure(val value: RejectedOutcomeV1) :
  IllegalArgumentException(value.message) {
  fun rejection(operationId: String, revision: Long): RejectedOutcomeV1 =
    value.copy(operationId = operationId, currentRevision = revision)
}

internal fun fail(
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

internal fun rejected(
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
