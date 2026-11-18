package ee.schimke.composeai.uibuilder.canvas

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The clock, the date and the complication data a Watch Face Format design is drawn against when
 * there is no watch: the face's `previewTime`, the environment's fixed date and a sample for each
 * complication. Not what the watch would show; enough for a face to look lived in.
 */
internal class WffPreviewContext(
  val hour: Int = 10,
  val minute: Int = 8,
  val second: Int = 32,
  val year: Int = 2024,
  val month: Int = 5,
  val day: Int = 16,
  val complication: WffComplicationSample = WffComplicationSample.Default,
) {
  /** 0 = Sunday. */
  val dayOfWeek: Int
    get() {
      // Sakamoto's method.
      val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
      val y = if (month < 3) year - 1 else year
      return (y + y / 4 - y / 100 + y / 400 + t[month - 1] + day) % 7
    }

  fun withComplication(sample: WffComplicationSample) =
    WffPreviewContext(hour, minute, second, year, month, day, sample)

  companion object {
    /**
     * From a `previewTime` (`HH:mm:ss`) and a `fixedTime` (an ISO instant), either may be blank.
     */
    fun of(previewTime: String, fixedTime: String): WffPreviewContext {
      val base = WffPreviewContext()
      val clock = previewTime.split(':').mapNotNull { it.trim().toIntOrNull() }
      val date = fixedTime.substringBefore('T').split('-').mapNotNull { it.toIntOrNull() }
      return WffPreviewContext(
        hour = clock.getOrNull(0)?.coerceIn(0, 23) ?: base.hour,
        minute = clock.getOrNull(1)?.coerceIn(0, 59) ?: base.minute,
        second = clock.getOrNull(2)?.coerceIn(0, 59) ?: base.second,
        year = date.getOrNull(0) ?: base.year,
        month = date.getOrNull(1)?.coerceIn(1, 12) ?: base.month,
        day = date.getOrNull(2)?.coerceIn(1, 31) ?: base.day,
      )
    }
  }
}

/** What a complication slot shows: its `[COMPLICATION.TEXT]` and `[COMPLICATION.TITLE]`. */
internal class WffComplicationSample(val text: String, val title: String = "") {
  /** The one line a slot with nothing of its own draws. */
  val line: String
    get() = if (title.isEmpty()) text else "$text $title"

  companion object {
    val Default = WffComplicationSample("72", "bpm")

    /** A believable value for what a slot's default provider supplies. */
    fun forProvider(provider: String): WffComplicationSample =
      when (provider) {
        "WATCH_BATTERY" -> WffComplicationSample("85%", "")
        "STEP_COUNT" -> WffComplicationSample("6,482", "")
        "DAY_OF_WEEK" -> WffComplicationSample("Thu", "")
        "DAY_AND_DATE" -> WffComplicationSample("Thu 16", "")
        "TIME_AND_DATE" -> WffComplicationSample("10:08", "")
        "UNREAD_NOTIFICATION_COUNT" -> WffComplicationSample("3", "")
        "SUNRISE_SUNSET" -> WffComplicationSample("6:12", "")
        else -> Default
      }
  }
}

/** A value an expression produces: a number or a string. */
internal sealed interface WffValue {
  data class Num(val value: Double) : WffValue

  data class Str(val value: String) : WffValue

  /** As a `<Parameter>` prints it. */
  fun asText(): String =
    when (this) {
      is Str -> value
      is Num -> numberText(value)
    }

  fun asNumber(): Double? =
    when (this) {
      is Num -> value
      is Str -> value.toDoubleOrNull()
    }
}

private fun numberText(value: Double): String =
  if (value == floor(value) && abs(value) < 1e15) value.roundToLong().toString()
  else value.toString().trimEnd('0').trimEnd('.')

private val DAY_NAMES =
  listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
private val MONTH_NAMES =
  listOf(
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
  )

private fun two(value: Int) = value.toString().padStart(2, '0')

/**
 * The value of a bracketed source such as `[HOUR_0_11]`, or null for one this preview has none of.
 */
