package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * A label with its catalog notes behind a small info button, collapsed until asked for.
 *
 * The notes a catalog writes for a property or a token are written for an agent: the wire spelling,
 * the codes a bad value is refused with, what the generated Kotlin calls. Printed in full under
 * every control they were most of the panel; behind the button they are one press away for the
 * person who wants them, and the catalog and MCP keep the full text.
 */
@Composable
internal fun LabelWithNotes(
  label: String,
  notes: String?,
  style: TextStyle,
  modifier: Modifier = Modifier,
) {
  var shown by remember(label, notes) { mutableStateOf(false) }
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), style = style)
    if (!notes.isNullOrBlank()) {
      Box(
        Modifier.size(24.dp)
          .clip(CircleShape)
          .clickable { shown = !shown }
          .semantics {
            role = Role.Button
            contentDescription = "About $label"
            stateDescription = if (shown) "Shown" else "Hidden"
          },
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Outlined.Info,
          contentDescription = null,
          Modifier.size(16.dp),
          tint =
            if (shown) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
  if (shown && !notes.isNullOrBlank()) {
    Text(
      notes,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}
