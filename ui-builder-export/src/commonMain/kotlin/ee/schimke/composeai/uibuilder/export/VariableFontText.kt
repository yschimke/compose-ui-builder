package ee.schimke.composeai.uibuilder.export

/**
 * Text drawn from a variable font's own outlines, with its axes free to move: the flexpress
 * `VariableFontText` (Compose UI) and `RemoteVariableFontText` (Remote Compose).
 *
 * A design names the font, the text and a value per axis; an axis bound to state or computed is
 * *animated*, every other one is held at its value. Export writes the text as Kotlin through
 * flexpress's code generator, which works the outline out ahead of time, so the app carries an
 * encoded string rather than the font. That generator runs on the JVM alone, so this file is the
 * shared half — what a request is, what it is called, and how its source joins the screen's — and
 * [VariableFontSourceGenerator] is the seam the JVM side (`FlexpressVariableFontSources`) fills.
 */
object VariableFontText {
  /** The Material 3 catalog's component, drawn with Compose UI. */
  const val M3_ID: String = "m3/variable-font-text"

  /** The Wear Material 3 catalog's component, drawn with Compose UI. */
  const val WEAR_ID: String = "wear-m3/variable-font-text"

  /** The Remote Material 3 catalog's component, drawn with Remote Compose. */
  const val REMOTE_ID: String = "remote-m3/remote-variable-font-text"

  /**
   * An axis of a [Font]: its OpenType [tag] and range. A design sets it through the property named
   * by [property], the tag in lower case, which is also the parameter name flexpress gives it.
   */
  data class Axis(val tag: String, val min: Float, val default: Float, val max: Float) {
    val property: String
      get() = tag.lowercase()
  }

  /**
   * The fonts a design can draw variable text in: the vendored variable files the canvas draws its
   * stand-in with, by [file] in `assets/rc-fonts`. [axes] are those a design may set; the files
   * have more (Roboto Flex's parametric axes, Google Sans Flex's `opsz` and `GRAD`), held at their
   * defaults.
   */
  enum class Font(val wire: String, val family: String, val file: String, val axes: List<Axis>) {
    RobotoFlex(
      wire = "robotoFlex",
      family = "Roboto Flex",
      file = "RobotoFlex.ttf",
      axes =
        listOf(
          Axis("wght", 100f, 400f, 1000f),
          Axis("wdth", 25f, 100f, 151f),
          Axis("slnt", -10f, 0f, 0f),
        ),
    ),
    GoogleSansFlex(
      wire = "googleSansFlex",
      family = "Google Sans Flex",
      file = "google-sans-flex-variable.ttf",
      axes =
        listOf(
          Axis("wght", 1f, 400f, 1000f),
          Axis("wdth", 25f, 100f, 151f),
          Axis("slnt", -10f, 0f, 0f),
          Axis("ROND", 0f, 0f, 100f),
        ),
    );

    /** The axis a design sets through [property], or null when this font has none by that name. */
    fun axis(property: String): Axis? = axes.firstOrNull { it.property == property }

    companion object {
      /** The font a design's `font` property names, or null for a name no font has. */
      fun fromWire(wire: String): Font? = entries.firstOrNull { it.wire == wire }
    }
  }

  /**
   * The axis properties any [Font] has, in the order a catalog lists them. Spelled out rather than
   * derived from [Font.axes], because they are catalog property names that the Wear lane's
   * readership check looks for by name; `VariableFontTextTest` holds the two equal.
   */
  val AXIS_PROPERTIES: List<String> = listOf("wght", "wdth", "slnt", "rond")

  /** What the generated composable draws with. */
  enum class Target {
    /** A Compose UI `@Composable`, each animated axis a `() -> Float`. */
    COMPOSE_UI,

    /** A Remote Compose `@RemoteComposable`, each animated axis a `RemoteFloat`. */
    REMOTE,
  }

  /**
   * One text to generate: [text] in [font], the axes in [animated] (OpenType tags) as parameters of
   * the composable, and [location] (OpenType tag to value) for every other axis a design set.
   */
  data class Spec(
    val font: Font,
    val text: String,
    val animated: List<String>,
    val location: Map<String, Float>,
    val target: Target,
  )

  /** A [Spec] with the name its composable is generated under. */
  data class Request(val functionName: String, val spec: Spec)

  /**
   * Why [spec] cannot be generated, or empty. Codegen fixes the text when it runs, so it must be
   * literal and non-empty; an axis must be one [Spec.font] lets a design set, and appear once.
   * Whether the font has a glyph for every character is the generator's to say: it has the font.
   */
  fun problems(spec: Spec): List<String> = buildList {
    if (spec.text.isEmpty()) add("variable font text needs some text")
    val tags = spec.animated + spec.location.keys
    tags
      .filter { tag -> spec.font.axes.none { it.tag == tag } }
      .forEach { add("${spec.font.family} has no ${it.lowercase()} axis a design can set") }
    tags
      .groupBy { it }
      .filterValues { it.size > 1 }
      .keys
      .forEach { add("the ${it.lowercase()} axis is both animated and held") }
  }

