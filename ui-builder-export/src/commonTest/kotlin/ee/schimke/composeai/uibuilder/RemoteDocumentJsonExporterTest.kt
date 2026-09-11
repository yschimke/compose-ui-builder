package ee.schimke.composeai.uibuilder

import kotlin.test.*
import kotlinx.serialization.json.*

internal fun remoteJsonSelectionFixture(
  values: List<JsonPrimitive> = listOf(JsonPrimitive(10), JsonPrimitive(20)),
  density: Int = 1,
): UiBuilderDocument {
  val kind =
    when {
      values.first().booleanOrNull != null -> "bool"
      values.first().intOrNull != null -> "int"
      else -> "float"
    }
  val ids = values.indices.map { "case$it" }
  val selection =
    StateSelection(
      buildJsonObject {
        put("type", "state")
        put("variable", "page")
      },
      ids.zip(values).toMap(),
      "fallback",
    )
  val click = buildJsonArray {
    if (kind == "bool") {
      add(
        buildJsonObject {
          put("type", "toggle")
          put("variable", "page")
        }
      )
    } else {
      add(
        buildJsonObject {
          put("type", "set")
          put("variable", "page")
          put("value", values.first())
        }
      )
      add(
        buildJsonObject {
          put("type", "set")
          put("variable", "page")
          put("value", values.last())
        }
      )
    }
  }
  val root =
    UiBuilderNode(
      "switch",
      "layout/box",
      properties = buildJsonObject { put(SHOW_BY_STATE, selection.encode()) },
      modifiers =
        buildJsonArray {
          add(buildJsonObject { put("type", "fillMaxSize") })
          add(
            buildJsonObject {
              put("type", "padding")
              listOf("startDp", "topDp", "endDp", "bottomDp").forEach { put(it, 8) }
            }
          )
        },
      slots = mapOf("children" to ids + "fallback"),
      eventBindings = buildJsonObject { put("click", click) },
    )
  val children =
    (ids + "fallback").mapIndexed { index, id ->
      val color =
        if (index == 0) "#FFFF0000" else if (id == "fallback") "#FF0000FF" else "#FF00FF00"
      UiBuilderNode(
        id,
        "layout/box",
        modifiers =
          buildJsonArray {
            add(buildJsonObject { put("type", "fillMaxSize") })
            add(
              buildJsonObject {
                put("type", "background")
                putJsonObject("color") {
                  put("type", "color")
                  put("value", color)
                }
              }
            )
          },
      )
    }
  return UiBuilderDocument(
    "ui-builder-design-v1",
    "selection",
    "Selection",
    1,
    JsonObject(emptyMap()),
    buildJsonObject {
      put("widthDp", 100)
      put("heightDp", 100)
      put("density", density)
    },
    buildJsonObject {
      putJsonObject("page") {
        put("valueType", kind)
        put("initialValue", values.first())
      }
    },
    listOf("switch"),
    (listOf(root) + children).associateBy { it.id },
  )
}

