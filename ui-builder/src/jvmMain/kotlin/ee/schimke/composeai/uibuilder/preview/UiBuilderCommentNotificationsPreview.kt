package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.CommentNotificationsState
import ee.schimke.composeai.uibuilder.inspector.CommentNotificationsToggle

/**
 * The comments panel's "Notify me about replies" row in each state it can be drawn in.
 *
 * Only the row, at the comments dock's width, rather than the whole editor four times: the rest of
 * the panel is [UiBuilderCommentsPanelPreview]'s to diff, and what this one has to keep honest is
 * the part no state test can see — the bell beside the switch, the blocked hint in the error
 * colour, and the iPhone sentence that replaces the switch. Hidden, the fifth state and the usual
 * one, draws nothing and so has no picture.
 */
@Preview(widthDp = 360, heightDp = 460)
@Composable
fun UiBuilderCommentNotificationsPreview() {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface {
      Column(
        Modifier.width(360.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        notificationStates.forEachIndexed { index, (label, state) ->
          if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
          Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          CommentNotificationsToggle(state, onToggle = {})
        }
      }
    }
  }
}

private val notificationStates =
  listOf(
    "Available, off" to CommentNotificationsState.Toggle(on = false),
    "On" to CommentNotificationsState.Toggle(on = true),
    "Permission denied" to CommentNotificationsState.Blocked(),
    "iPhone, in a Safari tab" to CommentNotificationsState.InstallFirst,
  )
