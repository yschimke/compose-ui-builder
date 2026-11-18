// Vendored from yschimke/watchface-tools wff-core 0.2.1
// (wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffExpression.kt).
// Only visibility (public -> internal) and the package differ. wff-core 0.2.1 is compiled for Java
// 21 bytecode;
// this repository's server floor is Java 17, so it cannot be consumed by coordinate yet.

package ee.schimke.composeai.uibuilder.export.wff

/**
 * What [WffExpressions.parse] found in an arithmetic expression: the first syntax error, if any,
 * and the data sources (`[HOUR_0_23]`) and functions (`clamp(...)`) it uses.
 */
internal class WffExpressionAnalysis(
  /** Null when the expression is well-formed. */
  internal val syntaxError: String?,
  /** Data source names without the brackets, in order of appearance. */
  internal val sources: List<String>,
  /** Called functions as `name/arity`, in order of appearance. */
  internal val functions: List<String>,
)

/**
 * Syntax check for the arithmetic expressions WFF uses in attributes and `<Expression>` text. The
 * grammar follows Google's `dwf-validator` (github.com/google/watchface, `ExpressionParser`):
 * ternary, `||`, `&&`, `|`, `&`, equality, comparison, `+ -`, `* / %`, one unary prefix, then a
 * number, colour, string, boolean, `[data source]`, function call or parenthesised expression. It
 * only reports the first error; data source and function names are checked by the caller against
 * the generated tables.
 */
internal object WffExpressions {
  internal fun parse(expression: String): WffExpressionAnalysis {
    val p = Parser(expression)
    val error =
      try {
        p.parseAll()
        null
      } catch (e: ExpressionSyntaxException) {
        e.message
      }
    return WffExpressionAnalysis(error, p.sources, p.functions)
  }

  private class ExpressionSyntaxException(message: String) : Exception(message)

  private enum class Kind {
    NUMBER,
    COLOR,
    BOOLEAN,
    STRING,
    WORD,
    OPERATOR,
    PAREN,
    UNKNOWN,
    EOF,
  }

  private class Token(val kind: Kind, val text: String)

  private val twoCharOperators = setOf("<=", ">=", "==", "!=", "&&", "||")
  private const val OPERATOR_CHARS = "+-*/%~!&|<>?:,"

  private fun tokenize(s: String): List<Token> {
    val out = mutableListOf<Token>()
    var i = 0

    fun isDigit(c: Char) = c in '0'..'9'

    fun isHex(c: Char) = isDigit(c) || c in 'a'..'f' || c in 'A'..'F'

    fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    while (i < s.length) {
      val c = s[i]
      when {
        c.isWhitespace() -> i++
        isDigit(c) || (c == '.' && i + 1 < s.length && isDigit(s[i + 1])) -> {
          var j = i
          while (j < s.length && isDigit(s[j])) j++
          if (j + 1 < s.length && s[j] == '.' && isDigit(s[j + 1])) {
            j++
            while (j < s.length && isDigit(s[j])) j++
          }
          out += Token(Kind.NUMBER, s.substring(i, j))
          i = j
        }
        c == '#' -> {
          var j = i + 1
          while (j < s.length && isHex(s[j]) && j - i <= 8) j++
          val digits = j - i - 1
          when {
            digits >= 8 -> {
              out += Token(Kind.COLOR, s.substring(i, i + 9))
              i += 9
            }
            digits >= 6 -> {
              out += Token(Kind.COLOR, s.substring(i, i + 7))
              i += 7
            }
            else -> {
              out += Token(Kind.UNKNOWN, "#")
              i++
            }
          }
        }
        c == '"' -> {
          val end = s.indexOf('"', i + 1)
          if (end < 0) {
            out += Token(Kind.UNKNOWN, "\"")
            i++
          } else {
            out += Token(Kind.STRING, s.substring(i, end + 1))
            i = end + 1
          }
        }
        isLetter(c) -> {
          var j = i + 1
          while (
            j < s.length && (isLetter(s[j]) || isDigit(s[j]) || s[j] == '_' || s[j] == '.')
          ) j++
          val word = s.substring(i, j)
          out +=
            Token(
              if (word in BOOLEANS) Kind.BOOLEAN else Kind.WORD,
              word,
            )
          i = j
        }
        i + 1 < s.length && s.substring(i, i + 2) in twoCharOperators -> {
          out += Token(Kind.OPERATOR, s.substring(i, i + 2))
          i += 2
        }
        c in OPERATOR_CHARS -> {
          out += Token(Kind.OPERATOR, c.toString())
          i++
        }
        c == '(' || c == ')' || c == '[' || c == ']' -> {
          out += Token(Kind.PAREN, c.toString())
          i++
        }
        else -> {
          out += Token(Kind.UNKNOWN, c.toString())
          i++
        }
      }
    }
    out += Token(Kind.EOF, "")
    return out
  }

