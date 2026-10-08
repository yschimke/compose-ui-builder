package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.export.RemoteClockTemplate
import ee.schimke.composeai.uibuilder.preview.RemoteClockPreview
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/** The Clock template validates against the catalog it is offered for, and the canvas draws it. */
@OptIn(ExperimentalTestApi::class)
class RemoteClockTemplateCatalogTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/remote-m3-capabilities-v1.json")).readText()
    )

  @Test
  fun `the clock validates against the remote-m3 catalog`() {
    val clock =
      RemoteClockTemplate.document("clock", JsonObject(emptyMap()), JsonObject(emptyMap()))

    val validation = CapabilityValidator(catalog).validate(clock)

    assertTrue(validation.structurallyValid, validation.issues.joinToString { it.message })
  }

  @Test
  fun `the clock preview draws`() =
    runDesktopComposeUiTest(width = 440, height = 440) {
      setContent { RemoteClockPreview() }

      onNodeWithText("Unsupported component", substring = true).assertDoesNotExist()
    }
}
