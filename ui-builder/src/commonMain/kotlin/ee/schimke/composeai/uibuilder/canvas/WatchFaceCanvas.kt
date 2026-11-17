package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter

/**
 * The face being drawn: its `width` × `height` in WFF pixels, and the dp one pixel is drawn as.
 * Phase 1 placeholder; the Remote Compose player preview replaces it.
 */
private class WffFace(val widthPx: Float, val heightPx: Float, val dpPerPx: Float)

private val LocalWffFace = compositionLocalOf { WffFace(450f, 450f, 0.5f) }

/** The time every placeholder clock shows: the template's `PREVIEW_TIME`. */
private const val PREVIEW_HOUR = 10
private const val PREVIEW_MINUTE = 8
private const val PREVIEW_SECOND = 32

/**
 * A rough drawing of a Watch Face Format node from its absolute bounds: labelled boxes, shapes and
 * clock-hand rectangles rotated to the preview time. Not what the watch draws.
 */
@Composable
internal fun WatchFaceCanvas(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  modifier: Modifier,
  child: @Composable (String, Modifier) -> Unit,
) {
  val face = LocalWffFace.current
  fun place(id: String) = wffPlace(document, id, face.widthPx, face.heightPx, face.dpPerPx)
  val placed = { slot: String ->
    node.slots[slot].orEmpty().filter { id ->
      document.nodes[id]?.componentId != WatchFaceXmlExporter.VARIANT
    }
  }
  when (node.componentId) {
    WatchFaceXmlExporter.SCENE -> {
      val width = node.float("width", 450f).coerceAtLeast(1f)
      val height = node.float("height", 450f).coerceAtLeast(1f)
      BoxWithConstraints(modifier.background(node.color("backgroundColor", Color.Black))) {
        val scale = maxWidth.value / width
        CompositionLocalProvider(LocalWffFace provides WffFace(width, height, scale)) {
          Box(Modifier.fillMaxSize()) {
            placed("content").forEach { id ->
              child(id, wffPlace(document, id, width, height, scale))
            }
          }
        }
      }
    }
    WatchFaceXmlExporter.GROUP,
    WatchFaceXmlExporter.DIGITAL_CLOCK ->
      Box(modifier) {
        placed(if (node.componentId == WatchFaceXmlExporter.GROUP) "content" else "texts")
          .forEach { id -> child(id, place(id)) }
      }
    WatchFaceXmlExporter.PART_DRAW -> {
      val fill = node.color("fillColor", Color.Unspecified)
      val stroke = node.color("strokeColor", Color.Unspecified)
      val strokeWidth = node.float("strokeWidth", 1f) * face.dpPerPx
      val ellipse = node.string("shape").ifEmpty { "ellipse" } == "ellipse"
      Box(
        modifier.drawBehind {
          val size = this.size
          val line = Stroke(width = strokeWidth.dp.toPx())
          if (fill != Color.Unspecified) {
            if (ellipse) drawOval(fill) else drawRect(fill)
          }
          if (stroke != Color.Unspecified) {
            // Inset by half the line so the stroke stays inside the part's bounds, as WFF draws it.
            val inset = line.width / 2
            val topLeft = Offset(inset, inset)
            val inner = Size(size.width - line.width, size.height - line.width)
            if (ellipse) drawOval(stroke, topLeft, inner, style = line)
            else drawRect(stroke, topLeft, inner, style = line)
          }
        },
      )
    }
    WatchFaceXmlExporter.PART_IMAGE ->
      Box(modifier.border(1.dp, Color(0x66FFFFFF)), contentAlignment = Alignment.Center) {
        Text(
          node.string("resource").ifEmpty { "image" },
          color = Color(0x99FFFFFF),
          fontSize = 10.sp,
        )
      }
    WatchFaceXmlExporter.PART_TEXT ->
      Box(modifier, contentAlignment = Alignment.Center) {
        Text(
          node.string("text"),
          color = node.color("color", Color.White),
          fontSize = (node.float("fontSize", 32f) * face.dpPerPx).sp,
        )
      }
    WatchFaceXmlExporter.TIME_TEXT ->
      Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          previewTime(node.string("format").ifEmpty { "hh:mm" }),
          color = node.color("color", Color.White),
          fontSize = (node.float("fontSize", 48f) * face.dpPerPx).sp,
        )
      }
    WatchFaceXmlExporter.ANALOG_CLOCK -> {
      val hands = node.slots["hands"].orEmpty().mapNotNull { document.nodes[it] }
      // Resolved here: a colour token needs the composition, the draw lambda has none.
      val tints = hands.map { it.color("tintColor", Color.White) }
      Box(
        modifier.drawBehind {
          hands.forEachIndexed { index, hand ->
            val degrees =
              when (hand.string("type")) {
                "hour" -> (PREVIEW_HOUR % 12 + PREVIEW_MINUTE / 60f) * 30f
                "minute" -> (PREVIEW_MINUTE + PREVIEW_SECOND / 60f) * 6f
                else -> PREVIEW_SECOND * 6f
              }
            val scale = face.dpPerPx.dp.toPx()
            val x = hand.float("x") * scale
            val y = hand.float("y") * scale
            val w = hand.float("width") * scale
            val h = hand.float("height") * scale
            val pivot =
              Offset(x + hand.float("pivotX", 0.5f) * w, y + hand.float("pivotY", 0.5f) * h)
            rotate(degrees, pivot) { drawRect(tints[index], Offset(x, y), Size(w, h)) }
          }
        },
      )
    }
    WatchFaceXmlExporter.COMPLICATION_SLOT ->
      Box(modifier.border(1.dp, Color(0x6600B0FF)), contentAlignment = Alignment.Center) {
        Text(
          "Complication ${node.float("slotId").toInt()}\n${node.string("supportedTypes")}",
          color = Color(0xFF80D8FF),
          fontSize = 9.sp,
        )
      }
    // Hands are drawn by their clock, variants are overrides: neither has a box of its own.
    else -> Box(modifier.size(0.dp))
  }
}

private fun wffPlace(
  document: UiBuilderDocument,
  id: String,
  sceneWidth: Float,
  sceneHeight: Float,
  scale: Float,
): Modifier {
  val target = document.nodes[id] ?: return Modifier
  return Modifier.offset((target.float("x") * scale).dp, (target.float("y") * scale).dp)
    .size(
      (target.float("width", sceneWidth) * scale).dp,
      (target.float("height", sceneHeight) * scale).dp,
    )
}

/** `hh:mm` and friends, filled with the preview time. */
private fun previewTime(format: String): String =
  format
    .replace("hh", PREVIEW_HOUR.toString().padStart(2, '0'))
    .replace("mm", PREVIEW_MINUTE.toString().padStart(2, '0'))
    .replace("ss", PREVIEW_SECOND.toString().padStart(2, '0'))
