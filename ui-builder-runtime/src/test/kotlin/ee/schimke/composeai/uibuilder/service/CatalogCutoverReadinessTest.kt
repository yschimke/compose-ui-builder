package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.CatalogExportRouting
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComposeSourceExportCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Could every catalog be switched to catalog-owned today ([CatalogOwnership.ALL]), and if not, what
 * is missing? Asked against fixtures captured from each catalog repository
 * ([CatalogCutoverFixtures]).
 *
 * - What already holds is asserted outright: served from own files, no Kotlin generator runs,
 *   legacy designs still open, every published seed validates.
 * - The gap ledger is asserted exactly, one line per missing item, so it only shrinks on purpose
 *   and an added line is a catalog regression.
 *
 * See `docs/design/UI_BUILDER_CATALOG_CUTOVER.md`.
 */
class CatalogCutoverReadinessTest {

  private val ids = CatalogCutoverFixtures.catalogIds

  @Test
  fun `runtime ownership is unconditional while legacy flag parsing stays compatible`() {
    assertEquals(
      CatalogOwnership.ALL,
      CurrentM3UiBuilderCatalogExecutor.Builder().catalogOwnership,
    )
    assertEquals(CatalogOwnership.NONE, CatalogOwnership.parse(null))
    assertEquals(CatalogOwnership.NONE, CatalogOwnership.parse(""))
    assertEquals(CatalogOwnership.ALL, CatalogOwnership.parse("all"))
    assertEquals(CatalogOwnership.of(setOf("wear-m3")), CatalogOwnership.parse(" wear-m3 "))
  }

  @Test
  fun `with the flag off, every catalog keeps its built-in seeds`() {
    for (id in ids) {
      val published = CatalogCutoverFixtures.templates(id)
      assertEquals(
        UiBuilderNewDesignSeed.templateIds(id),
        UiBuilderNewDesignSeed.templateIds(id, CatalogOwnership.NONE, published),
        "$id: the flag being off must not change what a new design can start as",
      )
      for (templateId in UiBuilderNewDesignSeed.templateIds(id)) {
        assertEquals(
          UiBuilderNewDesignSeed.document(
            "seed",
            id,
            templateId,
            "r",
            "n",
            CatalogCutoverProbe.fixture,
          ),
          UiBuilderNewDesignSeed.document(
            "seed",
            id,
            templateId,
            "r",
            "n",
            CatalogCutoverProbe.fixture,
            CatalogOwnership.NONE,
            published,
          ),
          "$id/$templateId",
        )
      }
    }
  }

  @Test
  fun `every catalog composes from what its repository publishes`() {
    for (id in ids + CatalogCutoverFixtures.FOUNDATION) {
      assertEquals(id, CatalogCutoverFixtures.catalog(id).benchmark.catalogSystemId)
    }
  }

  @Test
  fun `the published Wear theme tokens reach the served catalog`() {
    val published =
      CatalogCutoverFixtures.policy("wear-m3")
        .getValue("statusSemantics")
        .jsonObject
        .getValue("designTokens")
    assertTrue(published.jsonObject.getValue("tokens").jsonArray.size >= 15)
    val served =
      CatalogCutoverFixtures.executor(listOf("wear-m3"), CatalogOwnership.ALL)
        .listCatalogs()
        .single()
    assertEquals(published, served.statusSemantics["designTokens"])
  }

  @Test
  fun `with the flag on, every catalog is served from its own files and no generator runs`() {
    val executor = CatalogCutoverFixtures.executor(ids, CatalogOwnership.ALL)

    assertEquals(ids.toSet(), executor.listCatalogs().map { it.benchmark.catalogSystemId }.toSet())
    assertEquals(ids.associateWith { "published" }, executor.catalogSources)
    for (catalog in executor.listCatalogs()) {
      val id = catalog.benchmark.catalogSystemId
      // A catalog whose branch carries no `runtime.zip` (remote-widgets: its launcher widgets have
      // no native lane yet) is served as `candidate`, the pin every unstamped design carries.
      assertEquals(
        CatalogCutoverFixtures.rendererRuntimeId(id) ?: "candidate",
        catalog.benchmark.nativeRuntimeId,
        "$id is drawn by the renderer runtime its own repository published",
      )
    }
  }

