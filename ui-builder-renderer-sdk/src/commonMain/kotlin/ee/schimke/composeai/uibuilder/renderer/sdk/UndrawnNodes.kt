package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.runtime.mutableIntStateOf
import kotlinx.serialization.Serializable

/**
 * A node a canvas did not draw as itself: it drew a diagnostic or a stand-in where the export, the
 * device preview or the native render draws the real thing. The surfaces of one design are trusted
 * only while they agree, so a canvas says so instead of leaving a red box for someone to notice.
 */
@Serializable
data class UiBuilderUndrawnNode(
  val nodeId: String,
  val componentId: String,
  /** [UNSUPPORTED] or [STAND_IN]. */
  val reason: String,
) {
  companion object {
    /** Nothing on this canvas knows the component: it drew an error. */
    const val UNSUPPORTED: String = "unsupported"

    /** The canvas knows the component but draws a labelled stand-in, not the component itself. */
    const val STAND_IN: String = "stand-in"
  }
}

/**
 * Every node the canvases in this process did not draw, per inspection generation
 * (`documentId@revision`, [UiBuilderInspectionGeneration.key]).
 *
 * Process-wide rather than per surface because the two readers are not the composable that drew the
 * node: a catalog runtime's protocol endpoint attaches the list to its `rendered` reply, and the
 * editor shows a note over the canvas. A component that one canvas cannot draw in a generation is
 * one no canvas of that renderer can draw, so the surfaces of a generation share one list.
 */
object UiBuilderUndrawnNodes {
  private val byGeneration = LinkedHashMap<String, MutableMap<String, UiBuilderUndrawnNode>>()

  private val changes = mutableIntStateOf(0)

  /**
   * Told a generation's whole list whenever it changes, e.g. so a browser page can publish it for a
   * harness or an agent to read (`globalThis.__uiBuilderUndrawn`).
   */
  var listener: ((key: String, nodes: List<UiBuilderUndrawnNode>) -> Unit)? = null

  /** Read in composition to recompose when a list changes. */
  val version: Int
    get() = changes.intValue

  fun forGeneration(key: String): List<UiBuilderUndrawnNode> =
    byGeneration[key]?.values?.sortedBy { it.nodeId }.orEmpty()

  /** Record what a canvas did not draw. Idempotent, so recomposition does not churn [version]. */
  fun record(key: String, node: UiBuilderUndrawnNode) {
    val nodes = byGeneration.getOrPut(key) { mutableMapOf() }
    if (nodes[node.nodeId] == node) return
    nodes[node.nodeId] = node
    changed(key)
  }

  /** The node is drawn as itself again, or has left the composition: stop reporting it. */
  fun forget(key: String, nodeId: String) {
    val nodes = byGeneration[key] ?: return
    if (nodes.remove(nodeId) == null) return
    changed(key)
  }

  /**
   * A generation's whole list as another process reported it, e.g. a catalog runtime's reply, which
   * always sends everything it did not draw: what it no longer lists is drawn again.
   */
  fun replace(key: String, nodes: List<UiBuilderUndrawnNode>) {
    val next = nodes.associateByTo(mutableMapOf()) { it.nodeId }
    if (byGeneration[key] == next) return
    byGeneration[key] = next
    changed(key)
  }

  private fun changed(key: String) {
    while (byGeneration.size > MAX_GENERATIONS) byGeneration.remove(byGeneration.keys.first())
    changes.intValue++
    listener?.invoke(key, forGeneration(key))
  }

  /** For tests. */
  fun clear() {
    byGeneration.clear()
    changes.intValue++
  }

  /** Enough for an editor's open designs and their thumbnails; older generations are dropped. */
  private const val MAX_GENERATIONS = 64
}

/** The generation key of [UiBuilderInspectionGeneration.key] for a document. */
fun undrawnGenerationKey(documentId: String, revision: Int): String = "$documentId@$revision"
