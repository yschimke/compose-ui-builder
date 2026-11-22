package ee.schimke.composeai.uibuilder.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The files a catalog publishes for the editor to use itself, served at
 * `/api/ui-builder/v1/catalogs/{system}/files/{path...}`. Kobento's component definitions, theme
 * and compiler are such files: the editor expands and compiles a design with them, in the browser.
 *
 * A catalog's [sources] entry is where it publishes, the directory holding its `catalog.json` index
 * (whose `uiBuilderFile` names its `ui-builder.json`). Only a path that `ui-builder.json` lists in
 * `statusSemantics.files` is served, so a source is never a general fetch proxy. The files are
 * published in the open, so serving them needs no session.
 *
 * Each answer is kept for [ttlMillis], so the editors opening a design do not each reach the
 * catalog's host; a catalog republished is picked up when that runs out.
 */
internal class CatalogFiles(
  private val sources: Map<String, String>,
  private val client: HttpClient,
  private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
  private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  /** A file found, or why not. */
  sealed interface Answer {
    class Found(val bytes: ByteArray, val contentType: ContentType) : Answer

    /** The catalog is known, but does not publish [path] (or it could not be read). */
    data object NotFound : Answer
  }

  private class Cached(val value: ByteArray?, val at: Long)

  private val cache = ConcurrentHashMap<String, Cached>()

  /** Whether [system] is one this host serves files for; any other is left to the upstream. */
  fun serves(system: String): Boolean = system in sources

  suspend fun file(system: String, path: String): Answer {
    val base = sources[system] ?: return Answer.NotFound
    if (!isSafePath(path)) return Answer.NotFound
    if (path !in listedFiles(base)) return Answer.NotFound
    val bytes = fetch("$base/$path") ?: return Answer.NotFound
    return Answer.Found(bytes, contentTypeOf(path))
  }

  /** The paths `ui-builder.json` lists in `statusSemantics.files`, empty when it cannot be read. */
  private suspend fun listedFiles(base: String): Set<String> {
    val index = fetch("$base/catalog.json")?.let(::parseObject) ?: return emptySet()
    val uiBuilderFile =
      (index["uiBuilderFile"] as? JsonPrimitive)?.contentOrNull?.takeIf(::isSafePath)
        ?: return emptySet()
    val catalog = fetch("$base/$uiBuilderFile")?.let(::parseObject) ?: return emptySet()
    val files = (catalog["statusSemantics"] as? JsonObject)?.get("files") as? JsonArray
    return files.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
  }

  private suspend fun fetch(url: String): ByteArray? {
    val now = clock()
    cache[url]
      ?.takeIf { now - it.at < ttlMillis }
      ?.let { cached ->
        return cached.value
      }
    val bytes =
      withTimeoutOrNull(timeoutMillis) {
        runCatching {
          val response = client.get(url)
          if (response.status == HttpStatusCode.OK) response.bodyAsBytes() else null
        }
          .getOrNull()
      }
    cache[url] = Cached(bytes, now)
    return bytes
  }

  private fun parseObject(bytes: ByteArray): JsonObject? = runCatching {
    json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
  }
    .getOrNull()

  companion object {
    const val DEFAULT_TIMEOUT_MILLIS: Long = 10_000
    const val DEFAULT_TTL_MILLIS: Long = 5 * 60_000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `UI_BUILDER_CATALOG_SOURCES`: comma-separated `system=url` pairs, each url the directory a
     * catalog publishes to, e.g.
     * `kobento=https://raw.githubusercontent.com/yschimke/kobento-catalog-out/design-artifacts/kobento`.
     */
    fun parseSources(value: String?): Map<String, String> =
      value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).associate { entry ->
        val system = entry.substringBefore('=').trim()
        val url = entry.substringAfter('=', "").trim().trimEnd('/')
        require(system.isNotEmpty() && (url.startsWith("https://") || url.startsWith("http://"))) {
          "UI_BUILDER_CATALOG_SOURCES entries are system=url, got '$entry'"
        }
        system to url
      }

    /** A relative path with no `..`, `.` or empty segment, no scheme and no backslash. */
    fun isSafePath(path: String): Boolean =
      path.isNotEmpty() &&
        !path.startsWith("/") &&
        '\\' !in path &&
        ':' !in path &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    fun contentTypeOf(path: String): ContentType =
      when (path.substringAfterLast('.', "").lowercase()) {
        "json" -> ContentType.Application.Json
        "mjs",
        "js" -> ContentType.Text.JavaScript
        else -> ContentType.Application.OctetStream
      }
  }
}
