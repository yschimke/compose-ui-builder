package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonObject

/** Compatibility continuation used only while an upstream renderer still owns legacy adapters. */
class CanvasNodeFallbackScope
internal constructor(
  val entry: CanvasRenderNode,
  val prepared: PreparedCanvasNode,
  val state: Map<String, String?>,
  val renderChild: @Composable (CanvasRenderNode, Modifier) -> Unit,
) {
  val node: UiBuilderNode
    get() = entry.node

  val path: UiBuilderInstancePath
    get() = entry.path

  val adapterId: String
    get() = entry.adapterId

  fun slot(name: String): List<String> = node.slots[name].orEmpty()

  @Composable
  fun Child(id: String, modifier: Modifier = Modifier) {
    entry.child(id)?.let { renderChild(it, modifier) }
  }
}

/**
 * Render one resolved entry through a catalog registry and the SDK structural interpreter.
 *
 * This is the reusable recursive entry point for catalog runtimes. Resolution has already happened
 * in [CanvasDocumentHost]; this layer owns node preparation, descendant recursion, registry
 * dispatch and structural nodes. [fallback] exists only while the upstream compatibility table is
 * being retired. A catalog-only runtime supplies a diagnostic there instead of another interpreter.
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
    )
  ) {
    return
  }
  if (
    entry.renderStructure(
      modifier = prepared.modifier,
      renderChild = renderChild,
      missingComponent = missingComponent,
    )
  ) {
    return
  }
  fallback(CanvasNodeFallbackScope(entry, prepared, state, renderChild))
}
