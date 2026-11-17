package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.canvas.forPlatform
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.NewDesignDevice
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignTemplate
import ee.schimke.composeai.uibuilder.editor.newDesignDevice
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A Watch Face Format catalog in the editor: platform routing, device frames and the code pane. */
class WatchFaceEditorTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      syntheticCatalogJson(
        "wff-watchface",
        buildJsonObject { put("platform", "watchface") },
        listOf(
          syntheticComponent(
            "wff/scene",
            "Watch face",
            "Scaffold",
            slots = listOf(syntheticSlot("content", max = null)),
          ),
          syntheticComponent("wff/part-text", "PartText", "Leaf") { p("text", "string") },
        ),
      ),
    )
  private val reducer = UiBuilderEditorReducer(catalog)

  private fun document(vararg nodes: String) =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """{"schema":"compose-ui-builder-document/v1-candidate","id":"face","title":"Face",
        "revision":0,"catalogPin":{"systemId":"wff-watchface"},"environment":{},
        "stateVariables":{},"roots":["scene"],"nodes":{
          "scene":{"id":"scene","componentId":"wff/scene","slots":{"content":[${nodes.joinToString { "\"$it\"" }}]}},
          "hello":{"id":"hello","componentId":"wff/part-text",
            "properties":{"text":{"type":"string","value":"Hi"}}}}}""",
    )

  @Test
  fun `the catalog is a watch face platform and the code pane shows WFF XML, not Kotlin`() {
    assertEquals(UiBuilderCatalogPlatform.WATCH_FACE, catalog.platform)
    val source = assertIs<EditorGeneratedCode.Source>(reducer.generatedCode(document("hello")))
    assertTrue(source.kotlin.contains("<WatchFace width=\"450\" height=\"450\""), source.kotlin)
    assertTrue(source.kotlin.contains("<Template>Hi</Template>"), source.kotlin)
  }

  @Test
  fun `a node the face cannot write is refused in the code pane by name`() {
    val foreign =
      document("hello").let {
        it.copy(
          nodes = it.nodes + ("hello" to it.nodes.getValue("hello").copy(componentId = "m3/text")),
        )
      }
    val refused = assertIs<EditorGeneratedCode.Refused>(reducer.generatedCode(foreign))
    assertTrue(
      refused.reasons.any { it.startsWith("nodes.hello (`m3/text`)") },
      refused.reasons.toString(),
    )
  }

  @Test
  fun `a watch face is offered on Wear OS frames and is a watch design`() {
    val watch =
      UiBuilderDevicePreset(
        "id:wearos_large_round",
        "Wear OS Large Round",
        "Wear OS",
        227,
        227,
        2.0,
      )
    val phone = UiBuilderDevicePreset("id:pixel_7", "Pixel 7", "Phones", 411, 914, 2.625)
    assertEquals(
      listOf(watch),
      listOf(phone, watch).forPlatform(UiBuilderCatalogPlatform.WATCH_FACE),
    )
    val card =
      UiBuilderNewDesignCatalog(
        systemId = "wff-watchface",
        label = "Watch face",
        templates =
          listOf(UiBuilderNewDesignTemplate("analog-watch-face", "Analog watch face", "")),
        platform = UiBuilderCatalogPlatform.WATCH_FACE,
      )
    assertEquals(NewDesignDevice.WATCH, card.newDesignDevice)
  }
}
