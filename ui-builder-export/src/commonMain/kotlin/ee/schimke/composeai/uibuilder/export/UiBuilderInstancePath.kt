package ee.schimke.composeai.uibuilder.export

/**
 * Which drawn box a measurement, a selection or a comment belongs to.
 *
 * "One node id is one drawn box" is relied on everywhere (bounds, export, selection, comments,
 * `testTag`), and loops and component instances break it by drawing one node many times. A path
 * names a box:
 *
 * - With no repetition above it, the path is the node id (`cell-0`), so existing keys are
 *   unchanged.
 * - Inside a copy, it carries the repeating node, the copy index and the way down — `cell#3`,
 *   `cell#3/label`, `row#2/cell#4/label` — starting at the outermost repeat above the box.
 *
 * Identity is the segments, not [value]'s spelling: node ids may contain `/` or `#`, so parsing a
 * joined string would recover the wrong id.
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
   * The path of a child drawn inside this one: its own id below no repeat, otherwise the full chain
   * of copies that distinguishes it.
   */
  fun child(childNodeId: String): UiBuilderInstancePath =
    if (isAuthored) of(childNodeId) else UiBuilderInstancePath(segments + Segment(childNodeId))

  /**
   * One copy of this node, by its index in the run, applied by whatever draws the run before its
   * children.
   */
  fun occurrence(index: Int): UiBuilderInstancePath =
    UiBuilderInstancePath(segments.dropLast(1) + segments.last().copy(occurrence = index))

  /**
   * The scope a component body is drawn in, opened by the placing node. No copy index is needed
   * (one draw per placement); opening the scope makes the same body under two instances two boxes.
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
