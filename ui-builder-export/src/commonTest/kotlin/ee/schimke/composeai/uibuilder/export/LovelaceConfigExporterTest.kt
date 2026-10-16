package ee.schimke.composeai.uibuilder.export

import kotlin.test.*
import kotlinx.serialization.json.*

class LovelaceConfigExporterTest {
  @Test
  fun `future value spellings nulls and custom fields sharing known names remain opaque`() {
    val source =
      Json.parseToJsonElement(
          """{"title":null,"views":[{"type":"sections","sections":[{"cards":[
      {"type":"custom:test","name":{"future":true},"entity":null,"min":"template","grid_options":null},
      {"type":"tile","grid_options":{"columns":"future-size","rows":0}}
    ]}]}]}"""
        )
        .jsonObject
    assertEquals(
      source,
      exported(LovelaceConfigExporter.import(source, "future", JsonObject(emptyMap()))),
    )
  }

  private val config =
    Json.parseToJsonElement(
        """
        {
          "title":"Home", "background":{"image":"/local/home.jpg"}, "x-host":{"version":7},
          "views":[
            {"type":"sections","title":"Downstairs","path":"home","max_columns":3,
             "badges":[{"type":"entity","entity":"person.ada"}],"visible":[{"user":"fixture-user"}],
             "sections":[
               {"type":"grid","title":"Living room","column_span":2,"x-section":[1,null,true],
                "cards":[
                  {"type":"tile","entity":"light.sofa","name":"Sofa","tap_action":{"action":"toggle"},
                   "features":[{"type":"light-brightness"}],"grid_options":{"columns":6,"rows":2,"x-grid":false}},
                  {"type":"custom:mushroom-template-card","entity":"sensor.temperature","primary":"{{ states(entity) }}",
                   "card_mod":{"style":"ha-card { color: red; }"},"nested":{"type":"state","value":null}}
                ]},
               {"cards":[{"type":"markdown","content":"## Welcome","text_only":true}]}
             ]},
            {"title":"Legacy","cards":[{"type":"custom:apexcharts-card","series":[{"entity":"sensor.power"}]}]},
            {"type":"panel","title":"Map","cards":[{"type":"map","entities":["person.ada"]}]}
          ]
        }
        """
          .trimIndent()
      )
      .jsonObject

  private fun imported() =
    LovelaceConfigExporter.import(
      config,
      "fixture",
      buildJsonObject { put("systemId", "lovelace") },
    )

  private fun exported(document: UiBuilderDocument): JsonObject {
    val result =
      assertIs<LovelaceConfigExporter.Result.Emitted>(LovelaceConfigExporter.export(document))
    assertEquals(result.config, Json.parseToJsonElement(result.source))
    return result.config
  }

  @Test
  fun `multiple views and every unknown field survive import export and persisted reload`() {
    val document = imported()
    assertEquals(3, document.nodes.getValue("dashboard").slots.getValue("views").size)
    assertEquals("lovelace/card/custom", document.nodes.getValue("dashboard-0-0-1").componentId)
    val persisted = Json.encodeToString(UiBuilderDocument.serializer(), document)
    assertEquals(config, exported(Json.decodeFromString(UiBuilderDocument.serializer(), persisted)))
  }

  @Test
  fun `property removal does not resurrect imported values and resize preserves grid extensions`() {
    val document = imported()
    val id = "dashboard-0-0-0"
    val card = document.nodes.getValue(id)
    val updated =
      card.copy(
        properties =
          JsonObject(
            card.properties - "name" +
              ("grid_columns" to
                buildJsonObject {
                  put("type", "int")
                  put("value", 12)
                })
          )
      )
    val result = exported(document.copy(nodes = document.nodes + (id to updated)))
    val actual =
      result["views"]!!
        .jsonArray[0]
        .jsonObject["sections"]!!
        .jsonArray[0]
        .jsonObject["cards"]!!
        .jsonArray[0]
        .jsonObject
    val original =
      config["views"]!!
        .jsonArray[0]
        .jsonObject["sections"]!!
        .jsonArray[0]
        .jsonObject["cards"]!!
        .jsonArray[0]
        .jsonObject
    assertEquals(
      JsonObject(
        original - "name" +
          ("grid_options" to
            JsonObject(original["grid_options"]!!.jsonObject + ("columns" to JsonPrimitive(12))))
      ),
      actual,
    )
  }

