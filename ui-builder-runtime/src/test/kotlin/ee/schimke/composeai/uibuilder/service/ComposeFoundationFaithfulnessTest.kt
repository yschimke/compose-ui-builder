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
 * `compose-foundation` donates exactly what the synthesised catalogs donate — component for
 * component, note for note, shelf for shelf.
 *
 * Proves the extraction equal to `remoteM3Catalog`/`wearM3Catalog` while both still exist, before
 * those generators are deleted; afterwards this test goes with them and
 * `SynthesisedCatalogGoldenTest`'s fixtures take over. The per-platform sets are not
 * interchangeable (e.g. Wear must not get `layout/lazy-grid`).
 */
class ComposeFoundationFaithfulnessTest {

  private val executor =
    CurrentM3UiBuilderCatalogExecutor(
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
   * component (every deployment today): the packaged catalog's own seams.
   */
  private fun packagedSeams(base: CatalogCapabilityV1) =
    base.components
      .filter { it.componentId.startsWith(REMOTE_COMPOSE_NAMESPACE) }
      .associateBy { it.componentId }

  /**
   * The foundation for a synthesised catalog's platform, derived from the served packaged catalog
   * (`baseCatalog` is private). The only difference, the `remoteCompose` property filter, applies
   * uniformly to both sides.
   */
  private fun foundationFor(catalog: CatalogCapabilityV1) =
    synthesised(DEFAULT_CATALOG_SYSTEM_ID).let { base ->
      composeFoundationCatalog(base, catalog.platform, packagedSeams(base))
    }

  private fun assertDonatesTheSame(systemId: String) {
    val old = synthesised(systemId)
    val new = foundationFor(old)

    // Component by component: order decides insert-panel placement, and notes and modifier lists
    // are checked nowhere else.
    assertEquals(
      old.donated().map { it.componentId },
      new.components.map { it.componentId },
      "the $systemId foundation donates a different vocabulary, or in a different order",
    )
    assertEquals(old.donated(), new.components, "a donated component differs field-for-field")
  }

  /**
   * Holds by construction (mobile's curation is the identity); here to catch a future mobile
   * curation. The wear and remote-compose cases are the real checks.
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
   * The donor's asset registry and its shelves with their order, both read by
   * `withBuilderVocabulary`.
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
      // Per donated component: `remote-m3`'s menu also shelves its own `remote-m3/lottie`.
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

  /** The premise: the platforms donate different vocabularies, so these tests are not vacuous. */
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
   * Deliberate change: the donor is chosen by declared platform, not catalog id, so the palette
   * agrees with the exporter. The id is still consulted when no platform is declared (see
   * `WearM3ScreenCatalogTest`).
   */
  @Test
  fun `a declared platform beats the id, and no declaration still falls back to it`() {
    val wearVocabulary =
      synthesised(CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID).donated().map {
        it.componentId
      }

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
      vocabularyOf(stub()).toSortedSet(),
      "a published catalog that declares no platform stopped falling back to its id",
    )
    assertEquals(
      foundationFor(synthesised(DEFAULT_CATALOG_SYSTEM_ID))
        .components
        .map { it.componentId }
        .toSortedSet(),
      vocabularyOf(stub(platform = "mobile")).toSortedSet(),
      "the catalog's own declaration lost to its id",
    )
  }

  /**
   * A `remote-compose/` seam that `remote-m3` publishes wins; the packaged catalog is the fallback.
   * Sourced from what was published, since the served catalog already has seams injected.
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

    // Read through the Remote Compose platform; Wear no longer takes the seams.
    val foundation =
      composeFoundationCatalog(
        base,
        "remote-compose",
        packagedSeams(base) + ("remote-compose/document" to remoteM3Owned),
      )

    // Compare display names: this platform's curation also narrows modifiers, and the name shows
    // which seam won.
    assertEquals(
      remoteM3Owned.displayName,
      foundation.components.single { it.componentId == "remote-compose/document" }.displayName,
      "the packaged seam beat the catalog that owns Remote Compose",
    )
    // `remote-compose/inline` is donated to no platform now (a document does not embed a switch
    // into itself).
    assertTrue(
      foundation.components.none { it.componentId == REMOTE_COMPOSE_INLINE_COMPONENT_ID },
      "the inline seam is donated to no platform while the Wear seams are withdrawn",
    )
  }

  /**
   * A seam no source has is left off the palette rather than invented, instead of a `getValue`
   * startup crash.
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
   * The two things `withBuilderVocabulary` reads out of a donor's `componentMenu`, re-read here
   * because the runtime's helpers are file-private.
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
