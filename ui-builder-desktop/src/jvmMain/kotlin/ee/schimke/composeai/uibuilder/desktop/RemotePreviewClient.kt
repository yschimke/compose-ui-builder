package ee.schimke.composeai.uibuilder.desktop

import androidx.compose.ui.graphics.toComposeImageBitmap
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderNativeRender
import ee.schimke.composeai.uibuilder.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.toDesignDocumentV1
import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.skia.Image

/**
 * Remote, one-shot native render for a locally held design.
 *
 * The server's native-preview route deliberately renders saved designs only. To keep the Desktop
 * application's local workspace local, this client creates a short-lived private design, renders
 * it, then deletes it. Authentication is requested only on the first Preview action, through the
 * server's device-grant page; the token never appears in a command line, URL, or persisted file.
 */
internal class RemotePreviewClient(server: String) {
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
    val opened =
      postJson(
        "/agent-access/request",
        """{"label":"Compose UI Builder Desktop","scope":"live","capabilities":["ui-builder-write","ui-builder-export"]}""",
        authenticated = false,
      )
    require(opened.statusCode() in 200..299) {
      "preview server cannot start authentication (HTTP ${opened.statusCode()})"
    }
    val response = json.parseToJsonElement(opened.body()).jsonObject
    val approvalUrl = response.requiredString("approveUrl")
    val requestId = response.requiredString("requestId")
    val deviceSecret = response.requiredString("deviceSecret")
    var retryAfterSeconds = response.requiredLong("pollIntervalSeconds")
    Desktop.getDesktop().browse(URI(approvalUrl))
    val deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos()
    while (System.nanoTime() < deadline) {
      val remainingMillis = (deadline - System.nanoTime()).coerceAtLeast(0) / 1_000_000
      val requestedMillis =
        retryAfterSeconds.coerceAtLeast(1).let { seconds ->
          if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
        }
      delay(minOf(requestedMillis, remainingMillis))
      if (System.nanoTime() >= deadline) break
      val poll =
        postJson(
          sameOriginTarget(base, URI(response.requiredString("pollUrl"))),
          json.encodeToString(DevicePollRequest(requestId, deviceSecret)),
          authenticated = false,
        )
      require(poll.statusCode() in 200..299) { "preview server stopped the authentication request" }
      val answer = json.parseToJsonElement(poll.body()).jsonObject
      when (answer.requiredString("status")) {
        "approved" -> {
          token = answer.requiredString("token")
          return
        }
        "pending" -> Unit
        else ->
          error(
            "preview server declined authentication: ${answer["message"]?.jsonPrimitive?.contentOrNull}"
          )
      }
      retryAfterSeconds =
        answer["retryAfterSeconds"]?.jsonPrimitive?.longOrNull ?: retryAfterSeconds
    }
    error("preview server authentication timed out")
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

  private fun postJson(target: URI, body: String, authenticated: Boolean): HttpResponse<String> =
    request(target, "POST", body, authenticated = authenticated)

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

  private fun kotlinx.serialization.json.JsonObject.requiredString(name: String): String =
    get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
      ?: error("preview server authentication response has no $name")

  private fun kotlinx.serialization.json.JsonObject.requiredLong(name: String): Long =
    get(name)?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
      ?: error("preview server authentication response has no positive $name")
}

internal fun validatedServerOrigin(server: String): URI {
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

internal fun sameOriginTarget(origin: URI, target: URI): URI {
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
    "preview server authentication poll URL must be same-origin"
  }
  return resolved
}

@Serializable
private data class NativePreviewResult(
  val imageBase64: String? = null,
  val compileError: String? = null,
)

@Serializable
private data class DevicePollRequest(
  val requestId: String,
  val deviceSecret: String,
  val waitSeconds: Int = 30,
)

@Serializable private data class NativePreviewRefusal(val reasons: List<String> = emptyList())
