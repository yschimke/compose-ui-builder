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
 * The zoom the canvas asks for, anchored: [scale] is the new zoom, and [focus] is the point in the
 * workspace — pointer or pinch centre, in workspace pixels — that has to stay under the hand.
 */
internal data class CanvasZoomRequest(val scale: Float, val focus: Offset)

/**
 * The scale one notch of Ctrl/Cmd + wheel moves to, from [from].
 *
 * Exponential in the delta, so a smooth trackpad stream and a clicky wheel reach the same zoom for
 * the same travel, and zooming in then out by the same amount lands back where it started. A
 * browser reports a trackpad pinch as exactly this — a wheel event with Ctrl held — so this one
 * rule serves both.
 */
internal fun wheelZoom(from: Float, deltaY: Float): Float =
  // Capped per event: platforms disagree about a notch's size — a line on the desktop, a hundred
  // pixels in some browsers — and one event should never be a jump of several stops.
  (from * exp(-deltaY.coerceIn(-MAX_WHEEL_DELTA, MAX_WHEEL_DELTA) * WHEEL_ZOOM_RATE)).coerceIn(
    MIN_CANVAS_ZOOM,
    MAX_CANVAS_ZOOM,
  )

/**
 * Where a scroll offset has to go so the content point that was under [focus] at [oldScale] is
 * under it again at [newScale]: the content scales about the origin, the point under the hand is
 * `scroll + focus`, and it moves by the same ratio.
 */
internal fun anchoredScroll(scroll: Float, focus: Float, oldScale: Float, newScale: Float): Float =
  ((scroll + focus) * (newScale / oldScale) - focus).coerceAtLeast(0f)

/**
 * Pinch and Ctrl/Cmd + wheel, on the workspace.
 *
 * **Initial pass, and only what is ours.** The workspace's own scroll, a node's long-press move and
 * the selection tap all sit under this, so it looks first and takes only the two things nobody else
 * wants: a wheel with Ctrl or Cmd held (a plain wheel still scrolls), and a gesture with two or
 * more fingers down (one finger still scrolls, taps and holds). Two fingers also pan, because a
 * pinch that moves is how a hand says both at once.
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
