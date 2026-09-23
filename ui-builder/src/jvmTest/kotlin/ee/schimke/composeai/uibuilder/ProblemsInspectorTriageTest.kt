package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.EditorProblem
import ee.schimke.composeai.uibuilder.editor.ProblemAudience
import ee.schimke.composeai.uibuilder.editor.ProblemsInspector
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.problemBadgeCount
import ee.schimke.composeai.uibuilder.editor.problemHeading
import ee.schimke.composeai.uibuilder.editor.triageProblems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProblemsInspectorTriageTest {
  @Test
  fun `structural causes sort before grouped downstream failures and advisories`() {
    val groups = triageProblems(problems)

    assertEquals("SLOT_CARDINALITY", groups[0].code)
    assertTrue(groups[0].rootCause)
    assertEquals("COMPOSE_EXPORT_REFUSED", groups[1].code)
    assertFalse(groups[1].rootCause)
    assertEquals(2, groups[1].problems.size)
    assertEquals("PROPERTY_NOT_DECLARED", groups[2].code)
    assertFalse(groups[2].blocking)
    assertEquals(1, problemBadgeCount(problems))
    assertEquals("Issues · 1 root cause", problemHeading(problems))
  }

  @Test
  fun `unlocated design refusals remain author owned`() {
    val group =
      triageProblems(
          listOf(
            EditorProblem(
              code = "COMPOSE_EXPORT_REFUSED",
              message = "Widget padding must be 20dp",
            )
          )
        )
        .single()

    assertEquals(ProblemAudience.AUTHOR, group.audience)
  }

  @Test
  fun `same diagnostic on different components remains distinct`() {
    val groups =
      triageProblems(
        listOf(
          EditorProblem(
            "COMPONENT_DRIFTED",
            "Card drifted",
            componentId = "m3/card",
            blocking = false,
          ),
          EditorProblem(
            "COMPONENT_DRIFTED",
            "Text drifted",
            componentId = "m3/text",
            blocking = false,
          ),
        )
      )

    assertEquals(listOf("m3/card", "m3/text"), groups.mapNotNull { it.componentId }.sorted())
  }

  @Test
  fun `located group visibly navigates and retains copyable technical details`() =
    runDesktopComposeUiTest(width = 520, height = 700) {
      val events = mutableListOf<UiBuilderEditorEvent>()
      setContent { MaterialTheme { ProblemsInspector(problems) { events += it } } }

      onAllNodesWithContentDescription("Go to layer card")[0].assertExists().performClick()
      assertEquals("card", assertIs<UiBuilderEditorEvent.SelectNode>(events[0]).nodeId)
      assertEquals(
        EditorInspectorMode.Properties,
        assertIs<UiBuilderEditorEvent.ShowInspector>(events[1]).mode,
      )

      onNodeWithText("2 occurrences").performClick()
      onNodeWithText("Emitter could not write argument one").assertExists()
      onNodeWithText("Emitter could not write argument two").assertExists()
    }

  private companion object {
    val problems =
      listOf(
        EditorProblem(
          code = "COMPOSE_EXPORT_REFUSED",
          message = "Emitter could not write argument one",
          nodeId = "card",
          componentId = "m3/card",
        ),
        EditorProblem(
          code = "PROPERTY_NOT_DECLARED",
          message = "oldTone is no longer declared",
          nodeId = "headline",
          componentId = "m3/text",
          blocking = false,
          propertyName = "oldTone",
          replacementProperties = listOf("color"),
        ),
        EditorProblem(
          code = "SLOT_CARDINALITY",
          message = "content accepts one child but contains two",
          nodeId = "card",
          componentId = "m3/card",
        ),
        EditorProblem(
          code = "COMPOSE_EXPORT_REFUSED",
          message = "Emitter could not write argument two",
          nodeId = "card",
          componentId = "m3/card",
        ),
      )
  }
}
