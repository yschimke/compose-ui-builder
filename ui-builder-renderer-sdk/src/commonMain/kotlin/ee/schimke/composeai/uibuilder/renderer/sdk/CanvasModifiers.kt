package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.foundation.MarqueeDefaults
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DefaultCameraDistance
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import ee.schimke.composeai.uibuilder.export.RemoteModifierVocabulary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/** SDK-owned reading of one authored modifier. Theme values remain unresolved. */
sealed interface UiBuilderModifierPlan {
  data object FillMaxSize : UiBuilderModifierPlan

  data object FillMaxWidth : UiBuilderModifierPlan

  data object MatchParentSize : UiBuilderModifierPlan

  data object FillMaxHeight : UiBuilderModifierPlan

  data class Padding(val startDp: Float, val topDp: Float, val endDp: Float, val bottomDp: Float) :
    UiBuilderModifierPlan

  data class Size(val widthDp: Float?, val heightDp: Float?) : UiBuilderModifierPlan

  data class Clip(val shape: String?) : UiBuilderModifierPlan

  data class Width(val dp: Float) : UiBuilderModifierPlan

  data class Height(val dp: Float) : UiBuilderModifierPlan

  data class WidthIn(val minDp: Float?, val maxDp: Float?) : UiBuilderModifierPlan

  data class HeightIn(val minDp: Float?, val maxDp: Float?) : UiBuilderModifierPlan

  data class AspectRatio(val ratio: Float) : UiBuilderModifierPlan

  data class WrapContentSize(val alignment: String?) : UiBuilderModifierPlan

  data class Offset(val xDp: Float, val yDp: Float) : UiBuilderModifierPlan

  data class ZIndex(val value: Float) : UiBuilderModifierPlan

  data class Background(val color: String, val shape: String?) : UiBuilderModifierPlan

  data class Border(val widthDp: Float, val color: String, val shape: String?) :
    UiBuilderModifierPlan

  data class Alpha(val alpha: Float) : UiBuilderModifierPlan

  data class Shadow(val elevationDp: Float, val shape: String?, val clip: Boolean?) :
    UiBuilderModifierPlan

  data class Rotate(val degrees: Float) : UiBuilderModifierPlan

  data class Scale(val scaleX: Float, val scaleY: Float) : UiBuilderModifierPlan

  data object VerticalScroll : UiBuilderModifierPlan

  data object HorizontalScroll : UiBuilderModifierPlan

  data class TestTag(val tag: String) : UiBuilderModifierPlan

  data class Align(val alignment: String) : UiBuilderModifierPlan

  data class AlignHorizontal(val alignment: String) : UiBuilderModifierPlan

  data class AlignVertical(val alignment: String) : UiBuilderModifierPlan

  data class Weight(val weight: Float, val fill: Boolean?) : UiBuilderModifierPlan

  /**
   * A `RemoteModifier` call from the generated vocabulary. The canvas draws the ones Compose has an
   * honest counterpart for and leaves the rest to the player, which the device preview runs; a name
   * the released API does not have is not a plan at all, so the write is refused.
   */
  data class RemoteCall(val name: String, val args: Map<String, Float>) : UiBuilderModifierPlan
}

