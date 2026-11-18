package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter as Wff
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The palette picture of each Watch Face Format component, for [UiBuilderEditorReducer].
 *
 * The generic thumbnail frame is a `layout/box` the component is inserted into, and a WFF element
 * cannot sit in one: every part belongs on a `wff/scene`, many only inside a parent (a `shape`
 * inside a `part-draw`, a `clock-hand` inside an `analog-clock`). So a WFF thumbnail is a small
 * round scene drawn by [WatchFaceCanvas], with the component placed in the parent it needs and
 * given a sample that makes the tile mean something: a hand on a dial, "10:08", a ring.
 *
 * Configuration elements (`user-configurations`, `flavor`, ...) draw nothing on a watch face: they
 * are what a companion app offers. Their tile is the choice they put in front of the wearer, with
 * the real element carried in the scene's `face` slot, so what is shown is what Add would write.
 *
 * Every id the sample uses must be in the catalog, else there is no picture (a trimmed catalog
 * cannot draw a sample it does not offer).
 */
internal object WatchFacePalettePreviews {
  private const val SIZE = 450
  private const val CENTRE = SIZE / 2

  private const val ACCENT = "#ff4fc3f7"
  private const val WARM = "#fff6b26b"
  private const val SOFT = "#ff2a3441"
  private const val LIGHT = "#ffffffff"
  private const val DIAL = "#ff0e1319"

  /** The sample for [componentId], on [frame]'s pin and environment; null if not a WFF one. */
  fun document(
    catalog: CapabilityCatalog,
    componentId: String,
    frame: UiBuilderDocument,
  ): UiBuilderDocument? {
    if (!componentId.startsWith(Wff.PREFIX)) return null
    val sample = Sample().apply { build(componentId) }.takeIf { it.drawn } ?: return null
    val nodes = sample.nodes.toMap()
    if (nodes.values.any { it.componentId !in catalog.componentsById }) return null
    return frame.copy(
      id = "wff-palette-${componentId.removePrefix(Wff.PREFIX)}",
      roots = listOf(PREVIEW_FRAME_CELL_ID),
      nodes = nodes,
    )
  }

  private class Sample {
    val nodes = linkedMapOf<String, UiBuilderNode>()
    private val face = mutableListOf<String>()
    private val content = mutableListOf<String>()
    private var counter = 0
    var drawn = false

    /** A node, any `slots` mapped to ids, returning its id. */
    fun node(
      componentId: String,
      properties: Map<String, JsonObject> = emptyMap(),
      slots: Map<String, List<String>> = emptyMap(),
      id: String = "n${counter++}",
    ): String {
      nodes[id] =
        UiBuilderNode(id, componentId, JsonObject(properties), JsonArray(emptyList()), slots)
      return id
    }

    fun put(id: String) {
      content += id
    }

    fun build(componentId: String) {
      body(componentId)
      val scene =
        node(
          Wff.SCENE,
          mapOf(
            "width" to int(SIZE),
            "height" to int(SIZE),
            "clipShape" to string("CIRCLE"),
            "backgroundColor" to color("#ff000000"),
            "clockType" to string("ANALOG"),
            "previewTime" to string("10:08:32"),
          ),
          buildMap {
            put("content", content.toList())
            if (face.isNotEmpty()) put("face", face.toList())
          },
          id = PREVIEW_FRAME_CELL_ID,
        )
      drawn = scene in nodes && (content.isNotEmpty())
    }

    // The shapes a part-draw holds, each with its paint.
    fun shape(
      kind: String,
      box: List<Int>,
      fill: String? = null,
      stroke: String? = null,
      thickness: Int = 16,
      corner: Int = 0,
    ): String {
      val paints = buildList {
        if (fill != null) add(node(Wff.FILL, mapOf("color" to color(fill))))
        if (stroke != null)
          add(node(Wff.STROKE, mapOf("color" to color(stroke), "thickness" to int(thickness))))
      }
      return node(
        Wff.SHAPE,
        buildMap {
          put("shape", string(kind))
          put("x", int(box[0]))
          put("y", int(box[1]))
          put("width", int(box[2]))
          put("height", int(box[3]))
          if (corner > 0) {
            put("cornerRadiusX", int(corner))
            put("cornerRadiusY", int(corner))
          }
        },
        mapOf("content" to paints),
      )
    }

    fun draw(vararg shapes: String, extra: Map<String, List<String>> = emptyMap()): String =
      node(
        Wff.PART_DRAW,
        bounds(0, 0, SIZE, SIZE),
        mapOf("content" to shapes.toList()) + extra,
      )

