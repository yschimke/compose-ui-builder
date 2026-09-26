package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class A2uiDocumentExporterTest {

  private fun wrapped(type: String, value: String) = buildJsonObject {
    put("type", type)
    put("value", value)
  }

  private fun document(
    nodes: List<UiBuilderNode>,
    roots: List<String> = listOf(nodes.first().id),
    state: JsonObject = JsonObject(emptyMap()),
  ) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "booking",
      title = "Booking",
      revision = 1,
      catalogPin = buildJsonObject { put("catalogId", "a2ui-catalog") },
      environment = JsonObject(emptyMap()),
      stateVariables = state,
      roots = roots,
      nodes = nodes.associateBy { it.id },
    )

  private val card =
    listOf(
      UiBuilderNode("card", "a2ui/Card", slots = mapOf("child" to listOf("column"))),
      UiBuilderNode(
        "column",
        "a2ui/Column",
        properties = buildJsonObject { put("align", wrapped("enum", "stretch")) },
        slots = mapOf("children" to listOf("title", "name", "book")),
      ),
      UiBuilderNode(
        "title",
        "a2ui/Text",
        properties =
          buildJsonObject {
            put("text", wrapped("string", "Weekend in Lisbon"))
            put("variant", wrapped("enum", "h3"))
          },
      ),
      UiBuilderNode(
        "name",
        "a2ui/TextField",
        properties =
          buildJsonObject {
            put("label", wrapped("string", "Full name"))
            put(
              "value",
              buildJsonObject {
                put("type", "state")
                put("variable", "fullName")
              },
            )
          },
      ),
      UiBuilderNode(
        "book",
        "a2ui/Button",
        properties =
          buildJsonObject {
            put("variant", wrapped("enum", "primary"))
            put(
              "action",
              buildJsonObject {
                put("type", "object")
                put(
                  "value",
                  buildJsonObject { put("event", buildJsonObject { put("name", "book") }) },
                )
              },
            )
          },
        slots = mapOf("child" to listOf("book_label")),
      ),
      UiBuilderNode(
        "book_label",
        "a2ui/Text",
        properties = buildJsonObject { put("text", wrapped("string", "Book")) },
      ),
    )

  private val state = buildJsonObject {
    put(
      "fullName",
      buildJsonObject {
        put("type", "text")
        put("initialValue", "Ada Lovelace")
      },
    )
  }

  @Test
  fun `a design lowers to createSurface, updateDataModel and updateComponents`() {
    val emitted =
      assertIs<A2uiDocumentExporter.Result.Emitted>(
        A2uiDocumentExporter.export(document(card, state = state))
      )
    assertEquals(
      listOf("createSurface", "updateDataModel", "updateComponents"),
      emitted.messages.map { message -> message.keys.first { it != "version" } },
    )
    emitted.messages.forEach { assertEquals(JsonPrimitive("v0.9"), it["version"]) }
    assertEquals(
      JsonPrimitive(A2uiDocumentExporter.BASIC_CATALOG_ID),
      emitted.messages[0]["createSurface"]!!.jsonObject["catalogId"],
    )
    assertEquals(
      buildJsonObject { put("fullName", "Ada Lovelace") },
      emitted.messages[1]["updateDataModel"]!!.jsonObject["value"],
    )
  }

  @Test
  fun `components are flat, the root is renamed and references follow`() {
    val emitted =
      assertIs<A2uiDocumentExporter.Result.Emitted>(
        A2uiDocumentExporter.export(document(card, state = state))
      )
    val components =
      emitted.messages.last()["updateComponents"]!!.jsonObject["components"] as JsonArray
    val byId = components.associateBy {
      (it as JsonObject)["id"]!!.let { id -> (id as JsonPrimitive).content }
    }
    assertEquals(
      listOf("root", "column", "title", "name", "book", "book_label"),
      byId.keys.toList(),
    )
    assertEquals(
      Json.parseToJsonElement("""{"id":"root","component":"Card","child":"column"}"""),
      byId.getValue("root"),
    )
    assertEquals(
      Json.parseToJsonElement(
        """{"id":"column","component":"Column","align":"stretch","children":["title","name","book"]}"""
      ),
      byId.getValue("column"),
    )
    assertEquals(
      Json.parseToJsonElement(
        """{"id":"name","component":"TextField","label":"Full name","value":{"path":"/fullName"}}"""
      ),
      byId.getValue("name"),
    )
    assertEquals(
      Json.parseToJsonElement(
        """{"id":"book","component":"Button","variant":"primary","action":{"event":{"name":"book"}},"child":"book_label"}"""
      ),
      byId.getValue("book"),
    )
  }

  @Test
  fun `the source is JSON Lines, one message per line`() {
    val emitted =
      assertIs<A2uiDocumentExporter.Result.Emitted>(
        A2uiDocumentExporter.export(document(card, state = state))
      )
    val lines = emitted.source.trimEnd().lines()
    assertEquals(3, lines.size)
    lines.forEach { Json.parseToJsonElement(it).jsonObject }
  }

  @Test
  fun `what A2UI cannot express is refused with a reason`() {
    val bad =
      listOf(
        UiBuilderNode("col", "a2ui/Column", slots = mapOf("children" to listOf("box", "label"))),
        UiBuilderNode("box", "layout/box"),
        UiBuilderNode(
          "label",
          "a2ui/Text",
          properties =
            buildJsonObject {
              put(
                "text",
                buildJsonObject {
                  put("type", "state")
                  put("variable", "missing")
                },
              )
            },
          modifiers = buildJsonArray { add(buildJsonObject { put("type", "padding") }) },
          eventBindings = buildJsonObject { put("click", JsonArray(emptyList())) },
        ),
      )
    val refused =
      assertIs<A2uiDocumentExporter.Result.Refused>(A2uiDocumentExporter.export(document(bad)))
    val reasons = refused.reasons.joinToString("\n")
    assertTrue("`layout/box` is not an A2UI component" in reasons, reasons)
    assertTrue("nodes.label.modifiers" in reasons, reasons)
    assertTrue("nodes.label.eventBindings" in reasons, reasons)
    assertTrue("state `missing` is not declared" in reasons, reasons)
  }

  @Test
  fun `a design with two roots is refused`() {
    val refused =
      assertIs<A2uiDocumentExporter.Result.Refused>(
        A2uiDocumentExporter.export(
          document(
            listOf(UiBuilderNode("a", "a2ui/Divider"), UiBuilderNode("b", "a2ui/Divider")),
            roots = listOf("a", "b"),
          )
        )
      )
    assertTrue(refused.reasons.single().startsWith("roots:"))
  }
}
