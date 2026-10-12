package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ScreenParameter
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * What a Wear catalog's stickers need from the export (yschimke/wear-m3-catalog's `uid-stickers`):
 * `wear-m3/text`'s alignment and Wear type-scale roles, a split control's container and selection
 * clicks as intents, and its toggle's own content description as a string binding.
 */
class WearStickerVocabularyTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun document(control: String, text: String = "", checked: Boolean = true) =
    json.decodeFromString<DesignDocumentV1>(
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "row", "title": "Row", "revision": 0,
        "catalogPin": {"systemId": "wear-m3", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 192, "heightDp": 192, "density": 2.0, "theme": "dark",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
        "roots": ["place"],
        "components": {"row": {"name": "Row", "root": "control"}},
        "nodes": {
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "row",
              "arguments": {"label": {"type": "string", "value": "Label"}${
                if (checked) """, "checked": {"type": "bool", "value": true}""" else ""
              }}}},
          "control": {"id": "control", $control, "slots": {"label": ["text"]}},
          "text": {"id": "text", "componentId": "wear-m3/text",
            "properties": {"text": {"type": "binding", "value": "label"}$text}}
        }
      }
      """
    )

  private fun component(document: DesignDocumentV1) =
    ScreenDocumentProjection.project(document, emptyMap(), structure = ProjectedStructure()).let {
      assertIs<ScreenDocumentProjection.Outcome.Projected>(it, it.toString())
      it.document.functions.single()
    }

  private val button =
    """"componentId": "wear-m3/filled-tonal-button", "properties": {},
    "eventBindings": {"click": []}"""

  @Test
  fun `wear text takes an alignment and a role from Wear's own type scale`() {
    val row =
      component(
        document(
          button,
          """, "textAlign": {"type": "enum", "value": "start"},
            "style": {"type": "enum", "value": "bodyMedium"}""",
          checked = false,
        )
      )
    val tree = row.root.toString()
    assertTrue("androidx.compose.ui.text.style.TextAlign" in tree && "Start" in tree, tree)
    assertTrue("androidx.wear.compose.material3.MaterialTheme" in tree, tree)
    assertTrue("bodyMedium" in tree, tree)
  }

  @Test
  fun `a split control's two taps are two intents and its description a string binding`() {
    val row =
      component(
        document(
          """"componentId": "wear-m3/split-checkbox-button",
            "properties": {"checked": {"type": "binding", "value": "checked"},
              "toggleContentDescription": {"type": "binding", "value": "label"}},
            "eventBindings": {"checkedChange": [], "containerClick": []}"""
        )
      )
    val parameters = row.parameters.associateBy { it.name }
    assertIs<ScreenParameter.Callback>(parameters["onControlCheckedChange"], parameters.toString())
    assertIs<ScreenParameter.Callback>(parameters["onControlContainerClick"], parameters.toString())
    assertEquals("kotlin.String", assertIs<ScreenParameter.Value>(parameters["label"]).typeFqn)
  }

  @Test
  fun `a radio's select and a split radio's selection click are intents`() {
    for ((component, event) in
      listOf(
        "wear-m3/radio-button" to "select",
        "wear-m3/split-radio-button" to "selectionClick",
      )) {
      val row =
        component(
          document(
            """"componentId": "$component",
              "properties": {"selected": {"type": "binding", "value": "checked"}},
              "eventBindings": {"$event": []}"""
          )
        )
      val name = "onControl" + event.replaceFirstChar { it.uppercaseChar() }
      assertTrue(
        row.parameters.any { it is ScreenParameter.Callback && it.name == name },
        row.parameters.toString(),
      )
    }
  }
}
