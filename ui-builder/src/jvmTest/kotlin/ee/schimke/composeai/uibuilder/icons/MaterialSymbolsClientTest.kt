package ee.schimke.composeai.uibuilder.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What the editor asks the host for, and how often.
 *
 * The design's whole claim is that a client downloads bytes proportional to what is on screen
 * rather than to the catalog, and that only holds if the client batches a page into one request and
 * never asks twice. Both are properties of this class, so both are pinned here.
 */
class MaterialSymbolsClientTest {

  private class RecordingTransport(private val answer: (String) -> MaterialSymbolsHttpResponse) :
    MaterialSymbolsTransport {
    val urls = mutableListOf<String>()

    override suspend fun get(url: String): MaterialSymbolsHttpResponse {
      urls += url
      return answer(url)
    }
  }

  private fun outlines(vararg icons: Pair<String, String>, missing: List<String> = emptyList()) =
    MaterialSymbolsHttpResponse(
      200,
      """{"style":"outlined","axes":{},"icons":{${
        icons.joinToString(",") { "\"${it.first}\":\"${it.second}\"" }
      }},"missing":[${missing.joinToString(",") { "\"$it\"" }}]}""",
    )

  private val square = "M120 120L840 120L840 840L120 840Z"

  @Test
  fun `a page is one request, and a second pass is none`() = runBlocking {
    val transport = RecordingTransport { outlines("search" to square, "home" to square) }
    val client = MaterialSymbolsClient(transport)
    val keys = listOf(MaterialSymbolsKey("search"), MaterialSymbolsKey("home"))

    client.prefetch(keys)
    assertEquals(1, transport.urls.size, "a page should batch: ${transport.urls}")
    assertTrue(transport.urls.single().contains("names=search&names=home"))

    client.prefetch(keys)
    assertEquals(1, transport.urls.size, "already-known icons should not be refetched")
  }

  @Test
  fun `only non-default axes travel`() = runBlocking {
    val transport = RecordingTransport { outlines("search" to square) }
    val client = MaterialSymbolsClient(transport)

    client.prefetch(listOf(MaterialSymbolsKey("search")))
    assertEquals("/api/icons/outlined?names=search", transport.urls.single())

    client.prefetch(
      listOf(MaterialSymbolsKey("search", axes = MaterialSymbolsAxes(weight = 300f, fill = 1f)))
    )
    val customised = transport.urls.last()
    assertTrue(customised.contains("FILL=1"), customised)
    assertTrue(customised.contains("wght=300"), customised)
    assertTrue(!customised.contains("GRAD"), "grade was default and should not travel: $customised")
  }

  @Test
  fun `one request per axis position, not per icon`() = runBlocking {
    val transport = RecordingTransport { outlines("search" to square, "home" to square) }
    val client = MaterialSymbolsClient(transport)
    client.prefetch(
      listOf(
        MaterialSymbolsKey("search"),
        MaterialSymbolsKey("home"),
        MaterialSymbolsKey("search", axes = MaterialSymbolsAxes(weight = 700f)),
      )
    )
    assertEquals(
      2,
      transport.urls.size,
      "expected one request per axis position: ${transport.urls}",
    )
  }

  @Test
  fun `an icon the host does not carry is remembered as absent`() = runBlocking {
    val transport = RecordingTransport { outlines(missing = listOf("no_such_icon")) }
    val client = MaterialSymbolsClient(transport)
    val key = MaterialSymbolsKey("no_such_icon")

    client.prefetch(listOf(key))
    assertTrue(client.isAbsent(key))
    assertNull(client.vector(key))

    // Re-asking every frame for something the host has already said it does not have is the
    // failure this remembers away.
    client.prefetch(listOf(key))
    assertEquals(1, transport.urls.size)
  }

  @Test
  fun `a vector is built once and reused`() = runBlocking {
    val transport = RecordingTransport { outlines("search" to square) }
    val client = MaterialSymbolsClient(transport)
    val key = MaterialSymbolsKey("search")
    assertNull(client.vector(key), "nothing should be drawn before it is fetched")

    client.prefetch(listOf(key))
    val first = assertNotNull(client.vector(key))
    assertTrue(first === client.vector(key), "the vector should be cached, not rebuilt")
    assertEquals(960f, first.viewportWidth)
    assertEquals(960f, first.viewportHeight)
    assertEquals(24f, first.defaultWidth.value)
  }

