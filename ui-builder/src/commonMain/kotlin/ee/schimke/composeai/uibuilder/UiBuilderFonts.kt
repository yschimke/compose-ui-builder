package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.wearcmp.port.WearFonts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

fun parseVendoredFontManifest(text: String): VendoredFontManifest =
  manifestJson.decodeFromString(VendoredFontManifest.serializer(), text)

/**
 * The vendored families, loaded one at a time on first use.
 *
 * Lazy because the whole set is about 4.5 MB (Roboto Flex alone is 1.7 MB) and a design uses one
 * family, most use none. A family loads when something asks for it — the renderer, for the family
 * its document names, or the typeface picker, for the options it is about to draw — and [loaded] is
 * snapshot state, so whatever read the missing family recomposes when it arrives.
 *
 * Nothing happens at construction, the manifest included: a page that never needs a font makes no
 * font request, and one that does makes it after the first frame, never in the way of it. The
 * manifest is fetched by the first [request] or [loadFamilies], once.
 *
 * A family that fails to load is left out rather than retried: the renderer's answer to an
 * unresolved name is the default face, which is the right answer for a font the host cannot fetch
 * too, and retrying on every recomposition would turn one missing file into a request loop.
 *
 * A name the manifest does not list is not the end of it when the host can reach a font service:
 * [readRemoteFont] asks it for the family by name, which is how a design that names any Google
 * Fonts family — the usual result of an agent picking a face — draws in it rather than in the
 * default face. Without one, such a name resolves to nothing, as before.
 *
 * @param readManifest the manifest's text; failures leave the registry with no families.
 * @param readFont a manifest file's bytes, by the name the manifest gives it.
 * @param readRemoteFont a family's file at one weight, by family name, from outside the manifest;
 *   null for a host with no such service.
 */
class UiBuilderFontRegistry(
  private val scope: CoroutineScope,
  private val readManifest: suspend () -> String,
  private val readFont: suspend (file: String) -> ByteArray,
  private val readRemoteFont: (suspend (family: String, weight: Int) -> ByteArray)? = null,
) {
  internal suspend fun readManifestText(): String = readManifest()

  internal suspend fun readFontBytes(file: String): ByteArray = readFont(file)

  /** The families that can be asked for; empty until the manifest has loaded. */
  var families: List<VendoredFontFamily> by mutableStateOf(emptyList())
    private set

  /** Families that have finished loading, by name. */
  val loaded: SnapshotStateMap<String, FontFamily> = mutableStateMapOf()

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
            FontFamily(
              family.fonts.map { file ->
                platformFont(
                  identity = "ui-builder:${family.name}:${file.weight}",
                  data = readFont(file.file),
                  weight = FontWeight(file.weight),
                )
              }
            )
          }
            .onSuccess { loaded[name] = it }
        }
      // A generic keyword is the manifest's to answer; no font service has a family called `serif`.
      remote != null && canonical !in GENERIC_FAMILY_NAMES ->
        scope.launch {
          val family = remoteFamilyName(name)
          // Regular is the family: without it there is nothing to draw. Bold is a bonus, so a
          // family that has no 700 still loads, and its bold text is synthesised from the 400.
          val fonts = REMOTE_FONT_WEIGHTS.mapNotNull { weight ->
            runCatching {
              platformFont(
                identity = "ui-builder:remote:$family:$weight",
                data = remote(family, weight),
                weight = FontWeight(weight),
              )
            }
              .getOrNull() ?: if (weight == FontWeight.Normal.weight) return@launch else null
          }
          loaded[name] = FontFamily(fonts)
        }
    }
  }
}

/** The weights fetched for a family the manifest does not list: the two the vendored ones carry. */
private val REMOTE_FONT_WEIGHTS = listOf(400, 700)

private val GENERIC_FAMILY_NAMES = setOf("serif", "monospace", "sans-serif", "cursive", "fantasy")

/**
 * The family a document's typeface names, without the `google:` prefix Remote Compose documents and
 * catalog themes spell a downloadable family with, and with its whitespace tidied.
 */
internal fun remoteFamilyName(name: String): String =
  name.trim().removePrefix("google:").trim().replace(Regex("\\s+"), " ")

/**
 * [remoteFamilyName] for comparison — family names are not case-sensitive anywhere they resolve —
 * or null for a name that is nothing once the prefix and whitespace are gone.
 */
internal fun canonicalFamilyName(name: String): String? =
  remoteFamilyName(name).lowercase().takeIf { it.isNotEmpty() }

/**
 * Hand the Wear port the face Wear's type scale names, from this host's vendored fonts.
 *
 * Wear Material 3 sets every role in `DeviceFontFamilyName("roboto-flex")` — the watch's system
 * face, which the port resolves through `WearFonts` and then the platform's font manager. Nothing
 * registered it, and no desktop or browser has a font by that name, so every Wear screen on the
 * canvas was set in the platform's fallback sans: wider than Roboto Flex, enough to cut the clock
 * to "10:1" and wrap labels that fit on a watch.
 *
 * Call it before anything composes a Wear component. The port resolves the name once, when its type
 * scale is first read, and keeps the answer.
 */
suspend fun UiBuilderFontRegistry.registerWearDeviceFonts() {
  val manifest = runCatching { parseVendoredFontManifest(readManifestText()) }.getOrNull() ?: return
  registerWearDeviceFonts(manifest) { file -> readFontBytes(file) }
}

/** [registerWearDeviceFonts] from a manifest and a way to read its files, for any host. */
internal inline fun registerWearDeviceFonts(
  manifest: VendoredFontManifest,
  read: (file: String) -> ByteArray,
) {
  if (WearFonts.isRegistered(WearFonts.RobotoFlex)) return
  val file =
    manifest.families.firstOrNull { it.name == WEAR_DEVICE_FONT_FAMILY }?.fonts?.firstOrNull()
      ?: return
  runCatching { WearFonts.register(WearFonts.RobotoFlex, read(file.file)) }
}

/** The vendored family that is Wear's `roboto-flex`: the same face, as a variable font. */
internal const val WEAR_DEVICE_FONT_FAMILY = "Roboto Flex"

/**
 * Register Wear's face from what this platform bundles, synchronously, if it bundles one.
 *
 * The JVM (previews, tests, the desktop app) reads it off the classpath the first time a Wear
 * surface composes. The browser has nothing to read synchronously, so its hosts call
 * [registerWearDeviceFonts] before they start composing, and this does nothing there.
 */
internal expect fun ensureBundledWearDeviceFonts()

/** A font from bytes, which only the platform text stack knows how to build. */
internal expect fun platformFont(identity: String, data: ByteArray, weight: FontWeight): Font

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
    content = content,
  )
}
