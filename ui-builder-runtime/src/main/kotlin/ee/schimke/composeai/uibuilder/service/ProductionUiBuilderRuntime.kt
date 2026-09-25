@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.export.RemoteMaterial3
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.STATE_SELECTION_CONTAINER
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.inspectUiBuilderArgumentBindings
import ee.schimke.composeai.uibuilder.export.propertyMatches
import ee.schimke.composeai.uibuilder.export.stateBindingMatchesCatalog
import ee.schimke.composeai.uibuilder.export.stateSelectionIssue
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.export.toUiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.AssetBindingV1
import ee.schimke.composeai.uibuilder.protocol.AssetKeyValueV1
import ee.schimke.composeai.uibuilder.protocol.CatalogAssetSourceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DESIGN_COMPONENT_INSTANCE_COMPONENT_ID
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.EmbeddedAssetSourceV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCardinalityV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import ee.schimke.composeai.uibuilder.protocol.UploadedAssetSourceV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private data class Configuration(
  val source: String,
  val catalogSystemIds: Set<String>,
  val exportCapabilities: ExportCapabilitiesV1,
  val composeExportFor: (String) -> Boolean,
  val packs: List<UiBuilderComponentPackSource>,
  val published: Map<String, CatalogCapabilityV1>,
  val nativeRuntimeIds: Map<String, String> = emptyMap(),
)

private fun defaultExportCapabilities(): ExportCapabilitiesV1 =
  ExportCapabilitiesV1.Builder()
    .also {
      it.composeCode = true
      it.svg = false
      it.png = false
    }
    .build()

/**
 * The explicitly enabled production catalogs admitted by the v1 service.
 *
 * Resolution is exact across all four pin fields. The packaged catalog is parsed strictly and its
 * invariants are checked before it is exposed; there is no "closest" revision or permissive
 * component fallback. Export capabilities are supplied by the renderer adapter at startup, so a
 * host without the packaged daemon lane advertises Compose only instead of claiming artifacts it
 * cannot produce.
 */
