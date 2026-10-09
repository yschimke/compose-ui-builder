package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.CatalogExportRouting
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlinx.serialization.json.JsonObject

/**
 * What would change for one catalog if it were catalog-owned, measured against what it publishes
 * right now: the check a deployment runs in **shadow** for a catalog it is considering flipping,
 * and the one `CatalogCutoverReadinessTest` runs against captured fixtures. One answer for both, so
 * a box's shadow report and the readiness ledger cannot disagree about what "ready" means.
 *
 * Nothing here changes what is served. [ownedFindings] builds a private executor that owns just
 * this catalog and asks it the questions the flag would make real; [catalogDifferences] compares
 * the Kotlin catalog this build would otherwise synthesise with the published one, which is what
 * deleting that Kotlin would lose. Each returns one line per finding, `<catalog>[/<template>]:
 * <finding>`, and an empty list is the clean answer.
 */
public object CatalogCutoverShadow {

  /**
   * The whole shadow answer for [catalogId]: [ownedFindings], and what an editor would see change
   * if the catalog were owned and the Kotlin catalog deleted.
   *
   * The comparison is between the two catalogs an editor is actually *served*: the Kotlin catalog
   * as this build serves it today (with the builder vocabulary and font settings it adds), and the
   * published one as served owned. Comparing the raw published file instead would report the
   * builder's own layout components as lost, which no flip loses. [Report.differences] is null for
   * a catalog this build synthesises nothing for, which has nothing to lose, and for one that
   * cannot be owned at all because it declares no platform.
   */
  public fun report(
    catalogId: String,
    published: CatalogCapabilityV1,
    templates: CatalogSeedTemplates?,
    fixture: JsonObject,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    exportRecord: ComponentRecordFile? = null,
    nativeRuntimeId: String? = null,
  ): Report {
    // Owning a catalog that names no platform is refused outright, so there is no owned catalog to
    // compare: the finding says why, and there are no differences to report.
    if (published.declaredPlatform == null) {
      return Report(catalogId, listOf(noPlatform(catalogId)), differences = null)
    }
    val owned = servedCatalog(catalogId, mapOf(catalogId to published), owned = true)
    val kotlin =
      owned.second.synthesisedCatalog(catalogId)?.let {
        servedCatalog(catalogId, emptyMap(), owned = false).first
      }
    return Report(
      catalogId = catalogId,
      findings =
        ownedFindings(
          catalogId,
          published,
          templates,
          fixture,
          packComponents,
          exportRecord,
          nativeRuntimeId,
        ),
      differences = kotlin?.let { catalogDifferences(it, owned.first) },
    )
  }

  /** [report]'s answer: clean when [findings] is empty, and [differences] empty or null. */
  public class Report
  internal constructor(
    public val catalogId: String,
    public val findings: List<String>,
    public val differences: List<String>?,
  ) {
    public val ready: Boolean
      get() = findings.isEmpty()
  }

  private fun servedCatalog(
    catalogId: String,
    published: Map<String, CatalogCapabilityV1>,
    owned: Boolean,
  ): Pair<CatalogCapabilityV1, CurrentM3UiBuilderCatalogExecutor> {
    val executor =
      CurrentM3UiBuilderCatalogExecutor.Builder()
        .also {
          it.catalogSystemIds = setOf(catalogId)
          it.published = published
          it.catalogOwnership =
            if (owned) CatalogOwnership.of(setOf(catalogId)) else CatalogOwnership.NONE
        }
        .build()
    return executor.listCatalogs().single { it.benchmark.catalogSystemId == catalogId } to executor
  }

