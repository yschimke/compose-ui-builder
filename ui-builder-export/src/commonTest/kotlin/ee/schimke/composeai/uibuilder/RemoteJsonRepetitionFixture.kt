package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.*

internal fun remoteJsonRepetitionFixture(density: Int): UiBuilderDocument {
  fun cell(id: String, color: String, value: Int) =
    UiBuilderNode(
      id,
      "layout/box",
      modifiers =
        Json.parseToJsonElement(
            """[
        {"type":"size","widthDp":16,"heightDp":16},
        {"type":"background","color":{"type":"color","value":"$color"}}
      ]"""
          )
          .jsonArray,
      eventBindings =
        repetitionObject("""{"click":[{"type":"set","variable":"page","value":$value}]}"""),
    )
  val base = remoteJsonSelectionFixture(density = density)
  return base.copy(
    id = "repetition-proof",
    title = "Repeated component proof",
    environment = repetitionObject("""{"widthDp":100,"heightDp":120,"density":$density}"""),
    roots = listOf("screen"),
    nodes =
      mapOf(
        "screen" to
          UiBuilderNode(
            "screen",
            "layout/column",
            slots = mapOf("children" to listOf("loop", "indicator")),
          ),
        "loop" to
          UiBuilderNode(
            "loop",
            "layout/for-each",
            properties =
              repetitionObject(
                """{"verticalSpacingDp":{"type":"float","value":4},"data":{"type":"list","values":[
            {"type":"object","fields":{"gap":{"type":"float","value":0}}},
            {"type":"object","fields":{"gap":{"type":"float","value":8}}},
            {"type":"object","fields":{"gap":{"type":"float","value":16}}}
          ]}}"""
              ),
            slots = mapOf("template" to listOf("place")),
          ),
        "place" to
          UiBuilderNode(
            "place",
            "design/component-instance",
            modifiers =
              Json.parseToJsonElement(
                  """[{"type":"padding","startDp":4,"topDp":2,"endDp":4,"bottomDp":2}]"""
                )
                .jsonArray,
            component =
              repetitionObject(
                """{"componentKey":"pair","arguments":{"spacing":{"type":"binding","value":"gap"}}}"""
              ),
          ),
        "row" to
          UiBuilderNode(
            "row",
            "layout/row",
            properties =
              repetitionObject("""{"horizontalSpacingDp":{"type":"binding","value":"spacing"}}"""),
            slots = mapOf("children" to listOf("red", "green")),
          ),
        "red" to cell("red", "#FFFF0000", 10),
        "green" to cell("green", "#FF00FF00", 20),
        "indicator" to
          base.nodes
            .getValue("switch")
            .copy(
              id = "indicator",
              modifiers =
                Json.parseToJsonElement("""[{"type":"size","widthDp":60,"heightDp":20}]""")
                  .jsonArray,
              eventBindings = JsonObject(emptyMap()),
            ),
      ) + base.nodes.filterKeys { it != "switch" },
    components = repetitionObject("""{"pair":{"name":"Pair","root":"row"}}"""),
  )
}

private fun repetitionObject(source: String) = Json.parseToJsonElement(source).jsonObject
