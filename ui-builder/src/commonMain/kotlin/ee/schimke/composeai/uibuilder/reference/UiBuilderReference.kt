package ee.schimke.composeai.uibuilder.reference

import kotlin.io.encoding.Base64

// The reference stack is editor scaffolding, never design content: nothing here enters
// [UiBuilderDocument] or an export, but the host persists it per design.

/**
 * How the reference picture is compared against what the builder draws: [Overlay] for placement,
 * [Difference] for exact pixels, [Split] for a side-by-side wipe, [Boxes] for an SVG's own
 * geometry.
 *
 * There is no `Off`: hiding is [ReferenceOverlaySettings.visible], so toggling the overlay keeps
 * the mode.
 */
enum class ReferenceDiffMode(val wireValue: String, val label: String) {
  Overlay("overlay", "Overlay"),
  Difference("difference", "Difference"),
  Split("split", "Split"),
  Boxes("boxes", "Boxes");

  companion object {
    /** Tolerant, like every other wire read here: an unknown mode falls back rather than throws. */
    fun ofWire(value: String?): ReferenceDiffMode =
      entries.firstOrNull { it.wireValue == value } ?: Overlay
  }
}

/**
 * One imported picture. Not a `data class`: equality is [id] alone because the editor state is
 * compared every recomposition, and structural equality would compare a multi-megabyte [base64]
 * string each frame.
 */
class ReferenceImage(
  val id: String,
  /** Shown in the inspector so the operator can tell which mock is attached. */
  val name: String,
  /** `image/png`, `image/jpeg`, `image/webp` or `image/svg+xml`; nothing else gets this far. */
  val mediaType: String,
  val base64: String,
  /** Natural size where the importer knew it; 0 means "ask the decoder". */
  val widthPx: Int = 0,
  val heightPx: Int = 0,
  /**
   * Where the picture came from (typically a Figma node), for provenance only; never re-fetched.
   */
  val sourceUrl: String? = null,
) {
  val isVector: Boolean
    get() = mediaType == SVG_MEDIA_TYPE

  override fun equals(other: Any?): Boolean = other is ReferenceImage && other.id == id

  override fun hashCode(): Int = id.hashCode()

  override fun toString(): String = "ReferenceImage($id, $name, $mediaType)"

  companion object {
    const val SVG_MEDIA_TYPE: String = "image/svg+xml"

    /**
     * The media types the editor draws. Raster plus SVG; see [referenceSvgRefusal] for the terms.
     */
    val SUPPORTED_MEDIA_TYPES: Set<String> =
      setOf("image/png", "image/jpeg", "image/webp", SVG_MEDIA_TYPE)
  }
}

/**
 * The SVG source behind a vector import, or null when it is not one or its base64 will not decode.
 */
fun ReferenceImage.svgTextOrNull(): String? =
  if (!isVector) null
  else
    try {
      Base64.Default.decode(base64).decodeToString()
    } catch (_: IllegalArgumentException) {
      null
    }

/** How the base picture is drawn. Every field is operator-authored and persisted. */
data class ReferenceOverlaySettings(
  val mode: ReferenceDiffMode = ReferenceDiffMode.Overlay,
  /** Whether the overlay is currently drawn. The picture stays attached either way. */
  val visible: Boolean = true,
  /**
   * Whole percent, so the wire carries no float noise. Only [ReferenceDiffMode.Overlay] reads it.
   */
  val opacityPercent: Int = 50,
  /** Nudge, in screen dp, from the fitted position. */
  val offsetXDp: Float = 0f,
  val offsetYDp: Float = 0f,
  /** Scale about the fitted size, in whole percent. */
  val scalePercent: Int = 100,
  /** Where [ReferenceDiffMode.Split]'s wipe sits, in whole percent of the frame width. */
  val splitPercent: Int = 50,
  /** Draw the SVG's boxes on top of the other modes as well. */
  val alwaysShowBoxes: Boolean = false,
  /**
   * How the picture is placed before [scalePercent] and the nudge apply. Older hosts drop the field
   * and read back [ReferenceFit.Contain], the historic fit.
   */
  val fit: ReferenceFit = ReferenceFit.Contain,
) {
  val opacity: Float
    get() = opacityPercent.coerceIn(0, 100) / 100f

  val scale: Float
    get() = scalePercent.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT) / 100f

  val splitFraction: Float
    get() = splitPercent.coerceIn(0, 100) / 100f

  fun sanitized(): ReferenceOverlaySettings =
    copy(
      opacityPercent = opacityPercent.coerceIn(0, 100),
      offsetXDp = offsetXDp.finiteOrZero().coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
      offsetYDp = offsetYDp.finiteOrZero().coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
      scalePercent = scalePercent.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT),
      splitPercent = splitPercent.coerceIn(0, 100),
    )

  companion object {
    const val MIN_SCALE_PERCENT: Int = 10
    const val MAX_SCALE_PERCENT: Int = 400
    const val MAX_OFFSET_DP: Float = 4000f
  }
}

/**
 * A picture placed somewhere on the frame rather than fitted to it. The rectangle is in fractions
 * of the frame, so it survives a device-frame change.
 */
