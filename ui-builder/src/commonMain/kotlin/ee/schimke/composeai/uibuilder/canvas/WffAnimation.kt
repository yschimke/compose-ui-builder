package ee.schimke.composeai.uibuilder.canvas

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * A `<Transform>`'s `<Animation>`, as the Watch Face Format reference defines it: the change the
 * transform makes to its target is not applied at once but played over [durationSeconds], eased by
 * [interpolation], at [fps] frames a second, [repeat] more times after the first (`-1` for ever).
 * An angle turns the way [angleDirection] says.
 *
 * WFF defines no `<Sequence>` element (the reference and the v1–v4 schemas give `<Transform>` one
 * optional `<Animation>` child and nothing else), so there is no sequence to play here: a face
 * holding one is not valid WFF and is drawn settled.
 */
internal data class WffAnimation(
  val durationSeconds: Double,
  val interpolation: Interpolation = Interpolation.LINEAR,
  /** `CUBIC_BEZIER`'s control points `x1, y1, x2, y2`; the reference's default is all 0.5. */
  val controls: List<Double> = DEFAULT_CONTROLS,
  val angleDirection: AngleDirection = AngleDirection.NONE,
  /** How many times the animation plays again after the first: 0 never, -1 for ever. */
  val repeat: Int = 0,
  val fps: Int = DEFAULT_FPS,
) {
  enum class Interpolation {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    OVERSHOOT,
    CUBIC_BEZIER,
  }

  enum class AngleDirection {
    NONE,
    CLOCKWISE,
    COUNTER_CLOCKWISE,
  }

  /** Whether it never settles: it repeats for ever. */
  val endless: Boolean
    get() = repeat < 0 && durationSeconds > 0.0

  /** When the last play ends, in seconds from the start; infinite when [endless]. */
  val endSeconds: Double
    get() =
      when {
        durationSeconds <= 0.0 -> 0.0
        endless -> Double.POSITIVE_INFINITY
        else -> durationSeconds * (repeat + 1)
      }

  /**
   * The target's value [seconds] after the animation started, going from [from] to [to]; [to], the
   * settled frame, for null [seconds] or once the last play has ended. Each repeat starts again
   * from [from]. Time moves in steps of one frame at [fps], as the watch draws it. An [angle] goes
   * straight from [from] to [to] unless [angleDirection] names a way round, then turns that way.
   */
  fun valueAt(from: Double, to: Double, seconds: Double?, angle: Boolean = false): Double {
    val end = if (angle) from + angleDelta(from, to) else to
    if (seconds == null || durationSeconds <= 0.0) return end
    val t = if (fps > 0) floor(seconds.coerceAtLeast(0.0) * fps + 1e-9) / fps else seconds
    if (t >= endSeconds) return end
    val cycle = floor(t / durationSeconds)
    val progress = ((t - cycle * durationSeconds) / durationSeconds).coerceIn(0.0, 1.0)
    return from + (end - from) * eased(progress)
  }

  /** [progress] (0 to 1 through one play) as [interpolation] shapes it. */
  fun eased(progress: Double): Double {
    val p = progress.coerceIn(0.0, 1.0)
    return when (interpolation) {
      Interpolation.LINEAR -> p
      // Android's accelerate, decelerate and accelerate-decelerate interpolators, factor 1.
      Interpolation.EASE_IN -> p * p
      Interpolation.EASE_OUT -> 1 - (1 - p) * (1 - p)
      Interpolation.EASE_IN_OUT -> cos((p + 1) * PI) / 2 + 0.5
      // Android's overshoot interpolator at its default tension of 2: past the end, then back.
      Interpolation.OVERSHOOT -> {
        val q = p - 1
        q * q * ((OVERSHOOT_TENSION + 1) * q + OVERSHOOT_TENSION) + 1
      }
      Interpolation.CUBIC_BEZIER -> cubicBezier(p)
    }
  }

  /** The curve from (0,0) to (1,1) through [controls], read at x = [x]. */
  private fun cubicBezier(x: Double): Double {
    val (x1, y1, x2, y2) = controls
    fun at(t: Double, a: Double, b: Double): Double {
      val u = 1 - t
      return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t
    }
    // x(t) rises from 0 to 1 when x1 and x2 are within 0..1, as the reference's controls are:
    // bisect for the t whose x is the progress.
    var lo = 0.0
    var hi = 1.0
    repeat(40) {
      val mid = (lo + hi) / 2
      if (at(mid, x1, x2) < x) lo = mid else hi = mid
    }
    return at((lo + hi) / 2, y1, y2)
  }

  /** How far an angle turns from [from] to reach [to], the way [angleDirection] says. */
  private fun angleDelta(from: Double, to: Double): Double {
    val turn = to - from
    return when (angleDirection) {
      AngleDirection.NONE -> turn
      AngleDirection.CLOCKWISE ->
        turn.mod(360.0).let { if (it == 0.0 && turn != 0.0) 360.0 else it }
      AngleDirection.COUNTER_CLOCKWISE ->
        (-(-turn).mod(360.0)).let { if (it == 0.0 && turn != 0.0) -360.0 else it }
    }
  }

  companion object {
    const val DEFAULT_FPS = 15
    private const val OVERSHOOT_TENSION = 2.0
    private val DEFAULT_CONTROLS = listOf(0.5, 0.5, 0.5, 0.5)

    /**
     * From an `<Animation>`'s attributes, or null without a positive `duration` (the reference
     * requires one). An unknown enum value or an unreadable number takes the reference's default.
     */
    fun of(attributes: Map<String, String>): WffAnimation? {
      val duration = attributes["duration"]?.trim()?.toDoubleOrNull() ?: return null
      if (duration.isNaN() || duration <= 0.0) return null
      val controls =
        attributes["controls"]
          ?.split(',', ' ', '<', '>', '(', ')')
          ?.mapNotNull { it.trim().toDoubleOrNull() }
          ?.takeIf { it.size == 4 } ?: DEFAULT_CONTROLS
      return WffAnimation(
        durationSeconds = duration,
        interpolation =
          attributes["interpolation"]?.trim()?.let { name ->
            Interpolation.entries.firstOrNull { it.name == name }
          } ?: Interpolation.LINEAR,
        controls = controls,
        angleDirection =
          attributes["angleDirection"]?.trim()?.let { name ->
            AngleDirection.entries.firstOrNull { it.name == name }
          } ?: AngleDirection.NONE,
        repeat = attributes["repeat"]?.trim()?.toIntOrNull()?.coerceAtLeast(-1) ?: 0,
        fps = attributes["fps"]?.trim()?.toIntOrNull() ?: DEFAULT_FPS,
      )
    }
  }
}
