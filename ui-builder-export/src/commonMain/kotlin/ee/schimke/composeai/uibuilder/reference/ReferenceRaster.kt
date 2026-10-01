package ee.schimke.composeai.uibuilder.reference

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pixels the editor can reason about, with no Compose and no platform in sight.
 *
 * Everything that *measures* the reference — the diff, the ink boxes, the layer match — runs over
 * this type, so it runs identically in a browser, on the desktop and in a JVM test that builds its
 * pictures out of rectangles. The Compose half
 * ([ImageBitmap][androidx.compose.ui.graphics.ImageBitmap] in, a raster out) is one function in
 * `ReferenceMeasure.kt`.
 *
 * [pixels] are `0xAARRGGBB`, row-major, unpremultiplied.
 */
class ReferenceRaster(val width: Int, val height: Int, val pixels: IntArray) {
  init {
    require(width >= 0 && height >= 0 && pixels.size == width * height) {
      "a ${width}x$height raster needs ${width * height} pixels, not ${pixels.size}"
    }
  }

  operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

  companion object {
    fun filled(width: Int, height: Int, argb: Int): ReferenceRaster =
      ReferenceRaster(width, height, IntArray(width * height) { argb })
  }
}

/** An integer rectangle on a raster, right and bottom exclusive. */
data class RasterRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val width: Int
    get() = right - left

  val height: Int
    get() = bottom - top

  val empty: Boolean
    get() = width <= 0 || height <= 0

  fun clampedTo(width: Int, height: Int): RasterRect =
    RasterRect(
      left.coerceIn(0, width),
      top.coerceIn(0, height),
      right.coerceIn(0, width),
      bottom.coerceIn(0, height),
    )

  fun expanded(by: Int): RasterRect = RasterRect(left - by, top - by, right + by, bottom + by)
}

/**
 * [source] resampled onto a [gridWidth] × [gridHeight] grid, landing in the grid rectangle
 * [left]..[right] × [top]..[bottom] (fractional grid pixels). Grid pixels it does not cover are
 * transparent.
 *
 * Each grid pixel averages up to 4 × 4 source samples from its footprint rather than taking the
 * nearest one: a 3× screenshot brought down to one sample per dp otherwise aliases text into a
 * different shape from the canvas's, and every comparison after that measures the aliasing.
 */
fun resampleOnto(
  source: ReferenceRaster,
  gridWidth: Int,
  gridHeight: Int,
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
): ReferenceRaster {
  val out = IntArray(gridWidth * gridHeight)
  val spanX = right - left
  val spanY = bottom - top
  if (source.width == 0 || source.height == 0 || spanX <= 0f || spanY <= 0f) {
    return ReferenceRaster(gridWidth, gridHeight, out)
  }
  val perX = source.width / spanX
  val perY = source.height / spanY
  val samplesX = ceil(perX).toInt().coerceIn(1, 4)
  val samplesY = ceil(perY).toInt().coerceIn(1, 4)
  val x0 = floor(left).toInt().coerceIn(0, gridWidth)
  val x1 = ceil(right).toInt().coerceIn(0, gridWidth)
  val y0 = floor(top).toInt().coerceIn(0, gridHeight)
  val y1 = ceil(bottom).toInt().coerceIn(0, gridHeight)
  for (gy in y0 until y1) {
    for (gx in x0 until x1) {
      var a = 0
      var r = 0
      var g = 0
      var b = 0
      var n = 0
      for (sy in 0 until samplesY) {
        val srcY = ((gy + (sy + 0.5f) / samplesY - top) * perY).toInt()
        if (srcY < 0 || srcY >= source.height) continue
        for (sx in 0 until samplesX) {
          val srcX = ((gx + (sx + 0.5f) / samplesX - left) * perX).toInt()
          if (srcX < 0 || srcX >= source.width) continue
          val p = source[srcX, srcY]
          a += p ushr 24
          r += (p shr 16) and 0xFF
          g += (p shr 8) and 0xFF
          b += p and 0xFF
          n++
        }
      }
      if (n == 0) continue
      out[gy * gridWidth + gx] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }
  }
  return ReferenceRaster(gridWidth, gridHeight, out)
}

/**
 * Luminance, composited over white, 0..255. Transparent pixels read as white: a design with no
 * background of its own is drawn on the canvas's white, and a mock exported with a transparent
 * background was designed on one.
 */
