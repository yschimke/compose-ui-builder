package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.CommentNotificationsState
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The switch as the Talk panel draws it: present only when the host offers it, a click that reaches
 * the host, and the iPhone sentence in place of a switch.
 */
@OptIn(ExperimentalTestApi::class)
class CommentNotificationsToggleUiTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun render(state: CommentNotificationsState, onToggle: () -> Unit = {}) =
    @androidx.compose.runtime.Composable {
      MaterialTheme {
        UiBuilderEditor(
          document,
          catalog,
          chrome = PointerTestUiBuilderChrome,
          initialInspectorMode = EditorInspectorMode.Comments,
          initialInspectorOpen = true,
          onPostComment = {},
          onResolveCommentThread = { _, _ -> },
          commentNotifications = state,
          onToggleCommentNotifications = onToggle,
        )
      }
    }

  @Test
  fun `hidden draws nothing`() =
    runDesktopComposeUiTest(width = 1600, height = 900) {
      setContent(render(CommentNotificationsState.Hidden))
      waitForIdle()
      assertEquals(
        0,
        onAllNodesWithContentDescription("Notify me about replies").fetchSemanticsNodes().size,
      )
    }

  @Test
  fun `the switch reaches the host`() =
    runDesktopComposeUiTest(width = 1600, height = 900) {
      var clicks = 0
      setContent(render(CommentNotificationsState.Toggle(on = false)) { clicks++ })
      waitForIdle()
      onNodeWithContentDescription("Notify me about replies").assertIsOff().performClick()
      waitForIdle()
      assertEquals(1, clicks)
    }

  @Test
  fun `on shows on`() =
    runDesktopComposeUiTest(width = 1600, height = 900) {
      setContent(render(CommentNotificationsState.Toggle(on = true)))
      waitForIdle()
      onNodeWithContentDescription("Notify me about replies").assertIsOn()
    }

  @Test
  fun `an iPhone tab is told to install first`() =
    runDesktopComposeUiTest(width = 1600, height = 900) {
      setContent(render(CommentNotificationsState.InstallFirst))
      waitForIdle()
      onNodeWithText("Add to Home Screen to get notifications").assertExists()
      assertEquals(
        0,
        onAllNodesWithContentDescription("Notify me about replies").fetchSemanticsNodes().size,
      )
    }

  @Test
  fun `denied says how to allow it`() =
    runDesktopComposeUiTest(width = 1600, height = 900) {
      setContent(render(CommentNotificationsState.Blocked()))
      waitForIdle()
      onNodeWithText("Notifications are blocked", substring = true).assertExists()
      onNodeWithContentDescription("Notify me about replies").assertIsOff()
    }
}
