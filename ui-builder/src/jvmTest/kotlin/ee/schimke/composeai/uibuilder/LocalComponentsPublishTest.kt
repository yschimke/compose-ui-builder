package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorLibrarySource
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
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

/**
 * Publishing one of a design's components to the project library: what the editor sends, and what
 * it records once the library answers.
 *
 * What goes out is the file a project would commit — one component, its body as the only root — so
 * the server reads it with the reader it already has. What comes back is a digest, recorded as the
 * component's `source`, which turns the design's copy into a reference the drift report can follow.
 */
class LocalComponentsPublishTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )
  private val reducer = UiBuilderEditorReducer(catalog)

  private fun made(document: UiBuilderDocument = inbox()): UiBuilderEditorState {
    val made =
      reducer.reduce(
        reducer.initial(document, "email-1"),
        UiBuilderEditorEvent.MakeComponent("Inbox email"),
      )
    assertIs<CommandOutcome.Accepted>(made.lastOutcome, "${made.lastOutcome}")
    return made
  }

  @Test
  fun `a publication is the one-component document a project would commit`() {
    val made = made()

    val publication = assertNotNull(reducer.libraryPublication(made, "inbox-email"))

    assertEquals("m3-catalog", publication.system)
    assertEquals("inbox-email", publication.componentId)
    assertEquals("InboxEmail", publication.title)
    assertNull(publication.replacesDigest)
    val document = publication.document
    assertEquals(setOf("inbox-email"), document.components.keys)
    val root = document.roots.single()
    assertEquals(setOf(root, "email-1-sender", "email-1-subject"), document.nodes.keys)
    // Nothing of the screen around it: not the inbox, not the second row, not the placement.
    assertTrue("inbox" !in document.nodes && "email-2" !in document.nodes)
    // And the server takes it as its own document type.
    val wire = Json {
      ignoreUnknownKeys = true
    }
      .decodeFromString(
        DesignDocumentV1.serializer(),
        Json.encodeToString(UiBuilderDocument.serializer(), document),
      )
    assertEquals(root, wire.components.getValue("inbox-email").root)
    assertEquals("m3-catalog", wire.catalogPin.systemId)
  }

  @Test
  fun `a published component records where the library holds it, and updates name that version`() {
    val made = made()
    val source = EditorLibrarySource("m3-catalog", "inbox-email", "sha256:one")

    val recorded =
      reducer.reduce(made, UiBuilderEditorEvent.RecordLibrarySource("inbox-email", source))

    assertIs<CommandOutcome.Accepted>(recorded.lastOutcome, "${recorded.lastOutcome}")
    assertEquals(source, reducer.localComponents(recorded).single().source)
    val update = assertNotNull(reducer.libraryPublication(recorded, "inbox-email"))
    assertEquals("sha256:one", update.replacesDigest)
    // The record stays on the design's side: a published file carries no provenance of its own.
    val published = update.document.components["inbox-email"]!!.jsonObject
    assertTrue("source" !in published, published.toString())
    // One step back to a design-only component.
    val undone = reducer.reduce(recorded, UiBuilderEditorEvent.Undo)
    assertNull(reducer.localComponents(undone).single().source)
  }

  @Test
  fun `an imported component is published back under the id it came from`() {
    val made = made()
    val renamedKey =
      made.document.copy(
        components =
          JsonObject(
            mapOf(
              "my-row" to
                JsonObject(
                  made.document.components.getValue("inbox-email").jsonObject +
                    ("source" to
                      JsonObject(
                        mapOf(
                          "system" to JsonPrimitive("m3-catalog"),
                          "componentId" to JsonPrimitive("shared-row"),
                          "digest" to JsonPrimitive("sha256:shared"),
                        )
                      ))
                )
            )
          ),
        nodes =
          made.document.nodes.mapValues { (_, node) ->
            if (node.component == null) node
            else
              node.copy(
                component =
                  JsonObject(node.component!! + ("componentKey" to JsonPrimitive("my-row")))
              )
          },
      )

    val publication =
      assertNotNull(reducer.libraryPublication(reducer.initial(renamedKey, "inbox"), "my-row"))

    assertEquals("shared-row", publication.componentId)
    assertEquals("sha256:shared", publication.replacesDigest)
    assertEquals(setOf("shared-row"), publication.document.components.keys)
  }

  @Test
  fun `a component that places another is refused before it is sent`() {
    // The list made a component of its own needs a screen around it: a root is the whole screen.
    val screen =
      inbox().let { document ->
        document.copy(
          roots = listOf("screen"),
          nodes =
            document.nodes +
              ("screen" to
                UiBuilderNode(
                  id = "screen",
                  componentId = "layout/column",
                  slots = mapOf("children" to listOf("inbox")),
                )),
        )
      }
    val made = made(screen)
    val placement = made.selection.single()
    val outer =
      reducer.reduce(
        reducer.initial(made.document, "inbox"),
        UiBuilderEditorEvent.MakeComponent("Inbox list"),
      )
    assertIs<CommandOutcome.Accepted>(outer.lastOutcome, "${outer.lastOutcome}")
    val outerKey = outer.document.components.keys.single { it != "inbox-email" }
    assertTrue(placement in outer.document.nodes)

    assertNull(reducer.libraryPublication(outer, outerKey))
    val refusal = assertNotNull(reducer.libraryPublicationRefusal(outer, outerKey))
    assertTrue("InboxEmail" in refusal, refusal)
    assertNotNull(reducer.libraryPublication(outer, "inbox-email"))
  }

  private fun inbox(): UiBuilderDocument {
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
              "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
              "layoutDirection": "ltr", "windowPosture": "flat", "browserZoomPercent": 100,
              "fixedTime": "2024-05-16T12:00:00Z", "animations": "settled",
              "networkAccess": false
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
