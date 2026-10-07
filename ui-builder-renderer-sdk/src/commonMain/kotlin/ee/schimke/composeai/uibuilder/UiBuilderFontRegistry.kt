package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.composeai.uibuilder.export.FontSettings
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Lives in the renderer SDK so a catalog runtime resolves typefaces exactly as the editor canvas
// does.

/** One file of a vendored family, as `assets/rc-fonts/fonts.json` lists it. */
@Serializable data class VendoredFontFile(val file: String, val weight: Int = 400)

/**
 * A family the host ships, by the name a document's `typeface` uses.
 *
 * [role] is the manifest's: `default` is the face Remote Compose falls back to, `generic` the
 * `serif`/`monospace` stand-ins, `named` everything a design picks by name.
 */
@Serializable
data class VendoredFontFamily(
  val name: String,
  val role: String = "named",
  val fonts: List<VendoredFontFile>,
) {
  /** What a person reads in a menu: `serif` and `monospace` are CSS keywords, not names. */
  val label: String
    get() = if (role == "generic") name.replaceFirstChar { it.uppercase() } else name
}

@Serializable
data class VendoredFontManifest(val version: Int = 1, val families: List<VendoredFontFamily>)

private val manifestJson = Json { ignoreUnknownKeys = true }

/** One family name per line; blank lines and `#` comments skipped. */
fun parseFamilyList(text: String): List<String> =
  text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

fun parseVendoredFontManifest(text: String): VendoredFontManifest =
  manifestJson.decodeFromString(VendoredFontManifest.serializer(), text)

/**
 * The vendored families, loaded lazily one at a time on first use (the full set is ~4.5 MB).
 * [loaded] is snapshot state, so readers recompose when a family arrives. Nothing, not even the
 * manifest, loads until the first [request] or [loadFamilies].
 *
 * A family that fails to load is left out rather than retried, so a missing file cannot become a
 * request loop. Names outside the manifest go to [readRemoteFont] when the host has a font service
 * (e.g. any Google Fonts family).
 *
 * @param readManifest the manifest's text; failures leave the registry with no families. @param
 *   readFont a manifest file's bytes, by the name the manifest gives it. @param readRemoteFont a
 *   family's file at one weight, by family name; null for a host with no such service.
 */
