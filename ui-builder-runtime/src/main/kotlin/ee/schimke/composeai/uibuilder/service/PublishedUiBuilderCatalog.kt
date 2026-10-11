package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_MODIFIERS
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.protocol.BrowserPreviewCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CodeCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComposeSourceExportCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCardinalityV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityStatusV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SvgFallbackV1
import ee.schimke.composeai.uibuilder.protocol.UnrolledMockV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Composes a catalog's published `ui-builder.json` (policy) with its `components.json` (record)
 * into the capability catalog the builder serves, joined on [UiBuilderComponentPolicy.record]'s
 * `canonicalId`. See `docs/design/UI_BUILDER_CATALOG_CONTRACT.md`.
 *
 * It invents nothing: a component with no policy gets the record's defaults. The equivalence script
 * and frozen goldens prove the result equals what the server synthesises.
 */
public object PublishedUiBuilderCatalog {

  /** The outcome of composing one catalog, which is never an exception. */
  public sealed interface Result {
    /**
     * [catalog] is ready to serve; [note] is the one startup line saying where it came from.
     *
     * [records] maps each record-derived builder id to its record component — a join only the
     * published file can make, so it is carried rather than re-derived. Declaration-only builtins
     * are absent; a builtin with a catalog-shipped implementation record is present.
     */
    public data class Composed(
      public val catalog: CatalogCapabilityV1,
      public val note: String,
      public val records: Map<String, ComponentRecord>,
    ) : Result

