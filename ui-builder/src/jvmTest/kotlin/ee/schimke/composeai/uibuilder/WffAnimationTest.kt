package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.WffAnimation
import ee.schimke.composeai.uibuilder.canvas.WffAnimation.AngleDirection
import ee.schimke.composeai.uibuilder.canvas.WffAnimation.Interpolation
import ee.schimke.composeai.uibuilder.canvas.WffScrubTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `<Animation>` as the Watch Face Format reference defines it: `duration` seconds, `repeat` more
 * plays (-1 for ever), `interpolation` with `CUBIC_BEZIER`'s `controls`, `fps` steps and
 * `angleDirection`; at its end when settled.
 */
class WffAnimationTest {
  private fun linear(duration: Double = 1.0, repeat: Int = 0, fps: Int = 0) =
    WffAnimation(duration, repeat = repeat, fps = fps)

  @Test
  fun `the settled frame is the end, whatever the time`() {
    val fade = linear()
    assertEquals(255.0, fade.valueAt(0.0, 255.0, null))
    assertEquals(255.0, fade.valueAt(0.0, 255.0, 1.0))
    assertEquals(255.0, fade.valueAt(0.0, 255.0, 30.0))
  }

  @Test
  fun `one play goes from the value before to the transform's`() {
    val fade = linear()
    assertEquals(0.0, fade.valueAt(0.0, 255.0, 0.0))
    assertEquals(127.5, fade.valueAt(0.0, 255.0, 0.5))
    assertEquals(63.75, fade.valueAt(0.0, 255.0, 0.25))
    // Negative time (before the face is shown) is the start.
    assertEquals(0.0, fade.valueAt(0.0, 255.0, -3.0))
  }

  @Test
  fun `repeat plays again from the start that many more times, -1 for ever`() {
    val twice = linear(duration = 2.0, repeat = 1)
    assertEquals(4.0, twice.endSeconds)
    assertEquals(50.0, twice.valueAt(0.0, 100.0, 1.0))
    assertEquals(50.0, twice.valueAt(0.0, 100.0, 3.0))
    assertEquals(100.0, twice.valueAt(0.0, 100.0, 4.0))
    assertEquals(100.0, twice.valueAt(0.0, 100.0, 9.0))
    val forever = linear(repeat = -1)
    assertTrue(forever.endless)
    assertEquals(Double.POSITIVE_INFINITY, forever.endSeconds)
    assertEquals(25.0, forever.valueAt(0.0, 100.0, 1234.25), 1e-6)
    // ...but even an endless one is drawn at its end when settled.
    assertEquals(100.0, forever.valueAt(0.0, 100.0, null))
  }

  @Test
  fun `time moves in whole frames at fps, 15 by default`() {
    val stepped = WffAnimation(1.0)
    assertEquals(15, stepped.fps)
    // 0.1 s is frame 1 of 15: 1/15 of the way.
    assertEquals(150.0 / 15, stepped.valueAt(0.0, 150.0, 0.1), 1e-9)
    assertEquals(150.0 / 15, stepped.valueAt(0.0, 150.0, 0.13), 1e-9)
    assertEquals(0.0, WffAnimation(1.0, fps = 2).valueAt(0.0, 100.0, 0.49))
    assertEquals(50.0, WffAnimation(1.0, fps = 2).valueAt(0.0, 100.0, 0.5))
  }

  @Test
  fun `each interpolation eases as Android's interpolators do`() {
    fun at(interpolation: Interpolation, p: Double) =
      WffAnimation(1.0, interpolation = interpolation).eased(p)
    for (interpolation in Interpolation.entries) {
      assertEquals(0.0, at(interpolation, 0.0), 1e-6, "$interpolation starts at 0")
      assertEquals(1.0, at(interpolation, 1.0), 1e-6, "$interpolation ends at 1")
    }
    assertEquals(0.5, at(Interpolation.LINEAR, 0.5))
    assertEquals(0.25, at(Interpolation.EASE_IN, 0.5))
    assertEquals(0.75, at(Interpolation.EASE_OUT, 0.5))
    assertEquals(0.5, at(Interpolation.EASE_IN_OUT, 0.5), 1e-9)
    assertTrue(at(Interpolation.EASE_IN_OUT, 0.25) < 0.25)
    // Overshoot goes past the end before it settles.
    assertTrue((1..99).any { at(Interpolation.OVERSHOOT, it / 100.0) > 1.0 })
  }

