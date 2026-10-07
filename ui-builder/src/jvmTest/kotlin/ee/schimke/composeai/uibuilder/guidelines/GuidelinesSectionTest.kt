package ee.schimke.composeai.uibuilder.guidelines

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import ee.schimke.composeai.uibuilder.editor.GuidelinesSection
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

@OptIn(ExperimentalTestApi::class)
class GuidelinesSectionTest {
  private class Host(var key: String?) : DesignGuidelineHost {
    var signedIn = false
    var sent = ""

    override fun storedKey() = key

    override fun storeKey(key: String?) {
      this.key = key
    }

    override fun storedModel(): String? = null

    override fun storeModel(model: String?) {}

    override val signIn: (() -> Unit) = { signedIn = true }

    override suspend fun completeSignIn() = DesignGuidelineHost.SignInResult.NotReturning

    override suspend fun complete(body: String, key: String): DesignGuidelineHost.Response {
      sent = body
      val verdicts =
        """{"verdicts":[{"ruleId":"wear.layout.responsive-width","verdict":"fail",""" +
          """"confidence":0.9,"nodeIds":["stop"],"reason":"The Stop button has a fixed width."}]}"""
      return DesignGuidelineHost.Response(
        200,
        """{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(verdicts)}}}]}""",
      )
    }

    override suspend fun picture(document: UiBuilderDocument): String? = null
  }

  @Test
  fun `without a key it says how to get one, and connects or saves a pasted key`() =
    runComposeUiTest {
      val host = Host(key = null)
      val controller = DesignGuidelineController(host)
      setContent { MaterialTheme { GuidelinesSection(controller, document(), {}, {}) } }
      onNodeWithText("Get a key").assertExists()
      onNodeWithText("Connect OpenRouter").performClick()
      assertTrue(host.signedIn)
      onNodeWithContentDescription("OpenRouter API key").performTextInput("sk-or-pasted")
      onNodeWithText("Save key").performClick()
      waitForIdle()
      assertEquals("sk-or-pasted", host.key)
      onNodeWithText("Check guidelines").assertExists()
    }

  @Test
  fun `a check lists what broke and goes to the layer it is about`() = runComposeUiTest {
    val host = Host(key = "sk-or-1")
    val controller = DesignGuidelineController(host)
    val events = mutableListOf<UiBuilderEditorEvent>()
    setContent {
      MaterialTheme {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          GuidelinesSection(controller, document(), {}, { events += it })
        }
      }
    }
    onNodeWithText("Check guidelines").performClick()
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithText("The Stop button has a fixed width.").fetchSemanticsNodes().isNotEmpty()
    }
    assertTrue("wear.layout.responsive-width" in host.sent)
    onNodeWithContentDescription("Go to layer stop").performClick()
    assertEquals(UiBuilderEditorEvent.SelectNode("stop"), events.first())
  }

  private fun document(): UiBuilderDocument =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "workout", "title": "Workout", "revision": 1,
        "catalogPin": {"systemId": "wear-m3"},
        "environment": {"widthDp": 192, "heightDp": 192},
        "stateVariables": {},
        "roots": ["stop"],
        "nodes": {"stop": {"id": "stop", "componentId": "wear-m3/button",
          "modifiers": [{"type": "width", "value": 80}]}}
      }
      """,
    )
}
