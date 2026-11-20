package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A time of day the author scrubbed a watch-face preview to: [hour] and [minute] for the hands,
 * [seconds] into the minute (0 until 60, fractions included) for second-driven motion. The seconds
 * are also how far the face's `<Animation>`s have played: the scrubbed minute is the playback,
 * starting at its top.
 */
internal data class WffScrubTime(val hour: Int, val minute: Int, val seconds: Double) {
  init {
    require(hour in 0..23 && minute in 0..59 && seconds >= 0.0 && seconds < 60.0) {
      "not a time of day: $hour:$minute:$seconds"
    }
  }

  /** This time [delta] seconds on, wrapping within the minute so the window loops. */
  fun advancedBy(delta: Double): WffScrubTime = copy(seconds = (seconds + delta).mod(60.0))

  companion object {
    /** [hour], [minute] and [seconds], each brought into range. */
    fun of(hour: Int, minute: Int, seconds: Double) =
      WffScrubTime(hour.mod(24), minute.mod(60), seconds.coerceIn(0.0, MAX_SECONDS))

    /** The last frame of the window: 60 itself is the next minute's top. */
    const val MAX_SECONDS = 59.999
  }
}

/**
 * The time an editor's watch-face surfaces are drawn at: the wall clock while [scrubbed] is null,
 * else that time, moving on while [playing]. Editor state, shared by the editing canvas and the
 * Preview pane, never written to the design: a design pins its time with `fixedTime` only.
 *
 * Read through state, so a playing clock recomposes only the scenes that draw it.
 */
@Stable
internal class WffPreviewClockState {
  var scrubbed: WffScrubTime? by mutableStateOf(null)
  var playing: Boolean by mutableStateOf(false)

  /** Back to the wall clock. */
  fun live() {
    scrubbed = null
    playing = false
  }
}

/**
 * The editor's preview clock, for the watch-face scenes it draws. Null outside an editor (a host's
 * surface, an export, a thumbnail): there a face's animations play only when its document lets time
 * run, and settle otherwise.
 */
internal val LocalWffPreviewClock = staticCompositionLocalOf<WffPreviewClockState?> { null }
