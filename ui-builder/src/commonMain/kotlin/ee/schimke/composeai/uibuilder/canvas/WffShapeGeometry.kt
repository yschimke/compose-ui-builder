package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter

/**
 * A `<LinearGradient>`, `<RadialGradient>` or `<SweepGradient>` inside a `<Fill>` or `<Stroke>`,
 * read from the `wff/raw` child that carries it. Its coordinates are face pixels in the part, as
 * the shape's are; [brush] lays it out at a scale.
 */
internal class WffGradient(
  /** The element: `LinearGradient`, `RadialGradient` or `SweepGradient`. */
  val kind: String,
  /** The numeric attributes: `startX`…`endY`, `centerX`, `centerY`, `radius`, the angles. */
  val numbers: Map<String, Float>,
  val clockwise: Boolean,
  val colors: List<Color>,
  /** Where each colour sits, 0 to 1. */
  val positions: List<Float>,
) {
  /** The gradient as a brush, [k] canvas pixels to a face pixel. */
  fun brush(k: Float): Brush {
    fun at(name: String) = (numbers[name] ?: 0f) * k
    val stops = positions.zip(colors).toTypedArray()
    return when (kind) {
      LINEAR -> {
        val start = Offset(at("startX"), at("startY"))
        val end = Offset(at("endX"), at("endY"))
        // Skia has no direction for a gradient of no length; WFF draws its last colour.
        if (start == end) SolidColor(colors.last())
        else Brush.linearGradient(*stops, start = start, end = end)
      }
      RADIAL -> {
        val radius = at("radius")
        if (radius <= 0f) SolidColor(colors.last())
        else
          Brush.radialGradient(
            *stops,
            center = Offset(at("centerX"), at("centerY")),
            radius = radius,
          )
      }
      else ->
        Brush.sweepGradient(
          *sweepStops(
            numbers["startAngle"] ?: 0f,
            numbers["endAngle"] ?: 360f,
            clockwise,
            positions,
            colors,
          ),
          center = Offset(at("centerX"), at("centerY")),
        )
    }
  }

  companion object {
    const val LINEAR = "LinearGradient"
    const val RADIAL = "RadialGradient"
    const val SWEEP = "SweepGradient"

    private val ATTRIBUTE = Regex("""(\w+)\s*=\s*"([^"]*)"""")
    private val TAG = Regex("""^\s*<(LinearGradient|RadialGradient|SweepGradient)\b""")

    /**
     * The gradient a `wff/fill` or `wff/stroke` holds as a `wff/raw` child, or null: none, or one
     * whose colours are not all literals (a `[CONFIGURATION…]` colour has no preview value), so the
     * paint's flat colour is drawn instead. A number may be an expression, read at [preview].
     */
    fun of(document: UiBuilderDocument, paint: UiBuilderNode?, preview: WffPreviewContext) =
      paint
        ?.slots
        ?.get("content")
        .orEmpty()
        .asSequence()
        .mapNotNull { document.nodes[it] }
        .filter { it.componentId == WatchFaceXmlExporter.RAW }
        .firstNotNullOfOrNull { parse(it.string("xml"), preview) }

    fun parse(xml: String, preview: WffPreviewContext = WffPreviewContext()): WffGradient? {
      val kind = TAG.find(xml)?.groupValues?.get(1) ?: return null
      val head = xml.substringBefore('>')
      val attributes = ATTRIBUTE.findAll(head).associate { it.groupValues[1] to it.groupValues[2] }
      val colors =
        attributes["colors"]
          ?.trim()
          ?.split(Regex("\\s+"))
          ?.filter { it.isNotEmpty() }
          ?.map { color -> parseWffColor(color) ?: return null }
          .orEmpty()
      if (colors.isEmpty()) return null
      // One colour is a flat paint; Skia needs two stops for a gradient.
      val drawn = if (colors.size == 1) colors + colors else colors
      val stated =
        attributes["positions"]
          ?.trim()
          ?.split(Regex("\\s+"))
          ?.filter { it.isNotEmpty() }
          ?.map { it.toFloatOrNull()?.coerceIn(0f, 1f) ?: return null }
          ?.takeIf { it.size == drawn.size && it.zipWithNext().all { (a, b) -> a <= b } }
      // An absent or empty `positions` spreads the colours evenly, as the format says.
      val positions = stated ?: drawn.indices.map { it / (drawn.size - 1).toFloat() }
      val numbers =
        attributes
          .filterKeys { it in NUMERIC }
          .mapNotNull { (name, value) ->
            val number =
              value.trim().toFloatOrNull()
                ?: evaluateWffExpression(value, preview)?.asNumber()?.toFloat()
            number?.takeIf { it.isFinite() }?.let { name to it }
          }
          .toMap()
      return WffGradient(
        kind,
        numbers,
        clockwise = attributes["direction"] != "COUNTER_CLOCKWISE",
        drawn,
        positions,
      )
    }

    private val NUMERIC =
      setOf(
        "startX",
        "startY",
        "endX",
        "endY",
        "centerX",
        "centerY",
        "radius",
        "startAngle",
        "endAngle",
      )
  }
}

/** `#AARRGGBB` or `#RRGGBB`, as WFF writes a colour; null for anything else. */
internal fun parseWffColor(value: String): Color? {
  val hex = value.removePrefix("#")
  if (!value.startsWith("#") || (hex.length != 6 && hex.length != 8)) return null
  val argb = hex.toLongOrNull(16) ?: return null
  return Color(if (hex.length == 6) argb or 0xff000000 else argb)
}

/**
 * The sweep of a WFF arc, in Compose's degrees: WFF measures from twelve o'clock and an angle grows
 * clockwise (the format's own samples sweep a gauge from -150 to 150 through the top). The arc runs
 * from [start] to [end] in its direction: clockwise the long way round when [end] is before
 * [start], counter-clockwise the other way, a whole turn when they are 360° or more apart either
 * way round. Equal ends sweep nothing.
 */
internal fun wffArcSweep(start: Float, end: Float, clockwise: Boolean): Float {
  val span = if (clockwise) end - start else start - end
  val turn =
    when {
      // A whole turn whichever way it is written: 0 to 360 counter-clockwise is -360 here.
      kotlin.math.abs(span) >= 360f -> 360f
      span < 0f -> ((span % 360f) + 360f) % 360f
      else -> span
    }
  return if (clockwise || turn == 0f) turn else -turn
}

/** Compose's start angle (three o'clock, clockwise) for a WFF angle (twelve o'clock, clockwise). */
internal fun composeAngle(wffDegrees: Float): Float = wffDegrees - 90f

/**
 * The colour stops of a WFF sweep gradient as Compose's `sweepGradient` takes them, which always
 * starts at three o'clock and turns once clockwise. WFF's runs from [start] to [end] in its
 * direction; past the end it keeps its last colour until the turn comes back round to the start,
 * where it jumps back to the first, the hard edge a conic gradient has.
 */
internal fun sweepStops(
  start: Float,
  end: Float,
  clockwise: Boolean,
  positions: List<Float>,
  colors: List<Color>,
): Array<Pair<Float, Color>> {
  val sweep = wffArcSweep(start, end, clockwise)
  val span = kotlin.math.abs(sweep)
  // Where (0 to 1 of Compose's turn) a WFF angle falls.
  fun fraction(wffDegrees: Float): Float {
    val turn = ((composeAngle(wffDegrees) % 360f) + 360f) % 360f
    return turn / 360f
  }
  fun colorAt(t: Float): Color {
    if (t <= positions.first()) return colors.first()
    if (t >= positions.last()) return colors.last()
    val i = positions.indexOfLast { it <= t }
    val a = positions[i]
    val b = positions[i + 1]
    return lerpSrgb(colors[i], colors[i + 1], (t - a) / (b - a))
  }
  // The gradient's progress at a point of Compose's turn.
  fun progress(f: Float): Float {
    if (span == 0f) return 1f
    val wff = f * 360f + 90f
    val along = if (clockwise) wff - start else start - wff
    return (((along % 360f) + 360f) % 360f) / span
  }
  val origin = fraction(start)
  val points =
    (positions.map { fraction(start + sweep * it) } + listOf(0f, 1f))
      .filter { it != origin }
      .map { it to colorAt(progress(it).coerceAtMost(1f)) }
  // At the start the turn jumps: the last colour on one side of it, the first on the other.
  val edge =
    if (clockwise) listOf(origin to colors.last(), origin to colors.first())
    else listOf(origin to colors.first(), origin to colors.last())
  val atOrigin =
    if (origin == 0f)
      // The edge is Compose's own start and end, so each colour sits at its own end of the turn.
      listOf(0f to edge[1].second, 1f to edge[0].second)
    else edge
  return (points.filter { it.first != 0f && it.first != 1f || origin != 0f } + atOrigin)
    .sortedBy { it.first }
    .toTypedArray()
}

/** The colour [stops] (as [sweepStops] returns them) give at [fraction] of Compose's turn. */
internal fun sweepColorAt(stops: Array<Pair<Float, Color>>, fraction: Float): Color {
  if (fraction <= stops.first().first) return stops.first().second
  if (fraction >= stops.last().first) return stops.last().second
  val i = stops.indexOfLast { it.first <= fraction }
  val (a, from) = stops[i]
  val (b, to) = stops[i + 1]
  return if (b == a) to else lerpSrgb(from, to, (fraction - a) / (b - a))
}

/**
 * [from] to [to] by [t], channel by channel in sRGB: how Skia interpolates a gradient's colours,
 * where Compose's `lerp` goes through Oklab, so wedges drawn for an export match the shader.
 */
private fun lerpSrgb(from: Color, to: Color, t: Float): Color =
  Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = from.alpha + (to.alpha - from.alpha) * t,
  )
