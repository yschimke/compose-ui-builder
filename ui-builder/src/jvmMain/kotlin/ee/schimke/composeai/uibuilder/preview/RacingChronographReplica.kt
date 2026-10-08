package ee.schimke.composeai.uibuilder.preview

import ee.schimke.composeai.uibuilder.export.ClockCanvas
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.argb
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.enum
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.formula
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.num
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.text
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.twoDigits
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlinx.serialization.json.JsonObject

private const val CARBON = "#FF1F2023"
private const val CARBON_LAYER = "#FF7A7C82"
private const val ORANGE = "#FFF26A1B"
private const val ORANGE_DEEP = "#FFB9480C"
private const val TITANIUM = "#FFB4B9BF"
private const val TITANIUM_DARK = "#FF5E636A"
private const val SCREW = "#FF18181A"
private const val SCREW_RIM = "#FF5A5D63"
private const val SCREW_SPLINE = "#FF3C3E43"
private const val FLANGE = "#FF0C0C0E"
private const val PLATE = "#FF141517"
private const val BRIDGE = "#FF6F747B"
private const val BRIDGE_LIGHT = "#FFD5D9DE"
private const val WHEEL = "#FFA9AEB4"
private const val TACHY = "#FF7AD63E"
private const val LUME = "#FFDDF23C"
private const val WHITE = "#FFFFFFFF"
private const val HAND_SHADE = "#FFB9BEC4"
private const val HAND_LUME = "#FFE6F4D2"
private const val YELLOW = "#FFF1E51E"
private const val JEWEL = "#FFB3123A"
private const val BLACK = "#FF000000"

/** The case outline: a tonneau, flat-topped, its flanks bowed out. */
private const val CASE =
  "M44 12 C70 7 130 7 156 12 C178 46 181 154 156 188 C130 193 70 193 44 188 C19 154 22 46 44 12 Z"

/** The crystal's opening in the bezel: the same tonneau, inset. */
private const val CRYSTAL =
  "M54 31 C80 27 120 27 146 31 C162 64 163 136 146 169 C120 173 80 173 54 169 C37 136 38 64 54 31 Z"

private const val C = 100.0

/** [CASE], sampled, for the ribbons that follow its edge. */
private val CASE_OUTLINE =
  cubic(44.0 to 12.0, 70.0 to 7.0, 130.0 to 7.0, 156.0 to 12.0) +
    cubic(156.0 to 12.0, 178.0 to 46.0, 181.0 to 154.0, 156.0 to 188.0).drop(1) +
    cubic(156.0 to 188.0, 130.0 to 193.0, 70.0 to 193.0, 44.0 to 188.0).drop(1) +
    cubic(44.0 to 188.0, 19.0 to 154.0, 22.0 to 46.0, 44.0 to 12.0).drop(1)

/** [CRYSTAL], sampled, starting along its top edge. */
private val CRYSTAL_OUTLINE =
  cubic(54.0 to 31.0, 80.0 to 27.0, 120.0 to 27.0, 146.0 to 31.0) +
    cubic(146.0 to 31.0, 162.0 to 64.0, 163.0 to 136.0, 146.0 to 169.0).drop(1) +
    cubic(146.0 to 169.0, 120.0 to 173.0, 80.0 to 173.0, 54.0 to 169.0).drop(1) +
    cubic(54.0 to 169.0, 37.0 to 136.0, 38.0 to 64.0, 54.0 to 31.0).drop(1)

/** One carbon layer: a gentle double wave across the case at [y], shifted by [phase]. */
private fun wave(y: Double, phase: Double): List<Pair<Double, Double>> =
  (0..32).map { i ->
    val x = 4.0 + i * 6
    x to y + 1.8 * sin(x / 23 + phase) + 1.1 * sin(x / 9 + phase * 2)
  }

