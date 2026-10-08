package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.export.RemoteClockTemplates
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiDrawing
import ee.schimke.composeai.uibuilder.preview.AnalogClockPreview
import ee.schimke.composeai.uibuilder.preview.DigitalClockPreview
import ee.schimke.composeai.uibuilder.preview.LcdWatchReplicaPreview
import ee.schimke.composeai.uibuilder.preview.RacingChronographReplicaPreview
import ee.schimke.composeai.uibuilder.preview.lcdWatchReplica
import ee.schimke.composeai.uibuilder.preview.racingChronographReplica
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * The clock designs — the two templates and the two replicas — validate against the `remote-m3`
 * catalog and draw on the canvas.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteClockDesignsTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/remote-m3-capabilities-v1.json")).readText()
    )

  private val empty = JsonObject(emptyMap())

  private val designs: Map<String, UiBuilderDocument> =
    RemoteClockTemplates.entries.associate { it.name to it.document(it.templateId, empty, empty) } +
      mapOf(
        "Lcd" to lcdWatchReplica("lcd", empty, empty),
        "Racing" to racingChronographReplica("racing", empty, empty),
      )

  @Test
  fun `every clock validates against the remote-m3 catalog`() {
    designs.forEach { (name, design) ->
      val validation = CapabilityValidator(catalog).validate(design)
      assertTrue(
        validation.structurallyValid,
        "$name: ${validation.issues.joinToString { it.message }}",
      )
    }
  }

  @Test
  fun `the racing movement runs under the dial`() {
    val racing = designs.getValue("Racing")
    val ops = racing.nodes.getValue("racing-canvas").slots.getValue(UiDrawing.OPS_SLOT)

    assertTrue(ops.indexOf("racing-movement") < ops.indexOf("racing-dial-cut"), ops.toString())
  }

  @Test
  fun `every clock preview draws`() {
    listOf<@androidx.compose.runtime.Composable () -> Unit>(
        { AnalogClockPreview() },
        { DigitalClockPreview() },
        { LcdWatchReplicaPreview() },
        { RacingChronographReplicaPreview() },
      )
      .forEach { preview ->
        runDesktopComposeUiTest(width = 440, height = 440) {
          setContent { preview() }
          onNodeWithText("Unsupported component", substring = true).assertDoesNotExist()
        }
      }
  }
}
