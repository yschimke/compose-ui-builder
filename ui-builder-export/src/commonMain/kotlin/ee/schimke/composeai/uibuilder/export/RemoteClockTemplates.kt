package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.dayName
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.enum
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.formula
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.num
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.text
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.token
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.twoDigits
import kotlinx.serialization.json.JsonObject

/**
 * Clock faces on a `RemoteCanvas`: the worked examples of `remote-m3` content that moves.
 *
 * Nothing in them is state. Every moving part is a formula over the host's clock — `time.hour`,
 * `time.minuteOfDay`, `time.secondOfHour`, `time.continuousSecond`, `time.dayOfWeek`,
 * `time.dayOfMonth`, `time.utcOffset` — which the canvas evaluates at the environment's fixed time
 * and the player against its own clock every frame. A hand is a vertical line up to twelve o'clock
 * inside a `draw/group` turned by its formula; repeated marks are one mark in a `draw/repeat`.
 */
enum class RemoteClockTemplates(
  val templateId: String,
  val label: String,
  val supportingText: String,
) {
  /**
   * Hour marks from one loop, three hands, and one complication: the day of the week in a window at
   * three o'clock.
   */
  Analog(
    templateId = "remote-clock",
    label = "Analog clock",
    supportingText =
      "Hands that turn with the time, hour marks drawn by a loop, and a day-of-week window.",
  ),
  /**
   * The time as text, a ring of sixty second marks that light as the minute passes, and two
   * complications: the UTC offset and how much of the day has gone.
   */
  Digital(
    templateId = "remote-digital-clock",
    label = "Digital clock",
    supportingText =
      "The time as text inside a ring of seconds, with date, UTC offset and day-progress " +
        "complications.",
  );

  fun document(designId: String, catalogPin: JsonObject, environment: JsonObject) =
    when (this) {
      Analog -> analog(designId, catalogPin, environment)
      Digital -> digital(designId, catalogPin, environment)
    }

  companion object {
    /**
     * The clocks a new design may start from: all of them where Remote root content exports
     * (`-PuiBuilderRemoteCompose=true`), and none where it does not — a template whose Code pane
     * could only refuse is not a starting point.
     */
    val offered: List<RemoteClockTemplates>
      get() = if (UiBuilderBuildFeatures.remoteCompose) entries else emptyList()

    fun forTemplate(templateId: String): RemoteClockTemplates? = entries.firstOrNull {
      it.templateId == templateId
    }
  }
}

private fun analog(designId: String, catalogPin: JsonObject, environment: JsonObject) =
  ClockCanvas.document(designId, "Analog clock", "clock", catalogPin, environment) {
    val c = centre
    circle("clock-face", c - 4, token("surfaceContainerHigh"))
    repeat("clock-ticks", 12, "hour") {
      turned("clock-tick", "@hour * 30") {
        line(
          "clock-tick-mark",
          c to 16,
          c to 28,
          4,
          token("onSurface"),
          "alpha" to formula("select(@hour % 3 == 0, 1.0, 0.4)"),
        )
      }
    }
    // The complication, under the hands as a real date window is.
    rect("clock-day-window", 122, 89, 38, 22, token("surfaceContainer"), corner = 6)
    label(
      "clock-day",
      formula(dayName(SHORT_DAYS)),
      141,
      100,
      11,
      token("primary"),
    )
    turned("clock-hour-hand", "(time.minuteOfDay % 720) / 2") {
      line("clock-hour", c to c, c to c - 50, 8, token("onSurface"))
    }
    turned("clock-minute-hand", "time.secondOfHour / 10") {
      line("clock-minute", c to c, c to c - 74, 5, token("onSurface"))
    }
    turned("clock-second-hand", "(time.continuousSecond % 60) * 6") {
      line("clock-second", c to c + 16, c to c - 82, 2, token("primary"))
    }
    circle("clock-hub", 6, token("primary"))
  }

private fun digital(designId: String, catalogPin: JsonObject, environment: JsonObject) =
  ClockCanvas.document(designId, "Digital clock", "digital", catalogPin, environment) {
    val c = centre
    val minute = "toInt(time.minuteOfDay % 60)"
    val second = "toInt(time.secondOfHour % 60)"
    circle("digital-face", c - 2, token("surfaceContainer"))
    // Sixty marks, lit up to the current second: the loop index against the clock.
    repeat("digital-seconds", 60, "s") {
      turned("digital-second", "@s * 6") {
        line(
          "digital-second-mark",
          c to 7,
          c to 14,
          2.5,
          token("primary"),
          "alpha" to formula("select(@s <= $second, 1.0, 0.18)"),
        )
      }
    }
    label(
      "digital-date",
      formula("concat(${dayName(SHORT_DAYS)}, \" \", " + "toString(toInt(time.dayOfMonth)))"),
      c,
      56,
      14,
      token("primary"),
    )
    label(
      "digital-time",
      formula("concat(${twoDigits("toInt(time.hour)")}, \":\", ${twoDigits(minute)})"),
      c,
      94,
      46,
      token("onSurface"),
    )
    label("digital-time-seconds", formula(twoDigits(second)), c, 124, 14, token("onSurfaceVariant"))
    // Complication: the UTC offset, in whole hours.
    label("digital-utc-label", text("UTC"), 68, 140, 9, token("onSurfaceVariant"))
    label(
      "digital-utc",
      formula(
        "concat(select(time.utcOffset >= 0, \"+\", \"-\"), " +
          "toString(toInt(abs(time.utcOffset) / 3600)))"
      ),
      68,
      153,
      13,
      token("onSurface"),
    )
    // Complication: how much of the day has gone, as a ring and a percentage.
    op(
      "digital-day-track",
      "draw/arc",
      "xDp" to num(119),
      "yDp" to num(133),
      "widthDp" to num(26),
      "heightDp" to num(26),
      "startAngle" to num(-90),
      "sweepAngle" to num(360),
      "style" to enum("stroke"),
      "strokeWidthDp" to num(3),
      "color" to token("outlineVariant"),
    )
    op(
      "digital-day-progress",
      "draw/arc",
      "xDp" to num(119),
      "yDp" to num(133),
      "widthDp" to num(26),
      "heightDp" to num(26),
      "startAngle" to num(-90),
      "sweepAngle" to formula("time.minuteOfDay / 4"),
      "style" to enum("stroke"),
      "strokeWidthDp" to num(3),
      "strokeCap" to enum("round"),
      "color" to token("tertiary"),
    )
    label(
      "digital-day-percent",
      formula("toString(toInt(time.minuteOfDay / 14.4))"),
      132,
      146,
      9,
      token("onSurface"),
    )
  }

private val SHORT_DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