/**
 * A racing chronograph in the manner of the RM 11-03 McLaren that Formula 1 drivers wear, drawn
 * from a front-on reference photograph, for looking at only — never offered as a template, and
 * carrying no maker's or team's wordmark.
 *
 * - **Case.** A tonneau in carbon TPT: dark layers with grey striations, a few of them orange, cut
 *   by a polished bevel that catches the light along its left flank and top. Eight black spline
 *   screws hold the bezel, each with its six splines and a highlight on its domed head; hollow
 *   titanium trapezoids sit at twelve and six. At three, an orange knurled crown in a titanium
 *   guard; at two and four, the START/STOP and RESET/FLY-B pushers.
 * - **Flange.** Black, with a minute track, a tachymeter scale in green placed where 3600 ÷ seconds
 *   falls, and lume plots at the hours.
 * - **Dial.** Skeletonised: the movement runs underneath — wheels turning at their ratios, the
 *   escape wheel stepping eight times a second, the balance swinging at 4 Hz under grey bridges
 *   with polished edges. Over it, outlined orange numerals, a large date under twelve, and three
 *   registers: running seconds at nine (green), the chronograph minutes at three (yellow), its
 *   hours at six (orange).
 * - **Hands.** Faceted swords — each half a different shade, so the ridge reads as a polished edge
 *   — with lume; the central chronograph seconds sweeps.
 * - **Crystal.** Two reflections, a soft wide one and a sharp one, and a bright line where its edge
 *   meets the bezel.
 */
internal fun racingChronographReplica(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
): UiBuilderDocument =
  ClockCanvas.document(designId, "Racing chronograph", "racing", catalogPin, environment) {
    straps()
    crownAndPushers()
    caseBody()
    flange()
    movement()
    dial()
    hands()
    crystal()
  }

private fun ClockCanvas.straps() {
  listOf("top" to false, "bottom" to true).forEach { (end, flip) ->
    fun y(v: Double) = if (flip) 200 - v else v
    path(
      "racing-strap-$end",
      polygon(56 to y(0.0), 144 to y(0.0), 140 to y(24.0), 60 to y(24.0)),
      argb(ORANGE),
    )
    listOf(66.0, 108.0).forEachIndexed { index, x ->
      path(
        "racing-strap-$end-vent-$index",
        roundRectPath(x, if (flip) 182.0 else 6.0, 26.0, 9.0, 4.5),
        argb(ORANGE_DEEP),
      )
    }
    line(
      "racing-strap-$end-glint",
      58.5 to y(2.0),
      61.5 to y(22.0),
      0.8,
      argb(WHITE),
      "alpha" to num(0.35),
    )
  }
}

private fun ClockCanvas.crownAndPushers() {
  // Pushers at two and four: black, labelled in yellow, capped in titanium.
  listOf(
      Triple("start", polygon(160 to 40, 182 to 50, 180 to 76, 164 to 72), "START/STOP"),
      Triple("reset", polygon(164 to 128, 180 to 124, 182 to 150, 160 to 160), "RESET/FLY-B"),
    )
    .forEachIndexed { index, (name, body, words) ->
      path("racing-pusher-$name", body, argb("#FF141416"))
      val capTop = if (index == 0) 50.0 else 124.0
      path(
        "racing-pusher-$name-cap",
        polygon(180 to capTop, 188 to capTop + 4, 187 to capTop + 22, 180 to capTop + 26),
        argb(TITANIUM),
      )
      line(
        "racing-pusher-$name-cap-glint",
        181.8 to capTop + 2.6,
        185.6 to capTop + 4.6,
        0.8,
        argb(WHITE),
        "alpha" to num(0.8),
      )
      val cy = if (index == 0) 58.0 else 142.0
      turned("racing-pusher-$name-words", if (index == 0) "68" else "112", 175.0, cy) {
        label("racing-pusher-$name-label", text(words), 175, cy, 3.4, argb(YELLOW))
      }
    }
  // The crown guard and the knurled orange crown.
  path("racing-crown-guard", polygon(170 to 82, 184 to 86, 184 to 114, 170 to 118), argb(TITANIUM))
  path(
    "racing-crown-guard-shade",
    polygon(170 to 104, 184 to 104, 184 to 114, 170 to 118),
    argb(TITANIUM_DARK),
    "alpha" to num(0.6),
  )
  rect("racing-crown", 182, 85, 12, 30, argb(ORANGE), 3)
  repeat("racing-crown-knurls", 8, "n") {
    op(
      "racing-crown-knurl",
      "draw/rect",
      "xDp" to num(182.5),
      "yDp" to formula("87 + @n * 3.4"),
      "widthDp" to num(11),
      "heightDp" to num(1.1),
      "color" to argb(ORANGE_DEEP),
    )
  }
  line("racing-crown-glint", 183.6 to 87.0, 183.6 to 113.0, 0.8, argb(WHITE), "alpha" to num(0.45))
}

