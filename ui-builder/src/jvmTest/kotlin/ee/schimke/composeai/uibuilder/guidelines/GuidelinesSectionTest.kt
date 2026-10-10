package ee.schimke.composeai.uibuilder.guidelines

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import ee.schimke.composeai.uibuilder.editor.GuidelinesSection
import ee.schimke.composeai.uibuilder.editor.ProblemsInspector
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.promptForCopy
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

@OptIn(ExperimentalTestApi::class)
class GuidelinesSectionTest {
  private class Host(var key: String?, private val rememberable: Boolean = false) :
    DesignGuidelineHost {
    var signedIn = false
    var sent = ""
    var remembered = false

    override val canRememberKey
      get() = rememberable

    override fun keyRemembered() = remembered

    override fun rememberKey(remember: Boolean) {
      remembered = remember
    }

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

    var shared: DesignGuidelineRecord? = null

    override suspend fun sharedResult() = shared
  }

  @Test
  fun `a prompt that lands for an earlier revision is rebuilt for the design on screen`() =
    runComposeUiTest {
      val controller = DesignGuidelineController(Host(key = null))
      // A prompt built while the design was at an earlier revision, as one still loading during
      // an edit lands once the revision has moved on.
      val earlier = document().copy(revision = 0)
      kotlinx.coroutines.runBlocking {
        controller.preview(earlier, DesignGuidelineController.encode(earlier))
      }
      assertEquals(
        0,
        assertIs<DesignGuidelineController.PromptView.Shown>(controller.prompt.value)
          .request
          .revision,
      )
      setContent { MaterialTheme { IssuesPanel(controller, {}) } }
      waitUntil(timeoutMillis = 5_000) {
        (controller.prompt.value as? DesignGuidelineController.PromptView.Shown)
          ?.request
          ?.revision == 1
      }
    }

  @Test
  fun `the prompt is readable without a key, with where it came from`() = runComposeUiTest {
    val controller = DesignGuidelineController(Host(key = null))
    setContent { MaterialTheme { IssuesPanel(controller, {}) } }
    onNodeWithText("Show the prompt").performScrollTo().performClick()
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithText("Where this prompt comes from").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithText("Open the rule set").assertExists()
    onNodeWithContentDescription("Copy the prompt").assertExists()
    onNodeWithText("System prompt").assertExists()
    val request =
      assertIs<DesignGuidelineController.PromptView.Shown>(controller.prompt.value).request
    assertTrue("ruleId: wear.layout.responsive-width" in request.userText)
    onNodeWithText("Hide the prompt").performScrollTo().performClick()
    onNodeWithText("Where this prompt comes from").assertDoesNotExist()
  }

  @Test
  fun `the design's recorded result shows who ran it, and when it is stale`() = runComposeUiTest {
    val host = Host(key = null)
    host.shared =
      DesignGuidelineRecord(
        revision = 0,
        model = "anthropic/claude-haiku-5.5",
        rulesVersion = 3,
        asked = listOf("wear.layout.responsive-width"),
        verdicts =
          DesignGuidelinePrompt.parseVerdicts(
            """{"verdicts":[{"ruleId":"wear.layout.responsive-width","verdict":"fail",""" +
              """"confidence":0.9,"nodeIds":["stop"],"reason":"Recorded by an agent."}]}"""
          ),
        ranBy = "agent:review-bot",
      )
    val controller = DesignGuidelineController(host)
    setContent { MaterialTheme { IssuesPanel(controller, {}) } }
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithText("Recorded by an agent.").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithText("run by agent:review-bot", substring = true).assertExists()
    onNodeWithText("Checked an earlier revision", substring = true).assertExists()
  }

  @Test
  fun `the copied prompt leaves picture bytes out`() {
    val request =
      DesignGuidelinePrompt.prepare(
        DesignGuidelineRuleSet.Bundled,
        "workout",
        1,
        DesignGuidelineController.encode(document()),
        "data:image/png;base64,SECRETBYTES",
        null,
      )
    val copied = promptForCopy(request)
    assertTrue("SECRETBYTES" !in copied)
    assertTrue("device picture" in copied)
  }

  @Test
  fun `without a key it says how to get one, and connects or saves a pasted key`() =
    runComposeUiTest {
      val host = Host(key = null)
      val controller = DesignGuidelineController(host)
      setContent { MaterialTheme { IssuesPanel(controller, {}) } }
      onNodeWithText("Get a key").assertExists()
      onNodeWithText("Connect OpenRouter").performClick()
      assertTrue(host.signedIn)
      onNodeWithContentDescription("OpenRouter API key").performTextInput("sk-or-pasted")
      onNodeWithText("Save key").performClick()
      waitForIdle()
      assertEquals("sk-or-pasted", host.key)
      onNodeWithText("Check guidelines").assertExists()
      // A host that cannot keep a key beyond the session offers no choice about it.
      onNodeWithText("Remember on this device").assertDoesNotExist()
    }

  @Test
  fun `remembering the key on this device is an opt-in, and can be taken back`() =
    runComposeUiTest {
      val host = Host(key = null, rememberable = true)
      val controller = DesignGuidelineController(host)
      setContent { MaterialTheme { IssuesPanel(controller, {}) } }
      onNodeWithText("Remember on this device").assertIsOff()
      onNodeWithText("Remember on this device").performClick()
      assertTrue(host.remembered)
      onNodeWithContentDescription("OpenRouter API key").performTextInput("sk-or-pasted")
      onNodeWithText("Save key").performClick()
      waitForIdle()
      onNodeWithText("Model & key").performClick()
      onNodeWithText("Remember on this device").assertIsOn().performClick()
      assertFalse(host.remembered)
      onNodeWithText("Remember on this device").assertIsOff()
    }

  @Test
  fun `model settings open below the actions, with room for the field`() = runComposeUiTest {
    val controller = DesignGuidelineController(Host(key = "sk-or-1"))
    setContent { MaterialTheme { IssuesPanel(controller, {}) } }
    onNodeWithText("Model & key").performClick()
    val button = onNodeWithText("Check guidelines").fetchSemanticsNode().boundsInRoot
    val field = onNodeWithText("OpenRouter model").fetchSemanticsNode().boundsInRoot
    assertTrue(field.top >= button.bottom, "field $field should sit below $button")
    onNodeWithText("Forget key").assertExists()
  }

  @Test
  fun `a check lists what broke and goes to the layer it is about`() = runComposeUiTest {
    val host = Host(key = "sk-or-1")
    val controller = DesignGuidelineController(host)
    val events = mutableListOf<UiBuilderEditorEvent>()
    setContent { MaterialTheme { IssuesPanel(controller, { events += it }) } }
    onNodeWithText("Check guidelines").performClick()
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithText("The Stop button has a fixed width.").fetchSemanticsNodes().isNotEmpty()
    }
    assertTrue("wear.layout.responsive-width" in host.sent)
    onNodeWithContentDescription("Go to layer stop").performScrollTo().performClick()
    assertEquals(UiBuilderEditorEvent.SelectNode("stop"), events.first())
  }

  /** The section as the editor shows it: the first item of the Issues list, which scrolls. */
  @androidx.compose.runtime.Composable
  private fun IssuesPanel(
    controller: DesignGuidelineController,
    dispatch: (UiBuilderEditorEvent) -> Unit,
  ) {
    ProblemsInspector(
      emptyList(),
      dispatch,
      header = { GuidelinesSection(controller, document(), {}, dispatch) },
    )
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
