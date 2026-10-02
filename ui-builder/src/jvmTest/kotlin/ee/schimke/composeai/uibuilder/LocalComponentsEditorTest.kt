package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorLibraryPublishResult
import ee.schimke.composeai.uibuilder.editor.EditorLibrarySource
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The editor itself, with an inbox whose row has been made `InboxEmail` and placed three times: the
 * palette offers it on its "This design" shelf, and a selected placement is inspected as the
 * component it places. The screenshots it writes are the evidence a reviewer looks at.
 */
@OptIn(ExperimentalTestApi::class)
class LocalComponentsEditorTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )
  private val reducer = UiBuilderEditorReducer(catalog)

  @Test
  fun `the palette and the inspector show the design's own component`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      var state = reducer.initial(inbox(), "email-1")
      state = reducer.reduce(state, UiBuilderEditorEvent.MakeComponent("Inbox email"))
      assertIs<CommandOutcome.Accepted>(state.lastOutcome, "${state.lastOutcome}")
      // The row was laid out with placeholder content, which names the parameters; the first
      // placement then says what this inbox shows.
      state =
        reducer.reduce(
          state,
          UiBuilderEditorEvent.CommitProperty(state.selection.single(), "sender", "Ada Lovelace"),
        )
      state =
        reducer.reduce(
          state,
          UiBuilderEditorEvent.CommitProperty(
            state.selection.single(),
            "subject",
            "Notes on the engine",
          ),
        )
      val first = state.selection.single()
      // Two more, each saying something else — the reason to have made one.
      listOf("Grace Hopper" to "Compiler notes", "Alan Turing" to "On computable numbers")
        .forEach { (sender, subject) ->
          val target = checkNotNull(reducer.localComponentTarget(state, "inbox-email"))
          state =
            reducer.reduce(state, UiBuilderEditorEvent.InsertLocalComponent("inbox-email", target))
          val placed = state.selection.single()
          state =
            reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(placed, "sender", sender))
          val renamed = state.selection.single()
          state =
            reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(renamed, "subject", subject))
          assertIs<CommandOutcome.Accepted>(state.lastOutcome, "${state.lastOutcome}")
        }
      val document = state.document.copy(revision = state.document.revision)
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            initialSelectedNodeId = first,
            initialInspectorOpen = true,
            // The palette, where the component waits to be placed again.
            initialComponentsOpen = true,
          )
        }
      }
      capture("after")
      onAllNodesWithText("This design").onFirst().assertExists()
      onAllNodesWithText("InboxEmail", substring = true).onFirst().assertExists()
    }

  /**
   * The inspector of a placement on a host with a project library: the publish offer, then — once
   * the library answered with a version — where the library holds it and the update offer.
   */
  @Test
  fun `a placement's inspector offers to publish its component to the project library`() {
    var state = reducer.initial(inbox(), "email-1")
    state = reducer.reduce(state, UiBuilderEditorEvent.MakeComponent("Inbox email"))
    val placement = state.selection.single()
    publishCapture(state.document, placement, "publish-before")
    state =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.RecordLibrarySource(
          "inbox-email",
          EditorLibrarySource("m3-catalog", "inbox-email", "sha256:one"),
        ),
      )
    assertIs<CommandOutcome.Accepted>(state.lastOutcome, "${state.lastOutcome}")
    publishCapture(state.document, placement, "publish-after")
  }

  private fun publishCapture(document: UiBuilderDocument, selected: String, name: String) =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            initialSelectedNodeId = selected,
            initialInspectorOpen = true,
            initialComponentsOpen = true,
            publishLibraryComponent = { EditorLibraryPublishResult.Published("sha256:one") },
          )
        }
      }
      capture(name)
      onAllNodesWithText("library", substring = true).onFirst().assertExists()
    }

  @Test
  fun `before - the same row as plain layers`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            inbox(),
            catalog,
            initialSelectedNodeId = "email-1",
            initialInspectorOpen = true,
            initialComponentsOpen = true,
          )
        }
      }
      capture("before")
    }

  private fun ComposeUiTest.capture(name: String) {
    val root = File(System.getProperty("uiBuilderProjectDir"), "..")
    val screenshot = onRoot().captureToImage()
    File(root, "ui-builder/build/local-components-evidence/$name.png").apply {
      parentFile.mkdirs()
      writeBytes(
        checkNotNull(
            Image.makeFromBitmap(screenshot.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
          )
          .bytes
      )
    }
  }

  private fun inbox(): UiBuilderDocument {
    fun literal(value: String) =
      JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))
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
        properties =
          JsonObject(
            mapOf(
              "horizontalSpacingDp" to
                JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(12)))
            )
          ),
        slots = mapOf("children" to listOf("$id-avatar", "$id-sender", "$id-subject")),
      )
    // A coloured square, so the rows are visible in a capture whose canvas draws no glyphs.
    fun avatar(id: String) =
      UiBuilderNode(
        id = id,
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
        modifiers =
          kotlinx.serialization.json.JsonArray(
            listOf(
              JsonObject(
                mapOf(
                  "type" to JsonPrimitive("size"),
                  "widthDp" to JsonPrimitive(40),
                  "heightDp" to JsonPrimitive(40),
                )
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
            slots = mapOf("children" to listOf("email-1")),
          ),
          row("email-1"),
          avatar("email-1-avatar"),
          text("email-1-sender", "Sender"),
          text("email-1-subject", "Subject"),
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
}
