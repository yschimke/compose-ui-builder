package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.canvas.LocalRemoteComposeCaptures
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.preview.capturedInlineDocument
import ee.schimke.composeai.uibuilder.preview.inlineRemoteContentDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

/**
 * An inline subtree drawn twice: as the shapes it describes, and as the pixels a player produces.
 * Asserted by colour: the fill is only reachable through `RcComposePlayer` parsing the document,
 * resolving the `LAYOUT_CUSTOM` config name against this design's registry, and calling back into
 * Compose.
 */
@OptIn(ExperimentalComposeUiApi::class)
class InlineRemoteContentPlaybackTest {

  @Test
  fun `an uncaptured inline node draws its stand-ins, which stack under the label`() {
    val pixels = render(captured = false)

    // The stand-in path draws the design's own Compose in the design's own order: the `Search`
    // label, then the custom component's fill under it. So the top of the frame is not the fill.
    assertNotEquals(FILL, pixels.getColor(PROBE_X, ABOVE_STAND_IN_Y))
    assertEquals(FILL, pixels.getColor(PROBE_X, INSIDE_BOTH_Y))
  }

  @Test
  fun `a captured inline node is played, and the player lays the custom component out`() {
    val pixels = render(captured = true)

    // Only the player lays the fill out from the document's bounds, reaching a row the stand-ins
    // (which stack it under a label) never paint.
    assertEquals(FILL, pixels.getColor(PROBE_X, ABOVE_STAND_IN_Y))
    assertEquals(FILL, pixels.getColor(PROBE_X, INSIDE_BOTH_Y))
  }

  /**
   * The two states as PNGs for a pull request, rendered from the previews' own document (so the
   * text shows), through the same renderer as the tested path.
   */
  @Test
  fun `render before and after evidence`() {
    val output = Path.of("build", "evidence", "inline-remote-content")
    Files.createDirectories(output)
    listOf("before.png" to false, "after.png" to true).forEach { (name, captured) ->
      val image =
        renderComposeScene(WIDTH, HEIGHT, Density(1f)) {
          CompositionLocalProvider(
            LocalRemoteComposeCaptures provides
              { nodeId ->
                if (captured && nodeId == "remote") Result.success(capturedInlineDocument())
                else null
              }
          ) {
            UiBuilderSurface(document = inlineRemoteContentDocument(), editorOverlay = false)
          }
        }
      val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG, 100))
      Files.write(output.resolve(name), data.bytes)
    }
  }

  private fun render(captured: Boolean): Bitmap {
    val document = playableDesign()
    val image =
      renderComposeScene(WIDTH, HEIGHT, Density(1f)) {
        CompositionLocalProvider(
          LocalRemoteComposeCaptures provides
            { nodeId ->
              if (captured && nodeId == "remote") Result.success(capturedInlineDocument()) else null
            }
        ) {
          UiBuilderSurface(document = document, editorOverlay = false)
        }
      }
    return Bitmap().apply { allocN32Pixels(WIDTH, HEIGHT) }.also(image::readPixels)
  }

  /**
   * The preview's own screen with the custom component's content replaced by a solid fill, so a
   * pixel can be named.
   */
  private fun playableDesign(): UiBuilderDocument {
    val design = inlineRemoteContentDocument()
    val fill =
      UiBuilderNode(
        id = "field",
        componentId = "shape/linear-gradient",
        properties =
          JsonObject(
            mapOf("startColor" to property("#FFFF0000"), "endColor" to property("#FFFF0000"))
          ),
        modifiers = JsonArray(listOf(buildJsonObject { put("type", "fillMaxSize") })),
      )
    return design.copy(nodes = design.nodes + (fill.id to fill))
  }

  private fun property(value: String) = buildJsonObject {
    put("type", "string")
    put("value", value)
  }

  private companion object {
    const val WIDTH = 320
    const val HEIGHT = 260

    /** Down the middle of the screen, clear of the frame's own dashed stroke. */
    const val PROBE_X = 160

    /**
     * Between the played custom component's top (y=50) and the stand-in fill's top (y=100, below
     * the `Search` label).
     */
    const val ABOVE_STAND_IN_Y = 60

    /** Inside the fill on both paths, so neither test passes by drawing nothing at all. */
    const val INSIDE_BOTH_Y = 130

    const val FILL = 0xffff0000.toInt()
  }
}
