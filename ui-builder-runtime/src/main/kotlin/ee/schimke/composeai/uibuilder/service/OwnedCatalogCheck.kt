package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.CatalogExportProjections
import ee.schimke.composeai.uibuilder.export.CatalogExportRouting
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.CatalogScreenStructure
import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlinx.serialization.json.JsonObject

/**
 * Everything that is not clean about serving one catalog catalog-owned, from what it publishes
 * alone: its seeds validate against it and export through the route it declares. Returns
 * `<catalog>[/<template>]: <finding>` lines; empty is clean.
 */
internal object OwnedCatalogCheck {

  /**
   * Everything that is not clean about serving [catalogId] owned, from [published] alone.
   *
   * - Seeds: its published [templates] (none is a finding), each validating against the catalog.
   * - Export: its declared `composeSourceExport` route must ship in this build, and every seed must
   *   export through it (launcher emitter, record-free emitter, or record export against
   *   [exportRecord]).
   *
   * [fixture] is the operations fixture new designs seed from; [packComponents] are the composed
   * catalog's records for the record-free emitters.
   */
  fun findings(
    catalogId: String,
    published: CatalogCapabilityV1,
    templates: CatalogSeedTemplates?,
    fixture: JsonObject,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    exportRecord: ComponentRecordFile? = null,
    nativeRuntimeId: String? = null,
  ): List<String> {
    if (published.declaredPlatform == null) return listOf(noPlatform(catalogId))
    val ownership = CatalogOwnership.of(setOf(catalogId))
    val executor =
      CurrentM3UiBuilderCatalogExecutor.Builder()
        .also {
          it.catalogSystemIds = setOf(catalogId)
          it.published = mapOf(catalogId to published)
          nativeRuntimeId?.let { runtime -> it.nativeRuntimeIds = mapOf(catalogId to runtime) }
        }
        .build()
    val catalog = executor.listCatalogs().single { it.benchmark.catalogSystemId == catalogId }
    val route = CatalogExportRouting.route(catalog, ownership)
    return buildList {
      if (templates == null || templates.templates.isEmpty()) {
        add("$catalogId: publishes no seed templates")
      }
      if (route is CatalogExportRouting.Route.NotDeclared) {
        add("$catalogId: declares no composeSourceExport, so no export is offered")
      }
      if (route is CatalogExportRouting.Route.Unsupported) {
        add(
          "$catalogId: declares ${route.adapter}/v${route.version}, which this build does not ship"
        )
      }
      UiBuilderNewDesignSeed.templateIds(catalogId, ownership, templates).forEach { templateId ->
        val document =
          UiBuilderNewDesignSeed.document(
              designId = "shadow",
              catalogSystemId = catalogId,
              templateId = templateId,
              catalogRevision = catalog.benchmark.catalogRevision,
              nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
              fixture = fixture,
              ownership = ownership,
              published = templates,
            )
            .toDesignDocumentV1()
        executor.validate(document, catalog)?.let {
          add(
            "$catalogId/$templateId: does not validate: ${it.code} ${it.nodeId ?: ""} ${it.message}"
          )
        }
        exportRefusal(document, route, catalog, packComponents, exportRecord)?.let {
          add("$catalogId/$templateId: export refused: $it")
        }
      }
    }
  }

  /** The first reasons the seed does not export under [route], or null when it does. */
  private fun exportRefusal(
    document: DesignDocumentV1,
    route: CatalogExportRouting.Route,
    catalog: CatalogCapabilityV1,
    packComponents: Map<String, ComponentRecord>,
    exportRecord: ComponentRecordFile?,
  ): List<String>? {
    // A launcher widget is routed by its declaration alone, through the catalog-aware entry the
    // server calls under the flag, previewed at the catalog's own sizes.
    if (
      route is CatalogExportRouting.Route.RecordFree &&
        route.adapter == CatalogExportRouting.LAUNCHER_WIDGET
    ) {
      val launcher =
        RecordFreeExport.generate(
          document.toUiBuilderDocument(),
          route,
          CatalogExportRouting.frameSizes(catalog),
          CatalogExportRouting.launcherRoots(catalog),
          packComponents = packComponents,
        )
      return (launcher as? RecordFreeExport.Generated.Refused)?.reasons?.take(3)
    }
    val platform = CatalogExportRouting.recordFreePlatform(route) ?: return null
    return when (
      val recordFree =
        RecordFreeExport.generate(document, platform, packComponents = packComponents)
    ) {
      is RecordFreeExport.Generated.Emitted -> null
      is RecordFreeExport.Generated.Refused -> recordFree.reasons.take(3)
      null ->
        when (
          val generic =
            ScreenExportGate.export(
              document,
              exportRecord,
              CatalogExportProjections.from(catalog.statusSemantics),
              CatalogScreenStructure.from(catalog.statusSemantics),
            )
        ) {
          is ScreenExportGate.Outcome.Emitted -> null
          is ScreenExportGate.Outcome.Refused -> generic.reasons.take(3)
        }
    }
  }

  private fun noPlatform(catalogId: String): String =
    "$catalogId: its published file declares no platform (statusSemantics.platform), so it cannot be served catalog-owned"
}
