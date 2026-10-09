package ee.schimke.composeai.uibuilder.editor

import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ee.schimke.composeai.rcplayer.compose.LocalRcTimeSource
import ee.schimke.composeai.rcplayer.runtime.RcTimeSnapshot
import ee.schimke.composeai.rcplayer.runtime.RcTimeSource
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiExpressions
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Whether the device previews let time run: off until somebody asks, so a preview opens on the same
 * still frame every render of the design draws, and a click sets the clock hands turning and the
 * animations playing.
 */
@Composable
internal fun PreviewTimeToggle(
  running: Boolean,
  onRunningChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  FilterChip(
    selected = running,
    onClick = { onRunningChange(!running) },
    label = { Text(if (running) "Live time" else "Frozen time") },
    modifier =
      modifier.semantics {
        contentDescription = if (running) "Freeze preview time" else "Run preview time"
      },
  )
}

/**
 * [content]'s players at [document]'s `fixedTime` unless time runs — the wall clock the canvas
 * renderer draws a design that is not running at.
 *
 * Animation time is left to the player. Holding it with `LocalRcAnimationClock` would freeze it,
 * but a host-driven clock makes the player redraw every frame for any document that declares a
 * float animation — every Remote M3 button carries a press spring — so a frozen preview never
 * idled.
 */
@Composable
internal fun ProvidePreviewPlayerTime(
  document: UiBuilderDocument,
  running: Boolean,
  content: @Composable () -> Unit,
) {
  if (running) {
    content()
    return
  }
  val fixedTime = (document.environment["fixedTime"] as? JsonPrimitive)?.contentOrNull
  val source = remember(fixedTime) { FixedRcTimeSource(UiExpressions.Clock.of(fixedTime)) }
  CompositionLocalProvider(LocalRcTimeSource provides source, content = content)
}

/** Always [clock]'s instant, read as the canvas reads it: its own fields, not the host's zone. */
private class FixedRcTimeSource(private val clock: UiExpressions.Clock) : RcTimeSource {
  override fun currentTimeMillis(): Long = clock.epochMillis

  override fun snapshot(epochMillis: Long): RcTimeSnapshot =
    RcTimeSnapshot(
      epochMillis = clock.epochMillis,
      year = clock.year,
      month = clock.month,
      dayOfMonth = clock.dayOfMonth,
      dayOfYear = clock.dayOfYear,
      hour = clock.hour,
      minute = clock.minute,
      second = clock.second,
      isoDayOfWeek = clock.dayOfWeek,
      offsetSeconds = clock.utcOffsetSeconds,
    )
}