private fun ClockCanvas.caseBody() {
  // A soft shadow under the case, as on a photographed watch.
  container(
    "racing-case-shadow",
    "draw/group",
    "translateXDp" to num(1.5),
    "translateYDp" to num(2.5),
  ) {
    path("racing-case-shadow-shape", CASE, argb(BLACK), "alpha" to num(0.35))
  }
  path("racing-case", CASE, argb(CARBON))
  // Carbon TPT: hundreds of layers, read here as wavy grey striations with an orange few.
  clipped("racing-tpt", CASE) {
    repeat("racing-tpt-layers", 54, "w") {
      container(
        "racing-tpt-layer",
        "draw/group",
        "translateXDp" to formula("sin(@w * 1.7) * 9"),
        "translateYDp" to formula("@w * 3.55"),
      ) {
        band(
          "racing-tpt-grey",
          wave(8.0, 0.0),
          0.9,
          argb(CARBON_LAYER),
          "alpha" to formula("select(@w % 3 == 0, 0.38, select(@w % 3 == 1, 0.18, 0.09))"),
          taper = false,
        )
        // The orange layers gather in the middle of the case, as on the reference.
        band(
          "racing-tpt-orange",
          wave(9.6, 1.3),
          1.4,
          argb(ORANGE),
          "alpha" to
            formula("select((@w >= 14 && @w <= 40 && @w % 5 == 2) || @w % 17 == 6, 0.85, 0.0)"),
          taper = false,
        )
      }
    }
  }
  // The polished bevel: a bright line down the left flank and across the top, a dimmer right one.
  band("racing-case-edge", CASE_OUTLINE, 1.0, argb(BLACK), "alpha" to num(0.6), taper = false)
  band(
    "racing-case-glint-left",
    cubic(32.0 to 40.0, 23.5 to 80.0, 23.5 to 120.0, 32.0 to 160.0),
    1.6,
    argb(WHITE),
    "alpha" to num(0.42),
  )
  band(
    "racing-case-glint-top",
    cubic(60.0 to 9.6, 86.0 to 6.8, 114.0 to 6.8, 140.0 to 9.6),
    1.0,
    argb(WHITE),
    "alpha" to num(0.30),
  )
  band(
    "racing-case-glint-right",
    cubic(170.0 to 60.0, 175.5 to 90.0, 175.5 to 114.0, 170.0 to 142.0),
    0.9,
    argb(WHITE),
    "alpha" to num(0.14),
  )

  // Hollow titanium trapezoids at twelve and six: a polished frame round a dark well.
  listOf("top" to false, "bottom" to true).forEach { (end, flip) ->
    fun y(v: Double) = if (flip) 200 - v else v
    path(
      "racing-insert-$end",
      polygon(78 to y(13.0), 122 to y(13.0), 113 to y(30.0), 87 to y(30.0)),
      argb(TITANIUM),
    )
    path(
      "racing-insert-$end-shade",
      polygon(100 to y(13.0), 122 to y(13.0), 113 to y(30.0), 100 to y(30.0)),
      argb(TITANIUM_DARK),
      "alpha" to num(0.35),
    )
    path(
      "racing-insert-$end-well",
      polygon(83.5 to y(16.0), 116.5 to y(16.0), 110.5 to y(27.0), 89.5 to y(27.0)),
      argb("#FF0E0E10"),
    )
    line(
      "racing-insert-$end-glint",
      80.5 to y(13.7),
      98.0 to y(13.7),
      0.7,
      argb(WHITE),
      "alpha" to num(0.85),
    )
  }

  listOf(40 to 26, 160 to 26, 30 to 74, 170 to 74, 30 to 126, 170 to 126, 40 to 174, 160 to 174)
    .forEachIndexed { index, (x, y) ->
      splineScrew(
        "racing-screw-$index",
        x.toDouble(),
        y.toDouble(),
        4.6,
        argb(SCREW),
        argb(SCREW_RIM),
        argb(SCREW_SPLINE),
      )
    }
}

