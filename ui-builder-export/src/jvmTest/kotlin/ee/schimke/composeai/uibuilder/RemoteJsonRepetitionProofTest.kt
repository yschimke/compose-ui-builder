package ee.schimke.composeai.uibuilder

import java.io.File
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Compare production emission with the independently proven test-only expansion. */
class RemoteJsonRepetitionProofTest {
  @Test
  fun `authored rows and component arguments expand to independently clickable layouts`() {
    val output = File("build/remote-json-repetition").apply { mkdirs() }
    for (density in listOf(1, 2)) {
      val document = remoteJsonRepetitionFixture(density)
      val expanded = expand(document)
      val result =
        assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
          RemoteDocumentJsonExporter.export(expanded)
        )
      // Production emission must match the independently proven test-only expansion exactly.
      assertEquals(result, RemoteDocumentJsonExporter.export(document))
      assertEquals(3, expanded.nodes.values.count { it.componentId == "layout/row" })
      assertEquals(
        listOf(0, 8, 16),
        expanded.nodes.values
          .filter { it.componentId == "layout/row" }
          .map {
            it.properties
              .getValue("horizontalSpacingDp")
              .jsonObject
              .getValue("value")
              .jsonPrimitive
              .int
          },
      )
      File(output, "rows-$density.json").writeText(result.source)
      File(output, "rows-$density.document.json").writeText(Json.encodeToString(document))
      File(output, "rows-$density.expanded.json").writeText(Json.encodeToString(expanded))
    }
  }

  @Test
  fun `prototype refuses missing arguments and recursion`() {
    val document = remoteJsonRepetitionFixture(1)
    val row = document.nodes.getValue("row")
    val missing =
      document.copy(
        nodes =
          document.nodes +
            ("row" to
              row.copy(
                properties = obj("""{"horizontalSpacingDp":{"type":"binding","value":"missing"}}""")
              ))
      )
    assertContains(
      assertFailsWith<IllegalArgumentException> { expand(missing) }.message!!,
      "missing",
    )
    val cyclic = document.copy(components = obj("""{"pair":{"name":"Pair","root":"place"}}"""))
    assertContains(assertFailsWith<IllegalArgumentException> { expand(cyclic) }.message!!, "cycle")
  }

  /** A bounded, deliberately test-only elaboration; no change to the authored document or API. */
  private fun expand(document: UiBuilderDocument): UiBuilderDocument {
    val expanded = linkedMapOf<String, UiBuilderNode>()
    var serial = 0
    fun resolve(value: JsonElement, arguments: JsonObject): JsonElement =
      when (value) {
        is JsonObject ->
          if (value["type"] == JsonPrimitive("binding")) {
            val key = value.getValue("value").jsonPrimitive.content
            requireNotNull(arguments[key]) { "missing argument $key" }
          } else JsonObject(value.mapValues { resolve(it.value, arguments) })
        is JsonArray -> JsonArray(value.map { resolve(it, arguments) })
        else -> value
      }
    fun visit(id: String, arguments: JsonObject, ancestors: Set<String>): String {
      require(id !in ancestors) { "component or child cycle at $id" }
      require(serial < 1000) { "prototype expansion budget exceeded" }
      val node = document.nodes.getValue(id)
      val nextId = "expanded${serial++}"
      val path = ancestors + id
      val properties = resolve(node.properties, arguments).jsonObject
      val placement = node.component
      val result =
        when {
          placement != null -> {
            require(node.slots.isEmpty() && properties.isEmpty())
            val body =
              document.components
                .getValue(placement.getValue("componentKey").jsonPrimitive.content)
                .jsonObject
                .getValue("root")
                .jsonPrimitive
                .content
            val supplied =
              resolve(placement["arguments"] ?: JsonObject(emptyMap()), arguments).jsonObject
            node.copy(
              componentId = "layout/box",
              component = null,
              slots = mapOf("children" to listOf(visit(body, supplied, path))),
            )
          }
          node.componentId == "layout/for-each" -> {
            require(
              node.slots.keys == setOf("template") && node.slots.getValue("template").size == 1
            )
            val data = properties.getValue("data").jsonObject
            require(data["type"] == JsonPrimitive("list"))
            val children =
              data.getValue("values").jsonArray.map {
                val row = it.jsonObject
                require(row["type"] == JsonPrimitive("object"))
                visit(
                  node.slots.getValue("template").single(),
                  row.getValue("fields").jsonObject,
                  path,
                )
              }
            node.copy(
              componentId = "layout/column",
              properties = JsonObject(properties - "data"),
              slots = mapOf("children" to children),
            )
          }
          else -> {
            val children =
              node.slots.mapValues { (_, ids) -> ids.associateWith { visit(it, arguments, path) } }
            val selection = node.stateSelection()
            val mapped = children.values.flatMap { it.entries }.associate { it.key to it.value }
            val remappedProperties =
              if (selection == null) properties
              else
                JsonObject(
                  properties +
                    (SHOW_BY_STATE to
                      selection
                        .copy(
                          cases = selection.cases.mapKeys { mapped.getValue(it.key) },
                          fallback = selection.fallback?.let { mapped.getValue(it) },
                        )
                        .encode())
                )
            node.copy(
              properties = remappedProperties,
              slots = children.mapValues { it.value.values.toList() },
            )
          }
        }
      expanded[nextId] = result.copy(id = nextId)
      return nextId
    }
    val roots = document.roots.map { visit(it, JsonObject(emptyMap()), emptySet()) }
    return document.copy(roots = roots, nodes = expanded, components = JsonObject(emptyMap()))
  }

  private fun obj(source: String) = Json.parseToJsonElement(source).jsonObject
}