/** Parse one modifier without consulting a catalog theme. Invalid input is ignored, not thrown. */
fun uiBuilderModifier(value: JsonObject): UiBuilderModifierPlan? =
  when (value.string("type")) {
    "fillMaxSize" -> UiBuilderModifierPlan.FillMaxSize
    "fillMaxWidth" -> UiBuilderModifierPlan.FillMaxWidth
    "matchParentSize" -> UiBuilderModifierPlan.MatchParentSize
    "padding" ->
      UiBuilderModifierPlan.Padding(
        value.number("startDp"),
        value.number("topDp"),
        value.number("endDp"),
        value.number("bottomDp"),
      )
    "size" -> {
      val width = value.numberOrNull("widthDp")
      val height = value.numberOrNull("heightDp")
      if (width == null && height == null) null else UiBuilderModifierPlan.Size(width, height)
    }
    "clip" ->
      value.string("shape").let { shape ->
        if (isResolvableShape(shape)) UiBuilderModifierPlan.Clip(shape) else null
      }
    "fillMaxHeight" -> UiBuilderModifierPlan.FillMaxHeight
    "width" -> value.numberOrNull("widthDp")?.let(UiBuilderModifierPlan::Width)
    "height" -> value.numberOrNull("heightDp")?.let(UiBuilderModifierPlan::Height)
    "widthIn" -> {
      val min = value.numberOrNull("minDp")
      val max = value.numberOrNull("maxDp")
      if (min == null && max == null) null else UiBuilderModifierPlan.WidthIn(min, max)
    }
    "heightIn" -> {
      val min = value.numberOrNull("minDp")
      val max = value.numberOrNull("maxDp")
      if (min == null && max == null) null else UiBuilderModifierPlan.HeightIn(min, max)
    }
    "aspectRatio" ->
      value.numberOrNull("ratio")?.takeIf { it > 0f }?.let(UiBuilderModifierPlan::AspectRatio)
    "wrapContentSize" ->
      value.string("alignment").let { alignment ->
        if (alignment == null || isResolvableAlignment(alignment))
          UiBuilderModifierPlan.WrapContentSize(alignment)
        else null
      }
    "offset" -> UiBuilderModifierPlan.Offset(value.number("xDp"), value.number("yDp"))
    "zIndex" -> value.numberOrNull("zIndex")?.let(UiBuilderModifierPlan::ZIndex)
    "background" ->
      colorValue(value["color"])?.let { color ->
        val shape = value.string("shape")
        if (shape == null || isResolvableShape(shape))
          UiBuilderModifierPlan.Background(color, shape)
        else null
      }
    "border" ->
      colorValue(value["color"])?.let { color ->
        val width = value.numberOrNull("widthDp") ?: return@let null
        val shape = value.string("shape")
        if (shape == null || isResolvableShape(shape))
          UiBuilderModifierPlan.Border(width, color, shape)
        else null
      }
    "alpha" -> value.numberOrNull("alpha")?.let(UiBuilderModifierPlan::Alpha)
    "shadow" ->
      value.numberOrNull("elevationDp")?.let { elevation ->
        val shape = value.string("shape")
        if (shape == null || isResolvableShape(shape)) {
          UiBuilderModifierPlan.Shadow(elevation, shape, value["clip"].booleanOrNull())
        } else null
      }
    "rotate" -> value.numberOrNull("degrees")?.let(UiBuilderModifierPlan::Rotate)
    "scale" -> {
      val x = value.numberOrNull("scaleX")
      val y = value.numberOrNull("scaleY")
      if (x == null || y == null) null else UiBuilderModifierPlan.Scale(x, y)
    }
    "align" ->
      value.string("alignment")?.takeIf(::isResolvableAlignment)?.let(UiBuilderModifierPlan::Align)
    "alignHorizontal" ->
      value
        .string("alignment")
        ?.takeIf { it in HorizontalAlignments }
        ?.let(UiBuilderModifierPlan::AlignHorizontal)
    "alignVertical" ->
      value
        .string("alignment")
        ?.takeIf { it in VerticalAlignments }
        ?.let(UiBuilderModifierPlan::AlignVertical)
    "weight" ->
      value
        .numberOrNull("weight")
        ?.takeIf { it > 0f }
        ?.let { UiBuilderModifierPlan.Weight(it, value["fill"].booleanOrNull()) }
    "verticalScroll" -> UiBuilderModifierPlan.VerticalScroll
    "horizontalScroll" -> UiBuilderModifierPlan.HorizontalScroll
    "testTag" ->
      value.string("tag")?.takeIf(String::isNotBlank)?.let(UiBuilderModifierPlan::TestTag)
    RemoteModifierVocabulary.TYPE ->
      value
        .string("name")
        ?.takeIf { it in RemoteModifierVocabulary.modifiers }
        ?.let { name ->
          val args =
            (value["args"] as? JsonObject).orEmpty().mapNotNull { (argument, wrapped) ->
              (wrapped as? JsonObject)?.numberOrNull("value")?.let { argument to it }
            }
          UiBuilderModifierPlan.RemoteCall(name, args.toMap())
        }
    else -> null
  }

