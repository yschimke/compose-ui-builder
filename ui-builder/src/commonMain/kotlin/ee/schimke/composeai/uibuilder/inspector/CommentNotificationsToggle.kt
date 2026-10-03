package ee.schimke.composeai.uibuilder.inspector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.CommentNotificationsState
import ee.schimke.composeai.uibuilder.editor.INSTALL_FIRST_HINT
import ee.schimke.composeai.uibuilder.editor.INSTALL_FIRST_TITLE

/**
 * The comments panel's "Notify me about replies" switch, or nothing.
 *
 * A row in the panel's header, under the sentence that says what the panel is: it is about the
 * discussion as a whole, not one thread, and a person who has just posted a question is the person
 * who wants to know when it is answered. See [CommentNotificationsState] for when each state is
 * shown and for why [CommentNotificationsState.Hidden] is the answer more often than not.
 */
@Composable
internal fun CommentNotificationsToggle(
  state: CommentNotificationsState,
  onToggle: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  when (state) {
    CommentNotificationsState.Hidden -> return
    CommentNotificationsState.InstallFirst ->
      NotificationsRow(
        icon = { Icon(Icons.AutoMirrored.Filled.AddToHomeScreen, contentDescription = null, it) },
        title = INSTALL_FIRST_TITLE,
        message = INSTALL_FIRST_HINT,
        messageIsError = false,
        modifier = modifier,
      )
    is CommentNotificationsState.Blocked ->
      NotificationsRow(
        icon = { Icon(Icons.Filled.NotificationsOff, contentDescription = null, it) },
        title = NOTIFY_LABEL,
        message = state.message,
        messageIsError = true,
        modifier = modifier,
      ) {
        // Still a switch, still clickable: somebody who has just allowed notifications in the
        // site settings should not have to reload to say so. The click asks the browser again.
        NotifySwitch(checked = false, enabled = onToggle != null, onToggle = onToggle)
      }
    is CommentNotificationsState.Toggle ->
      NotificationsRow(
        icon = {
          Icon(
            if (state.on) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
            contentDescription = null,
            it,
          )
        },
        title = NOTIFY_LABEL,
        message = state.message,
        messageIsError = state.message != null,
        modifier = modifier,
      ) {
        NotifySwitch(
          checked = state.on,
          enabled = !state.busy && onToggle != null,
          onToggle = onToggle,
        )
      }
  }
}

internal const val NOTIFY_LABEL = "Notify me about replies"

@Composable
private fun NotificationsRow(
  icon: @Composable (Modifier) -> Unit,
  title: String,
  message: String?,
  messageIsError: Boolean,
  modifier: Modifier,
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier.fillMaxWidth().padding(top = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    icon(Modifier.size(18.dp))
    Column(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp)) {
      Text(title, style = MaterialTheme.typography.labelMedium)
      message?.let {
        Text(
          it,
          style = MaterialTheme.typography.labelSmall,
          color =
            if (messageIsError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    trailing?.invoke()
  }
}

@Composable
private fun NotifySwitch(checked: Boolean, enabled: Boolean, onToggle: (() -> Unit)?) {
  Switch(
    checked = checked,
    onCheckedChange = { onToggle?.invoke() },
    enabled = enabled,
    modifier =
      Modifier.semantics {
        contentDescription = NOTIFY_LABEL
        stateDescription = if (checked) "On" else "Off"
      },
  )
}
