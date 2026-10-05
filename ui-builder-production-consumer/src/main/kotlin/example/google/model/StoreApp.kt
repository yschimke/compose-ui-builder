// Application data for the reusable Google app item examples.
package example.google.model

import androidx.compose.ui.graphics.Color

data class StoreApp(
  val startColor: Color,
  val endColor: Color,
  val title: String,
  val rating: String,
  val contentDescription: String,
)
