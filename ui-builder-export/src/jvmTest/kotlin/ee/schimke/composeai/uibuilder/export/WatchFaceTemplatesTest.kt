package ee.schimke.composeai.uibuilder.export

import ee.schimke.watchface.wff.WffValidator
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The two starting points of the `wff-watchface` catalog export to a face a watch can run with
 * nothing beside it: it validates against the format at its own version, with no warnings, and it
 * names no bitmap, font or string resource that the design does not carry.
 */
class WatchFaceTemplatesTest {
  private val designs: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, "docs/design/fixtures/ui-builder/wff-watchface/designs") }
      .first { it.isDirectory }

  private fun template(name: String): UiBuilderDocument =
    Json.decodeFromString(UiBuilderDocument.serializer(), File(designs, name).readText())

  private fun xmlOf(name: String): String {
    val result = WatchFaceXmlExporter.export(template(name))
    return assertIs<WatchFaceXmlExporter.Result.Emitted>(
        result,
        (result as? WatchFaceXmlExporter.Result.Refused)?.reasons.toString(),
      )
      .xml
  }

  /** Kept under `build/`, for handing to the watchface-tools packager. */
  @Test
  fun `the exported XML is kept for the packager`() {
    val dir = File("build/wff-template-xml")
    dir.mkdirs()
    for (name in listOf("analog-watch-face", "digital-watch-face")) {
      File(dir, "$name.xml").writeText(xmlOf("$name.json"))
    }
  }

  @Test
  fun `each template exports XML that validates clean at its own version`() {
    for (name in listOf("analog-watch-face.json", "digital-watch-face.json")) {
      val document = template(name)
      val version =
        WffVersion.fromNumber(WatchFaceDesignValidation.versionOf(document))
          ?: error("version of $name")
      val parsed = WffXmlParser.parse(xmlOf(name))
      val diagnostics = WffValidator.check(parsed, version)
      assertTrue(diagnostics.isEmpty(), "$name: $diagnostics")
      assertTrue(WffValidator.minimumVersion(parsed) <= version, "$name needs a newer version")
    }
  }

  @Test
  fun `the templates reference no resources the design does not carry`() {
    for (name in listOf("analog-watch-face.json", "digital-watch-face.json")) {
      val xml = xmlOf(name)
      assertFalse(xml.contains("resource="), "$name names a bitmap resource:\n$xml")
      assertFalse(xml.contains("<Image"), "$name draws an image:\n$xml")
    }
  }

  @Test
  fun `the analog face draws its dial and drives its hands with shapes and transforms`() {
    val xml = xmlOf("analog-watch-face.json")
    for (expression in listOf("[HOUR_0_11]", "[MINUTE]", "[SECOND_MILLISECOND]")) {
      assertTrue(xml.contains(expression), "no $expression in:\n$xml")
    }
    assertEquals(3, Regex("<Transform target=\"angle\"").findAll(xml).count())
    assertEquals(1, Regex("<ComplicationSlot").findAll(xml).count())
    assertTrue(xml.contains("<Ellipse"), xml)
  }
}
