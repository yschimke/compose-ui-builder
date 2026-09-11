package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
class BehaviorInspectorTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )
  private val reducer = UiBuilderEditorReducer(catalog)

  private fun document() =
    blankUiBuilderDocument("behavior-test", JsonObject(emptyMap()), JsonObject(emptyMap()))

  @Test
  fun `state and actions can be authored using the actual inspectors`() =
    runDesktopComposeUiTest(width = 1000, height = 700) {
      val initial = document()
      // The real editor reducer validates catalog, topology, actions and protocol submissions.
      var state by mutableStateOf(reducer.initial(initial))
      val buttonId = "behavior-button"
      val button =
        UiBuilderNode(
          buttonId,
          "m3/button",
          slots = mapOf("content" to listOf("button-text")),
          properties =
            buildJsonObject {
              put(
                "style",
                buildJsonObject {
                  put("type", "enum")
                  put("value", "filled")
                },
              )
            },
        )
      state =
        reducer.initial(
          initial.copy(
            nodes =
              initial.nodes +
                (buttonId to button) +
                ("button-text" to
                  UiBuilderNode(
                    "button-text",
                    "m3/text",
                    properties =
                      Json.parseToJsonElement(
                          """{"text":{"type":"string","value":"Toggle"},"style":{"type":"typographyToken","value":"labelLarge"}}"""
                        )
                        .jsonObject,
                  )) +
                ("screen-content" to
                  initial.nodes
                    .getValue("screen-content")
                    .copy(slots = mapOf("children" to listOf(buttonId))))
          )
        )
      setContent {
        MaterialTheme {
          Surface {
            Row(
              Modifier.fillMaxSize().padding(24.dp),
              horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
              Column(Modifier.width(420.dp).verticalScroll(rememberScrollState())) {
                Text("Screen", style = MaterialTheme.typography.headlineSmall)
                StateVariablesInspector(state.document, {}, { state = reducer.reduce(state, it) })
              }
              Column(Modifier.width(420.dp).verticalScroll(rememberScrollState())) {
                Text("Toggle · properties", style = MaterialTheme.typography.headlineSmall)
                EventActionsInspector(
                  state.document,
                  state.document.nodes.getValue(buttonId),
                  {},
                  { state = reducer.reduce(state, it) },
                )
              }
            }
          }
        }
      }
      fun capture(name: String) {
        val screenshot = onRoot().captureToImage()
        val folder =
          File(System.getProperty("uiBuilderProjectDir"), "build/behavior-evidence").apply {
            mkdirs()
          }
        File(folder, name)
          .writeBytes(
            requireNotNull(
                Image.makeFromBitmap(screenshot.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
              )
              .bytes
          )
      }
      onNodeWithText("State · 0").performClick()
      onNodeWithText("Actions").performClick()
      capture("before-wiring.png")
      onNodeWithText("State name").performTextInput("expanded")
      onNodeWithContentDescription("Save state variable").performClick()
      runOnIdle {
        assertTrue("expanded" in state.document.stateVariables, state.lastOutcome.toString())
      }
      onNodeWithText("Choose state").performClick()
      onNodeWithContentDescription("Use state expanded").performClick()
      onNodeWithText("Toggle", useUnmergedTree = true).performClick()
      onNodeWithContentDescription("Save event action").performClick()
      runOnIdle {
        assertEquals(
          "toggle",
          state.document.nodes
            .getValue(buttonId)
            .eventBindings
            .getValue("click")
            .jsonArray
            .single()
            .jsonObject["type"]
            ?.jsonPrimitive
            ?.content,
        )
      }
      capture("state-and-actions.png")
      onNodeWithContentDescription("Remove state expanded").performClick()
      runOnIdle { assertTrue("expanded" in state.document.stateVariables) }
      onNodeWithContentDescription("Remove action 1").performClick()
      onNodeWithContentDescription("Remove state expanded").performClick()
      runOnIdle { assertTrue(state.document.stateVariables.isEmpty()) }
    }
}
