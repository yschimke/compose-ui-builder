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
 * Does the shelf composed from m3-catalog's published files match the shelf this server
 * synthesises?
 *
 * The readiness gate answers a narrower question — the catalog-level facts agree and the component
 * IDS agree — and says so. It does not compare what a component OFFERS, and the difference is not
 * academic: a first attempt at this cutover matched all the ids while replacing every component's
 * property vocabulary with its Compose parameter list, making `onClick` a required property no
 * design sets, and stripping every modifier. Ids matching is not shelves matching, which is the
 * same mistake as counting components and calling it identity.
 *
 * So this compares the composed component against the frozen one field by field, for every
 * component the frozen catalog owns.
 *
 * **What it does not prove, and what happened when that was assumed.** The published fixture is
 * hand-built: its `statusSemantics.components` block was written here, keyed on the ids the frozen
 * catalog uses. That makes this a real test of the COMPOSER — given a well-formed published pair,
 * does the shelf come out right — and no test at all of whether m3-catalog can produce that pair.
 * The two were conflated once already. Swapping in a genuinely generated pair turned 14 green tests
 * into 11 failures, all one root cause: m3-catalog derived its component ids from a catalog id's
 * last segment, which names the VARIANT, so `Button/Filled`, `Card/Filled` and four more all
 * claimed `m3/filled` and 49 of 108 components collided. The fixture, keyed the way a person would
 * key it, had no collisions and hid the whole problem.
 *
 * So: passing here is necessary before `--ui-builder-published-catalogs` names `m3-catalog`, and it
 * is not sufficient. The sufficient one now exists beside it:
 * [PublishedGeneratedM3CatalogEquivalenceTest] runs the same [compare] against a pair captured from
 * a real `composePreviewDiscover`, and the `m3 catalog-level equivalence` step in `ci.yml` holds
 * that pair to the frozen catalog fact by fact. Read that one for what m3-catalog can actually
 * offer; read this one for whether the composer turns a well-formed pair into the right shelf. Even
 * together they are a floor rather than a proof: [compare] checks what a design depends on, and a
 * catalog can still differ in something no assertion here reads.
 *
 * One caution, since it is the reason this fixture exists at all: do NOT edit a generated file to
 * keep a test green. The frozen shelf and a published m3 catalog hold deliberately different
 * component sets — `UI_BUILDER_CATALOG_CONTRACT.md` says so, and a real run offers about sixty
 * components the frozen one does not. The assertion that the composed catalog offers nothing extra
 * encodes the opposite, and settling that is a decision about the contract rather than a fixture to
 * adjust.
 *
 * Everything left here compares a composed shelf against the frozen one, which is the question this
 * fixture can answer honestly. Two of the composer's REFUSALS used to live here too — a builtin
 * with a malformed `jsonType`, a slot cardinality no child count satisfies — reaching their input
 * by string-splicing this fixture. Both now sit in `PublishedUiBuilderCatalogHostileInputTest`
 * against its own two-component record, because m3-catalog's policy states the catalog owns no
 * builtins: on a regenerated fixture the splice would land nowhere and both would have passed while
 * checking nothing.
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
   * The three the published catalog cannot offer, and why — [yschimke/m3-catalog#317].
   *
   * Asserted ABSENT rather than skipped. A gap nothing states is a gap that stops being noticed,
   * and if one of these starts composing (because the record grew the callable) this test fails and
   * says to move it up here, rather than passing quietly with a shelf nobody re-checked.
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
    // compose-ui-builder#230 let the frozen record's search field take a literal query as well as
    // bound state; m3-catalog's published policy still says `object`, narrower rather than wrong.
    // `PublishedGeneratedM3CatalogEquivalenceTest.REVIEWED_DIFFERENCES` records the same one.
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
    // Slots by name, then field by field — the same reason the properties below get it, and the
    // one place the lesson had not been applied. `cardinality` decides how many children can be
    // authored and `acceptedRoles`/`acceptedTraits` decide which components may go in, so a slot
    // that kept its name while losing its shape breaks designs exactly as a changed property does
    // and this comparison passed it (Codex, #673).
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
   * Properties the **builder** owns on `m3/icon`, which m3-catalog does not publish.
   *
   * The same shape of exemption as [generatedInventory] below, and for the same reason: the two
   * shelves are pinned independently, so equality asks a question neither repository can answer
   * from the other side. Material Symbols names an icon and positions it on four continuous axes,
   * which is this repository's model of an icon and not something m3-catalog has a view on.
   *
   * The asymmetry is what makes it safe. A property the FROZEN shelf adds is one the builder offers
   * and the published catalog has never heard of: a design authored here still validates, and the
   * published shelf loses nothing. The reverse — the composed catalog offering a property the
   * builder cannot handle — is the thing that would break a design, and it is still compared
   * strictly, because `got` is never filtered.
   *
   * What it costs, stated rather than hidden: serving m3-catalog's shelf under
   * `--ui-builder-published-catalogs` would offer none of these, so a design that names an icon
   * rather than keying one could not be authored against it until m3-catalog publishes them too.
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
   * The one property field where equality is the wrong question: an **inventory**, not a choice.
   *
   * Every other allowed-value list on this shelf is an enumeration somebody authored —
   * `m3/text`.`style`'s fifteen typography roles — and equality is exactly right for those: a value
   * appearing or disappearing is a design decision, and it belongs in a diff.
   *
   * `m3/icon`.`iconKey` is not that. It is the Material icon set, generated, and the two sides are
   * pinned independently: the frozen catalog is packaged in THIS repository, and the composed one
   * is whatever m3-catalog last published. #710 exposed the complete inventory here while
   * m3-catalog still ships the forty-six-icon hand-picked list, and equality then made this
   * comparison red on a fact neither repository can act on from the other side — printing all
   * 11,431 names into the failure, which is also how nobody reads it.
   *
   * The question worth asking survives the growth: **does the published catalog offer an icon the
   * builder cannot draw?** That is containment. It is stable while the inventory grows, it fails on
   * the thing that would actually break a design, and it names only the offending values.
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
   * The other half of the shelf: the builder's own components, which m3-catalog deliberately does
   * not publish.
   *
   * Its policy says why — declaring `layout/box` "would be this catalog claiming to own the
   * builder's own vocabulary" — and it is right, so the server supplies them. Without that a
   * published catalog replaces the synthesised one wholesale and the shelf loses its box, its image
   * and its gradients: sixteen components here, every one of which existing designs use.
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
   * The Wear palette is not the mobile one, and handing it the mobile vocabulary is not a kindness.
   *
   * `WearScreenCodeExporter` refuses everything outside `layout/box`, `layout/column`, `layout/row`
   * and `asset/image` with "no Wear Compose Material 3 counterpart this generator can write". A
   * palette entry that cannot be exported is worse than a missing one: the author finds out at the
   * end, with the design already built.
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
   * `asset/image` without a registry is an image component whose keys nothing checks.
   *
   * `declaredAssetKeys` returns null when `statusSemantics.assetRegistry` is absent, and a null
   * registry makes the check RETURN EARLY rather than fail — so an invented `assetKey` is accepted
   * into a saved design and surfaces as a broken picture at render time.
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

  /**
   * An injected component arrives on the shelf it was written for.
   *
   * The insert panel groups by `componentMenu`, and an entry with no group falls back to a generic
   * role heading — so injecting the builder vocabulary without the donor's groups would file the
   * whole of it under "Container" / "Leaf" instead of Layout, Content, Styles and Embedded.
   */
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
   * The set this file mirrors from the runtime's `accepts`, checked against the catalogs that
   * exist.
   *
   * Not proof the two agree — they are in different modules and the runtime's is a private `when` —
   * but it makes the drift visible from the side that matters: a catalog starting to declare a type
   * name this server would refuse fails here rather than in the field.
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

  /**
   * The builder's shelves land where the donor puts them, not where the injection loop reached
   * them.
   */
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
