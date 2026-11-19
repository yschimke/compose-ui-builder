package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceComplicationBounds
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.rememberAssetBitmap
import ee.schimke.composeai.uibuilder.renderer.sdk.rememberLiveWallClock
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The face being drawn: its `width` ? `height` in WFF pixels, and the dp one pixel is drawn as.
 * Phase 1 placeholder; the Remote Compose player preview replaces it.
 */
private class WffFace(val widthPx: Float, val heightPx: Float, val dpPerPx: Float)

private val LocalWffFace = compositionLocalOf { WffFace(450f, 450f, 0.5f) }

/**
 * The box, in WFF pixels, a node's children are placed in: the scene's for its own parts, else the
 * node's own size, which a node with no `width`/`height` takes from the box it sits in. A child
 * with no bounds of its own fills it, as the XML export writes it (yschimke/watchface-tools#59).
 */
private class WffBox(val widthPx: Float, val heightPx: Float)

private val LocalWffBox = compositionLocalOf<WffBox?> { null }

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
  // In ambient mode the node as its `<Variant mode="AMBIENT">` children make it.
  @Suppress("NAME_SHADOWING") val node = wffNodeIn(document, node, preview)
  // What this node's children are placed in: its own box, else the one it sits in.
  val parentBox = LocalWffBox.current ?: WffBox(face.widthPx, face.heightPx)
  val box = WffBox(node.float("width", parentBox.widthPx), node.float("height", parentBox.heightPx))
  fun place(id: String) =
    wffPlace(document, id, box.widthPx, box.heightPx, face.dpPerPx, preview, forExport)
  CompositionLocalProvider(LocalWffBox provides box) {
    WatchFaceNode(document, node, modifier, child, face, preview, forExport, box, ::place)
  }
}

