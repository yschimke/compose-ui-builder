package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.LocalRcTimeSource
import ee.schimke.composeai.rcplayer.runtime.RcTimeSnapshot
import ee.schimke.composeai.rcplayer.runtime.RcTimeSource
import ee.schimke.composeai.uibuilder.canvas.WffPreviewClockState
import ee.schimke.composeai.uibuilder.canvas.WffScrubTime
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiExpressions
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Whether device previews let time run: off by default so previews open on the design's still
 * frame; a click starts clocks and animations.
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
 * Interactive or Ambient, for a watch face's editing canvas: the mode the face is drawn in. The
 * Preview pane shows both side by side; this is the one frame the author edits.
 */
@Composable
internal fun WffAmbientToggle(
  ambient: Boolean,
  onAmbientChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    FilterChip(
      selected = !ambient,
      onClick = { onAmbientChange(false) },
      label = { Text("Interactive") },
      modifier = Modifier.semantics { contentDescription = "Draw the face interactive" },
    )
    FilterChip(
      selected = ambient,
      onClick = { onAmbientChange(true) },
      label = { Text("Ambient") },
      modifier = Modifier.semantics { contentDescription = "Draw the face ambient" },
    )
  }
}

/**
 * A watch face's preview clock: Live (the wall clock, animations playing from when the face is
 * shown) or Scrub, a time the author drags to with play and pause. Scrub's seconds slider is the
 * minute's 60-second window, which second-driven motion and a face's `<Animation>`s play through
 * (the window looping while it plays); hour and minute move the hands. Only the preview moves:
 * [clock] is editor state, and nothing here writes the design.
 */
@Composable
internal fun WffPreviewClockControls(clock: WffPreviewClockState, modifier: Modifier = Modifier) {
  val scrub = clock.scrubbed
  Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilterChip(
        selected = scrub == null,
        onClick = { clock.live() },
        label = { Text("Live") },
        modifier = Modifier.semantics { contentDescription = "Draw the face at the wall clock" },
      )
      FilterChip(
        selected = scrub != null,
        onClick = { if (scrub == null) clock.scrubbed = SCRUB_START },
        label = { Text(scrub?.label() ?: "Scrub") },
        modifier = Modifier.semantics { contentDescription = "Scrub the preview time" },
      )
      if (scrub != null) {
        IconButton(onClick = { clock.playing = !clock.playing }) {
          Icon(
            if (clock.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (clock.playing) "Pause preview time" else "Play preview time",
          )
        }
      }
    }
    if (scrub != null) {
      ClockSlider("s", scrub.seconds.toFloat(), 0f..60f, "Preview second") {
        clock.scrubbed = WffScrubTime.of(scrub.hour, scrub.minute, it.toDouble())
      }
      ClockSlider("m", scrub.minute.toFloat(), 0f..59f, "Preview minute") {
        clock.scrubbed = WffScrubTime.of(scrub.hour, it.roundToInt(), scrub.seconds)
      }
      ClockSlider("h", scrub.hour.toFloat(), 0f..23f, "Preview hour") {
        clock.scrubbed = WffScrubTime.of(it.roundToInt(), scrub.minute, scrub.seconds)
      }
    }
  }
}

@Composable
private fun ClockSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  description: String,
  onChange: (Float) -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.width(16.dp),
    )
    Slider(
      value = value,
      onValueChange = onChange,
      valueRange = range,
      modifier = Modifier.widthIn(max = 220.dp).semantics { contentDescription = description },
    )
  }
}

/** `10:08:32.4`, as the Scrub chip names the time it is at. */
internal fun WffScrubTime.label(): String {
  val whole = seconds.toInt()
  val tenths = ((seconds - whole) * 10).toInt().coerceIn(0, 9)
  fun two(n: Int) = n.toString().padStart(2, '0')
  return "${two(hour)}:${two(minute)}:${two(whole)}.$tenths"
}

/**
 * Where Scrub starts: 10:08, the hands' customary pose, at the top of the minute so the face's
 * animations are at their start.
 */
private val SCRUB_START = WffScrubTime(10, 8, 0.0)

/**
 * Moves [clock] on while it plays, frame by frame, its seconds looping in the minute's window. One
 * per editor, however many surfaces show the controls, so time does not run double.
 */
@Composable
internal fun RunWffPreviewClock(clock: WffPreviewClockState) {
  LaunchedEffect(clock) {
    snapshotFlow { clock.playing && clock.scrubbed != null }
      .collectLatest { playing ->
        if (!playing) return@collectLatest
        var last = withFrameMillis { it }
        while (true) {
          withFrameMillis { now ->
            clock.scrubbed = clock.scrubbed?.advancedBy((now - last) / 1000.0)
            last = now
          }
        }
      }
  }
}

/**
 * [content]'s players at [document]'s `fixedTime` unless time runs. Animation time is left to the
 * player: a host-driven clock would redraw every frame for any float animation, so a frozen preview
 * would never idle.
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
