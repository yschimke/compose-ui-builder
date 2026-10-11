package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The collision refusal, from the shape of a real published catalog: a policy declaring no
 * `components`, so ids derive from `Group/Variant` leaves (the variant), and most components
 * collide. A refusal rather than a warning because the survivors can match the frozen shelf's
 * count while sharing almost no ids. See `docs/design/UI_BUILDER_CATALOG_CONTRACT.md` § Phase 4;
 * real files are checked by `.github/scripts/ui-builder-equivalence.sh --record`.
 */
class PublishedUiBuilderCatalogCollisionTest {

  private val exports =
    ExportCapabilitiesV1.Builder()
      .also {
        it.composeCode = true
        it.svg = false
        it.png = false
      }
      .build()

  /**
   * The canonical id [record] gives the entry at [index], shared so fixtures cannot mis-spell it
   * and pass for the wrong reason.
   */
  private fun canonicalIdAt(componentId: String, index: Int): String {
    val group = componentId.substringBefore('/')
    val variant = componentId.substringAfter('/')
    return ":m3/androidx.compose.material3.${group}Kt.$variant$group$index"
  }

  /**
   * A record of `Group/Variant` component ids, written out one by one: the derived id is the leaf,
   * so a cross product would hide the collisions under test.
   */
  private fun record(vararg componentIds: String): ComponentRecordFile {
    val components = componentIds.mapIndexed { index, id ->
      val group = id.substringBefore('/')
      val variant = id.substringAfter('/')
      """
        {
          "canonicalId": "${canonicalIdAt(id, index)}",
          "componentIds": ["$id"],
          "symbol": {
            "name": "$variant$group",
            "callable": "androidx.compose.material3.$variant$group",
            "jvmOwner": "androidx.compose.material3.${group}Kt",
            "origin": "LIBRARY"
          },
          "parameters": [],
          "slots": [],
          "code": { "imports": [] }
        }
        """
        .trimIndent()
    }
    return Json { ignoreUnknownKeys = true }
      .decodeFromString(
        """
        {"schemaVersion":1,"module":":m3","variant":"debug",
         "components":[${components.joinToString(",")}]}
        """
          .trimIndent()
      )
  }

  /** A policy declaring no `components` — the shape that leaves every id to be derived. */
  private fun published(expected: Int) =
    """
    {
      "schema": "compose-ui-builder-catalog/v1",
      "catalog": { "id": "m3-shaped", "title": "A catalog declaring no components" },
      "record": { "file": "components.json", "schemaVersion": 1, "components": $expected },
      "statusSemantics": { "platform": "mobile", "componentIdPrefix": "m3/" }
    }
    """
      .trimIndent()

  @Test
  fun `a file whose ids are variants rather than components is refused`() {
    // Four groups sharing three variant words. The leaf is the variant, so twelve components derive
    // three ids and nine collide — 75%, the same register as the measured 61%.
    val result =
      PublishedUiBuilderCatalog.compose(
        published(expected = 12),
        record(
          "Button/Filled",
          "Button/Outlined",
          "Button/Small",
          "Card/Filled",
          "Card/Outlined",
          "Card/Small",
          "Dialog/Filled",
          "Dialog/Outlined",
          "Dialog/Small",
          "TopAppBar/Filled",
          "TopAppBar/Outlined",
          "TopAppBar/Small",
        ),
        exports,
      )
    val unusable = assertIs<PublishedUiBuilderCatalog.Result.Unusable>(result)
    // The reason has to carry the numbers: an operator reading one startup line is deciding whether
    // this is their catalog's bug or their server's, and "refused" alone does not say.
    assertTrue("9 of 12 eligible" in unusable.reason, unusable.reason)
    assertTrue("accident of record order" in unusable.reason, unusable.reason)
  }

  @Test
  fun `a catalog that names its components distinctly is unaffected`() {
    // Distinct leaves, no collisions: deriving ids is legitimate (`test-catalog` and Wear rely on
    // it).
    val result =
      PublishedUiBuilderCatalog.compose(
        published(expected = 4),
        record("Button/Filled", "Card/Elevated", "Dialog/Basic", "TopAppBar/Centre"),
        exports,
      )
    val composed = assertIs<PublishedUiBuilderCatalog.Result.Composed>(result)
    assertEquals(4, composed.catalog.components.size)
  }

