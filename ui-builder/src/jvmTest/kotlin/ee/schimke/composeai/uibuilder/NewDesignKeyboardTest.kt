package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
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
      onNodeWithContentDescription("Create design").performClick()

      assertEquals(
        listOf(NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(true))),
        state,
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
