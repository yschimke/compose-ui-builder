package ee.schimke.composeai.uibuilder.export

/**
 * A text's OpenType settings: the variation axes it sets on a variable font and the layout features
 * it turns on or off, and the property each is authored as.
 *
 * Both are a text property on every design system's text component — `m3/text`, `wear-m3/text`,
 * `remote-m3/remote-text` — because both are how a type system is actually specified now: a brand
 * on a variable face names a width or a grade, not just a weight, and a figure set in a clock or a
 * table needs `tnum`. Before these a design could only reach an axis through `fontWeight`'s four
 * named weights, and could not reach a feature at all.
 *
 * Both values are CSS's comma-separated lists with the tags written bare — the spelling every
 * platform's `fontFeatureSettings` parser accepts ([formatFeatures]) — so a value reads the same in
 * a design, in the inspector and in generated code. CSS's quoted tags are read too.
 * - [VARIATION_PROPERTY]: `wght 650, wdth 87.5` — `font-variation-settings`.
 * - [FEATURE_PROPERTY]: `tnum, ss01, liga 0` — `font-feature-settings`; a tag alone is on.
 *
 * Which axes and features a text may use is the typeface's, not this object's: any tag is accepted,
 * and the inspector reads the face the text is set in ([familyFor]) to offer the ones it has, with
 * each axis's range. A setting the face does not have is ignored by every text stack, which is what
 * CSS does too, so a design survives a typeface change without becoming invalid.
 *
 * One list, read by the catalogs that declare the properties, the canvas that draws them and the
 * generators that write them, so none of them can disagree about the syntax.
 */
object FontSettings {
  const val VARIATION_PROPERTY: String = "fontVariationSettings"
  const val FEATURE_PROPERTY: String = "fontFeatureSettings"

  val PROPERTIES: Set<String> = linkedSetOf(VARIATION_PROPERTY, FEATURE_PROPERTY)

  /** Every design system's text component, which all carry both properties. */
  val TEXT_COMPONENTS: Set<String> = setOf("m3/text", "wear-m3/text", "remote-m3/remote-text")

  /** Wear's own face, which a Wear role is set in when nothing names another one. */
  const val WEAR_DEFAULT_FAMILY: String = "Roboto Flex"

  /** One axis setting: a four-character tag and its coordinate. */
  data class Axis(val tag: String, val value: Float)

  /** One feature setting: a four-character tag and its value, 1 for on and 0 for off. */
  data class Feature(val tag: String, val value: Int = 1) {
    val enabled: Boolean
      get() = value != 0
  }

  private val TAG = Regex("^[\\x20-\\x7E]{4}$")

  /** Whether [tag] is a well-formed OpenType tag: four printable ASCII characters. */
  fun isTag(tag: String): Boolean = TAG.matches(tag)

  private fun entries(text: String?): List<Pair<String, String?>> =
    text
      .orEmpty()
      .split(',')
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .mapNotNull { entry ->
        // `'wght' 650`, `"wght" 650`, `wght 650` and `wght=650` all name the same setting.
        val match =
          Regex("^(['\"]?)([^'\"]{4})\\1\\s*(?:=\\s*)?(\\S+)?$").find(entry)
            ?: return@mapNotNull null
        val tag = match.groupValues[2]
        if (!isTag(tag)) null else tag to match.groupValues[3].ifEmpty { null }
      }

  /**
   * The axes [text] sets, in order, last setting of a tag winning; a malformed entry is skipped, as
   * CSS skips it.
   */
  fun parseVariations(text: String?): List<Axis> =
    entries(text)
      .mapNotNull { (tag, value) ->
        value?.toFloatOrNull()?.takeIf { it.isFinite() }?.let { Axis(tag, it) }
      }
      .associateBy { it.tag }
      .values
      .toList()

  /** The features [text] sets, in order, last setting of a tag winning. */
  fun parseFeatures(text: String?): List<Feature> =
    entries(text)
      .mapNotNull { (tag, value) ->
        val parsed =
          when (value?.lowercase()) {
            null,
            "on" -> 1
            "off" -> 0
            else -> value.toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
          }
        Feature(tag, parsed)
      }
      .associateBy { it.tag }
      .values
      .toList()

