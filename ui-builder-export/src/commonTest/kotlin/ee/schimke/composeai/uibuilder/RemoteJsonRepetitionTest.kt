package ee.schimke.composeai.uibuilder

import kotlin.test.*
import kotlinx.serialization.json.*

class RemoteJsonRepetitionTest {
  private fun obj(source: String) = Json.parseToJsonElement(source).jsonObject

  private fun emitted(document: UiBuilderDocument) =
    assertIs<RemoteDocumentJsonExporter.Result.Emitted>(RemoteDocumentJsonExporter.export(document))

  private fun refused(document: UiBuilderDocument, reason: String) {
    val result =
      assertIs<RemoteDocumentJsonExporter.Result.Refused>(
        RemoteDocumentJsonExporter.export(document)
      )
    assertTrue(result.reasons.any { reason in it }, result.reasons.joinToString())
  }

  private fun edit(
    document: UiBuilderDocument,
    id: String,
    transform: (UiBuilderNode) -> UiBuilderNode,
  ) = document.copy(nodes = document.nodes + (id to transform(document.nodes.getValue(id))))

  private fun rows(document: UiBuilderDocument, value: JsonElement) =
    edit(document, "loop") { it.copy(properties = JsonObject(it.properties + ("data" to value))) }

  @Test
  fun `empty rows retain the loop wrapper and do not draw a template`() {
    val source =
      emitted(rows(remoteJsonRepetitionFixture(1), obj("""{"type":"list","values":[]}"""))).source
    val loop =
      Json.parseToJsonElement(source)
        .jsonObject
        .getValue("root")
        .jsonArray
        .last()
        .jsonObject
        .getValue("children")
        .jsonArray
        .first()
        .jsonObject
    assertEquals(JsonPrimitive("column"), loop["type"])
    assertNull(loop["children"])
    assertContains(loop.getValue("modifiers").toString(), "spacedBy")
  }

  @Test
  fun `each repeated selection allocates distinct compiler expressions`() {
    val base = remoteJsonRepetitionFixture(1)
    val nested =
      edit(base, "row") { it.copy(slots = mapOf("children" to listOf("indicator"))) }
        .let {
          edit(it, "screen") { node -> node.copy(slots = mapOf("children" to listOf("loop"))) }
        }
    val source = emitted(nested).source
    val names = Regex("\"name\":\"([^\"]+)\"").findAll(source).map { it.groupValues[1] }.toList()
    assertTrue(names.isNotEmpty())
    assertEquals(names.size, names.toSet().size)
    assertEquals(3, Regex("\"type\":\"stateLayout\"").findAll(source).count())
  }

  @Test
  fun `component argument scope does not fall back to outer rows`() {
    val missing =
      edit(remoteJsonRepetitionFixture(1), "place") {
        it.copy(component = obj("""{"componentKey":"pair","arguments":{}}"""))
      }
    refused(
      missing,
      "nodes.row.properties.horizontalSpacingDp: missing or invalid argument spacing",
    )
    val malformed =
      edit(remoteJsonRepetitionFixture(1), "place") {
        it.copy(
          component =
            obj("""{"componentKey":"pair","arguments":{"spacing":{"type":"binding","value":{}}}}""")
        )
      }
    refused(malformed, "nodes.place.component.arguments.spacing")
  }

  @Test
  fun `nested loop rows shadow outer bindings`() {
    val base = remoteJsonRepetitionFixture(1)
    val outer =
      base.nodes.getValue("loop").copy(id = "outer", slots = mapOf("template" to listOf("loop")))
    val nested = base.copy(roots = listOf("outer"), nodes = base.nodes + ("outer" to outer))
    val source = emitted(nested).source
    assertEquals(9, Regex("\"type\":\"row\"").findAll(source).count())
    assertEquals(3, Regex("\"spacedBy\":16.0").findAll(source).count())
  }

  @Test
  fun `row values must match the property they bind`() {
    refused(
      rows(
        remoteJsonRepetitionFixture(1),
        obj(
          """{"type":"list","values":[{"type":"object","fields":{"gap":{"type":"string","value":"wide"}}}]}"""
        ),
      ),
      "nodes.row.properties.horizontalSpacingDp: expected a numeric literal",
    )
  }

  @Test
  fun `malformed rows and dynamic data are located refusals`() {
    for (value in
      listOf(
        """{"type":"state","variable":"rows"}""",
        """{"type":"list","values":{},"extra":1}""",
        """{"type":"list","values":[{"type":"object","fields":[],"ignored":1}]}""",
      )) refused(rows(remoteJsonRepetitionFixture(1), obj(value)), "nodes.loop.properties.data")
  }

  @Test
  fun `placements retain supported actions but refuse dropped fields`() {
    val base = remoteJsonRepetitionFixture(1)
    val clickable =
      edit(base, "place") { it.copy(eventBindings = base.nodes.getValue("red").eventBindings) }
    assertEquals(9, Regex("\"onClick\"").findAll(emitted(clickable).source).count())
    refused(
      edit(base, "place") { it.copy(properties = obj("""{"ignored":{"type":"int","value":1}}""")) },
      "nodes.place.component",
    )
    refused(
      edit(base, "place") {
        it.copy(component = obj("""{"componentKey":"pair","arguments":[],"callback":"lost"}"""))
      },
      "placement field",
    )
    refused(
      edit(base, "loop") { it.copy(slots = it.slots + ("children" to listOf("red"))) },
      "nodes.loop.slots",
    )
    refused(
      edit(base, "loop") {
        it.copy(properties = JsonObject(it.properties + ("ignored" to JsonPrimitive(1))))
      },
      "nodes.loop.properties.ignored",
    )
  }

  @Test
  fun `unimplemented modifier bindings cannot silently render as defaults`() {
    val document =
      edit(remoteJsonRepetitionFixture(1), "red") {
        it.copy(
          modifiers =
            Json.parseToJsonElement(
                """[{"type":"width","widthDp":{"type":"binding","value":"gap"}}]"""
              )
              .jsonArray
        )
      }
    refused(document, "nodes.red.modifiers[0]")
  }

  @Test
  fun `cycles and missing references are refused even under empty loops`() {
    val base = rows(remoteJsonRepetitionFixture(1), obj("""{"type":"list","values":[]}"""))
    refused(base.copy(components = obj("""{"pair":{"name":"Pair","root":"place"}}""")), "cyclic")
    refused(
      base.copy(components = obj("""{"pair":{"name":"Pair","root":"absent"}}""")),
      "nodes.absent: missing node",
    )
  }

  @Test
  fun `nested multiplication is bounded before producing an artifact`() {
    val base = remoteJsonRepetitionFixture(1)
    val many =
      obj(
        """{"type":"list","values":[${List(101) { """{"type":"object","fields":{}}""" }.joinToString(",")}]}"""
      )
    val inner =
      UiBuilderNode(
        "inner",
        "layout/for-each",
        properties = JsonObject(mapOf("data" to many)),
        slots = mapOf("template" to listOf("red")),
      )
    val outer = inner.copy(id = "outer", slots = mapOf("template" to listOf("inner")))
    refused(
      base.copy(
        roots = listOf("outer"),
        nodes = base.nodes + mapOf("outer" to outer, "inner" to inner),
      ),
      "expanded document exceeds 10000 nodes",
    )
  }
}
