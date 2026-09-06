package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every design kept in `docs/design/fixtures/ui-builder/designs/` still works.
 *
 * A file there is a design the repository owns — today the editor's own screens, drawn in the
 * editor's own catalog. Each one is replayed, hashed, validated and exported here so that a catalog
 * change which breaks a committed design, or a design which drifts from the catalog it pins, fails
 * the build rather than the next person who opens it. The directory is read from disk rather than
 * from a hand-kept list, so a file cannot be added without being checked, and the previews in
 * `DesignFixturePreviews.kt` are cross-checked the same way so a file cannot be added without being
 * drawn.
 */
class DesignFixturesTest {
  private val json = Json { encodeDefaults = true }
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val fixtures: List<File> =
    checkNotNull(designsDirectory().listFiles { file -> file.extension == "json" }) {
        "no designs directory at ${designsDirectory()}"
      }
      .sortedBy { it.name }

  @Test
  fun `the directory holds at least the editor's own screens`() {
    assertTrue(fixtures.isNotEmpty(), "no design fixtures under ${designsDirectory()}")
  }

  @Test
  fun `each fixture is named after the design it creates`() {
    fixtures.forEach { file ->
      assertEquals(file.nameWithoutExtension, file.fixture().string("designId"), file.name)
    }
  }

  @Test
  fun `each fixture replays to the hash it declares`() {
    fixtures.forEach { file ->
      val fixture = file.fixture()
      val document = UiBuilderReducer.replay(fixture).document
      val canonical = canonicalJson(json.parseToJsonElement(json.encodeToString(document)))
      assertEquals(
        fixture.string("expectedDocumentHash"),
        canonical.sha256(),
        "${file.name}: the replayed document does not match expectedDocumentHash — " +
          "regenerate it with scripts/ui-builder/design-sync.mjs, or replay-candidate.mjs",
      )
    }
  }

  @Test
  fun `each fixture validates against the catalog it pins`() {
    fixtures.forEach { file ->
      val document = UiBuilderReducer.replay(file.fixture()).document
      assertEquals(
        emptyList(),
        CapabilityValidator(catalog).validate(document).issues,
        "${file.name} no longer validates",
      )
    }
  }

  @Test
  fun `and therefore each exports as Compose`() {
    fixtures.forEach { file ->
      val document = UiBuilderReducer.replay(file.fixture()).document
      val source = CapabilityComposeCodeExporter.export(document, catalog).requireSource()
      assertTrue("@Composable" in source, "${file.name} exported no composable")
    }
  }

  @Test
  fun `each fixture has a preview that draws it`() {
    val previews =
      File(
          System.getProperty("uiBuilderProjectDir") ?: ".",
          "src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/DesignFixturePreviews.kt",
        )
        .readText()
    fixtures.forEach { file ->
      assertTrue(
        "DesignFixture(\"${file.nameWithoutExtension}\")" in previews,
        "${file.name} has no @Preview in DesignFixturePreviews.kt",
      )
    }
  }

  private fun File.fixture(): JsonObject = Json.parseToJsonElement(readText()).jsonObject

  private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

  private fun designsDirectory(): File =
    File(
      System.getProperty("uiBuilderDesignFixturesDir")
        ?: "../docs/design/fixtures/ui-builder/designs"
    )

  private fun resource(path: String): String =
    checkNotNull(javaClass.getResource(path)) { "missing resource $path" }.readText()
}

private fun String.sha256(): String =
  MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).joinToString("") {
    "%02x".format(it)
  }
