package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds a design whose content is one square `draw/canvas`: the shape every clock template is.
 *
 * Plain Remote Compose content rather than a Wear widget — the root is a `layout/box` holding the
 * canvas, not a widget container — so it exports as the `@RemoteComposable` body an application
 * captures and plays itself, and opens on a square frame rather than a host.
 *
 * The builder only spells the document: operations are added in draw order, a container's block
 * fills its `ops` slot, and every value is one of the property wrappers below. Formulas are parsed
 * by [UiExpressions.parseFormula], so a template reads as the text the inspector shows.
 */
class ClockCanvas private constructor(private val sizeDp: Int) {
  private val nodes = mutableListOf<UiBuilderNode>()
  private val open = ArrayDeque<MutableList<String>>().apply { addLast(mutableListOf()) }

  /** The canvas centre, where a group turns and a circle sits when neither names a point. */
  val centre: Double = sizeDp / 2.0

  /** Draws [componentId] with [properties]. */
  fun op(id: String, componentId: String, vararg properties: Pair<String, JsonObject>) {
    nodes += node(id, componentId, properties.toMap(), emptyMap())
    open.last() += id
  }

  /** A container: [block]'s operations are drawn inside it. */
  fun container(
    id: String,
    componentId: String,
    vararg properties: Pair<String, JsonObject>,
    block: ClockCanvas.() -> Unit,
  ) {
    val children = mutableListOf<String>()
    open.addLast(children)
    block()
    open.removeLast()
    nodes += node(id, componentId, properties.toMap(), mapOf(UiDrawing.OPS_SLOT to children))
    open.last() += id
  }

  /** [block] turned by the formula [rotate], about ([pivotX], [pivotY]) or the canvas centre. */
  fun turned(
    id: String,
    rotate: String,
    pivotX: Number? = null,
    pivotY: Number? = null,
    block: ClockCanvas.() -> Unit,
  ) {
    val pivot =
      listOfNotNull(pivotX?.let { "pivotXDp" to num(it) }, pivotY?.let { "pivotYDp" to num(it) })
    container(
      id,
      UiDrawing.GROUP,
      "rotate" to formula(rotate),
      *pivot.toTypedArray(),
      block = block,
    )
  }

  /** [block] drawn [count] times, reading the index as `@[index]`. */
  fun repeat(id: String, count: Int, index: String, block: ClockCanvas.() -> Unit) =
    container(id, UiDrawing.REPEAT, "until" to num(count), "index" to text(index), block = block)

  fun circle(
    id: String,
    radius: Number,
    color: JsonObject,
    x: Number? = null,
    y: Number? = null,
    vararg paint: Pair<String, JsonObject>,
  ) =
    op(
      id,
      "draw/circle",
      "radiusDp" to num(radius),
      "color" to color,
      *listOfNotNull(x?.let { "centerXDp" to num(it) }, y?.let { "centerYDp" to num(it) })
        .toTypedArray(),
      *paint,
    )

  /** A round-capped line. */
  fun line(
    id: String,
    from: Pair<Number, Number>,
    to: Pair<Number, Number>,
    width: Number,
    color: JsonObject,
    vararg paint: Pair<String, JsonObject>,
  ) =
    op(
      id,
      "draw/line",
      "startXDp" to num(from.first),
      "startYDp" to num(from.second),
      "endXDp" to num(to.first),
      "endYDp" to num(to.second),
      "strokeWidthDp" to num(width),
      "strokeCap" to enum("round"),
      "color" to color,
      *paint,
    )

  fun rect(
    id: String,
    x: Number,
    y: Number,
    width: Number,
    height: Number,
    color: JsonObject,
    corner: Number = 0,
    vararg paint: Pair<String, JsonObject>,
  ) =
    op(
      id,
      "draw/rect",
      "xDp" to num(x),
      "yDp" to num(y),
      "widthDp" to num(width),
      "heightDp" to num(height),
      "color" to color,
      *(if (corner.toDouble() > 0) arrayOf("cornerRadiusDp" to num(corner)) else emptyArray()),
      *paint,
    )

  /** Text whose middle sits at ([x], [y]), anchored by [align]. */
  fun label(
    id: String,
    value: JsonObject,
    x: Number,
    y: Number,
    sizeSp: Number,
    color: JsonObject,
    align: String = "center",
    vararg paint: Pair<String, JsonObject>,
  ) =
    op(
      id,
      "draw/text",
      "text" to value,
      "xDp" to num(x),
      "yDp" to num(y),
      "textSizeSp" to num(sizeSp),
      "align" to enum(align),
      "color" to color,
      *paint,
    )

  private fun node(
    id: String,
    componentId: String,
    properties: Map<String, JsonObject>,
    slots: Map<String, List<String>>,
  ): UiBuilderNode {
    require(nodes.none { it.id == id }) { "`$id` is drawn twice" }
    return UiBuilderNode(
      id = id,
      componentId = componentId,
      properties = JsonObject(properties),
      modifiers = JsonArray(emptyList()),
      slots = slots,
    )
  }