class UiBuilderFontRegistry(
  private val scope: CoroutineScope,
  private val readManifest: suspend () -> String,
  private val readFont: suspend (file: String) -> ByteArray,
  private val readRemoteFont: (suspend (family: String, weight: Int) -> ByteArray)? = null,
  /**
   * The names [readRemoteFont] can be asked for, one per line (`#` lines are comments), for a
   * picker to offer; null for a host with no font service, which offers the manifest alone.
   */
  private val readRemoteFamilies: (suspend () -> String)? = null,
) : UiBuilderFontVariants {
  /** The manifest's text, for a host that reads it for something else too (Wear's device face). */
  suspend fun readManifestText(): String = readManifest()

  /** A manifest file's bytes, for the same kind of host. */
  suspend fun readFontBytes(file: String): ByteArray = readFont(file)

  /** The families that can be asked for; empty until the manifest has loaded. */
  var families: List<VendoredFontFamily> by mutableStateOf(emptyList())
    private set

  /**
   * Every family [readRemoteFont] can fetch, by name, once [loadRemoteFamilies] has read them;
   * empty before, and for a host with no font service. The picker searches these.
   */
  var remoteFamilies: List<String> by mutableStateOf(emptyList())
    private set

  private var remoteFamiliesRequested = false

  /** Read the list behind [remoteFamilies], once; a failure leaves it empty. */
  fun loadRemoteFamilies() {
    val read = readRemoteFamilies ?: return
    if (remoteFamiliesRequested) return
    remoteFamiliesRequested = true
    scope.launch {
      remoteFamilies = runCatching { parseFamilyList(read()) }.getOrDefault(emptyList())
    }
  }

  /** Families that have finished loading, by name. */
  val loaded: SnapshotStateMap<String, FontFamily> = mutableStateMapOf()

  /**
   * What each loaded family offers a text's font settings — its variation axes with their ranges
   * and its layout features — read from the files themselves, under the same key as [loaded].
   */
  val typefaces: SnapshotStateMap<String, TypefaceInfo> = mutableStateMapOf()

  /** The files behind each loaded family, kept so [variant] can build an instance at any axes. */
  private val files = mutableMapOf<String, List<UiBuilderFontFile>>()
  private val variants = mutableMapOf<Pair<String, List<FontSettings.Axis>>, FontFamily>()

  /**
   * [name]'s family at the axis coordinates [axes] sets, or the plain family when [axes] is empty;
   * null until [name] has loaded. One `FontFamily` per name and setting, so a recomposition hands
   * the text stack the same instance and it does not lay the text out again. See
   * [variableFontFamily].
   */
  override fun variant(name: String, axes: List<FontSettings.Axis>): FontFamily? {
    if (axes.isEmpty()) return loaded[name]
    val loadedFiles = files[name] ?: return null
    return variants.getOrPut(name to axes) { variableFontFamily(loadedFiles, axes) }
  }

  private fun publish(name: String, loadedFiles: List<UiBuilderFontFile>) {
    files[name] = loadedFiles
    typefaces[name] =
      loadedFiles.map { readTypefaceInfo(it.data) }.fold(TypefaceInfo.EMPTY, TypefaceInfo::plus)
    loaded[name] =
      FontFamily(loadedFiles.map { platformFont(it.identity, it.data, FontWeight(it.weight)) })
  }

  private val requested = mutableSetOf<String>()
  private var manifestRequested = false
  private var manifestLoaded = false

  /** Fetch the manifest if nothing has yet, so [families] fills in; the picker's list needs it. */
  fun loadFamilies() {
    if (manifestRequested) return
    manifestRequested = true
    scope.launch {
      families =
        runCatching { parseVendoredFontManifest(readManifest()).families }.getOrDefault(emptyList())
      manifestLoaded = true
      // Anything asked for while the manifest was in flight.
      requested.toList().forEach(::load)
    }
  }

  /** Start loading [name] if it is a vendored family and nothing has asked for it yet. */
  fun request(name: String) {
    if (!requested.add(name)) return
    if (manifestLoaded) load(name) else loadFamilies()
  }

  /**
   * Load [name] into [loaded] under exactly that key, since it is the document's spelling the
   * renderer looks up. The manifest is matched by [canonicalFamilyName], so `google:Inter` and
   * `inter` are the vendored Inter; anything else goes to [readRemoteFont] when there is one.
   */
  private fun load(name: String) {
    val canonical = canonicalFamilyName(name) ?: return
    val family = families.firstOrNull { canonicalFamilyName(it.name) == canonical }
    val remote = readRemoteFont
    when {
      family != null ->
        scope.launch {
          runCatching {
            family.fonts.map { file ->
              UiBuilderFontFile(
                identity = "ui-builder:${family.name}:${file.weight}",
                data = readFont(file.file),
                weight = file.weight,
              )
            }
          }
            .onSuccess { publish(name, it) }
        }
      // A generic keyword is the manifest's to answer; no font service has a family called `serif`.
      remote != null && canonical !in GENERIC_FAMILY_NAMES ->
        scope.launch {
          val family = remoteFamilyName(name)
          // Regular is the family: without it there is nothing to draw. Bold is a bonus, so a
          // family that has no 700 still loads, and its bold text is synthesised from the 400.
          val fonts = REMOTE_FONT_WEIGHTS.mapNotNull { weight ->
            runCatching {
              UiBuilderFontFile("ui-builder:remote:$family:$weight", remote(family, weight), weight)
            }
              .getOrNull() ?: if (weight == FontWeight.Normal.weight) return@launch else null
          }
          publish(name, fonts)
        }
    }
  }
}

/** One font file of a family, as loaded: what a variable instance of it is built from. */
class UiBuilderFontFile(val identity: String, val data: ByteArray, val weight: Int)

/**
 * A family's files at the axis coordinates [axes] sets, for any host that has the bytes.
 *
 * A coordinate is passed to the face as given — the font clamps it to its own range, and drops an
 * axis it does not have — and `wght`, when [axes] leaves it out, stays at each file's own weight,
 * which is what the plain family draws.
 */
fun variableFontFamily(files: List<UiBuilderFontFile>, axes: List<FontSettings.Axis>): FontFamily =
  FontFamily(
    files.map { file ->
      val settings = buildList {
        if (axes.none { it.tag == "wght" }) add(FontVariation.weight(file.weight))
        axes.forEach { add(FontVariation.Setting(it.tag, it.value)) }
      }
      platformFont(
        identity = "${file.identity}:" + axes.joinToString(",") { "${it.tag}=${it.value}" },
        data = file.data,
        weight = FontWeight(file.weight),
        variationSettings = FontVariation.Settings(*settings.toTypedArray()),
      )
    }
  )

