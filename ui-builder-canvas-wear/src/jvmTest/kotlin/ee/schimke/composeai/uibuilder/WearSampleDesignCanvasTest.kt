package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.EncodedImageFormat

/**
 * The upstream Wear sample designs on the editor's two surfaces: the **canvas**, which unrolls the
 * whole list for editing, and the **device preview**, one screenful in the round frame.
 *
 * `DesignFixturesTest` proves each sample replays, validates and exports; nothing there composes
 * one. This does, and asks the composition two things only the drawing can answer: every label the
 * design authored reached it, and no node fell through to the renderer's "Unsupported component"
 * error. Both surfaces are also written as PNGs to `build/wear-sample-canvas/`, which is what the
 * write-up in `docs/design/UI_BUILDER_WEAR_SAMPLES.md` compares against the native render.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class WearSampleDesignCanvasTest {
  private val samples =
    listOf(
      "wear-starter-greeting",
      "wear-starter-list",
      "jetcaster-wear-library",
      "jetcaster-wear-episode",
      "jetcaster-wear-queue",
    )

  @Test
  fun `every sample's labels reach the canvas, with nothing unsupported`() {
    for (design in samples) {
      val document = document(design)
      runDesktopComposeUiTest(width = 400, height = 1600) {
        setContent { WearCatalogAdapters { UiBuilderSurface(document, unrolled = true) } }
        assertTrue(
          onAllNodesWithText("Unsupported component", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isEmpty(),
          "$design: a node fell through to the canvas's Unsupported component error",
        )
        for (label in document.labels()) {
          assertTrue(
            onAllNodesWithText(label, substring = true, useUnmergedTree = true)
              .fetchSemanticsNodes()
              .isNotEmpty(),
            "$design: the label \"$label\" is not in the canvas's composition",
          )
        }
      }
    }
  }

  @Test
  fun `every sample draws on the canvas and in the round device frame`() {
    val out = File("build/wear-sample-canvas").apply { mkdirs() }
    for (design in samples) {
      val document = document(design)
      // The round device frame: one 192dp screenful at the design's own 2.0 density.
      write(out, "$design-device", 384, 384) { UiBuilderSurface(document, unrolled = false) }
      // The editing canvas: the list unrolled into the stadium, as tall as its content — measured
      // against an unbounded height the way the editor measures its extent, never less than the
      // one screenful the round frame holds itself to.
      val extent = unboundedHeight { UiBuilderSurface(document, unrolled = true) }
      assertTrue(extent >= 384, "$design: the extent is shorter than one screen ($extent px)")
      write(out, "$design-canvas", 384, extent) { UiBuilderSurface(document, unrolled = true) }
    }
  }

  private fun write(
    out: File,
    name: String,
    width: Int,
    height: Int,
    content: @androidx.compose.runtime.Composable () -> Unit,
  ) {
    val image = renderComposeScene(width, height, Density(2f)) { WearCatalogAdapters(content) }
    val png = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "$name did not encode" }
    File(out, "$name.png").writeBytes(png.bytes)
    val lit = litPixels(image)
    assertTrue(lit > 0.01, "$name drew nothing but the ground (${"%.4f".format(lit)} lit)")
  }

  /** The height, in pixels at the design's 2.0 density, the canvas takes with no bound on it. */
  private fun unboundedHeight(content: @androidx.compose.runtime.Composable () -> Unit): Int {
    var measured = 0
    renderComposeScene(384, 384, Density(2f)) {
      Box(
        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
          .requiredWidth(192.dp)
          .onSizeChanged { measured = it.height }
      ) {
        WearCatalogAdapters(content)
      }
    }
    return measured
  }

  private fun litPixels(image: org.jetbrains.skia.Image): Double {
    val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
    var lit = 0
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
      val argb = bitmap.getColor(x, y)
      if (((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF) > 48) lit++
    }
    return lit.toDouble() / (bitmap.width * bitmap.height)
  }

  /** The strings the design authored as text, which the composition must carry. */
  private fun UiBuilderDocument.labels(): List<String> =
    nodes.values
      .filter { it.componentId == "wear-m3/text" || it.componentId == "wear-m3/list-header" }
      .mapNotNull { node ->
        ((node.properties["text"] as? JsonObject)?.get("value"))?.jsonPrimitive?.contentOrNull
      }
      // A line break is two text runs to a substring match; the first line is enough to find it.
      .map { it.substringBefore('\n') }

  private fun document(design: String): UiBuilderDocument {
    val file =
      File(
          System.getProperty("uiBuilderDesignFixturesDir")
            ?: "../docs/design/fixtures/ui-builder/designs"
        )
        .resolve("$design.json")
    return UiBuilderReducer.replay(Json.parseToJsonElement(file.readText()).jsonObject).document
  }
}
