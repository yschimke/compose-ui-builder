package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.editor.NewDesignDialog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignScreen
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignTemplate
import ee.schimke.composeai.uibuilder.export.NewDesignState
import ee.schimke.composeai.uibuilder.export.NewDesignStateType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalTestApi::class)
class NewDesignKeyboardTest {
  @Test
  fun `design id IME action creates the design`() =
    runDesktopComposeUiTest(width = 900, height = 900) {
      var createdId: String? = null
      setContent {
        UiBuilderNewDesignScreen(
          catalogs = listOf(catalog),
          initialCatalogSystemId = catalog.systemId,
          onCreate = { _, designId, _, _ -> createdId = designId },
        )
      }

      onNodeWithContentDescription("Design ID").performTextReplacement("keyboard-design")
      onNodeWithContentDescription("Design ID").performImeAction()

      assertEquals("keyboard-design", createdId)
    }

  @Test
  fun `state value IME action adds the declaration`() =
    runDesktopComposeUiTest(width = 900, height = 900) {
      var state = emptyList<NewDesignState>()
      setContent {
        UiBuilderNewDesignScreen(
          catalogs = listOf(catalog),
          initialCatalogSystemId = catalog.systemId,
          onCreate = { _, _, _, declared -> state = declared },
        )
      }

      onNodeWithContentDescription("Add state variables").performClick()
      onNodeWithContentDescription("State name").performTextReplacement("expanded")
      onNodeWithContentDescription("State initial value").performTextReplacement("true")
      onNodeWithContentDescription("State initial value").performImeAction()
      onNodeWithContentDescription("Create design").performScrollTo().performClick()

      assertEquals(
        listOf(NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(true))),
        state,
      )
    }

  @Test
  fun `creation defaults to private and uses the selected visibility for keyboard and button submit`() =
    runDesktopComposeUiTest(width = 900, height = 1100) {
      val creations = mutableListOf<String>()
      setContent {
        UiBuilderNewDesignScreen(
          catalogs = listOf(catalog),
          initialCatalogSystemId = catalog.systemId,
          onCreate = { _, _, _, _ -> creations += "private" },
          onCreatePublic = { _, _, _, _ -> creations += "public" },
        )
      }
      onNodeWithContentDescription("Design ID").performImeAction()
      onNodeWithText("Public (read only)").performScrollTo().performClick()
      onNodeWithContentDescription("Design ID").performImeAction()
      onNodeWithContentDescription("Create design").performScrollTo().performClick()
      onNodeWithText("Private").performScrollTo().performClick()
      onNodeWithContentDescription("Create design").performScrollTo().performClick()
      assertEquals(listOf("private", "public", "public", "private"), creations)
    }

  @Test
  fun `short new design dialog scrolls to lower templates and state controls`() =
    runDesktopComposeUiTest(width = 600, height = 480) {
      var createdTemplate: String? = null
      var createdState = emptyList<NewDesignState>()
      setContent {
        NewDesignDialog(
          catalogs =
            listOf(
              catalog.copy(
                systemId = "remote-m3",
                templates =
                  (1..5).map {
                    UiBuilderNewDesignTemplate("starter-$it", "Starter $it", "Widget template $it")
                  },
              )
            ),
          initialCatalogSystemId = "remote-m3",
          initialDesignId = "short-dialog",
          onDismiss = {},
          onCreate = { _, _, template, state ->
            createdTemplate = template
            createdState = state
          },
        )
      }
      onNodeWithText("Starter 5").performScrollTo().performClick()
      onNodeWithContentDescription("Design ID").performScrollTo()
      onNodeWithContentDescription("Add state variables").performScrollTo().performClick()
      onNodeWithContentDescription("State name")
        .performScrollTo()
        .performTextReplacement("expanded")
      onNodeWithContentDescription("State initial value")
        .performScrollTo()
        .performTextReplacement("true")
      onNodeWithContentDescription("State initial value").performImeAction()
      onNodeWithText("Create").performClick()
      assertEquals("starter-5", createdTemplate)
      assertEquals(
        listOf(NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(true))),
        createdState,
      )
    }

  private companion object {
    val catalog =
      UiBuilderNewDesignCatalog(
        systemId = "m3",
        label = "Material 3",
        templates = listOf(UiBuilderNewDesignTemplate("blank", "Blank", "Empty screen")),
      )
  }
}
