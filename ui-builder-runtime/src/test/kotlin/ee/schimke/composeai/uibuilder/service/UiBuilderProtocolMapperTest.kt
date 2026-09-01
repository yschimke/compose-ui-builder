package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UiBuilderProtocolMapperTest {
  private val trusted = AuthenticatedUiBuilderActor("trusted-actor")

  @Test
  fun `every request and collaboration submission round trips losslessly`() {
    val node = DesignNodeV1(id = "node", componentId = "m3.Text")
    val location = NodeLocationV1(afterNodeId = "anchor")
    val mutations =
      listOf(
        InsertNodeMutationV1(node, location),
        MoveNodeMutationV1("node", location),
        DeleteNodeMutationV1("node"),
        RestoreNodeMutationV1("node", location),
        SetPropertyMutationV1("node", "text", StringValueV1("Hello")),
      )
    val requests =
      listOf(
        ListCatalogsRequestV1,
        CreateDesignRequestV1(document()),
        ListDesignsRequestV1(cursor = "next", limit = 17),
        OpenDesignRequestV1("design"),
        GetDesignAccessRequestV1("design"),
        UpdateDesignAccessRequestV1(
          designId = "design",
          baseAccessRevision = 3,
          mutations =
            listOf(
              GrantActorAccessMutationV1(
                actorId = "collaborator",
                role = DesignAccessRoleV1.EDITOR,
                allowedActions = listOf(DesignAccessActionV1.READ, DesignAccessActionV1.WRITE),
              ),
              RevokeActorAccessMutationV1("former-collaborator"),
              CreateDesignShareLinkMutationV1(
                role = DesignAccessRoleV1.VIEWER,
                allowedActions = listOf(DesignAccessActionV1.READ),
                expiresAtEpochMillis = 1234,
              ),
              RevokeDesignShareLinkMutationV1("share"),
              TransferDesignOwnershipMutationV1("next-owner"),
            ),
        ),
        ApplyOperationRequestV1(
          DesignCommandV1("design", "batch", trusted.actorId, "browser", 4, mutations)
        ),
        ApplyOperationRequestV1(
          UndoCommandV1("design", "undo", trusted.actorId, "browser", 5, "batch")
        ),
        ApplyOperationRequestV1(
          RedoCommandV1("design", "redo", trusted.actorId, "browser", 6, "undo")
        ),
        GetSnapshotRequestV1("design", revision = 4),
        GetDeltaRequestV1("design", afterSequence = 9, limit = 31),
        UpdatePresenceRequestV1("design", presence()),
        ExportDesignRequestV1("design", revision = 7, format = ExportFormatV1.SVG),
      )

    requests.forEach { request ->
      val mapped =
        assertIs<ProtocolRequestMapping.Mapped>(
          UiBuilderProtocolMapper.toServiceCall(trusted, request)
        )
      assertEquals(trusted, mapped.call.actor)
      assertEquals(request, UiBuilderProtocolMapper.toProtocolRequest(mapped.call))
    }
  }

  @Test
  fun `all response variants round trip losslessly`() {
    val snapshot = snapshot()
    val accepted = acceptedOutcome()
    val responses =
      listOf(
        CatalogsResponseV1(listOf(catalog())),
        DesignsResponseV1(listOf(listItem()), nextCursor = "next"),
        DesignAccessResponseV1("design", access()),
        SnapshotResponseV1(snapshot),
        OperationOutcomeResponseV1(accepted),
        OperationOutcomeResponseV1(
          RejectedOutcomeV1(
            operationId = "rejected",
            currentRevision = 4,
            code = RejectionCodeV1.INVALID_LOCATION,
            message = "bad anchor",
            operationIndex = 2,
            nodeId = "node",
            field = "beforeNodeId",
          )
        ),
        DeltaResponseV1(
          ServiceDeltaV1(
            designId = "design",
            afterSequence = 6,
            throughSequence = 7,
            currentRevision = 4,
            retainedFromSequence = 2,
            operations = emptyList(),
            hasMore = true,
          )
        ),
        PresenceAcceptedResponseV1("design", trusted.actorId),
        ExportResponseV1(
          ExportArtifactV1(
            format = ExportFormatV1.SVG,
            mediaType = "image/svg+xml",
            encoding = ExportEncodingV1.UTF8,
            content = "<svg/>",
            contentDigest = "digest",
            diagnostics =
              listOf(
                ExportDiagnosticV1(
                  DiagnosticSeverityV1.WARNING,
                  "RASTER_FALLBACK",
                  "embedded image",
                  "node",
                )
              ),
          )
        ),
        ErrorResponseV1(
          ServiceErrorV1(
            code = ServiceErrorCodeV1.SNAPSHOT_REQUIRED,
            message = "compacted",
            retryable = true,
            currentRevision = 4,
            currentAccessRevision = 3,
            retainedFromSequence = 2,
          )
        ),
      )

    responses.forEach { response ->
      assertEquals(
        response,
        UiBuilderProtocolMapper.toProtocolResponse(
          UiBuilderProtocolMapper.toServiceResponse(response)
        ),
      )
    }
  }

  @Test
  fun `all subscription update variants round trip losslessly`() {
    val updates =
      listOf(
        SnapshotDesignUpdateV1(snapshot()),
        DeltaDesignUpdateV1(
          ServiceDeltaV1(
            designId = "design",
            afterSequence = 1,
            throughSequence = 1,
            currentRevision = 4,
            retainedFromSequence = 1,
            operations = emptyList<CommittedOperationV1>(),
          )
        ),
        PresenceDesignUpdateV1(PresenceUpsertV1(presence())),
        PresenceDesignUpdateV1(PresenceLeaveV1(trusted.actorId)),
        OutcomeDesignUpdateV1(acceptedOutcome()),
      )

    updates.forEach { update ->
      val envelope =
        UiBuilderProtocolMapper.toProtocolUpdate(
          designId = "design",
          update = UiBuilderProtocolMapper.toServiceUpdate(update),
        )
      assertEquals("design", envelope.designId)
      assertEquals(update, envelope.update)
    }
  }

  @Test
  fun `serialized envelope actor cannot choose the service principal`() {
    val forgedHttp =
      HttpRequestEnvelopeV1(
        requestId = "http",
        actorId = "forged",
        request = ListDesignsRequestV1(),
      )
    val forgedMcp =
      McpRequestEnvelopeV1(
        callId = "mcp",
        actorId = "forged",
        request = GetSnapshotRequestV1("design"),
      )

    listOf(forgedHttp.request, forgedMcp.request).forEach { request ->
      val mapped =
        assertIs<ProtocolRequestMapping.Mapped>(
          UiBuilderProtocolMapper.toServiceCall(trusted, request)
        )
      assertEquals(trusted, mapped.call.actor)
    }
  }

  @Test
  fun `nested requester actor is checked then removed from collaboration requests`() {
    val forgedSubmission =
      ApplyOperationRequestV1(DesignCommandV1("design", "op", "forged", "browser", 3, emptyList()))
    val forgedPresence = UpdatePresenceRequestV1("design", presence().copy(actorId = "forged"))

    listOf(forgedSubmission, forgedPresence).forEach { request ->
      val rejected =
        assertIs<ProtocolRequestMapping.Rejected>(
          UiBuilderProtocolMapper.toServiceCall(trusted, request)
        )
      assertEquals(ServiceErrorCodeV1.UNAUTHORIZED, rejected.error.code)
    }

    val mapped =
      assertIs<ProtocolRequestMapping.Mapped>(
          UiBuilderProtocolMapper.toServiceCall(
            trusted,
            ApplyOperationRequestV1(
              UndoCommandV1("design", "undo", trusted.actorId, "browser", 4, "target")
            ),
          )
        )
        .call
    val request = assertIs<UiBuilderServiceRequest.ApplyOperation>(mapped.request)
    val submission = assertIs<UiBuilderSubmission.Undo>(request.submission)
    assertEquals("browser", submission.clientId)
    // UiBuilderSubmission has no actor property; execution must use mapped.actor.
    assertEquals(trusted, mapped.actor)
  }

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder/v1",
      id = "design",
      title = "Discover",
      revision = 4,
      catalogPin = catalogReference(),
      environment =
        DesignEnvironmentV1(
          widthDp = 1280,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.DARK,
          locale = "en-GB",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
        ),
      roots = emptyList(),
      nodes = emptyMap(),
    )

  private fun catalogReference() =
    CatalogReferenceV1("m3", "catalog-revision", "capability-digest", "m3-runtime")

  private fun catalog(): CatalogCapabilityV1 =
    CatalogCapabilityV1(
      schema = "compose-catalog-capabilities/v1",
      benchmark = CatalogBenchmarkV1("m3", "source", "m3", "catalog-revision", "m3-runtime"),
      components = emptyList(),
      exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = true, png = true),
    )

  private fun access(): DesignAccessControlV1 =
    DesignAccessControlV1(accessRevision = 3, ownerActorId = "owner")

  private fun listItem(): DesignListItemV1 =
    DesignListItemV1(
      designId = "design",
      title = "Discover",
      revision = 4,
      accessRevision = 3,
      catalogPin = catalogReference(),
      ownerActorId = "owner",
      requesterAccess =
        DesignActorAccessV1(
          trusted.actorId,
          DesignAccessRoleV1.EDITOR,
          listOf(DesignAccessActionV1.READ, DesignAccessActionV1.WRITE),
        ),
    )

  private fun snapshot(): ServiceSnapshotV1 =
    ServiceSnapshotV1(
      designId = "design",
      state = DesignStateV1(lastSequence = 7, document = document()),
      catalog = catalog(),
      retainedFromSequence = 2,
      presence = listOf(presence()),
      access = access(),
    )

  private fun presence(): PresenceV1 =
    PresenceV1(
      actorId = trusted.actorId,
      clientId = "browser",
      displayName = "Trusted",
      colorArgbHex = "#FF112233",
      selectedNodeIds = listOf("node"),
      pointer = PointerV1(12.5, 23.5),
      observedRevision = 4,
    )

  private fun acceptedOutcome(): AcceptedOutcomeV1 =
    AcceptedOutcomeV1(
      operationId = "op",
      committedRevision = 4,
      sequence = 7,
      documentHash = "hash",
      idempotentReplay = false,
      conflicts = listOf(CommandConflictV1(ConflictCodeV1.STALE_MOVE, "node", null, 3)),
    )
}
