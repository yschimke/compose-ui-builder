package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val CHIP = Color(0x26FFFFFF)
private val TRACK = Color(0x33FFFFFF)
private val ACCENT = Color(0xFF4FC3F7)
private val INK = Color(0xFFFFFFFF)
private val SOFT_INK = Color(0xB3FFFFFF)

/**
 * What a slot with no `<Complication>` of its own for [sample]'s type draws: a chip of text for the
 * text types, a ring filled to the value for ranged and goal types, a segmented ring for weighted
 * elements and the placeholder picture for the image types. [side] is the slot's smaller side in px
 * of the face, which sizes the text.
 */
@Composable
internal fun WffComplicationDefault(
  sample: WffComplicationSample,
  side: Float,
  dpPerPx: Float,
  modifier: Modifier = Modifier,
) {
  fun sp(px: Float) = (px * dpPerPx).sp
  val type = sample.type
  when (type) {
    WffComplicationSample.RANGED_VALUE,
    WffComplicationSample.GOAL_PROGRESS,
    WffComplicationSample.WEIGHTED_ELEMENTS -> {
      Box(
        modifier.drawBehind {
          val stroke = min(size.width, size.height) * 0.1f
          val diameter = min(size.width, size.height) - stroke
          val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
          val ring = Size(diameter, diameter)
          val style = Stroke(stroke, cap = StrokeCap.Round)
          if (type == WffComplicationSample.WEIGHTED_ELEMENTS) {
            val total = sample.weights.sumOf { it.first }.takeIf { it > 0 } ?: 1.0
            val gap = 8f
            var start = -90f
            for ((weight, colour) in sample.weights) {
              val sweep = (weight / total * 360).toFloat()
              drawArc(
                Color(colour),
                start + gap / 2,
                (sweep - gap).coerceAtLeast(1f),
                useCenter = false,
                topLeft = topLeft,
                size = ring,
                style = Stroke(stroke, cap = StrokeCap.Butt),
              )
              start += sweep
            }
          } else {
            drawArc(TRACK, -90f, 360f, false, topLeft, ring, style = style)
            val progress = (sample.progress ?: 0.0).toFloat()
            if (progress > 0f)
              drawArc(ACCENT, -90f, 360f * progress, false, topLeft, ring, style = style)
          }
        },
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          sample.image?.let { image ->
            Box(
              Modifier.size((side * 0.22f * dpPerPx).dp).drawBehind {
                drawWffGlyph(image, SOFT_INK, Offset.Zero, size)
              },
            )
          }
          WffText(
            Modifier.fillMaxWidth().height((side * 0.3f * dpPerPx).dp),
            sample.text,
            INK,
            sp(side * 0.22f),
            "CENTER",
          )
        }
      }
    }
    WffComplicationSample.MONOCHROMATIC_IMAGE,
    WffComplicationSample.SMALL_IMAGE,
    WffComplicationSample.PHOTO_IMAGE -> {
      val image = sample.image ?: WffSampleImage.CLOCK
      val photo = type == WffComplicationSample.PHOTO_IMAGE
      Box(
        modifier
          .clip(RoundedCornerShape(percent = if (photo) 20 else 50))
          .background(if (type == WffComplicationSample.SMALL_IMAGE) ACCENT else Color.Transparent)
          .padding((side * (if (photo) 0f else 0.15f) * dpPerPx).dp)
          .drawBehind {
            drawWffGlyph(
              image,
              if (type == WffComplicationSample.SMALL_IMAGE) Color.Black else INK,
              Offset.Zero,
              size,
            )
          },
      )
    }
    WffComplicationSample.LONG_TEXT ->
      Row(
        modifier.clip(RoundedCornerShape(percent = 40)).background(CHIP).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        sample.image?.let { image ->
          Box(
            Modifier.size((side * 0.45f * dpPerPx).dp).drawBehind {
              drawWffGlyph(image, SOFT_INK, Offset.Zero, size)
            },
          )
        }
        WffText(
          Modifier.weight(1f).fillMaxSize(),
          sample.text,
          INK,
          sp(side * 0.42f),
          "START",
        )
      }
    else -> {
      // SHORT_TEXT and NO_DATA: the icon over the text, the title under it when there is room.
      Box(
        modifier.clip(RoundedCornerShape(percent = 30)).background(CHIP),
        contentAlignment = Alignment.Center,
      ) {
        val lines = listOfNotNull(sample.text, sample.title.takeIf { it.isNotEmpty() })
        Column(
          Modifier.fillMaxSize().padding(2.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          val icon = sample.image?.takeIf { type != WffComplicationSample.NO_DATA }
          if (icon != null && lines.size == 1)
            Box(
              Modifier.size((side * 0.3f * dpPerPx).dp).drawBehind {
                drawWffGlyph(icon, SOFT_INK, Offset.Zero, size)
              },
            )
          WffText(
            Modifier.fillMaxWidth().height((side * 0.42f * dpPerPx).dp),
            sample.text,
            INK,
            sp(side * 0.34f),
            "CENTER",
          )
          if (lines.size > 1)
            WffText(
              Modifier.fillMaxWidth().height((side * 0.28f * dpPerPx).dp),
              sample.title,
              SOFT_INK,
              sp(side * 0.22f),
              "CENTER",
            )
        }
      }
    }
  }
}

