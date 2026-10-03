package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.PropertyEditorControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The container's `background` has to be reachable from the inspector, not merely declared.
 *
 * It is typed `"string"`, so it fell through to a plain text field with no hint that a colour or a
 * token was what it wanted. A property the catalog declares and the renderer reads but the
 * inspector will not show is the same as not having it.
 *
 * Padding and corner radius used to be pinned here as number controls. The container no longer
 * declares them — `WearWidgetParams` hands them to the widget per host shape — so the override
 * table no longer carries editors for them either.
 *
 * Parsed from a synthetic catalog rather than the real `remote-m3` one: that catalog is synthesised
 * in `:ui-builder-runtime`, which this module may not depend on. What is under test is the override
 * table in the parser, and it is keyed by component id, so the id is all the fixture needs to
 * carry.
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
