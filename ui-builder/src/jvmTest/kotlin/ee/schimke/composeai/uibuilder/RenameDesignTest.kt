package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.renameRefusal
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Renaming a design from the editor (yschimke/compose-ui-builder#67): the title changes where the
 * person reads it, the id does not, and a refusal is shown in the dialog rather than lost.
 */
@OptIn(ExperimentalTestApi::class)
class RenameDesignTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )
  private val document = UiBuilderReducer.replay(fixture).document

  @Test
  fun `the toolbar title opens the rename dialog, and a stored rename retitles the editor`() =
    runDesktopComposeUiTest(width = 1600, height = 1000) {
      val requested = mutableListOf<String>()
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            onRenameDesign = { title ->
              requested += title
              if (title == "Taken") "the server refused this title" else null
            },
          )
        }
      }
      waitForIdle()
      onNodeWithText(document.title).performClick()
      onNodeWithText("Rename design").assertExists()

      onNodeWithContentDescription("Design title").performTextReplacement("Taken")
      onNodeWithText("Rename").performClick()
      waitForIdle()
      // Refused: said in the dialog, which stays open, and the toolbar keeps the old title.
      onNodeWithText("the server refused this title").assertExists()

      onNodeWithContentDescription("Design title").performTextReplacement("  Discover, renamed ")
      onNodeWithText("Rename").performClick()
      waitForIdle()

      assertEquals(listOf("Taken", "Discover, renamed"), requested)
      onNodeWithText("Rename design").assertDoesNotExist()
      onNodeWithText("Discover, renamed").assertExists()
    }

  @Test
  fun `the overflow menu offers Rename only where the host can rename`() =
    runDesktopComposeUiTest(width = 1600, height = 1000) {
      setContent { MaterialTheme { UiBuilderEditor(document = document, catalog = catalog) } }
      waitForIdle()
      onNodeWithContentDescription("More editor actions").performClick()
      waitForIdle()
      onNodeWithText("Rename design…").assertDoesNotExist()
    }

  @Test
  fun `a host fetch answer becomes the sentence the dialog shows`() {
    assertNull(renameRefusal("""{"status":200,"body":"{}"}"""))
    assertEquals(
      "design title is blank",
      renameRefusal("""{"status":400,"body":"design title is blank\n"}"""),
    )
    assertEquals(
      "this server cannot rename designs yet",
      renameRefusal("""{"status":405,"body":"Method Not Allowed"}"""),
    )
    assertEquals(
      "the server answered 502 while renaming",
      renameRefusal("""{"status":502,"body":"<html>bad gateway</html>"}"""),
    )
    assertEquals("the server answered something unreadable", renameRefusal("not json"))
  }

  private companion object {
    private val fixture =
      Json.parseToJsonElement(
          checkNotNull(
              RenameDesignTest::class.java.getResource("/jetcaster-discover-operations-v1.json")
            )
            .readText()
        )
        .jsonObject
  }
}