  @Test
  fun `legacy ownership settings cannot bring back the Kotlin fallback`() {
    val executor = CatalogCutoverFixtures.executor(ids, CatalogOwnership.NONE)
    assertEquals(ids.associateWith { "published" }, executor.catalogSources)
    for (ownership in listOf(CatalogOwnership.NONE, CatalogOwnership.ALL)) {
      for (id in listOf("m3-catalog", "wear-m3", "unknown-catalog")) {
        val failure =
          assertFailsWith<IllegalArgumentException> {
            CurrentM3UiBuilderCatalogExecutor.Builder()
              .also {
                it.catalogSystemIds = setOf(id)
                it.catalogOwnership = ownership
              }
              .build()
          }
        assertTrue("$id has no usable published file" in failure.message.orEmpty(), failure.message)
      }
    }
  }

  @Test
  fun `a design pinned to a synthesised catalog still opens once its owner serves it`() {
    val executor = CatalogCutoverFixtures.executor(ids, CatalogOwnership.ALL)
    for ((id, legacy) in LegacySynthesisedReferences.all) {
      assertNotNull(
        executor.resolve(legacy),
        "$id: a design created against the synthesised catalog must not strand (#796)",
      )
    }
  }

  @Test
  fun `a legacy pin stamped with a configured runtime still opens once its owner serves it`() {
    // A host that configured a runtime for the synthesised catalog stamped designs with that
    // runtime in place of `candidate`; the owned catalog must accept that pin as well.
    val executor = CatalogCutoverFixtures.executor(ids, CatalogOwnership.ALL)
    for ((id, legacy) in LegacySynthesisedReferences.all) {
      val runtime = CatalogCutoverFixtures.rendererRuntimeId(id) ?: continue
      val stamped =
        ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1(
          systemId = legacy.systemId,
          catalogRevision = legacy.catalogRevision,
          capabilityDigest = legacy.capabilityDigest,
          nativeRuntimeId = runtime,
        )
      assertNotNull(executor.resolve(stamped), "$id: $stamped")
    }
  }

