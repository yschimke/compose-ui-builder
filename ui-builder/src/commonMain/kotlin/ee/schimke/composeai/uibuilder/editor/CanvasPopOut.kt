package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderDetachedFrom
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderUnrolledHorizontal
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvas.renderDensity
import ee.schimke.composeai.uibuilder.canvas.selectionChain
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A container whose content scrolls on the device, and which way it scrolls. */
internal data class ScrollingContainer(val nodeId: String, val horizontal: Boolean)

/**
 * The innermost scrolling container that is [nodeId] or holds it, or null where nothing between it
 * and the root scrolls.
 *
 * Innermost, because that is the viewport clipping the selection: a chip in a lazy row inside a
 * lazy column is hidden by the row long before the column has anything to say about it.
 */
internal fun UiBuilderDocument.scrollingContainerOf(nodeId: String?): ScrollingContainer? =
  selectionChain(nodeId).firstNotNullOfOrNull { id ->
    val node = nodes[id] ?: return@firstNotNullOfOrNull null
    val scrolls =
      node.modifiers.mapNotNull {
        ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
      }
    when {
      node.componentId in VERTICAL_SCROLLERS -> ScrollingContainer(id, horizontal = false)
      node.componentId in HORIZONTAL_SCROLLERS -> ScrollingContainer(id, horizontal = true)
      "verticalScroll" in scrolls -> ScrollingContainer(id, horizontal = false)
      "horizontalScroll" in scrolls -> ScrollingContainer(id, horizontal = true)
      else -> null
    }
  }

private val VERTICAL_SCROLLERS = setOf("layout/lazy-column", "layout/lazy-grid")

private val HORIZONTAL_SCROLLERS = setOf("layout/lazy-row", "layout/horizontal-carousel")

/**
 * One scrolling container of the device frame, drawn beside it unrolled and editable.
 *
 * The device view clips a list at the frame the way the device does, which is the point of it and
 * also exactly what makes a long list hard to edit there: the rows past the fold are somewhere
 * nobody can click. So the container the selection is in comes out whole — a vertical one at the
 * width it was measured at in the frame and as tall as its content, a horizontal one at its
 * measured height and as wide as its content — as its own [UiBuilderSurface] rooted at that
 * container. Same node ids, so a click in here selects in the editor exactly as a click in the
 * frame does, and the frame then scrolls to it.
 *
 * Its own `renderSessionId`, for the reason [ConstrainedFramePane] has one: the surface keys its
 * geometry on it, and sharing the frame's would have the two overwrite each other.
 */
@Composable
internal fun UnrolledContainerPopOut(
  document: UiBuilderDocument,
  container: ScrollingContainer,
  label: String,
  /** The container's measured cross-axis size in the frame, in the design's dp. */
  crossDp: Float,
  /** Its unrolled main-axis size from the last measurement, in the design's dp. */
  extentDp: Float,
  scale: Float,
  /** The design's pixels per workspace pixel — see the same value in [PinnedDesignCanvas]. */
  densityRatio: Float,
  /** How far down the workspace the pop-out starts, so it opens level with its container. */
  topOffset: Dp,
  selectedNodeId: String?,
  onNodeSelected: (String) -> Unit,
  onExtentMeasured: (Float) -> Unit,
  /** The drawn surface's box in root pixels, unclipped — where the connectors land. */
  onRootBounds: (Rect) -> Unit,
) {
  val designDensity = document.renderDensity(LocalDensity.current).density
  val detached =
    remember(document, container.nodeId) { document.copy(roots = listOf(container.nodeId)) }
  val (widthDp, heightDp) = if (container.horizontal) extentDp to crossDp else crossDp to extentDp
  val drawScale = scale / densityRatio
  Column(Modifier.padding(top = topOffset)) {
    // Outside the scaled layer, like a variant pane's label, so it reads at the size it was written
    // however far the design is zoomed out.
    Text(
      label,
      Modifier.height(VARIANT_LABEL_ROOM_DP.dp).widthIn(max = maxOf(widthDp * scale, 120f).dp),
      color = MaterialTheme.colorScheme.primary,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Box(Modifier.size((widthDp * scale).dp, (heightDp * scale).dp)) {
      Surface(
        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
          // Only the cross axis is fixed. The main axis is left unbounded for the content to grow
          // along, which is the whole difference between this and the frame beside it.
          .then(
            if (container.horizontal) Modifier.requiredHeight((crossDp * densityRatio).dp)
            else Modifier.requiredWidth((crossDp * densityRatio).dp)
          )
          .onSizeChanged { size ->
            onExtentMeasured(
              (if (container.horizontal) size.width else size.height) / designDensity
            )
          }
          .graphicsLayer {
            scaleX = drawScale
            scaleY = drawScale
            transformOrigin = TransformOrigin(0f, 0f)
            compositingStrategy = CompositingStrategy.Offscreen
          }
          .onGloballyPositioned { onRootBounds(it.unclippedRootBounds()) }
          .semantics { contentDescription = "Unrolled $label" },
        shape = RoundedCornerShape(0.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
      ) {
        CompositionLocalProvider(
          LocalUiBuilderDetachedFrom provides document,
          LocalUiBuilderUnrolledHorizontal provides container.horizontal,
        ) {
          UiBuilderSurface(
            document = detached,
            editorOverlay = true,
            selectedNodeId = selectedNodeId,
            onNodeSelected = onNodeSelected,
            renderSessionId = POP_OUT_SESSION,
            unrolled = true,
          )
        }
      }
    }
  }
}

/** Keeps the pop-out's remembered geometry out of the frame's. */
private const val POP_OUT_SESSION = "unrolled-pop-out"

/**
 * The container's clipped box in the frame, and the two lines that carry it out to its pop-out.
 *
 * The same gesture a design tool's zoom callout makes: the eye follows the lines from the few rows
 * the device shows to the whole list beside it, and knows without being told that they are one
 * thing. Drawn over the workspace in root pixels — the space both boxes already report in — and
 * with no pointer input, so every press goes through to what is under it.
 */
@Composable
internal fun PopOutConnectors(
  /** The container's own box, unclipped, in root pixels. */
  container: Rect?,
  /** The device frame's box in root pixels, which clips the container. */
  frame: Rect,
  popOut: Rect?,
  modifier: Modifier = Modifier,
) {
  val from = container?.intersect(frame)?.takeIf { it.width > 0f && it.height > 0f } ?: return
  val to = popOut ?: return
  val color = MaterialTheme.colorScheme.primary
  var origin by remember { mutableStateOf(Offset.Zero) }
  Canvas(modifier.onGloballyPositioned { origin = it.positionInRoot() }.clearAndSetSemantics {}) {
    val stroke = 1.5.dp.toPx()
    val a = from.translate(-origin)
    val b = to.translate(-origin)
    drawRect(color, a.topLeft, a.size, style = Stroke(stroke))
    drawRect(color.copy(alpha = 0.6f), b.topLeft, b.size, style = Stroke(stroke))
    drawLine(color.copy(alpha = 0.8f), a.topRight, b.topLeft, stroke)
    drawLine(color.copy(alpha = 0.8f), a.bottomRight, b.bottomLeft, stroke)
  }
}

/** A box in root pixels that a clipping ancestor has not cut down to what is on screen. */
internal fun LayoutCoordinates.unclippedRootBounds(): Rect =
  Rect(
    localToRoot(Offset.Zero),
    localToRoot(Offset(size.width.toFloat(), size.height.toFloat())),
  )
