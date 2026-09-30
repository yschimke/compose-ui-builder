package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasInspection
import ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasRenderer
import ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasAdapterRegistry
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasDocumentHost
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasMode
import ee.schimke.composeai.uibuilder.renderer.sdk.RenderCanvasNode
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.applyCanvasModifier
import ee.schimke.composeai.uibuilder.renderer.sdk.canvasAdapterRegistry

/**
 * A catalog runtime run in this process, for driving the editor's runtime path on the desktop.
 *
 * [FakeRuntime][CanvasRuntimeDeviceViewTest] reports a box per container and draws nothing, which
 * pins what the editor ASKS of a runtime. This one answers the way a delivered runtime does: it
 * composes the document through the SDK's own [CanvasDocumentHost] and [RenderCanvasNode], inside a
 * root sized exactly to the surface it is handed (`requiredSize(widthDp, heightDp)`, as the
 * catalogs' `Main.kt` do), and reports the inspection the SDK measured. Only the sandbox is
 * missing: the runtime's own coordinates are this window's, so the renderer-space snapshot is the
 * editor's translated back to the surface's origin, which is what the iframe would have measured.
 */
internal class InProcessCatalogRuntime(
  private val registry: CanvasAdapterRegistry = foundationAdapters,
  private val capabilities: Set<String> = emptySet(),
) {
  /** Every surface asked for, in order, with the document it was asked to draw. */
  val drawn = mutableListOf<Pair<UiBuilderDocument, UiBuilderCanvasSurface>>()

  /** The last renderer-space inspection of the surface drawing these roots. */
  private val inspections = mutableMapOf<List<String>, UiBuilderInspectionSnapshot>()

  fun surfaceFor(roots: List<String>): UiBuilderCanvasSurface? =
    drawn.lastOrNull { it.first.roots == roots }?.second

  fun inspectionFor(roots: List<String>): UiBuilderInspectionSnapshot? = inspections[roots]

  val renderer: UiBuilderCanvasRenderer = { document, surface, _, _, _, onInspection ->
    drawn += document to surface
    // An editor that keeps asking for a different surface never settles, and a test waiting for it
    // to go idle would wait forever. Fail with the lengths it was flipping between instead.
    check(drawn.size < MAX_SURFACES) {
      "the editor asked for $MAX_SURFACES surfaces without settling; the last heights were " +
        drawn.takeLast(6).map { it.second.heightDp }
    }
    val mode =
      if (surface.mode == UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED)
        CanvasMode.AuthoringUnrolled
      else CanvasMode.Device
    val density = Density(surface.density)
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Each node's box in the surface's own pixels, as the host measured it relative to itself —
    // what the sandboxed runtime reports, since its window IS the surface. Not derived from this
    // window's coordinates: a node that does not move is not measured again, and the editor's box
    // around the surface moves while the surface grows.
    val local = remember { mutableMapOf<String, Rect>() }
    val state = remember { PublishState() }
    fun publish() {
      val measured = state.snapshot ?: return
      val surfaceRoot = root?.takeIf { it.isAttached } ?: return
      val native =
        measured.copy(
          nodes = measured.nodes.map { it.copy(bounds = local[it.nodeId]?.toPixelBounds()) }
        )
      // Mapped into the editor through the surface's current placement, as the web bridge maps a
      // runtime's snapshot whenever the surface moves.
      val origin = surfaceRoot.positionInRoot()
      val editor =
        native.copy(
          nodes =
            native.nodes.map { node ->
              node.copy(
                bounds = node.bounds?.let { it.copy(x = it.x + origin.x, y = it.y + origin.y) }
              )
            },
          slots = measured.slots,
        )
      inspections[document.roots] = native
      // Forwarded only when a box moved, as the web bridge forwards only a changed inspection: the
      // editor recomposes on every one it is handed, and every publish carries a new generation.
      val key = native.nodes to editor.nodes
      if (state.forwarded == key) return
      state.forwarded = key
      onInspection(UiBuilderCanvasInspection(native, editor, capabilities))
    }
    CompositionLocalProvider(LocalDensity provides density) {
      // Anchored top-left, as the iframe is: `requiredSize` alone centres a surface that is
      // briefly larger than the editor's box around it.
      Box(
        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
          .requiredSize(surface.widthDp.dp, surface.heightDp.dp)
          .onGloballyPositioned {
            root = it
            publish()
          }
      ) {
        CanvasDocumentHost(
          document = document,
          adapterIds = emptyMap(),
          adapterMappings = emptyMap(),
          mode = mode,
          density = density,
          modifier = Modifier.fillMaxSize(),
          onInspectionSnapshot = { snapshot ->
            state.snapshot = snapshot
            publish()
          },
          onOverlayBounds = { path, rect ->
            local[path.nodeId] = rect
            publish()
          },
          onOverlayBoundsForgotten = { path ->
            local.remove(path.nodeId)
            publish()
          },
        ) { entry, rootModifier ->
          RenderCanvasNode(
            entry = entry,
            registry = registry,
            modifier = rootModifier,
            applyModifier = { current, value ->
              current.applyCanvasModifier(
                value = value,
                mode = mode,
                resolveColor = { Color.Unspecified },
                resolveShape = { RectangleShape },
                unrolledHorizontally = unrolledHorizontally,
              )
            },
            missingComponent = { label, next -> BasicText("missing $label", next) },
          ) {
            BasicText("unsupported ${node.componentId}", prepared.modifier)
          }
        }
      }
    }
  }

  companion object {
    private const val MAX_SURFACES = 200

    /** Foundation only: a column and a text, which is all a scrolling column needs. */
    val foundationAdapters = canvasAdapterRegistry {
      register("layout/column") { Column(modifier) { Slot("children") } }
      register("m3/text") { BasicText(string("text"), modifier) }
    }
  }
}

private class PublishState {
  var snapshot: UiBuilderInspectionSnapshot? = null
  var forwarded: Any? = null
}

private fun Rect.toPixelBounds() = UiBuilderPixelBounds(left, top, width, height)
