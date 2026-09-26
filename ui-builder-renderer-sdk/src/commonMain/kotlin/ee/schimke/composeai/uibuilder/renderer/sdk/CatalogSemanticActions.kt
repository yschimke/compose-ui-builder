package ee.schimke.composeai.uibuilder.renderer.sdk

data class UiBuilderSemanticActionEntry(
  val enabled: Boolean = true,
  val activate: (() -> Unit)? = null,
  val scrollBy: ((Float) -> Float)? = null,
  /**
   * Brings the item at an index of this lazy container into its viewport — the half of `revealNode`
   * a container answers. By index, because the item being revealed is usually one the container has
   * not composed, and so has no bounds or entry of its own to be found by.
   */
  val scrollToItem: ((Int) -> Unit)? = null,
)

/** Correlates renderer-owned semantic callbacks with protocol actions from the editor host. */
class UiBuilderSemanticActionController : CatalogRuntimeActionDispatcher {
  private var entries = emptyMap<String, UiBuilderSemanticActionEntry>()
  private var viewportBounds: UiBuilderPixelBounds? = null
  private var itemAncestry: (String) -> List<Pair<String, Int>> = { emptyList() }

  fun install(
    value: Map<String, UiBuilderSemanticActionEntry>,
    viewport: UiBuilderPixelBounds?,
    /**
     * Each container holding a node, outermost first, with the index of the child that leads to it
     * — what `revealNode` scrolls. Read from the document by the host that composed it.
     */
    ancestry: (String) -> List<Pair<String, Int>> = { emptyList() },
  ) {
    entries = value
    viewportBounds = viewport
    itemAncestry = ancestry
  }

  override fun dispatch(
    action: CatalogRuntimeAction,
    snapshot: UiBuilderInspectionSnapshot?,
  ): UiBuilderSemanticActionResult {
    if (
      snapshot == null ||
        snapshot.documentId != action.documentId ||
        snapshot.documentRevision != action.documentRevision
    ) {
      return UiBuilderSemanticActionResult.Rejected(
        "STALE_DOCUMENT",
        "action does not target the current inspection snapshot",
      )
    }
    val inspected =
      snapshot.nodes.singleOrNull { it.nodeId == action.nodeId }
        ?: return UiBuilderSemanticActionResult.Rejected(
          "UNKNOWN_NODE",
          "semantic node was not found",
        )
    val bounds = inspected.bounds
    val viewport = viewportBounds
    // Before the visibility gate, which is the whole point of it: the node to reveal is usually
    // one no container has composed, so it has no bounds at all.
    if (action.kind == REVEAL_NODE_ACTION) {
      if (bounds != null && viewport != null && bounds.within(viewport)) {
        return UiBuilderSemanticActionResult.Applied
      }
      // Outermost first, so an outer list brings a nested one into existence before it is asked.
      // A container nested inside an item that is not composed yet has no entry to answer, and is
      // left for the next reveal once the outer scroll has composed it.
      val scrollers =
        itemAncestry(action.nodeId).mapNotNull { (containerId, index) ->
          entries[containerId]?.scrollToItem?.let { it to index }
        }
      if (scrollers.isEmpty()) {
        return UiBuilderSemanticActionResult.Rejected(
          "ACTION_NOT_AVAILABLE",
          "no composed container holding this node can scroll to it",
        )
      }
      scrollers.forEach { (scrollToItem, index) -> scrollToItem(index) }
      return UiBuilderSemanticActionResult.Applied
    }
    if (bounds == null || viewport == null || !bounds.intersects(viewport)) {
      return UiBuilderSemanticActionResult.Rejected(
        "ACTION_NOT_VISIBLE",
        "semantic node is not measured inside the current Compose viewport",
      )
    }
    val entry =
      entries[action.nodeId]
        ?: return UiBuilderSemanticActionResult.Rejected(
          "ACTION_NOT_AVAILABLE",
          "semantic node does not expose this action in the current composition",
        )
    return when (action.kind) {
      "activate" -> {
        if (!entry.enabled || inspected.semantics.enabled == false) {
          UiBuilderSemanticActionResult.Rejected("ACTION_DISABLED", "semantic node is disabled")
        } else if ("click" !in inspected.semantics.actions || entry.activate == null) {
          UiBuilderSemanticActionResult.Rejected(
            "ACTION_NOT_AVAILABLE",
            "semantic node does not expose activate",
          )
        } else {
          entry.activate.invoke()
          UiBuilderSemanticActionResult.Applied
        }
      }
      "scrollBy" -> {
        val scrollBy =
          entry.scrollBy
            ?: return UiBuilderSemanticActionResult.Rejected(
              "ACTION_NOT_AVAILABLE",
              "semantic node does not expose vertical scrollBy",
            )
        scrollBy(requireNotNull(action.deltaY).toFloat())
        UiBuilderSemanticActionResult.Applied
      }
      else -> UiBuilderSemanticActionResult.Rejected("UNSUPPORTED_ACTION", "unsupported action")
    }
  }
}

private fun UiBuilderPixelBounds.intersects(other: UiBuilderPixelBounds): Boolean =
  width > 0f &&
    height > 0f &&
    x < other.right &&
    right > other.x &&
    y < other.bottom &&
    bottom > other.y

private fun UiBuilderPixelBounds.within(other: UiBuilderPixelBounds): Boolean =
  x >= other.x && y >= other.y && right <= other.right && bottom <= other.bottom

/**
 * Scroll whatever lazy containers hold a node until it is on screen: the editor's selection made
 * visible in a device frame. Carries only the node id — the renderer walks the document for the
 * containers and indices, so it works for an item no container has composed.
 */
const val REVEAL_NODE_ACTION = "revealNode"
