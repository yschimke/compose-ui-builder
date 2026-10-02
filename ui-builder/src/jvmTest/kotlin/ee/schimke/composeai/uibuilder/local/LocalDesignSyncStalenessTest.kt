package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.canonicalDocumentHash
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ConflictCodeV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.EnvironmentFieldV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.SetFontScaleEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderResponseV1
import ee.schimke.composeai.uibuilder.protocol.UpdateEnvironmentMutationV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.ProtocolRequestMapping
import ee.schimke.composeai.uibuilder.service.UiBuilderProtocolMapper
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderStateStorage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Sync against the real hosted service: an offline run overwriting a server edit made after the run
 * started is reported on whichever command does it, not only on the first.
 *
 * `LocalDesignSyncBackTest` holds the base chain to a fake server; this holds the conflict answers
 * to the real reducer, through `UiBuilderProtocolMapper` the way a transport reaches it.
 */
class LocalDesignSyncStalenessTest {
  private val executor = CurrentM3UiBuilderCatalogExecutor()
  private val catalogV1: CatalogCapabilityV1 =
    executor.listCatalogs().single { it.benchmark.catalogSystemId == "m3-catalog" }
  private val catalog =
    CapabilityCatalogParser.parse(Json.encodeToString(CatalogCapabilityV1.serializer(), catalogV1))
  private val seed: DesignDocumentV1 =
    UiBuilderNewDesignSeed.document(
        designId = DESIGN_ID,
        catalogSystemId = "m3-catalog",
        templateId = UiBuilderNewDesignSeed.DEFAULT_TEMPLATE,
        catalogRevision = catalog.benchmark.catalogRevision,
        nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
        fixture =
          Json.parseToJsonElement(
              checkNotNull(javaClass.getResource("/jetcaster-discover-operations-v1.json"))
                .readText()
            )
            .jsonObject,
      )
      .toDesignDocumentV1()
      .let { it.copy(catalogPin = requireNotNull(executor.reference(catalogV1))) }

  @Test
  fun `a later command of the run that overwrites a server edit is reported, once`() = runBlocking {
    val server = HostedServer()
    val record = server.forkAndEditConcurrently()

    val report = sync(record, server::execute)

    assertTrue(report.complete, report.summary())
    assertEquals(
      listOf(null, FORK, FORK),
      server.sent.map { it.stalenessBaseRevision },
      "the first command's base is the fork; every later one says it was written against the fork",
    )
    assertEquals(
      listOf(emptyList(), listOf(EnvironmentFieldV1.FONT_SCALE), emptyList()),
      report.landed.map { landed -> landed.conflicts.map { it.environmentField } },
      "c2 overwrites the server's font scale; c3 overwrites only c2's, which its author wrote",
    )
    assertEquals(
      ConflictCodeV1.STALE_ENVIRONMENT_WRITE,
      report.landed[1].conflicts.single().code,
    )
  }

  @Test
  fun `the same run sent without the field reports nothing, as it did before`() = runBlocking {
    val server = HostedServer()
    val record = server.forkAndEditConcurrently()

    // A client that predates the field: every command is judged from its own chain base.
    val report =
      sync(record) { request ->
        server.execute(
          if (request is ApplyOperationRequestV1)
            ApplyOperationRequestV1(
              (request.submission as DesignCommandV1)
                .newBuilder()
                .also { it.stalenessBaseRevision = null }
                .build()
            )
          else request
        )
      }

    assertTrue(report.complete, report.summary())
    assertEquals(
      listOf(0, 0, 0),
      report.landed.map { it.conflicts.size },
      "c2's base is c1's revision, after the server edit, so the overwrite goes unreported",
    )
  }

  @Test
  fun `a live edit sends no staleness base and is judged against its own base`() = runBlocking {
    val server = HostedServer()
    server.forkAndEditConcurrently()

    // A live edit from the fork, after the server edit: reported, from its own base.
    val stale = server.applyFontScale("live-stale", base = FORK, value = 1.4)
    assertEquals(null, server.sent.last().stalenessBaseRevision)
    assertEquals(listOf(EnvironmentFieldV1.FONT_SCALE), stale.conflicts.map { it.environmentField })

    // A live edit at the head: nothing it did not see.
    val current = server.applyFontScale("live-current", base = stale.committedRevision, value = 1.5)
    assertTrue(current.conflicts.isEmpty())
  }

