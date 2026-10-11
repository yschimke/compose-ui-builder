package ee.schimke.composeai.uibuilder.export

/**
 * Text drawn from a variable font's own outlines, with its axes free to move: the flexpress
 * `VariableFontText` (Compose UI) and `RemoteVariableFontText` (Remote Compose).
 *
 * State-bound or computed axes animate; others are held. Export writes the outline ahead of time
 * through flexpress's JVM-only code generator. This is the shared half; the JVM side
 * (`FlexpressVariableFontSources`) implements [VariableFontSourceGenerator].
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
   * The fonts a design can draw variable text in, by [file] in `assets/rc-fonts`. [axes] are those
   * a design may set; other axes in the files stay at their defaults.
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
   * The axis properties any [Font] has, in catalog order. Spelled out because the Wear lane checks
   * them by name; `VariableFontTextTest` keeps them equal to [Font.axes].
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
   * Why [spec] cannot be generated, or empty: text must be literal and non-empty; axes must be ones
   * [Spec.font] allows, each once. Glyph coverage is the generator's to check.
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

/**
 * How an export writes variable font text: [generator] for the declarations, in [mode]. One value
 * so each export entry point gains one parameter.
 */
data class VariableFontExport(
  val generator: VariableFontSourceGenerator,
  val mode: VariableFontExportMode = VariableFontExportMode.LIBRARY,
) {
  /**
   * [source], a screen's generated file in [packageName], with its variable font text declarations
   * joined after it, or the refusals of any [requests] [generator] could not write.
   *
   * [requests] come from `ScreenDocumentProjection.Outcome.Projected.variableFontTexts`. Requests
   * with no generator answer keep their call with an export-time note. Public so a host running the
   * projection and generator itself writes the same file as [ScreenExportGate].
   */
  fun join(
    source: String,
    requests: List<VariableFontText.Request>,
    packageName: String,
  ): Joined {
    if (requests.isEmpty()) return Joined.Emitted(source)
    val generated = requests.map { generator.generate(it, packageName, mode) }
    val refusals = generated.filterIsInstance<VariableFontSource.Refused>().map { it.reason }
    if (refusals.isNotEmpty()) return Joined.Refused(refusals.distinct())
    val files = generated.filterIsInstance<VariableFontSource.Generated>().map { it.source }
    val pending = requests.filterIndexed { index, _ -> generated[index] == null }
    val noted = buildString {
      if (files.isNotEmpty() && mode == VariableFontExportMode.LIBRARY) {
        appendLine(
          "// Its variable font text draws through flexpress: " +
            "implementation(\"ee.schimke.flexpress:flexpress-compose:$FLEXPRESS_VERSION\")."
        )
      }
      append(source.trimEnd())
      appendLine()
      pending.forEach {
        appendLine()
        appendLine(
          "// ${it.functionName} draws \"${it.spec.text}\" in ${it.spec.font.family} from the " +
            "font's outlines (flexpress). It is generated when the design exports."
        )
      }
    }
    return Joined.Emitted(joinKotlinFiles(noted, files))
  }

  /** What [join] made of a screen's source. */
  sealed interface Joined {
    /** The screen's file with every declaration its variable font texts call. */
    data class Emitted(val source: String) : Joined

    /** Why a variable font text could not be generated: a character the font has no glyph for. */
    data class Refused(val reasons: List<String>) : Joined
  }

  companion object {
    /** No generator: the calls are written with a note that the declarations come at export. */
    val None: VariableFontExport = VariableFontExport(VariableFontSourceGenerator.Unavailable)
  }
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
 * Joins Kotlin files sharing a package into one: [main]'s header and package, every file's imports
 * once, then each file's declarations. flexpress names declarations after their function, so they
 * cannot collide; each file's leading comment is kept.
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
