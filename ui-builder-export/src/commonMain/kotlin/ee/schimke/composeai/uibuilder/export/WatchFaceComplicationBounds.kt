package ee.schimke.composeai.uibuilder.export

/**
 * Where the watch cuts a complication's content.
 *
 * The watch-face runtime clips everything a `<ComplicationSlot>` draws to that slot's bounding area
 * (`<BoundingBox>`, `<BoundingOval>` or `<BoundingRoundBox>`), so a label placed past it is cut on
 * the watch however whole it looks in the editor (compose-preview-server #2016: brazen-nugget's
 * labels sat outside their 96×96 boxes). [boundingArea] is that area as the export writes it, for
 * the canvas to clip to; [clipped] and [issues] name the parts that fall outside it.
 *
 * Coordinates are WFF pixels relative to the slot, which is also the space its `<Complication>`
 * content is placed in.
 */
object WatchFaceComplicationBounds {
  /** A warning, never a refusal: the face exports and runs, it just shows less than drawn. */
  const val CODE: String = "WFF_COMPLICATION_CONTENT_CLIPPED"

  data class Area(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float
      get() = x + width

    val bottom: Float
      get() = y + height

    /** Whether [other] lies inside this, allowing half a pixel for rounding. */
    fun covers(other: Area): Boolean =
      other.x >= x - SLACK &&
        other.y >= y - SLACK &&
        other.right <= right + SLACK &&
        other.bottom <= bottom + SLACK

    override fun toString(): String = "${format(x)},${format(y)} ${format(width)}×${format(height)}"
  }

  /** One drawn part of a slot's content that reaches past the slot's bounding area. */
  data class Outside(val nodeId: String, val componentId: String, val area: Area)

  data class Clipped(val slotId: String, val boundingArea: Area, val outside: List<Outside>)

  /**
   * The bounding area of [slot], a `wff/complication-slot` that is [slotWidth] × [slotHeight]: the
   * slot's own box, unless the design states the bounding element's attributes (`bounds:*`, kept in
   * `wffExtras` by the importer), exactly as [WatchFaceXmlExporter] writes it.
   */
  fun boundingArea(slot: UiBuilderNode, slotWidth: Float, slotHeight: Float): Area {
    val props = WatchFaceXmlExporter.Props(slot, mutableListOf())
    val width = props.float("width") ?: slotWidth
    val height = props.float("height") ?: slotHeight
    // The exporter applies `bounds:*` only through the element's recorded attribute order.
    val listed =
      if (props.has(BOUNDS_ORDER))
        props.text(BOUNDS_ORDER).orEmpty().split(' ').filter { it.isNotEmpty() }.toSet()
      else emptySet()
    fun stated(name: String, computed: Float): Float =
      if (name in listed) props.float("bounds:$name") ?: computed else computed
    return Area(
      stated("x", 0f),
      stated("y", 0f),
      stated("width", width),
      stated("height", height),
    )
  }

  /** Every slot in [document] with content past its bounding area, in document order. */
  fun clipped(document: UiBuilderDocument): List<Clipped> {
    val scene = document.roots.singleOrNull()?.let(document.nodes::get) ?: return emptyList()
    val sceneProps = WatchFaceXmlExporter.Props(scene, mutableListOf())
    val found = mutableListOf<Clipped>()
    val visited = mutableSetOf<String>()
    fun walkForSlots(node: UiBuilderNode, width: Float, height: Float) {
      if (!visited.add(node.id)) return
      for (child in children(document, node)) {
        val props = WatchFaceXmlExporter.Props(child, mutableListOf())
        val w = props.float("width") ?: width
        val h = props.float("height") ?: height
        if (child.componentId == WatchFaceXmlExporter.COMPLICATION_SLOT) {
          slotClipped(document, child, w, h)?.let(found::add)
        } else walkForSlots(child, w, h)
      }
    }
    walkForSlots(
      scene,
      sceneProps.float("width") ?: WatchFaceXmlExporter.DEFAULT_SIZE.toFloat(),
      sceneProps.float("height") ?: WatchFaceXmlExporter.DEFAULT_SIZE.toFloat(),
    )
    return found
  }

  /** [clipped] as the editor's and the design service's advisory issues, one per slot. */
  fun issues(document: UiBuilderDocument): List<WatchFaceDesignValidation.Issue> =
    clipped(document).map { slot ->
      val shown = slot.outside.take(NAMED)
      val parts =
        shown.joinToString(", ") { "`${it.nodeId}` at ${it.area}" } +
          (slot.outside.size - shown.size).let { if (it > 0) " and $it more" else "" }
      val count = slot.outside.size
      WatchFaceDesignValidation.Issue(
        code = CODE,
        message =
          "complication content extends outside its bounding area and is clipped on the " +
            "watch: `${slot.slotId}` draws only inside ${slot.boundingArea}, and " +
            (if (count == 1) "its part " else "$count of its parts fall outside it: ") +
            parts +
            (if (count == 1) " falls outside it" else "") +
            ". Grow the slot's bounding box (`bounds:x`, `bounds:y`, `bounds:width`, " +
            "`bounds:height`) to cover every layout it draws, or move the " +
            (if (count == 1) "part" else "parts") +
            " inside it.",
        nodeId = slot.slotId,
      )
    }

