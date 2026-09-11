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
 * Fetches the outlines of the icons about to be drawn, and nothing else.
 *
 * The editor holds no font. A design names about five icons and a grid page shows eighty, so asking
 * for ~550 bytes each is three orders of magnitude less than the 4.8 MB face they came from — which
 * is what lets the Wasm bundle drop the 17.8 MB of generated vectors without putting a download in
 * its place. See
 * [`UI_BUILDER_MATERIAL_SYMBOLS.md`](../../../../../../../../../docs/design/UI_BUILDER_MATERIAL_SYMBOLS.md).
 *
 * Everything fetched is kept: outlines are immutable for a given pin, so the second request for an
 * icon never happens, and scrolling back up a grid costs nothing. A name the host does not carry is
 * remembered too — re-asking for an icon that does not exist, once per frame, is the failure mode
 * this avoids.
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
   * Makes sure every key in [keys] is resolved, in one request per style and axis position.
   *
   * Callers hand this the page they are about to compose and then read [vector] for each row, so a
   * grid scroll is one round trip rather than eighty.
   */
  suspend fun prefetch(keys: Collection<MaterialSymbolsKey>) {
    keys
      .filterNot { it in pathData || it in absent }
      .groupBy { it.style to it.axes }
      .forEach { (group, wanted) ->
        val (style, axes) = group
        // Chunked to the server's own limit. One oversized request is refused wholesale, and since
        // a refusal caches nothing, an un-chunked prefetch of a large selection would resolve
        // nothing and repeat the same rejected call on every pass.
        wanted
          .distinctBy { it.name }
          .chunked(MAXIMUM_NAMES_PER_REQUEST)
          .forEach { batch ->
            // One `names=` per icon, not one comma-separated list: the server decodes the query
            // before it splits, so a comma inside a stale name would arrive as a delimiter no
            // matter how it was encoded and take the rest of the batch with it.
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
   * The vector for [key], or null when it has not been fetched yet or the host does not carry it.
   *
   * Deliberately not a suspending call: this is read from a composition, where the answer has to be
   * whatever is known now. A caller draws a placeholder for null and [prefetch]es the page.
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

    /**
     * Matches `MaterialSymbolsIcons.MAXIMUM_NAMES` on the server.
     *
     * Duplicated rather than shared because the two sides are separate modules; the server is the
     * one that enforces it, and this only has to avoid writing a request it knows will be refused.
     */
    const val MAXIMUM_NAMES_PER_REQUEST = 256

    /**
     * Percent-encodes a name for the query string.
     *
     * Every name the pinned face carries is `[a-z0-9_]`, but a *stale* one from an older design is
     * whatever was saved, and a `&` or `#` in it would rewrite the request rather than come back
     * under `missing` — taking the rest of its batch down with it. A comma is encoded too, though
     * the server no longer splits on one: the encoding is what makes the value unambiguous, and the
     * repeated parameter is what makes the boundary survive decoding.
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
     * Builds the vector a served outline describes.
     *
     * The viewport is the font's 960 units per em, so a 24 dp icon needs no scaling, and the fill
     * is black because `Icon` tints it — a path added without a brush is an outline that draws
     * nothing at all, which compiles and ships and shows an empty box.
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
