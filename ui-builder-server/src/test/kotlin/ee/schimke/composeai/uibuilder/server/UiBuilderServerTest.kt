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
import io.ktor.server.response.respond
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

  /**
   * A stand-in for the legacy `SERVE_ROLE=ui-builder` container. Like the real one, its `/readyz`
   * never latches (it renders no design-system catalog); `/healthz` answers while it is [alive].
   */
  private suspend fun legacy(alive: Boolean = true): Int = start {
    install(WebSockets)
    routing {
      get("/readyz") { call.respondText("warming", status = HttpStatusCode.ServiceUnavailable) }
      get("/healthz") {
        if (alive) call.respondText("ok")
        else call.respondText("down", status = HttpStatusCode.ServiceUnavailable)
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
          // A HEAD answer for a 42-byte resource: the length, and no body.
          call.respond(
            object : io.ktor.http.content.OutgoingContent.NoContent() {
              override val contentLength: Long = 42
              override val contentType: ContentType = ContentType.Application.OctetStream
            },
          )
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
    upstreamReadyPath: String = UiBuilderServerConfig.DEFAULT_UPSTREAM_READY_PATH,
    catalogSources: Map<String, String> = emptyMap(),
  ): Int {
    val config =
      UiBuilderServerConfig(
        port = 0,
        upstream = Url("http://127.0.0.1:$upstreamPort"),
        version = version,
        probeTimeoutMillis = probeTimeoutMillis,
        upstreamReadyPath = upstreamReadyPath,
        catalogSources = catalogSources,
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
  fun `not ready while the upstream is down`() = runBlocking {
    val port = host(legacy(alive = false))
    assertEquals(
      HttpStatusCode.ServiceUnavailable,
      client.get("http://127.0.0.1:$port/readyz").status,
    )
  }

  @Test
  fun `ready behind an upstream whose own readyz never latches`() = runBlocking {
    // The live case on preview.coo.ee: the ui-builder role serves every page while its /readyz
    // answers 503 forever, so gating on it left this host `warming` for good.
    val upstream = legacy()
    assertEquals(
      HttpStatusCode.ServiceUnavailable,
      client.get("http://127.0.0.1:$upstream/readyz").status,
    )
    assertEquals(HttpStatusCode.OK, client.get("http://127.0.0.1:${host(upstream)}/readyz").status)
    // A deployment whose upstream does latch can still gate on it.
    assertEquals(
      HttpStatusCode.ServiceUnavailable,
      client.get("http://127.0.0.1:${host(upstream, upstreamReadyPath = "/readyz")}/readyz").status,
    )
  }

  @Test
  fun `a stuck upstream bounds the deploy probes`() = runBlocking {
    // Accepts the connection and never answers.
    val stuck = start {
      routing {
        get("/healthz") { kotlinx.coroutines.awaitCancellation() }
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
    assertEquals("42", response.headers[HttpHeaders.ContentLength])
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
  fun `config reads the upstream readiness path`() {
    val base = mapOf("UI_BUILDER_UPSTREAM_URL" to "http://ui-builder:8080")
    assertEquals("/healthz", UiBuilderServerConfig.fromEnvironment(base).upstreamReadyPath)
    assertEquals(
      "/readyz",
      UiBuilderServerConfig.fromEnvironment(base + ("UI_BUILDER_UPSTREAM_READY_PATH" to "/readyz"))
        .upstreamReadyPath,
    )
    // Not a path: ignored rather than glued onto the upstream URL.
    assertEquals(
      "/healthz",
      UiBuilderServerConfig.fromEnvironment(base + ("UI_BUILDER_UPSTREAM_READY_PATH" to "readyz"))
        .upstreamReadyPath,
    )
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

  /**
   * Where a catalog publishes: its index, its `ui-builder.json`, a listed file and one unlisted.
   */
  private suspend fun publishedCatalog(fetched: MutableList<String>): Int = start {
    routing {
      get("/kobento/{path...}") {
        val path = call.parameters.getAll("path").orEmpty().joinToString("/")
        fetched += path
        when (path) {
          "catalog.json" ->
            call.respondText("""{"system":"kobento","uiBuilderFile":"ui-builder.json"}""")
          "ui-builder.json" ->
            call.respondText(
              """{"statusSemantics":{"files":["kobento/definitions.json","kobento/json2rc.mjs"]}}""",
            )
          "kobento/definitions.json" -> call.respondText("""{"components":{}}""")
          "kobento/json2rc.mjs" -> call.respondText("export function convert() {}")
          "kobento/unlisted.json" -> call.respondText("secret")
          else -> call.respondText("missing", status = HttpStatusCode.NotFound)
        }
      }
    }
  }

  @Test
  fun `a catalog's listed files are served here, typed, and nothing else is`() = runBlocking {
    val fetched = mutableListOf<String>()
    val published = publishedCatalog(fetched)
    val port =
      host(legacy(), catalogSources = mapOf("kobento" to "http://127.0.0.1:$published/kobento"))
    val files = "http://127.0.0.1:$port/api/ui-builder/v1/catalogs/kobento/files"
    val definitions = client.get("$files/kobento/definitions.json")
    assertEquals(HttpStatusCode.OK, definitions.status)
    assertEquals("""{"components":{}}""", definitions.bodyAsText())
    assertEquals(
      ContentType.Application.Json,
      definitions.headers[HttpHeaders.ContentType]?.let(ContentType::parse)?.withoutParameters(),
    )
    assertEquals("no-cache", definitions.headers[HttpHeaders.CacheControl])
    val compiler = client.get("$files/kobento/json2rc.mjs")
    assertEquals(
      ContentType.Text.JavaScript,
      compiler.headers[HttpHeaders.ContentType]?.let(ContentType::parse)?.withoutParameters(),
    )
    // Published, but not listed: never fetched, never served.
    assertEquals(HttpStatusCode.NotFound, client.get("$files/kobento/unlisted.json").status)
    assertTrue("kobento/unlisted.json" !in fetched)
    // The index and catalog were read once for all three requests.
    assertEquals(1, fetched.count { it == "catalog.json" })
  }

  @Test
  fun `a catalog with no source here keeps its files route on the upstream`() = runBlocking {
    val port = host(legacy())
    val response = client.get("http://127.0.0.1:$port/api/ui-builder/v1/catalogs/m3/files/a.json")
    // The legacy stand-in has no such route: the upstream's answer, not this host's.
    assertEquals(HttpStatusCode.NotFound, response.status)
    assertTrue(response.bodyAsText() != "not found")
  }

  @Test
  fun `catalog sources parse, and only plain relative paths are safe`() {
    assertEquals(
      mapOf("kobento" to "https://example.com/out/kobento", "m3" to "http://localhost:1/m3"),
      CatalogFiles.parseSources(
        " kobento=https://example.com/out/kobento/ , m3=http://localhost:1/m3",
      ),
    )
    assertEquals(emptyMap(), CatalogFiles.parseSources(null))
    assertTrue(runCatching { CatalogFiles.parseSources("kobento") }.isFailure)
    assertTrue(CatalogFiles.isSafePath("kobento/theme.json"))
    listOf("../x", "a/../b", "/abs", "a//b", "a/./b", "http://x", "a\\b", "").forEach {
      assertTrue(!CatalogFiles.isSafePath(it), it)
    }
  }
}
