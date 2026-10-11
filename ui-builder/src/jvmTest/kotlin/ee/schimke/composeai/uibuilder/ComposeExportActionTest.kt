package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.codegen.COMPOSE_EMITTED_CLICK_COMPONENTS
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
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
 * The editor can author `toggle` and `set`, and the exporter must emit them rather than a
 * `TODO("Unsupported action …")` that throws when pressed.
 */
class ComposeExportActionTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
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
  fun `navigatePage is an exported host callback rather than a throwing TODO`() {
    val source =
      exportOf(
        listOf(
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("navigatePage"),
              "pageKey" to JsonPrimitive("checkout-confirmation"),
            )
          )
        )
      )

    assertTrue(source.contains("onNavigatePage: (String) -> Unit = {}"), source)
    assertTrue(source.contains("onNavigatePage(\"checkout-confirmation\")"), source)
    assertFalse(source.contains("Unsupported action navigatePage"), source)
  }

  @Test
  fun `an assignment a declared type cannot hold is refused rather than emitted`() {
    // `eventBindings` arrives from other clients too; `expanded` is declared `bool`, and values it
    // can't hold must be refused (export validation doesn't read `stateVariables`).
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
    // `booleanOrNull` ignores quoting, so the check must read the quoting: `"true"` is not a flag.
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
    // A handler runs its actions in order as a unit, as the renderer dispatches it; export must
    // emit all of them.
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
    // `booleanOrNull` ignores quoting, so a quoted `"true"` must not make `caption` a Boolean.
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
    // The diagnostic covers every action, not just the first.
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
    // `!` doesn't apply to `Boolean?`; like the renderer, null toggles to true.
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
    // A malformed entry behind a valid first action must produce the diagnostic, not throw out of
    // export().
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
    // Integer literals are unsuffixed `Int`s, so variables must be declared `Int`, not `Long`.
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
    // Literals are spelled from the authored JSON text, not `toLong()` (which saturates `1e20`).
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
    // `stateVariables` is unvalidated, so malformed `nullable`/`valueType` must yield diagnostics,
    // not exceptions.
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
    // Click wiring is offered only for components whose emitter takes an onClick; others would lose
    // the interaction on export (UNEMITTED_EVENT).
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
