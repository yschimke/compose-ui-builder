package ee.schimke.composeai.uibuilder.host

import androidx.compose.ui.graphics.toComposeImageBitmap
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeRender
import ee.schimke.composeai.uibuilder.toDesignDocumentV1
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image

/**
 * Remote, one-shot native render for a locally held design.
 *
 * The server's native-preview route deliberately renders saved designs only. To keep the Desktop
 * application's local workspace local, this client creates a short-lived private design, renders
 * it, then deletes it. Authentication is requested only on the first Preview action, through the
 * server's device-grant page; the token never appears in a command line, URL, or persisted file.
 */
internal class RemotePreviewClient(
  server: String,
  private val presenter: ApprovalPresenter = SystemBrowserApprovalPresenter,
) {
  private val base = validatedServerOrigin(server)
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
  private val json = Json { ignoreUnknownKeys = true }
  private var token: String? = null

  suspend fun render(
    document: UiBuilderDocument,
    hostShape: WearWidgetHostShape,
  ): UiBuilderNativeRender =
    withContext(Dispatchers.IO) {
      val remoteId = "desktop-preview-${UUID.randomUUID()}"
      try {
        authorizeIfNeeded()
        create(remoteId, document.copy(id = remoteId))
        renderSaved(remoteId, hostShape)
      } catch (failure: Exception) {
        UiBuilderNativeRender(failure = failure.message ?: "remote preview failed")
      } finally {
        if (token != null) runCatching { delete(remoteId) }
      }
    }

  private suspend fun authorizeIfNeeded() {
    if (token != null) return
    token =
      authorizeDevice(
        origin = base,
        label = "Compose UI Builder Desktop",
        capabilities = listOf("ui-builder-write", "ui-builder-export"),
        presenter = presenter,
        post = { target, body -> request(target, "POST", body, authenticated = false) },
      )
  }

  private fun create(id: String, document: UiBuilderDocument) {
    val response =
      request(
        path = "/api/ui-builder/v1/designs/$id",
        method = "PUT",
        body = json.encodeToString(document.toDesignDocumentV1()),
        headers = mapOf("If-None-Match" to "*"),
      )
    require(response.statusCode() == 201) {
      "preview server could not prepare the design (HTTP ${response.statusCode()})"
    }
  }

  private fun renderSaved(id: String, hostShape: WearWidgetHostShape): UiBuilderNativeRender {
    val response =
      postJson(
        "/api/ui-builder/v1/designs/$id/native-preview",
        """{"hostShape":"${hostShape.id}"}""",
      )
    if (response.statusCode() == 422) {
      val refusal = json.decodeFromString(NativePreviewRefusal.serializer(), response.body())
      return UiBuilderNativeRender(refusals = refusal.reasons)
    }
    require(response.statusCode() == 200) {
      "preview server answered HTTP ${response.statusCode()} while compiling"
    }
    val result = json.decodeFromString(NativePreviewResult.serializer(), response.body())
    result.compileError?.let {
      return UiBuilderNativeRender(failure = it)
    }
    val image = result.imageBase64 ?: return UiBuilderNativeRender()
    return UiBuilderNativeRender(
      image =
        Image.makeFromEncoded(Base64.getDecoder().decode(image.substringAfterLast("base64,")))
          .toComposeImageBitmap()
    )
  }

  private fun delete(id: String) {
    val response =
      request(
        path = "/ui-builder/$id/delete",
        method = "POST",
        body = "confirm=delete",
        headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
      )
    check(response.statusCode() in 200..399) { "preview server could not remove temporary design" }
  }

  private fun postJson(
    path: String,
    body: String,
    authenticated: Boolean = true,
  ): HttpResponse<String> = request(path, "POST", body, authenticated = authenticated)

  private fun request(
    path: String,
    method: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    authenticated: Boolean = true,
  ): HttpResponse<String> = request(base.resolve(path), method, body, headers, authenticated)

  private fun request(
    target: URI,
    method: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    authenticated: Boolean = true,
  ): HttpResponse<String> {
    val request =
      HttpRequest.newBuilder(target)
        .timeout(Duration.ofMinutes(2))
        .header("Accept", "application/json")
        .apply {
          if (headers["Content-Type"] == null) header("Content-Type", "application/json")
          headers.forEach { (name, value) -> header(name, value) }
          if (authenticated) {
            val credential = requireNotNull(token) { "preview authentication was not completed" }
            header("X-Compose-Preview-Token", credential)
            header("Authorization", "Bearer $credential")
          }
        }
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build()
    return http.send(request, HttpResponse.BodyHandlers.ofString())
  }
}

/** [server] as a bare https origin (http only on loopback), or a refusal naming the rule. */
fun validatedServerOrigin(server: String): URI {
  val uri = URI(server.trimEnd('/'))
  val scheme = uri.scheme?.lowercase()
  require(
    !uri.isOpaque &&
      uri.host != null &&
      uri.userInfo == null &&
      uri.query == null &&
      uri.fragment == null &&
      (uri.path.isNullOrEmpty() || uri.path == "/") &&
      (scheme == "https" ||
        (scheme == "http" &&
          uri.host.lowercase().removeSurrounding("[", "]") in
            setOf("localhost", "127.0.0.1", "::1")))
  ) {
    "the remote preview server must be an https origin (http is allowed only on loopback)"
  }
  return URI(scheme, null, uri.host, uri.port, null, null, null)
}

internal fun sameOriginTarget(origin: URI, target: URI, what: String = "poll URL"): URI {
  val resolved = origin.resolve(target)
  fun URI.effectivePort(): Int =
    if (port >= 0) port
    else
      when (scheme?.lowercase()) {
        "http" -> 80
        "https" -> 443
        else -> -1
      }
  require(
    !resolved.isOpaque &&
      resolved.userInfo == null &&
      resolved.fragment == null &&
      resolved.scheme.equals(origin.scheme, ignoreCase = true) &&
      resolved.host.equals(origin.host, ignoreCase = true) &&
      resolved.effectivePort() == origin.effectivePort()
  ) {
    "preview server authentication $what must be same-origin"
  }
  return resolved
}

@Serializable
private data class NativePreviewResult(
  val imageBase64: String? = null,
  val compileError: String? = null,
)

@Serializable private data class NativePreviewRefusal(val reasons: List<String> = emptyList())