/** A point on the dial's rounded-rectangle "superellipse" at [degrees] clockwise from twelve. */
private fun onTonneau(
  degrees: Double,
  rx: Double,
  ry: Double,
  n: Double = 3.2,
): Pair<Double, Double> {
  val a = degrees * PI / 180
  val s = sin(a)
  val c = cos(a)
  return (C + rx * sign(s) * abs(s).pow(2 / n)) to (C - ry * sign(c) * abs(c).pow(2 / n))
}

private fun superellipsePath(rx: Double, ry: Double, n: Double = 3.2): String =
  (0 until 72)
    .map { onTonneau(it * 5.0, rx, ry, n) }
    .mapIndexed { index, (x, y) -> (if (index == 0) "M" else "L") + "${x.d()} ${y.d()}" }
    .joinToString(" ", postfix = " Z")

private fun ClockCanvas.flange() {
  path("racing-flange", CRYSTAL, argb(FLANGE))
  band("racing-flange-bevel", CRYSTAL_OUTLINE, 2.4, argb("#FF3B3E43"), taper = false)
  band("racing-flange-bevel-light", CRYSTAL_OUTLINE.take(20), 0.6, argb(WHITE), "alpha" to num(0.3))
  // Minute track: sixty ticks round the tonneau, longer at the fives.
  (0 until 60).forEach { tick ->
    val major = tick % 5 == 0
    val (x1, y1) = onTonneau(tick * 6.0, 52.0, 62.0)
    val (x2, y2) = onTonneau(tick * 6.0, if (major) 48.5 else 50.0, if (major) 58.0 else 60.0)
    line(
      "racing-track-$tick",
      x1 to y1,
      x2 to y2,
      if (major) 0.9 else 0.5,
      argb(WHITE),
      "alpha" to num(if (major) 0.9 else 0.6),
    )
  }
  // Tachymeter: each speed sits at the seconds a kilometre takes at it, 3600 / speed.
  listOf(700, 400, 300, 240, 200, 180, 165, 150, 140, 120, 105, 100, 95, 90, 85, 80, 75, 70, 65)
    .forEach { speed ->
      val seconds = 3600.0 / speed
      val (x, y) = onTonneau(seconds * 6, 57.0, 67.0)
      label("racing-tachy-$speed", text("$speed"), x, y, 3.8, argb(TACHY))
    }
  turned("racing-tachymeter-words", "36", 128.0, 44.0) {
    label("racing-tachymeter", text("TACHYMETER"), 128, 44, 3.2, argb(TACHY))
  }
  label(
    "racing-swiss",
    text("SWISS          MADE"),
    C,
    166.5,
    3.2,
    argb(WHITE),
    "center",
    "alpha" to num(0.85),
  )
  // Lume plots at the hours, pointing in.
  (0 until 12).forEach { hour ->
    val (px, py) = onTonneau(hour * 30.0, 46.0, 55.0)
    val length = hypot(C - px, C - py)
    val dx = (C - px) / length
    val dy = (C - py) / length
    path(
      "racing-plot-$hour",
      polygon(
        (px + dx * 3.2) to (py + dy * 3.2),
        (px - dx * 1.6 - dy * 2.1) to (py - dy * 1.6 + dx * 2.1),
        (px - dx * 1.6 + dy * 2.1) to (py - dy * 1.6 - dx * 2.1),
      ),
      argb(LUME),
    )
  }
}

