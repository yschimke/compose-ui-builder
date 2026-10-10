package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import ee.schimke.composeai.uibuilder.editor.AgentPromptContents
import ee.schimke.composeai.uibuilder.editor.UiBuilderAgentHost
import ee.schimke.composeai.uibuilder.editor.UiBuilderAgentPreferences
import ee.schimke.composeai.uibuilder.editor.UiBuilderCollaborator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AgentPromptContentsTest {
  private class Host : UiBuilderAgentHost {
    override var preferences by
      mutableStateOf(
        UiBuilderAgentPreferences(connectedBefore = true, generalInstructions = "Keep it concise")
      )
    override val agents = emptyList<UiBuilderCollaborator>()
    var copied = ""

    override fun prompt(includeSetup: Boolean, instructions: String) =
      "Design prompt; setup=$includeSetup; $instructions"

    override fun save(preferences: UiBuilderAgentPreferences): String? {
      this.preferences = preferences
      return null
    }

    override suspend fun copy(text: String): String {
      copied = text
      return "Copied"
    }

    override fun openSetup() {}

    override fun connectVsCode() {}
  }

  @Test
  fun `background handoff asks for a personal runtime and review before shared writes`() =
    runComposeUiTest {
      val host = Host()
      setContent { MaterialTheme { AgentPromptContents(host) {} } }
      onNodeWithText("Copy monitoring prompt").performScrollTo().performClick()
      waitForIdle()
      assertTrue(host.copied.contains("ui_builder_await_comments"))
      assertTrue(host.copied.contains("your own runtime"))
      assertTrue(host.copied.contains("ask before posting replies"))
      assertTrue(host.copied.contains("Do not send provider credentials"))
    }

  @Test
  fun `editing and clicking copies the visible prompt`() = runComposeUiTest {
    val host = Host()
    setContent { MaterialTheme { AgentPromptContents(host) {} } }
    onNodeWithText("Instructions for all designs").assertDoesNotExist()
    onNodeWithText("My agent is already set up").assertDoesNotExist()
    onNodeWithText("Prompt").performTextReplacement("My edited prompt")
    onNodeWithText("Prompt").performTouchInput { click() }
    waitForIdle()
    assertEquals("My edited prompt", host.copied)
    onNodeWithText("Prompt").performTextReplacement("Updated again")
    onNodeWithText("Copy prompt").performScrollTo().performClick()
    waitForIdle()
    assertEquals("Updated again", host.copied)
  }

  @Test
  fun `customization survives collapsing and setup has one remembered choice`() = runComposeUiTest {
    val host = Host()
    setContent { MaterialTheme { AgentPromptContents(host) {} } }
    onNodeWithText("Customize").performClick()
    onNodeWithText("Instructions for this design")
      .performScrollTo()
      .performTextReplacement("Use purple")
    onNodeWithText("Customize").performScrollTo().performClick()
    onNodeWithText("Instructions for this design").assertDoesNotExist()
    onNodeWithText("Include setup").performClick()
    onNodeWithText("Copy prompt").performScrollTo().performClick()
    waitForIdle()
    assertEquals("Design prompt; setup=true; Use purple", host.copied)
    assertFalse(host.preferences.connectedBefore)
    assertEquals("Use purple", host.preferences.documentInstructions)
    onNodeWithText("Include setup").performScrollTo().performClick()
    onNodeWithText("Copy prompt").performScrollTo().performClick()
    waitForIdle()
    assertTrue(host.preferences.connectedBefore)
    assertEquals("Design prompt; setup=false; Use purple", host.copied)
  }
}
