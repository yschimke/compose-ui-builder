package ee.schimke.composeai.uibuilder.host

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PublishedCatalogStoreTest {
  private val captures: Path =
    Path.of(checkNotNull(System.getProperty(PublishedCatalogStore.CATALOG_DIR_PROPERTY)))
  private val fromCaptures = CatalogFileFetcher.directory(captures)
  private val offline = CatalogFileFetcher { throw IOException("no route to host") }

  @Test
  fun `a catalog is composed from what it publishes`() {
    val load =
      PublishedCatalogStore(Files.createTempDirectory("cache"), fromCaptures).load("wear-m3")

    val loaded = assertIs<PublishedCatalogStore.Load.Loaded>(load)
    assertFalse(loaded.fromCache)
    assertEquals(listOf("wear-screen", "wear-list"), loaded.templates.ids)
    assertTrue(loaded.capabilityJson.contains("\"wear-m3\""))
  }

  @Test
  fun `a catalog fetched once opens again without a network`() {
    val cache = Files.createTempDirectory("cache")
    assertIs<PublishedCatalogStore.Load.Loaded>(
      PublishedCatalogStore(cache, fromCaptures).load("remote-widgets")
    )

    val again = PublishedCatalogStore(cache, offline).load("remote-widgets")

    val loaded = assertIs<PublishedCatalogStore.Load.Loaded>(again)
    assertTrue(loaded.fromCache)
    assertEquals(
      setOf("hello-widget", "counter-widget", "launcher-widget-2x1"),
      loaded.templates.ids.toSet(),
    )
  }

  @Test
  fun `a catalog never fetched and unreachable says so instead of opening a copy`() {
    val load = PublishedCatalogStore(Files.createTempDirectory("cache"), offline).load("m3-catalog")

    val unavailable = assertIs<PublishedCatalogStore.Load.Unavailable>(load)
    assertTrue(unavailable.reason.contains("has not been fetched yet"), unavailable.reason)
    assertTrue(unavailable.reason.contains("no route to host"), unavailable.reason)
  }

  @Test
  fun `a catalog that publishes nothing is not mistaken for an offline machine`() {
    val load =
      PublishedCatalogStore(Files.createTempDirectory("cache"), { null }).load("glimmer-catalog")

    val unavailable = assertIs<PublishedCatalogStore.Load.Unavailable>(load)
    assertTrue(unavailable.reason.contains("publishes no ui-builder.json"), unavailable.reason)
  }

  @Test
  fun `an unknown catalog is refused by name`() {
    val load = PublishedCatalogStore(Files.createTempDirectory("cache"), fromCaptures).load("nope")

    assertIs<PublishedCatalogStore.Load.Unavailable>(load)
  }
}
