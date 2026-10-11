package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.codegen.ComposeAssetAdapter
import ee.schimke.composeai.uibuilder.codegen.ComposeAssetRenderer
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A run of identical siblings is generated as one `repeat`. The fold is spelling, not semantics:
 * the same calls, in the same order, in the same scope. Boundaries pinned: a run of two is written
 * out, a differing cell breaks the run, and identity-bearing subtrees aren't folded.
 */
class RepeatedSiblingFoldTest {
  private val reference by lazy {
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")) as JsonObject
      )
      .document
  }
  private val catalog by lazy {
    CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  }

  @Test
  fun `twelve identical cells become one repeat`() {
    val source = exportSource(contributionRow(cells = 12))

    assertEquals(1, Regex("kotlin\\.repeat\\(12\\) \\{ _ ->").findAll(source).count())
    assertEquals(1, emittedCellBodies(source))
    assertTrue(source.contains("// repeated:12 nodes:cell-0,cell-1,"), source)
  }

  @Test
  fun `a run of two is still written out, because two calls read fine`() {
    val source = exportSource(contributionRow(cells = 2))

    assertFalse(source.contains("kotlin.repeat("), source)
    assertEquals(2, emittedCellBodies(source))
  }

  @Test
  fun `a cell that differs breaks the run at itself and neither side is lost`() {
    val document = contributionRow(cells = 8, distinctAt = 3)
    val source = exportSource(document)

    assertEquals(1, Regex("kotlin\\.repeat\\(3\\) \\{ _ ->").findAll(source).count())
    assertEquals(1, Regex("kotlin\\.repeat\\(4\\) \\{ _ ->").findAll(source).count())
    // Three folded, the odd one out, then four folded: three emitted bodies for eight cells.
    assertEquals(3, emittedCellBodies(source))
    assertTrue(source.contains("// node:cell-3 "), source)
  }

  @Test
  fun `a stableKey is an identity claim, so its cells are printed the long way`() {
    val source = exportSource(contributionRow(cells = 12, stableKeys = true))

    assertFalse(source.contains("kotlin.repeat("), source)
    assertEquals(12, emittedCellBodies(source))
    assertEquals(12, Regex("key\\(\"cell-").findAll(source).count())
  }

  /**
   * Which ids survive a fold, stated exactly. Every folded **sibling** is named in the comment
   * above its run; descendants aren't (the copies are identical), so they're found via their
   * sibling's id.
   */
  @Test
  fun `every folded sibling is named, and a copy's descendants are found through it`() {
    val folded = exportSource(contributionRow(cells = 12))

    (0 until 12).forEach { index ->
      assertTrue(
        folded.contains("cell-$index,") || folded.contains("cell-$index\n"),
        "cell-$index is not named in the generated source",
      )
    }
    assertEquals(1, Regex("// node:cell-\\d+-label ").findAll(folded).count())
    assertTrue(folded.contains("// node:cell-0-label "), folded)
  }

  /**
   * `repeat` and `it` may be captured by design state names, so the fold writes `kotlin.repeat(n) {
   * _ -> … }`, which neither can capture.
   */
  @Test
  fun `state named it or repeat does not capture the folded call or its parameter`() {
    listOf("it", "repeat").forEach { name ->
      val source = exportSource(contributionRow(cells = 12).withState(name))

      assertEquals(1, Regex("kotlin\\.repeat\\(12\\) \\{ _ ->").findAll(source).count())
      assertEquals(1, emittedCellBodies(source))
    }
  }

  /**
   * A design state named `kotlin` would capture the qualifier, so such a design is printed the long
   * way rather than refused.
   */
  @Test
  fun `state named kotlin turns the fold off, because the run qualifies through that root`() {
    val source = exportSource(contributionRow(cells = 12).withState("kotlin"))

    assertFalse(source.contains("kotlin.repeat("), source)
    assertEquals(12, emittedCellBodies(source))
  }

  /**
   * An adapter import (`ComposeAssetAdapter.renderer.importName`) bound to `kotlin` also captures
   * the qualifier; it's caller-supplied, invisible to document checks.
   */
  @Test
  fun `an asset renderer imported as kotlin turns the fold off`() {
    val adapter =
      ComposeAssetAdapter(
        id = "test-adapter/v1",
        bindings = emptyMap(),
        renderer = ComposeAssetRenderer(symbol = "kotlin", importName = "app.artwork.kotlin"),
      )
    val document = contributionRow(cells = 12)
    val result = CapabilityComposeCodeExporter.export(document, catalog, adapter)
    val source = assertNotNull(result.source)

    assertTrue(result.successful, result.diagnostics.joinToString { it.message })
    assertFalse(source.contains("kotlin.repeat("), source)
    assertEquals(12, emittedCellBodies(source))
  }

  /**
   * An import binds its alias, not its last segment (`import app.artwork.Renderer as kotlin` binds
   * `kotlin`).
   */
  @Test
  fun `a renderer aliased to kotlin turns the fold off as surely as one named it`() {
    val adapter =
      ComposeAssetAdapter(
        id = "test-adapter/v1",
        bindings = emptyMap(),
        renderer =
          ComposeAssetRenderer(
            symbol = "kotlin",
            importName = "app.artwork.Renderer as kotlin",
          ),
      )
    val result = CapabilityComposeCodeExporter.export(contributionRow(cells = 12), catalog, adapter)
    val source = assertNotNull(result.source)

    assertTrue(result.successful, result.diagnostics.joinToString { it.message })
    assertFalse(source.contains("kotlin.repeat("), source)
    assertEquals(12, emittedCellBodies(source))
  }

  /** A real lazy carousel emits one indexed branch per authored item rather than a row helper. */
  @Test
  fun `a run of identical carousel items keeps one branch per index`() {
    val source = exportSource(carousel(items = 5))

    assertFalse(source.contains("BuilderHorizontalCarousel("), source)
    assertTrue(source.contains("HorizontalUncontainedCarousel("), source)
    assertEquals(5, emittedCellBodies(source))
  }

  /**
   * Depth changes nothing about the answer: memoised signatures and the short-list shortcut must
   * not lose the fold at the bottom of a deep single-child chain.
   */
  @Test
  fun `a run nested under a chain of single-child containers still folds`() {
    val source = exportSource(nested(depth = 24, cells = 12))

    assertEquals(1, Regex("kotlin\\.repeat\\(12\\) \\{ _ ->").findAll(source).count())
    assertEquals(1, emittedCellBodies(source))
  }

  /** The contribution row, buried [depth] single-child columns down. */
  private fun nested(depth: Int, cells: Int): UiBuilderDocument {
    val base = contributionRow(cells = cells)
    val wrappers =
      (0 until depth).associate { level ->
        "wrap-$level" to
          UiBuilderNode(
            id = "wrap-$level",
            componentId = "layout/column",
            slots =
              mapOf("children" to listOf(if (level == depth - 1) "root" else "wrap-${level + 1}")),
          )
      }
    return base.copy(nodes = base.nodes + wrappers, roots = listOf("wrap-0"))
  }

  /** Identical cards in a carousel, which is the only container whose items it accepts. */
  private fun carousel(items: Int): UiBuilderDocument {
    val itemIds = (0 until items).map { "cell-$it" }
    val base = contributionRow(cells = items)
    val cards = itemIds.associateWith { id ->
      UiBuilderNode(
        id = id,
        componentId = "m3/card",
        slots = mapOf("content" to listOf("$id-label")),
      )
    }
    return base.copy(
      nodes =
        base.nodes +
          cards +
          ("row" to
            UiBuilderNode(
              id = "row",
              componentId = "layout/horizontal-carousel",
              properties =
                JsonObject(
                  mapOf(
                    "itemWidthDp" to JsonObject(mapOf("value" to JsonPrimitive(128))),
                    "scrollStateKey" to JsonObject(mapOf("value" to JsonPrimitive("carousel"))),
                  )
                ),
              slots = mapOf("items" to itemIds),
            ))
    )
  }

  private fun UiBuilderDocument.withState(name: String) =
    copy(
      stateVariables =
        JsonObject(
          mapOf(
            name to
              JsonObject(
                mapOf(
                  "valueType" to JsonPrimitive("string"),
                  "initialValue" to JsonPrimitive("x"),
                )
              )
          )
        )
    )

  /**
   * How many cell bodies the source holds, counted from each emitted node's located comment
   * (helpers contain `Surface(` too).
   */
  private fun emittedCellBodies(source: String): Int =
    Regex("// node:cell-\\d+ ").findAll(source).count()

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun exportSource(document: UiBuilderDocument): String {
    val result = CapabilityComposeCodeExporter.export(document, catalog)
    assertTrue(result.successful, result.diagnostics.joinToString { it.message })
    return assertNotNull(result.source)
  }

  /** A Column holding a Row of [cells] colour swatches — the shape that provoked this. */
  private fun contributionRow(
    cells: Int,
    distinctAt: Int? = null,
    stableKeys: Boolean = false,
  ): UiBuilderDocument {
    val cellIds = (0 until cells).map { "cell-$it" }
    val nodes =
      buildMap<String, UiBuilderNode> {
        put(
          "root",
          UiBuilderNode(
            id = "root",
            componentId = "layout/column",
            slots = mapOf("children" to listOf("row")),
          ),
        )
        put(
          "row",
          UiBuilderNode(
            id = "row",
            componentId = "layout/row",
            slots = mapOf("children" to cellIds),
          ),
        )
        cellIds.forEachIndexed { index, id ->
          val colour = if (index == distinctAt) "#39D353" else "#EBEDF0"
          put(
            id,
            UiBuilderNode(
              id = id,
              componentId = if (stableKeys) "m3/card" else "m3/surface",
              properties =
                JsonObject(
                  buildMap {
                    put("containerColor", JsonObject(mapOf("value" to JsonPrimitive(colour))))
                    if (stableKeys) {
                      put("stableKey", JsonObject(mapOf("value" to JsonPrimitive(id))))
                    }
                  }
                ),
              slots = mapOf("content" to listOf("$id-label")),
            ),
          )
          put(
            "$id-label",
            UiBuilderNode(
              id = "$id-label",
              componentId = "m3/text",
              properties =
                JsonObject(mapOf("text" to JsonObject(mapOf("value" to JsonPrimitive(""))))),
            ),
          )
        }
      }
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "contribution-graph",
      title = "Contribution graph",
      revision = 1,
      // The pin and the environment come from the frozen benchmark document: they are what the
      // export gate checks, and restating them here would be a second copy to keep in step.
      catalogPin = reference.catalogPin,
      environment = reference.environment,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("root"),
      nodes = nodes,
    )
  }
}
