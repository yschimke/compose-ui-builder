package ee.schimke.composeai.uibuilder.preview

import ee.schimke.composeai.uibuilder.export.ClockCanvas
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.enum
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.formula
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.num
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.text
import ee.schimke.composeai.uibuilder.export.UiDrawing
import kotlin.math.round
import kotlinx.serialization.json.JsonObject

/*
 * Drawing helpers the two watch replicas share: SVG paths in canvas coordinates, clips to a path,
 * and seven-segment LCD digits. Every path is drawn in a viewport the size of the canvas, so its
 * numbers are dp from the canvas's top-left.
 */

/** [d] filled with [color] — or stroked, given `"style" to enum("stroke")` in [paint]. */
internal fun ClockCanvas.path(
  id: String,
  d: String,
  color: JsonObject,
  vararg paint: Pair<String, JsonObject>,
) =
  op(
    id,
    "draw/path",
    "pathData" to text(d),
    "viewportWidth" to num(ClockCanvas.SIZE_DP),
    "viewportHeight" to num(ClockCanvas.SIZE_DP),
    "color" to color,
    *paint,
  )

/**
 * A curve [width] wide through [points], as a filled ribbon rather than a stroked path.
 *
 * A stroked `draw/path` scales its stroke with the path's viewport as well as the density, so a
 * hairline comes out several times too thick; a filled shape scales exactly. Every curved line on
 * these watches — a glint along a bevel, a layer of the carbon case — is therefore one of these.
 * [taper] narrows both ends to points, as a reflection fades at its ends.
 */
internal fun ClockCanvas.band(
  id: String,
  points: List<Pair<Double, Double>>,
  width: Double,
  color: JsonObject,
  vararg paint: Pair<String, JsonObject>,
  taper: Boolean = true,
) {
  val left = mutableListOf<Pair<Double, Double>>()
  val right = mutableListOf<Pair<Double, Double>>()
  points.forEachIndexed { index, (x, y) ->
    val (ax, ay) = points[maxOf(0, index - 1)]
    val (bx, by) = points[minOf(points.lastIndex, index + 1)]
    val length = kotlin.math.hypot(bx - ax, by - ay).takeIf { it > 0 } ?: 1.0
    val nx = -(by - ay) / length
    val ny = (bx - ax) / length
    val t = index.toDouble() / points.lastIndex
    val half =
      width / 2 * if (taper) kotlin.math.sin(t * kotlin.math.PI).coerceAtLeast(0.15) else 1.0
    left += (x + nx * half) to (y + ny * half)
    right += (x - nx * half) to (y - ny * half)
  }
  path(id, polygon(*(left + right.reversed()).toTypedArray()), color, *paint)
}

/** [steps] + 1 points along the cubic from [p0] to [p3]. */
internal fun cubic(
  p0: Pair<Double, Double>,
  p1: Pair<Double, Double>,
  p2: Pair<Double, Double>,
  p3: Pair<Double, Double>,
  steps: Int = 18,
): List<Pair<Double, Double>> =
  (0..steps).map { i ->
    val t = i.toDouble() / steps
    val u = 1 - t
    val x =
      u * u * u * p0.first +
        3 * u * u * t * p1.first +
        3 * u * t * t * p2.first +
        t * t * t * p3.first
    val y =
      u * u * u * p0.second +
        3 * u * u * t * p1.second +
        3 * u * t * t * p2.second +
        t * t * t * p3.second
    x to y
  }

/** The outline of a rounded rectangle, [width] wide: a stroked `draw/rect`, which is in dp. */
internal fun ClockCanvas.outline(
  id: String,
  x: Number,
  y: Number,
  w: Number,
  h: Number,
  corner: Number,
  width: Number,
  color: JsonObject,
  vararg paint: Pair<String, JsonObject>,
) =
  rect(
    id,
    x,
    y,
    w,
    h,
    color,
    corner,
    "style" to enum("stroke"),
    "strokeWidthDp" to num(width),
    *paint,
  )

/** [block] drawn only inside [d]. */
internal fun ClockCanvas.clipped(id: String, d: String, block: ClockCanvas.() -> Unit) =
  container(
    id,
    UiDrawing.CLIP,
    "pathData" to text(d),
    "viewportWidth" to num(ClockCanvas.SIZE_DP),
    "viewportHeight" to num(ClockCanvas.SIZE_DP),
    block = block,
  )

/** A closed polygon through [points]. */
internal fun polygon(vararg points: Pair<Number, Number>): String =
  points
    .mapIndexed { index, (x, y) -> (if (index == 0) "M" else "L") + "${x.d()} ${y.d()}" }
    .joinToString(" ", postfix = " Z")

/** A rounded rectangle as path data, for clips and strokes that follow a rounded edge. */
internal fun roundRectPath(x: Double, y: Double, w: Double, h: Double, r: Double): String {
  val k = r * 0.4477
  return "M${(x + r).d()} ${y.d()} L${(x + w - r).d()} ${y.d()} " +
    "C${(x + w - k).d()} ${y.d()} ${(x + w).d()} ${(y + k).d()} ${(x + w).d()} ${(y + r).d()} " +
    "L${(x + w).d()} ${(y + h - r).d()} " +
    "C${(x + w).d()} ${(y + h - k).d()} ${(x + w - k).d()} ${(y + h).d()} " +
    "${(x + w - r).d()} ${(y + h).d()} " +
    "L${(x + r).d()} ${(y + h).d()} " +
    "C${(x + k).d()} ${(y + h).d()} ${x.d()} ${(y + h - k).d()} ${x.d()} ${(y + h - r).d()} " +
    "L${x.d()} ${(y + r).d()} " +
    "C${x.d()} ${(y + k).d()} ${(x + k).d()} ${y.d()} ${(x + r).d()} ${y.d()} Z"
}