/** [image]'s placeholder picture in the box at [origin] of [extent], in [color]. */
internal fun DrawScope.drawWffGlyph(
  image: WffSampleImage,
  color: Color,
  origin: Offset,
  extent: Size,
) {
  val side = min(extent.width, extent.height)
  val left = origin.x + (extent.width - side) / 2
  val top = origin.y + (extent.height - side) / 2
  fun at(x: Float, y: Float) = Offset(left + x * side, top + y * side)
  fun box(w: Float, h: Float) = Size(w * side, h * side)
  val line = Stroke(side * 0.08f, cap = StrokeCap.Round)
  when (image) {
    WffSampleImage.BATTERY -> {
      drawRoundRect(
        color,
        at(0.1f, 0.3f),
        box(0.72f, 0.4f),
        CornerRadius(side * 0.06f),
        style = line,
      )
      drawRect(color, at(0.84f, 0.42f), box(0.08f, 0.16f))
      drawRect(color, at(0.18f, 0.38f), box(0.48f, 0.24f))
    }
    WffSampleImage.STEPS -> {
      drawOval(color, at(0.18f, 0.15f), box(0.26f, 0.4f))
      drawOval(color, at(0.2f, 0.6f), box(0.2f, 0.16f))
      drawOval(color, at(0.56f, 0.3f), box(0.26f, 0.4f))
      drawOval(color, at(0.58f, 0.75f), box(0.2f, 0.16f))
    }
    WffSampleImage.HEART -> {
      val path =
        Path().apply {
          moveTo(at(0.5f, 0.88f).x, at(0.5f, 0.88f).y)
          cubicTo(
            at(0.05f, 0.55f).x,
            at(0.05f, 0.55f).y,
            at(0.1f, 0.1f).x,
            at(0.1f, 0.1f).y,
            at(0.5f, 0.3f).x,
            at(0.5f, 0.3f).y,
          )
          cubicTo(
            at(0.9f, 0.1f).x,
            at(0.9f, 0.1f).y,
            at(0.95f, 0.55f).x,
            at(0.95f, 0.55f).y,
            at(0.5f, 0.88f).x,
            at(0.5f, 0.88f).y,
          )
          close()
        }
      drawPath(path, color)
    }
    WffSampleImage.SUN -> {
      drawCircle(color, side * 0.2f, at(0.5f, 0.5f))
      for (ray in 0 until 8) {
        val a = ray * PI.toFloat() / 4
        drawLine(
          color,
          at(0.5f + 0.3f * cos(a), 0.5f + 0.3f * sin(a)),
          at(0.5f + 0.42f * cos(a), 0.5f + 0.42f * sin(a)),
          strokeWidth = line.width,
          cap = StrokeCap.Round,
        )
      }
    }
    WffSampleImage.CALENDAR -> {
      drawRoundRect(
        color,
        at(0.15f, 0.2f),
        box(0.7f, 0.65f),
        CornerRadius(side * 0.06f),
        style = line,
      )
      drawRect(color, at(0.15f, 0.2f), box(0.7f, 0.18f))
      drawRect(color, at(0.3f, 0.52f), box(0.14f, 0.12f))
    }
    WffSampleImage.BELL -> {
      val path =
        Path().apply {
          moveTo(at(0.2f, 0.72f).x, at(0.2f, 0.72f).y)
          quadraticTo(at(0.28f, 0.6f).x, at(0.28f, 0.6f).y, at(0.28f, 0.45f).x, at(0.28f, 0.45f).y)
          quadraticTo(at(0.3f, 0.15f).x, at(0.3f, 0.15f).y, at(0.5f, 0.15f).x, at(0.5f, 0.15f).y)
          quadraticTo(at(0.7f, 0.15f).x, at(0.7f, 0.15f).y, at(0.72f, 0.45f).x, at(0.72f, 0.45f).y)
          quadraticTo(at(0.72f, 0.6f).x, at(0.72f, 0.6f).y, at(0.8f, 0.72f).x, at(0.8f, 0.72f).y)
          close()
        }
      drawPath(path, color)
      drawCircle(color, side * 0.08f, at(0.5f, 0.82f))
    }
    WffSampleImage.CLOCK -> {
      drawCircle(color, side * 0.4f, at(0.5f, 0.5f), style = line)
      drawLine(color, at(0.5f, 0.5f), at(0.5f, 0.25f), line.width, StrokeCap.Round)
      drawLine(color, at(0.5f, 0.5f), at(0.68f, 0.5f), line.width, StrokeCap.Round)
    }
    WffSampleImage.PHOTO -> {
      // A landscape filling the whole box: sky, sun, two hills.
      drawRect(
        Brush.verticalGradient(
          listOf(Color(0xFF5C9DFF), Color(0xFFFFC387)),
          startY = origin.y,
          endY = origin.y + extent.height,
        ),
        origin,
        extent,
      )
      drawCircle(
        Color(0xFFFFF1B5),
        extent.minDimension * 0.12f,
        Offset(origin.x + extent.width * 0.72f, origin.y + extent.height * 0.3f),
      )
      fun hill(peakX: Float, peakY: Float, tint: Color) {
        val path =
          Path().apply {
            moveTo(origin.x + extent.width * (peakX - 0.55f), origin.y + extent.height)
            lineTo(origin.x + extent.width * peakX, origin.y + extent.height * peakY)
            lineTo(origin.x + extent.width * (peakX + 0.55f), origin.y + extent.height)
            close()
          }
        drawPath(path, tint)
      }
      hill(0.3f, 0.45f, Color(0xFF3D6B4F))
      hill(0.75f, 0.6f, Color(0xFF2E5240))
    }
  }
}
