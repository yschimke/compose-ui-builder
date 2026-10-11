package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every Wear component the catalog declares is drawn by the canvas, and vice versa. `adapterStatus`
 * tells authors what the canvas can be trusted for and drives `nativeOnlyComponentIds`, so a
 * `supported` component without a renderer branch would draw a dashed box under a false claim.
 *
 * Reads the renderer's source because a `when` can't be enumerated at runtime, and composing each
 * component only shows that it draws *something*. `WearCanvasDrawsRealComponentsTest` checks the
 * branches draw the real components.
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
   * The labels the renderer has a dispatch branch for: component ids and canvas adapter ids
   * (`wasm.canvas`, read via `LocalUiBuilderCanvasAdapters`) alike, so a screen root named
   * differently but declaring `frame/round-screen` counts as drawn. Labels are quoted strings
   * before `->` at the dispatch's indentation, possibly several per branch.
   */
  private fun drawnLabels(): Set<String> {
    assertTrue(rendererSource.isFile, "cannot find the renderer source at $rendererSource")
    val source = rendererSource.readText()
    // Constants are resolved first, since a branch may name one (`ROUND_SCREEN_FRAME ->`).
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