public class CurrentM3UiBuilderCatalogExecutor private constructor(configuration: Configuration) :
  UiBuilderCatalogExecutor {
  private val source = configuration.source
  private val catalogSystemIds = configuration.catalogSystemIds
  private val exportCapabilities = configuration.exportCapabilities
  private val composeExportFor = configuration.composeExportFor
  private val packs = configuration.packs
  private val published = configuration.published
  private val nativeRuntimeIds = configuration.nativeRuntimeIds

  /**
   * The original constructor, retained temporarily for binary-compatible migration.
   *
   * Use [Builder] for new hosts. Kotlin compiles defaulted constructors into synthetic bridges, so
   * adding a parameter here would strand already compiled Preview Server releases. This constructor
   * is deprecated now and can be removed only in the next planned ABI break.
   */
  @Deprecated("Use CurrentM3UiBuilderCatalogExecutor.Builder", ReplaceWith("Builder().build()"))
  public constructor(
    source: String = packagedM3CatalogSource(),
    catalogSystemIds: Set<String> = setOf(DEFAULT_CATALOG_SYSTEM_ID),
    exportCapabilities: ExportCapabilitiesV1 = defaultExportCapabilities(),
    /**
     * Whether a given catalog can export Compose, asked per catalog rather than once.
     *
     * `exportCapabilities` is a field of `CatalogCapabilityV1` — one per catalog on the wire — and
     * the host used to compute a single boolean and copy it onto every enabled catalog. So a
     * deployment serving `m3-catalog` (which has a component record) alongside `remote-m3` (which
     * deliberately does not, Remote Compose being outside the Compose exporter) advertised no
     * Compose export **anywhere**, and the builder withdrew the action from the catalog that could
     * have used it. Defaults to the flat value, so a caller that does not care is unaffected.
     */
    composeExportFor: (String) -> Boolean = { exportCapabilities.composeCode },
    /**
     * Component packs admitted by the host, merged into every enabled catalog of the same platform.
     *
     * A pack is another catalog's components — a served application catalog such as
     * `confetti-mobile`, projected from its published component record — offered inside the
     * authoring catalogs it is compatible with. Merged here rather than served as catalogs of their
     * own because a design is pinned to one catalog and a pack is not a thing to pin to: it has no
     * scaffold, no templates and no canvas adapter of its own. What it has is components, and a
     * Material 3 phone screen that can hold a `SessionCard` beside its `m3/card` is the whole
     * point.
     *
     * Which catalogs a pack reaches is decided by platform, never by name. A mobile pack lands in
     * `m3-catalog`; it does not land in `remote-m3`, whose widget body is `@RemoteComposable` and
     * cannot call it, nor in `wear-m3`, which is not Material 3. The catalog declares what it
     * carried under `statusSemantics.componentPacks` so the editor can shelve the pack under its
     * own name and let an author switch it on and off.
     */
    packs: List<UiBuilderComponentPackSource> = emptyList(),
    /**
     * Catalogs composed from what a catalog repository PUBLISHED, keyed by system id.
     *
     * The cutover of `docs/design/UI_BUILDER_CATALOG_CONTRACT.md`, and the reason this class can
     * stop being the place a catalog is written. An entry here is preferred over the synthesised
     * catalog of the same id, and an id with no synthesiser is served from here alone — which is
     * what lets a catalog this binary has never heard of appear in the chooser.
     *
     * Per catalog and reversible on purpose: a catalog that publishes nothing, or whose published
     * file will not compose, keeps the synthesised one and a startup line says which source each
     * came from. Composing the file is `:server`'s job (it needs the component record reader, which
     * `checkUiBuilderRuntimeBoundary` keeps off this module's classpath), so this takes the
     * finished catalogs rather than the files.
     */
    published: Map<String, CatalogCapabilityV1> = emptyMap(),
  ) : this(
    Configuration(
      source = source,
      catalogSystemIds = catalogSystemIds,
      exportCapabilities = exportCapabilities,
      composeExportFor = composeExportFor,
      packs = packs,
      published = published,
    )
  )

  /** JVM compatibility for callers compiled against the all-default primary constructor. */
  @Deprecated("Use CurrentM3UiBuilderCatalogExecutor.Builder", ReplaceWith("Builder().build()"))
  public constructor() :
    this(
      source = packagedM3CatalogSource(),
      catalogSystemIds = setOf(DEFAULT_CATALOG_SYSTEM_ID),
      exportCapabilities = defaultExportCapabilities(),
      composeExportFor = { it -> defaultExportCapabilities().composeCode },
    )

  /** Additive host configuration for catalogs delivered with immutable renderer runtimes. */
  public class Builder {
    public var source: String = packagedM3CatalogSource()
    public var catalogSystemIds: Set<String> = setOf(DEFAULT_CATALOG_SYSTEM_ID)
    public var exportCapabilities: ExportCapabilitiesV1 = defaultExportCapabilities()
    public var composeExportFor: (String) -> Boolean = { exportCapabilities.composeCode }
    public var packs: List<UiBuilderComponentPackSource> = emptyList()
    public var published: Map<String, CatalogCapabilityV1> = emptyMap()
    public var nativeRuntimeIds: Map<String, String> = emptyMap()

    public fun build(): CurrentM3UiBuilderCatalogExecutor =
      CurrentM3UiBuilderCatalogExecutor(
        Configuration(
          source = source,
          catalogSystemIds = catalogSystemIds,
          exportCapabilities = exportCapabilities,
          composeExportFor = composeExportFor,
          packs = packs,
          published = published,
          nativeRuntimeIds = nativeRuntimeIds,
        )
      )
  }

  private val baseCatalog =
    json
      .decodeFromString<CatalogCapabilityV1>(source)
      .let(::validateCatalog)
      .newBuilder()
      .also { it.exportCapabilities = exportCapabilities }
      .build()
  private val synthesisedCatalogs =
    mapOf(
      DEFAULT_CATALOG_SYSTEM_ID to baseCatalog,
      REMOTE_M3_CATALOG_SYSTEM_ID to remoteM3Catalog(baseCatalog),
      WEAR_M3_CATALOG_SYSTEM_ID to wearM3Catalog(baseCatalog),
    )

  /**
   * Where a published catalog's builder components come from: [composeFoundationCatalog].
   *
   * NOT one fixed set. The foundation curates the builder vocabulary per platform, and it has to:
   * `wear` gets `layout/box`, `layout/column`, `layout/row` and `asset/image` and nothing else,
   * because `WearScreenCodeExporter` refuses everything else with "no Wear Compose Material 3
   * counterpart this generator can write". Handing a published Wear catalog all seventeen would put
   * `layout/lazy-grid`, `layout/scaffold` and the shapes on a watch palette, where a design that
   * uses one is guaranteed to fail export — a palette entry that cannot be exported is worse than a
   * missing one, because it is only discovered at the end.
   *
   * Keyed by PLATFORM, which is the axis the curation was always along -- the synthesised catalogs
   * used to be the donors and this hop tried their ids first.
   *
   * [platformFor] keeps that id hop for the one case where dropping it would change an answer: a
   * published catalog that declares NO platform. Such a catalog is mobile everywhere else in the
   * system, because that is what [platform] defaults to, but the old chain handed a published
   * `wear-m3` the Wear vocabulary off its id alone, and `WearM3ScreenCatalogTest` pins that. The
   * hop goes when `synthesisedCatalogs` does (#819 step 3), and that is a change to review on its
   * own rather than a side effect of this one.
   *
   * What is NOT kept is the id winning over a platform the catalog DID declare. A catalog saying
   * `platform: mobile` under any id now gets the mobile vocabulary, which is the one its exporter
   * can write, and which every other reader of `statusSemantics.platform` already assumed.
   *
   * Built once, at construction, for the platforms this deployment actually publishes something for
   * -- `donorFor` is only reached from `withBuilderVocabulary`, which runs over `published` while
   * `availableCatalogs` is initialised and never again. `getOrElse` rather than `getValue` so a
   * later caller outside that loop gets a donor rather than an exception.
   */
  /**
   * Where the `remote-compose/` seams come from, which is NOT the foundation.
   *
   * `remote-m3` is the catalog that describes Remote Compose, so a seam it declares ITSELF is the
   * one to hand out. It declares none today — like every published catalog it publishes only its
   * own prefix — so every seam currently falls through to the packaged catalog, which is where they
   * have always come from and why no deployment changes behaviour.
   *
   * Read off `published`, deliberately, rather than off `availableCatalogs`: the served `remote-m3`
   * is itself a published catalog with seams injected INTO it by `withBuilderVocabulary`, so
   * sourcing from the served one would be circular. What this asks is the narrower question the
   * contract cares about — what did the catalog repository actually publish.
   *
   * When it publishes them (#819), this map picks them up and the packaged fallback stops being
   * reached; a seam `remote-m3` declines, like `remote-compose/inline` today, keeps falling
   * through. The fallback goes when the packaged catalog stops carrying a `remote-compose/` id at
   * all, and not before — dropping it sooner takes the seams off every palette on a deployment that
   * serves no Remote Compose catalog, which is what `--ui-builder-catalogs` defaults to.
   */
  private val remoteComposeSeams: Map<String, ComponentCapabilityV1> = buildMap {
    baseCatalog.components
      .filter { it.componentId.startsWith(REMOTE_COMPOSE_NAMESPACE) }
      .forEach { put(it.componentId, it) }
    published[REMOTE_M3_CATALOG_SYSTEM_ID]
      ?.components
      ?.filter { it.componentId.startsWith(REMOTE_COMPOSE_NAMESPACE) }
      ?.forEach { put(it.componentId, it) }
  }

  private val composeFoundation: Map<String, CatalogCapabilityV1> =
    published.values.map(::platformFor).distinct().associateWith {
      composeFoundationCatalog(baseCatalog, it, remoteComposeSeams)
    }

  private fun platformFor(catalog: CatalogCapabilityV1): String =
    catalog.declaredPlatform
      ?: synthesisedCatalogs[catalog.benchmark.catalogSystemId]?.platform
      ?: DEFAULT_PLATFORM

  private fun donorFor(catalog: CatalogCapabilityV1): CatalogCapabilityV1 =
    platformFor(catalog).let { platform ->
      composeFoundation.getOrElse(platform) {
        composeFoundationCatalog(baseCatalog, platform, remoteComposeSeams)
      }
    }

  /**
   * A published catalog, plus the builder components it does not offer itself.
   *
   * Additive only, and the catalog wins every collision: a catalog that DOES declare `layout/box`
   * as a builtin keeps its own, so this cannot overwrite a deliberate statement.
   *
   * The donor's asset registry travels with `asset/image`. `declaredAssetKeys` reads
   * `statusSemantics.assetRegistry` and returns null when there is none, and a null registry makes
   * `INVALID_PROPERTY` checking return early rather than fail — so handing over the image component
   * without it would accept any `assetKey` a design invented and surface it as a broken picture at
   * render time. Only when the catalog states none of its own; a catalog with a registry keeps it.
   */
  private fun withBuilderVocabulary(catalog: CatalogCapabilityV1): CatalogCapabilityV1 {
    val donor = donorFor(catalog)
    val offered = catalog.components.mapTo(mutableSetOf()) { it.componentId }
    val missing =
      donor.components.filter { component ->
        component.componentId !in offered &&
          BUILDER_NAMESPACES.any { component.componentId.startsWith(it) }
      }
    if (missing.isEmpty()) return catalog
    val registryKey = CurrentM3UiBuilderCatalogExecutor.ASSET_REGISTRY_KEY
    var semantics = catalog.statusSemantics
    // The donor's asset keys travel with `asset/image`, UNIONED into whatever the catalog states
    // rather than only filling an absent registry.
    //
    // Filling only the absent case was half of it: the editor seeds a newly inserted image with
    // `editor.placeholder`, a builder-owned key, so a catalog that declares a registry of its own
    // and does not happen to list that key gets an image component it cannot insert — both write
    // validators reject it. A catalog's own keys win a collision; it is describing its own assets.
    if (missing.any { it.componentId.startsWith("asset/") }) {
      val donorKeys = (donor.statusSemantics[registryKey] as? JsonObject)?.get("keys") as? JsonArray
      if (donorKeys != null) {
        val own = semantics[registryKey] as? JsonObject
        val ownKeys = (own?.get("keys") as? JsonArray).orEmpty()
        val ownNames = ownKeys.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        val merged =
          JsonArray(
            ownKeys +
              donorKeys.filterNot { key -> (key as? JsonPrimitive)?.contentOrNull in ownNames }
          )
        semantics =
          JsonObject(
            semantics +
              (registryKey to JsonObject((own ?: JsonObject(emptyMap())) + ("keys" to merged)))
          )
      }
    }
    // A component's shelf comes with it. The insert panel groups by `componentMenu`, and an entry
    // with no group falls back to a generic role heading — so injecting `layout/box` without the
    // donor's "Layout" would put the whole builder vocabulary under "Container"/"Leaf" instead of
    // the shelves it was written for.
    //
    // The ORDER is the donor's, not the order the components happen to be injected in. Appending
    // as they came put `Layout` and `Scaffolds` — the builder's primary shelves — at the BOTTOM of
    // the insert panel, below every catalog group, because those components are iterated last.
    // Catalog-owned groups keep their relative order; a donor group is inserted where the donor
    // puts it relative to the groups already present.
    val donorGroups = donor.statusSemantics.menuGroups()
    val donorOrder = donor.statusSemantics.menuGroupOrder()
    for (component in missing) {
      val group = donorGroups[component.componentId] ?: continue
      if (semantics.menuGroups()[component.componentId] != null) continue
      semantics =
        JsonObject(
          semantics +
            ("componentMenu" to semantics.withMenuEntry(component.componentId, group, donorOrder))
        )
    }
    return catalog
      .newBuilder()
      .also {
        it.components = catalog.components + missing
        it.statusSemantics = semantics
      }
      .build()
  }

  // A published catalog wins over the synthesised one of the same id. The map is the union rather
  // than an overlay of the synthesised keys, so an id nothing here synthesises is servable — that
  // is the whole point, and an overlay would have quietly kept the set of possible catalogs closed.
  private val availableCatalogs =
    synthesisedCatalogs + published.mapValues { (_, catalog) -> withBuilderVocabulary(catalog) }
  private val catalogs =
    catalogSystemIds
      .also { require(it.isNotEmpty()) { "at least one UI-builder catalog must be enabled" } }
      .also { enabled ->
        packs.forEach { pack ->
          require(SAFE_SYSTEM_ID.matches(pack.id)) { "invalid UI-builder pack id: ${pack.id}" }
          require(pack.id !in enabled) {
            "UI-builder pack ${pack.id} is also an enabled catalog; a catalog cannot be its own pack"
          }
        }
        require(packs.map { it.id }.distinct().size == packs.size) {
          "UI-builder packs must have distinct ids"
        }
      }
      .associateWith { systemId ->
        require(SAFE_SYSTEM_ID.matches(systemId)) { "invalid UI-builder catalog id: $systemId" }
        val catalog =
          requireNotNull(availableCatalogs[systemId]) {
            "UI-builder catalog $systemId is neither published nor synthesised by this build"
          }
        catalog
          .newBuilder()
          .also {
            nativeRuntimeIds[systemId]?.let { runtimeId ->
              require(runtimeId.isNotBlank()) {
                "UI-builder catalog $systemId has a blank native runtime id"
              }
              it.benchmark =
                catalog.benchmark
                  .newBuilder()
                  .also { benchmark -> benchmark.nativeRuntimeId = runtimeId }
                  .build()
            }
            it.components =
              catalog.components.map { component ->
                if (!UiBuilderBuildFeatures.remoteCompose)
                  component
                    .newBuilder()
                    .also {
                      it.properties = component.properties.filterNot { it.name == SHOW_BY_STATE }
                    }
                    .build()
                else if (
                  component.componentId != STATE_SELECTION_CONTAINER ||
                    component.properties.any { it.name == SHOW_BY_STATE }
                )
                  component
                else
                  component
                    .newBuilder()
                    .also {
                      it.properties =
                        component.properties +
                          baseCatalog.components
                            .first { it.componentId == STATE_SELECTION_CONTAINER }
                            .properties
                            .first { it.name == SHOW_BY_STATE }
                    }
                    .build()
              }
            it.exportCapabilities =
              RemoteDocumentExportSupport.capabilities(
                catalog.exportCapabilities
                  .newBuilder()
                  .also { it.composeCode = composeExportFor(systemId) }
                  .build(),
                json =
                  catalog.platform == "remote-compose" &&
                    RemoteDocumentExportSupport.jsonFormat?.let {
                      RemoteDocumentExportSupport.supports(exportCapabilities, it)
                    } == true,
                document =
                  catalog.platform == "remote-compose" &&
                    RemoteDocumentExportSupport.documentFormat?.let {
                      RemoteDocumentExportSupport.supports(exportCapabilities, it)
                    } == true,
              )
          }
          .build()
          .withPacks(packs.filter { it.platform == catalog.platform })
      }
  /**
   * Where each enabled catalog came from — `published` or `synthesised` — for the startup line.
   *
   * Exposed rather than logged here because the cutover is the thing an operator most needs to be
   * able to see: "the shelf changed" and "this catalog started reading its own published file" are
   * the same event, and a host that switched sources silently would make that undiagnosable.
   */
  public val catalogSources: Map<String, String> =
    catalogs.keys.associateWith { if (it in published) "published" else "synthesised" }

  private fun referenceOf(catalog: CatalogCapabilityV1) =
    CatalogReferenceV1(
      systemId = catalog.benchmark.catalogSystemId,
      catalogRevision = catalog.benchmark.catalogRevision,
      // The frozen v1 fixture names this pin explicitly. When the catalog wire shape grows a
      // digest field, this becomes the digest read from the signed catalog rather than a
      // convention.
      capabilityDigest = CURRENT_CAPABILITY_DIGEST,
      nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
    )

  private val references = catalogs.mapValues { (_, catalog) -> referenceOf(catalog) }

  /**
   * Every reference a stored design may be pinned to for a catalog this deployment serves.
   *
   * A catalog's reference is built from its `benchmark`, and the SOURCE changes it: the synthesised
   * `remote-m3` states `wear-widget-scaffolds-v1` where the published one takes a content-hash
   * revision. So flipping `--ui-builder-published-catalogs` -- one variable, documented as per
   * catalog and reversible -- used to strand every design persisted against the other source:
   * `resolve` returned null, `unusableReason` turned that into `CATALOG_UNAVAILABLE`, and the
   * runtime offered no upgrade path (#796).
   *
   * Both sources' references are accepted for the same `systemId`. No history is kept and nothing
   * is persisted: the catalog the OTHER source would serve is already in this process --
   * `synthesisedCatalogs` still holds its entry while the published one is being served -- so its
   * reference is simply computed. Neither `withPacks` nor `withBuilderVocabulary` touches
   * `benchmark`, so the value computed here is the one that catalog would carry if it were the one
   * being served.
   *
   * ONE DIRECTION ONLY, and the asymmetry is in what the process holds rather than in this map. The
   * synthesised catalog is generated here and always resident, so a server on the published source
   * can always compute the synthesised reference. `ServeRunner` fetches a published file only for
   * the ids `--ui-builder-published-catalogs` names, so a server that has flipped BACK has never
   * seen the published file and cannot know the reference it would have produced.
   * `CatalogSourceFlipTest` asserts that gap rather than leaving it to be discovered; #818's
   * re-pinning is what closes it.
   *
   * This does NOT weaken the check that catches a document drifting from its catalog. The accepted
   * set is only ever the references of the SAME catalog id as this build can produce it; a pin
   * naming a revision from neither source is still refused, and a document that no longer fits the
   * catalog still fails the component checks below on their own terms.
   */
  private val acceptedReferences: Map<String, Set<CatalogReferenceV1>> =
    catalogs.mapValues { (systemId, catalog) ->
      setOfNotNull(
        referenceOf(catalog),
        synthesisedCatalogs[systemId]?.let(::referenceOf),
        published[systemId]?.let(::referenceOf),
      )
    }
  private val components = catalogs.mapValues { (_, catalog) ->
    catalog.components.associateBy { it.componentId }
  }

  override fun listCatalogs(): List<CatalogCapabilityV1> = catalogs.values.toList()

  override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? =
    catalogs[reference.systemId]?.takeIf {
      reference in acceptedReferences.getValue(reference.systemId)
    }

  override fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? =
    catalog.benchmark.catalogSystemId.takeIf { catalogs[it] == catalog }?.let(references::get)

  override fun validate(
    document: DesignDocumentV1,
    catalog: CatalogCapabilityV1,
  ): UiBuilderCatalogIssue? {
    val systemId = catalog.benchmark.catalogSystemId
    if (catalog != catalogs[systemId])
      return issue("CATALOG_MISMATCH", "catalog is not an enabled UI-builder catalog")
    // Moves with `resolve`, and must: `unusableReason` calls `resolve` first and this second, so
    // accepting a pin there and refusing it here would turn a stored design's CATALOG_UNAVAILABLE
    // into an INTERNAL "invalid stored design" -- the same dead design, now blaming the document.
    if (document.catalogPin !in acceptedReferences.getValue(systemId)) {
      return issue(
        "CATALOG_PIN_MISMATCH",
        "document catalog pin names no catalog source this deployment serves",
      )
    }
    val catalogComponents = components.getValue(systemId)
    val encodedDocument = json.encodeToJsonElement(document).jsonObject
    val encodedNodes = encodedDocument.getValue("nodes").jsonObject
    val argumentBindings = inspectUiBuilderArgumentBindings(document.toUiBuilderDocument())
    argumentBindings.issues.firstOrNull()?.let {
      return issue("INVALID_ARGUMENT_BINDING", it.message, it.nodeId, it.field)
    }
    for ((nodeId, nodeElement) in encodedNodes.entries.sortedBy { it.key }) {
      val node = nodeElement.jsonObject
      val componentId = node.requiredString("componentId")
      // A placement is a document construct, not a catalog component: no catalog declares
      // `design/component-instance`, so looking it up here refused every design that placed one of
      // its own components — which made `declareComponent` unusable, since nothing could then
      // instantiate what it declared. The editor's `CapabilityValidator` has always drawn this
      // distinction; this is the same rule on the writing side.
      //
      // What it is checked for instead is the one thing that can be wrong about it here: the key
      // has to name a component this document declares. Its properties are the body's arguments
      // rather than a catalog component's properties, so the property and slot rules below do not
      // apply to it and are not run against it.
      if (componentId == DESIGN_COMPONENT_INSTANCE_COMPONENT_ID) {
        val key = node["component"]?.jsonObject?.get("componentKey")?.jsonPrimitive?.contentOrNull
        if (key.isNullOrBlank()) {
          return issue("INVALID_PLACEMENT", "placement names no component", nodeId)
        }
        if (key !in document.components) {
          return issue("UNKNOWN_COMPONENT", "this design declares no component $key", nodeId)
        }
        // Children hung on a placement are drawn by nobody. Both the renderer's
        // component-instance path and `CapabilityComposeCodeExporter.emitPlacement` draw the
        // declared body and never look at the placement's own slots, so accepting these would
        // commit nodes that vanish from the preview and from the generated Kotlin while the
        // operation reported success — and the topology checker, which counts them as placed,
        // would agree they are fine.
        val placementSlots = node.objectOrEmpty("slots")
        val occupied = placementSlots.entries.firstOrNull { it.value.jsonArray.isNotEmpty() }
        if (occupied != null) {
          return issue(
            "INVALID_PLACEMENT",
            "a placement draws its component's body, so slot ${occupied.key} cannot hold children",
            nodeId,
            occupied.key,
          )
        }
        // Modifiers are the one catalog rule that does reach a placement: they are applied to
        // whatever the body's root draws, and `CapabilityComposeCodeExporter.modifierExpression`
        // throws on a type it cannot write. Skipping the check here let a direct `ApplyOperation`
        // persist a design that then failed at export — the editor's `CapabilityValidator` has
        // always resolved `placedCapability` and checked them.
        val placed =
          placedCapability(node, document, encodedNodes, catalogComponents)
            ?: return issue(
              "UNKNOWN_COMPONENT",
              "the body of component $key cannot be resolved",
              nodeId,
            )
        node.modifierIssue(nodeId, placed, "component $key's body")?.let {
          return it
        }
        continue
      }
      val component =
        catalogComponents[componentId]
          ?: return issue(
            "UNKNOWN_COMPONENT",
            "component $componentId is not in $systemId",
            nodeId,
          )
      stateSelectionIssue(
          document.nodes.getValue(nodeId).toUiBuilderNode(),
          encodedDocument.objectOrEmpty("stateVariables"),
        )
        ?.let {
          return issue("INVALID_PROPERTY_VALUE", it, nodeId, SHOW_BY_STATE)
        }
      val properties = node.objectOrEmpty("properties")
      val declaredProperties = component.properties.associateBy { it.name }
      for ((name, value) in properties) {
        val capability =
          declaredProperties[name]
            ?: return issue(
              "UNKNOWN_PROPERTY",
              "property $name is not declared by $componentId",
              nodeId,
              name,
            )
        val unwrapped = value.unwrapTypedValue()
        val argumentMatches =
          argumentBindings.propertyMatches(nodeId, name, value) { supplied ->
            val stateMatches =
              stateBindingMatchesCatalog(
                supplied,
                capability.jsonType,
                capability.allowedValues,
                encodedDocument.objectOrEmpty("stateVariables"),
                name,
              )
            stateMatches
              ?: (capability.jsonType.accepts(supplied.unwrapTypedValue()) &&
                (capability.allowedValues.isEmpty() ||
                  supplied.unwrapTypedValue() in capability.allowedValues))
          }
        val bindingMatches =
          argumentMatches
            ?: stateBindingMatchesCatalog(
              value,
              capability.jsonType,
              capability.allowedValues,
              encodedDocument.objectOrEmpty("stateVariables"),
              name,
            )
        if (
          bindingMatches == false ||
            (bindingMatches == null && !capability.jsonType.accepts(unwrapped))
        ) {
          return issue(
            "INVALID_PROPERTY_TYPE",
            "property $name does not match its catalog JSON type",
            nodeId,
            name,
          )
        }
        if (
          bindingMatches == null &&
            capability.allowedValues.isNotEmpty() &&
            unwrapped !in capability.allowedValues
        ) {
          return issue(
            "INVALID_PROPERTY_VALUE",
            "property $name is outside its catalog allowed values",
            nodeId,
            name,
          )
        }
      }
      component.properties
        .filter { it.required }
        .forEach { property ->
          if (property.name !in properties) {
            return issue(
              "MISSING_REQUIRED_PROPERTY",
              "required property ${property.name} is missing",
              nodeId,
              property.name,
            )
          }
        }

      node.modifierIssue(nodeId, component, componentId)?.let {
        return it
      }

      val declaredSlots = component.slots.associateBy { it.name }
      val acceptsDynamicSlots = "DynamicSlots" in component.traits
      val slots = node.objectOrEmpty("slots")
      for ((name, childrenElement) in slots) {
        val children = childrenElement.jsonArray.map { it.jsonPrimitive.content }
        val slot =
          declaredSlots[name]
            // An entry with no children says nothing is in that slot, which is what leaving the key
            // out says, so withdrawing a slot from the catalog does not invalidate every stored
            // document that never used it — and the editor writes a key for every slot declared at
            // the moment of the insert, so those keys outlive the declaration.
            // `CapabilityValidator`
            // in `:ui-builder` carries the same rule; a child in an undeclared slot is still
            // refused, because that child would be silently dropped.
            ?: if (acceptsDynamicSlots || children.isEmpty()) null
            else
              return issue(
                "UNKNOWN_SLOT",
                "slot $name is not declared by $componentId",
                nodeId,
                name,
              )
        val maximum = slot?.cardinality?.max
        if (
          slot != null &&
            (children.size < slot.cardinality.min || (maximum != null && children.size > maximum))
        ) {
          return issue(
            "SLOT_CARDINALITY",
            "slot $name has ${children.size} children; expected ${slot.cardinality.min}..${maximum ?: "unbounded"}",
            nodeId,
            name,
          )
        }
        for (childId in children) {
          val child =
            encodedNodes[childId]?.jsonObject
              ?: return issue(
                "UNKNOWN_CHILD",
                "slot $name references missing node $childId",
                nodeId,
                name,
              )
          val childComponentId = child.requiredString("componentId")
          // A placement in a slot is judged by the body it places, which is what decides whether
          // the slot accepts it. Resolved through the declaration rather than the catalog, for the
          // reason above: the placement itself is not a catalog component.
          val childCapability =
            if (childComponentId == DESIGN_COMPONENT_INSTANCE_COMPONENT_ID) {
              // Follows nested placements: a component whose body root places another component
              // is an ordinary composition the renderer and the exporter both traverse, and
              // stopping at the first hop rejected every one of them.
              placedCapability(child, document, encodedNodes, catalogComponents)
                ?: return issue(
                  "UNKNOWN_COMPONENT",
                  "child $childId places a component whose body cannot be resolved",
                  childId,
                )
            } else {
              catalogComponents[childComponentId]
                ?: return issue(
                  "UNKNOWN_COMPONENT",
                  "child $childId has an unknown component",
                  childId,
                )
            }
          if (slot != null && !slotAccepts(slot, childCapability)) {
            return issue(
              "INCOMPATIBLE_SLOT_CHILD",
              "child $childId is not compatible with slot $name",
              nodeId,
              name,
            )
          }
        }
      }
      component.slots
        .filter { it.name !in slots }
        .forEach { slot ->
          if (slot.cardinality.min > 0) {
            return issue(
              "SLOT_CARDINALITY",
              "required slot ${slot.name} is missing",
              nodeId,
              slot.name,
            )
          }
        }
    }
    return null
  }

  /** [validateWrite] against no document — the catalog's own rules alone. */
  public fun validateWrite(
    catalog: CatalogCapabilityV1,
    node: DesignNodeV1,
    property: String,
  ): UiBuilderCatalogIssue? = validateWrite(catalog, node, property, pinnedAssetKeys = emptySet())

  override fun validateWrite(
    catalog: CatalogCapabilityV1,
    document: DesignDocumentV1,
    node: DesignNodeV1,
    property: String,
  ): UiBuilderCatalogIssue? =
    validateWrite(catalog, node, property, pinnedAssetKeys = document.assets.keys)

  private fun validateWrite(
    catalog: CatalogCapabilityV1,
    node: DesignNodeV1,
    property: String,
    pinnedAssetKeys: Set<String>,
  ): UiBuilderCatalogIssue? {
    val value = node.properties[property] ?: return null
    // Undeclared is `validate`'s finding, and a wrapper the value rules do not speak about — a
    // state binding, a number — is answered by `jsonType` there too.
    components[catalog.benchmark.catalogSystemId]?.get(node.componentId)?.properties?.firstOrNull {
      it.name == property
    } ?: return null
    return when {
      isColourProperty(property) -> colourWriteIssue(node, property, value)
      property == ASSET_KEY_PROPERTY ->
        assetKeyWriteIssue(catalog, node, property, value, pinnedAssetKeys)
      else -> null
    }
  }

  /**
   * The colour rule: the wrapper says colour, and the value is one the canvas draws.
   *
   * A `string` wrapper committed, rendered, and was refused at export with a sentence about `Text`
   * (#476); a `colorToken` the canvas does not draw committed, and the renderer threw on it. Both
   * refused here, with the wording the `background` modifier's export refusal already had — it
   * names the property, says what a colour looks like, and names the wrapper that was wrong.
   */
  private fun colourWriteIssue(
    node: DesignNodeV1,
    property: String,
    value: UiValueV1,
  ): UiBuilderCatalogIssue? {
    val colour =
      when (value) {
        is StringValueV1 ->
          return issue(
            "INVALID_PROPERTY",
            "property $property is a colour, which is written as a `#RRGGBB` literal or as a " +
              "theme role — a `color` or `colorToken` wrapper, not `string`",
            node.id,
            property,
          )
        is ColorValueV1 -> value.value
        is ColorTokenValueV1 -> value.value
        else -> return null
      }
    if (isDrawableColour(colour)) return null
    return issue(
      "INVALID_PROPERTY",
      "property $property is `$colour`, which is neither a `#RRGGBB` literal nor one of the theme " +
        "roles the canvas draws (${CANVAS_COLOR_TOKENS.joinToString(", ")})",
      node.id,
      property,
    )
  }

  /**
   * The asset rule: the key is one the catalog's registry lists, or one the design has pinned.
   *
   * `asset/image` is the one component whose value the renderer must *resolve*, and it was the one
   * property nothing checked: the reducer accepted `avatar-lain`, and every render of the design
   * then failed with an `IllegalStateException` (#484). The registry is
   * `statusSemantics.assetRegistry.keys`; a catalog that declares none says nothing about keys.
   * [pinnedAssetKeys] are the design's own `assets` — what the asset lane put behind a key (#478) —
   * and they resolve exactly as a catalog key does, because the canvas draws them.
   */
  private fun assetKeyWriteIssue(
    catalog: CatalogCapabilityV1,
    node: DesignNodeV1,
    property: String,
    value: UiValueV1,
    pinnedAssetKeys: Set<String>,
  ): UiBuilderCatalogIssue? {
    val key =
      when (value) {
        is AssetKeyValueV1 -> value.value
        is StringValueV1 -> value.value
        else -> return null
      }
    val registry = declaredAssetKeys(catalog) ?: return null
    if (key in registry || key in pinnedAssetKeys) return null
    return issue(
      "INVALID_PROPERTY",
      "property $property is `$key`, which no asset this design or its catalog can draw " +
        "resolves; the catalog declares ${registry.joinToString(", ")}, and a picture is pinned " +
        "under a key of your own by PUT /api/ui-builder/v1/designs/{designId}/assets/{assetKey} " +
        "or the ui_builder_put_asset tool" +
        (if (pinnedAssetKeys.isEmpty()) ""
        else "; this design has pinned ${pinnedAssetKeys.sorted().joinToString(", ")}"),
      node.id,
      property,
    )
  }

  public companion object {
    public const val RESOURCE: String =
      "/ee/schimke/composeai/uibuilder/catalogs/m3-catalog-v1.json"

    /**
     * The value-kind rules, mirrored from `PropertyValueKinds` in `:ui-builder-export`, which this
     * module cannot reach — the same arrangement `slotAccepts` has with `SlotCapability.accepts`. A
     * property's name states its kind: `color` and `…Color` hold a colour, `assetKey` a key into
     * the catalog's asset registry. `docs/design/UI_BUILDER_VALUE_SEMANTICS.md` is the decision.
     */
    public fun isColourProperty(property: String): Boolean =
      property == "color" || property.endsWith("Color")

    /** The property whose value the canvas has to resolve against the catalog's registry. */
    public const val ASSET_KEY_PROPERTY: String = "assetKey"

    /** The `statusSemantics` entry listing the asset keys the canvas draws, as `{"keys": […]}`. */
    public const val ASSET_REGISTRY_KEY: String = "assetRegistry"

    /** The `statusSemantics` entry listing [CANVAS_COLOR_TOKENS] for a reader of the catalog. */
    public const val COLOR_TOKENS_KEY: String = "colorTokens"

    /**
     * The theme roles the canvas draws — `PropertyValueKinds.CANVAS_COLOR_TOKENS`, and the
     * catalog's own `statusSemantics.colorTokens.roles`; `ProductionUiBuilderRuntimeTest` pins the
     * three to one list.
     */
    public val CANVAS_COLOR_TOKENS: Set<String> =
      setOf(
        "background",
        "surface",
        "surfaceContainer",
        "surfaceContainerLow",
        "surfaceContainerHigh",
        "surfaceContainerHighest",
        "primary",
        "onPrimary",
        "secondary",
        "onSecondary",
        "tertiary",
        "onTertiary",
        "onSurface",
        "onSurfaceVariant",
        "outlineVariant",
        "transparent",
      )

    /** Whether the canvas draws [value]: a hex literal, a listed role, or nothing (the default). */
    public fun isDrawableColour(value: String): Boolean =
      value.isEmpty() || COLOR_LITERAL.matches(value) || value in CANVAS_COLOR_TOKENS

    /** The asset keys [catalog] declares, or null when it declares no registry. */
    public fun declaredAssetKeys(catalog: CatalogCapabilityV1): Set<String>? =
      declaredStrings(catalog.statusSemantics, ASSET_REGISTRY_KEY, "keys")

    /** The theme roles [catalog] lists, or null when it lists none. */
    public fun declaredColorTokens(catalog: CatalogCapabilityV1): Set<String>? =
      declaredStrings(catalog.statusSemantics, COLOR_TOKENS_KEY, "roles")

    private fun declaredStrings(
      statusSemantics: JsonObject,
      entry: String,
      field: String,
    ): Set<String>? {
      val declared =
        (statusSemantics[entry] as? JsonObject)?.get(field) as? JsonArray ?: return null
      return declared.mapNotNullTo(linkedSetOf()) {
        (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
      }
    }

    private val COLOR_LITERAL = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
    public const val CURRENT_CAPABILITY_DIGEST: String = "candidate"
    public const val DEFAULT_CATALOG_SYSTEM_ID: String = "m3-catalog"
    public const val REMOTE_M3_CATALOG_SYSTEM_ID: String = "remote-m3"
    public const val WEAR_M3_CATALOG_SYSTEM_ID: String = "wear-m3"

    /** The `statusSemantics` key a catalog declares its platform under. */
    public const val PLATFORM_KEY: String = "platform"

    /** The `statusSemantics` key a catalog lists the packs merged into it under. */
    public const val COMPONENT_PACKS_KEY: String = "componentPacks"

    /** The platform word of a catalog that declares none. */
    public const val DEFAULT_PLATFORM: String = "mobile"

    private val SAFE_SYSTEM_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    private fun packagedM3CatalogSource(): String =
      checkNotNull(CurrentM3UiBuilderCatalogExecutor::class.java.getResourceAsStream(RESOURCE)) {
          "packaged M3 UI-builder catalog is missing"
        }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
  }
}

/**
 * A component pack as the host admits it: another catalog's components, ready to be merged.
 *
 * The runtime does not derive packs — a pack derived from a served catalog's component record is
 * `:server`'s to build, because reading a record needs the discovery library this module's boundary
 * keeps out. What arrives here is the finished list of capabilities and the facts the merge needs
 * about them.
 *
 * @property id the pack's id, which is the served catalog it came from and the prefix every one of
 *   its component ids carries (`confetti-mobile/session-card`). Checked against the same id rule as
 *   a catalog, because it reaches a served-catalog branch name on the native lane.
 * @property label what the editor calls the pack.
 * @property platform the platform word (`mobile`, `wear`, `remote-compose`) naming which enabled
 *   catalogs receive it. Compared as written; a catalog declaring no platform is
 *   [CurrentM3UiBuilderCatalogExecutor.DEFAULT_PLATFORM].
 * @property nativeCatalog the served catalog whose bundle compiles a design that uses the pack, or
 *   null when the pack does not say. A derived pack names the catalog it was derived from.
 * @property components the capabilities, every id prefixed `<id>/`.
 * @property notes a sentence for the settings row; empty when there is nothing to add.
 */
public data class UiBuilderComponentPackSource(
  val id: String,
  val label: String,
  val platform: String,
  val nativeCatalog: String?,
  val components: List<ComponentCapabilityV1>,
  val notes: String = "",
) {
  init {
    require(id.isNotBlank()) { "a component pack needs an id" }
    require(platform.isNotBlank()) { "component pack $id needs a platform" }
    components.forEach { component ->
      require(component.componentId.startsWith("$id/")) {
        "component ${component.componentId} of pack $id must be namespaced under `$id/`"
      }
    }
    require(components.map { it.componentId }.distinct().size == components.size) {
      "component pack $id declares a component id twice"
    }
  }
}

/**
 * The Remote Compose ids `wear-m3` offers without calling them stand-ins for anything.
 *
 * A *Material* component could not be shared — a Wear card is not a Material 3 card — while a
 * *foundation* one is the same declaration on both platforms. These three are neither. They are the
 * Remote Compose seam itself — a published document, the switch into the remote vocabulary, and the
 * custom component that switches back out — and none of them is a Wear Compose component whose
 * fidelity a note could be making a claim about.
 */
/**
 * The node that switches a subtree into the Remote Compose vocabulary.
 *
 * Spelled here rather than imported from `:ui-builder-export`, which owns the same two constants
 * for the emitter and the canvas. This module's dependency graph is a positive allowlist checked by
 * `checkUiBuilderRuntimeBoundary` and written down in `docs/design/UI_BUILDER_PROJECT_BOUNDARY.md`;
 * taking an edge to the export module to reach two string literals would be a change to that
 * document for no gain. `SlotAcceptanceTest`'s committed table is what keeps the two spellings
 * honest — a catalog naming an id no component declares shows up there.
 */
internal const val REMOTE_COMPOSE_INLINE_COMPONENT_ID = "remote-compose/inline"

/** The node that switches back out of it — see [REMOTE_COMPOSE_INLINE_COMPONENT_ID]. */
internal const val REMOTE_COMPOSE_CUSTOM_COMPONENT_ID = "remote-compose/custom"

/**
 * The three namespaces `compose-foundation` owns: `androidx.compose.foundation` and
 * `androidx.compose.ui` publish one Box, one Column, one Row and one Image, not one per design
 * system.
 */
internal val FOUNDATION_NAMESPACES = listOf("layout/", "shape/", "asset/")

/**
 * The fourth, and NOT the foundation's.
 *
 * `remote-compose/document`, `/inline` and `/custom` are the seams into Remote Compose — a
 * different library from Compose UI, and `remote-m3` is the catalog that describes it. They are
 * separated here so [composeFoundationCatalog] takes them from a Remote Compose source rather than
 * declaring them, which is the shape that lets `remote-m3` publish them and this build stop
 * carrying them (#819).
 *
 * Kept inside [BUILDER_NAMESPACES] because the INJECTION rule has not changed: a catalog is still
 * right to publish only its own prefix, and a published shelf still needs these on it.
 */
internal const val REMOTE_COMPOSE_NAMESPACE: String = "remote-compose/"

/**
 * The id namespaces the BUILDER owns, on every shelf.
 *
 * Not a design system's: a box, a gradient, an image and the Remote Compose seams are the builder's
 * own vocabulary, which is why a catalog is right to publish components only under its own prefix
 * and why [composeFoundationCatalog] supplies the rest. Adding a namespace here widens what every
 * published catalog is handed, so it is a deliberate list rather than a pattern.
 *
 * Named rather than derived from the packaged catalog's prefix, deliberately: that catalog declares
 * no `componentIdPrefix`, and "everything the published catalog does not own" would hand a future
 * `m4/` catalog the whole `m3/` shelf.
 */
internal val BUILDER_NAMESPACES = FOUNDATION_NAMESPACES + REMOTE_COMPOSE_NAMESPACE

internal val REMOTE_COMPOSE_BORROWED_AS_THEMSELVES =
  setOf(
    "remote-compose/document",
    REMOTE_COMPOSE_INLINE_COMPONENT_ID,
    REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
  )

/**
 * The note a shared foundation component carries on a Wear palette.
 *
 * A Material component could never be shared — a Wear card is not a Material 3 card and the two
 * libraries are not used together — but Box, Column, Row and Image are the same declarations on
 * both platforms, so there is nothing to stand in for and nothing to translate.
 *
 * Shared between `wearM3Catalog` and [composeFoundationCatalog] rather than written out twice. The
 * two have to agree exactly — `ComposeFoundationFaithfulnessTest` compares the components they
 * donate field for field — and one constant cannot drift the way a copied string can.
 *
 * It lives in THIS file rather than beside the foundation because it names a catalog, and
 * `.github/scripts/ui-builder-catalog-literals.sh` holds that name to the files that already
 * carried one; a new file may not add one. When `wearM3Catalog` goes (#819 step 3) this constant
 * goes with it, and the note then has to say the same thing without naming a catalog — or move into
 * the published catalog's own data, which is where the contract puts it.
 */
internal const val WEAR_FOUNDATION_NOTE: String =
  "Foundation, shared by Compose on both platforms — `androidx.compose.foundation` " +
    "and `androidx.compose.ui` publish one of these, not two. It is the real " +
    "component rather than a stand-in, which is why `wear-m3` shares it and shares " +
    "no Material component at all."

/** The platform word a catalog declares, or the default for one that says nothing. */
internal val CatalogCapabilityV1.platform: String
  get() = declaredPlatform ?: CurrentM3UiBuilderCatalogExecutor.DEFAULT_PLATFORM

/**
 * The platform word a catalog declares, or null when it declares none.
 *
 * Separated from [platform] because the difference matters in exactly one place: choosing a catalog
 * a published one borrows its builder vocabulary from. "Says mobile" and "says nothing" are the
 * same answer everywhere else, and must not be here -- see `donorFor`.
 */
internal val CatalogCapabilityV1.declaredPlatform: String?
  get() =
    statusSemantics[CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY]
      ?.let { it as? JsonPrimitive }
      ?.contentOrNull
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotEmpty)

