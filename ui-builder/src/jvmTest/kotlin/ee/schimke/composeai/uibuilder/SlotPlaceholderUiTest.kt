package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The placeholder, on the real canvas: an empty container invites a component, and the invitation
 * is gone the moment it has one.
 */
@OptIn(ExperimentalTestApi::class)
class SlotPlaceholderUiTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `an empty container invites a drop, and the invitation goes once it is filled`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            chrome = PointerTestUiBuilderChrome,
            initialComponentsOpen = true,
            initialCanvasZoom = 1f,
          )
        }
      }
      waitForIdle()

      // The design's root is an empty box filling the frame: the whole canvas is its invitation.
      onNodeWithContentDescription("Drop into children").assertExists()
      // And the panel's own statement of where an Add lands names the layer, not an id.
      onNodeWithText("Adds into Box › children").assertExists()

      // Fill it the way a person would: find a component and press Add, which lands in the
      // selection's slot — the box is the selection on open.
      onNodeWithContentDescription("Component catalog search").performTextInput("Text")
      waitForIdle()
      onNodeWithContentDescription("Add Text").performClick()
      waitForIdle()

      onNodeWithContentDescription("Drop into children").assertDoesNotExist()
    }

  private companion object {
    private val FIXTURE =
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "slot-placeholder-ui-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Placeholder UI fixture",
                "catalogPin": {
                  "systemId": "m3-catalog",
                  "catalogRevision": "candidate",
                  "capabilityDigest": "candidate",
                  "nativeRuntimeId": "candidate"
                },
                "environment": {
                  "widthDp": 320, "heightDp": 640, "density": 1.0, "theme": "dark",
                  "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
                  "layoutDirection": "ltr", "windowPosture": "flat",
                  "browserZoomPercent": 100, "fixedTime": "2024-05-16T12:00:00Z",
                  "animations": "settled", "networkAccess": false
                },
                "stateVariables": {}
              },
              {
                "operationId": "box",
                "type": "insertNode",
                "parent": null,
                "node": {
                  "id": "root-box",
                  "componentId": "layout/box",
                  "modifiers": [{"type": "fillMaxSize"}]
                }
              }
            ]
          }
          """
            .trimIndent()
        )
        .jsonObject
  }
}
