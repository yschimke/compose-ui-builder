package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.EditorSubmission
import ee.schimke.composeai.uibuilder.client.MonotonicUiBuilderRequestIds
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolHttpClient
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCardinalityV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * The editor's own client, talking to the service in the page.
 *
 * Driven through [UiBuilderProtocolHttpClient] rather than by calling the service directly, because
 * the claim being tested is that the local mode is reached by *the same client*: the envelope, the
 * request-id correlation and the strict codec are all part of what has to work offline.
 *
 * On the JVM rather than in `commonTest` only because the service's front door is `suspend` and
 * this module's common test source set has no coroutine test runner. The code under test is common.
 */
class LocalUiBuilderServiceTest {
  private val storage = InMemoryLocalDesignStorage()
  private val store = LocalDesignStore(storage)
  private var catalogsAvailable = true
  private val service =
    LocalUiBuilderService(
      store = store,
      catalogs = {
        if (catalogsAvailable) listOf(catalog)
        else throw LocalDesignStorageException("the network is gone")
      },
      clock = { 1_700_000_000_000 },
    )
  private val client =
    UiBuilderProtocolHttpClient(
      actorId = "tester",
      endpoint = "local",
      transport = LocalUiBuilderHttpTransport(service),
      requestIds = MonotonicUiBuilderRequestIds("test-client"),
    )

  private suspend fun seed() = service.create(LocalDesignFixtures.document())

  @Test
  fun `a design this browser does not hold is not found`() = runBlocking {
    val result = client.execute(OpenDesignRequestV1("nothing-here"))

    val error = assertIs<UiBuilderHttpResult.ServiceError>(result)
    assertEquals(ServiceErrorCodeV1.NOT_FOUND, error.error.code)
  }

  @Test
  fun `a created design opens with the catalog it is pinned to`() = runBlocking {
    seed()

    val result = client.execute(OpenDesignRequestV1(LocalDesignFixtures.DESIGN_ID))

    val response = assertIs<UiBuilderHttpResult.Response>(result)
    val snapshot = assertIs<SnapshotResponseV1>(response.response).snapshot
    assertEquals(LocalDesignFixtures.DESIGN_ID, snapshot.designId)
    assertEquals(0, snapshot.state.lastSequence)
    assertEquals(LocalDesignFixtures.CATALOG_SYSTEM_ID, snapshot.catalog.benchmark.catalogSystemId)
  }

  @Test
  fun `creating a design that already exists is refused rather than overwriting it`() =
    runBlocking {
      seed()

      val second = service.create(LocalDesignFixtures.document())

      assertIs<ErrorResponseV1>(second)
      Unit
    }

  @Test
  fun `an edit commits, is stored, and the next snapshot shows it`() = runBlocking {
    seed()

    val applied =
      client.execute(
        ApplyOperationRequestV1(
          EditorSubmission.Batch(LocalDesignFixtures.insert("text-1", baseRevision = 0).command)
            .toProtocolSubmission(
              actorId = "tester",
              clientId = "test-client",
              authoritativeRevision = 0,
            )
        )
      )

    val response = assertIs<UiBuilderHttpResult.Response>(applied)
    val outcome = assertIs<OperationOutcomeResponseV1>(response.response).outcome
    val accepted = assertIs<AcceptedOutcomeV1>(outcome)
    assertEquals(1, accepted.committedRevision)
    assertEquals(1, accepted.sequence)
    assertTrue(accepted.documentHash.isNotEmpty())
    assertEquals(LocalPersistence.Stored, service.lastPersistence)

    val reopened = client.execute(OpenDesignRequestV1(LocalDesignFixtures.DESIGN_ID))
    val snapshot =
      assertIs<SnapshotResponseV1>(assertIs<UiBuilderHttpResult.Response>(reopened).response)
    assertTrue("text-1" in snapshot.snapshot.state.document.nodes)
    // Not just in memory: a page that reloads reads this and replays it.
    assertTrue(store.read(LocalDesignFixtures.DESIGN_ID)!!.log.isNotEmpty())
  }

  @Test
  fun `the catalog list is what the editor gets, and its absence is named`() = runBlocking {
    val listed = client.execute(ListCatalogsRequestV1)
    assertIs<CatalogsResponseV1>(assertIs<UiBuilderHttpResult.Response>(listed).response)

    catalogsAvailable = false
    val refused = client.execute(ListCatalogsRequestV1)

    val error = assertIs<UiBuilderHttpResult.ServiceError>(refused)
    assertEquals(ServiceErrorCodeV1.CATALOG_UNAVAILABLE, error.error.code)
  }

  private companion object {
    /** The two components the fixture design uses, and nothing else. */
    val catalog =
      CatalogCapabilityV1(
        schema = "compose-ui-builder-catalog/v1",
        benchmark =
          CatalogBenchmarkV1(
            id = "test",
            sourceRevision = "test",
            catalogSystemId = LocalDesignFixtures.CATALOG_SYSTEM_ID,
            catalogRevision = "test",
            nativeRuntimeId = "test",
          ),
        components =
          listOf(
            ComponentCapabilityV1(
              componentId = "m3/column",
              displayName = "Column",
              role = "container",
              slots =
                listOf(
                  SlotCapabilityV1(
                    name = "content",
                    cardinality = SlotCardinalityV1(),
                    ordered = true,
                  )
                ),
              wasm = WasmCapabilityV1(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED),
            ),
            ComponentCapabilityV1(
              componentId = "m3/text",
              displayName = "Text",
              role = "leaf",
              wasm = WasmCapabilityV1(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED),
            ),
          ),
      )
  }
}
