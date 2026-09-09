package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A run of identical siblings is generated as one `repeat`.
 *
 * The document has no loop, so a twelve-cell contribution row is twelve nodes — and the export
 * printed twelve identical `Surface` calls, which is a faithful screen nobody can read. The fold is
 * spelling, not semantics: the same calls, in the same order, in the same parent scope.
 *
 * What each test here pins is a boundary of that claim rather than the happy path alone: a run of
 * two is still written out, a cell that differs breaks the run at itself, and a subtree claiming an
 * identity keeps every one of its copies.
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
   * Which ids survive a fold, stated exactly.
   *
   * Every folded **sibling** is named in the comment above its run. Their descendants are not, and
   * deliberately: the emitted body carries the located comments of the first copy, and naming
   * `cell-1-label` through `cell-11-label` too would put back, as comments, the text the fold just
   * removed. The copies are identical — that is the premise — so a descendant is found through the
   * sibling id that stands for it.
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
   * `repeat` and the `it` it binds are names like any other, and the emitted form owns that.
   *
   * `exportedStateIdentifier` leaves both alone, so a design declaring state called either gets a
   * local of that name in the generated function — an unqualified call would resolve to it, and the
   * lambda's implicit parameter would shadow a read of it. `kotlin.repeat(n) { _ -> … }` can be
   * captured by neither, so the fold still happens and the cells still read what they read.
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
   * The one name left to protect, protected the same way as the others.
   *
   * A design whose state is called `kotlin` gets a local that captures the qualifier a folded run
   * writes, so it is printed the long way rather than refused — the design is legal and the canvas
   * draws it.
   */
  @Test
  fun `state named kotlin turns the fold off, because the run qualifies through that root`() {
    val source = exportSource(contributionRow(cells = 12).withState("kotlin"))

    assertFalse(source.contains("kotlin.repeat("), source)
    assertEquals(12, emittedCellBodies(source))
  }

  /**
   * The other way the qualifier can be taken: an adapter's own import.
   *
   * `ComposeAssetAdapter.renderer.importName` is supplied by the caller rather than by the design,
   * so no check over the document can see it — and a declaration imported as `kotlin` would capture
   * the qualifier and leave `repeat` unresolved.
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
   * An import binds its alias, not its last segment.
   *
   * `import app.artwork.Renderer as kotlin` puts `kotlin` in scope, and reading the dotted tail of
   * that string answers `Renderer as kotlin` — a name nothing collides with, so the fold would have
   * gone ahead and qualified through a captured name.
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

  /**
   * The carousel folds too, wrapper and all.
   *
   * `BuilderHorizontalCarousel` is a `Row` and its items carry no key, so it is a non-lazy
   * container like any other — but it puts a `Box(Modifier.width(itemWidth))` around each child,
   * and that expression is the same for every one of them. So the run stands for the wrapper as
   * well as the item.
   */
  @Test
  fun `a run of identical carousel items folds, and the item wrapper folds with it`() {
    val source = exportSource(carousel(items = 5))

    assertEquals(1, Regex("kotlin\\.repeat\\(5\\) \\{ _ ->").findAll(source).count())
    assertEquals(1, Regex("Box\\(Modifier\\.width\\(itemWidth\\)\\)").findAll(source).count())
    assertEquals(1, emittedCellBodies(source))
  }

  /**
   * Depth changes nothing about the answer, only what it costs to reach.
   *
   * Signatures are memoised and a list too short to hold a run is emitted without computing one, so
   * this pins that neither shortcut loses the fold at the bottom of a deep chain of single-child
   * containers — the shape both were introduced for.
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
   * How many cell bodies the source actually holds.
   *
   * Counted from the located node comment each emitted node carries rather than from the call — the
   * generated file's compatibility helpers contain `Surface(` of their own, and a count that
   * included those would move whenever a helper did.
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
