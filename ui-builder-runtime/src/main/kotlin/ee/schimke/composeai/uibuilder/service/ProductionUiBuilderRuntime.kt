@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

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

/**
 * The explicitly enabled production catalogs admitted by the v1 service.
 *
 * Resolution is exact across all four pin fields. The packaged catalog is parsed strictly and its
 * invariants are checked before it is exposed; there is no "closest" revision or permissive
 * component fallback. Export capabilities are supplied by the renderer adapter at startup, so a
 * host without the packaged daemon lane advertises Compose only instead of claiming artifacts it
 * cannot produce.
 */
public class CurrentM3UiBuilderCatalogExecutor(
  source: String = packagedM3CatalogSource(),
  catalogSystemIds: Set<String> = setOf(DEFAULT_CATALOG_SYSTEM_ID),
  exportCapabilities: ExportCapabilitiesV1 =
    ExportCapabilitiesV1(composeCode = true, svg = false, png = false),
  /**
   * Whether a given catalog can export Compose, asked per catalog rather than once.
   *
   * `exportCapabilities` is a field of `CatalogCapabilityV1` — one per catalog on the wire — and
   * the host used to compute a single boolean and copy it onto every enabled catalog. So a
   * deployment serving `m3-catalog` (which has a component record) alongside `remote-m3` (which
   * deliberately does not, Remote Compose being outside the Compose exporter) advertised no Compose
   * export **anywhere**, and the builder withdrew the action from the catalog that could have used
   * it. Defaults to the flat value, so a caller that does not care is unaffected.
   */
  composeExportFor: (String) -> Boolean = { exportCapabilities.composeCode },
  /**
   * Component packs admitted by the host, merged into every enabled catalog of the same platform.
   *
   * A pack is another catalog's components — a served application catalog such as
   * `confetti-mobile`, projected from its published component record — offered inside the authoring
   * catalogs it is compatible with. Merged here rather than served as catalogs of their own because
   * a design is pinned to one catalog and a pack is not a thing to pin to: it has no scaffold, no
   * templates and no canvas adapter of its own. What it has is components, and a Material 3 phone
   * screen that can hold a `SessionCard` beside its `m3/card` is the whole point.
   *
   * Which catalogs a pack reaches is decided by platform, never by name. A mobile pack lands in
   * `m3-catalog`; it does not land in `remote-m3`, whose widget body is `@RemoteComposable` and
   * cannot call it, nor in `wear-m3`, which is not Material 3. The catalog declares what it carried
   * under `statusSemantics.componentPacks` so the editor can shelve the pack under its own name and
   * let an author switch it on and off.
   */
  packs: List<UiBuilderComponentPackSource> = emptyList(),
  /**
   * Catalogs composed from what a catalog repository PUBLISHED, keyed by system id.
   *
   * The cutover of `docs/design/UI_BUILDER_CATALOG_CONTRACT.md`, and the reason this class can stop
   * being the place a catalog is written. An entry here is preferred over the synthesised catalog
   * of the same id, and an id with no synthesiser is served from here alone — which is what lets a
   * catalog this binary has never heard of appear in the chooser.
   *
   * Per catalog and reversible on purpose: a catalog that publishes nothing, or whose published
   * file will not compose, keeps the synthesised one and a startup line says which source each came
   * from. Composing the file is `:server`'s job (it needs the component record reader, which
   * `checkUiBuilderRuntimeBoundary` keeps off this module's classpath), so this takes the finished
   * catalogs rather than the files.
   */
  published: Map<String, CatalogCapabilityV1> = emptyMap(),
) : UiBuilderCatalogExecutor {
  private val baseCatalog =
    json
      .decodeFromString<CatalogCapabilityV1>(source)
      .let(::validateCatalog)
      .copy(exportCapabilities = exportCapabilities)
  private val synthesisedCatalogs =
    mapOf(
      DEFAULT_CATALOG_SYSTEM_ID to baseCatalog,
      REMOTE_M3_CATALOG_SYSTEM_ID to remoteM3Catalog(baseCatalog),
      WEAR_M3_CATALOG_SYSTEM_ID to wearM3Catalog(baseCatalog),
    )

  /**
   * The builder's own vocabulary, taken from the packaged catalog.
   *
   * A column is not a Material 3 component and this server does not render one on a catalog's
   * behalf: the `layout/`, `shape/`, `asset/` and `remote-compose/` namespaces are the BUILDER's,
   * offered on every shelf whatever design system it describes. m3-catalog's published policy says
   * the same thing from the other side — it declares no builtins, on the stated grounds that
   * declaring them "would be this catalog claiming to own the builder's own vocabulary".
   *
   * Which makes this the server's to supply. A published catalog replaces the synthesised one
   * wholesale, so without this a catalog that correctly declines to claim `layout/box` ships a
   * shelf with no box on it. Naming the namespaces here rather than deriving them from the base
   * catalog's prefix is deliberate: the packaged catalog declares no `componentIdPrefix`, and
   * "everything the published catalog does not own" would hand a future `m4/` catalog the whole
   * `m3/` shelf.
   */
  /**
   * Where a published catalog's builder components come from.
   *
   * NOT one fixed set. The synthesised catalogs curate the builder vocabulary per platform, and
   * they are right to: `wear-m3` borrows `layout/box`, `layout/column`, `layout/row` and
   * `asset/image` and nothing else, because `WearScreenCodeExporter` refuses everything else with
   * "no Wear Compose Material 3 counterpart this generator can write". Handing a published Wear
   * catalog all sixteen would put `layout/lazy-grid`, `layout/scaffold` and the shapes on a watch
   * palette, where a design that uses one is guaranteed to fail export — a palette entry that
   * cannot be exported is worse than a missing one, because it is only discovered at the end.
   *
   * So the donor is the synthesised catalog of the same id, then any synthesised catalog for the
   * same platform, then the packaged one. A catalog this binary has never heard of still gets the
   * vocabulary of its platform's peer rather than a set chosen for someone else.
   */
  private fun donorFor(catalog: CatalogCapabilityV1): CatalogCapabilityV1 =
    synthesisedCatalogs[catalog.benchmark.catalogSystemId]
      ?: synthesisedCatalogs.values.firstOrNull { it.platform == catalog.platform }
      ?: baseCatalog

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
    return catalog.copy(components = catalog.components + missing, statusSemantics = semantics)
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
          .copy(
            exportCapabilities =
              catalog.exportCapabilities.copy(composeCode = composeExportFor(systemId))
          )
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

  private val references = catalogs.mapValues { (_, catalog) ->
    CatalogReferenceV1(
      systemId = catalog.benchmark.catalogSystemId,
      catalogRevision = catalog.benchmark.catalogRevision,
      // The frozen v1 fixture names this pin explicitly. When the catalog wire shape grows a
      // digest field, this becomes the digest read from the signed catalog rather than a
      // convention.
      capabilityDigest = CURRENT_CAPABILITY_DIGEST,
      nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
    )
  }
  private val components = catalogs.mapValues { (_, catalog) ->
    catalog.components.associateBy { it.componentId }
  }

  override fun listCatalogs(): List<CatalogCapabilityV1> = catalogs.values.toList()

  override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? =
    catalogs[reference.systemId]?.takeIf { reference == references[reference.systemId] }

  override fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? =
    catalog.benchmark.catalogSystemId.takeIf { catalogs[it] == catalog }?.let(references::get)

  override fun validate(
    document: DesignDocumentV1,
    catalog: CatalogCapabilityV1,
  ): UiBuilderCatalogIssue? {
    val systemId = catalog.benchmark.catalogSystemId
    if (catalog != catalogs[systemId])
      return issue("CATALOG_MISMATCH", "catalog is not an enabled UI-builder catalog")
    if (document.catalogPin != references[systemId]) {
      return issue("CATALOG_PIN_MISMATCH", "document catalog pin does not resolve exactly")
    }
    val catalogComponents = components.getValue(systemId)
    val encodedDocument = json.encodeToJsonElement(document).jsonObject
    val encodedNodes = encodedDocument.getValue("nodes").jsonObject
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
        continue
      }
      val component =
        catalogComponents[componentId]
          ?: return issue(
            "UNKNOWN_COMPONENT",
            "component $componentId is not in $systemId",
            nodeId,
          )
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
        if (!capability.jsonType.accepts(unwrapped)) {
          return issue(
            "INVALID_PROPERTY_TYPE",
            "property $name does not match its catalog JSON type",
            nodeId,
            name,
          )
        }
        if (capability.allowedValues.isNotEmpty() && unwrapped !in capability.allowedValues) {
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

      val allowedModifiers = component.modifierCapabilities.toSet()
      node.arrayOrEmpty("modifiers").forEachIndexed { index, modifier ->
        val type = (modifier as? JsonObject)?.optionalString("type")
        if (type == null || type !in allowedModifiers) {
          return issue(
            "UNKNOWN_MODIFIER",
            "modifier ${type ?: "at index $index"} is not declared by $componentId",
            nodeId,
            "modifiers[$index]",
          )
        }
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
              val key =
                child["component"]?.jsonObject?.get("componentKey")?.jsonPrimitive?.contentOrNull
              val root = key?.let { document.components[it]?.root }
              // Absent or dangling is already refused where the node itself is checked, so
              // reaching here with nothing to resolve means the body root is missing — a document
              // that would draw a placement of nothing.
              val rootComponentId = root?.let {
                encodedNodes[it]?.jsonObject?.requiredString("componentId")
              }
              rootComponentId?.let { catalogComponents[it] }
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
 * The Remote Compose ids `wear-m3` borrows without calling them stand-ins for anything.
 *
 * A borrowed *Material* component is drawn as its mobile counterpart and its note says so; a
 * borrowed *foundation* one is the same declaration on both platforms. These three are neither.
 * They are the Remote Compose seam itself — a published document, the switch into the remote
 * vocabulary, and the custom component that switches back out — and none of them is a Wear Compose
 * component whose fidelity a note could be making a claim about.
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
private const val REMOTE_COMPOSE_INLINE_COMPONENT_ID = "remote-compose/inline"

/** The node that switches back out of it — see [REMOTE_COMPOSE_INLINE_COMPONENT_ID]. */
private const val REMOTE_COMPOSE_CUSTOM_COMPONENT_ID = "remote-compose/custom"

/**
 * The id namespaces the BUILDER owns, on every shelf.
 *
 * Not a design system's: a box, a gradient, an image and the Remote Compose seams are the builder's
 * own vocabulary, which is why a catalog is right to publish components only under its own prefix
 * and why this server supplies the rest. Adding a namespace here widens what every published
 * catalog is handed, so it is a deliberate list rather than a pattern.
 */
private val BUILDER_NAMESPACES = listOf("layout/", "shape/", "asset/", "remote-compose/")

private val REMOTE_COMPOSE_BORROWED_AS_THEMSELVES =
  setOf(
    "remote-compose/document",
    REMOTE_COMPOSE_INLINE_COMPONENT_ID,
    REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
  )

/** The platform word a catalog declares, or the default for one that says nothing. */
internal val CatalogCapabilityV1.platform: String
  get() =
    statusSemantics[CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY]
      ?.let { it as? JsonPrimitive }
      ?.contentOrNull
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotEmpty) ?: CurrentM3UiBuilderCatalogExecutor.DEFAULT_PLATFORM

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
  return copy(
    statusSemantics =
      JsonObject(
        statusSemantics +
          ("componentMenu" to menu) +
          (CurrentM3UiBuilderCatalogExecutor.COMPONENT_PACKS_KEY to declaredPacks)
      ),
    components = components + packs.flatMap { it.components },
  )
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
    PropertyCapabilityV1(
      name = "background",
      jsonType = JsonPrimitive("string"),
      notes =
        "The widget's own background, painted by the host as the rounded rect. Defaults to " +
          "#FF272430, the colour WearWidgetContainer applies to a widget that declares none.",
    ),
    PropertyCapabilityV1(
      name = "horizontalPaddingDp",
      jsonType = JsonPrimitive("number"),
      notes = "WearWidgetParams.horizontalPaddingDp; 8 in every shipped preview spec.",
    ),
    PropertyCapabilityV1(
      name = "verticalPaddingDp",
      jsonType = JsonPrimitive("number"),
      notes = "WearWidgetParams.verticalPaddingDp; 8 in every shipped preview spec.",
    ),
    PropertyCapabilityV1(
      name = "cornerRadiusDp",
      jsonType = JsonPrimitive("number"),
      notes =
        "WearWidgetParams.cornerRadiusDp: 26 squircle, 999 round, 0 rectangular. The host draws " +
          "this radius behind the content rather than clipping to it.",
    ),
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
private fun wearComponentMenu(): JsonObject {
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
      "Embedded" to
        listOf(
          "remote-compose/document",
          REMOTE_COMPOSE_INLINE_COMPONENT_ID,
          REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
        ),
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
private val REMOTE_M3_MODIFIERS =
  setOf(
    "align",
    "alignHorizontal",
    "alignVertical",
    "alpha",
    "background",
    "border",
    "clip",
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
    "size",
    "verticalScroll",
    "weight",
    "width",
    "widthIn",
    "wrapContentSize",
    "zIndex",
  )

private fun remoteM3Catalog(base: CatalogCapabilityV1): CatalogCapabilityV1 {
  val components = base.components.associateBy { it.componentId }
  val box = components.getValue("layout/box")
  val supportedWasm = components.getValue("m3/text").wasm
  val blockedSvg = components.getValue("remote-compose/document").svg
  val contentSlot =
    box.slots
      .single()
      .copy(
        name = "content",
        cardinality = box.slots.single().cardinality.copy(min = 0, max = 1),
      )
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
    contentSlot.copy(
      name = "background",
      cardinality = contentSlot.cardinality.copy(min = 0, max = null),
      acceptedRoles = listOf("Leaf"),
      acceptedTraits = listOf("DrawLayer", "ImageContent"),
    )
  fun widget(componentId: String, displayName: String) =
    box.copy(
      componentId = componentId,
      displayName = displayName,
      role = "Scaffold",
      traits = listOf("ScreenContent", "WearWidgetHost", "RemoteContentHost"),
      slots = listOf(backgroundSlot, contentSlot),
      // `WearWidgetContainer`'s own parameters, and only those. The container composable takes
      // (horizontalPadding, verticalPadding, cornerRadius, background); the content size comes from
      // `WearWidgetParams` and is what picks Small over Large, so it stays the component id rather
      // than becoming a fifth property nobody could set to a legal value.
      properties = widgetContainerProperties(),
      modifierCapabilities = emptyList(),
      wasm =
        supportedWasm.copy(
          notes =
            "Compose UI recreation of the Glance Wear squircle host preview; its content slot may host ordinary or nested Remote Compose content, and its background slot the gradient and image brushes WearWidgetBrush chains."
        ),
      code = null,
      svg =
        blockedSvg?.copy(
          notes = "The copied Wear widget host geometry has not yet passed structured SVG parity."
        ),
    )
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
      "m3/surface",
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
  // Every borrowed component is narrowed to the modifiers the generator can write. The two lists
  // used to be independent — the palette offered 28 on a widget node and `RemoteContentEmitter`
  // wrote three — so `size`, `background` and `weight` were authorable, drawable, and unexportable
  // (yschimke/compose-preview-server#508). Narrowing here moves the refusal to the moment the
  // modifier is added, which is the only moment an author can act on it.
  fun ComponentCapabilityV1.narrowed(): ComponentCapabilityV1 =
    copy(modifierCapabilities = modifierCapabilities.filter { it in REMOTE_M3_MODIFIERS })
  return base.copy(
    // A Wear widget body is a Remote Compose document, played rather than composed. Said here so
    // the New design chooser can group it apart from the phone screens, and so no mobile pack is
    // ever merged into it: a `@RemoteComposable` body cannot call an application's composables.
    statusSemantics =
      JsonObject(
        base.statusSemantics +
          (CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY to JsonPrimitive("remote-compose")) +
          // `remote-m3` reads the base catalog's shelves, and the base catalog has never heard of
          // this component: it is synthesized here. Shelved rather than left to fall back to its
          // role heading ("Leaf") for the reason `ComponentMenu` gives — a menu is presentation,
          // and an author looking for an animation looks under Content.
          ("componentMenu" to base.statusSemantics.withMenuEntry("remote-m3/lottie", "Content")) +
          // Which daemon draws this catalog natively, and it is not a preference: a widget's body
          // is `androidx.compose.remote.creation.compose`, its container is `androidx.glance.wear`,
          // and both are Android AARs. Left undeclared this defaulted to `desktop`, so the native
          // lane sent a widget to Skiko — a compile that fails on every import and reads like the
          // design is broken. The Wasm claim is left alone: unlike `wear-m3`'s Material 3
          // lookalikes, the canvas draws this catalog's own borrowed components.
          ("previewSurfaces" to
            buildJsonObject {
              putJsonObject("native") {
                put("fidelity", JsonPrimitive("authoritative"))
                put("backend", JsonPrimitive("android"))
              }
            })
      ),
    benchmark =
      base.benchmark.copy(
        id = "remote-m3-wear-widget-scaffolds",
        sourceRevision = "wear-m3-catalog@d4e4e684e61d0657aad4ccb7752b8c0ab5d9dedf",
        catalogSystemId = CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
        catalogRevision = "wear-widget-scaffolds-v1",
      ),
    components =
      listOf(
        widget("remote-m3/widget-container-small", "Wear widget · Small (216×76dp)"),
        widget("remote-m3/widget-container-large", "Wear widget · Large (216×124dp)"),
        lottie(components.getValue("asset/image"), supportedWasm, blockedSvg),
      ) + authoringIds.map { components.getValue(it).narrowed() },
  )
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
  borrowed.copy(
    componentId = "remote-m3/lottie",
    displayName = "Lottie animation",
    role = "Leaf",
    // Not `ImageContent`, deliberately, even though the nearest borrowed shape is `asset/image`:
    // the widget's `background` slot accepts that trait, and a background there is a
    // `WearWidgetBrush` built outside composition — which a `@RemoteComposable` animation is not.
    // `RemoteContent` is what `remote-compose/document` carries and says the same true thing.
    traits = listOf("RemoteContent"),
    slots = emptyList(),
    properties = lottieProperties(),
    // Exactly what `RemoteContentEmitter` can write, and no more. A component that advertises a
    // modifier the generator refuses is a component whose export fails after the design is drawn,
    // which is the worst moment to learn it.
    modifierCapabilities = borrowed.modifierCapabilities.filter { it in REMOTE_M3_MODIFIERS },
    wasm =
      supportedWasm.copy(
        notes =
          "Drawn as a named placeholder carrying the animation's source and size. The canvas has no Lottie renderer, and compiling the animation the way the export does — into Remote Compose operations — is Horologist's Android-only creation API, which a Wasm build cannot link. A lookalike would be an impression of an animation nobody could check; the picture comes from the native lane, which builds this design's own generated document."
      ),
    // No component record: `WearWidgetCodeExporter` writes the whole widget, so the call site comes
    // from `RemoteContentEmitter` like every other node in this catalog's body.
    code = null,
    svg =
      blockedSvg?.copy(
        notes =
          "A placeholder on the canvas must not claim structured SVG parity with an animation."
      ),
  )

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
    PropertyCapabilityV1(
      name = "url",
      jsonType = JsonPrimitive("string"),
      notes =
        "Where the animation was fetched from. Resolved into `json` once, in the builder; the " +
          "generated widget never reaches the network.",
    ),
    PropertyCapabilityV1(
      name = "json",
      jsonType = JsonPrimitive("string"),
      notes =
        "The Lottie animation itself, as JSON text. This is what is compiled into the Remote " +
          "Compose document, so it is what the export needs.",
    ),
    PropertyCapabilityV1(
      name = "progress",
      jsonType = JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("object"))),
      notes =
        "Pins the animation to one frame, 0 (first) to 1 (last). Unset — the default — lets the " +
          "document's animation clock run it in a loop.",
    ),
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
    PropertyCapabilityV1(
      name = "timeText",
      jsonType = JsonPrimitive("string"),
      notes =
        "The curved status strip's text. Frozen rather than live: a design whose render changed " +
          "every minute could not be diffed. Empty draws no strip, which is `ScreenScaffold` " +
          "without a `timeText` argument.",
    ),
    PropertyCapabilityV1(
      name = "scrollIndicator",
      jsonType = JsonPrimitive("boolean"),
      notes =
        "Whether the generated screen gives `ScreenScaffold` a scroll indicator. The canvas draws " +
          "none either way: an indicator shows where a viewport sits in the content, and the " +
          "long-screenshot extent has no viewport. The real capture agrees — a `ScrollMode.LONG` " +
          "render sets `LocalScrollCaptureInProgress` and the emitted scaffold suppresses the " +
          "indicator while it is set, which is what keeps a stitched capture free of the dashes " +
          "an indicator drawn per frame leaves down the edge.",
    ),
    PropertyCapabilityV1(
      name = "background",
      jsonType = JsonPrimitive("string"),
      notes =
        "The screen's background. Wear is dark-first, so it defaults to the Wear Material 3 " +
          "`background` — pure black — rather than to the editor theme's surface.",
    ),
  )

/**
 * What a `ListHeader` lets an author set, which is its label and how the label reads.
 *
 * Not the full `m3/text` surface it borrows its shape from: a header's colour, alignment and size
 * are the component's, and offering them would invite a design that no longer measures 48dp — the
 * one thing this component exists to guarantee.
 */
private val WEAR_LIST_HEADER_PROPERTIES = setOf("text", "maxLines", "overflow")

/** `TransformingLazyColumn`'s authored parameters, minus the ones its state object carries. */
private fun wearTransformingLazyColumnProperties(): List<PropertyCapabilityV1> =
  listOf(
    PropertyCapabilityV1(
      name = "verticalSpacingDp",
      jsonType = JsonPrimitive("number"),
      notes = "`Arrangement.spacedBy` between items; 4dp is the Wear list default.",
    ),
    PropertyCapabilityV1(
      name = "transformation",
      jsonType = JsonPrimitive("string"),
      allowedValues = listOf(JsonPrimitive("spec"), JsonPrimitive("none")),
      notes =
        "Whether each item carries `SurfaceTransformation(spec)` and `transformedHeight`. The " +
          "canvas cannot draw either — see the wasm note — so this says what the generated " +
          "Kotlin emits, not what you are looking at.",
    ),
  )

/**
 * The Wear Material 3 components this catalog offers that have **no Material 3 counterpart at
 * all**.
 *
 * ## Why these can exist now, when `wear-m3/checkbox-button` was refused once
 *
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md` rules out one specific thing, and it is worth quoting
 * rather than paraphrasing: *do not fabricate a component in the Wasm canvas to stand in for a
 * library the canvas cannot link*. That rule closed
 * [#395](https://github.com/yschimke/compose-preview-server/pull/395), which built
 * `CheckboxButton`, `SwitchButton` and `RadioButton` as hand-assembled Material 3 shapes at sizes
 * read off a screenshot — an impression of upstream with nothing in the build to check it against,
 * wrong silently in the one surface an author trusts. The same document says what would let them
 * in: *they arrive with the streaming preview or they do not arrive*.
 *
 * They arrive with the streaming preview. `ServeUiBuilderNativePreview` now compiles a Wear
 * design's own generated Kotlin against a bundle carrying `androidx.wear.compose:compose-material3`
 * and renders it on the Android/Robolectric daemon, and `wear-m3` declares that lane authoritative
 * and its own canvas approximate (`previewSurfaces`, read by `UiBuilderPreviewSurfaces`). So the
 * premise the rule rests on — that the canvas is the surface an author trusts — is no longer true
 * here, and the rule itself is kept rather than bent: **nothing below is drawn as a lookalike**.
 * The canvas gives each of these a named placeholder occupying its place in the layout and claiming
 * nothing about its size, colour or shape, and the picture comes from Android.
 *
 * What that buys is the whole point. A Wear screen can now hold the controls Wear actually
 * publishes — a labelled full-width `CheckboxButton`, a `Slider`, a `DatePicker` — instead of a
 * palette of three renamed borrows and a container to put them in.
 *
 * ## The three that stay lookalikes
 *
 * `wear-m3/text`, `wear-m3/card` and `wear-m3/button` keep the Material 3 drawing they have, for
 * the reason that made them acceptable in the first place: each is a *rename* of a borrow the
 * canvas was already drawing, not a shape assembled for the occasion. `WearCanvasStandInTest` pins
 * that map to exactly those three, and nothing here joins it.
 */
private fun wearNativeOnlyComponents(
  supportedWasm: WasmCapabilityV1,
  blockedSvg: SvgCapabilityV1?,
  iconKeys: List<JsonElement>,
): List<ComponentCapabilityV1> {
  /** The note every component in this group carries, with its own composable named. */
  fun note(composable: String, extra: String = "") =
    "Wear Material 3's `$composable`." +
      (if (extra.isEmpty()) "" else " $extra") +
      " The canvas draws a named placeholder where this node sits rather than the component: " +
      "`androidx.wear.compose:compose-material3` is an Android AAR a Wasm build cannot link, and a " +
      "hand-drawn lookalike would be an impression of upstream with nothing in this build to check " +
      "it against. Switch the render surface to Android for the real one — that lane compiles this " +
      "design's own generated Kotlin against real Wear Compose."

  fun component(
    componentId: String,
    displayName: String,
    composable: String,
    role: String,
    traits: List<String>,
    properties: List<PropertyCapabilityV1> = emptyList(),
    slots: List<SlotCapabilityV1> = emptyList(),
    extra: String = "",
  ) =
    ComponentCapabilityV1(
      componentId = componentId,
      displayName = displayName,
      role = role,
      traits = traits,
      slots = slots,
      properties = properties,
      // No modifier vocabulary, and that is a statement rather than an omission. A Wear control is
      // laid out by the list and the scaffold around it — a `CheckboxButton` is a full-width row
      // whose height upstream fixes — and `WearScreenCodeExporter` writes the whole screen, so
      // there is no per-node modifier chain for it to carry one into.
      modifierCapabilities = emptyList(),
      wasm = supportedWasm.copy(notes = note(composable, extra)),
      // The call site comes from `WearScreenCodeExporter`, which writes the whole screen, and never
      // from a per-component record: `wear-m3` has none, deliberately.
      code = null,
      svg =
        blockedSvg?.copy(
          notes =
            "A placeholder on the canvas must not claim structured SVG parity with $composable."
        ),
    )

  /** `label` and `secondaryLabel`, which is the shape every Wear selection control shares. */
  fun labelled(secondary: Boolean = true) = buildList {
    add(
      PropertyCapabilityV1(
        name = "label",
        jsonType = JsonPrimitive("string"),
        required = true,
        notes =
          "The row's primary label. Wear's selection controls are labelled rows, not bare boxes.",
      )
    )
    if (secondary) {
      add(
        PropertyCapabilityV1(
          name = "secondaryLabel",
          jsonType = JsonPrimitive("string"),
          notes = "The second line, where there is one. Empty emits no `secondaryLabel` argument.",
        )
      )
    }
  }

  /**
   * A checked/selected flag, drivable from a state variable exactly as `m3/checkbox.checked` is.
   */
  fun flag(name: String, notes: String) =
    PropertyCapabilityV1(
      name = name,
      // `object` beside `boolean` for the reason the five mobile flags carry it: a state binding
      // arrives as a wrapper object, and a declaration of `boolean` alone judges the binding by
      // whatever scalar happens to be inside it.
      jsonType = JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("object"))),
      notes = notes,
    )

  fun enum(name: String, values: List<String>, notes: String, required: Boolean = false) =
    PropertyCapabilityV1(
      name = name,
      jsonType = JsonPrimitive("string"),
      required = required,
      allowedValues = values.map(::JsonPrimitive),
      notes = notes,
    )

  fun number(name: String, notes: String) =
    PropertyCapabilityV1(
      name = name,
      jsonType = JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("object"))),
      notes = notes,
    )

  fun text(name: String, notes: String, required: Boolean = false) =
    PropertyCapabilityV1(
      name = name,
      jsonType = JsonPrimitive("string"),
      required = required,
      notes = notes,
    )

  /** One content slot holding a single child, for the components whose API takes one lambda. */
  fun singleSlot(name: String, traits: List<String>, min: Int = 0) =
    SlotCapabilityV1(
      name = name,
      cardinality = SlotCardinalityV1(min = min, max = 1),
      ordered = true,
      acceptedRoles = emptyList(),
      acceptedTraits = traits,
    )

  fun manySlot(name: String, traits: List<String>) =
    SlotCapabilityV1(
      name = name,
      cardinality = SlotCardinalityV1(min = 0, max = null),
      ordered = true,
      acceptedRoles = emptyList(),
      acceptedTraits = traits,
    )

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
          PropertyCapabilityV1(
            name = "iconKey",
            jsonType = JsonPrimitive("string"),
            required = true,
            allowedValues = iconKeys,
            // The same keys `m3/icon` offers, and deliberately the same table. An icon is
            // `androidx.compose.material.icons`, which is not Material 3 and not Wear Material 3 —
            // it is the shared vector library both draw with — so this is one of the few places a
            // Wear component and a mobile one really do name the same symbol.
            notes =
              "A Material icon key, resolved to `Icons.…` by the same table `m3/icon` uses. The " +
                "vectors are `androidx.compose.material.icons`, which both platforms share, so " +
                "this key means the same thing on a watch as on a phone.",
          ),
          number("sizeDp", "The icon's box. Wear's own default is 24dp inside a button."),
        ),
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
          )
        ),
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
    ),
    component(
      componentId = "wear-m3/list-sub-header",
      displayName = "List sub-header",
      composable = "ListSubHeader",
      role = "Leaf",
      traits = listItem,
      properties = listOf(text("text", "The sub-header's label.", required = true)),
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
          "which is the whole reason `m3/checkbox` could never have been borrowed for it.",
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
 * ## Why this is a re-creation and not the library
 *
 * `androidx.wear.compose:compose-material3` is an Android AAR. The builder's canvas is Compose
 * Multiplatform for Wasm, which cannot link an AAR at all, so there is no version of this adapter
 * that draws with the real components — unlike `m3-catalog`, where the canvas draws the same
 * Material 3 the export names. Every capability note below says so rather than implying parity.
 *
 * ## The two components that are this catalog's whole point
 *
 * `wear-m3/screen-scaffold` and `wear-m3/transforming-lazy-column`. A Wear screen is a
 * `ScreenScaffold` wrapping a `TransformingLazyColumn` in something over ninety per cent of the
 * Wear Material 3 surface area, and neither has a Compose Multiplatform counterpart: the scaffold
 * owns the curved `TimeText` and the bezel scroll indicator, and the list scales and fades its rows
 * against the round display through `SurfaceTransformation`.
 *
 * The canvas draws the scaffold as a **stadium** — the screen's width, the content's height, round
 * caps — which is the Wear long-screenshot convention rather than a device. That is a deliberate
 * choice about what an author is building: the whole scrolling extent at once, not a 192dp keyhole
 * onto it. What it costs is stated in the wasm notes and again in
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md`: straight sides overstate the width a row actually gets
 * near the curve, and the row transformation is not drawn.
 *
 * ## The rest is borrowed, and that is a limitation rather than a design
 *
 * The content components are `m3-catalog`'s. A Wear `Button` is not a Material 3 `Button` — it is a
 * pill 52dp tall with its own colour roles — and `TitleCard`, `ListHeader` and `EdgeButton` have no
 * mobile counterpart at all. They are borrowed here because the alternative was shipping a scaffold
 * with nothing to put in it, and every one of them is a wasm note saying "drawn as its mobile
 * counterpart". Real Wear content ids under `wear-m3` are the next change, not a missing detail of
 * this one.
 */
private fun wearM3Catalog(base: CatalogCapabilityV1): CatalogCapabilityV1 {
  val components = base.components.associateBy { it.componentId }
  val box = components.getValue("layout/box")
  val lazyColumn = components.getValue("layout/lazy-column")
  val supportedWasm = components.getValue("m3/text").wasm
  val blockedSvg = components.getValue("remote-compose/document").svg
  val boxSlot = box.slots.single()

  val contentSlot =
    boxSlot.copy(name = "content", cardinality = boxSlot.cardinality.copy(min = 0, max = 1))
  // `ScreenScaffold(edgeButton = …)` takes one composable, and upstream's own samples put an
  // `EdgeButton` in it and nothing else. Narrowed to `Action` so a Text cannot be dropped into a
  // slot whose whole job is to hug the bottom curve with a button in it.
  val edgeButtonSlot =
    contentSlot.copy(
      name = "edgeButton",
      acceptedRoles = emptyList(),
      acceptedTraits = listOf("Action"),
    )
  // Wear's dialogs are a screen *state*, not a place in the layout: each takes a `visible` flag and
  // draws over the whole display when it is set, and upstream's own samples put them beside the
  // scaffold in the same `AppScaffold`. A slot in `content` would have made them list rows, which
  // is a full-screen dialog inside a scrolling item. Unbounded, because a screen can have more than
  // one dialog it shows at different moments — only one is ever `visible`.
  val overlaySlot =
    contentSlot.copy(
      name = "overlays",
      cardinality = contentSlot.cardinality.copy(min = 0, max = null),
      acceptedRoles = emptyList(),
      acceptedTraits = listOf("Overlay"),
    )

  val scaffold =
    box.copy(
      componentId = "wear-m3/screen-scaffold",
      displayName = "Wear screen · ScreenScaffold",
      role = "Scaffold",
      traits = listOf("ScreenContent", "WearScreenHost"),
      slots = listOf(contentSlot, edgeButtonSlot, overlaySlot),
      properties = wearScreenScaffoldProperties(),
      modifierCapabilities = emptyList(),
      wasm =
        supportedWasm.copy(
          notes =
            "Drawn as a Wear long-screenshot stadium at the document frame's width, with the content padding the real `ScreenScaffold` computes for that screen size, the clock where `AppScaffold` puts it, and a bezel scroll indicator. It is not Wear Compose — `androidx.wear.compose:compose-material3` is an Android AAR the Wasm canvas cannot link — but it is measured against it: wear-m3-catalog's stitched `ScrollMode.LONG` capture of the same list matches this to within a dp."
        ),
      // No Compose export from the catalog's own record: `ScreenScaffold` is a scaffold with a
      // `contentPadding` lambda and a scroll-state argument that has to agree with the list inside
      // it, which is a shape `ScreenGenerator`'s call-site emitter cannot write from a record.
      // `WearScreenCodeExporter` writes the whole screen instead, the way
      // `WearWidgetCodeExporter` writes the whole widget.
      code = null,
      svg =
        blockedSvg?.copy(
          notes = "The stadium screen frame has not been through structured SVG parity."
        ),
    )

  // The first content component that is Wear's rather than borrowed, and it exists because the
  // round trip found it. `ListHeader` is a 48dp item at every screen size — measured — and the
  // template used to fake that with a padded `m3/text`. The canvas matched; the *generated screen*
  // did not, because a padded Text is not a ListHeader and the generator has no business emitting
  // one as the other. Fifteen dp of header is the difference between "the two pictures agree" and
  // "the two pictures agree except at the top", and there is no way to close it from the borrowed
  // side.
  val listHeader =
    components.getValue("m3/text").let { text ->
      text.copy(
        componentId = "wear-m3/list-header",
        displayName = "List header",
        traits = text.traits + "ListItem",
        properties = text.properties.filter { it.name in WEAR_LIST_HEADER_PROPERTIES },
        modifierCapabilities = emptyList(),
        wasm =
          text.wasm.copy(
            notes =
              "Wear Material 3's `ListHeader`: a 48dp item whose label sits low in it, drawn on the screen's own background rather than on a surface. The height is upstream's and is what makes a generated screen's first row land where the canvas puts it."
          ),
        code = null,
      )
    }

  val transformingLazyColumn =
    lazyColumn.copy(
      componentId = "wear-m3/transforming-lazy-column",
      displayName = "Transforming lazy column",
      traits = lazyColumn.traits + "WearListContent",
      slots =
        listOf(lazyColumn.slots.single().copy(cardinality = lazyColumn.slots.single().cardinality)),
      properties = wearTransformingLazyColumnProperties(),
      wasm =
        supportedWasm.copy(
          notes =
            "Drawn as a plain Column at the list's own spacing. That is what a stitched `ScrollMode.LONG` capture of the real one is: `LONG` turns the row transformation off in order to stitch, so every row on the reference is full content width at every position and the Column reproduces it exactly. What neither shows is a live frame, where `SurfaceTransformation` scales and fades a row by its distance from the bezel; the generated Kotlin emits that, and a single-frame render is what draws it."
        ),
      code = null,
      svg =
        blockedSvg?.copy(
          notes =
            "An untransformed stand-in must not claim structured SVG parity with the real list."
        ),
    )

  /**
   * A Wear component of this catalog's own, drawn on the canvas by its Material 3 lookalike.
   *
   * The rename is the point. `wear-m3` used to *borrow* `m3/text`, `m3/card`, `m3/button`,
   * `m3/icon` and `m3/surface` outright, and a borrowed Material id is a claim nobody should make
   * on a watch: **you do not use Material 3 and Wear Material 3 together.** They are different
   * libraries with different theme systems, sizes and colour roles, and a palette that offers
   * `m3/card` on a Wear screen says the design holds a `androidx.compose.material3.Card` when
   * `WearScreenCodeExporter` has always written it out as a `TitleCard`.
   *
   * So the id is Wear's and the drawing is borrowed, which is the same trade
   * [wear-m3/screen-scaffold][wearM3Catalog] and `wear-m3/list-header` already make: the Wasm
   * canvas cannot link `androidx.wear.compose:compose-material3` — it is an Android AAR — so it
   * draws the nearest Material 3 shape and says so in `wasm.notes`, while the generated Kotlin
   * names the real Wear composable.
   *
   * What is *actually* borrowed after this is foundation only: `layout/box`, `layout/column`,
   * `layout/row` and `asset/image` are `androidx.compose.foundation` and `androidx.compose.ui`,
   * which both platforms share, so borrowing them claims nothing about Material at all.
   */
  fun wearOwn(
    borrowedFrom: String,
    componentId: String,
    displayName: String,
    drawnAs: String,
    generatesAs: String,
  ) =
    components.getValue(borrowedFrom).let { source ->
      source.copy(
        componentId = componentId,
        displayName = displayName,
        wasm =
          source.wasm.copy(
            notes =
              "Wear Material 3's $generatesAs. Drawn on the canvas as $drawnAs, because the Wasm " +
                "canvas cannot link `androidx.wear.compose:compose-material3`; the generated " +
                "screen names the Wear composable."
          ),
        // The Compose call site comes from `WearScreenCodeExporter`, which writes the whole screen,
        // rather than from a per-component record: a Wear component's arguments are not the
        // Material
        // 3 component's, and a record naming the mobile callable is the mistake this rename undoes.
        code = null,
      )
    }

  val wearText = wearOwn("m3/text", "wear-m3/text", "Text", "a Material 3 Text", "`Text`")
  val wearCard =
    wearOwn("m3/card", "wear-m3/card", "Card", "a Material 3 Card", "`TitleCard`").let { card ->
      // A variant that **selects the composable**, the way `m3/card`'s does — not a recolouring of
      // one. Wear publishes four cards with different content lambdas: `TitleCard` (title,
      // subtitle, time), `AppCard` (an app name and icon above the title), `OutlinedCard` and the
      // plain `Card`. Rolling them into one id with a variant rather than four ids is the
      // `m3/card` precedent, and it keeps the palette the size of the vocabulary rather than the
      // size of the API.
      card.copy(
        properties =
          card.properties.filterNot { it.name == "variant" } +
            PropertyCapabilityV1(
              name = "variant",
              jsonType = JsonPrimitive("string"),
              allowedValues = listOf("title", "app", "outlined", "plain").map(::JsonPrimitive),
              notes =
                "Which card is written: `TitleCard`, `AppCard`, `OutlinedCard` or `Card`. " +
                  "`title` is the default and is the one a Wear list is mostly made of.",
            )
      )
    }
  val wearButton =
    wearOwn("m3/button", "wear-m3/button", "Button", "a Material 3 Button", "`Button`").let { button
      ->
      // The same treatment, and the same reason. Wear's four are `Button`, `FilledTonalButton`,
      // `OutlinedButton` and `ChildButton`; the mobile `style` list this borrowed carried `fab` and
      // `elevated`, which no watch publishes, so the property is replaced rather than filtered.
      // `m3/button` used to carry a `leadingIcon` slot, which this borrowed and had to re-point at
      // `wear-m3/icon`'s trait. The slot is gone from both: Wear's `Button` takes one content
      // lambda and an icon goes inside it, exactly as Material's does.
      button.copy(
        properties =
          button.properties.filterNot { it.name == "variant" || it.name == "style" } +
            PropertyCapabilityV1(
              name = "variant",
              jsonType = JsonPrimitive("string"),
              allowedValues =
                listOf("filled", "filled-tonal", "outlined", "child").map(::JsonPrimitive),
              notes =
                "Which button is written: `Button`, `FilledTonalButton`, `OutlinedButton` or " +
                  "`ChildButton`. There is no `fab` — a watch has no floating action button, " +
                  "which is one of the things a borrowed `m3/button` was quietly offering.",
            )
      )
    }
  val nativeOnly =
    wearNativeOnlyComponents(
      supportedWasm = supportedWasm,
      blockedSvg = blockedSvg,
      // The icon key table is `m3/icon`'s, taken from the catalog rather than restated: two lists
      // of icon names is two chances to disagree about which vector `genres` is.
      iconKeys =
        components.getValue("m3/icon").properties.single { it.name == "iconKey" }.allowedValues,
    )

  // Foundation only. Every one of these is `androidx.compose.foundation` or `androidx.compose.ui`,
  // shared by both platforms, so borrowing it claims nothing about which Material library a Wear
  // screen is built from — which is exactly what borrowing a Material component did claim.
  //
  // `m3/icon` left with the Material ones and has no Wear id yet: the icon key resolves to a vector
  // through a table this export module cannot reach, so `wear-m3/icon` would be a palette entry
  // that
  // refuses on export — which is what `m3/icon` already was here.
  val borrowedIds =
    listOf(
      "layout/box",
      "layout/column",
      "layout/row",
      "asset/image",
      // Every published `remote-m3` component, reachable from a Wear screen.
      //
      // Offering `remote-compose/document` is what lights up the builder's "Remote Compose
      // documents" palette, which lists every preview the *serving* catalog of that name publishes
      // an `ir/<id>.rc` for — on preview.coo.ee, the 28 Remote Compose components of
      // wear-m3-catalog's `:remote-catalog`, in all their published states. The bytes are fetched,
      // decoded and played in-process by the same `RcComposePlayer` the deployed player lanes use,
      // so a row dropped into a Wear list is drawn by the renderer a watch would use rather than by
      // a re-creation.
      //
      // This is the one component in the catalog that is not a stand-in for anything. It is also
      // the one the Compose generator refuses by name: a Remote Compose document has no Wear
      // Compose call site, so a screen holding one exports as a refusal that names the node rather
      // than as Kotlin that does not compile.
      "remote-compose/document",
      // The vocabulary switch and the way back out of it. `remote-compose/inline` says "everything
      // below me is @RemoteComposable", which is the second way a Wear screen embeds Remote Compose
      // content: `remote-compose/document` plays bytes somebody else published, and this one is
      // authored here, in this design, out of the same `layout/column` and `m3/text` stand-ins the
      // `remote-m3` catalog already publishes for exactly that purpose.
      //
      // `remote-compose/custom` is the return direction, and it is why the pair is worth having:
      // a Remote Compose document cannot call an application's composables, so the only way host
      // content gets inside one is a custom component the host registers a renderer under. A design
      // can therefore nest Compose inside Remote Compose inside Compose, which is the shape the
      // Wear catalogs' own stickers already take.
      REMOTE_COMPOSE_INLINE_COMPONENT_ID,
      REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
    )
  val borrowed =
    borrowedIds.map(components::getValue).map { component ->
      // The note every borrowed component carries, and it now says the opposite of what it used to.
      // A borrowed Material component was a stand-in — "drawn as the Material 3 component of the
      // same name" — because Wear publishes its own and this drew the wrong one. A borrowed
      // foundation component is not a stand-in for anything: `Box`, `Column`, `Row` and `Image` are
      // the same declarations on both platforms, which is the whole reason these are the only ones
      // left.
      if (component.componentId in REMOTE_COMPOSE_BORROWED_AS_THEMSELVES) component
      else
        component.copy(
          wasm =
            component.wasm.copy(
              notes =
                "Foundation, shared by Compose on both platforms — `androidx.compose.foundation` " +
                  "and `androidx.compose.ui` publish one of these, not two. It is the real " +
                  "component rather than a stand-in, which is why `wear-m3` borrows it and borrows " +
                  "no Material component at all."
            )
        )
    }

  return base.copy(
    // The one thing this catalog has to say about itself that is not a component.
    //
    // The Wasm canvas is where a `wear-m3` design is *authored* — you select a node on it, drag it,
    // watch the layout — and it is not where the design is *looked at*. It cannot be: every Wear
    // component on it is a Material 3 lookalike, because `androidx.wear.compose:compose-material3`
    // is an Android AAR and a Wasm build links no AAR, ever. Saying that here rather than leaving
    // each surface to work it out is what stops the editor offering a Preview mode whose claim is
    // false and the server picking a daemon by guessing from a catalog id.
    //
    // `statusSemantics` rather than a field of its own because `CatalogCapabilityV1` is published
    // from compose-preview-contracts and cannot grow one from here; this map is the catalog's own
    // open vocabulary and already carries `adapterStatus` and `svgStatus`. Read back by
    // `UiBuilderPreviewSurfaces.from`.
    statusSemantics =
      JsonObject(
        base.statusSemantics +
          // A round watch screen against Wear Compose: grouped apart from the phone screens in the
          // chooser, and offered no mobile pack — Wear Material 3 and Material 3 are not used
          // together, which is the rule this whole catalog is written around.
          (CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY to JsonPrimitive("wear")) +
          ("previewSurfaces" to
            buildJsonObject {
              putJsonObject("wasm") {
                put("fidelity", JsonPrimitive("approximate"))
                put(
                  "reason",
                  JsonPrimitive(
                    "The canvas is Compose Multiplatform for Wasm and Wear Material 3 is an " +
                      "Android AAR, so every Wear component here is drawn by its nearest Material " +
                      "3 lookalike. Author on it; do not read a size, a colour or a shape off it. " +
                      "The Android preview compiles this design's own generated Kotlin against " +
                      "real Wear Compose."
                  ),
                )
              }
              putJsonObject("native") {
                put("fidelity", JsonPrimitive("authoritative"))
                put("backend", JsonPrimitive("android"))
              }
            }) +
          // The other thing a catalog says about itself that is not a component: how the builder's
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
          // `:ui-builder`, which this module must never depend on — `checkUiBuilderRuntimeBoundary`
          // enforces the arrow, and the editor is above the runtime, not beside it.
          ("componentMenu" to wearComponentMenu())
      ),
    benchmark =
      base.benchmark.copy(
        id = "wear-m3-screen-scaffold",
        sourceRevision = "compose-ai-tools:samples/design-catalog-wear-m3",
        catalogSystemId = CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
        catalogRevision = "wear-screen-scaffold-v1",
      ),
    components =
      listOf(scaffold, transformingLazyColumn, listHeader, wearText, wearCard, wearButton) +
        nativeOnly +
        borrowed,
  )
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
    ExportCapabilitiesV1(composeCode = true, svg = renderer.supportsSvg, png = true)

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