  private val BOOLEANS = setOf("true", "false", "True", "False", "TRUE", "FALSE")

  private class Parser(source: String) {
    private val tokens = tokenize(source)
    private var pos = 0
    val sources = mutableListOf<String>()
    val functions = mutableListOf<String>()

    private val current: Token
      get() = tokens[pos]

    fun parseAll() {
      tokens
        .firstOrNull { it.kind == Kind.UNKNOWN }
        ?.let { throw ExpressionSyntaxException("unrecognised character '${it.text}'") }
      expression()
      if (current.kind != Kind.EOF) {
        throw ExpressionSyntaxException("unexpected '${current.text}' after the expression")
      }
    }

    private fun match(vararg values: String): Boolean {
      if (current.kind != Kind.OPERATOR && current.kind != Kind.PAREN) return false
      if (current.text in values) {
        pos++
        return true
      }
      return false
    }

    private fun expect(value: String) {
      if (!match(value)) {
        val got = if (current.kind == Kind.EOF) "end of expression" else "'${current.text}'"
        throw ExpressionSyntaxException("expected '$value' but found $got")
      }
    }

    private fun expression() {
      orExpression()
      if (match("?")) {
        expression()
        expect(":")
        expression()
      }
    }

    private fun orExpression() {
      andExpression()
      while (match("||")) andExpression()
    }

    private fun andExpression() {
      bitwiseOr()
      while (match("&&")) bitwiseOr()
    }

    private fun bitwiseOr() {
      bitwiseAnd()
      while (match("|")) bitwiseAnd()
    }

    private fun bitwiseAnd() {
      equality()
      while (match("&")) equality()
    }

    private fun equality() {
      comparison()
      if (match("==", "!=")) comparison()
    }

    private fun comparison() {
      addSub()
      if (match("<", ">", "<=", ">=")) addSub()
    }

    private fun addSub() {
      mulDiv()
      while (match("+", "-")) mulDiv()
    }

    private fun mulDiv() {
      unary()
      while (match("*", "/", "%")) unary()
    }

    private fun unary() {
      match("+", "-", "!", "~")
      atom()
    }

    private fun atom() {
      val t = current
      when (t.kind) {
        Kind.BOOLEAN,
        Kind.STRING -> pos++
        Kind.COLOR -> {
          pos++
          while (current.kind == Kind.COLOR) pos++
        }
        Kind.NUMBER -> {
          pos++
          while (current.kind == Kind.NUMBER) pos++
        }
        Kind.WORD -> {
          pos++
          if (match("(")) {
            var arity = 0
            if (!(current.kind == Kind.PAREN && current.text == ")")) {
              do {
                expression()
                arity++
              } while (match(","))
            }
            expect(")")
            functions += "${t.text}/$arity"
          }
        }
        Kind.PAREN ->
          when (t.text) {
            "(" -> {
              pos++
              expression()
              expect(")")
            }
            "[" -> {
              pos++
              val name = current
              if (name.kind == Kind.EOF) {
                throw ExpressionSyntaxException("unterminated '[': expected a data source name")
              }
              pos++
              expect("]")
              sources += name.text
            }
            else -> throw ExpressionSyntaxException("unexpected '${t.text}'")
          }
        Kind.EOF ->
          throw ExpressionSyntaxException("the expression ends where an operand was expected")
        else ->
          throw ExpressionSyntaxException("unexpected '${t.text}' where an operand was expected")
      }
    }
  }
}