internal fun WffPreviewContext.source(name: String): WffValue? {
  fun n(v: Int) = WffValue.Num(v.toDouble())
  fun s(v: String) = WffValue.Str(v)
  val hour12 = if (hour % 12 == 0) 12 else hour % 12
  return when (name) {
    "HOUR_0_11",
    "HOUR_0_11_Z" -> n(hour % 12)
    "HOUR_1_12",
    "HOUR_1_12_Z" -> n(hour12)
    "HOUR_0_23",
    "HOUR_0_23_Z" -> n(hour)
    "HOUR_1_24",
    "HOUR_1_24_Z" -> n(if (hour == 0) 24 else hour)
    "HOUR_0_11_MINUTE" -> WffValue.Num((hour % 12) + minute / 60.0)
    "MINUTE" -> n(minute)
    "MINUTE_Z" -> s(two(minute))
    "MINUTE_SECOND" -> WffValue.Num(minute + second / 60.0)
    "SECOND" -> n(second)
    "SECOND_Z" -> s(two(second))
    "SECOND_MILLISECOND" -> n(second * 1000)
    "AMPM_STATE" -> n(if (hour < 12) 0 else 1)
    "AMPM_POSITION" -> n(if (hour < 12) 0 else 1)
    "DAY" -> n(day)
    "DAY_Z" -> s(two(day))
    "DAY_OF_WEEK" -> n(dayOfWeek + 1)
    "DAY_OF_WEEK_S" -> s(DAY_NAMES[dayOfWeek].take(3))
    "DAY_OF_WEEK_F" -> s(DAY_NAMES[dayOfWeek])
    "MONTH" -> n(month)
    "MONTH_Z" -> s(two(month))
    "MONTH_S" -> s(MONTH_NAMES[month - 1].take(3))
    "MONTH_F" -> s(MONTH_NAMES[month - 1])
    "YEAR" -> n(year)
    "YEAR_S" -> s(two(year % 100))
    "COMPLICATION.TEXT" -> s(complication.text)
    "COMPLICATION.TITLE" -> s(complication.title)
    "BATTERY_PERCENT" -> n(85)
    "STEP_COUNT" -> n(6482)
    "HEART_RATE" -> n(72)
    else -> null
  }
}

/**
 * Evaluates a Watch Face Format expression against [context]: numbers, `[SOURCE]`s, `+ - * / %`,
 * parentheses and a few functions. A source or construct it does not know gives null, and the
 * caller draws a sample instead.
 */
internal fun evaluateWffExpression(expression: String, context: WffPreviewContext): WffValue? =
  runCatching {
    ExpressionParser(expression, context).parse()
  }
  .getOrNull()

private class ExpressionParser(val text: String, val context: WffPreviewContext) {
  var at = 0

  fun parse(): WffValue? {
    val value = sum() ?: return null
    skip()
    return value.takeIf { at == text.length }
  }

  private fun skip() {
    while (at < text.length && text[at].isWhitespace()) at++
  }

  private fun peek(): Char? {
    skip()
    return text.getOrNull(at)
  }

  private fun sum(): WffValue? {
    var left = product() ?: return null
    while (true) {
      val op = peek()?.takeIf { it == '+' || it == '-' } ?: return left
      at++
      val right = product() ?: return null
      // `+` on a string is a concatenation, as in WFF.
      left =
        if (op == '+' && (left is WffValue.Str || right is WffValue.Str))
          WffValue.Str(left.asText() + right.asText())
        else
          WffValue.Num(
            (left.asNumber() ?: return null).let {
              if (op == '+') it + (right.asNumber() ?: return null)
              else it - (right.asNumber() ?: return null)
            },
          )
    }
  }

  private fun product(): WffValue? {
    var left = unary() ?: return null
    while (true) {
      val op = peek()?.takeIf { it == '*' || it == '/' || it == '%' } ?: return left
      at++
      val a = left.asNumber() ?: return null
      val b = unary()?.asNumber() ?: return null
      left =
        WffValue.Num(
          when (op) {
            '*' -> a * b
            '/' -> if (b == 0.0) return null else a / b
            else -> if (b == 0.0) return null else a - b * floor(a / b)
          },
        )
    }
  }

