package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.editor.EditorLibraryComponent
import ee.schimke.composeai.uibuilder.editor.EditorLibrarySymbol
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A component the project publishes, placed from the palette's "Project library" shelf.
 *
 * The first placement imports it — the body, and a `source` naming the project, the symbol and the
 * digest it was read at — which is what lets the drift report say later that the library moved.
 * Every later placement places the version the design already holds.
 */
class LocalComponentsLibraryTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )
  private val reducer = UiBuilderEditorReducer(catalog)

  private val published =
    EditorLibraryComponent(
      system = "gmail",
      componentId = "inbox-email",
      paletteId = "project/inbox-email",
      title = "Inbox email",
    )

  private fun symbol(digest: String = "sha256:one"): EditorLibrarySymbol {
    fun binding(key: String) =
      JsonObject(mapOf("type" to JsonPrimitive("binding"), "value" to JsonPrimitive(key)))
    return EditorLibrarySymbol(
      component = published,
      digest = digest,
      declaration =
        JsonObject(mapOf("name" to JsonPrimitive("InboxEmail"), "root" to JsonPrimitive("row"))),
      nodes =
        listOf(
            UiBuilderNode(
              id = "row",
              componentId = "layout/row",
              slots = mapOf("children" to listOf("sender", "subject")),
            ),
            UiBuilderNode(
              id = "sender",
              componentId = "m3/text",
              properties = JsonObject(mapOf("text" to binding("sender"))),
            ),
            UiBuilderNode(
              id = "subject",
              componentId = "m3/text",
              properties = JsonObject(mapOf("text" to binding("subject"))),
            ),
          )
          .associateBy { it.id },
    )
  }

  @Test
  fun `the first placement imports the symbol with its source, the next places it again`() {
    val start = reducer.initial(inbox("inbox"), "email-2")
    val target = assertNotNull(reducer.libraryComponentTarget(start, symbol()))

    val imported =
      reducer.reduce(start, UiBuilderEditorEvent.InsertLibraryComponent(symbol(), target))

    assertIs<CommandOutcome.Accepted>(imported.lastOutcome, "${imported.lastOutcome}")
    val declaration = assertNotNull(imported.document.components["inbox-email"]?.jsonObject)
    val source = declaration["source"]!!.jsonObject
    assertEquals("gmail", source["system"]!!.jsonPrimitive.content)
    assertEquals("inbox-email", source["componentId"]!!.jsonPrimitive.content)
    assertEquals("sha256:one", source["digest"]!!.jsonPrimitive.content)
    assertEquals(listOf("inbox"), imported.document.roots)
    val first = imported.document.nodes.getValue(imported.selection.single())
    assertEquals(
      setOf("sender", "subject"),
      first.component!!["arguments"]!!.jsonObject.keys,
    )

    // A newer digest in the library is drift to report, not a second copy or a silent redraw.
    val again =
      reducer.reduce(
        imported,
        UiBuilderEditorEvent.InsertLibraryComponent(
          symbol("sha256:two"),
          assertNotNull(reducer.libraryComponentTarget(imported, symbol())),
        ),
      )
    assertIs<CommandOutcome.Accepted>(again.lastOutcome, "${again.lastOutcome}")
    assertEquals(setOf("inbox-email"), again.document.components.keys)
    assertEquals(
      "sha256:one",
      again.document.components["inbox-email"]!!
        .jsonObject["source"]!!
        .jsonObject["digest"]!!
        .jsonPrimitive
        .content,
    )
    assertEquals(2, reducer.localComponents(again).single().placements)
    val source2 = CapabilityComposeCodeExporter.export(again.document, catalog).requireSource()
    assertTrue(source2.contains("private fun InboxEmail(sender: String, subject: String"), source2)
  }

  @Test
  fun `the drift report keeps a finding about the imported source`() {
    val start = reducer.initial(inbox("inbox"), "email-2")
    val imported =
      reducer.reduce(
        start,
        UiBuilderEditorEvent.InsertLibraryComponent(
          symbol(),
          assertNotNull(reducer.libraryComponentTarget(start, symbol())),
        ),
      )
    val finding =
      ComponentDriftFinding(
        componentKey = "inbox-email",
        system = "gmail",
        componentId = "inbox-email",
        paletteId = "project/inbox-email",
        state = ComponentDriftState.DRIFTED,
        importedDigest = "sha256:one",
        currentDigest = "sha256:two",
      )

    val reported = reducer.reduce(imported, UiBuilderEditorEvent.SetComponentDrift(listOf(finding)))

    assertEquals(listOf(finding), reported.componentDrift)
  }

  private fun inbox(id: String): UiBuilderDocument {
    fun literal(value: String) =
      JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))
    fun text(nodeId: String, value: String) =
      UiBuilderNode(
        id = nodeId,
        componentId = "m3/text",
        properties = JsonObject(mapOf("text" to literal(value))),
      )
    fun row(nodeId: String) =
      UiBuilderNode(
        id = nodeId,
        componentId = "layout/row",
        slots = mapOf("children" to listOf("$nodeId-sender", "$nodeId-subject")),
      )
    val nodes =
      listOf(
          UiBuilderNode(
            id = "inbox",
            componentId = "layout/column",
            slots = mapOf("children" to listOf("email-1", "email-2")),
          ),
          row("email-1"),
          text("email-1-sender", "Sender"),
          text("email-1-subject", "Subject"),
          row("email-2"),
          text("email-2-sender", "Ada Lovelace"),
          text("email-2-subject", "Notes on the engine"),
        )
        .associateBy { it.id }
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = id,
      title = id,
      revision = 1,
      catalogPin =
        JsonObject(
          mapOf(
            "systemId" to JsonPrimitive("m3-catalog"),
            "catalogRevision" to JsonPrimitive("candidate"),
            "capabilityDigest" to JsonPrimitive("candidate"),
            "nativeRuntimeId" to JsonPrimitive("candidate"),
          )
        ),
      environment =
        Json.parseToJsonElement(
            """
            {
              "widthDp": 360, "heightDp": 640, "density": 1.0, "theme": "light",
              "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr",
              "animations": "settled"
            }
            """
          )
          .jsonObject,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("inbox"),
      nodes = nodes,
    )
  }
}
