package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The editing canvas draws a Wear list's rows where the watch does.
 *
 * The canvas draws a Wear screen at its whole extent, where `TransformingLazyColumn` is a `Column`
 * (a lazy layout cannot be measured against the extent's unbounded height). The real list hands
 * each row the full width and centres it; the `Column` handed rows nothing, so a `ListHeader`,
 * which centres its label within the width it is given, sat at the start: every header on the
 * canvas was left-aligned while the device previews beside it were centred.
 */
class WearUnrolledListAlignmentTest {

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `the canvas centres a list header the way the watch does`() =
    runDesktopComposeUiTest(width = 1280, height = 800) {
      val root =
        generateSequence(File("").absoluteFile) { it.parentFile }
          .first { File(it, "site/designs/wear-list.uid").isFile }
      val catalog =
        CapabilityCatalogParser.parse(
          File(root, "docs/design/fixtures/ui-builder/wear-m3-capabilities-v1.json").readText()
        )
      // The Wear list template: a `ListHeader` reading "Activity" over cards that fill the list.
      val document = UidDesignFiles.decode(File(root, "site/designs/wear-list.uid").readText())
      setContent {
        UiBuilderEditor(
          document = document,
          catalog = catalog,
          initialPanes = setOf(EditorPane.Editor),
        )
      }
      waitForIdle()

      val header = onAllNodesWithText("Activity").fetchSemanticsNodes().single().boundsInRoot
      val row =
        onAllNodesWithText("Session 1", substring = true).fetchSemanticsNodes().first().boundsInRoot

      assertTrue(
        abs(header.center.x - row.center.x) < 1f,
        "the header is centred at ${header.center.x}, the list at ${row.center.x}",
      )
    }
}
