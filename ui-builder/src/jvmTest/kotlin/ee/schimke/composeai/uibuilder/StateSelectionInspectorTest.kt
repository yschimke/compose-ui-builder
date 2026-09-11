package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

@OptIn(ExperimentalTestApi::class)
class StateSelectionInspectorTest {
  @kotlin.test.BeforeTest
  fun requireExperimentalBuild() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
  }

  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )
  private val reducer = UiBuilderEditorReducer(catalog)

  private fun document(): UiBuilderDocument {
    val initial =
      blankUiBuilderDocument("selection", JsonObject(emptyMap()), JsonObject(emptyMap()))
    val texts =
      listOf("First", "Second", "Other").associateWith { label ->
        UiBuilderNode(
          label,
          "m3/text",
          buildJsonObject { put("text", selectionLiteral(JsonPrimitive(label))) },
        )
      }
    return initial.copy(
      stateVariables =
        Json.parseToJsonElement(
            """{"page":{"type":"value","persistence":"preview","valueType":"int","initialValue":10}}"""
          )
          .jsonObject,
      nodes =
        initial.nodes +
          texts +
          ("choice" to
            UiBuilderNode(
              "choice",
              "layout/box",
              slots = mapOf("children" to texts.keys.toList()),
            )) +
          ("screen-content" to
            initial.nodes
              .getValue("screen-content")
              .copy(slots = mapOf("children" to listOf("choice")))),
    )
  }

  @Test
  fun `the full editor offers state selection on a box with no click action`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document(),
            catalog,
            onStateChanged = { latest = it },
            initialSelectedNodeId = "choice",
            initialInspectorOpen = true,
          )
        }
      }
      onNodeWithText("Show by state").assertIsDisplayed().performClick()
      onNodeWithText("Choose state").performClick()
      onNodeWithText("page").performClick()
      onNodeWithContentDescription("Case value First").performTextInput("10")
      onNodeWithContentDescription("Case value Second").performTextInput("20")
      onNodeWithContentDescription("Fallback Other").performClick()
      onNodeWithText("Apply cases").performClick()
      runOnIdle {
        val node = assertNotNull(latest).document.nodes.getValue("choice")
        assertNotNull(node.stateSelection())
        assertTrue(node.eventBindings.isEmpty())
      }
    }

  @Test
  fun `selection is undoable and duplication remaps case identities`() {
    var state = reducer.initial(document())
    val selection =
      StateSelection(
        buildJsonObject {
          put("type", "state")
          put("variable", "page")
        },
        mapOf("First" to JsonPrimitive(10), "Second" to JsonPrimitive(20)),
        "Other",
      )
    state = reducer.reduce(state, UiBuilderEditorEvent.SetStateSelection("choice", selection))
    assertEquals(
      selection,
      state.document.nodes.getValue("choice").stateSelection(),
      state.lastOutcome.toString(),
    )
    state = reducer.reduce(state, UiBuilderEditorEvent.Undo)
    assertNull(state.document.nodes.getValue("choice").stateSelection())
    state = reducer.reduce(state, UiBuilderEditorEvent.Redo)
    assertEquals(selection, state.document.nodes.getValue("choice").stateSelection())
    state = reducer.reduce(state, UiBuilderEditorEvent.DuplicateSelected)
    val copy = state.document.nodes.getValue(requireNotNull(state.selectedNodeId))
    assertNotEquals("choice", copy.id, state.lastOutcome.toString())
    val copiedSelection = assertNotNull(copy.stateSelection())
    assertEquals(selection.cases.values.toList(), copiedSelection.cases.values.toList())
    assertTrue(copiedSelection.cases.keys.none { it in selection.cases })
    assertNull(stateSelectionIssue(copy, state.document.stateVariables))
  }

  @Test
  fun `the real inspector commits cases and the existing canvas shows only the selected child`() =
    runDesktopComposeUiTest(width = 1000, height = 800) {
      var state by mutableStateOf(reducer.initial(document()))
      setContent {
        MaterialTheme {
          Surface {
            Row {
              Column(Modifier.width(440.dp).padding(16.dp)) {
                StateSelectionInspector(
                  state.document,
                  state.document.nodes.getValue("choice"),
                  {},
                  { state = reducer.reduce(state, it) },
                )
              }
              Box(Modifier.width(400.dp)) { UiBuilderSurface(state.document) }
            }
          }
        }
      }
      fun capture(name: String) {
        val bitmap = onRoot().captureToImage().asSkiaBitmap()
        File(System.getProperty("uiBuilderProjectDir"), "build/selection-evidence/$name.png")
          .apply {
            parentFile.mkdirs()
            writeBytes(
              requireNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG))
                .bytes
            )
          }
      }
      capture("before")
      onNodeWithText("Show by state").performClick()
      onNodeWithText("Choose state").performClick()
      onNodeWithText("page").performClick()
      onNodeWithContentDescription("Case value First").performTextInput("10")
      onNodeWithContentDescription("Case value Second").performTextInput("20")
      onNodeWithContentDescription("Fallback Other").performClick()
      onNodeWithText("Apply cases").assertIsEnabled().performClick()
      runOnIdle {
        assertNotNull(
          state.document.nodes.getValue("choice").stateSelection(),
          state.lastOutcome.toString(),
        )
      }
      capture("configured")
      onNodeWithText("First", substring = false).assertExists()
      onNodeWithText("Second", substring = false).assertDoesNotExist()
      onNodeWithText("Other", substring = false).assertDoesNotExist()
      runOnIdle {
        state =
          reducer.reduce(
            state,
            UiBuilderEditorEvent.SetStateVariable(
              "page",
              buildJsonObject {
                put("type", "value")
                put("persistence", "preview")
                put("valueType", "int")
                put("initialValue", 20)
              },
            ),
          )
      }
      runOnIdle {
        assertEquals(
          "20",
          state.document.stateVariables["page"]
            ?.jsonObject
            ?.get("initialValue")
            ?.jsonPrimitive
            ?.content,
          state.lastOutcome.toString(),
        )
      }
      onNodeWithText("Second", substring = false).assertExists()
      onNodeWithText("First", substring = false).assertDoesNotExist()
      runOnIdle {
        state =
          reducer.reduce(
            state,
            UiBuilderEditorEvent.SetStateVariable(
              "page",
              buildJsonObject {
                put("type", "value")
                put("persistence", "preview")
                put("valueType", "int")
                put("initialValue", 30)
              },
            ),
          )
      }
      onNodeWithText("Other", substring = false).assertExists()
      onNodeWithText("Second", substring = false).assertDoesNotExist()
      onNodeWithText("Show all children").performClick()
      runOnIdle { assertFalse(SHOW_BY_STATE in state.document.nodes.getValue("choice").properties) }
      onNodeWithText("First", substring = false).assertExists()
      onNodeWithText("Second", substring = false).assertExists()
    }
}
