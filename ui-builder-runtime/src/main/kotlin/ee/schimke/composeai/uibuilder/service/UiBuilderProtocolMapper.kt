package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DeltaDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.DeltaResponseV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessResponseV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignSubmissionV1
import ee.schimke.composeai.uibuilder.protocol.DesignUpdateEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.DesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.DesignsResponseV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportResponseV1
import ee.schimke.composeai.uibuilder.protocol.GetDeltaRequestV1
import ee.schimke.composeai.uibuilder.protocol.GetDesignAccessRequestV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListDesignsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.OutcomeDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.PointerV1
import ee.schimke.composeai.uibuilder.protocol.PresenceAcceptedResponseV1
import ee.schimke.composeai.uibuilder.protocol.PresenceDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.PresenceV1
import ee.schimke.composeai.uibuilder.protocol.RedoCommandV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderResponseV1
import ee.schimke.composeai.uibuilder.protocol.UndoCommandV1
import ee.schimke.composeai.uibuilder.protocol.UpdateDesignAccessRequestV1
import ee.schimke.composeai.uibuilder.protocol.UpdatePresenceRequestV1

sealed interface ProtocolRequestMapping {
  data class Mapped(val call: UiBuilderServiceCall) : ProtocolRequestMapping

  data class Rejected(val error: UiBuilderServiceError) : ProtocolRequestMapping
}

/** Pure conversion between the released v1 wire contract and the transport-neutral service port. */
object UiBuilderProtocolMapper {
  /**
   * Maps only a typed request plus identity independently established by the transport. There is no
   * overload accepting `HttpRequestEnvelopeV1.actorId` or `McpRequestEnvelopeV1.actorId`: those are
   * untrusted serialized fields. Nested actor fields required by v1 are checked, then stripped.
   */
  fun toServiceCall(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderRequestV1,
  ): ProtocolRequestMapping {
    val mapped =
      when (request) {
        ListCatalogsRequestV1 -> UiBuilderServiceRequest.ListCatalogs
        is CreateDesignRequestV1 -> UiBuilderServiceRequest.CreateDesign(request.document)
        is ListDesignsRequestV1 ->
          UiBuilderServiceRequest.ListDesigns(request.cursor, request.limit)
        is OpenDesignRequestV1 -> UiBuilderServiceRequest.OpenDesign(request.designId)
        is GetDesignAccessRequestV1 -> UiBuilderServiceRequest.GetDesignAccess(request.designId)
        is UpdateDesignAccessRequestV1 ->
          UiBuilderServiceRequest.UpdateDesignAccess(
            request.designId,
            request.baseAccessRevision,
            request.mutations,
          )
        is ApplyOperationRequestV1 -> {
          val submission = request.submission.toServiceSubmission(actor) ?: return actorMismatch()
          UiBuilderServiceRequest.ApplyOperation(submission)
        }
        is GetSnapshotRequestV1 ->
          UiBuilderServiceRequest.GetSnapshot(request.designId, request.revision)
        is GetDeltaRequestV1 ->
          UiBuilderServiceRequest.GetDelta(request.designId, request.afterSequence, request.limit)
        is UpdatePresenceRequestV1 -> {
          if (request.presence.actorId != actor.actorId) return actorMismatch()
          UiBuilderServiceRequest.UpdatePresence(
            request.designId,
            request.presence.toServicePresence(),
          )
        }
        is ExportDesignRequestV1 ->
          UiBuilderServiceRequest.ExportDesign(request.designId, request.revision, request.format)
      }
    return ProtocolRequestMapping.Mapped(UiBuilderServiceCall(actor, mapped))
  }

  fun toProtocolRequest(call: UiBuilderServiceCall): UiBuilderRequestV1 =
    when (val request = call.request) {
      UiBuilderServiceRequest.ListCatalogs -> ListCatalogsRequestV1
      is UiBuilderServiceRequest.CreateDesign -> CreateDesignRequestV1(request.document)
      is UiBuilderServiceRequest.ListDesigns -> ListDesignsRequestV1(request.cursor, request.limit)
      is UiBuilderServiceRequest.OpenDesign -> OpenDesignRequestV1(request.designId)
      is UiBuilderServiceRequest.GetDesignAccess -> GetDesignAccessRequestV1(request.designId)
      is UiBuilderServiceRequest.UpdateDesignAccess ->
        UpdateDesignAccessRequestV1(
          request.designId,
          request.baseAccessRevision,
          request.mutations,
        )
      is UiBuilderServiceRequest.ApplyOperation ->
        ApplyOperationRequestV1(request.submission.toProtocolSubmission(call.actor))
      is UiBuilderServiceRequest.GetSnapshot ->
        GetSnapshotRequestV1(request.designId, request.revision)
      is UiBuilderServiceRequest.GetDelta ->
        GetDeltaRequestV1(request.designId, request.afterSequence, request.limit)
      is UiBuilderServiceRequest.UpdatePresence ->
        UpdatePresenceRequestV1(
          request.designId,
          request.presence.toProtocolPresence(call.actor),
        )
      is UiBuilderServiceRequest.ExportDesign ->
        ExportDesignRequestV1(request.designId, request.revision, request.format)
    }

