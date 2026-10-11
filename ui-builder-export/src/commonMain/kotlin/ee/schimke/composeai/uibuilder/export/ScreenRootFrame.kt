package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.BooleanValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1

/**
 * A design rooted on the catalog's `screen-root` builtin, exported through its `screen-root` frame
 * (compose-ai-tools' `TYPED_STRUCTURAL_TEMPLATES.md`): the builtin is not a record call but the
 * frame's text, so its node is removed and its `content` child becomes the generated root.
 *
 * The frame has one hole, `${content}`, so anything else the root node says is refused by name
 * rather than dropped: another slot's children (`edgeButton`), a modifier, a binding, or a property
 * the frame does not already write. Two properties are what every frame writes or leaves outside
 * the screen: `scrollIndicator = true` (the frame's own indicator) and `timeText`, which belongs to
 * the app's scaffold around the screen function, as the hand-written Wear export puts it only in
 * its previews.
 */
object ScreenRootFrame {
  /** The root builtin's slot the frame's `${content}` hole holds. */
  const val CONTENT: String = "content"

  /** [document] with its screen-root node unwrapped, or the reasons it cannot be. */
  sealed interface Unwrapped {
    data class Document(val document: DesignDocumentV1) : Unwrapped

    data class Refused(val reasons: List<String>) : Unwrapped
  }

  /**
   * [document] as the frame's body when [structure] has a frame and a screen-root builtin and the
   * design is rooted on it; otherwise [document] unchanged.
   */
  fun unwrap(document: DesignDocumentV1, structure: CatalogScreenStructure?): Unwrapped {
    val component = structure?.rootComponent
    if (structure?.frame == null || component == null) return Unwrapped.Document(document)
    val rootId = document.roots.singleOrNull()
    val root = rootId?.let { document.nodes[it] }
    if (root == null || root.componentId != component) {
      return if (document.nodes.values.none { it.componentId == component })
        Unwrapped.Document(document)
      else
        Unwrapped.Refused(
          listOf("`$component` is written by the screen-root frame, so it must be the only root")
        )
    }
    val where = "`${root.id}` (`$component`)"
    val reasons = buildList {
      for ((name, value) in root.properties) {
        val framed =
          when (name) {
            "timeText" -> value is StringValueV1
            "scrollIndicator" -> (value as? BooleanValueV1)?.value == true
            else -> false
          }
        if (!framed) add("$where.`$name` is not written by the screen-root frame")
      }
      for ((slot, children) in root.slots) {
        if (slot != CONTENT && children.isNotEmpty()) {
          add("$where's `$slot` slot is not written by the screen-root frame")
        }
      }
      if (root.modifiers.isNotEmpty()) add("$where's modifiers are not written by the frame")
      if (root.eventBindings.values.any { it.isNotEmpty() }) {
        add("$where's event bindings are not written by the screen-root frame")
      }
      if (root.predicate != null || root.accessibility != null) {
        add("$where's predicate or accessibility is not written by the screen-root frame")
      }
      if (root.assetBindings.isNotEmpty() || root.tokenBindings.isNotEmpty()) {
        add("$where's bindings are not written by the screen-root frame")
      }
      when (root.slots[CONTENT].orEmpty().size) {
        1 -> Unit
        0 -> add("$where has no content for the screen-root frame to hold")
        else -> add("$where's `$CONTENT` holds more than the frame's one child")
      }
    }
    if (reasons.isNotEmpty()) return Unwrapped.Refused(reasons)
    val content = root.slots.getValue(CONTENT).single()
    return Unwrapped.Document(
      document.copy(roots = listOf(content), nodes = document.nodes - root.id)
    )
  }
}
