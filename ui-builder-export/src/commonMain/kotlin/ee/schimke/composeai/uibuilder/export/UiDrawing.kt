package ee.schimke.composeai.uibuilder.export

/**
 * The drawing vocabulary: a `draw/canvas` node whose `ops` slot holds `draw/…` operation nodes.
 *
 * Remote Compose draws through `RemoteCanvas { }` — rectangles, circles, arcs, lines, paths, text,
 * transforms — and until this the builder could place none of it: a gauge, a watch hand or a
 * sparkline had no spelling at all. Each operation is a **node** rather than one opaque JSON blob,
 * so selection, comments, MCP edits and the layer tree keep working per shape, and every geometry
 * property is an ordinary property: a literal, a state read or, once the wire carries one, a
 * computed value the player evaluates.
 *
 * Declared once, here, and read by every lane: the runtime builds the catalog declarations from
 * [OPERATIONS], the canvas stand-in and the Remote Kotlin writer read the same property names.
 *
 * Geometry is in dp from the canvas's top-left; an absent box is the whole canvas, and a stroked
 * shape whose geometry is defaulted is inset by half its stroke so the stroke stays inside. Angles
 * are degrees clockwise from three o'clock, as the player draws arcs.
 */
object UiDrawing {
  const val CANVAS: String = "draw/canvas"
  const val GROUP: String = "draw/group"
  const val OPS_SLOT: String = "ops"

  /** The slot trait a canvas and a group accept, and only draw operations carry. */
  const val TRAIT: String = "DrawOperation"

  /** What a numeric property measures, which decides its editor range. */
  enum class Unit(val minimum: Double, val maximum: Double, val step: Double) {
    DP(-4096.0, 4096.0, 0.5),
    DEGREES(-720.0, 720.0, 1.0),
    FRACTION(0.0, 1.0, 0.05),
    SCALE(0.0, 16.0, 0.05),
    VIEWPORT(1.0, 4096.0, 1.0),
    SP(1.0, 512.0, 1.0),
  }

  sealed interface Property {
    val name: String
    val notes: String

    class Number(override val name: String, val unit: Unit, override val notes: String) : Property

    class Color(override val name: String, override val notes: String) : Property

    class Choice(
      override val name: String,
      val values: List<String>,
      override val notes: String,
    ) : Property

    class Text(override val name: String, override val notes: String) : Property

    class Flag(override val name: String, override val notes: String) : Property
  }

  class Operation(
    val componentId: String,
    val displayName: String,
    /** The `RemoteDrawScope` call it is written as. */
    val remoteCall: String,
    val properties: List<Property>,
    val container: Boolean = false,
  )

  private val color = Property.Color("color", "Fill or stroke colour. Black when absent.")
  private val style =
    Property.Choice("style", listOf("fill", "stroke"), "Fill the shape or stroke its outline.")
  private val strokeWidth = Property.Number("strokeWidthDp", Unit.DP, "Stroke width, when stroked.")
  private val strokeCap =
    Property.Choice(
      "strokeCap",
      listOf("butt", "round", "square"),
      "How a stroke's ends are drawn.",
    )
  private val alpha = Property.Number("alpha", Unit.FRACTION, "Opacity of the paint, 0 to 1.")
  private val paint = listOf(color, style, strokeWidth, strokeCap, alpha)

  private fun box(what: String) =
    listOf(
      Property.Number("xDp", Unit.DP, "Left edge of the $what. 0 when absent."),
      Property.Number("yDp", Unit.DP, "Top edge of the $what. 0 when absent."),
      Property.Number(
        "widthDp",
        Unit.DP,
        "Width of the $what. The rest of the canvas when absent.",
      ),
      Property.Number(
        "heightDp",
        Unit.DP,
        "Height of the $what. The rest of the canvas when absent.",
      ),
    )

