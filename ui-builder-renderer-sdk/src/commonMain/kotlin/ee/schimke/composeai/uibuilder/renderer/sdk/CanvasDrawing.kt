package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiDrawing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * A `draw/canvas` on the editing canvas: its operations drawn by Compose's `Canvas`, with the same
 * geometry rules the Remote emitter writes — dp from the top-left, an absent box is the whole
 * canvas, a stroked shape with a defaulted box is inset by half its stroke, angles clockwise from
 * three o'clock.
 *
 * The operations arrive resolved: [CanvasRenderTree] has already evaluated computed values and
 * state reads into literals, so this reads plain numbers. Colours are resolved here, in
 * composition, by the host's [resolveColor] — a theme role means what the host's theme says —
 * because the draw lambda that follows is not composable.
 */
@Composable
fun UiBuilderDrawCanvas(
  entry: CanvasRenderNode,
  modifier: Modifier,
  resolveColor: @Composable (String) -> Color?,
) {
  val steps = collectSteps(entry.slot(UiDrawing.OPS_SLOT), resolveColor)
  val measurer = rememberTextMeasurer()
  val stated = entry.node.statedSizeDp()
  Canvas(modifier) {
    // A canvas that states its size draws in that size, as the export does; see `CanvasExtent`
    // in the Remote emitter for why the export reads the stated size rather than the measured one.
    val extent =
      Size(
        stated.first?.let { it * density } ?: size.width,
        stated.second?.let { it * density } ?: size.height,
      )
    steps.forEach { it.draw(this, measurer, extent) }
  }
}

@Composable
private fun collectSteps(
  operations: List<CanvasRenderNode>,
  resolveColor: @Composable (String) -> Color?,
): List<DrawStep> = operations.mapNotNull { operation ->
  val node = operation.node
  fun faded(base: Color) =
    node.number("alpha")?.let { base.copy(alpha = base.alpha * it.coerceIn(0f, 1f)) } ?: base
  val color = faded(node.text("color")?.let { resolveColor(it) } ?: Color.Black)
  val gradient = node.text("gradient")?.takeIf { it in UiDrawing.GRADIENTS }
  val gradientColor = gradient?.let {
    faded(node.text("gradientColor")?.let { resolveColor(it) } ?: Color.Transparent)
  }
  val paint =
    DrawPaint(
      color = color,
      gradient = gradient,
      gradientColor = gradientColor,
      stroke = node.text("style") == "stroke" || node.componentId == "draw/line",
      strokeWidthDp = node.number("strokeWidthDp") ?: 1f,
      cap =
        when (node.text("strokeCap")) {
          "round" -> StrokeCap.Round
          "square" -> StrokeCap.Square
          else -> StrokeCap.Butt
        },
    )
  when (node.componentId) {
    UiDrawing.GROUP ->
      DrawStep.Group(node, collectSteps(operation.slot(UiDrawing.OPS_SLOT), resolveColor))
    UiDrawing.CLIP ->
      DrawStep.Clip(node, collectSteps(operation.slot(UiDrawing.OPS_SLOT), resolveColor))
    // The condition arrives evaluated at the preview state, as the player evaluates it per frame.
    UiDrawing.IF ->
      if (node.flag("condition"))
        DrawStep.Sequence(collectSteps(operation.slot(UiDrawing.OPS_SLOT), resolveColor))
      else null
    UiDrawing.REPEAT -> DrawStep.Sequence(collectSteps(iterations(operation), resolveColor))
    in UiDrawing.BY_ID -> DrawStep.Shape(node, paint)
    else -> null
  }
}

/**
 * A [UiDrawing.REPEAT]'s operations once per index, each entered with the index bound under the
 * repeat's name so `@i` (a property bound to it, or a formula reading it) resolves to that pass's
 * value — the player's `loop(from, until, step)`, which runs while the index is below `until`.
 */
