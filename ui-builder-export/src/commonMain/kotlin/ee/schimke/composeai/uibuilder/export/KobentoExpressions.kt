package ee.schimke.composeai.uibuilder.export

import kotlin.math.abs
import kotlin.math.floor
import kotlinx.serialization.json.JsonPrimitive

/**
 * Kobento's static arithmetic, for [KobentoComponentExpander]: an expression whose every operand is
 * a literal folds to a number when a call is expanded (`"@theme.space_2 * 2"` is written `16`), and
 * a number is written as Kobento (JavaScript) writes it.
 */
internal object Expressions {
  /** [text] as a number, when it is arithmetic on literals only; null otherwise. */
  fun fold(text: String): JsonPrimitive? {
    val tokens = tokens(text) ?: return null
    if (tokens.isEmpty()) return null
    val parser = Parser(tokens)
    val value = runCatching { parser.expression() }.getOrNull() ?: return null
    if (!parser.done() || value.isNaN() || value.isInfinite()) return null
    // Kobento writes a folded value to six places: 16 * 1.4 is 22.4, 575 / 12 is 47.916667.
    // JavaScript's Math.round: halves round up, not to even.
    return number(floor(value * 1e6 + 0.5) / 1e6)
  }

  /** [value] as JSON: a whole number without a fraction. */
  fun number(value: Double): JsonPrimitive =
    if (value == floor(value) && abs(value) < 1e15) JsonPrimitive(value.toLong())
    else JsonPrimitive(value)

  /** [value] as text, as JavaScript's `String(number)` writes it for the usual range. */
  fun format(value: Double): String =
    if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()

  private fun tokens(text: String): List<String>? {
    val out = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        c.isWhitespace() -> i++
        c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
          val start = i
          while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
          out += text.substring(start, i)
        }
        // Kobento folds `+ - * /`, min, max, step, abs and clamp; `%`, floor, round and the rest
        // stay as written.
        c in "+-*/()," -> {
          out += c.toString()
          i++
        }
        c.isLetter() -> {
          val start = i
          while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
          val name = text.substring(start, i)
          // Only pure functions fold; anything read at run time (componentWidth(), a clock) does
          // not, and neither does a variable.
          if (name !in FUNCTIONS) return null
          out += name
        }
        else -> return null
      }
    }
    return out
  }

  private val FUNCTIONS = setOf("min", "max", "step", "abs", "clamp")

  private class Parser(val tokens: List<String>) {
    var at = 0

    fun done() = at == tokens.size

    fun peek() = tokens.getOrNull(at)

    fun take() = tokens[at++]

    fun expression(): Double {
      var value = term()
      while (peek() == "+" || peek() == "-") {
        value = if (take() == "+") value + term() else value - term()
      }
      return value
    }

    fun term(): Double {
      var value = unary()
      while (peek() == "*" || peek() == "/" || peek() == "%") {
        value =
          when (take()) {
            "*" -> value * unary()
            "/" -> value / unary()
            else -> value % unary()
          }
      }
      return value
    }

    fun unary(): Double =
      if (peek() == "-") {
        take()
        -unary()
      } else primary()

    fun primary(): Double {
      val token = take()
      if (token == "(") {
        val value = expression()
        check(take() == ")")
        return value
      }
      if (token in FUNCTIONS) {
        check(take() == "(")
        val args = mutableListOf(expression())
        while (peek() == ",") {
          take()
          args += expression()
        }
        check(take() == ")")
        return when (token) {
          "min" -> args.min()
          "max" -> args.max()
          // Remote Compose's step(x, edge): 1 past the edge, 0 at it and below (step(0, 0) is 0).
          "step" -> if (args[0] > args[1]) 1.0 else 0.0
          "abs" -> abs(args.single())
          "clamp" -> args[2].coerceIn(args[0], args[1])
          "floor" -> floor(args.single())
          "ceil" -> kotlin.math.ceil(args.single())
          "round" -> kotlin.math.round(args.single())
          else -> kotlin.math.sqrt(args.single())
        }
      }
      return token.toDouble()
    }
  }
}