  @Test
  fun `one stray duplicate is skipped rather than fatal`() {
    // A single collision is one catalog bug and is skipped, hence a rate with a floor of one rather
    // than `collisions > 0`.
    val ids = (1..20).map { "Group$it/Variant$it" } + "Extra/Variant1"
    val result =
      PublishedUiBuilderCatalog.compose(
        published(expected = 21),
        record(*ids.toTypedArray()),
        exports,
      )
    val composed = assertIs<PublishedUiBuilderCatalog.Result.Composed>(result)
    // 21 record components, 20 distinct leaves: exactly one collision, under the threshold.
    assertEquals(20, composed.catalog.components.size)
    assertTrue("1 skipped" in composed.note, composed.note)
  }

  @Test
  fun `an excluded component is out of the denominator, not hiding a colliding shelf`() {
    // The rate is over entries that competed: excluded entries claim no identity, so they must not
    // dilute the denominator (9 of 10 competing collide here, and it is refused).
    val ids = (1..10).map { "Button/Filled" } + (1..90).map { "Skip$it/Skip$it" }
    val excluded =
      ids
        .withIndex()
        .filter { (_, id) -> id.startsWith("Skip") }
        .joinToString(",") { (index, id) ->
          """"m3/skip$index": {"record": "${canonicalIdAt(id, index)}", "excluded": "not on the shelf"}"""
        }
    val policy =
      """
      {
        "schema": "compose-ui-builder-catalog/v1",
        "catalog": { "id": "m3-shaped" },
        "record": { "file": "components.json", "schemaVersion": 1, "components": 100 },
        "statusSemantics": { "platform": "mobile", "componentIdPrefix": "m3/",
          "components": { $excluded } }
      }
      """
        .trimIndent()
    val result = PublishedUiBuilderCatalog.compose(policy, record(*ids.toTypedArray()), exports)
    val unusable = assertIs<PublishedUiBuilderCatalog.Result.Unusable>(result)
    assertTrue("9 of 10 eligible" in unusable.reason, unusable.reason)
  }

  @Test
  fun `the floor holds on a record too small for the rate to mean anything`() {
    // Two components, one collision (50%): skipped because the allowance is
    // `maxOf(1, rate * eligible)`.
    val result =
      PublishedUiBuilderCatalog.compose(
        published(expected = 2),
        record("Button/Filled", "Card/Filled"),
        exports,
      )
    val composed = assertIs<PublishedUiBuilderCatalog.Result.Composed>(result)
    assertEquals(listOf("m3/filled"), composed.catalog.components.map { it.componentId })
  }

  /** A record entry claiming [componentIds], with the canonical id spelled out. */
  private fun entry(canonicalId: String, name: String, vararg componentIds: String) =
    """
    {
      "canonicalId": "$canonicalId",
      "componentIds": [${componentIds.joinToString(",") { "\"$it\"" }}],
      "symbol": {
        "name": "$name",
        "callable": "androidx.compose.foundation.layout.$name",
        "jvmOwner": "androidx.compose.foundation.layout.${name}Kt",
        "origin": "LIBRARY"
      },
      "parameters": [],
      "slots": [],
      "code": { "imports": [] }
    }
    """
      .trimIndent()

  private fun recordOf(vararg entries: String): ComponentRecordFile = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString(
      """{"schemaVersion":1,"module":":m3","variant":"debug","components":[${entries.joinToString(",")}]}"""
    )

  @Test
  fun `a record entry answering only to the builder's own namespaces is not this catalog's`() {
    // `layout/column` comes from `withBuilderVocabulary`; deriving `m3/column` too would offer the
    // same component under two ids. `ComponentRecordSource` puts these foundation entries in every
    // record.
    val result =
      PublishedUiBuilderCatalog.compose(
        published(expected = 3),
        recordOf(
          entry("cf/l.Kt.Column", "Column", "layout/column"),
          entry("cf/l.Kt.Box", "Box", "layout/box", "shape/colour-dot"),
          entry("m3/b.Kt.Badge", "Badge", "Badge/Number"),
        ),
        exports,
      )
    val composed = assertIs<PublishedUiBuilderCatalog.Result.Composed>(result)
    assertEquals(
      listOf("m3/number"),
      composed.catalog.components.map { it.componentId },
      "a foundation entry was published under this catalog's prefix",
    )
    // Not counted as skipped either: it never competed for an id on this shelf, and putting eight
    // of them in the startup note on every catalog would bury the skips that mean something.
    assertTrue("skipped" !in composed.note, composed.note)
  }