  /** [axes] in the canonical spelling: `wght 650, wdth 87.5`. */
  fun formatVariations(axes: List<Axis>): String =
    axes.joinToString(", ") { "${it.tag} ${number(it.value)}" }

  /**
   * [features] in the canonical spelling: `tnum, liga 0, salt 2`.
   *
   * Bare tags, not CSS's quoted ones: Compose Desktop and Web parse `fontFeatureSettings` with
   * Skia's `FontFeature.parseW3`, which splits on commas and spaces and takes a tag only when it is
   * exactly four characters, so `'tnum'` is silently dropped there. Android's HarfBuzz parser takes
   * both spellings, so the bare one is the one every platform draws.
   */
  fun formatFeatures(features: List<Feature>): String =
    features.joinToString(", ") { if (it.value == 1) it.tag else "${it.tag} ${it.value}" }

  /** A coordinate as CSS writes it: no trailing `.0`, at most two decimals. */
  fun number(value: Float): String {
    val rounded = kotlin.math.round(value * 100f) / 100f
    return if (rounded == kotlin.math.floor(rounded)) rounded.toLong().toString()
    else rounded.toString().trimEnd('0').trimEnd('.')
  }

  /**
   * The family a text is set in, by name, or null for the platform's own face.
   *
   * [style] is the text's own role, if it names one; otherwise the theme host's [ThemeTextStyle],
   * and otherwise `bodyLarge`. The role's family is the theme host's typeface for its group
   * ([ThemeTypefaces]); a role the host leaves unset is in [documentTypeface] on Material 3, and in
   * Wear's own face ([WEAR_DEFAULT_FAMILY]) on Wear. [readHost] is the theme host's string property
   * by name.
   */
  fun familyFor(
    style: String?,
    readHost: (String) -> String?,
    documentTypeface: String?,
    wear: Boolean,
  ): String? {
    val role =
      style?.trim()?.takeIf { it.isNotEmpty() }
        ?: ThemeTextStyle.role(readHost)
        ?: ThemeTextStyle.DEFAULT
    val families = ThemeTypefaces.families(readHost)
    return if (wear) {
      ThemeTypefaces.wearRoleFamilies(families)[ThemeTextStyle.wearRole(role)]
        ?: WEAR_DEFAULT_FAMILY
    } else {
      ThemeTypefaces.m3RoleFamilies(families)[ThemeTextStyle.m3Role(role)]
        ?: documentTypeface?.trim()?.takeIf { it.isNotEmpty() }
    }
  }

  /** The face a text node is set in, and whether it is set on Wear's type scale. */
  data class TextTypeface(
    /** The family's name, or null for the platform's own face. */
    val family: String?,
    /** Wear's (and Remote Material 3's) type scale rather than Material 3's. */
    val wear: Boolean,
  )

