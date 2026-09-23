package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderInstancePath
import ee.schimke.composeai.uibuilder.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * SDK-owned traversal and resolution for one document render.
 *
 * Catalog runtimes and the transitional compatibility renderer enter nodes through this tree so
 * cycle handling, instance paths, bindings, preview state and canvas mappings have one owner.
 */
class CanvasRenderTree(
  private val document: UiBuilderDocument,
  private val state: Map<String, String?>,
  private val adapterIds: Map<String, String>,
  private val adapterMappings: Map<String, CanvasAdapterMappingV1>,
) {
  /** Enter an authored root. Missing roots draw nothing rather than taking down the renderer. */
  fun root(nodeId: String): CanvasRenderNode? =
    resolve(
      nodeId = nodeId,
      path = UiBuilderInstancePath.of(nodeId),
      ancestors = emptySet(),
      arguments = JsonObject(emptyMap()),
    )

  private fun resolve(
    nodeId: String,
    path: UiBuilderInstancePath,
    ancestors: Set<String>,
    arguments: JsonObject,
  ): CanvasRenderNode? {
    val authored = document.nodes[nodeId] ?: return null
    if (nodeId in ancestors) return null
    val node =
      resolveCanvasNode(
        node = authored,
        arguments = arguments,
        state = state,
        mapping = adapterMappings[authored.componentId],
      )
    return CanvasRenderNode(
      tree = this,
      node = node,
      path = path,
      adapterId = adapterIds[node.componentId] ?: node.componentId,
      ancestors = ancestors,
      bindingArguments = arguments,
    )
  }

  private fun child(
    parent: CanvasRenderNode,
    nodeId: String,
    path: UiBuilderInstancePath,
    arguments: JsonObject,
  ): CanvasRenderNode? =
    resolve(
      nodeId = nodeId,
      path = path,
      ancestors = parent.ancestors + parent.node.id,
      arguments = arguments,
    )

  internal fun child(parent: CanvasRenderNode, nodeId: String): CanvasRenderNode? =
    child(parent, nodeId, parent.path.child(nodeId), parent.bindingArguments)

  internal fun occurrenceChild(
    parent: CanvasRenderNode,
    nodeId: String,
    index: Int,
    arguments: JsonObject,
  ): CanvasRenderNode? =
    child(parent, nodeId, parent.path.occurrence(index).child(nodeId), arguments)

  internal fun placementChild(
    parent: CanvasRenderNode,
    nodeId: String,
    arguments: JsonObject,
  ): CanvasRenderNode? = child(parent, nodeId, parent.path.placement().child(nodeId), arguments)

  internal fun componentRoot(node: UiBuilderNode): String? {
    val key = node.componentKey().takeIf(String::isNotEmpty) ?: return null
    val definition = document.components[key] as? JsonObject ?: return null
    val root = (definition["root"] as? JsonPrimitive)?.contentOrNull ?: return null
    return root.takeIf(document.nodes::containsKey)
  }
}

/** One resolved entry in a [CanvasRenderTree]. */
class CanvasRenderNode
internal constructor(
  private val tree: CanvasRenderTree,
  val node: UiBuilderNode,
  val path: UiBuilderInstancePath,
  val adapterId: String,
  internal val ancestors: Set<String>,
  val bindingArguments: JsonObject,
) {
  /** Resolve a directly referenced child using this node's argument scope. */
  fun child(nodeId: String): CanvasRenderNode? = tree.child(this, nodeId)

  /** Resolve the children authored in [name], dropping missing and cyclic references. */
  fun slot(name: String): List<CanvasRenderNode> = node.slots[name].orEmpty().mapNotNull(::child)

  /** Enter one copy of a repeated template with that row's binding dictionary. */
  fun occurrenceChild(
    nodeId: String,
    index: Int,
    arguments: JsonObject,
  ): CanvasRenderNode? = tree.occurrenceChild(this, nodeId, index, arguments)

  /** Enter the body of a placed design component with its resolved argument dictionary. */
  fun placementChild(nodeId: String, arguments: JsonObject): CanvasRenderNode? =
    tree.placementChild(this, nodeId, arguments)

  internal fun componentRoot(): String? = tree.componentRoot(node)
}

private fun UiBuilderNode.componentKey(): String =
  (component?.get("componentKey") as? JsonPrimitive)?.contentOrNull.orEmpty()
