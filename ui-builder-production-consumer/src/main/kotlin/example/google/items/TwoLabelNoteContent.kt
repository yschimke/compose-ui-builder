// Derived from the canonical Google app item body; see the consumer README for provenance.
package example.google.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import example.google.model.Note

@Composable
fun TwoLabelNoteContent(data: Note, modifier: Modifier = Modifier) {
  Box(modifier = modifier) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.Start,
    ) {
      Text(
        text = data.title,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleSmall,
      )
      Text(
        text = data.body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
      FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        FilterChip(
          selected = false,
          onClick = {},
          label = { Text(text = data.label, style = MaterialTheme.typography.labelSmall) },
          shape = MaterialTheme.shapes.small,
        )
        FilterChip(
          selected = false,
          onClick = {},
          label = { Text(text = data.label2, style = MaterialTheme.typography.labelSmall) },
          shape = MaterialTheme.shapes.small,
        )
      }
    }
  }
}