/**
 * This catalog with [packs] merged in, or itself when there are none.
 *
 * Three things change and nothing else does. The components are appended, in pack order, after the
 * catalog's own — an id the catalog already has is a configuration error and refused, since two
 * capabilities under one id would make validation depend on list order. The insert-panel shelf
 * declaration gains one shelf per pack, named for the pack, so its components read as a group
 * rather than being scattered by kind among the catalog's own; an existing shelf table is extended,
 * a missing one is created, and the pack's shelves go after every shelf the catalog declared. And
 * `componentPacks` records what was merged, which is how the editor and the server tell a pack's
 * component from the catalog's own afterwards — `UiBuilderComponentPacks` in `:ui-builder-export`
 * reads it back.
 *
 * The catalog pin is untouched. A pack is part of the catalog the way `wear-m3`'s borrowed
 * foundation components are, and a design pinned before a pack was admitted keeps resolving.
 */
private fun CatalogCapabilityV1.withPacks(
  packs: List<UiBuilderComponentPackSource>
): CatalogCapabilityV1 {
  if (packs.isEmpty()) return this
  val own = components.map { it.componentId }.toSet()
  packs.forEach { pack ->
    pack.components.forEach { component ->
      require(component.componentId !in own) {
        "pack ${pack.id} redeclares ${component.componentId}, which ${benchmark.catalogSystemId} already has"
      }
    }
  }
  val existingMenu = (statusSemantics["componentMenu"] as? JsonObject) ?: JsonObject(emptyMap())
  val existingOrder = (existingMenu["groupOrder"] as? JsonArray) ?: JsonArray(emptyList())
  val existingEntries = (existingMenu["components"] as? JsonObject) ?: JsonObject(emptyMap())
  val menu =
    JsonObject(
      existingMenu +
        ("groupOrder" to
          JsonArray(existingOrder + packs.map { JsonPrimitive(it.label) }.distinct())) +
        ("components" to
          JsonObject(
            existingEntries +
              packs.flatMap { pack ->
                pack.components.map { component ->
                  component.componentId to buildJsonObject { put("group", pack.label) }
                }
              }
          ))
    )
  val declaredPacks =
    JsonArray(
      packs.map { pack ->
        buildJsonObject {
          put("id", pack.id)
          put("label", pack.label)
          put("platform", pack.platform)
          pack.nativeCatalog?.let { put("nativeCatalog", it) }
          put("components", JsonArray(pack.components.map { JsonPrimitive(it.componentId) }))
          if (pack.notes.isNotEmpty()) put("notes", pack.notes)
        }
      }
    )
  return newBuilder()
    .also {
      it.statusSemantics =
        JsonObject(
          statusSemantics +
            ("componentMenu" to menu) +
            (CurrentM3UiBuilderCatalogExecutor.COMPONENT_PACKS_KEY to declaredPacks)
        )
      it.components = components + packs.flatMap { it.components }
    }
    .build()
}

/**
 * This catalog's component menu with one more component shelved on [group].
 *
 * Every miss degrades rather than throws, exactly as [CatalogCapabilityV1.withPacks] and
 * `ComponentMenu.from` do: a catalog declaring no menu gets one holding this single entry, and a
 * group name the order does not carry is appended rather than dropped. A wrong menu must never be
 * the reason a component cannot be inserted.
 */
/** Each component's shelf, as `componentMenu.components` states it. */
private fun JsonObject.menuGroups(): Map<String, String> {
  val menu = (this["componentMenu"] as? JsonObject) ?: return emptyMap()
  val entries = (menu["components"] as? JsonObject) ?: return emptyMap()
  return entries
    .mapNotNull { (id, entry) ->
      ((entry as? JsonObject)?.get("group") as? JsonPrimitive)?.contentOrNull?.let { id to it }
    }
    .toMap()
}

/** The shelf order a `statusSemantics` block states. */
private fun JsonObject.menuGroupOrder(): List<String> =
  ((this["componentMenu"] as? JsonObject)?.get("groupOrder") as? JsonArray).orEmpty().mapNotNull {
    (it as? JsonPrimitive)?.contentOrNull
  }

/**
 * The componentMenu with one more entry, and its group in `groupOrder`.
 *
 * @param reference where a NEW group belongs, when the caller knows: the donor's own order, so an
 *   injected `layout/box` puts "Layout" where the donor has it rather than wherever the injection
 *   loop reached it. Appending was how `Layout` and `Scaffolds` — the builder's primary shelves —
 *   ended up below every catalog group. Empty for callers with no opinion, which appends as before.
 */
private fun JsonObject.withMenuEntry(
  componentId: String,
  group: String,
  reference: List<String> = emptyList(),
): JsonObject {
  val menu = (this["componentMenu"] as? JsonObject) ?: JsonObject(emptyMap())
  val order = (menu["groupOrder"] as? JsonArray) ?: JsonArray(emptyList())
  val names = order.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
  val placed =
    when {
      group in names -> names
      // The last reference group that is already present decides the insertion point, so the
      // donor's relative order is reproduced while the catalog's own groups keep theirs.
      else -> {
        val before = reference.takeWhile { it != group }.filter { it in names }
        val at = before.lastOrNull()?.let { names.indexOf(it) + 1 } ?: 0
        if (reference.isEmpty()) names + group else names.take(at) + group + names.drop(at)
      }
    }
  val entries = (menu["components"] as? JsonObject) ?: JsonObject(emptyMap())
  return JsonObject(
    menu +
      ("groupOrder" to JsonArray(placed.map(::JsonPrimitive))) +
      ("components" to
        JsonObject(entries + (componentId to buildJsonObject { put("group", group) })))
  )
}

/**
 * Whether [slot] accepts a child of [component]: both the role and a trait have to match, an empty
 * list constrains nothing on its axis, and `AnyContent` is the same as declaring no traits.
 *
 * This is `SlotCapability.accepts` from `:ui-builder`, written again because this module cannot
 * reach that one; `SlotAcceptanceTest` on either side pins both to the same committed table so they
 * cannot drift. Before this, an empty role list counted as a role *match* and the two were joined
 * with *or*, so for a catalog built with `singleSlot`/`manySlot` — every slot below — the server
 * checked traits on no slot at all, while the editor did.
 */
internal fun slotAccepts(slot: SlotCapabilityV1, component: ComponentCapabilityV1): Boolean {
  val roleAccepted = slot.acceptedRoles.isEmpty() || component.role in slot.acceptedRoles
  val traitAccepted =
    slot.acceptedTraits.isEmpty() ||
      "AnyContent" in slot.acceptedTraits ||
      component.traits.any(slot.acceptedTraits::contains)
  return roleAccepted && traitAccepted
}

/**
 * The first Remote M3 authoring surface is deliberately a reviewed subset, not an alias for the
 * complete Material 3 catalog. The host dimensions copy the stable 240dp-screen squircle preview
 * contract from wear-m3-catalog: 200x60dp or 200x108dp content, 8dp padding on every edge and a
 * 26dp corner radius, producing 216x76dp and 216x124dp canvases.
 */
/**
 * The Wear widget host frame's authored parameters.
 *
 * These are `androidx.glance.wear.composable.WearWidgetContainer`'s four arguments. The one that
 * matters most is `background`: on-device it is the *widget's* own background — the brush passed to
 * `WearWidgetDocument` — and the container paints it as the round rect, so the coloured squircle IS
 * the widget. A scaffold without it forced an author to fake the background with a filled surface
 * inside the content slot, which draws a coloured rectangle inside a differently-coloured frame:
 * not what any widget looks like.
 *
 * Defaults match upstream. `background` defaults to the literal `#FF272430` that
 * `WearWidgetContainer` forks from
 * `androidx.wear.compose.material3.ColorScheme.surfaceContainerLow` and applies when a widget
 * declares no background of its own; padding and radius default to the 8dp/26dp squircle spec every
 * shipped `WidgetPreviewParams` provider carries.
 */
private fun widgetContainerProperties(): List<PropertyCapabilityV1> =
  listOf(
    PropertyCapabilityV1.Builder("background", JsonPrimitive("string"))
      .also {
        it.notes =
          "The widget's own background, painted by the host as the rounded rect. Defaults to " +
            "#FF272430, the colour WearWidgetContainer applies to a widget that declares none."
      }
      .build(),
    PropertyCapabilityV1.Builder("horizontalPaddingDp", JsonPrimitive("number"))
      .also { it.notes = "WearWidgetParams.horizontalPaddingDp; 8 in every shipped preview spec." }
      .build(),
    PropertyCapabilityV1.Builder("verticalPaddingDp", JsonPrimitive("number"))
      .also { it.notes = "WearWidgetParams.verticalPaddingDp; 8 in every shipped preview spec." }
      .build(),
    PropertyCapabilityV1.Builder("cornerRadiusDp", JsonPrimitive("number"))
      .also {
        it.notes =
          "WearWidgetParams.cornerRadiusDp: 26 squircle, 999 round, 0 rectangular. The host draws " +
            "this radius behind the content rather than clipping to it."
      }
      .build(),
  )

/**
 * How the builder's insert panel shelves `wear-m3`, and which property carries a component's
 * variants.
 *
 * Wear's own families rather than Material's: a watch app is a **screen** holding a **list**, so
 * those lead, and `Text inputs` — a whole shelf on the phone catalog — does not exist here at all.
 * `wear-m3/edge-button`'s `size` is a variant rather than a dimension: an edge button comes in four
 * sizes the way a card comes in four kinds, and picking one is choosing which button, not nudging a
 * number.
 *
 * Deliberately absent: `transformation` on the lazy column and `segmented` on the slider are
 * behaviours a design turns on, not kinds of component; `iconKey` is forty-six icons; and
 * `wear-m3/text.style` is fifteen type scales, which is a property of a text rather than a kind of
 * Text. The same three calls the M3 declaration makes, for the same reasons.
 */
internal fun wearComponentMenu(): JsonObject {
  val shelves =
    listOf(
      "Screens" to listOf("wear-m3/screen-scaffold"),
      "Layout" to listOf("layout/box", "layout/column", "layout/row"),
      "Lists" to
        listOf(
          "wear-m3/transforming-lazy-column",
          "wear-m3/list-header",
          "wear-m3/list-sub-header",
        ),
      "Actions" to
        listOf(
          "wear-m3/button",
          "wear-m3/text-button",
          "wear-m3/icon-button",
          "wear-m3/edge-button",
          "wear-m3/button-group",
        ),
      "Selection" to
        listOf(
          "wear-m3/checkbox-button",
          "wear-m3/switch-button",
          "wear-m3/radio-button",
          "wear-m3/slider",
          "wear-m3/stepper",
          "wear-m3/date-picker",
          "wear-m3/time-picker",
        ),
      "Containment" to
        listOf(
          "wear-m3/card",
          "wear-m3/alert-dialog",
          "wear-m3/confirmation-dialog",
          "wear-m3/open-on-phone-dialog",
        ),
      "Communication" to listOf("wear-m3/progress-indicator"),
      "Content" to listOf("wear-m3/text", "wear-m3/icon", "asset/image"),
      // The "Embedded" shelf held the three Remote Compose seams and is gone with them. It comes
      // back when they do; a shelf with nothing on it is a heading an author opens for nothing.
    )
  val variantProperties =
    mapOf(
      "wear-m3/card" to "variant",
      "wear-m3/button" to "variant",
      "wear-m3/text-button" to "variant",
      "wear-m3/icon-button" to "variant",
      "wear-m3/edge-button" to "size",
      "wear-m3/progress-indicator" to "variant",
      "wear-m3/confirmation-dialog" to "variant",
      "wear-m3/date-picker" to "type",
      "wear-m3/time-picker" to "type",
    )
  return buildJsonObject {
    putJsonArray("groupOrder") { shelves.forEach { (name, _) -> add(JsonPrimitive(name)) } }
    putJsonObject("components") {
      shelves.forEach { (name, componentIds) ->
        componentIds.forEach { componentId ->
          putJsonObject(componentId) {
            put("group", JsonPrimitive(name))
            variantProperties[componentId]?.let { put("variantProperty", JsonPrimitive(it)) }
          }
        }
      }
    }
  }
}

