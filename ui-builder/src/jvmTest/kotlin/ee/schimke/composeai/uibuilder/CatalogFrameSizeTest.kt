package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.canvas.UiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.forPlatform
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentValidationError
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
                  { "widthDp": { "value": 130 }, "heightDp": [102], "label": "malformed" },
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

  /**
   * A grid size is smaller than any screen — 2x1 is 130 × 102 dp — so a launcher widget takes the
   * widget minimum, as a Wear widget does, or picking one of its own sizes is refused.
   */
  @Test
  fun `a launcher widget accepts its own grid sizes, a screen still does not`() {
    fun document(root: String) = Json {
      ignoreUnknownKeys = true
    }
      .decodeFromString(
        UiBuilderDocument.serializer(),
        """
          {
            "schema": "compose-ui-builder-document/v1-candidate",
            "id": "w", "title": "w", "revision": 0,
            "catalogPin": { "systemId": "remote-widgets", "catalogRevision": "c",
              "capabilityDigest": "c", "nativeRuntimeId": "c" },
            "environment": { "widthDp": 203, "heightDp": 220, "density": 2.75 },
            "stateVariables": {},
            "roots": ["root"],
            "nodes": { "root": { "id": "root", "componentId": "$root", "properties": {},
              "modifiers": [], "slots": {} } }
          }
          """,
      )
    val widget = document("remote-widgets/launcher-widget")
    val twoByOne = widget.screenEnvironmentSettings().copy(widthDp = 130, heightDp = 102)
    assertNull(widget.screenEnvironmentValidationError(twoByOne))
    val screen = document("layout/box")
    assertNotNull(screen.screenEnvironmentValidationError(twoByOne))
  }
}
