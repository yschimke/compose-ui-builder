package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An analogue clock drawn on a `RemoteCanvas`: the worked example of `remote-m3` content that
 * moves.
 *
 * Plain Remote Compose content rather than a Wear widget — the root is a `layout/box`, not a widget
 * container — so it exports as the `@RemoteComposable` body an application captures and plays
 * itself, and opens on a square frame rather than a host.
 *
 * Every part is drawn about the canvas centre, which is where a `draw/group` pivots when it names
 * no pivot, so a hand is a vertical line from the centre up to twelve o'clock, turned by its group:
 * - the hour marks are one tick in a `draw/repeat` over `hour` from 0 to 12, turned by `@hour *
 *   30`, with the quarter hours drawn at full strength and the rest dimmed;
 * - the hour hand turns half a degree a minute, `(time.minuteOfDay % 720) / 2`;
 * - the minute hand a tenth of a degree a second, `time.secondOfHour / 10`;
 * - the second hand six degrees a second, read continuously so it sweeps rather than ticks.
 *
 * No state drives any of it. The canvas evaluates the formulas at the environment's fixed time; the
 * player evaluates them against its own clock every frame.
 */
object RemoteClockTemplate {

  const val TEMPLATE_ID: String = "remote-clock"

  const val LABEL: String = "Clock"

  const val SUPPORTING_TEXT: String =
    "An analogue clock on a RemoteCanvas: a loop draws the hour marks and the hands turn with " +
      "the time."

  /** The frame and the face: square, with a margin round the face. */
  const val FRAME_DP: Int = 200

  private const val FACE_DP: Int = 180

  private const val CENTRE: Double = FACE_DP / 2.0

  fun document(designId: String, catalogPin: JsonObject, environment: JsonObject) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = designId,
      title = LABEL,
      revision = 0,
      catalogPin = catalogPin,
      environment =
        JsonObject(
          environment +
            mapOf("widthDp" to JsonPrimitive(FRAME_DP), "heightDp" to JsonPrimitive(FRAME_DP))
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("clock"),
      nodes = nodes().associateBy(UiBuilderNode::id),
    )

  private fun nodes(): List<UiBuilderNode> {
    val face =
      drawing(
        "clock-face",
        "draw/circle",
        "radiusDp" to number(CENTRE - 2),
        "color" to colorToken("surfaceContainerHigh"),
      )
    val ticks =
      container(
        "clock-ticks",
        UiDrawing.REPEAT,
        JsonObject(
          mapOf(
            "until" to number(12),
            "index" to literal("string", JsonPrimitive("hour")),
          )
        ),
        "clock-tick",
      )
    val tick =
      turned(
        "clock-tick",
        "@hour * 30",
        drawing(
          "clock-tick-mark",
          "draw/line",
          "startXDp" to number(CENTRE),
          "startYDp" to number(16),
          "endXDp" to number(CENTRE),
          "endYDp" to number(28),
          "strokeWidthDp" to number(4),
          "strokeCap" to literal("enum", JsonPrimitive("round")),
          "color" to colorToken("onSurface"),
          "alpha" to formula("select(@hour % 3 == 0, 1.0, 0.4)"),
        ),
      )
    val hub =
      drawing("clock-hub", "draw/circle", "radiusDp" to number(6), "color" to colorToken("primary"))
    val hands =
      hand("clock-hour", "(time.minuteOfDay % 720) / 2", length = 44, width = 8, "onSurface") +
        hand("clock-minute", "time.secondOfHour / 10", length = 66, width = 5, "onSurface") +
        hand(
          "clock-second",
          "(time.continuousSecond % 60) * 6",
          length = 72,
          width = 2,
          "primary",
          tail = 14,
        )
    val canvas =
      UiBuilderNode(
        id = "clock-canvas",
        componentId = UiDrawing.CANVAS,
        properties = JsonObject(emptyMap()),
        modifiers =
          JsonArray(
            listOf(
              JsonObject(
                mapOf(
                  "type" to JsonPrimitive("size"),
                  "widthDp" to JsonPrimitive(FACE_DP),
                  "heightDp" to JsonPrimitive(FACE_DP),
                )
              ),
              JsonObject(
                mapOf("type" to JsonPrimitive("align"), "alignment" to JsonPrimitive("center"))
              ),
            )
          ),
        slots =
          mapOf(
            UiDrawing.OPS_SLOT to
              listOf(
                face.id,
                ticks.id,
                "clock-hour-hand",
                "clock-minute-hand",
                "clock-second-hand",
                hub.id,
              )
          ),
      )
    val root =
      UiBuilderNode(
        id = "clock",
        componentId = "layout/box",
        properties = JsonObject(emptyMap()),
        modifiers = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
        slots = mapOf("children" to listOf(canvas.id)),
      )
    return listOf(root, canvas, face, ticks) + tick + hands + hub
  }

  /** A hand: a round-capped line from [tail] below the centre to [length] above it. */
  private fun hand(
    id: String,
    rotate: String,
    length: Int,
    width: Int,
    color: String,
    tail: Int = 0,
  ) =
    turned(
      "$id-hand",
      rotate,
      drawing(
        id,
        "draw/line",
        "startXDp" to number(CENTRE),
        "startYDp" to number(CENTRE + tail),
        "endXDp" to number(CENTRE),
        "endYDp" to number(CENTRE - length),
        "strokeWidthDp" to number(width),
        "strokeCap" to literal("enum", JsonPrimitive("round")),
        "color" to colorToken(color),
      ),
    )

  /** [op] inside a `draw/group` turned by [rotate] about the canvas centre. */
  private fun turned(id: String, rotate: String, op: UiBuilderNode) =
    listOf(
      container(id, UiDrawing.GROUP, JsonObject(mapOf("rotate" to formula(rotate))), op.id),
      op,
    )

  private fun container(id: String, componentId: String, properties: JsonObject, child: String) =
    UiBuilderNode(
      id = id,
      componentId = componentId,
      properties = properties,
      modifiers = JsonArray(emptyList()),
      slots = mapOf(UiDrawing.OPS_SLOT to listOf(child)),
    )

  private fun drawing(
    id: String,
    componentId: String,
    vararg properties: Pair<String, JsonObject>,
  ) =
    UiBuilderNode(
      id = id,
      componentId = componentId,
      properties = JsonObject(properties.toMap()),
      modifiers = JsonArray(emptyList()),
      slots = emptyMap(),
    )

  private fun formula(text: String): JsonObject = UiExpressions.parseFormula(text, emptySet())

  private fun number(value: Number): JsonObject = literal("float", JsonPrimitive(value))

  private fun colorToken(token: String): JsonObject = literal("colorToken", JsonPrimitive(token))

  private fun literal(type: String, value: JsonPrimitive): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))
}