data class ReferencePiece(
  val id: String,
  val image: ReferenceImage,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
  val opacityPercent: Int = 100,
  /**
   * The catalog component this piece is a picture of, which makes promoting it a lookup rather than
   * a guess. Null for screenshots and exports.
   */
  val componentId: String? = null,
) {
  val width: Float
    get() = right - left

  val height: Float
    get() = bottom - top

  val opacity: Float
    get() = opacityPercent.coerceIn(0, 100) / 100f

  /**
   * Moved by a fraction of the frame. The result is clamped, not the delta, so a grabbable sliver
   * always stays on screen.
   */
  fun movedBy(dx: Float, dy: Float): ReferencePiece {
    val newLeft =
      (left + dx.finiteOrZero()).coerceIn(MIN_PIECE_FRACTION - width, 1f - MIN_PIECE_FRACTION)
    val newTop =
      (top + dy.finiteOrZero()).coerceIn(MIN_PIECE_FRACTION - height, 1f - MIN_PIECE_FRACTION)
    return copy(
      left = newLeft,
      right = newLeft + width,
      top = newTop,
      bottom = newTop + height,
    )
  }

  /** Scaled about its own centre, so resizing does not also move what was just positioned. */
  fun scaledBy(factor: Float): ReferencePiece {
    if (!factor.isFinite() || factor <= 0f) return this
    val centreX = (left + right) / 2f
    val centreY = (top + bottom) / 2f
    val halfWidth = (width * factor / 2f).coerceIn(MIN_PIECE_FRACTION / 2f, 4f)
    val halfHeight = (height * factor / 2f).coerceIn(MIN_PIECE_FRACTION / 2f, 4f)
    return copy(
      left = centreX - halfWidth,
      right = centreX + halfWidth,
      top = centreY - halfHeight,
      bottom = centreY + halfHeight,
    )
  }

  companion object {
    /** Smaller than this and a piece cannot be grabbed again, which is a piece that is lost. */
    const val MIN_PIECE_FRACTION: Float = 0.02f
  }
}

/**
 * What a mark is. Every kind but [Pen] is defined by two points and drawn by dragging out its
 * bounds.
 */
enum class ReferenceMarkupKind(val wireValue: String, val label: String) {
  /** Freehand. The points are the path. */
  Pen("pen", "Draw"),
  /** Two points: opposite corners. */
  Rectangle("rectangle", "Box"),
  /** A box with the corner radius Material puts on nearly everything. */
  RoundedRectangle("roundedRectangle", "Rounded"),
  /** Two points: the bounds it is inscribed in. */
  Ellipse("ellipse", "Ellipse"),
  /** Two points: tail, then head. */
  Arrow("arrow", "Arrow"),
  /**
   * A filled rectangle in the screen's background colour, which "erases" a region of a screenshot
   * so a real component can be built into the hole.
   */
  Fill("fill", "Erase"),
  /** A label, drawn inside the bounds that were dragged out for it. */
  Text("text", "Text"),
  /** The crossed box that means "a picture goes here, this size". */
  ImagePlaceholder("imagePlaceholder", "Image box");

  /** Whether a drag samples a path or only its two ends. */
  val freehand: Boolean
    get() = this == Pen

  companion object {
    fun ofWire(value: String?): ReferenceMarkupKind =
      entries.firstOrNull { it.wireValue == value } ?: Pen
  }
}

/**
 * One individually removable annotation. Points are frame fractions, like a [ReferencePiece]'s
 * rectangle.
 */
data class ReferenceMark(
  val id: String,
  val kind: ReferenceMarkupKind,
  /** Alternating x, y in frame fractions. At least two points; [Pen] may have many. */
  val points: List<Float>,
  /** `0xAARRGGBB`, as a Long because Kotlin has no unsigned literal that survives the wire. */
  val colorArgb: Long = DEFAULT_MARKUP_COLOR,
  val strokeWidthDp: Float = 2f,
  /**
   * The label a [ReferenceMarkupKind.Text] mark draws, or an image placeholder's caption; on the
   * mark so erasing it takes the words too.
   */
  val text: String? = null,
) {
  val pointCount: Int
    get() = points.size / 2

  fun x(index: Int): Float = points[index * 2]

  fun y(index: Int): Float = points[index * 2 + 1]

  /** A mark with fewer than two points, or an odd point list, cannot be drawn and is dropped. */
  val drawable: Boolean
    get() = points.size >= 4 && points.size % 2 == 0 && points.all { it.isFinite() }

  companion object {
    const val DEFAULT_MARKUP_COLOR: Long = 0xFFFF5252
  }
}

/** The colours the markup palette offers. */
val REFERENCE_MARKUP_COLORS: List<Long> =
  listOf(0xFFFF5252, 0xFFFFC400, 0xFF00E676, 0xFF40C4FF, 0xFFFFFFFF)

/**
 * What a pointer press on the canvas does while the reference panel is open. [None] keeps normal
 * layer selection; any other tool takes the pointer, and is only ever chosen explicitly.
 */
