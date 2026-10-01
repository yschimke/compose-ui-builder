package ee.schimke.composeai.uibuilder.reference

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One node of the open design to photograph; a new [sequence] is a new photograph. */
internal data class NodeCaptureRequest(val nodeId: String, val sequence: Int)

/**
 * Draws [request]'s node on its own, off screen, and hands back a PNG of it — or null when the node
 * is gone or the platform will not encode a bitmap.
 *
 * The node is made the design's only root, in the design's own frame and environment, so it is
 * drawn by the same renderer and theme the canvas uses; the transparent margin around it is then
 * trimmed, the way [ReferenceComponentCapture] trims a component. A node that fills the screen is a
 * picture of the screen. Recorded and never shown, for the reason that capture gives.
 */
@Composable
internal fun NodeCapture(
  request: NodeCaptureRequest?,
  document: UiBuilderDocument,
  onCaptured: (NodeCaptureRequest, ReferenceImage?) -> Unit,
) {
  if (request == null) return
  if (document.nodes[request.nodeId] == null) {
    LaunchedEffect(request) { onCaptured(request, null) }
    return
  }
  val specimen = document.copy(roots = listOf(request.nodeId))
  val widthDp = document.frameDp("widthDp", DEFAULT_WIDTH_DP)
  val heightDp = document.frameDp("heightDp", DEFAULT_HEIGHT_DP)
  val layer = rememberGraphicsLayer()
  Box(
    Modifier.wrapContentSize(unbounded = true, align = Alignment.TopStart)
      .requiredSize(widthDp.dp, heightDp.dp)
      .drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
      }
  ) {
    UiBuilderSurface(document = specimen, editorOverlay = false)
  }
  LaunchedEffect(request) {
    // Two frames, so the layer has been recorded with the composed node before it is read.
    repeat(2) { withFrameNanos {} }
    val image =
      try {
        val bitmap = trimmed(layer.toImageBitmap())
        encodeReferencePng(bitmap)?.let { png ->
          ReferenceImage(
            id = "node-${request.nodeId}-${request.sequence}",
            name = request.nodeId,
            mediaType = "image/png",
            base64 = Base64.Default.encode(png),
            widthPx = bitmap.width,
            heightPx = bitmap.height,
          )
        }
      } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        null
      }
    onCaptured(request, image)
  }
}

private fun UiBuilderDocument.frameDp(key: String, fallback: Int): Int =
  (environment[key] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()?.toInt()?.coerceIn(1, MAX_DP)
    ?: fallback

private const val DEFAULT_WIDTH_DP = 360
private const val DEFAULT_HEIGHT_DP = 640

/** A frame no bigger than a large tablet: a capture is a crop for the model, not an export. */
private const val MAX_DP = 1600