internal fun ReferenceRaster.luma(): IntArray =
  IntArray(pixels.size) { index ->
    val p = pixels[index]
    val alpha = p ushr 24
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    val y = (r * 299 + g * 587 + b * 114) / 1000
    (y * alpha + 255 * (255 - alpha)) / 255
  }

private fun channelDistance(a: Int, b: Int): Int {
  fun over(p: Int, shift: Int): Int {
    val alpha = p ushr 24
    val c = (p shr shift) and 0xFF
    return (c * alpha + 255 * (255 - alpha)) / 255
  }
  return max(
    abs(over(a, 16) - over(b, 16)),
    max(abs(over(a, 8) - over(b, 8)), abs(over(a, 0) - over(b, 0))),
  )
}

/** One area where the design and the reference disagree, in grid pixels. */
data class ReferenceDiffRegion(
  val rect: RasterRect,
  /** Share of the region's pixels that differ, 0..1. */
  val mismatch: Float,
)

/**
 * How far the design is from the reference, and where.
 *
 * [mismatch] is over the pixels both pictures cover; [coverage] is how much of the frame that is,
 * because "2% different" over a tenth of the screen is a different claim from the same figure over
 * all of it.
 */
data class ReferenceDiffReport(
  val coverage: Float,
  val mismatch: Float,
  /** Largest first, at most [MAX_REGIONS]. */
  val regions: List<ReferenceDiffRegion>,
) {
  companion object {
    const val MAX_REGIONS: Int = 8
  }
}

/**
 * Compare two frame-aligned rasters of the same size.
 *
 * Only pixels inside [coverage] — where the reference is placed — are compared: outside a cropped
 * component or below a short mock there is nothing to disagree with. Inside it a transparent pixel
 * is compared as the white it sits on, because a snapshot of a design with no background of its own
 * is transparent everywhere it draws nothing, and that nothing is still a statement. A pixel
 * differs when any channel is more than [tolerance] apart, which forgives anti-aliasing and
 * colour-profile drift while still catching a wrong grey.
 *
 * Regions are found on a grid of [cellPx] cells rather than per pixel: one glyph rendered half a
 * pixel over is a hundred scattered differing pixels and is one thing to look at, not a hundred.
 */
fun diffRasters(
  design: ReferenceRaster,
  reference: ReferenceRaster,
  cellPx: Int,
  tolerance: Int = 40,
  coverage: RasterRect = RasterRect(0, 0, reference.width, reference.height),
): ReferenceDiffReport {
  require(design.width == reference.width && design.height == reference.height) {
    "diff needs rasters of one size"
  }
  val width = design.width
  val height = design.height
  if (width == 0 || height == 0) return ReferenceDiffReport(0f, 0f, emptyList())
  val cell = cellPx.coerceAtLeast(1)
  val cellsX = (width + cell - 1) / cell
  val cellsY = (height + cell - 1) / cell
  val cellCompared = IntArray(cellsX * cellsY)
  val cellDiffering = IntArray(cellsX * cellsY)
  var compared = 0
  var differing = 0
  for (y in 0 until height) {
    for (x in 0 until width) {
      if (x < coverage.left || x >= coverage.right || y < coverage.top || y >= coverage.bottom) {
        continue
      }
      val ref = reference[x, y]
      compared++
      val c = (y / cell) * cellsX + (x / cell)
      cellCompared[c]++
      if (channelDistance(design[x, y], ref) > tolerance) {
        differing++
        cellDiffering[c]++
      }
    }
  }
  val hot =
    BooleanArray(cellsX * cellsY) {
      cellCompared[it] > 0 && cellDiffering[it] >= max(2, cellCompared[it] / 10)
    }
  val seen = BooleanArray(hot.size)
  val regions = mutableListOf<Pair<ReferenceDiffRegion, Int>>()
  for (start in hot.indices) {
    if (!hot[start] || seen[start]) continue
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    var regionCompared = 0
    var regionDiffering = 0
    val pending = ArrayDeque(listOf(start))
    seen[start] = true
    while (pending.isNotEmpty()) {
      val c = pending.removeFirst()
      val cx = c % cellsX
      val cy = c / cellsX
      minX = min(minX, cx)
      minY = min(minY, cy)
      maxX = max(maxX, cx)
      maxY = max(maxY, cy)
      regionCompared += cellCompared[c]
      regionDiffering += cellDiffering[c]
      // Eight-connected, so a diagonal stroke of differences is one region and not a staircase.
      for (ny in cy - 1..cy + 1) for (nx in cx - 1..cx + 1) {
        if (nx < 0 || ny < 0 || nx >= cellsX || ny >= cellsY) continue
        val n = ny * cellsX + nx
        if (hot[n] && !seen[n]) {
          seen[n] = true
          pending.addLast(n)
        }
      }
    }
    regions +=
      ReferenceDiffRegion(
        RasterRect(minX * cell, minY * cell, (maxX + 1) * cell, (maxY + 1) * cell)
          .clampedTo(width, height),
        regionDiffering.toFloat() / regionCompared.coerceAtLeast(1),
      ) to regionDiffering
  }
  return ReferenceDiffReport(
    coverage = compared.toFloat() / (width * height),
    mismatch = if (compared == 0) 0f else differing.toFloat() / compared,
    regions =
      regions
        .sortedByDescending { it.second }
        .take(ReferenceDiffReport.MAX_REGIONS)
        .map { it.first },
  )
}