/** Apply generic modifier behavior with catalog-owned color and shape resolution. */
@Composable
fun Modifier.applyCanvasModifier(
  value: JsonObject,
  mode: CanvasMode,
  resolveColor: @Composable (String) -> Color,
  resolveShape: @Composable (String?) -> Shape,
  /**
   * Drop `horizontalScroll`, as the unrolled mode drops `verticalScroll`; see
   * [UI_BUILDER_UNROLLED_AXIS_KEY].
   */
  unrolledHorizontally: Boolean = false,
): Modifier =
  when (val plan = uiBuilderModifier(value)) {
    UiBuilderModifierPlan.FillMaxSize -> fillMaxSize()
    UiBuilderModifierPlan.FillMaxWidth -> fillMaxWidth()
    UiBuilderModifierPlan.FillMaxHeight -> fillMaxHeight()
    UiBuilderModifierPlan.MatchParentSize,
    is UiBuilderModifierPlan.Align,
    is UiBuilderModifierPlan.AlignHorizontal,
    is UiBuilderModifierPlan.AlignVertical,
    is UiBuilderModifierPlan.Weight -> this
    is UiBuilderModifierPlan.Padding ->
      padding(plan.startDp.dp, plan.topDp.dp, plan.endDp.dp, plan.bottomDp.dp)
    is UiBuilderModifierPlan.Size ->
      when {
        plan.widthDp != null && plan.heightDp != null -> size(plan.widthDp.dp, plan.heightDp.dp)
        plan.widthDp != null -> width(plan.widthDp.dp)
        else -> plan.heightDp?.let { height(it.dp) } ?: this
      }
    is UiBuilderModifierPlan.Clip -> clip(resolveShape(plan.shape))
    is UiBuilderModifierPlan.Width -> width(plan.dp.dp)
    is UiBuilderModifierPlan.Height -> height(plan.dp.dp)
    is UiBuilderModifierPlan.WidthIn ->
      widthIn(plan.minDp?.dp ?: Dp.Unspecified, plan.maxDp?.dp ?: Dp.Unspecified)
    is UiBuilderModifierPlan.HeightIn ->
      heightIn(plan.minDp?.dp ?: Dp.Unspecified, plan.maxDp?.dp ?: Dp.Unspecified)
    is UiBuilderModifierPlan.AspectRatio -> aspectRatio(plan.ratio)
    is UiBuilderModifierPlan.WrapContentSize -> wrapContentSize(alignmentFor(plan.alignment))
    is UiBuilderModifierPlan.Offset -> offset(plan.xDp.dp, plan.yDp.dp)
    is UiBuilderModifierPlan.ZIndex -> zIndex(plan.value)
    is UiBuilderModifierPlan.Background ->
      background(resolveColor(plan.color), plan.shape?.let { resolveShape(it) } ?: RectangleShape)
    is UiBuilderModifierPlan.Border ->
      border(
        plan.widthDp.dp,
        resolveColor(plan.color),
        plan.shape?.let { resolveShape(it) } ?: RectangleShape,
      )
    is UiBuilderModifierPlan.Alpha -> alpha(plan.alpha)
    is UiBuilderModifierPlan.Shadow ->
      shadow(
        plan.elevationDp.dp,
        plan.shape?.let { resolveShape(it) } ?: RectangleShape,
        clip = plan.clip ?: (plan.elevationDp > 0f),
      )
    is UiBuilderModifierPlan.Rotate -> rotate(plan.degrees)
    is UiBuilderModifierPlan.Scale -> scale(plan.scaleX, plan.scaleY)
    UiBuilderModifierPlan.VerticalScroll ->
      if (mode == CanvasMode.AuthoringUnrolled) this else verticalScroll(rememberScrollState())
    UiBuilderModifierPlan.HorizontalScroll ->
      if (unrolledHorizontally) this else horizontalScroll(rememberScrollState())
    is UiBuilderModifierPlan.TestTag -> testTag(plan.tag)
    is UiBuilderModifierPlan.RemoteCall ->
      when (plan.name) {
        "defaultMinSize" ->
          defaultMinSize(
            plan.args["minWidth"]?.dp ?: Dp.Unspecified,
            plan.args["minHeight"]?.dp ?: Dp.Unspecified,
          )
        "wrapContentWidth" -> wrapContentWidth()
        "wrapContentHeight" -> wrapContentHeight()
        // The arguments Compose's marquee shares with the Remote one. `animationMode` is a raw
        // int code upstream, so the canvas keeps Compose's default for it.
        "basicMarquee" -> {
          val repeatDelay =
            plan.args["repeatDelayMillis"]?.toInt() ?: MarqueeDefaults.RepeatDelayMillis
          basicMarquee(
            iterations = plan.args["iterations"]?.toInt() ?: MarqueeDefaults.Iterations,
            repeatDelayMillis = repeatDelay,
            // Compose's own default: the first delay is the repeat delay.
            initialDelayMillis = plan.args["initialDelayMillis"]?.toInt() ?: repeatDelay,
            spacing =
              plan.args["spacing"]?.let { MarqueeSpacing(it.dp) } ?: MarqueeDefaults.Spacing,
            velocity = plan.args["velocity"]?.dp ?: MarqueeDefaults.Velocity,
          )
        }
        // The same layer Compose draws, argument for argument; translation is in pixels in both.
        "graphicsLayer" ->
          graphicsLayer(
            scaleX = plan.args["scaleX"] ?: 1f,
            scaleY = plan.args["scaleY"] ?: 1f,
            alpha = plan.args["alpha"] ?: 1f,
            translationX = plan.args["translationX"] ?: 0f,
            translationY = plan.args["translationY"] ?: 0f,
            shadowElevation = plan.args["shadowElevation"] ?: 0f,
            rotationX = plan.args["rotationX"] ?: 0f,
            rotationY = plan.args["rotationY"] ?: 0f,
            rotationZ = plan.args["rotationZ"] ?: 0f,
            cameraDistance = plan.args["cameraDistance"] ?: DefaultCameraDistance,
            transformOrigin =
              TransformOrigin(
                plan.args["transformOriginX"] ?: 0.5f,
                plan.args["transformOriginY"] ?: 0.5f,
              ),
          )
        // Accessibility, not appearance: the player's screen reader is where it shows.
        else -> this
      }
    null -> this
  }

