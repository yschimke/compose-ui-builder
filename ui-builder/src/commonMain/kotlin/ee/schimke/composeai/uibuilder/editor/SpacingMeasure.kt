package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Figma's Alt/Option redlines: with a node selected, hold Alt and hover another node to read the
 * gaps between them in dp. Nothing here touches the document — it is a look, not an edit — and it
 * needs a hovering mouse, so on touch it simply never appears.
 */

/** One measured gap, in the design's dp: a red line from [start] to [end] labelled [dp]. */
internal data class SpacingSegment(val start: Offset, val end: Offset, val dp: Float)

/**
 * A dashed extension line in dp. Drawn when a gap is measured at a height (or column) the hovered
 * node does not reach, so the eye can follow the gap back to the edge it was measured from.
 */
internal data class SpacingGuide(val start: Offset, val end: Offset)

internal data class SpacingMeasurement(
  val segments: List<SpacingSegment>,
  val guides: List<SpacingGuide> = emptyList(),
) {
  companion object {
    val None = SpacingMeasurement(emptyList())
  }
}

/**
 * The gaps between [selected] and [hovered], both in dp, the way Figma answers them:
 * - one inside the other (the parent, or a child): the four insets between the inner box and the
 *   outer box's edges, drawn through the inner box's centre;
 * - apart: the gap between their nearest edges on each axis they are apart on, drawn through the
 *   middle of the span they share, or through the selection's centre when they share none (with a
 *   dashed guide back to the hovered node);
 * - overlapping without containment: the offsets between their matching edges, drawn through the
 *   middle of the overlap.
 *
 * Zero-length gaps are left out: a shared edge has nothing to label.
 */
internal fun spacingMeasurement(selected: Rect, hovered: Rect): SpacingMeasurement {
  if (selected.isEmptyArea() || hovered.isEmptyArea() || selected.near(hovered)) {
    return SpacingMeasurement.None
  }
  val outer =
    when {
      hovered.encloses(selected) -> hovered
      selected.encloses(hovered) -> selected
      else -> null
    }
  if (outer != null) {
    val inner = if (outer === hovered) selected else hovered
    val cx = inner.center.x
    val cy = inner.center.y
    return SpacingMeasurement(
      listOfNotNull(
        segment(Offset(outer.left, cy), Offset(inner.left, cy)),
        segment(Offset(inner.right, cy), Offset(outer.right, cy)),
        segment(Offset(cx, outer.top), Offset(cx, inner.top)),
        segment(Offset(cx, inner.bottom), Offset(cx, outer.bottom)),
      )
    )
  }
  val apartX = selected.right <= hovered.left || hovered.right <= selected.left
  val apartY = selected.bottom <= hovered.top || hovered.bottom <= selected.top
  if (!apartX && !apartY) {
    // Overlapping, neither inside the other: how far each edge is from its counterpart.
    val overlap = selected.intersect(hovered)
    val cx = overlap.center.x
    val cy = overlap.center.y
    return SpacingMeasurement(
      listOfNotNull(
        segment(
          Offset(min(selected.left, hovered.left), cy),
          Offset(max(selected.left, hovered.left), cy),
        ),
        segment(
          Offset(min(selected.right, hovered.right), cy),
          Offset(max(selected.right, hovered.right), cy),
        ),
        segment(
          Offset(cx, min(selected.top, hovered.top)),
          Offset(cx, max(selected.top, hovered.top)),
        ),
        segment(
          Offset(cx, min(selected.bottom, hovered.bottom)),
          Offset(cx, max(selected.bottom, hovered.bottom)),
        ),
      )
    )
  }
  val segments = mutableListOf<SpacingSegment>()
  val guides = mutableListOf<SpacingGuide>()
  if (apartX) {
    val sharedTop = max(selected.top, hovered.top)
    val sharedBottom = min(selected.bottom, hovered.bottom)
    val y = if (sharedTop < sharedBottom) (sharedTop + sharedBottom) / 2f else selected.center.y
    val hoveredOnRight = hovered.left >= selected.right
    val (from, to) =
      if (hoveredOnRight) selected.right to hovered.left else hovered.right to selected.left
    segment(Offset(from, y), Offset(to, y))?.let(segments::add)
    if (y < hovered.top || y > hovered.bottom) {
      val edgeX = if (hoveredOnRight) hovered.left else hovered.right
      guides +=
        SpacingGuide(Offset(edgeX, y.coerceIn(hovered.top, hovered.bottom)), Offset(edgeX, y))
    }
  }
  if (apartY) {
    val sharedLeft = max(selected.left, hovered.left)
    val sharedRight = min(selected.right, hovered.right)
    val x = if (sharedLeft < sharedRight) (sharedLeft + sharedRight) / 2f else selected.center.x
    val hoveredBelow = hovered.top >= selected.bottom
    val (from, to) =
      if (hoveredBelow) selected.bottom to hovered.top else hovered.bottom to selected.top
    segment(Offset(x, from), Offset(x, to))?.let(segments::add)
    if (x < hovered.left || x > hovered.right) {
      val edgeY = if (hoveredBelow) hovered.top else hovered.bottom
      guides +=
        SpacingGuide(Offset(x.coerceIn(hovered.left, hovered.right), edgeY), Offset(x, edgeY))
    }
  }
  return SpacingMeasurement(segments, guides)
}