/**
 * What builds a family at a text's variation axes, by the family name a document uses: the editor's
 * [UiBuilderFontRegistry], or a production render's resolved families. Null where the host has no
 * font bytes, and then a text draws its features and not its axes.
 */
fun interface UiBuilderFontVariants {
  /** [name]'s family at [axes], or null when [name] is not loaded here. */
  fun variant(name: String, axes: List<FontSettings.Axis>): FontFamily?
}

val LocalUiBuilderFontVariants = staticCompositionLocalOf<UiBuilderFontVariants?> { null }

/** The weights fetched for a family the manifest does not list: the two the vendored ones carry. */
private val REMOTE_FONT_WEIGHTS = listOf(400, 700)

private val GENERIC_FAMILY_NAMES = setOf("serif", "monospace", "sans-serif", "cursive", "fantasy")

/**
 * The family a document's typeface names, without the `google:` prefix Remote Compose documents and
 * catalog themes spell a downloadable family with, and with its whitespace tidied.
 */
fun remoteFamilyName(name: String): String =
  name.trim().removePrefix("google:").trim().replace(Regex("\\s+"), " ")

/**
 * [remoteFamilyName] for comparison — family names are not case-sensitive anywhere they resolve —
 * or null for a name that is nothing once the prefix and whitespace are gone.
 */
fun canonicalFamilyName(name: String): String? =
  remoteFamilyName(name).lowercase().takeIf { it.isNotEmpty() }

/** A font from bytes, which only the platform text stack knows how to build. */
internal expect fun platformFont(
  identity: String,
  data: ByteArray,
  weight: FontWeight,
  variationSettings: FontVariation.Settings? = null,
): Font

/** The registry the host provides, which the typeface picker lists and the renderer asks. */
val LocalUiBuilderFontRegistry = staticCompositionLocalOf<UiBuilderFontRegistry?> { null }

/**
 * Make [registry]'s families available to everything in [content]: the picker's list, and
 * [LocalUiBuilderFontFamilies] for the renderer. Null leaves both empty, which is the behaviour of
 * a host that ships no fonts.
 */
@Composable
fun ProvideUiBuilderFonts(registry: UiBuilderFontRegistry?, content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalUiBuilderFontRegistry provides registry,
    LocalUiBuilderFontFamilies provides (registry?.loaded ?: emptyMap()),
    LocalUiBuilderFontVariants provides registry,
    content = content,
  )
}

/**
 * Families the host has already loaded, keyed by the family name a document's `typeface` names.
 *
 * **The renderer resolves, it does not fetch.** A document carries a family NAME
 * (`DesignEnvironmentV1.typeface`), and how that name becomes glyphs differs per host: the browser
 * renderer vendors eight families in `assets/rc-fonts` and can download anything else from Google
 * Fonts; a JVM test host has neither. Loading is asynchronous and platform-specific, and this
 * module is `commonMain` with no I/O in it, so the host does the loading and provides the result
 * here.
 *
 * That split is also what keeps the network question where it can be answered. A vendored family
 * resolves with no request at all, so the common cases — Roboto Flex and Google Sans Flex among
 * them — never touch the network; only a family nobody vendored needs one, and the host is the only
 * layer that knows whether the document's `networkAccess` permits it.
 *
 * Empty by default, which is exactly today's behaviour: nothing resolves, and every document
 * renders in the platform default face.
 */
val LocalUiBuilderFontFamilies = staticCompositionLocalOf<Map<String, FontFamily>> { emptyMap() }

/**
 * The family each type-scale role is drawn in under a theme host, by role name, for the groups
 * whose family has loaded.
 *
 * [families] is what the host names, by group ([ThemeTypefaces.families]). Each family is asked of
 * the page's font registry here, where it is read, and the result is snapshot state: the canvas
 * draws in the platform face until a family arrives and recomposes in it when it does. A family
 * that never loads — no network, not a Google Fonts family — stays on the platform face, which is
 * what the design looked like before it named one.
 */
@Composable
fun rememberThemeRoleFamilies(
  families: Map<ThemeTypefaces.Group, String>,
  wear: Boolean,
): Map<String, FontFamily> {
  if (families.isEmpty()) return emptyMap()
  val registry = LocalUiBuilderFontRegistry.current
  LaunchedEffect(registry, families) { families.values.toSet().forEach { registry?.request(it) } }
  val loaded = LocalUiBuilderFontFamilies.current
  val byRole =
    if (wear) ThemeTypefaces.wearRoleFamilies(families) else ThemeTypefaces.m3RoleFamilies(families)
  return byRole.mapNotNull { (role, name) -> loaded[name]?.let { role to it } }.toMap()
}