  companion object {
    /** The instant a clock opens at in the editor: Thursday 16 May 2024, 10:10:30 UTC. */
    const val PREVIEW_TIME: String = "2024-05-16T10:10:30Z"

    /** The frame and canvas side every clock is drawn at. */
    const val SIZE_DP: Int = 200

    /**
     * A document whose root box holds one [sizeDp]-square canvas, centred, drawn by [draw].
     *
     * The canvas id is `"$rootId-canvas"`.
     */
    fun document(
      designId: String,
      title: String,
      rootId: String,
      catalogPin: JsonObject,
      environment: JsonObject,
      sizeDp: Int = SIZE_DP,
      draw: ClockCanvas.() -> Unit,
    ): UiBuilderDocument {
      val canvas = ClockCanvas(sizeDp).apply(draw)
      val canvasId = "$rootId-canvas"
      val canvasNode =
        UiBuilderNode(
          id = canvasId,
          componentId = UiDrawing.CANVAS,
          properties = JsonObject(emptyMap()),
          modifiers =
            JsonArray(
              listOf(
                JsonObject(
                  mapOf(
                    "type" to JsonPrimitive("size"),
                    "widthDp" to JsonPrimitive(sizeDp),
                    "heightDp" to JsonPrimitive(sizeDp),
                  )
                ),
                JsonObject(
                  mapOf("type" to JsonPrimitive("align"), "alignment" to JsonPrimitive("center"))
                ),
              )
            ),
          slots = mapOf(UiDrawing.OPS_SLOT to canvas.open.single()),
        )
      val root =
        UiBuilderNode(
          id = rootId,
          componentId = "layout/box",
          properties = JsonObject(emptyMap()),
          modifiers = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
          slots = mapOf("children" to listOf(canvasId)),
        )
      return UiBuilderDocument(
        schema = "compose-ui-builder-document/v1-candidate",
        id = designId,
        title = title,
        revision = 0,
        catalogPin = catalogPin,
        environment =
          JsonObject(
            environment +
              mapOf(
                "widthDp" to JsonPrimitive(sizeDp),
                "heightDp" to JsonPrimitive(sizeDp),
                // 10:10:30, the watch-face convention: at a fixture's noon every hand stacks on
                // twelve, and the picture cannot show that each turns by its own formula.
                "fixedTime" to JsonPrimitive(PREVIEW_TIME),
              )
          ),
        stateVariables = JsonObject(emptyMap()),
        roots = listOf(rootId),
        nodes = (listOf(root, canvasNode) + canvas.nodes).associateBy(UiBuilderNode::id),
      )
    }

    fun num(value: Number): JsonObject = literal("float", JsonPrimitive(value))

    fun formula(text: String): JsonObject = UiExpressions.parseFormula(text, emptySet())

    fun token(role: String): JsonObject = literal("colorToken", JsonPrimitive(role))

    fun argb(hex: String): JsonObject = literal("color", JsonPrimitive(hex))

    fun text(value: String): JsonObject = literal("string", JsonPrimitive(value))

    fun enum(value: String): JsonObject = literal("enum", JsonPrimitive(value))

    fun flag(value: Boolean): JsonObject = literal("bool", JsonPrimitive(value))

    /** [value] as two digits, `07`: a formula over a whole number below 100. */
    fun twoDigits(value: String): String =
      "select($value < 10, concat(\"0\", toString($value)), toString($value))"

    /** The day of the week as text, from `time.dayOfWeek` (1 = Monday). */
    fun dayName(names: List<String>): String {
      require(names.size == 7) { "a week has seven days" }
      return names.dropLast(1).foldRightIndexed("\"${names.last()}\"") { index, name, rest ->
        "select(time.dayOfWeek == ${index + 1}, \"$name\", $rest)"
      }
    }

    /**
     * A circle as SVG path data, in four cubic Béziers rather than arcs: every path writer the
     * export reaches draws a cubic, and the eye cannot tell the two apart.
     */
    fun circlePath(x: Double, y: Double, r: Double): String {
      val k = r * 0.5523
      fun p(a: Double, b: Double) = "${a.format()} ${b.format()}"
      return "M${p(x, y - r)} " +
        "C${p(x + k, y - r)} ${p(x + r, y - k)} ${p(x + r, y)} " +
        "C${p(x + r, y + k)} ${p(x + k, y + r)} ${p(x, y + r)} " +
        "C${p(x - k, y + r)} ${p(x - r, y + k)} ${p(x - r, y)} " +
        "C${p(x - r, y - k)} ${p(x - k, y - r)} ${p(x, y - r)} Z"
    }

    private fun Double.format(): String {
      val rounded = kotlin.math.round(this * 100) / 100
      return if (rounded == kotlin.math.floor(rounded)) rounded.toLong().toString()
      else rounded.toString()
    }

    private fun literal(type: String, value: JsonPrimitive): JsonObject =
      JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))
  }
}
