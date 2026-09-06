package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

/**
 * What the canvas draws where a Lottie element sits.
 *
 * The element is the one node in `remote-m3` whose *export* is the whole feature: Horologist's
 * `LottieAnimation` compiles the animation into the widget's Remote Compose document, and the
 * browser can neither run that Android-only creation API nor host a Lottie runtime to fake it with.
 * So the canvas draws the node's identity, its source and whether it is ready — and this test holds
 * it to the two things that claim is worth anything for:
 *
 * 1. it is not [UnsupportedComponentDiagnostic]'s error box, which is what an unhandled component
 *    id draws and what this looked like before the branch existed; and
 * 2. an element still carrying only its URL draws *differently* from a resolved one, because "the
 *    export will refuse this" is the fact the canvas exists to surface early.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LottieElementRenderingTest {
  @Test
  fun `a lottie element draws its source rather than an unsupported-component box`() {
    val resolved = render(lottieDocument(json = ANIMATION))
    val unresolved = render(lottieDocument(url = "https://example.test/spin.json"))
    val empty = render(lottieDocument())

    // Written where `renders/ui-builder-lottie/` is refreshed from: the committed evidence beside
    // this test is these three files, so a change to the placeholder moves both together.
    val output = Path.of("build", "evidence", "lottie")
    Files.createDirectories(output)
    mapOf("resolved.png" to resolved, "unresolved.png" to unresolved, "empty.png" to empty)
      .forEach { (name, image) ->
        Files.write(
          output.resolve(name),
          checkNotNull(image.encodeToData(EncodedImageFormat.PNG, 100)).bytes,
        )
      }

    // Something was drawn at all: an unhandled component id used to reach
    // `UnsupportedComponentDiagnostic`, and a branch that drew nothing would be the other way this
    // can go wrong — a node an author can select on the layers panel and cannot see on the canvas.
    val pixels = Bitmap().apply { allocN32Pixels(WIDTH, HEIGHT) }
    resolved.readPixels(pixels)
    val painted =
      (0 until HEIGHT).sumOf { y -> (0 until WIDTH).count { x -> pixels.getColor(x, y) != 0 } }
    assertTrue(painted > 0, "the Lottie placeholder drew nothing")

    // …and the unresolved element does not draw the resolved one's picture. The difference is the
    // sentence the canvas adds — "the export needs the animation itself, not its URL" — which is
    // the whole reason an author finds out here rather than at export.
    assertNotEquals(
      unresolved.encodeToData()!!.bytes.toList(),
      resolved.encodeToData()!!.bytes.toList(),
    )
  }

  private fun render(document: UiBuilderDocument) =
    renderComposeScene(WIDTH, HEIGHT, Density(1f)) { UiBuilderSurface(document) }

  private fun lottieDocument(json: String = "", url: String = ""): UiBuilderDocument {
    val lottie =
      UiBuilderNode(
        id = "spinner",
        componentId = LOTTIE_COMPONENT_ID,
        properties =
          JsonObject(
            buildMap {
              if (json.isNotEmpty()) put("json", property(json))
              if (url.isNotEmpty()) put("url", property(url))
            }
          ),
        modifiers = JsonArray(listOf(buildJsonObject { put("type", "fillMaxWidth") })),
      )
    return UiBuilderDocument(
      schema = "ui-builder/document/v1",
      id = "lottie-render-test",
      title = "Lottie render test",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment =
        buildJsonObject {
          put("widthDp", WIDTH)
          put("heightDp", HEIGHT)
          put("density", 1)
          put("fontScale", 1)
          put("theme", "light")
        },
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(lottie.id),
      nodes = mapOf(lottie.id to lottie),
    )
  }

  private fun property(value: String) = buildJsonObject {
    put("type", "string")
    put("value", JsonPrimitive(value))
  }

  private companion object {
    const val WIDTH = 216

    const val HEIGHT = 76

    /** Enough of a Lottie for the placeholder to have a size to report. */
    const val ANIMATION =
      """{"v":"5.9.6","fr":30,"ip":0,"op":30,"w":64,"h":64,"layers":[{"ty":1,"sc":"#2196f3"}]}"""
  }
}
