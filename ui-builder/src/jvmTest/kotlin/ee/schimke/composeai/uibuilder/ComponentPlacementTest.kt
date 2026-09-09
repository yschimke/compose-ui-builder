package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * One body, placed four times, drawn four ways.
 *
 * The document could only say a repeated thing by repeating its nodes, so four cells were four
 * subtrees and a change to what a cell *is* had to be made four times. A component is the other
 * half: the body lives once in the ordinary `nodes` map — every reducer, validator and renderer
 * path below the placement is the one a design's own nodes take — and what each placement adds is a
 * scope. The arguments it passes are the dictionary the body reads by key, and the path segment it
 * opens is what makes the same body under two instances two boxes rather than one.
 *
 * Asserted in pixels rather than in structure, because the claim is about what a person sees: four
 * cells, four colours, from one authored cell.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ComponentPlacementTest {

  private val shades =
    listOf(Color(0xFFEBEDF0), Color(0xFF9BE9A8), Color(0xFF40C463), Color(0xFF216E39))

  @Test
  fun `each placement draws the shared body with its own arguments`() {
    val image = renderComposeScene(FRAME_PX, FRAME_PX, Density(1f)) { UiBuilderSurface(document()) }
    val pixels = image.toComposeImageBitmap().toPixelMap()

    val drawn = shades.indices.map { pixels[cellCentreX(it), CELL_CENTRE_Y] }

    assertEquals(shades, drawn)
  }

  /**
   * A component that places itself draws nothing rather than taking the composition down — the rule
   * every other reference in this renderer follows, because a canvas that crashes cannot draw the
   * Issues panel that would explain why.
   */
  @Test
  fun `a component that places itself is refused rather than fatal`() {
    val recursive =
      document().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("cell-body" to
                base.nodes.getValue("cell-body").copy(slots = mapOf("content" to listOf("cell-0"))))
        )
      }

    val image = renderComposeScene(FRAME_PX, FRAME_PX, Density(1f)) { UiBuilderSurface(recursive) }

    // The outermost placement still draws its own cell; the recursion below it stops.
    assertTrue(
      image.toComposeImageBitmap().toPixelMap()[cellCentreX(0), CELL_CENTRE_Y] == shades[0]
    )
  }

  /** A placement naming a component the design does not define says so, and draws on. */
  @Test
  fun `an unknown component key is a diagnostic, not a crash`() {
    val unknown = document().let { base -> base.copy(components = JsonObject(emptyMap())) }

    renderComposeScene(FRAME_PX, FRAME_PX, Density(1f)) { UiBuilderSurface(unknown) }
  }

  /**
   * A component body is reachable through the component that owns it, not through a slot.
   *
   * Counted as a graph entry point beside the document's own root, so validation neither reports
   * every body as unreachable nor — once a second design places the same component — as referenced
   * twice. Export itself still refuses a placement, and says why: the catalog declares no
   * `design/component-instance`, and until it does there is nothing faithful to emit.
   */
  @Test
  fun `a component body is reachable, and export refuses the placement by name`() {
    val catalog =
      CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )

    val result = CapabilityComposeCodeExporter.export(document(), catalog)

    assertTrue(result.diagnostics.none { it.code == "UNREACHABLE_NODE" }, "${result.diagnostics}")
    assertTrue(
      result.diagnostics.any {
        it.code == "UNKNOWN_COMPONENT" && it.message.contains("design/component-instance")
      },
      "${result.diagnostics}",
    )
  }

  private fun cellCentreX(index: Int) = index * CELL_PX + CELL_PX / 2

  /** A row of four placements of one `contribution-cell`, each passing its own shade. */
  private fun document(): UiBuilderDocument {
    val cellIds = shades.indices.map { "cell-$it" }
    val nodes =
      buildMap<String, UiBuilderNode> {
        put(
          "root",
          UiBuilderNode(
            id = "root",
            componentId = "layout/row",
            slots = mapOf("children" to cellIds),
          ),
        )
        cellIds.forEachIndexed { index, id ->
          put(
            id,
            UiBuilderNode(
              id = id,
              componentId = "design/component-instance",
              component =
                JsonObject(
                  mapOf(
                    "componentKey" to JsonPrimitive("contribution-cell"),
                    "arguments" to
                      JsonObject(
                        mapOf(
                          "containerColor" to
                            JsonObject(mapOf("value" to JsonPrimitive(shades[index].toDesignHex())))
                        )
                      ),
                  )
                ),
            ),
          )
        }
        put(
          "cell-body",
          UiBuilderNode(
            id = "cell-body",
            componentId = "m3/surface",
            properties =
              JsonObject(
                mapOf(
                  // The one bound property: read by key from whatever the placement passed.
                  "containerColor" to
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("binding"),
                        "value" to JsonPrimitive("containerColor"),
                      )
                    )
                )
              ),
            modifiers =
              JsonArray(
                listOf(
                  JsonObject(
                    mapOf(
                      "type" to JsonPrimitive("size"),
                      "widthDp" to JsonPrimitive(CELL_PX),
                      "heightDp" to JsonPrimitive(CELL_PX),
                    )
                  )
                )
              ),
            slots = mapOf("content" to emptyList()),
          ),
        )
      }
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "contribution-graph",
      title = "Contribution graph",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment =
        Json.parseToJsonElement(
            """
            {
              "widthDp": $FRAME_PX, "heightDp": $FRAME_PX, "density": 1.0, "theme": "light",
              "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr",
              "animations": "settled"
            }
            """
          )
          .jsonObject,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("root"),
      nodes = nodes,
      components =
        JsonObject(
          mapOf(
            "contribution-cell" to
              JsonObject(
                mapOf(
                  "name" to JsonPrimitive("ContributionCell"),
                  "root" to JsonPrimitive("cell-body"),
                )
              )
          )
        ),
    )
  }

  private fun Color.toDesignHex(): String {
    fun channel(value: Float) = (value * 255f + 0.5f).toInt().toString(16).padStart(2, '0')
    return "#${channel(red)}${channel(green)}${channel(blue)}"
  }

  private companion object {
    const val CELL_PX = 24
    const val CELL_CENTRE_Y = CELL_PX / 2
    const val FRAME_PX = 160
  }
}
