package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CodeCapability
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A screen like Gmail's inbox, built once as rows of text, and the row turned into `InboxEmail`.
 *
 * The flow under test is the one a designer takes: build one row, make it a component, place it
 * again with different content, and find it in the palette and as its own composable in the code —
 * then, once the app ships its own `InboxEmail`, swap the design's copy for it.
 */
class LocalComponentsTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)

  @Test
  fun `making a component leaves the screen drawing what it drew`() {
    val made = reducer.reduce(reducer.initial(inbox(), "email-1"), MAKE)

    assertIs<CommandOutcome.Accepted>(made.lastOutcome, "${made.lastOutcome}")
    val document = made.document
    val declaration = document.components["inbox-email"]?.jsonObject
    assertEquals("InboxEmail", declaration?.get("name")?.jsonPrimitive?.content)
    assertEquals("email-1", declaration?.get("root")?.jsonPrimitive?.content)
    // Still one screen: the body left the column and is a definition, not a second root.
    assertEquals(listOf("inbox"), document.roots)
    val placementId = made.selection.single()
    assertEquals(listOf(placementId, "email-2"), document.nodes.getValue("inbox").slots["children"])
    // Each text the row showed is now a parameter, named after what it said, and the placement
    // passes what it said — so the canvas draws the same row.
    assertEquals(binding("sender"), document.nodes.getValue("email-1-sender").properties["text"])
    assertEquals(binding("subject"), document.nodes.getValue("email-1-subject").properties["text"])
    val arguments = document.nodes.getValue(placementId).component?.get("arguments")?.jsonObject
    assertEquals(literal("Sender"), arguments?.get("sender"))
    assertEquals(literal("Subject"), arguments?.get("subject"))
  }

  @Test
  fun `one undo takes the component back out`() {
    val start = reducer.initial(inbox(), "email-1")
    val undone = reducer.reduce(reducer.reduce(start, MAKE), UiBuilderEditorEvent.Undo)

    assertIs<CommandOutcome.Accepted>(undone.lastOutcome, "${undone.lastOutcome}")
    assertEquals(start.document.roots, undone.document.roots)
    assertEquals(start.document.nodes, undone.document.nodes)
    assertEquals(start.document.components, undone.document.components)
  }

  @Test
  fun `the palette lists it and a second placement says something else`() {
    val made = reducer.reduce(reducer.initial(inbox(), "email-1"), MAKE)
    val first = made.selection.single()

    val listed = reducer.localComponents(made).single()
    assertEquals("InboxEmail", listed.name)
    assertEquals(listOf("sender", "subject"), listed.parameters)
    assertEquals(1, listed.placements)

    // With the placement selected, the palette's Add lands beside it, passing what it passes.
    val target = assertNotNull(reducer.localComponentTarget(made, "inbox-email"))
    val placed =
      reducer.reduce(
        made,
        UiBuilderEditorEvent.InsertLocalComponent("inbox-email", target, afterNodeId = first),
      )
    assertIs<CommandOutcome.Accepted>(placed.lastOutcome, "${placed.lastOutcome}")
    val second = placed.selection.single()
    assertEquals(
      listOf(first, second, "email-2"),
      placed.document.nodes.getValue("inbox").slots["children"],
    )
    assertEquals(2, reducer.localComponents(placed).single().placements)

    // The inspector edits a placement's arguments as fields, and committing one is an edit to that
    // placement alone.
    val fields = reducer.propertyFields(placed)
    assertEquals(listOf("sender", "subject"), fields.map { it.name })
    assertEquals("Sender", fields.first().value)
    val edited =
      reducer.reduce(placed, UiBuilderEditorEvent.CommitProperty(second, "sender", "Grace Hopper"))
    assertIs<CommandOutcome.Accepted>(edited.lastOutcome, "${edited.lastOutcome}")
    val replacement = edited.selection.single()
    assertEquals(
      literal("Grace Hopper"),
      edited.document.nodes
        .getValue(replacement)
        .component
        ?.get("arguments")
        ?.jsonObject
        ?.get("sender"),
    )
    assertEquals(
      literal("Sender"),
      edited.document.nodes.getValue(first).component?.get("arguments")?.jsonObject?.get("sender"),
    )
    assertEquals(
      listOf(first, replacement, "email-2"),
      edited.document.nodes.getValue("inbox").slots["children"],
    )
    assertEquals("Grace Hopper", reducer.propertyFields(edited).first().value)
  }

  @Test
  fun `the body's own text is the parameter, not an editable literal`() {
    val made = reducer.reduce(reducer.initial(inbox(), "email-1"), MAKE)
    val bodyText = made.copy(selection = listOf("email-1-sender"))

    val field = reducer.propertyFields(bodyText).first { it.name == "text" }
    assertTrue(field.supporting.orEmpty().contains("sender"), "${field.supporting}")
    val refused =
      reducer.reduce(bodyText, UiBuilderEditorEvent.CommitProperty("email-1-sender", "text", "x"))
    assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
  }

  @Test
  fun `the export writes the component as its own composable`() {
    val made = reducer.reduce(reducer.initial(inbox(), "email-1"), MAKE)
    val target = assertNotNull(reducer.localComponentTarget(made, "inbox-email"))
    val placed =
      reducer.reduce(made, UiBuilderEditorEvent.InsertLocalComponent("inbox-email", target))

    val source = CapabilityComposeCodeExporter.export(placed.document, catalog).requireSource()
    assertTrue(source.contains("private fun InboxEmail(sender: String, subject: String"), source)
    assertEquals(2, Regex("InboxEmail\\(sender = \"Sender\"").findAll(source).count(), source)
    // The code pane's own lane — the generator the server's export uses — as well.
    val pane = reducer.generatedCode(placed.document)
    val kotlin = assertIs<EditorGeneratedCode.Source>(pane, "$pane").kotlin
    assertTrue(kotlin.contains("fun InboxEmail("), kotlin)
    assertEquals(2, Regex("InboxEmail\\(").findAll(kotlin).count() - 1, kotlin)
  }

  @Test
  fun `a renamed component exports under its new name`() {
    val made = reducer.reduce(reducer.initial(inbox(), "email-1"), MAKE)

    val renamed =
      reducer.reduce(made, UiBuilderEditorEvent.RenameLocalComponent("inbox-email", "thread row"))

    assertIs<CommandOutcome.Accepted>(renamed.lastOutcome, "${renamed.lastOutcome}")
    assertEquals("ThreadRow", reducer.localComponents(renamed).single().name)
    val refused =
      reducer.reduce(made, UiBuilderEditorEvent.RenameLocalComponent("inbox-email", "1st"))
    assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
  }

  @Test
  fun `a body that handles an event is refused before anything changes`() {
    val clickable =
      inbox().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("email-1" to
                base.nodes
                  .getValue("email-1")
                  .copy(
                    eventBindings =
                      JsonObject(mapOf("click" to JsonArray(listOf(JsonObject(mapOf())))))
                  ))
        )
      }
    val state = reducer.initial(clickable, "email-1")

    assertNotNull(reducer.makeComponentRefusal(state))
    val refused = reducer.reduce(state, MAKE)
    assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertEquals(clickable.nodes, refused.document.nodes)
  }

  @Test
  fun `the root cannot be a component, and nothing selected names why`() {
    assertNotNull(reducer.makeComponentRefusal(reducer.initial(inbox(), "inbox")))
    assertNotNull(reducer.makeComponentRefusal(reducer.initial(inbox())))
    assertNull(reducer.makeComponentRefusal(reducer.initial(inbox(), "email-2")))
  }

  /**
   * The graduation step: the app's catalog now ships `InboxEmail`, so every placement becomes a
   * call to it and the design's own copy goes.
   */
  @Test
  fun `a component the catalog now ships replaces every placement`() {
    val shipped =
      catalog.components
        .first { it.componentId == "m3/card" }
        .copy(
          componentId = "gmail/inbox-email",
          displayName = "Inbox email",
          slots = emptyList(),
          properties =
            listOf(
              PropertyCapability("sender", JsonPrimitive("string")),
              PropertyCapability("subject", JsonPrimitive("string")),
            ),
          code = CodeCapability("com.example.gmail.InboxEmail"),
        )
    val graduated = catalog.copy(components = catalog.components + shipped)
    val graduatedReducer = UiBuilderEditorReducer(graduated)
    val made = graduatedReducer.reduce(graduatedReducer.initial(inbox(), "email-1"), MAKE)
    assertEquals(
      "gmail/inbox-email",
      graduatedReducer.localComponents(made).single().publishedAs?.componentId,
    )

    val replaced =
      graduatedReducer.reduce(
        made,
        UiBuilderEditorEvent.ReplaceLocalComponent("inbox-email", "gmail/inbox-email"),
      )

    assertIs<CommandOutcome.Accepted>(replaced.lastOutcome, "${replaced.lastOutcome}")
    val document = replaced.document
    assertTrue(document.components.isEmpty())
    assertFalse("email-1" in document.nodes)
    assertEquals(listOf("inbox"), document.roots)
    val call = document.nodes.getValue(replaced.selection.single())
    assertEquals("gmail/inbox-email", call.componentId)
    assertEquals(literal("Sender"), call.properties["sender"])
    assertEquals(literal("Subject"), call.properties["subject"])
    assertEquals(
      listOf(call.id, "email-2"),
      document.nodes.getValue("inbox").slots["children"],
    )
    // And it undoes in one step, back to the design's own component.
    val undone = graduatedReducer.reduce(replaced, UiBuilderEditorEvent.Undo)
    assertIs<CommandOutcome.Accepted>(undone.lastOutcome, "${undone.lastOutcome}")
    assertEquals(made.document.components, undone.document.components)
  }

  private fun inbox(): UiBuilderDocument {
    fun text(id: String, value: String) =
      UiBuilderNode(
        id = id,
        componentId = "m3/text",
        properties = JsonObject(mapOf("text" to literal(value))),
      )
    fun row(id: String) =
      UiBuilderNode(
        id = id,
        componentId = "layout/row",
        slots = mapOf("children" to listOf("$id-sender", "$id-subject")),
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
      nodes = nodes,
    )
  }

  private fun literal(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))

  private fun binding(key: String) =
    JsonObject(mapOf("type" to JsonPrimitive("binding"), "value" to JsonPrimitive(key)))

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private companion object {
    val MAKE = UiBuilderEditorEvent.MakeComponent("Inbox email")
  }
}
