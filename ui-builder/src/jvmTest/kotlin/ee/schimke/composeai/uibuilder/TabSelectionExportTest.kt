package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tab row a person can click, exported as one.
 *
 * The document has modelled this the whole time — a variable, a `set` on the tab's click, and a
 * `stateEquals` deciding which tab is selected — and both halves of the export dropped it: the tab
 * was written with `onClick = {}` and the row read `selectedTabIndex` as the literal the design was
 * saved with. The generated screen therefore had a tab row that could not move, which is the one
 * thing a tab row is for.
 */
@OptIn(ExperimentalTestApi::class)
class TabSelectionExportTest {
  private val reference by lazy {
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")) as JsonObject
      )
      .document
  }
  private val catalog by lazy {
    CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  }

  @Test
  fun `the click is emitted and the row reads the variable it writes`() {
    val source = exportSource(tabs())

    assertTrue(source.contains("selectedTabIndex = selectedTab"), source)
    assertTrue(source.contains("onClick = { selectedTab = 0 }"), source)
    assertTrue(source.contains("onClick = { selectedTab = 1 }"), source)
    assertTrue(source.contains("selected = selectedTab == 0"), source)
  }

  /**
   * A variable that cannot be an index is refused rather than coerced.
   *
   * `toInt()` on a `String` and `0` on a `Boolean` are both a guess about what the author meant,
   * and a guess here compiles into a screen that quietly shows the wrong tab.
   */
  @Test
  fun `an index bound to a variable of another type is a refusal`() {
    val source = exportSource(tabs(indexType = "string", indexInitial = JsonPrimitive("first")))

    assertTrue(
      source.contains("TODO(\"State variable selectedTab is declared String, not Int\")"),
      source,
    )
  }

  @Test
  fun `an index naming no declared variable is a refusal`() {
    val source = exportSource(tabs(indexVariable = "missing"))

    assertTrue(source.contains("TODO(\"Undeclared state variable missing\")"), source)
  }

  /**
   * The same document on the canvas, where the click has to land for anyone to author one.
   *
   * The export is only half the claim: a preview whose tabs do not move is what a person sees
   * first, and the row drew its indicator — and each tab its own selection — from the saved
   * literal, so the press wrote the variable and the canvas ignored it.
   */
  @Test
  fun `clicking a tab moves the selection on the canvas`() = runComposeUiTest {
    setContent { UiBuilderSurface(tabs()) }

    onNodeWithText("tab-0").assertIsSelected()
    onNodeWithText("tab-1").assertIsNotSelected()

    onNodeWithText("tab-1").performClick()

    onNodeWithText("tab-1").assertIsSelected()
    onNodeWithText("tab-0").assertIsNotSelected()
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun exportSource(document: UiBuilderDocument): String {
    val result = CapabilityComposeCodeExporter.export(document, catalog)
    assertTrue(result.successful, result.diagnostics.joinToString { it.message })
    return assertNotNull(result.source)
  }

  private fun stateValue(variable: String) =
    JsonObject(mapOf("type" to JsonPrimitive("state"), "variable" to JsonPrimitive(variable)))

  /** A two-tab row whose tabs write the variable the row reads. */
  private fun tabs(
    indexType: String = "int",
    indexInitial: JsonPrimitive = JsonPrimitive(0),
    indexVariable: String = "selectedTab",
  ): UiBuilderDocument {
    val tabIds = listOf(0, 1).map { "tab-$it" }
    val nodes =
      buildMap<String, UiBuilderNode> {
        put(
          "root",
          UiBuilderNode(
            id = "root",
            componentId = "m3/primary-tab-row",
            properties = JsonObject(mapOf("selectedIndex" to stateValue(indexVariable))),
            slots = mapOf("tabs" to tabIds),
          ),
        )
        tabIds.forEachIndexed { index, id ->
          put(
            id,
            UiBuilderNode(
              id = id,
              componentId = "m3/tab",
              properties =
                JsonObject(
                  mapOf(
                    "selected" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("stateEquals"),
                          "variable" to JsonPrimitive("selectedTab"),
                          "value" to JsonPrimitive(index),
                        )
                      )
                  )
                ),
              eventBindings =
                JsonObject(
                  mapOf(
                    "click" to
                      JsonArray(
                        listOf(
                          JsonObject(
                            mapOf(
                              "type" to JsonPrimitive("set"),
                              "variable" to JsonPrimitive("selectedTab"),
                              "value" to JsonPrimitive(index),
                            )
                          )
                        )
                      )
                  )
                ),
              slots = mapOf("text" to listOf("$id-label")),
            ),
          )
          put(
            "$id-label",
            UiBuilderNode(
              id = "$id-label",
              componentId = "m3/text",
              properties =
                JsonObject(mapOf("text" to JsonObject(mapOf("value" to JsonPrimitive(id))))),
            ),
          )
        }
      }
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "tabs",
      title = "Tabs",
      revision = 1,
      catalogPin = reference.catalogPin,
      environment = reference.environment,
      stateVariables =
        JsonObject(
          mapOf(
            "selectedTab" to
              JsonObject(
                mapOf(
                  "valueType" to JsonPrimitive(indexType),
                  "initialValue" to indexInitial,
                )
              )
          )
        ),
      roots = listOf("root"),
      nodes = nodes,
    )
  }
}
