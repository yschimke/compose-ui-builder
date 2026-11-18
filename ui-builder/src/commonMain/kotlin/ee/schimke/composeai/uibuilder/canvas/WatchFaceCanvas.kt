package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
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
 * Whether the face is drawn for an export whose recorder writes the canvas out as SVG, which keeps
 * only what reaches the canvas as plain drawing:
 * - a rotated element is turned while it draws (a canvas `rotate` around its content), not by a
 *   `graphicsLayer`, whose transform such a recorder can drop — which left every index and hand of
 *   a rotated `wff/group` at its unrotated place;
 * - a sweep gradient, which SVG has no element for, is drawn as thin wedges of its colours.
 *
 * The editor leaves it off: its hit testing and selection boxes read a layer's rotation from the
 * layout, and a shader draws a sweep in one pass.
 */
internal val LocalWffCanvasExport = staticCompositionLocalOf { false }

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
  val forExport = LocalWffCanvasExport.current
  fun place(id: String) =
    wffPlace(document, id, face.widthPx, face.heightPx, face.dpPerPx, preview, forExport)
  val placed = { slot: String ->
    node.slots[slot].orEmpty().filter { id ->
      document.nodes[id]?.componentId.let {
        it != WatchFaceXmlExporter.VARIANT && it != WatchFaceXmlExporter.RAW
      }
    }
  }
  when (node.componentId) {
    WatchFaceXmlExporter.SCENE -> {
      val width = node.float("width", 450f).coerceAtLeast(1f)
      val height = node.float("height", 450f).coerceAtLeast(1f)
      val context =
        remember(node, document.environment) {
          WffPreviewContext.of(
            node.string("previewTime"),
            (document.environment["fixedTime"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
          )
        }
      val configurations =
        remember(document) { WffConfigurationState(document.wffConfigurations()) }
      val clip =
        when (node.string("clipShape").ifEmpty { "CIRCLE" }) {
          "CIRCLE" -> CircleShape
          // A square watch's screen has rounded corners; "NONE" draws the whole frame.
          "RECTANGLE" -> RoundedCornerShape(percent = 8)
          else -> RoundedCornerShape(0.dp)
        }
      // The face keeps its own aspect ratio, centred in whatever box it is given: cut to a circle
      // in a landscape box it would be an ellipse, and its pixels would be drawn off-scale. The
      // configurations are provided around the whole face, so the scene's own background reads a
      // `[CONFIGURATION.<id>.<n>]` colour as its parts do.
      CompositionLocalProvider(LocalWffConfigurations provides configurations) {
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
          val scale = minOf(maxWidth.value / width, maxHeight.value / height)
          CompositionLocalProvider(
            LocalWffFace provides WffFace(width, height, scale),
            LocalWffPreview provides context,
          ) {
            // The editing canvas draws the screen's shape (a faint outline, and the background
            // inside it) but does not cut the parts to it, so content outside the circle can be
            // seen and selected. The preview and the exports are cut.
            val editing = LocalUiBuilderEditingCanvas.current
            val background = node.wffColor("backgroundColor", Color.Black)
            val face = Modifier.size((width * scale).dp, (height * scale).dp)
            Box(
              if (editing)
                face.background(background, clip).drawWithContent {
                  drawContent()
                  if (clip == CircleShape) {
                    drawCircle(Color(0x33FFFFFF), style = Stroke(1.dp.toPx()))
                  }
                }
              else face.clip(clip).background(background),
            ) {
              placed("content").forEach { id ->
                child(id, wffPlace(document, id, width, height, scale, context, forExport))
              }
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
          .map { shape ->
            fun paint(component: String) =
              shape.slots["content"]
                .orEmpty()
                .mapNotNull { document.nodes[it] }
                .firstOrNull { it.componentId == component }
            val fillNode = paint(WatchFaceXmlExporter.FILL)
            val strokeNode = paint(WatchFaceXmlExporter.STROKE)
            val fillGradient = WffGradient.of(document, fillNode, preview)
            val strokeGradient = WffGradient.of(document, strokeNode, preview)
            WffShapeDrawing(
              kind = shape.string("shape").ifEmpty { "ellipse" },
              // A gradient paints where its colour would; one with no flat colour still draws.
              fill =
                fillNode?.wffColor("color", Color.Unspecified)?.takeIf { it != Color.Unspecified }
                  ?: (if (fillGradient != null) Color.Black else Color.Unspecified),
              stroke =
                strokeNode?.wffColor("color", Color.Unspecified)?.takeIf { it != Color.Unspecified }
                  ?: (if (strokeGradient != null) Color.Black else Color.Unspecified),
              strokeWidth = (strokeNode?.float("thickness", 1f) ?: 1f) * face.dpPerPx,
              ends = listOf("startX", "startY", "endX", "endY").map { shape.float(it) },
              box = shapeBox(shape),
              cornerX = shape.float("cornerRadiusX"),
              cornerY = shape.float("cornerRadiusY"),
              startAngle = shape.float("startAngle"),
              endAngle = shape.float("endAngle", 360f),
              clockwise = shape.string("direction") != "COUNTER_CLOCKWISE",
              fillGradient = fillGradient,
              strokeGradient = strokeGradient,
            )
          }
          .ifEmpty {
            listOf(
              WffShapeDrawing(
                kind = node.string("shape").ifEmpty { "ellipse" },
                fill = node.wffColor("fillColor", Color.Unspecified),
                stroke = node.wffColor("strokeColor", Color.Unspecified),
                strokeWidth = node.float("strokeWidth", 1f) * face.dpPerPx,
                ends = listOf("startX", "startY", "endX", "endY").map { node.float(it) },
                box = legacyArcBox(node),
                cornerX = node.float("cornerRadiusX"),
                cornerY = node.float("cornerRadiusY"),
                startAngle = node.float("startAngle"),
                endAngle = node.float("endAngle", 360f),
                clockwise = node.string("direction") != "COUNTER_CLOCKWISE",
              ),
            )
          }
      Box(
        modifier.drawBehind {
          val k = face.dpPerPx.dp.toPx()
          for (drawing in shapes) drawWffShape(drawing, k, forExport)
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
        node.wffColor("color", Color.White),
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
        node.wffColor("color", Color.White),
        (node.float("fontSize", 48f) * face.dpPerPx).sp,
        node.string("align").ifEmpty { "CENTER" },
      )
    WatchFaceXmlExporter.ANALOG_CLOCK -> {
      val hands = node.slots["hands"].orEmpty().mapNotNull { document.nodes[it] }
      // Resolved here: a colour token needs the composition, the draw lambda has none.
      val tints = hands.map { it.wffColor("tintColor", Color.White) }
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
    // A configuration container draws the parts of the option selected now; the others are not
    // drawn, as the watch does not draw them.
    WatchFaceXmlExporter.SCENE_BOOLEAN_CONFIGURATION,
    WatchFaceXmlExporter.SCENE_LIST_CONFIGURATION -> {
      val state = LocalWffConfigurations.current
      val options =
        node.slots["options"]
          .orEmpty()
          .mapNotNull { document.nodes[it] }
          .filter {
            it.componentId == WatchFaceXmlExporter.SCENE_BOOLEAN_OPTION ||
              it.componentId == WatchFaceXmlExporter.SCENE_LIST_OPTION
          }
      val id = node.wffValue("id")
      val shown =
        state.selected(id)
          ?: if (node.componentId == WatchFaceXmlExporter.SCENE_BOOLEAN_CONFIGURATION) "TRUE"
          else options.firstOrNull()?.wffValue("id")
      Box(modifier) {
        options
          .firstOrNull { it.wffValue("id") == shown }
          ?.slots
          ?.get("content")
          .orEmpty()
          .filter { document.nodes[it]?.componentId != WatchFaceXmlExporter.RAW }
          .forEach { childId -> child(childId, place(childId)) }
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
  drawnRotation: Boolean = false,
): Modifier {
  val target = document.nodes[id] ?: return Modifier
  val box =
    Modifier.offset((target.float("x") * scale).dp, (target.float("y") * scale).dp)
      // At its own size even past its parent's edge: a 500px group at -25,-25 on a 450px face
      // was cut to 450 by the parent's constraints, which moved its centre, the pivot it turns on.
      .wrapContentSize(Alignment.TopStart, unbounded = true)
      .size(
        (target.float("width", sceneWidth) * scale).dp,
        (target.float("height", sceneHeight) * scale).dp,
      )
  val degrees = wffAngle(document, target, preview)
  val alpha = target.float("alpha", 255f).coerceIn(0f, 255f) / 255f
  val pivotX = target.float("pivotX", 0.5f)
  val pivotY = target.float("pivotY", 0.5f)
  return if (degrees == 0f && alpha == 1f) box
  else if (drawnRotation)
    box
      .then(if (alpha == 1f) Modifier else Modifier.graphicsLayer { this.alpha = alpha })
      .then(
        if (degrees == 0f) Modifier
        else
          Modifier.drawWithContent {
            rotate(degrees, Offset(size.width * pivotX, size.height * pivotY)) {
              this@drawWithContent.drawContent()
            }
          },
      )
  else
    box.graphicsLayer {
      rotationZ = degrees
      this.alpha = alpha
      transformOrigin = TransformOrigin(pivotX, pivotY)
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
  /** An `arc`'s ends, WFF degrees: from twelve o'clock, growing clockwise. */
  val startAngle: Float = 0f,
  val endAngle: Float = 360f,
  /** An `arc`'s `direction`. */
  val clockwise: Boolean = true,
  /** A gradient the `<Fill>` or `<Stroke>` holds, painted in place of its flat colour. */
  val fillGradient: WffGradient? = null,
  val strokeGradient: WffGradient? = null,
)

/** A legacy arc part's box, from its `centerX`/`centerY`/`shapeWidth`/`shapeHeight`; else null. */
internal fun legacyArcBox(part: UiBuilderNode): List<Float>? {
  if (part.string("shape") != "arc") return null
  val width = part.float("shapeWidth", -1f)
  val height = part.float("shapeHeight", -1f)
  if (width < 0 || height < 0) return null
  return listOf(
    part.float("centerX") - width / 2,
    part.float("centerY") - height / 2,
    width,
    height,
  )
}

/** A shape node's box: its own `x`/`y`/`width`/`height`, or an arc's from its centre. */
internal fun shapeBox(shape: UiBuilderNode): List<Float>? {
  val width = shape.float("width", -1f)
  val height = shape.float("height", -1f)
  if (width < 0 || height < 0) return null
  return if (shape.string("shape") == "arc")
    listOf(shape.float("centerX") - width / 2, shape.float("centerY") - height / 2, width, height)
  else listOf(shape.float("x"), shape.float("y"), width, height)
}

/**
 * A `<TimeText>` `format` filled with the preview time, read as runs of one letter, as a date
 * pattern is: `h`/`hh` and `H`/`HH` the hour, 1–12 when [twelveHour] (`hourFormat="12"`), else 0–23
 * as the preview has always drawn a synced device; `m`/`mm`, `s`/`ss` the minutes and seconds; `a`
 * AM or PM. A doubled letter pads to two digits. Anything else is drawn as written.
 */
internal fun previewTime(format: String, preview: WffPreviewContext, twelveHour: Boolean): String {
  val twelve = if (preview.hour % 12 == 0) 12 else preview.hour % 12
  return buildString {
    var i = 0
    while (i < format.length) {
      val letter = format[i]
      var end = i
      while (end < format.length && format[end] == letter) end++
      val run = end - i
      fun number(value: Int) = append(if (run >= 2) value.toString().padStart(2, '0') else value)
      when (letter) {
        'h',
        'H' -> number(if (twelveHour) twelve else preview.hour)
        'm' -> number(preview.minute)
        's' -> number(preview.second)
        'a' -> append(if (preview.hour < 12) "AM" else "PM")
        else -> append(format, i, end)
      }
      i = end
    }
  }
}

/**
 * Draws [drawing] into this scope: [pxPerWffPx] canvas pixels to a face pixel. A shape with a box
 * draws only inside it (fill and stroke both), else it fills the whole scope, as the part does.
 */
internal fun DrawScope.drawWffShape(
  drawing: WffShapeDrawing,
  pxPerWffPx: Float,
  /** [LocalWffCanvasExport]: a sweep gradient is drawn as wedges. */
  forExport: Boolean = false,
) {
  val shape = drawing.kind
  if (shape == "none") return
  val ellipse = shape == "ellipse"
  val arc = shape == "arc"
  val rounded = shape == "round-rectangle"
  val line = Stroke(width = drawing.strokeWidth.dp.toPx())
  val fill: Brush? =
    drawing.fillGradient?.brush(pxPerWffPx)
      ?: drawing.fill.takeIf { it != Color.Unspecified }?.let(::SolidColor)
  val stroke: Brush? =
    drawing.strokeGradient?.brush(pxPerWffPx)
      ?: drawing.stroke.takeIf { it != Color.Unspecified }?.let(::SolidColor)
  if (shape == "line") {
    val k = pxPerWffPx
    val start = Offset(drawing.ends[0] * k, drawing.ends[1] * k)
    val end = Offset(drawing.ends[2] * k, drawing.ends[3] * k)
    val sweepLine = drawing.strokeGradient?.takeIf { forExport && it.kind == WffGradient.SWEEP }
    if (sweepLine != null && start != end) {
      // As every other shape's: the area a butt-capped line covers, filled with the wedges.
      val along = end - start
      val normal = Offset(-along.y, along.x) / along.getDistance() * (line.width / 2)
      val band =
        Path().apply {
          moveTo(start.x + normal.x, start.y + normal.y)
          lineTo(end.x + normal.x, end.y + normal.y)
          lineTo(end.x - normal.x, end.y - normal.y)
          lineTo(start.x - normal.x, start.y - normal.y)
          close()
        }
      drawSweepWedges(sweepLine, pxPerWffPx, band)
    } else if (stroke != null) {
      drawLine(
        stroke,
        start,
        end,
        strokeWidth = line.width,
      )
    }
    return
  }
  val box = drawing.box
  val origin = if (box == null) Offset.Zero else Offset(box[0] * pxPerWffPx, box[1] * pxPerWffPx)
  val extent = if (box == null) size else Size(box[2] * pxPerWffPx, box[3] * pxPerWffPx)
  // WFF angles start at twelve o'clock, Compose's at three; both grow clockwise.
  val start = composeAngle(drawing.startAngle)
  val sweep = wffArcSweep(drawing.startAngle, drawing.endAngle, drawing.clockwise)
  if (forExport && drawing.fillGradient?.kind == WffGradient.SWEEP) {
    val area = Path()
    when {
      ellipse -> area.addOval(Rect(origin, extent))
      arc -> area.addArc(Rect(origin, extent), start, sweep)
      rounded -> area.addRoundRect(RoundRect(Rect(origin, extent), corner(drawing, pxPerWffPx)))
      else -> area.addRect(Rect(origin, extent))
    }
    drawSweepWedges(drawing.fillGradient, pxPerWffPx, area)
  } else if (fill != null) {
    if (ellipse) drawOval(fill, origin, extent)
    else if (arc) drawArc(fill, start, sweep, useCenter = false, origin, extent)
    else if (rounded) drawRoundRect(fill, origin, extent, corner(drawing, pxPerWffPx))
    else drawRect(fill, origin, extent)
  }
  if (stroke != null) {
    // Inset by half the line so the stroke stays inside the shape's bounds, as WFF draws it.
    val inset = line.width / 2
    val topLeft = Offset(origin.x + inset, origin.y + inset)
    val inner = Size(extent.width - line.width, extent.height - line.width)
    val band =
      if (forExport && drawing.strokeGradient?.kind == WffGradient.SWEEP) {
        strokeBand(drawing, pxPerWffPx, origin, extent, line.width, start, sweep)
      } else null
    if (band != null) drawSweepWedges(drawing.strokeGradient!!, pxPerWffPx, band)
    else if (ellipse) drawOval(stroke, topLeft, inner, style = line)
    else if (arc) drawArc(stroke, start, sweep, useCenter = false, topLeft, inner, style = line)
    else if (rounded)
      drawRoundRect(stroke, topLeft, inner, corner(drawing, pxPerWffPx), style = line)
    else drawRect(stroke, topLeft, inner, style = line)
  }
}

/**
 * The area a butt-capped stroke of [width] inside the box covers: between the shape and the same
 * shape [width] inside it, or the whole shape when the stroke is that wide. Null for a shape whose
 * stroke is not such a band.
 */
private fun strokeBand(
  drawing: WffShapeDrawing,
  pxPerWffPx: Float,
  origin: Offset,
  extent: Size,
  width: Float,
  start: Float,
  sweep: Float,
): Path? {
  val outer = Rect(origin, extent)
  val inner = outer.deflate(width)
  // A stroke as wide as the shape leaves no hole: it covers the whole shape, an arc's sector.
  if (inner.width <= 0f || inner.height <= 0f)
    return Path().apply {
      when (drawing.kind) {
        "ellipse" -> addOval(outer)
        "arc" -> {
          moveTo(outer.center.x, outer.center.y)
          arcTo(outer, start, sweep, forceMoveTo = false)
          close()
        }
        "rectangle" -> addRect(outer)
        "round-rectangle" ->
          addRoundRect(RoundRect(outer, corner(drawing, pxPerWffPx).grownBy(width / 2)))
        else -> return null
      }
    }
  return Path().apply {
    when (drawing.kind) {
      "ellipse" -> {
        fillType = PathFillType.EvenOdd
        addOval(outer)
        addOval(inner)
      }
      "arc" -> {
        arcTo(outer, start, sweep, forceMoveTo = true)
        arcTo(inner, start + sweep, -sweep, forceMoveTo = false)
        close()
      }
      "rectangle" -> {
        fillType = PathFillType.EvenOdd
        addRect(outer)
        addRect(inner)
      }
      "round-rectangle" -> {
        fillType = PathFillType.EvenOdd
        // The editor strokes the corner's radius centred on the line half a width in, so the
        // band's outside corner is half a width rounder and its inside one half a width tighter.
        val radius = corner(drawing, pxPerWffPx)
        addRoundRect(RoundRect(outer, radius.grownBy(width / 2)))
        addRoundRect(RoundRect(inner, radius.grownBy(-width / 2)))
      }
      else -> return null
    }
  }
}

/**
 * [gradient], a sweep, drawn inside [area] as wedges of its colours a degree and a half wide, each
 * opaque one reaching a little over the next so no seam shows between them: what an SVG recorder
 * can keep.
 */
private fun DrawScope.drawSweepWedges(gradient: WffGradient, pxPerWffPx: Float, area: Path) {
  val center =
    Offset(
      (gradient.numbers["centerX"] ?: 0f) * pxPerWffPx,
      (gradient.numbers["centerY"] ?: 0f) * pxPerWffPx,
    )
  val stops =
    sweepStops(
      gradient.numbers["startAngle"] ?: 0f,
      gradient.numbers["endAngle"] ?: 360f,
      gradient.clockwise,
      gradient.positions,
      gradient.colors,
    )
  val bounds = area.getBounds()
  // Far enough that each wedge's outer edge is past every corner of the area.
  val reach =
    2f *
      listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight).maxOf {
        (it - center).getDistance()
      } + 1f
  clipPath(area) {
    val steps = WEDGES
    val colors = List(steps) { i -> sweepColorAt(stops, (i + 0.5f) / steps) }
    for (i in 0 until steps) {
      val color = colors[i]
      val from = i / steps.toFloat()
      // Reaching half a wedge over the next hides the seam antialiasing leaves between two opaque
      // wedges; where either is translucent the overlap would be painted twice and show as a
      // band, so they meet edge to edge.
      val overlap = color.alpha >= 1f && colors[(i + 1) % steps].alpha >= 1f
      val to = (i + if (overlap) 1.5f else 1f) / steps
      fun point(fraction: Float): Offset {
        val radians = fraction * 2.0 * kotlin.math.PI
        return Offset(
          center.x + reach * kotlin.math.cos(radians).toFloat(),
          center.y + reach * kotlin.math.sin(radians).toFloat(),
        )
      }
      val wedge =
        Path().apply {
          moveTo(center.x, center.y)
          point(from).let { lineTo(it.x, it.y) }
          point((from + to) / 2).let { lineTo(it.x, it.y) }
          point(to).let { lineTo(it.x, it.y) }
          close()
        }
      drawPath(wedge, color)
    }
  }
}

private const val WEDGES = 240

/**
 * This radius [by] larger each way (smaller when negative), never below square. A square corner
 * stays square, as a stroke's mitred corner is.
 */
private fun CornerRadius.grownBy(by: Float) =
  CornerRadius(
    if (x > 0f) (x + by).coerceAtLeast(0f) else 0f,
    if (y > 0f) (y + by).coerceAtLeast(0f) else 0f,
  )

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
