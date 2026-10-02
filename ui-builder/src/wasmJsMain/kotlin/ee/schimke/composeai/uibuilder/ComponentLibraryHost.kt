@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.EditorLibraryComponent
import ee.schimke.composeai.uibuilder.editor.EditorLibraryPublication
import ee.schimke.composeai.uibuilder.editor.EditorLibraryPublishResult
import ee.schimke.composeai.uibuilder.editor.EditorLibrarySymbol
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The project's shared component library, as the palette reads it.
 *
 * `GET /api/ui-builder/v1/component-library` lists what the projects this host serves publish, and
 * `…/{system}/{componentId}` answers one symbol with its body and digest; both authenticate like
 * every other builder call. A host that serves no library answers an error, and the palette then
 * simply has no shelf for it — the reason [loadComponentDrift] tolerates failure too.
 */
internal suspend fun loadComponentLibrary(): List<EditorLibraryComponent> =
  try {
    val listing = libraryJson.parseToJsonElement(fetchText(COMPONENT_LIBRARY_PATH)).jsonObject
    listing["components"]?.jsonArray.orEmpty().mapNotNull { row ->
      val entry = row as? JsonObject ?: return@mapNotNull null
      fun field(name: String) = entry[name]?.jsonPrimitive?.contentOrNull
      EditorLibraryComponent(
        system = field("system") ?: return@mapNotNull null,
        componentId = field("componentId") ?: return@mapNotNull null,
        paletteId = field("paletteId") ?: return@mapNotNull null,
        title = field("title") ?: field("componentId") ?: return@mapNotNull null,
        description = field("description"),
      )
    }
  } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    emptyList()
  }

/** One published symbol, fetched when its tile is pressed; null when it cannot be read. */
internal suspend fun loadLibrarySymbol(component: EditorLibraryComponent): EditorLibrarySymbol? =
  try {
    val body =
      libraryJson
        .parseToJsonElement(
          fetchText(
            "$COMPONENT_LIBRARY_PATH/${encodeUrlComponent(component.system)}/" +
              encodeUrlComponent(component.componentId)
          )
        )
        .jsonObject
    val nodes =
      body["nodes"]?.jsonObject.orEmpty().mapValues { (_, node) ->
        libraryJson.decodeFromJsonElement<UiBuilderNode>(node)
      }
    EditorLibrarySymbol(
      component = component,
      digest = body["digest"]?.jsonPrimitive?.contentOrNull ?: return null,
      declaration = body["component"] as? JsonObject ?: return null,
      nodes = nodes,
    )
  } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    null
  }

/**
 * Publishes one of a design's components to this host's project library.
 *
 * `PUT …/{system}/{componentId}` with the one-component document a project would commit, plus the
 * digest of the version it replaces when there is one. The server keeps it beside its design state
 * in the same `ui-builder/components/` layout a repository holds, and answers the symbol it now
 * serves — whose digest the design then records — or a refusal worth showing as it is: a `409` when
 * somebody published in between, or when the project has committed that component itself.
 */
internal suspend fun publishLibraryComponent(
  publication: EditorLibraryPublication
): EditorLibraryPublishResult {
  val body =
    JsonObject(
      buildMap {
        put("title", JsonPrimitive(publication.title))
        publication.description?.let { put("description", JsonPrimitive(it)) }
        publication.replacesDigest?.let { put("replacesDigest", JsonPrimitive(it)) }
        put(
          "document",
          libraryJson.encodeToJsonElement(UiBuilderDocument.serializer(), publication.document),
        )
      }
    )
  val url =
    sameOriginRequestUrl(
      "$COMPONENT_LIBRARY_PATH/${encodeUrlComponent(publication.system)}/" +
        encodeUrlComponent(publication.componentId)
    )
  val answer = awaitLibraryText(putJsonPromise(url, body.toString()))
  val status = answer.substringBefore('\n').toIntOrNull() ?: 0
  val reply = runCatching {
    libraryJson.parseToJsonElement(answer.substringAfter('\n', "")).jsonObject
  }
    .getOrNull()
  fun field(name: String) = reply?.get(name)?.jsonPrimitive?.contentOrNull
  return when {
    status in 200..299 ->
      field("digest")?.let(EditorLibraryPublishResult::Published)
        ?: EditorLibraryPublishResult.Refused("The project library answered without a version")
    status == 401 || status == 403 ->
      EditorLibraryPublishResult.Refused("Publishing to the project library needs write access")
    status == 404 || status == 405 ->
      EditorLibraryPublishResult.Refused("This host does not take published components")
    else ->
      EditorLibraryPublishResult.Refused(
        field("error")?.replaceFirstChar(Char::uppercaseChar)
          ?: "The project library refused it (HTTP $status)"
      )
  }
}

private suspend fun awaitLibraryText(promise: Promise<JsString>): String =
  suspendCancellableCoroutine { continuation ->
    promise
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

/** `status + '\n' + body`, since a refusal's text is the part worth showing. */
@JsFun(
  """(url, body) => fetch(url, {
      method: 'PUT',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body,
    }).then((response) => response.text().then((text) => response.status + '\n' + text))"""
)
private external fun putJsonPromise(url: String, body: String): Promise<JsString>

private const val COMPONENT_LIBRARY_PATH = "/api/ui-builder/v1/component-library"

private val libraryJson = Json { ignoreUnknownKeys = true }