  @Test
  fun `an entry claiming no id at all is still reached, and still composed`() {
    // `ElevatedCard` and `OutlinedCard` claim no ids (selected by canonical id via `variant`);
    // `all {}` over an empty list is true, so the filter needs its emptiness guard.
    val result =
      PublishedUiBuilderCatalog.compose(
        published(expected = 1),
        recordOf(entry("m3/c.Kt.ElevatedCard", "ElevatedCard")),
        exports,
      )
    val composed = assertIs<PublishedUiBuilderCatalog.Result.Composed>(result)
    // `derivedId` falls back to the symbol's own name where there is no component id to take a
    // leaf from, and `slug` splits it: `ElevatedCard` is `elevated-card`.
    assertEquals(listOf("m3/elevated-card"), composed.catalog.components.map { it.componentId })
  }

  /**
   * A policy stating a record id for every component, under [prefix]. Only the prefix varies, which
   * is the difference between the mismatched pair this tests.
   */
  private fun statingRecords(prefix: String, vararg componentIds: String): String {
    val components =
      componentIds.withIndex().joinToString(",") { (index, id) ->
        val leaf = id.substringAfter('/').lowercase()
        val canonical = prefix + canonicalIdAt(id, index).substringAfter('/')
        """"m3/$leaf": {"record": "$canonical",
           "propertyCapabilities": [{"name": "style", "jsonType": "string"}]}"""
      }
    return """
      {
        "schema": "compose-ui-builder-catalog/v1",
        "catalog": { "id": "m3-shaped" },
        "record": { "file": "components.json", "schemaVersion": 1, "components": ${componentIds.size} },
        "statusSemantics": { "platform": "mobile", "componentIdPrefix": "m3/",
          "components": { $components } }
      }
      """
      .trimIndent()
  }

  @Test
  fun `a published file joined to another catalog's record is refused`() {
    // The published file joins on `catalog/…` while the staged record uses another module name. No
    // id collides, yet no policy is found, so components get call-site vocabulary and lose `style`,
    // `color`, etc. Must be refused.
    val result =
      PublishedUiBuilderCatalog.compose(
        statingRecords("catalog/", "Button/Filled", "Card/Elevated", "Dialog/Basic"),
        record("Button/Filled", "Card/Elevated", "Dialog/Basic"),
        exports,
      )
    val unusable = assertIs<PublishedUiBuilderCatalog.Result.Unusable>(result)
    // Both spellings, because the operator reading this one startup line has to be able to tell
    // WHICH of the two files is the stale one, and neither half says that on its own.
    assertTrue("none of the 3 component(s)" in unusable.reason, unusable.reason)
    assertTrue("catalog/androidx.compose.material3." in unusable.reason, unusable.reason)
    assertTrue(":m3/androidx.compose.material3." in unusable.reason, unusable.reason)
    assertTrue("not a pair" in unusable.reason, unusable.reason)
  }

  @Test
  fun `the same file joined to its own record keeps the vocabulary the catalog declared`() {
    // When the join lands, the stated property (`style`) is served, so the refusal above does not
    // ban stating records.
    val result =
      PublishedUiBuilderCatalog.compose(
        statingRecords(":m3/", "Button/Filled", "Card/Elevated", "Dialog/Basic"),
        record("Button/Filled", "Card/Elevated", "Dialog/Basic"),
        exports,
      )
    val composed = assertIs<PublishedUiBuilderCatalog.Result.Composed>(result)
    assertEquals(
      listOf("m3/basic", "m3/elevated", "m3/filled"),
      composed.catalog.components.map { it.componentId }.sorted(),
    )
    assertEquals(
      listOf("style"),
      composed.catalog.components
        .single { it.componentId == "m3/filled" }
        .properties
        .map { it.name },
    )
  }
}