  @Test
  fun `an owned catalog with nothing published is refused rather than synthesised`() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        CurrentM3UiBuilderCatalogExecutor.Builder()
          .also {
            it.catalogSystemIds = setOf(CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID)
            it.catalogOwnership = CatalogOwnership.ALL
          }
          .build()
      }
    assertTrue("no usable published file" in failure.message.orEmpty(), failure.message)
  }

  @Test
  fun `an owned catalog must say which platform it is`() {
    val wear = CatalogCutoverFixtures.catalog("wear-m3")
    val unstated =
      wear
        .newBuilder()
        .also { it.statusSemantics = JsonObject(wear.statusSemantics - "platform") }
        .build()
    assertFailsWith<IllegalArgumentException> {
      CatalogCutoverFixtures.executor(mapOf("wear-m3" to unstated), CatalogOwnership.ALL)
    }
  }

  @Test
  fun `every published seed validates against the catalog it is served as`() {
    val findings = CatalogCutoverProbe.findings()
    assertEquals(
      emptyList(),
      findings.filter { "does not validate" in it },
      "a template the catalog publishes must be a design that catalog accepts",
    )
  }

  /**
   * The gap ledger. Each line is work in the named catalog repository, not here; see
   * `UI_BUILDER_CATALOG_CUTOVER.md` § The gap ledger for the fix each one wants.
   */
  /**
   * A published `remote-compose` component that states no `modifiers` still offers the Remote-only
   * ones (`sharedElement`, `animateEnterExit`); a catalog's own list is taken at its word.
   */
  @Test
  fun `a published remote-compose component offers the remote-only modifiers`() {
    val catalog =
      CatalogCutoverFixtures.executor(listOf("remote-m3"), CatalogOwnership.ALL)
        .listCatalogs()
        .single()
    val byId = catalog.components.associateBy { it.componentId }

    val toggle = byId.getValue("remote-m3/remote-switch-button").modifierCapabilities
    assertEquals(
      REMOTE_ONLY_MODIFIERS.toSet(),
      toggle.filter { it in REMOTE_ONLY_MODIFIERS }.toSet(),
      "a published switch button offers every Remote-only modifier: $toggle",
    )
    assertEquals(
      emptyList(),
      byId.getValue("remote-m3/widget-container-small").modifierCapabilities,
      "a stated empty list stays empty",
    )
  }

  @Test
  fun `the gap ledger is exactly what each catalog still has to publish`() {
    assertEquals(
      listOf("glimmer-catalog: declares no composeSourceExport, so no export is offered"),
      CatalogCutoverProbe.findings(),
    )
  }

  /**
   * The export half of the ledger is one line of policy per catalog: with that declaration, every
   * published seed exports through the emitter it reaches today by id.
   */
  @Test
  fun `declaring the export route is all the export half needs`() {
    val declared =
      mapOf(
        "wear-m3" to CatalogExportRouting.WEAR_SCREEN,
        "remote-m3" to CatalogExportRouting.REMOTE_COMPOSE,
        "a2ui-catalog" to CatalogExportRouting.A2UI,
      )
    val published = declared.mapValues { (id, adapter) ->
      CatalogCutoverFixtures.catalog(id).declaring(adapter)
    }

    assertEquals(emptyList(), CatalogCutoverProbe.findings(published))
  }

  /**
   * A2UI's published `a2ui-column` is the phase-3a freeze of the Kotlin `a2ui-column` builder, so a
   * design created either side of the cutover starts as the same tree. The environment is the
   * catalog's to choose and is not compared.
   */
  @Test
  fun `a2ui-catalog's published a2ui-column is the built-in seed it replaces`() {
    val id = "a2ui-catalog"
    val builtIn = assertNotNull(frozenBuiltInTemplates(id)["a2ui-column"]).document
    val published = assertNotNull(CatalogCutoverFixtures.templates(id)["a2ui-column"]).document
    assertEquals(builtIn.title, published.title)
    assertEquals(builtIn.roots, published.roots)
    assertEquals(builtIn.nodes, published.nodes)
    assertEquals(builtIn.stateVariables, published.stateVariables)
  }

  /**
   * [id]'s built-in templates, written out as the documents its repository would publish: what the
   * Kotlin builders seed, pinned to `candidate` the way every captured seed document is.
   */
  private fun frozenBuiltInTemplates(id: String): CatalogSeedTemplates {
    val documents =
      UiBuilderNewDesignSeed.templateIds(id).associateWith { templateId ->
        val document =
          UiBuilderNewDesignSeed.document(
            templateId,
            id,
            templateId,
            "candidate",
            "candidate",
            CatalogCutoverProbe.fixture,
          )
        Json.encodeToString(UiBuilderDocument.serializer(), document)
      }
    val read =
      CatalogSeedTemplates.read(id, documents.keys.map { "ui-builder/designs/$it.json" }) { path ->
        documents[path.substringAfterLast('/').removeSuffix(".json")]
      }
    return (read as CatalogSeedTemplates.Result.Read).templates
  }

  @Test
  fun `the published compose-foundation record does not carry the builder vocabulary yet`() {
    val published =
      CatalogCutoverFixtures.record(CatalogCutoverFixtures.FOUNDATION).components.flatMap {
        it.componentIds
      }
    val packaged =
      CatalogCutoverFixtures.packagedFoundationRecord.components.flatMap { it.componentIds }
    // When m3-catalog's `:foundation-catalog` publishes call sites for these, the server can read
    // the builder's own layout record from the delivery branch instead of packaging it, and
    // this assertion flips: delete it and move the record read (`UI_BUILDER_CATALOG_CUTOVER.md`).
    assertEquals(emptyList(), packaged.filter { it in published })
  }

  private fun CatalogCapabilityV1.declaring(adapter: String): CatalogCapabilityV1 =
    newBuilder()
      .also {
        it.composeSourceExport =
          ComposeSourceExportCapabilityV1.Builder(adapter, CatalogExportRouting.V1).build()
      }
      .build()
}
