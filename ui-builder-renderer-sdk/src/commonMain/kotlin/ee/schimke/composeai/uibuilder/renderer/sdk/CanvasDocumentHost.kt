package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderInstancePath
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/** Generic state, inspection and semantic-action services for one document composition. */
class CanvasDocumentScope
internal constructor(
  val state: Map<String, String?>,
  val mode: CanvasMode,
  private val updateState: (String, String?) -> Unit,
  private val recordBounds: (UiBuilderInstancePath, LayoutCoordinates) -> Unit,
  private val forgetBounds: (UiBuilderInstancePath) -> Unit,
  private val recordText: (UiBuilderInstancePath, TextLayoutResult) -> Unit,
  private val semanticActions: MutableMap<String, UiBuilderSemanticActionEntry>,
) {
  fun setState(name: String, value: String?) = updateState(name, value)

  fun recordNodeBounds(path: UiBuilderInstancePath, coordinates: LayoutCoordinates) =
    recordBounds(path, coordinates)

  /**
   * The node drawn at [path] has left the composition; see
   * [UiBuilderInspectionCollector.forgetNodeBounds].
   */
  fun forgetNodeBounds(path: UiBuilderInstancePath) = forgetBounds(path)

  fun recordTextLayout(path: UiBuilderInstancePath, result: TextLayoutResult) =
    recordText(path, result)

  fun registerSemanticAction(nodeId: String, action: UiBuilderSemanticActionEntry) {
    semanticActions[nodeId] = action
  }

  fun updateSemanticAction(
    nodeId: String,
    update: (UiBuilderSemanticActionEntry) -> UiBuilderSemanticActionEntry,
  ) {
    semanticActions[nodeId] = update(semanticActions[nodeId] ?: UiBuilderSemanticActionEntry())
  }
}

/**
 * Compose one document's resolved roots with generic state, inspection and action lifecycle.
 *
 * Theme, frame and component implementation remain outside this host. A catalog runtime supplies
 * those around [content]; an editor may separately draw selection/drop overlays from
 * [onOverlayBounds] without putting editor UI inside the catalog runtime.
 */
