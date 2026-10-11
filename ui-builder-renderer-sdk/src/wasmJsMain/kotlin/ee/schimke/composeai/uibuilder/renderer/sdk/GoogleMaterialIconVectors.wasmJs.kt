@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.js.Promise
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await

/**
 * [MaterialIconData] files fetched from `icons/` beside the bundle, one file per first use. Files
 * are kept as path text and vectors built on draw; concurrent requests share a fetch; a failed
 * fetch answers null and is retried next time.
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
 * `icons/` beside the bundle's own module (as fonts are found), since the page and bundle may be
 * served from different paths. Falls back to the page's own location.
 */
@JsFun(
  """() => {
    const module = document.querySelector(
      'script[type="module"][src$="uiBuilder.mjs"], script[type="module"][src$="uiBuilderRenderer.mjs"]');
    return module ? new URL('icons/', module.src).href : 'icons/';
  }"""
)
private external fun iconDataBaseUrl(): String