  /**
   * Names for [specs], in order: identical specs share one request, and different specs whose names
   * collide take `_2`, `_3`, … in the order they first appear. The name reads the font, the text
   * and the animated axes, so a screen's generated declarations say what they draw.
   */
  fun requests(specs: List<Spec>): List<Request> {
    val named = linkedMapOf<Spec, Request>()
    val taken = mutableMapOf<String, Int>()
    for (spec in specs) {
      if (spec in named) continue
      val base = baseName(spec)
      val count = (taken[base] ?: 0) + 1
      taken[base] = count
      named[spec] = Request(if (count == 1) base else "${base}_$count", spec)
    }
    return named.values.toList()
  }

  /** The request [spec] was named under in [requests]. */
  fun List<Request>.of(spec: Spec): Request = first { it.spec == spec }

  private fun baseName(spec: Spec): String = buildString {
    append("VariableFontText")
    append(pascal(spec.font.wire))
    append(textName(spec.text))
    spec.animated.forEach { append(pascal(it.lowercase())) }
  }

  /** [text]'s words in Pascal case, as many whole ones as fit in [TEXT_NAME_LENGTH]. */
  private fun textName(text: String): String {
    val words = words(text)
    var length = 0
    val kept = words.takeWhile { word ->
      length += word.length
      length <= TEXT_NAME_LENGTH
    }
    return kept.joinToString("").ifEmpty { words.firstOrNull()?.take(TEXT_NAME_LENGTH) ?: "Text" }
  }

  private fun pascal(text: String): String = words(text).joinToString("")

  private fun words(text: String): List<String> =
    text
      .split(Regex("[^A-Za-z0-9]+"))
      .filter { it.isNotEmpty() }
      .map { word -> word.replaceFirstChar { it.uppercaseChar() } }

  private const val TEXT_NAME_LENGTH = 24
}

/** Whether exported variable font text draws through flexpress or carries its own drawing. */
enum class VariableFontExportMode {
  /**
   * The generated code calls `flexpress-compose` / `flexpress-remote`, which the app depends on.
   */
  LIBRARY,

  /** The generated code decodes and draws the outline itself: Compose alone, no flexpress. */
  STANDALONE,
}

/** What a [VariableFontSourceGenerator] gives back for one request. */
sealed interface VariableFontSource {
  /**
   * The generated Kotlin file for the request: its own package and imports, as codegen wrote it.
   */
  data class Generated(val fileName: String, val source: String) : VariableFontSource

  /** Why the request cannot be generated: a character the font has no glyph for, say. */
  data class Refused(val reason: String) : VariableFontSource
}

/**
 * Writes a [VariableFontText.Request] as Kotlin, or null where no generator is available — the wasm
 * editor, which shows the call and says the declaration is written at export.
 */
fun interface VariableFontSourceGenerator {
  fun generate(
    request: VariableFontText.Request,
    packageName: String,
    mode: VariableFontExportMode,
  ): VariableFontSource?

  companion object {
    /** No generator: every request comes back null. */
    val Unavailable: VariableFontSourceGenerator = VariableFontSourceGenerator { _, _, _ -> null }
  }
}

/**
 * Joins Kotlin files that share a package into one: [main]'s header and package, every file's
 * imports once and in order, then each file's declarations after [main]'s.
 *
 * For an export lane that carries one file. Every declaration flexpress generates is named after
 * its function (`internal val <fn>Outline`, `private object <Fn>Outline`), so declarations from
 * several requests cannot collide. Each other file's leading comment (codegen's "Generated by"
 * note) is kept above its declarations.
 */
fun joinKotlinFiles(main: String, others: List<String>): String {
  if (others.isEmpty()) return main
  val parts = (listOf(main) + others).map(::splitKotlinFile)
  val head = parts.first()
  val imports = parts.flatMap { it.imports }.distinct()
  return buildString {
    append(head.header)
    if (imports.isNotEmpty()) {
      imports.forEach { appendLine(it) }
      appendLine()
    }
    parts.forEachIndexed { index, part ->
      if (index > 0) {
        appendLine()
        part.leading.takeIf { it.isNotBlank() }?.let { appendLine(it.trim()) }
      }
      append(part.body.trim())
      appendLine()
    }
  }
}

private class KotlinFileParts(
  /** Everything to the package line and the blank line after it. */
  val header: String,
  /** What comes before the package line: the file's leading comments. */
  val leading: String,
  val imports: List<String>,
  val body: String,
)

/** A Kotlin file in [KotlinFileParts]. A file with no package line is all body. */
private fun splitKotlinFile(source: String): KotlinFileParts {
  val lines = source.lines()
  val packageLine = lines.indexOfFirst { it.startsWith("package ") }
  if (packageLine < 0) return KotlinFileParts("", "", emptyList(), source)
  val imports = mutableListOf<String>()
  var index = packageLine + 1
  while (index < lines.size && (lines[index].isBlank() || lines[index].startsWith("import "))) {
    if (lines[index].startsWith("import ")) imports += lines[index]
    index++
  }
  val header = lines.subList(0, packageLine + 1).joinToString("\n") + "\n\n"
  val leading = lines.subList(0, packageLine).joinToString("\n")
  return KotlinFileParts(header, leading, imports, lines.drop(index).joinToString("\n"))
}
