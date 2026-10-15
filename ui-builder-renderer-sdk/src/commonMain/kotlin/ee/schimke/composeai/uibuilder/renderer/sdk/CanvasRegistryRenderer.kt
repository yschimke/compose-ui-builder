package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import ee.schimke.composeai.uibuilder.export.UiBuilderInstancePath
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlinx.serialization.json.JsonObject

/** Compatibility continuation used only while an upstream renderer still owns legacy adapters. */
class CanvasNodeFallbackScope
internal constructor(
  val entry: CanvasRenderNode,
  val prepared: PreparedCanvasNode,
  val state: Map<String, String?>,
  val renderChild: @Composable (CanvasRenderNode, Modifier) -> Unit,
  private val report: (nodeId: String, componentId: String, reason: String) -> Unit = { _, _, _ ->
  },
  private val forget: (nodeId: String) -> Unit = {},
) {
  val node: UiBuilderNode
    get() = entry.node

  val path: UiBuilderInstancePath
    get() = entry.path

  val adapterId: String
    get() = entry.adapterId

  /**
   * Say that this node is not drawn as itself: call it from the branch that draws a diagnostic
   * ([UiBuilderUndrawnNode.UNSUPPORTED]) or a labelled stand-in ([UiBuilderUndrawnNode.STAND_IN]).
   * The editor and the catalog runtime protocol report it, so a canvas that disagrees with the
   * export says so instead of relying on someone noticing a red box.
   */
  @Composable
  fun ReportUndrawn(reason: String = UiBuilderUndrawnNode.UNSUPPORTED) {
    val nodeId = node.id
    val componentId = node.componentId
    // Withdrawn when this branch leaves the composition (the node is drawn as itself again, or
    // gone), so the report is what is on screen now rather than everything that ever was.
    DisposableEffect(nodeId, componentId, reason) {
      report(nodeId, componentId, reason)
      onDispose { forget(nodeId) }
    }
  }

  fun slot(name: String): List<String> = node.slots[name].orEmpty()

  @Composable
  fun Child(id: String, modifier: Modifier = Modifier) {
    entry.child(id)?.let { renderChild(it, modifier) }
  }
}

/**
 * Render one resolved entry through a catalog registry and the SDK structural interpreter: node
 * preparation, recursion, dispatch and structural nodes. [fallback] exists only while the
 * compatibility table is retired; catalog-only runtimes supply a diagnostic.
 */
@Composable
fun CanvasDocumentScope.RenderCanvasNode(
  entry: CanvasRenderNode,
  registry: CanvasAdapterRegistry,
  modifier: Modifier = Modifier,
  onNavigate: (String) -> Unit = {},
  handlesClick: (UiBuilderNode) -> Boolean = { false },
  applyModifier: @Composable (Modifier, JsonObject) -> Modifier,
  missingComponent: @Composable (String, Modifier) -> Unit,
  fallback: @Composable CanvasNodeFallbackScope.() -> Unit,
) {
  // Unconditional, and the decision taken at disposal: an effect entered only in device mode would
  // leave the composition — and forget every box — the moment the canvas switched to the extent,
  // while the nodes themselves stayed put and never reported again.
  DisposableEffect(entry.path) { onDispose { forgetNodeBounds(entry.path) } }
  val prepared =
    entry.prepare(
      modifier = modifier,
      state = state,
      onState = ::setState,
      onNavigate = onNavigate,
      handlesClick = handlesClick(entry.node),
      applyModifier = applyModifier,
      onBounds = ::recordNodeBounds,
      onSemanticAction = ::registerSemanticAction,
    )
  val renderChild: @Composable (CanvasRenderNode, Modifier) -> Unit = { child, next ->
    RenderCanvasNode(
      entry = child,
      registry = registry,
      modifier = next,
      onNavigate = onNavigate,
      handlesClick = handlesClick,
      applyModifier = applyModifier,
      missingComponent = missingComponent,
      fallback = fallback,
    )
  }
  if (
    entry.renderAdapter(
      registry = registry,
      modifier = prepared.modifier,
      mode = mode,
      renderChild = renderChild,
      dispatchEvent = prepared::dispatch,
      updateState = ::setState,
      recordText = { result -> recordTextLayout(entry.path, result) },
      registerScrolling = { scrollBy, scrollToItem ->
        updateSemanticAction(entry.node.id) {
          it.copy(
            scrollBy = scrollBy ?: it.scrollBy,
            scrollToItem = scrollToItem ?: it.scrollToItem,
          )
        }
      },
      unrolledHorizontally = unrolledHorizontally,
    )
  ) {
    return
  }
  if (
    entry.renderStructure(
      modifier = prepared.modifier,
      renderChild = renderChild,
      // A placement whose component is gone draws a diagnostic, so it is undrawn here.
      missingComponent = { label, next ->
        val node = entry.node
        DisposableEffect(node.id, node.componentId) {
          recordUndrawn(node.id, node.componentId, UiBuilderUndrawnNode.UNSUPPORTED)
          onDispose { forgetUndrawn(node.id) }
        }
        missingComponent(label, next)
      },
    )
  ) {
    return
  }
  fallback(
    CanvasNodeFallbackScope(entry, prepared, state, renderChild, ::recordUndrawn, ::forgetUndrawn)
  )
}
