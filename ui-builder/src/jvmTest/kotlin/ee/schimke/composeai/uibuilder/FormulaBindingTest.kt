package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiExpressions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A property computed by a formula: typed in the inspector, stored as the expression tree the
 * player evaluates, and drawn by the canvas at the design's preview state and fixed time.
 */
@OptIn(ExperimentalTestApi::class)
class FormulaBindingTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/remote-m3-capabilities-v1.json")).readText()
    )
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = decodeProductionRendererDocument(DOCUMENT)

  @Test
  fun `a formula is parsed into the stored tree`() {
    val initial = reducer.initial(document, selectedNodeId = "time")

    val bound =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.BindPropertyToFormula("time", "text", "concat(count + 1, \" left\")"),
      )

    assertIs<CommandOutcome.Accepted>(bound.lastOutcome)
    val text = bound.document.nodes.getValue("time").properties.getValue("text").jsonObject
    assertEquals("expr", text.getValue("type").jsonPrimitive.content)
    assertEquals("concat(count + 1, \" left\")", UiExpressions.format(text))
    val field = reducer.propertyFields(bound).single { it.name == "text" }
    assertEquals("concat(count + 1, \" left\")", field.boundFormula)
    // Offered in the inspector only once a `.uid` file can hold one.
    assertEquals(UiExpressions.wireSupported, field.formulaAllowed)
  }

  @Test
  fun `a formula that does not parse or type is refused with the reason`() {
    val initial = reducer.initial(document, selectedNodeId = "time")

    val unknown =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.BindPropertyToFormula("time", "text", "nope + 1"),
      )
    val untyped =
      reducer.reduce(initial, UiBuilderEditorEvent.BindPropertyToFormula("time", "text", "!count"))

    val first = assertIs<CommandOutcome.Rejected>(unknown.lastOutcome)
    assertTrue("nope" in first.message, first.message)
    val second = assertIs<CommandOutcome.Rejected>(untyped.lastOutcome)
    assertTrue("not" in second.message, second.message)
  }

  @Test
  fun `unbinding a formula gives the property a literal again`() {
    val bound =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "time"),
        UiBuilderEditorEvent.BindPropertyToFormula("time", "text", "toString(time.hour)"),
      )

    val unbound = reducer.reduce(bound, UiBuilderEditorEvent.UnbindProperty("time", "text"))

    val text = unbound.document.nodes.getValue("time").properties.getValue("text").jsonObject
    assertEquals("string", text.getValue("type").jsonPrimitive.content)
  }

  @Test
  fun `the canvas draws a formula's value at the fixed time`() =
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent { ProductionUiBuilderSurface(document) }

      // `time.hour` and `time.minuteOfDay % 60` at 2024-05-16T12:34:56Z.
      onNodeWithText("12:34").assertExists()
    }

  private companion object {
    val DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"clock","title":"Clock",
       "revision":1,
       "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":216,"heightDp":124,"density":2.0,"theme":"dark",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr",
         "fixedTime":"2024-05-16T12:34:56Z"},
       "stateVariables":{"count":{"type":"value","valueType":"int","initialValue":3,
         "nullable":false,"persistence":"session"}},
       "roots":["widget"],
       "nodes":{
        "widget":{"id":"widget","componentId":"remote-m3/widget-container-large",
          "properties":{},"modifiers":[],
          "slots":{"background":[],"content":["time"]},"eventBindings":{}},
        "time":{"id":"time","componentId":"m3/text",
          "properties":{"text":{"type":"expr","op":"concat","args":[
            {"type":"system","value":"time.hour"},
            {"type":"string","value":":"},
            {"type":"expr","op":"mod","args":[
              {"type":"system","value":"time.minuteOfDay"},{"type":"int","value":60}]}]}},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
        .trimIndent()
  }
}
