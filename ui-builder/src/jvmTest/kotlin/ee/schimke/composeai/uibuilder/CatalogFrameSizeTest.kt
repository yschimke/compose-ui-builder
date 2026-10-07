package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.canvas.UiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.forPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A catalog's own frame sizes — `frame.geometry.sizesDp` — in the Screen dock's frame picker.
 *
 * The case they exist for is a launcher widget catalog (`remote-widgets`), whose designs are
 * measured in launcher grid cells rather than devices: `3x2` is a size a person picks a widget by,
 * and no phone or watch frame is.
 */
class CatalogFrameSizeTest {

  private val launcherWidgets =
    UiBuilderFrameGeometry.from(
      Json.parseToJsonElement(
          """
          {
            "platform": "remote-compose",
            "frame": {
              "adapter": "frame/launcher-widget",
              "geometry": {
                "sizesDp": [
                  { "widthDp": 130, "heightDp": 102, "label": "2x1" },
                  { "widthDp": 203, "heightDp": 220, "label": "3x2" },
                  { "widthDp": 0, "heightDp": 220, "label": "broken" },
                  { "widthDp": 276, "heightDp": 220 }
                ]
              }
            }
          }
          """
        )
        .jsonObject
    )

  private val watch =
    UiBuilderDevicePreset("id:wearos_small_round", "Small round", "Wear OS", 192, 192, 2.0)

  @Test
  fun `the declared sizes are read in order, unlabelled ones named by their dp`() {
    assertEquals(
      listOf(
        UiBuilderFrameGeometry.FrameSize(130, 102, "2x1"),
        UiBuilderFrameGeometry.FrameSize(203, 220, "3x2"),
        UiBuilderFrameGeometry.FrameSize(276, 220, "276 × 220 dp"),
      ),
      launcherWidgets.sizes,
    )
  }

  @Test
  fun `a size is a frame at the design's own density, never a device token`() {
    val preset = launcherWidgets.sizePresets(density = 2.75)[1]
    assertEquals("3x2", preset.label)
    assertEquals(203 to 220, preset.widthDp to preset.heightDp)
    assertEquals(2.75, preset.density)
    assertEquals("size:3x2", preset.id)
    assertEquals(UiBuilderFrameGeometry.CATALOG_SIZES_GROUP, preset.group)
  }

  @Test
  fun `a catalog with sizes opens the picker on them, not on its platform's devices`() {
    val presets = launcherWidgets.sizePresets(2.75) + watch
    assertEquals(
      listOf("2x1", "3x2", "276 × 220 dp"),
      presets.forPlatform(UiBuilderCatalogPlatform.REMOTE_COMPOSE).map { it.label },
    )
    // A catalog that declares none keeps the platform's families.
    assertEquals(listOf(watch), listOf(watch).forPlatform(UiBuilderCatalogPlatform.REMOTE_COMPOSE))
  }
}
