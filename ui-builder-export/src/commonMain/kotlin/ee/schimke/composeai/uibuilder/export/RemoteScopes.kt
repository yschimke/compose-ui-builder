package ee.schimke.composeai.uibuilder.export

/**
 * The node that switches a subtree from Compose's vocabulary to Remote Compose's. Shared by the
 * catalog, canvas, Compose exporter (stops here), [RemoteContentEmitter] (starts here) and this
 * file.
 */
public const val REMOTE_COMPOSE_INLINE_COMPONENT_ID: String = "remote-compose/inline"

/**
 * The node that switches back: a named custom component (a `LAYOUT_CUSTOM` operation) whose
 * `content` slot holds host Compose content the registered renderer draws; `name` is the registry
 * key.
 */
public const val REMOTE_COMPOSE_CUSTOM_COMPONENT_ID: String = "remote-compose/custom"

/**
 * Which nodes of a design are written in the Remote Compose vocabulary, and which in Compose's.
 *
 * A document question, not a catalog one: `layout/column` is `Column` in a screen and
 * `RemoteColumn` inside a [REMOTE_COMPOSE_INLINE_COMPONENT_ID], so ancestry decides. Scope turns on
 * at a remote host (an inline node or a `remote-m3` widget container) and off inside a custom
 * component's `content` slot, so scopes nest:
 * ```
 * Scaffold → Column → Remote Compose → RemoteColumn → Custom("field") → BasicTextField
 * ```
 *
 * Every consumer reads scopes from here rather than deciding again.
 */
public class RemoteScopes private constructor(private val remoteNodeIds: Set<String>) {

  /** Whether [nodeId] is written in the Remote Compose vocabulary. */
  public fun isRemote(nodeId: String): Boolean = nodeId in remoteNodeIds

  /** Whether any node is. A design with none is every design this builder had before inline. */
  public val isEmpty: Boolean
    get() = remoteNodeIds.isEmpty()

  public companion object {
    /**
     * Components whose content is already Remote Compose without an inline node: the `remote-m3`
     * widget containers. Listed because consumers hold a document, not a catalog with its
     * `RemoteContentHost` trait.
     */
    public val REMOTE_HOST_COMPONENT_IDS: Set<String> =
      WEAR_WIDGET_CONTAINER_IDS + REMOTE_COMPOSE_INLINE_COMPONENT_ID

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
