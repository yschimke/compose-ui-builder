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
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
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
    // A window as wide as the widest layout, so the 1280dp box is not clipped to the default 1024.
    runDesktopComposeUiTest(width = 1280, height = 800) {
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

  // The fold follows the window size class, so each width is its own window rather than a resized
  // box inside one.
  @Test
  fun `hosted toolbar folds session actions and viewers into More below the Large width class`() {
    for (width in listOf(960, 848)) {
      hostedToolbar(width) { output ->
        // The agent control and More are the trailing controls the row used to clip.
        assertFullyOnToolbar(hasText("Connect agent"), 100.dp)
        assertFullyOnToolbar(hasContentDescription("More editor actions"), 40.dp)
        onNodeWithContentDescription("New design").assertDoesNotExist()
        onNodeWithContentDescription("Fork this design").assertDoesNotExist()
        onNodeWithContentDescription("Sharing · Private · invited collaborators only")
          .assertDoesNotExist()
        onNodeWithText("Viewers · 2").assertDoesNotExist()
        onNodeWithContentDescription("More editor actions").performSemanticsAction(
          SemanticsActions.OnClick
        ) {
          it()
        }
        onNodeWithText("New design").assertIsDisplayed()
        onNodeWithText("Fork this design").assertIsDisplayed()
        onNodeWithText("Private · invited collaborators only").assertIsDisplayed()
        onNodeWithText("Viewers · 2").assertIsDisplayed()
        onNodeWithText("Ada · browser").assertIsDisplayed()
        onNodeWithText("Fork this design").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, forked)
        onNodeWithContentDescription("More editor actions").performSemanticsAction(
          SemanticsActions.OnClick
        ) {
          it()
        }
        onNodeWithText("Sharing").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, sharingOpened)
        capture(output, "intermediate-$width.png")
      }
    }
  }

  @Test
  fun `hosted toolbar shows every action from the Large width class up`() =
    hostedToolbar(1440) { output ->
      capture(output, "wide-1440.png")
      assertFullyOnToolbar(hasContentDescription("New design"), 40.dp)
      assertFullyOnToolbar(hasContentDescription("Fork this design"), 40.dp)
      assertFullyOnToolbar(
        hasContentDescription("Sharing · Private · invited collaborators only"),
        40.dp,
      )
      assertFullyOnToolbar(hasText("Viewers · 2"), 40.dp)
      assertFullyOnToolbar(hasText("Connect agent"), 100.dp)
      assertFullyOnToolbar(hasContentDescription("More editor actions"), 40.dp)
    }

  private class HostedToolbar(test: ComposeUiTest) : ComposeUiTest by test {
    var forked = 0
    var sharingOpened = 0

    fun capture(output: File, name: String) {
      ImageIO.write(
        onNodeWithTag("toolbar-overflow-layout").captureToImage().toAwtImage(),
        "png",
        File(output, name),
      )
    }

    // Whole and full size, not merely intersecting the window: a control squeezed to a sliver or
    // half pushed off the end still counts as displayed.
    fun assertFullyOnToolbar(matcher: SemanticsMatcher, minWidth: Dp) {
      val node =
        onNode(matcher).assertIsDisplayed().assertWidthIsAtLeast(minWidth).fetchSemanticsNode()
      val layout = onNodeWithTag("toolbar-overflow-layout").fetchSemanticsNode().boundsInRoot
      assertTrue(
        node.boundsInRoot.right <= layout.right,
        "${matcher.description} ends at ${node.boundsInRoot.right}",
      )
    }
  }

  /** The editor filling a [width]dp window, with everything LiveSessionApp supplies at once. */
  private fun hostedToolbar(width: Int, block: HostedToolbar.(output: File) -> Unit) =
    runDesktopComposeUiTest(width = width, height = 700) {
      val toolbar = HostedToolbar(this)
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
      val exportHost =
        object : UiBuilderExportHost {
          override val formats = listOf(EditorExportFormat.Png)

          override suspend fun copyPicture(format: EditorExportFormat) = "copied"

          override suspend fun copyLink(format: EditorExportFormat) = "linked"

          override suspend fun download(format: EditorExportFormat) = "downloaded"
        }
      val reviewer =
        UiBuilderCollaborator(
          "ada",
          "Ada",
          "#AA3355",
          emptyList(),
          UiBuilderParticipantKind.Browser,
        )
      setContent {
        MaterialTheme {
          Box(Modifier.fillMaxSize().testTag("toolbar-overflow-layout")) {
            UiBuilderEditor(
              document,
              catalog,
              collaborators = listOf(reviewer),
              agentHost = Host(),
              exportHost = exportHost,
              newDesignCatalogs =
                listOf(UiBuilderNewDesignCatalog("m3", "Material 3", emptyList())),
              onCreateDesign = { _, _, _, _ -> },
              onBrowseDesigns = {},
              onForkDesign = { toolbar.forked++ },
              visibilityLabel = "Private · invited collaborators only",
              onManageVisibility = { toolbar.sharingOpened++ },
            )
          }
        }
      }
      waitForIdle()
      toolbar.block(File("build/toolbar-overflow").apply { mkdirs() })
    }
}
