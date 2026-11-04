package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument

/** One node's box under the canvas, in whatever space the caller's point is in. */
internal data class CanvasHitBox(
  val nodeId: String,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
) {
  val area: Float
    get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)

  fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

/**
 * Which node a tap on the canvas selects.
 *
 * The deepest node under the point, as before — except where a child fills its container. A runtime
 * can report a label's box as the whole content area of the button around it (a Remote Compose
 * button lays its content out that way), so the smallest box under a tap on the button's padding
 * was the label's, and the button itself was reachable only through Layers (#354). A box that
 * covers at least [FILLS] of its nearest hit ancestor's is not something a pointer can tell apart
 * from that ancestor, so those form one stack: the first tap selects the outermost, and a tap on
 * the selection drills one level in, as design tools do.
 */
internal object CanvasHitTest {
  /** The share of an ancestor's area a child must cover to be stacked with it. */
  const val FILLS: Float = 0.9f

  /** The node a tap at ([x], [y]) selects, given what is [selectedNodeId] now. */
  fun tap(
    boxes: List<CanvasHitBox>,
    x: Float,
    y: Float,
    selectedNodeId: String?,
    parents: Map<String, String>,
  ): String? {
    val stack = stack(boxes, x, y, parents) ?: return null
    val selected = stack.indexOf(selectedNodeId)
    // On the selection: one level in, staying on the innermost. Elsewhere: the outermost.
    return if (selected > 0) stack[selected - 1] else if (selected == 0) stack[0] else stack.last()
  }

  /**
   * What a press that does not drill (a context menu, a drag pickup) lands on: the selection when
   * it is one of the stacked boxes under the point, else the outermost of them.
   */
  fun press(
    boxes: List<CanvasHitBox>,
    x: Float,
    y: Float,
    selectedNodeId: String?,
    parents: Map<String, String>,
  ): String? {
    val stack = stack(boxes, x, y, parents) ?: return null
    return selectedNodeId?.takeIf { it in stack } ?: stack.last()
  }

  /**
   * The boxes under the point that cannot be told apart, innermost first: the smallest containing
   * box, then each nearest hit ancestor it fills. Null when the point is over nothing.
   */
  internal fun stack(
    boxes: List<CanvasHitBox>,
    x: Float,
    y: Float,
    parents: Map<String, String>,
  ): List<String>? {
    // A node drawn more than once is one candidate: its smallest box under the point.
    val hits =
      boxes
        .filter { it.contains(x, y) }
        .groupBy(CanvasHitBox::nodeId)
        .mapValues { (_, copies) -> copies.minOf(CanvasHitBox::area) }
    val innermost = hits.minByOrNull { it.value }?.key ?: return null
    val stack = mutableListOf(innermost)
    var current = innermost
    while (true) {
      val ancestor =
        generateSequence(parents[current]) { parents[it] }.firstOrNull { it in hits } ?: break
      val ancestorArea = hits.getValue(ancestor)
      if (ancestorArea <= 0f || hits.getValue(current) < ancestorArea * FILLS) break
      stack += ancestor
      current = ancestor
    }
    return stack
  }

  /** Each node's parent in [document], for [tap]. */
  fun parents(document: UiBuilderDocument): Map<String, String> = buildMap {
    document.nodes.values.forEach { node ->
      node.slots.values.forEach { children -> children.forEach { put(it, node.id) } }
    }
  }
}

/** The node boxes a runtime inspection reported, for [CanvasHitTest]. */
internal fun ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot?.hitBoxes():
  List<CanvasHitBox> =
  this?.nodes.orEmpty().mapNotNull { node ->
    node.bounds?.let { CanvasHitBox(node.nodeId, it.x, it.y, it.x + it.width, it.y + it.height) }
  }
