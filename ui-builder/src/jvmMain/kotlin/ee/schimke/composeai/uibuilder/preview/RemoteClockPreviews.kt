package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.ProvideProductionCatalog
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.ClockCanvas
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.argb
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.dayName
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.enum
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.formula
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.num
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.text
import ee.schimke.composeai.uibuilder.export.ClockCanvas.Companion.twoDigits
import ee.schimke.composeai.uibuilder.export.RemoteClockTemplates
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The clock designs as the editor canvas draws them, at 10:10:30 on a Thursday rather than the
 * shared environment's noon, where every hand would stack on twelve and the pictures could not show
 * that each turns by its own formula.
 */

@Preview(widthDp = 220, heightDp = 220)
@Composable
fun AnalogClockPreview() = ClockSurface(RemoteClockTemplates.Analog.document(ID, PIN, CLOCK_TIME))

@Preview(widthDp = 220, heightDp = 220)
@Composable
fun DigitalClockPreview() = ClockSurface(RemoteClockTemplates.Digital.document(ID, PIN, CLOCK_TIME))

/**
 * A racing chronograph with its movement running underneath, for looking at only: see
 * [racingChronographReplica].
 */
@Preview(widthDp = 220, heightDp = 220)
@Composable
fun RacingChronographReplicaPreview() = ClockSurface(racingChronographReplica(ID, PIN, CLOCK_TIME))

/**
 * A replica of a classic resin LCD digital watch, for looking at only.
 *
 * Deliberately not a template: it copies another company's product design, so it lives beside the
 * previews, which never ship, rather than in the New design chooser. It carries no maker's name.
 */
@Preview(widthDp = 220, heightDp = 220)
@Composable
fun LcdWatchReplicaPreview() = ClockSurface(lcdWatchReplica(ID, PIN, CLOCK_TIME))

@Composable
private fun ClockSurface(document: UiBuilderDocument) {
  ProvideProductionCatalog(document) {
    UiBuilderSurface(document = document, editorOverlay = false)
  }
}

private const val ID = "clock-preview"

private val PIN = wearWidgetSampleCatalogPin

private val CLOCK_TIME =
  JsonObject(wearWidgetSampleEnvironment + ("fixedTime" to JsonPrimitive("2024-05-16T10:10:30Z")))

private const val RESIN = "#FF1C1D1F"
private const val STRAP = "#FF141414"
private const val BUTTON = "#FF2E2F33"
private const val STEEL = "#FFB4B9BF"
private const val STEEL_DARK = "#FF6B7077"
private const val INK = "#FF1B1D1A"
private const val LCD = "#FFA6B09A"
private const val LCD_FRAME = "#FF2B2F2B"
private const val ACCENT = "#FFD5402B"
private const val BLUE = "#FF2A4A9C"

/** The replica: a resin case, a steel faceplate and a grey-green LCD reading the time. */
internal fun lcdWatchReplica(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
): UiBuilderDocument =
  ClockCanvas.document(designId, "LCD watch replica", "lcd", catalogPin, environment) {
    rect("lcd-strap-top", 54, 0, 92, 24, argb(STRAP))
    rect("lcd-strap-bottom", 54, 176, 92, 24, argb(STRAP))
    rect("lcd-button-light", 9, 60, 10, 16, argb(BUTTON), corner = 3)
    rect("lcd-button-mode", 9, 128, 10, 16, argb(BUTTON), corner = 3)
    rect("lcd-button-alarm", 181, 128, 10, 16, argb(BUTTON), corner = 3)
    rect("lcd-case", 16, 18, 168, 164, argb(RESIN), corner = 28)
    rect(
      "lcd-faceplate",
      30,
      32,
      140,
      136,
      argb(STEEL),
      12,
      "gradient" to enum("vertical"),
      "gradientColor" to argb(STEEL_DARK),
    )
    label("lcd-light", text("LIGHT"), 38, 42, 6, argb(INK), "start")
    label("lcd-model-line", text("ALARM CHRONOGRAPH"), 100, 52, 8, argb(ACCENT))
    rect("lcd-frame", 42, 60, 116, 82, argb(LCD_FRAME), corner = 6)
    rect("lcd-glass", 47, 65, 106, 72, argb(LCD), corner = 3)

    // Unlit segments: every digit's 8, faintly, as a real LCD shows them.
    label("lcd-ghost-time", text("88:88"), 136, 108, 30, argb(INK), "end", "alpha" to num(0.07))
    label("lcd-ghost-seconds", text("88"), 150, 113, 14, argb(INK), "end", "alpha" to num(0.07))

    label(
      "lcd-day",
      formula(dayName(listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU"))),
      54,
      78,
      11,
      argb(INK),
      "start",
    )
    label("lcd-date", formula("toString(toInt(time.dayOfMonth))"), 146, 78, 11, argb(INK), "end")
    label(
      "lcd-time",
      formula(
        "concat(toString(toInt(time.hour)), \":\", ${twoDigits("toInt(time.minuteOfDay % 60)")})"
      ),
      136,
      108,
      30,
      argb(INK),
      "end",
    )
    label(
      "lcd-seconds",
      formula(twoDigits("toInt(time.secondOfHour % 60)")),
      150,
      113,
      14,
      argb(INK),
      "end",
    )

    label("lcd-mode", text("MODE"), 36, 150, 5, argb(INK), "start")
    label("lcd-alarm", text("ALARM ON·OFF/24HR"), 164, 150, 5, argb(INK), "end")
    label("lcd-water", text("WATER"), 46, 160, 7, argb(BLUE), "start")
    label("lcd-wr", text("WR"), 100, 160, 7, argb(BLUE))
    label("lcd-resist", text("RESIST"), 154, 160, 7, argb(BLUE), "end")
  }