  private fun slotClipped(
    document: UiBuilderDocument,
    slot: UiBuilderNode,
    width: Float,
    height: Float,
  ): Clipped? {
    val area = boundingArea(slot, width, height)
    val outside = mutableListOf<Outside>()
    val visited = mutableSetOf<String>()
    // [toSlot] takes a point in [node]'s box to the slot's coordinates: every ancestor's offset,
    // and its angle and scale about its pivot, as the canvas places it (`wffPlace`).
    fun walk(node: UiBuilderNode, toSlot: Affine, boxW: Float, boxH: Float) {
      if (!visited.add(node.id)) return
      for (child in children(document, node)) {
        val props = WatchFaceXmlExporter.Props(child, mutableListOf())
        val w = props.float("width") ?: boxW
        val h = props.float("height") ?: boxH
        val pivotX = (props.float("pivotX") ?: 0.5f) * w
        val pivotY = (props.float("pivotY") ?: 0.5f) * h
        // Scaled, then turned, both about the pivot.
        val placed =
          toSlot
            .translate(props.float("x") ?: 0f, props.float("y") ?: 0f)
            .translate(pivotX, pivotY)
            .rotate(props.float("angle") ?: 0f)
            .scale(props.float("scaleX") ?: 1f, props.float("scaleY") ?: 1f)
            .translate(-pivotX, -pivotY)
        // A container is judged by what it draws, not by its box: a full-face group around one
        // small label is not cut anywhere the label is not. A part-draw with shapes of its own
        // paints only them; its box is just their coordinate space.
        val painted: Area? =
          if (child.componentId !in DRAWS) null
          else {
            val shapes = shapeBoxes(document, child, w, h)
            when {
              // A hand turns with the time: it can reach anywhere on the circle its far corner
              // sweeps around its pivot.
              child.componentId == WatchFaceXmlExporter.CLOCK_HAND -> {
                val pivotX = (props.float("pivotX") ?: 0.5f) * w
                val pivotY = (props.float("pivotY") ?: 0.5f) * h
                val reach =
                  listOf(0f to 0f, w to 0f, 0f to h, w to h).maxOf { (x, y) ->
                    kotlin.math.hypot(x - pivotX, y - pivotY)
                  }
                placed.circleBounds(pivotX, pivotY, reach)
              }
              // A part without shapes paints its whole box.
              shapes == null -> placed.bounds(w, h)
              // Else the first of its shapes past the box, or none.
              else -> shapes.map { placed.bounds(it) }.firstOrNull { !area.covers(it) }
            }
          }
        if (painted != null && !area.covers(painted)) {
          outside += Outside(child.id, child.componentId, painted)
        } else if (child.componentId !in DRAWS) walk(child, placed, w, h)
      }
    }
    // Each `<Complication>` fills its slot, so its content is placed in the slot's own box.
    slot.slots["variants"]
      .orEmpty()
      .mapNotNull { document.nodes[it] }
      .filter { it.componentId == WatchFaceXmlExporter.COMPLICATION }
      .forEach { walk(it, Affine.IDENTITY, width, height) }
    return if (outside.isEmpty()) null else Clipped(slot.id, area, outside)
  }

