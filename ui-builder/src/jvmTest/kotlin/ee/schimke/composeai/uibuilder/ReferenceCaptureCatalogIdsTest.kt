package ee.schimke.composeai.uibuilder

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * What the *capture* draws for a component the canvas has no case for: a named placeholder, as on
 * the canvas ([UndrawnCatalogComponentTest]), so the capture's surface must be mounted inside the
 * provider that supplies the catalog's ids.
 *
 * Driven through `UiBuilderEditor`'s Component… menu, since the defect is in what the capture
 * inherits from where it's mounted. Asserted on the specimen while the capture is in flight: this
 * harness runs the `LaunchedEffect` before the layer is drawn, so `toImageBitmap` returns nothing
 * here.
 */
@OptIn(ExperimentalTestApi::class)
class ReferenceCaptureCatalogIdsTest {
  /** The packaged catalog with one more component on it, the way a published catalog adds them. */
  private val catalog =
    CapabilityCatalogParser.parse(
      withExtraComponent(resource("/m3-catalog-capabilities-v1.json"), UNDRAWN, DISPLAY_NAME)
    )

  @Test
  fun `the specimen a capture photographs draws the placeholder, not the error container`() =
    runDesktopComposeUiTest(width = 1600, height = 1000) {
      setContent {
        UiBuilderEditor(
          document = blankDocument(),
          catalog = catalog,
          // The markup controls are drawn once the reference has something on it, and the component
          // picker lives among them.
          initialEdits = listOf(UiBuilderEditorEvent.AttachReference(blankReference())),
          initialInspectorOpen = true,
          initialInspectorMode = EditorInspectorMode.Screen,
        )
      }

      // Through the semantics action rather than a tap: the panel scrolls and the menu is as long
      // as the catalog, and neither says anything about what the capture draws.
      onNodeWithContentDescription("Place a component")
        .performSemanticsAction(SemanticsActions.OnClick)
      val item = onNodeWithText(DISPLAY_NAME)
      item.fetchSemanticsNode()

      // Advance frame by frame: one frame after the pick, the specimen is composed.
      mainClock.autoAdvance = false
      item.performSemanticsAction(SemanticsActions.OnClick)
      mainClock.advanceTimeByFrame()

      // The placeholder writes the id's own words — `m3/badge` reads "badge". Nothing else in this
      // design says it: the document is one empty box and the palette is closed.
      onNodeWithText(PLACEHOLDER_TEXT).assertExists()
      // And the error container is not what is being photographed. This is the assertion that
      // fails before the fix, where the specimen drew "Unsupported component: m3/badge".
      onNodeWithText("Unsupported component", substring = true).assertDoesNotExist()
    }

  private fun blankDocument() =
    UiBuilderDocument(
      schema = "ui-builder/v1",
      id = "capture",
      title = "Capture",
      revision = 1,
      catalogPin = JsonObject(mapOf("catalogId" to JsonPrimitive("m3-catalog"))),
      environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(900),
            "heightDp" to JsonPrimitive(1400),
            "density" to JsonPrimitive(1.0),
            "theme" to JsonPrimitive("light"),
          )
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("root"),
      nodes = mapOf("root" to UiBuilderNode(id = "root", componentId = "layout/box")),
    )

  /** Something for the reference to hold, so the markup controls are drawn at all. */
  private fun blankReference() =
    ReferenceImage(
      id = "reference",
      name = "reference.png",
      mediaType = "image/png",
      base64 = ONE_TRANSPARENT_PIXEL,
      widthPx = 1,
      heightPx = 1,
    )

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private companion object {
    /**
     * A real published m3 component this renderer has no case for, not in a pack (packs are caught
     * by an earlier branch).
     */
    const val UNDRAWN = "m3/badge"

    /** What the picker calls it, and what the placeholder calls it. */
    const val DISPLAY_NAME = "Badge"

    const val PLACEHOLDER_TEXT = "badge"

    const val ONE_TRANSPARENT_PIXEL =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII="

    fun withExtraComponent(source: String, componentId: String, displayName: String): String {
      val root = Json.parseToJsonElement(source).jsonObject
      val added = buildJsonObject {
        put("componentId", componentId)
        put("displayName", displayName)
        put("role", "Leaf")
        put("traits", buildJsonArray {})
        put("slots", buildJsonArray {})
        put("properties", buildJsonArray {})
        put("modifierCapabilities", buildJsonArray { add(JsonPrimitive("padding")) })
        put(
          "wasm",
          buildJsonObject {
            put("platformSupported", false)
            put("adapterStatus", "unsupported")
          },
        )
      }
      val components = buildJsonArray {
        root.getValue("components").jsonArray.forEach(::add)
        add(added)
      }
      return Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(root + ("components" to components)),
      )
    }
  }
}
