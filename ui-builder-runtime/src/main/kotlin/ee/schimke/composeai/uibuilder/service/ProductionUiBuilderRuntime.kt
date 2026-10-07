@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.FontSettings
import ee.schimke.composeai.uibuilder.export.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.STATE_SELECTION_CONTAINER
import ee.schimke.composeai.uibuilder.export.ThemeTextStyle
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
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
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import ee.schimke.composeai.uibuilder.protocol.UploadedAssetSourceV1
import java.io.Closeable
import java.lang.invoke.MethodHandles
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
     * Whether a given catalog can export Compose. Per catalog, so one catalog without a component
     * record (e.g. `remote-m3`) does not withdraw Compose export from all the others.
     */
    composeExportFor: (String) -> Boolean = { exportCapabilities.composeCode },
    /**
     * Component packs admitted by the host (another catalog's components, e.g. `confetti-mobile`),
     * merged into every enabled catalog of the same platform. Merged rather than served because a
     * pack has no scaffold or templates to pin to. The catalog records them under
     * `statusSemantics.componentPacks`.
     */
    packs: List<UiBuilderComponentPackSource> = emptyList(),
    /**
     * Catalogs composed from what a catalog repository published, keyed by system id
     * (`docs/design/UI_BUILDER_CATALOG_CONTRACT.md`). Preferred over the synthesised catalog of the
     * same id; a catalog with no synthesiser is served from here alone. Composed by `:server`,
     * which can read component records.
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
      A2UI_CATALOG_SYSTEM_ID to a2uiCatalog(baseCatalog),
    )

  /**
   * Where the `remote-compose/` seams come from: the packaged catalog, overridden by any
   * `remote-m3` publishes itself (#819). Read from `published`, not the served catalogs, because
   * those already have seams injected.
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

  /**
   * The builder vocabulary donated to published catalogs, curated per platform by
   * [composeFoundationCatalog] (Wear gets only what `WearScreenCodeExporter` can write). Built for
   * the platforms this deployment publishes.
   *
   * [platformFor] falls back to the synthesised catalog's platform for a published catalog that
   * declares none (pinned by `WearM3ScreenCatalogTest`; goes with `synthesisedCatalogs`, #819 step
   * 3). [donorFor] uses `getOrElse` so a caller outside construction still gets a donor.
   */
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
   * A published catalog plus the builder components it does not offer itself. Additive only: the
   * catalog wins every collision. The donor's asset registry travels with `asset/image` unless the
   * catalog has its own, since without one any invented `assetKey` would be accepted.
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
    // Injected components keep the donor's insert-panel shelves, in the donor's order relative to
    // the catalog's own groups (appending put the primary Layout and Scaffolds shelves at the
    // bottom).
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
    (synthesisedCatalogs + published.mapValues { (_, catalog) -> withBuilderVocabulary(catalog) })
      .mapValues { (_, catalog) -> withFontSettingsVocabulary(catalog) }
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
                .let { capabilities ->
                  // An A2UI design's JSON is the A2UI messages, not Remote Compose, so it is not
                  // behind the Remote Compose authoring flag: offered wherever the host says its
                  // executor writes JSON (`remoteJson`, the one JSON flag the contract has).
                  if (catalog.platform != A2UI_PLATFORM) capabilities
                  else
                    capabilities
                      .newBuilder()
                      .also { a2ui -> a2ui.remoteJson = exportCapabilities.remoteJson }
                      .build()
                }
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
   * Every reference a stored design may be pinned to for a catalog this deployment serves: both the
   * synthesised and the published source's, so flipping `--ui-builder-published-catalogs` does not
   * strand designs (#796).
   *
   * One direction only: the synthesised catalog is always resident, but a server that flipped back
   * has never seen the published file (asserted by `CatalogSourceFlipTest`; #818 closes it). Pins
   * naming neither source are still refused.
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
      // A placement is a document construct, not a catalog component: check only that its key names
      // a component this document declares. Catalog property and slot rules do not apply.
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
   * The asset rule: the key must be in the catalog's `statusSemantics.assetRegistry` or the
   * design's own pinned [pinnedAssetKeys] (#478); unchecked keys used to fail every render (#484).
   * A catalog without a registry says nothing about keys.
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
     * The A2UI basic catalog v0.9.1 as a builder palette; see `a2uiCatalog`. The same id
     * `:ui-builder-export`'s `A2uiDocumentExporter.CATALOG_SYSTEM_ID` names.
     */
    public const val A2UI_CATALOG_SYSTEM_ID: String = "a2ui-catalog"

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
 * A component pack as the host admits it: another catalog's finished capabilities, built by
 * `:server` (reading records is outside this module's boundary).
 *
 * @property id the pack's id: the served catalog it came from and the prefix of every component id
 *   (`confetti-mobile/session-card`). Checked like a catalog id. @property label what the editor
 *   calls the pack. @property platform the platform word (`mobile`, `wear`, `remote-compose`)
 *   naming which enabled catalogs receive it; a catalog declaring none is
 *   [CurrentM3UiBuilderCatalogExecutor.DEFAULT_PLATFORM]. @property nativeCatalog the served
 *   catalog whose bundle compiles a design using the pack, or null. @property components the
 *   capabilities, every id prefixed `<id>/`. @property notes a sentence for the settings row; empty
 *   when there is nothing to add.
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
 * The node that switches a subtree into the Remote Compose vocabulary. Duplicated from
 * `:ui-builder-export` to keep this module's dependency allowlist (`checkUiBuilderRuntimeBoundary`)
 * unchanged; `SlotAcceptanceTest` keeps the spellings honest.
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
 * The Remote Compose seams' namespace. Separate from the foundation's so [composeFoundationCatalog]
 * can take them from a Remote Compose source, letting `remote-m3` publish them (#819); still part
 * of [BUILDER_NAMESPACES].
 */
internal const val REMOTE_COMPOSE_NAMESPACE: String = "remote-compose/"

/**
 * The id namespaces the builder owns on every shelf, which [composeFoundationCatalog] supplies to
 * published catalogs. An explicit list: widening it widens every published catalog.
 */
internal val BUILDER_NAMESPACES = FOUNDATION_NAMESPACES + REMOTE_COMPOSE_NAMESPACE + DRAW_NAMESPACE

/**
 * The drawing vocabulary's namespace (`draw/canvas`, `draw/rect`, …). Only the Remote Compose
 * foundation declares any, so naming it here adds them to Remote Compose catalogs and nothing to
 * the others.
 */
internal const val DRAW_NAMESPACE: String = "draw/"

internal val REMOTE_COMPOSE_BORROWED_AS_THEMSELVES =
  setOf(
    "remote-compose/document",
    REMOTE_COMPOSE_INLINE_COMPONENT_ID,
    REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
  )

/**
 * The note a shared foundation component (Box, Column, Row, Image) carries on a Wear palette.
 * Shared by `wearM3Catalog` and [composeFoundationCatalog], which
 * `ComposeFoundationFaithfulnessTest` compares field for field. Lives here because
 * `ui-builder-catalog-literals.sh` restricts which files may name a catalog; goes with
 * `wearM3Catalog` (#819 step 3).
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
 * This catalog with [packs] merged in: components appended in pack order (duplicate ids refused),
 * one insert-panel shelf per pack after the catalog's own, and `componentPacks` recording what was
 * merged. The catalog pin is unchanged, so existing designs keep resolving.
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
 * Whether [slot] accepts a child of [component]: role and trait must both match, an empty list
 * constrains nothing, and `AnyContent` equals no traits. A copy of `:ui-builder`'s
 * `SlotCapability.accepts`; `SlotAcceptanceTest` pins both to one table.
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
 * The Wear widget host frame's authored parameters. `background` is the widget's own background
 * (the brush passed to `WearWidgetDocument`), defaulting to `WearWidgetContainer`'s `#FF272430`.
 *
 * Padding and corner radius are deliberately absent: the host supplies them per shape
 * (yschimke/wear-m3-catalog#623). Stored designs that carry them keep the values, but no lane reads
 * them: only the predefined host shapes are supported.
 */
internal fun widgetContainerProperties(): List<PropertyCapabilityV1> =
  listOf(
    PropertyCapabilityV1.Builder("background", JsonPrimitive("string"))
      .also {
        it.notes =
          "The widget's own background, painted by the host inside the frame it draws. Defaults " +
            "to #FF272430, the colour WearWidgetContainer applies to a widget that declares none. " +
            "Padding and corner radius are not properties: WearWidgetParams hands them to the " +
            "widget per host shape (0 rectangular, 26 squircle, 999 round), so the outermost box " +
            "never authors a radius."
      }
      .build()
  ) +
    // The widget's typefaces, one per group of `RemoteTypography` roles; the generated widget
    // sets them on `RemoteMaterialTheme`'s typography.
    ThemeTypefaces.WEAR_GROUPS.map { group ->
      PropertyCapabilityV1.Builder(group.property, JsonPrimitive("string"))
        .also { it.notes = ThemeTypefaces.notes(group, wear = true) }
        .build()
    } +
    // The role text with no `style` of its own is set in; see [ThemeTextStyle].
    PropertyCapabilityV1.Builder(ThemeTextStyle.PROPERTY, JsonPrimitive("string"))
      .also {
        it.notes = ThemeTextStyle.notes(wear = true)
        it.allowedValues = ThemeTextStyle.WEAR_ROLES.map(::JsonPrimitive)
      }
      .build()

/**
 * [catalog] with every text component carrying the [FontSettings] pair: a variable font's axes and
 * OpenType features, which every design system's text draws and every generator writes, so no
 * catalog has to publish them before a design can use them. Additive, like the rest of the builder
 * vocabulary: a catalog that declares either name keeps its own declaration.
 */
internal fun withFontSettingsVocabulary(catalog: CatalogCapabilityV1): CatalogCapabilityV1 {
  if (catalog.components.none { it.componentId in FontSettings.TEXT_COMPONENTS }) return catalog
  return catalog
    .newBuilder()
    .also { builder ->
      builder.components =
        catalog.components.map { component ->
          if (component.componentId !in FontSettings.TEXT_COMPONENTS) return@map component
          val declared = component.properties.mapTo(mutableSetOf()) { it.name }
          val missing =
            listOf(
                FontSettings.VARIATION_PROPERTY to FontSettings.VARIATION_NOTES,
                FontSettings.FEATURE_PROPERTY to FontSettings.FEATURE_NOTES,
              )
              .filter { (name, _) -> name !in declared }
          if (missing.isEmpty()) return@map component
          component
            .newBuilder()
            .also {
              it.properties =
                component.properties +
                  missing.map { (name, notes) ->
                    PropertyCapabilityV1.Builder(name, JsonPrimitive("string"))
                      .also { property -> property.notes = notes }
                      .build()
                  }
            }
            .build()
        }
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
   * The system property naming the directory of Google Fonts files (`<slug>-<weight>.ttf`) the
   * bundle's preview draws a design's typefaces from: `:ui-builder`'s
   * `GOOGLE_FONTS_DIRECTORY_PROPERTY`, which a host sets on the daemon it starts for [PREVIEW_ID].
   * Unset, every family the bundle does not vendor draws in the default face.
   */
  public const val GOOGLE_FONTS_DIRECTORY_PROPERTY: String = "uiBuilder.googleFontsDir"

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

  /**
   * Which pipeline draws a PNG export, as a SHA-256: the packaged bundle, and the projection
   * ([projectRendererDocument]) this runtime applies to a saved design before the bundle sees it.
   *
   * A host that keeps renders across restarts keys them by this, because the host's own version
   * does not move when only its UI-builder pin does. preview.coo.ee's design-card thumbnails were
   * keyed by the server version alone, so pictures drawn before 3.85.0 — whose projection dropped
   * every placement's `component`, drawing each as "Unsupported component:
   * design/component-instance → (none)" — kept serving after the release that fixed it shipped.
   *
   * Both halves, because that fix was in the projection: a hash of the bundle alone need not have
   * moved with it. The projection half is the compiled class the projection lives in, read from
   * this runtime's own jar, so any change to it changes the identity without a number to raise.
   */
  public fun digest(): String =
    "bundle=${bundleBytes().sha256()}\nprojection=${projectionBytes().sha256()}".sha256()

  public fun copyTo(root: Path): Path {
    val bytes = bundleBytes()
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

  private fun bundleBytes(): ByteArray =
    checkNotNull(javaClass.getResourceAsStream(RESOURCE)) {
        "packaged UI-builder renderer bundle is missing"
      }
      .use { it.readBytes() }

  private fun projectionBytes(): ByteArray {
    val resource = "/" + PROJECTION_CLASS.name.replace('.', '/') + ".class"
    return checkNotNull(PROJECTION_CLASS.getResourceAsStream(resource)) {
        "UI-builder renderer projection class $resource is not readable"
      }
      .use { it.readBytes() }
  }
}

/**
 * The file class [projectRendererDocument] compiles into, for
 * [PackagedUiBuilderRenderBundle.digest].
 */
private val PROJECTION_CLASS: Class<*> = MethodHandles.lookup().lookupClass()

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
              ) + node.filterKeys { it == "component" }
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
      ) + source.filterKeys { it == "components" } + projectedAssets(document, resolveAsset)
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
 * The capability of what a placement actually draws, following placements through their bodies'
 * roots; null when unresolvable. [seen] stops a `GRAPH_CYCLE` from recursing forever.
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
