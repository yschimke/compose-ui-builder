package ee.schimke.composeai.uibuilder.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UiBuilderServerTest {
  private val servers = mutableListOf<EmbeddedServer<*, *>>()
  private val client =
    HttpClient(OkHttp) {
      followRedirects = false
      expectSuccess = false
      install(ClientWebSockets)
    }

  @AfterTest
  fun stop() {
    client.close()
    servers.forEach { it.stop(0, 0) }
  }

  private suspend fun start(module: Application.() -> Unit): Int {
    val server = embeddedServer(CIO, port = 0, host = "127.0.0.1", module = module)
    servers += server
    server.start(wait = false)
    return server.engine.resolvedConnectors().first().port
  }

  /** A stand-in for the legacy `SERVE_ROLE=ui-builder` container. */
  private suspend fun legacy(ready: Boolean = true): Int = start {
    install(WebSockets)
    routing {
      get("/readyz") {
        if (ready) call.respondText("ready")
        else call.respondText("warming", status = HttpStatusCode.ServiceUnavailable)
      }
      get("/version") {
        call.respondText(
          """{"schema":"compose-preview-serve/version/v1","version":"3.192.0","public":true}""",
          ContentType.Application.Json,
        )
      }
      get("/echo/{rest...}") {
        call.response.header(HttpHeaders.SetCookie, "a=1; Path=/")
        call.response.header(HttpHeaders.SetCookie, "b=2; Path=/")
        call.respondText(
          "uri=${call.request.uri} host=${call.request.header(HttpHeaders.Host)} " +
            "cookie=${call.request.header(HttpHeaders.Cookie)} " +
            "xff=${call.request.header(HttpHeaders.XForwardedFor)}",
        )
      }
      get("/") { call.respondText("root on ${call.request.header(HttpHeaders.Host)}") }
      route("/echo/{rest...}", HttpMethod.Head) {
        handle {
          call.response.header("X-Echo", "head")
          call.respondText("")
        }
      }
      get("/redirect") {
        call.response.header(HttpHeaders.Location, "/ui-builder/elsewhere")
        call.respondText("", status = HttpStatusCode.Found)
      }
      post("/api/ui-builder/v1/designs") {
        call.respondText(
          "created ${call.request.header(HttpHeaders.ContentType)} ${call.receiveText()}",
          status = HttpStatusCode.Created,
        )
      }
      webSocket("/api/ui-builder/v1/designs/{designId}/updates") {
        send(Frame.Text("hello ${call.parameters["designId"]}"))
        for (frame in incoming) {
          if (frame is Frame.Text) send(Frame.Text("echo ${frame.readText()}"))
        }
      }
    }
  }

  private suspend fun host(
    upstreamPort: Int,
    version: String = "3.150.0",
    probeTimeoutMillis: Long = UiBuilderServerConfig.DEFAULT_PROBE_TIMEOUT_MILLIS,
  ): Int {
    val config =
      UiBuilderServerConfig(
        port = 0,
        upstream = Url("http://127.0.0.1:$upstreamPort"),
        version = version,
        probeTimeoutMillis = probeTimeoutMillis,
      )
    val proxyClient = upstreamClient()
    return start { uiBuilderServer(config, proxyClient) }
  }

  @Test
  fun `deployable endpoints answer here and report the upstream build`() = runBlocking {
    val port = host(legacy())
    assertEquals("ok", client.get("http://127.0.0.1:$port/healthz").bodyAsText())
    assertEquals(HttpStatusCode.OK, client.get("http://127.0.0.1:$port/readyz").status)
    val body = client.get("http://127.0.0.1:$port/version").bodyAsText()
    val version = Json.parseToJsonElement(body).jsonObject
    assertEquals(UiBuilderServerVersion.SCHEMA, version["schema"]!!.jsonPrimitive.content)
    assertEquals("ui-builder", version["component"]!!.jsonPrimitive.content)
    assertEquals("3.150.0", version["version"]!!.jsonPrimitive.content)
    assertEquals("3.192.0", version["upstreamVersion"]!!.jsonPrimitive.content)
    // Exactly one `"version":` key, for deploy checks that read it with a regex.
    assertEquals(1, Regex("\"version\":").findAll(body).count())
  }

  @Test
  fun `not ready while the upstream is not`() = runBlocking {
    val port = host(legacy(ready = false))
    assertEquals(
      HttpStatusCode.ServiceUnavailable,
      client.get("http://127.0.0.1:$port/readyz").status,
    )
  }

  @Test
  fun `a stuck upstream bounds the deploy probes`() = runBlocking {
    // Accepts the connection and never answers.
    val stuck = start {
      routing {
        get("/readyz") { kotlinx.coroutines.awaitCancellation() }
        get("/version") { kotlinx.coroutines.awaitCancellation() }
      }
    }
    val port = host(stuck, probeTimeoutMillis = 300)
    val started = System.nanoTime()
    assertEquals(
      HttpStatusCode.ServiceUnavailable,
      client.get("http://127.0.0.1:$port/readyz").status,
    )
    val version =
      Json.parseToJsonElement(client.get("http://127.0.0.1:$port/version").bodyAsText()).jsonObject
    assertEquals("3.150.0", version["version"]!!.jsonPrimitive.content)
    assertEquals("null", version["upstreamVersion"].toString())
    val elapsedMillis = (System.nanoTime() - started) / 1_000_000
    assertTrue(elapsedMillis < 5_000, "probes took ${elapsedMillis}ms")
  }

  @Test
  fun `an unreachable upstream is a 502 and not ready`() = runBlocking {
    // A port nothing listens on.
    val dead = java.net.ServerSocket(0).use { it.localPort }
    val port = host(dead)
    assertEquals(HttpStatusCode.BadGateway, client.get("http://127.0.0.1:$port/anything").status)
    assertEquals(
      HttpStatusCode.ServiceUnavailable,
      client.get("http://127.0.0.1:$port/readyz").status,
    )
    val version =
      Json.parseToJsonElement(client.get("http://127.0.0.1:$port/version").bodyAsText()).jsonObject
    assertEquals("null", version["upstreamVersion"].toString())
  }

  @Test
  fun `forwards path, query, host, cookies and forwarded-for unchanged`() = runBlocking {
    val port = host(legacy())
    val response =
      client.get("http://127.0.0.1:$port/echo/a%20b/c?x=1&y=%2F") {
        header(HttpHeaders.Host, "ui.coo.ee")
        header(HttpHeaders.Cookie, "cp_gh_session=s1")
        header(HttpHeaders.XForwardedFor, "203.0.113.7")
      }
    assertEquals(
      "uri=/echo/a%20b/c?x=1&y=%2F host=ui.coo.ee cookie=cp_gh_session=s1 xff=203.0.113.7",
      response.bodyAsText(),
    )
    assertEquals(
      listOf("a=1; Path=/", "b=2; Path=/"),
      response.headers.getAll(HttpHeaders.SetCookie),
    )
  }

  @Test
  fun `the root is forwarded too`() = runBlocking {
    val port = host(legacy())
    val response = client.get("http://127.0.0.1:$port/") { header(HttpHeaders.Host, "ui.coo.ee") }
    assertEquals("root on ui.coo.ee", response.bodyAsText())
  }

  @Test
  fun `redirects are passed back, not followed`() = runBlocking {
    val port = host(legacy())
    val response = client.get("http://127.0.0.1:$port/redirect")
    assertEquals(HttpStatusCode.Found, response.status)
    assertEquals("/ui-builder/elsewhere", response.headers[HttpHeaders.Location])
  }

  @Test
  fun `a request body and its content type reach the upstream`() = runBlocking {
    val port = host(legacy())
    val response =
      client.post("http://127.0.0.1:$port/api/ui-builder/v1/designs") {
        contentType(ContentType.Application.Json)
        setBody("""{"catalog":"wear-m3"}""")
      }
    assertEquals(HttpStatusCode.Created, response.status)
    assertEquals("created application/json {\"catalog\":\"wear-m3\"}", response.bodyAsText())
  }

  @Test
  fun `HEAD carries headers and no body`() = runBlocking {
    val port = host(legacy())
    val response = client.head("http://127.0.0.1:$port/echo/x")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("head", response.headers["X-Echo"])
    assertEquals("", response.bodyAsText())
  }

  @Test
  fun `a websocket is bridged frame for frame`() = runBlocking {
    val port = host(legacy())
    val received = mutableListOf<String>()
    client.webSocket("ws://127.0.0.1:$port/api/ui-builder/v1/designs/d1/updates") {
      received += (incoming.receive() as Frame.Text).readText()
      send(Frame.Text("ping"))
      received += (incoming.receive() as Frame.Text).readText()
    }
    assertEquals(listOf("hello d1", "echo ping"), received)
  }

  @Test
  fun `config needs an upstream and defaults the port`() {
    val config =
      UiBuilderServerConfig.fromEnvironment(
        mapOf("UI_BUILDER_UPSTREAM_URL" to "http://ui-builder:8080/"),
      )
    assertEquals(8080, config.port)
    assertEquals("http://ui-builder:8080", config.upstream.toString())
    val missing = runCatching {
      UiBuilderServerConfig.fromEnvironment(emptyMap())
    }
      .exceptionOrNull()
    assertTrue(missing is IllegalArgumentException)
    assertNull(
      runCatching {
        UiBuilderServerConfig.fromEnvironment(
          mapOf("UI_BUILDER_UPSTREAM_URL" to "http://x", "PORT" to "9"),
        )
      }
        .exceptionOrNull(),
    )
  }
}
