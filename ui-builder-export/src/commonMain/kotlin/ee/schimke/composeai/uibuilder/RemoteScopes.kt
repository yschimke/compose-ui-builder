package ee.schimke.composeai.uibuilder

/**
 * The node that switches a subtree from Compose's vocabulary to Remote Compose's.
 *
 * Named because five places have to agree on it: the catalog declares it, the canvas frames it, the
 * Compose exporter stops walking at it, [RemoteContentEmitter] starts writing at it, and this file
 * decides which nodes are on which side of it.
 */
public const val REMOTE_COMPOSE_INLINE_COMPONENT_ID: String = "remote-compose/inline"

/**
 * The node that switches back — a named custom component whose slot holds ordinary Compose content.
 *
 * A Remote Compose document cannot call an application's composables, so the way host content gets
 * inside one is a `LAYOUT_CUSTOM` operation naming a renderer the host registered. This component
 * is that operation: its `name` is the registry key, and its `content` slot is what the registered
 * renderer draws.
 */
public const val REMOTE_COMPOSE_CUSTOM_COMPONENT_ID: String = "remote-compose/custom"

/**
 * Which nodes of a design are written in the Remote Compose vocabulary, and which in Compose's.
 *
 * ## Why this is a document question rather than a catalog one
 *
 * Slot acceptance answers "may this component go in that slot", and it answers it from the two
 * components alone. That is enough for every other rule in this builder and not enough for this
 * one, because `layout/column` is authored on **both** sides of the boundary: a column inside a
 * mobile screen becomes `androidx.compose.foundation.layout.Column`, and the identical node inside
 * a [REMOTE_COMPOSE_INLINE_COMPONENT_ID] becomes `RemoteColumn`. The component does not change;
 * where it sits does. A trait cannot say that, so the ancestry does.
 *
 * ## The two switches
 *
 * Scope turns **on** at a remote host — an inline node, or a `remote-m3` widget container, whose
 * whole body is already `@RemoteComposable`. It turns **off** again inside a custom component's
 * `content` slot, which is host content by definition: the point of
 * [REMOTE_COMPOSE_CUSTOM_COMPONENT_ID] is that a document reserves a box and an application fills
 * it with its own Compose. So the two nest, and a design like
 *
 * ```
 * Scaffold → Column → Remote Compose → RemoteColumn → Custom("field") → BasicTextField
 * ```
 *
 * has three scopes in one tree, in that order. Every consumer that has to write, draw or refuse a
 * node reads them from here rather than deciding again.
 */
public class RemoteScopes private constructor(private val remoteNodeIds: Set<String>) {

  /** Whether [nodeId] is written in the Remote Compose vocabulary. */
  public fun isRemote(nodeId: String): Boolean = nodeId in remoteNodeIds

  /** Whether any node is. A design with none is every design this builder had before inline. */
  public val isEmpty: Boolean
    get() = remoteNodeIds.isEmpty()

  public companion object {
    /**
     * The components whose content is already Remote Compose without an inline node saying so.
     *
     * The two `remote-m3` widget containers: a widget body *is* a document, which is why
     * [WearWidgetCodeExporter] writes one and the Compose exporter refuses one. Listed rather than
     * read from the catalog's `RemoteContentHost` trait because this module has no catalog — the
     * emitter, the canvas and the export gate all reach it holding a document alone.
     */
    public val REMOTE_HOST_COMPONENT_IDS: Set<String> =
      WearWidgetScaffoldSize.entries.map { it.componentId }.toSet() +
        REMOTE_COMPOSE_INLINE_COMPONENT_ID

    /**
     * Resolve [document]'s scopes. A cycle is walked once and left to the export gate to report.
     */
    public fun of(
      document: UiBuilderDocument,
      hostComponentIds: Set<String> = REMOTE_HOST_COMPONENT_IDS,
    ): RemoteScopes {
      val remote = mutableSetOf<String>()
      val seen = mutableSetOf<String>()

      fun visit(nodeId: String, inRemote: Boolean) {
        if (!seen.add(nodeId)) return
        val node = document.nodes[nodeId] ?: return
        if (inRemote) remote += nodeId
        // A custom component is itself remote — the document carries its operation — while what
        // fills it is not. Every other slot inherits, including a host's own background brushes.
        val childScope =
          if (node.componentId in hostComponentIds) true
          else if (node.componentId == REMOTE_COMPOSE_CUSTOM_COMPONENT_ID) false else inRemote
        node.slots.values.flatten().forEach { visit(it, childScope) }
      }

      document.roots.forEach { visit(it, false) }
      return RemoteScopes(remote)
    }
  }
}
