package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.CatalogExportRouting
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComposeSourceExportCapabilityV1
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `remote-widgets`, the phone launcher widget design system remote-m3-catalog publishes from
 * `:widget-catalog`, under the catalog-owned cutover.
 *
 * It is the catalog the cutover fits best, and the one that most needs it: the builder synthesises
 * nothing for it, so its definition was only ever its own; its frame sizes are already catalog data
 * (`frame.geometry.sizesDp`); and it publishes its own seed templates. Off the flag it gets the
 * builder's mobile `blank` and `jetcaster` seeds, which are not launcher widgets at all, and its
 * export reaches `LauncherWidgetCodeExporter` only because its root is spelled
 * `remote-widgets/launcher-widget`.
 *
 * Its delivery branch does not exist yet, so these read its policy and templates as its repository
 * wrote them ([CatalogCutoverFixtures.unpublishedIds]); the readiness ledger carries the missing
 * branch.
 */
class LauncherWidgetCutoverTest {

  private val id = "remote-widgets"
  private val json = Json { classDiscriminator = "type" }

  private val policy: JsonObject
    get() = CatalogCutoverFixtures.sourcePolicy(id)

  /** The catalog as served once published, with the one-line declaration it would add. */
  private fun declared(
    adapter: String = CatalogExportRouting.LAUNCHER_WIDGET
  ): CatalogCapabilityV1 =
    CatalogCapabilityV1.Builder(
        schema = "compose-ui-builder-capability/v1",
        benchmark =
          CatalogBenchmarkV1.Builder(
              id = id,
              sourceRevision = "source",
              catalogSystemId = id,
              catalogRevision = "revision",
              nativeRuntimeId = "candidate",
            )
            .build(),
        components = emptyList(),
      )
      .also {
        it.statusSemantics = policy
        it.composeSourceExport =
          ComposeSourceExportCapabilityV1.Builder(adapter, CatalogExportRouting.V1).build()
      }
      .build()

  private fun seed(templateId: String): UiBuilderDocument =
    UiBuilderNewDesignSeed.document(
      designId = "probe",
      catalogSystemId = id,
      templateId = templateId,
      catalogRevision = "revision",
      nativeRuntimeId = "candidate",
      fixture = CatalogCutoverProbe.fixture,
      ownership = CatalogOwnership.ALL,
      published = CatalogCutoverFixtures.templates(id),
    )

  @Test
  fun `off the flag it is offered the builder's mobile seeds, on it its own`() {
    assertEquals(
      setOf("blank", UiBuilderNewDesignSeed.DEFAULT_TEMPLATE),
      UiBuilderNewDesignSeed.templateIds(id),
    )
    assertEquals(
      listOf("launcher-widget-2x1", "counter-widget"),
      UiBuilderNewDesignSeed.templateIds(
          id,
          CatalogOwnership.ALL,
          CatalogCutoverFixtures.templates(id),
        )
        .toList(),
    )
  }

  @Test
  fun `its frame sizes are the launcher grid, read from the catalog`() {
    val sizes = CatalogExportRouting.frameSizes(policy)
    assertEquals("1x1", sizes.first().label)
    assertEquals(
      listOf(130 to 102, 203 to 220),
      listOf("2x1", "3x2").map { label ->
        sizes.first { it.label == label }.let { it.widthDp to it.heightDp }
      },
    )
  }

  @Test
  fun `a declared launcher widget route is the launcher emitter`() {
    assertEquals(
      CatalogExportRouting.Route.RecordFree(
        CatalogExportRouting.LAUNCHER_WIDGET,
        UiBuilderCatalogPlatform.REMOTE_COMPOSE,
      ),
      CatalogExportRouting.route(declared(), CatalogOwnership.ALL),
    )
  }

  @Test
  fun `its published seed exports as a launcher widget at the catalog's own size`() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
      UiBuilderBuildFeatures.remoteCompose,
      "needs -PuiBuilderRemoteCompose=true",
    )
    val catalog = declared()
    val generated =
      RecordFreeExport.generate(
        seed("launcher-widget-2x1"),
        CatalogExportRouting.route(catalog, CatalogOwnership.ALL),
        CatalogExportRouting.frameSizes(catalog),
      )
    val source = assertIs<RecordFreeExport.Generated.Emitted>(generated, "$generated").source
    assertContains(source, ": RemoteComposeWidget() {")
    assertContains(source, "@Preview(name = \"2x1\", widthDp = 130, heightDp = 102)")
  }

  /**
   * The property routing by declaration buys: a launcher widget catalog may call its root anything.
   * Routed by component id the same design is not recognised as a launcher widget at all.
   */
  @Test
  fun `a renamed root still exports when the catalog declares the route`() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
      UiBuilderBuildFeatures.remoteCompose,
      "needs -PuiBuilderRemoteCompose=true",
    )
    val renamed =
      json.decodeFromString(
        UiBuilderDocument.serializer(),
        json
          .encodeToString(UiBuilderDocument.serializer(), seed("launcher-widget-2x1"))
          .replace("remote-widgets/launcher-widget", "acme-widgets/home-widget"),
      )
    val catalog = declared()

    val owned =
      RecordFreeExport.generate(
        renamed,
        CatalogExportRouting.route(catalog, CatalogOwnership.ALL),
        CatalogExportRouting.frameSizes(catalog),
      )
    assertContains(
      assertIs<RecordFreeExport.Generated.Emitted>(owned, "$owned").source,
      ": RemoteComposeWidget() {",
    )

    val byId = RecordFreeExport.generate(renamed, UiBuilderCatalogPlatform.REMOTE_COMPOSE)
    assertFalse(
      (byId as? RecordFreeExport.Generated.Emitted)
        ?.source
        .orEmpty()
        .contains("RemoteComposeWidget"),
      "routed by component id, a launcher root under another name is not a launcher widget",
    )
  }
}
