package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
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
 *
 * The claim under test is the one the capture lane exists to make good on — that a captured
 * `remote-compose/inline` node stops being an approximation. Asserted by colour rather than by
 * structure, because "did a real player draw this" is not a question a node tree can answer: the
 * fill is only reachable through `RcComposePlayer` parsing the document, finding the
 * `LAYOUT_CUSTOM` operation, resolving its config name against the registry this design built, and
 * calling back into Compose inside the bounds it measured.
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

    // Reached only through the player: it parses the document, finds the `LAYOUT_CUSTOM` operation,
    // resolves its config name against the registry this design built from its own
    // `remote-compose/custom` node, and draws that node's `content` inside the bounds **the
    // document** gives it. Which is why the fill reaches a row the stand-ins never paint — the
    // canvas stacked it under a label, and the player laid it out from the document instead.
    assertEquals(FILL, pixels.getColor(PROBE_X, ABOVE_STAND_IN_Y))
    assertEquals(FILL, pixels.getColor(PROBE_X, INSIDE_BOTH_Y))
  }

  /**
   * The two states as PNGs, for a pull request to embed.
   *
   * Rendered from the previews' own document rather than from [playableDesign]: the assertions want
   * a fill with a colour to name, and a reader wants to see the text the custom component actually
   * holds. Both draw the same node through the same renderer, so the picture is of the tested path
   * — regenerated here rather than captured by hand once and described for ever.
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
   * The preview's own screen with the custom component's content replaced by a solid fill.
   *
   * The same three-scope tree the design docs describe, so this tests the shape an author actually
   * builds; only the leaf inside the custom component differs, because a `Text` has no pixel a test
   * can name and a gradient of one colour has nothing but.
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
     * Inside the played document and above everything the stand-in path paints red.
     *
     * The measured boundary: uncaptured, the fill's own box starts at y=100 because the `Search`
     * label is above it; played, the document's custom component starts at y=50. A row between the
     * two is where the difference between describing the content and playing it is visible.
     */
    const val ABOVE_STAND_IN_Y = 60

    /** Inside the fill on both paths, so neither test passes by drawing nothing at all. */
    const val INSIDE_BOTH_Y = 130

    const val FILL = 0xffff0000.toInt()
  }
}
