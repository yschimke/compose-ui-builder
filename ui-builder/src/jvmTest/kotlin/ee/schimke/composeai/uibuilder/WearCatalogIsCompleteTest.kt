package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every Wear component the catalog declares is drawn by the canvas, and vice versa.
 *
 * ## The disagreement this exists to stop
 *
 * `adapterStatus` is what the editor shows an author to say what the canvas can be trusted for, and
 * it is read by `nativeOnlyComponentIds` to decide which components get drawn as a named
 * placeholder instead of as themselves. So a component declaring `supported` while the renderer has
 * no branch for it is the canvas saying "this is what it looks like" about a dashed box.
 *
 * That is exactly what had happened. Twenty-three components declared `supported`; six had a
 * branch. Two that *were* drawn, the card and the button, declared `planned` — the field was wrong
 * in both directions at once, because `wearOwn` inherited whatever status the borrowed Material 3
 * component happened to carry rather than deciding one per component.
 *
 * Nothing caught it. The catalog is a generated golden and the renderer is a `when`, and no test
 * compared them, so the claim and the drawing drifted apart silently and were only noticed by
 * authoring a Wear screen and looking at the picture.
 *
 * ## Why it reads the renderer's source
 *
 * A `when` cannot be enumerated at runtime, and the alternative — compose every component and look
 * at what came out — answers a different and weaker question, because a component can draw
 * *something* without drawing itself. The two sets here are the two claims that must agree: what
 * the catalog tells an author, and what the renderer has a branch for.
 * `WearCanvasDrawsRealComponentsTest` covers the part this cannot: that the branches draw the real
 * components rather than lookalikes.
 */
class WearCatalogIsCompleteTest {
  private val catalogJson =
    checkNotNull(javaClass.getResource("/wear-m3-capabilities-v1.json")) {
        "missing the wear capability golden on the test resources path"
      }
      .readText()

  private val rendererSource =
    java.io
      .File("src/commonMain/kotlin/ee/schimke/composeai/uibuilder/canvas/UiBuilderRenderer.kt")
      .let { relative ->
        // Run from the module directory under Gradle; fall back for a repository-root runner.
        if (relative.isFile) relative else java.io.File("ui-builder/${relative.path}")
      }

  /**
   * The labels the renderer has a dispatch branch for: component ids and canvas adapter ids alike.
   *
   * A component is drawn when the renderer has a case for **its id or for the adapter its catalog
   * declares** — `wasm.canvas` in the golden, which the editor reads into
   * `LocalUiBuilderCanvasAdapters` and the renderer dispatches on. That is the whole point of the
   * adapter field: a catalog whose screen root is not called `wear-m3/screen-scaffold` asks for the
   * same drawing by naming `frame/round-screen`, so a check that only looked for ids would report
   * it as undrawn.
   *
   * Branch labels are read as any quoted string followed by `->` at the dispatch's own indentation,
   * because a branch may name several (`ROUND_SCREEN_FRAME, WEAR_SCREEN_SCAFFOLD ->`) and the id
   * that is still there for a synthesised catalog is a label like any other.
   */
  private fun drawnLabels(): Set<String> {
    assertTrue(rendererSource.isFile, "cannot find the renderer source at $rendererSource")
    val source = rendererSource.readText()
    // A branch may name a constant rather than a literal (`ROUND_SCREEN_FRAME ->`), so the
    // constants in this file are resolved first: a check that only read quoted labels would report
    // a component drawn by a named adapter as undrawn.
    val constants =
      Regex("const val ([A-Za-z0-9_]+) = \"([^\"]+)\"").findAll(source).associate {
        it.groupValues[1] to it.groupValues[2]
      }
    val branchLabels =
      Regex(
          "^\\s*((?:[A-Za-z0-9_]+|\"[A-Za-z0-9/_.-]+\")(?:\\s*,\\s*(?:[A-Za-z0-9_]+|\"[A-Za-z0-9/_.-]+\"))*)\\s*->",
          RegexOption.MULTILINE,
        )
        .findAll(source)
        .flatMap { match -> match.groupValues[1].split(",") }
        .map { it.trim().trim('"') }
        .toSet()
    return branchLabels.map { constants[it] ?: it }.toSet()
  }

  /** Ids the renderer draws, by the component's own id or by the adapter the catalog gives it. */
  private fun drawnIds(): Set<String> =
    declaredIds()
      .filter { id -> id in drawnLabels() || declaredAdapter(id) in drawnLabels() }
      .toSet()

  /** Every id the catalog publishes, whatever its adapter status. */
  private fun declaredIds(): Set<String> =
    Regex("\"componentId\"\\s*:\\s*\"(wear-m3/[a-z0-9-]+)\"")
      .findAll(catalogJson)
      .map { it.groupValues[1] }
      .toSet()

  /** The `wasm.canvas` adapter this catalog names for [id], or the empty string. */
  private fun declaredAdapter(id: String): String {
    val at = catalogJson.indexOf("\"componentId\": \"$id\"")
    val wasmAt = catalogJson.indexOf("\"wasm\"", at)
    val end = catalogJson.indexOf("\"svg\"", wasmAt).let { if (it < 0) catalogJson.length else it }
    return Regex("\"canvas\"\\s*:\\s*\"([^\"]+)\"")
      .find(catalogJson.substring(wasmAt, end))
      ?.groupValues
      ?.get(1)
      .orEmpty()
  }

  /** Ids the catalog declares `supported` on the canvas. */
  private fun declaredSupportedIds(): Set<String> =
    Regex("\"componentId\"\\s*:\\s*\"(wear-m3/[a-z0-9-]+)\"")
      .findAll(catalogJson)
      .map { it.groupValues[1] }
      .filter { id ->
        val at = catalogJson.indexOf("\"componentId\": \"$id\"")
        // The component's own `wasm` block is the next one after its id.
        val wasmAt = catalogJson.indexOf("\"wasm\"", at)
        val status = catalogJson.indexOf("\"adapterStatus\"", wasmAt)
        catalogJson.substring(status, status + 60).contains("\"supported\"")
      }
      .toSet()

  @Test
  fun `the catalog declares exactly what the canvas draws`() {
    val drawn = drawnIds()
    val declared = declaredSupportedIds()

    assertEquals(
      emptySet(),
      declared - drawn,
      "declared `supported` with no renderer branch — the editor is promising a component and " +
        "the canvas will draw a dashed placeholder",
    )
    assertEquals(
      emptySet(),
      drawn - declared,
      "drawn by the renderer but not declared `supported` — the canvas draws the real component " +
        "and the editor tells the author it does not",
    )
  }

  @Test
  fun `the catalog is not empty, so the comparison above means something`() {
    // Both sides are derived by regex. If either stopped matching, the test above would pass by
    // comparing two empty sets and would keep passing forever.
    assertTrue(drawnIds().size >= 20, "found only ${drawnIds().size} renderer branches")
    assertTrue(declaredSupportedIds().size >= 20, "found only ${declaredSupportedIds().size} ids")
  }
}