@Composable
fun CanvasDocumentHost(
  document: UiBuilderDocument,
  adapterIds: Map<String, String>,
  adapterMappings: Map<String, CanvasAdapterMappingV1>,
  mode: CanvasMode,
  density: Density,
  modifier: Modifier = Modifier,
  renderSessionId: String = "",
  runtimeActionController: UiBuilderSemanticActionController? = null,
  onInspectionSnapshot: ((UiBuilderInspectionSnapshot) -> Unit)? = null,
  onInspectionInvalidated: ((UiBuilderInspectionCollector) -> Unit)? = null,
  onStateSnapshot: ((Map<String, String?>) -> Unit)? = null,
  onOverlayBounds: (UiBuilderInstancePath, Rect) -> Unit = { _, _ -> },
  /**
   * A path [onOverlayBounds] reported has stopped being drawn; see
   * [CanvasDocumentScope.forgetNodeBounds].
   */
  onOverlayBoundsForgotten: (UiBuilderInstancePath) -> Unit = {},
  rootModifier: BoxScope.(CanvasRenderNode) -> Modifier = { Modifier },
  content: @Composable CanvasDocumentScope.(CanvasRenderNode, Modifier) -> Unit,
) {
  val semanticActions = mutableMapOf<String, UiBuilderSemanticActionEntry>()
  var surfaceCoordinates by
    remember(document.id, document.revision, renderSessionId) {
      mutableStateOf<LayoutCoordinates?>(null)
    }
  val currentInspectionCallback = rememberUpdatedState(onInspectionSnapshot)
  val currentInspectionInvalidated = rememberUpdatedState(onInspectionInvalidated)
  val inspection =
    remember(document.id, document.revision, renderSessionId, adapterIds) {
      UiBuilderInspectionCollector(
        document = document,
        onSnapshot = { snapshot -> currentInspectionCallback.value?.invoke(snapshot) },
        onInvalidated =
          onInspectionInvalidated?.let {
            { collector -> currentInspectionInvalidated.value?.invoke(collector) }
          },
        canvasAdapterIds = adapterIds,
      )
    }
  // Read through holders rather than captured, because a disposal is reported by whichever scope
  // the leaving item was composed under, and that can be a revision older than the collector now
  // answering: forgetting into a discarded collector would publish its stale snapshot over the
  // current one.
  val currentInspection = rememberUpdatedState(inspection)
  val currentMode = rememberUpdatedState(mode)
  val currentOverlayForgotten = rememberUpdatedState(onOverlayBoundsForgotten)
  // Which drawing of a node measured it last. A node repeated by a `for-each` reports under one id
  // from several paths, and one copy leaving must not take the box of a copy still on screen.
  val lastMeasuredPath = remember(renderSessionId) { mutableMapOf<String, UiBuilderInstancePath>() }
  // Forgotten a frame late, on purpose. A disposal is also what closing the whole surface looks
  // like, node by node, and a one-shot render reads the snapshot *after* its scene is closed:
  // forgetting there published an empty inspection over the one it had just measured. A surface
  // that is still alive recomposes the flush below; one that has gone never does.
  val pendingForgets = remember(renderSessionId) { mutableSetOf<UiBuilderInstancePath>() }
  val forgetRequests = remember(renderSessionId) { mutableIntStateOf(0) }
  val flushForgets = {
    val gone =
      pendingForgets
        .filter { lastMeasuredPath[it.nodeId] == it }
        .onEach { lastMeasuredPath.remove(it.nodeId) }
    pendingForgets.forEach { currentOverlayForgotten.value(it) }
    pendingForgets.clear()
    if (gone.isNotEmpty()) {
      currentInspection.value.forgetNodeBounds(*gone.map { it.nodeId }.toTypedArray())
    }
  }
  val state =
    remember(document.id) {
      mutableStateMapOf<String, String?>().also { target ->
        document.stateVariables.forEach { (name, declaration) ->
          target[name] =
            (declaration as? JsonObject)
              ?.get("initialValue")
              ?.takeUnless { it is JsonNull }
              ?.let { it as? JsonPrimitive }
              ?.contentOrNull
        }
      }
    }
  var appliedDeclarations by remember(document.id) { mutableStateOf(document.stateVariables) }
  SideEffect {
    if (appliedDeclarations != document.stateVariables) {
      reconcileCanvasState(state, appliedDeclarations, document.stateVariables)
      appliedDeclarations = document.stateVariables
    }
    inspection.updateState(state)
    // A hidden or background browser surface may not receive a layout frame immediately. Publish
    // the generation now; later bounds and text callbacks replace it with measured snapshots.
    inspection.publishSnapshot()
    onStateSnapshot?.invoke(state.toMap())
    val size = surfaceCoordinates?.size
    runtimeActionController?.install(
      semanticActions.toMap(),
      size?.let { UiBuilderPixelBounds(0f, 0f, it.width.toFloat(), it.height.toFloat()) },
    )
  }

  val renderTree =
    CanvasRenderTree(
      document = document,
      state = state,
      adapterIds = adapterIds,
      adapterMappings = adapterMappings,
    )
  val scope =
    CanvasDocumentScope(
      state = state,
      mode = mode,
      updateState = { name, value ->
        state[name] = value
        inspection.updateState(state)
        onStateSnapshot?.invoke(state.toMap())
      },
      recordBounds = { path, coordinates ->
        val unit = coordinates.localToRoot(Offset(1f, 1f)) - coordinates.localToRoot(Offset.Zero)
        val bounds =
          Rect(
            offset = coordinates.positionInRoot(),
            size =
              Size(
                coordinates.size.width * unit.x,
                coordinates.size.height * unit.y,
              ),
          )
        lastMeasuredPath[path.nodeId] = path
        // Measured again, so whatever left before this was a recycle rather than a departure.
        pendingForgets -= path
        inspection.recordNodeBounds(
          path.nodeId,
          bounds.left,
          bounds.top,
          bounds.right,
          bounds.bottom,
        )
        surfaceCoordinates?.let { surface ->
          onOverlayBounds(path, surface.overlayBoundsOf(coordinates))
        }
      },
      // Only on a device surface, where a lazy layout really does dispose what scrolls out. The
      // unrolled extent composes everything, and what leaves it there is left as it always was.
      forgetBounds = { path ->
        if (currentMode.value == CanvasMode.Device) {
          pendingForgets += path
          forgetRequests.intValue++
        }
      },
      recordText = { path, result ->
        inspection.recordTextLayout(
          path.nodeId,
          result.lineCount,
          result.firstBaseline,
          result.lastBaseline,
          with(density) {
            document.nodes.getValue(path.nodeId).textContentTopPaddingDp().dp.toPx()
          },
        )
      },
      semanticActions = semanticActions,
    )

  Box(
    modifier.fillMaxSize().onGloballyPositioned { coordinates ->
      surfaceCoordinates = coordinates
      runtimeActionController?.install(
        semanticActions.toMap(),
        UiBuilderPixelBounds(
          0f,
          0f,
          coordinates.size.width.toFloat(),
          coordinates.size.height.toFloat(),
        ),
      )
    }
  ) {
    FlushForgottenBounds(forgetRequests, flushForgets)
    document.roots.forEach { root ->
      renderTree.root(root)?.let { entry -> scope.content(entry, rootModifier(entry)) }
    }
  }
}

private fun UiBuilderNode.textContentTopPaddingDp(): Float =
  modifiers
    .sumOf { modifier ->
      val value = modifier as? JsonObject
      if ((value?.get("type") as? JsonPrimitive)?.contentOrNull == "padding") {
        ((value["topDp"] as? JsonPrimitive)?.floatOrNull ?: 0f).toDouble()
      } else {
        0.0
      }
    }
    .toFloat()

/**
 * [node]'s bounds in this surface's coordinates.
 *
 * A node drawn inside a `Dialog` or a popup is laid out in that layer's own hierarchy, not the
 * surface's, and `localBoundingBoxOf` refuses a pair with no common ancestor: it threw from the
 * positioning pass, so a design holding an `m3/dialog` took the whole canvas down with it. Both
 * hierarchies share the window, so such a node is mapped through window space instead.
 */
private fun LayoutCoordinates.overlayBoundsOf(node: LayoutCoordinates): Rect =
  if (node.findRootCoordinates() == findRootCoordinates()) {
    localBoundingBoxOf(node, clipBounds = false)
  } else {
    node.boundsInWindow().translate(-positionInWindow())
  }

/**
 * Applies the forgets queued since the last frame.
 *
 * Its own scope so that reading [requests] recomposes this and nothing else: the host above it
 * rebuilds every entry of the tree when it recomposes, and a list scrolled by a wheel disposes an
 * item nearly every frame.
 */
@Composable
private fun FlushForgottenBounds(requests: IntState, flush: () -> Unit) {
  if (requests.intValue > 0) SideEffect(flush)
}