    /**
     * The published file could not be used; [reason] says why for an operator. Never fatal: the
     * catalog falls back to whatever the server can synthesise.
     */
    public data class Unusable(public val reason: String) : Result
  }

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = false
  }

  /**
   * Compose [publishedJson] with [record] into a capability catalog. [record] may be null: a
   * catalog with policy and no inventory is all builtins.
   */
  public fun compose(
    publishedJson: String,
    record: ComponentRecordFile?,
    exportCapabilities: ExportCapabilitiesV1,
  ): Result {
    val root =
      runCatching { json.parseToJsonElement(publishedJson) as? JsonObject }
        .getOrElse {
          return Result.Unusable("ui-builder.json did not parse: ${it.message}")
        } ?: return Result.Unusable("ui-builder.json is not a JSON object")
    val file = runCatching {
      json.decodeFromJsonElement<PublishedFile>(root)
    }
      .getOrElse {
        return Result.Unusable("ui-builder.json did not parse: ${it.message}")
      }
    // The whole published block, kept verbatim for the readers this one does not interpret.
    val rawSemantics = root["statusSemantics"] as? JsonObject ?: JsonObject(emptyMap())
    if (file.schema != UI_BUILDER_CATALOG_SCHEMA) {
      // An unknown major is refused rather than read optimistically; the fallback keeps the catalog
      // served.
      return Result.Unusable(
        "ui-builder.json declares schema ${file.schema}; this server reads $UI_BUILDER_CATALOG_SCHEMA"
      )
    }
    val semantics = file.statusSemantics
    val prefix = semantics.componentIdPrefix.trim()
    if (prefix.isEmpty()) {
      return Result.Unusable("ui-builder.json declares no componentIdPrefix")
    }
    val id = file.catalog.id.trim()
    if (id.isEmpty()) return Result.Unusable("ui-builder.json declares no catalog id")

    // Refuse a `jsonType` the design validator cannot read, or an unsatisfiable slot cardinality.
    // `JsonElement.accepts` would otherwise THROW while a design is saved. Builtins are checked
    // too. Cardinality is checked here because the runtime's `validateCatalog` only sees
    // `m3-catalog`.
    val unreadable = mutableListOf<String>()
    fun checkProperties(owner: String, properties: List<UiBuilderPropertyPolicy>) {
      // Unique names: `CapabilityCatalogParser.validateCatalogShape` THROWS on duplicates, which
      // would stop the editor opening.
      properties
        .groupBy { it.name }
        .filterValues { it.size > 1 }
        .keys
        .forEach { unreadable += "$owner.$it (declared twice)" }
      for (property in properties) {
        when (val type = property.jsonType) {
          is JsonArray -> {
            // `[]` admits no value, so a required property declaring it cannot be authored.
            if (type.isEmpty()) unreadable += "$owner.${property.name} (jsonType is empty)"
            else
              type.forEach { entry ->
                val name = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (name == null || name !in SUPPORTED_JSON_TYPES)
                  unreadable += "$owner.${property.name} (jsonType $entry is not a type name)"
              }
          }
          is JsonPrimitive ->
            if (!type.isString || type.content !in SUPPORTED_JSON_TYPES)
              unreadable += "$owner.${property.name} (jsonType $type is not a type name)"
          else -> unreadable += "$owner.${property.name} (jsonType is neither a name nor a list)"
        }
        // The editor seeds a new node from the first allowed value as a primitive, so a
        // non-primitive one is refused.
        property.allowedValues.forEachIndexed { index, value ->
          if (value !is JsonPrimitive)
            unreadable += "$owner.${property.name}.allowedValues[$index] (not a literal)"
        }
      }
    }
    fun checkSlots(owner: String, slots: List<UiBuilderSlotPolicy>) {
      slots
        .groupBy { it.name }
        .filterValues { it.size > 1 }
        .keys
        .forEach { unreadable += "$owner.$it (slot declared twice)" }
      for (slot in slots) {
        val min = slot.cardinality.min
        val max = slot.cardinality.max
        if (min < 0) unreadable += "$owner.${slot.name} (cardinality min $min is negative)"
        else if (max != null && max < min)
          unreadable += "$owner.${slot.name} (cardinality max $max is below min $min)"
      }
    }
    // `checkSlots` applied to a builtin's slots: `required` is the minimum, `max` the bound. No
    // duplicate-name check: builtin slots are a map.
    fun checkBuiltinSlots(owner: String, slots: Map<String, UiBuilderBuiltinSlot>) {
      for ((name, slot) in slots) {
        val min = if (slot.required) 1 else 0
        val max = slot.max ?: continue
        if (max < min) unreadable += "$owner.$name (cardinality max $max is below min $min)"
      }
    }
    semantics.components.forEach { (componentId, policy) ->
      checkProperties(componentId, policy.propertyCapabilities.orEmpty())
      checkSlots(componentId, policy.slotCapabilities.orEmpty())
    }
    semantics.builtins.forEach { (builtinId, builtin) ->
      checkProperties(builtinId, builtin.properties.orEmpty())
      checkBuiltinSlots(builtinId, builtin.slots)
    }
    if (unreadable.isNotEmpty()) {
      return Result.Unusable(
        "${unreadable.size} declaration(s) the builder cannot serve: " +
          unreadable.sorted().take(8).joinToString(", ") +
          (if (unreadable.size > 8) ", …" else "")
      )
    }

    val policyByRecordId =
      semantics.components.entries.associateBy({ it.value.record }, { it.key to it.value })
    val taken = linkedMapOf<String, ComponentCapabilityV1>()
    // The same join as `taken`, kept as records. See [Result.Composed.records].
    val recordsById = linkedMapOf<String, ComponentRecord>()
    val recordsByCanonicalId =
      record?.components.orEmpty().associateBy(ComponentRecord::canonicalId)
    val skipped = mutableListOf<String>()
    // Counted apart from `skipped`: a collision means two components claimed one identity.
    var collisions = 0
    // Entries that competed for an identity. Excluded components are not counted, so a mostly
    // excluded record cannot hide a shelf where everything left collides.
    var eligible = 0
    // Record components the published file recognised; zero is the mismatched-pair case refused
    // below. Excluded entries count as joined.
    var joined = 0

    record?.components.orEmpty().forEach { component ->
      val declared = policyByRecordId[component.canonicalId]
      if (declared != null) joined++
      // An entry answering only to the builder's own namespaces (`layout/`, `shape/`, `asset/`) is
      // unioned in by `ProductionUiBuilderRuntime.withBuilderVocabulary`; deriving `<prefix>column`
      // here too would offer a duplicate. Skipped only where the policy says nothing about it.
      // `componentIds.isNotEmpty()` matters: entries with no id are reached by canonical id
      // instead.
      if (declared == null && component.isBuilderVocabulary()) return@forEach
      val policy = declared?.second
      val componentId = declared?.first ?: derivedId(prefix, component)
      val excluded = policy?.excluded
      when {
        excluded != null -> skipped += "$componentId — $excluded"
        // A duplicate id is reported, not resolved: picking a winner would bind saved designs to
        // whichever entry sorted first.
        taken.containsKey(componentId) -> {
          eligible++
          collisions++
          skipped += "$componentId — a component of the same id was already taken from this record"
        }
        else -> {
          eligible++
          taken[componentId] = capability(componentId, component, policy, semantics.platform)
          recordsById[componentId] = component
        }
      }
    }

    semantics.builtins.forEach { (builtinId, builtin) ->
      if (taken.containsKey(builtinId)) {
        skipped += "$builtinId — declared as a builtin, but a record component publishes this id"
      } else {
        val implementation = builtin.implementation?.let(recordsByCanonicalId::get)
        taken[builtinId] = builtinCapability(builtinId, builtin, semantics.platform, implementation)
        implementation?.let { recordsById[builtinId] = it }
      }
    }

    if (taken.isEmpty()) {
      return Result.Unusable(
        "composing ui-builder.json with the component record yielded no components"
      )
    }
    // One collision is a catalog bug and is skipped; collisions in bulk mean the ids are not
    // identities, so the file is refused. `maxOf(1, …)` keeps a single collision a skip at any
    // record size.
    val allowed = maxOf(1, (eligible * COLLISION_REFUSAL_RATE).toInt())
    if (eligible > 0 && collisions > allowed) {
      return Result.Unusable(
        "$collisions of $eligible eligible record components collided on an already-taken " +
          "component id, leaving ${taken.size} — the published file is not naming components " +
          "distinctly, so which one survives is an accident of record order"
      )
    }
    // The file expected an inventory and none arrived; refuse rather than serve a builtins-only
    // shelf.
    val expected = file.record?.components ?: 0
    if (expected > 0 && record == null) {
      return Result.Unusable(
        "ui-builder.json was generated against a $expected-component record and none is available " +
          "here, so it would compose to its builtins alone"
      )
    }
    // A record arrived for a different catalog: no policy joined, so every component would be
    // served its call-site vocabulary under the right names. Refused rather than matched on a
    // suffix; which record a file describes is the producer's to state.
    val stated = semantics.components.values.count { it.record != null }
    val inventory = record?.components.orEmpty()
    if (stated > 0 && inventory.isNotEmpty() && joined == 0) {
      return Result.Unusable(
        "none of the $stated component(s) ui-builder.json states a record for is in the " +
          "${inventory.size}-component record supplied here — the file joins on " +
          "`${policyByRecordId.keys.filterNotNull().min()}` and the record canonicalises its " +
          "components as `${inventory.minOf { it.canonicalId }}`, so the two are not a pair and " +
          "every component would be served its call site's vocabulary rather than this catalog's"
      )
    }

    val catalog =
      CatalogCapabilityV1.Builder(
          CAPABILITY_SCHEMA,
          CatalogBenchmarkV1.Builder(
              id,
              file.record?.file ?: UI_BUILDER_CATALOG_FILE_NAME,
              id,
              revisionOf(publishedJson),
              NATIVE_RUNTIME_ID,
            )
            .build(),
          taken.values.toList(),
        )
        .also {
          it.exportCapabilities = exportCapabilities
          it.statusSemantics = rawSemantics
          it.browserPreview = semantics.browserPreview
          it.composeSourceExport = semantics.composeSourceExport
        }
        .build()
    val note =
      "$id — published ui-builder.json (${taken.size} component(s)" +
        (if (skipped.isEmpty()) "" else ", ${skipped.size} skipped") +
        ")"
    return Result.Composed(catalog, note, recordsById)
  }

  /**
   * The namespaces the builder owns rather than any catalog. Must agree with `ui-builder-runtime`'s
   * internal `BUILDER_NAMESPACES`.
   */
  private val BUILDER_VOCABULARY_NAMESPACES = listOf("layout/", "shape/", "asset/")

  /** Whether this record entry answers only to ids the builder owns; see [compose]. */
  private fun ComponentRecord.isBuilderVocabulary(): Boolean =
    componentIds.isNotEmpty() &&
      componentIds.all { id -> BUILDER_VOCABULARY_NAMESPACES.any(id::startsWith) }

  /**
   * The builder id of a record component the published file says nothing about.
   *
   * Reproduces the generator's derivation (the catalog id leaf where there is one); the equivalence
   * gate proves the two still match.
   */
  private fun derivedId(prefix: String, component: ComponentRecord): String {
    val leaf =
      component.componentIds.firstOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: component.symbol.name
    return "$prefix${slug(leaf)}"
  }

  /**
   * `CheckboxButton` → `checkbox-button`, `RTLText` → `rtl-text`, `Button2` → `button2`.
   *
   * A run of capitals is one word. Must match the generator: a derived id is a saved design's
   * identity.
   */
  internal fun slug(name: String): String {
    val out = StringBuilder()
    name.forEachIndexed { index, ch ->
      when {
        ch.isLetterOrDigit() -> {
          val previous = name.getOrNull(index - 1)
          val next = name.getOrNull(index + 1)
          val startsWord =
            previous != null &&
              ch.isUpperCase() &&
              (previous.isLowerCase() ||
                previous.isDigit() ||
                (previous.isUpperCase() && next?.isLowerCase() == true))
          if (startsWord && out.isNotEmpty() && out.last() != '-') out.append('-')
          out.append(ch.lowercaseChar())
        }
        out.isNotEmpty() && out.last() != '-' -> out.append('-')
      }
    }
    return out.toString().trim('-')
  }

  /** One record component, as the builder offers it, with the catalog's policy applied. */
  private fun capability(
    componentId: String,
    component: ComponentRecord,
    policy: UiBuilderComponentPolicy?,
    platform: String,
  ): ComponentCapabilityV1 {
    val remote =
      UiBuilderCatalogPlatform.fromWord(platform) == UiBuilderCatalogPlatform.REMOTE_COMPOSE
    // The catalog's slots, else the composable's.
    val slots =
      policy?.slotCapabilities?.map { stated ->
        SlotCapabilityV1.Builder(
            stated.name,
            SlotCardinalityV1.Builder()
              .also {
                it.min = stated.cardinality.min
                it.max = stated.cardinality.max
              }
              .build(),
            stated.ordered,
          )
          .also {
            it.acceptedRoles = stated.acceptedRoles
            it.acceptedTraits = stated.acceptedTraits
          }
          .build()
      }
        ?: component.slots.map { slot ->
          SlotCapabilityV1.Builder(
              slot.name,
              SlotCardinalityV1.Builder()
                .also {
                  it.min = 0
                  it.max = null
                }
                .build(),
              true,
            )
            .also {
              // A derived Remote slot takes any shelf role the emitter can write, not `AnyContent`,
              // which would admit mobile components the emitter refuses.
              if (remote) {
                it.acceptedRoles = REMOTE_SLOT_ROLES
                it.acceptedTraits = REMOTE_SLOT_TRAITS
              }
            }
            .build()
        }
    val slotNames = slots.map { it.name }.toSet()
    // What the catalog says it offers, else what its call site takes.
    val derivedProperties =
      component.parameters
        .filterNot { it.composableSlot || it.name in slotNames }
        .mapNotNull { parameter ->
          val jsonType = ComponentRecordPacks.jsonTypeOf(parameter) ?: return@mapNotNull null
          PropertyCapabilityV1.Builder(
              ComponentRecordPacks.propertyNameOf(parameter),
              if (remote && parameter.typeFqn in REMOTE_STATE_TYPES)
                JsonArray(listOf(JsonPrimitive(jsonType), JsonPrimitive("object")))
              else JsonPrimitive(jsonType),
            )
            .also {
              it.required = !parameter.hasDefault && !parameter.nullable
              it.notes = "`${parameter.name}: ${parameter.type}` on `${component.symbol.callable}`."
            }
            .build()
        }
    // Authored policy is the exception vocabulary and overrides a convention of the same name.
    val authoredProperties = policy?.propertyCapabilities?.map { it.toCapability() }.orEmpty()
    val authoredNames = authoredProperties.mapTo(mutableSetOf()) { it.name }
    val properties = authoredProperties + derivedProperties.filter { it.name !in authoredNames }
    return ComponentCapabilityV1.Builder(
        componentId,
        policy?.displayName ?: component.symbol.name,
        // Stated role first: slot presence cannot tell a scaffold from a container. Unknown words
        // fall back to the derivation; see [SHELF_ROLES].
        policy?.shelfRole?.takeIf { it in SHELF_ROLES }
          ?: if (slots.isNotEmpty()) "Container" else "Leaf",
        wasm(
          policy?.canvas,
          policy?.nativeOnly == true,
          component.symbol.callable,
          policy?.unrolled,
          policy?.canvasMapping,
        ),
      )
      .also {
        it.traits =
          if (remote) {
            // `RemoteAuthorable` is what a Remote slot admits, so it says "the widget exporter can
            // write this". One no other file may call (`remote-m3/theme-specimen`) every export
            // refuses (`RemoteContentEmitter.recordCall`), so it is not offered for placing there.
            val traits =
              if (component.callableFromAnotherFile) REMOTE_COMPONENT_TRAITS
              else REMOTE_COMPONENT_TRAITS - REMOTE_AUTHORABLE
            (policy?.traits.orEmpty() + traits).distinct()
          } else policy?.traits.orEmpty()
        it.slots = slots
        it.properties = properties
        it.modifierCapabilities =
          (policy?.modifierCapabilities ?: structuralModifiers(slots.isNotEmpty(), platform))
            .writableOn(platform)
        it.code =
          CodeCapabilityV1.Builder(component.symbol.callable)
            .also {
              it.imports =
                component.code?.imports.orEmpty().ifEmpty { listOf(component.symbol.callable) }
            }
            .build()
      }
      .build()
  }

  private fun UiBuilderPropertyPolicy.toCapability(): PropertyCapabilityV1 =
    PropertyCapabilityV1.Builder(name, jsonType)
      .also {
        it.required = required
        it.allowedValues = allowedValues
        it.notes = notes
      }
      .build()

  /**
   * What a component accepts when the catalog does not say: the same container/leaf split a pack
   * component gets, so a catalog that states nothing is still usable.
   */
  private fun structuralModifiers(container: Boolean, platform: String): List<String> =
    when (UiBuilderCatalogPlatform.fromWord(platform)) {
      // On Remote Compose, what `RemoteContentEmitter` writes is the whole list, leaf or container.
      UiBuilderCatalogPlatform.REMOTE_COMPOSE -> REMOTE_CONTENT_MODIFIERS.toList()
      else -> ComponentRecordPacks.structuralModifiers(container) + platformOnlyModifiers(platform)
    }

  /**
   * Modifiers a platform's emitter writes that no Compose default names (e.g. `sharedElement`,
   * `remoteCall` on Remote Compose). A catalog stating its own list is taken at its word.
   */
  private fun platformOnlyModifiers(platform: String): List<String> =
    when (UiBuilderCatalogPlatform.fromWord(platform)) {
      UiBuilderCatalogPlatform.REMOTE_COMPOSE -> REMOTE_ONLY_MODIFIERS
      else -> emptyList()
    }

  /**
   * The shelf role of a builtin: `Scaffold`, `Container` or `Leaf`.
   *
   * Not the builtin's structural `role` (the template engine's set), except that `screen-root` maps
   * to `Scaffold`. A stated `shelfRole` wins; otherwise derived from slots, which cannot tell
   * `layout/box` from `layout/scaffold`.
   */
  private fun shelfRole(builtin: UiBuilderBuiltin): String =
    when {
      // Stated role first; the derivation below is the fallback.
      builtin.shelfRole in SHELF_ROLES -> builtin.shelfRole!!
      builtin.role == "screen-root" -> "Scaffold"
      builtin.slots.isNotEmpty() -> "Container"
      else -> "Leaf"
    }

  /**
   * The shelf roles a catalog may claim. Unknown words are ignored in favour of the derivation
   * rather than refusing the whole catalog; the publisher reports
   * `policy.builtin.shelfRole.unknown`.
   */
  private val SHELF_ROLES = setOf("Scaffold", "Container", "Leaf")

  // Traits every record component of a Remote Compose catalog has by virtue of the emitter
  // (`RemoteContent`, `RemoteAuthorable`). A catalog's own `traits` are added, not replaced.
  private const val REMOTE_AUTHORABLE = "RemoteAuthorable"

  private val REMOTE_COMPONENT_TRAITS = listOf("RemoteContent", REMOTE_AUTHORABLE)

  private val REMOTE_SLOT_ROLES = listOf("Scaffold", "Container", "Leaf")

  private val REMOTE_SLOT_TRAITS = listOf("RemoteAuthorable")

  /**
   * Remote value types a design may bind to document state as well as set: `[<scalar>, "object"]`.
   * `RemoteColor` and `RemoteTextUnit` stay scalar since no design state carries them.
   */
  private val REMOTE_STATE_TYPES =
    setOf(
      "androidx.compose.remote.creation.compose.state.RemoteString",
      "androidx.compose.remote.creation.compose.state.RemoteBoolean",
      "androidx.compose.remote.creation.compose.state.RemoteFloat",
      "androidx.compose.remote.creation.compose.state.RemoteInt",
    )

  /**
   * A builtin, as a component. It has no call site, so everything comes from policy; it carries
   * [CodeCapabilityV1] only when the policy points at a wrapper in the catalog's record.
   */
  private fun builtinCapability(
    id: String,
    builtin: UiBuilderBuiltin,
    platform: String,
    implementation: ComponentRecord?,
  ): ComponentCapabilityV1 =
    ComponentCapabilityV1.Builder(
        id,
        builtin.displayName ?: id.substringAfterLast('/'),
        shelfRole(builtin),
        wasm(builtin.canvas, nativeOnly = false, callable = null, unrolled = builtin.unrolled)
          .overriddenBy(builtin.wasm),
      )
      .also {
        it.traits = builtin.traits
        it.slots =
          builtin.slots.map { (name, slot) ->
            SlotCapabilityV1.Builder(
                name,
                SlotCardinalityV1.Builder()
                  .also {
                    it.min = if (slot.required) 1 else 0
                    it.max = slot.max
                  }
                  .build(),
                slot.ordered,
              )
              .also {
                it.acceptedRoles = slot.acceptedRoles
                it.acceptedTraits = slot.acceptedTraits
              }
              .build()
          }
        it.properties = builtin.properties.orEmpty().map { it.toCapability() }
        it.modifierCapabilities =
          (builtin.modifierCapabilities
              ?: structuralModifiers(builtin.slots.isNotEmpty(), platform))
            .writableOn(platform)
        it.code =
          implementation?.let { record ->
            CodeCapabilityV1.Builder(record.symbol.callable)
              .also {
                it.imports =
                  record.code?.imports.orEmpty().ifEmpty { listOf(record.symbol.callable) }
              }
              .build()
          }
            ?: builtin.code?.let { code ->
              CodeCapabilityV1.Builder(code.symbol)
                .also { builder -> builder.imports = code.imports }
                .build()
            }
        it.svg = builtin.svg?.toCapability()
      }
      .build()

  /**
   * The stated SVG block as a capability, or null where it names a word this build cannot decode
   * (an unknown enum word would fail the whole capability document).
   */
  private fun UiBuilderBuiltinSvg.toCapability(): SvgCapabilityV1? {
    val status = SVG_STATUSES[status] ?: return null
    val fallback = SVG_FALLBACKS[fallback] ?: return null
    return SvgCapabilityV1.Builder(status, fallback, blocksExport).also { it.notes = notes }.build()
  }

  private val SVG_STATUSES =
    mapOf(
      "verified" to SvgCapabilityStatusV1.VERIFIED,
      "unverified" to SvgCapabilityStatusV1.UNVERIFIED,
      "raster-fallback-required" to SvgCapabilityStatusV1.RASTER_FALLBACK_REQUIRED,
      "unsupported" to SvgCapabilityStatusV1.UNSUPPORTED,
    )

  private val SVG_FALLBACKS =
    mapOf("none" to SvgFallbackV1.NONE, "embedded-raster" to SvgFallbackV1.EMBEDDED_RASTER)

  /**
   * The derived canvas-lane block with the catalog's stated fields laid over it, field by field, so
   * stating one field is not a claim about the others.
   */
  private fun WasmCapabilityV1.overriddenBy(stated: UiBuilderBuiltinWasm?): WasmCapabilityV1 {
    if (stated == null) return this
    return newBuilder()
      .also {
        it.platformSupported = stated.platformSupported ?: platformSupported
        // An unknown status keeps the derived one: an undecodable word fails the whole document.
        it.adapterStatus = ADAPTER_STATUSES[stated.adapterStatus] ?: adapterStatus
        it.notes = stated.notes ?: notes
      }
      .build()
  }

  private val ADAPTER_STATUSES =
    mapOf(
      "supported" to WasmAdapterStatusV1.SUPPORTED,
      "planned" to WasmAdapterStatusV1.PLANNED,
      "unsupported" to WasmAdapterStatusV1.UNSUPPORTED,
    )

  private fun wasm(
    canvas: String?,
    nativeOnly: Boolean,
    callable: String?,
    unrolled: UiBuilderUnrolledMock? = null,
    canvasMapping: CanvasAdapterMappingV1? = null,
  ): WasmCapabilityV1 {
    val drawn = !nativeOnly && canvas != null && canvas != PLACEHOLDER_CANVAS
    return WasmCapabilityV1.Builder(
        platformSupported = JsonPrimitive(drawn),
        adapterStatus =
          if (drawn) WasmAdapterStatusV1.SUPPORTED else WasmAdapterStatusV1.UNSUPPORTED,
      )
      .also {
        it.notes =
          when {
            nativeOnly ->
              "Rendered only on the native lane; the canvas draws a named placeholder." +
                (callable?.let { " The native preview compiles `$it`." } ?: "")
            drawn -> "Drawn on the canvas by the `$canvas` adapter."
            else -> "Drawn on the canvas as a named placeholder: this catalog claims no adapter."
          }
        it.canvas = canvas
        it.unrolled = unrolled?.toContract()
        it.canvas = canvas
        it.canvasMapping = canvasMapping
      }
      .build()
  }

  /** The catalog's declaration as the wire carries it, nested under the `wasm` block. */
  private fun UiBuilderUnrolledMock.toContract() =
    UnrolledMockV1.Builder(layout)
      .also {
        it.cellWidthDp = cellWidthDp
        it.spacingDp = spacingDp
      }
      .build()

  /**
   * The modifiers this platform's emitter can write, or null where every default is writable.
   *
   * A shelf must never offer what an export then refuses. The structural default is a Jetpack
   * Compose list (`testTag`, `aspectRatio`, `shadow`) that `RemoteContentEmitter` cannot write.
   * Stated `modifierCapabilities` are narrowed too.
   */
  private fun writableModifiers(platform: String): Set<String>? =
    when (UiBuilderCatalogPlatform.fromWord(platform)) {
      UiBuilderCatalogPlatform.REMOTE_COMPOSE -> REMOTE_CONTENT_MODIFIERS
      else -> null
    }

  private fun List<String>.writableOn(platform: String): List<String> {
    val writable = writableModifiers(platform) ?: return this
    return filter { it in writable }
  }

  /**
   * The revision this catalog is pinned by: a digest of the published bytes, so hosts fetching the
   * same file agree on the pin without coordinating.
   */
  private fun revisionOf(published: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(published.toByteArray())
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }.take(32)
  }

  /**
   * The share of a record's components that may collide before the whole file is refused.
   *
   * Deliberately loose: correct catalogs collide on zero, broken ones on most. If a real catalog
   * trips this, fix its ids via `statusSemantics.components` rather than raising it.
   */
  private const val COLLISION_REFUSAL_RATE = 0.10

  /**
   * The type names a design's value can be checked against; mirrors the private
   * `JsonElement.accepts` in `ProductionUiBuilderRuntime`. Adding a name there means adding it
   * here; the equivalence test catches drift.
   */
  private val SUPPORTED_JSON_TYPES =
    setOf("null", "string", "boolean", "number", "integer", "array", "object")

  private const val UI_BUILDER_CATALOG_SCHEMA = "compose-ui-builder-catalog/v1"
  private const val CAPABILITY_SCHEMA = "compose-catalog-capabilities/v1"
  private const val UI_BUILDER_CATALOG_FILE_NAME = "ui-builder.json"
  private const val PLACEHOLDER_CANVAS = "placeholder"

  /**
   * What a published catalog claims of the native lane: the same `candidate` every synthesised
   * catalog declares.
   */
  private const val NATIVE_RUNTIME_ID = "candidate"

  // The wire shape of `ui-builder.json`, as this reader needs it. A narrow mirror rather than the
  // generator's type, which `checkUiBuilderRuntimeBoundary` keeps off this classpath.

  @Serializable
  private data class PublishedFile(
    val schema: String = "",
    val catalog: PublishedIdentity = PublishedIdentity(),
    val record: PublishedRecordRef? = null,
    val statusSemantics: UiBuilderStatusSemantics = UiBuilderStatusSemantics(),
  )

  @Serializable private data class PublishedIdentity(val id: String = "", val title: String = "")

  @Serializable
  private data class PublishedRecordRef(val file: String? = null, val components: Int = 0)

  @Serializable
  internal data class UiBuilderStatusSemantics(
    val componentIdPrefix: String = "",
    /**
     * The catalog's lane — `mobile`, `wear`, `remote-compose`. Used to narrow modifiers; see
     * [writableModifiers].
     */
    val platform: String = "",
    val browserPreview: BrowserPreviewCapabilityV1? = null,
    val composeSourceExport: ComposeSourceExportCapabilityV1? = null,
    val builtins: Map<String, UiBuilderBuiltin> = emptyMap(),
    val components: Map<String, UiBuilderComponentPolicy> = emptyMap(),
  )

  @Serializable
  internal data class UiBuilderBuiltin(
    val role: String = "",
    val displayName: String? = null,
    val canvas: String? = null,
    /**
     * The editing canvas's mock for this builtin. The wire nests it under `wasm`; the policy states
     * it beside the adapter word.
     */
    val unrolled: UiBuilderUnrolledMock? = null,
    val traits: List<String> = emptyList(),
    val slots: Map<String, UiBuilderBuiltinSlot> = emptyMap(),
    /**
     * A builtin has no record, so this is its only source. Named `properties` (not
     * `propertyCapabilities`) because that is what `ui-builder.policy.schema.json` spells it.
     */
    val properties: List<UiBuilderPropertyPolicy>? = null,
    /** See [UiBuilderComponentPolicy.modifierCapabilities]. */
    val modifierCapabilities: List<String>? = null,
    /**
     * Optional canonical id of a catalog-shipped wrapper callable for generic Compose export.
     * Absent or unresolved, the builtin degrades to a declaration-only placeholder. Data, not
     * executable Wasm.
     */
    val implementation: String? = null,
    /**
     * The stated shelf role; see [shelfRole]. Distinct from the structural [role]; a capability
     * document serves this field as `role`.
     */
    val shelfRole: String? = null,
    /**
     * The canvas lane, overriding what [canvas] implies. Every field is nullable; this is the only
     * way to state `planned`, which the derivation never produces.
     */
    val wasm: UiBuilderBuiltinWasm? = null,
    /**
     * The callable this builtin exports as, when no record entry can name it (e.g. foundation
     * symbols). [implementation] is preferred where it exists.
     */
    val code: UiBuilderBuiltinCode? = null,
    /** What a structured-SVG export makes of this builtin. See [svg]. */
    val svg: UiBuilderBuiltinSvg? = null,
  )

  /** The canvas-lane block a builtin may state, as `ui-builder.policy.schema.json` spells it. */
  @Serializable
  internal data class UiBuilderBuiltinWasm(
    val platformSupported: JsonElement? = null,
    val adapterStatus: String? = null,
    val notes: String? = null,
  )

  /**
   * The layout the editing canvas draws for a component while an author is inside it, so children
   * of a scrollable container past the frame edge stay editable. Constrained surfaces (preview,
   * frames, exports) never see it. Unknown `layout` names are carried, not refused.
   */
  @Serializable
  internal data class UiBuilderUnrolledMock(
    val layout: String,
    val cellWidthDp: JsonElement? = null,
    val spacingDp: JsonElement? = null,
  )

  /** The export call a builtin may state. */
  @Serializable
  internal data class UiBuilderBuiltinCode(
    val symbol: String,
    val imports: List<String> = emptyList(),
  )

  /** What a structured-SVG export makes of a builtin. */
  @Serializable
  internal data class UiBuilderBuiltinSvg(
    val status: String,
    val fallback: String,
    val blocksExport: Boolean = false,
    val notes: String? = null,
  )

  /**
   * One slot a builtin declares, as `ui-builder.policy.schema.json` spells it. `role` is the
   * structural role children are written with, not the shelf's, so it is not `acceptedRoles`.
   */
  @Serializable
  internal data class UiBuilderBuiltinSlot(
    val acceptedRoles: List<String> = emptyList(),
    val acceptedTraits: List<String> = emptyList(),
    val required: Boolean = false,
    /** The most children this slot admits; null (the default) is unbounded. */
    val max: Int? = null,
    val role: String? = null,
    /** Whether child order is meaningful. Defaults to `true`, as before the field existed. */
    val ordered: Boolean = true,
  )

  @Serializable
  internal data class UiBuilderComponentPolicy(
    /** The record's `canonicalId` — the join back to the inventory. */
    val record: String = "",
    @SerialName("catalogId") val catalogId: String? = null,
    val displayName: String? = null,
    val canvas: String? = null,
    val canvasMapping: CanvasAdapterMappingV1? = null,
    /** See [UiBuilderUnrolledMock]. */
    val unrolled: UiBuilderUnrolledMock? = null,
    val nativeOnly: Boolean = false,
    val traits: List<String> = emptyList(),
    val excluded: String? = null,
    /**
     * The stated shelf role — `Scaffold`, `Container` or `Leaf` — with the same unknown-word
     * fallback as [UiBuilderBuiltin.shelfRole]. Served as `role`.
     */
    val shelfRole: String? = null,
    /**
     * The properties this component offers a design, or null to derive them from the record.
     *
     * A catalog's vocabulary is not its parameter list (e.g. `m3/button` offers `style`, not
     * `onClick`), so a catalog replacing a synthesised shelf states this.
     */
    val propertyCapabilities: List<UiBuilderPropertyPolicy>? = null,
    /**
     * The slots this component offers a design, or null to derive them from the record. Derived
     * slots accept anything and follow parameter names, so curated catalogs state these.
     */
    val slotCapabilities: List<UiBuilderSlotPolicy>? = null,
    /**
     * The modifiers this component accepts, or null for the structural default. The runtime rejects
     * undeclared modifiers, and the right list is editorial, not inferable.
     */
    val modifierCapabilities: List<String>? = null,
  )

  /**
   * One slot a catalog states, mirroring `SlotCapabilityV1`. Policy lists are named `…Capabilities`
   * because the generator's `UiBuilderComponentPolicy` already uses `slots`/`properties` for other
   * types.
   */
  @Serializable
  internal data class UiBuilderSlotPolicy(
    val name: String,
    val cardinality: UiBuilderSlotCardinality = UiBuilderSlotCardinality(),
    val ordered: Boolean = false,
    val acceptedRoles: List<String> = emptyList(),
    val acceptedTraits: List<String> = emptyList(),
  )

  /** How many children a stated slot takes; `max` null is unbounded. */
  @Serializable internal data class UiBuilderSlotCardinality(val min: Int = 0, val max: Int? = null)

  @Serializable
  internal data class UiBuilderPropertyPolicy(
    val name: String,
    val jsonType: JsonElement,
    val required: Boolean = false,
    val allowedValues: List<JsonElement> = emptyList(),
    val notes: String = "",
  )
}
