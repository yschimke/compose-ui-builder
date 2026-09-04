package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The editor can author `toggle` and `set`; the exporter could not emit them.
 *
 * A control built with either exported to `TODO("Unsupported action …")`, which throws the moment
 * anyone presses it. The preview ran the action and the export did not, which is the worst shape
 * for a builder whose whole claim is that what you see is the Compose you get.
 */
class ComposeExportActionTest {
  private val catalog =
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  private val fixture =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun literal(type: String, value: JsonPrimitive) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

  private fun documentWith(actions: List<JsonElement>): UiBuilderDocument {
    val button =
      UiBuilderNode(
        id = "toggle-button",
        componentId = "m3/button",
        properties = JsonObject(mapOf("style" to literal("enum", JsonPrimitive("filled")))),
        slots = mapOf("content" to listOf("toggle-label")),
        eventBindings = JsonObject(mapOf("click" to JsonArray(actions))),
      )
    val label =
      UiBuilderNode(
        id = "toggle-label",
        componentId = "m3/text",
        properties = JsonObject(mapOf("text" to literal("string", JsonPrimitive("Press")))),
      )
    return fixture.copy(
      roots = listOf(button.id),
      nodes = mapOf(button.id to button, label.id to label),
      stateVariables =
        JsonObject(
          mapOf(
            "expanded" to
              JsonObject(
                mapOf(
                  "type" to JsonPrimitive("value"),
                  "valueType" to JsonPrimitive("bool"),
                  "initialValue" to JsonPrimitive(false),
                  "persistence" to JsonPrimitive("preview"),
                )
              )
          )
        ),
    )
  }

  private fun exportOf(actions: List<JsonObject>): String {
    val result = CapabilityComposeCodeExporter.export(documentWith(actions), catalog)
    return assertNotNull(result.source, result.diagnostics.joinToString { it.message })
  }

  @Test
  fun `toggle flips the boolean the document declares`() {
    val source =
      exportOf(
        listOf(
          JsonObject(
            mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive("expanded"))
          )
        )
      )

