package ee.schimke.composeai.uibuilder.reference

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle in the caller's unit (frame dp or grid pixels). Not Compose's `Rect`, because the
 * measuring code is shared with hosts that have no Compose.
 */
data class ReferenceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width: Float
    get() = right - left

  val height: Float
    get() = bottom - top

  val centerX: Float
    get() = (left + right) / 2f

  val centerY: Float
    get() = (top + bottom) / 2f

  fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

  fun overlaps(other: ReferenceBox): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

  fun intersectionArea(other: ReferenceBox): Float {
    val w = min(right, other.right) - max(left, other.left)
    val h = min(bottom, other.bottom) - max(top, other.top)
    return if (w <= 0f || h <= 0f) 0f else w * h
  }

  /** Intersection over union: 1 for the same box, 0 for boxes that do not touch. */
  fun overlapRatio(other: ReferenceBox): Float {
    val union = width * height + other.width * other.height - intersectionArea(other)
    return if (union <= 0f) 0f else intersectionArea(other) / union
  }

  fun offset(dx: Float, dy: Float): ReferenceBox =
    ReferenceBox(left + dx, top + dy, right + dx, bottom + dy)
}

/**
 * Where the base reference lands inside a [frameWidth] × [frameHeight] frame, in that frame's
 * units, under [fit], then scaled by [scale] and nudged by [offsetX], [offsetY]. The one placement
 * rule, shared by the editor's overlay and measurements and the serve host's agent tools.
 *
 * - [ReferenceFit.Contain]: the whole picture, centred (stretching would hide aspect mismatches).
 * - [ReferenceFit.Width]: across the width, pinned to the top — how a tall capture lines up.
 * - [ReferenceFit.Actual]: its own dp size from the top-left; needs [facts], else falls back to
 *   contain.
 *
 * The nudge applies after the scale, so a unit of nudge is a unit on screen.
 */
fun referencePlacement(
  frameWidth: Float,
  frameHeight: Float,
  imageWidthPx: Float,
  imageHeightPx: Float,
  scale: Float,
  offsetX: Float,
  offsetY: Float,
  fit: ReferenceFit = ReferenceFit.Contain,
  facts: ReferenceFacts? = null,
): ReferenceBox {
  if (imageWidthPx <= 0f || imageHeightPx <= 0f || frameWidth <= 0f || frameHeight <= 0f) {
    return ReferenceBox(0f, 0f, frameWidth, frameHeight)
  }
  when (fit) {
    ReferenceFit.Contain -> Unit
    ReferenceFit.Width -> {
      val width = frameWidth * scale
      val height = width * imageHeightPx / imageWidthPx
      val left = (frameWidth - width) / 2f + offsetX
      return ReferenceBox(left, offsetY, left + width, offsetY + height)
    }
    ReferenceFit.Actual -> {
      if (facts != null && facts.known && facts.frameWidthDp > 0f) {
        val perDp = frameWidth / facts.frameWidthDp
        val width = facts.widthDp * perDp * scale
        val height = facts.heightDp * perDp * scale
        return ReferenceBox(offsetX, offsetY, offsetX + width, offsetY + height)
      }
    }
  }
  val contain = min(frameWidth / imageWidthPx, frameHeight / imageHeightPx)
  val width = imageWidthPx * contain * scale
  val height = imageHeightPx * contain * scale
  val left = (frameWidth - width) / 2f + offsetX
  val top = (frameHeight - height) / 2f + offsetY
  return ReferenceBox(left, top, left + width, top + height)
}
