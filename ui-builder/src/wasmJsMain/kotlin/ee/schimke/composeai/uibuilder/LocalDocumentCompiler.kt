package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.CatalogDocumentFormat
import ee.schimke.composeai.uibuilder.export.LocalCompilation
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A catalog's documents compiled in the browser, where its format says how ([LocalCompilation]):
 * the files it reads and its compiler module come from the catalog, served by the host at
 * [catalogFilePath], loaded once and kept. The canvas and Preview then draw an edit with no round
 * trip.
 *
 * [compile] answers null whenever it cannot (a file or the compiler not served, the format refusing
 * the design, the compiler failing), and the caller asks the host's service as before. So a host
 * that serves none of this, or a design using what only the service can compile, is exactly as it
 * was.
 */
internal class LocalDocumentCompiler(
  private val catalogSystemId: String,
  private val compilation: LocalCompilation,
) {
  private val loading = Mutex()
  private var loaded: Loaded? = null
  private var unavailable = false

  private class Loaded(val files: Map<String, String>, val compiler: JsAny)

  /**
   * Files fetched as an expansion asked for them ([LocalCompilation.onDemand]); null if not served.
   */
  private val fetched = HashMap<String, String?>()

  /** [document] compiled, as base64 `.rc` bytes, or null to ask the host's service. */
  suspend fun compile(document: UiBuilderDocument): String? {
    val ready = load() ?: return null
    var expanded: CatalogDocumentFormat.Result = CatalogDocumentFormat.Result.Refused(emptyList())
    // Each pass fetches what the last one read and lacked (an icon shard), then expands again; a
    // design draws from a handful of shards, so a few passes answer it.
    for (pass in 1..MAX_PASSES) {
      val missing = mutableSetOf<String>()
      val files =
        ReadingMap(ready.files + fetched.mapNotNullValues()) { path ->
          if (path in compilation.onDemand && path !in fetched) missing += path
        }
      // A served file the format cannot read (malformed, or another shape) throws: that, too, is
      // the service's to compile.
      expanded =
        try {
          compilation.expand(document, files)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Throwable) {
          return null
        }
      if (expanded !is CatalogDocumentFormat.Result.Refused || missing.isEmpty()) break
      loading.withLock {
        for (path in missing) {
          fetched[path] =
            try {
              fetchText(catalogFilePath(catalogSystemId, path))
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (_: Throwable) {
              null
            }
        }
      }
    }
    val json =
      when (val result = expanded) {
        is CatalogDocumentFormat.Result.Emitted -> result.content
        is CatalogDocumentFormat.Result.Refused -> return null
      }
    return runCatching { compileToBase64(ready.compiler, json) }.getOrNull()
  }

  private suspend fun load(): Loaded? = loading.withLock {
    loaded?.let {
      return@withLock it
    }
    if (unavailable) return@withLock null
    try {
      val files =
        compilation.files.associateWith { fetchText(catalogFilePath(catalogSystemId, it)) }
      val compiler = importModule(catalogFilePath(catalogSystemId, compilation.compiler)).await()
      Loaded(files, compiler).also { loaded = it }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      // Not served here (an older host, a catalog without the files): the service compiles.
      unavailable = true
      null
    }
  }
}

/** How many times [LocalDocumentCompiler.compile] expands, fetching what each pass lacked. */
private const val MAX_PASSES = 4

private fun <K, V : Any> Map<K, V?>.mapNotNullValues(): Map<K, V> = buildMap {
  this@mapNotNullValues.forEach { (key, value) -> if (value != null) put(key, value) }
}

/** [files], telling [onMiss] each path read that it does not hold. */
private class ReadingMap(
  private val files: Map<String, String>,
  private val onMiss: (String) -> Unit,
) : AbstractMap<String, String>() {
  override val entries: Set<Map.Entry<String, String>>
    get() = files.entries

  override fun get(key: String): String? = files[key].also { if (it == null) onMiss(key) }

  override fun containsKey(key: String): Boolean = files.containsKey(key)
}

/** The host route serving a file [catalogSystemId]'s `ui-builder.json` declares, by its path. */
internal fun catalogFilePath(catalogSystemId: String, path: String): String =
  "/api/ui-builder/v1/catalogs/${encodeUriComponent(catalogSystemId)}/files/" +
    path.split('/').joinToString("/") { encodeUriComponent(it) }

private suspend fun Promise<JsAny>.await(): JsAny = suspendCancellableCoroutine { continuation ->
  then { value ->
    if (continuation.isActive) continuation.resume(value)
    null
  }
    .catch { error ->
      if (continuation.isActive) {
        continuation.resumeWithException(IllegalStateException(error.toString()))
      }
      null
    }
}

@JsFun("(url) => import(new URL(url, globalThis.location.href).href)")
private external fun importModule(url: String): Promise<JsAny>

/** The module's `convert(text)` bytes, as base64, the form a preview carries a document in. */
@JsFun(
  """(module, text) => {
    const bytes = module.convert(text);
    let binary = '';
    for (let offset = 0; offset < bytes.length; offset += 8192) {
      binary += String.fromCharCode.apply(null, Array.from(bytes.subarray(offset, offset + 8192)));
    }
    return btoa(binary);
  }""",
)
private external fun compileToBase64(module: JsAny, text: String): String
