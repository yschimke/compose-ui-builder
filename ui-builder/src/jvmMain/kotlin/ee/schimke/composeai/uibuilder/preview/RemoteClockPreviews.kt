package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.ProvideProductionCatalog
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.ClockCanvas
import ee.schimke.composeai.uibuilder.export.RemoteClockTemplates
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument

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

/** The shared environment; the clocks set their own 10:10:30 ([ClockCanvas.PREVIEW_TIME]). */
private val CLOCK_TIME = wearWidgetSampleEnvironment