/**
 * The modifiers a `remote-m3` component may advertise: what `RemoteContentEmitter` can write.
 *
 * A copy, and it has to be one. The emitter lives in `:ui-builder-export` and this module's
 * `CheckUiBuilderRuntimeBoundary` keeps that classpath out on purpose, so the list cannot be
 * imported from the one place it is derived. `RemoteContentModifierParityTest` in `:server` — which
 * has both — fails when this set and `REMOTE_CONTENT_MODIFIERS` disagree, so the copy cannot rot
 * quietly the way the last one did.
 */
internal val REMOTE_M3_MODIFIERS =
  setOf(
    "align",
    "alignHorizontal",
    "alignVertical",
    "alpha",
    "background",
    "border",
    "clip",
    "collapsiblePriority",
    "fillMaxHeight",
    "fillMaxSize",
    "fillMaxWidth",
    "height",
    "heightIn",
    "horizontalScroll",
    "offset",
    "padding",
    "rotate",
    "scale",
    "sharedElement",
    "size",
    "verticalScroll",
    "weight",
    "width",
    "widthIn",
    "wrapContentSize",
    "zIndex",
  )

/**
 * The modifiers only Remote Compose has, offered on every node a widget body lays out.
 *
 * Neither is in the borrowed Compose vocabulary, so filtering a borrowed list by
 * [REMOTE_M3_MODIFIERS] can never produce them: they are appended instead. `sharedElement` matches
 * an element across the branches of a "Show by state" box and animates its bounds between them.
 * `collapsiblePriority` is a member of the collapsible scopes; the emitter refuses it anywhere
 * else.
 */
internal val REMOTE_ONLY_MODIFIERS: List<String> = listOf("collapsiblePriority", "sharedElement")

/**
 * A borrowed modifier list narrowed to what the Remote emitter writes, plus
 * [REMOTE_ONLY_MODIFIERS].
 */
internal fun List<String>.remoteAuthorableModifiers(): List<String> =
  filter { it in REMOTE_M3_MODIFIERS && it !in REMOTE_ONLY_MODIFIERS } + REMOTE_ONLY_MODIFIERS

/**
 * The note a component outside the Glance Wear widget profile carries on the palette.
 *
 * `remote-creation-compose` publishes it and the builder writes it, but `GlanceWearProfiles` admits
 * none of its operations, so a widget using it fails to capture on Android. Offered anyway — the
 * native lane is where that failure shows, and this note is where an author learns of it first.
 */
private fun outsideWidgetProfile(operation: String): String =
  "Not in the Glance Wear widget profile ($operation): the Native / Live render of a widget " +
    "using it fails while the document is captured."

/**
 * The layouts `remote-creation-compose` publishes that the packaged foundation does not declare.
 *
 * Each has a foundation sibling it is derived from — same slot, same arrangement vocabulary — and
 * differs in id, name and the call it writes. `layout/flow-row` is not here: the packaged catalog
 * declares it, and `RemoteFlowRow` takes the same arguments.
 *
 * - `layout/fit-box`: `RemoteFitBox`. Its children are alternatives, largest first; it shows the
 *   first one that fits. In the Glance Wear widget profile (`LAYOUT_FIT_BOX`).
 * - `layout/collapsible-column` / `layout/collapsible-row`: `RemoteCollapsibleColumn` and
 *   `RemoteCollapsibleRow`, which HIDE whole children, lowest `collapsiblePriority` first, rather
 *   than squeezing them. Outside the widget profile — see [outsideWidgetProfile].
 */
internal fun remoteOnlyLayout(
  componentId: String,
  declared: Map<String, ComponentCapabilityV1>,
): ComponentCapabilityV1? {
  fun derived(
    from: String,
    displayName: String,
    notes: String,
    properties: List<PropertyCapabilityV1>? = null,
  ): ComponentCapabilityV1 {
    val donor = declared.getValue(from)
    return donor
      .newBuilder()
      .also {
        it.componentId = componentId
        it.displayName = displayName
        it.slots = donor.slots.map { slot -> slot.acceptingRemoteAuthorable() }
        properties?.let { declaredProperties -> it.properties = declaredProperties }
        it.wasm = donor.wasm.newBuilder().also { wasm -> wasm.notes = notes }.build()
        // Not a Compose call: the regular Compose exporter has no counterpart to write, and the
        // Remote emitter writes these by id.
        it.code = null
      }
      .build()
  }
  return when (componentId) {
    "layout/fit-box" ->
      derived(
        "layout/box",
        "Fit box",
        "RemoteFitBox: its children are alternatives, largest first, and it shows the first one that fits.",
        listOf(
          PropertyCapabilityV1.Builder("horizontalAlignment", JsonPrimitive("string"))
            .also {
              it.allowedValues =
                listOf(JsonPrimitive("start"), JsonPrimitive("center"), JsonPrimitive("end"))
              it.notes = "Where the chosen child sits across the box. Centred when absent."
            }
            .build(),
          PropertyCapabilityV1.Builder("verticalArrangement", JsonPrimitive("string"))
            .also {
              it.allowedValues =
                listOf(JsonPrimitive("top"), JsonPrimitive("center"), JsonPrimitive("bottom"))
              it.notes = "Where the chosen child sits down the box. Centred when absent."
            }
            .build(),
        ),
      )
    "layout/collapsible-column" ->
      derived(
        "layout/column",
        "Collapsible column",
        "RemoteCollapsibleColumn: hides whole children, lowest collapsiblePriority first, when it " +
          "runs out of height. " +
          outsideWidgetProfile("LAYOUT_COLLAPSIBLE_COLUMN"),
      )
    "layout/collapsible-row" ->
      derived(
        "layout/row",
        "Collapsible row",
        "RemoteCollapsibleRow: hides whole children, lowest collapsiblePriority first, when it " +
          "runs out of width. " +
          outsideWidgetProfile("LAYOUT_COLLAPSIBLE_ROW"),
      )
    else -> null
  }
}

/**
 * A container slot narrowed to what `RemoteContentEmitter` can write inside it.
 *
 * The donor slots accept `AnyContent`, which would let a document, a gradient or a nested widget
 * host into a Remote layout — each refused at export. The widget content slots already accept only
 * `RemoteAuthorable`; the Remote-only layouts take the same rule.
 */
private fun SlotCapabilityV1.acceptingRemoteAuthorable(): SlotCapabilityV1 =
  newBuilder().also { it.acceptedTraits = listOf("RemoteAuthorable") }.build()

/** The ids [remoteOnlyLayout] answers, in palette order. */
internal val REMOTE_ONLY_LAYOUT_IDS: List<String> =
  listOf("layout/fit-box", "layout/collapsible-column", "layout/collapsible-row")

/**
 * `layout/flow-row` narrowed for a widget body: the packaged declaration, with the note that
 * `LAYOUT_FLOW` is outside the Glance Wear widget profile (it is an experimental-profile
 * operation).
 */
internal fun ComponentCapabilityV1.withWidgetProfileNote(): ComponentCapabilityV1 =
  if (componentId != "layout/flow-row") this
  else
    newBuilder()
      .also {
        it.slots = slots.map { slot -> slot.acceptingRemoteAuthorable() }
        it.wasm =
          wasm
            .newBuilder()
            .also { wasm -> wasm.notes = "RemoteFlowRow. " + outsideWidgetProfile("LAYOUT_FLOW") }
            .build()
      }
      .build()

/**
 * A borrowed component, narrowed to what `RemoteContentEmitter` can write into a widget body.
 *
 * One function because two derivations apply it — [remoteM3Catalog] and the `remote-compose`
 * curation in [composeFoundationCatalog] — and `ComposeFoundationFaithfulnessTest` holds them equal
 * field for field. Every clause here moves a refusal from export time to the moment the author
 * acts, which is the only moment they can do anything about it.
 */
internal fun ComponentCapabilityV1.narrowedForRemoteAuthoring(): ComponentCapabilityV1 =
  newBuilder()
    .also {
      // The palette used to offer 28 modifiers on a widget node while the generator wrote three, so
      // `size`, `background` and `weight` were authorable, drawable, and unexportable
      // (yschimke/compose-preview-server#508).
      it.modifierCapabilities =
        // A brush can only sit in the container's background slot, and `WearWidgetBrush` has no
        // geometry to hang a modifier on — the generator refuses every one it finds there. So the
        // gradient offers none, rather than eighteen that each end in a refusal.
        if (componentId == "shape/linear-gradient") emptyList()
        else modifierCapabilities.remoteAuthorableModifiers()
      // `RemoteAuthorable` is a capability of the Remote Compose emitter, not a property inherited
      // from a mobile component. The reviewed vocabulary does have an emitter branch (or
      // component-record fallback) and may enter a widget body.
      it.traits =
        (traits - "RemoteAuthorable").let { traits ->
          if (componentId !in setOf("remote-compose/document", "shape/linear-gradient")) {
            traits + "RemoteAuthorable"
          } else {
            traits
          }
        }
    }
    .build()

/**
 * `remote-m3`'s palette shelves: the Lottie element under Content, then each Remote Material 3
 * component under the shelf the published catalog files it on.
 *
 * Folded over the whole status semantics rather than the menu alone, because [withMenuEntry] reads
 * the menu out of the semantics it is given and returns the menu: handed a menu, it finds none and
 * drops every shelf the base catalog already declared.
 *
 * The published-catalog foundation's `remote-compose` curation builds its menu with this too, so a
 * shelf the foundation injects into a published catalog lands where it lands here.
 */
internal fun remoteM3ComponentMenu(base: JsonObject): JsonObject =
  RemoteMaterial3.components
    .fold(
      REMOTE_ONLY_LAYOUT_IDS.fold(
        JsonObject(base + ("componentMenu" to base.withMenuEntry("remote-m3/lottie", "Content")))
      ) { semantics, id ->
        JsonObject(semantics + ("componentMenu" to semantics.withMenuEntry(id, "Layout")))
      }
    ) { semantics, component ->
      JsonObject(
        semantics +
          ("componentMenu" to
            semantics.withMenuEntry(
              component.componentId,
              component.group,
              REMOTE_MATERIAL_3_SHELVES,
            ))
      )
    }
    .getValue("componentMenu") as JsonObject

/**
 * The published catalog's shelf order, so the Remote Material 3 shelves land after this catalog's
 * own and in the order wear-m3-catalog files them.
 */
private val REMOTE_MATERIAL_3_SHELVES =
  listOf(
    "Content",
    "Containment",
    "Buttons",
    "Selection buttons",
    "Edge-hugging buttons",
    "Sliders",
    "Steppers",
    "Communication",
  )

/**
 * `androidx.wear.compose.remote.material3` on the widget palette: every component in
 * [RemoteMaterial3], its properties and slots read off its own signature in the embedded record.
 *
 * - **Properties** are the parameters `RemoteContentEmitter`'s record fallback can write: Remote
 *   and Kotlin scalars and colours. A required one — `checked`, `progress`, `value` — is required
 *   here too, and `StarterContent` seeds it, so a component arrives exporting. The rest (colours
 *   objects, shapes, padding) keep their library defaults.
 * - **Slots** are its `@Composable` parameters under their own names, which is what the record
 *   fallback fills them from. They take what a widget body takes, and none is required: an empty
 *   required slot is written as an empty lambda, which is what an unfilled one means.
 * - **The canvas** draws each with the Wear Material 3 adapter the published catalog names, through
 *   its mapping, so a widget shows a Wear button rather than a placeholder.
 * - **Actions** (`onClick`, `onCheckedChange`) are not properties. Unbound, they are written as
 *   `lambdaAction {}`, which is what an action nobody has wired yet is.
 */
private fun remoteMaterial3Components(
  template: ComponentCapabilityV1,
  bodySlot: SlotCapabilityV1,
  supportedWasm: WasmCapabilityV1,
  blockedSvg: SvgCapabilityV1?,
): List<ComponentCapabilityV1> =
  RemoteMaterial3.components.mapNotNull { component ->
    val record = RemoteMaterial3.records[component.componentId] ?: return@mapNotNull null
    val slotParameters = record.parameters.filter { it.composableSlot }
    template
      .newBuilder()
      .also {
        it.componentId = component.componentId
        it.displayName = component.displayName
        it.role = if (slotParameters.isEmpty()) "Leaf" else "Container"
        it.traits = listOf("RemoteContent", "RemoteAuthorable")
        it.slots = slotParameters.map { parameter ->
          bodySlot
            .newBuilder()
            .also { slot ->
              slot.name = parameter.name
              slot.cardinality =
                bodySlot.cardinality
                  .newBuilder()
                  .also { cardinality ->
                    cardinality.min = 0
                    cardinality.max = null
                  }
                  .build()
            }
            .build()
        }
        it.properties =
          record.parameters.mapNotNull { parameter ->
            remoteMaterial3Property(record.symbol.name, parameter)
          }
        it.modifierCapabilities = template.modifierCapabilities.remoteAuthorableModifiers()
        it.wasm =
          supportedWasm
            .newBuilder()
            .also { wasm ->
              wasm.canvas = component.canvas
              wasm.canvasMapping = component.canvasMapping
              wasm.notes =
                "Drawn with Wear Material 3's `${component.canvas}`, the adapter the published " +
                  "catalog names; the widget plays `${record.symbol.name}` itself."
            }
            .build()
        it.code = null
        it.svg =
          blockedSvg
            ?.newBuilder()
            ?.also { svg ->
              svg.notes = "Remote Material 3 inside a widget body has no structured SVG answer."
            }
            ?.build()
      }
      .build()
  }

/** One parameter of a Remote Material 3 component as a property, or null for one it cannot be. */
private fun remoteMaterial3Property(
  symbol: String,
  parameter: TargetParameter,
): PropertyCapabilityV1? {
  if (parameter.composableSlot || parameter.name == "modifier") return null
  fun types(vararg names: String) = JsonArray(names.map(::JsonPrimitive))
  val type =
    when (parameter.typeFqn) {
      "androidx.compose.remote.creation.compose.state.RemoteString" -> types("string", "object")
      "androidx.compose.remote.creation.compose.state.RemoteBoolean" -> types("boolean", "object")
      "androidx.compose.remote.creation.compose.state.RemoteFloat" -> types("number", "object")
      "androidx.compose.remote.creation.compose.state.RemoteColor" -> JsonPrimitive("string")
      "androidx.compose.remote.creation.compose.state.RemoteTextUnit" -> JsonPrimitive("number")
      "kotlin.String" -> JsonPrimitive("string")
      "kotlin.Boolean" -> JsonPrimitive("boolean")
      "kotlin.Int" -> JsonPrimitive("integer")
      "kotlin.Float" -> JsonPrimitive("number")
      else -> return null
    }
  return PropertyCapabilityV1.Builder(parameter.name, type)
    .also {
      it.required = !parameter.hasDefault && !parameter.nullable
      it.notes = "`$symbol`'s `${parameter.name}: ${parameter.type}`."
    }
    .build()
}

private fun remoteM3Catalog(base: CatalogCapabilityV1): CatalogCapabilityV1 {
  val components = base.components.associateBy { it.componentId }
  val box = components.getValue("layout/box")
  val supportedWasm = components.getValue("m3/text").wasm
  val blockedSvg = components.getValue("remote-compose/document").svg
  // A widget body is Remote Compose, not arbitrary Compose UI. Keeping this narrower than
  // `AnyContent` makes the palette refuse a component at insertion time when the Remote Compose
  // emitter has no lowering for it. This is the same catalogue discipline Wear M3 uses: foundation
  // is shared, but a component from a different Material library is not a stand-in for one here.
  val contentSlot =
    box.slots
      .single()
      .newBuilder()
      .also {
        it.name = "content"
        it.cardinality =
          box.slots
            .single()
            .cardinality
            .newBuilder()
            .also {
              it.min = 0
              it.max = 1
            }
            .build()
        it.acceptedTraits = listOf("RemoteAuthorable")
      }
      .build()
  // `WearWidgetBrush` is a CHAIN of drawing elements, and `WearWidgetContainer` folds over it,
  // drawing a round rect per element before the content. A slot is an ordered list of nodes, so the
  // chain models exactly as one — which is why gradients and images are a slot rather than more
  // properties: `background` covers the one-element solid-colour case that a string can carry, and
  // anything the wire cannot say in a string goes in here as a node that already knows how to draw
  // itself.
  //
  // Narrowed to the two traits that ARE brushes. `AnyContent` would let a Text be dropped in as a
  // "background", which upstream has no way to express.
  val backgroundSlot =
    contentSlot
      .newBuilder()
      .also {
        it.name = "background"
        it.cardinality =
          contentSlot.cardinality
            .newBuilder()
            .also {
              it.min = 0
              it.max = null
            }
            .build()
        it.acceptedRoles = listOf("Leaf")
        it.acceptedTraits = listOf("DrawLayer", "ImageContent")
      }
      .build()
  fun widget(componentId: String, displayName: String) =
    box
      .newBuilder()
      .also {
        it.componentId = componentId
        it.displayName = displayName
        it.role = "Scaffold"
        it.traits = listOf("ScreenContent", "WearWidgetHost", "RemoteContentHost")
        it.slots = listOf(backgroundSlot, contentSlot)
        it.properties = widgetContainerProperties()
        it.modifierCapabilities = emptyList()
        it.wasm =
          supportedWasm
            .newBuilder()
            .also {
              it.notes =
                "Compose UI recreation of the Glance Wear squircle host preview; its content slot may host ordinary or nested Remote Compose content, and its background slot the gradient and image brushes WearWidgetBrush chains."
            }
            .build()
        it.code = null
        it.svg =
          blockedSvg
            ?.newBuilder()
            ?.also {
              it.notes =
                "The copied Wear widget host geometry has not yet passed structured SVG parity."
            }
            ?.build()
      }
      .build()
  // **Experimental.** One widget for both container sizes (`AdaptiveWearWidget`). The body is
  // three named slots rather than one, because the template decides where each goes: a Small
  // widget keeps the headline and the action and drops the supporting line. Each takes any number
  // of Remote Compose nodes, stacked in order.
  fun contentSlotNamed(name: String) =
    contentSlot
      .newBuilder()
      .also {
        it.name = name
        it.cardinality = contentSlot.cardinality.newBuilder().also { it.max = null }.build()
      }
      .build()
  val adaptiveWidget =
    widget(AdaptiveWearWidget.COMPONENT_ID, "Wear widget · ${AdaptiveWearWidget.LABEL}")
      .newBuilder()
      .also {
        it.slots = listOf(backgroundSlot) + AdaptiveWearWidget.CONTENT_SLOTS.map(::contentSlotNamed)
        it.wasm =
          supportedWasm
            .newBuilder()
            .also {
              it.notes =
                "Experimental. Edited at Large, where every slot shows; the preview draws the resolved Small and Large widgets in each host shape, and the export branches on WearWidgetParams.containerType."
            }
            .build()
      }
      .build()
  // The reviewed `remote-m3` subset. The last two are brushes, and they are here because the
  // background slot above declares `DrawLayer` and `ImageContent` and nothing else in this list
  // carries either — a slot narrowed to traits no component in its own catalog has is a slot no
  // author can fill from the palette, from a drop, or from a document the validator would accept
  // (yschimke/compose-preview-server#428).
  //
  // `shape/radial-gradient` is deliberately not among them. `RemoteContentEmitter` writes
  // `horizontalGradient`/`verticalGradient` chains from `shape/linear-gradient` and has an authored
  // refusal for `asset/image` that names what to add by hand; a radial gradient would fall through
  // to the generic "is not a widget background brush", which is a worse answer than not offering
  // it. It joins the list when `WearWidgetBrush` gains the chain element it needs.
  val authoringIds =
    listOf(
      "layout/box",
      "layout/column",
      "layout/row",
      "layout/for-each",
      "layout/flow-row",
      *REMOTE_ONLY_LAYOUT_IDS.toTypedArray(),
      "m3/text",
      "remote-compose/document",
      // The way host content gets inside a widget body. A `@RemoteComposable` body cannot call an
      // application's composables — that is the rule this catalog exists to keep — so a custom
      // component is not a hole in it: the document carries an operation naming a renderer, and the
      // application registers Compose under that name. `remote-compose/inline` is deliberately
      // absent, because this catalog's whole body is already a document; an inline switch inside it
      // would be a second answer to a question the container already answered.
      REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
      "shape/linear-gradient",
      "asset/image",
    )
  return base
    .newBuilder()
    .also {
      it.statusSemantics =
        JsonObject(
          base.statusSemantics +
            (CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY to JsonPrimitive("remote-compose")) +
            ("componentMenu" to remoteM3ComponentMenu(base.statusSemantics)) +
            ("previewSurfaces" to
              buildJsonObject {
                putJsonObject("native") {
                  put("fidelity", JsonPrimitive("authoritative"))
                  put("backend", JsonPrimitive("android"))
                }
              })
        )
      it.benchmark =
        base.benchmark
          .newBuilder()
          .also {
            it.id = "remote-m3-wear-widget-scaffolds"
            it.sourceRevision = "wear-m3-catalog@d4e4e684e61d0657aad4ccb7752b8c0ab5d9dedf"
            it.catalogSystemId = CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID
            it.catalogRevision = "wear-widget-scaffolds-v1"
          }
          .build()
      it.components =
        listOf(
          widget("remote-m3/widget-container-small", "Wear widget · Small (216×76dp)"),
          widget("remote-m3/widget-container-large", "Wear widget · Large (216×124dp)"),
          adaptiveWidget,
          lottie(components.getValue("asset/image"), supportedWasm, blockedSvg),
        ) +
          authoringIds.map {
            // Narrowed to what the generator can write; the published-catalog foundation applies
            // the
            // same function.
            (components[it] ?: remoteOnlyLayout(it, components) ?: components.getValue(it))
              .narrowedForRemoteAuthoring()
              .withWidgetProfileNote()
          } +
          remoteMaterial3Components(box, contentSlot, supportedWasm, blockedSvg)
    }
    .build()
}

