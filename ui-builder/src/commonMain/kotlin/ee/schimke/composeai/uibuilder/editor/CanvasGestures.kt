@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A right-click, and where in this element it landed.
 *
 * Compose has no context-click gesture in common code, so this reads the pointer stream directly.
 * It watches the [PointerEventPass.Initial] pass and consumes the press, so a right-click on a
 * layer row does not also start the drag the same row listens for with the left button.
 */
internal fun Modifier.onSecondaryClick(key: Any?, onClick: (Offset) -> Unit): Modifier =
  pointerInput(key) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
          val position = event.changes.firstOrNull()?.position ?: Offset.Zero
          event.changes.forEach { it.consume() }
          onClick(position)
        }
      }
    }
  }

/**
 * The press that becomes a canvas move: hold a node still, then carry it, and land or cancel it on
 * release.
 *
 * Moving a layer is the deliberate act, not the default one — the default is to drop components
 * into slots, where the component's own defaults and the container's layout decide what happens. So
 * the drag arms only after the platform's long-press time with the pointer still: a press that
 * moves sooner is a scroll or a tap, and belongs to the gesture underneath. That friction is the
 * point — a canvas that rearranged itself under every hurried swipe would be a canvas nobody trusts
 * — and the ghost appearing after the hold is what says the node is now in hand.
 *
 * A tap must stay a tap, so nothing is consumed until the hold lands — the selection tap underneath
 * still answers a still press, and a press on empty ground is left to the workspace's scroll. Once
 * a node is picked up every change is consumed, which is what keeps the scroll from chasing the
 * drag; a gesture somebody else took first (a pin, a menu) is left alone entirely.
 *
 * [hitTest] is asked about the **press** position, not the current one: the node picked up is the
 * node that was under the finger, however far the design has scrolled since.
 *
 * **Except with a mouse, over the selection.** Selecting is already the deliberate act the hold
 * stands in for, and a mouse has a wheel to scroll with, so a mouse press inside the selected node
 * is carried as soon as it moves past the slop — the way every desktop design tool moves the thing
 * you just clicked. A finger still holds first: on a touch screen a swipe over the selection is as
 * likely to be a scroll as anything else.
 */
internal fun Modifier.canvasNodeDrag(
  key: Any?,
  enabled: Boolean,
  /** The press/drag position in this element's local space, as a point in editor root space. */
  rootPoint: (Offset) -> Offset,
  /** The deepest node containing a root-space point, or null when the point is over nothing. */
  hitTest: (Offset) -> String?,
  /** Whether a root-space point is inside the current selection, where a mouse needs no hold. */
  insideSelection: (Offset) -> Boolean = { false },
  onStarted: (String, Offset) -> Unit,
  onDragged: (Offset) -> Unit,
  onEnded: (Offset?) -> Unit,
): Modifier = composed {
  if (!enabled) {
    Modifier
  } else {
    val currentRootPoint = rememberUpdatedState(rootPoint)
    val currentHitTest = rememberUpdatedState(hitTest)
    val currentInsideSelection = rememberUpdatedState(insideSelection)
    val currentOnStarted = rememberUpdatedState(onStarted)
    val currentOnDragged = rememberUpdatedState(onDragged)
    val currentOnEnded = rememberUpdatedState(onEnded)
    Modifier.pointerInput(key) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val immediate =
          down.type != PointerType.Touch &&
            currentInsideSelection.value(currentRootPoint.value(down.position))
        // The hold: wait out the long-press timeout with the pointer still and unconsumed. A press
        // that is released, taken by somebody else, or moved past the slop before the timeout ends
        // the gesture here, and whatever is underneath — a tap, the workspace's scroll — gets it.
        val armed =
          if (immediate) {
            // No wait: the press is armed the moment it moves, and a release before that is the
            // selection tap underneath.
            awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } != null
          } else
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
              var outcome: Boolean? = null
              while (outcome == null) {
                val event = awaitPointerEvent()
                // A right-drag belongs to the context menu, which took the press in the initial
                // pass.
                if (event.buttons.isSecondaryPressed) {
                  outcome = false
                  break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || change.isConsumed) {
                  outcome = false
                  break
                }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                  outcome = false
                  break
                }
              }
              outcome ?: false
            } ?: true
        if (!armed) return@awaitEachGesture
        val node =
          currentHitTest.value(currentRootPoint.value(down.position)) ?: return@awaitEachGesture
        var lastRoot = currentRootPoint.value(down.position)
        var lastLocal = down.position
        currentOnStarted.value(node, lastRoot)
        try {
          while (true) {
            val event = awaitPointerEvent()
            if (event.buttons.isSecondaryPressed) {
              currentOnEnded.value(null)
              return@awaitEachGesture
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.pressed) {
              change.consume()
              if (change.position != lastLocal) {
                lastLocal = change.position
                lastRoot = currentRootPoint.value(change.position)
                currentOnDragged.value(lastRoot)
              }
            } else {
              change.consume()
              currentOnEnded.value(lastRoot)
              break
            }
          }
        } catch (cancelled: CancellationException) {
          currentOnEnded.value(null)
          throw cancelled
        }
      }
    }
  }
}

/**
 * A stroke width that lands as [screenPx] on screen whatever the canvas is zoomed to.
 *
 * Overlays inside the scaled frame are measured in the design's pixels, so a width written in them
 * thins with the zoom — and the marker that says where a drop will land is exactly the affordance a
 * reader needs most when the design is too small to read. Clamped, because a very small scale would
 * otherwise turn a hairline into a band across the design.
 */
internal fun screenStroke(screenPx: Float, drawScale: Float): Float =
  if (drawScale <= 0f) screenPx else (screenPx / drawScale).coerceIn(screenPx, screenPx * 8f)

/**
 * The step this frame's auto-scroll should take for a pointer at [offsetInView], or zero.
 *
 * The band is an edge zone, not a line: the deeper the pointer is into it, the faster the scroll,
 * so reaching for the edge slows into the stop rather than jumping. Direction is the edge's — the
 * start edge scrolls back, the end edge scrolls on — and an edge with nothing left to give scrolls
 * nothing, which is what lets the frame-step loop above stop instead of spinning.
 */
internal fun edgeAutoScrollDelta(
  offsetInView: Float,
  viewSize: Float,
  band: Float,
  maxStep: Float,
  scroll: ScrollState,
): Float {
  if (maxStep <= 0f) return 0f
  val strength =
    when {
      offsetInView < band -> -1f + offsetInView / band
      offsetInView > viewSize - band -> (offsetInView - (viewSize - band)) / band
      else -> return 0f
    }
  val remaining =
    if (strength < 0f) scroll.value.toFloat() else (scroll.maxValue - scroll.value).toFloat()
  if (remaining <= 0f) return 0f
  return (strength * maxStep).coerceIn(-remaining, remaining)
}

/** How close to a workspace edge a drag begins to scroll, and how fast it scrolls there. */
internal val DRAG_AUTO_SCROLL_BAND_DP = 56f
internal val DRAG_AUTO_SCROLL_SPEED_DP = 720f

internal fun String.toPresenceColor(): Color {
  val hex = removePrefix("#")
  val argb = hex.takeIf { it.length == 8 }?.toULongOrNull(16) ?: return Color(0xff7788aa)
  return Color(argb.toInt())
}
