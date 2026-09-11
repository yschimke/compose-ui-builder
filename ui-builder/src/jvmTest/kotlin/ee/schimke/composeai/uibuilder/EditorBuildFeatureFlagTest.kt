package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

@OptIn(ExperimentalTestApi::class)
class EditorBuildFeatureFlagTest {
  @Test
  fun `the existing editor exposes state selection only in an enabled build`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      val root = File(System.getProperty("uiBuilderProjectDir"), "..")
      val catalog =
        CapabilityCatalogParser.parse(
          checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
        )
      val document = Json {
        ignoreUnknownKeys = true
      }
        .decodeFromString<UiBuilderDocument>(
          File(root, "experiments/remote-state-selection/bound-actions.document.json").readText()
        )
      assertEquals(
        UiBuilderBuildFeatures.remoteCompose,
        catalog.componentsById.getValue("layout/box").properties.any { it.name == SHOW_BY_STATE },
      )
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            initialSelectedNodeId = "indicator",
            initialInspectorOpen = true,
          )
        }
      }
      if (UiBuilderBuildFeatures.remoteCompose) onNodeWithText("Show by state").assertExists()
      else onNodeWithText("Show by state").assertDoesNotExist()
      val screenshot = onRoot().captureToImage()
      val name = if (UiBuilderBuildFeatures.remoteCompose) "enabled" else "disabled"
      File(root, "ui-builder/build/feature-flag-evidence/$name.png").apply {
        parentFile.mkdirs()
        writeBytes(
          checkNotNull(
              Image.makeFromBitmap(screenshot.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
            )
            .bytes
        )
      }
    }
}