class RemoteDocumentJsonExporterTest {
  @Test
  fun `decimal selection uses exact comparisons and the state compiler profile`() {
    val values = listOf(JsonPrimitive(1f), JsonPrimitive(Float.fromBits(1f.toBits() + 1)))
    val result =
      assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
        RemoteDocumentJsonExporter.export(remoteJsonSelectionFixture(values))
      )
    val json = Json.parseToJsonElement(result.source).jsonObject
    assertEquals(
      RemoteDocumentJsonExporter.STATE_PROFILE,
      json["compilerProfile"]!!.jsonPrimitive.content,
    )
    val comparisons =
      json["root"]!!
        .jsonArray
        .last()
        .jsonObject["children"]!!
        .jsonArray
        .map { it.jsonObject }
        .filter { it["type"] == JsonPrimitive("floatEquals") }
    assertEquals(2, comparisons.size)
    assertEquals(values.reversed(), comparisons.map { it["right"] })
    assertTrue(comparisons.all { it["left"] == JsonPrimitive("@page") })
    assertEquals("float", result.stateKinds["page"])
  }

  @Test
  fun `selection keeps the authored box and ordered actions`() {
    val document = remoteJsonSelectionFixture()
    val exported =
      assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
        RemoteDocumentJsonExporter.export(document)
      )
    val json = Json.parseToJsonElement(exported.source).jsonObject
    assertEquals(
      RemoteDocumentJsonExporter.INTEGER_PROFILE,
      json["compilerProfile"]!!.jsonPrimitive.content,
    )
    val root = json["root"]!!.jsonArray.last().jsonObject
    assertEquals("box", root["type"]!!.jsonPrimitive.content)
    val children = root["children"]!!.jsonArray
    assertEquals("integerExpression", children.first().jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals("stateLayout", children.last().jsonObject["type"]!!.jsonPrimitive.content)
    val actions = root["modifiers"]!!.jsonArray.last().jsonObject["onClick"]!!.jsonArray
    assertEquals(listOf(10, 20), actions.map { it.jsonObject["value"]!!.jsonPrimitive.int })
    assertEquals(exported, RemoteDocumentJsonExporter.export(document))
  }

  @Test
  fun `dimensions and padding use the captured density`() {
    val exported =
      assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
        RemoteDocumentJsonExporter.export(remoteJsonSelectionFixture(density = 2))
      )
    val json = Json.parseToJsonElement(exported.source).jsonObject
    assertEquals(200, json["header"]!!.jsonObject["width"]!!.jsonPrimitive.int)
    val padding =
      json["root"]!!
        .jsonArray
        .last()
        .jsonObject["modifiers"]!!
        .jsonArray[1]
        .jsonObject["padding"]!!
        .jsonObject
    assertEquals(16.0, padding["start"]!!.jsonPrimitive.double)
    val original = remoteJsonSelectionFixture()
    val fractional =
      original.copy(
        environment =
          buildJsonObject {
            put("widthDp", 101)
            put("heightDp", 100)
            put("density", 1.5)
          }
      )
    val rounded =
      assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
        RemoteDocumentJsonExporter.export(fractional)
      )
    assertEquals(
      152,
      Json.parseToJsonElement(rounded.source)
        .jsonObject["header"]!!
        .jsonObject["width"]!!
        .jsonPrimitive
        .int,
    )
  }

  @Test
  fun `boolean export identifies its integer host representation`() {
    val exported =
      assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
        RemoteDocumentJsonExporter.export(
          remoteJsonSelectionFixture(listOf(JsonPrimitive(false), JsonPrimitive(true)))
        )
      )
    assertEquals(mapOf("page" to "bool"), exported.stateKinds)
    val json = Json.parseToJsonElement(exported.source).jsonObject
    assertEquals(
      0,
      json["root"]!!
        .jsonArray[0]
        .jsonObject["integers"]!!
        .jsonObject["page"]!!
        .jsonObject["value"]!!
        .jsonPrimitive
        .int,
    )
  }

  @Test
  fun `unsupported catalog calls and modifier fields are located`() {
    val original = remoteJsonSelectionFixture()
    val unsupported = original.nodes.getValue("case0").copy(componentId = "remote-m3/button")
    val result =
      assertIs<RemoteDocumentJsonExporter.Result.Refused>(
        RemoteDocumentJsonExporter.export(
          original.copy(nodes = original.nodes + ("case0" to unsupported))
        )
      )
    assertTrue(result.reasons.any { "nodes.case0.componentId" in it && "recipe" in it })
    val rounded =
      original.nodes
        .getValue("case0")
        .copy(
          modifiers =
            buildJsonArray {
              add(
                buildJsonObject {
                  put("type", "background")
                  put("color", "#FF000000")
                  put("shape", "circle")
                }
              )
            }
        )
    assertTrue(
      assertIs<RemoteDocumentJsonExporter.Result.Refused>(
          RemoteDocumentJsonExporter.export(
            original.copy(nodes = original.nodes + ("case0" to rounded))
          )
        )
        .reasons
        .any { "modifiers[0].shape" in it }
    )
  }

  @Test
  fun `malformed selection and cycles return diagnostics rather than partial source`() {
    val original = remoteJsonSelectionFixture()
    val root = original.nodes.getValue("switch")
    val malformed = root.copy(properties = buildJsonObject { put(SHOW_BY_STATE, "bad") })
    assertIs<RemoteDocumentJsonExporter.Result.Refused>(
      RemoteDocumentJsonExporter.export(
        original.copy(nodes = original.nodes + ("switch" to malformed))
      )
    )
    val cyclic =
      root.copy(properties = JsonObject(emptyMap()), slots = mapOf("children" to listOf("switch")))
    assertIs<RemoteDocumentJsonExporter.Result.Refused>(
      RemoteDocumentJsonExporter.export(
        original.copy(nodes = original.nodes + ("switch" to cyclic))
      )
    )
  }

  @Test
  fun `mutable strings use independent state declarations and unambiguous action literals`() {
    val result =
      assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
        RemoteDocumentJsonExporter.export(remoteJsonStringFixture("@second"))
      )
    val json = Json.parseToJsonElement(result.source).jsonObject
    assertEquals(JsonPrimitive(RemoteDocumentJsonExporter.STATE_PROFILE), json["compilerProfile"])
    val roots = json.getValue("root").jsonArray.map { it.jsonObject }
    val strings = roots.filter { it["type"] == JsonPrimitive("mutableString") }
    assertEquals(
      setOf("first", "second", "__rc_text_0"),
      strings.map { it.getValue("name").jsonPrimitive.content }.toSet(),
    )
    assertTrue(strings.all { it["value"] == JsonPrimitive("Ready") })
    val literals = roots.filter { it["type"] == JsonPrimitive("variable") }
    assertTrue(literals.any { it["value"] == JsonPrimitive("@second") })
    assertTrue(
      literals.none { it["name"] == JsonPrimitive("__rc_text_0") },
      "Generated names must not shadow state",
    )
    assertEquals("string", result.stateKinds["first"])
  }
}

internal fun remoteJsonStringFixture(value: String): UiBuilderDocument {
  val original = remoteJsonSelectionFixture()
  val declarations =
    JsonObject(
      original.stateVariables +
        listOf("first", "second", "__rc_text_0").associateWith {
          buildJsonObject {
            put("valueType", "string")
            put("initialValue", "Ready")
          }
        }
    )
  val root = original.nodes.getValue("switch")
  val changed =
    root.copy(
      eventBindings =
        buildJsonObject {
          putJsonArray("click") {
            for (text in listOf("Interim", value)) add(
              buildJsonObject {
                put("type", "set")
                put("variable", "first")
                put("value", text)
              }
            )
          }
        }
    )
  return original.copy(stateVariables = declarations, nodes = original.nodes + (root.id to changed))
}