/**
 * `remote-m3/lottie` — a Lottie animation, **compiled into the document** rather than played from
 * it.
 *
 * This is the whole reason it can exist here and nowhere else. Horologist's `remotecompose/lottie`
 * (vendored at `yschimke/rc-players`'s `third_party/horologist-lottie`, whose PROVENANCE.md carries
 * the pinned commit) is not a Lottie player: `LottieAnimation(json = …)` is a `@RemoteComposable`
 * that parses the animation once, at document-build time, and re-emits every layer, shape and
 * keyframe as Remote Compose operations over the document's own animation clock. What ships to the
 * watch is a `.rc` document that draws the animation — no Lottie runtime on the device, no JSON, no
 * fetch.
 *
 * So it belongs to the catalog whose export *is* a Remote Compose document, and to no other. A
 * `wear-m3` screen or an `m3-catalog` phone screen exports ordinary Compose, where the answer to
 * "play a Lottie" is `lottie-compose`, a different library with a different API that this element
 * would misdescribe.
 *
 * ## Two sources, one compiled thing
 *
 * `url` is where the animation came from and `json` is what gets compiled. The builder resolves the
 * first into the second once, at authoring time (`UiBuilderEditor`'s Lottie fetch), and keeps both:
 * the URL because "which animation is this?" is a question an author asks of a design six months
 * later, and the JSON because a generated widget cannot reach the network while it is being built.
 * An element carrying only a URL is authored-but-unresolved — the canvas says so and
 * [RemoteContentEmitter] refuses it by name rather than writing source that would not compile.
 */
private fun lottie(
  borrowed: ComponentCapabilityV1,
  supportedWasm: WasmCapabilityV1,
  blockedSvg: SvgCapabilityV1?,
): ComponentCapabilityV1 =
  borrowed
    .newBuilder()
    .also {
      it.componentId = "remote-m3/lottie"
      it.displayName = "Lottie animation"
      it.role = "Leaf"
      it.traits = listOf("RemoteContent", "RemoteAuthorable")
      it.slots = emptyList()
      it.properties = lottieProperties()
      it.modifierCapabilities = borrowed.modifierCapabilities.remoteAuthorableModifiers()
      it.wasm =
        supportedWasm
          .newBuilder()
          .also {
            it.notes =
              "Drawn as a named placeholder carrying the animation's source and size. The canvas has no Lottie renderer, and compiling the animation the way the export does — into Remote Compose operations — is Horologist's Android-only creation API, which a Wasm build cannot link. A lookalike would be an impression of an animation nobody could check; the picture comes from the native lane, which builds this design's own generated document."
          }
          .build()
      it.code = null
      it.svg =
        blockedSvg
          ?.newBuilder()
          ?.also {
            it.notes =
              "A placeholder on the canvas must not claim structured SVG parity with an animation."
          }
          ?.build()
    }
    .build()

/**
 * `url`, `json` and `progress` — the animation, where it came from, and whether it runs.
 *
 * `progress` is the one that is not obvious. `LottieAnimation`'s `progress` argument is a
 * `RemoteFloat?`, and leaving it out is what makes the compiled document drive the animation from
 * its own clock: `floor(ANIMATION_TIME * frameRate) % frames`, looping forever. Setting it pins the
 * animation to one frame — 0 is the first, 1 the last — which is what a widget that must not
 * animate (a glanceable state, a still icon) wants. Unset means "run", which is why it has no
 * default rather than defaulting to 0.
 */
private fun lottieProperties(): List<PropertyCapabilityV1> =
  listOf(
    PropertyCapabilityV1.Builder("url", JsonPrimitive("string"))
      .also {
        it.notes =
          "Where the animation was fetched from. Resolved into `json` once, in the builder; the " +
            "generated widget never reaches the network."
      }
      .build(),
    PropertyCapabilityV1.Builder("json", JsonPrimitive("string"))
      .also {
        it.notes =
          "The Lottie animation itself, as JSON text. This is what is compiled into the Remote " +
            "Compose document, so it is what the export needs."
      }
      .build(),
    PropertyCapabilityV1.Builder(
        "progress",
        JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("object"))),
      )
      .also {
        it.notes =
          "Pins the animation to one frame, 0 (first) to 1 (last). Unset — the default — lets the " +
            "document's animation clock run it in a loop."
      }
      .build(),
  )

/**
 * The Wear screen host's authored parameters.
 *
 * `androidx.wear.compose.material3.ScreenScaffold` is NOT the widget container's kind of stand-in.
 * The widget frame is drawn by the launcher, so `WearWidgetCodeExporter` erases it;
 * `ScreenScaffold` is a composable the author calls, so this scaffold is *emitted* rather than
 * erased. What it fakes is only the drawing: the browser has no Wear Compose to draw with, so the
 * canvas approximates the frame and the generated Kotlin names the real one.
 *
 * The screen's diameter is deliberately absent. It is the document's own frame — the Screen
 * inspector's Wear OS device presets already carry 192/227/240dp at the right density — and a fifth
 * property would be a second answer to a question the environment already answers.
 */
private fun wearScreenScaffoldProperties(): List<PropertyCapabilityV1> =
  listOf(
    PropertyCapabilityV1.Builder("timeText", JsonPrimitive("string"))
      .also {
        it.notes =
          "The curved status strip's text. Frozen rather than live: a design whose render changed " +
            "every minute could not be diffed. Empty draws no strip, which is `ScreenScaffold` " +
            "without a `timeText` argument."
      }
      .build(),
    PropertyCapabilityV1.Builder("scrollIndicator", JsonPrimitive("boolean"))
      .also {
        it.notes =
          "Whether the generated screen gives `ScreenScaffold` a scroll indicator. The canvas draws " +
            "none either way: an indicator shows where a viewport sits in the content, and the " +
            "long-screenshot extent has no viewport. The real capture agrees — a `ScrollMode.LONG` " +
            "render sets `LocalScrollCaptureInProgress` and the emitted scaffold suppresses the " +
            "indicator while it is set, which is what keeps a stitched capture free of the dashes " +
            "an indicator drawn per frame leaves down the edge."
      }
      .build(),
    PropertyCapabilityV1.Builder("background", JsonPrimitive("string"))
      .also {
        it.notes =
          "The screen's background. Wear is dark-first, so it defaults to the Wear Material 3 " +
            "`background` — pure black — rather than to the editor theme's surface."
      }
      .build(),
  )

// ── The property vocabulary
//
// Small builders rather than one component's list copied onto another's. A Wear `Text` and a
// Material 3 `Text` both take a `style` whose values are the same fifteen role names, and that
// sameness is a fact about Compose's type scale rather than about either component — so the
// *vocabulary* is shared and every component states its own properties from it. What is not shared
// is the component: nothing here derives a Wear component from a Material 3 one.

/** A free string, or a closed set when [allowed] is given. */
private fun wearString(
  name: String,
  allowed: List<String> = emptyList(),
  required: Boolean = false,
  notes: String? = null,
): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(name, JsonPrimitive("string"))
    .also {
      it.required = required
      it.allowedValues = allowed.map(::JsonPrimitive)
      notes?.let { note -> it.notes = note }
    }
    .build()

/** A number: a dp, a size, a spacing. A constant rather than a value a state drives. */
private fun wearNumber(name: String, notes: String? = null): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(name, JsonPrimitive("number"))
    .also { notes?.let { note -> it.notes = note } }
    .build()

/** A number a state variable can drive, which arrives as a wrapper object. */
/**
 * The action a click runs.
 *
 * An object or null rather than a string, which is the shape the mobile catalog declares for the
 * same property: it is a binding, not a name, and declaring it as a string here would make one
 * property mean two things across the two catalogs.
 */
private fun wearClickAction(): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(
      "onClickAction",
      JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))),
    )
    .build()

private fun wearBindableNumber(name: String, notes: String? = null): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(
      name,
      JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("object"))),
    )
    .also { it.notes = notes }
    .build()

/** A count, where a fractional value is meaningless. */
private fun wearInteger(name: String, notes: String? = null): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(name, JsonPrimitive("integer"))
    .also { notes?.let { note -> it.notes = note } }
    .build()

/** A boolean, or an object when a state binding is written into it. */
private fun wearBoolean(name: String, notes: String? = null): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(name, JsonPrimitive("boolean"))
    .also { notes?.let { note -> it.notes = note } }
    .build()

/** A boolean a state variable can drive, which arrives as a wrapper object. */
private fun wearBindableBoolean(name: String, notes: String? = null): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(
      name,
      JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("object"))),
    )
    .also { notes?.let { note -> it.notes = note } }
    .build()

/**
 * A colour, in either of the two shapes the colour token vocabulary allows.
 *
 * The note is the catalog's rather than a component's: it is the same string wherever a colour is
 * authorable, and it is the one an author reads to find out how to write one.
 */
private fun wearColor(name: String = "color"): PropertyCapabilityV1 =
  wearString(
    name,
    notes =
      "A colour, written as {\"type\":\"color\",\"value\":\"#RRGGBB\"} or as " +
        "{\"type\":\"colorToken\",\"value\":\"primary\"} naming one of " +
        "statusSemantics.colorTokens. A string wrapper, or a role the canvas does not draw, is " +
        "rejected rather than guessed at.",
  )

/** One content slot holding a single child, for the components whose API takes one lambda. */
private fun singleSlot(name: String, traits: List<String>, min: Int = 0): SlotCapabilityV1 =
  SlotCapabilityV1.Builder(
      name,
      SlotCardinalityV1.Builder()
        .also {
          it.min = min
          it.max = 1
        }
        .build(),
      true,
    )
    .also {
      it.acceptedRoles = emptyList()
      it.acceptedTraits = traits
    }
    .build()

/** A slot holding any number of children. */
private fun manySlot(
  name: String,
  traits: List<String>,
  roles: List<String> = emptyList(),
  min: Int = 0,
): SlotCapabilityV1 =
  SlotCapabilityV1.Builder(
      name,
      SlotCardinalityV1.Builder()
        .also {
          it.min = min
          it.max = null
        }
        .build(),
      true,
    )
    .also {
      it.acceptedRoles = roles
      it.acceptedTraits = traits
    }
    .build()

/**
 * The modifiers the canvas applies to a node that draws no surface of its own: text, a button, a
 * list.
 *
 * `Modifier` extensions rather than arguments of any composable, which is why they are a vocabulary
 * the canvas owns rather than a component's surface — and why `fillMaxSize` is not among them,
 * these being nodes that size themselves.
 */
private val WEAR_MODIFIERS =
  listOf(
    "size",
    "fillMaxWidth",
    "padding",
    "alpha",
    "offset",
    "rotate",
    "scale",
    "zIndex",
    "testTag",
    "width",
    "height",
    "widthIn",
    "heightIn",
    "aspectRatio",
    "align",
    "alignHorizontal",
    "alignVertical",
    "weight",
  )

/**
 * What a button that can sit in a `ButtonGroup` takes: its share of the group, and the few
 * modifiers that size or tag it. `weight` is `ButtonGroupScope.weight` there, and refused anywhere
 * without a row, a column or a group to share.
 */
private val WEAR_BUTTON_GROUP_CHILD_MODIFIERS =
  listOf("weight", "size", "width", "padding", "testTag")

/**
 * What a node that paints its own surface can take as well: the clip and the four painting
 * modifiers, which a `Text` or a list has nothing to apply them to.
 */
private val WEAR_SURFACE_MODIFIERS =
  listOf(
    "size",
    "fillMaxWidth",
    "padding",
    "clip",
    "alpha",
    "offset",
    "rotate",
    "scale",
    "zIndex",
    "testTag",
    "width",
    "height",
    "widthIn",
    "heightIn",
    "aspectRatio",
    "background",
    "border",
    "shadow",
    "wrapContentSize",
    "align",
    "alignHorizontal",
    "alignVertical",
    "weight",
  )

/** Where a node sits in a parent that gives it more room than it needs. */
private val WEAR_ALIGNMENTS =
  listOf(
    "topStart",
    "topCenter",
    "topEnd",
    "centerStart",
    "center",
    "centerEnd",
    "bottomStart",
    "bottomCenter",
    "bottomEnd",
  )

/** The fifteen type-scale roles both libraries publish, under the names they share. */
private val WEAR_TYPE_ROLES =
  listOf(
    "displayLarge",
    "displayMedium",
    "displaySmall",
    "headlineLarge",
    "headlineMedium",
    "headlineSmall",
    "titleLarge",
    "titleMedium",
    "titleSmall",
    "bodyLarge",
    "bodyMedium",
    "bodySmall",
    "labelLarge",
    "labelMedium",
    "labelSmall",
  )

/**
 * The label a header draws, and its truncation.
 *
 * Upstream's `ListHeader` and `ListSubHeader` take a content lambda rather than a string, so these
 * are the `Text` inside them — one vocabulary for both, which is why it is a function.
 */
private fun wearLabelProperties(): List<PropertyCapabilityV1> =
  listOf(
    wearString("text", required = true, notes = "The label."),
    wearInteger("maxLines", "How many lines the label may occupy before it truncates."),
    wearString(
      "overflow",
      allowed = listOf("clip", "ellipsis", "visible"),
      notes = "What a truncated label does at its edge.",
    ),
  )

/** `TransformingLazyColumn`'s authored parameters, minus the ones its state object carries. */
private fun wearTransformingLazyColumnProperties(): List<PropertyCapabilityV1> =
  listOf(
    wearNumber(
      "verticalSpacingDp",
      "`Arrangement.spacedBy` between items; 4dp is the Wear list default.",
    ),
    wearString(
      "transformation",
      allowed = listOf("spec", "none"),
      notes =
        "Whether each item carries `SurfaceTransformation(spec)` and `transformedHeight`. The " +
          "canvas draws both in a bounded frame and neither at the extent — see the wasm note — " +
          "and the generated Kotlin emits them either way.",
    ),
  )

/**
 * The Wear Material 3 components this catalog offers that have **no Material 3 counterpart at
 * all**.
 *
 * ## Why these exist
 *
 * `wear-m3/checkbox-button` was refused once. The rule it was refused under is worth quoting rather
 * than paraphrasing: *do not fabricate a component in the Wasm canvas to stand in for a library the
 * canvas cannot link*. That rule closed
 * [#395](https://github.com/yschimke/compose-preview-server/pull/395), which built
 * `CheckboxButton`, `SwitchButton` and `RadioButton` as hand-assembled Material 3 shapes at sizes
 * read off a screenshot — an impression of upstream with nothing in the build to check it against,
 * wrong silently in the one surface an author trusts.
 *
 * They are drawn by the real thing now, so the rule is kept rather than bent: nothing here is a
 * lookalike, and nothing here is a placeholder. `ee.schimke.wearcmp:*` is Wear Compose compiled for
 * Compose Multiplatform, which is what lets the canvas call the same `CheckboxButton`, `Slider` and
 * `DatePicker` the generated screen names. See `wearM3Catalog` for the three the canvas still draws
 * for itself, and why each is about the shape of the page rather than about the library.
 *
 * ## Why the name says "only"
 *
 * These are Wear's own: a labelled full-width row, a segmented slider, a three-column picker. They
 * have no Material 3 counterpart at all, which is why they are declared here rather than beside the
 * three that do — and the three that do are declared just the same way, because a shared name is
 * not a shared component.
 */