/** "16", "12.5": a dp value as a label, to a tenth, without a trailing ".0". */
internal fun formatSpacingDp(dp: Float): String {
  val tenths = (dp * 10f).roundToInt()
  return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${abs(tenths % 10)}"
}

/** Edges this close count as the same edge: bounds come back from pixels and are not exact. */
private const val EDGE_EPSILON_DP = 0.05f

private fun segment(start: Offset, end: Offset): SpacingSegment? {
  val length = (end - start).getDistance()
  return if (length < EDGE_EPSILON_DP) null else SpacingSegment(start, end, length)
}

private fun Rect.isEmptyArea() = width <= 0f || height <= 0f

private fun Rect.encloses(other: Rect) =
  left <= other.left + EDGE_EPSILON_DP &&
    top <= other.top + EDGE_EPSILON_DP &&
    right >= other.right - EDGE_EPSILON_DP &&
    bottom >= other.bottom - EDGE_EPSILON_DP

private fun Rect.near(other: Rect) =
  abs(left - other.left) < EDGE_EPSILON_DP &&
    abs(top - other.top) < EDGE_EPSILON_DP &&
    abs(right - other.right) < EDGE_EPSILON_DP &&
    abs(bottom - other.bottom) < EDGE_EPSILON_DP

/**
 * Whether Alt (Option) is down. Written from two places because neither alone is enough: a pointer
 * event carries the modifiers, but releasing Alt with the mouse still sends no pointer event, so
 * the editor's key handler writes it too ([onMeasureKey]). A release the editor misses (Alt-Tab to
 * another window) is corrected by the next pointer move.
 */
internal object MeasureModifierKey {
  var altHeld by mutableStateOf(false)
}

/** The pointer over the frame, in the frame's own pixels, while a mouse hovers it. */
@Stable
internal class MeasureHoverState {
  var point by mutableStateOf<Offset?>(null)
}

/**
 * Follows a hovering mouse over the frame for [MeasureHoverState]. Reads the initial pass and
 * consumes nothing, so the taps, drags and menus underneath see every event as before. Only mouse
 * hover counts: a touch has no hover, so nothing is ever measured from one.
 */
internal fun Modifier.measureHover(state: MeasureHoverState): Modifier =
  pointerInput(state) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull() ?: continue
        println()
        if (change.type != PointerType.Mouse) continue
        when (event.type) {
          PointerEventType.Exit -> state.point = null
          PointerEventType.Enter,
          PointerEventType.Move -> {
            MeasureModifierKey.altHeld = event.keyboardModifiers.isAltPressed
            // Kept while Alt is up too, so pressing it measures at once without a nudge. The
            // overlay derives the hovered node from this, so a move alone recomposes nothing.
            state.point = change.position
          }
        }
      }
    }
  }

/**
 * The editor's key handler reports every key here. The Alt keys themselves are read by their key
 * type rather than the modifier flag, which platforms disagree on for the Alt key's own events.
 * Never consumes: Alt still reaches the shortcuts and the browser.
 */
internal fun onMeasureKey(event: KeyEvent) {
  val held =
    if (event.key == Key.AltLeft || event.key == Key.AltRight) event.type == KeyEventType.KeyDown
    else event.isAltPressed
  if (MeasureModifierKey.altHeld != held) MeasureModifierKey.altHeld = held
}

/**
 * Draws [spacingMeasurement] between the selection and the node under the pointer while Alt is
 * held. Hovering the selection itself (or empty space inside it) measures it against its parent, as
 * Figma does. Strokes and labels are screen-sized, like every other canvas overlay, so they stay
 * legible zoomed out.
 */
