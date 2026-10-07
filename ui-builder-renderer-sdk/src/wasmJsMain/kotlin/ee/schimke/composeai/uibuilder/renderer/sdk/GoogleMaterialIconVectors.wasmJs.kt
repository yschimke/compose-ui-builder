@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.js.Promise
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await

/**
 * [MaterialIconData] files fetched from `icons/` beside the bundle, one file per first use.
 *
 * A loaded file is kept as path text; an icon becomes an `ImageVector` only when something draws
 * it, so a picker page that loads a whole file builds 80 vectors, not 180. Concurrent requests for
 * icons in the same file share one fetch. A failed fetch (offline, a host that does not serve the
 * directory) answers null and is forgotten, so the next request retries.
 */
actual object GoogleMaterialIconVectors {
  private val vectors = HashMap<String, ImageVector>()
  private val data = HashMap<String, MaterialIconData.IconVector>()
  private val shards = HashMap<Int, CompletableDeferred<Unit>>()

  actual fun cached(key: String): ImageVector? =
    vectors[key]
      ?: data.remove(key)?.let { MaterialIconData.decode(it).also { v -> vectors[key] = v } }

  actual suspend fun load(key: String): ImageVector? {
    cached(key)?.let {
      return it
    }
    val shard = MaterialIconData.shardOf(key) ?: return null
    shards[shard]?.let {
      it.await()
      return cached(key)
    }
    val loading = CompletableDeferred<Unit>()
    shards[shard] = loading
    try {
      val text =
        fetchIconShard(iconDataBaseUrl() + MaterialIconData.shardFileName(shard))
          .await<JsString>()
          .toString()
      MaterialIconData.readShard(text).forEach { (k, v) -> if (k !in vectors) data[k] = v }
    } catch (failure: Throwable) {
      shards.remove(shard)
      if (failure is kotlinx.coroutines.CancellationException) {
        loading.complete(Unit)
        throw failure
      }
      println("compose-ui-builder: Material icon file $shard could not be loaded: $failure")
    }
    loading.complete(Unit)
    return cached(key)
  }
}

@JsFun(
  """(url) => fetch(url).then((response) => {
    if (!response.ok) throw new Error('HTTP ' + response.status + ' for ' + url);
    return response.text();
  })"""
)
private external fun fetchIconShard(url: String): Promise<JsString>

/**
 * `icons/` beside the bundle's own module, as the fonts are found: a host may serve the page at
 * `/ui-builder/<design>` and the bundle under an immutable prefix, and the renderer runtime is a
 * different module in a different directory. A page with neither resolves against itself.
 */
@JsFun(
  """() => {
    const module = document.querySelector(
      'script[type="module"][src$="uiBuilder.mjs"], script[type="module"][src$="uiBuilderRenderer.mjs"]');
    return module ? new URL('icons/', module.src).href : 'icons/';
  }"""
)
private external fun iconDataBaseUrl(): String
