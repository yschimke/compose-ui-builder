package example.google

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import example.google.items.*
import example.google.model.*

/**
 * Application-owned choice of the two design styles keeps checked state and text styling together.
 */
@Composable
fun KeepChecklistItem(data: ChecklistEntry) {
  if (data.checked) CompletedChecklistItem(data) else ChecklistItem(data)
}

@Preview(widthDp = 360, heightDp = 220)
@Composable
fun GmailItemsPreview() {
  MaterialTheme {
    Column {
      UnreadEmailSummary(Email("Alex", "10:30", "Design review", "The latest mockups are ready."))
      EmailSummary(Email("Sam", "Yesterday", "Lunch", "See you at noon."))
    }
  }
}

@Preview(widthDp = 360, heightDp = 180)
@Composable
fun CalendarItemsPreview() {
  MaterialTheme {
    Column {
      CalendarEventItem(
        CalendarEvent(MaterialTheme.colorScheme.primary, "Design review", "10–11 AM")
      )
      CalendarEventItem(CalendarEvent(MaterialTheme.colorScheme.tertiary, "Lunch", "12–1 PM"))
    }
  }
}

@Preview(widthDp = 360, heightDp = 180)
@Composable
fun PhotosItemPreview() {
  MaterialTheme {
    PhotoTileLeftToRightFavorite(
      Photo(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, "Favorite")
    )
  }
}

@Preview(widthDp = 360, heightDp = 280)
@Composable
fun KeepItemsPreview() {
  MaterialTheme {
    Column {
      LabeledNoteContent(Note("Trip", "Book train tickets", "Travel", ""))
      KeepChecklistItem(ChecklistEntry(false, "Pack a charger"))
      KeepChecklistItem(ChecklistEntry(true, "Reserve a room"))
    }
  }
}

@Preview(widthDp = 240, heightDp = 220)
@Composable
fun PlayItemPreview() {
  MaterialTheme {
    StoreAppCard(
      StoreApp(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        "Sketchbook",
        "4.8",
        "Rating",
      )
    )
  }
}

@Preview(widthDp = 320, heightDp = 240)
@Composable
fun DocsItemsPreview() {
  MaterialTheme {
    Column {
      ReviewerCommentItem(ReviewerComment("Alex", "Could we clarify this paragraph?"))
      ReviewerCommentItem(ReviewerComment("Sam", "Added a concrete example."))
    }
  }
}
