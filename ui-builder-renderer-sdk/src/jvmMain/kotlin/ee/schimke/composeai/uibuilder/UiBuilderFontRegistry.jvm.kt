package ee.schimke.composeai.uibuilder

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight

internal actual fun platformFont(identity: String, data: ByteArray, weight: FontWeight): Font =
  androidx.compose.ui.text.platform.Font(identity, data, weight)
