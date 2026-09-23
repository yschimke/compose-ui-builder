package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.local.InMemoryLocalDesignStorage
import ee.schimke.composeai.uibuilder.local.LocalDesignStore
import ee.schimke.composeai.uibuilder.local.LocalUiBuilderService
import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderResponseV1
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The editor's in-page service and the hosted runtime service answer the same v1 requests alike.
 *
 * Two implementations answer the released protocol: `LocalUiBuilderService`, which the browser's
 * local mode, the desktop app and the IntelliJ plugin run, and `PersistentUiBuilderService`, which
 * compose-preview-server hosts. The editor above them is the same client with the same ordering
 * rules, so every place they disagree about an answer is a place a design behaves differently
 * offline than on the server. Each scenario here drives both with the same requests — the hosted
 * one through `UiBuilderProtocolMapper`, the way a transport reaches it — and asserts the answers
 * match, down to the committed document hash.
 *
 * What is deliberately not compared: access control, exports and catalog upgrades, which the local
 * service refuses by name because there is nobody to share with and nothing to render with.
 */
class ServiceConformanceTest {
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

  private fun services(): List<Pair<String, suspend (UiBuilderRequestV1) -> UiBuilderResponseV1>> {
    val local =
      LocalUiBuilderService(
        store = LocalDesignStore(InMemoryLocalDesignStorage()),
        catalogs = { listOf(catalogV1) },
      )
    val hosted =
      PersistentUiBuilderService(
        storage = MemoryStorage(),
        catalogs = executor,
        exporter = { _ -> error("no export in conformance") },
        clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC),
      )
    return listOf(
      "local" to { request -> local.execute(request) },
      "hosted" to
        { request ->
          when (val mapping = UiBuilderProtocolMapper.toServiceCall(ACTOR, request)) {
            is ProtocolRequestMapping.Mapped ->
              UiBuilderProtocolMapper.toProtocolResponse(hosted.execute(mapping.call))
            is ProtocolRequestMapping.Rejected ->
              UiBuilderProtocolMapper.toProtocolResponse(
                UiBuilderServiceResponse.Error(mapping.error)
              )
          }
        },
    )
  }

  /** Runs [scenario] against both services and asserts they produced the same observations. */
  private fun conform(
    scenario: suspend (suspend (UiBuilderRequestV1) -> UiBuilderResponseV1) -> List<Any?>
  ) = runBlocking {
    val observed = services().associate { (name, service) -> name to scenario(service) }
    assertEquals(observed.getValue("hosted"), observed.getValue("local"))
  }

  @Test
  fun `a created design opens as the document it was created from`() = conform { service ->
    val created = assertIs<SnapshotResponseV1>(service(CreateDesignRequestV1(seed)))
    val opened = assertIs<SnapshotResponseV1>(service(OpenDesignRequestV1(DESIGN_ID)))
    listOf(created.snapshot.state.document, opened.snapshot.state.document)
  }

  @Test
  fun `an unknown design is not found`() = conform { service ->
    listOf(assertIs<ErrorResponseV1>(service(OpenDesignRequestV1("missing"))).error.code)
  }

  @Test
  fun `an accepted edit commits the same revision and document hash`() = conform { service ->
    service(CreateDesignRequestV1(seed))
    val outcome = assertIs<AcceptedOutcomeV1>(apply(service, heightDelta = 8, baseRevision = 0))
    val reopened = assertIs<SnapshotResponseV1>(service(OpenDesignRequestV1(DESIGN_ID)))
    listOf(outcome.committedRevision, outcome.documentHash, reopened.snapshot.state.document)
  }

  @Test
  fun `a replayed operation is answered idempotently`() = conform { service ->
    service(CreateDesignRequestV1(seed))
    val first = assertIs<AcceptedOutcomeV1>(apply(service, heightDelta = 8, baseRevision = 0))
    val replay = assertIs<AcceptedOutcomeV1>(apply(service, heightDelta = 8, baseRevision = 0))
    listOf(first.committedRevision, replay.committedRevision, replay.idempotentReplay)
  }

  @Test
  fun `a command against a missing node is rejected alike`() = conform { service ->
    service(CreateDesignRequestV1(seed))
    val submission =
      EditorSubmission.Batch(
          DesignCommand(
            designId = DESIGN_ID,
            operationId = "delete-missing",
            actorId = ACTOR.actorId,
            clientId = CLIENT,
            baseRevision = 0,
            operations = listOf(DesignOperation.DeleteNode("nothing-here")),
          )
        )
        .toProtocolSubmission(ACTOR.actorId, CLIENT, 0)
    val outcome =
      assertIs<OperationOutcomeResponseV1>(service(ApplyOperationRequestV1(submission))).outcome
    val rejected = assertIs<RejectedOutcomeV1>(outcome)
    listOf(rejected.code, rejected.currentRevision, rejected.nodeId)
  }

  /** One environment edit made the way the editor makes it: through the reducer. */
  private suspend fun apply(
    service: suspend (UiBuilderRequestV1) -> UiBuilderResponseV1,
    heightDelta: Int,
    baseRevision: Int,
  ): Any {
    val reducer = UiBuilderEditorReducer(catalog, ACTOR.actorId, CLIENT, operationIdPrefix = CLIENT)
    val initial = reducer.initial(seed.toUiBuilderDocument())
    val settings = initial.document.screenEnvironmentSettings()
    val edited =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          settings.copy(heightDp = settings.heightDp + heightDelta)
        ),
      )
    val submission =
      requireNotNull(reducer.acceptedSubmission(initial, edited))
        .toProtocolSubmission(ACTOR.actorId, CLIENT, baseRevision)
    return assertIs<OperationOutcomeResponseV1>(service(ApplyOperationRequestV1(submission)))
      .outcome
  }

  private class MemoryStorage : UiBuilderStateStorage {
    private var bytes: ByteArray? = null

    override fun load(): ByteArray? = bytes?.copyOf()

    override fun replace(value: ByteArray) {
      bytes = value.copyOf()
    }
  }

  private companion object {
    const val DESIGN_ID = "conformance"
    const val CLIENT = "conformance-client"
    val ACTOR = AuthenticatedUiBuilderActor("conformance-actor")
  }
}
