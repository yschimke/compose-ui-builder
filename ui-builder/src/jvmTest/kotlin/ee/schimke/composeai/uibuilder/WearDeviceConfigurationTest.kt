package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.UiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.wearDeviceConfiguration
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The watch the canvas hands the Wear components inside a design. [WearCanvasDeviceSizeTest] proves
 * the canvas uses it; this pins the rule: the Screen inspector's three sizes (measured by
 * `ScreenScaffoldContentPaddingTest` in wear-m3-catalog), with the smallest real watch as the
 * fallback for an unpicked (e.g. phone) frame.
 */
class WearDeviceConfigurationTest {

  /** The frame a Wear catalog declares: three round sizes, each with its content padding. */
  private val declaredFrame = SyntheticCatalogs.wear.frameGeometry

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
      val device = document(dp).wearDeviceConfiguration(declaredFrame)
      assertEquals(dp, device.screenWidthDp, "screen width at ${dp}dp")
      // A round screen is as tall as it is wide; the port reads `screenHeightDp` for vertical
      // padding.
      assertEquals(dp, device.screenHeightDp, "screen height at ${dp}dp")
      assertTrue(device.isScreenRound, "a Wear design is drawn on a round watch at ${dp}dp")
    }
  }

  @Test
  fun `a frame outside the declared range falls back to the smallest declared diameter`() {
    // Phone, tablet, nonsense and absent frames all fall back to a real declared watch.
    listOf(411, 1280, 0, -1, null).forEach { dp ->
      assertEquals(
        declaredFrame.contentPadding.first().screenDp,
        document(dp).wearDeviceConfiguration(declaredFrame).screenWidthDp,
        "screen width at $dp",
      )
    }
  }

  @Test
  fun `a catalog that declares no frame draws the frame the document names`() {
    // With no frame declared by the catalog there's nothing to fall back to, so the document's own
    // width stands.
    assertEquals(411, document(411).wearDeviceConfiguration().screenWidthDp)
    assertEquals(1280, document(1280).wearDeviceConfiguration().screenWidthDp)
    assertEquals(
      UiBuilderFrameGeometry.REFERENCE_DIAMETER_DP,
      document(null).wearDeviceConfiguration().screenWidthDp,
    )
  }

  @Test
  fun `the height follows the diameter rather than the frame's own height`() {
    // A tall frame is the canvas's extent, not a tall watch: height is the diameter.
    val device = document(widthDp = 192, heightDp = 400).wearDeviceConfiguration()
    assertEquals(192, device.screenWidthDp)
    assertEquals(192, device.screenHeightDp)
  }
}
