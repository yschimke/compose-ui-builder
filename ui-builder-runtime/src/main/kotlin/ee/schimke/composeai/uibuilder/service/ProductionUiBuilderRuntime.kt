@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.RemoteDocumentExportSupport
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
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import ee.schimke.composeai.uibuilder.protocol.UploadedAssetSourceV1
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
  /**
   * The one catalog this build defines itself: Material 3, the builder's built-in default.
   *
   * Wear (`wear-m3`) and Remote Compose (`remote-m3`) used to be synthesised here too, derived from
   * this one in Kotlin. They are published by their own repository now — capability file and
   * renderer runtime — so an instance serves them as add-ons from [published] and this build no
   * longer carries a second, drifting definition of either.
   */
  private val synthesisedCatalogs = mapOf(DEFAULT_CATALOG_SYSTEM_ID to baseCatalog)

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
   * used to be the donors, and an id hop to them outlived them as a fallback for a catalog that
   * declared no platform. That went with them: an undeclared platform is mobile, as it is
   * everywhere else [platform] is read, and both published add-ons declare theirs.
   *
   * The id never wins over a platform the catalog DID declare. A catalog saying `platform: mobile`
   * under any id now gets the mobile vocabulary, which is the one its exporter can write, and which
   * every other reader of `statusSemantics.platform` already assumed.
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
    catalog.declaredPlatform ?: DEFAULT_PLATFORM

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
        LEGACY_SYNTHESISED_REFERENCES[systemId],
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

    /**
     * The references the retired synthesised `wear-m3` and `remote-m3` catalogs gave the designs
     * stored against them, still accepted while their published catalog is served.
     *
     * The definitions are gone, but a design saved before a host switched to the published file
     * still pins the old revision. Accepting it keeps that design openable — it resolves to the
     * catalog this host serves under the same id, and the document is still checked against it
     * component by component — rather than turning it into `CATALOG_UNAVAILABLE`.
     */
    internal val LEGACY_SYNTHESISED_REFERENCES: Map<String, CatalogReferenceV1> =
      mapOf(
          REMOTE_M3_CATALOG_SYSTEM_ID to "wear-widget-scaffolds-v1",
          WEAR_M3_CATALOG_SYSTEM_ID to "wear-screen-scaffold-v1",
        )
        .mapValues { (systemId, revision) ->
          CatalogReferenceV1(
            systemId = systemId,
            catalogRevision = revision,
            capabilityDigest = CURRENT_CAPABILITY_DIGEST,
            nativeRuntimeId = "candidate",
          )
        }

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
 * Written once for [composeFoundationCatalog]'s Wear curation, and equal to what the retired
 * synthesised `wear-m3` carried — `ComposeFoundationFaithfulnessTest` compares the donated
 * components field for field against that catalog's frozen fixture.
 *
 * It lives in THIS file rather than beside the foundation because it names a catalog, and
 * `.github/scripts/ui-builder-catalog-literals.sh` holds that name to the files that already
 * carried one; a new file may not add one. The contract's end state is for the note to move into
 * the published catalog's own data.
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
internal fun JsonObject.withMenuEntry(
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
