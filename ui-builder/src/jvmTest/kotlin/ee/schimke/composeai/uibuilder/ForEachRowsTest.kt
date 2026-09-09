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
 * One template, drawn once per row of the design's own data.
 *
 * The last thing the format could not say. A component gave a design one body placed *n* times, but
 * how many times and with what was still authored by hand: four cells were four placements. A
 * `layout/for-each` says it once — `data` is a list of dictionaries, and the template reads a row
 * by key through exactly the reader a component body uses for its arguments.
 *
 * That reuse is the claim worth pinning: nothing about binding, substitution or instance paths is
 * new here, only where the dictionary comes from. Asserted in pixels, because the point is what a
 * person sees: three rows, three shades, one authored template.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ForEachRowsTest {

  private val shades = listOf(Color(0xFF9BE9A8), Color(0xFF40C463), Color(0xFF216E39))

  @Test
  fun `each row draws the template with its own values`() {
    val image = renderComposeScene(FRAME_PX, FRAME_PX, Density(1f)) { UiBuilderSurface(document()) }
    val pixels = image.toComposeImageBitmap().toPixelMap()

    assertEquals(shades, shades.indices.map { pixels[CELL_PX / 2, rowCentreY(it)] })
  }

  /**
   * A row that is not a dictionary, and a `data` that is not a list, draw nothing rather than
   * taking the composition down — the rule every accessor on this canvas follows, because a canvas
   * that crashes cannot draw the Issues panel that would explain why.
   */
  @Test
  fun `malformed data draws nothing and keeps the canvas alive`() {
    val malformed =
      document().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("loop" to
                base.nodes
                  .getValue("loop")
                  .copy(
                    properties =
                      JsonObject(mapOf("data" to JsonObject(mapOf("value" to JsonPrimitive(7)))))
                  ))
        )
      }

    val image = renderComposeScene(FRAME_PX, FRAME_PX, Density(1f)) { UiBuilderSurface(malformed) }

    // Nothing drawn where the first row would have been.
    assertTrue(image.toComposeImageBitmap().toPixelMap()[CELL_PX / 2, rowCentreY(0)] != shades[0])
  }

  /**
   * The export refuses a loop by name, because there is nothing faithful to emit yet.
   *
   * A `forEach` around the template's call needs a data class for the row — its properties derived
   * from the keys the template binds, the same derivation a component's parameters already use —
   * and that is the next change rather than a guess made here. Pinned so the refusal is a decision
   * and not a discovery.
   */
  @Test
  fun `the export refuses a loop by name`() {
    val catalog =
      CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )

    val result = CapabilityComposeCodeExporter.export(document(), catalog)

    assertTrue(
      result.diagnostics.any {
        it.code == "UNSUPPORTED_CODE_COMPONENT" && it.componentId == "layout/for-each"
      },
      "${result.diagnostics.map { it.code to it.componentId }}",
    )
  }

  /**
   * A loop's SVG capability is unverified, and an export of a design holding one says so.
   *
   * The structured recorder correlates a text element by authored node id, and a loop draws its
   * template more than once — so only the last row's measurements survive and the typography
   * annotation cannot be matched to the earlier elements. Refusing by name is the honest state
   * until the recorder reads instance paths; claiming `verified` would have produced an SVG whose
   * text is silently unannotated.
   */
  @Test
  fun `an svg export of a design holding a loop refuses by name`() {
    val catalog =
      CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )

    val readiness =
      inspectDocumentSvgExport(
        document(),
        catalog,
        DocumentSvgExecutionBridge.JVM_SKIA_SCENE_RECORDING,
      )

    assertTrue(!readiness.ready, "readiness=$readiness")
    assertTrue("loop" in readiness.unverifiedNodeIds, "${readiness.unverifiedNodeIds}")
  }

  private fun rowCentreY(index: Int) = index * CELL_PX + CELL_PX / 2

  /** A loop over three rows, whose template is one surface reading `shade`. */
  private fun document(): UiBuilderDocument {
    val rows =
      JsonArray(
        shades.map { shade ->
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("object"),
              "fields" to
                JsonObject(
                  mapOf("shade" to JsonObject(mapOf("value" to JsonPrimitive(shade.toDesignHex()))))
                ),
            )
          )
        }
      )
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "contribution-column",
      title = "Contribution column",
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
              "widthDp": $FRAME_PX, "heightDp": $FRAME_PX, "density": 1.0, "theme": "light",
              "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr",
              "animations": "settled"
            }
            """
          )
          .jsonObject,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("loop"),
      nodes =
        mapOf(
          "loop" to
            UiBuilderNode(
              id = "loop",
              componentId = "layout/for-each",
              properties =
                JsonObject(
                  mapOf(
                    "data" to JsonObject(mapOf("type" to JsonPrimitive("list"), "values" to rows))
                  )
                ),
              slots = mapOf("template" to listOf("cell")),
            ),
          "cell" to
            UiBuilderNode(
              id = "cell",
              componentId = "m3/surface",
              properties =
                JsonObject(
                  mapOf(
                    "containerColor" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("binding"),
                          "value" to JsonPrimitive("shade"),
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
        ),
    )
  }

  private fun Color.toDesignHex(): String {
    fun channel(value: Float) = (value * 255f + 0.5f).toInt().toString(16).padStart(2, '0')
    return "#${channel(red)}${channel(green)}${channel(blue)}"
  }

  private companion object {
    const val CELL_PX = 24
    const val FRAME_PX = 120
  }
}
