package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.wearcmp.port.LocalWearDeviceConfiguration
import ee.schimke.wearcmp.port.WearDeviceConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The canvas lays a Wear component out against the document's watch, not against the host window.
 *
 * The CMP port reads the device from `LocalWearDeviceConfiguration`, which on Wasm defaults to the
 * browser viewport; unprovided, Wear components were laid out against the editor window. A JVM test
 * can't reach the browser default, so it composes the canvas inside a host that answers with a
 * viewport and asserts the canvas overrides it with the document's frame (as `renderDensity` does
 * for density). The trigger is the catalog's declared platform (`LocalUiBuilderCatalogPlatform`),
 * not component ids.
 *
 * The observable is `CircularProgressIndicator`'s stroke (`if (isSmallScreen()) 8.dp else 12.dp`),
 * counted in pixels; bounds can't show it, since pickers fill whatever they're given.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WearCanvasDeviceSizeTest {

  /** The device the browser reports when nobody overrides it: its viewport. */
  private val browserViewport = WearDeviceConfiguration(screenWidthDp = 1440, screenHeightDp = 900)

  /**
   * The frame is drawn by the adapter the catalog names (`frame/round-screen`), not by recognising
   * a component id, so a screen root under an unknown id draws the same.
   */
  @Test
  fun `a screen root drawn by the frame adapter is framed the same way`() {
    val byAdapter =
      screenFrame(
        "acme/watch-screen",
        adapters = mapOf("acme/watch-screen" to "frame/round-screen"),
      )
    val byId = screenFrame("wear-m3/screen-scaffold", adapters = wearCatalogAdapters)

    assertEquals(
      byId,
      byAdapter,
      "the round-screen drawing should not depend on what the catalog calls its screen root",
    )
    // And it is the frame, not a zero-sized box: the stadium is the frame's width.
    assertEquals(
      FRAME_WIDTH_PX.toFloat(),
      byAdapter.width,
      "the frame's width at the document's diameter",
    )
  }

  @Test
  fun `a browser host's viewport does not resize the watch`() {
    val onTheWatch = strokes(hostDevice = null)
    val inABrowser = strokes(hostDevice = browserViewport)

    assertEquals(
      onTheWatch,
      inABrowser,
      "the same design drew a different ring under a browser host than under the desktop default. " +
        "The canvas is inheriting the host's viewport instead of providing the document's watch — " +
        "see UiBuilderDocument.wearDeviceConfiguration.",
    )
    // Also assert the absolute value: both hosts agreeing doesn't prove either is right.
    assertEquals(WATCH_STROKE_PX, inABrowser.first, "the ring's leading edge on a 192dp watch")
    assertEquals(WATCH_STROKE_PX, inABrowser.second, "the ring's trailing edge on a 192dp watch")
  }

  /**
   * The ring's two edge thicknesses on the horizontal line through its centre, in device pixels:
   * the indicator's stroke and the track's, in that order.
   */
  /** The bounds of a screen root drawn with [rootId], under the adapters a catalog declares. */
  private fun screenFrame(
    rootId: String,
    adapters: Map<String, String>,
  ): UiBuilderPixelBounds {
    var snapshot: UiBuilderInspectionSnapshot? = null
    renderComposeScene(SCENE_PX, SCENE_PX, Density(1f)) {
      CompositionLocalProvider(
        LocalUiBuilderCatalogPlatform provides UiBuilderCatalogPlatform.WEAR.wireValue
      ) {
        UiBuilderSurface(
          document = screen(rootId),
          canvasAdapterIds = adapters,
          onInspectionSnapshot = { snapshot = it },
        )
      }
    }
    return checkNotNull(checkNotNull(snapshot).nodes.firstOrNull { it.nodeId == rootId }?.bounds) {
      "the screen root was not measured"
    }
  }

  /** A screen root under [rootId] holding one label, which is all the frame needs to draw. */
  private fun screen(rootId: String) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "screen-frame",
      title = "Screen frame",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment =
        Json.parseToJsonElement(
            """
            {
              "widthDp": 192, "heightDp": 192, "density": 2.0, "theme": "dark",
              "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr",
              "animations": "settled"
            }
            """
          )
          .jsonObject,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(rootId),
      nodes =
        mapOf(
          rootId to
            UiBuilderNode(
              id = rootId,
              componentId = rootId,
              properties =
                JsonObject(
                  mapOf("timeText" to literal("10:10"), "background" to literal("#000000"))
                ),
              modifiers = JsonArray(emptyList()),
              slots = mapOf("content" to listOf("label")),
            ),
          "label" to
            UiBuilderNode(
              id = "label",
              componentId = "wear-m3/text",
              properties = JsonObject(mapOf("text" to literal("Activity"))),
              modifiers = JsonArray(emptyList()),
              slots = emptyMap(),
            ),
        ),
    )

  private fun strokes(hostDevice: WearDeviceConfiguration?): Pair<Int, Int> {
    var snapshot: UiBuilderInspectionSnapshot? = null
    val image =
      renderComposeScene(SCENE_PX, SCENE_PX, Density(1f)) {
        // Declare the catalog's `platform: "wear"`; without it the canvas leaves the device
        // question to the host.
        CompositionLocalProvider(
          LocalUiBuilderCatalogPlatform provides UiBuilderCatalogPlatform.WEAR.wireValue
        ) {
          if (hostDevice == null) {
            UiBuilderSurface(document = document(), onInspectionSnapshot = { snapshot = it })
          } else {
            CompositionLocalProvider(LocalWearDeviceConfiguration provides hostDevice) {
              UiBuilderSurface(document = document(), onInspectionSnapshot = { snapshot = it })
            }
          }
        }
      }
    val bounds =
      checkNotNull(snapshot).nodes.firstOrNull { it.nodeId == "ring" }?.bounds
        ?: error("the ring was not measured")
    val pixels = image.toComposeImageBitmap().toPixelMap()
    val y = (bounds.y + bounds.height / 2).toInt().coerceIn(0, pixels.height - 1)
    val runs = mutableListOf<Int>()
    var run = 0
    for (x in 0 until pixels.width) {
      // Anything the ring painted, over a transparent ground.
      if (pixels[x, y].alpha > 0.5f) {
        run++
      } else if (run > 0) {
        runs += run
        run = 0
      }
    }
    if (run > 0) runs += run
    assertTrue(runs.size == 2, "expected the ring's two edges on y=$y, found $runs")
    return runs[0] to runs[1]
  }

  /** A 192dp round watch holding one 48dp circular progress indicator, half complete. */
  private fun document() =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "device-size",
      title = "Device size",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment =
        Json.parseToJsonElement(
            """
            {
              "widthDp": 192, "heightDp": 192, "density": 2.0, "theme": "dark",
              "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr",
              "animations": "settled"
            }
            """
          )
          .jsonObject,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("ring"),
      nodes =
        mapOf(
          "ring" to
            UiBuilderNode(
              id = "ring",
              componentId = "wear-m3/progress-indicator",
              properties =
                JsonObject(
                  mapOf(
                    "variant" to literal("circular"),
                    "progress" to literal("0.5"),
                    "enabled" to literal("true"),
                  )
                ),
              modifiers = JsonArray(listOf(size(48))),
              slots = emptyMap(),
            )
        ),
    )

  private fun literal(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))

  private fun size(dp: Int) =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive("size"),
        "widthDp" to JsonPrimitive(dp),
        "heightDp" to JsonPrimitive(dp),
      )
    )

  private companion object {
    /** Room for the 192dp frame at the document's 2.0 density, with the ring centred in it. */
    const val SCENE_PX = 384

    /** The document's diameter at its 2.0 density: 192dp of frame. */
    const val FRAME_WIDTH_PX = 384

    /**
     * `CircularProgressIndicator`'s small-screen stroke: 8dp at 2.0 density (the large screen's
     * 12dp would be 24px).
     */
    const val WATCH_STROKE_PX = 16
  }
}
