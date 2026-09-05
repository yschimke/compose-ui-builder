package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1
import ee.schimke.composeai.uibuilder.protocol.CommandOutcomeV1
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

/** Actor identity established by the host's authentication layer, never by a request payload. */
@JvmInline
public value class AuthenticatedUiBuilderActor(public val actorId: String) {
  init {
    require(actorId.isNotBlank()) { "authenticated UI-builder actor id must not be blank" }
  }
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
  public data class Catalogs(val catalogs: List<CatalogCapabilityV1>) : UiBuilderServiceResponse

  public data class Designs(val designs: List<DesignListItemV1>, val nextCursor: String?) :
    UiBuilderServiceResponse

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
)

public interface UiBuilderServiceDiagnosticsSource {
  public fun diagnostics(): UiBuilderServiceDiagnostics
}
