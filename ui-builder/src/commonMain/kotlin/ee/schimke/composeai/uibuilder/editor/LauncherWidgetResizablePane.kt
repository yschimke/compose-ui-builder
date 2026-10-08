package ee.schimke.composeai.uibuilder.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.renderDensity
import ee.schimke.composeai.uibuilder.export.LauncherWidgetGrid
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A launcher widget on a patch of home screen, with a handle at its corner that resizes it the way
 * a launcher's does.
 *
 * The fixed panes beside it answer "what does it look like at 3x2"; this one answers "what happens
 * as somebody drags its handle", which is a question about every size on the way and the step from
 * one to the next.
 *
 * ## Live, in grid units
 *
 * While the handle is held the frame follows it continuously, and the design is laid out at the
 * nearest whole cell count — re-laid out the moment the frame crosses into the next one, because a
 * launcher never hands a widget a size between two counts, so neither does this pane. On release
 * the frame springs onto that count. What moves continuously is only the frame's clip: the design
 * is composed once per cell count, at its reference dp, since each pane draws in a scene of its own
 * that is rebuilt whenever its size changes (see [ConstrainedFramePane]).
 */
@Composable
internal fun LauncherWidgetResizablePane(
  pane: UiBuilderVariantPane,
  scale: Float,
  hostDensity: Density,
  deviceRenderer: UiBuilderCanvasRenderer? = null,
  positionVersion: Int = 0,
) {
  val document = pane.document
  val start =
    remember(document.id) {
      clampLauncherSize(document.launcherGridSize() ?: LauncherWidgetGrid.DEFAULT)
    }
  // The cell count the design is laid out at, and the frame's own extent in fractional cells.
  var size by remember(document.id) { mutableStateOf(start) }
  val columns = remember(document.id) { Animatable(start.columns.toFloat()) }
  val rows = remember(document.id) { Animatable(start.rows.toFloat()) }
  val scope = rememberCoroutineScope()

  val cellWidth = LauncherWidgetGrid.CELL_WIDTH_DP.toFloat()
  val cellHeight = LauncherWidgetGrid.CELL_HEIGHT_DP.toFloat()
  val inset = LauncherWidgetGrid.CELL_MARGIN_DP / 2f
  val margin = LauncherWidgetGrid.CELL_MARGIN_DP.toFloat()
  val gridColour = MaterialTheme.colorScheme.outlineVariant
  val handleColour = MaterialTheme.colorScheme.primary
  val pixelsPerDp = LocalDensity.current.density

  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      "${pane.label} · ${launcherSizeLabel(size)}",
      Modifier.height(VARIANT_LABEL_ROOM_DP.dp).widthIn(max = (pane.widthDp * scale).dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Box(Modifier.size((pane.widthDp * scale).dp, (pane.heightDp * scale).dp)) {
      // The home screen's cells, each the room one cell's worth of widget sits in: drawn so a
      // resize reads as the widget taking or giving up a cell rather than as a box changing size.
      Canvas(Modifier.size((pane.widthDp * scale).dp, (pane.heightDp * scale).dp)) {
        val unit = scale * density
        for (column in 0 until LAUNCHER_RESIZE_MAX.columns) {
          for (row in 0 until LAUNCHER_RESIZE_MAX.rows) {
            drawRoundRect(
              color = gridColour,
              topLeft =
                Offset((column * cellWidth + inset) * unit, (row * cellHeight + inset) * unit),
              size = Size((cellWidth - margin) * unit, (cellHeight - margin) * unit),
              cornerRadius = CornerRadius(LAUNCHER_CORNER_DP * unit),
              style = Stroke(width = 1.dp.toPx()),
            )
          }
        }
      }
      // The widget: its frame where the handle is, its design at the nearest cell count.
      Box(
        Modifier.offset((inset * scale).dp, (inset * scale).dp)
          .size(
            ((cellWidth * columns.value - margin) * scale).dp,
            ((cellHeight * rows.value - margin) * scale).dp,
          )
          .clip(RoundedCornerShape((LAUNCHER_CORNER_DP * scale).dp))
          .background(MaterialTheme.colorScheme.surfaceVariant)
      ) {
        // Unbounded, so the design keeps its own size inside a frame that is between two counts.
        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)) {
          ConstrainedFramePane(
            document = document.atLauncherSize(size),
            widthDp = size.widthDp.toFloat(),
            heightDp = size.heightDp.toFloat(),
            scale = scale,
            densityRatio = document.renderDensity(hostDensity).density / hostDensity.density,
            renderSessionId = pane.id,
            deviceRenderer = deviceRenderer,
            positionVersion = positionVersion,
          )
        }
      }
      // The resize handle, on the frame's corner wherever it is.
      var dragStart by remember { mutableStateOf(size) }
      var dragged by remember { mutableStateOf(Offset.Zero) }
      Box(
        Modifier.offset(
            ((inset + cellWidth * columns.value - margin) * scale).dp - LAUNCHER_HANDLE_DP.dp / 2,
            ((inset + cellHeight * rows.value - margin) * scale).dp - LAUNCHER_HANDLE_DP.dp / 2,
          )
          .size(LAUNCHER_HANDLE_DP.dp)
          .background(handleColour, CircleShape)
          .pointerHoverIcon(PointerIcon.Crosshair)
          .semantics { contentDescription = "Resize widget" }
          .pointerInput(scale, pixelsPerDp) {
            // Let go, the frame springs onto the cell count the design is already laid out at.
            val settle = {
              scope.launch { columns.animateTo(size.columns.toFloat(), LAUNCHER_SNAP) }
              scope.launch { rows.animateTo(size.rows.toFloat(), LAUNCHER_SNAP) }
              Unit
            }
            detectDragGestures(
              onDragStart = {
                dragStart = size
                dragged = Offset.Zero
              },
              onDragEnd = settle,
              onDragCancel = settle,
              onDrag = { change, delta ->
                change.consume()
                dragged += delta
                val columnsMoved = dragged.x / (cellWidth * scale * pixelsPerDp)
                val rowsMoved = dragged.y / (cellHeight * scale * pixelsPerDp)
                size = launcherSizeAfterDrag(dragStart, columnsMoved, rowsMoved)
                val maxColumns = LAUNCHER_RESIZE_MAX.columns.toFloat()
                val maxRows = LAUNCHER_RESIZE_MAX.rows.toFloat()
                scope.launch {
                  columns.snapTo((dragStart.columns + columnsMoved).coerceIn(1f, maxColumns))
                }
                scope.launch { rows.snapTo((dragStart.rows + rowsMoved).coerceIn(1f, maxRows)) }
              },
            )
          }
      )
    }
  }
}

/**
 * Where a drag of the resize handle that has moved [columnsMoved] and [rowsMoved] cells from [from]
 * lands: the nearest whole cell count, held on the patch.
 */
internal fun launcherSizeAfterDrag(
  from: LauncherWidgetGrid.Size,
  columnsMoved: Float,
  rowsMoved: Float,
): LauncherWidgetGrid.Size =
  LauncherWidgetGrid.Size(
    (from.columns + columnsMoved.roundToInt()).coerceIn(1, LAUNCHER_RESIZE_MAX.columns),
    (from.rows + rowsMoved.roundToInt()).coerceIn(1, LAUNCHER_RESIZE_MAX.rows),
  )

/** How the frame lands on a cell count when the handle is let go. */
private val LAUNCHER_SNAP =
  spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)

/**
 * The corner radius launchers clip a widget to (Android 12's
 * `system_app_widget_background_radius`).
 */
private const val LAUNCHER_CORNER_DP = 16f

/** The resize handle's diameter, in workspace dp: a touch target, so not scaled with the design. */
private const val LAUNCHER_HANDLE_DP = 16f