  fun toProtocolResponse(response: UiBuilderServiceResponse): UiBuilderResponseV1 =
    when (response) {
      is UiBuilderServiceResponse.Catalogs -> CatalogsResponseV1(response.catalogs)
      is UiBuilderServiceResponse.Designs ->
        DesignsResponseV1(response.designs, response.nextCursor)
      is UiBuilderServiceResponse.DesignAccess ->
        DesignAccessResponseV1(response.designId, response.access)
      is UiBuilderServiceResponse.Snapshot -> SnapshotResponseV1(response.snapshot)
      is UiBuilderServiceResponse.OperationOutcome -> OperationOutcomeResponseV1(response.outcome)
      is UiBuilderServiceResponse.Delta -> DeltaResponseV1(response.delta)
      is UiBuilderServiceResponse.PresenceAccepted ->
        PresenceAcceptedResponseV1(response.designId, response.actorId)
      is UiBuilderServiceResponse.Export -> ExportResponseV1(response.artifact)
      is UiBuilderServiceResponse.Error -> ErrorResponseV1(response.error.toProtocol())
    }

  fun toServiceResponse(response: UiBuilderResponseV1): UiBuilderServiceResponse =
    when (response) {
      is CatalogsResponseV1 -> UiBuilderServiceResponse.Catalogs(response.catalogs)
      is DesignsResponseV1 ->
        UiBuilderServiceResponse.Designs(response.designs, response.nextCursor)
      is DesignAccessResponseV1 ->
        UiBuilderServiceResponse.DesignAccess(response.designId, response.access)
      is SnapshotResponseV1 -> UiBuilderServiceResponse.Snapshot(response.snapshot)
      is OperationOutcomeResponseV1 -> UiBuilderServiceResponse.OperationOutcome(response.outcome)
      is DeltaResponseV1 -> UiBuilderServiceResponse.Delta(response.delta)
      is PresenceAcceptedResponseV1 ->
        UiBuilderServiceResponse.PresenceAccepted(response.designId, response.actorId)
      is ExportResponseV1 -> UiBuilderServiceResponse.Export(response.artifact)
      is ErrorResponseV1 -> UiBuilderServiceResponse.Error(response.error.toService())
    }

  fun toProtocolUpdate(designId: String, update: UiBuilderServiceUpdate): DesignUpdateEnvelopeV1 =
    DesignUpdateEnvelopeV1(
      designId = designId,
      update =
        when (update) {
          is UiBuilderServiceUpdate.Snapshot -> SnapshotDesignUpdateV1(update.snapshot)
          is UiBuilderServiceUpdate.Delta -> DeltaDesignUpdateV1(update.delta)
          is UiBuilderServiceUpdate.Presence -> PresenceDesignUpdateV1(update.update)
          is UiBuilderServiceUpdate.Outcome -> OutcomeDesignUpdateV1(update.outcome)
        },
    )

  fun toServiceUpdate(update: DesignUpdateV1): UiBuilderServiceUpdate =
    when (update) {
      is SnapshotDesignUpdateV1 -> UiBuilderServiceUpdate.Snapshot(update.snapshot)
      is DeltaDesignUpdateV1 -> UiBuilderServiceUpdate.Delta(update.delta)
      is PresenceDesignUpdateV1 -> UiBuilderServiceUpdate.Presence(update.update)
      is OutcomeDesignUpdateV1 -> UiBuilderServiceUpdate.Outcome(update.outcome)
    }

  private fun actorMismatch(): ProtocolRequestMapping.Rejected =
    ProtocolRequestMapping.Rejected(
      UiBuilderServiceError(
        code = ServiceErrorCodeV1.UNAUTHORIZED,
        message = "request actor does not match the authenticated actor",
      )
    )
}

private fun DesignSubmissionV1.toServiceSubmission(
  actor: AuthenticatedUiBuilderActor
): UiBuilderSubmission? =
  when (this) {
    is DesignCommandV1 ->
      if (actorId != actor.actorId) null
      else UiBuilderSubmission.Batch(designId, operationId, clientId, baseRevision, operations)
    is UndoCommandV1 ->
      if (actorId != actor.actorId) null
      else
        UiBuilderSubmission.Undo(
          designId,
          operationId,
          clientId,
          baseRevision,
          targetOperationId,
        )
    is RedoCommandV1 ->
      if (actorId != actor.actorId) null
      else
        UiBuilderSubmission.Redo(
          designId,
          operationId,
          clientId,
          baseRevision,
          targetUndoOperationId,
        )
  }

private fun UiBuilderSubmission.toProtocolSubmission(
  actor: AuthenticatedUiBuilderActor
): DesignSubmissionV1 =
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

private fun PresenceV1.toServicePresence(): UiBuilderPresence =
  UiBuilderPresence(
    clientId = clientId,
    displayName = displayName,
    colorArgbHex = colorArgbHex,
    selectedNodeIds = selectedNodeIds,
    pointerX = pointer?.x,
    pointerY = pointer?.y,
    observedRevision = observedRevision,
  )

private fun UiBuilderPresence.toProtocolPresence(actor: AuthenticatedUiBuilderActor): PresenceV1 =
  PresenceV1(
    actorId = actor.actorId,
    clientId = clientId,
    displayName = displayName,
    colorArgbHex = colorArgbHex,
    selectedNodeIds = selectedNodeIds,
    pointer =
      when {
        pointerX == null && pointerY == null -> null
        pointerX != null && pointerY != null -> PointerV1(pointerX, pointerY)
        else -> error("presence pointer coordinates must both be null or both be present")
      },
    observedRevision = observedRevision,
  )

private fun UiBuilderServiceError.toProtocol(): ServiceErrorV1 =
  ServiceErrorV1(
    code,
    message,
    retryable,
    currentRevision,
    currentAccessRevision,
    retainedFromSequence,
  )

private fun ServiceErrorV1.toService(): UiBuilderServiceError =
  UiBuilderServiceError(
    code,
    message,
    retryable,
    currentRevision,
    currentAccessRevision,
    retainedFromSequence,
  )
