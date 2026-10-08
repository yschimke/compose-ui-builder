package ee.schimke.composeai.uibuilder.preview

import ee.schimke.composeai.uibuilder.export.ClockCanvas
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.argb
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.enum
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.formula
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.num
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.JsonObject

private const val RESIN = "#FF151517"
private const val RESIN_EDGE = "#FF2C2D31"
private const val FACE = "#FF0B0B0C"
private const val KEYLINE = "#FF2D4DA6"
private const val PRINT = "#FFE9E9E6"
private const val PRINT_DIM = "#FFB9BAB6"
private const val WR_RED = "#FFE0412A"
private const val STEEL = "#FF9FA5AB"
private const val STEEL_LIGHT = "#FFF2F4F6"
private const val STEEL_DARK = "#FF4A4F55"
private const val LCD_FRAME = "#FF26292B"
private const val LCD = "#FFC3C9B3"
private const val LCD_SHADE = "#FF7D8671"
private const val INK = "#FF1A1D17"
private const val WHITE = "#FFFFFFFF"

/**
 * A replica of the classic resin LCD digital watch — the F-91W — for looking at only: drawn from a
 * front-on reference photograph, never offered as a template.
 *
 * What makes it read as the real thing rather than a picture of a clock:
 * - the case is matt black resin whose moulded edge catches a thin line of light, and whose step
 *   down to the face is a second, darker bevel;
 * - the face carries its printing where the watch does — the top line, LIGHT and ALARM CHRONOGRAPH
 *   above the display, MODE and ALARM ON·OFF/24HR beneath it, WATER, a red WR box and RESIST along
 *   the bottom — inside a thin blue keyline;
 * - the display is a seven-segment LCD: every segment of every digit is drawn, the unlit ones as a
 *   faint ghost, the lit ones in ink, from formulas over the clock. The hour is twelve-hour with no
 *   leading zero, the colon blinks once a second, PM lights after noon, as module 593 does;
 * - the crystal throws two reflections, a soft wide band and a sharp thin one, and the steel
 *   buttons are lit from above.
 *
 * It carries no maker's wordmark: the top line, where the name sits, reads only the model line.
 */
internal fun lcdWatchReplica(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
): UiBuilderDocument =
  ClockCanvas.document(designId, "LCD watch replica", "lcd", catalogPin, environment) {
    strap()
    buttons()

    // The case: resin, a soft vertical falloff, and its moulded edge catching the light.
    val case = roundRectPath(24.0, 14.0, 152.0, 172.0, 24.0)
    // A soft shadow under the case, as on a photographed watch.
    rect("lcd-case-shadow", 25.5, 16.5, 152, 172, argb("#FF000000"), 24, "alpha" to num(0.3))
    path(
      "lcd-case",
      case,
      argb(RESIN),
      "gradient" to enum("vertical"),
      "gradientColor" to argb("#FF0A0A0B"),
    )
    outline("lcd-case-edge", 24, 14, 152, 172, 24, 1.0, argb(RESIN_EDGE))
    band(
      "lcd-case-glint",
      (0..6).map { 25.8 to 72.0 - it * 5 } +
        cubic(25.8 to 40.0, 25.8 to 24.0, 34.0 to 15.8, 50.0 to 15.8) +
        (1..10).map { 50.0 + it * 7 to 15.8 },
      1.5,
      argb(WHITE),
      "alpha" to num(0.34),
    )
    line("lcd-case-glint-right", 174.6 to 62, 174.6 to 150, 0.8, argb(WHITE), "alpha" to num(0.12))

    // The face: a step down from the case, then the printed plate inside its blue keyline.
    val step = roundRectPath(32.0, 23.0, 136.0, 154.0, 16.0)
    path("lcd-step", step, argb("#FF060607"))
    outline("lcd-step-bevel", 32, 23, 136, 154, 16, 0.8, argb("#FF3A3B40"), "alpha" to num(0.9))
    val face = roundRectPath(36.0, 27.0, 128.0, 146.0, 12.0)
    path("lcd-face", face, argb(FACE))
    outline("lcd-keyline", 39, 30, 122, 140, 10, 1.2, argb(KEYLINE))

    type("lcd-model", "F-91W", 152.0, 44.0, 28.0, argb(PRINT), "end")
    type("lcd-light", "LIGHT", 45.0, 57.0, 12.0, argb(PRINT_DIM), "start")
    type("lcd-line", "ALARM CHRONOGRAPH", 155.0, 57.0, 66.0, argb(PRINT), "end")

    lcd()

    type("lcd-mode", "MODE", 45.0, 145.0, 12.0, argb(PRINT_DIM), "start")
    type("lcd-alarm", "ALARM ON·OFF/24HR", 155.0, 145.0, 50.0, argb(PRINT_DIM), "end")
    type("lcd-water", "WATER", 62.0, 160.5, 24.0, argb(PRINT))
    rect(
      "lcd-wr-box",
      88.5,
      151.5,
      23,
      13,
      argb(WR_RED),
      2,
      "style" to enum("stroke"),
      "strokeWidthDp" to num(1.2),
    )
    type("lcd-wr", "WR", 100.0, 160.5, 12.0, argb(WR_RED))
    type("lcd-resist", "RESIST", 138.0, 160.5, 25.0, argb(PRINT))

    // The crystal over the face: a wide soft reflection and a thin bright one.
    clipped("lcd-crystal", face) {
      path(
        "lcd-crystal-sheen",
        polygon(36 to 27, 92 to 27, 58 to 173, 36 to 173),
        argb(WHITE),
        "alpha" to num(0.045),
      )
      line("lcd-crystal-glint", 98 to 28, 64 to 172, 0.6, argb(WHITE), "alpha" to num(0.10))
      line("lcd-crystal-top", 50 to 28.6, 150 to 28.6, 0.6, argb(WHITE), "alpha" to num(0.22))
    }
  }