/** Two decimals at most, as path data wants. */
internal fun Number.d(): String {
  val value = round(toDouble() * 100) / 100
  return if (value == kotlin.math.floor(value)) value.toLong().toString() else value.toString()
}

/** The segments a digit 0–9 lights, as the formula over the digit `$d` that is true when lit. */
private val SEGMENT_LIT: Map<Char, (String) -> String> =
  mapOf(
    'a' to { d -> "!($d == 1 || $d == 4)" },
    'b' to { d -> "!($d == 5 || $d == 6)" },
    'c' to { d -> "$d != 2" },
    'd' to { d -> "!($d == 1 || $d == 4 || $d == 7)" },
    'e' to { d -> "$d == 0 || $d == 2 || $d == 6 || $d == 8" },
    'f' to { d -> "!($d == 1 || $d == 2 || $d == 3 || $d == 7)" },
    'g' to { d -> "!($d == 0 || $d == 1 || $d == 7)" },
  )

/**
 * One seven-segment LCD digit in the box ([x], [y], [w], [h]), showing the formula [digit] (a whole
 * number 0–9): each segment a slanted hexagon, lit at full ink and otherwise a faint ghost, as an
 * LCD's unlit segments show. [blankWhenZero] leaves a leading zero unlit.
 */
internal fun ClockCanvas.segmentDigit(
  id: String,
  digit: String,
  x: Double,
  y: Double,
  w: Double,
  h: Double,
  thickness: Double,
  ink: JsonObject,
  ghost: Double = 0.07,
  blankWhenZero: Boolean = false,
  slant: Double = 0.1,
) {
  val t = thickness
  val gap = t * 0.22
  val left = x + t / 2
  val right = x + w - t / 2
  val top = y + t / 2
  val mid = y + h / 2
  val bottom = y + h - t / 2
  val base = y + h
  fun italic(px: Double, py: Double) = (px + (base - py) * slant) to py
  fun horizontal(yc: Double, xl: Double, xr: Double) =
    polygon(
      italic(xl, yc),
      italic(xl + t / 2, yc - t / 2),
      italic(xr - t / 2, yc - t / 2),
      italic(xr, yc),
      italic(xr - t / 2, yc + t / 2),
      italic(xl + t / 2, yc + t / 2),
    )
  fun vertical(xc: Double, yt: Double, yb: Double) =
    polygon(
      italic(xc, yt),
      italic(xc + t / 2, yt + t / 2),
      italic(xc + t / 2, yb - t / 2),
      italic(xc, yb),
      italic(xc - t / 2, yb - t / 2),
      italic(xc - t / 2, yt + t / 2),
    )
  val shapes =
    mapOf(
      'a' to horizontal(top, left + gap, right - gap),
      'g' to horizontal(mid, left + gap, right - gap),
      'd' to horizontal(bottom, left + gap, right - gap),
      'f' to vertical(left, top + gap, mid - gap),
      'b' to vertical(right, top + gap, mid - gap),
      'e' to vertical(left, mid + gap, bottom - gap),
      'c' to vertical(right, mid + gap, bottom - gap),
    )
  shapes.forEach { (segment, shape) ->
    val lit = SEGMENT_LIT.getValue(segment)("($digit)")
    val on = if (blankWhenZero) "($digit) != 0 && ($lit)" else lit
    path("$id-$segment", shape, ink, "alpha" to formula("select($on, 0.92, $ghost)"))
  }
}

/** A spline screw head: a disc, its six splines, and the light catching its domed top-left. */
internal fun ClockCanvas.splineScrew(
  id: String,
  x: Double,
  y: Double,
  r: Double,
  head: JsonObject,
  rim: JsonObject,
  spline: JsonObject,
  turn: Double = 0.0,
) {
  circle(
    "$id-shadow",
    r + 0.9,
    ClockCanvas.argb("#FF000000"),
    x + 0.5,
    y + 0.8,
    "alpha" to num(0.45),
  )
  circle("$id-head", r, head, x, y)
  circle(
    "$id-rim",
    r - 0.3,
    rim,
    x,
    y,
    "style" to enum("stroke"),
    "strokeWidthDp" to num(0.8),
  )
  turned("$id-splines", "$turn", x, y) {
    repeat("$id-spline-set", 6, "k") {
      turned("$id-spline", "@k * 60", x, y) {
        rect("$id-spline-slot", x - r * 0.16, y - r * 0.62, r * 0.32, r * 0.5, spline, r * 0.08)
      }
    }
    circle("$id-socket", r * 0.28, spline, x, y)
  }
  op(
    "$id-glint",
    "draw/arc",
    "xDp" to num(x - r + 0.9),
    "yDp" to num(y - r + 0.9),
    "widthDp" to num(2 * r - 1.8),
    "heightDp" to num(2 * r - 1.8),
    "startAngle" to num(195),
    "sweepAngle" to num(80),
    "style" to enum("stroke"),
    "strokeWidthDp" to num(0.9),
    "strokeCap" to enum("round"),
    "color" to ClockCanvas.argb("#FFFFFFFF"),
    "alpha" to num(0.55),
  )
}
