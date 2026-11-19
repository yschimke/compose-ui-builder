package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.WffPreviewContext
import ee.schimke.composeai.uibuilder.canvas.WffValue
import ee.schimke.composeai.uibuilder.canvas.evaluateWffExpression
import ee.schimke.composeai.uibuilder.canvas.wffReadsSubSecond
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `[MILLISECOND]` and `[UTC_TIMESTAMP]` evaluate against the preview clock, and a face that reads
 * them is redrawn between seconds: yschimke/watchface-tools#39, item (a).
 */
class WffTimeSourceTest {
  private val at =
    WffPreviewContext(
      hour = 10,
      minute = 8,
      second = 32,
      fraction = 0.25,
      year = 2024,
      month = 5,
      day = 16,
    )

  private fun number(expression: String, context: WffPreviewContext = at) =
    (evaluateWffExpression(expression, context) as WffValue.Num).value

  @Test
  fun `MILLISECOND is the millisecond of the second`() {
    assertEquals(250.0, number("[MILLISECOND]"))
    assertEquals(0.0, number("[MILLISECOND]", WffPreviewContext()))
  }

  @Test
  fun `UTC_TIMESTAMP is milliseconds since the epoch`() {
    assertEquals(
      Instant.parse("2024-05-16T10:08:32.250Z").toEpochMilli().toDouble(),
      number("[UTC_TIMESTAMP]"),
    )
    // Across a leap day and a year's start.
    for (date in listOf("2024-02-29", "2000-03-01", "1970-01-01", "2023-12-31")) {
      val (y, m, d) = date.split('-').map { it.toInt() }
      val context =
        WffPreviewContext(hour = 0, minute = 0, second = 0, year = y, month = m, day = d)
      assertEquals(
        Instant.parse("${date}T00:00:00Z").toEpochMilli().toDouble(),
        number("[UTC_TIMESTAMP]", context),
        date,
      )
    }
  }

  @Test
  fun `a face reading a sub-second source is redrawn between seconds`() {
    fun face(alpha: String) =
      wffFace(
        listOf("dot"),
        wffNode(
          "dot",
          "wff/part-draw",
          mapOf("width" to 10, "height" to 10, "alpha" to alpha),
        ),
      )
    assertTrue(face("[MILLISECOND] / 4").wffReadsSubSecond())
    assertTrue(face("[UTC_TIMESTAMP] % 255").wffReadsSubSecond())
    assertTrue(face("[ SECOND_MILLISECOND ] * 6").wffReadsSubSecond())
    assertTrue(face("[SECOND_MILLISECOND]*6").wffReadsSubSecond())
    assertFalse(face("[SECOND] * 4").wffReadsSubSecond())
  }
}
