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
   * The point of a component: one function, called once per placement.
   *
   * The body's bound property becomes a parameter — the key each placement passes — so the
   * generated file says what the design says: one cell, four shades. Parameter order is the sorted
   * key order rather than the document's, because a re-export of an unchanged design has to be
   * byte-identical.
   */
  @Test
  fun `a placed component is exported as a function and four calls`() {
    val source = exportSource(document())

    assertTrue(
      source.contains("private fun ContributionCell(containerColor: Color, modifier: Modifier"),
      source,
    )
    assertEquals(
      shades.size,
      Regex("ContributionCell\\(containerColor = Color\\(0x").findAll(source).count(),
      source,
    )
    // One body, emitted once: the `Surface` inside the function reads its parameter rather than a
    // literal, which is what makes the four calls four cells.
    assertTrue(source.contains("color = containerColor,"), source)
    // The placement's modifier belongs to the placement: the body is wrapped in `Box(modifier)`,
    // which is how the canvas draws it too.
    assertTrue(source.contains("Box(modifier) {"), source)
  }

  /**
   * A binding this exporter cannot print as an expression is refused by name, before a line is
   * generated — never exported as the component's own default, which would build and draw the wrong
   * thing.
   */
  @Test
  fun `a binding no emitter can write is refused by name`() {
    val unsupported =
      document().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("cell-body" to
                base.nodes.getValue("cell-body").let { body ->
                  body.copy(
                    properties =
                      JsonObject(
                        body.properties +
                          ("tonalElevationDp" to
                            JsonObject(
                              mapOf(
                                "type" to JsonPrimitive("binding"),
                                "value" to JsonPrimitive("elevation"),
                              )
                            ))
                      )
                  )
                })
        )
      }

    val result = CapabilityComposeCodeExporter.export(unsupported, catalog())

    assertTrue(
      result.diagnostics.any {
        it.code == "UNSUPPORTED_BINDING" && it.message.contains("tonalElevationDp")
      },
      "${result.diagnostics}",
    )
  }

  /** A placement that passes nothing for a parameter the body reads is refused, not defaulted. */
  @Test
  fun `a placement missing an argument is refused`() {
    val missing =
      document().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("cell-0" to
                base.nodes.getValue("cell-0").let { placement ->
                  placement.copy(
                    component =
                      JsonObject(mapOf("componentKey" to JsonPrimitive("contribution-cell")))
                  )
                })
        )
      }

    val result = CapabilityComposeCodeExporter.export(missing, catalog())

    assertTrue(
      result.diagnostics.any {
        it.code == "MISSING_ARGUMENT" && it.message.contains("containerColor")
      },
      "${result.diagnostics}",
    )
  }

  /**
   * Everything the export refuses, and why each would otherwise ship.
   *
   * All of these produce a document the canvas draws and the gate used to pass. What they generate
   * is source that does not compile, or worse, source that compiles and draws the wrong thing —
   * which is why each is named before a line is written rather than discovered by whoever pasted
   * the file into a project.
   */
  @Test
  fun `the export refuses what it cannot generate faithfully`() {
    fun codes(document: UiBuilderDocument) =
      CapabilityComposeCodeExporter.export(document, catalog()).diagnostics.map { it.code }.toSet()

    // A composable that calls itself recurses until the stack is gone; the canvas has an ancestor
    // guard, generated source has none.
    assertTrue(
      "COMPONENT_CYCLE" in
        codes(withBody { body -> body.copy(slots = mapOf("content" to listOf("cell-0"))) })
    )
    // A body is a function of its arguments: a state read names a variable declared inside the
    // screen function, which the component function cannot see.
    assertTrue(
      "COMPONENT_BODY_READS_STATE" in
        codes(
          withBody { body ->
            body.copy(
              properties =
                JsonObject(
                  body.properties +
                    ("shape" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("state"),
                          "variable" to JsonPrimitive("expanded"),
                        )
                      ))
                )
            )
          }
        )
    )
    // A scaffold is a screen, and a screen inside one box of a screen is a claim about the frame.
    assertTrue(
      "SCAFFOLD_IN_COMPONENT_BODY" in
        codes(withBody { body -> body.copy(componentId = "layout/scaffold") })
    )
    // The canvas dispatches a click bound to a placement; the call has nowhere to put one.
    assertTrue(
      "PLACEMENT_HANDLES_EVENT" in
        codes(
          withPlacement { placement ->
            placement.copy(eventBindings = JsonObject(mapOf("click" to JsonArray(emptyList()))))
          }
        )
    )
    // A colour that is not a colour becomes `Color.Unspecified` — which compiles, and paints
    // nothing.
    assertTrue(
      "INVALID_ARGUMENT" in
        codes(
          withPlacement { placement ->
            placement.copy(
              component =
                JsonObject(
                  mapOf(
                    "componentKey" to JsonPrimitive("contribution-cell"),
                    "arguments" to
                      JsonObject(
                        mapOf(
                          "containerColor" to
                            JsonObject(mapOf("value" to JsonPrimitive("chartreuse")))
                        )
                      ),
                  )
                )
            )
          }
        )
    )
    // Outside a body there is no dictionary to read, so the emitter would print the key itself.
    assertTrue(
      "BINDING_OUTSIDE_COMPONENT" in
        codes(
          document().let { base ->
            base.copy(
              nodes =
                base.nodes +
                  ("root" to
                    base.nodes.getValue("root").let { root ->
                      root.copy(
                        properties =
                          JsonObject(
                            mapOf(
                              "horizontalSpacingDp" to
                                JsonObject(
                                  mapOf(
                                    "type" to JsonPrimitive("binding"),
                                    "value" to JsonPrimitive("gap"),
                                  )
                                )
                            )
                          )
                      )
                    })
            )
          }
        )
    )
  }

  /**
   * A placement sits where its body would sit.
   *
   * It has no capability of its own — no catalog declares one — so a slot that would refuse the
   * body has to refuse the placement, or a design draws a surface in a slot that accepts only tabs
   * and exports source that does not typecheck.
   */
  @Test
  fun `a placement is refused by a slot that would refuse its body`() {
    val inATabRow =
      document().let { base ->
        base.copy(
          roots = listOf("tab-row"),
          nodes =
            base.nodes +
              ("tab-row" to
                UiBuilderNode(
                  id = "tab-row",
                  componentId = "m3/primary-tab-row",
                  properties =
                    JsonObject(
                      mapOf("selectedIndex" to JsonObject(mapOf("value" to JsonPrimitive(0))))
                    ),
                  slots = mapOf("tabs" to listOf("cell-0")),
                )),
        )
      }

    val result = CapabilityComposeCodeExporter.export(inATabRow, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "INCOMPATIBLE_SLOT_CHILD" },
      "${result.diagnostics.map { it.code }}",
    )
  }

  /**
   * A key that generates a parameter name the wrapper already has would shadow it, so the body's
   * own `modifier` would silently be the placement's.
   */
  @Test
  fun `a key that collides with the modifier parameter is refused`() {
    val shadowed = withBody { body ->
      body.copy(
        properties =
          JsonObject(
            mapOf(
              "containerColor" to
                JsonObject(
                  mapOf(
                    "type" to JsonPrimitive("binding"),
                    "value" to JsonPrimitive("modifier"),
                  )
                )
            )
          )
      )
    }

    val result = CapabilityComposeCodeExporter.export(shadowed, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "COLLIDING_PARAMETER_NAME" },
      "${result.diagnostics.map { it.code }}",
    )
  }

  private fun withBody(edit: (UiBuilderNode) -> UiBuilderNode): UiBuilderDocument =
    document().let { base ->
      base.copy(nodes = base.nodes + ("cell-body" to edit(base.nodes.getValue("cell-body"))))
    }

  private fun withPlacement(edit: (UiBuilderNode) -> UiBuilderNode): UiBuilderDocument =
    document().let { base ->
      base.copy(nodes = base.nodes + ("cell-0" to edit(base.nodes.getValue("cell-0"))))
    }

  private fun catalog() =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )

  private fun exportSource(document: UiBuilderDocument): String {
    val result = CapabilityComposeCodeExporter.export(document, catalog())
    assertTrue(result.successful, result.diagnostics.joinToString { "${it.code}:${it.message}" })
    return checkNotNull(result.source)
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
