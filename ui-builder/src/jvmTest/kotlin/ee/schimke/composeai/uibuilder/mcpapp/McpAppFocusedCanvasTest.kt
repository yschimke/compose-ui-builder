package ee.schimke.composeai.uibuilder.mcpapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.UidDesignFiles
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The MCP App's focused canvas (compose-ui-builder#374), driven through [McpAppEditorScreen] — the
 * same composable the browser host shows — against a fake host.
 *
 * The design is `state-actions.uid` with the button's label made a literal "Ready", so the quick
 * editor has a text field to type in rather than a state binding to show.
 */
@OptIn(ExperimentalTestApi::class)
class McpAppFocusedCanvasTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val file = McpAppFile("state-actions.uid", "host-resource://design")
  private val designText: String = run {
    val design = UidDesignFiles.decode(resource("/state-actions.uid"))
    val label = design.nodes.getValue("label")
    UidDesignFiles.encode(
      design.copy(
        nodes =
          design.nodes +
            ("label" to
              label.copy(
                properties =
                  JsonObject(
                    mapOf(
                      "text" to
                        JsonObject(
                          mapOf(
                            "type" to JsonPrimitive("string"),
                            "value" to JsonPrimitive("Ready"),
                          )
                        )
                    )
                  )
              ))
      )
    )
  }

  private class Harness(val host: FakeMcpAppHost) {
    var state by mutableStateOf<McpAppDesignState?>(null)
    val session =
      McpAppDesignSession(host, McpAppFile("state-actions.uid", "host-resource://design")) {
        state = it
      }
    var layout by mutableStateOf(McpAppLayout.Focused)
    var editor: UiBuilderEditorState? = null
  }

  private fun ComposeUiTest.open(
    layout: McpAppLayout = McpAppLayout.Focused,
    comments: Boolean = true,
  ): Harness {
    val harness = Harness(FakeMcpAppHost(file.resourceUri, designText))
    harness.layout = layout
    val session = harness.session
    runImmediate { session.open() }
    setContent {
      MaterialTheme {
        McpAppEditorScreen(
          state = checkNotNull(harness.state),
          session = session,
          catalog = catalog,
          layout = harness.layout,
          onLayoutChange = { harness.layout = it },
          commentsEnabled = comments,
          onEditorState = { editor ->
            harness.editor = editor
            session.edited(editor.document)
          },
        )
      }
    }
    waitForIdle()
    return harness
  }

  /** Right-clicks the canvas's "Ready" label, which selects it and opens its menu. */
  private fun ComposeUiTest.openLabelMenu() {
    val label = onAllNodesWithText("Ready").onFirst().fetchSemanticsNode().boundsInRoot
    window().performMouseInput { rightClick(label.center) }
    waitForIdle()
  }

  @Test
  fun `the focused layout has no docks, toolbar or status bar, and the canvas fills the panel`() =
    runDesktopComposeUiTest(width = 900, height = 900) {
      open()

      onNodeWithContentDescription("Full editor").assertExists()
      for (dock in listOf("components", "layers", "properties", "comments", "issues", "code")) {
        onNodeWithContentDescription("Open $dock panel").assertDoesNotExist()
      }
      onAllNodes(hasContentDescription("Undo", substring = true)).assertCountEquals(0)
      // The design sits in the middle of the whole panel: nothing on either side takes width.
      val label = onAllNodesWithText("Ready").onFirst().fetchSemanticsNode().boundsInRoot
      val panel = window().fetchSemanticsNode().boundsInRoot
      // The label sits at the start of a 400dp design, so a design centred in the whole panel puts
      // it just left of the middle; a dock or a rail on either side would push it off that.
      assertTrue(
        label.center.x in (panel.center.x - 200f)..panel.center.x,
        "the label at ${label.center.x} is not where a design centred in the panel " +
          "(${panel.center.x}) puts it",
      )
    }

  @Test
  fun `the full layout still has its docks`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      open(McpAppLayout.Full)

      onNodeWithContentDescription("Focused canvas").assertExists()
      onNodeWithContentDescription("Open layers panel").assertExists()
      onNodeWithContentDescription("Open components panel").assertExists()
    }

  @Test
  fun `the node menu offers Quick edit and Comment, and no Properties panel to open`() =
    runDesktopComposeUiTest(width = 900, height = 900) {
      open()
      openLabelMenu()

      onNodeWithText("Quick edit").assertExists()
      onNodeWithText("Comment").assertExists()
      onNodeWithText("Properties").assertDoesNotExist()
    }

  @Test
  fun `a host that takes no messages gets no Comment row`() =
    runDesktopComposeUiTest(width = 900, height = 900) {
      open(comments = false)
      openLabelMenu()

      onNodeWithText("Quick edit").assertExists()
      onNodeWithText("Comment").assertDoesNotExist()
    }

  @Test
  fun `quick edit in the focused layout saves through the bridge`() =
    runDesktopComposeUiTest(width = 900, height = 900) {
      val harness = open()
      openLabelMenu()
      onNodeWithText("Quick edit").performClick()
      waitForIdle()

      editLabel("Go")
      // What the browser host's autosave does a moment after the edit.
      runImmediate { harness.session.save() }

      val written = UidDesignFiles.decode(harness.host.writes.single().text)
      assertEquals("v1", harness.host.writes.single().ifMatch)
      assertEquals("Go", written.labelText())
      assertEquals("Saved", savedStatus(harness))
    }

  @Test
  fun `a comment sends one model context and one message about the node`() =
    runDesktopComposeUiTest(width = 900, height = 900) {
      val harness = open()
      openLabelMenu()
      onNodeWithText("Comment").performClick()
      waitForIdle()

      onNodeWithContentDescription("Comment on node").assertExists()
      val field = onNodeWithContentDescription("Comment text")
      // The card takes the keyboard: the caret is already in it, and Enter sends.
      field.assertIsFocused()
      field.performTextInput("Make this say Start")
      field.performKeyInput { pressKey(Key.Enter) }
      waitUntil(timeoutMillis = 10_000) { harness.host.messages.isNotEmpty() }
      waitForIdle()

      assertEquals(listOf("ui/update-model-context", "ui/message"), harness.host.conversation)
      val message = harness.host.messages.single().toParams()
      val blocks = message["content"]!!.jsonArray.map { it.jsonObject }
      assertEquals("Make this say Start", blocks[0]["text"]!!.jsonPrimitive.content)
      assertEquals(
        "Text · label",
        blocks[1]["_meta"]!!.jsonObject["openai/title"]!!.jsonPrimitive.content,
      )
      val context = harness.host.contexts.single()
      val types = context.content.map { it.jsonObject["type"]!!.jsonPrimitive.content }
      // The label drawn on its own, then the assistant-only detail.
      assertEquals(listOf("image", "text"), types)
      val image = context.content[0].jsonObject
      assertEquals("image/png", image["mimeType"]!!.jsonPrimitive.content)
      assertTrue(image["data"]!!.jsonPrimitive.content.isNotEmpty())
      onNodeWithContentDescription("Comment on node").assertDoesNotExist()
      assertTrue(harness.host.writes.isEmpty(), "a comment wrote the file")
    }

  @Test
  fun `an empty comment sends nothing`() =
    runDesktopComposeUiTest(width = 900, height = 900) {
      val harness = open()
      openLabelMenu()
      onNodeWithText("Comment").performClick()
      waitForIdle()

      onNodeWithContentDescription("Send comment").assertIsNotEnabled()
      onNodeWithContentDescription("Comment text").performTextInput("   ")
      onNodeWithContentDescription("Comment text").performKeyInput { pressKey(Key.Enter) }
      waitForIdle()
      onNodeWithContentDescription("Send comment").assertIsNotEnabled()
      onNodeWithText("Cancel").performClick()
      waitForIdle()

      onNodeWithContentDescription("Comment on node").assertDoesNotExist()
      assertTrue(harness.host.conversation.isEmpty(), harness.host.conversation.toString())
    }

  @Test
  fun `the layout toggle round-trips and keeps the selection and the edit`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      val harness = open()
      openLabelMenu()
      onNodeWithText("Quick edit").performClick()
      waitForIdle()
      editLabel("Go")
      val before = assertNotNull(harness.editor)
      assertEquals("label", before.selectedNodeId)

      onNodeWithContentDescription("Full editor").performClick()
      waitForIdle()
      assertEquals(McpAppLayout.Full, harness.layout)
      onNodeWithContentDescription("Open layers panel").assertExists()
      assertEquals("label", harness.editor?.selectedNodeId)

      onNodeWithContentDescription("Focused canvas").performClick()
      waitForIdle()
      assertEquals(McpAppLayout.Focused, harness.layout)
      onNodeWithContentDescription("Open layers panel").assertDoesNotExist()

      val after = assertNotNull(harness.editor)
      assertEquals("label", after.selectedNodeId)
      assertEquals("Go", after.document.labelText())
      // The same editor, not a new one: its undo history came through both switches.
      assertTrue(UiBuilderEditorReducer(catalog).canUndo(after), "the switch lost the undo history")
      assertTrue(harness.session.state.dirty)
    }

  /**
   * Types over the label's Text in the open quick editor, then presses the empty canvas beside the
   * design: the card closes, and leaving the field is its commit.
   */
  private fun ComposeUiTest.editLabel(text: String) {
    val field = onNodeWithContentDescription("Text value")
    field.performMouseInput { click() }
    waitForIdle()
    field.assertIsFocused()
    field.performTextReplacement(text)
    val panel = window().fetchSemanticsNode().boundsInRoot
    window().performMouseInput { click(Offset(24f, panel.bottom - 24f)) }
    waitForIdle()
    onNodeWithContentDescription("Selection editor").assertDoesNotExist()
  }

  private fun savedStatus(harness: Harness): String =
    with(harness.session.state) { if (dirty) "Unsaved changes" else "Saved" }

  private fun UiBuilderDocument.labelText(): String? =
    (nodes["label"]?.properties?.get("text") as? JsonObject)?.get("value")?.jsonPrimitive?.content

  private fun ComposeUiTest.window() = onAllNodes(isRoot()).onFirst()

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