private fun iterations(repeat: CanvasRenderNode): List<CanvasRenderNode> {
  val node = repeat.node
  val from = node.number("from") ?: 0f
  val until = node.number("until") ?: return emptyList()
  val step = node.number("step") ?: 1f
  if (step <= 0f || !from.isFinite() || !until.isFinite()) return emptyList()
  val name = UiDrawing.indexName(repeat.node) ?: return emptyList()
  val children = node.slots[UiDrawing.OPS_SLOT].orEmpty()
  return buildList {
    var index = from
    var pass = 0
    while (index < until && pass < UiDrawing.MAX_ITERATIONS) {
      val arguments =
        JsonObject(
          repeat.bindingArguments +
            (name to
              JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(index))))
        )
      children.forEach { child -> repeat.occurrenceChild(child, pass, arguments)?.let(::add) }
      pass++
      index = from + step * pass
    }
  }
}

private class DrawPaint(
  val color: Color,
  val gradient: String?,
  val gradientColor: Color?,
  val stroke: Boolean,
  val strokeWidthDp: Float,
  val cap: StrokeCap,
) {
  /**
   * What the shape is filled or stroked with: its colour, or a gradient from it to [gradientColor]
   * laid across the whole canvas, as the exported `RemoteBrush` is.
   */
  fun brush(extent: Size): Brush {
    val colors = listOf(color, gradientColor ?: color)
    val center = Offset(extent.width / 2f, extent.height / 2f)
    return when (gradient) {
      "horizontal" -> Brush.horizontalGradient(colors, 0f, extent.width)
      "vertical" -> Brush.verticalGradient(colors, 0f, extent.height)
      "radial" -> Brush.radialGradient(colors, center, extent.minDimension / 2f)
      "sweep" -> Brush.sweepGradient(colors, center)
      else -> SolidColor(color)
    }
  }
}

private sealed interface DrawStep {
  fun draw(scope: DrawScope, measurer: TextMeasurer, extent: Size)

  class Group(private val node: UiBuilderNode, private val children: List<DrawStep>) : DrawStep {
    override fun draw(scope: DrawScope, measurer: TextMeasurer, extent: Size) =
      with(scope) {
        val pivot =
          Offset(
            node.number("pivotXDp")?.dp(this) ?: extent.width / 2f,
            node.number("pivotYDp")?.dp(this) ?: extent.height / 2f,
          )
        withTransform({
          translate(
            node.number("translateXDp")?.dp(this@with) ?: 0f,
            node.number("translateYDp")?.dp(this@with) ?: 0f,
          )
          node.number("rotate")?.let { rotate(it, pivot) }
          node.number("scale")?.let { scale(it, it, pivot) }
        }) {
          children.forEach { it.draw(this, measurer, extent) }
        }
      }
  }

  class Sequence(private val children: List<DrawStep>) : DrawStep {
    override fun draw(scope: DrawScope, measurer: TextMeasurer, extent: Size) = children.forEach {
      it.draw(scope, measurer, extent)
    }
  }

  class Clip(private val node: UiBuilderNode, private val children: List<DrawStep>) : DrawStep {
    override fun draw(scope: DrawScope, measurer: TextMeasurer, extent: Size) =
      with(scope) {
        val x = node.number("xDp")?.dp(this) ?: 0f
        val y = node.number("yDp")?.dp(this) ?: 0f
        clipRect(
          left = x,
          top = y,
          right = node.number("widthDp")?.let { x + it.dp(this) } ?: extent.width,
          bottom = node.number("heightDp")?.let { y + it.dp(this) } ?: extent.height,
          clipOp = if (node.flag("exclude")) ClipOp.Difference else ClipOp.Intersect,
        ) {
          children.forEach { it.draw(this, measurer, extent) }
        }
      }
  }

