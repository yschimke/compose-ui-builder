@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.protocol.*
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
import kotlinx.serialization.json.encodeToJsonElement

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
  /**
   * The most whole-document revisions one design may retain.
   *
   * This was 1,025, and a retained revision is a **whole copy** of the document and of the position
   * map. A 40-node design serializes to about 9.5 KB and therefore occupied 11.5 MB of the one
   * state file — 99.9% of a store whose live documents were 0.1% of it — which is how
   * `preview.coo.ee` reached 24.5 MB against a 32 MiB ceiling with a handful of designs and nothing
   * wrong with any of them (yschimke/compose-preview-server#568). Retention depth is not a protocol
   * promise: `SNAPSHOT_REQUIRED` and the retained-from floor beside it exist precisely so the
   * service can say how far back it still goes, which leaves this free to be a number chosen for
   * the sizes designs actually reach. The store this is the stopgap for is
   * `docs/design/UI_BUILDER_STATE_STORAGE.md`.
   */
  val retainedRevisionSnapshots: Int = 128,
  /**
   * The byte budget those retained revisions share, which binds first when documents are large.
   *
   * A count alone bounds nothing: at `maximumSerializedDocumentBytes` a design retaining 128
   * revisions would want a gigabyte. The depth actually used is this budget divided by the size of
   * the document being retained — measured, not guessed, from the canonical bytes the commit
   * already hashes — clamped between [minimumRetainedRevisionSnapshots] and
   * [retainedRevisionSnapshots]. Position snapshots follow the same depth and cost a fraction of
   * it.
   */
  val retainedRevisionBytes: Long = 2L * 1_024 * 1_024,
  /**
   * The depth [retainedRevisionBytes] may never cut below.
   *
   * Below some depth collaboration breaks rather than degrades: a client editing against a
   * `baseRevision` needs that revision's position snapshot to rebase onto, and undo replays through
   * retained state. So a design whose documents are large enough to exhaust the budget keeps this
   * many anyway and is reported through the storage gauge instead — still strictly better than the
   * 1,025 the same design would have kept before.
   *
   * Never raises [retainedRevisionSnapshots]: a caller that deliberately sets a shallow ceiling
   * means it, and a floor above it is read as "as deep as the ceiling allows" rather than as a
   * contradiction to refuse construction over.
   */
  val minimumRetainedRevisionSnapshots: Int = 32,
  /**
   * The byte budget one design's undo state may hold, applied to `acceptedOperations` and to
   * `tombstones` separately.
   *
   * Measuring the live store is what put this here. The guess was that retained revisions held the
   * bytes; they held 35% of it. Undo bookkeeping held **55%** — `acceptedOperations` alone was
   * 29.9% — and one 386-node design spent 4.29 MB across **ten** accepted operations, about 430 KB
   * each. The cause is structural: `StructureChangeV1` carries `before` and `after` as whole node
   * subtrees, so one edit near the root of a large design stores that subtree twice, and
   * `acceptedOperations` was bounded only by [retainedOperationOutcomes] — a count of 4,096 with no
   * relation to how big a record is. Four thousand records at that size is a design that alone
   * exceeds any ceiling, and nothing stood between the store and it.
   *
   * A count cannot bound this because the records differ in size by three orders of magnitude. The
   * budget is walked newest-first and stops as soon as it is exceeded, so the work one commit does
   * is proportional to the budget rather than to the history behind it.
   */
  val retainedUndoBytes: Long = 4L * 1_024 * 1_024,
  /**
   * Undo steps kept regardless of [retainedUndoBytes], so undo never becomes unavailable.
   *
   * Pruning past this point degrades rather than breaks: `undo` and `redo` resolve their target
   * through a lookup that answers `UNKNOWN_OPERATION` when it is gone, and a `restoreNode` whose
   * tombstone has aged out is refused with `DELETED_NODE`. What a designer loses is depth, and only
   * on a design whose individual operations are large enough to spend the budget.
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
   * Resolves the outline of an icon a design names, so the design can carry its own picture.
   *
   * Null on a host with no icon source, which leaves the registry empty and every lane exactly as
   * it was. Hosts that have one fill it here rather than in the editor, because the editor is not
   * the only thing that writes: `ui_builder_apply` writes an icon over MCP, a `CreateDesign` can
   * carry one from the start, and such a design can be exported or natively previewed before
   * anybody opens it.
   */
  private val iconOutlines: IconOutlineResolver?,
) :
  UiBuilderServicePort, UiBuilderServiceDiagnosticsSource, UiBuilderAdminPort, UiBuilderAssetPort {

  /**
   * The constructor this class published before it learned about icon outlines, defaults and all.
   *
   * Both halves of the released shape have to come back, which is the part the first attempt got
   * wrong. A Kotlin default argument compiles into *two* JVM constructors — the plain
   * seven-parameter one and a synthetic `(…, int, DefaultConstructorMarker)` that a caller omitting
   * a defaulted argument invokes — and adding an eighth parameter with a default replaces both.
   * Restoring only the plain one still leaves `NoSuchMethodError` for every consumer compiled
   * against `PersistentUiBuilderService(store, catalogs, exporter)`.
   *
   * So the defaults live here rather than on the primary constructor: this emits exactly the two
   * descriptors 3.24 published, and the primary — which now has no defaults at all — adds the
   * eight-parameter one beside them without touching either.
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
   * The single-file storage this service was built on, behind the per-design port.
   *
   * Kept for the hosts that never had a directory to write into — an in-memory storage, a test, a
   * caller outside this repository — and for `migratePersistenceToLatest`, which is about that
   * format and only that format. A host with a state directory opens [UiBuilderDesignStateStore]
   * instead, and pays per design rather than per store on every edit.
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

  private val lock = ReentrantLock()
  private val store: UiBuilderDesignStore = designStore.store
  private val loadedPersistence = store.load()
  private var persisted: PersistedServiceV1 =
    PersistedServiceV1(loadedPersistence.designs.mapValues { (_, design) -> design.rePinned() })
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
   * What the durable storage holds against its ceiling, or null when it reports neither.
   *
   * Read where every other gauge is read, under the service lock, and never allowed to fail a
   * status route: a storage that throws while being asked how big it is reports nothing rather than
   * taking down the answer it is one row of.
   */
  private val storageUsage: UiBuilderStorageUsage?
    get() = runCatching { store.usage() }.getOrNull()

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
   * What it must not become is a one-way door. A quarantined design is still its owner's content as
   * much as the operator's: the header a quarantine carries names who owns it, so the owner can
   * delete it from the same file manager that lists it, and `adminDesignDocument` reads the stored
   * document out regardless of whether it can be served — retiring it with [adminDeleteDesign] is a
   * choice rather than the only move left.
   *
   * The line this does **not** cross is integrity. A state file whose checksum does not match, that
   * is truncated, or that declares a format this build cannot read is still refused by the storage
   * layer before any of this runs, and `restoreBackup` is the recovery. Trusting a file that failed
   * those checks would be worse than not starting; carrying a design the catalog outgrew is not.
   */
  private data class UnusableDesign(
    val code: ServiceErrorCodeV1,
    val reason: String,
    /**
     * True when the *store* could not read this design, rather than the catalog refusing a document
     * it read fine. The two are unusable for different reasons and recover differently: a document
     * the catalog outgrew can be downloaded and repaired, and one the store cannot decode can only
     * be retired.
     */
    val storeQuarantine: Boolean = false,
    /**
     * True when the document is sound and the CATALOG is what moved — the pin names a revision this
     * deployment no longer serves, or the served one refuses a document it read fine.
     *
     * The distinction earns its keep in [execute]: a design in this state may still be asked what
     * moving it to another catalog would cost, because that is the only repair it has. A document
     * that is itself broken cannot be repaired by a catalog move and stays refused.
     */
    val catalogFault: Boolean = false,
  )

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
        .toMap() +
        // A design whose own files could not be read. The single-file store could not have this
        // entry: its checksum covered every design at once, so one bad byte was the whole lane
        // rather than one design. Reported like any other unusable design, and repaired the same
        // way — by an operator who can now see which one it is.
        loadedPersistence.quarantined.mapValues { (_, record) ->
          UnusableDesign(
            ServiceErrorCodeV1.INTERNAL,
            "stored design cannot be read: ${record.reason}",
            storeQuarantine = true,
          )
        }
    )

  /**
   * The designs the store could not read, by the id each is reported under, with what the store
   * still knows about them.
   *
   * [unusableDesigns] answers "may this request name this design"; this answers "who does this
   * quarantined design belong to, and what was it called". The header a quarantine carries is the
   * access record read at load, which is what lets [delete] and [list] treat the design as its
   * owner's rather than an operator's orphan; a record without one (a header this build could not
   * read) has nobody left to check against and stays the operator's.
   *
   * Written under [lock] only — loaded once, removed by the two paths that retire a quarantine.
   */
  private val quarantinedDesigns: MutableMap<String, StoredQuarantineV3> =
    ConcurrentHashMap(loadedPersistence.quarantined)

  /**
   * The same design, pinned to the catalog reference this deployment actually serves.
   *
   * ## The half a stored design was left in
   *
   * `--ui-builder-published-catalogs` changes a catalog's `benchmark`, and therefore the reference
   * built from it, without changing the catalog a document fits. `acceptedReferences` (#816) made
   * the **server** accept both sources' references for one `systemId`, so a design written before
   * the flip opens again instead of reporting `CATALOG_UNAVAILABLE`. The browser was not party to
   * that: it holds the served catalog and nothing else, so `ExportValidation.validateCatalogPin`
   * compared the stored pin field by field against the catalog in front of it, put
   * `CATALOG_PIN_MISMATCH` in the Issues panel, and — because the Compose and SVG projections share
   * that fail-closed validator — refused every export. The design came back and could not be used
   * (#818).
   *
   * ## Why re-pinning, and why here
   *
   * Teaching the editor about alternate pins means shipping the other source's reference to the
   * client, which turns a server-side compatibility detail into part of the wire contract and
   * leaves two validators that have to agree about it forever. Re-pinning keeps that knowledge
   * where it already lives, and the document the editor receives simply names the catalog it is
   * being shown.
   *
   * This runs where `persisted.designs` is built, so every downstream `catalogs.resolve(...)` —
   * `unusableReason` included, which is computed from this map — sees the re-pinned document
   * without each call site needing to know. In memory only: nothing is written on a read path, and
   * the stored file converges at the next save on its own, because a write carries
   * `document.catalogPin` forward rather than re-stamping it.
   *
   * ## The conditions, which are the whole safety argument
   *
   * The pin must resolve to a catalog this deployment serves, name that catalog's **own
   * `systemId`**, and the document must **validate against it**. That last clause is what keeps the
   * drift check intact: a document that has drifted from the catalog fails validation, is not
   * re-pinned, and goes on failing for its own reasons under its own pin. A design that is unusable
   * for a topology or limit reason is untouched here as well — this changes one field of one
   * document and nothing about what is checked afterwards.
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

  /** What the startup re-pin did, for the health surface. */
  private data class RePinOutcome(val designs: Int = 0, val failure: String? = null)

  /**
   * Writes the re-pinned designs through to the store, once, at startup.
   *
   * ## Why this is not left in memory
   *
   * The re-pin above converges the stored file "at the next save on its own", and a design nobody
   * edits is never saved — so its stored pin keeps naming the source it was written against for as
   * long as nobody opens it. That is survivable only while BOTH references are still computable,
   * which is the job `acceptedReferences` does by keeping the other source's catalog resident. The
   * moment a synthesised catalog is retired (#819 step 3), its reference stops existing in the
   * process, `resolve` returns null for an old pin, and `rePinned` cannot help — it asks `resolve`
   * first. Every design not edited since the flip would go `CATALOG_UNAVAILABLE` permanently: the
   * outage of 2026-09-13, made durable.
   *
   * So the convergence has to have happened BEFORE the fallback is removed, and it has to happen
   * for designs nobody touches. One write at boot does that, and only for the designs whose pin
   * actually moved — a store that is already converged writes nothing and the next boot is free.
   *
   * ## Why a failure here is not fatal
   *
   * The in-memory re-pin has already been applied, so this process serves correctly whether or not
   * the write lands; what a failure costs is the convergence, which the next boot retries. Failing
   * startup over it would put back exactly the trap the surrounding design removed — a content
   * condition taking the whole server down — and a read-only or full store is a condition an
   * operator acts on, not one the server should die of. It is reported instead: counted in
   * [diagnostics] and carried to `status.json`.
   *
   * The count is what was attempted rather than what provably landed. [UiBuilderDesignStore.commit]
   * is per design, so a failure part-way through leaves some written and some not, and claiming a
   * number for that would be a guess; the failure string beside it is the honest signal.
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
      // The CLASS, never the message. `/status.json` is unauthenticated on a `--public` host and
      // this map is owner-free by contract, but the store says `cannot store UI-builder design
      // <id> under <directory>` -- a design id and the absolute state path, published to anyone
      // who can reach the host. The class name is bounded, identifies nobody, and still separates
      // the store refusing the write from the filesystem refusing it.
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
    // Judged on the probe: a property the catalog stopped declaring is a warning about this
    // design, not a reason to refuse every request naming it. What the probe still refuses is a
    // real defect -- an unknown component has nothing to draw -- and stays fatal.
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
   * The design a request is about, or null for the requests that are about none of them.
   *
   * Listing is deliberately in the second group: a design that cannot be served still appears, so
   * an operator can see that it is there. Hiding it would make it unfindable as well as unusable.
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
      UiBuilderServiceRequest.ListCatalogs,
      is UiBuilderServiceRequest.ExportDocument,
      is UiBuilderServiceRequest.CreateDesign,
      is UiBuilderServiceRequest.ListDesigns -> null
    }

  override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
    call.request.designId()?.let { designId ->
      unusableDesigns[designId]?.let { unusable ->
        // Three exceptions, and only three.
        //
        // A design the CATALOG outgrew may still be asked what moving it would cost. Refusing that
        // refuses the only repair such a design has -- the designs the preview was written for are
        // exactly the ones quarantined here, so a gate in front of it would put the feature
        // permanently out of their reach.
        //
        // And a design may be deleted by whoever owns it, however wrong the document itself is --
        // key mismatch, node count, quota, topology, a catalog nobody serves, files the store
        // cannot read. The listing keeps an unusable design visible and the page that lists it
        // offers the owner a delete button; a delete that always answered with the reason it is
        // unusable is not a warning, it is a trap -- corruption would then be removable only by an
        // operator with an admin token. Ownership is [delete]'s question, answered from the access
        // record the design or its quarantine carries, not this gate's.
        //
        // A design whose files loaded may also be renamed by an actor with write, for the same
        // reason one step smaller: naming a design is not serving it. One the STORE could not read
        // has no document in memory to rename, and stays refused.
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
        if (!previewing && !recovering && !deleting && !renaming) {
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
    val canonical = documentCanonicalBytes(document)
    val retained = limits.retainedRevisionsFor(canonical.size)
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
          (design.revisionSnapshots + RevisionStateV1(document, sequence)).takeLast(retained),
        positionSnapshots =
          (design.positionSnapshots + PositionStateV1(revision, design.positions)).takeLast(
            retained
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
    }

  /** See [UiBuilderServiceRequest.ListRevisions]. */
  private fun revisions(actor: AuthenticatedUiBuilderActor, designId: String): LockedExecution {
    val design = persisted.designs[designId] ?: return serviceError(notFound(designId))
    if (!design.allows(actor, DesignAccessActionV1.READ)) {
      return serviceError(forbidden("read", designId))
    }
    val authors = design.history.associate { it.outcome.sequence to it.submission.actorId() }
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
   * See [UiBuilderServiceRequest.RestoreRevision]. Built as the wire operation it is recorded as
   * and handed to [apply], so it shares everything a submission gets there: the write check,
   * idempotency on [UiBuilderServiceRequest.RestoreRevision.operationId], the rate limit, quotas,
   * the history record and the broadcast to subscribers.
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
    commitDesign(request.designId, updated)
    return LockedExecution(UiBuilderServiceResponse.DesignRenamed(updated.listItem(actor)))
  }

  /**
   * See [UiBuilderServiceRequest.DeleteDesign] for who may. The removal itself is the operator's
   * [adminDeleteDesign], reached through an ownership check rather than an admin token; the two
   * share [removeLocked] so they cannot disagree about what "gone" means.
   *
   * Reached for an unusable design too — [execute] lets the request past its guard, because a
   * corrupted design its owner cannot delete is a trap the file manager pages straight into. A
   * design the store could not read is removed through its quarantine record: when the record
   * carries the header the store read at load, the access record answers [ownedBy] and the owner
   * retires it as their own; when it does not — a header this build could not read — there is
   * nothing left to check ownership against, and the answer is the reason it is quarantined, which
   * is the operator's door.
   */
  private fun delete(actor: AuthenticatedUiBuilderActor, designId: String): LockedExecution {
    val design = persisted.designs[designId]
    if (design != null) {
      if (!design.ownedBy(actor)) {
        return serviceError(forbidden("delete", designId))
      }
      // Closed under the lock, as [updateAccess] closes the streams of an actor it revoked: a
      // subscriber learns the design is gone only after the removal is durable, which
      // [removeLocked] guarantees by committing first.
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
      if (!header.access.ownedBy(actor)) {
        return serviceError(forbidden("delete", designId))
      }
      store.remove(designId)
      // The same two clears [adminDeleteDesign]'s quarantine branch makes: a stale entry would
      // answer this id with the reason rather than "not found", and would follow a design
      // re-created under it.
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
    // A design whose files could not be read is absent from the map above but present on the disk,
    // under the directory this id resolves to. Creating over it would write into somebody else's
    // design — and the id would then answer every request with the stale quarantine, while a delete
    // aimed at the quarantine took the new design with it. Retiring the old one is the door.
    //
    // Asked of the store by place rather than by id, because a quarantine is not always reported
    // under the id it holds: a design whose header will not parse has no id to be read out of it
    // and is reported under its directory, and one restored under another name is reported under
    // that name. Either still occupies the directory this id resolves to.
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
    // Validate the candidate BEFORE resolving its outlines. Resolution can download and parse a
    // 10 MB font under the lock this whole service shares, and malformed input should cost a
    // structured error rather than a cold host's first font fetch — so the cheap guard runs on
    // what the caller sent, and the quota is checked again below on what actually gets stored.
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
    // Only now, on a document that is known to be well-formed. The outlines are part of what gets
    // stored, so the quota is re-checked against them: adding them after the check can carry a
    // design past `maximumEmbeddedAssetBytes` and have it quarantined on the next start for
    // exceeding a limit it was accepted under.
    val document = withIconOutlines(candidate)
    documentQuotaIssue(document, countRejection = true)?.let {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, it)
    }

    val design =
      PersistedDesignV1(
        // The same `document` the snapshot below stores: a design can be created with its icons
        // already in it — an MCP `CreateDesign`, a template, a pack — and an immediate export or
        // `GetSnapshot(revision = 0)` must not read a retained snapshot without them.
        document = document,
        lastSequence = 0,
        // Owned by the human when the caller is acting for one. An agent's grant is a
        // short-lived delegation of *their* authority, so a design it creates has to outlive the
        // grant in the hands of the person who approved it — the alternative is what this fixes: a
        // design owned by an id that stops existing in an hour, which its own approver is then
        // refused when they open the link the agent sent them.
        access =
          DesignAccessControlV1(0, canonicalActorId(actor.onBehalfOfActorId ?: actor.actorId)),
        revisionSnapshots = listOf(RevisionStateV1(document, 0)),
        positions = derivePositions(document),
        positionSnapshots = listOf(PositionStateV1(0, derivePositions(document))),
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
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
        .forEach { add(it.document.id to { it.listItem(actor) }) }
      // A design the store could not read is listed too, when its quarantine knows who owns it:
      // a design the owner cannot see is one they cannot delete, and the file manager is where
      // both happen. Reported under the id its header named and never under the renamed key a
      // collision forced — that key shares its owner with a live design of the same id, and a
      // second row for it would be a lie about how many designs there are.
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
   * What [actor] may do here, or a not-found that does not say whether the design exists.
   *
   * A design this actor cannot read answers exactly as a design that is not here does. That is the
   * whole reason this is safe to expose to a grantee where [access] is not: it describes only the
   * caller's own reach, and an actor learns nothing it could not learn by opening the design.
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
   * What moving [request]'s design to another catalog would cost it — without moving it.
   *
   * Read access is enough, because this writes nothing: the stored design is untouched, and the
   * candidate travels back to the caller to be looked at. That is the whole point of a preview on a
   * design that has stopped opening — an owner decides whether a move that drops `letterSpacingSp`
   * is the repair they want, rather than discovering it after the fact.
   *
   * BLOCKED rather than an error where the candidate still does not validate. A refusal here is an
   * answer to the question that was asked ("can this design move?"), not a failure to answer it,
   * and the changes and issues beside it are what say why.
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
      // Names this preview, not its document: an apply quotes it back, and a plan that changed
      // between the two -- a catalog republished under the same pin, a rule edited here -- must
      // not be accepted as the one somebody looked at. Hashing the candidate alone would miss
      // exactly that, since two plans can land on the same document by different routes.
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

    val closed = mutableListOf<SubscriberMailbox>()
    runtime.getValue(request.designId).subscribers.entries.removeIf { (_, subscriber) ->
      val revoke = !updated.allows(subscriber.actor, DesignAccessActionV1.READ)
      if (revoke) closed += subscriber.mailbox
      revoke
    }
    closed.forEach(SubscriberMailbox::close)
    return LockedExecution(UiBuilderServiceResponse.DesignAccess(request.designId, access))
  }

  /**
   * The design with the outlines of every icon it names present, and stale ones dropped.
   *
   * Run on the reduced design rather than inside the reducer so that it applies to every write once
   * — an apply, a batch, an undo, a redo — instead of to whichever mutations somebody remembered.
   * It cannot fail a write: a host with no resolver, or one whose icon cache is cold, leaves the
   * entries it already had and adds none, so an offline write is a design with fewer pictures
   * rather than a rejected command.
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
        // Two bounds, and the byte one is the load-bearing half: keeping `acceptedOperations` a
        // subset of the retained outcomes preserves the invariant those two have always had, and
        // the budget is what stops one design's undo records from being most of the store.
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
    commitDesign(submission.designId, recorded)
    if (reduction.outcome !is AcceptedOutcomeV1) {
      return LockedExecution(UiBuilderServiceResponse.OperationOutcome(reduction.outcome))
    }
    if (submission.isCatalogUpgradeOnly()) {
      // The write above was validated against the target catalog, so the condition that held this
      // design out of every ordinary request has been repaired in this process as well as on disk.
      // Leaving the boot-time entry behind would make a successful recovery require a restart.
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
    // Exported WITHOUT the properties the catalog no longer declares, and validated in that shape.
    // Not a silent edit of the design: the stored document keeps every one of them, and the catalog
    // has no meaning to give a property it does not declare, so there is nothing an exporter could
    // faithfully write for it. Emitting the original would put a value into generated source that
    // the catalog cannot account for, which is the one outcome worse than leaving it out.
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
          actor,
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
    // The undeclared VALUES this design already carried are tolerated; anything else is not.
    // `withoutProperties` drops a property only where the candidate still holds the stored value,
    // so both a freshly invented undeclared property and a rewrite of a tolerated one stay in the
    // probe, where `validate` rejects them as it always has -- nobody gets to author against a
    // property the catalog does not have, under cover of one it once had. Without this the design
    // would open and then refuse every edit, which is worse than plainly unusable.
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
   * A restore, recorded as the whole-document replacement it is: [RESTORE_DIGEST_PREFIX] plus the
   * revision in the digest, and both hashes binding it to exactly the documents it replaces and
   * brings back. Nothing is taken on trust from the wire — the retained revision is looked up again
   * here and its hash must match — so the only way to commit one is to name a revision this design
   * really had.
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
      // Positions are derived afresh, as a created design's are: the retained document is whole,
      // and the positions kept for the current one describe nodes that may no longer exist. A node
      // coming back is no longer a tombstone.
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
    // Outlines are resolved HERE, on the accepted candidate, and not after `reduce` returns.
    // Everything below reads this value — the canonical bytes, the accepted hash, the retained
    // revision snapshot — so filling the registry afterwards would hand a client a hash and a
    // delta for a document the host does not have, and would leave `OpenDesign` and a
    // revision-pinned export disagreeing about what the design contains. A rejected reduction
    // never reaches this function at all, so it can no longer change a document without
    // advancing its revision.
    val document =
      withIconOutlines(working.document).copy(revision = revision, updatedAtEpochMillis = now)
    val canonical = documentCanonicalBytes(document)
    val retained = limits.retainedRevisionsFor(canonical.size)
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
    val snapshots =
      (design.revisionSnapshots + RevisionStateV1(document, sequence)).takeLast(retained)
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
      (design.positionSnapshots + PositionStateV1(revision, working.positions)).takeLast(retained)
    val updated =
      design.copy(
        document = document,
        lastSequence = sequence,
        history = history,
        revisionSnapshots = snapshots,
        positions = working.positions,
        positionSnapshots = positionSnapshots,
        acceptedOperations = accepted,
        // Pruned against the oldest revision a submission can still name, NOT by a count of its
        // own. `reduceCommand` refuses a `baseRevision` with no retained position snapshot, so that
        // is exactly the window a staleness check can be asked about, and matching the two here is
        // what keeps the answer "nobody wrote this" from meaning "we no longer know".
        conflictTouches =
          (design.conflictTouches +
              ConflictTouchRecordV1(revision, changes.flatMap(ChangeRecordV1::touchKeys).toSet()))
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
                original.touchedSince(command.baseRevision, touchKey("s", mutation.nodeId))
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
              original.lastTouchSince(
                command.baseRevision,
                touchKey("e", environmentField.name),
              )
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
                original.touchedSince(command.baseRevision, touchKey("m", mutation.nodeId))
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
        is DeclareComponentMutationV1 -> {
          if (mutation.componentKey.isBlank()) {
            fail(
              RejectionCodeV1.INVALID_COMMAND,
              "component key is blank",
              operationIndex = index,
            )
          }
          // The body has to exist before the name can point at it. A declaration naming a root
          // this document does not hold is a component that draws nothing, and an instance of it
          // reports success while rendering blank — the same failure the state-variable rules
          // exist to prevent, one level up.
          //
          // An import is a batch: the body's `insertNode`s precede the declaration, so by the time
          // this runs the root is in `working.document` even though it was not in the base.
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
          val document =
            working.document.copy(
              components =
                working.document.components + (mutation.componentKey to mutation.declaration)
            )
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            ComponentChangeV1(mutation.componentKey, before, mutation.declaration),
            staleComponentWrites(original, command, mutation.componentKey),
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
          // The obligation this mutation's contract states: a placement may still name the key,
          // and a placement whose component is gone draws nothing while reporting success.
          //
          // The body is deliberately not checked — undeclaring alone leaves a subtree nothing
          // draws, which is what a body looks like between being written and being named, and is a
          // legitimate intermediate state rather than a broken document.
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
          val document =
            working.document.copy(components = working.document.components - mutation.componentKey)
          MutationResult(
            WorkingDesign(document, working.tombstones, working.positions),
            ComponentChangeV1(mutation.componentKey, before, null),
            staleComponentWrites(original, command, mutation.componentKey),
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
            if (
              command.baseRevision < original.document.revision &&
                original.touchedSince(
                  command.baseRevision,
                  touchKey("b", mutation.nodeId, mutation.event),
                )
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
          original.touchedSince(command.baseRevision, touchKey("p", nodeId, property))
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
            // Taking a declaration back out is only safe if nothing has since come to place it:
            // undoing a `declareComponent` that somebody has since instantiated would leave a
            // placement drawing nothing and reporting success, which is the state the removal rule
            // refuses to commit in the first place. The mirror of the state-variable check above.
            if (target == null) {
              working.document.nodes.values
                .firstOrNull { it.component?.componentKey == change.componentKey }
                ?.let { placement ->
                  fail(
                    RejectionCodeV1.UNSAFE_COMPENSATION,
                    "node ${placement.id} places component ${change.componentKey}",
                    nodeId = placement.id,
                    field = change.componentKey,
                  )
                }
            }
            val declarations =
              if (target == null) working.document.components - change.componentKey
              else working.document.components + (change.componentKey to target)
            // Putting one back has the same obligation a declaration does: it must still name a
            // body this document holds, or the compensation writes the broken state the forward
            // path refuses.
            if (target != null && target.root !in working.document.nodes) {
              fail(
                RejectionCodeV1.UNSAFE_COMPENSATION,
                "component ${change.componentKey} names a body root `${target.root}` " +
                  "this design no longer holds",
                field = target.root,
              )
            }
            working = working.copy(document = working.document.copy(components = declarations))
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
      validateTopology(working.document)?.let { throw ReductionFailure(it) }
      val catalog =
        catalogs.resolve(working.document.catalogPin)
          ?: fail(RejectionCodeV1.INVALID_DOCUMENT, "catalog pin is unavailable")
      // Same tolerance as `reduceBatch`: undoing or redoing an operation on a design the catalog
      // outgrew must not be the thing that fails, or an edit made before the catalog moved could
      // never be taken back.
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
      commitDesign(designId, repaired)
      unusableDesigns.remove(designId)
      runtime.getOrPut(designId) { RuntimeDesign() }
      UiBuilderAdminRepair.Repaired(designId, document.revision, unusable.reason)
    }

  override fun adminDeleteDesign(designId: String): Boolean {
    val closed: List<SubscriberMailbox> = lock.withLock {
      // A design whose stored files could not be read is not in the design map — there is no
      // document to put there — but it is still on the disk, still counted against the store, and
      // still the operator's to retire. Retiring it is the one action that has to keep working when
      // reading it does not; download and repair genuinely cannot, because both need the document
      // the store could not decode.
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
   * Stores one design's new value, and only that design's.
   *
   * The whole-store candidate this replaced is what made an edit cost `O(everything stored)`: a
   * padding value nudged in the smallest of 36 designs re-serialized all 23.4 MB, including the
   * 12.5 MB belonging to a design the edit never touched (yschimke/compose-preview-server#578). The
   * store is handed the value before and after so it can write only the parts that differ.
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
   * A host on the per-design store has nothing to do here: that store migrates a v2 file the first
   * time it opens one, so this answers "already at the latest" rather than refusing, which is what
   * `--ui-builder-migrate-state` on such a host should hear.
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
