package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.*
import ee.schimke.composeai.uibuilder.export.*
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Editor behavior against a small host-supplied catalog, not a copy of harc's published palette.
 */
class LovelaceEditorTest {
  private fun component(id: String, trait: String, slot: String? = null, accepts: String = "") =
    syntheticComponent(
      "lovelace/$id",
      id,
      if (id == "dashboard") "Scaffold" else if (slot != null) "Container" else "Leaf",
      traits = trait,
      slots =
        if (slot == null) emptyList()
        else listOf(syntheticSlot(slot, max = null, traits = accepts)),
    ) {
      p("rawConfig", "string")
      if (id != "opaque-view") p("title", "string")
      if (id.startsWith("card/")) {
        p("entity", "string")
        p("name", "string")
        p("grid_columns", "integer", "string", allowed = "6 12 full")
        p("grid_rows", "integer", "string")
      }
    }

  private fun numericGridChoices(component: JsonObject): JsonObject =
    JsonObject(
      component +
        ("properties" to
          JsonArray(
            component.getValue("properties").jsonArray.map { property ->
              val p = property.jsonObject
              if (p["name"] == JsonPrimitive("grid_columns"))
                JsonObject(
                  p +
                    ("allowedValues" to
                      JsonArray(listOf(JsonPrimitive(6), JsonPrimitive(12), JsonPrimitive("full"))))
                )
              else p
            }
          ))
    )

  private val catalog =
    CapabilityCatalogParser.parse(
      syntheticCatalogJson(
        "lovelace",
        buildJsonObject { put("platform", "lovelace") },
        listOf(
          component("dashboard", "LovelaceDashboard", "views", "LovelaceView"),
          component("view", "LovelaceView", "sections", "LovelaceSection"),
          component("opaque-view", "LovelaceView"),
          component("section", "LovelaceSection", "cards", "LovelaceCard"),
          numericGridChoices(component("card/tile", "LovelaceCard")),
          numericGridChoices(component("card/custom", "LovelaceCard")),
        ),
      )
    )
  private val reducer = UiBuilderEditorReducer(catalog)
  private val source =
    Json.parseToJsonElement(
        """{
    "title":"Home","future":{"preserve":true},"views":[
      {"type":"sections","title":"Home","sections":[
        {"title":"Lights","cards":[{"type":"tile","entity":"light.sofa","grid_options":{"columns":6,"rows":2}},
          {"type":"custom:unknown","opaque":{"nested":[null,1],"large":9007199254740993}}]},
        {"title":"Other","cards":[]}]},
      {"type":"panel","cards":[{"type":"map","entities":["person.ada"]}]}]}
  """
      )
      .jsonObject

  private fun initial() =
    reducer.initial(
      LovelaceConfigExporter.import(
        source,
        "fixture",
        buildJsonObject {
          put("systemId", "lovelace")
          put("catalogRevision", catalog.benchmark.catalogRevision)
          put("capabilityDigest", "candidate")
          put("nativeRuntimeId", catalog.benchmark.nativeRuntimeId)
        },
      )
    )

  private fun exported(state: UiBuilderEditorState) =
    assertIs<LovelaceConfigExporter.Result.Emitted>(LovelaceConfigExporter.export(state.document))
      .config

  @Test
  fun `cards move between sections and undo restores complete unknown configs`() {
    val initial = initial()
    val moved =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.MoveNodeInto("dashboard-0-0-1", ParentSlot("dashboard-0-1", "cards")),
      )
    assertEquals(
      listOf("dashboard-0-0-1"),
      moved.document.nodes.getValue("dashboard-0-1").slots["cards"],
    )
    assertEquals(source, exported(reducer.reduce(moved, UiBuilderEditorEvent.Undo)))
  }

  @Test
  fun `catalog constraints refuse cards in views and views in card slots`() {
    val initial = initial()
    for ((node, parent) in
      listOf(
        "dashboard-0-0-0" to ParentSlot("dashboard", "views"),
        "dashboard-1" to ParentSlot("dashboard-0-1", "cards"),
      )) {
      assertEquals(
        initial.document,
        reducer.reduce(initial, UiBuilderEditorEvent.MoveNodeInto(node, parent)).document,
      )
    }
  }

  @Test
  fun `grid edits export and undo while custom config and other views remain exact`() {
    val initial = initial()
    val resized =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.CommitProperty("dashboard-0-0-0", "grid_columns", "12"),
      )
    val config = exported(resized)
    val card =
      config["views"]!!
        .jsonArray[0]
        .jsonObject["sections"]!!
        .jsonArray[0]
        .jsonObject["cards"]!!
        .jsonArray[0]
        .jsonObject
    assertEquals(JsonPrimitive(12), card["grid_options"]!!.jsonObject["columns"])
    assertEquals(source["views"]!!.jsonArray[1], config["views"]!!.jsonArray[1])
    assertEquals(source, exported(reducer.reduce(resized, UiBuilderEditorEvent.Undo)))
  }

  @Test
  fun `delete card removes it from config and undo recovers raw config`() {
    val initial = reducer.initial(initial().document, "dashboard-0-0-1")
    val deleted = reducer.reduce(initial, UiBuilderEditorEvent.DeleteSelected)
    assertFalse(deleted.document.nodes.containsKey("dashboard-0-0-1"))
    assertEquals(
      1,
      exported(deleted)["views"]!!
        .jsonArray[0]
        .jsonObject["sections"]!!
        .jsonArray[0]
        .jsonObject["cards"]!!
        .jsonArray
        .size,
    )
    assertEquals(source, exported(reducer.reduce(deleted, UiBuilderEditorEvent.Undo)))
  }

  @Test
  fun `code pane emits dashboard JSON for host owned designs`() {
    val code = assertIs<EditorGeneratedCode.Source>(reducer.generatedCode(initial().document))
    assertEquals(source, Json.parseToJsonElement(code.kotlin))
  }

  @Test
  fun `imported dashboards persist through the production uid codec with unknown fields intact`() {
    val document = initial().document
    val reopened = UidDesignFiles.decode(UidDesignFiles.encode(document))
    assertEquals(
      source,
      assertIs<LovelaceConfigExporter.Result.Emitted>(LovelaceConfigExporter.export(reopened))
        .config,
    )
  }

  @Test
  fun `sections and opaque views delete as complete subtrees and undo restores them`() {
    for ((id, remaining) in listOf("dashboard-0-0" to 1, "dashboard-1" to 1)) {
      val state = reducer.initial(initial().document, id)
      val deleted = reducer.reduce(state, UiBuilderEditorEvent.DeleteSelected)
      val output = exported(deleted)
      if (id == "dashboard-1") assertEquals(remaining, output["views"]!!.jsonArray.size)
      else
        assertEquals(
          remaining,
          output["views"]!!.jsonArray[0].jsonObject["sections"]!!.jsonArray.size,
        )
      assertEquals(source, exported(reducer.reduce(deleted, UiBuilderEditorEvent.Undo)))
    }
  }

  @Test
  fun `Lovelace sections remain data when generic state and component actions are requested`() {
    val state = reducer.initial(initial().document, "dashboard-0-0")
    val madeComponent = reducer.reduce(state, UiBuilderEditorEvent.MakeComponent("Heating"))
    assertEquals(state.document, madeComponent.document)
    assertNotNull(reducer.makeComponentRefusal(state))
    val addedState =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.SetStateVariable(
          "title",
          buildJsonObject {
            put("type", "string")
            put("initial", "Local state")
          },
        ),
      )
    assertEquals(state.document, addedState.document)
    assertEquals(source, exported(addedState))
  }
}