/** Black resin straps above and below, each with its moulded grooves. */
private fun ClockCanvas.strap() {
  listOf("top" to 0.0, "bottom" to 176.0).forEach { (end, y) ->
    rect("lcd-strap-$end", 50, y, 100, 24, argb("#FF101011"))
    repeat("lcd-strap-$end-grooves", 4, "g") {
      op(
        "lcd-strap-$end-groove",
        "draw/rect",
        "xDp" to num(52),
        "yDp" to formula("${y + 3} + @g * 5"),
        "widthDp" to num(96),
        "heightDp" to num(1.2),
        "color" to argb("#FF000000"),
        "alpha" to num(0.55),
      )
    }
    line(
      "lcd-strap-$end-glint",
      52.6 to y + 2,
      52.6 to y + 22,
      0.6,
      argb(WHITE),
      "alpha" to num(0.12),
    )
  }
}

/** The three steel buttons — LIGHT and MODE on the left, the alarm button on the right. */
private fun ClockCanvas.buttons() {
  listOf("light" to (16.0 to 46.0), "mode" to (16.0 to 128.0), "alarm" to (176.0 to 128.0))
    .forEach { (name, at) ->
      val (x, y) = at
      rect("lcd-button-$name", x, y, 8, 16, argb(STEEL), 2)
      rect("lcd-button-$name-shade", x, y + 10, 8, 6, argb(STEEL_DARK), 2, "alpha" to num(0.7))
      line(
        "lcd-button-$name-glint",
        x + 1.5 to y + 2.2,
        x + 6.5 to y + 2.2,
        0.9,
        argb(STEEL_LIGHT),
        "alpha" to num(0.9),
      )
    }
}

