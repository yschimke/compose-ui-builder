package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.editor.undrawnStatusLabel
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderUndrawnNode
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderUndrawnNodes
import ee.schimke.composeai.uibuilder.renderer.sdk.undrawnGenerationKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The editing canvas says which nodes it did not draw as themselves, so a diagnostic box is never
 * the only sign that it disagrees with the export and the device previews.
 */
@OptIn(ExperimentalTestApi::class)
class UndrawnNodesRenderTest {
  @AfterTest fun reset() = UiBuilderUndrawnNodes.clear()

  @Test
  fun `a component the canvas cannot draw is recorded, and a drawing is not`() =
    runDesktopComposeUiTest(width = 600, height = 400) {
      val document = decodeProductionRendererDocument(DOCUMENT)
      setContent { ProductionUiBuilderSurface(document) }
      waitForIdle()

      assertEquals(
        listOf(
          UiBuilderUndrawnNode("mystery", "nowhere/mystery", UiBuilderUndrawnNode.UNSUPPORTED)
        ),
        UiBuilderUndrawnNodes.forGeneration(undrawnGenerationKey(document.id, document.revision)),
      )
    }

  @Test
  fun `the status bar names the components, most severe colouring aside`() {
    assertNull(undrawnStatusLabel(emptyList()))
    assertEquals(
      "3 not drawn here: draw/canvas, nowhere/mystery",
      undrawnStatusLabel(
        listOf(
          UiBuilderUndrawnNode("a", "draw/canvas", UiBuilderUndrawnNode.UNSUPPORTED),
          UiBuilderUndrawnNode("b", "draw/canvas", UiBuilderUndrawnNode.UNSUPPORTED),
          UiBuilderUndrawnNode("c", "nowhere/mystery", UiBuilderUndrawnNode.STAND_IN),
        )
      ),
    )
    assertEquals(
      "4 not drawn here: a/a, b/b, c/c +1",
      undrawnStatusLabel(
        listOf("a/a", "b/b", "c/c", "d/d").map {
          UiBuilderUndrawnNode(it, it, UiBuilderUndrawnNode.STAND_IN)
        }
      ),
    )
  }

  private companion object {
    val DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"undrawn","title":"Undrawn",
       "revision":3,
       "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":216,"heightDp":124,"density":2.0,"theme":"dark",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{},"roots":["widget"],
       "nodes":{
        "widget":{"id":"widget","componentId":"remote-m3/widget-container-large",
          "properties":{},"modifiers":[],
          "slots":{"background":[],"content":["row"]},"eventBindings":{}},
        "row":{"id":"row","componentId":"layout/row","properties":{},"modifiers":[],
          "slots":{"children":["canvas","mystery"]},"eventBindings":{}},
        "canvas":{"id":"canvas","componentId":"draw/canvas","properties":{},
          "modifiers":[{"type":"size","widthDp":40,"heightDp":40}],
          "slots":{"ops":["square"]},"eventBindings":{}},
        "square":{"id":"square","componentId":"draw/rect","properties":{
          "color":{"type":"color","value":"#FFFF0000"}},
          "modifiers":[],"slots":{},"eventBindings":{}},
        "mystery":{"id":"mystery","componentId":"nowhere/mystery","properties":{},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
        .trimIndent()
  }
}
