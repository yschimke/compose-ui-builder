// Derived from the canonical Google app item body; see the consumer README for provenance.
package example.google.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import example.google.model.CalendarEvent

@Composable
fun CalendarEventItem(data: CalendarEvent, modifier: Modifier = Modifier) {
  Box(modifier = modifier) {
    Card(
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      shape = MaterialTheme.shapes.medium,
      colors = CardDefaults.cardColors(containerColor = data.containerColor),
    ) {
      Box(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
          horizontalAlignment = Alignment.Start,
        ) {
          Text(
            text = data.title,
            color = Color(0xFFFFFFFF),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            style = MaterialTheme.typography.titleSmall,
          )
          Text(
            text = data.schedule,
            color = Color(0xFFFFFFFF),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}