@Composable
internal fun SpacingMeasureOverlay(
  inspection: UiBuilderInspectionSnapshot?,
  selectedNodeId: String?,
  parentNodeId: String?,
  hover: MeasureHoverState,
  /** Where the frame's own top-left is in root space, as for the presence overlay. */
  frameOrigin: Offset,
  drawScale: Float,
  /** Root pixels per design dp: the workspace density times the zoom. */
  pxPerDp: Float,
) {
  if (selectedNodeId == null || inspection == null || pxPerDp <= 0f) return
  val boundsByNode =
    remember(inspection) { inspection.nodes.mapNotNull { n -> n.bounds?.let { n.nodeId to it } } }
  // Derived, so the overlay recomposes when the hovered node changes rather than on every move.
  val target by
    remember(boundsByNode, selectedNodeId, parentNodeId, frameOrigin, drawScale) {
      derivedStateOf {
        val point = hover.point ?: return@derivedStateOf null
        if (!MeasureModifierKey.altHeld) return@derivedStateOf null
        val root = Offset(frameOrigin.x + point.x * drawScale, frameOrigin.y + point.y * drawScale)
        val hit =
          boundsByNode
            .filter { (_, b) ->
              root.x >= b.x && root.x <= b.right && root.y >= b.y && root.y <= b.bottom
            }
            .minByOrNull { (_, b) -> b.width * b.height }
            ?.first
        if (hit == null || hit == selectedNodeId) parentNodeId else hit
      }
    }
  val hoveredId = target ?: return
  val byId = boundsByNode.toMap()
  val selected = byId[selectedNodeId]?.toDpRect(frameOrigin, pxPerDp) ?: return
  val hovered = byId[hoveredId]?.toDpRect(frameOrigin, pxPerDp) ?: return
  val measurement = remember(selected, hovered) { spacingMeasurement(selected, hovered) }
  val textMeasurer = rememberTextMeasurer()
  val labelStyle = MaterialTheme.typography.labelSmall
  // Design dp to the overlay's own pixels: the frame is drawn at `drawScale` of root pixels.
  val localPerDp = pxPerDp / drawScale
  val screen = screenStroke(1f, drawScale)
  val description =
    "Spacing to $hoveredId: " +
      measurement.segments.joinToString { "${formatSpacingDp(it.dp)}dp" }.ifEmpty { "none" }
  Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
    fun local(p: Offset) = Offset(p.x * localPerDp, p.y * localPerDp)
    drawRect(
      color = MEASURE_RED,
      topLeft = local(hovered.topLeft),
      size = Size(hovered.width * localPerDp, hovered.height * localPerDp),
      style = Stroke(width = screen),
    )
    measurement.guides.forEach { guide ->
      drawLine(
        color = MEASURE_RED,
        start = local(guide.start),
        end = local(guide.end),
        strokeWidth = screen,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * screen, 3f * screen), 0f),
      )
    }
    measurement.segments.forEach { segment ->
      val start = local(segment.start)
      val end = local(segment.end)
      drawLine(MEASURE_RED, start, end, strokeWidth = screen)
      // End ticks across the line, so a short gap still reads as a measured span.
      val horizontal = abs(end.x - start.x) >= abs(end.y - start.y)
      val tick = if (horizontal) Offset(0f, 4f * screen) else Offset(4f * screen, 0f)
      listOf(start, end).forEach {
        drawLine(MEASURE_RED, it - tick, it + tick, strokeWidth = screen)
      }
      val text =
        textMeasurer.measure(
          formatSpacingDp(segment.dp),
          labelStyle.copy(
            color = Color.White,
            fontSize = labelStyle.fontSize * screen,
            textAlign = TextAlign.Center,
          ),
          maxLines = 1,
        )
      val padX = 4f * screen
      val padY = 1f * screen
      val pill = Size(text.size.width + 2 * padX, text.size.height + 2 * padY)
      val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
      // Beside a vertical line and below a horizontal one, so the label never hides the line.
      val pillTopLeft =
        if (horizontal) Offset(mid.x - pill.width / 2f, mid.y + 3f * screen)
        else Offset(mid.x + 3f * screen, mid.y - pill.height / 2f)
      drawRoundRect(MEASURE_RED, pillTopLeft, pill, CornerRadius(2f * screen))
      drawText(text, topLeft = Offset(pillTopLeft.x + padX, pillTopLeft.y + padY))
    }
  }
}

private fun UiBuilderPixelBounds.toDpRect(origin: Offset, pxPerDp: Float) =
  Rect(
    (x - origin.x) / pxPerDp,
    (y - origin.y) / pxPerDp,
    (right - origin.x) / pxPerDp,
    (bottom - origin.y) / pxPerDp,
  )

/** Figma's redline red: distinct from the theme's selection colour on purpose. */
private val MEASURE_RED = Color(0xFFF24822)
