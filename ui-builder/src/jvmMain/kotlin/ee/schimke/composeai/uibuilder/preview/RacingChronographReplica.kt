package ee.schimke.composeai.uibuilder.preview

import ee.schimke.composeai.uibuilder.export.ClockCanvas
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.argb
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.circlePath
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.enum
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.flag
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.formula
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.num
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.text
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiDrawing
import kotlinx.serialization.json.JsonObject

private const val STEEL = "#FFD9DCE0"
private const val STEEL_SHADE = "#FF5F646B"
private const val BEZEL = "#FF0B0B0D"
private const val CARBON = "#FF121316"
private const val WEAVE = "#FF3A3D44"
private const val RED = "#FFE10600"
private const val WHITE = "#FFF5F5F2"
private const val LUME = "#FFDDEBC9"
private const val GOLD = "#FFCFA562"
private const val RHODIUM = "#FF5A5E66"
private const val JEWEL = "#FFB3123A"

/**
 * A racing chronograph in the style a Formula 1 driver wears: a steel case with crown and pushers,
 * a black tachymeter bezel, a carbon dial with lumed indices and two sub-dials, and a running
 * automatic movement underneath — gears turning, the balance wheel swinging four times a second —
 * faint through the smoked dial and clear through an open window at six.
 *
 * A replica for looking at only, like [lcdWatchReplica]: not a template, and it carries no maker's
 * name. Everything that moves is a formula over the host's clock.
 */
