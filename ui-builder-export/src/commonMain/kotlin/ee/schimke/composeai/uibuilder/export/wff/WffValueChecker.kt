// Vendored from yschimke/watchface-tools wff-core 0.2.1
// (wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffValueChecker.kt).
// Only visibility (public -> internal) and the package differ. wff-core 0.2.1 is compiled for Java
// 21 bytecode;
// this repository's server floor is Java 17, so it cannot be consumed by coordinate yet.

package ee.schimke.composeai.uibuilder.export.wff

/** Something a value needs that only some format versions provide. */
internal class VersionNeed(
  val version: WffVersion,
  val code: WffDiagnosticCode,
  val message: String,
)

/** A value is [accepted] by a rule, or not; either way it may [needs] later versions. */
internal class ValueResult(
  val accepted: Boolean,
  val needs: List<VersionNeed> = emptyList(),
  /** Problems that are errors at every version (an unknown data source, say). */
  val problems: List<Pair<WffDiagnosticCode, String>> = emptyList(),
  /** Why a rejected value was rejected. */
  val reason: String? = null,
) {
  val requiredVersion: WffVersion
    get() = needs.maxOfOrNull { it.version } ?: WffVersion.V1
}

/** Evaluates a [WffValueRule] against a value. */
internal object WffValueChecker {
  /** Expression prefixes whose names are defined by the face, not by the format. */
  private val OPEN_SOURCE_PREFIXES = listOf("CONFIGURATION.", "COMPLICATION.", "REFERENCE.")

  private val sourceBrackets = Regex("""\[([A-Za-z][A-Za-z0-9_.]*)\]""")

  fun check(rule: WffValueRule, value: String): ValueResult =
    when (rule) {
      WffValueRule.Anything -> ValueResult(true)
      is WffValueRule.Enumeration -> {
        if (value in rule.values) {
          val since = rule.since[value]?.let(WffVersion::fromNumber)
          ValueResult(
            true,
            if (since == null) emptyList()
            else
              listOf(
                VersionNeed(
                  since,
                  WffDiagnosticCode.VALUE_TOO_NEW,
                  "value '$value' requires WFF version ${since.number}",
                ),
              ),
          )
        } else {
          ValueResult(false, reason = "expected ${describe(rule)}")
        }
      }
      is WffValueRule.Pattern ->
        if (rule.compiled.matches(value)) {
          // `[SOURCE]` forms are accepted by the pattern; the source itself is still checked.
          val sources =
            if (rule.regex.contains("\\[")) {
              sourceBrackets.findAll(value).map { it.groupValues[1] }.toList()
            } else emptyList()
          sourceResult(sources, emptyList())
        } else ValueResult(false, reason = "expected ${describe(rule)}")
      is WffValueRule.Number ->
        if (matchesNumber(rule, value)) ValueResult(true)
        else ValueResult(false, reason = "expected ${describe(rule)}")
      WffValueRule.Expression -> {
        val a = WffExpressions.parse(value)
        if (a.syntaxError != null)
          ValueResult(false, reason = "invalid expression: ${a.syntaxError}")
        else sourceResult(a.sources, a.functions)
      }
      is WffValueRule.ListOf -> {
        val items = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (items.isEmpty()) {
          ValueResult(false, reason = "expected a non-empty list: ${describe(rule)}")
        } else {
          val needs = mutableListOf<VersionNeed>()
          val problems = mutableListOf<Pair<WffDiagnosticCode, String>>()
          var failure: String? = null
          for (item in items) {
            val r = check(rule.item, item)
            if (!r.accepted) {
              failure = "'$item' is not valid: ${r.reason}"
              break
            }
            needs += r.needs
            problems += r.problems
          }
          if (failure != null) ValueResult(false, reason = failure)
          else ValueResult(true, needs, problems)
        }
      }
      is WffValueRule.Union -> {
        val results = rule.members.map { check(it, value) }
        val accepted = results.filter { it.accepted }
        if (accepted.isNotEmpty()) {
          // Prefer an alternative that needs no newer version and has no problems.
          accepted.minWith(
            compareBy<ValueResult>({ it.problems.size }, { it.requiredVersion.number }),
          )
        } else {
          val expression = results.firstOrNull {
            it.reason?.startsWith("invalid expression") == true
          }
          ValueResult(false, reason = expression?.reason ?: "expected ${describe(rule)}")
        }
      }
    }

