package ee.schimke.composeai.uibuilder

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import ee.schimke.composeai.uibuilder.export.FontSettings
import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The system property a host sets on the [ProductionUiBuilderPreview] daemon: the directory of
 * fetched Google Fonts files (`<slug>-<weight>.ttf`, as the server's `/api/fonts/google` cache
 * writes them).
 */
const val GOOGLE_FONTS_DIRECTORY_PROPERTY: String = "uiBuilder.googleFontsDir"

/**
 * A production render's typefaces, by the family name a document uses, resolved synchronously when
 * first asked, so a daemon's single frame is already in the design's faces (the browser's async
 * [UiBuilderFontRegistry] would miss it). Vendored families come from the classpath
 * (`assets/rc-fonts`), others from [googleFontsDirectory].
 *
 * Provided as [LocalUiBuilderFontFamilies] (read by the canvas, [rememberThemeRoleFamilies] and
 * `RemoteComposeCanvas`'s player). A family with no file stays on the platform face; nothing is
 * fetched.
 */
internal class ProductionFontFamilies(
  private val readResource: (path: String) -> ByteArray?,
  private val googleFontsDirectory: File?,
) : AbstractMap<String, FontFamily>(), UiBuilderFontVariants {
  private val manifest: VendoredFontManifest? by lazy {
    readResource("/fonts/fonts.json")?.let {
      runCatching { parseVendoredFontManifest(it.decodeToString()) }.getOrNull()
    }
  }

  private val resolved = ConcurrentHashMap<String, Optional<FontFamily>>()
  private val files = ConcurrentHashMap<String, Optional<List<UiBuilderFontFile>>>()
  private val variants = ConcurrentHashMap<Pair<String, List<FontSettings.Axis>>, FontFamily>()

  /** [key]'s family at a text's axes, from the same files [get] reads; see [variableFontFamily]. */
  override fun variant(name: String, axes: List<FontSettings.Axis>): FontFamily? {
    if (axes.isEmpty()) return get(name)
    val loaded = filesOf(name) ?: return null
    return variants.computeIfAbsent(name to axes) { variableFontFamily(loaded, axes) }
  }

  override fun hasAxis(name: String, tag: String): Boolean =
    filesOf(name).orEmpty().any { tag in it.axisTags }

  private fun filesOf(name: String): List<UiBuilderFontFile>? =
    files.computeIfAbsent(name) { Optional.ofNullable(loadFiles(it)) }.orElse(null)

  override val entries: Set<Map.Entry<String, FontFamily>>
    get() =
      resolved.entries
        .mapNotNull { (name, family) ->
          family.orElse(null)?.let { java.util.AbstractMap.SimpleEntry(name, it) }
        }
        .toSet()

  override fun containsKey(key: String): Boolean = get(key) != null

  override fun get(key: String): FontFamily? =
    resolved
      .computeIfAbsent(key) { name ->
        Optional.ofNullable(
          filesOf(name)?.let { loaded ->
            // With its weight as a `wght` setting: without one a variable file draws its
            // default instance at every weight the manifest lists it at.
            FontFamily(
              loaded.map {
                Font(
                  it.identity,
                  it.data,
                  FontWeight(it.weight),
                  FontStyle.Normal,
                  FontVariation.Settings(FontVariation.weight(it.weight)),
                )
              }
            )
          }
        )
      }
      .orElse(null)

  private fun loadFiles(name: String): List<UiBuilderFontFile>? {
    val canonical = canonicalFamilyName(name) ?: return null
    manifest
      ?.families
      ?.firstOrNull { canonicalFamilyName(it.name) == canonical }
      ?.let { family ->
        val data =
          family.fonts.map { it.file }.distinct().associateWith { readResource("/fonts/$it") }
        val fonts =
          family.fonts.mapNotNull { file ->
            data[file.file]?.let {
              UiBuilderFontFile("ui-builder:${family.name}:${file.weight}", it, file.weight)
            }
          }
        // A manifest family whose files this classpath lacks is still a Google Fonts family the
        // host may have cached, so it falls through to the cache rather than to the default face.
        if (fonts.isNotEmpty()) return fonts
      }
    if (canonical in GENERIC_FAMILIES) return null
    val directory = googleFontsDirectory ?: return null
    val family = remoteFamilyName(name)
    // Regular is the family; bold is a bonus, synthesised from the regular when it is missing — the
    // same two weights, and the same rule, as the browser registry's font-service lane.
    val fonts = GOOGLE_FONT_WEIGHTS.mapNotNull { weight ->
      File(directory, "${googleFontSlug(family)}-$weight.ttf")
        .takeIf { it.isFile && it.length() > 0 }
        ?.let { UiBuilderFontFile("ui-builder:remote:$family:$weight", it.readBytes(), weight) }
        ?: if (weight == FontWeight.Normal.weight) return null else null
    }
    return fonts
  }

  companion object {
    /** The daemon's: the bundle's classpath, and the directory its host names, if any. */
    val production: ProductionFontFamilies by lazy {
      ProductionFontFamilies(
        readResource = { path ->
          ProductionFontFamilies::class.java.getResourceAsStream(path)?.use { it.readBytes() }
        },
        googleFontsDirectory =
          System.getProperty(GOOGLE_FONTS_DIRECTORY_PROPERTY)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File),
      )
    }

    private val GOOGLE_FONT_WEIGHTS = listOf(400, 700)

    private val GENERIC_FAMILIES = setOf("serif", "monospace", "sans-serif", "cursive", "fantasy")

    /**
     * The file-name stem the server's cache gives [family]: lowercase, each non-alphanumeric run
     * one `-`, none at the ends (`Exo 2` is `exo-2`).
     */
    fun googleFontSlug(family: String): String =
      family.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
  }
}
