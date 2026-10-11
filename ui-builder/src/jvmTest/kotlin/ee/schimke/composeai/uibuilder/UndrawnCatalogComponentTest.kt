package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
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
 * What the canvas draws for a component the catalog offers and it has no case for. The renderer's
 * `else` distinguishes:
 * - an id **no catalog offers** (stale import, wrong pin, typo): an error;
 * - an id **the catalog offers** that this canvas can't draw: a named placeholder, as the published
 *   shelf promises (`PublishedUiBuilderCatalog.wasm()`), like Wear and pack components.
 *
 * Asserted through `UiBuilderEditor`, since the defect was nothing supplying the catalog's ids to
 * the renderer.
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

      // Matched exactly: the layers panel and inspector show "Badge · m3/badge", but only the
      // canvas placeholder's whole text is "badge".
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
     * A real published m3 component this renderer has no case for, not in a pack (packs are caught
     * by an earlier branch).
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
