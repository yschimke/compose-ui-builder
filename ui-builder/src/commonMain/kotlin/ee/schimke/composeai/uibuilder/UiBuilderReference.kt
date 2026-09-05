package ee.schimke.composeai.uibuilder

import kotlin.io.encoding.Base64

/**
 * The reference stack: what the editor draws over the canvas to build against, and how.
 *
 * Reference material is **editor scaffolding, not design content**. None of it enters
 * [UiBuilderDocument]: no node holds it, the Compose and SVG exports cannot see it, and no
 * collaboration operation carries it. That is deliberate — a mock pasted in to line a screen up
 * must not become an `m3/image` node that ships in the generated Kotlin — and the closed wire
 * mutation set has no room for one anyway.
 *
 * It is still per-design and durable. The host loads it when the design opens and persists it when
 * it changes, because the alignment *is* the work: a mock nudged two dp left, a red circle round
 * the thing that is wrong, and a component dropped where it should go are worth exactly as much as
 * they are worth the next morning.
 */

/**
 * How the reference picture is compared against what the builder draws.
 *
 * The four modes are the ones every visual-diff tool converges on, for different questions:
 * [Overlay] answers "is this in the right place" (a translucent mock over the real thing),
 * [Difference] answers "is this *exactly* right" (matching pixels go black, so anything that is not
 * black is the answer), [Split] answers "does this look the same" without the two fighting for the
 * same pixels, and [Boxes] answers "are the boxes the right size" from an SVG's own geometry rather
 * than from its paint.
 *
 * There is no `Off` member on purpose. Hiding the overlay is [ReferenceOverlaySettings.visible],
 * which is a different fact: an operator who puts the mock away has not stopped working in
 * difference mode, and folding the two together would lose the mode every time the overlay was
 * toggled — which is several times per adjustment.
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
 * One imported picture, identified by [id] rather than by its bytes.
 *
 * Not a `data class`, and equality is [id] alone, because this value is reachable from
 * [UiBuilderEditorState] — which the editor compares on every recomposition to decide whether to
 * publish a new state. Structural equality would compare a multi-megabyte base64 string on every
 * frame. [id] is opaque to the editor: whoever mints it only has to keep it stable for identical
 * bytes and different for different ones.
 *
 * [base64] rather than a `ByteArray` because that is the form it arrives in and leaves in — the
 * transport is JSON both ways — and because a `ByteArray` field would silently reintroduce
 * reference equality here.
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
   * Where the picture came from, for provenance only — typically the Figma node it was exported
   * from. Never fetched: the serve host holds no Figma credential and makes no outbound call for
   * it, so this is a link an operator can follow, not an import mechanism.
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
 * The SVG source behind a vector import, or null when there is none to read.
 *
 * Null rather than an exception for a base64 payload that will not decode: the picture arrived from
 * a browser paste or a stored file, and neither is worth taking the editor down for.
 */
fun ReferenceImage.svgTextOrNull(): String? =
  if (!isVector) null
  else
    try {
      Base64.Default.decode(base64).decodeToString()
    } catch (_: IllegalArgumentException) {
      null
    }

/**
 * How the base picture is currently being drawn.
 *
 * Every field is authored by the operator and every one is persisted, because the alignment is the
 * work: a mock scaled to 98% to line up with a screen is worthless if reopening the design throws
 * that away and leaves only the bytes.
 */
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
 * A picture placed *somewhere on the frame* rather than fitted to it.
 *
 * This is the "drop a Figma component where it should go" half. The base reference answers "is the
 * whole screen right"; a piece answers "should this button be here, at this size", which is a
 * question about one region and cannot be asked by anything stretched across the frame.
 *
 * The rectangle is in **fractions of the frame**, not dp, so it survives a device-frame change: a
 * piece placed over the top third of a phone is still over the top third of the tablet the operator
 * switches to, which is the intent, where a dp rectangle would drift off the corner.
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
   * The catalog component this piece is a *picture of*, when it is a picture of one.
   *
   * The hinge of the one-way door between the two halves of this editor, and the reason the door
   * can stay one-way. A live preview dropped onto the reference is rasterised on the way in — it
   * becomes pixels, immediately, like everything else here — but it need not forget what it was.
   * With this recorded, "build this for real" is a lookup rather than a guess, and without it the
   * only way back is for somebody to recognise the picture.
   *
   * Null for a screenshot, a Figma export, or anything else that was never a component.
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
   * Moved by a fraction of the frame, keeping a grabbable sliver on screen.
   *
   * Clamped on the *result* rather than on the delta, so a drag that would carry a piece off the
   * edge stops at the edge instead of stopping the drag: a pointer that has left the frame is a
   * normal way to finish a fling, and a piece dragged out of reach is a piece that cannot be
   * fetched back.
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
 * What a mark is.
 *
 * The set is the vocabulary of a whiteboard next to a screen, not of a drawing program: freehand to
 * scribble, a box and a rounded box because that is the shape of nearly everything in a Compose
 * layout, an ellipse to circle a mistake, an arrow to point at one, a label to say what is wrong,
 * and an image placeholder for the picture that is not there yet. Every one but [Pen] is defined by
 * two points, so every one but [Pen] is drawn by dragging out its bounds.
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
   * A filled rectangle, painted in the screen's own background.
   *
   * The tool that makes a real screenshot editable. A photograph of a shipped screen is one flat
   * picture: there is no card to delete and no row to move. Painting over a region in the colour
   * the screen is already painted in *removes* that region — and what is left is a screenshot with
   * a hole in it, which is exactly the space a real component can then be built into and compared
   * against its surroundings.
   *
   * Filled rather than stroked, and drawn in document order like every other mark, so covering
   * something and then annotating the space works the way a paint program has taught everyone it
   * does.
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
 * One removable annotation drawn over the frame.
 *
 * Points are fractions of the frame, for the same reason a [ReferencePiece]'s rectangle is: an
 * arrow pointing at the overflow menu should still point at it after the frame changes size.
 *
 * Every mark is individually removable and carries its own [id] — that is what "markup" has to mean
 * to be usable. A layer of drawing that can only be cleared wholesale is one nobody commits to.
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
   * The label a [ReferenceMarkupKind.Text] mark draws, and the caption an image placeholder gets.
   *
   * On the mark rather than on a separate list, because a note and the box it belongs to are one
   * thing: rubbing out the box has to take the words with it.
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