  private fun sourceResult(sources: List<String>, functions: List<String>): ValueResult {
    val needs = mutableListOf<VersionNeed>()
    val problems = mutableListOf<Pair<WffDiagnosticCode, String>>()
    for (name in sources) {
      if (OPEN_SOURCE_PREFIXES.any { name.startsWith(it) && name.length > it.length }) continue
      val since = GENERATED_DATA_SOURCES[name.replace(Regex("""\.\d+"""), ".{index}")]
      if (since == null) {
        problems += WffDiagnosticCode.UNKNOWN_DATA_SOURCE to "unknown data source [$name]"
      } else if (since > 1) {
        WffVersion.fromNumber(since)?.let {
          needs +=
            VersionNeed(
              it,
              WffDiagnosticCode.DATA_SOURCE_TOO_NEW,
              "data source [$name] requires WFF version $since",
            )
        }
      }
    }
    for (f in functions) {
      val since = GENERATED_FUNCTIONS[f]
      val shown = f.substringBefore('/') + "(" + f.substringAfter('/') + " arguments)"
      if (since == null) {
        problems += WffDiagnosticCode.UNKNOWN_FUNCTION to "unknown function $shown"
      } else if (since > 1) {
        WffVersion.fromNumber(since)?.let {
          needs +=
            VersionNeed(
              it,
              WffDiagnosticCode.FUNCTION_TOO_NEW,
              "function $shown requires WFF version $since",
            )
        }
      }
    }
    return ValueResult(true, needs, problems)
  }

  private fun matchesNumber(rule: WffValueRule.Number, value: String): Boolean {
    // XSD numeric types have whiteSpace="collapse": surrounding XML whitespace is not part of the
    // value, and WffXmlParser keeps attribute text verbatim.
    val n = parseNumber(value.trim(' ', '\t', '\n', '\r'), rule.integer) ?: return false
    // NaN compares false against any bound, so it would slip past every one of them; the XSD
    // validator rejects it for a bounded type.
    if (n.isNaN() && (rule.min != null || rule.max != null)) return false
    rule.min?.let { if (if (rule.minExclusive) n <= it else n < it) return false }
    rule.max?.let { if (if (rule.maxExclusive) n >= it else n > it) return false }
    return true
  }

  /** XSD lexical forms: `[+-]?digits`, and for floats decimals, exponent, INF and NaN. */
  private fun parseNumber(value: String, integer: Boolean): Double? {
    if (value.isEmpty()) return null
    if (!integer && (value == "NaN" || value == "INF" || value == "-INF" || value == "+INF")) {
      return if (value == "NaN") Double.NaN
      else if (value.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    }
    var i = 0
    if (value[0] == '+' || value[0] == '-') i++
    val intStart = i
    while (i < value.length && value[i] in '0'..'9') i++
    var digits = i - intStart
    if (integer) return if (i == value.length && digits > 0) value.toDoubleOrNull() else null
    if (i < value.length && value[i] == '.') {
      i++
      val fracStart = i
      while (i < value.length && value[i] in '0'..'9') i++
      digits += i - fracStart
    }
    if (digits == 0) return null
    if (i < value.length && (value[i] == 'e' || value[i] == 'E')) {
      i++
      if (i < value.length && (value[i] == '+' || value[i] == '-')) i++
      val expStart = i
      while (i < value.length && value[i] in '0'..'9') i++
      if (i == expStart) return null
    }
    return if (i == value.length) value.toDoubleOrNull() else null
  }

  fun describe(rule: WffValueRule): String =
    when (rule) {
      is WffValueRule.Enumeration -> {
        val shown = rule.values.take(12).joinToString(", ")
        "one of $shown" + if (rule.values.size > 12) ", ..." else ""
      }
      is WffValueRule.Pattern -> "a value matching ${rule.regex}"
      is WffValueRule.Number -> {
        val kind = if (rule.integer) "an integer" else "a number"
        val bounds = buildList {
          rule.min?.let { add((if (rule.minExclusive) "> " else ">= ") + trim(it)) }
          rule.max?.let { add((if (rule.maxExclusive) "< " else "<= ") + trim(it)) }
        }
        if (bounds.isEmpty()) kind else "$kind ${bounds.joinToString(" and ")}"
      }
      WffValueRule.Expression -> "an expression"
      is WffValueRule.ListOf -> "a space-separated list of ${describe(rule.item)}"
      is WffValueRule.Union -> rule.members.joinToString(" or ") { describe(it) }
      WffValueRule.Anything -> "any text"
    }

  private fun trim(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
