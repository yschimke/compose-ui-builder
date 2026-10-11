package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Does the shelf composed from a hand-built, well-formed m3-catalog published pair match the shelf
 * this server synthesises, field by field for every component the frozen catalog owns? (Ids
 * matching is not shelves matching.)
 *
 * This tests the composer, not whether m3-catalog can produce such a pair: that is
 * [PublishedGeneratedM3CatalogEquivalenceTest], against a real `composePreviewDiscover` capture.
 * Even together they are a floor, not a proof.
 *
 * Do not edit a generated file to keep a test green: the frozen and published component sets differ
 * deliberately (`UI_BUILDER_CATALOG_CONTRACT.md`). Composer refusal tests live in
 * `PublishedUiBuilderCatalogHostileInputTest`.
 */
class PublishedM3CatalogEquivalenceTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val exports =
    ExportCapabilitiesV1.Builder()
      .also {
        it.composeCode = true
        it.svg = false
        it.png = false
      }
      .build()

  /** See [PUBLISHED_CATALOG_FIXTURES]. */
  private fun fixture(name: String) = publishedCatalogFixture(name)

  private val frozen: CatalogCapabilityV1 =
    json.decodeFromString(fixture("m3-catalog-capabilities-v1.json"))

  private val composed: CatalogCapabilityV1 by lazy {
    val record = json.decodeFromString<ComponentRecordFile>(fixture("m3-catalog-record-v1.json"))
    val result =
      PublishedUiBuilderCatalog.compose(fixture("m3-catalog-published-v1.json"), record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the published pair must compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    (result as PublishedUiBuilderCatalog.Result.Composed).catalog
  }

  /**
   * The three the published catalog cannot offer, and why (yschimke/m3-catalog#317). Asserted
   * absent so one that starts composing fails this test.
   */
  private val knownAbsent =
    mapOf(
      "m3/date-picker" to "the record carries DateRangePicker but not DatePicker",
      "m3/navigation-suite-item" to "added by compose-ui-builder#241; not in this hand-built file",
      "m3/navigation-suite-scaffold" to "the same",
      "m3/primary-scrollable-tab-row" to "the same",
      "m3/snackbar-host" to "the record carries Snackbar but not SnackbarHost",
      "m3/time-picker" to "the record carries neither TimePicker nor TimeInput",
    )

  private fun frozenOwned() = frozen.components.filter { it.componentId.startsWith("m3/") }

  @Test
  fun `every component the frozen catalog owns is offered, or known absent for a stated reason`() {
    val composedIds = composed.components.map { it.componentId }.toSet()
    val missing = frozenOwned().map { it.componentId }.filterNot { it in composedIds }
    assertEquals(
      knownAbsent.keys.sorted(),
      missing.sorted(),
      "the set of components the published catalog cannot offer has changed — see m3-catalog#317",
    )
  }

  @Test
  fun `an offered component matches the frozen one field by field`() {
    val byId = composed.components.associateBy { it.componentId }
    val differences = mutableListOf<String>()
    for (expected in frozenOwned()) {
      if (expected.componentId in knownAbsent) continue
      val actual = byId[expected.componentId] ?: continue
      differences += compare(expected, actual)
    }
    // The frozen search field accepts a literal query or bound state; m3-catalog's policy says
    // `object` (narrower, not wrong). Mirrored in `PublishedGeneratedM3CatalogEquivalenceTest`.
    val reviewed =
      setOf(
        "  m3/search-input-field.properties[value].jsonType: frozen=[\"object\",\"string\"] " +
          "composed=\"object\""
      )
    val unreviewed = differences - reviewed
    assertTrue(
      unreviewed.isEmpty(),
      "the composed shelf differs from the frozen one:\n" + unreviewed.joinToString("\n"),
    )
  }

  /** Everything a design depends on, and nothing a rendering detail would churn. */
  private fun compare(
    expected: ComponentCapabilityV1,
    actual: ComponentCapabilityV1,
  ): List<String> {
    val id = expected.componentId
    val out = mutableListOf<String>()
    fun check(field: String, want: Any?, got: Any?) {
      if (want != got) out += "  $id.$field: frozen=$want composed=$got"
    }
    check("displayName", expected.displayName, actual.displayName)
    check("role", expected.role, actual.role)
    check("traits", expected.traits.sorted(), actual.traits.sorted())
    // Slots by name, then field by field: cardinality and accepted roles/traits break designs as
    // surely as a changed property.
    check("slots", expected.slots.map { it.name }.sorted(), actual.slots.map { it.name }.sorted())
    val wantSlots = expected.slots.associateBy { it.name }
    val gotSlots = actual.slots.associateBy { it.name }
    for ((name, w) in wantSlots) {
      val g = gotSlots[name] ?: continue
      check("slots[$name].cardinality.min", w.cardinality.min, g.cardinality.min)
      check("slots[$name].cardinality.max", w.cardinality.max, g.cardinality.max)
      check("slots[$name].ordered", w.ordered, g.ordered)
      check("slots[$name].acceptedRoles", w.acceptedRoles.sorted(), g.acceptedRoles.sorted())
      check("slots[$name].acceptedTraits", w.acceptedTraits.sorted(), g.acceptedTraits.sorted())
    }
    check(
      "modifierCapabilities",
      expected.modifierCapabilities.sorted(),
      actual.modifierCapabilities.sorted(),
    )
    // Properties by name, then field by field: "the same 5 properties" is the count mistake again,
    // and a property that changed from optional to required breaks every design that omitted it.
    val builderOwned = builderOwnedProperties[id].orEmpty()
    val want = expected.properties.filterNot { it.name in builderOwned }.associateBy { it.name }
    val got = actual.properties.associateBy { it.name }
    check("missingProperties", emptyList<String>(), (want.keys - got.keys).sorted())
    for ((name, w) in want) {
      val g = got[name] ?: continue
      check("properties[$name].jsonType", w.jsonType, g.jsonType)
      check("properties[$name].required", w.required, g.required)
      allowed(name, w, g)?.let { out += it }
    }
    return out
  }

  /**
   * Properties the builder owns on `m3/icon` that m3-catalog does not publish (Material Symbols
   * naming and axes). Safe to exempt because only the frozen side is filtered: the composed catalog
   * offering something the builder cannot handle is still compared strictly. Cost: serving
   * m3-catalog's published shelf would not offer these.
   */
  private val builderOwnedProperties =
    mapOf(
      "m3/icon" to
        setOf(
          "iconName",
          "iconStyle",
          "iconFill",
          "iconWeight",
          "iconGrade",
          "iconOpticalSize",
          "iconAutoMirror",
        )
    )

  /**
   * Properties whose allowed values are a GENERATED inventory rather than an authored enumeration.
   * See [allowed].
   */
  private val generatedInventory = setOf("iconKey")

  /**
   * Allowed values compared by containment, not equality, for a generated inventory such as
   * `m3/icon`.`iconKey`: the two sides are pinned independently. The question is whether the
   * published catalog offers an icon the builder cannot draw.
   */
  private fun allowed(
    name: String,
    want: PropertyCapabilityV1,
    got: PropertyCapabilityV1,
  ): String? {
    val field = "properties[$name].allowedValues"
    if (name !in generatedInventory) {
      return if (want.allowedValues == got.allowedValues) null
      else "$field: frozen=${want.allowedValues} composed=${got.allowedValues}"
    }
    val undrawable = got.allowedValues.filterNot { it in want.allowedValues }
    return if (undrawable.isEmpty()) null
    else
      "$field: composed offers ${undrawable.size} value(s) the frozen shelf does not: $undrawable"
  }

  /**
   * The builder's own components, which m3-catalog deliberately does not publish, are supplied by
   * the server so the shelf keeps its box, image and gradients.
   */
  @Test
  fun `a published catalog is still served the builder's own vocabulary`() {
    val served =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("m3-catalog"),
          published = mapOf("m3-catalog" to composed),
        )
        .listCatalogs()
        .single()
    val builderOwned =
      frozen.components.map { it.componentId }.filterNot { it.startsWith("m3/") }.sorted()
    val servedIds = served.components.map { it.componentId }.toSet()
    assertEquals(
      emptyList(),
      builderOwned.filterNot { it in servedIds },
      "a published catalog lost builder components the synthesised one offers",
    )
    assertEquals(
      "published",
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("m3-catalog"),
          published = mapOf("m3-catalog" to composed),
        )
        .catalogSources["m3-catalog"],
      "the union must not quietly turn a published catalog back into a synthesised one",
    )
  }

  /**
   * The Wear palette does not get the mobile vocabulary: `WearScreenCodeExporter` refuses
   * everything outside `layout/box`, `layout/column`, `layout/row` and `asset/image`.
   */
  @Test
  fun `a published catalog is handed its own platform's builder vocabulary, not every catalog's`() {
    val wearShaped =
      composed
        .newBuilder()
        .also {
          it.benchmark =
            composed.benchmark.newBuilder().also { it.catalogSystemId = "wear-m3" }.build()
          it.statusSemantics =
            JsonObject(composed.statusSemantics + ("platform" to JsonPrimitive("wear")))
        }
        .build()
    val served =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("wear-m3"),
          published = mapOf("wear-m3" to wearShaped),
        )
        .listCatalogs()
        .single()
        .components
        .map { it.componentId }
        .toSet()
    assertTrue(
      "layout/box" in served,
      "the Wear shelf borrows layout/box; it is one of the four the exporter writes",
    )
    for (mobileOnly in listOf("layout/lazy-grid", "layout/scaffold", "shape/radial-gradient")) {
      assertTrue(
        mobileOnly !in served,
        "$mobileOnly reached a Wear palette, where the exporter refuses it",
      )
    }
  }

  /**
   * `asset/image` needs a registry: with none, `declaredAssetKeys` is null and the check returns
   * early, accepting invented keys.
   */
  @Test
  fun `the donor's asset registry travels with the image component`() {
    val served =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("m3-catalog"),
          published = mapOf("m3-catalog" to composed),
        )
        .listCatalogs()
        .single()
    assertTrue(
      "asset/image" in served.components.map { it.componentId },
      "precondition: the image component is injected",
    )
    val keys = CurrentM3UiBuilderCatalogExecutor.declaredAssetKeys(served)
    assertTrue(
      keys != null && keys.isNotEmpty(),
      "the composed catalog declares no asset registry, so no assetKey is validated",
    )
  }

  @Test
  fun `a jsonType the design validator cannot read is refused, not composed`() {
    val record = json.decodeFromString<ComponentRecordFile>(fixture("m3-catalog-record-v1.json"))
    val published = fixture("m3-catalog-published-v1.json")
    // `accepts` reads a jsonType with `jsonPrimitive`, so an object throws while a design is being
    // WRITTEN — an authoring path that crashes on save, not a shelf that fails to load.
    val broken = published.replaceFirst("\"jsonType\": \"string\"", "\"jsonType\": {}")
    assertTrue(broken != published, "precondition: the fixture has a string jsonType to break")
    val result = PublishedUiBuilderCatalog.compose(broken, record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Unusable,
      "a jsonType of {} composed instead of being refused",
    )
    assertTrue(
      (result as PublishedUiBuilderCatalog.Result.Unusable).reason.contains("jsonType"),
      "the refusal does not name the field: ${result.reason}",
    )
  }

  /** An injected component arrives on the shelf it was written for, not a generic role heading. */
  @Test
  fun `the donor's menu entries travel with the injected components`() {
    val served =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("m3-catalog"),
          published = mapOf("m3-catalog" to composed),
        )
        .listCatalogs()
        .single()
    val menu = served.statusSemantics["componentMenu"] as? JsonObject
    val entries = menu?.get("components") as? JsonObject ?: JsonObject(emptyMap())
    val order =
      (menu?.get("groupOrder") as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.content
      }
    val frozenGroups =
      (frozen.statusSemantics["componentMenu"] as JsonObject)["components"] as JsonObject
    for (builderOwned in
      frozen.components.map { it.componentId }.filterNot { it.startsWith("m3/") }) {
      val want =
        ((frozenGroups[builderOwned] as? JsonObject)?.get("group") as? JsonPrimitive)?.content
          ?: continue
      val got = ((entries[builderOwned] as? JsonObject)?.get("group") as? JsonPrimitive)?.content
      assertEquals(want, got, "$builderOwned reached the shelf with no group, or the wrong one")
      assertTrue(want in order, "group $want is not in groupOrder, so the panel cannot render it")
    }
  }

  /** Every refusal this round added, each as the smallest document that triggers it. */
  @Test
  fun `declarations the builder cannot serve are refused`() {
    val record = json.decodeFromString<ComponentRecordFile>(fixture("m3-catalog-record-v1.json"))
    val published = fixture("m3-catalog-published-v1.json")
    fun refusalFor(edit: (String) -> String, what: String): String {
      val broken = edit(published)
      assertTrue(broken != published, "precondition failed for $what")
      val result = PublishedUiBuilderCatalog.compose(broken, record, exports)
      assertTrue(
        result is PublishedUiBuilderCatalog.Result.Unusable,
        "$what composed instead of being refused",
      )
      return (result as PublishedUiBuilderCatalog.Result.Unusable).reason
    }
    // `[]` is a union of nothing, and `all {}` is vacuously true — which is how it passed the
    // first cut of the readability check.
    assertTrue(
      refusalFor(
          { it.replaceFirst("\"jsonType\": \"string\"", "\"jsonType\": []") },
          "an empty jsonType",
        )
        .contains("empty"),
      "the refusal does not say the jsonType is empty",
    )
    // A name the runtime's `accepts` does not know matches nothing, so a required property
    // declaring one is a component nobody can author.
    assertTrue(
      refusalFor(
          { it.replaceFirst("\"jsonType\": \"string\"", "\"jsonType\": \"date\"") },
          "an unknown type name",
        )
        .contains("not a type name"),
      "the refusal does not name the unknown type",
    )
    // The editor seeds a new node from the first allowed value and reads it as a primitive.
    assertTrue(
      refusalFor(
          { it.replaceFirst("\"allowedValues\": [", "\"allowedValues\": [ {},") },
          "a non-primitive allowed value",
        )
        .contains("not a literal"),
      "the refusal does not name the non-literal allowed value",
    )
  }

  /**
   * The type-name set mirrored from the runtime's private `accepts`, checked against existing
   * catalogs so a new type name fails here rather than in the field.
   */
  @Test
  fun `every type name the frozen catalogs declare is one the server accepts`() {
    val known = setOf("null", "string", "boolean", "number", "integer", "array", "object")
    val declared =
      frozen.components
        .flatMap { it.properties }
        .flatMap { property ->
          when (val type = property.jsonType) {
            is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOf(type.content)
            else -> emptyList()
          }
        }
        .toSortedSet()
    assertEquals(
      emptySet<String>(),
      declared - known,
      "the frozen catalog declares a type name this server's SUPPORTED_JSON_TYPES does not carry",
    )
  }

  /** The builder's shelves land where the donor puts them. */
  @Test
  fun `injected groups follow the donor's order, not the injection order`() {
    val served =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("m3-catalog"),
          published = mapOf("m3-catalog" to composed),
        )
        .listCatalogs()
        .single()
    val order =
      ((served.statusSemantics["componentMenu"] as JsonObject)["groupOrder"] as JsonArray).map {
        (it as JsonPrimitive).content
      }
    val donorOrder =
      ((frozen.statusSemantics["componentMenu"] as JsonObject)["groupOrder"] as JsonArray).map {
        (it as JsonPrimitive).content
      }
    // Every group the donor knows is present, and any two of them appear in the donor's relative
    // order — "Layout" before "Content", "Scaffolds" before "Layout".
    val shared = donorOrder.filter { it in order }
    assertEquals(
      shared,
      order.filter { it in donorOrder },
      "the shelves are not in the donor's relative order",
    )
    assertTrue(
      order.indexOf("Layout") < order.indexOf("Styles"),
      "Layout landed below Styles: $order",
    )
  }

  /** The donor's asset keys are unioned in, not only used to fill an absent registry. */
  @Test
  fun `the donor's asset keys are merged into a catalog's own registry`() {
    val ownRegistry =
      JsonObject(
        composed.statusSemantics +
          ("assetRegistry" to
            JsonObject(mapOf("keys" to JsonArray(listOf(JsonPrimitive("catalog.own"))))))
      )
    val served =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("m3-catalog"),
          published =
            mapOf(
              "m3-catalog" to
                composed.newBuilder().also { it.statusSemantics = ownRegistry }.build()
            ),
        )
        .listCatalogs()
        .single()
    val keys = CurrentM3UiBuilderCatalogExecutor.declaredAssetKeys(served).orEmpty()
    assertTrue("catalog.own" in keys, "the catalog's own key was dropped: $keys")
    // The editor seeds a new image with this builder-owned key, so an injected `asset/image`
    // without it is a palette component that cannot be inserted.
    assertTrue("editor.placeholder" in keys, "the donor's placeholder key was not merged: $keys")
  }

  @Test
  fun `the composed catalog offers no component the frozen one does not`() {
    val frozenIds = frozen.components.map { it.componentId }.toSet()
    val surplus = composed.components.map { it.componentId }.filterNot { it in frozenIds }
    assertEquals(
      emptyList(),
      surplus,
      "the published catalog offers components the frozen shelf does not; every one is a component " +
        "a design could be built on and then lose",
    )
  }
}