@Composable
private fun WatchFaceNode(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  modifier: Modifier,
  child: @Composable (String, Modifier) -> Unit,
  face: WffFace,
  preview: WffPreviewContext,
  forExport: Boolean,
  box: WffBox,
  place: (String) -> Modifier,
) {
  val placed = { slot: String ->
    node.slots[slot].orEmpty().filter { id ->
      val candidate = document.nodes[id]
      candidate != null &&
        candidate.componentId !in WFF_NOT_DRAWN &&
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
      val swaying = remember(document.nodes) { document.wffHasGyro() }
      // The clock, and on a live canvas the wrist's sway a `<Gyro>` follows; the mode is per layer.
      val settings = remember(document.nodes) { document.wffConfigurationValues() }
      val clock =
        remember(node, fixedTime, live, swaying, settings) {
          (live?.let { WffPreviewContext.of(it).let { now -> if (swaying) now.swaying() else now } }
              ?: WffPreviewContext.of(node.string("previewTime"), fixedTime))
            .withConfiguration(settings)
        }
      // Interactive and ambient cross-fade as the watch switches, rather than cut: while the
      // switch runs both are drawn, the mode being entered over the one being left.
      val ambientShown by
        animateFloatAsState(
          if (ambient) 1f else 0f,
          tween(WFF_AMBIENT_SWITCH_MILLIS),
          label = "wff-ambient",
        )
      val layers =
        when (ambientShown) {
          0f -> listOf(false to 1f)
          1f -> listOf(true to 1f)
          else ->
            listOf(!ambient to 1f, ambient to if (ambient) ambientShown else 1f - ambientShown)
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
          // The editing canvas draws the screen's shape (a faint outline, and the background
          // inside it) but does not cut the parts to it, so content outside the circle can be
          // seen and selected. The preview and the exports are cut.
          val editing = LocalUiBuilderEditingCanvas.current
          val face = Modifier.size((width * scale).dp, (height * scale).dp)
          Box(
            if (editing)
              face.drawWithContent {
                drawContent()
                if (clip == CircleShape) {
                  drawCircle(Color(0x33FFFFFF), style = Stroke(1.dp.toPx()))
                }
              }
            else face.clip(clip),
          ) {
            for ((layerAmbient, alpha) in layers) {
              // Ambient holds the wrist still: the sway is the interactive layer's only.
              val context =
                remember(clock, layerAmbient) {
                  clock.inMode(layerAmbient).let { if (layerAmbient) it.withTilt(0.0, 0.0) else it }
                }
              CompositionLocalProvider(
                LocalWffFace provides WffFace(width, height, scale),
                LocalWffBox provides WffBox(width, height),
                LocalWffPreview provides context,
              ) {
                val background =
                  if (layerAmbient) Color.Black else node.wffColor("backgroundColor", Color.Black)
                Box(
                  Modifier.matchParentSize()
                    .graphicsLayer { this.alpha = alpha }
                    .background(background, if (editing) clip else RectangleShape),
                ) {
                  node.slots["content"]
                    .orEmpty()
                    .filter { id ->
                      val candidate = document.nodes[id]
                      candidate != null &&
                        candidate.componentId !in WFF_NOT_DRAWN &&
                        wffVisibleIn(wffNodeIn(document, candidate, context), context)
                    }
                    .forEach { id ->
                      child(id, wffPlace(document, id, width, height, scale, context, forExport))
                    }
                }
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
    WatchFaceXmlExporter.PART_IMAGE -> {
      // A complication's image source draws the sample's placeholder picture.
      val source = node.string("resource")
      val image =
        when (source) {
          "[COMPLICATION.PHOTO_IMAGE]" ->
            WffSampleImage.PHOTO.takeIf { preview.complication.image == WffSampleImage.PHOTO }
          "[COMPLICATION.MONOCHROMATIC_IMAGE]",
          "[COMPLICATION.SMALL_IMAGE]" -> preview.complication.image
          else -> null
        }
      // Any other resource names a design asset (`ui_builder_put_asset`): the bitmap the bundle
      // packs as that drawable, stretched to the part's box as the watch draws it.
      val bitmap = if (image == null) document.rememberAssetBitmap(source) else null
      if (image != null) {
        val tint = node.wffColor("tintColor", Color.White)
        Box(modifier.drawBehind { drawWffGlyph(image, tint, Offset.Zero, size) })
      } else if (bitmap != null) {
        val filter = wffTintFilter(node.wffColor("tintColor", Color.Unspecified))
        Box(modifier.drawBehind { drawWffBitmap(bitmap, Offset.Zero, size, filter) })
      } else
        Box(modifier.border(1.dp, Color(0x66FFFFFF)), contentAlignment = Alignment.Center) {
          Text(
            source.ifEmpty { "image" },
            color = Color(0x99FFFFFF),
            fontSize = 10.sp,
          )
        }
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
      val hands =
        node.slots["hands"]
          .orEmpty()
          .mapNotNull { document.nodes[it] }
          .map { wffNodeIn(document, it, preview) }
          .filter { wffVisibleIn(it, preview) }
      // Resolved here: a colour token needs the composition, the draw lambda has none.
      // With each hand's alpha (an ambient variant's included), which the draw below applies.
      val tints = hands.map { hand ->
        val tint = hand.wffColor("tintColor", Color.White)
        tint.copy(alpha = tint.alpha * hand.float("alpha", 255f).coerceIn(0f, 255f) / 255f)
      }
      // A hand whose `resource` is a design asset draws that bitmap, tinted only when it sets a
      // `tintColor`; one with no bitmap here draws as a bar in its tint.
      val bitmaps = hands.map { document.rememberAssetBitmap(it.string("resource")) }
      val filters = hands.map { wffTintFilter(it.wffColor("tintColor", Color.Unspecified)) }
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
            val bitmap = bitmaps[index]
            rotate(degrees, pivot) {
              if (bitmap == null) drawRect(tints[index], Offset(x, y), Size(w, h))
              else
                drawWffBitmap(
                  bitmap,
                  Offset(x, y),
                  Size(w, h),
                  filters[index],
                  alpha = hand.float("alpha", 255f).coerceIn(0f, 255f) / 255f,
                )
            }
          }
        },
      )
    }
    WatchFaceXmlExporter.COMPLICATION_SLOT -> {
      // The sample the slot shows (the editor's pick, else its default provider and type) drawn by
      // the slot's own `<Complication type>` for that type; a slot with none for it draws a tidy
      // default, and `EMPTY` draws nothing.
      val variants =
        node.slots["variants"]
          .orEmpty()
          .mapNotNull { document.nodes[it] }
          .filter { it.componentId == WatchFaceXmlExporter.COMPLICATION }
      val picks = LocalWffComplicationPicks.current
      val sample =
        remember(node, picks) {
          wffSlotSample(
            node.id,
            node.string("defaultSystemProvider"),
            node.string("defaultSystemProviderType"),
            node.string("supportedTypes").split(' ').filter { it.isNotBlank() },
            picks,
          )
        }
      val drawn = variants.firstOrNull { it.string("type") == sample.type }
      val context = remember(preview, sample) { preview.withComplication(sample) }
      val side = minOf(node.float("width", 100f), node.float("height", 100f))
      // The runtime draws a slot's content only inside its bounding area, so the canvas cuts it
      // there too: a label past the box looks cut here exactly where the watch cuts it.
      val area =
        remember(node, box.widthPx, box.heightPx) {
          WatchFaceComplicationBounds.boundingArea(node, box.widthPx, box.heightPx)
        }
      CompositionLocalProvider(LocalWffPreview provides context) {
        Box(
          modifier.clipToBounds().drawWithContent {
            val scale = face.dpPerPx.dp.toPx()
            clipRect(area.x * scale, area.y * scale, area.right * scale, area.bottom * scale) {
              this@drawWithContent.drawContent()
            }
          },
          contentAlignment = Alignment.Center,
        ) {
          when {
            sample.type == WffComplicationSample.EMPTY -> Unit
            drawn != null -> child(drawn.id, Modifier.fillMaxSize())
            else -> WffComplicationDefault(sample, side, face.dpPerPx, Modifier.fillMaxSize())
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
    // A condition draws the branch the watch would take: see [wffConditionBranch].
    WatchFaceXmlExporter.CONDITION ->
      Box(modifier) {
        wffConditionBranch(document, node, preview)?.let { id -> child(id, Modifier.fillMaxSize()) }
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
internal fun WffText(
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

/**
 * Where [id] sits in its parent: at its `x`,`y` and its own size, a missing `width`/`height` taking
 * the [parentWidth] × [parentHeight] box it sits in, WFF pixels.
 */
private fun wffPlace(
  document: UiBuilderDocument,
  id: String,
  parentWidth: Float,
  parentHeight: Float,
  scale: Float,
  preview: WffPreviewContext,
  drawnRotation: Boolean = false,
): Modifier {
  val target = document.nodes[id]?.let { wffNodeIn(document, it, preview) } ?: return Modifier
  val box =
    Modifier.offset((target.float("x") * scale).dp, (target.float("y") * scale).dp)
      // At its own size even past its parent's edge: a 500px group at -25,-25 on a 450px face
      // was cut to 450 by the parent's constraints, which moved its centre, the pivot it turns on.
      .wrapContentSize(Alignment.TopStart, unbounded = true)
      .wffSize(
        target.float("width", parentWidth) * scale,
        target.float("height", parentHeight) * scale,
      )
  // The angle, alpha and scale with the node's transforms applied (see [wffNodeIn]).
  val degrees = target.float("angle")
  val alpha = target.float("alpha", 255f).coerceIn(0f, 255f) / 255f
  val scaleX = target.float("scaleX", 1f)
  val scaleY = target.float("scaleY", 1f)
  val pivotX = target.float("pivotX", 0.5f)
  val pivotY = target.float("pivotY", 0.5f)
  val transformed = degrees != 0f || scaleX != 1f || scaleY != 1f
  return if (!transformed && alpha == 1f) box
  else if (drawnRotation)
    box.then(
      if (!transformed) Modifier.graphicsLayer { this.alpha = alpha }
      else
        Modifier.drawWithContent {
          val pivot = Offset(size.width * pivotX, size.height * pivotY)
          // As the layer would: scaled, then turned, both about the pivot. The opacity is applied
          // inside the turn, over the node's own box there, as the editor's turned layer applies
          // it; a layer outside the turn would cut off what the turn moves past the box.
          withTransform(
            {
              rotate(degrees, pivot)
              scale(scaleX, scaleY, pivot)
            },
          ) {
            if (alpha < 1f)
              drawContext.canvas.saveLayer(
                Rect(Offset.Zero, size),
                Paint().also { it.alpha = alpha },
              )
            this@drawWithContent.drawContent()
            if (alpha < 1f) drawContext.canvas.restore()
          }
        },
    )
  else
    box.graphicsLayer {
      rotationZ = degrees
      this.alpha = alpha
      this.scaleX = scaleX
      this.scaleY = scaleY
      transformOrigin = TransformOrigin(pivotX, pivotY)
    }
}

/** How long the switch between interactive and ambient takes to fade across. */
private const val WFF_AMBIENT_SWITCH_MILLIS = 400

/**
 * What sits among a part's children but is not drawn as one: an override, the motion that drives
 * the part, or raw XML.
 */
private val WFF_NOT_DRAWN =
  setOf(
    WatchFaceXmlExporter.VARIANT,
    WatchFaceXmlExporter.TRANSFORM,
    WatchFaceXmlExporter.GYRO,
    WatchFaceXmlExporter.RAW,
  )

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
  // A hairline is drawn a device pixel wide, as Skia draws a stroke of no width: a 1px stroke on a
  // face drawn at half size would otherwise be half a pixel and all but vanish.
  val line =
    Stroke(
      width =
        drawing.strokeWidth.dp.toPx().let {
          if (drawing.strokeWidth > 0f) it.coerceAtLeast(1f) else it
        },
    )
  val fill: Brush? =
    drawing.fillGradient?.brush(pxPerWffPx)
      ?: drawing.fill.takeIf { it != Color.Unspecified }?.let(::SolidColor)
  val stroke: Brush? =
    drawing.strokeGradient?.brush(pxPerWffPx)
      ?: drawing.stroke.takeIf { it != Color.Unspecified }?.let(::SolidColor)
  // The width the editor's stroke covers: a stroke of no width is Skia's hairline, a device pixel
  // centred on the path. An export band of no width would paint nothing.
  val covered = if (line.width > 0f) line.width else 1f
  if (shape == "line") {
    val k = pxPerWffPx
    val start = Offset(drawing.ends[0] * k, drawing.ends[1] * k)
    val end = Offset(drawing.ends[2] * k, drawing.ends[3] * k)
    val sweepLine = drawing.strokeGradient?.takeIf { forExport && it.kind == WffGradient.SWEEP }
    if (sweepLine != null && start != end) {
      // As every other shape's: the area a butt-capped line covers, filled with the wedges.
      val along = end - start
      val normal = Offset(-along.y, along.x) / along.getDistance() * (covered / 2)
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
  val extent =
    if (box == null) size else Size(hairline(box[2] * pxPerWffPx), hairline(box[3] * pxPerWffPx))
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
    val inner =
      Size(
        (extent.width - line.width).coerceAtLeast(0f),
        (extent.height - line.width).coerceAtLeast(0f),
      )
    val band =
      if (forExport && drawing.strokeGradient?.kind == WffGradient.SWEEP) {
        // A hairline is centred on the box's edge, not inset: the band straddles it.
        val grow = (covered - line.width) / 2
        strokeBand(
          drawing,
          pxPerWffPx,
          Offset(origin.x - grow, origin.y - grow),
          Size(extent.width + 2 * grow, extent.height + 2 * grow),
          covered,
          start,
          sweep,
        )
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
  val collapsed = inner.width <= 0f || inner.height <= 0f
  // An oval that is not a circle: its stroke's edges are offset curves, not ovals, so the band is
  // traced from the centre line the editor strokes rather than two ovals.
  val centre = outer.deflate(width / 2)
  // An arc whose stroke fills its hole is traced the same way, circle or not: the stroke reaches
  // past the centre into angles outside the arc's sector, so a sector would cut it short.
  val oval = drawing.kind == "ellipse" || drawing.kind == "arc"
  if (
    oval &&
      (kotlin.math.abs(centre.width - centre.height) > 0.01f ||
        (collapsed && drawing.kind == "arc"))
  ) {
    val ring = drawing.kind == "ellipse"
    return offsetBand(
      centre,
      width / 2,
      if (ring) 0f else start,
      if (ring) 360f else sweep,
      ring,
      collapsed,
    )
  }
  // A stroke as wide as the shape leaves no hole: it covers the whole shape, an arc's sector.
  if (collapsed)
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
 * The area a stroke of half-width [half] along the oval [centre] covers, from [start] for [sweep]
 * degrees (Compose's, which on an oval are its parametric angles, as `drawArc` takes them): each
 * edge is the centre line moved [half] along its normal. A [ring] is closed; an arc's ends are cut
 * square, as a butt cap is. [collapsed]: the stroke leaves no hole, so a ring keeps only its outer
 * edge; an arc keeps both, for its square ends.
 */
private fun offsetBand(
  centre: Rect,
  half: Float,
  start: Float,
  sweep: Float,
  ring: Boolean,
  collapsed: Boolean,
): Path {
  val steps = (kotlin.math.abs(sweep) * 2).toInt().coerceAtLeast(16)
  val a = centre.width / 2
  val b = centre.height / 2
  fun edge(i: Int, side: Float): Offset {
    val t = (start + sweep * i / steps) * kotlin.math.PI / 180
    val cos = kotlin.math.cos(t).toFloat()
    val sin = kotlin.math.sin(t).toFloat()
    val nx = b * cos
    val ny = a * sin
    val length = kotlin.math.sqrt(nx * nx + ny * ny).takeIf { it > 0f } ?: 1f
    return Offset(
      centre.center.x + a * cos + side * half * nx / length,
      centre.center.y + b * sin + side * half * ny / length,
    )
  }
  return Path().apply {
    // Non-zero winding with the inner edge run backwards: the hole cancels out, and where a thick
    // stroke's inner edge loops back on itself the loop stays filled, as the editor's stroke is.
    fillType = PathFillType.NonZero
    val outer = (0..steps).map { edge(it, 1f) }
    if (ring) {
      outer.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
      close()
      if (!collapsed) {
        (steps downTo 0)
          .map { edge(it, -1f) }
          .forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
      }
      return@apply
    }
    // An arc keeps both edges even when the stroke fills its hole: its ends are cut square along
    // the end normals, which on an oval miss the centre, so closing through the centre (a sector)
    // would add or cut triangles at each end. A self-crossing inner edge stays filled.
    outer.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    (steps downTo 0).forEach { i -> edge(i, -1f).let { lineTo(it.x, it.y) } }
    close()
  }
}

/**
 * This radius [by] larger each way (smaller when negative), never below square. A square corner
 * stays square, as a stroke's mitred corner is.
 */
private fun CornerRadius.grownBy(by: Float) =
  CornerRadius(
    if (x > 0f) (x + by).coerceAtLeast(0f) else 0f,
    if (y > 0f) (y + by).coerceAtLeast(0f) else 0f,
  )

/** A length of [px] canvas pixels, at least one when it is any at all, so a hairline shows. */
private fun hairline(px: Float): Float = if (px > 0f) px.coerceAtLeast(1f) else px

/**
 * An element's box, [widthDp] × [heightDp]: rounded to whole pixels as layout rounds it, but at
 * least one pixel each way when it has any size at all. A 1px index on a face drawn at half size is
 * half a pixel, which layout rounds to nothing, so it was not drawn.
 */
private fun Modifier.wffSize(widthDp: Float, heightDp: Float): Modifier =
  layout { measurable, constraints ->
    fun px(dp: Float): Int {
      if (dp <= 0f) return 0
      return dp.dp.toPx().roundToInt().coerceAtLeast(1)
    }
    // Within what the parent allows, as `size` keeps it.
    val width = constraints.constrainWidth(px(widthDp))
    val height = constraints.constrainHeight(px(heightDp))
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
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

/**
 * A Watch Face Format `tintColor` on a bitmap: the image's colours multiplied by it, so a white
 * mark takes the tint and its alpha is kept. No tint ([Color.Unspecified]) draws the bitmap as is.
 */
internal fun wffTintFilter(tint: Color): ColorFilter? =
  if (tint == Color.Unspecified) null else ColorFilter.tint(tint, BlendMode.Modulate)

/** [bitmap] stretched to [extent] at [origin], through [filter], as `PartImage` draws it. */
internal fun DrawScope.drawWffBitmap(
  bitmap: ImageBitmap,
  origin: Offset,
  extent: Size,
  filter: ColorFilter?,
  alpha: Float = 1f,
) {
  if (extent.width <= 0f || extent.height <= 0f) return
  drawImage(
    bitmap,
    dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
    dstSize = IntSize(extent.width.roundToInt(), extent.height.roundToInt()),
    alpha = alpha,
    colorFilter = filter,
  )
}
