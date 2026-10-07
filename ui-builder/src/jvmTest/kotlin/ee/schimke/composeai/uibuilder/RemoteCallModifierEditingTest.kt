package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Remote call (here `visibility`) from the generated vocabulary is offered on the modifier menu,
 * added with a starting value for each argument it requires, and its numeric arguments edit in
 * place.
 */
class RemoteCallModifierEditingTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/remote-m3-capabilities-v1.json")).readText()
    )
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = decodeProductionRendererDocument(DOCUMENT)

  private fun visibility(state: ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState) =
    state.document.nodes
      .getValue("label")
      .modifiers
      .map { it.jsonObject }
      .single { it["name"]?.jsonPrimitive?.content == "visibility" }

  @Test
  fun `a Remote call is offered, added, edited and removed`() {
    val initial = reducer.initial(document, selectedNodeId = "label")

    val offered = reducer.modifierToggles(initial).single { it.type == "remoteCall:visibility" }
    assertEquals("Remote: visibility", offered.label)
    assertTrue(!offered.applied)
    // A call a typed modifier already writes is not offered twice.
    assertTrue(reducer.modifierToggles(initial).none { it.type == "remoteCall:border" })

    val added =
      reducer.reduce(initial, UiBuilderEditorEvent.ToggleModifier("label", "remoteCall:visibility"))
    assertIs<CommandOutcome.Accepted>(
      added.lastOutcome,
      (added.lastOutcome as? CommandOutcome.Rejected)?.message,
    )
    val args = visibility(added).getValue("args").jsonObject
    assertEquals(setOf("visible"), args.keys)
    assertTrue(reducer.modifierToggles(added).single { it.type == "remoteCall:visibility" }.applied)

    val field = reducer.modifierFields(added).single { it.type == "remoteCall:visibility" }
    assertEquals("visible", field.field)
    assertEquals("0", field.value)

    val edited =
      reducer.reduce(
        added,
        UiBuilderEditorEvent.SetModifierValue("label", "remoteCall:visibility", "visible", "2"),
      )
    assertIs<CommandOutcome.Accepted>(edited.lastOutcome)
    val visible = visibility(edited).getValue("args").jsonObject.getValue("visible") as JsonObject
    assertEquals("int", visible.getValue("type").jsonPrimitive.content)
    assertEquals("2", visible.getValue("value").jsonPrimitive.content)

    val removed =
      reducer.reduce(edited, UiBuilderEditorEvent.ToggleModifier("label", "remoteCall:visibility"))
    assertTrue(removed.document.nodes.getValue("label").modifiers.isEmpty())
  }

  private companion object {
    val DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"calls","title":"Calls",
       "revision":1,
       "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":216,"heightDp":124,"density":2.0,"theme":"dark",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{},
       "roots":["widget"],
       "nodes":{
        "widget":{"id":"widget","componentId":"remote-m3/widget-container-large",
          "properties":{},"modifiers":[],
          "slots":{"background":[],"content":["label"]},"eventBindings":{}},
        "label":{"id":"label","componentId":"remote-m3/remote-text",
          "properties":{"text":{"type":"string","value":"Hi"}},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
        .trimIndent()
  }
}