private fun ClockCanvas.movement() {
  val opening = superellipsePath(44.0, 52.0)
  clipped("racing-skeleton", opening) {
    path("racing-plate", opening, argb(PLATE))
    // Wheels turning at their ratios under the bridges, and the escape wheel stepping.
    gear("racing-wheel-centre", 116.0, 80.0, 13.0, 20, "time.continuousSecond * 6")
    gear("racing-wheel-third", 84.0, 78.0, 10.0, 16, "-time.continuousSecond * 9.4")
    gear("racing-wheel-fourth", 120.0, 120.0, 9.0, 15, "-time.continuousSecond * 14")
    gear("racing-escape", 96.0, 120.0, 6.0, 15, "floor(time.continuousSecond * 8) * 24")
    // The balance at 4 Hz, swinging 200 degrees either way, with its hairspring breathing.
    turned("racing-balance", "sin(time.continuousSecond * 25.13) * 200", 76.0, 122.0) {
      circle(
        "racing-balance-rim",
        10,
        argb(WHEEL),
        76,
        122,
        "style" to enum("stroke"),
        "strokeWidthDp" to num(2.2),
      )
      repeat("racing-balance-arms", 2, "k") {
        turned("racing-balance-arm", "@k * 180", 76.0, 122.0) {
          line("racing-balance-arm-line", 76.0 to 122.0, 76.0 to 112.0, 1.4, argb(WHEEL))
        }
      }
    }
    repeat("racing-hairspring", 4, "r") {
      op(
        "racing-hairspring-coil",
        "draw/circle",
        "centerXDp" to num(76),
        "centerYDp" to num(122),
        "radiusDp" to formula("2.2 + @r * 1.7 + sin(time.continuousSecond * 25.13) * 0.25"),
        "style" to enum("stroke"),
        "strokeWidthDp" to num(0.45),
        "color" to argb(WHEEL),
        "alpha" to num(0.85),
      )
    }
    // Bridges: grey titanium with a polished chamfer along one edge.
    listOf(
        (58.0 to 58.0) to (100.0 to 92.0),
        (100.0 to 92.0) to (142.0 to 58.0),
        (58.0 to 146.0) to (100.0 to 110.0),
        (100.0 to 110.0) to (142.0 to 146.0),
        (100.0 to 46.0) to (100.0 to 62.0),
        (60.0 to 100.0) to (80.0 to 100.0),
        (120.0 to 100.0) to (140.0 to 100.0),
      )
      .forEachIndexed { index, (from, to) ->
        line("racing-bridge-$index", from, to, 5.0, argb(BRIDGE))
        line(
          "racing-bridge-$index-chamfer",
          from.first to from.second - 1.6,
          to.first to to.second - 1.6,
          0.7,
          argb(BRIDGE_LIGHT),
          "alpha" to num(0.75),
        )
      }
    circle(
      "racing-bridge-ring",
      12,
      argb(BRIDGE),
      C,
      C,
      "style" to enum("stroke"),
      "strokeWidthDp" to num(5),
    )
    // The bridges' own screws, polished, catching the light.
    listOf(66 to 64, 134 to 64, 66 to 140, 134 to 140, 100 to 50).forEachIndexed { index, (x, y) ->
      circle("racing-bridge-screw-$index", 2.1, argb(BRIDGE_LIGHT), x, y)
      line(
        "racing-bridge-screw-$index-slot",
        x - 1.4 to y + 0.6,
        x + 1.4 to y - 0.6,
        0.5,
        argb(BRIDGE),
      )
    }
    listOf(84 to 78, 116 to 80, 120 to 120, 96 to 120, 76 to 122).forEachIndexed { index, (x, y) ->
      circle("racing-jewel-$index", 1.6, argb(JEWEL), x, y)
    }
  }
  band(
    "racing-skeleton-edge",
    (0..72).map { onTonneau(it * 5.0, 44.0, 52.0) },
    1.0,
    argb("#FF2E3034"),
    taper = false,
  )
}

/** An outlined numeral: white drawn round it, then orange on top, as the printed numerals are. */
private fun ClockCanvas.numeral(hour: Int, x: Double, y: Double) {
  listOf(-0.55 to 0.0, 0.55 to 0.0, 0.0 to -0.55, 0.0 to 0.55).forEachIndexed { index, (dx, dy) ->
    label("racing-numeral-$hour-outline-$index", text("$hour"), x + dx, y + dy, 11.5, argb(WHITE))
  }
  label("racing-numeral-$hour", text("$hour"), x, y, 11.5, argb(ORANGE))
}

