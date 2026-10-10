package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.UiBuilderBoard
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.*
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalTestApi::class)
class AgentToolbarTest {
  private class ChatHost : UiBuilderChatHost {
    override var connected by mutableStateOf(false)
    override val rememberConnection = false
    override val monitoringAvailable = true
    var requests = 0

    override fun connect() {}

    override fun useKey(key: String) {}

    override fun rememberConnection(remember: Boolean) {}

    override fun disconnect() {
      connected = false
    }

    override fun load() = UiBuilderChatSession()

    override fun save(session: UiBuilderChatSession): String? = null

    override fun clear(): String? = null

    override suspend fun complete(model: String, messages: List<UiBuilderChatMessage>): String {
      requests++
      return CompletableDeferred<String>().await()
    }
  }

  private class Host(override val chat: UiBuilderChatController? = null) : UiBuilderAgentHost {
    override val preferences = UiBuilderAgentPreferences()
    override var agents: List<UiBuilderCollaborator>? by mutableStateOf(emptyList())

    override fun prompt(includeSetup: Boolean, instructions: String) = "Design prompt"

    override fun save(preferences: UiBuilderAgentPreferences): String? = null

    override suspend fun copy(text: String) = "Copied"

    override fun openSetup() {}

    override fun connectVsCode() {}
  }

  private val claude =
    UiBuilderCollaborator(
      "claude",
      "Claude Code",
      "#123456",
      emptyList(),
      UiBuilderParticipantKind.Agent,
      "Claude Sonnet",
    )

  @Test
  fun `agent name opens external presence even when browser chat is available`() =
    runComposeUiTest {
      lateinit var host: Host
      var opened by mutableStateOf(false)
      val provider = ChatHost()
      setContent {
        val scope = rememberCoroutineScope()
        host = remember {
          Host(UiBuilderChatController(provider, scope)).apply { agents = listOf(claude) }
        }
        MaterialTheme {
          AgentToolbarAction(host, { opened = true })
          if (opened) AgentPromptDialog(host, { opened = false }) {}
        }
      }
      onNodeWithText("Claude Code").performSemanticsAction(SemanticsActions.OnClick) { it() }
      onNodeWithText("Active on this design").assertExists()
      assertTrue(
        onAllNodesWithText("Claude Sonnet (reported)", substring = true)
          .fetchSemanticsNodes()
          .isNotEmpty()
      )
      assertEquals(0, provider.requests)
    }

  @Test
  fun `browser readiness and work stay distinct from reported external agents`() =
    runComposeUiTest {
      lateinit var host: Host
      val provider = ChatHost()
      setContent {
        val scope = rememberCoroutineScope()
        host = remember { Host(UiBuilderChatController(provider, scope)) }
        MaterialTheme { AgentToolbarAction(host, {}) }
      }
      onNodeWithText("Connect agent").assertExists()
      runOnIdle { provider.connected = true }
      onNodeWithText("OpenRouter").assertExists()
      runOnIdle { host.agents = listOf(claude) }
      onNodeWithText("Claude Code +1").assertExists()
      runOnIdle { host.chat!!.send("Review") }
      onNodeWithText("OpenRouter +1 · working").assertExists()
      runOnIdle {
        host.chat!!.stop()
        host.agents = emptyList()
      }
      onNodeWithText("OpenRouter").assertExists()
      runOnIdle {
        provider.connected = false
        host.agents = null
      }
      onNodeWithText("Agents").assertExists()
      assertTrue(agentToolbarDescription(host).contains("unavailable"))
    }

  @Test
  fun `editor has one agent entry and preserves access notice at desktop and compact sizes`() =
    runComposeUiTest {
      var width by mutableStateOf(1280)
      var showAccessNotice by mutableStateOf(true)
      val host = Host()
      var sharingOpened = 0
      val empty = JsonObject(emptyMap())
      val document =
        UiBuilderDocument(
          "ui-builder-document.v1",
          "toolbar",
          "Design",
          1,
          empty,
          empty,
          empty,
          listOf("board"),
          mapOf("board" to UiBuilderBoard.node("board")),
        )
      val catalog =
        CapabilityCatalogParser.parse(
          checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
        )
      setContent {
        MaterialTheme {
          Box(Modifier.width(width.dp).height(800.dp).testTag("agent-toolbar-layout")) {
            UiBuilderEditor(
              document,
              catalog,
              agentHost = host,
              visibilityLabel = "Private · invited collaborators only",
              onManageVisibility = { sharingOpened++ },
              openingNotice = if (showAccessNotice) "Read-only" else null,
              openingNoticeAction =
                if (showAccessNotice) EditorNoticeAction("Sign in") {} else null,
            )
          }
        }
      }
      val output = File("build/agent-toolbar").apply { mkdirs() }
      for ((name, size) in listOf("desktop" to 1280, "compact" to 412)) {
        runOnIdle {
          width = size
          showAccessNotice = true
          host.agents = emptyList()
        }
        waitForIdle()
        onAllNodesWithText("Connect agent").assertCountEquals(1)
        onNodeWithText("Bring your agent into this design").assertDoesNotExist()
        onNodeWithText("Read-only").assertExists()
        onNodeWithText("Sign in").assertExists()
        onAllNodesWithText("Private · invited collaborators only").assertCountEquals(0)
        if (size > 840) {
          onNodeWithContentDescription("Sharing · Private · invited collaborators only")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        assertEquals(1, sharingOpened)
        runOnIdle { showAccessNotice = false }
        waitForIdle()
        ImageIO.write(
          onNodeWithTag("agent-toolbar-layout").captureToImage().toAwtImage(),
          "png",
          File(output, "$name-connect.png"),
        )
        runOnIdle { host.agents = listOf(claude) }
        waitForIdle()
        onNodeWithText("Claude Code").assertExists()
        ImageIO.write(
          onNodeWithTag("agent-toolbar-layout").captureToImage().toAwtImage(),
          "png",
          File(output, "$name-agent.png"),
        )
      }
      onNodeWithContentDescription("More editor actions").performSemanticsAction(
        SemanticsActions.OnClick
      ) {
        it()
      }
      onNodeWithText("Private · invited collaborators only").assertExists()
      onNodeWithText("Sharing").performSemanticsAction(SemanticsActions.OnClick) { it() }
      assertEquals(2, sharingOpened)
    }
}
