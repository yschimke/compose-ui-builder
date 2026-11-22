package ee.schimke.composeai.uibuilder.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a UI-builder host needs to start.
 *
 * @property upstream the legacy compose-preview-server container (`SERVE_ROLE=ui-builder`) that
 *   still answers every route group not yet moved here.
 * @property version this host's release version, reported by `/version`.
 * @property probeTimeoutMillis how long `/readyz` and `/version` wait on the upstream. Proxied
 *   requests have no read timeout (editor updates and exports are long-lived), so the deploy probes
 *   need their own bound: a stuck upstream must read as not ready, not hang the probe.
 * @property upstreamReadyPath the upstream path whose 200 makes this host ready. `/healthz`, not
 *   the upstream's `/readyz`: compose-preview-server's `/readyz` latches only once every design
 *   system catalog has rendered a preview, which its `SERVE_ROLE=ui-builder` container may never do
 *   (preview-coo-ee health-checks that container on `/healthz` for the same reason). Pointed at
 *   `/readyz`, this host stayed `503 warming` behind a container serving every page.
 * @property catalogSources where each catalog this host serves files for publishes; see
 *   [CatalogFiles]. A catalog not named here has its files route forwarded like any other.
 */
internal data class UiBuilderServerConfig(
  val port: Int,
  val upstream: Url,
  val version: String,
  val probeTimeoutMillis: Long = DEFAULT_PROBE_TIMEOUT_MILLIS,
  val upstreamReadyPath: String = DEFAULT_UPSTREAM_READY_PATH,
  val catalogSources: Map<String, String> = emptyMap(),
) {
  companion object {
    const val DEFAULT_PROBE_TIMEOUT_MILLIS: Long = 5_000
    const val DEFAULT_UPSTREAM_READY_PATH: String = "/healthz"

    /**
     * Reads `PORT` (default 8080), `UI_BUILDER_UPSTREAM_URL` (required),
     * `UI_BUILDER_UPSTREAM_READY_PATH` (default `/healthz`) and `UI_BUILDER_CATALOG_SOURCES`
     * (default none; see [CatalogFiles.parseSources]).
     */
    fun fromEnvironment(env: Map<String, String> = System.getenv()): UiBuilderServerConfig {
      val upstream =
        requireNotNull(env["UI_BUILDER_UPSTREAM_URL"]?.takeIf(String::isNotBlank)) {
          "UI_BUILDER_UPSTREAM_URL is required: the compose-preview-server ui-builder container " +
            "that serves the routes this host does not handle yet, e.g. http://ui-builder:8080"
        }
      return UiBuilderServerConfig(
        port = env["PORT"]?.toIntOrNull() ?: 8080,
        upstream = Url(upstream.trimEnd('/')),
        version = releaseVersion(),
        upstreamReadyPath =
          env["UI_BUILDER_UPSTREAM_READY_PATH"]?.takeIf { it.startsWith("/") }
            ?: DEFAULT_UPSTREAM_READY_PATH,
        catalogSources = CatalogFiles.parseSources(env["UI_BUILDER_CATALOG_SOURCES"]),
      )
    }

    /** The version the build stamped into `ui-builder-server.properties`. */
    fun releaseVersion(): String =
      UiBuilderServerConfig::class.java.getResourceAsStream("/ui-builder-server.properties")?.use {
        stream ->
        java.util.Properties().apply { load(stream) }.getProperty("version")
      } ?: "0.0.0-dev"
  }
}

/** `/version`'s body. */
@Serializable
internal data class UiBuilderServerVersion(
  val schema: String = SCHEMA,
  val component: String = COMPONENT,
  val version: String,
  /**
   * The legacy container's `/version` `version`, or null when it did not answer: which server build
   * still serves the routes that have not moved. A flat string on purpose. Deploy checks read
   * `version` with a regex (compose-preview-contracts `DEPLOYMENT_PROTOCOL.md`), so this body must
   * carry no second `"version"` key.
   */
  val upstreamVersion: String? = null,
) {
  companion object {
    const val SCHEMA: String = "compose-ui-builder-server/version/v1"
    const val COMPONENT: String = "ui-builder"
  }
}

