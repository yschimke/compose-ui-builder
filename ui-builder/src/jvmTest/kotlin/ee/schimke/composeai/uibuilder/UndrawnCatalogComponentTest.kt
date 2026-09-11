package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
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
 * What the canvas draws for a component the catalog offers and it has no case for.
 *
 * `UiBuilderRenderer` dispatches on `when (componentId)` over ids compiled into this module, and
 * everything else fell to one `else` that drew an error container reading "Unsupported component".
 * That branch was answering two questions with one sentence:
 * - an id **no catalog offers** — a stale import, the wrong pin, a typo. Something is wrong.
 * - an id **the catalog offers** and this canvas has no case for. Nothing is wrong: the component
 *   is on the palette, it exports, and its own catalog renders it.
 *
 * The second is the whole of m3-catalog#324's other half. A published m3 catalog serves 108 `m3/`
 * components and this renderer has a case for twenty-five, so **eighty-three of them could be
 * inserted from the palette and drew a red error box** — while the shelf that served them published
 * the note "drawn on the canvas as a named placeholder" for every one
 * (`PublishedUiBuilderCatalog.wasm()`). The canvas now keeps that promise, with the placeholder the
 * Wear and pack components already use.
 *
 * Asserted through `UiBuilderEditor` rather than by providing the composition local directly: the
 * defect was that nothing supplied the catalog's ids to the renderer, so a test that supplies them
 * itself would pass against the broken build.
 */
@OptIn(ExperimentalTestApi::class)
class UndrawnCatalogComponentTest {
  /** The packaged catalog with one more component on it, the way a published catalog adds them. */
  private val catalog =
    CapabilityCatalogParser.parse(
      withExtraComponent(resource("/m3-catalog-capabilities-v1.json"), UNDRAWN, "Badge")
    )

  @Test
  fun `a component the catalog offers and the canvas cannot draw is named, not called an error`() =
    runEditor {
      setContent { UiBuilderEditor(document = documentPlacing(UNDRAWN), catalog = catalog) }

      // The placeholder writes the id's own words, and only those — `m3/badge` reads "badge".
      // Matched exactly rather than as a substring: the layers panel and the inspector both carry
      // "Badge · m3/badge", so a substring match finds three nodes and proves nothing about which
      // surface drew what. The one node whose whole text is the id's tail is the canvas's.
      onNodeWithText("badge").assertExists()
      // And the error container is gone. This is the assertion that fails before the fix: the
      // node drew "Unsupported component: m3/badge" and nothing else.
      onNodeWithText("Unsupported component", substring = true).assertDoesNotExist()
    }

  @Test
  fun `an id no catalog offers is still an error, which is the only thing that sentence now says`() =
    runEditor {
      setContent { UiBuilderEditor(document = documentPlacing(ABSENT), catalog = catalog) }

      onNodeWithText("Unsupported component", substring = true).assertExists()
    }

  private fun documentPlacing(componentId: String) =
    UiBuilderDocument(
      schema = "ui-builder/v1",
      id = "undrawn",
      title = "Undrawn",
      revision = 1,
      catalogPin = JsonObject(mapOf("catalogId" to JsonPrimitive("m3-catalog"))),
      environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(900),
            "heightDp" to JsonPrimitive(1400),
            "density" to JsonPrimitive(2.0),
            "theme" to JsonPrimitive("light"),
          )
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("subject"),
      nodes = mapOf("subject" to UiBuilderNode(id = "subject", componentId = componentId)),
    )

  private fun runEditor(block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
    runDesktopComposeUiTest(width = 1600, height = 900) { block() }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private companion object {
    /**
     * A real component of the published m3 shelf that this renderer has no case for — one of the
     * eighty-three. Not in a pack, deliberately: a pack's ids are caught by an earlier branch, and
     * the gap this is about is a component the catalog adds directly.
     */
    const val UNDRAWN = "m3/badge"

    /** An id nothing declares. */
    const val ABSENT = "m3/not-a-component"

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
