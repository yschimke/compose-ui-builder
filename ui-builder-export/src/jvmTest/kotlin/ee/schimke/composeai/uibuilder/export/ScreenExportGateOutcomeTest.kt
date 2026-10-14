package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.AssetKeyValueV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * What [ScreenExportGate.Outcome] tells a host besides the source and the reasons: the pictures it
 * drew with a placeholder painter, and which refusals are the design's own fault. A host reports
 * both (a warning beside the file; a document code rather than a record one), and could not recover
 * either from the source or the merged reason list.
 */
class ScreenExportGateOutcomeTest {
  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder").isDirectory }

  private val record: ComponentRecordFile = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString(
      File(root, "docs/design/fixtures/ui-builder/compose-foundation-components-v1.json").readText()
    )

  private fun image(assetKey: UiValueV1): DesignDocumentV1 {
    val base =
      Json.decodeFromString<DesignDocumentV1>(
        File(root, "docs/design/evidence/ui-builder-repetition-export/repetition-initial.uid")
          .readText()
      )
    val image = DesignNodeV1("hero", "asset/image", properties = mapOf("assetKey" to assetKey))
    return base.copy(
      title = "Gate outcome",
      stateVariables = emptyMap(),
      components = emptyMap(),
      assets = emptyMap(),
      roots = listOf(image.id),
      nodes = mapOf(image.id to image),
    )
  }

  @Test
  fun `a picture drawn with a placeholder painter is named on the emitted outcome`() {
    val outcome = ScreenExportGate.export(image(AssetKeyValueV1("hero-art")), record)

    val emitted = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString())
    assertTrue(emitted.source.contains("ColorPainter"), emitted.source)
    assertEquals(
      listOf(ScreenDocumentProjection.AssetPlaceholder("hero", "hero-art", null, null)),
      emitted.assetPlaceholders,
    )
  }

  @Test
  fun `a refusal the projection raises is the document's, and is still among the reasons`() {
    val outcome = ScreenExportGate.export(image(DecimalValueV1(3.0)), record)

    val refused = assertIs<ScreenExportGate.Outcome.Refused>(outcome, outcome.toString())
    assertTrue(refused.documentReasons.isNotEmpty(), refused.toString())
    assertTrue(refused.reasons.containsAll(refused.documentReasons), refused.toString())
  }

  @Test
  fun `a host with no record has no document reason`() {
    val outcome = ScreenExportGate.export(image(AssetKeyValueV1("hero-art")), null)

    val refused = assertIs<ScreenExportGate.Outcome.Refused>(outcome)
    assertEquals(emptyList(), refused.documentReasons)
  }
}
