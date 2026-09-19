package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
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
 * ## The bug this pins
 *
 * `androidx.wear.compose` reads the device out of Android's `Configuration`, which does not exist
 * off Android. The CMP port replaces it with one composition local, `LocalWearDeviceConfiguration`,
 * and each platform answers it for itself: the JVM takes the 192dp reference watch, and the browser
 * reports **its own viewport** — `window.innerWidth` / `window.innerHeight` — because a viewport is
 * the closest thing a browser has to `Configuration.screenWidthDp`.
 *
 * The canvas never provided it, so on Wasm every Wear component that branches on the screen was
 * laid out against the editor window. Measured on the real `ScreenScaffold`, whose content padding
 * is 5.2% of the screen's width and 10% of its height: 10dp x 20dp on the watch and **75dp x 90dp
 * in a 1440x900 window**. The same design on the desktop canvas got the watch, so two lanes of one
 * canvas disagreed about what a watch is.
 *
 * ## Why the host's answer has to be provided from outside
 *
 * The JVM's own answer is the reference watch, so changing the scene size proves nothing here — the
 * browser's behaviour is not reachable from a JVM test. What is reachable is the *shape* of it: a
 * host that answers with its viewport. So the test composes the canvas inside exactly that host,
 * the way `window.innerWidth` reaches it on Wasm, and asserts the canvas overrides it with the
 * document's frame.
 *
 * Overriding is the point rather than a side effect, and it is the same call `renderDensity`
 * already makes: the design is drawn at the frame and the density its environment names, not the
 * host's, so the device it is drawn *for* has to be the document's too. A canvas that took the
 * host's answer would draw a 192dp watch with a 1440dp watch's insides.
 *
 * The trigger is the platform the *catalog* declares, so the test says "this is a Wear catalog" the
 * way the editor does — `LocalUiBuilderCatalogPlatform` — rather than relying on the component ids
 * in the document happening to be ones this build recognises.
 *
 * ## Why a ring, and why pixels
 *
 * The observable has to be something a *component* does with the screen size rather than something
 * the document says. `CircularProgressIndicator` is the cleanest: its stroke is `if
 * (isSmallScreen()) 8.dp else 12.dp`, so the number of pixels in the ring's edge is a direct read
 * of which screen the canvas thought it was drawing for — 16 or 24 at the Wear template's 2.0
 * density.
 *
 * A bounds assertion cannot make this point — `DatePicker` and friends fill whatever height they
 * are given, so their measured box says nothing about the screen they think they are on. That is
 * why this one counts pixels, and why `WearScreenParityTest` next door, which does measure the
 * template's boxes, could not have caught it either.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WearCanvasDeviceSizeTest {

  /**
   * The device the browser reports: its viewport, which is what the Wasm host hands the port when
   * nobody overrides it.
   */
  private val browserViewport = WearDeviceConfiguration(screenWidthDp = 1440, screenHeightDp = 900)

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
    // Asserted as well, because "both hosts agree" is also true of two hosts that are both wrong.
    // 8dp at 2.0 density is 16px; the large screen's stroke is 12dp, 24px, and a canvas reading the
    // browser's 1440dp viewport drew exactly that.
    assertEquals(WATCH_STROKE_PX, inABrowser.first, "the ring's leading edge on a 192dp watch")
    assertEquals(WATCH_STROKE_PX, inABrowser.second, "the ring's trailing edge on a 192dp watch")
  }

  /**
   * The ring's two edge thicknesses on the horizontal line through its centre, in device pixels:
   * the indicator's stroke and the track's, in that order.
   */
  private fun strokes(hostDevice: WearDeviceConfiguration?): Pair<Int, Int> {
    var snapshot: UiBuilderInspectionSnapshot? = null
    val image =
      renderComposeScene(SCENE_PX, SCENE_PX, Density(1f)) {
        // The catalog the design is authored against, declared the way a served catalog declares
        // itself: `platform: "wear"`. Without it the canvas is drawing for no declared platform, so
        // it leaves the device question to the host — which is what the second half of this test
        // needs to be able to see.
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

    /**
     * `CircularProgressIndicator`'s stroke on a small screen: 8dp at 2.0 density. The large
     * screen's is 12dp, which is 24px — what a canvas reading a 1440dp browser viewport drew.
     */
    const val WATCH_STROKE_PX = 16
  }
}
