package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.renderer.sdk.rememberLiveWallClock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The face being drawn: its `width` ? `height` in WFF pixels, and the dp one pixel is drawn as.
 * Phase 1 placeholder; the Remote Compose player preview replaces it.
 */
private class WffFace(val widthPx: Float, val heightPx: Float, val dpPerPx: Float)

private val LocalWffFace = compositionLocalOf { WffFace(450f, 450f, 0.5f) }

/** The clock, date and complication data the face is drawn against. */
private val LocalWffPreview = compositionLocalOf { WffPreviewContext() }

/**
 * A drawing of a Watch Face Format node from its absolute bounds: shapes, text with its templates
 * filled from a preview clock, and rotated groups, cut to the face's shape. Not what the watch
 * draws, but close: a face reads as itself here.
 */
@Composable
internal fun WatchFaceCanvas(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  modifier: Modifier,
  child: @Composable (String, Modifier) -> Unit,
) {
  val face = LocalWffFace.current
  val preview = LocalWffPreview.current
  // In ambient mode the node as its `<Variant mode="AMBIENT">` children make it.
  @Suppress("NAME_SHADOWING") val node = wffNodeIn(document, node, preview)
  fun place(id: String) = wffPlace(document, id, face.widthPx, face.heightPx, face.dpPerPx, preview)
  val placed = { slot: String ->
    node.slots[slot].orEmpty().filter { id ->
      val candidate = document.nodes[id]
      candidate != null &&
        candidate.componentId != WatchFaceXmlExporter.VARIANT &&
        candidate.componentId != WatchFaceXmlExporter.RAW &&
        wffVisibleIn(wffNodeIn(document, candidate, preview), preview)
    }
  }
  when (node.componentId) {
    WatchFaceXmlExporter.SCENE -> {
      val width = node.float("width", 450f).coerceAtLeast(1f)
      val height = node.float("height", 450f).coerceAtLeast(1f)
      // The real clock, unless the environment fixes the time: then it is frozen at that.
      val fixedTime = (document.environment["fixedTime"] as? JsonPrimitive)?.contentOrNull.orEmpty()
      val ambient = document.isWffAmbient()
      val live =
        rememberLiveWallClock(
          continuous = remember(document.nodes) { document.wffReadsSubSecond() } && !ambient,
          enabled = fixedTime.isBlank(),
        )
      val context =
        remember(node, fixedTime, live, ambient) {
          (live?.let { WffPreviewContext.of(it) }
              ?: WffPreviewContext.of(node.string("previewTime"), fixedTime))
            .inMode(ambient)
        }
      val clip =
        when (node.string("clipShape").ifEmpty { "CIRCLE" }) {
          "CIRCLE" -> CircleShape
          // A square watch's screen has rounded corners; "NONE" draws the whole frame.
          "RECTANGLE" -> RoundedCornerShape(percent = 8)
          else -> RoundedCornerShape(0.dp)
        }
      BoxWithConstraints(
        modifier
          .clip(clip)
          .background(if (ambient) Color.Black else node.color("backgroundColor", Color.Black)),
      ) {
        val scale = maxWidth.value / width
        CompositionLocalProvider(
          LocalWffFace provides WffFace(width, height, scale),
          LocalWffPreview provides context,
        ) {
          Box(Modifier.fillMaxSize()) {
            placed("content").forEach { id ->
              child(id, wffPlace(document, id, width, height, scale, context))
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
      // The shapes the part draws, in order: its `wff/shape` children, else the legacy properties
      // of the part itself as one shape.
      val shapes =
        node.slots["content"]
          .orEmpty()
          .mapNotNull { document.nodes[it] }
          .filter { it.componentId == WatchFaceXmlExporter.SHAPE }
          .map { wffNodeIn(document, it, preview) }
          .filter { wffVisibleIn(it, preview) }
          .map { shape ->
            fun paint(component: String) =
              shape.slots["content"]
                .orEmpty()
                .mapNotNull { document.nodes[it] }
                .firstOrNull { it.componentId == component }
                ?.let { wffNodeIn(document, it, preview) }
            val fillNode = paint(WatchFaceXmlExporter.FILL)
            val strokeNode = paint(WatchFaceXmlExporter.STROKE)
            WffShapeDrawing(
              kind = shape.string("shape").ifEmpty { "ellipse" },
              fill = fillNode?.color("color", Color.Unspecified) ?: Color.Unspecified,
              stroke = strokeNode?.color("color", Color.Unspecified) ?: Color.Unspecified,
              strokeWidth = (strokeNode?.float("thickness", 1f) ?: 1f) * face.dpPerPx,
              ends = listOf("startX", "startY", "endX", "endY").map { shape.float(it) },
              box = shapeBox(shape),
              cornerX = shape.float("cornerRadiusX"),
              cornerY = shape.float("cornerRadiusY"),
            )
          }
          .ifEmpty {
            listOf(
              WffShapeDrawing(
                kind = node.string("shape").ifEmpty { "ellipse" },
                fill = node.color("fillColor", Color.Unspecified),
                stroke = node.color("strokeColor", Color.Unspecified),
                strokeWidth = node.float("strokeWidth", 1f) * face.dpPerPx,
                ends = listOf("startX", "startY", "endX", "endY").map { node.float(it) },
                box = null,
                cornerX = node.float("cornerRadiusX"),
                cornerY = node.float("cornerRadiusY"),
              ),
            )
          }
      Box(
        modifier.drawBehind {
          val k = face.dpPerPx.dp.toPx()
          for (drawing in shapes) drawWffShape(drawing, k)
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
    WatchFaceXmlExporter.PART_TEXT -> {
      val text =
        remember(node, preview) {
          val template = node.string("text")
          val parameters =
            node.string("parameters").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
          // A part with only parameters prints the first, as WFF's own `%s` default would.
          fillWffTemplate(template.ifEmpty { "%s" }, parameters, preview)
        }
      WffText(
        modifier,
        text,
        node.color("color", Color.White),
        (node.float("fontSize", 32f) * face.dpPerPx).sp,
        node.string("align"),
      )
    }
    WatchFaceXmlExporter.TIME_TEXT ->
      WffText(
        modifier.fillMaxSize(),
        previewTime(
          node.string("format").ifEmpty { "hh:mm" },
          preview,
          twelveHour = node.string("hourFormat") == "12",
        ),
        node.color("color", Color.White),
        (node.float("fontSize", 48f) * face.dpPerPx).sp,
        node.string("align").ifEmpty { "CENTER" },
      )
    WatchFaceXmlExporter.ANALOG_CLOCK -> {
      val hands =
        node.slots["hands"]
          .orEmpty()
          .mapNotNull { document.nodes[it] }
          .map { wffNodeIn(document, it, preview) }
          .filter { wffVisibleIn(it, preview) }
      // Resolved here: a colour token needs the composition, the draw lambda has none.
      val tints = hands.map { it.color("tintColor", Color.White) }
      Box(
        modifier.drawBehind {
          hands.forEachIndexed { index, hand ->
            val degrees =
              when (hand.string("type")) {
                "hour" -> (preview.hour % 12 + preview.minute / 60f) * 30f
                "minute" -> (preview.minute + preview.second / 60f) * 6f
                else -> preview.second * 6f
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
    WatchFaceXmlExporter.COMPLICATION_SLOT -> {
      // The slot's own complication (the one for its default type, else the first) draws what the
      // face says to; a slot with none draws one line of sample data in its bounds.
      val variants =
        node.slots["variants"]
          .orEmpty()
          .mapNotNull { document.nodes[it] }
          .filter { it.componentId == WatchFaceXmlExporter.COMPLICATION }
      val wanted = node.string("defaultSystemProviderType")
      val drawn = variants.firstOrNull { it.string("type") == wanted } ?: variants.firstOrNull()
      val sample = WffComplicationSample.forProvider(node.string("defaultSystemProvider"))
      val context = remember(preview, sample) { preview.withComplication(sample) }
      CompositionLocalProvider(LocalWffPreview provides context) {
        Box(
          modifier.clipToBounds(),
          contentAlignment = Alignment.Center,
        ) {
          if (drawn != null) child(drawn.id, Modifier.fillMaxSize())
          else {
            Box(
              Modifier.fillMaxSize()
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(percent = 20)),
            )
            WffText(
              Modifier.fillMaxSize(),
              sample.line,
              Color(0xCCFFFFFF),
              (24f * face.dpPerPx).sp,
              "CENTER",
            )
          }
        }
      }
    }
    // A condition draws its first branch, the one a preview with no data takes.
    WatchFaceXmlExporter.CONDITION ->
      Box(modifier) {
        node.slots["branches"]
          .orEmpty()
          .firstOrNull { document.nodes[it]?.componentId != WatchFaceXmlExporter.RAW }
          ?.let { id -> child(id, Modifier.fillMaxSize()) }
      }
    WatchFaceXmlExporter.COMPARE,
    WatchFaceXmlExporter.DEFAULT,
    WatchFaceXmlExporter.COMPLICATION ->
      Box(modifier) { placed("content").forEach { id -> child(id, place(id)) } }
    // Hands are drawn by their clock, variants are overrides: neither has a box of its own.
    else -> Box(modifier.size(0.dp))
  }
}

/** One line of face text in its box: clipped to it, never wrapped, aligned as WFF's `align`. */
@Composable
private fun WffText(
  modifier: Modifier,
  text: String,
  color: Color,
  size: androidx.compose.ui.unit.TextUnit,
  align: String,
) {
  val (box, textAlign) =
    when (align) {
      "START",
      "LEFT" -> Alignment.CenterStart to TextAlign.Start
      "END",
      "RIGHT" -> Alignment.CenterEnd to TextAlign.End
      else -> Alignment.Center to TextAlign.Center
    }
  Box(modifier.clipToBounds(), contentAlignment = box) {
    Text(
      text,
      color = color,
      fontSize = size,
      lineHeight = size,
      textAlign = textAlign,
      maxLines = 1,
      softWrap = false,
      overflow = TextOverflow.Clip,
    )
  }
}

private fun wffPlace(
  document: UiBuilderDocument,
  id: String,
  sceneWidth: Float,
  sceneHeight: Float,
  scale: Float,
  preview: WffPreviewContext,
): Modifier {
  val target = document.nodes[id]?.let { wffNodeIn(document, it, preview) } ?: return Modifier
  val box =
    Modifier.offset((target.float("x") * scale).dp, (target.float("y") * scale).dp)
      .size(
        (target.float("width", sceneWidth) * scale).dp,
        (target.float("height", sceneHeight) * scale).dp,
      )
  val degrees = wffAngle(document, target, preview)
  val alpha = target.float("alpha", 255f).coerceIn(0f, 255f) / 255f
  return if (degrees == 0f && alpha == 1f) box
  else
    box.graphicsLayer {
      rotationZ = degrees
      this.alpha = alpha
      transformOrigin = TransformOrigin(target.float("pivotX", 0.5f), target.float("pivotY", 0.5f))
    }
}

private val TRANSFORM_ATTRIBUTE = Regex("""(\w+)\s*=\s*"([^"]*)"""")

/**
 * The rotation of a node, in degrees: its static `angle`, or the `angle` a `<Transform>` among its
 * children drives, evaluated at the preview time. A `<Transform>` is carried as a `wff/raw` child.
 */
private fun wffAngle(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  preview: WffPreviewContext,
): Float {
  var degrees = node.float("angle")
  for (id in node.slots.values.flatten()) {
    val raw = document.nodes[id]?.takeIf { it.componentId == WatchFaceXmlExporter.RAW } ?: continue
    val xml = raw.string("xml")
    if (!xml.trimStart().startsWith("<Transform")) continue
    val attributes =
      TRANSFORM_ATTRIBUTE.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    if (attributes["target"] != "angle") continue
    val value =
      attributes["value"]?.let { evaluateWffExpression(it, preview) }?.asNumber() ?: continue
    degrees = if (attributes["mode"] == "BY") degrees + value.toFloat() else value.toFloat()
  }
  return degrees
}

/** One shape of a part, resolved for drawing. */
internal class WffShapeDrawing(
  val kind: String,
  val fill: Color,
  val stroke: Color,
  val strokeWidth: Float,
  val ends: List<Float>,
  /** x, y, width, height of the shape inside its part, px of the face; null for the whole part. */
  val box: List<Float>?,
  /** The corner radii of a `round-rectangle`, px of the face. */
  val cornerX: Float = 0f,
  val cornerY: Float = 0f,
)

/** A shape node's box: its own `x`/`y`/`width`/`height`, or an arc's from its centre. */
internal fun shapeBox(shape: UiBuilderNode): List<Float>? {
  val width = shape.float("width", -1f)
  val height = shape.float("height", -1f)
  if (width < 0 || height < 0) return null
  return if (shape.string("shape") == "arc")
    listOf(shape.float("centerX") - width / 2, shape.float("centerY") - height / 2, width, height)
  else listOf(shape.float("x"), shape.float("y"), width, height)
}

/** `hh:mm` and friends, filled with the preview time. */
private fun previewTime(format: String, preview: WffPreviewContext, twelveHour: Boolean): String {
  val hour =
    if (twelveHour) (if (preview.hour % 12 == 0) 12 else preview.hour % 12) else preview.hour
  return format
    .replace("hh", hour.toString().padStart(2, '0'))
    .replace("mm", preview.minute.toString().padStart(2, '0'))
    .replace("ss", preview.second.toString().padStart(2, '0'))
}

/**
 * Draws [drawing] into this scope: [pxPerWffPx] canvas pixels to a face pixel. A shape with a box
 * draws only inside it (fill and stroke both), else it fills the whole scope, as the part does.
 */
internal fun DrawScope.drawWffShape(drawing: WffShapeDrawing, pxPerWffPx: Float) {
  val shape = drawing.kind
  if (shape == "none") return
  val ellipse = shape == "ellipse" || shape == "arc"
  val rounded = shape == "round-rectangle"
  val line = Stroke(width = drawing.strokeWidth.dp.toPx())
  if (shape == "line") {
    if (drawing.stroke != Color.Unspecified) {
      val k = pxPerWffPx
      drawLine(
        drawing.stroke,
        Offset(drawing.ends[0] * k, drawing.ends[1] * k),
        Offset(drawing.ends[2] * k, drawing.ends[3] * k),
        strokeWidth = line.width,
      )
    }
    return
  }
  val box = drawing.box
  val origin = if (box == null) Offset.Zero else Offset(box[0] * pxPerWffPx, box[1] * pxPerWffPx)
  val extent = if (box == null) size else Size(box[2] * pxPerWffPx, box[3] * pxPerWffPx)
  if (drawing.fill != Color.Unspecified) {
    if (ellipse) drawOval(drawing.fill, origin, extent)
    else if (rounded) drawRoundRect(drawing.fill, origin, extent, corner(drawing, pxPerWffPx))
    else drawRect(drawing.fill, origin, extent)
  }
  if (drawing.stroke != Color.Unspecified) {
    // Inset by half the line so the stroke stays inside the shape's bounds, as WFF draws it.
    val inset = line.width / 2
    val topLeft = Offset(origin.x + inset, origin.y + inset)
    val inner = Size(extent.width - line.width, extent.height - line.width)
    if (ellipse) drawOval(drawing.stroke, topLeft, inner, style = line)
    else if (rounded)
      drawRoundRect(drawing.stroke, topLeft, inner, corner(drawing, pxPerWffPx), style = line)
    else drawRect(drawing.stroke, topLeft, inner, style = line)
  }
}

private fun corner(drawing: WffShapeDrawing, pxPerWffPx: Float) =
  CornerRadius(
    drawing.cornerX * pxPerWffPx,
    drawing.cornerY.takeIf { it > 0f }?.times(pxPerWffPx) ?: (drawing.cornerX * pxPerWffPx),
  )

/** Whether this is a watch face whose screen is a circle, so the surface under it must be one. */
internal fun UiBuilderDocument.isRoundWatchFace(): Boolean {
  val scene = roots.firstNotNullOfOrNull { nodes[it] } ?: return false
  return scene.componentId == WatchFaceXmlExporter.SCENE &&
    scene.string("clipShape").ifEmpty { "CIRCLE" } == "CIRCLE"
}
