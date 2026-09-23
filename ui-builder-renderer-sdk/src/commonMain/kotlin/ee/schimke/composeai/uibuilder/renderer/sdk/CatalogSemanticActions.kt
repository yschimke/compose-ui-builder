package ee.schimke.composeai.uibuilder.renderer.sdk

data class UiBuilderSemanticActionEntry(
  val enabled: Boolean = true,
  val activate: (() -> Unit)? = null,
  val scrollBy: ((Float) -> Float)? = null,
)

/** Correlates renderer-owned semantic callbacks with protocol actions from the editor host. */
class UiBuilderSemanticActionController : CatalogRuntimeActionDispatcher {
  private var entries = emptyMap<String, UiBuilderSemanticActionEntry>()
  private var viewportBounds: UiBuilderPixelBounds? = null

  fun install(
    value: Map<String, UiBuilderSemanticActionEntry>,
    viewport: UiBuilderPixelBounds?,
  ) {
    entries = value
    viewportBounds = viewport
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