  /** A 2D affine transform, `x' = a·x + c·y + e`, `y' = b·x + d·y + f`. */
  private data class Affine(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
  ) {
    private fun then(o: Affine) =
      Affine(
        a * o.a + c * o.b,
        b * o.a + d * o.b,
        a * o.c + c * o.d,
        b * o.c + d * o.d,
        a * o.e + c * o.f + e,
        b * o.e + d * o.f + f,
      )

    fun translate(x: Float, y: Float) = then(Affine(1f, 0f, 0f, 1f, x, y))

    fun scale(x: Float, y: Float) = then(Affine(x, 0f, 0f, y, 0f, 0f))

    fun rotate(degrees: Float): Affine {
      if (degrees == 0f) return this
      val radians = degrees * kotlin.math.PI / 180.0
      val cos = kotlin.math.cos(radians).toFloat()
      val sin = kotlin.math.sin(radians).toFloat()
      return then(Affine(cos, sin, -sin, cos, 0f, 0f))
    }

    /** The axis-aligned box a [width] × [height] rectangle at the origin covers once moved. */
    fun bounds(width: Float, height: Float): Area = bounds(Area(0f, 0f, width, height))

    /**
     * The axis-aligned box a circle covers once moved: the ellipse it becomes reaches `r·√(a²+c²)`
     * across and `r·√(b²+d²)` down from its moved centre, which a box around the circle would
     * overstate under a turn.
     */
    fun circleBounds(centerX: Float, centerY: Float, radius: Float): Area {
      val x = a * centerX + c * centerY + e
      val y = b * centerX + d * centerY + f
      val halfWidth = radius * kotlin.math.hypot(a, c)
      val halfHeight = radius * kotlin.math.hypot(b, d)
      return Area(
        round(x - halfWidth),
        round(y - halfHeight),
        round(2 * halfWidth),
        round(2 * halfHeight),
      )
    }

    /** The axis-aligned box [box] covers once moved. */
    fun bounds(box: Area): Area {
      val xs = mutableListOf<Float>()
      val ys = mutableListOf<Float>()
      for ((x, y) in
        listOf(box.x to box.y, box.right to box.y, box.x to box.bottom, box.right to box.bottom)) {
        xs += a * x + c * y + e
        ys += b * x + d * y + f
      }
      val left = xs.min()
      val top = ys.min()
      return Area(round(left), round(top), round(xs.max() - left), round(ys.max() - top))
    }

    companion object {
      val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)

      /** Trig noise off a whole-pixel result, so a 180° turn still reads as 80,80. */
      private fun round(value: Float) = kotlin.math.round(value * 1000f) / 1000f
    }
  }

  /**
   * The boxes a `wff/part-draw`'s `wff/shape` children paint, in the part's own coordinates, as the
   * canvas measures them (`shapeBox`): an arc around its centre, a line between its ends widened by
   * half its stroke, anything else at its `x`,`y`. A shape with no size fills the part. Null for a
   * part that is not a part-draw, or has no shapes (it paints its whole box).
   */
  private fun shapeBoxes(
    document: UiBuilderDocument,
    part: UiBuilderNode,
    width: Float,
    height: Float,
  ): List<Area>? {
    if (part.componentId != WatchFaceXmlExporter.PART_DRAW) return null
    val shapes =
      part.slots["content"]
        .orEmpty()
        .mapNotNull { document.nodes[it] }
        .filter { it.componentId == WatchFaceXmlExporter.SHAPE }
    if (shapes.isEmpty()) return null
    return shapes.map { shape ->
      val props = WatchFaceXmlExporter.Props(shape, mutableListOf())
      fun value(name: String) = props.float(name)
      when (props.string("shape")) {
        "line" -> {
          val stroke =
            shape.slots["content"]
              .orEmpty()
              .mapNotNull { document.nodes[it] }
              .firstOrNull { it.componentId == WatchFaceXmlExporter.STROKE }
              ?.let { WatchFaceXmlExporter.Props(it, mutableListOf()).float("thickness") } ?: 1f
          val x0 = value("startX") ?: 0f
          val y0 = value("startY") ?: 0f
          val x1 = value("endX") ?: 0f
          val y1 = value("endY") ?: 0f
          val half = stroke / 2
          Area(
            minOf(x0, x1) - half,
            minOf(y0, y1) - half,
            kotlin.math.abs(x1 - x0) + stroke,
            kotlin.math.abs(y1 - y0) + stroke,
          )
        }
        else -> {
          val w = value("width")
          val h = value("height")
          if (w == null || h == null) Area(0f, 0f, width, height)
          else if (props.string("shape") == "arc")
            Area((value("centerX") ?: 0f) - w / 2, (value("centerY") ?: 0f) - h / 2, w, h)
          // A stroke is drawn inset by half its width, so it stays inside the shape's box.
          else Area(value("x") ?: 0f, value("y") ?: 0f, w, h)
        }
      }
    }
  }

  private fun children(document: UiBuilderDocument, node: UiBuilderNode): List<UiBuilderNode> =
    node.slots.values
      .flatten()
      .mapNotNull { document.nodes[it] }
      .filter { it.componentId !in NOT_DRAWN }

  private fun WatchFaceXmlExporter.Props.float(name: String): Float? =
    if (has(name)) number(name)?.toFloatOrNull() else null

  private fun format(value: Float): String =
    if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

  private const val BOUNDS_ORDER = "boundsAttrOrder"
  private const val SLACK = 0.5f
  private const val NAMED = 3

  /** What draws pixels of its own, as opposed to holding other parts. */
  private val DRAWS =
    setOf(
      WatchFaceXmlExporter.PART_TEXT,
      WatchFaceXmlExporter.PART_DRAW,
      WatchFaceXmlExporter.PART_IMAGE,
      // An `<AnalogClock>` is a container too: it paints only its hands.
      WatchFaceXmlExporter.CLOCK_HAND,
      // A `<DigitalClock>` is a container: its `<TimeText>` children are placed on their own.
      WatchFaceXmlExporter.TIME_TEXT,
    )

  /** Overrides and bookkeeping: none has a box of its own on the watch. */
  private val NOT_DRAWN =
    setOf(
      WatchFaceXmlExporter.VARIANT,
      WatchFaceXmlExporter.TRANSFORM,
      WatchFaceXmlExporter.GYRO,
      WatchFaceXmlExporter.RAW,
      WatchFaceXmlExporter.LAUNCH,
    )
}
