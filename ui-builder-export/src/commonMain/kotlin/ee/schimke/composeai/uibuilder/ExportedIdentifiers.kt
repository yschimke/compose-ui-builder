package ee.schimke.composeai.uibuilder

/**
 * What the Compose generator will call a state variable, and what it may not call one.
 *
 * These live beside the export projection rather than inside the browser's emitter because two
 * callers need them: the emitter, which writes `var $$name` bare, and design creation, which
 * refuses a name the emitter could never write. Creation moved to the server when creating a design
 * became a request rather than a page load, and a rule enforced on only one of the two sides is a
 * design that can be created and never exported.
 */
fun exportedStateIdentifier(name: String): String = name.identifier()

/**
 * Kotlin's hard keywords: reserved wherever an identifier is expected, and not escapable by the
 * generator, which writes `var $$name` bare.
 */
val KOTLIN_HARD_KEYWORDS: Set<String> =
  setOf(
    "as",
    "break",
    "class",
    "continue",
    "do",
    "else",
    "false",
    "for",
    "fun",
    "if",
    "in",
    "interface",
    "is",
    "null",
    "object",
    "package",
    "return",
    "super",
    "this",
    "throw",
    "true",
    "try",
    "typealias",
    "typeof",
    "val",
    "var",
    "when",
    "while",
  )

private fun String.identifier(): String {
  val words = split(Regex("[^A-Za-z0-9]+")).filter(String::isNotEmpty)
  val joined =
    words
      .mapIndexed { index, word ->
        if (index == 0) word.replaceFirstChar { it.lowercase() }
        else word.replaceFirstChar { it.uppercase() }
      }
      .joinToString("")
  val candidate = joined.ifEmpty { "generatedValue" }
  return if (candidate.first().isDigit()) "generated$candidate" else candidate
}