  @Test
  fun `move between sections and delete update arrays without stale raw children`() {
    val doc = imported()
    val a = doc.nodes.getValue("dashboard-0-0")
    val b = doc.nodes.getValue("dashboard-0-1")
    val changed =
      doc.copy(
        nodes =
          doc.nodes +
            mapOf(
              a.id to a.copy(slots = mapOf("cards" to emptyList())),
              b.id to
                b.copy(slots = mapOf("cards" to listOf("dashboard-0-0-1", "dashboard-0-1-0"))),
            )
      )
    val sections = exported(changed)["views"]!!.jsonArray[0].jsonObject["sections"]!!.jsonArray
    assertEquals(0, sections[0].jsonObject["cards"]!!.jsonArray.size)
    assertEquals(
      JsonPrimitive("custom:mushroom-template-card"),
      sections[1].jsonObject["cards"]!!.jsonArray[0].jsonObject["type"],
    )
    assertEquals(2, sections[1].jsonObject["cards"]!!.jsonArray.size)
  }

  @Test
  fun `invalid structure shared children cycles and invalid grid are refused`() {
    val doc = imported()
    fun refuses(node: UiBuilderNode) {
      assertIs<LovelaceConfigExporter.Result.Refused>(
        LovelaceConfigExporter.export(doc.copy(nodes = doc.nodes + (node.id to node)))
      )
    }
    val root = doc.nodes.getValue("dashboard")
    refuses(root.copy(slots = mapOf("views" to listOf("missing"))))
    refuses(root.copy(slots = mapOf("views" to listOf("dashboard"))))
    refuses(root.copy(slots = mapOf("views" to listOf("dashboard-0", "dashboard-0"))))
    refuses(root.copy(slots = mapOf("cards" to listOf("dashboard-0-0-0"))))
    val card = doc.nodes.getValue("dashboard-0-0-0")
    refuses(
      card.copy(
        properties =
          JsonObject(
            card.properties +
              ("grid_columns" to
                buildJsonObject {
                  put("type", "int")
                  put("value", 13)
                })
          )
      )
    )
    refuses(
      card.copy(
        properties =
          JsonObject(
            card.properties +
              ("entity" to
                buildJsonObject {
                  put("type", "state")
                  put("variable", "credential")
                })
          )
      )
    )
  }

  @Test
  fun `empty sections and dashboards without a views array remain exact`() {
    for (source in
      listOf("{}", "{\"views\":[]}", "{\"views\":[{\"type\":\"sections\",\"sections\":[]}]}")) {
      val obj = Json.parseToJsonElement(source).jsonObject
      assertEquals(
        obj,
        exported(LovelaceConfigExporter.import(obj, "empty", JsonObject(emptyMap()))),
      )
    }
  }

  @Test
  fun `unknown scalar representations remain opaque rather than acquiring invalid catalog properties`() {
    val config =
      Json.parseToJsonElement(
          """{"views":[{"type":"sections","max_columns":"future","sections":[{"cards":[{"type":"tile","min":"5","show_state":"true","future":9007199254740993},{"type":"gauge","min":9007199254740993,"max":0.12345678901234567890}]}]}]}"""
        )
        .jsonObject
    val document = LovelaceConfigExporter.import(config, "future", JsonObject(emptyMap()))
    val card = document.nodes.getValue("dashboard-0-0-0")
    assertFalse(card.properties.containsKey("min"))
    assertFalse(card.properties.containsKey("show_state"))
    assertEquals(config, exported(document))
  }

  @Test
  fun `strategy views stay opaque and new custom cards need an actual config type`() {
    val source =
      Json.parseToJsonElement(
          """{"views":[{"type":"sections","strategy":{"type":"custom:strategy"},"sections":[{"future":true}]}]}"""
        )
        .jsonObject
    val document = LovelaceConfigExporter.import(source, "strategy", JsonObject(emptyMap()))
    assertEquals(
      LovelaceConfigExporter.OPAQUE_VIEW,
      document.nodes.getValue("dashboard-0").componentId,
    )
    assertEquals(source, exported(document))
    val original = imported()
    val card = original.nodes.getValue("dashboard-0-0-1")
    val withoutType =
      card.copy(properties = JsonObject(card.properties - LovelaceConfigExporter.RAW_CONFIG))
    assertIs<LovelaceConfigExporter.Result.Refused>(
      LovelaceConfigExporter.exportCard(
        original.copy(nodes = original.nodes + (card.id to withoutType)),
        card.id,
      )
    )
  }
}