    assertTrue(
      source.contains("expanded = !expanded"),
      source.lineSequence().first { "onClick" in it },
    )
    assertFalse(
      source.contains("TODO("),
      "a toggle used to export as a TODO that throws when pressed",
    )
  }

  @Test
  fun `set is an assignment, and its value keeps the declared type`() {
    val source =
      exportOf(
        listOf(
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("set"),
              "variable" to JsonPrimitive("expanded"),
              "value" to JsonPrimitive(true),
            )
          )
        )
      )

    // Unquoted: the exporter declares `expanded` from its boolean initial value, so a quoted
    // "true" would not compile against it.
    assertTrue(source.contains("expanded = true"), source)
    assertFalse(source.contains("expanded = \"true\""), source)
  }

  @Test
  fun `an assignment a declared type cannot hold is refused rather than emitted`() {
    // The editor refuses these, and the editor is not the only author: `eventBindings` arrives on
    // the wire from other clients and future versions of this one. `expanded` is declared `bool`,
    // and this used to emit `expanded = "yes"` — source nobody can compile, with no diagnostic,
    // because export validation does not read `stateVariables`.
    listOf(JsonPrimitive("yes"), JsonPrimitive(3), JsonNull).forEach { bad ->
      val source =
        exportOf(
          listOf(
            JsonObject(
              mapOf(
                "type" to JsonPrimitive("set"),
                "variable" to JsonPrimitive("expanded"),
                "value" to bad,
              )
            )
          )
        )

      val handler = source.lineSequence().first { "onClick" in it }
      assertTrue(handler.contains("TODO("), handler)
      assertTrue(handler.contains("expanded is Boolean"), handler)
      assertFalse(handler.contains("expanded = "), handler)
    }
  }

  @Test
  fun `a quoted flag is not a flag`() {
    // `booleanOrNull` parses a `JsonPrimitive` whether or not it was quoted, so the check has to
    // read the quoting rather than the content — otherwise `"true"` passes and emits
    // `expanded = "true"`.
    val source =
      exportOf(
        listOf(
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("set"),
              "variable" to JsonPrimitive("expanded"),
              "value" to JsonPrimitive("true"),
            )
          )
        )
      )

    assertTrue(source.lineSequence().first { "onClick" in it }.contains("TODO("), source)
  }

  @Test
  fun `every action in a handler is emitted, not just the first`() {
    // `eventBindings` is a list because a handler runs its actions in order and as a unit, which
    // is how the renderer dispatches it. Exporting only the head shipped a button that did less
    // than the preview showed.
    val source =
      exportOf(
        listOf(
          JsonObject(
            mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive("expanded"))
          ),
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("set"),
              "variable" to JsonPrimitive("expanded"),
              "value" to JsonPrimitive(false),
            )
          ),
        )
      )

    assertTrue(source.contains("expanded = !expanded; expanded = false"), source)
  }

  @Test
  fun `a toggle against a non-boolean variable refuses rather than emitting broken source`() {
    val document =
      documentWith(
          listOf(
            JsonObject(
              mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive("caption"))
            )
          )
        )
        .let { base ->
          base.copy(
            stateVariables =
              JsonObject(
                mapOf(
                  "caption" to
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("text"),
                        "initialValue" to JsonPrimitive("hello"),
                        "persistence" to JsonPrimitive("preview"),
                      )
                    )
                )
              )
          )
        }
    val source = assertNotNull(CapabilityComposeCodeExporter.export(document, catalog).source)

    // `!caption` would not compile. A refusal is the honest output.
    assertTrue(source.contains("TODO(\"toggle needs a boolean state variable\")"), source)
  }

  private fun documentDeclaring(
    variable: String,
    declaration: JsonObject,
    actions: List<JsonObject>,
  ): UiBuilderDocument =
    documentWith(actions).copy(stateVariables = JsonObject(mapOf(variable to declaration)))

  @Test
  fun `a text variable holding the word true is still a String`() {
    // `booleanOrNull` on a `JsonPrimitive` parses the content whether or not it was quoted, so
    // every classifier that asked it without checking `isString` read this as a flag: the export
    // declared `var caption` as a Boolean and a `set` wrote an unquoted `true` into it.
    val document =
      documentDeclaring(
        "caption",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("text"),
            "valueType" to JsonPrimitive("string"),
            "initialValue" to JsonPrimitive("true"),
            "persistence" to JsonPrimitive("preview"),
          )
        ),
        listOf(
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("set"),
              "variable" to JsonPrimitive("caption"),
              "value" to JsonPrimitive("hello"),
            )
          )
        ),
      )
    val source = assertNotNull(CapabilityComposeCodeExporter.export(document, catalog).source)

    assertTrue(source.contains("var caption: String by remember"), source)
    assertTrue(source.contains("caption = \"hello\""), source)
    assertFalse(source.contains("!caption"), source)
  }

  @Test
  fun `a nullable variable declares its type rather than inferring Nothing`() {
    // `mutableStateOf(null)` infers `MutableState<Nothing?>`, which rejects every later
    // assignment. The declaration says what the variable holds, so the export writes it out.
    val document =
      documentDeclaring(
        "selectedTrack",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("selection"),
            "valueType" to JsonPrimitive("string"),
            "nullable" to JsonPrimitive(true),
            "initialValue" to JsonNull,
            "persistence" to JsonPrimitive("preview"),
          )
        ),
        listOf(
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("selectOrClear"),
              "variable" to JsonPrimitive("selectedTrack"),
              "value" to JsonPrimitive("droidCon"),
            )
          )
        ),
      )
    val source = assertNotNull(CapabilityComposeCodeExporter.export(document, catalog).source)

    assertTrue(source.contains("var selectedTrack: String? by remember"), source)
    assertTrue(
      source.contains("selectedTrack = if (selectedTrack == \"droidCon\") null else \"droidCon\""),
      source,
    )
  }

  @Test
  fun `clearing a variable the document declares non-nullable refuses`() {
    val document =
      documentDeclaring(
        "selectedDay",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("selection"),
            "valueType" to JsonPrimitive("int"),
            "nullable" to JsonPrimitive(false),
            "initialValue" to JsonPrimitive(0),
            "persistence" to JsonPrimitive("preview"),
          )
        ),
        listOf(
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("selectOrClear"),
              "variable" to JsonPrimitive("selectedDay"),
              "value" to JsonPrimitive(1),
            )
          )
        ),
      )
    val source = assertNotNull(CapabilityComposeCodeExporter.export(document, catalog).source)

    // Assigning null to a non-nullable `Long` does not compile; a refusal is the honest output.
    assertTrue(source.contains("TODO(\"selectOrClear needs a nullable state variable\")"), source)
    assertFalse(source.contains("else null"), source)
  }

  @Test
  fun `an unsupported action is reported wherever it sits in the handler`() {
    // The diagnostic used to read only the first action, so an unsupported second one reached the
    // generated source as a bare TODO with nothing warning about it — and every multi-action
    // handler drew a PARTIAL_EVENT saying only the head was emitted, which stopped being true.
    val result =
      CapabilityComposeCodeExporter.export(
        documentWith(
          listOf(
            JsonObject(
              mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive("expanded"))
            ),
            JsonObject(
              mapOf("type" to JsonPrimitive("navigate"), "variable" to JsonPrimitive("expanded"))
            ),
          )
        ),
        catalog,
      )

    assertTrue(
      result.diagnostics.any { it.code == "UNSUPPORTED_EVENT_ACTION" && "navigate" in it.message },
      result.diagnostics.joinToString { "${it.code}: ${it.message}" },
    )
    assertFalse(
      result.diagnostics.any { it.code == "PARTIAL_EVENT" },
      "every action is emitted now, so nothing is partial",
    )
  }

  @Test
  fun `toggling a nullable flag matches what the preview does with null`() {
    // The declared type is `Boolean?` and `!` does not apply to one. The renderer reads a missing
    // value as not-true, so null toggles to true; the export says the same rather than emitting
    // `!flag` against a nullable.
    val document =
      documentDeclaring(
        "flag",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("value"),
            "valueType" to JsonPrimitive("bool"),
            "nullable" to JsonPrimitive(true),
            "initialValue" to JsonNull,
            "persistence" to JsonPrimitive("preview"),
          )
        ),
        listOf(
          JsonObject(mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive("flag")))
        ),
      )
    val source = assertNotNull(CapabilityComposeCodeExporter.export(document, catalog).source)

    assertTrue(source.contains("var flag: Boolean? by remember"), source)
    assertTrue(source.contains("flag = !(flag ?: false)"), source)
  }

  @Test
  fun `a malformed later action is a diagnostic rather than an exception`() {
    // Emitting every action means a malformed entry behind a valid first one now reaches the
    // emitter. It used to sit unread; an unchecked `jsonObject` on it would throw out of export()
    // instead of producing the diagnostic that already covers it.
    val result =
      CapabilityComposeCodeExporter.export(
        documentWith(
          listOf(
            JsonObject(
              mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive("expanded"))
            )
          ) + JsonPrimitive("not an action")
        ),
        catalog,
      )

    val source = assertNotNull(result.source, result.diagnostics.joinToString { it.message })
    assertTrue(source.contains("expanded = !expanded"), source)
    assertTrue(source.contains("TODO(\"Malformed action\")"), source)
    assertTrue(
      result.diagnostics.any { it.code == "UNSUPPORTED_EVENT_ACTION" },
      result.diagnostics.joinToString { "${it.code}: ${it.message}" },
    )
  }

  @Test
  fun `an integer state and the values compared against it are the same Kotlin type`() {
    // `kotlinLiteral` emits a JSON number verbatim, so every integer this exporter writes is an
    // unsuffixed `Int` literal. Declaring the variable `Long` made both the initial value and a
    // comparison operand mismatch against it.
    val document =
      documentDeclaring(
        "selectedDay",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("selection"),
            "valueType" to JsonPrimitive("int"),
            "nullable" to JsonPrimitive(false),
            "initialValue" to JsonPrimitive(0),
            "persistence" to JsonPrimitive("preview"),
          )
        ),
        listOf(
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("set"),
              "variable" to JsonPrimitive("selectedDay"),
              "value" to JsonPrimitive(1),
            )
          )
        ),
      )
    val source = assertNotNull(CapabilityComposeCodeExporter.export(document, catalog).source)

    assertTrue(source.contains("var selectedDay: Int by remember { mutableStateOf(0) }"), source)
    assertTrue(source.contains("selectedDay = 1"), source)
    assertFalse(source.contains("Long"), source)
  }

  @Test
  fun `a float variable seeded with a whole number still declares a Double`() {
    val document =
      documentDeclaring(
        "ratio",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("value"),
            "valueType" to JsonPrimitive("float"),
            "nullable" to JsonPrimitive(false),
            "initialValue" to JsonPrimitive(1),
            "persistence" to JsonPrimitive("preview"),
          )
        ),
        emptyList(),
      )
    val source = assertNotNull(CapabilityComposeCodeExporter.export(document, catalog).source)

    // `mutableStateOf(1)` behind a `Double` property is an Int delegate and does not compile.
    assertTrue(source.contains("var ratio: Double by remember { mutableStateOf(1.0) }"), source)
  }

  @Test
  fun `a float variable keeps the value it was declared with`() {
    // The literal used to be spelled through `toLong()`, which saturates: `1e20` came out as
    // `9223372036854775807.0`, so the preview started from one value and the exported screen from
    // another. Spelling it from the authored JSON text has neither that problem nor the one the
    // `.0` exists to stop.
    listOf(
        JsonPrimitive(1e20) to "1.0E20",
        JsonPrimitive(0.5) to "0.5",
        JsonPrimitive(1) to "1.0",
        JsonPrimitive(-7) to "-7.0",
      )
      .forEach { (initial, expected) ->
        val document =
          documentDeclaring(
            "ratio",
            JsonObject(
              mapOf(
                "type" to JsonPrimitive("value"),
                "valueType" to JsonPrimitive("float"),
                "nullable" to JsonPrimitive(false),
                "initialValue" to initial,
                "persistence" to JsonPrimitive("preview"),
              )
            ),
            emptyList(),
          )
        val source = assertNotNull(CapabilityComposeCodeExporter.export(document, catalog).source)

        assertTrue(
          source.contains("var ratio: Double by remember { mutableStateOf($expected) }"),
          "$initial should emit $expected: $source",
        )
      }
  }

  @Test
  fun `a malformed declaration is a diagnostic rather than an exception`() {
    // Export validation never reads `stateVariables`, so a document carrying `"nullable": {}` or
    // `"valueType": []` reached the declaration parser. An accessor that throws on a non-primitive
    // turned that into an exception out of export() instead of source plus diagnostics.
    val document =
      documentDeclaring(
        "odd",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("text"),
            "valueType" to JsonArray(emptyList()),
            "nullable" to JsonObject(emptyMap()),
            "initialValue" to JsonPrimitive("a"),
            "persistence" to JsonPrimitive("preview"),
          )
        ),
        emptyList(),
      )

    val source = assertNotNull(CapabilityComposeCodeExporter.export(document, catalog).source)

    assertTrue(source.contains("var odd: String by remember"), source)
  }

  @Test
  fun `a later action naming an undeclared variable does not export as an assignment`() {
    val result =
      CapabilityComposeCodeExporter.export(
        documentWith(
          listOf(
            JsonObject(
              mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive("expanded"))
            ),
            JsonObject(
              mapOf(
                "type" to JsonPrimitive("set"),
                "variable" to JsonPrimitive("doesNotExist"),
                "value" to JsonPrimitive(true),
              )
            ),
          )
        ),
        catalog,
      )

    val source = assertNotNull(result.source, result.diagnostics.joinToString { it.message })
    assertFalse(source.contains("doesNotExist = true"), source)
    assertTrue(source.contains("Undeclared state variable"), source)
  }

  @Test
  fun `only components whose click the exporter emits are offered for action insertion`() {
    // `click` is universal in the renderer, so this used to offer every component the destination
    // accepted. The exporter emits a handler only where the component's emitter takes an onClick;
    // everything else reported UNEMITTED_EVENT and generated nothing, so the control worked in the
    // preview and lost its interaction on export.
    val reducer = UiBuilderEditorReducer(catalog)
    val withState =
      fixture.copy(
        stateVariables =
          JsonObject(
            mapOf(
              "expanded" to
                JsonObject(
                  mapOf(
                    "type" to JsonPrimitive("value"),
                    "valueType" to JsonPrimitive("bool"),
                    "initialValue" to JsonPrimitive(false),
                    "persistence" to JsonPrimitive("preview"),
                  )
                )
            )
          )
      )
    val state = reducer.initial(withState, selectedNodeId = "main-episode-footer")

    val candidates = reducer.actionInsertCandidates(state).map { it.componentId }

    assertTrue(candidates.isNotEmpty(), "the row accepts something")
    assertTrue(
      candidates.all { it in COMPOSE_EMITTED_CLICK_COMPONENTS },
      "offered a component the export would drop the click of: $candidates",
    )
    assertFalse(candidates.contains("m3/text"), candidates.toString())
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
