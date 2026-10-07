package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.CatalogExportRouting
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * What happens to each catalog when the catalog-owned flag is on: the measurement
 * [CatalogCutoverReadinessTest] holds to its gap ledger. One line per finding, so the ledger reads
 * as the list of things a catalog repository has to publish before its cutover is a no-op.
 */
internal object CatalogCutoverProbe {

  val fixture by lazy {
    Json.parseToJsonElement(
        File("../docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json").readText()
      )
      .jsonObject
  }

  /**
   * `<catalog>: <finding>` for everything that is not clean under [CatalogOwnership.ALL].
   *
   * [published] is what each catalog publishes, by id; a test that asks "what if this catalog also
   * declared X" passes the captured catalog with X added, which is exactly the change the catalog
   * repository would make.
   */
  fun findings(
    published: Map<String, CatalogCapabilityV1> =
      CatalogCutoverFixtures.catalogIds.associateWith(CatalogCutoverFixtures::catalog),
    templates: (String) -> CatalogSeedTemplates = CatalogCutoverFixtures::templates,
  ): List<String> {
    val ownership = CatalogOwnership.ALL
    val executor = CatalogCutoverFixtures.executor(published, ownership)
    val served = executor.listCatalogs().associateBy { it.benchmark.catalogSystemId }
    return published.keys.flatMap { id ->
      val catalog = served.getValue(id)
      val templates = templates(id)
      val route = CatalogExportRouting.route(catalog, ownership)
      buildList {
        if (templates.templates.isEmpty()) add("$id: publishes no seed templates")
        if (route is CatalogExportRouting.Route.NotDeclared)
          add("$id: declares no composeSourceExport, so no export is offered")
        if (route is CatalogExportRouting.Route.Unsupported)
          add("$id: declares ${route.adapter}/v${route.version}, which this build does not ship")
        UiBuilderNewDesignSeed.templateIds(id, ownership, templates).forEach { templateId ->
          val document =
            UiBuilderNewDesignSeed.document(
                designId = "probe",
                catalogSystemId = id,
                templateId = templateId,
                catalogRevision = catalog.benchmark.catalogRevision,
                nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
                fixture = fixture,
                ownership = ownership,
                published = templates,
              )
              .toDesignDocumentV1()
          executor.validate(document, catalog)?.let {
            add("$id/$templateId: does not validate: ${it.code} ${it.nodeId ?: ""} ${it.message}")
          }
          val platform = CatalogExportRouting.recordFreePlatform(route) ?: return@forEach
          val recordFree =
            RecordFreeExport.generate(
              document,
              platform,
              packComponents = CatalogCutoverFixtures.composed(id).records,
            )
          when (recordFree) {
            is RecordFreeExport.Generated.Emitted -> Unit
            is RecordFreeExport.Generated.Refused ->
              add("$id/$templateId: export refused: ${recordFree.reasons.take(3)}")
            null ->
              when (
                val generic =
                  ScreenExportGate.export(document, CatalogCutoverFixtures.exportRecord(id))
              ) {
                is ScreenExportGate.Outcome.Emitted -> Unit
                is ScreenExportGate.Outcome.Refused ->
                  add("$id/$templateId: export refused: ${generic.reasons.take(3)}")
              }
          }
        }
      }
    }
  }
}
