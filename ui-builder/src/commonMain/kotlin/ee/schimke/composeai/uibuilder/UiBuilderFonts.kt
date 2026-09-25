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
 * A family that fails to load is left out rather than retried: the renderer's answer to an
 * unresolved name is the default face, which is the right answer for a font the host cannot fetch
 * too, and retrying on every recomposition would turn one missing file into a request loop.
 *
 * @param readManifest the manifest's text; failures leave the registry with no families.
 * @param readFont a manifest file's bytes, by the name the manifest gives it.
 */
class UiBuilderFontRegistry(
  private val scope: CoroutineScope,
  private val readManifest: suspend () -> String,
  private val readFont: suspend (file: String) -> ByteArray,
) {
  /** The families that can be asked for; empty until the manifest has loaded. */
  var families: List<VendoredFontFamily> by mutableStateOf(emptyList())
    private set

  /** Families that have finished loading, by name. */
  val loaded: SnapshotStateMap<String, FontFamily> = mutableStateMapOf()

  private val requested = mutableSetOf<String>()
  private var manifestLoaded = false

  init {
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
    if (manifestLoaded) load(name)
  }

  private fun load(name: String) {
    val family = families.firstOrNull { it.name == name } ?: return
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
  }
}

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