  val OPERATIONS: List<Operation> =
    listOf(
      Operation(
        "draw/rect",
        "Rectangle",
        "drawRect",
        box("rectangle") +
          Property.Number(
            "cornerRadiusDp",
            Unit.DP,
            "Rounds the corners; written as drawRoundRect.",
          ) +
          paint,
      ),
      Operation(
        "draw/circle",
        "Circle",
        "drawCircle",
        listOf(
          Property.Number("centerXDp", Unit.DP, "Centre across. The canvas centre when absent."),
          Property.Number("centerYDp", Unit.DP, "Centre down. The canvas centre when absent."),
          Property.Number(
            "radiusDp",
            Unit.DP,
            "Radius. Half the canvas's shorter side when absent.",
          ),
        ) + paint,
      ),
      Operation("draw/oval", "Oval", "drawOval", box("oval's bounds") + paint),
      Operation(
        "draw/arc",
        "Arc",
        "drawArc",
        box("arc's oval") +
          listOf(
            Property.Number(
              "startAngle",
              Unit.DEGREES,
              "Where the arc starts; 0 is three o'clock.",
            ),
            Property.Number("sweepAngle", Unit.DEGREES, "How far it sweeps, clockwise."),
            Property.Flag("useCenter", "Close the arc through the centre, as a pie slice."),
          ) +
          paint,
      ),
      Operation(
        "draw/line",
        "Line",
        "drawLine",
        listOf(
          Property.Number("startXDp", Unit.DP, "Start across."),
          Property.Number("startYDp", Unit.DP, "Start down."),
          Property.Number("endXDp", Unit.DP, "End across."),
          Property.Number("endYDp", Unit.DP, "End down."),
          color,
          strokeWidth,
          strokeCap,
          alpha,
        ),
      ),
      Operation(
        "draw/path",
        "Path",
        "drawPath",
        listOf(
          Property.Text("pathData", "SVG path data, e.g. `M2 12 L12 2 L22 12 Z`."),
          Property.Number(
            "viewportWidth",
            Unit.VIEWPORT,
            "The width the path data is drawn in; scaled to the canvas. 24 when absent.",
          ),
          Property.Number(
            "viewportHeight",
            Unit.VIEWPORT,
            "The height the path data is drawn in; scaled to the canvas. 24 when absent.",
          ),
        ) + paint,
      ),
      Operation(
        "draw/text",
        "Text",
        "drawAnchoredText",
        listOf(
          Property.Text("text", "The text to draw."),
          Property.Number("xDp", Unit.DP, "Anchor across. The canvas centre when absent."),
          Property.Number("yDp", Unit.DP, "Anchor down, at the text's middle. Centre when absent."),
          Property.Number("textSizeSp", Unit.SP, "Text size. 14 when absent."),
          Property.Choice(
            "align",
            listOf("start", "center", "end"),
            "Which end of the text sits at the anchor.",
          ),
          color,
          alpha,
        ),
      ),
      Operation(
        GROUP,
        "Transform group",
        "withTransform",
        listOf(
          Property.Number("translateXDp", Unit.DP, "Moves its operations across."),
          Property.Number("translateYDp", Unit.DP, "Moves its operations down."),
          Property.Number("rotate", Unit.DEGREES, "Turns its operations about the pivot."),
          Property.Number("scale", Unit.SCALE, "Scales its operations about the pivot."),
          Property.Number("pivotXDp", Unit.DP, "Pivot across. The canvas centre when absent."),
          Property.Number("pivotYDp", Unit.DP, "Pivot down. The canvas centre when absent."),
        ),
        container = true,
      ),
    )

  val BY_ID: Map<String, Operation> = OPERATIONS.associateBy { it.componentId }

  /** Every id in the vocabulary, the canvas first. */
  val COMPONENT_IDS: List<String> = listOf(CANVAS) + OPERATIONS.map { it.componentId }

  fun isDrawing(componentId: String): Boolean = componentId == CANVAS || componentId in BY_ID
}
