package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
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

  /**
   * The frame the Wear catalog declares, from the golden on the test classpath.
   *
   * Read rather than written: a test that restated the diameters would keep passing after the
   * catalog changed them, which is the failure this reader exists to remove.
   */
  private val declaredFrame =
    CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/wear-m3-capabilities-v1.json")) {
            "missing the wear capability golden on the test resources path"
          }
          .readText()
      )
      .frameGeometry

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
      // A round watch's screen is as tall as it is wide, and the port reads `screenHeightDp` for
      // its
      // vertical content padding and its list's minimum vertical padding.
      assertEquals(dp, device.screenHeightDp, "screen height at ${dp}dp")
      assertTrue(device.isScreenRound, "a Wear design is drawn on a round watch at ${dp}dp")
    }
  }

  @Test
  fun `a frame outside the declared range falls back to the smallest declared diameter`() {
    // A phone frame, a tablet frame, a nonsense frame and an absent one, against a catalog that
    // declares round watches: the components must be laid out against a real watch even when the
    // document has not picked one.
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
    // The other half of the rule, and the reason it is the catalog's rather than this build's: with
    // nothing declared there is no claim to fall back to, so the document's own frame stands. A
    // builder that invented a diameter here would be answering a question the catalog never
    // answered.
    assertEquals(411, document(411).wearDeviceConfiguration().screenWidthDp)
    assertEquals(1280, document(1280).wearDeviceConfiguration().screenWidthDp)
    assertEquals(
      UiBuilderFrameGeometry.REFERENCE_DIAMETER_DP,
      document(null).wearDeviceConfiguration().screenWidthDp,
    )
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
