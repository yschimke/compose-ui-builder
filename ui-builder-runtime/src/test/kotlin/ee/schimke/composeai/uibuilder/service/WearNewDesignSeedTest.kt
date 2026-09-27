package ee.schimke.composeai.uibuilder.service

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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Both `wear-m3` templates the New design form offers are documents the service accepts. */
class WearNewDesignSeedTest {
  private val executor =
    PublishedCatalogFixtures.executor(
      catalogSystemIds =
        setOf(
          CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
        )
    )

  private val wear =
    executor.listCatalogs().single {
      it.benchmark.catalogSystemId == CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID
    }

  private val fixture =
    Json.parseToJsonElement(
        File("../docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json").readText()
      )
      .jsonObject

  private fun seed(templateId: String) =
    UiBuilderNewDesignSeed.document(
        designId = "wear-seed",
        catalogSystemId = CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
        templateId = templateId,
        catalogRevision = wear.benchmark.catalogRevision,
        nativeRuntimeId = wear.benchmark.nativeRuntimeId,
        fixture = fixture,
      )
      .toDesignDocumentV1()

  @Test
  fun `the blank wear screen validates`() {
    assertNull(executor.validate(seed(UiBuilderNewDesignSeed.WEAR_SCREEN_TEMPLATE), wear))
  }

  @Test
  fun `the wear list validates`() {
    assertNull(executor.validate(seed(UiBuilderNewDesignSeed.WEAR_LIST_TEMPLATE), wear))
  }

  @Test
  fun `the service creates both`() {
    val service =
      PersistentUiBuilderService(
        designStore = UiBuilderDesignStateStore.open(createTempDirectory("wear-seed")),
        catalogs = executor,
        exporter = UiBuilderExportExecutor { error("no export in this test") },
        clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
      )
    for (template in
      listOf(
        UiBuilderNewDesignSeed.WEAR_SCREEN_TEMPLATE,
        UiBuilderNewDesignSeed.WEAR_LIST_TEMPLATE,
      )) {
      val document = seed(template).copy(id = "wear-$template")
      val response = runSuspend {
        service.execute(
          UiBuilderServiceCall(
            AuthenticatedUiBuilderActor("owner"),
            UiBuilderServiceRequest.CreateDesign(document),
          )
        )
      }
      assertIs<UiBuilderServiceResponse.Snapshot>(response, "$template: $response")
    }
  }

  private fun <T> runSuspend(block: suspend () -> T): T {
    var completion: Result<T>? = null
    block.startCoroutine(
      object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
          completion = result
        }
      }
    )
    return checkNotNull(completion) { "suspended without completing" }.getOrThrow()
  }
}
