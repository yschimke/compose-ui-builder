package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1
import ee.schimke.composeai.uibuilder.protocol.CommandOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessControlV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignHomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignListItemV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.PresenceUpdateV1
import ee.schimke.composeai.uibuilder.protocol.ServiceDeltaV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceSnapshotV1
import java.io.Closeable

/**
 * Actor identity established by the host's authentication layer, never by a request payload.
 *
 * [onBehalfOfActorId] is the human a delegated credential (e.g. an approved agent grant) acts for.
 * It only widens access checks via [accessIdentities]; everything the actor writes stays under
 * [actorId], so audit and presence still name the agent.
 */
public data class AuthenticatedUiBuilderActor(
  public val actorId: String,
  public val onBehalfOfActorId: String? = null,
) {
  init {
    require(actorId.isNotBlank()) { "authenticated UI-builder actor id must not be blank" }
    require(onBehalfOfActorId?.isNotBlank() != false) {
      "a delegating principal's actor id must not be blank"
    }
    require(onBehalfOfActorId != actorId) { "an actor cannot act on behalf of itself" }
  }

  /**
   * Every identity this actor's authority may be found under, its own first, so a delegate's own
   * grant wins over its principal's.
   */
  public val accessIdentities: List<String>
    get() = listOfNotNull(actorId, onBehalfOfActorId)
}

/** One transport-neutral service invocation with its independently authenticated principal. */
public data class UiBuilderServiceCall(
  val actor: AuthenticatedUiBuilderActor,
  val request: UiBuilderServiceRequest,
)

public sealed interface UiBuilderServiceRequest {
  public data object ListCatalogs : UiBuilderServiceRequest

  public data class CreateDesign(val document: DesignDocumentV1) : UiBuilderServiceRequest

  public data class ListDesigns(val cursor: String?, val limit: Int) : UiBuilderServiceRequest

  public data class OpenDesign(val designId: String) : UiBuilderServiceRequest

  public data class GetDesignAccess(val designId: String) : UiBuilderServiceRequest

  /**
   * What this actor may do to one design, asked without doing anything. Unlike owner-only
   * [GetDesignAccess], any reader may ask; a design it cannot read answers as missing.
   * Host-internal, with no protocol shape (see [RenameDesign]).
   */
  public data class GetDesignActions(val designId: String) : UiBuilderServiceRequest

  public data class UpdateDesignAccess(
    val designId: String,
    val baseAccessRevision: Long,
    val mutations: List<DesignAccessMutationV1>,
  ) : UiBuilderServiceRequest

  public data class PreviewCatalogUpgrade(
    val designId: String,
    val baseRevision: Long,
    val sourceCatalogPin: CatalogReferenceV1,
    val targetCatalogPin: CatalogReferenceV1,
  ) : UiBuilderServiceRequest

  /**
   * Preview moving an unusable design to the catalog revision served now. The recovery path for a
   * pin this runtime can no longer resolve: the source pin comes from the stored document and the
   * target from [UiBuilderCatalogExecutor.reference]. Committing still needs the hash-bound
   * [ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeMutationV1].
   */
  public data class PreviewCurrentCatalogUpgrade(val designId: String) : UiBuilderServiceRequest

  public data class ApplyOperation(val submission: UiBuilderSubmission) : UiBuilderServiceRequest

  public data class GetSnapshot(val designId: String, val revision: Long?) : UiBuilderServiceRequest

  public data class GetDelta(val designId: String, val afterSequence: Long, val limit: Int) :
    UiBuilderServiceRequest

  public data class UpdatePresence(val designId: String, val presence: UiBuilderPresence) :
    UiBuilderServiceRequest

  public data class ExportDesign(
    val designId: String,
    val revision: Long?,
    val format: ExportFormatV1,
  ) : UiBuilderServiceRequest

