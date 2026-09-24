package ee.schimke.composeai.uibuilder.host

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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class CatalogOverrideTest {
  private val packaged =
    checkNotNull(javaClass.getResource("/wear-m3-capabilities-v1.json")).readText()

  @Test
  fun `a session authors against the file instead of the packaged catalog`() {
    val file = write(packaged.replace("\"Wear screen · ScreenScaffold\"", "\"Scaffold from disk\""))

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
  fun `an override for another catalog leaves the packaged one in place`() {
    val override = CatalogOverride.read(write(packaged))

    OfflineUiBuilderSession(
        Files.createTempDirectory("override-other"),
        catalogSystemId = "m3-catalog",
        catalogOverride = override,
      )
      .use { session -> assertEquals("m3-catalog", session.catalog.benchmark.catalogSystemId) }
  }

  @Test
  fun `a catalog this host does not package is refused, naming the ones it does`() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        CatalogOverride.read(write(packaged.replace("\"wear-m3\"", "\"glimmer\"")))
      }
    assertContains(failure.message.orEmpty(), "glimmer")
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
      // Created against the packaged catalog, so pinned to its revision.
      OfflineUiBuilderSession(workspace, catalogSystemId = "wear-m3").use { session ->
        withTimeout(10.seconds) { session.snapshot.filterNotNull().first() }
      }
      val regenerated =
        CatalogOverride.read(
          write(packaged.replace("\"wear-screen-scaffold-v1\"", "\"wear-screen-scaffold-v2\""))
        )

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
            "wear-screen-scaffold-v2",
            pin["catalogRevision"]?.jsonPrimitive?.contentOrNull,
          )
        }
    }
}
