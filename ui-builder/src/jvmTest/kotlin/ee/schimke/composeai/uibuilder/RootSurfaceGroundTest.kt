package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap

/**
 * A root `m3/surface`'s `containerColor` reaches the frame's ground exactly when the root fills the
 * frame, and [RootSurfaceGround] says so exactly when it does not.
 *
 * Both halves are pinned together on purpose (compose-preview-server #485): the notice describes
 * what the renderer does, so a change to either without the other is the disagreement this test
 * exists to catch.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RootSurfaceGroundTest {

  @Test
  fun `a root surface that fills the frame paints its containerColor to every corner`() {
    val pixels = render(document(fillsFrame = true))

    for ((x, y) in corners()) {
      assertEquals(GROUND, pixels.rgb(x, y), "corner ($x, $y)")
    }
    assertNull(RootSurfaceGround.diagnose(document(fillsFrame = true)))
  }

  @Test
  fun `a root surface that wraps its content leaves the frame to the theme, and is reported`() {
    val document = document(fillsFrame = false)
    val pixels = render(document)

    // Behind its own content the colour is there — the property does work.
    assertEquals(GROUND, pixels.rgb(1, 1), "behind the surface's own content")
    // The far corner is not the surface's: nothing painted it, which is the theme's ground in the
    // editor and the preview backdrop in a native render.
    assertTrue(pixels.getColor(SIZE - 2, SIZE - 2) == 0, "the frame's far corner is unpainted")

    val notice = assertNotNull(RootSurfaceGround.diagnose(document))
    assertEquals("root", notice.nodeId)
    assertTrue("fillMaxSize" in notice.message, notice.message)
    assertTrue("environment.theme" in notice.message, notice.message)
  }

  private fun render(document: UiBuilderDocument): Bitmap {
    val image = renderComposeScene(SIZE, SIZE, Density(1f)) { UiBuilderSurface(document) }
    return Bitmap().apply { allocN32Pixels(SIZE, SIZE) }.also(image::readPixels)
  }

  private fun corners() =
    listOf(1 to 1, SIZE - 2 to 1, 1 to SIZE - 2, SIZE - 2 to SIZE - 2, SIZE / 2 to SIZE / 2)

  private fun Bitmap.rgb(x: Int, y: Int): Int = getColor(x, y) and 0xFFFFFF

  private fun document(fillsFrame: Boolean) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "dark-chat",
      title = "Dark chat",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("root"),
      nodes =
        mapOf(
          "root" to
            UiBuilderNode(
              id = "root",
              componentId = "m3/surface",
              properties = JsonObject(mapOf("containerColor" to colour("#313338"))),
              modifiers =
                JsonArray(
                  if (fillsFrame) listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))
                  else emptyList()
                ),
              slots = mapOf("content" to listOf("text")),
            ),
          "text" to
            UiBuilderNode(
              id = "text",
              componentId = "m3/text",
              properties =
                JsonObject(
                  mapOf(
                    "text" to
                      JsonObject(
                        mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("hello"))
                      )
                  )
                ),
            ),
        ),
    )

  private fun colour(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("color"), "value" to JsonPrimitive(value)))

  private companion object {
    const val SIZE = 64
    const val GROUND = 0x313338
  }
}