  /**
   * Compile supplied Remote document content without creating or reading a stored design.
   *
   * The host admits this with export capability. Only self-contained Remote JSON/RC exports are
   * accepted; catalog pins, topology and quotas are checked just as for saved designs. The existing
   * DesignDocumentV1 and ExportArtifactV1 shapes travel through the host's document-export route.
   */
  public data class ExportDocument(val document: DesignDocumentV1, val format: ExportFormatV1) :
    UiBuilderServiceRequest

  /**
   * Change a design's title only. Outside the operation log: the title is metadata no node or
   * export reads, so it takes no revision, delta or undo record. Anyone who may write may rename.
   *
   * Has no `ui-builder-protocol` request shape yet; see
   * [UiBuilderProtocolMapper.toProtocolRequest].
   */
  public data class RenameDesign(val designId: String, val title: String) : UiBuilderServiceRequest

  /**
   * Remove a design, its history and its access list, closing every open stream on it.
   *
   * Owner only: not a grantee and not a delegate acting on the owner's behalf
   * (`AGENT_ACCESS_GRANTS.md`); [UiBuilderAdminPort.adminDeleteDesign] covers designs whose owner
   * is gone. Ownership is checked against the loaded or quarantined access record, so an unservable
   * design can still be deleted.
   *
   * Has no `ui-builder-protocol` request shape yet; see
   * [UiBuilderProtocolMapper.toProtocolRequest].
   */
  public data class DeleteDesign(val designId: String) : UiBuilderServiceRequest

  /**
   * The revisions this service retains a whole document for, newest first. Needs read; no protocol
   * shape (see [RenameDesign]).
   */
  public data class ListRevisions(val designId: String) : UiBuilderServiceRequest

  /**
   * Make a retained [revision]'s document current again, as a new revision (so restoring is undone
   * by restoring again). Commits through the hash-bound whole-document replacement a catalog
   * upgrade uses, so a raced edit is refused. Needs write and [baseRevision] must be current; no
   * protocol shape (see [RenameDesign]).
   */
  public data class RestoreRevision(
    val designId: String,
    val revision: Long,
    val baseRevision: Long,
    val operationId: String,
  ) : UiBuilderServiceRequest

  /**
   * Move the canonical home of a design without inferring authority from the copy being edited.
   *
   * [sourceHome] and [baseRevision] must still describe the stored design. A successful move is a
   * new revision: the service retains the old snapshot, keeps its own stored copy as a pointer to
   * [targetHome], and pushes a whole snapshot because no v1 delta can express home metadata.
   */
  public data class MoveDesignHome(
    val designId: String,
    val sourceHome: DesignHomeV1?,
    val targetHome: DesignHomeV1,
    val baseRevision: Long,
    val operationId: String,
  ) : UiBuilderServiceRequest

  /**
   * Authoritatively replace one stored design from a complete document copy.
   *
   * The supplied document must name the stored design and its current home. The service owns the
   * committed id, home, revision and timestamps; validates the complete candidate; and retains the
   * previous revision before broadcasting the replacement as a whole snapshot. This is the seam a
   * host uses to save a temporary copy back or re-import an existing canonical document.
   */
  public data class ReplaceDesignDocument(
    val designId: String,
    val document: DesignDocumentV1,
    val baseRevision: Long,
    val operationId: String,
  ) : UiBuilderServiceRequest
}

/** One retained revision, as [UiBuilderServiceRequest.ListRevisions] reports it. */
public data class UiBuilderRevisionSummary(
  val revision: Long,
  val sequence: Long,
  val updatedAtEpochMillis: Long?,
  /** Who committed the operation that produced this revision; null for the design's creation. */
  val actorId: String?,
)

/**
 * An admitted collaboration submission. Actor identity is intentionally absent: the service must
 * use [UiBuilderServiceCall.actor], after the protocol mapper has checked any nested wire actor.
 */
public sealed interface UiBuilderSubmission {
  public val designId: String
  public val operationId: String
  public val clientId: String
  public val baseRevision: Long

