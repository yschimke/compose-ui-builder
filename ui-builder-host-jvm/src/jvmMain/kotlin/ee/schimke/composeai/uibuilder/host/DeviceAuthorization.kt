package ee.schimke.composeai.uibuilder.host

import java.awt.Desktop
import java.net.URI
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Puts the page where a person approves this device's grant in front of them. */
fun interface ApprovalPresenter {
  /**
   * Shows [approvalUrl]. Returns false when it could not, so the flow can name the URL in what it
   * reports instead of waiting ten minutes on a page nobody saw.
   */
  fun present(approvalUrl: URI): Boolean
}

/**
 * Opens the approval page in the system browser.
 *
 * `Desktop.isDesktopSupported()` alone is not enough: a Linux desktop without a registered browser
 * handler supports `Desktop` but not `BROWSE`, and `browse` then throws. Where the page cannot be
 * opened the URL goes to stderr, which is where a desktop launched from a terminal is looked at.
 */
object SystemBrowserApprovalPresenter : ApprovalPresenter {
  override fun present(approvalUrl: URI): Boolean {
    val opened = runCatching {
      Desktop.isDesktopSupported() &&
        Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
        Desktop.getDesktop().browse(approvalUrl).let { true }
    }
      .getOrDefault(false)
    if (!opened) System.err.println("Open $approvalUrl in a browser to approve Compose UI Builder.")
    return opened
  }
}

/**
 * The server's device-grant flow, shared by every JVM host that signs in to a preview server.
 *
 * Asks for a grant labelled [label] with [capabilities], shows the approval page through
 * [presenter], then polls until the person approves, the server declines, or [timeout] passes. Both
 * the approval page and the poll target must be on [origin]: a server answer cannot send this
 * process, or the person's browser, anywhere else.
 *
 * [post] sends an unauthenticated JSON POST; the caller owns the HTTP client and its timeouts.
 */
internal suspend fun authorizeDevice(
  origin: URI,
  label: String,
  capabilities: List<String>,
  presenter: ApprovalPresenter,
  post: suspend (target: URI, body: String) -> HttpResponse<String>,
  timeout: Duration = Duration.ofMinutes(10),
): String {
  val opened =
    post(
      origin.resolve("/agent-access/request"),
      deviceJson.encodeToString(
        DeviceGrantRequest.serializer(),
        DeviceGrantRequest(label, capabilities = capabilities),
      ),
    )
  require(opened.statusCode() in 200..299) {
    "server cannot start UI Builder authentication (HTTP ${opened.statusCode()})"
  }
  val response = deviceJson.parseToJsonElement(opened.body()).jsonObject
  val approvalUrl =
    sameOriginTarget(origin, URI(response.requiredString("approveUrl")), "approval page")
  val pollUrl = sameOriginTarget(origin, URI(response.requiredString("pollUrl")), "poll URL")
  val requestId = response.requiredString("requestId")
  val deviceSecret = response.requiredString("deviceSecret")
  var retryAfterSeconds = response.requiredLong("pollIntervalSeconds")
  val presented = presenter.present(approvalUrl)
  val deadline = System.nanoTime() + timeout.toNanos()
  while (System.nanoTime() < deadline) {
    val remainingMillis = (deadline - System.nanoTime()).coerceAtLeast(0) / 1_000_000
    val requestedMillis =
      retryAfterSeconds.coerceAtLeast(1).let { seconds ->
        if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
      }
    delay(minOf(requestedMillis, remainingMillis))
    if (System.nanoTime() >= deadline) break
    val poll =
      post(
        pollUrl,
        deviceJson.encodeToString(
          DevicePollRequest.serializer(),
          DevicePollRequest(requestId, deviceSecret),
        ),
      )
    require(poll.statusCode() in 200..299) { "server stopped the authentication request" }
    val answer = deviceJson.parseToJsonElement(poll.body()).jsonObject
    when (answer.requiredString("status")) {
      "approved" -> return answer.requiredString("token")
      "pending" -> Unit
      else ->
        error(
          "server declined UI Builder authentication" +
            answer["message"]?.jsonPrimitive?.contentOrNull?.let { ": $it" }.orEmpty()
        )
    }
    retryAfterSeconds = answer["retryAfterSeconds"]?.jsonPrimitive?.longOrNull ?: retryAfterSeconds
  }
  error(
    "UI Builder authentication timed out" +
      if (presented) "" else "; approve this device at $approvalUrl"
  )
}

private val deviceJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

@Serializable
private data class DeviceGrantRequest(
  val label: String,
  val scope: String = "live",
  val capabilities: List<String>,
)

@Serializable private data class DevicePollRequest(val requestId: String, val deviceSecret: String)

private fun JsonObject.requiredString(name: String): String =
  get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    ?: error("authentication response has no $name")

private fun JsonObject.requiredLong(name: String): Long =
  get(name)?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
    ?: error("authentication response has no positive $name")