  /**
   * [familyFor] for the node [nodeId] of [document]: its own `style`, the nearest ancestor that
   * sets a typeface or text role as its theme host, and the document's `typeface`. A text is on
   * Wear's scale when it is a Wear or Remote text or sits inside a Wear screen or widget.
   */
  fun textTypeface(document: UiBuilderDocument, nodeId: String): TextTypeface? {
    val node = document.nodes[nodeId] ?: return null
    val parents = buildMap {
      document.nodes.values.forEach { parent ->
        parent.slots.values.forEach { children -> children.forEach { put(it, parent.id) } }
      }
    }
    val ancestors =
      generateSequence(parents[nodeId]) { parents[it] }
        .take(document.nodes.size)
        .mapNotNull(document.nodes::get)
        .toList()
    fun UiBuilderNode.read(name: String): String? = properties[name]?.stringOrNull()
    val hostKeys = ThemeTypefaces.PROPERTIES + ThemeTextStyle.PROPERTY
    val host = ancestors.firstOrNull { ancestor -> hostKeys.any { ancestor.read(it) != null } }
    val wear =
      node.componentId != "m3/text" ||
        ancestors.any {
          it.componentId.startsWith("wear-m3/") || it.componentId.startsWith("remote-m3/")
        }
    val family =
      familyFor(
        node.read("style"),
        { name -> host?.read(name) },
        document.environment["typeface"]?.let {
          (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        },
        wear,
      )
    return TextTypeface(family, wear)
  }

  /**
   * What a generated Compose `Text` writes for a node's font settings.
   *
   * - [featureSettings]: the canonical feature string, for `style = <role>.copy(fontFeatureSettings
   *   = …)` — `Text` has no argument of its own for it.
   * - [fontFamily]: an expression for a family that carries the axes. Compose sets axes on a
   *   `Font`, not on a style, so this is `FontFamily(Font(DeviceFontFamilyName(…),
   *   variationSettings = …))` over the device face the text is set in: Wear's `roboto-flex`, or
   *   Android's `sans-serif` for a Material 3 text with no typeface of its own.
   * - [fontWeight]: a text set in a Google Fonts typeface cannot carry axes — the downloadable-font
   *   provider serves named instances, not the variable file — so its `wght` becomes the
   *   `FontWeight` the provider fetches, and its other axes are [droppedAxes].
   */
  data class ComposeArguments(
    val featureSettings: String?,
    val fontFamily: String?,
    val fontWeight: String?,
    val droppedAxes: List<String>,
    /** Fully-qualified imports the expressions need. */
    val imports: Set<String>,
    /**
     * The node's `wght` decides the weight, so its `fontWeight` must not be written: on the device
     * face an authored weight would be matched or synthesised over the variable instance, which is
     * not what the canvas draws.
     */
    val overridesWeight: Boolean = false,
  ) {
    /**
     * What a generated `Text` appends to its style: `.copy(…)` with the features and, for an
     * instance whose `wght` is the weight, no weight synthesis — a synthesised bold over `wght` 800
     * would be two bolds. Null when there is nothing to copy.
     */
    val styleCopy: String?
      get() {
        val arguments = buildList {
          featureSettings?.let { add("fontFeatureSettings = \"$it\"") }
          if (overridesWeight && fontFamily != null) add("fontSynthesis = FontSynthesis.Style")
        }
        return if (arguments.isEmpty()) null else ".copy(${arguments.joinToString(", ")})"
      }
  }

  fun composeArguments(document: UiBuilderDocument, nodeId: String): ComposeArguments {
    val node = document.nodes[nodeId]
    fun read(name: String) =
      (node?.properties?.get(name) as? kotlinx.serialization.json.JsonObject)
        ?.get("value")
        ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
        ?.content
    val features = formatFeatures(parseFeatures(read(FEATURE_PROPERTY))).ifEmpty { null }
    val axes = parseVariations(read(VARIATION_PROPERTY))
    if (axes.isEmpty()) return ComposeArguments(features, null, null, emptyList(), emptySet())
    val typeface = textTypeface(document, nodeId)
    val device =
      when {
        typeface == null -> null
        typeface.wear && typeface.family == WEAR_DEFAULT_FAMILY -> "roboto-flex"
        !typeface.wear && typeface.family == null -> "sans-serif"
        else -> null
      }
    if (device == null) {
      val weight = axes.firstOrNull { it.tag == "wght" }?.value?.toInt()?.coerceIn(1, 1000)
      return ComposeArguments(
        features,
        fontFamily = null,
        fontWeight = weight?.let { "FontWeight($it)" },
        droppedAxes = axes.map { it.tag }.filter { it != "wght" },
        imports =
          if (weight != null) setOf("androidx.compose.ui.text.font.FontWeight") else emptySet(),
        overridesWeight = weight != null,
      )
    }
    val settings =
      axes.joinToString(", ") { "FontVariation.Setting(\"${it.tag}\", ${number(it.value)}f)" }
    return ComposeArguments(
      features,
      fontFamily =
        "FontFamily(Font(DeviceFontFamilyName(\"$device\"), " +
          "variationSettings = FontVariation.Settings($settings)))",
      fontWeight = null,
      droppedAxes = emptyList(),
      imports =
        setOfNotNull(
          "androidx.compose.ui.text.font.DeviceFontFamilyName",
          "androidx.compose.ui.text.font.Font",
          "androidx.compose.ui.text.font.FontFamily",
          "androidx.compose.ui.text.font.FontVariation",
          "androidx.compose.ui.text.font.FontSynthesis".takeIf { axes.any { it.tag == "wght" } },
        ),
      overridesWeight = axes.any { it.tag == "wght" },
    )
  }

  /** Readable names for the registered axes and the common features, for an inspector label. */
  val AXIS_NAMES: Map<String, String> =
    mapOf(
      "wght" to "Weight",
      "wdth" to "Width",
      "opsz" to "Optical size",
      "ital" to "Italic",
      "slnt" to "Slant",
      "GRAD" to "Grade",
      "ROND" to "Roundness",
      "FILL" to "Fill",
      "XOPQ" to "Thick stroke",
      "YOPQ" to "Thin stroke",
      "XTRA" to "Counter width",
      "YTUC" to "Uppercase height",
      "YTLC" to "Lowercase height",
      "YTAS" to "Ascender height",
      "YTDE" to "Descender depth",
      "YTFI" to "Figure height",
      "CASL" to "Casual",
      "CRSV" to "Cursive",
      "MONO" to "Monospace",
      "SOFT" to "Softness",
      "WONK" to "Wonky",
    )

  val FEATURE_NAMES: Map<String, String> =
    mapOf(
      "aalt" to "Access all alternates",
      "c2sc" to "Small capitals from capitals",
      "calt" to "Contextual alternates",
      "case" to "Case-sensitive forms",
      "ccmp" to "Glyph composition",
      "cpsp" to "Capital spacing",
      "cv01" to "Character variant 1",
      "dlig" to "Discretionary ligatures",
      "dnom" to "Denominators",
      "frac" to "Fractions",
      "kern" to "Kerning",
      "liga" to "Standard ligatures",
      "lnum" to "Lining figures",
      "locl" to "Localized forms",
      "mark" to "Mark positioning",
      "mkmk" to "Mark-to-mark positioning",
      "numr" to "Numerators",
      "onum" to "Oldstyle figures",
      "ordn" to "Ordinals",
      "pnum" to "Proportional figures",
      "rvrn" to "Required variation alternates",
      "salt" to "Stylistic alternates",
      "sinf" to "Scientific inferiors",
      "smcp" to "Small capitals",
      "subs" to "Subscript",
      "sups" to "Superscript",
      "swsh" to "Swash",
      "tnum" to "Tabular figures",
      "unic" to "Unicase",
      "zero" to "Slashed zero",
    )

  /**
   * Features a text stack applies on its own and that an author has no reason to switch: shaping
   * and positioning, without which a script does not render correctly. The inspector lists them
   * apart from the ones worth choosing.
   */
  val REQUIRED_FEATURES: Set<String> =
    setOf("ccmp", "locl", "mark", "mkmk", "rvrn", "rlig", "rtlm", "abvm", "blwm", "curs", "dist")

  /** A feature's readable name: the registered one, `Stylistic set N`, or the tag. */
  fun featureName(tag: String): String =
    FEATURE_NAMES[tag]
      ?: Regex("^ss(\\d\\d)$").find(tag)?.let { "Stylistic set ${it.groupValues[1].toInt()}" }
      ?: Regex("^cv(\\d\\d)$").find(tag)?.let { "Character variant ${it.groupValues[1].toInt()}" }
      ?: tag

  /** What an agent reads about [VARIATION_PROPERTY] in the catalog. */
  val VARIATION_NOTES: String =
    "Variable-font axis coordinates, CSS `font-variation-settings` style with bare tags: " +
      "`wght 650, wdth 87.5, GRAD -50`. Applies to the typeface the text is set in (its role's theme " +
      "typeface, or the platform face); an axis that face does not have is ignored, so the " +
      "inspector offers only the face's own axes with their ranges. Roboto Flex, Wear's own face, " +
      "has thirteen (wght 100–1000, wdth 25–151, opsz 8–144, GRAD -200–150, …). `wght` set here " +
      "overrides `fontWeight`."

  /** What an agent reads about [FEATURE_PROPERTY] in the catalog. */
  val FEATURE_NOTES: String =
    "OpenType layout features, CSS `font-feature-settings` style with bare tags: " +
      "`tnum, ss01, liga 0` " +
      "— a tag alone turns the feature on, `0` turns it off and a larger number picks an " +
      "alternate. `tnum` gives every figure the same width, which a clock, a timer or a column of " +
      "numbers needs. A feature the typeface does not have is ignored; the inspector lists the " +
      "face's own."
}
