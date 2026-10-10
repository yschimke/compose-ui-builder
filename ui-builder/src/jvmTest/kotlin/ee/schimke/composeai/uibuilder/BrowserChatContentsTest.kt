package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred

@OptIn(ExperimentalTestApi::class)
class BrowserChatContentsTest {
  private class Host : UiBuilderChatHost {
    override var connected by mutableStateOf(false)
    override var rememberConnection by mutableStateOf(false)
    override val monitoringAvailable = true
    var connects = 0
    var requests = 0
    var stored = UiBuilderChatSession()

    override fun connect() {
      connects++
    }

    override fun useKey(key: String) {
      connected = key.isNotBlank()
    }

    override fun rememberConnection(remember: Boolean) {
      rememberConnection = remember
    }

    override fun disconnect() {
      connected = false
      rememberConnection = false
    }

    override fun load() = stored

    override fun save(session: UiBuilderChatSession): String? {
      stored = session
      return null
    }

    override fun clear(): String? {
      stored = UiBuilderChatSession()
      return null
    }

    override suspend fun complete(model: String, messages: List<UiBuilderChatMessage>): String {
      requests++
      return CompletableDeferred<String>().await()
    }
  }

  @Test
  fun `connecting does not send until the person chooses Send and Stop cancels the turn`() =
    runComposeUiTest {
      val host = Host()
      lateinit var chat: UiBuilderChatController
      setContent {
        val scope = rememberCoroutineScope()
        chat = androidx.compose.runtime.remember { UiBuilderChatController(host, scope) }
        MaterialTheme { BrowserChatContents(chat) }
      }
      assertEquals(0, host.connects)
      assertEquals(0, host.requests)
      onNodeWithText("OpenRouter key").performTextReplacement("test-key")
      onNodeWithText("Use key").performClick()
      waitForIdle()
      assertEquals(0, host.requests)
      assertFalse(host.rememberConnection)
      onNodeWithText("Message").performScrollTo().performTextReplacement("Review this design")
      onNodeWithText("Send").performScrollTo().performClick()
      waitForIdle()
      assertEquals(1, host.requests)
      onNodeWithText("Stop").performScrollTo().performClick()
      waitForIdle()
      assertFalse(chat.busy)
      assertFalse(chat.monitoring)
      onNodeWithText("Disconnect").performScrollTo().performClick()
      waitForIdle()
      onNodeWithText("Connect OpenRouter").assertExists()
    }

  @Test
  fun `capture the external handoff and browser chat at desktop and mobile widths`() =
    runComposeUiTest {
      val chatHost = Host().apply { connected = true }
      var browserChat by mutableStateOf(false)
      var width by mutableStateOf(440)
      val externalHost =
        object : UiBuilderAgentHost {
          override val preferences = UiBuilderAgentPreferences(connectedBefore = true)
          override val agents = emptyList<UiBuilderCollaborator>()

          override fun prompt(includeSetup: Boolean, instructions: String) =
            "Review this design with your own agent. Read the comments and propose changes as suggestions."

          override fun save(preferences: UiBuilderAgentPreferences): String? = null

          override suspend fun copy(text: String) = "Copied"

          override fun openSetup() {}

          override fun connectVsCode() {}
        }
      setContent {
        val scope = rememberCoroutineScope()
        val chat = androidx.compose.runtime.remember { UiBuilderChatController(chatHost, scope) }
        MaterialTheme {
          Surface(Modifier.width(width.dp).height(640.dp).testTag("chat-evidence")) {
            Column(Modifier.padding(16.dp)) {
              if (browserChat) BrowserChatContents(chat) else AgentPromptContents(externalHost) {}
            }
          }
        }
      }
      val output = File("build/browser-chat").apply { mkdirs() }
      for ((pixels, label) in listOf(440 to "desktop", 320 to "mobile")) {
        runOnIdle {
          width = pixels
          browserChat = false
        }
        waitForIdle()
        ImageIO.write(
          onNodeWithTag("chat-evidence").captureToImage().toAwtImage(),
          "png",
          File(output, "$label.before.png"),
        )
        runOnIdle { browserChat = true }
        waitForIdle()
        ImageIO.write(
          onNodeWithTag("chat-evidence").captureToImage().toAwtImage(),
          "png",
          File(output, "$label.after.png"),
        )
      }
    }
}