enum class ReferenceTool(val label: String, val markupKind: ReferenceMarkupKind? = null) {
  None("Select"),
  /** Drag a placed piece into position. */
  MovePiece("Move"),
  Pen("Draw", ReferenceMarkupKind.Pen),
  Rectangle("Box", ReferenceMarkupKind.Rectangle),
  RoundedRectangle("Rounded", ReferenceMarkupKind.RoundedRectangle),
  Ellipse("Ellipse", ReferenceMarkupKind.Ellipse),
  Arrow("Arrow", ReferenceMarkupKind.Arrow),
  Fill("Erase", ReferenceMarkupKind.Fill),
  Text("Text", ReferenceMarkupKind.Text),
  ImagePlaceholder("Image box", ReferenceMarkupKind.ImagePlaceholder);

  companion object {
    /** The tool for a kind, so a restored mark and a fresh one agree on what drew them. */
    fun of(kind: ReferenceMarkupKind): ReferenceTool = entries.first { it.markupKind == kind }
  }
}

/**
 * The reference half of the editor's state. [layoutBoxes] is derived once at attach by
 * [extractSvgLayoutBoxes] and is empty for a raster import.
 */
data class ReferenceOverlayState(
  val image: ReferenceImage? = null,
  val settings: ReferenceOverlaySettings = ReferenceOverlaySettings(),
  val layoutBoxes: List<ReferenceLayoutBox> = emptyList(),
  val pieces: List<ReferencePiece> = emptyList(),
  val marks: List<ReferenceMark> = emptyList(),
  /** The active tool. Not persisted: reopening a design holding a pen would be a surprise. */
  val tool: ReferenceTool = ReferenceTool.None,
  val markupColorArgb: Long = ReferenceMark.DEFAULT_MARKUP_COLOR,
  /**
   * The words the next [ReferenceMarkupKind.Text] mark will carry, typed before the box is dragged.
   */
  val markupText: String = "",
  /** Which piece [ReferenceTool.MovePiece] moves. The last one placed, until another is picked. */
  val selectedPieceId: String? = null,
  /**
   * How many mark and piece ids this session has minted. A counter because the reducer is pure (no
   * clock or randomness); restored state seeds it past stored ids.
   */
  val mintedIds: Int = 0,
) {
  val attached: Boolean
    get() = image != null

  /** Anything at all to draw — a base picture, a placed piece, or a mark. */
  val hasContent: Boolean
    get() = attached || pieces.isNotEmpty() || marks.isNotEmpty()

  /** Whether the overlay is drawn right now. */
  val drawing: Boolean
    get() = hasContent && settings.visible

  val selectedPiece: ReferencePiece?
    get() = pieces.firstOrNull { it.id == selectedPieceId }

  /** Modes worth offering for this import: [ReferenceDiffMode.Boxes] needs boxes to draw. */
  val availableModes: List<ReferenceDiffMode>
    get() =
      ReferenceDiffMode.entries.filter { it != ReferenceDiffMode.Boxes || layoutBoxes.isNotEmpty() }

  /** One piece replaced in place, or this state unchanged when no piece has that id. */
  fun mapPiece(pieceId: String, block: (ReferencePiece) -> ReferencePiece): ReferenceOverlayState =
    if (pieces.none { it.id == pieceId }) this
    else copy(pieces = pieces.map { if (it.id == pieceId) block(it) else it })
}

/**
 * A reference the host read back from storage. Not a [ReferenceOverlayState]: the editor re-derives
 * the layout boxes on attach, and the tool is never persisted.
 */
data class RestoredReference(
  val image: ReferenceImage? = null,
  val settings: ReferenceOverlaySettings = ReferenceOverlaySettings(),
  val pieces: List<ReferencePiece> = emptyList(),
  val marks: List<ReferenceMark> = emptyList(),
)

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

/** How wide a piece is when it lands, as a fraction of the frame. */
internal const val PLACED_PIECE_WIDTH_FRACTION: Float = 0.4f

/** The corner radius of a [ReferenceMarkupKind.RoundedRectangle]: Material 3's medium corner. */
internal const val MARKUP_CORNER_RADIUS_DP: Float = 12f

/** The type size a [ReferenceMarkupKind.Text] mark is drawn at, in sp-equivalent dp. */
internal const val MARKUP_TEXT_SIZE_DP: Float = 14f

/** [ReferenceFacts] for this picture against a frame. */
fun ReferenceImage.facts(
  frameWidthDp: Float,
  frameHeightDp: Float,
  designDensity: Float,
  /** The decoded size, for an import whose size was not known up front (an SVG, a stored file). */
  decodedWidthPx: Int = 0,
  decodedHeightPx: Int = 0,
): ReferenceFacts =
  ReferenceFacts(
    widthPx = widthPx.takeIf { it > 0 } ?: decodedWidthPx,
    heightPx = heightPx.takeIf { it > 0 } ?: decodedHeightPx,
    frameWidthDp = frameWidthDp,
    frameHeightDp = frameHeightDp,
    // An SVG has no pixels of its own: it is drawn at whatever size it is asked for, so a declared
    // density would be a statement about a rasterisation this editor chose.
    declaredDensity = if (isVector) null else declaredDensityFromName(name),
    designDensity = designDensity,
  )
