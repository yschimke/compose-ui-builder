package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.AdaptiveScreenTemplates
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.preview.designFixtureDocument
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalTestApi::class)
class AdaptiveListDetailTest {
  private fun document(
    policy: String = "fixedStart",
    width: Int = 360,
    fraction: Float = .5f,
  ): UiBuilderDocument {
    val seed =
      AdaptiveScreenTemplates.document(
        "list-detail",
        "panes",
        JsonObject(emptyMap()),
        JsonObject(emptyMap()),
      )
    fun value(type: String, v: JsonPrimitive) =
      JsonObject(mapOf("type" to JsonPrimitive(type), "value" to v))
    return seed.copy(
      environment = JsonObject(mapOf("density" to JsonPrimitive(1))),
      nodes =
        seed.nodes +
          ("panes" to
            seed.nodes
              .getValue("panes")
              .copy(
                properties =
                  JsonObject(
                    seed.nodes.getValue("panes").properties +
                      mapOf(
                        "paneSizing" to value("enum", JsonPrimitive(policy)),
                        "fixedPaneWidthDp" to value("float", JsonPrimitive(width)),
                        "splitFraction" to value("float", JsonPrimitive(fraction)),
                      )
                  )
              )),
    )
  }

  @Test
  fun `compact selection opens details and back returns to the list`() =
    runDesktopComposeUiTest(width = 412, height = 915) {
      org.junit.Assume.assumeTrue(
        "Enable with -PuiBuilderRemoteCompose=true",
        UiBuilderBuildFeatures.remoteCompose,
      )
      setContent { MaterialTheme { UiBuilderSurface(document()) } }
      onNodeWithText("List").assertIsDisplayed()
      onNodeWithText("Detail").assertIsNotDisplayed()
      onNodeWithText("Open item").performClick()
      mainClock.advanceTimeBy(1000)
      onNodeWithText("Detail").assertIsDisplayed()
      onNodeWithText("List").assertIsNotDisplayed()
      val output =
        File(System.getProperty("uiBuilderProjectDir"), "build/adaptive-evidence").apply {
          mkdirs()
        }
      ImageIO.write(
        onRoot().captureToImage().toAwtImage(),
        "png",
        File(output, "list-detail-compact.png"),
      )
      onNodeWithText("Back to list").performClick()
      mainClock.advanceTimeBy(1000)
      onNodeWithText("List").assertIsDisplayed()
    }

  @Test
  fun `an optional extra pane can become the compact destination`() =
    runDesktopComposeUiTest(width = 412, height = 915) {
      val seed = document()
      val pane = seed.nodes.getValue("panes")
      val extra =
        seed.nodes
          .getValue("main-title")
          .copy(
            id = "extra-title",
            properties =
              JsonObject(
                mapOf(
                  "text" to
                    JsonObject(
                      mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("Extra"))
                    )
                )
              ),
          )
      val extraBox =
        seed.nodes
          .getValue("main")
          .copy(id = "extra", slots = mapOf("children" to listOf("extra-title")))
      val changed =
        seed.copy(
          nodes =
            seed.nodes +
              mapOf(
                "extra-title" to extra,
                "extra" to extraBox,
                "panes" to
                  pane.copy(
                    slots = pane.slots + ("extraPane" to listOf("extra")),
                    properties =
                      JsonObject(
                        pane.properties +
                          ("activePaneIndex" to
                            JsonObject(
                              mapOf("type" to JsonPrimitive("int"), "value" to JsonPrimitive(2))
                            ))
                      ),
                  ),
              )
        )
      setContent { MaterialTheme { UiBuilderSurface(changed) } }
      onNodeWithText("Extra").assertIsDisplayed()
      onNodeWithText("List").assertIsNotDisplayed()
      onNodeWithText("Detail").assertIsNotDisplayed()
    }

  @Test
  fun `first party list-detail samples render at compact and expanded widths`() {
    for (sample in listOf("google-gmail-tablet", "google-calendar-tablet")) for (width in
      listOf(412, 1280)) {
      runDesktopComposeUiTest(width = width, height = 800) {
        setContent { MaterialTheme { UiBuilderSurface(designFixtureDocument(sample)) } }
        val output =
          File(System.getProperty("uiBuilderProjectDir"), "build/adaptive-evidence").apply {
            mkdirs()
          }
        ImageIO.write(
          onRoot().captureToImage().toAwtImage(),
          "png",
          File(output, "$sample-$width.png"),
        )
      }
    }
  }

  private fun detailLeft(policy: String, width: Int, frame: Int, fraction: Float = .5f): Float {
    var left = 0f
    runDesktopComposeUiTest(width = frame, height = 800) {
      setContent { MaterialTheme { UiBuilderSurface(document(policy, width, fraction)) } }
      onNodeWithText("List").assertIsDisplayed()
      onNodeWithText("Detail").assertIsDisplayed()
      left = onNodeWithText("Detail").fetchSemanticsNode().boundsInRoot.left - 24f
      val output =
        File(System.getProperty("uiBuilderProjectDir"), "build/adaptive-evidence").apply {
          mkdirs()
        }
      ImageIO.write(
        onRoot().captureToImage().toAwtImage(),
        "png",
        File(output, "$policy-$frame.png"),
      )
    }
    return left
  }

  @Test
  fun `fixed list stays fixed while detail grows`() {
    assertTrue(abs(detailLeft("fixedStart", 360, 1200) - 384) < 2)
    assertTrue(abs(detailLeft("fixedStart", 360, 1600) - 384) < 2)
  }

  @Test
  fun `fixed detail stays fixed while list grows`() {
    assertTrue(abs(detailLeft("fixedEnd", 380, 1200) - 820) < 2)
    assertTrue(abs(detailLeft("fixedEnd", 380, 1600) - 1220) < 2)
  }

  @Test
  fun `flexible panes preserve their proportion`() {
    assertTrue(abs(detailLeft("split", 360, 1200, .4f) - 492) < 2)
    assertTrue(abs(detailLeft("split", 360, 1600, .4f) - 652) < 2)
  }
}
