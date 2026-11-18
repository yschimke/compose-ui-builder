package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.WffComplicationSample
import ee.schimke.composeai.uibuilder.canvas.WffPreviewContext
import ee.schimke.composeai.uibuilder.canvas.WffValue
import ee.schimke.composeai.uibuilder.canvas.evaluateWffExpression
import ee.schimke.composeai.uibuilder.canvas.fillWffTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a face's `<Template>` and `<Transform>` expressions say at the preview time. */
class WffPreviewExpressionsTest {
  // 10:08:32 on Thursday 16 May 2024, the templates' own preview.
  private val context = WffPreviewContext.of("10:08:32", "2024-05-16T10:08:32Z")

  private fun number(expression: String) =
    (evaluateWffExpression(expression, context) as? WffValue.Num)?.value

  @Test
  fun `the hands' angles come out of the clock`() {
    assertEquals(10 * 30 + 8 * 0.5, number("[HOUR_0_11] * 30 + [MINUTE] * 0.5"))
    assertEquals(8 * 6 + 32 * 0.1, number("[MINUTE] * 6 + [SECOND] * 0.1")!!, 1e-9)
    assertEquals(192.0, number("[SECOND_MILLISECOND] * 6"))
  }

  @Test
  fun `SECOND_MILLISECOND is seconds with their fraction, so the second hand is at 195 degrees at 32 point 5`() {
    val half = WffPreviewContext.of("10:08:32.5", "2024-05-16T10:08:32Z")
    assertEquals(32.5, (evaluateWffExpression("[SECOND_MILLISECOND]", half) as WffValue.Num).value)
    assertEquals(
      195.0,
      (evaluateWffExpression("[SECOND_MILLISECOND] * 6", half) as WffValue.Num).value,
    )
    assertEquals(
      "05",
      evaluateWffExpression("[HOUR_0_11_Z]", WffPreviewContext.of("05:00:00", ""))?.asText(),
    )
  }

  @Test
  fun `arithmetic follows precedence, parentheses and unary minus`() {
    assertEquals(14.0, number("2 + 3 * 4"))
    assertEquals(20.0, number("(2 + 3) * 4"))
    assertEquals(-6.0, number("-[HOUR_0_11] + 4"))
    assertEquals(2.0, number("[MINUTE] % 3"))
    assertEquals(4.0, number("max(floor(3.7), 4)"))
  }

  @Test
  fun `a source or construct the preview has no value for is null, not a guess`() {
    assertNull(evaluateWffExpression("[NOT_A_SOURCE]", context))
    assertNull(evaluateWffExpression("[HOUR_0_11] ? 1 : 2", context))
    assertNull(evaluateWffExpression("1 / 0", context))
    assertNull(evaluateWffExpression("(1 + ", context))
  }

  @Test
  fun `the date comes from the environment's fixed time`() {
    assertEquals("Thu", evaluateWffExpression("[DAY_OF_WEEK_S]", context)?.asText())
    assertEquals("Thursday", evaluateWffExpression("[DAY_OF_WEEK_F]", context)?.asText())
    assertEquals("May", evaluateWffExpression("[MONTH_S]", context)?.asText())
    assertEquals("16", evaluateWffExpression("[DAY]", context)?.asText())
  }

  @Test
  fun `a template prints its parameters instead of the raw percent sign`() {
    assertEquals("Thu 16", fillWffTemplate("%s %d", listOf("[DAY_OF_WEEK_S]", "[DAY]"), context))
    assertEquals("10:08", fillWffTemplate("%02d:%02d", listOf("[HOUR_0_23]", "[MINUTE]"), context))
    assertEquals("85%", fillWffTemplate("%s", listOf("[COMPLICATION.TEXT]"), complication("85%")))
    assertEquals("100%", fillWffTemplate("100%%", emptyList(), context))
    assertEquals("12.50", fillWffTemplate("%.2f", listOf("25 / 2"), context))
  }

  @Test
  fun `a parameter that cannot be computed prints a placeholder, never a percent sign`() {
    assertEquals("--", fillWffTemplate("%s", listOf("[WEATHER.TEMPERATURE]"), context))
    assertEquals("--", fillWffTemplate("%s", emptyList(), context))
    assertEquals("Thu --", fillWffTemplate("%s %d", listOf("[DAY_OF_WEEK_S]", "[X]"), context))
  }

  @Test
  fun `a complication slot's sample follows its default provider`() {
    assertEquals("85%", WffComplicationSample.forProvider("WATCH_BATTERY").line)
    assertEquals("6,482", WffComplicationSample.forProvider("STEP_COUNT").line)
    assertEquals("72 bpm", WffComplicationSample.forProvider("").line)
  }

  private fun complication(text: String) = context.withComplication(WffComplicationSample(text))
}
