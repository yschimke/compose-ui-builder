package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
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
value class AuthenticatedUiBuilderActor(val actorId: String) {
  init {
    require(actorId.isNotBlank()) { "authenticated UI-builder actor id must not be blank" }
  }
}

/** One transport-neutral service invocation with its independently authenticated principal. */
data class UiBuilderServiceCall(
  val actor: AuthenticatedUiBuilderActor,
  val request: UiBuilderServiceRequest,
)

sealed interface UiBuilderServiceRequest {
  data object ListCatalogs : UiBuilderServiceRequest

  data class CreateDesign(val document: DesignDocumentV1) : UiBuilderServiceRequest

  data class ListDesigns(val cursor: String?, val limit: Int) : UiBuilderServiceRequest

  data class OpenDesign(val designId: String) : UiBuilderServiceRequest

  data class GetDesignAccess(val designId: String) : UiBuilderServiceRequest

  data class UpdateDesignAccess(
    val designId: String,
    val baseAccessRevision: Long,
    val mutations: List<DesignAccessMutationV1>,
  ) : UiBuilderServiceRequest

  data class ApplyOperation(val submission: UiBuilderSubmission) : UiBuilderServiceRequest

  data class GetSnapshot(val designId: String, val revision: Long?) : UiBuilderServiceRequest

  data class GetDelta(val designId: String, val afterSequence: Long, val limit: Int) :
    UiBuilderServiceRequest

  data class UpdatePresence(val designId: String, val presence: UiBuilderPresence) :
    UiBuilderServiceRequest

  data class ExportDesign(
    val designId: String,
    val revision: Long?,
    val format: ExportFormatV1,
  ) : UiBuilderServiceRequest
}

/**
 * An admitted collaboration submission. Actor identity is intentionally absent: the service must
 * use [UiBuilderServiceCall.actor], after the protocol mapper has checked any nested wire actor.
 */
sealed interface UiBuilderSubmission {
  val designId: String
  val operationId: String
  val clientId: String
  val baseRevision: Long

  data class Batch(
    override val designId: String,
    override val operationId: String,
    override val clientId: String,
    override val baseRevision: Long,
    val operations: List<DesignMutationV1>,
  ) : UiBuilderSubmission

  data class Undo(
    override val designId: String,
    override val operationId: String,
    override val clientId: String,
    override val baseRevision: Long,
    val targetOperationId: String,
  ) : UiBuilderSubmission

  data class Redo(
    override val designId: String,
    override val operationId: String,
    override val clientId: String,
    override val baseRevision: Long,
    val targetUndoOperationId: String,
  ) : UiBuilderSubmission
}

/** Ephemeral presence after its untrusted actor field has been removed. */
data class UiBuilderPresence(
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

sealed interface UiBuilderServiceResponse {
  data class Catalogs(val catalogs: List<CatalogCapabilityV1>) : UiBuilderServiceResponse

  data class Designs(val designs: List<DesignListItemV1>, val nextCursor: String?) :
    UiBuilderServiceResponse

  data class DesignAccess(val designId: String, val access: DesignAccessControlV1) :
    UiBuilderServiceResponse

  data class Snapshot(val snapshot: ServiceSnapshotV1) : UiBuilderServiceResponse

  data class OperationOutcome(val outcome: CommandOutcomeV1) : UiBuilderServiceResponse

  data class Delta(val delta: ServiceDeltaV1) : UiBuilderServiceResponse

  data class PresenceAccepted(val designId: String, val actorId: String) : UiBuilderServiceResponse

  data class Export(val artifact: ExportArtifactV1) : UiBuilderServiceResponse

  data class Error(val error: UiBuilderServiceError) : UiBuilderServiceResponse
}

data class UiBuilderServiceError(
  val code: ServiceErrorCodeV1,
  val message: String,
  val retryable: Boolean = false,
  val currentRevision: Long? = null,
  val currentAccessRevision: Long? = null,
  val retainedFromSequence: Long? = null,
)

/** Transport-neutral server-push payload. */
sealed interface UiBuilderServiceUpdate {
  data class Snapshot(val snapshot: ServiceSnapshotV1) : UiBuilderServiceUpdate

  data class Delta(val delta: ServiceDeltaV1) : UiBuilderServiceUpdate

  data class Presence(val update: PresenceUpdateV1) : UiBuilderServiceUpdate

  data class Outcome(val outcome: CommandOutcomeV1) : UiBuilderServiceUpdate
}

data class UiBuilderSubscriptionCall(
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
interface UiBuilderServicePort {
  suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse

  fun subscribe(
    call: UiBuilderSubscriptionCall,
    listener: (UiBuilderServiceUpdate) -> Unit,
  ): Closeable
}
