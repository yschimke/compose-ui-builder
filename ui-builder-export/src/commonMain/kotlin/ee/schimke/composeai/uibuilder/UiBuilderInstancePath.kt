package ee.schimke.composeai.uibuilder

/**
 * Which drawn box a measurement, a selection or a comment belongs to.
 *
 * The document is a flat map of nodes, and three walks agree with it one for one: the canvas
 * reports bounds per node, the exporters emit one call per node, and everything anchored to a node
 * id — selection, the inspector, comments, the native lane's `testTag` — reads that agreement as an
 * identity. **One node id is one drawn box** is load-bearing far outside the renderer, and it is
 * exactly what a loop over data and an instance of a reusable component both break: one node, drawn
 * *n* times.
 *
 * A path is what survives that. It names a box rather than a node, and it is deliberately no longer
 * than it has to be:
 *
 * - With no repetition above it, the path **is** the node id — `cell-0` — because node ids are
 *   unique in the document and nothing needs disambiguating. Every key every consumer writes today
 *   is unchanged, which is the point: the seam can land before anything produces a second copy.
 * - Inside a copy, the path carries the repeating node, which copy, and the way down from it —
 *   `cell#3`, `cell#3/label`, and `row#2/cell#4/label` where one repeat sits inside another. The
 *   chain begins at the **outermost repeat above the box**, never at the root: above every repeat
 *   there is nothing to disambiguate, and below one the copies are exactly what a bare id loses.
 *
 * ## The path is the segments, not the spelling
 *
 * [value] is a rendering for a log line, a `testTag` or a wire field — never the state. A node id
 * is whatever the document says it is: `InsertNode` rejects a blank or an already-used id and
 * nothing else, so `section/title` and `foo#bar` are ids a design may legitimately carry. Parsing
 * [nodeId] back out of a joined string would answer `title` and `foo` for those, and the renderer
 * looks the node up by that answer — `document.nodes.getValue(path.nodeId)` — so a punctuated id
 * would have published its bounds under the wrong name and taken the canvas down on a text node.
 * Holding the segments means the id comes back exactly as it went in, whatever it contains, and two
 * paths are equal when their segments are.
 */
class UiBuilderInstancePath private constructor(internal val segments: List<Segment>) {

  /**
   * One step of a path: a node, which copy of it if a run draws it more than once, and whether it
   * opens a scope — a component body, whose nodes are drawn once per placement rather than once.
   */
  internal data class Segment(
    val nodeId: String,
    val occurrence: Int? = null,
    val placement: Boolean = false,
  )

  /** The document node this box drew, exactly as the document spells it. */
  val nodeId: String
    get() = segments.last().nodeId

  /** Whether this box is drawn once, so that its node id already identifies it. */
  val isAuthored: Boolean
    get() = segments.size == 1 && segments.single().let { it.occurrence == null && !it.placement }

  /**
   * A rendering, for a log line or a tag — `cell#3/label`. Two different paths can only be told
   * apart by their segments, so nothing decides identity by comparing this.
   */
  val value: String
    get() =
      segments.joinToString(SEPARATOR.toString()) { segment ->
        segment.occurrence?.let { "${segment.nodeId}$OCCURRENCE$it" } ?: segment.nodeId
      }

  /**
   * The path of a child drawn inside this one.
   *
   * Below no repeat this is the child's own id — the identity it already had. Below one it keeps
   * the whole chain of copies it sits in, because those are the only thing that distinguishes it
   * from the same node in the copy beside it.
   */
  fun child(childNodeId: String): UiBuilderInstancePath =
    if (isAuthored) of(childNodeId) else UiBuilderInstancePath(segments + Segment(childNodeId))

  /**
   * One copy of this node, by its index in the run.
   *
   * Applied by whatever draws the run — a loop over rows, a component placed more than once — to
   * the path of the node being repeated, before its children are drawn.
   */
  fun occurrence(index: Int): UiBuilderInstancePath =
    UiBuilderInstancePath(segments.dropLast(1) + segments.last().copy(occurrence = index))

  /**
   * The scope a component body is drawn in, opened by the node that places it.
   *
   * A body carries no copy index because there is nothing to count: it is drawn once per placement,
   * and the placing node's id is unique. What it does need is that its nodes stop identifying
   * themselves — the same body under two instances is two boxes — which is exactly what opening a
   * scope means for every path below it.
   */
  fun placement(): UiBuilderInstancePath =
    UiBuilderInstancePath(segments.dropLast(1) + segments.last().copy(placement = true))

  override fun equals(other: Any?): Boolean =
    this === other || (other is UiBuilderInstancePath && segments == other.segments)

  override fun hashCode(): Int = segments.hashCode()

  override fun toString(): String = value

  companion object {
    private const val SEPARATOR = '/'
    private const val OCCURRENCE = '#'

    /** The path of a node drawn once, which is every node the format can express today. */
    fun of(nodeId: String): UiBuilderInstancePath = UiBuilderInstancePath(listOf(Segment(nodeId)))
  }
}
