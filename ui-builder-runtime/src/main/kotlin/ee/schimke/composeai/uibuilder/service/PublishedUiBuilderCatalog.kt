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
 * A catalog's published `ui-builder.json` (the POLICY: platform, shelves, frame, templates, editor
 * knowledge) composed with its `components.json` (the INVENTORY) into the capability catalog the
 * builder serves; the read half of `docs/design/UI_BUILDER_CATALOG_CONTRACT.md`, joined on
 * [UiBuilderComponentPolicy.record]'s `canonicalId`.
 *
 * Policy is read from the published file even though the record carries a copy: a catalog need not
 * have a record here at all, and `statusSemantics.components` is published for exactly this reader.
 * The two agree by construction.
 *
 * It invents nothing: a component with no policy is admitted with the record's defaults (see
 * [UiBuilderStatusSemantics.componentIdPrefix]). `.github/scripts/ui-builder-equivalence.sh` and
 * the frozen goldens prove the composition equals what the server synthesises.
 *
 * It lives here, beside the runtime that serves what it composes, rather than in
 * compose-preview-server, where it started. There, its tests pinned this repository's exporter and
 * catalog behaviour, so every change here turned that repository's checkout build red until a
 * release shipped and its pin moved. [compose] and [Result] are the whole public surface the host
 * needs.
 */
public object PublishedUiBuilderCatalog {

  /** The outcome of composing one catalog, which is never an exception. */
  public sealed interface Result {
    /**
     * [catalog] is ready to serve; [note] is the one startup line saying where it came from.
     *
     * [records] is each component of [catalog] that came from the record, under the builder id it
     * is served as — the join this composition performs and nothing else can. A design node names a
     * builder id; only the published file states which record component that id was derived from,
     * so a reader handed the record alone would have to re-run [derivedId] and the policy's
     * `record` field to get back here. Carried rather than re-derived, because two implementations
     * of one derivation is how a saved design and the code generated for it come to disagree.
     *
     * A declaration-only builtin is absent. A builtin carrying a catalog-shipped implementation
     * record is present under its builder id, so the generic exporter can call the wrapper without
     * knowing that id or symbol in advance.
     */
    public data class Composed(
      public val catalog: CatalogCapabilityV1,
      public val note: String,
      public val records: Map<String, ComponentRecord>,
    ) : Result