private fun ClockCanvas.dial() {
  listOf(1, 2, 4, 5, 7, 8, 10, 11, 12).forEach { hour ->
    val (x, y) = onTonneau(hour * 30.0, 37.0, 45.0)
    numeral(hour, x, y)
  }
  // The large date, under twelve.
  rect("racing-date-frame", 88, 64, 24, 13, argb("#FF0D0D0F"), 1.5)
  rect(
    "racing-date-rim",
    88,
    64,
    24,
    13,
    argb(TITANIUM),
    1.5,
    "style" to enum("stroke"),
    "strokeWidthDp" to num(1.1),
  )
  label("racing-date", formula(twoDigits("toInt(time.dayOfMonth)")), C, 70.5, 9, argb(WHITE))
  // The orange bars either side of the date.
  listOf(83.0, 113.0).forEachIndexed { index, x ->
    rect("racing-date-bar-$index", x, 66.5, 4, 8, argb(ORANGE), 0.8)
    line(
      "racing-date-bar-$index-glint",
      x + 0.6 to 67.2,
      x + 3.4 to 67.2,
      0.4,
      argb(WHITE),
      "alpha" to num(0.6),
    )
  }

  register(
    "racing-seconds",
    68.0,
    102.0,
    "(time.secondOfHour % 60) * 6",
    TACHY,
    TACHY,
    listOf("60", "15", "30", "45"),
  )
  register(
    "racing-minutes",
    132.0,
    102.0,
    "(time.minuteOfDay % 60) * 6",
    YELLOW,
    ORANGE,
    listOf("60", "15", "30", "45"),
  )
  register(
    "racing-hours",
    C,
    136.0,
    "(time.minuteOfDay % 720) / 2",
    ORANGE,
    ORANGE,
    listOf("12", "3", "6", "9"),
  )
}

/** A chronograph register: snailed disc, a coloured track, its numbers and an arrow hand. */
private fun ClockCanvas.register(
  id: String,
  x: Double,
  y: Double,
  rotate: String,
  hand: String,
  accent: String,
  numbers: List<String>,
) {
  circle("$id-disc", 15.5, argb("#FF09090A"), x, y)
  repeat("$id-snail", 5, "s") {
    op(
      "$id-snail-ring",
      "draw/circle",
      "centerXDp" to num(x),
      "centerYDp" to num(y),
      "radiusDp" to formula("3 + @s * 2.4"),
      "style" to enum("stroke"),
      "strokeWidthDp" to num(0.4),
      "color" to argb(WHITE),
      "alpha" to num(0.10),
    )
  }
  circle(
    "$id-track",
    13.2,
    argb(accent),
    x,
    y,
    "style" to enum("stroke"),
    "strokeWidthDp" to num(1.6),
    "alpha" to num(0.9),
  )
  repeat("$id-ticks", 30, "m") {
    turned("$id-tick", "@m * 12", x, y) {
      line("$id-tick-line", x to y - 15.2, x to y - 12.6, 0.45, argb(WHITE), "alpha" to num(0.85))
    }
  }
  numbers.forEachIndexed { index, number ->
    val a = index * PI / 2
    label(
      "$id-number-$index",
      text(number),
      x + 8.6 * sin(a),
      y - 8.6 * cos(a),
      3.6,
      argb(if (index == 0) accent else WHITE),
    )
  }
  circle(
    "$id-rim",
    15.5,
    argb(TITANIUM),
    x,
    y,
    "style" to enum("stroke"),
    "strokeWidthDp" to num(0.8),
  )
  turned("$id-hand", rotate, x, y) {
    line("$id-hand-line", x to y + 3, x to y - 9, 1.0, argb(hand))
    path(
      "$id-hand-tip",
      polygon(x to y - 13.5, (x - 2) to (y - 8.5), (x + 2) to (y - 8.5)),
      argb(hand),
    )
  }
  circle("$id-pin", 1.6, argb(TITANIUM), x, y)
}

/** A faceted sword hand [length] long and [width] wide, with its lume strip. */
private fun ClockCanvas.sword(id: String, rotate: String, length: Double, width: Double) =
  turned(id, rotate) {
    val tip = C - length
    val shoulder = C - length * 0.28
    val tail = C + length * 0.12
    path("$id-light", polygon(C to tail, (C - width / 2) to shoulder, C to tip), argb(WHITE))
    path("$id-shade", polygon(C to tail, (C + width / 2) to shoulder, C to tip), argb(HAND_SHADE))
    path(
      "$id-lume",
      polygon(
        C to (shoulder - 2),
        (C - width * 0.2) to (C - length * 0.4),
        C to (tip + 5),
        (C + width * 0.2) to (C - length * 0.4),
      ),
      argb(HAND_LUME),
    )
    line("$id-ridge", C to tail, C to tip, 0.35, argb(WHITE), "alpha" to num(0.9))
  }