    fun text(
      text: String,
      box: List<Int>,
      size: Int,
      tint: String = LIGHT,
      parameters: String? = null,
    ): String =
      node(
        Wff.PART_TEXT,
        buildMap {
          putAll(bounds(box[0], box[1], box[2], box[3]))
          put("text", string(text))
          if (parameters != null) put("parameters", string(parameters))
          put("fontSize", int(size))
          put("color", color(tint))
          put("align", string("CENTER"))
        },
      )

    fun hand(type: String, length: Int, width: Int, tint: String): String =
      node(
        Wff.CLOCK_HAND,
        buildMap {
          putAll(bounds(CENTRE - width / 2, CENTRE - length, width, length + 20))
          put(
            "pivotX",
            JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(0.5))),
          )
          put(
            "pivotY",
            JsonObject(
              mapOf(
                "type" to JsonPrimitive("float"),
                "value" to JsonPrimitive(length / (length + 20.0)),
              ),
            ),
          )
          put("type", string(type))
          put("tintColor", color(tint))
        },
      )

    fun dial(): String =
      draw(shape("ellipse", listOf(24, 24, 402, 402), fill = DIAL, stroke = SOFT, thickness = 14))

    fun swatches(vararg colours: String, size: Int = 96): List<String> {
      val gap = 32
      val left = CENTRE - (colours.size * size + (colours.size - 1) * gap) / 2
      return colours.mapIndexed { index, colour ->
        draw(
          shape(
            "ellipse",
            listOf(left + index * (size + gap), CENTRE - size / 2, size, size),
            fill = colour,
            stroke = LIGHT,
            thickness = 6,
          ),
        )
      }
    }

    /** A rounded row of a list the wearer picks from, with its label. */
    fun row(label: String, top: Int, picked: Boolean): List<String> =
      listOf(
        draw(
          shape(
            "round-rectangle",
            listOf(75, top, 300, 70),
            fill = if (picked) ACCENT else SOFT,
            corner = 35,
          ),
        ),
        text(label, listOf(75, top + 8, 300, 56), 40, if (picked) DIAL else LIGHT),
      )

    fun toggle(): List<String> =
      listOf(
        draw(shape("round-rectangle", listOf(105, 165, 240, 120), fill = ACCENT, corner = 60)),
        draw(shape("ellipse", listOf(225, 175, 100, 100), fill = LIGHT)),
      )

    fun body(componentId: String) {
      when (componentId) {
        Wff.SCENE -> {
          // A small analog face: dial, a mark at each hour, the hands.
          put(dial())
          repeat(12) { hour ->
            val mark =
              node(
                Wff.GROUP,
                bounds(0, 0, SIZE, SIZE) + angle(hour * 30),
                mapOf(
                  "content" to
                    listOf(
                      draw(
                        shape(
                          "round-rectangle",
                          listOf(216, 36, 18, if (hour % 3 == 0) 56 else 30),
                          fill = if (hour % 3 == 0) ACCENT else LIGHT,
                          corner = 5,
                        ),
                      ),
                    ),
                ),
              )
            put(mark)
          }
          put(
            node(
              Wff.ANALOG_CLOCK,
              bounds(0, 0, SIZE, SIZE),
              mapOf(
                "hands" to
                  listOf(
                    hand("hour", 120, 22, LIGHT),
                    hand("minute", 170, 14, LIGHT),
                    hand("second", 190, 6, WARM),
                  ),
              ),
            ),
          )
        }
        Wff.GROUP ->
          put(
            node(
              Wff.GROUP,
              bounds(0, 0, SIZE, SIZE),
              mapOf(
                "content" to
                  listOf(
                    draw(shape("ellipse", listOf(95, 95, 180, 180), fill = ACCENT)),
                    draw(
                      shape(
                        "round-rectangle",
                        listOf(185, 185, 180, 180),
                        fill = WARM,
                        corner = 36,
                      ),
                    ),
                  ),
              ),
            ),
          )
        Wff.PART_DRAW ->
          put(
            draw(
              shape(
                "round-rectangle",
                listOf(70, 100, 310, 250),
                fill = SOFT,
                stroke = ACCENT,
                corner = 60,
              ),
              shape("ellipse", listOf(155, 155, 140, 140), fill = WARM),
            ),
          )
        Wff.SHAPE ->
          put(
            draw(
              shape(
                "round-rectangle",
                listOf(75, 115, 300, 220),
                fill = ACCENT,
                stroke = LIGHT,
                thickness = 12,
                corner = 50,
              ),
            ),
          )
        Wff.FILL -> put(draw(shape("ellipse", listOf(75, 75, 300, 300), fill = ACCENT)))
        Wff.STROKE ->
          put(draw(shape("ellipse", listOf(80, 80, 290, 290), stroke = WARM, thickness = 40)))
        Wff.PART_TEXT -> put(text("10:08", listOf(25, 150, 400, 150), 130))
        Wff.TIME_TEXT ->
          put(
            node(
              Wff.TIME_TEXT,
              buildMap {
                putAll(bounds(25, 150, 400, 150))
                put("format", string("hh:mm"))
                put("hourFormat", string("24"))
                put("fontSize", int(130))
                put("color", color(LIGHT))
                put("align", string("CENTER"))
              },
            ),
          )
        Wff.DIGITAL_CLOCK ->
          put(
            node(
              Wff.DIGITAL_CLOCK,
              bounds(0, 0, SIZE, SIZE),
              mapOf(
                "texts" to
                  listOf(
                    node(
                      Wff.TIME_TEXT,
                      buildMap {
                        putAll(bounds(25, 150, 400, 150))
                        put("format", string("hh:mm"))
                        put("hourFormat", string("24"))
                        put("fontSize", int(130))
                        put("color", color(LIGHT))
                        put("align", string("CENTER"))
                      },
                    ),
                  ),
              ),
            ),
          )
        Wff.ANALOG_CLOCK -> {
          put(dial())
          put(
            node(
              Wff.ANALOG_CLOCK,
              bounds(0, 0, SIZE, SIZE),
              mapOf(
                "hands" to listOf(hand("hour", 120, 22, LIGHT), hand("minute", 170, 14, ACCENT)),
              ),
            ),
          )
        }
        Wff.CLOCK_HAND -> {
          put(dial())
          put(
            node(
              Wff.ANALOG_CLOCK,
              bounds(0, 0, SIZE, SIZE),
              mapOf("hands" to listOf(hand("minute", 180, 18, ACCENT))),
            ),
          )
        }
        Wff.PART_IMAGE ->
          put(
            node(
              Wff.PART_IMAGE,
              bounds(100, 100, 250, 250) + mapOf("resource" to string("photo")),
            ),
          )
        Wff.COMPLICATION_SLOT -> put(slot(null))
        Wff.COMPLICATION ->
          put(
            slot(
              node(
                Wff.COMPLICATION,
                mapOf("type" to string("SHORT_TEXT")),
                mapOf("content" to listOf(text("85%", listOf(0, 0, 300, 140), 90, ACCENT))),
              ),
            ),
          )
        Wff.VARIANT ->
          // An ambient override of the part it sits in: the part, drawn at its normal value.
          put(
            draw(
              shape("ellipse", listOf(75, 75, 300, 300), fill = ACCENT),
              extra =
                mapOf(
                  "variants" to
                    listOf(
                      node(
                        Wff.VARIANT,
                        mapOf(
                          "mode" to string("AMBIENT"),
                          "target" to string("alpha"),
                          "value" to string("100"),
                        ),
                      ),
                    ),
                ),
            ),
          )
        Wff.TRANSFORM ->
          // A hand the transform drives, held upright: a rotated layer reports no bounds to the
          // tile.
          put(
            node(
              Wff.GROUP,
              bounds(0, 0, SIZE, SIZE) +
                mapOf("pivotX" to string("0.5"), "pivotY" to string("0.5")),
              mapOf(
                "content" to
                  listOf(
                    node(
                      Wff.TRANSFORM,
                      mapOf("target" to string("angle"), "value" to string("0")),
                    ),
                    draw(
                      shape(
                        "round-rectangle",
                        listOf(205, 60, 40, 180),
                        fill = ACCENT,
                        corner = 20,
                      ),
                    ),
                  ),
              ),
            ),
          )
        Wff.GYRO ->
          // The part the tilt moves, drawn as a flat watch holds it.
          put(
            draw(
              shape("ellipse", listOf(95, 95, 260, 260), fill = WARM),
              extra =
                mapOf(
                  "variants" to
                    listOf(
                      node(
                        Wff.GYRO,
                        mapOf(
                          "x" to string("[ACCELEROMETER_ANGLE_X] * 2"),
                          "y" to string("[ACCELEROMETER_ANGLE_Y] * 2"),
                        ),
                      ),
                    ),
                ),
            ),
          )
        Wff.CONDITION ->
          put(
            node(
              Wff.CONDITION,
              emptyMap(),
              mapOf(
                "branches" to
                  listOf(compare(draw(shape("ellipse", listOf(75, 75, 300, 300), fill = ACCENT)))),
              ),
            ),
          )
        Wff.COMPARE ->
          put(
            node(
              Wff.CONDITION,
              emptyMap(),
              mapOf(
                "branches" to
                  listOf(
                    compare(
                      draw(
                        shape(
                          "round-rectangle",
                          listOf(75, 115, 300, 220),
                          fill = WARM,
                          corner = 50,
                        ),
                      ),
                    ),
                  ),
              ),
            ),
          )
        Wff.DEFAULT ->
          put(
            node(
              Wff.CONDITION,
              emptyMap(),
              mapOf(
                "branches" to
                  listOf(
                    node(
                      Wff.DEFAULT,
                      emptyMap(),
                      mapOf(
                        "content" to
                          listOf(
                            draw(
                              shape(
                                "ellipse",
                                listOf(95, 95, 260, 260),
                                stroke = LIGHT,
                                thickness = 24,
                              ),
                            ),
                          ),
                      ),
                    ),
                  ),
              ),
            ),
          )
        Wff.LAUNCH ->
          put(
            draw(
              shape("ellipse", listOf(95, 95, 260, 260), fill = ACCENT),
              extra =
                mapOf(
                  "variants" to
                    listOf(
                      node(Wff.LAUNCH, mapOf("target" to string("com.example.face/.Settings"))),
                    ),
                ),
            ),
          )
        Wff.USER_CONFIGURATIONS -> {
          face += node(Wff.USER_CONFIGURATIONS)
          swatches(ACCENT, WARM, LIGHT).forEach(::put)
        }
        Wff.COLOR_CONFIGURATION -> {
          face += node(Wff.COLOR_CONFIGURATION)
          swatches(ACCENT, WARM, "#ffb4e197").forEach(::put)
        }
        Wff.COLOR_OPTION -> {
          face += node(Wff.COLOR_OPTION)
          swatches(WARM, size = 180).forEach(::put)
        }
        Wff.LIST_CONFIGURATION -> {
          face += node(Wff.LIST_CONFIGURATION)
          (row("Arabic", 90, true) + row("Roman", 190, false) + row("Dots", 290, false)).forEach(
            ::put,
          )
        }
        Wff.LIST_OPTION -> {
          face += node(Wff.LIST_OPTION)
          row("Roman", 190, true).forEach(::put)
        }
        Wff.BOOLEAN_CONFIGURATION -> {
          face += node(Wff.BOOLEAN_CONFIGURATION)
          toggle().forEach(::put)
        }
        Wff.FLAVORS -> {
          face += node(Wff.FLAVORS)
          put(
            draw(
              shape(
                "ellipse",
                listOf(30, 125, 190, 190),
                fill = DIAL,
                stroke = ACCENT,
                thickness = 12,
              ),
            ),
          )
          put(
            draw(
              shape(
                "ellipse",
                listOf(230, 125, 190, 190),
                fill = DIAL,
                stroke = WARM,
                thickness = 12,
              ),
            ),
          )
        }
        Wff.FLAVOR -> {
          face += node(Wff.FLAVOR)
          put(
            draw(
              shape(
                "ellipse",
                listOf(75, 75, 300, 300),
                fill = DIAL,
                stroke = ACCENT,
                thickness = 16,
              ),
            ),
          )
          put(draw(shape("ellipse", listOf(195, 195, 60, 60), fill = ACCENT)))
        }
        Wff.FLAVOR_CONFIGURATION -> {
          face += node(Wff.FLAVOR_CONFIGURATION)
          row("Roman", 95, true).forEach(::put)
          swatches(ACCENT, WARM).forEach(::put)
        }
        Wff.RAW -> {
          face += node(Wff.RAW, mapOf("xml" to string("<!-- raw -->")))
          put(text("</>", listOf(25, 150, 400, 150), 130, ACCENT))
        }
      }
    }

    private fun compare(branch: String): String =
      node(
        Wff.COMPARE,
        mapOf("expression" to string("[CONFIGURATION.show]")),
        mapOf("content" to listOf(branch)),
      )

    private fun slot(complication: String?): String =
      node(
        Wff.COMPLICATION_SLOT,
        bounds(75, 155, 300, 140) +
          mapOf(
            "slotId" to int(0),
            "supportedTypes" to string("SHORT_TEXT"),
            "boundsType" to string("RECTANGLE"),
            "defaultSystemProvider" to string("WATCH_BATTERY"),
            "defaultSystemProviderType" to string("SHORT_TEXT"),
          ),
        if (complication == null) emptyMap() else mapOf("variants" to listOf(complication)),
      )
  }

  private fun int(value: Int) =
    JsonObject(mapOf("type" to JsonPrimitive("int"), "value" to JsonPrimitive(value)))

  private fun string(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))

  private fun color(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("color"), "value" to JsonPrimitive(value)))

  private fun angle(degrees: Int) = mapOf("angle" to int(degrees))

  private fun bounds(x: Int, y: Int, width: Int, height: Int) =
    mapOf("x" to int(x), "y" to int(y), "width" to int(width), "height" to int(height))
}
