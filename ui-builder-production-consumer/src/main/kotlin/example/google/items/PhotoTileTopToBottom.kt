// Derived from the canonical Google app item body; see the consumer README for provenance.
package example.google.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import example.google.model.Photo
import kotlin.collections.listOf

@Composable
fun PhotoTileTopToBottom(data: Photo, modifier: Modifier = Modifier) {
  Box(modifier = modifier) {
    Box(modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp))) {
      Box(
        modifier =
          Modifier.matchParentSize()
            .background(brush = Brush.verticalGradient(listOf(data.startColor, data.endColor))),
        content = {},
      )
    }
  }
}