  private suspend fun sync(
    record: LocalDesignRecordV1,
    execute: suspend (UiBuilderRequestV1) -> UiBuilderHttpResult,
  ): LocalSyncReport =
    assertIs<LocalSyncResult.Replayed>(
        LocalDesignSyncBack(ACTOR.actorId, OFFLINE_CLIENT, execute).sync(record)
      )
      .report

  /** The hosted service behind its protocol mapper, recording every command it is sent. */
  private inner class HostedServer {
    private val service =
      PersistentUiBuilderService(
        storage = MemoryStorage(),
        catalogs = executor,
        exporter = { _ -> error("no export here") },
        clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC),
      )
    val sent = mutableListOf<DesignCommandV1>()

    suspend fun execute(request: UiBuilderRequestV1): UiBuilderHttpResult {
      if (request is ApplyOperationRequestV1) sent += request.submission as DesignCommandV1
      return UiBuilderHttpResult.Response("request", respond(request))
    }

    private suspend fun respond(request: UiBuilderRequestV1): UiBuilderResponseV1 =
      when (val mapping = UiBuilderProtocolMapper.toServiceCall(ACTOR, request)) {
        is ProtocolRequestMapping.Mapped ->
          UiBuilderProtocolMapper.toProtocolResponse(service.execute(mapping.call))
        is ProtocolRequestMapping.Rejected ->
          UiBuilderProtocolMapper.toProtocolResponse(UiBuilderServiceResponse.Error(mapping.error))
      }

    /**
     * Creates the design, records a browser copy forked at [FORK], then lands a live font-scale
     * edit on the server the copy never sees. Returns the copy, with an offline run of three
     * commands: c1 changes the height, c2 and c3 the font scale.
     */
    suspend fun forkAndEditConcurrently(): LocalDesignRecordV1 {
      assertIs<SnapshotResponseV1>(respond(CreateDesignRequestV1(seed)))
      val forked =
        assertIs<SnapshotResponseV1>(respond(GetSnapshotRequestV1(DESIGN_ID, FORK)))
          .snapshot
          .state
          .document
      assertTrue(applyFontScale("server-edit", base = FORK, value = 1.2).conflicts.isEmpty())
      sent.clear()

      fun offline(operationId: String, field: String, value: JsonPrimitive) =
        LocalSubmissionRecordV1.Batch(
          DesignCommand(
            designId = DESIGN_ID,
            operationId = operationId,
            actorId = ACTOR.actorId,
            clientId = OFFLINE_CLIENT,
            baseRevision = 0,
            operations = listOf(DesignOperation.SetEnvironment(field, value)),
          )
        )
      return LocalDesignRecordV1(
        designId = DESIGN_ID,
        catalogSystemId = "m3-catalog",
        seed = forked.toUiBuilderDocument(),
        log =
          listOf(
            offline("c1", "heightDp", JsonPrimitive(seed.environment.heightDp + 8)),
            offline("c2", "fontScale", JsonPrimitive(1.3)),
            offline("c3", "fontScale", JsonPrimitive(1.35)),
          ),
        origin =
          LocalDesignOriginV1(
            server = "https://preview.example",
            designId = DESIGN_ID,
            revision = FORK.toInt(),
            sequence = 0,
            documentDigest = forked.canonicalDocumentHash(),
            takenAtEpochMillis = 0,
          ),
      )
    }

    suspend fun applyFontScale(operationId: String, base: Long, value: Double): AcceptedOutcomeV1 {
      val command =
        DesignCommandV1.Builder(
            designId = DESIGN_ID,
            operationId = operationId,
            actorId = ACTOR.actorId,
            clientId = LIVE_CLIENT,
            baseRevision = base,
            operations =
              listOf(UpdateEnvironmentMutationV1(listOf(SetFontScaleEnvironmentChangeV1(value)))),
          )
          .build()
      val response =
        assertIs<UiBuilderHttpResult.Response>(execute(ApplyOperationRequestV1(command)))
      return assertIs<AcceptedOutcomeV1>(
        assertIs<OperationOutcomeResponseV1>(response.response).outcome
      )
    }
  }

  private class MemoryStorage : UiBuilderStateStorage {
    private var bytes: ByteArray? = null

    override fun load(): ByteArray? = bytes?.copyOf()

    override fun replace(value: ByteArray) {
      bytes = value.copyOf()
    }
  }

  private companion object {
    const val DESIGN_ID = "sync-staleness"
    const val OFFLINE_CLIENT = "offline-browser"
    const val LIVE_CLIENT = "live-browser"
    const val FORK = 0L
    val ACTOR = AuthenticatedUiBuilderActor("sync-actor")
  }
}