  private fun unary(): WffValue? {
    if (peek() == '-') {
      at++
      return WffValue.Num(-(unary()?.asNumber() ?: return null))
    }
    return atom()
  }

  private fun atom(): WffValue? {
    val c = peek() ?: return null
    when {
      c == '(' -> {
        at++
        val inner = sum() ?: return null
        if (peek() != ')') return null
        at++
        return inner
      }
      c == '[' -> {
        val end = text.indexOf(']', at)
        if (end < 0) return null
        val name = text.substring(at + 1, end).trim()
        at = end + 1
        return context.source(name)
      }
      c == '\'' || c == '"' -> {
        val end = text.indexOf(c, at + 1)
        if (end < 0) return null
        val value = text.substring(at + 1, end)
        at = end + 1
        return WffValue.Str(value)
      }
      c.isDigit() || c == '.' -> {
        val start = at
        while (at < text.length && (text[at].isDigit() || text[at] == '.')) at++
        return WffValue.Num(text.substring(start, at).toDoubleOrNull() ?: return null)
      }
      c.isLetter() -> {
        val start = at
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
        val name = text.substring(start, at)
        if (peek() != '(') return null
        at++
        val args = mutableListOf<Double>()
        if (peek() == ')') at++
        else {
          while (true) {
            args += sum()?.asNumber() ?: return null
            when (peek()) {
              ',' -> at++
              ')' -> {
                at++
                break
              }
              else -> return null
            }
          }
        }
        return call(name, args)
      }
    }
    return null
  }

  private fun call(name: String, args: List<Double>): WffValue? {
    val a = args.getOrNull(0) ?: return null
    return WffValue.Num(
      when (name) {
        "abs" -> abs(a)
        "floor" -> floor(a)
        "ceil" -> ceil(a)
        "round" -> a.roundToLong().toDouble()
        "sqrt" -> sqrt(a)
        "min" -> min(a, args.getOrNull(1) ?: return null)
        "max" -> max(a, args.getOrNull(1) ?: return null)
        else -> return null
      },
    )
  }
}

private val SPECIFIER = Regex("%(0?)(\\d*)(?:\\.(\\d+))?([sdf%])")

/**
 * A `<Template>` filled from its `<Parameter>` expressions, one per `%` specifier in order. A
 * parameter that cannot be evaluated here prints [fallback], so the face never shows a raw `%s`.
 */
internal fun fillWffTemplate(
  template: String,
  parameters: List<String>,
  context: WffPreviewContext,
  fallback: String = "--",
): String {
  var next = 0
  return SPECIFIER.replace(template) { match ->
    val (zero, width, precision, kind) = match.destructured
    if (kind == "%") return@replace "%"
    val value =
      parameters.getOrNull(next++)?.let { evaluateWffExpression(it, context) }
        ?: return@replace fallback
    var out =
      when (kind) {
        "d" -> value.asNumber()?.roundToLong()?.toString() ?: value.asText()
        "f" -> value.asNumber()?.let { fixed(it, precision.toIntOrNull() ?: 6) } ?: value.asText()
        else -> value.asText()
      }
    val pad = width.toIntOrNull() ?: 0
    if (out.length < pad) out = out.padStart(pad, if (zero.isNotEmpty()) '0' else ' ')
    out
  }
}

private fun fixed(value: Double, digits: Int): String {
  var scale = 1.0
  repeat(digits) { scale *= 10 }
  val rounded = (abs(value) * scale).roundToLong()
  val whole = rounded / scale.toLong()
  val fraction = (rounded % scale.toLong()).toString().padStart(digits, '0')
  val sign = if (value < 0 && rounded != 0L) "-" else ""
  return if (digits == 0) "$sign$whole" else "$sign$whole.$fraction"
}
