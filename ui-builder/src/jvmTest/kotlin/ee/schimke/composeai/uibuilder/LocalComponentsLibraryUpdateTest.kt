package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.editor.EditorLibraryComponent
import ee.schimke.composeai.uibuilder.editor.EditorLibrarySymbol
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Taking the library's newer version of a component this design imported.
 *
 * Drift is reported and never redrawn on its own; this is the decision the report leaves to the
 * design's owner. The body is replaced, the recorded digest moves, and every placement stays where
 * it was with the content it was given — minus what the new body no longer reads.
 */
class LocalComponentsLibraryUpdateTest {
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

  private fun binding(key: String) =
    JsonObject(mapOf("type" to JsonPrimitive("binding"), "value" to JsonPrimitive(key)))

  private fun text(id: String, parameter: String) =
    UiBuilderNode(
      id = id,
      componentId = "m3/text",
      properties = JsonObject(mapOf("text" to binding(parameter))),
    )

  /** The first version: a row reading a sender and a subject. */
  private val versionOne =
    EditorLibrarySymbol(
      component = published,
      digest = "sha256:one",
      declaration =
        JsonObject(mapOf("name" to JsonPrimitive("InboxEmail"), "root" to JsonPrimitive("row"))),
      nodes =
        listOf(
            UiBuilderNode(
              id = "row",
              componentId = "layout/row",
              slots = mapOf("children" to listOf("sender", "subject")),
            ),
            text("sender", "sender"),
            text("subject", "subject"),
          )
          .associateBy { it.id },
    )

  /** The next: a column that keeps the sender, drops the subject and adds a snippet. */
  private val versionTwo =
    EditorLibrarySymbol(
      component = published,
      digest = "sha256:two",
      declaration =
        JsonObject(mapOf("name" to JsonPrimitive("InboxEmail"), "root" to JsonPrimitive("col"))),
      nodes =
        listOf(
            UiBuilderNode(
              id = "col",
              componentId = "layout/column",
              slots = mapOf("children" to listOf("from", "snippet")),
            ),
            text("from", "sender"),
            text("snippet", "snippet"),
          )
          .associateBy { it.id },
    )

  /** An inbox holding two placements of version one, the first one given its own sender. */
  private fun twoPlaced(): UiBuilderEditorState {
    var state = reducer.initial(inbox(), "inbox")
    repeat(2) {
      val target = assertNotNull(reducer.libraryComponentTarget(state, versionOne))
      state = reducer.reduce(state, UiBuilderEditorEvent.InsertLibraryComponent(versionOne, target))
      assertIs<CommandOutcome.Accepted>(state.lastOutcome, "${state.lastOutcome}")
    }
    val first = placements(state).first()
    state =
      reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(first.id, "sender", "Ada Lovelace"))
    assertIs<CommandOutcome.Accepted>(state.lastOutcome, "${state.lastOutcome}")
    return state
  }

  private fun placements(state: UiBuilderEditorState): List<UiBuilderNode> {
    val children = state.document.nodes.getValue("inbox").slots.getValue("children")
    return children.mapNotNull { state.document.nodes[it] }.filter { it.component != null }
  }

  private fun arguments(node: UiBuilderNode): Map<String, String> =
    node.component!!["arguments"]!!.jsonObject.mapValues { (_, value) ->
      value.jsonObject["value"]!!.jsonPrimitive.content
    }

  @Test
  fun `taking the newer version replaces the body and keeps every placement's content`() {
    val before = twoPlaced()
    val oldBody = reducer.localComponents(before).single().rootNodeId

    val updated =
      reducer.reduce(before, UiBuilderEditorEvent.UpdateLibraryComponent("inbox-email", versionTwo))

    assertIs<CommandOutcome.Accepted>(updated.lastOutcome, "${updated.lastOutcome}")
    val document = updated.document
    assertEquals(setOf("inbox-email"), document.components.keys)
    val declaration = document.components.getValue("inbox-email").jsonObject
    assertEquals("sha256:two", declaration["source"]!!.jsonObject["digest"]!!.jsonPrimitive.content)
    // The old body is gone, the new one is detached from the screen, and nothing else moved.
    assertFalse(oldBody in document.nodes, oldBody)
    val newRoot = declaration["root"]!!.jsonPrimitive.content
    assertEquals("layout/column", document.nodes.getValue(newRoot).componentId)
    assertEquals(listOf("inbox"), document.roots)
    assertEquals(listOf("sender", "snippet"), reducer.localComponents(updated).single().parameters)
    // Two placements still, in the same places: the sender each was given survives, the subject
    // the new body no longer reads is dropped, and the snippet starts as its own name.
    val placed = placements(updated)
    assertEquals(2, placed.size)
    assertEquals(mapOf("sender" to "Ada Lovelace", "snippet" to "Snippet"), arguments(placed[0]))
    assertEquals(mapOf("sender" to "Sender", "snippet" to "Snippet"), arguments(placed[1]))
    val source = CapabilityComposeCodeExporter.export(document, catalog).requireSource()
    assertTrue(source.contains("private fun InboxEmail(sender: String, snippet: String"), source)

    // One step back to the version it had.
    val undone = reducer.reduce(updated, UiBuilderEditorEvent.Undo)
    assertIs<CommandOutcome.Accepted>(undone.lastOutcome, "${undone.lastOutcome}")
    assertEquals(before.document.nodes, undone.document.nodes)
    assertEquals(before.document.components, undone.document.components)
  }

  @Test
  fun `the version the design already holds changes nothing`() {
    val before = twoPlaced()

    val same =
      reducer.reduce(before, UiBuilderEditorEvent.UpdateLibraryComponent("inbox-email", versionOne))

    assertSame(before.document, same.document)
  }

  @Test
  fun `a symbol the component was not imported from is refused`() {
    val before = twoPlaced()
    val other = versionTwo.copy(component = published.copy(componentId = "thread-row"))

    val refused =
      reducer.reduce(before, UiBuilderEditorEvent.UpdateLibraryComponent("inbox-email", other))

    assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertEquals(before.document, refused.document)
  }

  @Test
  fun `the inspector is told when the library holds a newer version`() {
    val before = twoPlaced()
    assertFalse(reducer.localComponents(before).single().newerInLibrary)

    val reported =
      reducer.reduce(
        before,
        UiBuilderEditorEvent.SetComponentDrift(
          listOf(
            ComponentDriftFinding(
              componentKey = "inbox-email",
              system = "gmail",
              componentId = "inbox-email",
              paletteId = "project/inbox-email",
              state = ComponentDriftState.DRIFTED,
              importedDigest = "sha256:one",
              currentDigest = "sha256:two",
            )
          )
        ),
      )

    assertTrue(reducer.localComponents(reported).single().newerInLibrary)
    // Taking it resolves the row: the finding no longer describes the design.
    val updated =
      reducer.reduce(
        reported,
        UiBuilderEditorEvent.UpdateLibraryComponent("inbox-email", versionTwo),
      )
    assertFalse(reducer.localComponents(updated).single().newerInLibrary)
  }

  private fun inbox(): UiBuilderDocument =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "inbox",
      title = "Inbox",
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
      nodes =
        mapOf(
          "inbox" to
            UiBuilderNode(
              id = "inbox",
              componentId = "layout/column",
              slots = mapOf("children" to emptyList()),
            )
        ),
    )
}
