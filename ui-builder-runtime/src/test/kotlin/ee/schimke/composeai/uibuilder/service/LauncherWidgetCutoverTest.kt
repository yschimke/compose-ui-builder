package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.CatalogExportRouting
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.LauncherWidgetTemplates
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComposeSourceExportCapabilityV1
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * `remote-widgets`, the phone launcher widget design system remote-m3-catalog publishes from
 * `:widget-catalog`, under the catalog-owned cutover.
 *
 * It is the catalog the cutover fits best, and the one that most needs it: the builder synthesises
 * nothing for it, so its definition was only ever its own; its frame sizes are already catalog data
 * (`frame.geometry.sizesDp`); and it publishes its own seed templates. Off the flag it gets the
 * builder's Kotlin copy of a launcher seed (`LauncherWidgetTemplates`), and its export reaches
 * `LauncherWidgetCodeExporter` only because its root is spelled `remote-widgets/launcher-widget`.
 *
 * Read from its delivery branch (`design-artifacts/remote-widgets`) like every other captured
 * catalog: the published `statusSemantics` carries the same `builtins`, `frame` and
 * `composeSourceExport` its policy file states, so these hold the catalog as a host serves it.
 */
class LauncherWidgetCutoverTest {

  private val id = "remote-widgets"
  private val json = Json { classDiscriminator = "type" }

  private val published: CatalogCapabilityV1
    get() = CatalogCutoverFixtures.catalog(id)

  /** The published `statusSemantics`: the catalog's policy, as its delivery branch serves it. */
  private val policy: JsonObject
    get() = published.statusSemantics

  /**
   * The catalog as served. An [adapter] other than the one it publishes asks "what if it declared
   * this route instead", which is exactly the one-line change its repository would make.
   */
  private fun declared(adapter: String? = null): CatalogCapabilityV1 =
    if (adapter == null) published
    else
      published
        .newBuilder()
        .also {
          it.composeSourceExport =
            ComposeSourceExportCapabilityV1.Builder(adapter, CatalogExportRouting.V1).build()
        }
        .build()

  @Test
  fun `it publishes the launcher widget route`() {
    assertEquals(
      CatalogExportRouting.LAUNCHER_WIDGET,
      published.composeSourceExport?.adapter,
      "remote-widgets declares its own export route, so owning it needs no builder table",
    )
  }

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
  fun `off the flag it is offered the builder's launcher seed, on it its own`() {
    assertEquals(LauncherWidgetTemplates.ids, UiBuilderNewDesignSeed.templateIds(id))
    assertEquals(
      LauncherWidgetTemplates.ids,
      UiBuilderNewDesignSeed.templateIds(
        id,
        CatalogOwnership.NONE,
        CatalogCutoverFixtures.templates(id),
      ),
      "the flag off ignores what the catalog publishes",
    )
    assertEquals(
      listOf("hello-widget", "launcher-widget-2x1", "counter-widget"),
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
    val catalog = declared()
    val generated =
      RecordFreeExport.generate(
        seed("launcher-widget-2x1"),
        CatalogExportRouting.route(catalog, CatalogOwnership.ALL),
        CatalogExportRouting.frameSizes(catalog),
        CatalogExportRouting.launcherRoots(policy),
      )
    val source = assertIs<RecordFreeExport.Generated.Emitted>(generated, "$generated").source
    assertContains(source, ": RemoteComposeWidget() {")
    assertContains(source, "@Preview(name = \"2x1\", widthDp = 130, heightDp = 102)")
  }

  /**
   * The property routing by declaration buys: a launcher widget catalog may call its root anything,
   * so long as it marks that root `LauncherWidgetHost`. Here the catalog is the same policy with
   * its root renamed. Routed by component id the same design is not recognised as a launcher widget
   * at all.
   */
  @Test
  fun `a renamed root still exports when the catalog declares the route`() {
    val renamed =
      json.decodeFromString(
        UiBuilderDocument.serializer(),
        json
          .encodeToString(UiBuilderDocument.serializer(), seed("launcher-widget-2x1"))
          .replace("remote-widgets/launcher-widget", "acme-widgets/home-widget"),
      )
    val catalog = declared()
    val renamedPolicy =
      Json.parseToJsonElement(
          policy.toString().replace("remote-widgets/launcher-widget", "acme-widgets/home-widget")
        )
        .jsonObject
    assertEquals(
      setOf("acme-widgets/home-widget"),
      CatalogExportRouting.launcherRoots(renamedPolicy),
    )

    val owned =
      RecordFreeExport.generate(
        renamed,
        CatalogExportRouting.route(catalog, CatalogOwnership.ALL),
        CatalogExportRouting.frameSizes(catalog),
        CatalogExportRouting.launcherRoots(renamedPolicy),
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

  @Test
  fun `a declared route refuses a root the catalog does not mark as a launcher widget`() {
    val buttonRooted =
      json.decodeFromString(
        UiBuilderDocument.serializer(),
        json
          .encodeToString(UiBuilderDocument.serializer(), seed("launcher-widget-2x1"))
          .replace("remote-widgets/launcher-widget", "remote-widgets/widget-button"),
      )
    val catalog = declared()
    val generated =
      RecordFreeExport.generate(
        buttonRooted,
        CatalogExportRouting.route(catalog, CatalogOwnership.ALL),
        CatalogExportRouting.frameSizes(catalog),
        CatalogExportRouting.launcherRoots(policy),
      )
    val refused = assertIs<RecordFreeExport.Generated.Refused>(generated, "$generated")
    assertContains(refused.reasons.single(), CatalogExportRouting.LAUNCHER_HOST_TRAIT)
  }

  @Test
  fun `the launcher widget root is the one the catalog marks as its host`() {
    assertEquals(
      setOf("remote-widgets/launcher-widget"),
      CatalogExportRouting.launcherRoots(policy),
    )
    // Composed, the trait reaches the served component, so a host reading the capability and one
    // reading the policy agree on the root.
    assertEquals(
      CatalogExportRouting.launcherRoots(policy),
      CatalogExportRouting.launcherRoots(published),
    )
  }

  @Test
  fun `a record-driven route is never answered by a record-free emitter`() {
    val catalog =
      declared(
        ee.schimke.composeai.uibuilder.export.CatalogComposeSourceExportAdapters.COMPOSE_MATERIAL3
      )
    assertEquals(
      null,
      RecordFreeExport.generate(
        seed("launcher-widget-2x1"),
        CatalogExportRouting.route(catalog, CatalogOwnership.ALL),
      ),
    )
  }
}