internal fun racingChronographReplica(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
): UiBuilderDocument =
  ClockCanvas.document(designId, "Racing chronograph", "racing", catalogPin, environment) {
    val c = centre
    val windowY = 142.0
    val window = 24.0

    // Case: lugs, then pushers at two and four and the crown at three, then the round case.
    rect("racing-lugs-top", 58, 0, 84, 30, argb(STEEL_SHADE), corner = 6)
    rect("racing-lugs-bottom", 58, 170, 84, 30, argb(STEEL_SHADE), corner = 6)
    listOf(60, 120).forEach { angle ->
      turned("racing-pusher-$angle", "$angle") {
        rect("racing-pusher-$angle-body", 94, 0, 12, 10, argb(STEEL), corner = 2)
      }
    }
    rect("racing-crown", 188, 92, 12, 16, argb(STEEL), corner = 3)
    circle(
      "racing-case",
      c - 4,
      argb(STEEL),
      c,
      c,
      "gradient" to enum("radial"),
      "gradientColor" to argb(STEEL_SHADE),
    )

    // Bezel: a tachymeter scale, read against the chronograph seconds.
    circle(
      "racing-bezel",
      90,
      argb(BEZEL),
      c,
      c,
      "style" to enum("stroke"),
      "strokeWidthDp" to num(14),
    )
    repeat("racing-bezel-ticks", 60, "q") {
      turned("racing-bezel-tick", "@q * 6") {
        line("racing-bezel-tick-mark", c to 4, c to 7, 0.8, argb(WHITE), "alpha" to num(0.7))
      }
    }
    // Speed over a timed kilometre: 3600 / seconds, placed at that many seconds round the dial.
    listOf(500, 400, 300, 250, 200, 150, 120, 100, 90, 80, 70).forEach { speed ->
      op(
        "racing-tachy-$speed",
        UiDrawing.TEXT_CIRCLE,
        "text" to text("$speed"),
        "radiusDp" to num(86),
        "angle" to num(270 + 3600.0 / speed * 6),
        "textSizeSp" to num(6.5),
        "color" to argb(WHITE),
      )
    }
    op(
      "racing-tachymeter",
      UiDrawing.TEXT_CIRCLE,
      "text" to text("TACHYMETER"),
      "radiusDp" to num(86),
      "angle" to num(293),
      "textSizeSp" to num(5),
      "color" to argb(RED),
    )
    op(
      "racing-bezel-pip",
      "draw/path",
      "pathData" to text("M100 9 L104 16 L96 16 Z"),
      "viewportWidth" to num(ClockCanvas.SIZE_DP),
      "viewportHeight" to num(ClockCanvas.SIZE_DP),
      "color" to argb(RED),
    )

    // The movement, drawn before the dial so the dial covers it.
    container(
      "racing-movement",
      UiDrawing.CLIP,
      "pathData" to text(circlePath(c, c, 78.0)),
      "viewportWidth" to num(ClockCanvas.SIZE_DP),
      "viewportHeight" to num(ClockCanvas.SIZE_DP),
    ) {
      circle("racing-plate", 78, argb(RHODIUM), c, c)
      // Côtes de Genève: the plate's decorative stripes.
      repeat("racing-stripes", 14, "w") {
        op(
          "racing-stripe",
          "draw/rect",
          "xDp" to num(22),
          "yDp" to formula("24 + @w * 11"),
          "widthDp" to num(156),
          "heightDp" to num(5),
          "color" to argb(WHITE),
          "alpha" to num(0.06),
        )
      }
      gear("racing-gear-centre", c, 100.0, 34.0, 24, "time.continuousSecond * 6")
      gear("racing-gear-left", 60.0, 128.0, 20.0, 14, "-time.continuousSecond * 10.3")
      gear("racing-gear-right", 140.0, 128.0, 16.0, 11, "-time.continuousSecond * 13.1")
      // The escape wheel steps eight times a second rather than turning smoothly.
      gear("racing-escape", 122.0, 156.0, 9.0, 15, "floor(time.continuousSecond * 8) * 6")
      // The balance swings four times a second through 150 degrees either way.
      turned("racing-balance", "sin(time.continuousSecond * 25.13) * 150", c, windowY) {
        circle(
          "racing-balance-rim",
          16,
          argb(GOLD),
          c,
          windowY,
          "style" to enum("stroke"),
          "strokeWidthDp" to num(3),
        )
        repeat("racing-balance-spokes", 3, "k") {
          turned("racing-balance-spoke", "@k * 120", c, windowY) {
            line("racing-balance-arm", c to windowY, c to windowY - 16, 1.6, argb(GOLD))
          }
        }
      }
      repeat("racing-hairspring", 4, "r") {
        op(
          "racing-hairspring-coil",
          "draw/circle",
          "centerXDp" to num(c),
          "centerYDp" to num(windowY),
          "radiusDp" to formula("3 + @r * 2.5"),
          "style" to enum("stroke"),
          "strokeWidthDp" to num(0.6),
          "color" to argb(GOLD),
          "alpha" to num(0.8),
        )
      }
      circle("racing-balance-jewel", 2.2, argb(JEWEL), c, windowY)
    }

    // The dial: carbon, smoked, everywhere but the window at six.
    container(
      "racing-dial-cut",
      UiDrawing.CLIP,
      "pathData" to text(circlePath(c, windowY, window)),
      "viewportWidth" to num(ClockCanvas.SIZE_DP),
      "viewportHeight" to num(ClockCanvas.SIZE_DP),
      "exclude" to flag(true),
    ) {
      circle("racing-dial", 78, argb(CARBON), c, c, "alpha" to num(0.9))
      container(
        "racing-weave-clip",
        UiDrawing.CLIP,
        "pathData" to text(circlePath(c, c, 78.0)),
        "viewportWidth" to num(ClockCanvas.SIZE_DP),
        "viewportHeight" to num(ClockCanvas.SIZE_DP),
      ) {
        turned("racing-weave", "45") {
          repeat("racing-weave-lines", 40, "v") {
            op(
              "racing-weave-line",
              "draw/rect",
              "xDp" to formula("-20 + @v * 6"),
              "yDp" to num(-20),
              "widthDp" to num(3),
              "heightDp" to num(240),
              "color" to argb(WEAVE),
              "alpha" to num(0.35),
            )
          }
        }
      }
    }
    circle(
      "racing-window-rim",
      window,
      argb(STEEL),
      c,
      windowY,
      "style" to enum("stroke"),
      "strokeWidthDp" to num(2),
    )

    // Minute track and lumed indices, leaving three, six and nine to the sub-dials and window.
    repeat("racing-minute-track", 60, "n") {
      turned("racing-minute", "@n * 6") {
        line("racing-minute-mark", c to 23, c to 26, 0.8, argb(WHITE), "alpha" to num(0.75))
      }
    }
    repeat("racing-hours", 12, "h") {
      turned("racing-hour", "@h * 30") {
        rect(
          "racing-hour-bar",
          c - 3,
          28,
          6,
          15,
          argb(WHITE),
          1.5,
          "alpha" to formula("select(@h == 0 || @h == 3 || @h == 6 || @h == 9, 0.0, 1.0)"),
        )
        rect(
          "racing-hour-lume",
          c - 1.5,
          30,
          3,
          11,
          argb(LUME),
          0,
          "alpha" to formula("select(@h == 0 || @h == 3 || @h == 6 || @h == 9, 0.0, 1.0)"),
        )
      }
    }
    rect("racing-twelve-left", c - 8, 28, 6, 17, argb(WHITE), corner = 1.5)
    rect("racing-twelve-right", c + 2, 28, 6, 17, argb(WHITE), corner = 1.5)
    label("racing-name", text("CHRONOGRAPH"), c, 56, 7, argb(WHITE))
    label("racing-automatic", text("AUTOMATIC"), c, 64, 5, argb(RED))
    label("racing-depth", text("200 M"), c, 72, 4.5, argb(WHITE), "center", "alpha" to num(0.7))

    // Date at half past four.
    rect("racing-date-window", 130, 124, 18, 13, argb(WHITE), corner = 1.5)
    label(
      "racing-date",
      formula("toString(toInt(time.dayOfMonth))"),
      139,
      130.5,
      8,
      argb(CARBON),
    )

    subDial("racing-seconds", 58.0, 100.0, "(time.secondOfHour % 60) * 6", WHITE, "60")
    subDial("racing-counter", 142.0, 100.0, "(time.minuteOfDay % 30) * 12", RED, "30")
    label(
      "racing-swiss",
      text("SWISS MADE"),
      c,
      173,
      3.5,
      argb(WHITE),
      "center",
      "alpha" to num(0.8),
    )

    // Hands: lumed hour and minute, then the red chronograph seconds with its counterweight.
    turned("racing-hour-hand", "(time.minuteOfDay % 720) / 2") {
      line("racing-hour-body", c to c + 8, c to c - 44, 7, argb(WHITE))
      line("racing-hour-hand-lume", c to c - 12, c to c - 40, 3, argb(LUME))
    }
    turned("racing-minute-hand", "time.secondOfHour / 10") {
      line("racing-minute-body", c to c + 10, c to c - 70, 5, argb(WHITE))
      line("racing-minute-lume", c to c - 16, c to c - 66, 2, argb(LUME))
    }
    turned("racing-chrono-hand", "(time.continuousSecond % 60) * 6") {
      line("racing-chrono-body", c to c + 24, c to c - 76, 1.4, argb(RED))
      circle("racing-chrono-weight", 4, argb(RED), c, c + 20)
    }
    circle("racing-hub", 4.5, argb(RED), c, c)
    circle("racing-hub-pin", 1.6, argb(STEEL), c, c)
  }