    /**
     * The published file could not be used, and [reason] says why in a form an operator can act on.
     *
     * Never fatal by itself. A catalog whose published file will not compose falls back to whatever
     * the server can synthesise, exactly as one that publishes nothing does — the cutover is per
     * catalog and reversible, and a host that refused to start over another repository's bad
     * publish would be down until that repository's CI ran again.
     */
    public data class Unusable(public val reason: String) : Result
  }

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = false
  }

  /**
   * Compose [publishedJson] with [record] into a capability catalog.
   *
   * [record] may be null: a catalog that publishes policy and no inventory is still a catalog — one
   * whose components are all builtins — and refusing it here would be refusing the simplest thing
   * the contract can express.
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
      // A major this reader does not know is refused by name rather than read optimistically. The
      // fallback keeps the catalog served, so the cost of refusing is a stale shelf and a line
      // saying so, and the cost of guessing is a shelf that silently means something else.
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

    // A `jsonType` the design validator can read, and a slot cardinality that can be satisfied,
    // or the file is refused.
    //
    // `PropertyCapabilityV1.jsonType` is a free-form `JsonElement`, and the runtime's
    // `JsonElement.accepts` reads it as `jsonPrimitive.content` — or, for an array, each entry's.
    // So `"jsonType": {}`, or `["string", 7]`, decodes here and THROWS there, while a design is
    // being written. That is the worst shape a bad catalog can take: not a shelf that refuses to
    // load, but an authoring path that crashes on save.
    //
    // BUILTINS are checked alongside components, and were not on the first cut of this — the same
    // "some of the places" this file keeps being corrected for. A builtin's properties reach
    // `builtinCapability` by the identical route and are read by the identical validator.
    //
    // Cardinality is here rather than in the runtime's `validateCatalog` because that function
    // requires `catalogSystemId == "m3-catalog"` and so can only ever see the packaged catalog. A
    // published one reaches the shelf unvalidated, and `max < min` makes every child count invalid
    // — a component nobody can author, on a shelf that loaded cleanly.
    val unreadable = mutableListOf<String>()
    fun checkProperties(owner: String, properties: List<UiBuilderPropertyPolicy>) {
      // Unique names, because `CapabilityCatalogParser.validateCatalogShape` requires them and
      // THROWS while installing the catalog — so a duplicate does not degrade the editor, it stops
      // it opening, which is the one outcome the per-catalog fallback exists to prevent.
      properties
        .groupBy { it.name }
        .filterValues { it.size > 1 }
        .keys
        .forEach { unreadable += "$owner.$it (declared twice)" }
      for (property in properties) {
        when (val type = property.jsonType) {
          is JsonArray -> {
            // `[]` is a union of nothing: no value satisfies it, so a required property declaring
            // it is a component nobody can author. `all {}` is vacuously true, which is how it got
            // past the first cut of this check.
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
        // The EDITOR seeds a new node from the first allowed value and reads it as a primitive, so
        // a non-primitive one is a component that cannot be inserted. Refused rather than
        // stringified: a summary can degrade, an authoring path cannot.
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
    // The rule `checkSlots` applies to a component's slots, over the pair a builtin derives:
    // `required` sets the minimum, `max` bounds it above. This could not fail until a builtin
    // could state `max` — the minimum is 0 or 1 and the maximum was always unbounded — which is
    // why the builtins loop below checked only properties. Now that the bound is writable, an
    // impossible one reaches the shelf the same way a component's would, and the paragraph above
    // says what that costs: a component nobody can author, on a catalog that loaded cleanly.
    //
    // No duplicate-name arm, unlike its sibling: a builtin's slots are a map, so the wire cannot
    // carry the same name twice.
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
    // The same join as `taken`, kept as the records rather than the capabilities. See
    // [Result.Composed.records].
    val recordsById = linkedMapOf<String, ComponentRecord>()
    val recordsByCanonicalId =
      record?.components.orEmpty().associateBy(ComponentRecord::canonicalId)
    val skipped = mutableListOf<String>()
    // Counted apart from the rest of `skipped`, because a collision means something the other skip
    // reasons do not: two components claimed one identity. See [COLLISION_REFUSAL_RATE].
    var collisions = 0
    // Entries that actually competed for an identity. An excluded component never enters the shelf,
    // so counting it in the denominator lets a policy excluding most of its record hide a shelf
    // where everything left collides: 100 entries, 90 excluded, the remaining 10 all deriving one
    // id is 9 collisions against an allowance of 10 — composed, with a one-component shelf.
    var eligible = 0
    // How many record components the published file actually recognised. Zero is the
    // mismatched-pair
    // case refused below; it is counted here rather than derived afterwards because an excluded
    // entry joined too, and a file whose every entry is excluded still described this record.
    var joined = 0

    record?.components.orEmpty().forEach { component ->
      val declared = policyByRecordId[component.canonicalId]
      if (declared != null) joined++
      // A record entry answering ONLY to the builder's own namespaces is not this catalog's
      // component, and deriving an id for it publishes a second, wrong one.
      //
      // `layout/column` is `androidx.compose.foundation`'s, and the shelf gets it from
      // `ProductionUiBuilderRuntime.withBuilderVocabulary`, which unions the builder's own
      // `layout/`, `shape/` and `asset/` components into every published catalog. Left in this
      // loop, the same component ALSO derives `<prefix>column` here — so the palette offers
      // `layout/column` and `<prefix>column` side by side, a design can be saved against the
      // second, and the second vanishes the day the record stops carrying it.
      //
      // Only where the policy says nothing. A catalog that deliberately publishes a component
      // claiming one of these ids states it in `statusSemantics.components`, and a statement is
      // what this whole file defers to; `declared != null` is that statement.
      //
      // `componentIds.isNotEmpty()` is load-bearing: an entry claiming NO id is reached by
      // canonical id instead (`ElevatedCard`, `OutlinedCard` — see `ScreenDocumentProjection`), and
      // an `all {}` over an empty list is true, which would drop both.
      if (declared == null && component.isBuilderVocabulary()) return@forEach
      val policy = declared?.second
      val componentId = declared?.first ?: derivedId(prefix, component)
      val excluded = policy?.excluded
      when {
        excluded != null -> skipped += "$componentId — $excluded"
        // Everything below this arm competed for `componentId`, so everything below counts.
        // A duplicate id is the catalog's to fix and is reported rather than resolved: picking a
        // winner silently would bind saved designs to whichever entry happened to sort first.
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
    // Collisions in bulk mean the ids are not identities.
    //
    // A single collision is one catalog bug and the shelf around it is still the right shelf, so it
    // is skipped and reported. A large fraction is a different claim: the file is not naming
    // components, and what survives is whichever entry happened to be walked first. The case this
    // was written from published a policy declaring no `components`, so every id fell to
    // `derivedId`, and its `componentIds` were a `Group/Variant` taxonomy whose LEAF is the
    // variant. One variant word was claimed by 15 components. 63 of 104 collided, and the 41
    // survivors shared exactly ONE id with the catalog this server synthesises.
    //
    // That is the shape this refusal is for, and note what did NOT catch it: the composition
    // produced 41 components against a frozen shelf of 41, so any check comparing counts reports a
    // match. Only the ids say otherwise.
    // `maxOf(1, …)` rather than the bare rate, so ONE collision never refuses whatever the record's
    // size. On a two-component record a single collision is 50% and would have tripped a plain
    // rate — which contradicts the paragraph above, and did: it broke
    // `PublishedUiBuilderCatalogHostileInputTest`'s two-component collision fixture, which expects
    // a skip. The rate is for the bulk case; the floor keeps the stated "one is a catalog bug"
    // true at every scale.
    val allowed = maxOf(1, (eligible * COLLISION_REFUSAL_RATE).toInt())
    if (eligible > 0 && collisions > allowed) {
      return Result.Unusable(
        "$collisions of $eligible eligible record components collided on an already-taken " +
          "component id, leaving ${taken.size} — the published file is not naming components " +
          "distinctly, so which one survives is an accident of record order"
      )
    }
    // The file says how many record components it was generated against. If it expected an
    // inventory and none arrived, this composed to its builtins alone — which does not fail, it
    // quietly serves a near-empty shelf in place of whatever the server would otherwise offer.
    // Refusing is the safe direction: the fallback keeps the catalog whole, and the reason names
    // the missing file rather than leaving an operator to notice that a shelf got shorter.
    val expected = file.record?.components ?: 0
    if (expected > 0 && record == null) {
      return Result.Unusable(
        "ui-builder.json was generated against a $expected-component record and none is available " +
          "here, so it would compose to its builtins alone"
      )
    }
    // A record arrived, for a different catalog.
    //
    // `policyByRecordId` joins on the canonical id the published file states for each component,
    // and nothing downstream notices when that join finds nothing. Every component falls to
    // [derivedId] with a null policy, keeps the id a saved design names it by, and is served the
    // vocabulary DERIVED FROM ITS CALL SITE instead of the one the catalog declared — a shelf of
    // roughly the right shape, under the right names, offering the wrong properties. None of the
    // refusals above sees it: nothing collides, an inventory did arrive, and components compose.
    //
    // That is what a deployed `m3-catalog` host served. The published file joins on
    // `catalog/androidx.compose.material3.TextKt.Text`; the record staged into the image
    // canonicalises the same callable as `m3-catalog/…`. So `m3/text` composed to
    // `text, softWrap, maxLines, minLines` — every `style`, `color`, `fontWeight` and `weight` a
    // saved design had been authored against dropped by [ComponentRecordPacks.jsonTypeOf] for want
    // of a policy to state them — and `m3/surface` and `m3/card` composed to no properties at all.
    // Every design using them opened with `property style is not declared by m3/text`.
    //
    // Refused rather than repaired. Which record a published file describes is the producer's to
    // state, and matching on a suffix here would make this reader a second opinion about what a
    // component is called — the drift the stated join exists to prevent. The fallback keeps the
    // catalog the server synthesises, which still declares the vocabulary those designs use.
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
   * `layout/`, `shape/` and `asset/` — the namespaces the BUILDER owns rather than any catalog.
   *
   * Stated here rather than shared with `ui-builder-runtime`'s `BUILDER_NAMESPACES`, which is
   * `internal` to that module. The two must agree, and the paragraph in [compose] that uses this
   * says which mechanism on the other side it is agreeing with.
   */
  private val BUILDER_VOCABULARY_NAMESPACES = listOf("layout/", "shape/", "asset/")

  /** Whether this record entry answers only to ids the builder owns; see [compose]. */
  private fun ComponentRecord.isBuilderVocabulary(): Boolean =
    componentIds.isNotEmpty() &&
      componentIds.all { id -> BUILDER_VOCABULARY_NAMESPACES.any(id::startsWith) }

  /**
   * The builder id of a record component the published file says nothing about.
   *
   * Reproduces the generator's derivation, which is the one place this reader and the producer have
   * to agree without a field to agree through: an unannotated component is deliberately absent from
   * `statusSemantics.components` and still belongs on the shelf, so its id must be DERIVABLE. The
   * leaf is the component's own catalog id where it has one, because that is the name its author
   * chose; the equivalence gate is what proves the two derivations still match.
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
   * Splits on a lower-to-upper boundary and on any run of non-alphanumerics; a run of capitals is
   * one word, because splitting it letter by letter produces ids nobody would type. The same rule
   * the generator applies, stated here because a derived id is a saved design's identity and the
   * two sides must not drift.
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
    // The catalog's slots, or failing that the composable's. See
    // `UiBuilderComponentPolicy.slotCapabilities`.
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
            .build()
        }
    val slotNames = slots.map { it.name }.toSet()
    // What the catalog says it offers, and only failing that what its call site happens to take.
    // See `UiBuilderComponentPolicy.propertyCapabilities` for why the two are not the same
    // question.
    val derivedProperties =
      component.parameters
        .filterNot { it.composableSlot || it.name in slotNames }
        .mapNotNull { parameter ->
          val jsonType = ComponentRecordPacks.jsonTypeOf(parameter) ?: return@mapNotNull null
          PropertyCapabilityV1.Builder(
              ComponentRecordPacks.propertyNameOf(parameter),
              JsonPrimitive(jsonType),
            )
            .also {
              it.required = !parameter.hasDefault && !parameter.nullable
              it.notes = "`${parameter.name}: ${parameter.type}` on `${component.symbol.callable}`."
            }
            .build()
        }
    // Authored policy is the exception vocabulary — builder state, layout participation, roles on
    // another class — and overrides a convention of the same name. It no longer has to repeat the
    // callable's scalar vocabulary merely because it needs to add one exceptional property.
    val authoredProperties = policy?.propertyCapabilities?.map { it.toCapability() }.orEmpty()
    val authoredNames = authoredProperties.mapTo(mutableSetOf()) { it.name }
    val properties = authoredProperties + derivedProperties.filter { it.name !in authoredNames }
    return ComponentCapabilityV1.Builder(
        componentId,
        policy?.displayName ?: component.symbol.name,
        if (slots.isNotEmpty()) "Container" else "Leaf",
        wasm(
          policy?.canvas,
          policy?.nativeOnly == true,
          component.symbol.callable,
          policy?.unrolled,
          policy?.canvasMapping,
        ),
      )
      .also {
        it.traits = policy?.traits.orEmpty()
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
   * What a component accepts when the catalog does not say.
   *
   * Deliberately NOT an attempt to reproduce a frozen catalog's editorial list — those differ by
   * component in ways nothing structural predicts. This is the honest fallback: the same
   * container/leaf split a pack component already gets, so a catalog that states nothing is usable
   * rather than inert. A catalog replacing a synthesised shelf states `modifiers` and does not
   * reach this.
   */
  private fun structuralModifiers(container: Boolean, platform: String): List<String> =
    ComponentRecordPacks.structuralModifiers(container) + platformOnlyModifiers(platform)

  /**
   * The modifiers a platform's emitter writes that no Compose default could name, offered by
   * default on that platform the way [writableModifiers] narrows it.
   *
   * The structural default is a Compose list, so a published `remote-compose` component that states
   * no `modifiers` lost every Remote-only one its synthesised twin offers — `sharedElement`,
   * `animateEnterExit`, `remoteCall`, `collapsiblePriority` — and a catalog-owned remote-m3 could
   * not animate a switch between states at all. Which modifiers an emitter writes is the server's
   * fact about its own emitters, so it is added here rather than asked of every catalog; a catalog
   * that states its own list is taken at its word.
   */
  private fun platformOnlyModifiers(platform: String): List<String> =
    when (UiBuilderCatalogPlatform.fromWord(platform)) {
      UiBuilderCatalogPlatform.REMOTE_COMPOSE -> REMOTE_ONLY_MODIFIERS
      else -> emptyList()
    }

  /**
   * The shelf role of a builtin: `Scaffold`, `Container` or `Leaf`.
   *
   * Two different words are spelled `role` around here and they are not the same vocabulary. A
   * builtin's own `role` is the STRUCTURAL one — the template engine's closed set (`screen-root`,
   * `list`, `list-item`, `container`, `overlay`, `controlled`, `decoration`) — which says which
   * template writes it. The shelf's is `Scaffold` / `Container` / `Leaf`, which decides what the
   * editor calls it and which slots will take it.
   *
   * The structural one was read and then dropped, and the shelf role derived from whether there
   * were slots at all. That makes a design ROOT — a Wear catalog's `widget-container-small`, whose
   * synthesised twin in `ProductionUiBuilderRuntime.widget()` is a `Scaffold` — arrive as an
   * ordinary `Container`. `screen-root` is the one structural role that names a scaffold outright,
   * so it is the one that maps; every other builtin keeps the derivation, because `list` and
   * `container` say how a thing is WRITTEN and not what shape it is on the shelf.
   *
   * A catalog can now say it outright, and what it says wins. The derivation stays for everything
   * published before the field existed, and stays wrong in the same ways: nothing structural tells
   * `layout/box` from `layout/scaffold`, which both have one slot named `content`.
   */
  private fun shelfRole(builtin: UiBuilderBuiltin): String =
    when {
      // What the catalog SAYS, first. The derivation below is a fallback for a catalog that says
      // nothing, and the field exists because the derivation is wrong in ways nothing structural
      // predicts: `layout/box` and `layout/scaffold` both have one slot named `content`.
      builtin.shelfRole in SHELF_ROLES -> builtin.shelfRole!!
      builtin.role == "screen-root" -> "Scaffold"
      builtin.slots.isNotEmpty() -> "Container"
      else -> "Leaf"
    }

  /**
   * The shelf roles a catalog may claim.
   *
   * A word outside this set names no shelf, so it is ignored in favour of the derivation rather
   * than served: an unknown role reaches the editor as a component it has no name for, and the
   * derived answer — which is what every catalog published before the field existed — is at worst
   * wrong in a way the editor can still render. The publishing side reports it as a diagnostic
   * (`policy.builtin.shelfRole.unknown`); refusing the catalog here would take a whole palette off
   * a shelf over one misspelled word, which is the trade this contract settles the other way
   * everywhere else.
   */
  private val SHELF_ROLES = setOf("Scaffold", "Container", "Leaf")

  /**
   * A builtin, as a component.
   *
   * A builtin exists because it has NO call site — there is nothing in the record to discover — so
   * everything it offers comes from the policy. It carries [CodeCapabilityV1] only when the policy
   * points at a wrapper in the catalog's own record; otherwise the templates named by its role are
   * what write it.
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
   * How the canvas draws this component.
   *
   * The catalog's `canvas` word is an ADAPTER ID, and a build that lacks the adapter draws a named
   * placeholder rather than nothing — the same honest shape a pack component already uses. Which
   * adapters exist is the renderer's business, so this reports what the catalog asked for and lets
   * the canvas resolve it; a catalog naming an adapter this build has never heard of degrades to a
   * placeholder instead of failing to load.
   */
  /**
   * The stated SVG block as a capability, or null where it names a word this build cannot decode.
   *
   * `status` and `fallback` are closed enums on the wire, and a capability document carrying a word
   * the builder cannot decode fails the WHOLE document rather than one field. So an unknown word
   * drops the block back to what a catalog that stated nothing gets, which is what every catalog
   * got before it could state this at all — the same bargain the adapter status takes.
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
   * The derived canvas-lane block with whatever the catalog stated laid over it, field by field.
   *
   * Field by field rather than all or nothing, because the three answer different questions and a
   * catalog rarely knows all three: `layout/box` states `planned`, which the derivation cannot
   * produce, while its platform support is exactly what the adapter id implies. Replacing the whole
   * block would make stating one field a claim about the other two.
   */
  private fun WasmCapabilityV1.overriddenBy(stated: UiBuilderBuiltinWasm?): WasmCapabilityV1 {
    if (stated == null) return this
    return newBuilder()
      .also {
        it.platformSupported = stated.platformSupported ?: platformSupported
        // An unknown status keeps the derived one rather than failing the load. A capability
        // document carrying a word the builder cannot decode fails the WHOLE document, not one
        // field, so a typo in one builtin would cost the catalog its entire palette.
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
   * The modifiers this platform's emitter can actually write, or null where every modifier the
   * structural default offers is writable.
   *
   * A shelf must never offer what an export then refuses — the invariant
   * `RemoteM3VocabularyParityTest` holds the synthesised palette to, because a control you can
   * apply and cannot export is worse than one that is missing: the author finds out at the end,
   * with the design already built.
   *
   * The structural default is the server's own invention (`ComponentRecordPacks`), and it is a
   * JETPACK COMPOSE default: it carries `testTag`, `aspectRatio` and, for a container, `shadow`.
   * `RemoteContentEmitter` writes none of the three, so a published `remote-compose` catalog
   * advertised three controls whose use made every export of that design fail
   * (compose-preview-server#674 blocker 3).
   *
   * Keyed on the platform the CATALOG declares rather than on anything about the catalog itself:
   * which modifiers an emitter can write is the server's fact about its own emitters, and the
   * platform word is the catalog saying which emitter it is for.
   *
   * A stated `modifierCapabilities` is narrowed too, not only the default. A catalog naming a
   * modifier its own lane cannot write is the same defect written by hand, and the honest shelf is
   * the same either way.
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
   * The revision this catalog is pinned by: a digest of the published bytes.
   *
   * A design pins the catalog it was authored against, and the pin has to change when the catalog
   * does. A published catalog has no revision of its own to borrow — the delivery branch's commit
   * describes the whole branch, not this file — so the file's own content is what identifies it.
   * Two hosts fetching the same published file therefore agree on the pin without coordinating,
   * which a branch commit would not give.
   */
  private fun revisionOf(published: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(published.toByteArray())
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }.take(32)
  }

  /**
   * The share of a record's components that may collide on an already-taken id before the whole
   * published file is refused.
   *
   * A tenth, which is deliberately loose. The number that matters is not this threshold but the gap
   * either side of it: a catalog naming its components correctly collides on **zero**, and a
   * catalog whose ids are not identities collides on most of them — the two measured cases sit at
   * 61% and 36%. Nothing real sits near 10%, so the threshold does not have to be argued about; it
   * only has to separate a stray duplicate, which is one catalog bug worth skipping past, from a
   * file that is not describing components at all. The measurements, with the catalogs named, are
   * in `docs/design/UI_BUILDER_CATALOG_CONTRACT.md` § Phase 4 — this file may not name one.
   *
   * Raising it is not the fix if a real catalog ever trips this. The collisions are its own to
   * resolve, by declaring `statusSemantics.components` rather than leaving every id to be derived.
   */
  private const val COLLISION_REFUSAL_RATE = 0.10

  /**
   * The type names a design's value can be checked against.
   *
   * `ProductionUiBuilderRuntime`'s `JsonElement.accepts` is the list, and this mirrors it — a name
   * outside it matches nothing, so a property declaring one can never hold a value and a REQUIRED
   * property declaring one is a component nobody can author. I argued in review that an unknown
   * name degrades safely to a type mismatch; that is true for an optional property and false for a
   * required one, which is the same "impossible to author" this file already refuses a `max < min`
   * cardinality for.
   *
   * Mirrored because `accepts` is a private `when` over these names. It was written when this
   * composer lived in compose-preview-server, which could not reach it; both now sit in this
   * module, so the list could be hoisted beside `accepts` and shared. Until then, adding a name
   * there means adding it here; the equivalence test's check that every frozen catalog's declared
   * types are in this set is what makes the drift visible.
   */
  private val SUPPORTED_JSON_TYPES =
    setOf("null", "string", "boolean", "number", "integer", "array", "object")

  private const val UI_BUILDER_CATALOG_SCHEMA = "compose-ui-builder-catalog/v1"
  private const val CAPABILITY_SCHEMA = "compose-catalog-capabilities/v1"
  private const val UI_BUILDER_CATALOG_FILE_NAME = "ui-builder.json"
  private const val PLACEHOLDER_CANVAS = "placeholder"

  /**
   * What a published catalog claims of the native lane.
   *
   * `candidate` is what every catalog this server serves already declares, and a published catalog
   * is not making a stronger claim than a synthesised one; the native lane's own compile is the
   * check either way.
   */
  private const val NATIVE_RUNTIME_ID = "candidate"

  // The wire shape of `ui-builder.json`, as this reader needs it. Deliberately a narrow mirror of
  // the generator's output rather than a shared type: the generator's model lives in a
  // compose-ai-tools module `checkUiBuilderRuntimeBoundary` keeps off this classpath, and a reader
  // that decodes only what it reads cannot be broken by a field it ignores.

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
     * The word the catalog uses for its lane — `mobile`, `wear`, `remote-compose`.
     *
     * Read for one purpose: a platform whose emitter has a narrower modifier vocabulary than the
     * structural default cannot be offered the difference. See [writableModifiers].
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
     * The editing canvas's mock for this builtin, beside [canvas] rather than inside [wasm].
     *
     * The wire nests it under the component's `wasm` block, because that is the canvas-lane block
     * there; the policy states it beside the adapter word, which is the declaration it belongs with
     * — it is the same declaration for a builtin and for a record component, and the two do not
     * share a `wasm` block.
     */
    val unrolled: UiBuilderUnrolledMock? = null,
    val traits: List<String> = emptyList(),
    val slots: Map<String, UiBuilderBuiltinSlot> = emptyMap(),
    /**
     * A builtin has no record, so this is its only source.
     *
     * Named `properties`, unlike [UiBuilderComponentPolicy.propertyCapabilities] beside it, because
     * that is the name on the wire: `ui-builder.policy.schema.json` spells a builtin's list
     * `properties` with `additionalProperties: false`, and the generator's own `UiBuilderBuiltin`
     * carries it through under that name. This read `propertyCapabilities` — a name no schema-valid
     * catalog can write — so every builtin composed with zero properties and the one test that
     * covered the path wrote the server's name into its own fixture and passed. The argument for
     * the distinct `…Capabilities` names is a real one and it is about COMPONENTS, where `slots`
     * and `properties` are already taken by other types; a builtin has no such collision, and
     * copying the convention across cost the field its only writer.
     */
    val properties: List<UiBuilderPropertyPolicy>? = null,
    /** See [UiBuilderComponentPolicy.modifierCapabilities]. */
    val modifierCapabilities: List<String>? = null,
    /**
     * Optional canonical id of a catalog-shipped wrapper callable for generic Compose export.
     *
     * The declaration remains sufficient for an older server to place and placeholder-render the
     * builtin. A server that understands this field adds the embedded record to the same export
     * inventory as discovered components, so no component id or call syntax is compiled into the
     * host. A missing reference degrades to the same declaration-only placeholder older servers
     * draw. This is data with the same trust boundary as the catalog's ordinary component record,
     * not executable Wasm loaded into the browser.
     */
    val implementation: String? = null,
    /**
     * What this builtin IS on the shelf, when the catalog says rather than leaving it derived.
     *
     * See [shelfRole], which is where the derivation and the reason for it live. Spelled
     * `shelfRole` because [role] beside it is the STRUCTURAL one, and the two are different
     * vocabularies published under one word: a capability document serves this field as `role`.
     */
    val shelfRole: String? = null,
    /**
     * The canvas lane, overriding what [canvas] implies. See [wasm].
     *
     * Every field is nullable, so a catalog stating only a note keeps the derived support and
     * status. The derivation can produce `supported` and `unsupported` and never `planned`, which
     * is a claim about an adapter that is COMING rather than one that will never exist — and the
     * builder vocabulary this donor republishes is `planned` on nine of its seventeen components.
     */
    val wasm: UiBuilderBuiltinWasm? = null,
    /**
     * The callable this builtin exports as, when no record entry can name it.
     *
     * [implementation] answers the same question by pointing into the catalog's own record, and
     * stays the better answer where there is one: the record is discovered, so it cannot drift.
     * This is for the component whose call site is in neither this catalog nor any record — every
     * foundation symbol, because discovery scopes library components to material3/material/wear.
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
   * The layout the editing canvas draws for a component while an author is inside it.
   *
   * A scrollable container drawn as itself cannot show a child past the frame's edge — the ninth
   * row of a lazy column is not on the canvas and cannot be edited — so a catalog states how its
   * children are laid out while editing. The CONSTRAINED surfaces never see it: the preview pane,
   * each device frame, the native lane and every export draw the component itself.
   *
   * `layout` is the builder's vocabulary, exactly as `canvas` is, so a name this build does not
   * know is carried rather than refused: the builder resolves it against its own registry and falls
   * back to the component's own layout. The dimensions are `JsonElement`s, matching the contract.
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
   * One slot a builtin declares, as `ui-builder.policy.schema.json` spells it.
   *
   * Decoded as `Map<String, JsonElement>` before, which parsed every shape and read none: a slot
   * marked `required` composed with `min = 0`, and its `acceptedTraits` were dropped, so the rules
   * that stop a design putting a scaffold inside a widget's background slot were gone. The `role`
   * here is the STRUCTURAL role each child is written with — the template engine's closed set, not
   * the shelf's `Container`/`Leaf` — so it is carried and not turned into `acceptedRoles`.
   */
  @Serializable
  internal data class UiBuilderBuiltinSlot(
    val acceptedRoles: List<String> = emptyList(),
    val acceptedTraits: List<String> = emptyList(),
    val required: Boolean = false,
    /**
     * The most children this slot admits, or null for unbounded.
     *
     * A builtin is the only way a catalog offers a component with no call site, so what it declares
     * is all there is — and its slots could say what they accept but not how many. A host that
     * draws exactly one child composed unbounded either way, so the shelf offered a container a
     * design could put three children into while the host drew one of them.
     *
     * Absent stays unbounded, which is what every builtin slot was before this field: no existing
     * declaration acquires a bound it never asked for. `required` sets the other end.
     */
    val max: Int? = null,
    val role: String? = null,
    /**
     * Whether the order of this slot's children is meaningful.
     *
     * Composed as `true` for every builtin slot before this field, because there was nothing to
     * read — and the packaged vocabulary this donor republishes has six of its fifteen slots
     * unordered, so the shelf stated an editorial fact about six slots that their own catalog
     * denies. `true` stays the default: no existing declaration changes meaning by being reread.
     */
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
    /**
     * See [UiBuilderUnrolledMock]: the same declaration a builtin states, carried to the same
     * field.
     */
    val unrolled: UiBuilderUnrolledMock? = null,
    val nativeOnly: Boolean = false,
    val traits: List<String> = emptyList(),
    val excluded: String? = null,
    /**
     * The properties this component offers a design, or null to derive them from the record.
     *
     * A catalog's vocabulary is not its component's parameter list. `m3/button` offers `style`,
     * `selected` and `containerColor`; `androidx.compose.material3.Button` takes `onClick`,
     * `shape`, `colors`, `elevation`, `border`, `contentPadding` and `interactionSource`. Deriving
     * from the record produced the second, which is a different vocabulary rather than a different
     * spelling of the first — and made `onClick` REQUIRED, since it carries no default, so every
     * design that had ever placed a button failed validation.
     *
     * So a catalog that means to replace a synthesised shelf states this. Null keeps the derived
     * behaviour, which is right for a catalog whose components ARE their call sites.
     */
    val propertyCapabilities: List<UiBuilderPropertyPolicy>? = null,
    /**
     * The slots this component offers a design, or null to derive them from the record.
     *
     * Curated for the same reason properties are, and one level further: a catalog's slot is not
     * always its composable's parameter. `m3/list-item` offers `headline`, `supporting` and
     * `trailing`; `ListItem` takes `headlineContent`, `leadingContent`, `overlineContent`,
     * `supportingContent` and `trailingContent`. Deriving renamed three slots and invented two, and
     * because a component's role follows from whether it has any, it also turned `m3/switch` from a
     * Leaf into a Container by finding its `thumbContent`.
     *
     * The acceptance model is the other half. A derived slot accepts anything — no cardinality, no
     * `acceptedRoles`, no `acceptedTraits` — so the rules that stop a design putting a scaffold
     * inside a chip's label simply vanish.
     */
    val slotCapabilities: List<UiBuilderSlotPolicy>? = null,
    /**
     * The modifiers this component accepts, or null for the structural default.
     *
     * `ProductionUiBuilderRuntime` rejects any modifier a component does not declare, so the empty
     * list this used to pass meant no design could set `padding` on anything. It cannot be inferred
     * either: the frozen catalog gives `m3/icon` 17, `m3/text` 18 and `layout/box` 28, and the
     * difference is editorial — whether `fillMaxWidth` makes sense on an icon — not structural.
     */
    val modifierCapabilities: List<String>? = null,
  )

  /**
   * One property a catalog states, mirroring `PropertyCapabilityV1`.
   *
   * Declared here rather than reusing the protocol type because this is the AUTHORED shape: a
   * catalog writes it into `ui-builder.json`, and the protocol type is what the builder is served.
   * They agree field for field today; if the protocol gains a field the catalog cannot state, only
   * this one stays still.
   */
  /**
   * Why these three are `…Capabilities` and not `properties` / `slots` / `modifiers`.
   *
   * The generator's own `UiBuilderComponentPolicy` — compose-ai-tools, published in
   * `preview-discovery`, and the thing that WRITES the file this reads — already spells two of
   * those names for different types: a component's `slots` is a `Map<String, List<String>>` of
   * `@BuilderComponent` content hints, and a builtin's `properties` is a `List<JsonElement>`. Both
   * are empty in every catalog published today, so reusing the names would have decoded fine right
   * up until the first catalog annotated a slot, and then failed the whole file rather than one
   * field. Distinct names cost nothing and cannot collide.
   */
  /** One slot a catalog states, mirroring `SlotCapabilityV1`. See [UiBuilderPropertyPolicy]. */
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
