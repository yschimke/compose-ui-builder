package ee.schimke.composeai.uibuilder.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class DeviceAuthorizationTest {
  private val origin = URI("https://preview.example")

  @Test
  fun `approves through the presented same-origin page`() = runBlocking {
    val presented = mutableListOf<URI>()
    val posted = mutableListOf<URI>()
    val token =
      authorizeDevice(
        origin = origin,
        label = "test",
        capabilities = listOf("ui-builder-read"),
        presenter = {
          presented += it
          true
        },
        post = { target, _ ->
          posted += target
          if (target.path == "/agent-access/request") response(grant())
          else response("""{"status":"approved","token":"t0k"}""")
        },
      )

    assertEquals("t0k", token)
    assertEquals(listOf(URI("https://preview.example/agent-access/approve?id=1")), presented)
    assertEquals(URI("https://preview.example/agent-access/poll"), posted.last())
  }

  @Test
  fun `an approval page on another origin is refused before anything opens it`() = runBlocking {
    val presented = mutableListOf<URI>()
    val failure =
      assertFailsWith<IllegalArgumentException> {
        authorizeDevice(
          origin = origin,
          label = "test",
          capabilities = emptyList(),
          presenter = {
            presented += it
            true
          },
          post = { _, _ -> response(grant(approveUrl = "https://attacker.invalid/approve")) },
        )
      }

    assertContains(failure.message.orEmpty(), "approval page")
    assertEquals(emptyList(), presented)
  }

  @Test
  fun `a page nobody could open is named when the grant times out`() = runBlocking {
    val failure =
      assertFailsWith<IllegalStateException> {
        authorizeDevice(
          origin = origin,
          label = "test",
          capabilities = emptyList(),
          presenter = { false },
          post = { target, _ ->
            if (target.path == "/agent-access/request") response(grant())
            else response("""{"status":"pending"}""")
          },
          timeout = Duration.ofMillis(50),
        )
      }

    assertContains(failure.message.orEmpty(), "https://preview.example/agent-access/approve?id=1")
  }

  private fun grant(approveUrl: String = "/agent-access/approve?id=1") =
    """{"approveUrl":"$approveUrl","pollUrl":"/agent-access/poll","requestId":"r",""" +
      """"deviceSecret":"s","pollIntervalSeconds":1}"""

  private fun response(body: String): HttpResponse<String> =
    object : HttpResponse<String> {
      override fun statusCode() = 200

      override fun request(): HttpRequest = HttpRequest.newBuilder(origin).build()

      override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

      override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

      override fun body() = body

      override fun sslSession(): Optional<SSLSession> = Optional.empty()

      override fun uri(): URI = origin

      override fun version() = HttpClient.Version.HTTP_1_1
    }
}
