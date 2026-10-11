package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.PropertyEditorControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The container's `background` (typed `"string"`) is reachable from the inspector as a colour
 * editor. Padding and corner radius aren't the container's (`WearWidgetParams` supplies them per
 * host shape), so they have no editors.
 *
 * Parsed from a synthetic catalog: the real `remote-m3` is synthesised in `:ui-builder-runtime`,
 * which this module can't depend on, and the override table is keyed by component id only.
 */
class WearWidgetContainerEditorsTest {
  private val catalog = CapabilityCatalogParser.parse(CONTAINER_CATALOG)

  @Test
  fun `the background is a colour control offering the catalog's tokens`() {
    val editor =
      assertNotNull(
        catalog.componentsById
          .getValue("remote-m3/widget-container-small")
          .propertiesByName
          .getValue("background")
          .editor
      )

    assertEquals(PropertyEditorControl.COLOR, editor.control)
    // The same token list every other colour control offers, so `primary` is as reachable on a
    // widget background as it is on a text.
    assertTrue("primary" in editor.suggestedValues, editor.suggestedValues.toString())
    assertTrue("onPrimary" in editor.suggestedValues, editor.suggestedValues.toString())
  }

  private companion object {
    val CONTAINER_CATALOG =
      """
      {
        "schema": "compose-ui-builder-catalog/v1-candidate",
        "benchmark": {
          "id": "wear-widget-container-editors",
          "sourceRevision": "test",
          "catalogSystemId": "remote-m3",
          "catalogRevision": "wear-widget-scaffolds-v1",
          "nativeRuntimeId": "candidate"
        },
        "statusSemantics": {},
        "components": [
          {
            "componentId": "remote-m3/widget-container-small",
            "displayName": "Small",
            "role": "Scaffold",
            "traits": ["ScreenContent"],
            "slots": [],
            "properties": [
              { "name": "background", "jsonType": "string", "required": false }
            ],
            "modifierCapabilities": [],
            "wasm": { "platformSupported": true, "adapterStatus": "supported" }
          },
          {
            "componentId": "remote-m3/widget-container-large",
            "displayName": "Large",
            "role": "Scaffold",
            "traits": ["ScreenContent"],
            "slots": [],
            "properties": [
              { "name": "background", "jsonType": "string", "required": false }
            ],
            "modifierCapabilities": [],
            "wasm": { "platformSupported": true, "adapterStatus": "supported" }
          }
        ]
      }
      """
        .trimIndent()
  }
}