private fun wearOnlyComponents(
  canvasSupported: WasmCapabilityV1,
  noStructuredSvg: SvgCapabilityV1?,
  iconKeys: List<JsonElement>,
): List<ComponentCapabilityV1> {
  /**
   * The note every component in this group carries, with its own composable named.
   *
   * [drawnBy] is the one thing that differs between them, and it is a parameter because the
   * catalog's claim has to match the renderer's branch: an entry that says "Wear Compose itself"
   * about a component the canvas draws some other way is the same failure as a `supported` status
   * with no branch behind it, one layer up and harder to see.
   */
  fun note(
    composable: String,
    extra: String = "",
    drawnBy: String =
      "Wear Compose itself, through the Compose Multiplatform build of the library the canvas " +
        "links instead of the Android AAR",
  ) =
    "Wear Material 3's `$composable`." +
      (if (extra.isEmpty()) "" else " $extra") +
      " The canvas draws it with $drawnBy; the generated screen and the native render use " +
      "`androidx.wear.compose` itself."

  fun component(
    componentId: String,
    displayName: String,
    composable: String,
    role: String,
    traits: List<String>,
    properties: List<PropertyCapabilityV1> = emptyList(),
    slots: List<SlotCapabilityV1> = emptyList(),
    extra: String = "",
    drawnBy: String? = null,
    modifierCapabilities: List<String> = emptyList(),
  ) =
    ComponentCapabilityV1.Builder(
        componentId,
        displayName,
        role,
        canvasSupported
          .newBuilder()
          .also {
            it.notes =
              if (drawnBy == null) note(composable, extra) else note(composable, extra, drawnBy)
          }
          .build(),
      )
      .also {
        it.traits = traits
        it.slots = slots
        it.properties = properties
        // No modifier vocabulary by default, and that is a statement rather than an omission: a
        // Wear control is laid out by the list and the scaffold around it — a `CheckboxButton` is
        // a full-width row whose height upstream fixes. The exceptions pass one: an icon button in
        // a `ButtonGroup` is sized by its `weight`, which is how Jetcaster makes play the wider of
        // two, and `WearScreenCodeExporter` writes a node's authored chain.
        it.modifierCapabilities = modifierCapabilities
        it.code = null
        it.svg =
          noStructuredSvg
            ?.newBuilder()
            ?.also {
              it.notes =
                "No structured SVG from this catalog's own record: `$composable` has no " +
                  "per-component call site to walk — `WearScreenCodeExporter` writes the whole " +
                  "screen — so the lane has nothing to emit from. The canvas draws the component " +
                  "itself."
            }
            ?.build()
      }
      .build()

  /** `label` and `secondaryLabel`, which is the shape every Wear selection control shares. */
  fun labelled(secondary: Boolean = true) = buildList {
    add(
      PropertyCapabilityV1.Builder("label", JsonPrimitive("string"))
        .also {
          it.required = true
          it.notes =
            "The row's primary label. Wear's selection controls are labelled rows, not bare boxes."
        }
        .build()
    )
    if (secondary) {
      add(
        PropertyCapabilityV1.Builder("secondaryLabel", JsonPrimitive("string"))
          .also {
            it.notes =
              "The second line, where there is one. Empty emits no `secondaryLabel` argument."
          }
          .build()
      )
    }
  }

  /**
   * A checked/selected flag, drivable from a state variable exactly as `m3/checkbox.checked` is.
   */
  fun flag(name: String, notes: String) =
    PropertyCapabilityV1.Builder(
        name,
        JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("object"))),
      )
      .also { it.notes = notes }
      .build()

  fun enum(name: String, values: List<String>, notes: String, required: Boolean = false) =
    PropertyCapabilityV1.Builder(name, JsonPrimitive("string"))
      .also {
        it.required = required
        it.allowedValues = values.map(::JsonPrimitive)
        it.notes = notes
      }
      .build()

  fun number(name: String, notes: String) =
    PropertyCapabilityV1.Builder(
        name,
        JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("object"))),
      )
      .also { it.notes = notes }
      .build()

  fun text(name: String, notes: String, required: Boolean = false) =
    PropertyCapabilityV1.Builder(name, JsonPrimitive("string"))
      .also {
        it.required = required
        it.notes = notes
      }
      .build()

  val listItem = listOf("ListItem", "WearListContent")

  return listOf(
    component(
      componentId = "wear-m3/icon",
      displayName = "Icon",
      composable = "Icon",
      role = "Leaf",
      traits = listOf("Adornment"),
      properties =
        listOf(
          PropertyCapabilityV1.Builder("iconKey", JsonPrimitive("string"))
            .also {
              it.required = true
              it.allowedValues = iconKeys
              it.notes =
                "A Material icon key, resolved to `Icons.…` by the same table `m3/icon` uses. The " +
                  "vectors are `androidx.compose.material.icons`, which both platforms share, so " +
                  "this key means the same thing on a watch as on a phone."
            }
            .build(),
          number("sizeDp", "The icon's box. Wear's own default is 24dp inside a button."),
          text(
            "contentDescription",
            "What a screen reader says for the icon. Leave it empty beside a label, which already " +
              "names the action; set it when the icon is the whole button — an icon button's only " +
              "accessible name is this.",
          ),
        ),
      // The one component in this group the canvas does NOT draw with Wear Compose, and the
      // difference is stated rather than left to be discovered. `BuilderIcon` is the canvas's own
      // icon drawer, because an icon is a tinted vector at a size on both platforms — Wear
      // publishes no shape of its own here — and `BuilderIcon` is what owns this build's key table,
      // its tint resolution and the structured-path export the SVG lane needs. Drawing it twice
      // would be two answers to one question.
      drawnBy =
        "the canvas's own icon drawer, not Wear's `Icon` — an icon is a tinted vector on both " +
          "platforms, and that drawer owns this build's key table, tint resolution and " +
          "structured-path export",
    ),
    component(
      componentId = "wear-m3/icon-button",
      displayName = "Icon button",
      composable = "IconButton",
      role = "Container",
      traits = listOf("Action", "ListItem"),
      slots = listOf(singleSlot("content", listOf("Adornment"), min = 1)),
      properties =
        listOf(
          enum(
            "variant",
            listOf("filled", "filled-tonal", "filled-variant", "outlined", "standard"),
            "Which of the five `IconButton` overloads is written: `FilledIconButton`, " +
              "`FilledTonalIconButton`, `FilledVariantIconButton`, `OutlinedIconButton` or plain " +
              "`IconButton`. A variant selects the composable rather than tinting one, the way " +
              "`m3/button`'s style does.",
          ),
          // Recolours the variant's own palette rather than replacing the variant: upstream
          // passes `IconButtonDefaults.filledIconButtonColors(containerColor = …)` to a
          // `FilledIconButton`, so the variant still decides the shape and the disabled colours.
          wearColor("containerColor"),
          wearColor("contentColor"),
        ),
      modifierCapabilities = WEAR_BUTTON_GROUP_CHILD_MODIFIERS,
    ),
    component(
      componentId = "wear-m3/text-button",
      displayName = "Text button",
      composable = "TextButton",
      role = "Container",
      traits = listOf("Action", "ListItem"),
      slots = listOf(singleSlot("content", listOf("AnyContent"), min = 1)),
      properties =
        listOf(
          enum(
            "variant",
            listOf("filled", "filled-tonal", "filled-variant", "outlined", "standard"),
            "As `wear-m3/icon-button`'s: the variant names the composable — `FilledTextButton` " +
              "and the rest — rather than recolouring one.",
          )
        ),
      modifierCapabilities = WEAR_BUTTON_GROUP_CHILD_MODIFIERS,
    ),
    component(
      componentId = "wear-m3/list-sub-header",
      displayName = "List sub-header",
      composable = "ListSubHeader",
      role = "Leaf",
      traits = listItem,
      // The same three the header declares, and for the same reason: upstream takes a content
      // lambda, so the label and its truncation are the `Text` inside it. Offering only `text` here
      // made the canvas's and the exporter's reads unreachable — the service refuses a property the
      // catalog does not declare.
      properties = wearLabelProperties(),
      extra =
        "The second level of list heading, under `wear-m3/list-header`: smaller, start-aligned, " +
          "and the one used to divide a long list into named runs.",
    ),
    component(
      componentId = "wear-m3/checkbox-button",
      displayName = "Checkbox button",
      composable = "CheckboxButton",
      role = "Leaf",
      traits = listItem + "Selection",
      properties =
        labelled() + flag("checked", "Whether the box is ticked. Bindable to a state variable."),
      extra =
        "A full-width labelled row with the checkbox at its end — not the mobile 20dp square, " +
          "which is why Material 3's `Checkbox` could never stand in for it.",
    ),
    component(
      componentId = "wear-m3/switch-button",
      displayName = "Switch button",
      composable = "SwitchButton",
      role = "Leaf",
      traits = listItem + "Selection",
      properties =
        labelled() + flag("checked", "Whether the switch is on. Bindable to a state variable."),
      extra = "The same labelled row as `wear-m3/checkbox-button`, with a switch as its control.",
    ),
    component(
      componentId = "wear-m3/radio-button",
      displayName = "Radio button",
      composable = "RadioButton",
      role = "Leaf",
      traits = listItem + "Selection",
      properties =
        labelled() + flag("selected", "Whether this row is the chosen one of its group."),
      extra = "The same labelled row again, with a radio control and single-choice semantics.",
    ),
    component(
      componentId = "wear-m3/slider",
      displayName = "Slider",
      composable = "Slider",
      role = "Leaf",
      traits = listItem,
      properties =
        listOf(
          number("value", "The current value, between `valueFrom` and `valueTo`."),
          number("valueFrom", "The low end of the range. 0 when absent."),
          number("valueTo", "The high end of the range. 1 when absent."),
          number("steps", "How many discrete stops sit between the ends. 0 is continuous."),
          enum(
            "segmented",
            listOf("segmented", "continuous"),
            "Whether the track is drawn as separated segments, which is what Wear's stepped " +
              "slider looks like.",
          ),
        ),
      extra =
        "Wear's slider is a row with a decrement and an increment button around the track, not a " +
          "bare thumb on a line.",
    ),
    component(
      componentId = "wear-m3/stepper",
      displayName = "Stepper",
      composable = "Stepper",
      role = "Container",
      traits = listOf("ScreenContent"),
      slots = listOf(singleSlot("content", listOf("AnyContent"), min = 1)),
      properties =
        listOf(
          number("value", "The current value."),
          number("valueFrom", "The low end of the range. 0 when absent."),
          number("valueTo", "The high end of the range. 1 when absent."),
          number("steps", "How many discrete stops sit between the ends. 0 is continuous."),
        ),
      extra =
        "A full-screen control: increment and decrement buttons at the top and bottom of the " +
          "round display with the current value between them. It is not a list row, which is why " +
          "it carries `ScreenContent` rather than `ListItem`.",
    ),
    component(
      componentId = "wear-m3/progress-indicator",
      displayName = "Progress indicator",
      composable = "CircularProgressIndicator",
      role = "Leaf",
      traits = listItem + "ScreenContent",
      properties =
        listOf(
          enum(
            "variant",
            listOf("circular", "segmented-circular", "linear", "arc"),
            "Which indicator is written: `CircularProgressIndicator`, " +
              "`SegmentedCircularProgressIndicator`, `LinearProgressIndicator` or " +
              "`ArcProgressIndicator`. The circular ones ring the whole display; the linear one is " +
              "a list row.",
          ),
          number("progress", "0..1. Absent is the indeterminate form, which takes no progress."),
          number("segments", "How many segments the segmented circular form is divided into."),
        ),
      extra = "Wear publishes four, and which one you get is the `variant`.",
    ),
    component(
      componentId = "wear-m3/edge-button",
      displayName = "Edge button",
      composable = "EdgeButton",
      role = "Container",
      traits = listOf("Action"),
      slots = listOf(singleSlot("content", listOf("AnyContent"), min = 1)),
      properties =
        listOf(
          enum(
            "size",
            listOf("extra-small", "small", "medium", "large"),
            "`EdgeButtonSize`. The button's shape comes from the screen's bottom curve, so its " +
              "size is chosen from upstream's four rather than set in dp.",
          )
        ),
      extra =
        "The button that hugs the bottom of a round screen. It belongs in the scaffold's " +
          "`edgeButton` slot, where `ScreenScaffold` reveals it from the scroll state; it is a " +
          "component of its own now rather than a `wear-m3/button` placed there, because " +
          "`EdgeButton` is a different composable with a different shape and a size enum.",
    ),
    component(
      componentId = "wear-m3/button-group",
      displayName = "Button group",
      composable = "ButtonGroup",
      role = "Container",
      traits = listItem,
      slots = listOf(manySlot("children", listOf("Action"))),
      extra =
        "A row of buttons that share the width and grow the one being pressed. Its children are " +
          "buttons; anything else has no `ButtonGroupScope` to be laid out in.",
    ),
    component(
      componentId = "wear-m3/alert-dialog",
      displayName = "Alert dialog",
      composable = "AlertDialog",
      role = "Container",
      traits = listOf("Overlay"),
      slots =
        listOf(
          manySlot("content", listOf("AnyContent")),
          singleSlot("confirmButton", listOf("Action")),
          singleSlot("dismissButton", listOf("Action")),
        ),
      properties =
        listOf(
          text("title", "The dialog's title.", required = true),
          text("text", "The supporting line under the title. Empty emits no `text` argument."),
          flag(
            "visible",
            "Whether the dialog is showing. A dialog is a screen state rather than a place in the " +
              "layout, so this is what the generated `AlertDialog(visible = …)` reads.",
          ),
        ),
      extra =
        "Wear's own, which is a full-screen scrolling dialog with its buttons on the bottom curve " +
          "— not a card floating over a scrim.",
    ),
    component(
      componentId = "wear-m3/confirmation-dialog",
      displayName = "Confirmation dialog",
      composable = "ConfirmationDialog",
      role = "Leaf",
      traits = listOf("Overlay"),
      properties =
        listOf(
          text("text", "The line shown under the icon.", required = true),
          enum(
            "variant",
            listOf("generic", "success", "failure"),
            "`ConfirmationDialog`, `SuccessConfirmationDialog` or `FailureConfirmationDialog`. " +
              "The two named ones bring their own icon and curved text; the generic one takes the " +
              "text alone.",
          ),
          flag("visible", "Whether it is showing, as for `wear-m3/alert-dialog`."),
        ),
      extra =
        "The brief full-screen acknowledgement Wear shows after an action and then dismisses.",
    ),
    component(
      componentId = "wear-m3/open-on-phone-dialog",
      displayName = "Open on phone dialog",
      composable = "OpenOnPhoneDialog",
      role = "Leaf",
      traits = listOf("Overlay"),
      properties =
        listOf(
          text("text", "The curved line under the animation. Empty takes upstream's own."),
          flag("visible", "Whether it is showing, as for `wear-m3/alert-dialog`."),
        ),
      extra =
        "The one Wear surface with no mobile analogue at all: it tells the wearer the rest of this " +
          "journey happens on their phone.",
    ),
    component(
      componentId = "wear-m3/date-picker",
      displayName = "Date picker",
      composable = "DatePicker",
      role = "Leaf",
      traits = listOf("ScreenContent"),
      properties =
        listOf(
          text("initialDate", "ISO-8601 `yyyy-MM-dd`. Empty picks upstream's own initial date."),
          enum(
            "type",
            listOf("year-month-day", "day-month-year", "month-day-year"),
            "`DatePickerType`, which is field order rather than formatting.",
          ),
        ),
      extra = "A full-screen three-column picker, driven by the rotary side button.",
    ),
    component(
      componentId = "wear-m3/time-picker",
      displayName = "Time picker",
      composable = "TimePicker",
      role = "Leaf",
      traits = listOf("ScreenContent"),
      properties =
        listOf(
          text("initialTime", "ISO-8601 `HH:mm[:ss]`. Empty picks upstream's own initial time."),
          enum(
            "type",
            listOf("hours-minutes-seconds", "hours-minutes-am-pm", "hours-minutes-24h"),
            "`TimePickerType`, which decides both the columns and the clock.",
          ),
        ),
      extra = "The time counterpart of `wear-m3/date-picker`, and the same full-screen shape.",
    ),
  )
}

/**
 * `wear-m3`: the Wear Compose Material 3 screen, as an authoring surface.
 *
 * ## The components are Wear's own, through the port
 *
 * `androidx.wear.compose:compose-material3` is an Android AAR and the builder's canvas is Compose
 * Multiplatform for Wasm, which cannot link an AAR — but the canvas never had to link *that*
 * artifact. `ee.schimke.wearcmp:*` is the same library's source compiled for Compose Multiplatform
 * with `jvm` and `wasmJs` variants, which are exactly this module's targets, so every component
 * below is drawn by Wear Compose rather than by an impression of it. The generated screen and the
 * native render use the real AAR; the canvas is the lane that trades a port for a browser.
 *
 * Three things the canvas still draws for itself, each for a reason of its own rather than a
 * missing dependency, and each saying so in its `wasm` note: the screen scaffold (the extent has no
 * viewport), the unrolled list (a `ScrollMode.LONG` capture turns the row transformation off), and
 * `wear-m3/text` (the canvas's theme still carries the mobile type scale).
 *
 * ## The two components that are this catalog's whole point
 *
 * `wear-m3/screen-scaffold` and `wear-m3/transforming-lazy-column`. A Wear screen is a
 * `ScreenScaffold` wrapping a `TransformingLazyColumn` in something over ninety per cent of the
 * Wear Material 3 surface area.
 *
 * The canvas draws the scaffold as a **stadium** — the screen's width, the content's height, round
 * caps — which is the Wear long-screenshot convention rather than a device. That is a deliberate
 * choice about what an author is building: the whole scrolling extent at once, not a 192dp keyhole
 * onto it. What it costs is stated in the wasm notes and again in
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md`: straight sides overstate the width a row actually gets
 * near the curve, and the row transformation is not drawn at the extent — the frame pane beside it
 * is where the real lazy layout and its transformation are.
 *
 * ## What used to be here
 *
 * The Wear content ids, and a note on every one of them reading "drawn as its mobile counterpart"
 * or "the canvas draws a named placeholder". Both were true while `wear-m3` was a re-creation of
 * the library out of Material 3 shapes, and both stopped being true when the port landed — which is
 * exactly the kind of claim that outlives its reason, because nothing fails when prose goes stale.
 */
