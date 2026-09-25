package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlin.math.roundToInt

/** Which edges one handle moves. */
/**
 * Which edges one handle moves.
 *
 * In drawing order, which is hit-test order in reverse: the corner first, so where its target
 * overlaps an edge's on a small node the single-axis handle — the one the dot under the pointer is
 * — wins.
 */
internal enum class ResizeHandle(val width: Boolean, val height: Boolean, val label: String) {
  Corner(width = true, height = true, label = "size"),
  Bottom(width = false, height = true, label = "height"),
  End(width = true, height = false, label = "width"),
}

/**
 * Where a handle drag in flight would leave the node: its outer box, in root pixels, and what each
 * axis would be written as — null where that axis is not being changed.
 */
internal data class ResizePreview(
  val widthPx: Float,
  val heightPx: Float,
  val width: EditorSizing?,
  val height: EditorSizing?,
)

/**
 * The new size a handle dragged by ([dx], [dy]) root pixels asks for.
 *
 * Pulled out of the composable so the snapping is a function of numbers, testable without a
 * pointer. An edge taken to within [snapPx] of the parent's edge — or past it — snaps to Fill,
 * which is the easy way to `fillMaxWidth` the issue asked for: drag the side to the side. An axis
 * the catalog will not let be a number only ever snaps, and otherwise stays as it was.
 */
internal fun resizePreview(
  handle: ResizeHandle,
  sizing: EditorNodeSizing,
  bounds: UiBuilderPixelBounds,
  parentBounds: UiBuilderPixelBounds?,
  dx: Float,
  dy: Float,
  pxPerDp: Float,
  snapPx: Float,
  /** The node's own `scale` per axis, which its measured box already includes. */
  nodeScaleX: Float = 1f,
  nodeScaleY: Float = 1f,
): ResizePreview {
  fun axis(
    active: Boolean,
    axis: EditorAxisSizing,
    start: Float,
    size: Float,
    delta: Float,
    parentEnd: Float?,
    nodeScale: Float,
  ): Pair<Float, EditorSizing?> {
    if (!active) return size to null
    val raw = (size + delta).coerceAtLeast(pxPerDp)
    if (axis.canFill && parentEnd != null && start + raw >= parentEnd - snapPx) {
      return (parentEnd - start).coerceAtLeast(pxPerDp) to EditorSizing.Fill
    }
    if (!axis.canFix) return size to null
    return raw to EditorSizing.Fixed((raw / (pxPerDp * nodeScale)).roundToInt().toFloat())
  }
  val (widthPx, width) =
    axis(handle.width, sizing.width, bounds.x, bounds.width, dx, parentBounds?.right, nodeScaleX)
  val (heightPx, height) =
    axis(
      handle.height,
      sizing.height,
      bounds.y,
      bounds.height,
      dy,
      parentBounds?.bottom,
      nodeScaleY,
    )
  return ResizePreview(widthPx, heightPx, width, height)
}

/**
 * What a double-click on a handle does: Fill if the axis is not already filling, Hug if it is — the
 * two ends of the axis, one gesture apart. A corner fills both, or hugs both once both fill.
 */
internal fun resizeToggle(
  handle: ResizeHandle,
  sizing: EditorNodeSizing,
): Pair<EditorSizing?, EditorSizing?> {
  val filling =
    (!handle.width || sizing.width.current == EditorSizing.Fill || !sizing.width.canFill) &&
      (!handle.height || sizing.height.current == EditorSizing.Fill || !sizing.height.canFill)
  fun toggle(active: Boolean, axis: EditorAxisSizing): EditorSizing? =
    when {
      !active -> null
      filling -> EditorSizing.Hug
      axis.canFill -> EditorSizing.Fill
      else -> null
    }
  return toggle(handle.width, sizing.width) to toggle(handle.height, sizing.height)
}

/**
 * The selection's resize handles, on its end edge, its bottom edge and the corner between them.
 *
 * **Screen-sized, outside the design.** The handles sit in the workspace over the scaled frame,
 * like the hover editor, so a handle is a finger's width however far the design is zoomed out — the
 * visible dot is small, the target around it is not. Only the end and bottom edges: a composable is
 * laid out by its parent, so moving the start or top edge would be moving the node, which is a
 * different gesture with its own drop marker.
 *
 * Drag one and the new box is drawn dashed with its size beside it; release to write it. Take the
 * edge to the parent's edge and it snaps to **Fill**. Double-click (or double-tap) a handle to flip
 * that axis between Fill and Hug. Nothing is written while the drag is in the air — one release is
 * one edit, one undo step and one round to every collaborator.
 */