/**
 * The bounding box of whatever is drawn inside [window], or null when nothing is.
 *
 * "Drawn" means differing from the window's background, and the background is the most common
 * colour along the window's edge — the colour a label sits on is the one that surrounds it. This is
 * what lets text be compared with text: a node's box includes its line-height padding, a mock's
 * glyphs do not, and the two only agree once both are reduced to the ink.
 */
fun inkBox(raster: ReferenceRaster, window: RasterRect, tolerance: Int = 48): RasterRect? {
  val w = window.clampedTo(raster.width, raster.height)
  if (w.empty) return null
  val edge = HashMap<Int, Int>()
  fun count(x: Int, y: Int) {
    val p = quantised(raster[x, y])
    edge[p] = (edge[p] ?: 0) + 1
  }
  for (x in w.left until w.right) {
    count(x, w.top)
    count(x, w.bottom - 1)
  }
  for (y in w.top until w.bottom) {
    count(w.left, y)
    count(w.right - 1, y)
  }
  val background = edge.maxByOrNull { it.value }?.key ?: return null
  var minX = Int.MAX_VALUE
  var minY = Int.MAX_VALUE
  var maxX = Int.MIN_VALUE
  var maxY = Int.MIN_VALUE
  var inked = 0
  for (y in w.top until w.bottom) {
    for (x in w.left until w.right) {
      if (channelDistance(raster[x, y], background) <= tolerance) continue
      inked++
      minX = min(minX, x)
      minY = min(minY, y)
      maxX = max(maxX, x)
      maxY = max(maxY, y)
    }
  }
  // Three pixels is a speck of noise or a stray anti-aliased edge, not something drawn.
  if (inked < 3) return null
  return RasterRect(minX, minY, maxX + 1, maxY + 1)
}

/** A colour bucketed to 16 levels a channel, so the edge's "most common colour" has a winner. */
private fun quantised(p: Int): Int = (p and 0xF0F0F0F0.toInt()) or 0x08080808

/**
 * Where the patch [rect] of [design] best lines up in [reference], and at what scale.
 *
 * A plain template search: every offset within [searchPx] of where the patch sits now, at every one
 * of [scales] (about the patch's top-left corner), scored by mean absolute luminance difference —
 * coarse at a two-pixel step, then refined at one pixel around the winner. Bounded on purpose: the
 * patch is subsampled to at most a few thousand pixels, so the whole search is a few tens of
 * millions of additions, which is milliseconds on the JVM and comfortably under a frame's worth of
 * patience in a browser.
 *
 * Null when the patch has no texture to match on — a plain surface lines up equally well
 * everywhere, and an answer would be a guess. See [RasterMatch.confident] for the other refusal.
 */
