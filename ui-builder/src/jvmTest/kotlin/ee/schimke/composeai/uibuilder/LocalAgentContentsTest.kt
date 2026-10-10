package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred

@OptIn(ExperimentalTestApi::class)
class LocalAgentContentsTest {
  private class ChatHost : UiBuilderChatHost {
    override val connected = true
    override val rememberConnection = false
    override val monitoringAvailable = false
    var requests = 0

    override fun connect() {}

    override fun useKey(key: String) {}

    override fun rememberConnection(remember: Boolean) {}

    override fun disconnect() {}

    override fun load() = UiBuilderChatSession()

    override fun save(session: UiBuilderChatSession): String? = null

    override fun clear(): String? = null

    override suspend fun complete(model: String, messages: List<UiBuilderChatMessage>): String {
      requests++
      return CompletableDeferred<String>().await()
    }
  }

  @Test
  fun `local panel sends only on Send and Stop and New session cancel work`() = runComposeUiTest {
    val provider = ChatHost()
    lateinit var local: UiBuilderLocalAgentHost
    setContent {
      val scope = rememberCoroutineScope()
      local = remember {
        object : UiBuilderLocalAgentHost {
          override val harnesses =
            listOf(
              UiBuilderLocalHarness("codex", "Codex", true),
              UiBuilderLocalHarness("claude", "Claude Code", true),
            )
          override var selected by mutableStateOf("codex")
          override val conversation = UiBuilderChatController(provider, scope)

          override fun select(id: String) {
            selected = id
          }

          override fun refresh() {}

          override fun openSetup() {}
        }
      }
      MaterialTheme { LocalAgentContents(local) }
    }
    assertEquals(0, provider.requests)
    onNodeWithText("Monitor comments while open").assertDoesNotExist()
    onNodeWithText("Claude Code").performClick()
    assertEquals("claude", local.selected)
    onNodeWithText("Message").performScrollTo().performTextReplacement("Review this")
    onNodeWithText("Send").performScrollTo().performClick()
    waitForIdle()
    assertEquals(1, provider.requests)
    onNodeWithText("Stop").performScrollTo().performClick()
    waitForIdle()
    assertFalse(local.conversation.busy)
    onNodeWithText("New session").performScrollTo().performClick()
    assertTrue(local.conversation.session.messages.isEmpty())
  }

  @Test
  fun `capture local agent panel at desktop and compact widths`() = runComposeUiTest {
    var width by mutableStateOf(440)
    setContent {
      val scope = rememberCoroutineScope()
      val local = remember {
        object : UiBuilderLocalAgentHost {
          override val harnesses =
            listOf(
              UiBuilderLocalHarness("codex", "Codex", true),
              UiBuilderLocalHarness("claude", "Claude Code", true),
            )
          override val selected = "codex"
          override val conversation = UiBuilderChatController(ChatHost(), scope)

          override fun select(id: String) {}

          override fun refresh() {}

          override fun openSetup() {}
        }
      }
      MaterialTheme {
        Surface(Modifier.width(width.dp).height(640.dp).testTag("local-agent-evidence")) {
          Column(Modifier.padding(16.dp)) { LocalAgentContents(local) }
        }
      }
    }
    val output = File("build/local-agent").apply { mkdirs() }
    for ((name, size) in listOf("desktop" to 440, "compact" to 320)) {
      runOnIdle { width = size }
      waitForIdle()
      ImageIO.write(
        onNodeWithTag("local-agent-evidence").captureToImage().toAwtImage(),
        "png",
        File(output, "$name.png"),
      )
    }
  }
}