private fun ClockCanvas.hands() {
  // The hands' shadows on the dial: the same swords, darkened, a little down and right.
  container(
    "racing-hand-shadows",
    "draw/group",
    "translateXDp" to num(1.3),
    "translateYDp" to num(1.8),
  ) {
    listOf(
        "hour" to ("(time.minuteOfDay % 720) / 2" to 36.0),
        "minute" to ("time.secondOfHour / 10" to 56.0),
      )
      .forEach { (name, hand) ->
        turned("racing-$name-shadow", hand.first) {
          path(
            "racing-$name-shadow-shape",
            polygon(
              C to C + hand.second * 0.12,
              (C - 4.2) to (C - hand.second * 0.28),
              C to (C - hand.second),
              (C + 4.2) to (C - hand.second * 0.28),
            ),
            argb(BLACK),
            "alpha" to num(0.4),
          )
        }
      }
  }
  sword("racing-hour", "(time.minuteOfDay % 720) / 2", 36.0, 9.0)
  sword("racing-minute", "time.secondOfHour / 10", 56.0, 7.0)
  turned("racing-chrono", "(time.continuousSecond % 60) * 6") {
    line("racing-chrono-body", C to C + 16, C to C - 60, 0.9, argb("#FFDADDE1"))
    circle("racing-chrono-weight", 2.4, argb("#FFDADDE1"), C, C + 12)
    path(
      "racing-chrono-tip",
      polygon(C to C - 64, (C - 1.6) to (C - 58), (C + 1.6) to (C - 58)),
      argb(ORANGE),
    )
  }
  circle("racing-hub", 3.8, argb(TITANIUM), C, C)
  circle("racing-hub-pin", 1.4, argb("#FF2A2C30"), C, C)
  op(
    "racing-hub-glint",
    "draw/arc",
    "xDp" to num(C - 3.2),
    "yDp" to num(C - 3.2),
    "widthDp" to num(6.4),
    "heightDp" to num(6.4),
    "startAngle" to num(200),
    "sweepAngle" to num(70),
    "style" to enum("stroke"),
    "strokeWidthDp" to num(0.7),
    "color" to argb(WHITE),
    "alpha" to num(0.8),
  )
}

private fun ClockCanvas.crystal() {
  clipped("racing-crystal", CRYSTAL) {
    path(
      "racing-crystal-sheen",
      polygon(38 to 27, 96 to 27, 66 to 173, 38 to 173),
      argb(WHITE),
      "alpha" to num(0.05),
    )
    line(
      "racing-crystal-glint",
      104.0 to 28.0,
      74.0 to 172.0,
      0.5,
      argb(WHITE),
      "alpha" to num(0.14),
    )
  }
  band(
    "racing-crystal-edge",
    cubic(60.0 to 30.4, 82.0 to 27.0, 118.0 to 27.0, 140.0 to 30.4),
    0.7,
    argb(WHITE),
    "alpha" to num(0.3),
  )
}

/** A steel wheel of [teeth] teeth at ([x], [y]), turned by [rotate] about its own centre. */
private fun ClockCanvas.gear(
  id: String,
  x: Double,
  y: Double,
  radius: Double,
  teeth: Int,
  rotate: String,
) =
  turned(id, rotate, x, y) {
    circle(
      "$id-rim",
      radius,
      argb(WHEEL),
      x,
      y,
      "style" to enum("stroke"),
      "strokeWidthDp" to num(2.0),
    )
    repeat("$id-teeth", teeth, "t") {
      turned("$id-tooth", "@t * ${360.0 / teeth}", x, y) {
        rect("$id-tooth-block", x - 1.0, y - radius - 2.6, 2.0, 3.4, argb(WHEEL))
      }
    }
    repeat("$id-spokes", 4, "p") {
      turned("$id-spoke", "@p * 90", x, y) {
        line("$id-spoke-arm", x to y, x to y - radius, 1.4, argb(WHEEL))
      }
    }
  }
