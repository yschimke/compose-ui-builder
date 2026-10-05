// Derived from the canonical Google app item body; see the consumer README for provenance.
package example.google.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import example.google.model.ReviewerComment

@Composable
fun ReviewerCommentItem(data: ReviewerComment, modifier: Modifier = Modifier) {
  Box(modifier = modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Text(text = data.author, style = MaterialTheme.typography.labelLarge)
      Text(text = data.body, style = MaterialTheme.typography.bodyLarge)
    }
  }
}
