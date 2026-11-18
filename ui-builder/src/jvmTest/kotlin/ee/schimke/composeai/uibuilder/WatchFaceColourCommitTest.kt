package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A face colour option chip is a real commit: it is accepted on a watch-face colour, written where
 * the exporter reads it, and a Material role is still refused there.
 */
class WatchFaceColourCommitTest {
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
          syntheticComponent("wff/part-draw", "PartDraw", "Leaf") {
            p("shape", "string")
            p("x", "integer")
            p("y", "integer")
            p("width", "integer")
            p("height", "integer")
            p("fillColor", "string")
            p("wffExtras", "string")
          },
        ),
      ),
    )
  private val reducer = UiBuilderEditorReducer(catalog)

  private val document =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """{"schema":"compose-ui-builder-document/v1-candidate","id":"face","title":"Face",
        "revision":0,"catalogPin":{"systemId":"wff-watchface","catalogRevision":"synthetic","capabilityDigest":"synthetic","nativeRuntimeId":"candidate"},"environment":{"widthDp":227,"heightDp":227,"density":2.0,"theme":"dark","dynamicColor":false,"locale":"en-US","fontScale":1,"layoutDirection":"ltr","windowPosture":"flat","browserZoomPercent":100,"fixedTime":"2024-05-16T10:08:32Z","animations":"settled","networkAccess":false,"exportDevices":["id:wearos_large_round"]},
        "stateVariables":{},"roots":["scene"],"nodes":{
          "scene":{"id":"scene","componentId":"wff/scene","slots":{"content":["dot"]}},
          "dot":{"id":"dot","componentId":"wff/part-draw","properties":{
            "shape":{"type":"string","value":"ellipse"},
            "x":{"type":"int","value":10},"y":{"type":"int","value":10},
            "width":{"type":"int","value":50},"height":{"type":"int","value":50},
            "fillColor":{"type":"color","value":"#ffff0000"}}}}}""",
    )

  @Test
  fun `a face colour option commits, is written to the XML and replaces the literal`() {
    val state = reducer.initial(document, selectedNodeId = "dot")
    val edited =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.CommitProperty("dot", "fillColor", "[CONFIGURATION.theme.0]"),
      )
    assertIs<CommandOutcome.Accepted>(edited.lastOutcome, edited.lastOutcome.toString())
    val dot = edited.document.nodes.getValue("dot")
    assertNull(dot.properties["fillColor"], "the literal it replaces is unset")
    val extras =
      Json.parseToJsonElement(
          dot.properties.getValue("wffExtras").jsonObject.getValue("value").jsonPrimitive.content,
        )
        .jsonObject
    assertEquals("[CONFIGURATION.theme.0]", extras.getValue("fillColor").jsonPrimitive.content)
    val xml =
      assertIs<WatchFaceXmlExporter.Result.Emitted>(WatchFaceXmlExporter.export(edited.document))
        .xml
    assertTrue(xml.contains("""<Fill color="[CONFIGURATION.theme.0]""""), xml)
  }

  @Test
  fun `a Material role and a malformed colour are still refused on a watch-face colour`() {
    val state = reducer.initial(document, selectedNodeId = "dot")
    for (bad in listOf("primary", "onSurface", "notacolor")) {
      val edited =
        reducer.reduce(state, UiBuilderEditorEvent.CommitProperty("dot", "fillColor", bad))
      assertIs<CommandOutcome.Rejected>(edited.lastOutcome, "$bad: ${edited.lastOutcome}")
    }
    val literal =
      reducer.reduce(state, UiBuilderEditorEvent.CommitProperty("dot", "fillColor", "#ff00ff00"))
    assertIs<CommandOutcome.Accepted>(literal.lastOutcome)
  }
}
