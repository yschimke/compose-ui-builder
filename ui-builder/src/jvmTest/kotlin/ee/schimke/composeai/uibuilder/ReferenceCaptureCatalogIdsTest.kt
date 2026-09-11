package ee.schimke.composeai.uibuilder

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
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
 * What the *capture* draws for a component the canvas has no case for.
 *
 * [UndrawnCatalogComponentTest] is this test's neighbour and covers the canvas: a component the
 * catalog offers and this renderer cannot draw is a named placeholder, not an error. The capture
 * that photographs a component for the reference layer composes a `UiBuilderSurface` of its own,
 * and it was mounted *beside* the provider that hands the canvas the catalog's ids rather than
 * inside it — so the specimen composed with both sets empty, every such component fell to the error
 * branch, and placing one on the reference photographed "Unsupported component: …" while the canvas
 * beside it drew the placeholder for the same id in the same session.
 *
 * Driven through `UiBuilderEditor`'s own Component… menu rather than by composing
 * [ReferenceComponentCapture] directly, for the reason its neighbour gives: the defect is in what
 * the capture's surface inherits from where it is mounted, so a test that mounts it itself would
 * pass against the broken build.
 *
 * Asserted on the specimen *while the capture is in flight* rather than on the pixels it produces.
 * The capture reads its graphics layer from a `LaunchedEffect`, which this harness runs before the
 * frame that records the layer has been drawn — so `toImageBitmap` has nothing to hand back here,
 * whatever the specimen composed. The frame the specimen is composed in is the thing under test
 * anyway: what that surface draws is exactly what the photograph would be of.
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

      // Frame by frame from here: the capture composes its specimen, reads its layer and takes
      // itself back out of the tree, and the composed specimen is what this is about. One frame
      // after the pick is the frame it is in.
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
     * A real component of the published m3 shelf that this renderer has no case for — one of the
     * eighty-three. Not in a pack, deliberately: a pack's ids are caught by an earlier branch, and
     * the gap this is about is a component the catalog adds directly.
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