  @Test
  fun `auto-mirroring reaches the vector`() {
    assertTrue(
      MaterialSymbolsClient.imageVector("arrow_back", square, autoMirror = true).autoMirror
    )
    assertTrue(!MaterialSymbolsClient.imageVector("search", square).autoMirror)
  }

  @Test
  fun `names are fetched once`() = runBlocking {
    val transport = RecordingTransport {
      MaterialSymbolsHttpResponse(
        200,
        """{"style":"outlined","pin":"abc123","names":["home","search"]}""",
      )
    }
    val client = MaterialSymbolsClient(transport)
    assertEquals(listOf("home", "search"), client.names())
    assertEquals(listOf("home", "search"), client.names())
    assertEquals(1, transport.urls.size, "the name list is immutable for a pin")
  }

  @Test
  fun `a large selection is split to the server's limit`() = runBlocking {
    val transport = RecordingTransport { outlines("search" to square) }
    val client = MaterialSymbolsClient(transport)
    val keys = (1..600).map { MaterialSymbolsKey("icon_$it") }
    client.prefetch(keys)
    assertEquals(3, transport.urls.size, "600 names should split into three requests")
    transport.urls.forEach {
      val count = it.split("names=").size - 1
      assertTrue(
        count <= MaterialSymbolsClient.MAXIMUM_NAMES_PER_REQUEST,
        "a batch of $count would be refused wholesale",
      )
    }
  }

  @Test
  fun `a name with a comma stays one name`() = runBlocking {
    val transport = RecordingTransport { outlines(missing = listOf("a,b")) }
    val client = MaterialSymbolsClient(transport)
    client.prefetch(listOf(MaterialSymbolsKey("a,b"), MaterialSymbolsKey("home")))
    // Two names, two parameters: the server counts parameters, so the comma cannot inflate the
    // batch past the limit or invent an icon called "b".
    assertEquals("/api/icons/outlined?names=a%2Cb&names=home", transport.urls.single())
  }

  @Test
  fun `a name that could rewrite the query is encoded`() {
    assertEquals("search", MaterialSymbolsClient.encode("search"))
    assertEquals("bad%26FILL%3Dx", MaterialSymbolsClient.encode("bad&FILL=x"))
    assertEquals("a%2Cb", MaterialSymbolsClient.encode("a,b"))
    assertEquals("%C3%A9", MaterialSymbolsClient.encode("\u00e9"))
  }

  @Test
  fun `the pin is echoed once it is known`() = runBlocking {
    val transport = RecordingTransport { url ->
      if (url.contains("/names"))
        MaterialSymbolsHttpResponse(
          200,
          """{"style":"outlined","pin":"abc123","names":["search"]}""",
        )
      else outlines("search" to square)
    }
    val client = MaterialSymbolsClient(transport)
    client.prefetch(listOf(MaterialSymbolsKey("search")))
    assertTrue(!transport.urls.last().contains("v="), "no pin known yet: ${transport.urls.last()}")

    client.names()
    client.prefetch(listOf(MaterialSymbolsKey("home")))
    assertTrue(transport.urls.last().contains("v=abc123"), transport.urls.last())
  }

  @Test
  fun `a refused request leaves nothing cached`() = runBlocking {
    val transport = RecordingTransport { MaterialSymbolsHttpResponse(503, "cold cache") }
    val client = MaterialSymbolsClient(transport)
    val key = MaterialSymbolsKey("search")
    client.prefetch(listOf(key))
    assertNull(client.vector(key))
    assertTrue(
      !client.isAbsent(key),
      "a host that could not answer has not said the icon is absent",
    )
    // So a later attempt, once the host is warm, still asks.
    client.prefetch(listOf(key))
    assertEquals(2, transport.urls.size)
  }

  @Test
  fun `credentials are carried onto both routes`() = runBlocking {
    val transport = RecordingTransport { outlines("search" to square) }
    val client = MaterialSymbolsClient(transport, credentials = "token=abc")
    client.prefetch(listOf(MaterialSymbolsKey("search")))
    assertEquals("/api/icons/outlined?names=search&token=abc", transport.urls.single())
  }
}
