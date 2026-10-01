package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A component's parameters beyond its texts, and the way back out of a component.
 *
 * Making a component promotes its texts; a colour — the avatar each inbox row tints differently —
 * becomes a parameter when somebody asks for it, and stops being one the same way. Detaching an
 * instance turns it back into the layers it drew, holding the values it passed.
 */
class LocalComponentsParametersTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )
  private val reducer = UiBuilderEditorReducer(catalog)

  private fun twoPlaced(): UiBuilderEditorState {
    var state =
      reducer.reduce(
        reducer.initial(inbox("inbox"), "email-1"),
        UiBuilderEditorEvent.MakeComponent("Inbox email"),
      )
    val target = checkNotNull(reducer.localComponentTarget(state, "inbox-email"))
    state = reducer.reduce(state, UiBuilderEditorEvent.InsertLocalComponent("inbox-email", target))
    assertIs<CommandOutcome.Accepted>(state.lastOutcome, "${state.lastOutcome}")
    return state
  }

  private fun placements(state: UiBuilderEditorState) =
    state.document.nodes.values.filter { it.component?.get("componentKey") != null }

  @Test
  fun `a colour in the body becomes a parameter every placement passes`() {
    val placed = twoPlaced().let { it.copy(selection = listOf("email-1-avatar")) }
    val offered = reducer.componentActions(placed)
    val expose = offered.single { it.label.startsWith("Make") }

    val exposed = reducer.reduce(placed, expose.event)

    assertIs<CommandOutcome.Accepted>(exposed.lastOutcome, "${exposed.lastOutcome}")
    assertEquals(
      listOf("containerColor", "sender", "subject"),
      reducer.localComponents(exposed).single().parameters,
    )
    placements(exposed).forEach {
      assertEquals(
        "#FF6750A4",
        it.component!!["arguments"]!!
          .jsonObject["containerColor"]!!
          .jsonObject["value"]
          .toString()
          .trim('"'),
      )
    }
    val source = CapabilityComposeCodeExporter.export(exposed.document, catalog).requireSource()
    assertTrue(source.contains("containerColor: Color"), source)
    // And the same menu offers the way back.
    val inline =
      reducer.componentActions(exposed.copy(selection = listOf("email-1-avatar"))).single {
        it.label.startsWith("Stop")
      }
    val inlined = reducer.reduce(exposed, inline.event)
    assertIs<CommandOutcome.Accepted>(inlined.lastOutcome, "${inlined.lastOutcome}")
    assertEquals(listOf("sender", "subject"), reducer.localComponents(inlined).single().parameters)
    assertEquals(
      placed.document.nodes.getValue("email-1-avatar").properties,
      inlined.document.nodes.getValue("email-1-avatar").properties,
    )
    placements(inlined).forEach {
      assertFalse("containerColor" in it.component!!["arguments"]!!.jsonObject, it.id)
    }
  }

  @Test
  fun `a detached instance is ordinary layers holding what it passed`() {
    val placed = twoPlaced()
    val second = placed.selection.single()
    val edited =
      reducer.reduce(placed, UiBuilderEditorEvent.CommitProperty(second, "sender", "Grace Hopper"))
    val instance = edited.selection.single()
    val detach = reducer.componentActions(edited).single()
    assertEquals("Detach instance", detach.label)

    val detached = reducer.reduce(edited, detach.event)

    assertIs<CommandOutcome.Accepted>(detached.lastOutcome, "${detached.lastOutcome}")
    val document = detached.document
    assertFalse(instance in document.nodes)
    val copy = document.nodes.getValue(detached.selection.single())
    assertEquals("layout/row", copy.componentId)
    val texts =
      copy.slots
        .getValue("children")
        .mapNotNull { document.nodes[it] }
        .filter { it.componentId == "m3/text" }
    assertEquals(
      listOf("Grace Hopper", "Subject"),
      texts.map { it.properties["text"]!!.jsonObject["value"].toString().trim('"') },
    )
    // The component stays, with the placement nobody detached.
    assertEquals(1, reducer.localComponents(detached).single().placements)
    val undone = reducer.reduce(detached, UiBuilderEditorEvent.Undo)
    assertEquals(edited.document.nodes, undone.document.nodes)
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
        slots = mapOf("children" to listOf("$nodeId-avatar", "$nodeId-sender", "$nodeId-subject")),
      )
    fun avatar(nodeId: String) =
      UiBuilderNode(
        id = nodeId,
        componentId = "m3/surface",
        properties =
          JsonObject(
            mapOf(
              "containerColor" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("color"), "value" to JsonPrimitive("#FF6750A4"))
                )
            )
          ),
        slots = mapOf("content" to emptyList()),
      )
    val nodes =
      listOf(
          UiBuilderNode(
            id = "inbox",
            componentId = "layout/column",
            slots = mapOf("children" to listOf("email-1", "email-2")),
          ),
          row("email-1"),
          avatar("email-1-avatar"),
          text("email-1-sender", "Sender"),
          text("email-1-subject", "Subject"),
          row("email-2"),
          avatar("email-2-avatar"),
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
