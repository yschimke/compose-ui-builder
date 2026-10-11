package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.CatalogExportProjections
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
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What would change for one catalog if it were catalog-owned, measured against what it publishes
 * now: the shadow check a deployment runs before flipping, and what `CatalogCutoverReadinessTest`
 * runs on fixtures, so both agree on "ready".
 *
 * Nothing here changes what is served. [ownedFindings] asks a private executor owning just this
 * catalog; [catalogDifferences] compares the synthesised Kotlin catalog with the published one.
 * Each returns `<catalog>[/<template>]: <finding>` lines; empty is clean.
 */
public object CatalogCutoverShadow {

  /**
   * The whole shadow answer for [catalogId]: [ownedFindings], and what an editor would see change.
   * Compares the two catalogs as actually served (builder vocabulary and font settings included),
   * so builder-owned components are not reported lost. [Report.differences] is null when nothing is
   * synthesised or the catalog declares no platform.
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
   * [report]'s answer. [ready] when there are no [findings] and no [losses]; purely additive or
   * relaxing differences do not block. [losses] is null exactly when [differences] is.
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
   * Losses: a Kotlin-only component; a changed role; a dropped trait, modifier, property, slot, or
   * accepted slot role/trait; a property type losing an alternative; a newly required property;
   * narrowed allowed values or cardinality. A line that both loses and gains is a loss.
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
   * - Seeds: its published [templates] (none is a finding), each validating against the catalog.
   * - Export: its declared `composeSourceExport` route must ship in this build, and every seed must
   *   export through it (launcher emitter, record-free emitter, or record export against
   *   [exportRecord]).
   *
   * [fixture] is the operations fixture new designs seed from; [packComponents] are the composed
   * catalog's records for the record-free emitters.
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
        when (
          val generic =
            ScreenExportGate.export(
              document,
              exportRecord,
              CatalogExportProjections.from(catalog.statusSemantics),
            )
        ) {
          is ScreenExportGate.Outcome.Emitted -> null
          is ScreenExportGate.Outcome.Refused -> generic.reasons.take(3)
        }
    }
  }

  /**
   * The differences between the synthesised Kotlin catalog and the published one: the cost of
   * deleting the Kotlin, component by component.
   *
   * Compares components, roles, traits, properties (name, JSON type, required, allowed values) and
   * slots (name, cardinality, accepted roles/traits); presentation fields are ignored. What the
   * published `statusSemantics.supersedes` carries across ([planCatalogUpgrade]) is reported as
   * superseded or migrated, not lost.
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
    // A catalog's own ids already name it (`wear-m3/button`, and `m3/button` or `a2ui/Column`,
    // whose namespace is not the system id); the builder's do not (`layout/column`), and those are
    // the ones that need it to say which catalog lost them.
    fun line(componentId: String, finding: Finding): Difference =
      Difference(
        if (BUILDER_NAMESPACES.any { componentId.startsWith(it) })
          "$id/$componentId: ${finding.text}"
        else "$componentId: ${finding.text}",
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
  ): Map<String, String?> {
    val declared = owned[successor.componentId]?.properties?.map { it.name }?.toSet().orEmpty()
    return buildMap {
      successor.variants?.let { variants ->
        if (variants.components.values.all { it in owned }) put(variants.property, null)
      }
      // A rename keeps where it goes, so its value's constraints are compared with the
      // destination's: [planCatalogUpgrade] copies the value across unchanged.
      successor.properties.forEach { (from, to) -> if (to in declared) put(from, to) }
      // Only a modifier [planCatalogUpgrade] can write: any other blocks the upgrade
      // (`UNSUPPORTED_MODIFIER`), so the property is lost, not migrated.
      successor.modifiers.forEach { (from, to) -> if (canWriteUpgradeModifier(to)) put(from, null) }
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
    migrated: Map<String, String?> = emptyMap(),
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
    val carried = (kotlinProperties.keys - publishedProperties.keys) intersect migrated.keys
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
      addAll(
        propertyDifferences(
          name,
          kotlinProperties.getValue(name),
          publishedProperties.getValue(name),
        )
      )
    }
    // A renamed property's value is copied onto its destination unchanged, so the destination has
    // to accept everything the old property did.
    carried.sorted().forEach { from ->
      val to = migrated[from] ?: return@forEach
      val destination = publishedProperties[to] ?: return@forEach
      addAll(propertyDifferences("$from -> $to", kotlinProperties.getValue(from), destination))
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
      slotFilter("slot $name accepted roles", a.acceptedRoles.toSet(), b.acceptedRoles.toSet())
        ?.let(::add)
      slotFilter("slot $name accepted traits", a.acceptedTraits.toSet(), b.acceptedTraits.toSet())
        ?.let(::add)
    }
  }

  /** How one property's constraints moved, [label] naming it (or the rename that carries it). */
  private fun propertyDifferences(
    label: String,
    a: PropertyCapabilityV1,
    b: PropertyCapabilityV1,
  ): List<Finding> = buildList {
    if (a.jsonType != b.jsonType) {
      add(
        Finding(
          "property $label type ${a.jsonType} -> ${b.jsonType}",
          loss = !admitsAll(jsonTypes(b.jsonType), jsonTypes(a.jsonType)),
        )
      )
    }
    if (a.required != b.required) {
      add(Finding("property $label required ${a.required} -> ${b.required}", loss = b.required))
    }
    if (a.allowedValues.toSet() != b.allowedValues.toSet()) {
      // An empty list allows anything, so narrowing to any list is a loss and widening to none
      // is not.
      val narrows =
        b.allowedValues.isNotEmpty() &&
          (a.allowedValues.isEmpty() || !b.allowedValues.containsAll(a.allowedValues))
      add(
        Finding(
          "property $label allowed values ${a.allowedValues} -> ${b.allowedValues}",
          loss = narrows,
        )
      )
    }
  }

  /**
   * Whether the JSON types [published] admits every value [kotlin] did. `integer` is a subtype of
   * `number` — the runtime's `number` accepts an integral value — so widening one to the other
   * loses nothing.
   */
  private fun admitsAll(published: Set<String>, kotlin: Set<String>): Boolean = kotlin.all {
    it in published || (it == "integer" && "number" in published)
  }

  /**
   * [differ] for a slot's accepted roles or traits, where an EMPTY list accepts everything
   * (`slotAccepts`): adding a filter to an empty one narrows the slot, and dropping every filter
   * widens it, whatever the strings say.
   */
  private fun slotFilter(what: String, kotlin: Set<String>, published: Set<String>): Finding? {
    val finding = differ(what, kotlin, published) ?: return null
    return when {
      kotlin.isEmpty() -> Finding(finding.text, loss = true)
      published.isEmpty() -> Finding(finding.text, loss = false)
      else -> finding
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