  public data class Batch(
    override val designId: String,
    override val operationId: String,
    override val clientId: String,
    override val baseRevision: Long,
    val operations: List<DesignMutationV1>,
    /**
     * The revision the author had seen when older than [baseRevision]
     * (`DesignCommandV1.stalenessBaseRevision`), as sent by replayed offline runs; `STALE_*` is
     * judged from here while positions resolve against [baseRevision]. Null for a live edit.
     */
    val stalenessBaseRevision: Long? = null,
  ) : UiBuilderSubmission {
    /** The constructor of earlier releases, kept so a host compiled against one still links. */
    public constructor(
      designId: String,
      operationId: String,
      clientId: String,
      baseRevision: Long,
      operations: List<DesignMutationV1>,
    ) : this(designId, operationId, clientId, baseRevision, operations, null)

    /** The `copy` of earlier releases, for the same reason; it keeps [stalenessBaseRevision]. */
    public fun copy(
      designId: String = this.designId,
      operationId: String = this.operationId,
      clientId: String = this.clientId,
      baseRevision: Long = this.baseRevision,
      operations: List<DesignMutationV1> = this.operations,
    ): Batch =
      copy(designId, operationId, clientId, baseRevision, operations, stalenessBaseRevision)
  }

  public data class Undo(
    override val designId: String,
    override val operationId: String,
    override val clientId: String,
    override val baseRevision: Long,
    val targetOperationId: String,
  ) : UiBuilderSubmission

  public data class Redo(
    override val designId: String,
    override val operationId: String,
    override val clientId: String,
    override val baseRevision: Long,
    val targetUndoOperationId: String,
  ) : UiBuilderSubmission
}

/** Ephemeral presence after its untrusted actor field has been removed. */
public data class UiBuilderPresence(
  val clientId: String,
  val displayName: String,
  val colorArgbHex: String,
  val selectedNodeIds: List<String>,
  val pointerX: Double?,
  val pointerY: Double?,
  val observedRevision: Long,
) {
  init {
    require((pointerX == null) == (pointerY == null)) {
      "presence pointer coordinates must both be null or both be present"
    }
  }
}

public sealed interface UiBuilderServiceResponse {
  /**
   * The catalogs a design may pin to; [pins] gives the exact [CatalogReferenceV1] for each system
   * id, or is empty where the executor cannot say.
   */
  public data class Catalogs(
    val catalogs: List<CatalogCapabilityV1>,
    val pins: Map<String, CatalogReferenceV1> = emptyMap(),
  ) : UiBuilderServiceResponse

  public data class Designs(val designs: List<DesignListItemV1>, val nextCursor: String?) :
    UiBuilderServiceResponse

  /**
   * The actions the caller may take on one design, resolved through its principal. Owners are
   * reported as holding every action rather than as a role.
   */
  public data class DesignActions(
    val designId: String,
    val actions: List<DesignAccessActionV1>,
  ) : UiBuilderServiceResponse

  public data class DesignAccess(val designId: String, val access: DesignAccessControlV1) :
    UiBuilderServiceResponse

  public data class CatalogUpgradePreview(val preview: CatalogUpgradePreviewV1) :
    UiBuilderServiceResponse

  public data class Snapshot(val snapshot: ServiceSnapshotV1) : UiBuilderServiceResponse

  public data class OperationOutcome(val outcome: CommandOutcomeV1) : UiBuilderServiceResponse

  public data class Delta(val delta: ServiceDeltaV1) : UiBuilderServiceResponse

  public data class PresenceAccepted(val designId: String, val actorId: String) :
    UiBuilderServiceResponse

  public data class Export(val artifact: ExportArtifactV1) : UiBuilderServiceResponse

  /** The design as the caller now sees it in a listing, carrying its new title. */
  public data class DesignRenamed(val design: DesignListItemV1) : UiBuilderServiceResponse

