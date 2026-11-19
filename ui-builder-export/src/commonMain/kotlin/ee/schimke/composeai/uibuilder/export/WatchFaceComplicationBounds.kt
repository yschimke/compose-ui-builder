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
    fun walk(node: UiBuilderNode, originX: Float, originY: Float, boxW: Float, boxH: Float) {
      if (!visited.add(node.id)) return
      for (child in children(document, node)) {
        val props = WatchFaceXmlExporter.Props(child, mutableListOf())
        val placed =
          Area(
            originX + (props.float("x") ?: 0f),
            originY + (props.float("y") ?: 0f),
            props.float("width") ?: boxW,
            props.float("height") ?: boxH,
          )
        // A container is judged by what it draws, not by its box: a full-face group around one
        // small label is not cut anywhere the label is not.
        if (child.componentId in DRAWS && !area.covers(placed)) {
          outside += Outside(child.id, child.componentId, placed)
        } else walk(child, placed.x, placed.y, placed.width, placed.height)
      }
    }
    // Each `<Complication>` fills its slot, so its content is placed in the slot's own box.
    slot.slots["variants"]
      .orEmpty()
      .mapNotNull { document.nodes[it] }
      .filter { it.componentId == WatchFaceXmlExporter.COMPLICATION }
      .forEach { walk(it, 0f, 0f, width, height) }
    return if (outside.isEmpty()) null else Clipped(slot.id, area, outside)
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
      WatchFaceXmlExporter.ANALOG_CLOCK,
      WatchFaceXmlExporter.DIGITAL_CLOCK,
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