/** The display: frame, glass, and the module's segments, in DSEG's LCD cells. */
private fun ClockCanvas.lcd() {
  val frame = roundRectPath(41.0, 62.0, 118.0, 74.0, 4.0)
  path("lcd-frame", frame, argb(LCD_FRAME))
  line("lcd-frame-lip", 43 to 62.7, 157 to 62.7, 0.6, argb(WHITE), "alpha" to num(0.18))
  val glass = roundRectPath(45.0, 66.0, 110.0, 66.0, 2.0)
  path(
    "lcd-glass",
    glass,
    argb(LCD),
    "gradient" to enum("vertical"),
    "gradientColor" to argb(LCD_SHADE),
  )
  clipped("lcd-glass-clip", glass) {
    // Day of the week, in two fourteen-segment cells.
    dayCells(50.0, 70.0, 14.0)
    type(
      "lcd-pm",
      "PM",
      50.0,
      91.5,
      6.0,
      argb(INK),
      "start",
      "alpha" to formula("select(time.hour >= 12, 0.9, 0.06)"),
    )

    // Date, top right; no leading zero.
    val date = "floor(time.dayOfMonth)"
    lcdDigit("lcd-date-tens", "floor($date / 10)", 131.0, 70.0, 14.0, blankWhenZero = true)
    lcdDigit("lcd-date-ones", "$date % 10", 131.0 + 14 * LcdGlyphs.SEVEN_ADVANCE, 70.0, 14.0)

    // Hours and minutes: twelve-hour, no leading zero on the hour, a colon that blinks.
    val hour = "((floor(time.hour) + 11) % 12 + 1)"
    val minute = "floor(time.minuteOfDay % 60)"
    val second = "floor(time.secondOfHour % 60)"
    val big = 25.0
    val step = big * LcdGlyphs.SEVEN_ADVANCE
    lcdDigit("lcd-hour-tens", "floor($hour / 10)", 47.0, 94.0, big, blankWhenZero = true)
    lcdDigit("lcd-hour-ones", "$hour % 10", 47.0 + step, 94.0, big)
    LcdGlyphs.COLON.forEachIndexed { index, dot ->
      path(
        "lcd-colon-$index",
        dot.pathAt(47.0 + 2 * step, 94.0, big),
        argb(INK),
        "alpha" to formula("select(floor(time.continuousSecond * 2) % 2 == 0, 0.9, 0.06)"),
      )
    }
    val minutes = 47.0 + 2 * step + big * LcdGlyphs.COLON_ADVANCE
    lcdDigit("lcd-minute-tens", "floor($minute / 10)", minutes, 94.0, big)
    lcdDigit("lcd-minute-ones", "$minute % 10", minutes + step, 94.0, big)
    lcdDigit("lcd-second-tens", "floor($second / 10)", 133.0, 105.0, 14.0)
    lcdDigit("lcd-second-ones", "$second % 10", 133.0 + 14 * LcdGlyphs.SEVEN_ADVANCE, 105.0, 14.0)

    // Light on the glass: one broad pale band, one fine bright edge.
    path(
      "lcd-glass-sheen",
      polygon(45 to 66, 84 to 66, 64 to 132, 45 to 132),
      argb(WHITE),
      "alpha" to num(0.10),
    )
    line("lcd-glass-glint", 90 to 66, 70 to 132, 0.5, argb(WHITE), "alpha" to num(0.22))
  }
}

/** One DSEG7 cell [h] tall at ([x], [y]) showing [digit]: lit segments in ink, the rest ghosted. */
private fun ClockCanvas.lcdDigit(
  id: String,
  digit: String,
  x: Double,
  y: Double,
  h: Double,
  blankWhenZero: Boolean = false,
) {
  LcdGlyphs.SEVEN.forEach { (segment, outline) ->
    val lit = SEGMENT_LIT.getValue(segment)("($digit)")
    val on = if (blankWhenZero) "($digit) != 0 && ($lit)" else lit
    path(
      "$id-$segment",
      outline.pathAt(x, y, h),
      argb(INK),
      "alpha" to formula("select($on, 0.9, 0.06)"),
    )
  }
}

/** The day's two DSEG14 cells: each segment lit on the days whose letter uses it. */
private fun ClockCanvas.dayCells(x: Double, y: Double, h: Double) {
  val days = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
  (0..1).forEach { position ->
    LcdGlyphs.FOURTEEN.forEachIndexed { segment, outline ->
      val lighting =
        days.indices.filter { segment in LcdGlyphs.FOURTEEN_LETTERS.getValue(days[it][position]) }
      val alpha =
        if (lighting.isEmpty()) num(0.06)
        else
          formula(
            "select(" +
              lighting.joinToString(" || ") { "time.dayOfWeek == ${it + 1}" } +
              ", 0.9, 0.06)"
          )
      path(
        "lcd-day-$position-$segment",
        outline.pathAt(x + position * h * LcdGlyphs.FOURTEEN_ADVANCE, y, h),
        argb(INK),
        "alpha" to alpha,
      )
    }
  }
}

/** [label] in Michroma, [width] wide, its baseline at [baseline], anchored at [x] by [align]. */
private fun ClockCanvas.type(
  id: String,
  label: String,
  x: Double,
  baseline: Double,
  width: Double,
  color: JsonObject,
  align: String = "center",
  vararg paint: Pair<String, JsonObject>,
) {
  val (ems, outlines) = LcdGlyphs.TYPE.getValue(label)
  val size = width / ems
  val start =
    when (align) {
      "start" -> x
      "end" -> x - width
      else -> x - width / 2
    }
  path(id, outlines.joinToString(" ") { it.pathAt(start, baseline, size) }, color, *paint)
}

/** These points, scaled by [scale] and placed at ([x], [y]), as one closed contour. */
private fun DoubleArray.pathAt(x: Double, y: Double, scale: Double): String =
  (indices step 2).joinToString(" ", postfix = " Z") { i ->
    (if (i == 0) "M" else "L") + "${(x + this[i] * scale).d()} ${(y + this[i + 1] * scale).d()}"
  }
