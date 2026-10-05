// Derived from the canonical Google app item body; see the consumer README for provenance.
package example.google.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import example.google.model.NoteBullet

@Composable
fun BulletNoteItem(data: NoteBullet, modifier: Modifier = Modifier) {
  Box(modifier = modifier) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier.size(6.dp)
            .clip(CircleShape)
            .background(color = MaterialTheme.colorScheme.onSurfaceVariant),
        content = {},
      )
      Text(
        text = data.label,
        modifier = Modifier.weight(1f),
        textDecoration = TextDecoration.None,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}