private fun wearM3Catalog(base: CatalogCapabilityV1): CatalogCapabilityV1 {
  val components = base.components.associateBy { it.componentId }
  val box = components.getValue("layout/box")
  // Foundation's lazy column, read for its MODIFIER vocabulary alone — see the Wear list's
  // declaration. `layout/lazy-column` is `androidx.compose.foundation`, which both platforms
  // share, so this is the one entry here a Wear component legitimately reads anything from.
  val lazyColumn = components.getValue("layout/lazy-column")
  // The shared capability blocks, read off the packaged catalog once because they are statements
  // about a LANE rather than about a component: `canvasSupported` is what the canvas says about a
  // component it draws, `noStructuredSvg` what the SVG lane says about one with no call site to
  // walk, and `recordedTextSvg` what the same-runtime recorder says about text. None of the three
  // is a claim about Material 3, which is why none of them is a component being copied.
  val canvasSupported =
    components
      .getValue("m3/text")
      .wasm
      .newBuilder()
      .also {
        it.adapterStatus = WasmAdapterStatusV1.SUPPORTED
        it.platformSupported = JsonPrimitive(true)
      }
      .build()
  val noStructuredSvg = components.getValue("remote-compose/document").svg
  val recordedTextSvg = components.getValue("m3/text").svg
  // What the SVG lane says about a node it can record structurally. The recorder walks the composed
  // scene, so this is a statement about the recorder rather than about any component — which is why
  // the same block answers for a Wear card and a Material 3 one.
  val recordedSceneSvg = components.getValue("m3/card").svg
  val boxSlot = box.slots.single()

  val contentSlot =
    boxSlot
      .newBuilder()
      .also {
        it.name = "content"
        it.cardinality =
          boxSlot.cardinality
            .newBuilder()
            .also {
              it.min = 0
              it.max = 1
            }
            .build()
      }
      .build()
  // `ScreenScaffold(edgeButton = …)` takes one composable, and upstream's own samples put an
  // `EdgeButton` in it and nothing else. Narrowed to `Action` so a Text cannot be dropped into a
  // slot whose whole job is to hug the bottom curve with a button in it.
  val edgeButtonSlot =
    contentSlot
      .newBuilder()
      .also {
        it.name = "edgeButton"
        it.acceptedRoles = emptyList()
        it.acceptedTraits = listOf("Action")
      }
      .build()
  // Wear's dialogs are a screen *state*, not a place in the layout: each takes a `visible` flag and
  // draws over the whole display when it is set, and upstream's own samples put them beside the
  // scaffold in the same `AppScaffold`. A slot in `content` would have made them list rows, which
  // is a full-screen dialog inside a scrolling item. Unbounded, because a screen can have more than
  // one dialog it shows at different moments — only one is ever `visible`.
  val overlaySlot =
    contentSlot
      .newBuilder()
      .also {
        it.name = "overlays"
        it.cardinality =
          contentSlot.cardinality
            .newBuilder()
            .also {
              it.min = 0
              it.max = null
            }
            .build()
        it.acceptedRoles = emptyList()
        it.acceptedTraits = listOf("Overlay")
      }
      .build()

  val scaffold =
    box
      .newBuilder()
      .also {
        it.componentId = "wear-m3/screen-scaffold"
        it.displayName = "Wear screen · ScreenScaffold"
        it.role = "Scaffold"
        it.traits = listOf("ScreenContent", "WearScreenHost")
        it.slots = listOf(contentSlot, edgeButtonSlot, overlaySlot)
        it.properties = wearScreenScaffoldProperties()
        it.modifierCapabilities = emptyList()
        it.wasm =
          canvasSupported
            .newBuilder()
            .also {
              // The drawing that frames this screen root, named rather than left to the renderer to
              // recognise by id. `frame/round-screen` is an adapter this build ships; a catalog
              // whose screen root is called something else names the same adapter and gets the same
              // frame.
              it.canvas = "frame/round-screen"
              it.notes =
                "Drawn as a Wear long-screenshot stadium at the document frame's width, with the " +
                  "content padding the real `ScreenScaffold` computes for that screen size, the " +
                  "clock where `AppScaffold` puts it, and a bezel scroll indicator. It is not Wear " +
                  "Compose's own scaffold, and that is about the shape of the page rather than " +
                  "about the library: this pane draws the design's EXTENT — the long-screenshot " +
                  "form, with no viewport for a scroll state to be live in — and `ScreenScaffold` " +
                  "is a viewport. What it does claim is the geometry: wear-m3-catalog's stitched " +
                  "`ScrollMode.LONG` capture of the same list matches this to within a dp."
            }
            .build()
        it.code = null
        it.svg =
          noStructuredSvg
            ?.newBuilder()
            ?.also {
              it.notes = "The stadium screen frame has not been through structured SVG parity."
            }
            ?.build()
      }
      .build()

  // Wear's `ListHeader`, and it exists because the round trip found it: it is a 48dp item at every
  // screen size — measured — and the template used to fake that with a padded `m3/text`. The canvas
  // matched; the *generated screen* did not, because a padded `Text` is not a `ListHeader` and the
  // generator has no business emitting one as the other. Fifteen dp of header is the difference
  // between "the two pictures agree" and "the two pictures agree except at the top".
  //
  // Declared rather than filtered out of `m3/text`, and the label's properties are the header's own
  // vocabulary: upstream takes a content lambda, so `maxLines` and `overflow` are the `Text` inside
  // it.
  val listHeader =
    ComponentCapabilityV1.Builder(
        "wear-m3/list-header",
        "List header",
        "Leaf",
        canvasSupported
          .newBuilder()
          .also {
            it.notes =
              "Wear Material 3's `ListHeader`: a 48dp item whose label sits low in it, drawn on " +
                "the screen's own background rather than on a surface. The height is upstream's " +
                "and is what makes a generated screen's first row land where the canvas puts it."
          }
          .build(),
      )
      .also {
        it.traits = listOf("TextContent", "RemoteAuthorable", "ListItem")
        it.properties = wearLabelProperties()
        it.modifierCapabilities = emptyList()
        it.code = null
        it.svg = recordedTextSvg
      }
      .build()

  // Wear's own lazy list, from `androidx.wear.compose.foundation.lazy`. It shares the *shape* of a
  // lazy column with foundation's — ordered, vertical, scrollable, repeated — and none of its
  // implementation, which is why the traits are stated here rather than inherited from
  // `layout/lazy-column`: what the two have in common is Compose's vocabulary, not a component.
  val transformingLazyColumn =
    ComponentCapabilityV1.Builder(
        "wear-m3/transforming-lazy-column",
        "Transforming lazy column",
        "Container",
        canvasSupported
          .newBuilder()
          .also {
            it.notes =
              "Drawn by Wear Compose's own `TransformingLazyColumn` in a bounded frame, where " +
                "it scales and fades each row through the library's `transformedHeight` and its " +
                "own `SurfaceTransformation`. At the extent it is a plain Column at the list's " +
                "own spacing, which is what a stitched `ScrollMode.LONG` capture of the real " +
                "one is: `LONG` turns the row transformation off in order to stitch, so every " +
                "row on the reference is full content width at every position and the Column " +
                "reproduces it exactly."
          }
          .build(),
      )
      .also {
        it.traits =
          listOf(
            "OrderedContent",
            "VerticalContent",
            "ScrollableContent",
            "RepeatedContent",
            "WearListContent",
          )
        it.slots = listOf(manySlot("items", listOf("AnyContent"), listOf("Container", "Leaf")))
        // The modifier vocabulary, and the one thing here that IS taken from another entry: these
        // are `Modifier` extensions — `size`, `padding`, `alpha`, `weight` — which every container
        // accepts, so they are the canvas's vocabulary rather than this component's arguments. The
        // entry they are read from is foundation's, which is what both platforms share.
        it.modifierCapabilities = lazyColumn.modifierCapabilities
        it.properties = wearTransformingLazyColumnProperties()
        it.wasm =
          canvasSupported
            .newBuilder()
            .also {
              it.notes =
                "Drawn by Wear Compose's own `TransformingLazyColumn` in a bounded frame, where " +
                  "it scales and fades each row through the library's `transformedHeight` and its " +
                  "own `SurfaceTransformation`. At the extent it is a plain Column at the list's " +
                  "own spacing, which is what a stitched `ScrollMode.LONG` capture of the real " +
                  "one is: `LONG` turns the row transformation off in order to stitch, so every " +
                  "row on the reference is full content width at every position and the Column " +
                  "reproduces it exactly."
            }
            .build()
        it.code = null
        it.svg =
          noStructuredSvg
            ?.newBuilder()
            ?.also {
              it.notes =
                "No structured SVG from this catalog's own record: `TransformingLazyColumn` has " +
                  "no per-component call site to walk — `WearScreenCodeExporter` writes the whole " +
                  "screen — so the lane has nothing to emit from. The extent's untransformed rows " +
                  "could not be claimed as the list's own layout in any case."
            }
            ?.build()
      }
      .build()

  /**
   * Wear's three content components that have a Material 3 namesake: `Text`, `TitleCard` and
   * `Button`.
   *
   * ## Why they are declared here rather than derived from the mobile ones
   *
   * They share a NAME with a Material 3 component and nothing else. `wear-m3` and `m3` describe two
   * different libraries — different theme systems, different type scales, different sizes, and
   * **you do not use them together** — so a Wear card is not a recoloured Material 3 card, and
   * saying so by copying one and renaming it was a claim this catalog had no business making. What
   * the two do share is Compose's own vocabulary: a `style` is one of the same fifteen role names,
   * a `color` is written the same way, and `Modifier` extensions are `Modifier` extensions. That
   * vocabulary lives in the helpers above and every component states its own surface from it.
   *
   * The differences are the point, and they show up as differences: Wear publishes ONE card shape
   * where Material 3 publishes three, so there is no `shape` here to move; Wear's `Button` has no
   * `selected` and no `containerColor`; and `TitleCard`, `AppCard`, `OutlinedCard` and `Card` are
   * four composables rather than four styles of one, which is why `variant` selects a composable.
   */
  fun wearComponent(
    componentId: String,
    displayName: String,
    role: String,
    composable: String,
    traits: List<String>,
    properties: List<PropertyCapabilityV1>,
    slots: List<SlotCapabilityV1> = emptyList(),
    modifierCapabilities: List<String> = emptyList(),
    svg: SvgCapabilityV1? = null,
  ) =
    ComponentCapabilityV1.Builder(
        componentId,
        displayName,
        role,
        canvasSupported
          .newBuilder()
          .also {
            it.notes =
              "Wear Material 3's `$composable`. The canvas draws it with Wear Compose's own " +
                "`$composable`, out of the Compose Multiplatform build of the library it links; " +
                "the generated screen and the native render use `androidx.wear.compose` itself."
          }
          .build(),
      )
      .also {
        it.traits = traits
        it.slots = slots
        it.properties = properties
        it.modifierCapabilities = modifierCapabilities
        it.code = null
        it.svg = svg
      }
      .build()

  val wearText =
    wearComponent(
        componentId = "wear-m3/text",
        displayName = "Text",
        role = "Leaf",
        composable = "Text",
        traits = listOf("TextContent", "RemoteAuthorable"),
        modifierCapabilities = WEAR_MODIFIERS,
        properties =
          listOf(
            wearString("text", required = true),
            wearString(
              "style",
              allowed = WEAR_TYPE_ROLES,
              notes = "The role the text is set in, resolved against Wear's own type scale.",
            ),
            wearString(
              "fontWeight",
              allowed = listOf("normal", "medium", "semiBold", "bold"),
              notes = "Overrides the role's weight.",
            ),
            wearString(
              "fontStyle",
              allowed = listOf("normal", "italic"),
              notes = "Overrides the role's style.",
            ),
            wearColor(),
            wearNumber("fontSizeSp", "Overrides the role's size."),
            wearNumber("lineHeightSp", "Overrides the role's line height."),
            wearNumber("letterSpacingSp", "Overrides the role's tracking."),
            wearInteger("minLines", "The smallest height the text occupies, in lines."),
            wearInteger("maxLines", "How many lines the text may occupy before it truncates."),
            wearBoolean("softWrap", "Whether the text breaks at soft line breaks."),
            wearString(
              "overflow",
              allowed = listOf("clip", "ellipsis", "visible"),
              notes = "What a truncated text does at its edge.",
            ),
            wearString(
              "textAlign",
              allowed = listOf("start", "center", "end", "justify"),
              notes = "How the lines are aligned within the text's own width.",
            ),
            wearString(
              "textDecoration",
              allowed = listOf("none", "underline", "lineThrough"),
              notes = "A decoration painted under, or through, the glyphs.",
            ),
            wearString(
              "alignment",
              allowed = WEAR_ALIGNMENTS,
              notes = "Where the text sits in a parent that gives it more room than it needs.",
            ),
            wearNumber("weight", "The share of a parent's remaining space the text takes."),
          ),
      )
      .let { text ->
        // The one thing a text node adds to the shared block: what the recorder does with text.
        text.newBuilder().also { it.svg = recordedTextSvg }.build()
      }

  val wearCard =
    wearComponent(
      componentId = "wear-m3/card",
      displayName = "Card",
      role = "Container",
      composable = "TitleCard",
      traits = listOf("GridItem", "CarouselItem", "ListItem", "OverlayContent"),
      modifierCapabilities = WEAR_SURFACE_MODIFIERS,
      slots =
        listOf(manySlot("content", listOf("AnyContent"), listOf("Container", "Leaf"), min = 1)),
      svg = recordedSceneSvg,
      properties =
        listOf(
          wearString(
            "variant",
            allowed = listOf("title", "app", "outlined", "plain"),
            notes =
              "Which card is written: `TitleCard`, `AppCard`, `OutlinedCard` or `Card`. Wear " +
                "publishes four composables with different content lambdas rather than four " +
                "styles of one, so this selects the composable.",
          ),
          wearString(
            "stableKey",
            notes =
              "The item's identity, written as the generated lazy list's `key`. An identity " +
                "rather than a look: two rows sharing one are one row to a lazy layout.",
          ),
          wearClickAction(),
        ),
    )

  val wearButton =
    wearComponent(
      componentId = "wear-m3/button",
      displayName = "Button",
      role = "Container",
      composable = "Button",
      traits = listOf("Action", "ToolbarItem"),
      modifierCapabilities = WEAR_MODIFIERS,
      slots =
        listOf(manySlot("content", listOf("AnyContent"), listOf("Leaf", "Container"), min = 1)),
      svg = recordedSceneSvg,
      properties =
        listOf(
          wearString(
            "variant",
            allowed = listOf("filled", "filled-tonal", "outlined", "child"),
            notes =
              "Which button is written: `Button`, `FilledTonalButton`, `OutlinedButton` or " +
                "`ChildButton`. There is no `fab` and no `elevated` — a watch publishes neither.",
          ),
          wearBoolean("enabled", "Whether the button responds to a press."),
          wearColor("containerColor"),
          wearColor("contentColor"),
          wearClickAction(),
        ),
    )

  val wearOnly =
    wearOnlyComponents(
      canvasSupported = canvasSupported,
      noStructuredSvg = noStructuredSvg,
      // The icon key table is `m3/icon`'s, taken from the catalog rather than restated: two lists
      // of icon names is two chances to disagree about which vector `genres` is.
      iconKeys =
        components.getValue("m3/icon").properties.single { it.name == "iconKey" }.allowedValues,
    )

  // Foundation only. Every one of these is `androidx.compose.foundation` or `androidx.compose.ui`,
  // the same declaration on both platforms, so sharing it says nothing about which Material library
  // a Wear screen is built from — which is exactly what sharing a Material component would claim.
  //
  // `m3/icon` left with the Material ones and has no Wear id yet: the icon key resolves to a vector
  // through a table this export module cannot reach, so `wear-m3/icon` would be a palette entry
  // that
  // refuses on export — which is what `m3/icon` already was here.
  // Four, and all four are genuinely shared: `Box`, `Column`, `Row` and `Image` are
  // `androidx.compose.foundation` / `androidx.compose.ui`, the same declarations on both platforms,
  // taken from the base catalog here rather than restated — which is what keeps one source for
  // them. Wear publishes no `Image` of its own; it does publish `Icon`, which is why `wear-m3/icon`
  // is a Wear component of its own.
  //
  // The three Remote Compose seams — `remote-compose/document`, `-inline` and `-custom` — were here
  // too, and were offered without being usable: the whole-screen Wear generator has no case for any
  // of them, so a design that placed one was refused at export. An entry that cannot be exported is
  // worse than a missing one, because it is only discovered at the end. Withdrawn rather than
  // fixed, because what a Remote Compose seam means inside a Wear SCREEN is a real question — they
  // are widget vocabulary and this generator writes plain Compose — and is tracked to come back
  // once it has an answer.
  val foundationIds = listOf("layout/box", "layout/column", "layout/row", "asset/image")
  val sharedFoundation =
    foundationIds.map(components::getValue).map { component ->
      // The note every shared foundation component carries, and it says the opposite of what the
      // Material components' notes used to say. A Material component was a stand-in — "drawn as
      // the Material 3 component of the same name" — because the two libraries publish different
      // ones and the catalog had picked the wrong library. A foundation component is the same
      // declaration on both platforms, so there is nothing to stand in for.
      component
        .newBuilder()
        .also {
          it.wasm = component.wasm.newBuilder().also { it.notes = WEAR_FOUNDATION_NOTE }.build()
        }
        .build()
    }

  return base
    .newBuilder()
    .also {
      // The one thing this catalog has to say about itself that is not a component.
      //
      // The Wasm canvas is where a `wear-m3` design is *authored* — you select a node on it, drag
      // it, watch the layout — and it is not where the design is *looked at*. The components on it
      // are Wear Compose's own, drawn through a Compose Multiplatform build of the library, but the
      // screen frame is a stand-in (the extent has no viewport) and the font is the port's. Saying
      // that here rather than leaving each surface to work it out is what stops the editor
      // offering a Preview mode whose claim is false and the server picking a daemon by guessing
      // from a catalog id.
      //
      // `statusSemantics` rather than a field of its own because `CatalogCapabilityV1` is published
      // from compose-preview-contracts and cannot grow one from here; this map is the catalog's own
      // open vocabulary and already carries `adapterStatus` and `svgStatus`. Read back by
      // `UiBuilderPreviewSurfaces.from`.
      it.statusSemantics =
        JsonObject(
          base.statusSemantics +
            // A round watch screen against Wear Compose: grouped apart from the phone screens in
            // the
            // chooser, and offered no mobile pack — Wear Material 3 and Material 3 are not used
            // together, which is the rule this whole catalog is written around.
            (CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY to JsonPrimitive("wear")) +
            // The frame, which used to exist only as branches in the renderer: `wear-m3`'s screen
            // root was special-cased by id, and the measured content padding was a constant there
            // while the catalog's own `ui-builder.policy.json` declared it — the same three pairs,
            // written by a test in that repository and by hand here. The catalog states it now and
            // the renderer reads it, which is what makes the frame a catalog fact rather than a
            // Wear fact. `wear-m3-differences.json` recorded the gap in three `frame.*` exemptions;
            // they are gone, because both sides state this block.
            ("frame" to
              buildJsonObject {
                put("adapter", JsonPrimitive("frame/round-screen"))
                put("seedDevice", JsonPrimitive("id:wearos_small_round"))
                putJsonObject("geometry") {
                  put(
                    "contentPadding",
                    // `ScreenScaffoldContentPaddingTest` in wear-m3-catalog composes the real
                    // `ScreenScaffold` at each round size and asserts its own policy file equals
                    // the
                    // measurement; these are that measurement.
                    JsonArray(
                      listOf(
                          Triple(192, 10, 20),
                          Triple(227, 12, 23),
                          Triple(240, 13, 24),
                        )
                        .map { (screen, horizontal, vertical) ->
                          JsonObject(
                            mapOf(
                              "screenDp" to JsonPrimitive(screen),
                              "horizontalDp" to JsonPrimitive(horizontal),
                              "verticalDp" to JsonPrimitive(vertical),
                            )
                          )
                        }
                    ),
                  )
                }
              }) +
            ("previewSurfaces" to
              buildJsonObject {
                putJsonObject("wasm") {
                  put("fidelity", JsonPrimitive("approximate"))
                  put(
                    "reason",
                    JsonPrimitive(
                      "The canvas draws this catalog's components with a Compose Multiplatform " +
                        "build of Wear Compose — the Android AAR has no browser variant — so each " +
                        "is the component itself rather than an impression of it. What is still " +
                        "the canvas's own: the screen frame (the extent has no viewport for a " +
                        "real scaffold), the unrolled list (a long screenshot turns the row " +
                        "transformation off), and the font, which is the port's vendored Roboto " +
                        "Flex rather than the platform's. Author on it; check a size on the " +
                        "Android preview, which compiles this design's own generated Kotlin " +
                        "against the real AAR."
                    ),
                  )
                }
                putJsonObject("native") {
                  put("fidelity", JsonPrimitive("authoritative"))
                  put("backend", JsonPrimitive("android"))
                  // The catalog's own sentence, and stated here rather than left to the reader to
                  // infer from `backend`: this is the lane that renders the design's own generated
                  // Kotlin against the real `androidx.wear.compose` AAR under Robolectric, which is
                  // the same lane that produces wear-m3-catalog's published stickers.
                  put(
                    "reason",
                    JsonPrimitive(
                      "The generated Kotlin compiled against this module's own classpath and " +
                        "rendered under Robolectric: the same lane that produces this catalog's " +
                        "stickers."
                    ),
                  )
                }
              }) +
            // The other thing a catalog says about itself that is not a component: how the
            // builder's
            // insert panel shelves it. Until a catalog could declare this, the shelves were a table
            // in `:ui-builder` keyed by m3-catalog's ids, so `wear-m3` fell back to
            // Scaffolds/Containers/Composables — a statement about what each component may *hold*
            // rather than about what any of them is for. Same mechanism, and the same reason, as
            // `previewSurfaces` above; read back by `ComponentMenu.from`.
            //
            // It REPLACES the base catalog's declaration rather than merging with it: that one is
            // keyed by `m3/…` ids this catalog does not have, so a merge would leave every Wear
            // component unshelved while carrying entries for thirty-nine components that are gone.
            // The key as a literal, like `previewSurfaces` above: `ComponentMenu` lives in
            // `:ui-builder`, which this module must never depend on —
            // `checkUiBuilderRuntimeBoundary`
            // enforces the arrow, and the editor is above the runtime, not beside it.
            ("componentMenu" to wearComponentMenu())
        )
      it.benchmark =
        base.benchmark
          .newBuilder()
          .also { benchmark ->
            benchmark.id = "wear-m3-screen-scaffold"
            benchmark.sourceRevision = "compose-ai-tools:samples/design-catalog-wear-m3"
            benchmark.catalogSystemId = CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID
            benchmark.catalogRevision = "wear-screen-scaffold-v1"
          }
          .build()
      it.components =
        listOf(scaffold, transformingLazyColumn, listHeader, wearText, wearCard, wearButton) +
          wearOnly +
          sharedFoundation
    }
    .build()
}