fun alignmentFor(value: String?): Alignment =
  when (value) {
    "topStart" -> Alignment.TopStart
    "topCenter" -> Alignment.TopCenter
    "topEnd" -> Alignment.TopEnd
    "centerStart" -> Alignment.CenterStart
    "centerEnd" -> Alignment.CenterEnd
    "bottomStart" -> Alignment.BottomStart
    "bottomCenter" -> Alignment.BottomCenter
    "bottomEnd" -> Alignment.BottomEnd
    else -> Alignment.Center
  }

fun isResolvableAlignment(value: String): Boolean = value in ResolvableAlignments

private fun colorValue(element: JsonElement?): String? {
  val value = (element as? JsonObject)?.string("value") ?: return null
  val type = element.string("type")
  return value.takeIf {
    (type == "colorToken" && it in ResolvableColorTokens) || (type == "color" && it.startsWith("#"))
  }
}

private fun isResolvableShape(value: String?): Boolean =
  value.isNullOrEmpty() || value in NamedShapes || value.toFloatOrNull() != null

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(name: String): Float = numberOrNull(name) ?: 0f

private fun JsonObject.numberOrNull(name: String): Float? =
  (this[name] as? JsonPrimitive)?.floatOrNull

private fun JsonElement?.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private val NamedShapes = setOf("large", "medium", "small")
private val HorizontalAlignments = setOf("start", "centerHorizontally", "end")
private val VerticalAlignments = setOf("top", "centerVertically", "bottom")
private val ResolvableAlignments =
  setOf(
    "topStart",
    "topCenter",
    "topEnd",
    "centerStart",
    "center",
    "centerEnd",
    "bottomStart",
    "bottomCenter",
    "bottomEnd",
  )
private val ResolvableColorTokens =
  setOf(
    "background",
    "surface",
    "surfaceContainer",
    "surfaceContainerLow",
    "surfaceContainerHigh",
    "surfaceContainerHighest",
    "primary",
    "onPrimary",
    "secondary",
    "onSecondary",
    "tertiary",
    "onTertiary",
    "onSurface",
    "onSurfaceVariant",
    "outlineVariant",
    "transparent",
  )