/** A gold gear of [teeth] teeth at ([x], [y]), turned by [rotate] about its own centre. */
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
      argb(GOLD),
      x,
      y,
      "style" to enum("stroke"),
      "strokeWidthDp" to num(2.5),
    )
    repeat("$id-teeth", teeth, "t") {
      turned("$id-tooth", "@t * ${360.0 / teeth}", x, y) {
        rect("$id-tooth-block", x - 1.4, y - radius - 3.5, 2.8, 4.5, argb(GOLD))
      }
    }
    repeat("$id-spokes", 5, "p") {
      turned("$id-spoke", "@p * 72", x, y) {
        line("$id-spoke-arm", x to y, x to y - radius, 1.8, argb(GOLD))
      }
    }
    circle("$id-jewel", 2, argb(JEWEL), x, y)
  }

/** A sub-dial at ([x], [y]) with snailed rings, twelve marks, its top number, and a hand. */
private fun ClockCanvas.subDial(
  id: String,
  x: Double,
  y: Double,
  rotate: String,
  hand: String,
  top: String,
) {
  circle("$id-face", 19, argb(CARBON), x, y)
  repeat("$id-snail", 5, "s") {
    op(
      "$id-snail-ring",
      "draw/circle",
      "centerXDp" to num(x),
      "centerYDp" to num(y),
      "radiusDp" to formula("4 + @s * 3"),
      "style" to enum("stroke"),
      "strokeWidthDp" to num(0.5),
      "color" to argb(WHITE),
      "alpha" to num(0.12),
    )
  }
  circle(
    "$id-ring",
    19,
    argb(STEEL),
    x,
    y,
    "style" to enum("stroke"),
    "strokeWidthDp" to num(1.2),
  )
  repeat("$id-marks", 12, "m") {
    turned("$id-mark", "@m * 30", x, y) {
      line("$id-mark-line", x to y - 18, x to y - 15, 0.8, argb(WHITE))
    }
  }
  label("$id-top", text(top), x, y - 10, 4.5, argb(WHITE))
  turned("$id-hand", rotate, x, y) {
    line("$id-hand-line", x to y + 4, x to y - 16, 1.4, argb(hand))
  }
  circle("$id-pin", 2, argb(hand), x, y)
}
