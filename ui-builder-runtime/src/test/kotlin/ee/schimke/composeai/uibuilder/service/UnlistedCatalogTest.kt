package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.CatalogListing
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A host can serve a catalog without listing it: it is still served, pinned, created against and
 * opened by id, and `list-catalogs` carries it with the unlisted mark so the chooser can skip it.
 */
class UnlistedCatalogTest {
  private val m3 = CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID
  private val wearM3 = CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID

  private fun executor(unlisted: Set<String>) =
    CurrentM3UiBuilderCatalogExecutor.Builder()
      .also {
        it.catalogSystemIds = linkedSetOf(m3, wearM3)
        it.published = PublishedCatalogFixtures.publishedFor(setOf(m3, wearM3))
        it.unlistedCatalogIds = unlisted
      }
      .build()

  @Test
  fun `by default every served catalog is listed`() {
    val executor = executor(emptySet())
    assertEquals(emptySet(), executor.unlistedCatalogIds)
    assertEquals(
      listOf(m3, wearM3),
      CatalogListing.listed(executor.listCatalogs()).map { it.benchmark.catalogSystemId },
    )
  }

  @Test
  fun `an unlisted catalog is still served, marked, and resolves by its pin`() {
    val executor = executor(setOf(wearM3, "not-served"))
    val served = executor.listCatalogs()
    assertEquals(listOf(m3, wearM3), served.map { it.benchmark.catalogSystemId })
    assertEquals(listOf(m3), CatalogListing.listed(served).map { it.benchmark.catalogSystemId })

    val wear = served.single { it.benchmark.catalogSystemId == wearM3 }
    assertTrue(CatalogListing.isUnlisted(wear))
    val reference = assertNotNull(executor.reference(wear))
    assertEquals(wear, executor.resolve(reference))
    assertEquals(
      executor(emptySet())
        .reference(
          executor(emptySet()).listCatalogs().single { it.benchmark.catalogSystemId == wearM3 }
        ),
      reference,
      "unlisting a catalog must not move its pin, or toggling it would strand designs",
    )
  }

  @Test
  fun `list-catalogs keeps the unlisted catalog, with its pin, and a design opens against it`() {
    val executor = executor(setOf(wearM3))
    val service =
      PersistentUiBuilderService(
        designStore = UiBuilderDesignStateStore.open(createTempDirectory("unlisted")),
        catalogs = executor,
        exporter = UiBuilderExportExecutor { error("no export in this test") },
        clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
      )
    val listed =
      assertIs<UiBuilderServiceResponse.Catalogs>(
        execute(service, UiBuilderServiceRequest.ListCatalogs)
      )
    val wear = listed.catalogs.single { it.benchmark.catalogSystemId == wearM3 }
    assertTrue(CatalogListing.isUnlisted(wear))
    assertFalse(
      CatalogListing.isUnlisted(listed.catalogs.single { it.benchmark.catalogSystemId == m3 })
    )
    assertTrue(wearM3 in listed.pins)

    val fixture =
      Json.parseToJsonElement(
          File("../docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
            .readText()
        )
        .jsonObject
    val document =
      publishedTestSeed(
          systemId = wearM3,
          templateId = UiBuilderNewDesignSeed.WEAR_SCREEN_TEMPLATE,
          designId = "pilot-design",
          catalogRevision = wear.benchmark.catalogRevision,
          nativeRuntimeId = wear.benchmark.nativeRuntimeId,
        )
        .toDesignDocumentV1()
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, UiBuilderServiceRequest.CreateDesign(document))
    )
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, UiBuilderServiceRequest.OpenDesign("pilot-design"))
    )
  }

  private fun execute(
    service: PersistentUiBuilderService,
    request: UiBuilderServiceRequest,
  ): UiBuilderServiceResponse {
    var completion: Result<UiBuilderServiceResponse>? = null
    suspend { service.execute(UiBuilderServiceCall(AuthenticatedUiBuilderActor("owner"), request)) }
      .startCoroutine(
        object : Continuation<UiBuilderServiceResponse> {
          override val context = EmptyCoroutineContext

          override fun resumeWith(result: Result<UiBuilderServiceResponse>) {
            completion = result
          }
        }
      )
    return checkNotNull(completion) { "suspended without completing" }.getOrThrow()
  }
}