  /** The design is gone, durably. */
  public data class DesignDeleted(val designId: String) : UiBuilderServiceResponse

  /** [UiBuilderServiceRequest.ListRevisions]'s answer, newest first. */
  public data class Revisions(
    val designId: String,
    val currentRevision: Long,
    val revisions: List<UiBuilderRevisionSummary>,
  ) : UiBuilderServiceResponse

  public data class Error(val error: UiBuilderServiceError) : UiBuilderServiceResponse
}

public data class UiBuilderServiceError(
  val code: ServiceErrorCodeV1,
  val message: String,
  val retryable: Boolean = false,
  val currentRevision: Long? = null,
  val currentAccessRevision: Long? = null,
  val retainedFromSequence: Long? = null,
)

/** Transport-neutral server-push payload. */
public sealed interface UiBuilderServiceUpdate {
  public data class Snapshot(val snapshot: ServiceSnapshotV1) : UiBuilderServiceUpdate

  public data class Delta(val delta: ServiceDeltaV1) : UiBuilderServiceUpdate

  public data class Presence(val update: PresenceUpdateV1) : UiBuilderServiceUpdate

  public data class Outcome(val outcome: CommandOutcomeV1) : UiBuilderServiceUpdate
}

public data class UiBuilderSubscriptionCall(
  val actor: AuthenticatedUiBuilderActor,
  val designId: String,
  /** Exclusive durable cursor. Null requests the current snapshot. */
  val afterSequence: Long?,
)

/**
 * Pure service boundary consumed by future HTTP, WebSocket, and MCP-client adapters.
 *
 * Implementations own authorization, reducer ordering, persistence, and export execution. The
 * callback may be invoked until the returned handle is closed; transport adapters must serialize or
 * buffer it according to their own lifecycle and backpressure policy.
 */
public interface UiBuilderServicePort {
  public suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse

  public fun subscribe(
    call: UiBuilderSubscriptionCall,
    listener: (UiBuilderServiceUpdate) -> Unit,
  ): Closeable
}

/** Aggregate, owner-free production diagnostics; no actor, design, operation, or capability IDs. */
public data class UiBuilderServiceDiagnostics(
  val activeSubscribers: Int,
  val peakSubscribers: Long,
  val rejectedBatchLimit: Long,
  val rejectedSubscriberLimit: Long,
  val slowSubscribersClosed: Long,
  val rejectedPresenceLimit: Long,
  val activeExports: Int,
  val peakExports: Long,
  val rejectedExportLimit: Long,
  val rejectedMutationRate: Long,
  val rejectedDocumentBytes: Long,
  val rejectedAssetBytes: Long,
  val timedOutExports: Long,
  val activeMutationBuckets: Int,
  val persistenceMigrations: Long,
  /**
   * Stored designs the current catalog or limits cannot serve. They are loaded but refuse every
   * request, and do not stop startup.
   */
  val unusableDesigns: Int = 0,
  /** Stored, servable designs carrying properties their catalog no longer declares. */
  val degradedDesigns: Int = 0,
  /**
   * Stored designs whose catalog pin was rewritten to the served reference as they loaded. A count
   * that stays non-zero across restarts means the write-through is failing — see
   * [rePinPersistenceFailure].
   */
  val rePinnedDesigns: Int = 0,
  /**
   * The exception class (never its message) that stopped re-pin writes, or null. Messages name
   * design ids and state paths, and this record is served unauthenticated on public hosts. Not
   * fatal: the re-pin already applies in memory.
   */
  val rePinPersistenceFailure: String? = null,
  /**
   * Bytes the durable state occupies and the ceiling writes are refused at; both 0 where unbounded
   * or unmeasurable (yschimke/compose-preview-server#568).
   */
  val storageBytes: Long = 0,
  val storageMaximumBytes: Long = 0,
)

public interface UiBuilderServiceDiagnosticsSource {
  public fun diagnostics(): UiBuilderServiceDiagnostics
}
