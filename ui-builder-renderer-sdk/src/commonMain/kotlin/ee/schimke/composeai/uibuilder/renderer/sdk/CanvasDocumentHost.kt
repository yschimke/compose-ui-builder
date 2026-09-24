package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
  private val recordText: (UiBuilderInstancePath, TextLayoutResult) -> Unit,
  private val semanticActions: MutableMap<String, UiBuilderSemanticActionEntry>,
) {
  fun setState(name: String, value: String?) = updateState(name, value)

  fun recordNodeBounds(path: UiBuilderInstancePath, coordinates: LayoutCoordinates) =
    recordBounds(path, coordinates)

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
        inspection.recordNodeBounds(
          path.nodeId,
          bounds.left,
          bounds.top,
          bounds.right,
          bounds.bottom,
        )
        surfaceCoordinates?.let { surface ->
          onOverlayBounds(path, surface.localBoundingBoxOf(coordinates, clipBounds = false))
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
