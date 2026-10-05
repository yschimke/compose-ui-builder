// Derived from the canonical Google app item body; see the consumer README for provenance.
package example.google.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import example.google.model.ChecklistEntry

@Composable
fun CompletedChecklistItem(
  data: ChecklistEntry,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(checked = data.checked, onCheckedChange = null)
      Text(
        text = data.label,
        modifier = Modifier.weight(1f),
        textDecoration = TextDecoration.LineThrough,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}