  @Test
  fun `a cubic bezier follows its control points`() {
    // The default controls (all 0.5) are a straight line.
    val straight = WffAnimation(1.0, interpolation = Interpolation.CUBIC_BEZIER)
    for (p in listOf(0.1, 0.3, 0.5, 0.9)) assertEquals(p, straight.eased(p), 1e-6)
    // CSS's `ease-in` (0.42, 0, 1, 1) is slow to start.
    val easeIn =
      WffAnimation(
        1.0,
        interpolation = Interpolation.CUBIC_BEZIER,
        controls = listOf(0.42, 0.0, 1.0, 1.0),
      )
    assertEquals(0.315, easeIn.eased(0.5), 0.01)
  }

  @Test
  fun `an angle turns the way angleDirection says, ending where the transform puts it`() {
    fun turn(direction: AngleDirection, from: Double, to: Double) =
      WffAnimation(1.0, angleDirection = direction, fps = 0).let {
        it.valueAt(from, to, 0.5, angle = true) to it.valueAt(from, to, null, angle = true)
      }
    // From 350 to 10: straight back down through 180, the short way clockwise, or the long way
    // counter-clockwise.
    assertEquals(180.0 to 10.0, turn(AngleDirection.NONE, 350.0, 10.0))
    assertEquals(360.0 to 370.0, turn(AngleDirection.CLOCKWISE, 350.0, 10.0))
    assertEquals(180.0 to 10.0, turn(AngleDirection.COUNTER_CLOCKWISE, 350.0, 10.0))
    assertEquals(90.0 to 180.0, turn(AngleDirection.CLOCKWISE, 0.0, 180.0))
    assertEquals(-90.0 to -180.0, turn(AngleDirection.COUNTER_CLOCKWISE, 0.0, 180.0))
    // A whole turn back to the same angle still turns.
    assertEquals(180.0 to 360.0, turn(AngleDirection.CLOCKWISE, 0.0, 360.0))
    // Only an angle: an alpha ignores the direction.
    assertEquals(
      180.0,
      WffAnimation(1.0, angleDirection = AngleDirection.CLOCKWISE, fps = 0)
        .valueAt(350.0, 10.0, 0.5),
    )
  }

  @Test
  fun `attributes read as the reference gives them, defaults for what is missing`() {
    val read =
      WffAnimation.of(
        mapOf(
          "duration" to "2.5",
          "interpolation" to "CUBIC_BEZIER",
          "controls" to "0.1, 0.2,0.3 0.4",
          "angleDirection" to "COUNTER_CLOCKWISE",
          "repeat" to "-1",
          "fps" to "30",
        ),
      )
    assertEquals(
      WffAnimation(
        2.5,
        Interpolation.CUBIC_BEZIER,
        listOf(0.1, 0.2, 0.3, 0.4),
        AngleDirection.COUNTER_CLOCKWISE,
        repeat = -1,
        fps = 30,
      ),
      read,
    )
    assertEquals(WffAnimation(1.0), WffAnimation.of(mapOf("duration" to "1")))
    assertEquals(
      WffAnimation(1.0),
      WffAnimation.of(mapOf("duration" to "1", "interpolation" to "BOUNCE", "controls" to "1,2")),
    )
    // `duration` is required, and a play of no time is no animation.
    assertNull(WffAnimation.of(emptyMap()))
    assertNull(WffAnimation.of(mapOf("duration" to "0")))
    assertNull(WffAnimation.of(mapOf("duration" to "soon")))
  }

  @Test
  fun `a scrubbed time stays a time of day and loops its minute`() {
    assertEquals(WffScrubTime(10, 8, 1.5), WffScrubTime(10, 8, 59.5).advancedBy(2.0))
    assertEquals(WffScrubTime(23, 59, WffScrubTime.MAX_SECONDS), WffScrubTime.of(23, 59, 60.0))
    assertEquals(WffScrubTime(1, 2, 0.0), WffScrubTime.of(25, 62, -1.0))
    assertFailsWith<IllegalArgumentException> { WffScrubTime(24, 0, 0.0) }
    assertFailsWith<IllegalArgumentException> { WffScrubTime(0, 0, 60.0) }
  }
}
