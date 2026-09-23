package ee.schimke.composeai.uibuilder

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import ee.schimke.composeai.uibuilder.renderer.sdk.canvasAdapterRegistry
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The canvas dispatch is keyed on the adapter id the catalog names, not on the component id.
 *
 * ## What this buys, and why it was the coupling worth breaking
 *
 * `UI_BUILDER_CATALOG_CONTRACT.md` item 17: *"The adapter registry is the existing `when`, keyed by
 * adapter id instead of component id, and an adapter this build lacks draws a placeholder and
 * logs."*
 *
 * Keyed on the component id, every id this canvas could draw had to be written into this repository
 * — which is why it held a hand-written copy of a catalog's inventory at all, and why adding a
 * component here meant a release of a repository that has never compiled that component. Keyed on
 * an adapter id, a catalog can point a new component at a drawing this build already has, and this
 * repository needs to know nothing about it.
 *
 * ## Why this reads as a no-op today
 *
 * No catalog populates `components[].wasm.canvas` yet — the published Wear catalog carries the
 * field and leaves it null on all 78 — so the map is empty, every component keys on its own id, and
 * nothing about the current picture changes. That is deliberate: the consumer half lands first, and
 * these tests are what prove it works before there is anything to read.
 *
 * So the map is supplied directly here rather than through a catalog. The point under test is the
 * dispatch, not the plumbing that fills it.
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
      // The case item 17 calls out. The catalog offers the component and names an adapter this
      // build does not ship — a newer catalog against an older server, which is the normal state
      // of affairs once catalogs ship independently.
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
      // This is the regression the `else` was restructured to prevent. Keyed on the adapter id,
      // the membership test would compare `adapter/…` against a set of COMPONENT ids, miss, and
      // draw the error container — telling the author their component is unsupported when it is
      // on the palette and exports perfectly well.
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
