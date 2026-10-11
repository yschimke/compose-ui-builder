package ee.schimke.composeai.uibuilder.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One same-origin `GET`, kept an interface so the JVM can test this without a browser. */
fun interface MaterialSymbolsTransport {
  suspend fun get(url: String): MaterialSymbolsHttpResponse
}

data class MaterialSymbolsHttpResponse(val statusCode: Int, val body: String)

/** The axis position an icon is drawn at; the defaults are the face's own. */
data class MaterialSymbolsAxes(
  val fill: Float = 0f,
  val weight: Float = 400f,
  val grade: Float = 0f,
  val opticalSize: Float = 24f,
) {
  /** Only the axes that differ from the default travel, so the common request is the short one. */
  internal fun query(): String = buildString {
    if (fill != 0f) append("&FILL=").append(number(fill))
    if (weight != 400f) append("&wght=").append(number(weight))
    if (grade != 0f) append("&GRAD=").append(number(grade))
    if (opticalSize != 24f) append("&opsz=").append(number(opticalSize))
  }

  private fun number(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
}

/** What a design or a grid row names: one icon, one style, one axis position. */
data class MaterialSymbolsKey(
  val name: String,
  val style: String = "outlined",
  val axes: MaterialSymbolsAxes = MaterialSymbolsAxes(),
  val autoMirror: Boolean = false,
)

@Serializable
private data class NamesPayload(
  val style: String,
  /** The pin these names came from; echoed on outline requests so cached URLs expire with it. */
  val pin: String = "",
  val names: List<String>,
)

@Serializable
private data class OutlinesPayload(
  val style: String,
  val icons: Map<String, String> = emptyMap(),
  val missing: List<String> = emptyList(),
)

/**
 * Fetches the outlines of the icons about to be drawn, and nothing else (~550 bytes each instead of
 * a 4.8 MB font). See
 * [`UI_BUILDER_MATERIAL_SYMBOLS.md`](../../../../../../../../../docs/design/UI_BUILDER_MATERIAL_SYMBOLS.md).
 *
 * Everything fetched is cached (outlines are immutable per pin), including misses, so unknown names
 * aren't re-requested every frame.
 */
class MaterialSymbolsClient(
  private val transport: MaterialSymbolsTransport,
  private val basePath: String = "/api/icons",
  private val credentials: String = "",
) {

  private val pathData = mutableMapOf<MaterialSymbolsKey, String>()
  private val vectors = mutableMapOf<MaterialSymbolsKey, ImageVector>()
  private val absent = mutableSetOf<MaterialSymbolsKey>()
  private val names = mutableMapOf<String, List<String>>()
  private val pins = mutableMapOf<String, String>()

  /** The names a face carries, fetched once and filtered locally from then on. */
  suspend fun names(style: String = "outlined"): List<String> {
    names[style]?.let {
      return it
    }
    val response = transport.get(url("$basePath/$style/names"))
    if (response.statusCode != 200) return emptyList()
    val payload = JSON.decodeFromString(NamesPayload.serializer(), response.body)
    if (payload.pin.isNotEmpty()) pins[style] = payload.pin
    return payload.names.also { names[style] = it }
  }

  /**
   * Makes sure every key in [keys] is resolved, in one request per style and axis position, so a
   * grid page is one round trip.
   */
  suspend fun prefetch(keys: Collection<MaterialSymbolsKey>) {
    keys
      .filterNot { it in pathData || it in absent }
      .groupBy { it.style to it.axes }
      .forEach { (group, wanted) ->
        val (style, axes) = group
        // Chunked to the server's limit: an oversized request is refused wholesale and caches
        // nothing.
        wanted
          .distinctBy { it.name }
          .chunked(MAXIMUM_NAMES_PER_REQUEST)
          .forEach { batch ->
            // One `names=` per icon: the server decodes before splitting, so a comma in a stale
            // name would break the batch.
            val requested = batch.joinToString("&names=") { encode(it.name) }
            val pin = pins[style]?.let { "&v=$it" }.orEmpty()
            val response =
              transport.get(url("$basePath/$style?names=$requested${axes.query()}$pin"))
            if (response.statusCode != 200) return@forEach
            val payload = JSON.decodeFromString(OutlinesPayload.serializer(), response.body)
            val byName = batch.associateBy { it.name }
            wanted
              .filter { it.name in byName }
              .forEach { key ->
                val path = payload.icons[key.name]
                if (path == null) absent += key else pathData[key] = path
              }
          }
      }
  }

  /**
   * The vector for [key], or null when not fetched yet or not carried by the host. Non-suspending,
   * for composition; callers draw a placeholder and [prefetch].
   */
  fun vector(key: MaterialSymbolsKey): ImageVector? {
    vectors[key]?.let {
      return it
    }
    val path = pathData[key] ?: return null
    return imageVector(key.name, path, key.autoMirror).also { vectors[key] = it }
  }

  /** True once [key] has been asked for and the host said it has no such icon. */
  fun isAbsent(key: MaterialSymbolsKey): Boolean = key in absent

  private fun url(path: String): String =
    if (credentials.isEmpty()) path
    else if ('?' in path) "$path&$credentials" else "$path?$credentials"

  companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    /** Matches `MaterialSymbolsIcons.MAXIMUM_NAMES` on the server, which enforces it. */
    const val MAXIMUM_NAMES_PER_REQUEST = 256

    /**
     * Percent-encodes a name for the query string. Pinned names are `[a-z0-9_]`, but stale names
     * from older designs may contain `&`, `#` or `,`.
     */
    internal fun encode(name: String): String = buildString {
      name.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xFF
        val character = value.toChar()
        if (
          character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '_' ||
            character == '-' ||
            character == '.' ||
            character == '~'
        ) {
          append(character)
        } else {
          append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
      }
    }

    private const val HEX = "0123456789ABCDEF"

    /**
     * Builds the vector a served outline describes. The viewport is the font's 960 units per em (no
     * scaling at 24dp); the fill is black because `Icon` tints it, and a path without a brush draws
     * nothing.
     */
    fun imageVector(name: String, pathData: String, autoMirror: Boolean = false): ImageVector =
      ImageVector.Builder(
          name = name,
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 960f,
          viewportHeight = 960f,
          autoMirror = autoMirror,
        )
        .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()
  }
}
