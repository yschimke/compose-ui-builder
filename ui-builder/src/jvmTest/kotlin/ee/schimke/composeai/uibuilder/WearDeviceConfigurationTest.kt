package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The watch the canvas hands the Wear components inside a design.
 *
 * [WearCanvasDeviceSizeTest] proves the canvas *uses* this; this pins the rule it uses, which is
 * the half a picture cannot state. The three sizes are the ones the Screen inspector offers and
 * `ScreenScaffoldContentPaddingTest` in wear-m3-catalog measured, and the fallback is the same one
 * the scaffold stand-in takes: a design opened on a phone frame is a design somebody has not picked
 * a watch for yet, and a 411dp watch is a worse answer than the smallest real one.
 */
class WearDeviceConfigurationTest {

  private fun document(widthDp: Int?, heightDp: Int? = widthDp): UiBuilderDocument =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "device",
      title = "Device",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment =
        JsonObject(
          buildMap {
            widthDp?.let { put("widthDp", JsonPrimitive(it)) }
            heightDp?.let { put("heightDp", JsonPrimitive(it)) }
          }
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = emptyList(),
      nodes = emptyMap(),
    )

  @Test
  fun `each round watch size is answered as itself`() {
    listOf(192, 204, 216, 227, 240).forEach { dp ->
      val device = document(dp).wearDeviceConfiguration()
      assertEquals(dp, device.screenWidthDp, "screen width at ${dp}dp")
      // A round watch's screen is as tall as it is wide, and the port reads `screenHeightDp` for
      // its
      // vertical content padding and its list's minimum vertical padding.
      assertEquals(dp, device.screenHeightDp, "screen height at ${dp}dp")
      assertTrue(device.isScreenRound, "a Wear design is drawn on a round watch at ${dp}dp")
    }
  }

  @Test
  fun `a frame that is not a watch falls back to the reference watch`() {
    // A phone frame, a tablet frame, a nonsense frame and an absent one. `wearScreenWidthDp`'s
    // rule,
    // and the reason it exists: the components must be laid out against a real watch even when the
    // document has not picked one.
    listOf(411, 1280, 0, -1, null).forEach { dp ->
      assertEquals(192, document(dp).wearDeviceConfiguration().screenWidthDp, "screen width at $dp")
    }
  }

  @Test
  fun `the height follows the diameter rather than the frame's own height`() {
    // A tall frame is not a tall watch: the extent a list is drawn at is the canvas's, and a Wear
    // screen's height is its diameter. Reading `heightDp` here would give the port a 400dp-tall
    // watch and a 40dp vertical content padding.
    val device = document(widthDp = 192, heightDp = 400).wearDeviceConfiguration()
    assertEquals(192, device.screenWidthDp)
    assertEquals(192, device.screenHeightDp)
  }
}
