package ee.schimke.composeai.uibuilder

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import ee.schimke.composeai.uibuilder.renderer.sdk.canvasAdapterRegistry
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The canvas dispatch is keyed on the adapter id the catalog names, not on the component id
 * (`UI_BUILDER_CATALOG_CONTRACT.md` item 17), so a catalog can point a new component at a drawing
 * this build already has; an unknown adapter draws a placeholder.
 *
 * No catalog populates `components[].wasm.canvas` yet, so the map is supplied directly here; the
 * dispatch is what's under test.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasAdapterIdTest {
  private fun document(componentId: String, label: String) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "adapter-id",
      title = "Adapter id",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("node"),
      nodes =
        mapOf(
          "node" to
            UiBuilderNode(
              id = "node",
              componentId = componentId,
              // The wrapper shape every property takes on the wire: a kind and a value.
              properties =
                JsonObject(
                  mapOf(
                    "text" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("string"),
                          "value" to JsonPrimitive(label),
                        )
                      )
                  )
                ),
            )
        ),
    )

  @Test
  fun `a component draws through the adapter its catalog names`() =
    runDesktopComposeUiTest(width = 400, height = 400) {
      // An id this canvas has no case for, pointed at one it does. Nothing in this repository
      // knows `acme/headline` exists; the catalog says "draw it with the text adapter".
      setContent {
        UiBuilderSurface(
          document("acme/headline", "Routed"),
          catalogComponentIds = setOf("acme/headline"),
          canvasAdapterIds = mapOf("acme/headline" to "m3/text"),
        )
      }

      assertTrue(
        onAllNodesWithText("Routed", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        "the node did not draw through the adapter the catalog named — it should have been drawn " +
          "as m3/text and shown its text",
      )
    }

  @Test
  fun `a catalog runtime draws through its executable registry`() =
    runDesktopComposeUiTest(width = 400, height = 400) {
      val registry = canvasAdapterRegistry {
        register("acme/text") { Text(string("text"), modifier = modifier) }
      }
      setContent {
        UiBuilderSurface(
          document("acme/headline", "Catalog owned"),
          catalogComponentIds = setOf("acme/headline"),
          canvasAdapterIds = mapOf("acme/headline" to "acme/text"),
          canvasAdapterRegistry = registry,
        )
      }

      assertTrue(
        onAllNodesWithText("Catalog owned", useUnmergedTree = true)
          .fetchSemanticsNodes()
          .isNotEmpty(),
        "the generic interpreter did not invoke the catalog-owned adapter",
      )
    }

  @Test
  fun `a catalog can normalize a property only for its canvas adapter`() =
    runDesktopComposeUiTest(width = 400, height = 400) {
      val source =
        document("acme/headline", "ignored").let { document ->
          document.copy(
            nodes =
              document.nodes.mapValues { (_, node) ->
                node.copy(
                  properties =
                    JsonObject(
                      mapOf(
                        "remoteText" to
                          JsonObject(
                            mapOf(
                              "type" to JsonPrimitive("string"),
                              "value" to JsonPrimitive("Mapped"),
                            )
                          )
                      )
                    )
                )
              }
          )
        }
      val mapping =
        CanvasAdapterMappingV1.Builder()
          .also { it.properties = mapOf("text" to "remoteText") }
          .build()
      setContent {
        UiBuilderSurface(
          source,
          catalogComponentIds = setOf("acme/headline"),
          canvasAdapterIds = mapOf("acme/headline" to "m3/text"),
          canvasAdapterMappings = mapOf("acme/headline" to mapping),
        )
      }

      assertTrue(
        onAllNodesWithText("Mapped", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
      )
    }

  @Test
  fun `an adapter this build lacks draws the placeholder, not the error`() =
    runDesktopComposeUiTest(width = 400, height = 400) {
      // The catalog offers the component and names an adapter this build doesn't ship (a newer
      // catalog against an older server).
      setContent {
        UiBuilderSurface(
          document("acme/headline", "Routed"),
          catalogComponentIds = setOf("acme/headline"),
          canvasAdapterIds = mapOf("acme/headline" to "adapter/this-build-has-never-heard-of"),
        )
      }

      // The placeholder labels itself from the component id, not the adapter id: the author is
      // owed the name of the thing they placed.
      assertTrue(
        onAllNodesWithText("headline", substring = true, useUnmergedTree = true)
          .fetchSemanticsNodes()
          .isNotEmpty(),
        "expected the named placeholder for a component whose adapter this build lacks",
      )
      // The membership test must use component ids, not adapter ids, or an offered, exportable
      // component would draw as an error.
      assertTrue(
        onAllNodesWithText("Unsupported component", substring = true, useUnmergedTree = true)
          .fetchSemanticsNodes()
          .isEmpty(),
        "a component the catalog offers must never draw the error container",
      )
    }

  @Test
  fun `a component the catalog does not offer still draws the error`() =
    runDesktopComposeUiTest(width = 400, height = 400) {
      // The other half of the same `else`, kept honest: an id from nowhere is still wrong, and
      // must not be softened into a placeholder by the restructuring above.
      setContent {
        UiBuilderSurface(
          document("acme/not-in-any-catalog", "Routed"),
          catalogComponentIds = emptySet(),
        )
      }

      assertTrue(
        onAllNodesWithText("Unsupported component", substring = true, useUnmergedTree = true)
          .fetchSemanticsNodes()
          .isNotEmpty(),
        "an id no catalog offers must still say so",
      )
    }

  @Test
  fun `with no mapping a component keys on its own id`() =
    runDesktopComposeUiTest(width = 400, height = 400) {
      // Today's state for every catalog, asserted so the no-op stays a no-op.
      setContent { UiBuilderSurface(document("m3/text", "Unmapped")) }

      assertTrue(
        onAllNodesWithText("Unmapped", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        "an empty adapter map must leave the component keying on its own id",
      )
    }
}
