package ee.schimke.composeai.uibuilder

import androidx.compose.ui.graphics.toPixelMap
import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.canvas.WffShapeDrawing
import ee.schimke.composeai.uibuilder.canvas.drawWffShape
import ee.schimke.composeai.uibuilder.canvas.forPlatform
import ee.schimke.composeai.uibuilder.canvas.shapeBox
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.NewDesignDevice
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignTemplate
import ee.schimke.composeai.uibuilder.editor.newDesignDevice
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
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
          syntheticComponent("wff/part-text", "PartText", "Leaf") {
            p("text", "string")
            p("parameters", "string")
          },
        ),
      ),
    )
  private val reducer = UiBuilderEditorReducer(catalog)

  private fun document(vararg nodes: String) =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """{"schema":"compose-ui-builder-document/v1-candidate","id":"face","title":"Face",
        "revision":0,"catalogPin":{"systemId":"wff-watchface","catalogRevision":"synthetic","capabilityDigest":"synthetic","nativeRuntimeId":"candidate"},"environment":{"widthDp":227,"heightDp":227,"density":2.0,"theme":"dark","dynamicColor":false,"locale":"en-US","fontScale":1,"layoutDirection":"ltr","windowPosture":"flat","browserZoomPercent":100,"fixedTime":"2024-05-16T10:08:32Z","animations":"settled","networkAccess":false,"exportDevices":["id:wearos_large_round"]},
        "stateVariables":{},"roots":["scene"],"nodes":{
          "scene":{"id":"scene","componentId":"wff/scene","slots":{"content":[${nodes.joinToString { "\"$it\"" }}]}},
          "hello":{"id":"hello","componentId":"wff/part-text",
            "properties":{"text":{"type":"string","value":"Hi %s"},"parameters":{"type":"string","value":"[DAY_OF_WEEK_S]"}}}}}""",
    )

  @Test
  fun `the catalog is a watch face platform and the code pane shows WFF XML, not Kotlin`() {
    assertEquals(UiBuilderCatalogPlatform.WATCH_FACE, catalog.platform)
    val source = assertIs<EditorGeneratedCode.Source>(reducer.generatedCode(document("hello")))
    assertTrue(source.kotlin.contains("<WatchFace width=\"450\" height=\"450\""), source.kotlin)
    assertTrue(
      source.kotlin.contains(
        "<Template>Hi %s<Parameter expression=\"[DAY_OF_WEEK_S]\"/></Template>",
      ),
      source.kotlin,
    )
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
  fun `a valid watch face has no issues, a refused one reports the exporter's reasons`() {
    assertEquals(emptyList(), reducer.problems(document("hello")))
    val foreign =
      document("hello").let {
        it.copy(
          nodes = it.nodes + ("hello" to it.nodes.getValue("hello").copy(componentId = "m3/text")),
        )
      }
    val problems = reducer.problems(foreign)
    assertTrue(
      problems.any { it.message.startsWith("nodes.hello (`m3/text`)") },
      problems.toString(),
    )
    assertTrue(problems.none { "component record" in it.message }, problems.toString())
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

  @Test
  fun `a shape draws inside its own box, two rectangles apart`() {
    fun rect(id: String, x: Int, y: Int) =
      UiBuilderNode(
        id,
        "wff/shape",
        kotlinx.serialization.json.JsonObject(
          mapOf(
            "shape" to prop("string", kotlinx.serialization.json.JsonPrimitive("rectangle")),
            "x" to prop("int", kotlinx.serialization.json.JsonPrimitive(x)),
            "y" to prop("int", kotlinx.serialization.json.JsonPrimitive(y)),
            "width" to prop("int", kotlinx.serialization.json.JsonPrimitive(20)),
            "height" to prop("int", kotlinx.serialization.json.JsonPrimitive(30)),
          ),
        ),
      )
    assertEquals(listOf(5f, 6f, 20f, 30f), shapeBox(rect("a", 5, 6)))
    assertEquals(listOf(100f, 120f, 20f, 30f), shapeBox(rect("b", 100, 120)))
    assertEquals(null, shapeBox(UiBuilderNode("c", "wff/shape")))
  }

  private fun prop(type: String, value: kotlinx.serialization.json.JsonPrimitive) =
    buildJsonObject {
      put("type", type)
      put("value", value)
    }

  @Test
  fun `two rectangles in one part are drawn apart, each in its own box`() {
    val image = androidx.compose.ui.graphics.ImageBitmap(100, 100)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
      androidx.compose.ui.unit.Density(1f),
      androidx.compose.ui.unit.LayoutDirection.Ltr,
      androidx.compose.ui.graphics.Canvas(image),
      androidx.compose.ui.geometry.Size(100f, 100f),
    ) {
      fun rect(x: Float, y: Float, color: androidx.compose.ui.graphics.Color) =
        WffShapeDrawing(
          "rectangle",
          fill = color,
          stroke = androidx.compose.ui.graphics.Color.Unspecified,
          strokeWidth = 1f,
          ends = listOf(0f, 0f, 0f, 0f),
          box = listOf(x, y, 20f, 20f),
        )
      drawWffShape(rect(10f, 10f, androidx.compose.ui.graphics.Color.Red), 1f)
      drawWffShape(rect(60f, 60f, androidx.compose.ui.graphics.Color.Blue), 1f)
    }
    val pixels = image.toPixelMap()
    assertEquals(androidx.compose.ui.graphics.Color.Red, pixels[15, 15])
    assertEquals(androidx.compose.ui.graphics.Color.Blue, pixels[65, 65])
    // Outside both boxes nothing is drawn; the first does not spill into the second's place.
    assertEquals(0f, pixels[40, 40].alpha)
    assertEquals(0f, pixels[15, 65].alpha)
    assertEquals(0f, pixels[65, 15].alpha)
  }
}
