package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Properties panel for a text in a Wear widget closes when its close button says it will.
 *
 * Checked on both layouts, because they draw the panel from different state: the wide one from
 * whether the inspector is open, the compact one — anything under 840dp, which an editor beside its
 * preview pane easily is — from which bottom sheet is showing.
 */
@OptIn(ExperimentalTestApi::class)
class WearWidgetTextInspectorCloseTest {
  private val document = decodeProductionRendererDocument(ProductionRemoteTextTest.WIDGET_DOCUMENT)
  private val catalog = assertNotNull(productionPreviewCatalog(document))

  @Test fun `the wide layout closes the text's properties`() = closesAt(width = 1600)

  @Test fun `the compact layout closes the text's properties`() = closesAt(width = 800)

  private fun closesAt(width: Int) =
    runDesktopComposeUiTest(width = width, height = 1000) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            initialSelectedNodeId = "header",
            initialInspectorOpen = true,
          )
        }
      }

      onNodeWithContentDescription("Close Properties", substring = true).performClick()
      waitForIdle()

      assertEquals(
        0,
        onAllNodesWithContentDescription("Close Properties", substring = true)
          .fetchSemanticsNodes()
          .size,
        "the Properties panel is still open after its close button was pressed",
      )
    }
}
