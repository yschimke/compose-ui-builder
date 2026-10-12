package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json

/**
 * The colours, outlines and enum states the catalog's selection controls, icon buttons, list items
 * and rails are drawn with: each lowers to the bundle or member Compose takes, so a document can
 * spell the kit's error radio, its outlined icon button or its rail's arrangement.
 */
class ControlColourVocabularyTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun root(
    componentId: String,
    properties: String,
  ): ee.schimke.composeai.discovery.ScreenNode {
    val document =
      json.decodeFromString<DesignDocumentV1>(
        """
        {
          "schema": "compose-ui-builder-document/v1-candidate",
          "id": "controls", "title": "Controls", "revision": 0,
          "catalogPin": {"systemId": "m3-catalog", "catalogRevision": "candidate",
            "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
          "environment": {"widthDp": 360, "heightDp": 640, "density": 1.0, "theme": "light",
            "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
          "roots": ["control"],
          "nodes": {"control": {"id": "control", "componentId": "$componentId",
            "properties": {$properties}}}
        }
        """
      )
    val projected = ScreenDocumentProjection.project(document)
    assertIs<ScreenDocumentProjection.Outcome.Projected>(projected, projected.toString())
    return projected.document.root
  }

  private fun construct(value: ScreenValue?): ScreenValue.Construct =
    assertIs<ScreenValue.Construct>(value, value.toString())

  @Test
  fun `an icon button's content colour is its style's IconButtonColors`() {
    val colors =
      construct(
        root(
            "m3/icon-button",
            """"contentColor": {"type": "colorToken", "value": "onSurfaceVariant"}""",
          )
          .arguments["colors"]
      )
    assertEquals(
      "androidx.compose.material3.IconButtonDefaults.iconButtonColors",
      colors.callableFqn,
    )
    assertEquals(setOf("contentColor"), colors.named.keys)
  }

  @Test
  fun `an outlined icon button takes its outline as a BorderStroke`() {
    val node =
      root(
        "m3/outlined-icon-button",
        """"borderColor": {"type": "colorToken", "value": "outlineVariant"},
           "contentColor": {"type": "colorToken", "value": "onSurfaceVariant"}""",
      )
    val border = construct(node.arguments["border"])
    assertEquals("androidx.compose.foundation.BorderStroke", border.callableFqn)
    assertEquals(2, border.positional.size)
    assertEquals(
      "androidx.compose.material3.IconButtonDefaults.outlinedIconButtonColors",
      construct(node.arguments["colors"]).callableFqn,
    )
  }

  @Test
  fun `an error radio is the error role in its RadioButtonColors`() {
    val colors =
      construct(
        root(
            "m3/radio-button",
            """"selectedColor": {"type": "colorToken", "value": "error"},
               "unselectedColor": {"type": "colorToken", "value": "error"}""",
          )
          .arguments["colors"]
      )
    assertEquals("androidx.compose.material3.RadioButtonDefaults.colors", colors.callableFqn)
    assertEquals(setOf("selectedColor", "unselectedColor"), colors.named.keys)
  }

  @Test
  fun `a list item's container colour is its ListItemColors`() {
    val colors =
      construct(
        root("m3/list-item", """"containerColor": {"type": "color", "value": "#00000000"}""")
          .arguments["colors"]
      )
    assertEquals("androidx.compose.material3.ListItemDefaults.colors", colors.callableFqn)
  }

  @Test
  fun `rail arrangement and tri-state state are enum members`() {
    val rail =
      root("m3/wide-navigation-rail", """"arrangement": {"type": "enum", "value": "center"}""")
    assertEquals(
      listOf("Center"),
      assertIs<ScreenValue.Reference>(rail.arguments["arrangement"]).members,
    )
    val box =
      root("m3/tri-state-checkbox", """"state": {"type": "enum", "value": "indeterminate"}""")
    assertEquals(
      listOf("Indeterminate"),
      assertIs<ScreenValue.Reference>(box.arguments["state"]).members,
    )
  }

  @Test
  fun `a toggle button's shapes name the kit size, round or square`() {
    val round =
      construct(
        root("m3/toggle-button", """"shapes": {"type": "enum", "value": "m"}""").arguments["shapes"]
      )
    assertEquals("androidx.compose.material3.ToggleButtonDefaults.shapesFor", round.callableFqn)
    val square =
      construct(
        root("m3/tonal-toggle-button", """"shapes": {"type": "enum", "value": "xlSquare"}""")
          .arguments["shapes"]
      )
    assertEquals("androidx.compose.material3.ToggleButtonDefaults.shapes", square.callableFqn)
    assertEquals(
      listOf("extraLargeSquareShape"),
      assertIs<ScreenValue.Reference>(square.named["shape"]).members,
    )
    assertEquals(
      listOf("roundShape"),
      assertIs<ScreenValue.Reference>(square.named["checkedShape"]).members,
    )
  }

  @Test
  fun `a small top app bar's container colour is its TopAppBarColors`() {
    val colors =
      construct(
        root("m3/top-app-bar", """"containerColor": {"type": "color", "value": "#00000000"}""")
          .arguments["colors"]
      )
    assertEquals("androidx.compose.material3.TopAppBarDefaults.topAppBarColors", colors.callableFqn)
  }

  @Test
  fun `a wide rail's initial state is its remembered state`() {
    val rail =
      root(
        "m3/wide-navigation-rail",
        """"state": {"type": "enum", "value": "expanded"},
           "arrangement": {"type": "enum", "value": "top"}""",
      )
    val state = construct(rail.arguments["state"])
    assertEquals("androidx.compose.material3.rememberWideNavigationRailState", state.callableFqn)
    assertEquals(
      listOf("Expanded"),
      assertIs<ScreenValue.Reference>(state.named["initialValue"]).members,
    )
    assertEquals(
      "androidx.compose.foundation.layout.Arrangement.Vertical",
      assertIs<ScreenValue.Reference>(rail.arguments["arrangement"]).typeFqn,
    )
  }
}
