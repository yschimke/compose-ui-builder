package ee.schimke.composeai.uibuilder

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

internal actual fun platformFont(
  identity: String,
  data: ByteArray,
  weight: FontWeight,
  variationSettings: FontVariation.Settings?,
): Font =
  androidx.compose.ui.text.platform.Font(
    identity,
    data,
    weight,
    FontStyle.Normal,
    variationSettings ?: FontVariation.Settings(FontVariation.weight(weight.weight)),
  )
