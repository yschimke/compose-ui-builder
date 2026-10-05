package example

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import example.google.KeepChecklistItem
import example.google.items.*
import example.google.model.*
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GoogleItemDataTest {
  @Test
  fun `email instances and document comments bind independent data and recompose`() =
    runComposeUiTest {
      val email = mutableStateOf(Email("Alex", "Now", "First subject", "First snippet"))
      setContent {
        MaterialTheme {
          Column {
            UnreadEmailSummary(email.value)
            EmailSummary(Email("Sam", "Yesterday", "Second subject", "Second snippet"))
            ReviewerCommentItem(ReviewerComment("Reviewer", "Needs clarification"))
          }
        }
      }
      onNodeWithText("First subject").assertExists()
      onNodeWithText("Second subject").assertExists()
      onNodeWithText("Needs clarification").assertExists()
      runOnIdle { email.value = email.value.copy(subject = "Updated subject") }
      onNodeWithText("First subject").assertDoesNotExist()
      onNodeWithText("Updated subject").assertExists()
      onNodeWithText("Second subject").assertExists()
    }

  @Test
  fun `note checklist state and labels follow the data class`() = runComposeUiTest {
    val entry = mutableStateOf(ChecklistEntry(false, "Pack"))
    setContent { MaterialTheme { KeepChecklistItem(entry.value) } }
    val unchecked = onRoot().captureToImage().toPixelMap()
    runOnIdle { entry.value = entry.value.copy(checked = true, label = "Packed") }
    val checked = onRoot().captureToImage().toPixelMap()
    // Display-only checkboxes intentionally have no toggle action or semantics.
    assertTrue(
      (0 until minOf(unchecked.width, checked.width, 48)).any { x ->
        (0 until minOf(unchecked.height, checked.height)).any { y ->
          unchecked[x, y] != checked[x, y]
        }
      }
    )
    onNodeWithText("Packed").assertExists()
    onNodeWithText("Pack").assertDoesNotExist()
  }

  @Test
  fun `color bindings accept Compose colors and update actual pixels`() = runComposeUiTest {
    val event = mutableStateOf(CalendarEvent(Color.Red, "Review", "10 AM"))
    setContent { MaterialTheme { CalendarEventItem(event.value) } }
    fun contains(color: Color): Boolean {
      val pixels = onRoot().captureToImage().toPixelMap()
      return (0 until pixels.width).any { x ->
        (0 until pixels.height).any { y -> pixels[x, y] == color }
      }
    }
    assertTrue(contains(Color.Red))
    runOnIdle { event.value = event.value.copy(containerColor = Color.Blue, title = "Planning") }
    onNodeWithText("Planning").assertExists()
    assertTrue(contains(Color.Blue))
  }

  @Test
  fun `photo badge and store listing read their models`() = runComposeUiTest {
    val app = mutableStateOf(StoreApp(Color.Red, Color.Blue, "Sketchbook", "4.8", "Rating"))
    setContent {
      MaterialTheme {
        Column {
          PhotoTileLeftToRightFavorite(Photo(Color.Red, Color.Blue, "Favorite photo"))
          StoreAppCard(app.value)
          PlainNoteContent(Note("Ideas", "Draw something", "", ""))
        }
      }
    }
    onNodeWithContentDescription("Favorite photo").assertExists()
    onNodeWithText("Sketchbook").assertExists()
    onNodeWithText("Draw something").assertExists()
    runOnIdle { app.value = app.value.copy(title = "Updated app", rating = "4.9") }
    onNodeWithText("Updated app").assertExists()
    onNodeWithText("4.9").assertExists()
  }
}
