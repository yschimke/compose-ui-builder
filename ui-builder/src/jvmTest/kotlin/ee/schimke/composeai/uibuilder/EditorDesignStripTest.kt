package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderFileDesign
import ee.schimke.composeai.uibuilder.editor.UiBuilderFileDesigns
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The strip above the canvas that lists a file's designs and moves between them. */
@OptIn(ExperimentalTestApi::class)
class EditorDesignStripTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
      .copy(title = "Strip active design")

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun render(designs: UiBuilderFileDesigns?) =
    @androidx.compose.runtime.Composable {
      MaterialTheme {
        UiBuilderEditor(
          document,
          catalog,
          chrome = PointerTestUiBuilderChrome,
          fileDesigns = designs,
        )
      }
    }

  private fun designs(
    onSelect: ((String) -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
  ) =
    UiBuilderFileDesigns(
      designs =
        listOf(
          UiBuilderFileDesign(document.id, "Stale title"),
          UiBuilderFileDesign("strip-second-design", ""),
        ),
      active = document.id,
      onSelect = onSelect,
      onAdd = onAdd,
      onRemove = onRemove,
    )

  @Test
  fun `the strip lists every design, the active one selected under its live title`() =
    runDesktopComposeUiTest(width = 1600, height = 900) {
      val selected = mutableListOf<String>()
      var added = 0
      var removed = 0
      setContent(render(designs({ selected += it }, { added++ }, { removed++ })))
      waitForIdle()

      onNodeWithText("Designs").assertExists()
      onNode(hasText("Strip active design") and hasClickAction()).assertIsSelected()
      // The toolbar names the design too; the chip is the one that can be pressed.
      // A design with no title is named by its id.
      onNodeWithText("strip-second-design").performClick()
      // Selecting the design that is already open asks the host for nothing.
      onNode(hasText("Strip active design") and hasClickAction()).performClick()
      onNodeWithText("Add design").performClick()
      onNodeWithText("Remove design").performClick()
      waitForIdle()

      assertEquals(listOf("strip-second-design"), selected)
      assertEquals(1, added)
      assertEquals(1, removed)
    }

  @Test
  fun `a host that cannot switch lists the designs without offering to`() =
    runDesktopComposeUiTest(width = 1600, height = 900) {
      setContent(render(designs()))
      waitForIdle()

      onNodeWithText("strip-second-design").assertIsNotEnabled()
      assertEquals(0, onAllNodesWithText("Add design").fetchSemanticsNodes().size)
      assertEquals(0, onAllNodesWithText("Remove design").fetchSemanticsNodes().size)
    }

  @Test
  fun `a file of one design with nothing to add draws no strip`() =
    runDesktopComposeUiTest(width = 1600, height = 900) {
      setContent(
        render(UiBuilderFileDesigns(listOf(UiBuilderFileDesign(document.id, "")), document.id))
      )
      waitForIdle()
      assertEquals(0, onAllNodesWithText("Designs").fetchSemanticsNodes().size)
    }
}
