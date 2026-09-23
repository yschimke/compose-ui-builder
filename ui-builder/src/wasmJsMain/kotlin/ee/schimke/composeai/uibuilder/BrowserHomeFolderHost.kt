@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared server-side organization for designs listed on the builder home screen. */
internal class BrowserHomeFolderHost {
  suspend fun load(): Map<String, String>? {
    val response = request("GET", HOME_FOLDERS_PATH, null)
    if (response.status != 200) return null
    return runCatching {
      HOME_FOLDER_JSON.decodeFromString(FolderListWire.serializer(), response.body).folders
    }
      .getOrNull()
  }

  suspend fun move(designId: String, folder: String?): Map<String, String>? {
    val response =
      if (folder == null) {
        request("DELETE", "$HOME_FOLDERS_PATH/${encodeUriComponent(designId)}", null)
      } else {
        request(
          "PUT",
          "$HOME_FOLDERS_PATH/${encodeUriComponent(designId)}",
          HOME_FOLDER_JSON.encodeToString(FolderMoveWire.serializer(), FolderMoveWire(folder)),
        )
      }
    if (response.status != 200) return null
    return runCatching {
      HOME_FOLDER_JSON.decodeFromString(FolderListWire.serializer(), response.body).folders
    }
      .getOrNull()
  }

  private suspend fun request(method: String, path: String, body: String?): FolderHttpResponse {
    val encoded = suspendCancellableCoroutine { continuation ->
      homeFolderFetch(method, sameOriginRequestUrl(path), body.orEmpty(), body != null)
        .then { value ->
          if (continuation.isActive) continuation.resume(value.toString())
          null
        }
        .catch { error ->
          if (continuation.isActive) {
            continuation.resumeWithException(IllegalStateException(error.toString()))
          }
          null
        }
    }
    return HOME_FOLDER_JSON.decodeFromString(FolderHttpResponse.serializer(), encoded)
  }
}

@Serializable private data class FolderListWire(val folders: Map<String, String> = emptyMap())

@Serializable private data class FolderMoveWire(val folder: String)

@Serializable private data class FolderHttpResponse(val status: Int, val body: String)

private const val HOME_FOLDERS_PATH = "/api/ui-builder/v1/home-folders"
private val HOME_FOLDER_JSON = Json { ignoreUnknownKeys = true }

@JsFun(
  """(method, url, body, hasBody) => fetch(url, {
    method,
    headers: hasBody ? { 'content-type': 'application/json' } : {},
    body: hasBody ? body : undefined,
  }).then((response) => response.text().then((text) => JSON.stringify({
    status: response.status,
    body: text,
  })))"""
)
private external fun homeFolderFetch(
  method: String,
  url: String,
  body: String,
  hasBody: Boolean,
): Promise<JsString>