/** Immutable, renderer-neutral request for one exact saved document revision. */
public data class UiBuilderRenderRequest(
  val designId: String,
  val revision: Long,
  val documentHash: String,
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val localeTag: String,
  val fontScale: Float,
  val encodedDocument: String,
)

/** Narrow pixel/vector port implemented by the server beside its render-host dependency. */
public interface UiBuilderRenderPort : Closeable {
  public val supportsSvg: Boolean

  public fun renderPng(request: UiBuilderRenderRequest): ByteArray

  public fun renderSvg(request: UiBuilderRenderRequest): ByteArray
}

/** Combines the runtime-owned Compose projection with an injected renderer-neutral port. */
public class ProductionUiBuilderExportExecutor(
  private val renderer: UiBuilderRenderPort,
  // Required, not defaulted. It used to default to a projection this module owned, and that
  // default is what let three different things emit Compose for one document: the real generator
  // (`ScreenGenerator`, reached from `:server`, which is the only layer allowed to hold both the
  // component record and this port), the editor's own exporter, and the default here — which
  // shipped an `ALMOST_COMPILING_PROJECTION` warning on every export because it could not tell
  // whether its output compiled. `ServeRunner` has always passed the real one, so the default was
  // reachable only by wiring nobody does in production; a default nobody should take is worse than
  // an argument everybody must pass.
  private val compose: UiBuilderExportExecutor,
  /**
   * Where a design's uploaded asset bytes are. The daemon render sees one string — the projected
   * document — so the bytes an `asset/image` names are inlined into that string here, from this
   * store, as an embedded source. Null leaves every uploaded binding as it is, and the renderer
   * draws its placeholder for it.
   */
  private val assets: UiBuilderAssetStore? = null,
) : UiBuilderExportExecutor, Closeable {
  public val capabilities: ExportCapabilitiesV1 =
    ExportCapabilitiesV1.Builder()
      .also {
        it.composeCode = true
        it.svg = renderer.supportsSvg
        it.png = true
      }
      .build()

  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 =
    when (request.format) {
      ExportFormatV1.COMPOSE -> compose.export(request)
      ExportFormatV1.PNG -> request.binaryArtifact(renderer.renderPng(request.toRenderRequest()))
      ExportFormatV1.SVG -> request.svgArtifact(renderer.renderSvg(request.toRenderRequest()))
      // Unreachable through the service: [capabilities] leaves `bundle` at its false default, and
      // PersistentUiBuilderService refuses any format the pinned catalog does not advertise before
      // an executor is reached. Thrown rather than folded into an `else`, so that implementing
      // yschimke/compose-preview-server#528 starts from a compile error here instead of silently
      // handing back Compose source for a caller that asked for a bundle. The service already
      // wraps this call, so the throw surfaces as an export error, not a crash.
      ExportFormatV1.BUNDLE ->
        throw UnsupportedOperationException(
          "bundle export is not implemented; ExportCapabilitiesV1.bundle is false for this executor"
        )
      // The same arrangement for the two formats contracts 2.17.0 added: [capabilities] leaves
      // `remoteJson` and `remoteDocument` at their false defaults, so the service refuses these
      // before an executor is reached, and implementing either starts from a compile error here.
      ExportFormatV1.JSON ->
        throw UnsupportedOperationException(
          "remote JSON export is not implemented; ExportCapabilitiesV1.remoteJson is false for " +
            "this executor"
        )
      ExportFormatV1.RC ->
        throw UnsupportedOperationException(
          "remote document export is not implemented; ExportCapabilitiesV1.remoteDocument is " +
            "false for this executor"
        )
    }

  override fun close(): Unit = renderer.close()

  private fun RevisionPinnedUiBuilderExport.toRenderRequest(): UiBuilderRenderRequest =
    UiBuilderRenderRequest(
      designId = designId,
      revision = revision,
      documentHash = documentHash,
      widthPx = (document.environment.widthDp * document.environment.density).toInt(),
      heightPx = (document.environment.heightDp * document.environment.density).toInt(),
      density = document.environment.density.toFloat(),
      localeTag = document.environment.locale,
      fontScale = document.environment.fontScale.toFloat(),
      encodedDocument =
        projectRendererDocument(document) { binding ->
          (binding.source as? UploadedAssetSourceV1)?.let { assets?.read(it.storageKey) }
        },
    )

  private fun RevisionPinnedUiBuilderExport.binaryArtifact(bytes: ByteArray): ExportArtifactV1 =
    ExportArtifactV1(
      format = ExportFormatV1.PNG,
      mediaType = "image/png",
      encoding = ExportEncodingV1.BASE64,
      content = Base64.getEncoder().encodeToString(bytes),
      contentDigest = bytes.sha256(),
      diagnostics = provenanceDiagnostics(),
    )

  private fun RevisionPinnedUiBuilderExport.svgArtifact(bytes: ByteArray): ExportArtifactV1 =
    ExportArtifactV1(
      format = ExportFormatV1.SVG,
      mediaType = "image/svg+xml; charset=utf-8",
      encoding = ExportEncodingV1.UTF8,
      content = bytes.toString(Charsets.UTF_8),
      contentDigest = bytes.sha256(),
      diagnostics = provenanceDiagnostics(),
    )

  private fun RevisionPinnedUiBuilderExport.provenanceDiagnostics(): List<ExportDiagnosticV1> =
    listOf(
      ExportDiagnosticV1(
        severity = DiagnosticSeverityV1.INFO,
        code = "REVISION_PINNED_DAEMON_RENDER",
        message =
          "Rendered design $designId revision $revision ($documentHash) through the packaged Compose UI-builder preview.",
      )
    )
}

/** Runtime-owned opaque preview bundle; materialization and rendering stay outside this module. */
public object PackagedUiBuilderRenderBundle {
  public const val RESOURCE: String =
    "/ee/schimke/composeai/uibuilder/renderer/ui-builder-renderer.bundle.png"
  public const val PREVIEW_ID: String =
    "ee.schimke.composeai.uibuilder.ProductionUiBuilderPreviewKt.ProductionUiBuilderPreview"
  public const val DOCUMENT_OVERRIDE_KEY: String = "uiBuilder.document.v1"

  /**
   * The bundle's own statement of the Java feature version its classes need, beside the bundle.
   *
   * Written by `:ui-builder-render-bundle` from the same catalog entry `:ui-builder`'s toolchain
   * reads. A caller comparing it against the JVM it is about to render on turns a wrong-JVM launch
   * from an `UnsupportedClassVersionError` inside a spawned daemon into one sentence naming both
   * versions (yschimke/compose-preview-server#344).
   */
  public const val MANIFEST_RESOURCE: String =
    "/ee/schimke/composeai/uibuilder/renderer/ui-builder-renderer.bundle.properties"

  /** The Java feature version [copyTo]'s bundle needs, read from [MANIFEST_RESOURCE]. */
  public fun requiredJavaFeatureVersion(): Int {
    val properties =
      checkNotNull(javaClass.getResourceAsStream(MANIFEST_RESOURCE)) {
          "packaged UI-builder renderer bundle manifest is missing"
        }
        .use { Properties().apply { load(it) } }
    val javaMin = properties.getProperty("javaMin")
    return checkNotNull(javaMin?.trim()?.toIntOrNull()) {
      "packaged UI-builder renderer bundle manifest has no numeric javaMin: $javaMin"
    }
  }

  public fun copyTo(root: Path): Path {
    val bytes =
      checkNotNull(javaClass.getResourceAsStream(RESOURCE)) {
          "packaged UI-builder renderer bundle is missing"
        }
        .use { it.readBytes() }
    val generation = root.toAbsolutePath().normalize().resolve(bytes.sha256())
    Files.createDirectories(generation)
    val bundle = generation.resolve("ui-builder-renderer.bundle.png")
    if (!Files.exists(bundle) || !Files.readAllBytes(bundle).contentEquals(bytes)) {
      val partial = Files.createTempFile(generation, ".ui-builder-renderer.", ".tmp")
      try {
        Files.write(partial, bytes)
        Files.move(
          partial,
          bundle,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } finally {
        Files.deleteIfExists(partial)
      }
    }
    return bundle
  }
}

/** Canonical, loss-checked protocol → renderer wire projection used by the named override. */
public fun projectRendererDocument(document: DesignDocumentV1): String =
  projectRendererDocument(document) { null }

/**
 * The same projection, carrying the design's `assets` with uploaded bytes inlined.
 *
 * [resolveAsset] answers the bytes behind a binding, or null. A binding it answers is rewritten as
 * an embedded source so the renderer — which sees only this string — can draw it; one it cannot
 * answer travels as it is and the renderer draws a placeholder in its place. An empty map is left
 * out altogether, so a design with no assets projects to the same bytes it always did.
 */
public fun projectRendererDocument(
  document: DesignDocumentV1,
  resolveAsset: (AssetBindingV1) -> ByteArray?,
): String {
  require(document.revision in 0..Int.MAX_VALUE.toLong()) {
    "renderer revision is outside the v1 Int range: ${document.revision}"
  }
  val source = json.encodeToJsonElement(document).jsonObject
  val projectedNodes =
    JsonObject(
      source
        .getValue("nodes")
        .jsonObject
        .entries
        .sortedBy { it.key }
        .associate { (id, value) ->
          val node = value.jsonObject
          id to
            JsonObject(
              linkedMapOf(
                "id" to node.getValue("id"),
                "componentId" to node.getValue("componentId"),
                "properties" to (node["properties"] ?: JsonObject(emptyMap())),
                "modifiers" to (node["modifiers"] ?: JsonArray(emptyList())),
                "slots" to (node["slots"] ?: JsonObject(emptyMap())),
                "eventBindings" to (node["eventBindings"] ?: JsonObject(emptyMap())),
              )
            )
        }
    )
  val projected =
    JsonObject(
      linkedMapOf(
        "schema" to source.getValue("schema"),
        "id" to source.getValue("id"),
        "title" to source.getValue("title"),
        "revision" to JsonPrimitive(document.revision.toInt()),
        "catalogPin" to source.getValue("catalogPin"),
        "environment" to source.getValue("environment"),
        "stateVariables" to (source["stateVariables"] ?: JsonObject(emptyMap())),
        "roots" to source.getValue("roots"),
        "nodes" to projectedNodes,
      ) + projectedAssets(document, resolveAsset)
    )
  return canonicalJson(projected)
}

private fun projectedAssets(
  document: DesignDocumentV1,
  resolveAsset: (AssetBindingV1) -> ByteArray?,
): Map<String, JsonElement> {
  if (document.assets.isEmpty()) return emptyMap()
  val inlined =
    document.assets.entries
      .sortedBy { it.key }
      .associate { (key, binding) ->
        val carried =
          when (binding.source) {
            is UploadedAssetSourceV1 ->
              resolveAsset(binding)?.let {
                binding.copy(source = EmbeddedAssetSourceV1(Base64.getEncoder().encodeToString(it)))
              } ?: binding
            is EmbeddedAssetSourceV1,
            is CatalogAssetSourceV1 -> binding
          }
        key to json.encodeToJsonElement(carried)
      }
  return mapOf("assets" to JsonObject(inlined))
}

private fun validateCatalog(catalog: CatalogCapabilityV1): CatalogCapabilityV1 {
  require(catalog.schema.isNotBlank()) { "catalog schema must not be blank" }
  require(catalog.benchmark.catalogSystemId == "m3-catalog") { "unexpected catalog system" }
  require(catalog.benchmark.catalogRevision == "candidate") { "unexpected catalog revision" }
  require(catalog.benchmark.nativeRuntimeId == "candidate") { "unexpected native runtime" }
  require(catalog.components.isNotEmpty()) { "catalog must contain components" }
  require(catalog.components.map { it.componentId }.distinct().size == catalog.components.size) {
    "catalog component ids must be unique"
  }
  catalog.components.forEach { component ->
    require(component.componentId.isNotBlank()) { "component id must not be blank" }
    require(component.slots.map { it.name }.distinct().size == component.slots.size) {
      "duplicate slot in ${component.componentId}"
    }
    require(component.properties.map { it.name }.distinct().size == component.properties.size) {
      "duplicate property in ${component.componentId}"
    }
    component.slots.forEach { slot ->
      require(slot.cardinality.min >= 0) { "negative slot minimum" }
      require(slot.cardinality.max == null || slot.cardinality.max!! >= slot.cardinality.min) {
        "invalid slot maximum"
      }
    }
  }
  return catalog
}

private fun issue(
  code: String,
  message: String,
  nodeId: String? = null,
  field: String? = null,
): UiBuilderCatalogIssue = UiBuilderCatalogIssue(code, message, nodeId, field)

private val json = Json {
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = false
}

private fun JsonObject.requiredString(name: String): String =
  requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) { "$name must be text" }

private fun JsonObject.optionalString(name: String): String? =
  this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

/**
 * The catalog capability of what a placement actually draws, or null when it cannot be resolved.
 *
 * A `design/component-instance` is a document construct with no capability of its own; what decides
 * which modifiers it may carry, and which slots will accept it, is the component it places. So this
 * walks from the placement to its declaration, to that declaration's body root — and, when the root
 * is itself a placement, keeps walking. Nested design components are an ordinary composition that
 * the renderer and the exporter both traverse.
 *
 * [seen] guards the walk. A declaration whose body root places the declaration itself is a cycle
 * the export gate reports as `GRAPH_CYCLE`; reaching it from here must return "cannot resolve"
 * rather than recurse until the stack gives out.
 */
private fun placedCapability(
  placement: JsonObject,
  document: DesignDocumentV1,
  encodedNodes: JsonObject,
  catalogComponents: Map<String, ComponentCapabilityV1>,
  seen: MutableSet<String> = mutableSetOf(),
): ComponentCapabilityV1? {
  val key =
    placement["component"]?.jsonObject?.get("componentKey")?.jsonPrimitive?.contentOrNull
      ?: return null
  if (!seen.add(key)) return null
  val root = document.components[key]?.root ?: return null
  val rootNode = encodedNodes[root]?.jsonObject ?: return null
  val rootComponentId = rootNode.requiredString("componentId")
  return if (rootComponentId == DESIGN_COMPONENT_INSTANCE_COMPONENT_ID)
    placedCapability(rootNode, document, encodedNodes, catalogComponents, seen)
  else catalogComponents[rootComponentId]
}

/**
 * The modifier rule, asked of one node against the capability that decides it.
 *
 * Shared so a placement is held to its body's modifiers by the same code that holds an ordinary
 * node to its component's — two spellings of this rule would eventually disagree, and the
 * disagreement would be discovered at export.
 */
private fun JsonObject.modifierIssue(
  nodeId: String,
  capability: ComponentCapabilityV1,
  declaredBy: String,
): UiBuilderCatalogIssue? {
  val allowed = capability.modifierCapabilities.toSet()
  arrayOrEmpty("modifiers").forEachIndexed { index, modifier ->
    val type = (modifier as? JsonObject)?.optionalString("type")
    if (type == null || type !in allowed) {
      return UiBuilderCatalogIssue(
        "UNKNOWN_MODIFIER",
        "modifier ${type ?: "at index $index"} is not declared by $declaredBy",
        nodeId,
        "modifiers[$index]",
      )
    }
  }
  return null
}

private fun JsonObject.objectOrEmpty(name: String): JsonObject =
  this[name] as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.arrayOrEmpty(name: String): JsonArray =
  this[name] as? JsonArray ?: JsonArray(emptyList())

private fun JsonElement.unwrapTypedValue(): JsonElement {
  val objectValue = this as? JsonObject ?: return this
  return objectValue["value"] ?: objectValue
}

private fun JsonElement.accepts(value: JsonElement): Boolean {
  val names =
    if (this is JsonArray) map { it.jsonPrimitive.content } else listOf(jsonPrimitive.content)
  return names.any { name ->
    when (name) {
      "null" -> value is JsonNull
      "string" -> value is JsonPrimitive && value.isString
      "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
      "number" -> value is JsonPrimitive && value.doubleOrNull != null
      "integer" -> value is JsonPrimitive && value.doubleOrNull?.rem(1.0) == 0.0
      "array" -> value is JsonArray
      "object" -> value is JsonObject
      else -> false
    }
  }
}

private fun JsonObject.number(name: String, default: Double = 0.0): String =
  (this[name] as? JsonPrimitive)?.doubleOrNull?.let { value ->
    if (value.rem(1.0) == 0.0) value.toLong().toString() else value.toString()
  } ?: default.toString()

private fun JsonElement?.kotlinLiteral(): String =
  when (this) {
    null,
    JsonNull -> "null"
    is JsonPrimitive -> if (isString) "\"${content.escapeKotlin()}\"" else content
    is JsonArray -> joinToString(prefix = "listOf(", postfix = ")") { it.kotlinLiteral() }
    is JsonObject ->
      entries
        .sortedBy { it.key }
        .joinToString(prefix = "mapOf(", postfix = ")") { (key, value) ->
          "\"${key.escapeKotlin()}\" to ${value.kotlinLiteral()}"
        }
  }

private fun String.identifier(): String {
  val words = split(Regex("[^A-Za-z0-9_]+")).filter(String::isNotEmpty)
  val candidate =
    words
      .mapIndexed { index, word ->
        if (index == 0) word.replaceFirstChar { it.lowercase() }
        else word.replaceFirstChar { it.uppercase() }
      }
      .joinToString("")
      .ifEmpty { "GeneratedDesign" }
  val safe = if (candidate.first().isDigit()) "_$candidate" else candidate
  return if (safe in KOTLIN_KEYWORDS) "`${safe}`" else safe
}

private fun String.escapeKotlin(): String =
  replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

private fun String.sha256(): String = toByteArray(Charsets.UTF_8).sha256()

private fun ByteArray.sha256(): String =
  MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private val KOTLIN_KEYWORDS =
  setOf(
    "as",
    "break",
    "class",
    "continue",
    "do",
    "else",
    "false",
    "for",
    "fun",
    "if",
    "in",
    "interface",
    "is",
    "null",
    "object",
    "package",
    "return",
    "super",
    "this",
    "throw",
    "true",
    "try",
    "typealias",
    "typeof",
    "val",
    "var",
    "when",
    "while",
  )
