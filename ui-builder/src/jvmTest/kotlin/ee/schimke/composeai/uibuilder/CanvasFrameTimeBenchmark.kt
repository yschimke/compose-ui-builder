package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume

/**
 * How long one editor edit takes to reach the canvas: the reducer, then recomposition, layout and
 * draw of the Jetcaster fixture, for a scripted sequence — select, drag a property through twenty
 * values, insert, undo.
 *
 * **This is the JVM desktop backend, not the Wasm canvas.** The product spec's 39.5ms p95 against a
 * 16.67ms frame (#193) was taken in the browser, where Wasm compilation, browser scheduling and
 * browser rendering all add to it; none of those are here, so these numbers are their own series,
 * not comparable to that baseline. What they do catch is the shared part — the reducer and the
 * canvas composition both backends run — getting slower. The browser measurement belongs with the
 * headless-Chromium smoke (#185).
 *
 * It writes the numbers; it does not judge them. A shared runner is too noisy for an absolute line
 * to be a stable pass/fail, so a gate, when it comes, compares against the base branch's number
 * from the same runner class.
 *
 * Off in the ordinary test run, where a timing loop would only slow `check` down. Run it with
 * `./gradlew :ui-builder:canvasFrameBenchmark`, which writes
 * `build/reports/ui-builder/canvas-frame-times.json`.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasFrameTimeBenchmark {
  @BeforeTest
  fun onlyWhenAsked() {
    Assume.assumeTrue(
      "Run with ./gradlew :ui-builder:canvasFrameBenchmark",
      System.getProperty(ENABLED_PROPERTY) == "true",
    )
  }

  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `scripted edits reach the canvas`() =
    runDesktopComposeUiTest(width = 1200, height = 900) {
      var state by mutableStateOf(reducer.initial(document, selectedNodeId = null))
      setContent {
        MaterialTheme {
          UiBuilderSurface(
            state.document,
            editorOverlay = true,
            selectedNodeId = state.selectedNodeId,
          )
        }
      }
      waitForIdle()

      val samples = mutableMapOf<String, MutableList<Long>>()
      repeat(WARMUP_PASSES + MEASURED_PASSES) { pass ->
        for ((step, event) in script(state, pass)) {
          val start = System.nanoTime()
          state = reducer.reduce(state, event)
          waitForIdle()
          val elapsed = System.nanoTime() - start
          if (pass >= WARMUP_PASSES) samples.getOrPut(step) { mutableListOf() } += elapsed
        }
      }

      val report = report(samples)
      System.getProperty(REPORT_PROPERTY)?.let { path ->
        File(path).apply { parentFile.mkdirs() }.writeText(report)
      }
      println(report)
      assertTrue(samples.values.all { it.isNotEmpty() }, "every step was measured")
    }

  /**
   * One pass of the edit sequence, as named steps. The drag commits the same text property twenty
   * times, the way a slider or a typed value arrives a frame at a time; the insert lands in the
   * Discover grid and the undo takes it back out, so every pass starts from the same tree.
   *
   * The select alternates between two nodes by [pass]. Undo restores the selection from before the
   * insert, so selecting the same node every pass would time a no-op instead of the overlay moving.
   */
  private fun script(
    state: UiBuilderEditorState,
    pass: Int,
  ): List<Pair<String, UiBuilderEditorEvent>> {
    val text =
      state.document.nodes.values.first { it.componentId == "m3/text" && "text" in it.properties }
    val target =
      requireNotNull(reducer.dropTarget(reducer.initial(state.document, GRID_ID), "m3/text"))
    return buildList {
      add("select" to UiBuilderEditorEvent.SelectNode(if (pass % 2 == 0) text.id else GRID_ID))
      repeat(DRAG_STEPS) { step ->
        add("drag" to UiBuilderEditorEvent.CommitProperty(text.id, "text", "Frame $step"))
      }
      add(
        "insert" to UiBuilderEditorEvent.InsertComponent(componentId = "m3/text", target = target)
      )
      add("undo" to UiBuilderEditorEvent.Undo)
    }
  }

  private fun report(samples: Map<String, List<Long>>): String {
    val all = samples.values.flatten()
    val json = buildJsonObject {
      put("fixture", "jetcaster-discover")
      // Not the Wasm canvas: see the class comment before comparing with a browser number.
      put("backend", "jvm-desktop")
      put("warmupPasses", WARMUP_PASSES)
      put("measuredPasses", MEASURED_PASSES)
      put("all", summary(all))
      putJsonObject("steps") { samples.forEach { (step, values) -> put(step, summary(values)) } }
    }
    return Json { prettyPrint = true }.encodeToString(json)
  }

  private fun summary(nanos: List<Long>) = buildJsonObject {
    val sorted = nanos.sorted()
    put("count", sorted.size)
    put("p50Ms", millis(sorted.percentile(0.50)))
    put("p95Ms", millis(sorted.percentile(0.95)))
    put("maxMs", millis(sorted.last()))
  }

  private fun List<Long>.percentile(fraction: Double): Long =
    this[((size - 1) * fraction).toInt().coerceIn(0, size - 1)]

  private fun millis(nanos: Long) = JsonPrimitive((nanos / 10_000) / 100.0)

  private fun resource(path: String): String =
    checkNotNull(javaClass.getResource(path)) { "missing $path" }.readText()

  private companion object {
    const val ENABLED_PROPERTY = "uiBuilder.canvasBenchmark"
    const val REPORT_PROPERTY = "uiBuilder.canvasBenchmark.report"
    const val GRID_ID = "discover-grid"
    const val DRAG_STEPS = 20
    const val WARMUP_PASSES = 3
    const val MEASURED_PASSES = 10
  }
}
