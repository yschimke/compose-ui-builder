package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.exp

/**
 * The zoom the canvas asks for: [scale], and the workspace-pixel [focus] (pointer or pinch centre)
 * that must stay under the hand.
 */
internal data class CanvasZoomRequest(val scale: Float, val focus: Offset)

/**
 * The scale one notch of Ctrl/Cmd + wheel moves to from [from]. Exponential in the delta, so
 * trackpad and wheel agree and in-then-out returns to start; browsers report trackpad pinch the
 * same way.
 */
internal fun wheelZoom(from: Float, deltaY: Float): Float =
  // Capped per event: platforms disagree about a notch's size — a line on the desktop, a hundred
  // pixels in some browsers — and one event should never be a jump of several stops.
  (from * exp(-deltaY.coerceIn(-MAX_WHEEL_DELTA, MAX_WHEEL_DELTA) * WHEEL_ZOOM_RATE)).coerceIn(
    MIN_CANVAS_ZOOM,
    MAX_CANVAS_ZOOM,
  )

/**
 * A zoom in flight: the requested [scale], the root-space [focus], and the [designPoint] under it
 * (frame offset divided by the scale it was measured at).
 */
internal data class ZoomAnchor(val scale: Float, val focus: Offset, val designPoint: Offset)

/**
 * How far to scroll so [designPoint] is under [focus] again at [frameOrigin] and [scale]; positive
 * when the point landed right of or below the hand. Scroll clamping keeps small designs centred.
 */
internal fun anchorDrift(
  frameOrigin: Offset,
  designPoint: Offset,
  scale: Float,
  focus: Offset,
): Offset = frameOrigin + designPoint * scale - focus

/**
 * Pinch and Ctrl/Cmd + wheel on the workspace. Reads the initial pass and takes only Ctrl/Cmd
 * wheels and multi-finger gestures (which also pan), leaving plain wheels, single-finger scrolls,
 * taps and holds to the gestures underneath.
 */
internal fun Modifier.canvasZoomGestures(
  scale: Float,
  onZoom: (CanvasZoomRequest) -> Unit,
  onPan: (Offset) -> Unit,
): Modifier = composed {
  val currentScale by rememberUpdatedState(scale)
  val currentOnZoom by rememberUpdatedState(onZoom)
  val currentOnPan by rememberUpdatedState(onPan)
  this.pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          if (event.type != PointerEventType.Scroll) continue
          if (!event.keyboardModifiers.isCtrlPressed && !event.keyboardModifiers.isMetaPressed) {
            continue
          }
          val change = event.changes.firstOrNull() ?: continue
          val deltaY = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
          if (deltaY == 0f) continue
          event.changes.forEach { it.consume() }
          val next = wheelZoom(currentScale, deltaY)
          if (next != currentScale) currentOnZoom(CanvasZoomRequest(next, change.position))
        }
      }
    }
    .pointerInput(Unit) {
      awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // The scale this pinch is multiplying, kept locally: the zoom it asks for comes back as
        // recomposition, which may lag the next move event.
        var pinched = currentScale
        do {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val down = event.changes.count { it.pressed }
          if (down >= 2) {
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val centroid = event.calculateCentroid(useCurrent = true)
            event.changes.forEach { it.consume() }
            if (zoom != 1f) {
              val next = (pinched * zoom).coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM)
              if (next != pinched) {
                pinched = next
                currentOnZoom(CanvasZoomRequest(next, centroid))
              }
            }
            if (pan != Offset.Zero) currentOnPan(pan)
          } else {
            pinched = currentScale
          }
        } while (event.changes.any { it.pressed })
      }
    }
}

/** How fast a wheel delta zooms: one 1.0-unit notch is about 12%. */
private const val WHEEL_ZOOM_RATE = 0.12f

/** The largest wheel delta one event counts for: about 43% of zoom. */
private const val MAX_WHEEL_DELTA = 3f
