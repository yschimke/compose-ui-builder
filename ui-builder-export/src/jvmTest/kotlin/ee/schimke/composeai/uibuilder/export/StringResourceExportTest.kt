package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A `resource` value on a text parameter exports as the catalog's own translated string, through
 * the Compose Multiplatform accessor its structure declares, and is refused without one.
 */
class StringResourceExportTest {
  private val json = Json { ignoreUnknownKeys = true }

  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder/m3-catalog-components-v1.json").isFile }

  private val record: ComponentRecordFile =
    json.decodeFromString(
      File(root, "docs/design/fixtures/ui-builder/m3-catalog-components-v1.json").readText()
    )

  private val res = "ee.schimke.m3catalog.generated.resources.Res"

  private fun document(key: String) =
    json.decodeFromString<DesignDocumentV1>(
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "copy",
        "title": "Copy",
        "revision": 0,
        "catalogPin": {"systemId": "m3-catalog", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 360, "heightDp": 640, "density": 1.0, "theme": "light",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
        "roots": ["label"],
        "nodes": {
          "label": {"id": "label", "componentId": "m3/text",
            "properties": {"text": {"type": "resource", "resourceKey": "$key"}},
            "slots": {}, "modifiers": []}
        }
      }
      """
    )

  private fun export(key: String, structure: CatalogScreenStructure?) =
    ScreenExportGate.export(document(key), record, emptyMap(), structure)

  @Test
  fun `a resource value exports as stringResource through the catalog's accessor`() {
    val outcome = export("card_title", CatalogScreenStructure(stringResources = res))
    assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString())
    val source = outcome.source
    assertTrue("stringResource(Res.string.card_title)" in source, source)
    assertTrue("import org.jetbrains.compose.resources.stringResource" in source, source)
    assertTrue("import $res\n" in source, source)
    // The member is a generated extension property in the accessor's package.
    assertTrue("import ee.schimke.m3catalog.generated.resources.card_title" in source, source)
  }

  @Test
  fun `without a declared accessor a resource value is refused by name`() {
    val outcome = export("card_title", null)
    assertIs<ScreenExportGate.Outcome.Refused>(outcome)
    assertTrue(
      outcome.reasons.any { "resource `card_title`" in it && "code.stringResources" in it },
      outcome.reasons.toString(),
    )
  }

  @Test
  fun `a key that is not a Kotlin identifier is refused`() {
    val outcome = export("card-title", CatalogScreenStructure(stringResources = res))
    assertIs<ScreenExportGate.Outcome.Refused>(outcome)
    assertTrue(outcome.reasons.any { "not a Kotlin identifier" in it }, outcome.reasons.toString())
  }

  @Test
  fun `the accessor is read from the catalog's code block`() {
    val semantics =
      json.parseToJsonElement("""{"code": {"stringResources": "$res"}}""") as JsonObject
    assertEquals(res, CatalogScreenStructure.from(semantics).stringResources)
  }
}