  /**
   * Everything that is not clean about serving [catalogId] owned, from [published] alone.
   *
   * - **Seeds:** the templates it publishes ([templates]; null or empty is a finding), each of
   *   which must validate against the catalog it is served as.
   * - **Export:** its declared `composeSourceExport` route, which must be one this build ships, and
   *   every seed must export through it: a launcher widget through the launcher emitter at the
   *   catalog's own sizes, a record-free platform through its emitter, anything else through the
   *   generic record export against [exportRecord].
   *
   * [fixture] is the operations fixture the deployment seeds new designs from; [packComponents] are
   * the composed catalog's records, which the record-free emitters resolve call sites from.
   */
  public fun ownedFindings(
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
          it.catalogOwnership = ownership
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
        when (val generic = ScreenExportGate.export(document, exportRecord)) {
          is ScreenExportGate.Outcome.Emitted -> null
          is ScreenExportGate.Outcome.Refused -> generic.reasons.take(3)
        }
    }
  }

  /**
   * What the published catalog does not say that the Kotlin catalog this build synthesises does,
   * and the reverse: the cost of deleting the Kotlin, component by component.
   *
   * Compared are the components each offers, and for a component both offer, its role, traits,
   * properties (name, JSON type, required, allowed values) and slots (name, cardinality, accepted
   * roles and traits). Display names, notes and canvas adapters are presentation and are left out:
   * they differ by design and a report full of them would hide the ones that matter.
   */
  public fun catalogDifferences(
    synthesised: CatalogCapabilityV1,
    published: CatalogCapabilityV1,
  ): List<String> {
    val id = published.benchmark.catalogSystemId
    val kotlin = synthesised.components.associateBy { it.componentId }
    val owned = published.components.associateBy { it.componentId }
    // A catalog's own ids already carry its prefix (`wear-m3/button`); the builder's do not
    // (`layout/column`), and those are the ones that need it to say which catalog lost them.
    fun line(componentId: String, finding: String): String =
      if (componentId.startsWith("$id/")) "$componentId: $finding" else "$id/$componentId: $finding"
    return buildList {
      (kotlin.keys - owned.keys).sorted().forEach {
        add(line(it, "only the Kotlin catalog has it"))
      }
      (owned.keys - kotlin.keys).sorted().forEach {
        add(line(it, "only the published catalog has it"))
      }
      (kotlin.keys intersect owned.keys).sorted().forEach { componentId ->
        componentDifferences(kotlin.getValue(componentId), owned.getValue(componentId)).forEach {
          add(line(componentId, it))
        }
      }
    }
  }

  private fun noPlatform(catalogId: String): String =
    "$catalogId: its published file declares no platform (statusSemantics.platform), which an " +
      "owned catalog cannot borrow from the Kotlin one"

  private fun componentDifferences(
    kotlin: ComponentCapabilityV1,
    published: ComponentCapabilityV1,
  ): List<String> = buildList {
    if (kotlin.role != published.role) add("role ${kotlin.role} -> ${published.role}")
    differ("traits", kotlin.traits.toSet(), published.traits.toSet())?.let(::add)
    differ(
        "modifiers",
        kotlin.modifierCapabilities.toSet(),
        published.modifierCapabilities.toSet(),
      )
      ?.let(::add)
    val kotlinProperties = kotlin.properties.associateBy { it.name }
    val publishedProperties = published.properties.associateBy { it.name }
    differ("properties", kotlinProperties.keys, publishedProperties.keys)?.let(::add)
    (kotlinProperties.keys intersect publishedProperties.keys).sorted().forEach { name ->
      val a = kotlinProperties.getValue(name)
      val b = publishedProperties.getValue(name)
      if (a.jsonType != b.jsonType) add("property $name type ${a.jsonType} -> ${b.jsonType}")
      if (a.required != b.required) add("property $name required ${a.required} -> ${b.required}")
      if (a.allowedValues.toSet() != b.allowedValues.toSet()) {
        add("property $name allowed values ${a.allowedValues} -> ${b.allowedValues}")
      }
    }
    val kotlinSlots = kotlin.slots.associateBy { it.name }
    val publishedSlots = published.slots.associateBy { it.name }
    differ("slots", kotlinSlots.keys, publishedSlots.keys)?.let(::add)
    (kotlinSlots.keys intersect publishedSlots.keys).sorted().forEach { name ->
      val a = kotlinSlots.getValue(name)
      val b = publishedSlots.getValue(name)
      if (a.cardinality.min != b.cardinality.min || a.cardinality.max != b.cardinality.max) {
        add(
          "slot $name cardinality ${a.cardinality.min}..${a.cardinality.max} -> " +
            "${b.cardinality.min}..${b.cardinality.max}"
        )
      }
      differ("slot $name accepted roles", a.acceptedRoles.toSet(), b.acceptedRoles.toSet())
        ?.let(::add)
      differ("slot $name accepted traits", a.acceptedTraits.toSet(), b.acceptedTraits.toSet())
        ?.let(::add)
    }
  }

  private fun differ(what: String, kotlin: Set<String>, published: Set<String>): String? {
    val lost = (kotlin - published).sorted()
    val gained = (published - kotlin).sorted()
    if (lost.isEmpty() && gained.isEmpty()) return null
    return buildList {
        if (lost.isNotEmpty()) add("loses ${lost.joinToString()}")
        if (gained.isNotEmpty()) add("gains ${gained.joinToString()}")
      }
      .joinToString("; ", prefix = "$what: ")
  }
}
