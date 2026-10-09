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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    val differences = kotlin?.let { catalogDifferenceDetails(it, owned.first) }
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
      differences = differences?.map { it.text },
      losses = differences?.filter { it.loss }?.map { it.text },
    )
  }

  /**
   * [report]'s answer. [ready] when there are no [findings] AND no [losses]: owning the catalog
   * must neither refuse something it serves now nor take away something an editor can do today. A
   * difference that only adds — a component, trait, modifier or property the published catalog has
   * and the Kotlin one does not, or a constraint it relaxes — does not block it.
   *
   * [losses] is the subset of [differences] that takes something away; null exactly when
   * [differences] is.
   */
  public class Report
  internal constructor(
    public val catalogId: String,
    public val findings: List<String>,
    public val differences: List<String>?,
    public val losses: List<String>? = differences?.let { emptyList() },
  ) {
    public val ready: Boolean
      get() = findings.isEmpty() && losses.isNullOrEmpty()
  }

  /**
   * One line of [catalogDifferences], with whether it takes something away from an editor.
   *
   * A loss is: a component only the Kotlin catalog has; a changed role; a trait, modifier, property
   * or slot (or a slot's accepted role or trait) the published catalog drops; a property type that
   * drops an alternative (`["boolean","object"] -> "boolean"` loses binding to state); a property
   * that becomes required; allowed values that narrow; a slot cardinality that narrows. A line that
   * both loses and gains is a loss.
   */
  public class Difference internal constructor(public val text: String, public val loss: Boolean) {
    override fun toString(): String = (if (loss) "loss: " else "") + text
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
   *
   * What the published catalog's `statusSemantics.supersedes` carries across is not lost: a saved
   * design is moved through it ([planCatalogUpgrade]) rather than stranded. A Kotlin-only component
   * it supersedes, every successor of which the published catalog offers, is reported as superseded
   * and not as a loss; and a property it migrates — the `variants.property` that chooses the
   * successor, a property renamed onto one the successor declares, or one restated as a modifier —
   * is reported as migrated rather than lost.
   */
  public fun catalogDifferences(
    synthesised: CatalogCapabilityV1,
    published: CatalogCapabilityV1,
  ): List<String> = catalogDifferenceDetails(synthesised, published).map { it.text }

  /** [catalogDifferences], each line classified as a [Difference.loss] or not. */
  public fun catalogDifferenceDetails(
    synthesised: CatalogCapabilityV1,
    published: CatalogCapabilityV1,
  ): List<Difference> {
    val id = published.benchmark.catalogSystemId
    val kotlin = synthesised.components.associateBy { it.componentId }
    val owned = published.components.associateBy { it.componentId }
    // A catalog's own ids already carry its prefix (`wear-m3/button`); the builder's do not
    // (`layout/column`), and those are the ones that need it to say which catalog lost them.
    fun line(componentId: String, finding: Finding): Difference =
      Difference(
        if (componentId.startsWith("$id/")) "$componentId: ${finding.text}"
        else "$id/$componentId: ${finding.text}",
        finding.loss,
      )
    val successors = successors(published)
    return buildList {
      (kotlin.keys - owned.keys).sorted().forEach {
        add(line(it, kotlinOnly(successors[it], owned.keys)))
      }
      (owned.keys - kotlin.keys).sorted().forEach {
        add(line(it, Finding("only the published catalog has it", loss = false)))
      }
      (kotlin.keys intersect owned.keys).sorted().forEach { componentId ->
        val successor = successors[componentId]
        componentDifferences(
            kotlin.getValue(componentId),
            owned.getValue(componentId),
            successor?.let { migratedProperties(it, owned) }.orEmpty(),
          )
          .forEach { add(line(componentId, it)) }
      }
    }
  }

  /**
   * A component only the Kotlin catalog has: lost, unless the published catalog supersedes it and
   * offers every component a saved one would be moved to.
   */
  private fun kotlinOnly(successor: ComponentSuccessor?, owned: Set<String>): Finding {
    if (successor == null) return Finding("only the Kotlin catalog has it", loss = true)
    val targets =
      (listOf(successor.componentId) + successor.variants?.components?.values.orEmpty()).distinct()
    val missing = targets.filterNot { it in owned }
    return if (missing.isEmpty()) {
      Finding(
        "only the Kotlin catalog has it; superseded by ${targets.joinToString()}",
        loss = false,
      )
    } else {
      Finding(
        "only the Kotlin catalog has it; superseded by ${targets.joinToString()}, but the " +
          "published catalog lacks ${missing.joinToString()}",
        loss = true,
      )
    }
  }

  /**
   * The properties [successor] carries across rather than drops: its variant selector, a property
   * renamed onto one the successor declares, and one restated as a modifier.
   */
  private fun migratedProperties(
    successor: ComponentSuccessor,
    owned: Map<String, ComponentCapabilityV1>,
  ): Set<String> {
    val declared = owned[successor.componentId]?.properties?.map { it.name }?.toSet().orEmpty()
    return buildSet {
      successor.variants?.let { variants ->
        if (variants.components.values.all { it in owned }) add(variants.property)
      }
      successor.properties.forEach { (from, to) -> if (to in declared) add(from) }
      addAll(successor.modifiers.keys)
    }
  }

  /** A component-level difference before it is addressed to its component. */
  private class Finding(val text: String, val loss: Boolean)

  private fun noPlatform(catalogId: String): String =
    "$catalogId: its published file declares no platform (statusSemantics.platform), which an " +
      "owned catalog cannot borrow from the Kotlin one"

  private fun componentDifferences(
    kotlin: ComponentCapabilityV1,
    published: ComponentCapabilityV1,
    migrated: Set<String> = emptySet(),
  ): List<Finding> = buildList {
    // A role decides where the component may be placed and what may be placed in it, so a changed
    // one can strand designs either way: counted as a loss rather than guessed at.
    if (kotlin.role != published.role) {
      add(Finding("role ${kotlin.role} -> ${published.role}", loss = true))
    }
    differ("traits", kotlin.traits.toSet(), published.traits.toSet())?.let(::add)
    differ(
        "modifiers",
        kotlin.modifierCapabilities.toSet(),
        published.modifierCapabilities.toSet(),
      )
      ?.let(::add)
    val kotlinProperties = kotlin.properties.associateBy { it.name }
    val publishedProperties = published.properties.associateBy { it.name }
    val carried = (kotlinProperties.keys - publishedProperties.keys) intersect migrated
    differ("properties", kotlinProperties.keys - carried, publishedProperties.keys)?.let(::add)
    if (carried.isNotEmpty()) {
      add(
        Finding(
          "properties: migrates ${carried.sorted().joinToString()} through statusSemantics.supersedes",
          loss = false,
        )
      )
    }
    (kotlinProperties.keys intersect publishedProperties.keys).sorted().forEach { name ->
      val a = kotlinProperties.getValue(name)
      val b = publishedProperties.getValue(name)
      if (a.jsonType != b.jsonType) {
        add(
          Finding(
            "property $name type ${a.jsonType} -> ${b.jsonType}",
            loss = !jsonTypes(b.jsonType).containsAll(jsonTypes(a.jsonType)),
          )
        )
      }
      if (a.required != b.required) {
        add(Finding("property $name required ${a.required} -> ${b.required}", loss = b.required))
      }
      if (a.allowedValues.toSet() != b.allowedValues.toSet()) {
        // An empty list allows anything, so narrowing to any list is a loss and widening to none
        // is not.
        val narrows =
          b.allowedValues.isNotEmpty() &&
            (a.allowedValues.isEmpty() || !b.allowedValues.containsAll(a.allowedValues))
        add(
          Finding(
            "property $name allowed values ${a.allowedValues} -> ${b.allowedValues}",
            loss = narrows,
          )
        )
      }
    }
    val kotlinSlots = kotlin.slots.associateBy { it.name }
    val publishedSlots = published.slots.associateBy { it.name }
    differ("slots", kotlinSlots.keys, publishedSlots.keys)?.let(::add)
    (kotlinSlots.keys intersect publishedSlots.keys).sorted().forEach { name ->
      val a = kotlinSlots.getValue(name)
      val b = publishedSlots.getValue(name)
      if (a.cardinality.min != b.cardinality.min || a.cardinality.max != b.cardinality.max) {
        // A null max is unbounded.
        val narrows =
          b.cardinality.min > a.cardinality.min ||
            (b.cardinality.max != null &&
              (a.cardinality.max == null || b.cardinality.max!! < a.cardinality.max!!))
        add(
          Finding(
            "slot $name cardinality ${a.cardinality.min}..${a.cardinality.max} -> " +
              "${b.cardinality.min}..${b.cardinality.max}",
            loss = narrows,
          )
        )
      }
      differ("slot $name accepted roles", a.acceptedRoles.toSet(), b.acceptedRoles.toSet())
        ?.let(::add)
      differ("slot $name accepted traits", a.acceptedTraits.toSet(), b.acceptedTraits.toSet())
        ?.let(::add)
    }
  }

  /** A property's JSON type as the set of alternatives it admits: `"x"` or `["x","y"]`. */
  private fun jsonTypes(type: JsonElement?): Set<String> =
    when (type) {
      is JsonPrimitive -> setOf(type.content)
      is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
      else -> emptySet()
    }

  private fun differ(what: String, kotlin: Set<String>, published: Set<String>): Finding? {
    val lost = (kotlin - published).sorted()
    val gained = (published - kotlin).sorted()
    if (lost.isEmpty() && gained.isEmpty()) return null
    return Finding(
      buildList {
          if (lost.isNotEmpty()) add("loses ${lost.joinToString()}")
          if (gained.isNotEmpty()) add("gains ${gained.joinToString()}")
        }
        .joinToString("; ", prefix = "$what: "),
      loss = lost.isNotEmpty(),
    )
  }
}
