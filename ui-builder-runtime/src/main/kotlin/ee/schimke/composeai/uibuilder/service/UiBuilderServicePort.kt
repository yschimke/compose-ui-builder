package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1
import ee.schimke.composeai.uibuilder.protocol.CommandOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessControlV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
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
 * [onBehalfOfActorId] is the second half of that identity: the human an automated actor is acting
 * for. A host mints one when a credential is itself a delegation — this repository's server does it
 * for an agent grant, whose whole existence is a person clicking *approve* on an agent's request —
 * and leaves it null for a credential that speaks for itself, which is every browser session.
 *
 * The service reads it in exactly one place, [designs' access control][accessIdentities]: a
 * delegate may do what its principal may do, and nothing more. Everything an actor *writes* —
 * operations, presence, undo eligibility — stays under [actorId] alone, so the audit record still
 * says the agent did it and two identities never collide in one design's presence.
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
   * Every identity this actor's authority may be found under, its own first.
   *
   * The order matters where an answer is derived from the first match — a delegate that also holds
   * a grant of its own is described by that grant rather than by its principal's.
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
   * What this actor may do to one design — the design's own answer, asked without doing anything.
   *
   * [GetDesignAccess] answers a neighbouring question and cannot serve this one: it is owner-only,
   * because the whole access list names every collaborator, and a grantee has no business reading
   * who else was shared in. A snapshot cannot either, since it carries `access` only for the owner.
   * So an actor holding a grant had no way to learn its own actions short of attempting the write,
   * and a caller outside the operation log — the sidecars beside a design, which are not the
   * document and so never reach [ApplyOperation] — had no way at all.
   *
   * Answers only for a design this actor may read, and is otherwise indistinguishable from a design
   * that does not exist: the point is to describe an actor's own reach, never to confirm an id.
   *
   * Has no `ui-builder-protocol` request shape, and deliberately so — this is a host asking its own
   * service a question, not a wire message; see [RenameDesign] for the same argument.
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
   * Change a design's title, and nothing else about it.
   *
   * Outside the operation log on purpose. The title is document metadata rather than design
   * content: no node reads it, no export emits it, and the revision — which is what an editor
   * quotes as `baseRevision` and what an export pins — identifies the *design*, which a rename
   * leaves untouched. Making it a mutation would give a rename a revision, a delta and an undo
   * record, for something a concurrent edit cannot conflict with. Anybody who may write the design
   * may name it. A listing shows the new title at once; an open editor shows it when it next opens
   * the design.
   *
   * Has no `ui-builder-protocol` request shape yet, so it is answered outside the released
   * envelope; see [UiBuilderProtocolMapper.toProtocolRequest].
   */
  public data class RenameDesign(val designId: String, val title: String) : UiBuilderServiceRequest

  /**
   * Remove a design, its history and its access list; every open stream on it is closed.
   *
   * **Owner only** — not a grantee, however wide its grant, and not an actor that merely holds a
   * write capability on the host. That is the guard `AGENT_ACCESS_GRANTS.md` argues for: an agent
   * must not be able to wipe somebody else's work. An agent acting under an approved grant owns
   * what it created *as the person who approved it*, so a session can clean up after itself and
   * cannot reach past that. The operator's [UiBuilderAdminPort.adminDeleteDesign] remains the way
   * to remove a design whose owner is gone.
   *
   * Has no `ui-builder-protocol` request shape yet, so it is answered outside the released
   * envelope; see [UiBuilderProtocolMapper.toProtocolRequest].
   */
  public data class DeleteDesign(val designId: String) : UiBuilderServiceRequest
}

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
  ) : UiBuilderSubmission

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
   * The catalogs a design may pin to. [pins], keyed by catalog system id, is the exact
   * [CatalogReferenceV1] a document must carry to resolve to each — the thing a client used to have
   * to guess, since the capability itself does not spell its digest. Empty where the executor
   * cannot say.
   */
  public data class Catalogs(
    val catalogs: List<CatalogCapabilityV1>,
    val pins: Map<String, CatalogReferenceV1> = emptyMap(),
  ) : UiBuilderServiceResponse

  public data class Designs(val designs: List<DesignListItemV1>, val nextCursor: String?) :
    UiBuilderServiceResponse

  /**
   * The actions [actor][UiBuilderServiceCall.actor] may take on one design, resolved through its
   * principal where it has one, exactly as every other authorisation here is.
   *
   * An owner is reported as holding every action rather than as an owner: callers of this ask "may
   * I write", and answering with a role would make each of them re-derive what a role permits.
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
   * Stored designs the current catalog or limits cannot serve, which are loaded but answer every
   * request naming them with the reason. They do not stop the service starting, so this is how an
   * operator learns they exist without opening one.
   */
  val unusableDesigns: Int = 0,
  /**
   * Bytes the durable state currently occupies, and the ceiling a write is refused at.
   *
   * Both 0 when the storage bounds nothing (in-memory, tests) or cannot be measured. This is the
   * headroom an operator had no way to see: `preview.coo.ee` sat at 73% of its ceiling for weeks
   * and the first signal would have been a refused save (yschimke/compose-preview-server#568).
   */
  val storageBytes: Long = 0,
  val storageMaximumBytes: Long = 0,
)

public interface UiBuilderServiceDiagnosticsSource {
  public fun diagnostics(): UiBuilderServiceDiagnostics
}
