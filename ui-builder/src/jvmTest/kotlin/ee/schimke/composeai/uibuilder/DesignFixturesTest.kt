package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
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
  /**
   * The catalog a fixture pins, rather than one catalog for the whole directory.
   *
   * It was `m3-catalog` for everything, which was true while everything here was a mobile screen
   * and silently wrong the moment one was not: a `wear-m3` design validated against a catalog that
   * declares none of its components would fail with thirty "unknown component" issues, so the
   * directory was effectively closed to every catalog but one. Both capability files already sit
   * beside the designs and are already on this module's resources path, so the fixture's own
   * `catalogPin.systemId` is enough to pick.
   */
  private val catalogs =
    mutableMapOf<String, ee.schimke.composeai.uibuilder.capability.CapabilityCatalog>()

  private fun catalogFor(systemId: String) =
    catalogs.getOrPut(systemId) {
      CapabilityCatalogParser.parse(resource("/$systemId-capabilities-v1.json"))
    }

  private fun File.pinnedCatalog() =
    catalogFor(
      (fixture()["operations"] as? kotlinx.serialization.json.JsonArray)?.firstNotNullOfOrNull {
        operation ->
        ((operation as? JsonObject)?.get("catalogPin") as? JsonObject)
          ?.get("systemId")
          ?.jsonPrimitive
          ?.content
      } ?: "m3-catalog"
    )

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
        CapabilityValidator(file.pinnedCatalog()).validate(document).issues,
        "${file.name} no longer validates",
      )
    }
  }

  /**
   * Exported by whichever generator actually writes that design, which is not one generator.
   *
   * A Wear screen's `ScreenScaffold` takes a scroll state no component record can recover, and a
   * Wear widget ships as Remote Compose, so both go through `RecordFreeExport` — the same call the
   * editor's Code pane and the server's export make. Asking the record-driven exporter instead
   * answers a question that design was never posed: every node came back `MISSING_CODE_CAPABILITY:
   * no Kotlin symbol/import mapping exists`, which is true of that exporter and says nothing about
   * the design.
   */
  @Test
  fun `and therefore each exports as Compose`() {
    fixtures.forEach { file ->
      val document = UiBuilderReducer.replay(file.fixture()).document
      val platform = file.pinnedCatalog().platform
      val source =
        when (val recordFree = RecordFreeExport.generate(document, platform)) {
          is RecordFreeExport.Generated.Emitted -> recordFree.source
          is RecordFreeExport.Generated.Refused ->
            fail("${file.name} was refused by its own emitter: ${recordFree.reasons}")
          null ->
            CapabilityComposeCodeExporter.export(document, file.pinnedCatalog()).requireSource()
        }
      assertTrue("@Composable" in source, "${file.name} exported no composable")
    }
  }

  @Test
  fun `each fixture has a preview that draws it, framed at its own environment`() {
    val previews = previewSource()
    fixtures.forEach { file ->
      val call = "DesignFixture(\"${file.nameWithoutExtension}\")"
      assertTrue(call in previews, "${file.name} has no @Preview in DesignFixturePreviews.kt")

      // The device spec immediately above the call, which is where the frame is declared.
      val spec =
        Regex(
            "@Preview\\(device = \"([^\"]+)\"\\)\\s*\\n@Composable\\s*\\nfun \\w+\\(\\) = ${Regex.escape(call)}"
          )
          .find(previews)
          ?.groupValues
          ?.get(1)
      assertNotNull(spec, "${file.name}'s preview does not declare a device spec")

      val environment =
        UiBuilderReducer.replay(file.fixture()).document.environment.let { env ->
          Triple(
            env.getValue("widthDp").jsonPrimitive.int,
            env.getValue("heightDp").jsonPrimitive.int,
            env.getValue("density").jsonPrimitive.float,
          )
        }
      val (widthDp, heightDp, density) = environment
      assertEquals(
        "spec:width=${widthDp}dp,height=${heightDp}dp,dpi=${(density * 160).roundToInt()}",
        spec,
        "${file.name}'s preview frame does not match the environment the design pins. " +
          "`UiBuilderRenderer` composes at the document's own density, so a frame at a different " +
          "one captures the design in a corner of the image",
      )
    }
  }

  private fun previewSource(): String =
    File(
        System.getProperty("uiBuilderProjectDir") ?: ".",
        "src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/preview/DesignFixturePreviews.kt",
      )
      .readText()

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
