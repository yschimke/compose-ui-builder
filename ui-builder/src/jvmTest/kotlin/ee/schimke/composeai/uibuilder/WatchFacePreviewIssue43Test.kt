package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.WffPreviewContext
import ee.schimke.composeai.uibuilder.canvas.previewTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Preview fixes from yschimke/watchface-tools#43: time formats, and groups placed off the face. */
class WatchFacePreviewIssue43Test {
  private val morning = WffPreviewContext(hour = 9, minute = 8, second = 5)
  private val evening = WffPreviewContext(hour = 22, minute = 8, second = 5)
  private val midnight = WffPreviewContext(hour = 0, minute = 30, second = 0)

  @Test
  fun `HH is the hour, as hh is`() {
    // Item 1: `HH:mm` drew as a literal "HH:08".
    assertEquals("22:08", previewTime("HH:mm", evening, twelveHour = false))
    assertEquals("09:08", previewTime("HH:mm", morning, twelveHour = false))
    assertEquals("22:08", previewTime("hh:mm", evening, twelveHour = false))
  }

  @Test
  fun `single letters are unpadded, doubled ones pad to two digits`() {
    assertEquals("9:08:05", previewTime("H:mm:ss", morning, twelveHour = false))
    assertEquals("9:8:5", previewTime("h:m:s", morning, twelveHour = false))
    assertEquals("08", previewTime("mm", morning, twelveHour = false))
  }

  @Test
  fun `a 12-hour face draws 1 to 12 and a for AM or PM`() {
    assertEquals("10:08 PM", previewTime("hh:mm a", evening, twelveHour = true))
    assertEquals("10:08", previewTime("HH:mm", evening, twelveHour = true))
    assertEquals("12:30 AM", previewTime("h:mm a", midnight, twelveHour = true))
    assertEquals("9:08 AM", previewTime("h:mm a", morning, twelveHour = false).replace("09", "9"))
  }

  @Test
  fun `other characters are drawn as written`() {
    assertEquals("09.08 · 05", previewTime("HH.mm · ss", morning, twelveHour = false))
  }

  /** A tick at twelve o'clock, turned 45° by a group laid out at [x],[y], [side] square. */
  private fun turnedTick(x: Int, side: Int) =
    wffFace(
      listOf("group"),
      listOf(
          wffNode(
            "group",
            "wff/group",
            mapOf(
              "x" to x,
              "y" to x,
              "width" to side,
              "height" to side,
              "angle" to 45,
              "pivotX" to 0.5,
              "pivotY" to 0.5,
            ),
            mapOf("content" to listOf("tick")),
          ),
          // The same place on the face in both: 215,10 in face pixels.
          wffNode(
            "tick",
            "wff/part-draw",
            mapOf(
              "x" to 215 - x,
              "y" to 10 - x,
              "width" to 20,
              "height" to 60,
              "shape" to "rectangle",
              "fillColor" to "#ffffffff",
            ),
          ),
        )
        .joinToString(","),
    )

  @Test
  fun `a group past the face's edge turns about its own centre`() {
    // Item 2: a 500px group at -25,-25 is centred on the face as a 450px one at 0,0 is, so the
    // same content turned about either centre lands in the same place.
    val inside = renderWffFace(turnedTick(0, 450), "issue43-group-inside")
    val outside = renderWffFace(turnedTick(-25, 500), "issue43-group-outside")
    var differing = 0
    for (y in 0 until inside.height) for (x in 0 until inside.width) {
      if (inside.getColor(x, y) != outside.getColor(x, y)) differing++
    }
    // Antialiased edges may differ by rounding; a misplaced tick differs by hundreds of pixels.
    assertTrue(differing < 40, "$differing pixels differ")
    // And the tick is where a 45° turn about the face's centre puts it: up and to the right.
    assertEquals(listOf(255, 255, 255, 255), outside.argb(355, 100))
  }
}
