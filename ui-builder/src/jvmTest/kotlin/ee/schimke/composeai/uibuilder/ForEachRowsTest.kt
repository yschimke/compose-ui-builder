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
   * The rows become a list of a generated row type, walked once.
   *
   * The data class's properties are the keys the template binds, typed by the emitter that prints
   * them — the same derivation that gives a component its parameters, because a row and a
   * placement's arguments are the same dictionary seen from two sides.
   */
  @Test
  fun `a loop is exported as a row type and one forEach`() {
    val source = exportSource(document())

    assertTrue(source.contains("private data class LoopRow(val shade: Color)"), source)
    assertTrue(
      source.contains("kotlin.collections.listOf(LoopRow(shade = Color(0xFF9BE9A8)"),
      source,
    )
    assertTrue(source.contains(".forEach { row ->"), source)
    // The template reads the row rather than a literal, which is what makes one template n cells.
    assertTrue(source.contains("color = row.shade,"), source)
    // One template, emitted once.
    assertEquals(1, Regex("// node:cell ").findAll(source).count(), source)
  }

  /**
   * A row missing a key its template reads is refused, not defaulted.
   *
   * The generated call would have a parameter unfilled; a default would compile and draw a cell
   * that is not the one the design describes.
   */
  @Test
  fun `a row missing a key the template reads is refused`() {
    val short =
      document().let { base ->
        val loop = base.nodes.getValue("loop")
        val data = loop.properties["data"] as JsonObject
        val values = (data["values"] as JsonArray).drop(1)
        base.copy(
          nodes =
            base.nodes +
              ("loop" to
                loop.copy(
                  properties =
                    JsonObject(
                      mapOf(
                        "data" to
                          JsonObject(
                            mapOf(
                              "type" to JsonPrimitive("list"),
                              "values" to
                                JsonArray(
                                  listOf(
                                    JsonObject(
                                      mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "fields" to JsonObject(emptyMap()),
                                      )
                                    )
                                  ) + values
                                ),
                            )
                          )
                      )
                    )
                ))
        )
      }

    val result = CapabilityComposeCodeExporter.export(short, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "MISSING_ROW_VALUE" && it.message.contains("shade") },
      "${result.diagnostics.map { it.code }}",
    )
  }

  /**
   * A loop inside a lazy container is refused rather than emitted as a `forEach` inside an `item`.
   *
   * That would compose every row as a single item — one key, one recycling unit — where rows belong
   * in `items(rows, key = { … })`. Refused until that emitter exists.
   */
  @Test
  fun `a loop inside a lazy container is refused by name`() {
    val lazy =
      document().let { base ->
        base.copy(
          roots = listOf("list"),
          nodes =
            base.nodes +
              ("list" to
                UiBuilderNode(
                  id = "list",
                  componentId = "layout/lazy-column",
                  properties =
                    JsonObject(
                      mapOf(
                        "scrollStateKey" to
                          JsonObject(mapOf("value" to JsonPrimitive("list-scroll")))
                      )
                    ),
                  slots = mapOf("items" to listOf("loop")),
                )),
        )
      }

    val result = CapabilityComposeCodeExporter.export(lazy, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "LOOP_IN_LAZY_CONTAINER" },
      "${result.diagnostics.map { it.code }}",
    )
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

  /**
   * A loop whose template is a component placement — the composition the design record calls the
   * point of having both: one body, defined once, drawn per row with that row's values.
   *
   * A placement reads through its *arguments* rather than its properties, so a scan that looked
   * only at properties lost every row-varying argument: a colour was refused as `INVALID_ARGUMENT`
   * and a string became the key's own name.
   */
  @Test
  fun `a loop over a placed component passes each row to the call`() {
    val source = exportSource(loopOverComponent())

    assertTrue(source.contains("private data class LoopRow(val shade: Color)"), source)
    assertTrue(source.contains("Cell(containerColor = row.shade, modifier = Modifier)"), source)
    assertTrue(
      source.contains("private fun Cell(containerColor: Color, modifier: Modifier = Modifier)"),
      source,
    )
  }

  /**
   * An empty row list still has to say what it is a list of.
   *
   * `listOf().forEach { row -> row.shade }` has nothing to infer the element from, and a design
   * with no rows yet is the state every loop starts in.
   */
  @Test
  fun `an empty row list carries its element type`() {
    val empty = withRows(JsonArray(emptyList()))

    val source = exportSource(empty)

    assertTrue(source.contains("kotlin.collections.listOf<LoopRow>()"), source)
  }

  /**
   * A loop inside a loop reads its own rows, so the outer signature stops at the inner template.
   *
   * Descending into it demanded the inner keys of the outer rows and refused a nesting the emitter
   * draws correctly.
   */
  @Test
  fun `a nested loop does not put its keys on the outer rows`() {
    val nested =
      document().let { base ->
        base.copy(
          nodes =
            base.nodes +
              mapOf(
                "loop" to
                  base.nodes.getValue("loop").copy(slots = mapOf("template" to listOf("inner"))),
                "inner" to
                  base.nodes
                    .getValue("loop")
                    .copy(id = "inner", slots = mapOf("template" to listOf("cell"))),
              )
        )
      }

    val result = CapabilityComposeCodeExporter.export(nested, catalog())

    assertTrue(
      result.diagnostics.none { it.code == "MISSING_ROW_VALUE" },
      "${result.diagnostics.map { it.code to it.message }}",
    )
  }

  /**
   * An identity inside a repeated template is refused: every row reaches the same `key("…")`, so
   * remembered and scroll state would follow whichever row composed last. The sibling fold refuses
   * an identity-bearing subtree for the same reason.
   */
  @Test
  fun `an identity inside a template is refused`() {
    val identified =
      document().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("cell" to
                base.nodes.getValue("cell").let { cell ->
                  // `m3/card` declares `stableKey`; `m3/surface` does not, and an undeclared
                  // property is a different diagnostic than the one under test. Its content slot
                  // takes a child, so the document stays valid for every other reason.
                  cell.copy(
                    componentId = "m3/card",
                    properties =
                      JsonObject(
                        cell.properties +
                          ("stableKey" to JsonObject(mapOf("value" to JsonPrimitive("cell"))))
                      ),
                    slots = mapOf("content" to listOf("cell-label")),
                  )
                }) +
              ("cell-label" to
                UiBuilderNode(
                  id = "cell-label",
                  componentId = "m3/text",
                  properties =
                    JsonObject(mapOf("text" to JsonObject(mapOf("value" to JsonPrimitive(""))))),
                ))
        )
      }

    val result = CapabilityComposeCodeExporter.export(identified, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "IDENTITY_IN_LOOP_TEMPLATE" },
      "${result.diagnostics.map { it.code }}",
    )
  }

  /**
   * Two keys that generate one Kotlin name would make a data class with duplicate properties and a
   * constructor call with duplicate arguments — source that looks right and does not compile.
   */
  @Test
  fun `row keys that generate one identifier are refused`() {
    val colliding =
      document().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("cell" to
                base.nodes.getValue("cell").let { cell ->
                  cell.copy(
                    properties =
                      JsonObject(
                        mapOf(
                          "containerColor" to binding("cell shade"),
                          "shape" to binding("cell-shade"),
                        )
                      )
                  )
                })
        )
      }

    val result = CapabilityComposeCodeExporter.export(colliding, catalog())

    assertTrue(
      result.diagnostics.any {
        it.code == "COLLIDING_ROW_PROPERTY" || it.code == "UNSUPPORTED_BINDING"
      },
      "${result.diagnostics.map { it.code }}",
    )
  }

  /**
   * Which lane exports a loop, pinned rather than asserted.
   *
   * `CapabilityComposeCodeExporter` is the editor's own gate — the Issues panel reads its
   * `diagnose`, and it writes the checked-in benchmark fixture. What the **code pane** shows and
   * what the server's export and native preview write is the record-driven lane (`ScreenExportGate`
   * → `ScreenGenerator`), which has no loop and refuses one by name. Until that lane grows a
   * `forEach` of its own, a design holding a loop is refused everywhere a person can see the code,
   * and saying otherwise is how a claim in a design record rots.
   */
  @Test
  fun `the record-driven lane the code pane uses still refuses a loop`() {
    val reducer = UiBuilderEditorReducer(catalog())

    val generated = reducer.generatedCode(document())

    assertTrue(
      generated is EditorGeneratedCode.Refused,
      "the code pane generated a loop it has no emitter for: $generated",
    )
  }

  /**
   * The canvas draws what the export writes, for a loop over a placed component.
   *
   * The export prints `row.shade` and the canvas substitutes the row into the placement's arguments
   * — without that substitution the body received the binding wrapper and drew its fallback, so the
   * preview and the generated screen disagreed about the one thing a loop is for.
   */
  @Test
  fun `a loop over a placed component draws each row's value`() {
    val image =
      renderComposeScene(FRAME_PX, FRAME_PX, Density(1f)) { UiBuilderSurface(loopOverComponent()) }
    val pixels = image.toComposeImageBitmap().toPixelMap()

    assertEquals(shades, shades.indices.map { pixels[CELL_PX / 2, rowCentreY(it)] })
  }

  /**
   * A malformed identity is a diagnostic, not a crash.
   *
   * `diagnose` runs the loop's checks before it returns what capability validation already found,
   * so an accessor that threw here turned a malformed document into an editor that could not draw
   * the panel naming its fault.
   */
  @Test
  fun `a malformed identity value does not take the gate down`() {
    val malformed =
      document().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("cell" to
                base.nodes.getValue("cell").let { cell ->
                  cell.copy(
                    properties =
                      JsonObject(
                        cell.properties +
                          ("stableKey" to JsonObject(mapOf("value" to JsonObject(emptyMap()))))
                      )
                  )
                })
        )
      }

    // The claim is that this returns at all: `diagnose` reads the identity before it hands back
    // what capability validation already found, and an accessor that threw took the editor with it.
    val diagnostics = CapabilityComposeCodeExporter.diagnose(malformed, catalog())

    assertTrue(diagnostics.any { it.nodeId == "cell" }, "$diagnostics")
  }

  /**
   * A component placed by a body whose key sorts later is still derived first.
   *
   * A bound argument takes its type from the parameter it fills, so a signature derived in key
   * order refused a perfectly acyclic composition purely because of the names involved.
   */
  @Test
  fun `a component placing a later-named component still exports`() {
    val source = exportSource(loopOverComponent(placedKey = "zzz-cell"))

    assertTrue(source.contains("containerColor = row.shade"), source)
  }

  /**
   * A loop inside a component body reads the loop's rows, not the component's arguments.
   *
   * Derived through the loop, the row key became a parameter of the enclosing function too, and a
   * placement of it was refused for not passing a value the generated function never reads.
   */
  @Test
  fun `a loop inside a component body does not add its keys to the component`() {
    val nested = loopInsideComponent()

    val result = CapabilityComposeCodeExporter.export(nested, catalog())

    assertTrue(
      result.diagnostics.none { it.code == "MISSING_ARGUMENT" },
      "${result.diagnostics.map { it.code to it.message }}",
    )
  }

  /**
   * An identity inside a component the template places is shared by every row, so it is refused.
   */
  @Test
  fun `an identity inside a placed component body is refused`() {
    val identified =
      loopOverComponent().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("cell" to
                base.nodes.getValue("cell").let { cell ->
                  // `m3/card` declares `stableKey`; `m3/surface` does not, and an undeclared
                  // property is a different diagnostic than the one under test. Its content slot
                  // takes a child, so the document stays valid for every other reason.
                  cell.copy(
                    componentId = "m3/card",
                    properties =
                      JsonObject(
                        cell.properties +
                          ("stableKey" to JsonObject(mapOf("value" to JsonPrimitive("cell"))))
                      ),
                    slots = mapOf("content" to listOf("cell-label")),
                  )
                }) +
              ("cell-label" to
                UiBuilderNode(
                  id = "cell-label",
                  componentId = "m3/text",
                  properties =
                    JsonObject(mapOf("text" to JsonObject(mapOf("value" to JsonPrimitive(""))))),
                ))
        )
      }

    val result = CapabilityComposeCodeExporter.export(identified, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "IDENTITY_IN_LOOP_TEMPLATE" },
      "${result.diagnostics.map { it.code }}",
    )
  }

  /**
   * A component that places itself from inside a loop still recurses, so it is still refused.
   *
   * Scoping the cycle walk to the enclosing scope — right for parameter inference, because a
   * template's bindings read the loop's rows — hid the placement entirely, and the export emitted a
   * function that calls itself once per row until the stack is gone.
   */
  @Test
  fun `a component placing itself from inside a loop is refused`() {
    val recursive =
      loopInsideComponent().let { base ->
        base.copy(
          nodes =
            base.nodes +
              mapOf(
                "loop" to
                  base.nodes.getValue("loop").copy(slots = mapOf("template" to listOf("again"))),
                "again" to
                  UiBuilderNode(
                    id = "again",
                    componentId = "design/component-instance",
                    component = JsonObject(mapOf("componentKey" to JsonPrimitive("panel"))),
                  ),
              )
        )
      }

    val result = CapabilityComposeCodeExporter.export(recursive, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "COMPONENT_CYCLE" },
      "${result.diagnostics.map { it.code }}",
    )
  }

  /**
   * A `state` read inside a loop template inside a component body names a variable the generated
   * component function cannot see, wherever in the body it sits.
   *
   * The loop's own checks do not stand in for the component's: they ask what varies per row, not
   * what the function can reach.
   */
  @Test
  fun `a component body reading state through a loop template is refused`() {
    val reads =
      loopInsideComponent().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("cell" to
                base.nodes
                  .getValue("cell")
                  .copy(
                    properties =
                      JsonObject(
                        mapOf(
                          "containerColor" to
                            JsonObject(
                              mapOf(
                                "type" to JsonPrimitive("state"),
                                "variable" to JsonPrimitive("tint"),
                              )
                            )
                        )
                      )
                  ))
        )
      }

    val result = CapabilityComposeCodeExporter.export(reads, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "COMPONENT_BODY_READS_STATE" },
      "${result.diagnostics.map { it.code }}",
    )
  }

  /**
   * An identity two placements deep is shared by every row exactly as one directly in the template
   * is, so the walk that finds it follows placements to the bottom rather than one level down.
   */
  @Test
  fun `an identity below a nested placement is refused`() {
    val nested =
      loopOverComponent().let { base ->
        base.copy(
          nodes =
            base.nodes +
              mapOf(
                "cell" to
                  base.nodes
                    .getValue("cell")
                    .copy(
                      componentId = "m3/card",
                      slots = mapOf("content" to listOf("inner-place")),
                    ),
                "inner-place" to
                  UiBuilderNode(
                    id = "inner-place",
                    componentId = "design/component-instance",
                    component = JsonObject(mapOf("componentKey" to JsonPrimitive("inner"))),
                  ),
                "inner" to
                  UiBuilderNode(
                    id = "inner",
                    componentId = "m3/card",
                    properties =
                      JsonObject(
                        mapOf("stableKey" to JsonObject(mapOf("value" to JsonPrimitive("inner"))))
                      ),
                  ),
              ),
          components =
            JsonObject(
              base.components +
                ("inner" to
                  JsonObject(
                    mapOf("name" to JsonPrimitive("Inner"), "root" to JsonPrimitive("inner"))
                  ))
            ),
        )
      }

    val result = CapabilityComposeCodeExporter.export(nested, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "IDENTITY_IN_LOOP_TEMPLATE" },
      "${result.diagnostics.map { it.code }}",
    )
  }

  /**
   * A placement argument is an open-keyed dictionary that capability validation never type-checks,
   * so the binding reader has to survive one shaped like anything at all.
   */
  @Test
  fun `a malformed placement argument does not take the gate down`() {
    // The claim is that this returns rather than throwing; what it refuses on is secondary.
    val result = CapabilityComposeCodeExporter.export(malformedPlacementArgument(), catalog())

    assertTrue(!result.successful, "${result.diagnostics.map { it.code }}")
  }

  /**
   * A row key that normalises to a Kotlin keyword generates `val class: Color` and `row.class`.
   *
   * `identifier()` does not escape hard keywords — nothing downstream writes the backticks — so the
   * name is refused where it is claimed rather than emitted and discovered by the compiler.
   */
  @Test
  fun `a row key that generates a Kotlin keyword is refused`() {
    val keyword =
      document().let { base ->
        base.copy(
          nodes =
            base.nodes +
              mapOf(
                "loop" to
                  base.nodes
                    .getValue("loop")
                    .copy(
                      properties =
                        JsonObject(
                          mapOf(
                            "data" to
                              JsonObject(
                                mapOf(
                                  "type" to JsonPrimitive("list"),
                                  "values" to
                                    JsonArray(
                                      shades.map { shade ->
                                        JsonObject(
                                          mapOf(
                                            "type" to JsonPrimitive("object"),
                                            "fields" to
                                              JsonObject(
                                                mapOf(
                                                  "class" to
                                                    JsonObject(
                                                      mapOf(
                                                        "value" to
                                                          JsonPrimitive(shade.toDesignHex())
                                                      )
                                                    )
                                                )
                                              ),
                                          )
                                        )
                                      }
                                    ),
                                )
                              )
                          )
                        )
                    ),
                "cell" to
                  base.nodes
                    .getValue("cell")
                    .copy(properties = JsonObject(mapOf("containerColor" to binding("class")))),
              )
        )
      }

    val result = CapabilityComposeCodeExporter.export(keyword, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "RESERVED_ROW_PROPERTY" },
      "${result.diagnostics.map { it.code }}",
    )
  }

  /**
   * The canvas survives the malformed placement argument the exporter refuses.
   *
   * The refusal is shown in a panel the canvas has to stay alive to draw, so a wrapper shaped
   * `{"type": "binding", "value": {}}` has to leave the property holding what it already held
   * rather than reach `jsonPrimitive` and take the composition down.
   */
  @Test
  fun `a malformed placement argument does not take the canvas down`() {
    val malformed = malformedPlacementArgument()

    val image = renderComposeScene(FRAME_PX, FRAME_PX, Density(1f)) { UiBuilderSurface(malformed) }

    assertEquals(FRAME_PX, image.width)
  }

  /**
   * An argument the placed component does not declare is never written into the emitted call, so it
   * is refused for being unread rather than for being an unsupported binding.
   */
  @Test
  fun `an argument the component does not take is named as unknown`() {
    val stray =
      loopOverComponent().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("place" to
                base.nodes
                  .getValue("place")
                  .copy(
                    component =
                      JsonObject(
                        mapOf(
                          "componentKey" to JsonPrimitive("cell"),
                          "arguments" to
                            JsonObject(
                              mapOf(
                                "containerColor" to binding("shade"),
                                "notAParameter" to binding("shade"),
                              )
                            ),
                        )
                      )
                  ))
        )
      }

    val result = CapabilityComposeCodeExporter.export(stray, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "UNKNOWN_ARGUMENT" } &&
        result.diagnostics.none { it.code == "UNSUPPORTED_BINDING" },
      "${result.diagnostics.map { it.code to it.message }}",
    )
  }

  /**
   * A component parameter derived through a placement is subject to the same keyword rule as a row
   * property — `fun Cell(class: Color)` is no more Kotlin than `val class: Color`.
   *
   * Reachable because a bound placement argument now contributes its key to the enclosing
   * component's signature, which is what this change introduced.
   */
  @Test
  fun `a component parameter that generates a Kotlin keyword is refused`() {
    val keyword =
      loopInsideComponent().let { base ->
        base.copy(
          nodes =
            base.nodes +
              mapOf(
                "panel" to
                  base.nodes.getValue("panel").copy(slots = mapOf("children" to listOf("inner"))),
                "inner" to
                  UiBuilderNode(
                    id = "inner",
                    componentId = "design/component-instance",
                    component =
                      JsonObject(
                        mapOf(
                          "componentKey" to JsonPrimitive("cell"),
                          "arguments" to JsonObject(mapOf("containerColor" to binding("class"))),
                        )
                      ),
                  ),
                "cell" to
                  base.nodes
                    .getValue("cell")
                    .copy(
                      properties = JsonObject(mapOf("containerColor" to binding("containerColor")))
                    ),
              ),
          components =
            JsonObject(
              base.components +
                ("cell" to
                  JsonObject(
                    mapOf("name" to JsonPrimitive("Cell"), "root" to JsonPrimitive("cell"))
                  ))
            ),
        )
      }

    val result = CapabilityComposeCodeExporter.export(keyword, catalog())

    assertTrue(
      result.diagnostics.any { it.code == "RESERVED_PARAMETER" },
      "${result.diagnostics.map { it.code to it.message }}",
    )
  }

  /** A component whose body holds a loop over its own rows. */
  private fun loopInsideComponent(): UiBuilderDocument =
    document().let { base ->
      base.copy(
        roots = listOf("place"),
        nodes =
          base.nodes +
            mapOf(
              "place" to
                UiBuilderNode(
                  id = "place",
                  componentId = "design/component-instance",
                  component = JsonObject(mapOf("componentKey" to JsonPrimitive("panel"))),
                ),
              "panel" to
                UiBuilderNode(
                  id = "panel",
                  componentId = "layout/column",
                  slots = mapOf("children" to listOf("loop")),
                ),
            ),
        components =
          JsonObject(
            mapOf(
              "panel" to
                JsonObject(
                  mapOf("name" to JsonPrimitive("Panel"), "root" to JsonPrimitive("panel"))
                )
            )
          ),
      )
    }

  private fun binding(key: String) =
    JsonObject(mapOf("type" to JsonPrimitive("binding"), "value" to JsonPrimitive(key)))

  private fun withRows(values: JsonArray): UiBuilderDocument =
    document().let { base ->
      base.copy(
        nodes =
          base.nodes +
            ("loop" to
              base.nodes
                .getValue("loop")
                .copy(
                  properties =
                    JsonObject(
                      mapOf(
                        "data" to
                          JsonObject(mapOf("type" to JsonPrimitive("list"), "values" to values))
                      )
                    )
                ))
      )
    }

  /** The same loop, but its template places a component that takes the row's shade. */
  private fun loopOverComponent(placedKey: String = "cell"): UiBuilderDocument =
    document().let { base ->
      base.copy(
        nodes =
          base.nodes +
            mapOf(
              "loop" to
                base.nodes.getValue("loop").copy(slots = mapOf("template" to listOf("place"))),
              "place" to
                UiBuilderNode(
                  id = "place",
                  componentId = "design/component-instance",
                  component =
                    JsonObject(
                      mapOf(
                        "componentKey" to JsonPrimitive(placedKey),
                        "arguments" to JsonObject(mapOf("containerColor" to binding("shade"))),
                      )
                    ),
                ),
              "cell" to
                base.nodes
                  .getValue("cell")
                  .copy(
                    properties = JsonObject(mapOf("containerColor" to binding("containerColor")))
                  ),
            ),
        components =
          JsonObject(
            mapOf(
              placedKey to
                JsonObject(
                  mapOf(
                    "name" to JsonPrimitive("Cell"),
                    "root" to JsonPrimitive("cell"),
                  )
                )
            )
          ),
      )
    }

  /** A placement whose bound argument holds an object where a key belongs. */
  private fun malformedPlacementArgument(): UiBuilderDocument =
    loopOverComponent().let { base ->
      base.copy(
        nodes =
          base.nodes +
            ("place" to
              base.nodes
                .getValue("place")
                .copy(
                  component =
                    JsonObject(
                      mapOf(
                        "componentKey" to JsonPrimitive("cell"),
                        "arguments" to
                          JsonObject(
                            mapOf(
                              "containerColor" to
                                JsonObject(
                                  mapOf(
                                    "type" to JsonPrimitive("binding"),
                                    "value" to JsonObject(emptyMap()),
                                  )
                                )
                            )
                          ),
                      )
                    )
                ))
      )
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
