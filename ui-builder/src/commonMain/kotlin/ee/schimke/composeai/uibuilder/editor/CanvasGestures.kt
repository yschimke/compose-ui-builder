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
 * A right-click and where it landed. Common Compose has no context-click gesture, so this reads the
 * [PointerEventPass.Initial] pass and consumes the press so it does not also start a left-button
 * drag.
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
 * The press that becomes a canvas move: hold a node still, then carry it, landing or cancelling on
 * release.
 *
 * Moving is deliberate, so the drag arms only after the long-press timeout with the pointer still;
 * earlier movement is a scroll or tap for the gesture underneath. Nothing is consumed until then,
 * and everything after. [hitTest] is asked about the press position.
 *
 * **Except a mouse over the selection**: selecting was already deliberate and a mouse scrolls with
 * its wheel, so it carries as soon as it passes the slop. Touch still holds first.
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
        // Wait out the long-press timeout with the pointer still; a release, a claim by another
        // gesture, or moving past the slop ends it here.
        val armed =
          if (immediate) {
            // No wait: armed the moment it moves; a release before that is the selection tap.
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
        // A finger covers the node it is holding, so the ghost appearing under it is not enough to
        // say the hold landed: a short buzz does, where the device has one.
        if (down.type == PointerType.Touch) pickupHaptic()
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
 * A stroke width that lands as [screenPx] on screen at any zoom, clamped so tiny scales do not make
 * bands.
 */
internal fun screenStroke(screenPx: Float, drawScale: Float): Float =
  if (drawScale <= 0f) screenPx else (screenPx / drawScale).coerceIn(screenPx, screenPx * 8f)

/**
 * This frame's auto-scroll step for a pointer at [offsetInView], or zero. Faster deeper into the
 * edge band; an exhausted edge returns zero so the frame loop stops.
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

/** A short pulse saying a held node is in hand; a no-op where there is no vibration motor. */
internal expect fun pickupHaptic()
