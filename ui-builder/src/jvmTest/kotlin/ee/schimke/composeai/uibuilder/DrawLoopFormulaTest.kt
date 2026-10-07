package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A formula on an operation inside a `draw/repeat` may read the loop's index as `@i`; the same
 * formula on an operation outside every loop names a field that is not there, and is refused.
 */
class DrawLoopFormulaTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/remote-m3-capabilities-v1.json")).readText()
    )
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = decodeProductionRendererDocument(DOCUMENT)

  @Test
  fun `an operation inside a repeat reads its index`() {
    val bound =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "tick"),
        UiBuilderEditorEvent.BindPropertyToFormula("tick", "xDp", "@i * 20"),
      )

    assertIs<CommandOutcome.Accepted>(
      bound.lastOutcome,
      (bound.lastOutcome as? CommandOutcome.Rejected)?.message,
    )
  }

  @Test
  fun `an operation outside every repeat cannot`() {
    val bound =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "loose"),
        UiBuilderEditorEvent.BindPropertyToFormula("loose", "xDp", "@i * 20"),
      )

    val refused = assertIs<CommandOutcome.Rejected>(bound.lastOutcome)
    assertTrue("`i`" in refused.message, refused.message)
  }

  private companion object {
    val DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"loop","title":"Loop",
       "revision":1,
       "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":216,"heightDp":124,"density":2.0,"theme":"dark",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{},"roots":["widget"],
       "nodes":{
        "widget":{"id":"widget","componentId":"remote-m3/widget-container-large",
          "properties":{},"modifiers":[],
          "slots":{"background":[],"content":["canvas"]},"eventBindings":{}},
        "canvas":{"id":"canvas","componentId":"draw/canvas","properties":{},
          "modifiers":[{"type":"size","widthDp":200,"heightDp":100}],
          "slots":{"ops":["ticks","loose"]},"eventBindings":{}},
        "ticks":{"id":"ticks","componentId":"draw/repeat","properties":{
          "until":{"type":"float","value":5}},
          "modifiers":[],"slots":{"ops":["tick"]},"eventBindings":{}},
        "tick":{"id":"tick","componentId":"draw/rect","properties":{
          "widthDp":{"type":"float","value":10},"heightDp":{"type":"float","value":10}},
          "modifiers":[],"slots":{},"eventBindings":{}},
        "loose":{"id":"loose","componentId":"draw/rect","properties":{
          "widthDp":{"type":"float","value":10},"heightDp":{"type":"float","value":10}},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
        .trimIndent()
  }
}
