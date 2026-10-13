package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CatalogOverrideTest {
  private val published by lazy { OfflineCatalog.WEAR_M3.capabilityJson() }

  @Test
  fun `a session authors against the file instead of the published catalog`() {
    val original =
      CapabilityCatalogParser.parse(published)
        .componentsById
        .getValue("wear-m3/screen-scaffold")
        .displayName
    val file = write(published.replace("\"$original\"", "\"Scaffold from disk\""))

    val override = CatalogOverride.read(file)

    assertEquals("wear-m3", override.systemId)
    OfflineUiBuilderSession(
        Files.createTempDirectory("override-session"),
        catalogSystemId = "wear-m3",
        catalogOverride = override,
      )
      .use { session ->
        assertEquals(
          "Scaffold from disk",
          session.catalog.componentsById.getValue("wear-m3/screen-scaffold").displayName,
        )
      }
  }

  @Test
  fun `an override for another catalog leaves the published one in place`() {
    val override = CatalogOverride.read(write(published))

    OfflineUiBuilderSession(
        Files.createTempDirectory("override-other"),
        catalogSystemId = "m3-catalog",
        catalogOverride = override,
      )
      .use { session -> assertEquals("m3-catalog", session.catalog.benchmark.catalogSystemId) }
  }

  @Test
  fun `an unknown catalog is refused, naming the ones this host supports`() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        CatalogOverride.read(write(published.replace("\"wear-m3\"", "\"unknown-catalog\"")))
      }
    assertContains(failure.message.orEmpty(), "unknown-catalog")
    assertContains(failure.message.orEmpty(), "m3-catalog")
  }

  @Test
  fun `a file that is not a capability catalog is refused`() {
    assertFailsWith<IllegalArgumentException> { CatalogOverride.read(write("{\"hello\": 1}")) }
  }

  private fun write(text: String): Path =
    Files.createTempFile("capabilities", ".json").also { Files.writeString(it, text) }

  @Test
  fun `a stored design follows a regenerated catalog's revision instead of failing its pin`() =
    runBlocking {
      val workspace = Files.createTempDirectory("override-repin")
      // Created against the published catalog, so pinned to its revision.
      OfflineUiBuilderSession(workspace, catalogSystemId = "wear-m3").use { session ->
        withTimeout(10.seconds) { session.snapshot.filterNotNull().first() }
      }
      val revision =
        Json.parseToJsonElement(published)
          .jsonObject
          .getValue("benchmark")
          .jsonObject
          .getValue("catalogRevision")
          .jsonPrimitive
          .content
      val regenerated =
        CatalogOverride.read(write(published.replace("\"$revision\"", "\"revised-catalog\"")))

      OfflineUiBuilderSession(workspace, catalogSystemId = "wear-m3", catalogOverride = regenerated)
        .use { session ->
          val pin =
            withTimeout(10.seconds) { session.snapshot.filterNotNull().first() }
              .snapshot
              .state
              .document
              .toUiBuilderDocument()
              .catalogPin
          assertEquals(
            "revised-catalog",
            pin["catalogRevision"]?.jsonPrimitive?.contentOrNull,
          )
        }
    }
}
