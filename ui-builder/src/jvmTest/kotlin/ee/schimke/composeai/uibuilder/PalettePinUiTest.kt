package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The pin, driven through the real palette: the star pins, the shelf appears at the top with the
 * component drawn twice — once under Pinned, once on its shelf — the star flips, and taking it off
 * takes the shelf away with it.
 *
 * The star is found in the unmerged tree: a row is one merged semantics node by design, so the
 * star's own node is what a press aimed at the star — or a screen reader — must be able to reach.
 */
@OptIn(ExperimentalTestApi::class)
class PalettePinUiTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `the star pins a component into a shelf at the top`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            chrome = PointerTestUiBuilderChrome,
            initialComponentsOpen = true,
          )
        }
      }
      waitForIdle()

      // Find the row by search rather than by scrolling for it: the catalog is a long lazy list.
      onNodeWithContentDescription("Component catalog search").performTextInput("Button")
      waitForIdle()
      onNodeWithContentDescription("Pin Button", useUnmergedTree = true).performClick()
      waitForIdle()

      // Clear the search: the shelf is at the top, and its star is now the way back off — which is
      // what says the press landed.
      onNodeWithContentDescription("Component catalog search").performTextClearance()
      waitForIdle()
      onNodeWithText("Pinned").assertExists()
      onNodeWithContentDescription("Unpin Button", useUnmergedTree = true).assertExists()

      // Taking the star off empties the shelf, so the shelf goes with it.
      onNodeWithContentDescription("Unpin Button", useUnmergedTree = true).performClick()
      waitForIdle()
      onNodeWithText("Pinned").assertDoesNotExist()
    }
}
