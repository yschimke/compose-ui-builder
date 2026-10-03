package ee.schimke.composeai.uibuilder

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The system property a host sets, on the daemon that runs [ProductionUiBuilderPreview], to the
 * directory of Google Fonts files it has fetched: `<slug>-<weight>.ttf`, as the server's
 * `/api/fonts/google` cache writes them.
 */
const val GOOGLE_FONTS_DIRECTORY_PROPERTY: String = "uiBuilder.googleFontsDir"

/**
 * A production render's typefaces, by the family name a document uses, resolved when first asked.
 *
 * The browser's [UiBuilderFontRegistry] loads a family asynchronously and recomposes once it lands,
 * which suits a canvas someone is looking at and not a daemon that draws one frame: the frame was
 * taken first, so every thumbnail and PNG export drew a themed design in the platform face. Here a
 * lookup reads the bytes then and there — the vendored families from the classpath, where the build
 * copies `assets/rc-fonts`, and any other family from [googleFontsDirectory] — so the first frame
 * is already in the design's faces.
 *
 * It is the map [LocalUiBuilderFontFamilies] provides, which is where the canvas and
 * [rememberThemeRoleFamilies] look a family up, and through `RemoteComposeCanvas` what a Remote
 * document's player draws `google:` names in. A family with no file stays on the platform face, as
 * it does in the browser when the font service has nothing for it; nothing is fetched from here.
 */
internal class ProductionFontFamilies(
  private val readResource: (path: String) -> ByteArray?,
  private val googleFontsDirectory: File?,
) : AbstractMap<String, FontFamily>() {
  private val manifest: VendoredFontManifest? by lazy {
    readResource("/fonts/fonts.json")?.let {
      runCatching { parseVendoredFontManifest(it.decodeToString()) }.getOrNull()
    }
  }

  private val resolved = ConcurrentHashMap<String, Optional<FontFamily>>()

  override val entries: Set<Map.Entry<String, FontFamily>>
    get() =
      resolved.entries
        .mapNotNull { (name, family) ->
          family.orElse(null)?.let { java.util.AbstractMap.SimpleEntry(name, it) }
        }
        .toSet()

  override fun containsKey(key: String): Boolean = get(key) != null

  override fun get(key: String): FontFamily? =
    resolved.computeIfAbsent(key) { Optional.ofNullable(load(it)) }.orElse(null)

  private fun load(name: String): FontFamily? {
    val canonical = canonicalFamilyName(name) ?: return null
    manifest
      ?.families
      ?.firstOrNull { canonicalFamilyName(it.name) == canonical }
      ?.let { family ->
        val fonts =
          family.fonts.mapNotNull { file ->
            readResource("/fonts/${file.file}")?.let {
              Font("ui-builder:${family.name}:${file.weight}", it, FontWeight(file.weight))
            }
          }
        return fonts.takeIf { it.isNotEmpty() }?.let(::FontFamily)
      }
    if (canonical in GENERIC_FAMILIES) return null
    val directory = googleFontsDirectory ?: return null
    val family = remoteFamilyName(name)
    // Regular is the family; bold is a bonus, synthesised from the regular when it is missing — the
    // same two weights, and the same rule, as the browser registry's font-service lane.
    val fonts = GOOGLE_FONT_WEIGHTS.mapNotNull { weight ->
      File(directory, "${googleFontSlug(family)}-$weight.ttf")
        .takeIf { it.isFile && it.length() > 0 }
        ?.let { Font("ui-builder:remote:$family:$weight", it.readBytes(), FontWeight(weight)) }
        ?: if (weight == FontWeight.Normal.weight) return null else null
    }
    return FontFamily(fonts)
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
     * The file-name stem the server's cache gives [family]: lowercase, each run of anything but a
     * letter or digit one `-`, none at the ends (`Exo 2` is `exo-2`).
     */
    fun googleFontSlug(family: String): String =
      family.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
  }
}
