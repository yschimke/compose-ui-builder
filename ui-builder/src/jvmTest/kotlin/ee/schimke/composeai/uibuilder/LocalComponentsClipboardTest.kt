package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.decodeEditorClipboard
import ee.schimke.composeai.uibuilder.editor.encodeEditorClipboard
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A component moving between designs, and a parameter renamed after the fact.
 *
 * Copying a placement carries its component — declaration and body — on the clipboard, so pasting
 * it into a design that has never seen `InboxEmail` brings `InboxEmail` with it. The clipboard also
 * goes out as text, which is how another tab, window or IDE reads it back.
 */
class LocalComponentsClipboardTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )
  private val reducer = UiBuilderEditorReducer(catalog)

  private fun made(): UiBuilderEditorState {
    val made =
      reducer.reduce(
        reducer.initial(inbox("inbox"), "email-1"),
        UiBuilderEditorEvent.MakeComponent("Inbox email"),
      )
    assertIs<CommandOutcome.Accepted>(made.lastOutcome, "${made.lastOutcome}")
    return made
  }

  @Test
  fun `a placement copied and pasted within a design places the same component again`() {
    val made = made()
    val placement = made.selection.single()

    val copied = reducer.reduce(made, UiBuilderEditorEvent.CopySelected)
    assertEquals(setOf("inbox-email"), copied.clipboard?.components?.keys)
    val pasted = reducer.reduce(copied, UiBuilderEditorEvent.Paste)

    assertIs<CommandOutcome.Accepted>(pasted.lastOutcome, "${pasted.lastOutcome}")
    assertEquals(setOf("inbox-email"), pasted.document.components.keys)
    val children = pasted.document.nodes.getValue("inbox").slots.getValue("children")
    assertEquals(3, children.size)
    assertEquals(placement, children.first())
    assertEquals(2, reducer.localComponents(pasted).single().placements)
  }

  @Test
  fun `a placement pasted into another design brings its component`() {
    val clipboard =
      assertNotNull(reducer.reduce(made(), UiBuilderEditorEvent.CopySelected).clipboard)
    // Through the text the system clipboard carries, as another tab or IDE would read it.
    val arrived = assertNotNull(decodeEditorClipboard(encodeEditorClipboard(clipboard)))
    val other =
      reducer.reduce(
        reducer.initial(inbox("other"), "email-2"),
        UiBuilderEditorEvent.ReceiveClipboard(arrived),
      )

    val pasted = reducer.reduce(other, UiBuilderEditorEvent.Paste)

    assertIs<CommandOutcome.Accepted>(pasted.lastOutcome, "${pasted.lastOutcome}")
    val document = pasted.document
    assertEquals(listOf("inbox"), document.roots)
    val declaration = assertNotNull(document.components["inbox-email"]?.jsonObject)
    assertEquals("InboxEmail", declaration["name"]?.jsonPrimitive?.content)
    // A fresh body, not the source design's ids, detached from the screen.
    val root = declaration["root"]!!.jsonPrimitive.content
    assertTrue(root in document.nodes && root != "email-1", root)
    assertTrue(root !in document.nodes.values.flatMap { it.slots.values.flatten() })
    val source = CapabilityComposeCodeExporter.export(document, catalog).requireSource()
    assertTrue(source.contains("private fun InboxEmail(sender: String, subject: String"), source)
  }

  @Test
  fun `a different component under the same key is imported beside it`() {
    val clipboard =
      assertNotNull(reducer.reduce(made(), UiBuilderEditorEvent.CopySelected).clipboard)
    // Another design that already defines `inbox-email` — as something else entirely.
    val busy =
      reducer.reduce(
        reducer.initial(inbox("busy"), "email-2"),
        UiBuilderEditorEvent.MakeComponent("Thread row"),
      )
    val clashing =
      busy.document.components.getValue("thread-row").let { declaration ->
        busy.document.copy(
          components = JsonObject(mapOf("inbox-email" to declaration)),
          nodes =
            busy.document.nodes.mapValues { (_, node) ->
              if (node.component == null) node
              else
                node.copy(
                  component =
                    JsonObject(node.component!! + ("componentKey" to JsonPrimitive("inbox-email")))
                )
            },
        )
      }
    val target =
      reducer.reduce(
        reducer.initial(clashing, "email-1"),
        UiBuilderEditorEvent.ReceiveClipboard(clipboard),
      )

    val pasted = reducer.reduce(target, UiBuilderEditorEvent.Paste)

    assertIs<CommandOutcome.Accepted>(pasted.lastOutcome, "${pasted.lastOutcome}")
    assertEquals(setOf("inbox-email", "inbox-email-2"), pasted.document.components.keys)
    val placed = pasted.document.nodes.getValue(pasted.selection.single())
    assertEquals("inbox-email-2", placed.component?.get("componentKey")?.jsonPrimitive?.content)
  }

  @Test
  fun `text that is not an editor clipboard is not one`() {
    assertNull(decodeEditorClipboard("hello"))
    assertNull(decodeEditorClipboard("""{"format":"something-else","roots":["a"],"nodes":{}}"""))
    assertNull(
      decodeEditorClipboard(
        """{"format":"compose-ui-builder-clipboard/v1","roots":["missing"],"nodes":{}}"""
      )
    )
  }

  @Test
  fun `renaming a parameter renames the body's read and every placement's argument`() {
    val made = made()
    val target = assertNotNull(reducer.localComponentTarget(made, "inbox-email"))
    val two = reducer.reduce(made, UiBuilderEditorEvent.InsertLocalComponent("inbox-email", target))

    val renamed =
      reducer.reduce(
        two,
        UiBuilderEditorEvent.RenameComponentParameter("inbox-email", "sender", "author"),
      )

    assertIs<CommandOutcome.Accepted>(renamed.lastOutcome, "${renamed.lastOutcome}")
    assertEquals(listOf("author", "subject"), reducer.localComponents(renamed).single().parameters)
    val placements =
      renamed.document.nodes.values.filter { it.component?.get("componentKey") != null }
    assertEquals(2, placements.size)
    placements.forEach { placement ->
      val arguments = placement.component!!["arguments"]!!.jsonObject
      assertEquals(setOf("author", "subject"), arguments.keys, placement.id)
    }
    val source = CapabilityComposeCodeExporter.export(renamed.document, catalog).requireSource()
    assertTrue(source.contains("InboxEmail(author = \"Sender\""), source)
    // One step back to where it was.
    val undone = reducer.reduce(renamed, UiBuilderEditorEvent.Undo)
    assertIs<CommandOutcome.Accepted>(undone.lastOutcome, "${undone.lastOutcome}")
    assertEquals(two.document.nodes, undone.document.nodes)
  }

  @Test
  fun `a parameter name the export could not write is refused`() {
    val made = made()
    for (name in listOf("modifier", "subject", "Author", "fun", "argument0", " ")) {
      val refused =
        reducer.reduce(
          made,
          UiBuilderEditorEvent.RenameComponentParameter("inbox-email", "sender", name),
        )
      assertIs<CommandOutcome.Rejected>(refused.lastOutcome, name)
    }
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
