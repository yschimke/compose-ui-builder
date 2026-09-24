package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `compose-foundation` donates exactly what the retired synthesised catalogs donated — component
 * for component, note for note, shelf for shelf.
 *
 * `remoteM3Catalog` and `wearM3Catalog` are gone
 * ([#819](https://github.com/yschimke/compose-preview-server/issues/819)); their last job was to be
 * the donor [`withBuilderVocabulary`][CurrentM3UiBuilderCatalogExecutor] handed a published catalog
 * its `layout/`, `shape/`, `asset/` and `remote-compose/` from. This test proved the foundation
 * equal to them while both existed, and now measures it against the frozen
 * `docs/design/fixtures/ui-builder/<id>-capabilities-v1.json` descriptions they left behind —
 * served through [PublishedCatalogFixtures], where the fixture's own builder components win every
 * collision, so what is compared is still what the generator produced.
 *
 * ## What a failure means
 *
 * The foundation dropped or changed something, and the diff says what. The per-platform sets are
 * NOT interchangeable — a watch palette handed the mobile seventeen offers `layout/lazy-grid` and
 * `layout/scaffold`, which `WearScreenCodeExporter` refuses by name, so the design fails at export
 * rather than at insert.
 */
class ComposeFoundationFaithfulnessTest {

  private val executor =
    PublishedCatalogFixtures.executor(
      catalogSystemIds =
        linkedSetOf(
          CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
        )
    )

  private fun synthesised(systemId: String): CatalogCapabilityV1 =
    executor.listCatalogs().single { it.benchmark.catalogSystemId == systemId }

  /** What a donor is read for: its builder-namespace components, in its own order. */
  private fun CatalogCapabilityV1.donated() = components.filter { component ->
    BUILDER_NAMESPACES.any { namespace -> component.componentId.startsWith(namespace) }
  }

  /**
   * The seam source the runtime builds when no published catalog declares a `remote-compose/`
   * component -- which is every deployment today, so this is the live configuration rather than a
   * convenience: the packaged catalog's own seams.
   */
  private fun packagedSeams(base: CatalogCapabilityV1) =
    base.components
      .filter { it.componentId.startsWith(REMOTE_COMPOSE_NAMESPACE) }
      .associateBy { it.componentId }

  /**
   * The foundation for a synthesised catalog's platform, derived from the SERVED packaged catalog.
   *
   * `baseCatalog` is private to the runtime, so this takes the m3-catalog entry `listCatalogs`
   * returns. It differs from `baseCatalog` only by the `UiBuilderBuildFeatures.remoteCompose`
   * property filter, which is applied uniformly to every component of every catalog — so both sides
   * of every assertion below see the same filtering, whichever way the flag is set.
   */
  private fun foundationFor(catalog: CatalogCapabilityV1) =
    synthesised(DEFAULT_CATALOG_SYSTEM_ID).let { base ->
      composeFoundationCatalog(base, catalog.platform, packagedSeams(base))
    }

  private fun assertDonatesTheSame(systemId: String) {
    val old = synthesised(systemId)
    val new = foundationFor(old)

    // Component by component rather than set-wise: the ORDER decides where an injected component
    // lands in the insert panel, and the note and the modifier list are the two fields nothing else
    // in the suite looks at.
    assertEquals(
      old.donated().map { it.componentId },
      new.components.map { it.componentId },
      "the $systemId foundation donates a different vocabulary, or in a different order",
    )
    assertEquals(old.donated(), new.components, "a donated component differs field-for-field")
  }

  /**
   * The weak one of the three, and said so rather than left to look like proof.
   *
   * Mobile's curation is the identity applied to the same namespace filter, so this holds by
   * construction and would keep holding if the filter were wrong. It is here to catch a later
   * change that gives mobile a curation of its own -- the wear and remote-compose cases below are
   * where the derivation is actually being checked against a different one.
   */
  @Test
  fun `the mobile foundation donates what the packaged catalog donates`() {
    assertDonatesTheSame(DEFAULT_CATALOG_SYSTEM_ID)
  }

  @Test
  fun `the wear foundation donates what wear-m3 donates`() {
    assertDonatesTheSame(CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID)
  }

  @Test
  fun `the remote-compose foundation donates what remote-m3 donates`() {
    assertDonatesTheSame(CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID)
  }

  /**
   * The other two things a donor is read for, and neither is a component.
   *
   * `withBuilderVocabulary` takes the donor's asset registry (so `asset/image` arrives with keys
   * that validate rather than with a null registry that accepts anything) and the donor's shelves
   * AND shelf order (so `layout/box` is filed under "Layout" where the donor puts it, rather than
   * appended below every catalog group under a generic role heading).
   */
  @Test
  fun `every foundation carries the donor's asset registry and shelves`() {
    val registryKey = CurrentM3UiBuilderCatalogExecutor.ASSET_REGISTRY_KEY
    for (systemId in
      listOf(
        DEFAULT_CATALOG_SYSTEM_ID,
        CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
        CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
      )) {
      val old = synthesised(systemId)
      val new = foundationFor(old)

      assertEquals(
        old.statusSemantics[registryKey],
        new.statusSemantics[registryKey],
        "$systemId: the foundation's asset registry differs, so a donated asset/image validates " +
          "against different keys",
      )
      // Per donated component rather than whole-object: `remote-m3`'s menu also carries a shelf
      // for `remote-m3/lottie`, which is that catalog's own component and not the foundation's.
      // What `withBuilderVocabulary` reads is each injected component's group, plus the order it
      // places a NEW group relative to the ones a catalog already has.
      for (componentId in new.components.map { it.componentId }) {
        assertEquals(
          old.statusSemantics.shelfOf(componentId),
          new.statusSemantics.shelfOf(componentId),
          "$systemId: $componentId is filed under a different shelf",
        )
      }
      assertEquals(
        old.statusSemantics.shelfOrder(),
        new.statusSemantics.shelfOrder(),
        "$systemId: a new shelf would be inserted in a different place",
      )
      assertEquals(
        old.platform,
        new.platform,
        "$systemId: the foundation declares another platform",
      )
    }
  }

  /**
   * The premise, asserted so these tests cannot pass by comparing one thing to itself.
   *
   * If the three platforms donated the same vocabulary there would be nothing to curate and a
   * single list would do — and this suite would be green while checking nothing.
   */
  @Test
  fun `the three platforms really do donate different vocabularies`() {
    val sets =
      listOf(
          DEFAULT_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
        )
        .map { systemId ->
          foundationFor(synthesised(systemId)).components.map { it.componentId }.toSet()
        }

    assertEquals(sets.size, sets.distinct().size, "two platforms donate the same set")
    // The one that matters: a watch must not be offered what its exporter refuses by name.
    val wear =
      foundationFor(synthesised(CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID))
    val offered = wear.components.map { it.componentId }
    assertTrue("layout/box" in offered, "a Wear palette with no box on it")
    for (refused in listOf("layout/lazy-grid", "layout/scaffold", "shape/radial-gradient")) {
      assertTrue(
        refused !in offered,
        "the Wear foundation offers $refused, which cannot be exported",
      )
    }
  }

  /** An unknown platform gets the mobile vocabulary, which is where the fallback always pointed. */
  @Test
  fun `a platform the foundation has never heard of falls back to mobile`() {
    val base = synthesised(DEFAULT_CATALOG_SYSTEM_ID)

    assertEquals(
      composeFoundationCatalog(
          base,
          CurrentM3UiBuilderCatalogExecutor.DEFAULT_PLATFORM,
          packagedSeams(base),
        )
        .components,
      composeFoundationCatalog(base, "tv", packagedSeams(base)).components,
    )
  }

  /**
   * The platform a catalog DECLARES decides its vocabulary, and its id never does.
   *
   * The old chain picked a donor by catalog id first, so a published catalog was handed the Wear
   * vocabulary whenever its id was `wear-m3`, even while declaring itself mobile. That was narrowed
   * to a fallback for catalogs declaring no platform, and the fallback went with the synthesised
   * catalogs it looked up: an undeclared platform is mobile, as it is everywhere else that reads
   * `statusSemantics.platform`. Both published add-ons declare theirs.
   */
  @Test
  fun `a declared platform decides the vocabulary, and the id does not`() {
    val wearVocabulary =
      synthesised(CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID).donated().map {
        it.componentId
      }
    val mobileVocabulary =
      foundationFor(synthesised(DEFAULT_CATALOG_SYSTEM_ID)).components.map { it.componentId }

    fun vocabularyOf(catalog: CatalogCapabilityV1) =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds =
            linkedSetOf(CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID),
          published = mapOf(CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID to catalog),
        )
        .listCatalogs()
        .single()
        .donated()
        .map { it.componentId }

    assertEquals(
      wearVocabulary.toSortedSet(),
      vocabularyOf(stub(platform = "wear")).toSortedSet(),
      "a catalog declaring wear lost the wear vocabulary",
    )
    assertEquals(
      mobileVocabulary.toSortedSet(),
      vocabularyOf(stub()).toSortedSet(),
      "a catalog declaring no platform was handed a vocabulary off its id",
    )
    assertEquals(
      mobileVocabulary.toSortedSet(),
      vocabularyOf(stub(platform = "mobile")).toSortedSet(),
      "the catalog's own declaration lost to its id",
    )
  }

  /**
   * The `remote-compose/` seams are Remote Compose's, and the foundation only says where they sit.
   *
   * `remote-m3` describes Remote Compose, so a seam IT declares is the one handed out; the packaged
   * catalog is the fallback for the ones it does not. Today it declares none — every published
   * catalog publishes only its own prefix — so the fallback is always taken, which is why the
   * faithfulness assertions above still hold unchanged. This is the part that will stop being a
   * fallback (#819).
   *
   * Sourced from what a catalog PUBLISHED rather than from what the server serves it: the served
   * `remote-m3` has seams injected into it by `withBuilderVocabulary`, so reading that one back
   * would be circular.
   */
  @Test
  fun `a seam remote-m3 declares itself wins over the packaged one`() {
    val base = synthesised(DEFAULT_CATALOG_SYSTEM_ID)
    val packaged = packagedSeams(base).getValue("remote-compose/document")
    val remoteM3Owned =
      packaged
        .newBuilder()
        .also { it.displayName = "Remote Compose document, as remote-m3 says it" }
        .build()

    // The Remote Compose platform rather than Wear. Wear used to take all three seams and no
    // longer takes any — they were offered there without being exportable — so this reads through
    // the platform Remote Compose content is actually authored on.
    val foundation =
      composeFoundationCatalog(
        base,
        "remote-compose",
        packagedSeams(base) + ("remote-compose/document" to remoteM3Owned),
      )

    // On the display name rather than the whole component: this platform's curation narrows
    // `modifierCapabilities` on everything it donates, so whole-object equality would be asserting
    // the curation as well as the override. The name is the mark this test varies, and it is the
    // one that says WHICH seam won.
    assertEquals(
      remoteM3Owned.displayName,
      foundation.components.single { it.componentId == "remote-compose/document" }.displayName,
      "the packaged seam beat the catalog that owns Remote Compose",
    )
    // `remote-compose/inline` is donated to NO platform now, and that is a consequence worth
    // asserting rather than losing. It was on Wear's borrow list alone, so withdrawing the seams
    // there withdrew it everywhere; the Remote Compose platform never took it, because a document
    // does not embed a switch into itself. It returns with the Wear seams.
    assertTrue(
      foundation.components.none { it.componentId == REMOTE_COMPOSE_INLINE_COMPONENT_ID },
      "the inline seam is donated to no platform while the Wear seams are withdrawn",
    )
  }

  /**
   * A seam no source has is left off the palette rather than invented.
   *
   * The shape a deployment gets once `remote-m3` owns the seams and the packaged catalog stops
   * carrying them: no Remote Compose catalog served means no Remote Compose on the shelf. Asserted
   * now because the alternative — a `getValue` on the seam map — would be a startup crash rather
   * than a missing palette entry, and the difference only shows up on someone's box.
   */
  @Test
  fun `a seam no source declares is left off the palette`() {
    val base = synthesised(DEFAULT_CATALOG_SYSTEM_ID)

    val foundation = composeFoundationCatalog(base, "wear", emptyMap())

    assertEquals(
      emptyList(),
      foundation.components
        .map { it.componentId }
        .filter { it.startsWith(REMOTE_COMPOSE_NAMESPACE) },
      "a seam was served from somewhere other than the seam source",
    )
    // The foundation's own three are untouched by a missing seam source.
    assertEquals(
      listOf("layout/box", "layout/column", "layout/row", "asset/image"),
      foundation.components.map { it.componentId },
    )
  }

  /** The smallest thing that is a catalog, under the Wear id, optionally declaring a platform. */
  private fun stub(platform: String? = null) =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder(
            CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
            "ui-builder.json",
            CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
            "sha256:stub",
            "candidate",
          )
          .build(),
        listOf(
          ComponentCapabilityV1.Builder(
              "stub/only",
              "Only",
              "Leaf",
              WasmCapabilityV1.Builder(
                  platformSupported = JsonPrimitive(false),
                  adapterStatus = WasmAdapterStatusV1.UNSUPPORTED,
                )
                .build(),
            )
            .build()
        ),
      )
      .also {
        it.statusSemantics =
          platform?.let {
            JsonObject(mapOf(CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY to JsonPrimitive(it)))
          } ?: JsonObject(emptyMap())
        it.exportCapabilities =
          ExportCapabilitiesV1.Builder()
            .also {
              it.composeCode = false
              it.svg = false
              it.png = false
            }
            .build()
      }
      .build()

  /**
   * The two things `withBuilderVocabulary` reads out of a donor's `componentMenu`, spelled here.
   *
   * The runtime's own `menuGroups`/`menuGroupOrder` are file-private to
   * `ProductionUiBuilderRuntime` and widening them for a test would be widening the wrong thing --
   * a second reading of the same JSON is what a golden would do anyway, and this one fails if the
   * shape ever changes.
   */
  private fun JsonObject.shelfOf(componentId: String): String? =
    ((((this["componentMenu"] as? JsonObject)?.get("components") as? JsonObject)?.get(componentId)
          as? JsonObject)
        ?.get("group") as? JsonPrimitive)
      ?.content

  private fun JsonObject.shelfOrder(): List<String> =
    ((this["componentMenu"] as? JsonObject)?.get("groupOrder") as? JsonArray).orEmpty().mapNotNull {
      (it as? JsonPrimitive)?.content
    }

  private companion object {
    const val DEFAULT_CATALOG_SYSTEM_ID =
      CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID
  }
}
