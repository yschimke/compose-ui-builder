package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.export.WearWidgetHostSpec

/**
 * A design with no Wear container of its own, drawn in [host]'s: its shape (a round host's 999dp
 * radius clamps to a stadium), padding and the background a widget declaring none gets. [dpScale]
 * is the pane's dp-to-drawn-dp factor, so the semantic canvas and the played document agree.
 */
internal fun Modifier.wearHostFrame(host: WearWidgetHostSpec, dpScale: Float): Modifier {
  val radius = minOf(host.cornerRadiusDp, host.frameHeightDp / 2f)
  return clip(RoundedCornerShape((radius * dpScale).dp))
    .background(WEAR_HOST_FRAME_BACKGROUND)
    .padding(
      horizontal = (host.horizontalPaddingDp * dpScale).dp,
      vertical = (host.verticalPaddingDp * dpScale).dp,
    )
}

private val WEAR_HOST_FRAME_BACKGROUND = Color(red = 39, green = 36, blue = 48)