@Composable
internal fun ResizeHandles(
  sizing: EditorNodeSizing,
  /** The selected node's box, in root pixels. */
  bounds: UiBuilderPixelBounds,
  /** Its parent's box, in root pixels, which is where Fill snaps — or null for none. */
  parentBounds: UiBuilderPixelBounds?,
  /** Where this overlay's own top-left is in root pixels. */
  origin: Offset,
  /** Root pixels per design dp, at the zoom the canvas is drawn at. */
  pxPerDp: Float,
  /** The node's own `scale` per axis — see [drawnScale]. */
  nodeScale: Pair<Float, Float> = 1f to 1f,
  onResizing: (Boolean) -> Unit,
  onResize: (EditorSizing?, EditorSizing?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val snapPx = with(density) { RESIZE_SNAP.toPx() }
  val targetPx = with(density) { HANDLE_TARGET.toPx() }
  var preview by remember(sizing.nodeId) { mutableStateOf<ResizePreview?>(null) }
  val current = rememberUpdatedState(Triple(sizing, bounds, parentBounds))
  val currentPxPerDp = rememberUpdatedState(pxPerDp)
  val currentNodeScale = rememberUpdatedState(nodeScale)
  val currentOnResize = rememberUpdatedState(onResize)
  val currentOnResizing = rememberUpdatedState(onResizing)
  val color = MaterialTheme.colorScheme.primary
  val left = bounds.x - origin.x
  val top = bounds.y - origin.y
  Box(modifier) {
    preview?.let { shown ->
      Canvas(Modifier.matchParentSize()) {
        val stroke = 1.5.dp.toPx()
        drawRoundRect(
          color = color,
          topLeft = Offset(left, top),
          size = Size(shown.widthPx, shown.heightPx),
          cornerRadius = CornerRadius(2.dp.toPx()),
          style =
            Stroke(
              width = stroke,
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f),
            ),
        )
      }
      val readout =
        listOfNotNull(
            shown.width?.let { "W ${it.label()}" },
            shown.height?.let { "H ${it.label()}" },
          )
          .joinToString("  ")
      if (readout.isNotEmpty()) {
        Surface(
          Modifier.offset {
            IntOffset(
              (left + shown.widthPx + 8.dp.toPx()).roundToInt(),
              (top + shown.heightPx + 8.dp.toPx()).roundToInt(),
            )
          },
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.primary,
        ) {
          Text(
            readout,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }
    ResizeHandle.entries.forEach { handle ->
      val offered =
        (!handle.width || sizing.width.resizable) && (!handle.height || sizing.height.resizable)
      if (!offered) return@forEach
      // A corner on a line of text would sit on top of the end handle and take its presses; on a
      // box that small the two edges are the handles, one at a time.
      if (
        handle == ResizeHandle.Corner &&
          (bounds.width < targetPx * 2f || bounds.height < targetPx * 2f)
      ) {
        return@forEach
      }
      val centre =
        Offset(
          x = left + if (handle.width) bounds.width else bounds.width / 2f,
          y = top + if (handle.height) bounds.height else bounds.height / 2f,
        )
      Box(
        Modifier.offset {
            IntOffset(
              (centre.x - targetPx / 2f).roundToInt(),
              (centre.y - targetPx / 2f).roundToInt(),
            )
          }
          .size(HANDLE_TARGET)
          .semantics {
            contentDescription = "Resize ${handle.label}"
            onClick(label = "Toggle fill ${handle.label}") {
              val (width, height) = resizeToggle(handle, current.value.first)
              currentOnResize.value(width, height)
              true
            }
          }
          .pointerInput(handle) {
            detectTapGestures(
              onDoubleTap = {
                val (width, height) = resizeToggle(handle, current.value.first)
                currentOnResize.value(width, height)
              }
            )
          }
          .pointerInput(handle) {
            var dx = 0f
            var dy = 0f
            fun measure() {
              val (s, b, p) = current.value
              val (scaleX, scaleY) = currentNodeScale.value
              preview =
                resizePreview(
                  handle,
                  s,
                  b,
                  p,
                  dx,
                  dy,
                  currentPxPerDp.value,
                  snapPx,
                  scaleX,
                  scaleY,
                )
            }
            detectDragGestures(
              onDragStart = {
                dx = 0f
                dy = 0f
                currentOnResizing.value(true)
                measure()
              },
              onDrag = { change, amount ->
                change.consume()
                dx += amount.x
                dy += amount.y
                measure()
              },
              onDragEnd = {
                val landed = preview
                preview = null
                currentOnResizing.value(false)
                if (landed != null && (landed.width != null || landed.height != null)) {
                  currentOnResize.value(landed.width, landed.height)
                }
              },
              onDragCancel = {
                preview = null
                currentOnResizing.value(false)
              },
            )
          },
        contentAlignment = Alignment.Center,
      ) {
        Surface(
          Modifier.size(if (handle == ResizeHandle.Corner) 10.dp else 8.dp),
          shape =
            if (handle == ResizeHandle.Corner) RoundedCornerShape(2.dp) else RoundedCornerShape(50),
          color = MaterialTheme.colorScheme.surface,
          border = androidx.compose.foundation.BorderStroke(1.5.dp, color),
          shadowElevation = 1.dp,
        ) {}
      }
    }
  }
}

/** How close to the parent's edge a dragged edge has to come to snap to Fill, on screen. */
private val RESIZE_SNAP = 12.dp

/** A handle's hit target: small dot, finger-sized target. */
private val HANDLE_TARGET = 28.dp