/** The proxy's client: no redirects followed, no body decoding, no read timeout for streams. */
internal fun upstreamClient(): HttpClient =
  HttpClient(OkHttp) {
    followRedirects = false
    expectSuccess = false
    install(io.ktor.client.plugins.websocket.WebSockets)
    engine {
      config {
        connectTimeout(30, TimeUnit.SECONDS)
        // Editor updates, comment feeds and exports are long-lived; the caller's own connection
        // decides when they end.
        readTimeout(0, TimeUnit.MILLISECONDS)
        writeTimeout(0, TimeUnit.MILLISECONDS)
        followRedirects(false)
        followSslRedirects(false)
      }
    }
  }

private val json = Json {
  ignoreUnknownKeys = true
  // `schema` and `component` are defaults, and a reader needs both on the wire.
  encodeDefaults = true
}

/**
 * The host: deployable endpoints here, everything else forwarded to
 * [UiBuilderServerConfig.upstream].
 *
 * A route group moves to this repository by being installed in this `routing` block ahead of the
 * catch-alls; the proxy then never sees it.
 */
internal fun Application.uiBuilderServer(config: UiBuilderServerConfig, client: HttpClient) {
  install(WebSockets) { maxFrameSize = Long.MAX_VALUE }
  val proxy = UpstreamProxy(config.upstream, client)
  val readiness =
    UpstreamReadiness(config.upstream, config.upstreamReadyPath, client, config.probeTimeoutMillis)
  val catalogFiles = CatalogFiles(config.catalogSources, client)
  routing {
    // Liveness: this process answers. Not the rollout gate; see `/readyz`.
    get("/healthz") { call.respondText("ok") }
    // Readiness: ready only while the upstream answers, because most routes are still served there.
    // See UiBuilderServerConfig.upstreamReadyPath for why that is its `/healthz` by default.
    get("/readyz") {
      call.response.header(HttpHeaders.CacheControl, "no-store")
      if (readiness.ready()) call.respondText("ready")
      else call.respondText("warming", status = HttpStatusCode.ServiceUnavailable)
    }
    get("/version") {
      call.response.header(HttpHeaders.CacheControl, "no-store")
      val upstreamVersion =
        withTimeoutOrNull(config.probeTimeoutMillis) {
          runCatching {
            val response = client.get("${config.upstream}/version")
            if (response.status != HttpStatusCode.OK) return@runCatching null
            json
              .parseToJsonElement(response.bodyAsText())
              .jsonObject["version"]
              ?.jsonPrimitive
              ?.content
          }
            .getOrNull()
        }
      call.respondText(
        json.encodeToString(
          UiBuilderServerVersion.serializer(),
          UiBuilderServerVersion(version = config.version, upstreamVersion = upstreamVersion),
        ),
        ContentType.Application.Json,
      )
    }
    // A catalog's published files, for the editor to expand and compile with. A catalog this host
    // has no source for is still the upstream's.
    get("/api/ui-builder/v1/catalogs/{system}/files/{path...}") {
      val system = call.parameters["system"].orEmpty()
      if (!catalogFiles.serves(system)) return@get proxy.forward(call)
      val path = call.parameters.getAll("path").orEmpty().joinToString("/")
      call.response.header(HttpHeaders.CacheControl, "no-cache")
      when (val answer = catalogFiles.file(system, path)) {
        is CatalogFiles.Answer.Found -> call.respondBytes(answer.bytes, answer.contentType)
        CatalogFiles.Answer.NotFound ->
          call.respondText("not found", status = HttpStatusCode.NotFound)
      }
    }
    // Everything not handled above, in this order: an upgrade request is a WebSocket for the
    // upstream; any other request is plain HTTP.
    webSocket("{...}") { proxy.forwardWebSocket(this) }
    route("{...}") { handle { proxy.forward(call) } }
  }
}

/** The upstream's readiness path, cached briefly so a polling deploy check does not hammer it. */
private class UpstreamReadiness(
  private val upstream: Url,
  private val path: String,
  private val client: HttpClient,
  private val timeoutMillis: Long,
) {
  @Volatile private var checkedAt = 0L
  @Volatile private var lastReady = false

  suspend fun ready(): Boolean {
    val now = System.currentTimeMillis()
    if (now - checkedAt < CACHE_MILLIS) return lastReady
    lastReady =
      withTimeoutOrNull(timeoutMillis) {
        runCatching { client.get("$upstream$path").status == HttpStatusCode.OK }.getOrDefault(false)
      } ?: false
    checkedAt = now
    return lastReady
  }

  private companion object {
    const val CACHE_MILLIS = 2_000L
  }
}