fun matchPatch(
  design: ReferenceRaster,
  reference: ReferenceRaster,
  rect: RasterRect,
  searchPx: Int,
  scales: List<Float> = listOf(1f),
  /** Where the reference is placed; the patch is only scored where it lands inside. */
  coverage: RasterRect = RasterRect(0, 0, reference.width, reference.height),
): RasterMatch? {
  require(design.width == reference.width && design.height == reference.height) {
    "match needs rasters of one size"
  }
  val patch = rect.clampedTo(design.width, design.height)
  if (patch.width < 2 || patch.height < 2) return null
  val designLuma = design.luma()
  val referenceLuma = reference.luma()
  val stride = max(1, sqrt(patch.width.toFloat() * patch.height / MAX_PATCH_SAMPLES).toInt())
  val xs = (patch.left until patch.right step stride).toList()
  val ys = (patch.top until patch.bottom step stride).toList()
  var sum = 0.0
  var sumSq = 0.0
  for (y in ys) for (x in xs) {
    val v = designLuma[y * design.width + x].toDouble()
    sum += v
    sumSq += v * v
  }
  val n = xs.size * ys.size
  val mean = sum / n
  val deviation = sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
  if (deviation < MIN_TEXTURE) return null

  /**
   * Mean |Δ| at an offset and scale, or null when too little of the patch lands on the reference.
   */
  fun score(dx: Int, dy: Int, scale: Float): Float? {
    var total = 0L
    var counted = 0
    for (y in ys) {
      val ry = patch.top + dy + ((y - patch.top) * scale).roundToInt()
      if (ry < coverage.top || ry >= coverage.bottom || ry < 0 || ry >= reference.height) continue
      for (x in xs) {
        val rx = patch.left + dx + ((x - patch.left) * scale).roundToInt()
        if (rx < coverage.left || rx >= coverage.right || rx < 0 || rx >= reference.width) continue
        val ri = ry * reference.width + rx
        total += abs(designLuma[y * design.width + x] - referenceLuma[ri])
        counted++
      }
    }
    return if (counted * 2 < n) null else total.toFloat() / counted
  }

  val zero = score(0, 0, 1f)
  var best: Triple<Int, Int, Float>? = null
  var bestScore = Float.MAX_VALUE
  for (scale in scales) {
    var dy = -searchPx
    while (dy <= searchPx) {
      var dx = -searchPx
      while (dx <= searchPx) {
        val s = score(dx, dy, scale)
        if (s != null && s < bestScore) {
          bestScore = s
          best = Triple(dx, dy, scale)
        }
        dx += 2
      }
      dy += 2
    }
  }
  val coarse = best ?: return null
  // Refined at one pixel and, where scales were searched, at a finer step between the coarse ones:
  // a 22 sp label that should be 28 is 1.27×, which a 5% grid can only call 1.25 or 1.30.
  val fineScales =
    if (scales.size <= 1) listOf(coarse.third)
    else (-4..4).map { coarse.third + it * 0.01f }.filter { it > 0f }
  for (scale in fineScales) {
    for (dy in coarse.second - 2..coarse.second + 2) {
      for (dx in coarse.first - 2..coarse.first + 2) {
        val s = score(dx, dy, scale)
        if (s != null && s < bestScore) {
          bestScore = s
          best = Triple(dx, dy, scale)
        }
      }
    }
  }
  val found = best ?: return null
  return RasterMatch(
    dx = found.first,
    dy = found.second,
    scale = found.third,
    score = bestScore,
    zeroScore = zero,
    texture = deviation.toFloat(),
  )
}

/** The answer [matchPatch] found. Scores are mean absolute luminance differences, 0..255. */
data class RasterMatch(
  val dx: Int,
  val dy: Int,
  val scale: Float,
  val score: Float,
  /** The score where the patch sits now, or null when it sits off the reference. */
  val zeroScore: Float?,
  /** The patch's own luminance spread; how much there was to match on. */
  val texture: Float,
) {
  /**
   * Whether this is a match rather than the least-bad place.
   *
   * The residual has to be small against how much the patch varies: a dark label on white has a
   * spread near 100, and lining it up within a few grey levels is a match, where the best spot for
   * a label whose words differ from the mock's is still a mismatch of half that.
   */
  val confident: Boolean
    get() = score <= max(MATCH_FLOOR, texture * 0.35f)

  companion object {
    private const val MATCH_FLOOR = 12f
  }
}

/** Above this many samples a patch is subsampled; enough for text, cheap enough for a card. */
private const val MAX_PATCH_SAMPLES = 3000f

/** A luminance spread under this is a flat colour, which matches everywhere and so nowhere. */
private const val MIN_TEXTURE = 6.0
