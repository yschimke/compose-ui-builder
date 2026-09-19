package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Desktop pointer coverage for the two non-drag interactions a visual editor must not hide behind a
 * rail: choosing a layer starts editing it, while its secondary click opens its context menu.
 *
 * Palette and canvas drags are covered by [BesideDropTest] and [CanvasMoveDragTest]. This owns the
 * native mouse path specifically: touch input cannot prove a secondary button reaches the menu.
 */
@OptIn(ExperimentalTestApi::class)
class EditorDesktopPointerInteractionTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val document = UiBuilderReducer.replay(fixture).document

  @Test
  fun `a primary layer click opens the selected component properties`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      editor()

      onNodeWithContentDescription("Select root-surface").performMouseInput { click() }

      onNodeWithText("Properties").assertExists()
      onNodeWithText("m3/surface").assertExists()
    }

  @Test
  fun `a secondary layer click selects it and opens its action menu`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      editor()

      onNodeWithContentDescription("Select root-surface").performMouseInput { rightClick() }

      onNodeWithText("Duplicate").assertExists()
      onNodeWithText("Delete").assertExists()
    }

  private fun androidx.compose.ui.test.ComposeUiTest.editor() {
    setContent {
      MaterialTheme {
        UiBuilderEditor(
          document = document,
          catalog = catalog,
          initialLayersOpen = true,
          initialCanvasZoom = 1f,
        )
      }
    }
    waitForIdle()
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private companion object {
    private val fixture =
      Json.parseToJsonElement(
          checkNotNull(
              EditorDesktopPointerInteractionTest::class
                .java
                .getResource("/jetcaster-discover-operations-v1.json")
            )
            .readText()
        )
        .jsonObject
  }
}