/**
 * The colours the markup palette offers. Enough to mean different things; few enough to pick from.
 */
val REFERENCE_MARKUP_COLORS: List<Long> =
  listOf(0xFFFF5252, 0xFFFFC400, 0xFF00E676, 0xFF40C4FF, 0xFFFFFFFF)

/**
 * What a pointer press on the canvas does while the reference panel is open.
 *
 * [None] is the default and is not a tool: it means the canvas behaves exactly as it always has,
 * selecting layers. Any other value takes the pointer, which is why it is a mode the operator turns
 * on rather than something inferred — an editor where dragging sometimes moves a node and sometimes
 * draws on it is an editor nobody trusts.
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
 * The reference half of the editor's state: the picture, the pieces, the marks, and the aim.
 *
 * [layoutBoxes] is derived rather than authored — [extractSvgLayoutBoxes] reads them out of an
 * imported SVG once, at attach, so [ReferenceDiffMode.Boxes] costs a list walk per frame instead of
 * a parse. An empty list is the honest answer for a raster import, and the panel then does not
 * offer a mode with nothing in it.
 */
data class ReferenceOverlayState(
  val image: ReferenceImage? = null,
  val settings: ReferenceOverlaySettings = ReferenceOverlaySettings(),
  val layoutBoxes: List<ReferenceLayoutBox> = emptyList(),
  val pieces: List<ReferencePiece> = emptyList(),
  val marks: List<ReferenceMark> = emptyList(),
  /**
   * The active tool. Editor state rather than persisted state: which tool was in hand is a fact
   * about a moment, not about the design, and reopening a design holding a pen would be a surprise.
   */
  val tool: ReferenceTool = ReferenceTool.None,
  val markupColorArgb: Long = ReferenceMark.DEFAULT_MARKUP_COLOR,
  /**
   * The words the next [ReferenceMarkupKind.Text] mark will carry.
   *
   * Typed before the box is dragged rather than after, because a text field that appears where a
   * drag ended is a text field over the thing being annotated — and because this way the same label
   * can be dropped in three places without retyping it.
   */
  val markupText: String = "",
  /** Which piece [ReferenceTool.MovePiece] moves. The last one placed, until another is picked. */
  val selectedPieceId: String? = null,
  /**
   * How many marks and pieces this session has minted ids for.
   *
   * A counter rather than a hash or a random id, because ids have to be stable across a state
   * update and distinct between two identical strokes — and because a pure reducer has no clock and
   * no randomness. Restored state seeds it past whatever came back from storage.
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
 * A reference the host read back from storage: the picture and pieces, plus the alignment and marks
 * they were left at.
 *
 * Deliberately not a [ReferenceOverlayState]: the derived half of that value — the layout boxes —
 * is not the host's to supply, and a host that guessed at them would be a second reader of SVG
 * geometry disagreeing with the first. The editor re-derives them on attach. The tool is absent for
 * the reason given on [ReferenceOverlayState.tool].
 */
data class RestoredReference(
  val image: ReferenceImage? = null,
  val settings: ReferenceOverlaySettings = ReferenceOverlaySettings(),
  val pieces: List<ReferencePiece> = emptyList(),
  val marks: List<ReferenceMark> = emptyList(),
)

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

/**
 * How wide a piece is when it lands, as a fraction of the frame.
 *
 * Two fifths: big enough to see what was dropped and to get a pointer onto it, small enough that it
 * does not hide the region it is being compared against.
 */
internal const val PLACED_PIECE_WIDTH_FRACTION: Float = 0.4f

/**
 * The corner radius a [ReferenceMarkupKind.RoundedRectangle] is drawn with, in dp.
 *
 * 12, which is Material 3's medium corner — the shape a card, a text field and a menu already have,
 * so a box drawn round one of them lines up with it instead of being visibly a different radius.
 */
internal const val MARKUP_CORNER_RADIUS_DP: Float = 12f

/** The type size a [ReferenceMarkupKind.Text] mark is drawn at, in sp-equivalent dp. */
internal const val MARKUP_TEXT_SIZE_DP: Float = 14f