  class Shape(private val node: UiBuilderNode, private val paint: DrawPaint) : DrawStep {
    override fun draw(scope: DrawScope, measurer: TextMeasurer, extent: Size) =
      with(scope) {
        val strokePx = paint.strokeWidthDp.dp(this)
        val style: DrawStyle = if (paint.stroke) Stroke(width = strokePx, cap = paint.cap) else Fill
        val boxStated = listOf("xDp", "yDp", "widthDp", "heightDp").any { it in node.properties }
        val inset = if (paint.stroke && !boxStated) strokePx / 2f else 0f
        val x = node.number("xDp")?.dp(this) ?: inset
        val y = node.number("yDp")?.dp(this) ?: inset
        val box =
          Size(
            node.number("widthDp")?.dp(this)
              ?: if (boxStated) extent.width - x else extent.width - inset * 2,
            node.number("heightDp")?.dp(this)
              ?: if (boxStated) extent.height - y else extent.height - inset * 2,
          )
        val brush = paint.brush(extent)
        when (node.componentId) {
          "draw/rect" -> {
            val radius = node.number("cornerRadiusDp")?.dp(this)
            if (radius == null) drawRect(brush, Offset(x, y), box, style = style)
            else
              drawRoundRect(brush, Offset(x, y), box, CornerRadius(radius, radius), style = style)
          }
          "draw/oval" -> drawOval(brush, Offset(x, y), box, style = style)
          "draw/arc" ->
            drawArc(
              brush,
              startAngle = node.number("startAngle") ?: 0f,
              sweepAngle = node.number("sweepAngle") ?: 360f,
              useCenter = node.flag("useCenter"),
              topLeft = Offset(x, y),
              size = box,
              style = style,
            )
          "draw/circle" ->
            drawCircle(
              brush,
              radius = node.number("radiusDp")?.dp(this) ?: (extent.minDimension / 2f - inset),
              center =
                Offset(
                  node.number("centerXDp")?.dp(this) ?: extent.width / 2f,
                  node.number("centerYDp")?.dp(this) ?: extent.height / 2f,
                ),
              style = style,
            )
          "draw/line" ->
            drawLine(
              brush,
              Offset(
                node.number("startXDp")?.dp(this) ?: 0f,
                node.number("startYDp")?.dp(this) ?: 0f,
              ),
              Offset(
                node.number("endXDp")?.dp(this) ?: extent.width,
                node.number("endYDp")?.dp(this) ?: extent.height,
              ),
              strokeWidth = strokePx,
              cap = paint.cap,
            )
          "draw/path" -> {
            val path =
              node.text("pathData")?.let { data ->
                runCatching { PathParser().parsePathString(data).toPath() }.getOrNull()
              }
            if (path != null) {
              val viewportWidth = node.number("viewportWidth")?.takeIf { it > 0f } ?: 24f
              val viewportHeight = node.number("viewportHeight")?.takeIf { it > 0f } ?: 24f
              scale(extent.width / viewportWidth, extent.height / viewportHeight, Offset.Zero) {
                drawPath(path, brush, style = style)
              }
            }
          }
          "draw/text" -> {
            val layout =
              measurer.measure(
                node.text("text").orEmpty(),
                TextStyle(color = paint.color, fontSize = (node.number("textSizeSp") ?: 14f).sp),
              )
            val pan =
              when (node.text("align")) {
                "start" -> -1f
                "end" -> 1f
                else -> 0f
              }
            val anchorX = node.number("xDp")?.dp(this) ?: extent.width / 2f
            val anchorY = node.number("yDp")?.dp(this) ?: extent.height / 2f
            drawText(
              layout,
              brush = brush,
              topLeft =
                Offset(
                  anchorX - layout.size.width * (1f + pan) / 2f,
                  anchorY - layout.size.height / 2f,
                ),
            )
          }
        }
      }
  }
}

private fun Float.dp(scope: DrawScope): Float = this * scope.density

private fun UiBuilderNode.scalar(name: String): JsonPrimitive? =
  (properties[name] as? JsonObject)?.get("value") as? JsonPrimitive

private fun UiBuilderNode.number(name: String): Float? = scalar(name)?.floatOrNull

private fun UiBuilderNode.text(name: String): String? =
  scalar(name)?.contentOrNull?.takeIf(String::isNotEmpty)

private fun UiBuilderNode.flag(name: String): Boolean = scalar(name)?.booleanOrNull ?: false

/** The `size`/`width`/`height` the canvas node states, in dp, either of which may be absent. */
private fun UiBuilderNode.statedSizeDp(): Pair<Float?, Float?> {
  var width: Float? = null
  var height: Float? = null
  modifiers.forEach { element ->
    val modifier = element as? JsonObject ?: return@forEach
    fun number(name: String) = (modifier[name] as? JsonPrimitive)?.floatOrNull
    when ((modifier["type"] as? JsonPrimitive)?.contentOrNull) {
      "size" -> {
        number("widthDp")?.let { width = it }
        number("heightDp")?.let { height = it }
      }
      "width" -> number("widthDp")?.let { width = it }
      "height" -> number("heightDp")?.let { height = it }
    }
  }
  return width to height
}
