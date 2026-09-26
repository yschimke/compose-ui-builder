@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot

/** See `UiBuilderEditor`'s `selectionRequest`. [serial] distinguishes two requests for one node. */
data class EditorSelectionRequest(val nodeId: String, val serial: Int)

data class UiBuilderNewDesignTemplate(
  val id: String,
  val label: String,
  val supportingText: String,
)

data class UiBuilderNewDesignCatalog(
  val systemId: String,
  val label: String,
  val templates: List<UiBuilderNewDesignTemplate>,
  /** Which kind of screen it authors; the chooser orders and groups catalogs by it. */
  val platform: UiBuilderCatalogPlatform = UiBuilderCatalogPlatform.MOBILE,
)

/**
 * One render of the current design by real Compose on the host, as the editor needs it.
 *
 * An [ImageBitmap] rather than the bytes the route returns: decoding is the host's job, because
 * `wasmJs` and the JVM decode differently and neither belongs in an editor. [refusals] is not an
 * error state — a design the generator cannot express has no native render and the reasons are the
 * actionable half, exactly as in the code pane. [failure] is the transport failing, which is a
 * different sentence: try again versus fix the design.
 */
data class UiBuilderNativeRender(
  val image: ImageBitmap? = null,
  val refusals: List<String> = emptyList(),
  val failure: String? = null,
  /**
   * Design node id → the box it drew, in [image]'s own pixels.
   *
   * What turns the frame from a picture into a surface: the selected node is outlined in it, and a
   * click resolves to the smallest box containing the point. A node the host reports no box for —
   * one the render never placed — is simply not selectable there, which is the same answer the
   * inspection snapshot gives for a lazy slot that never composed.
   */
  val nodeBounds: Map<String, UiBuilderNativeNodeBounds> = emptyMap(),
  /**
   * Where this render can be *watched* rather than looked at, or null when it cannot.
   *
   * The compile lane has always stood a live session up behind the still — the same daemon, the
   * same classes, the Android one on a catalog whose native backend is Android — and the editor has
   * always thrown the coordinates away. With this present the native pane streams that session and
   * forwards taps into it; without it the pane draws [image] and says it is a still.
   */
  val live: UiBuilderNativeLive? = null,
)

/**
 * Where a native render's live session is, as the host reported it.
 *
 * Opaque to the editor on purpose: it is the host that knows how to reach a session (which origin,
 * which token, which socket), and the editor's business is only to hand this back to
 * [UiBuilderEditor]'s stream seam and draw what comes out.
 */
data class UiBuilderNativeLive(val sessionId: String, val previewId: String)

/**
 * One frame off a live native session, already decoded by the host.
 *
 * [image]'s own pixels are the coordinate space every [UiBuilderNativeInput] is stated in, which is
 * why the frame carries the picture and nothing else: the pane scales it to fit and inverts that
 * one factor to place a tap, exactly as it already does for a still render's node boxes.
 */
data class UiBuilderNativeFrame(val image: ImageBitmap, val sequence: Long = 0)

/**
 * One user input to dispatch into a live native composition.
 *
 * The wire spellings are the daemon's (`click`, `pointerDown`, `pointerMove`, `pointerUp`,
 * `scroll`), named here rather than enumerated because this type crosses into a host that speaks
 * that protocol already and an editor that invents no vocabulary of its own. Coordinates are in the
 * frame's own pixels — see [UiBuilderNativeFrame].
 */
data class UiBuilderNativeInput(
  val kind: String,
  val pixelX: Int,
  val pixelY: Int,
  val pointerId: Int = 0,
  val scrollDeltaY: Float? = null,
)

/**
 * A live native session, opened by the host and driven by the native pane.
 *
 * Frames arrive as state rather than as a callback so the pane is an ordinary Compose reader of
 * them: the newest frame is the one to draw, an older one that arrives late is not, and a pane that
 * recomposes for another reason redraws what it already had rather than waiting for the next.
 */
interface UiBuilderNativeStream {
  /** The newest frame, or null until the first one lands. */
  val frame: UiBuilderNativeFrame?

  /** Why there is no frame, or null while the stream is healthy or still connecting. */
  val failure: String?

  /**
   * Dispatch one input. Dropped silently while the socket is not open, which is the honest no-op.
   */
  fun send(input: UiBuilderNativeInput)

  /** Stop streaming and release the session's seat. Idempotent. */
  fun close()
}

/** One node's rectangle on a native frame, in that frame's pixels, origin at its top-left. */
data class UiBuilderNativeNodeBounds(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
) {
  internal fun contains(px: Float, py: Float): Boolean =
    px >= x && py >= y && px < x + width && py < y + height

  internal val area: Long
    get() = width.toLong() * height.toLong()
}

/** Host-supplied isolated renderer for the editor's authoritative design surface. */
data class UiBuilderCanvasSurface(
  val widthDp: Float,
  val heightDp: Float,
  val density: Float,
  val mode: UiBuilderRendererSurfaceModeV2,
  val positionVersion: Int = 0,
  /**
   * Whether a [DEVICE][UiBuilderRendererSurfaceModeV2.DEVICE] surface takes the pointer itself.
   *
   * A device preview pane does: it is a live design, and a press there presses the design's button.
   * The editing canvas's device view does not. There a click selects, so the runtime is left under
   * the editor, which hit-tests the press against the runtime's inspection and hands a wheel over a
   * list to the runtime as a `scrollBy` — see [scroll].
   */
  val interactive: Boolean = true,
  /**
   * The node the runtime should scroll its lazy containers to, sent as a `revealNode` action each
   * time it changes. Only to a runtime whose `initialized` reply lists that capability: an older
   * one leaves its lists where they are.
   */
  val revealNodeId: String? = null,
  /**
   * The latest wheel over a list the editor handed on; sent once per
   * [UiBuilderCanvasScroll.sequence].
   */
  val scroll: UiBuilderCanvasScroll? = null,
)

/** A vertical scroll the editor asks one of the runtime's containers to take, in its pixels. */
data class UiBuilderCanvasScroll(val nodeId: String, val deltaY: Float, val sequence: Int)

/** One renderer inspection in its native pixels and in the editor root used for hit-testing. */
data class UiBuilderCanvasInspection(
  val renderer: UiBuilderInspectionSnapshot,
  val editor: UiBuilderInspectionSnapshot,
  /**
   * What the runtime announced beyond the base protocol — `revealNode`, `horizontalUnroll`. Empty
   * for a runtime that predates the list, which is how the editor knows to leave those out.
   */
  val capabilities: Set<String> = emptySet(),
)

typealias UiBuilderCanvasRenderer =
  @Composable
  (
    document: UiBuilderDocument,
    surface: UiBuilderCanvasSurface,
    selectedNodeId: String?,
    selectionEnabled: Boolean,
    onNodeSelected: (String) -> Unit,
    onInspectionSnapshot: (UiBuilderCanvasInspection) -> Unit,
  ) -> Unit

// Deliberately not a composition local. A catalog's pinned renderer is a sandboxed iframe booting
// its own Wasm runtime, and a local made it one `.current` away from every surface that draws a
// design: palette tiles, drag ghosts and device panes each started one. It is handed to the editing
// canvas as a parameter, and nothing else draws with it.
