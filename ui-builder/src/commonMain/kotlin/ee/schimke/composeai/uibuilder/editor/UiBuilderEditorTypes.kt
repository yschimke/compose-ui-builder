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
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
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
  /** The exact starting document, rendered as a thumbnail in the chooser. */
  val previewDocument: UiBuilderDocument? = null,
  /**
   * Chooser heading when a catalog offers more than one kind of thing; null lists it under none.
   */
  val group: String? = null,
)

data class UiBuilderNewDesignCatalog(
  val systemId: String,
  val label: String,
  val templates: List<UiBuilderNewDesignTemplate>,
  /** Which kind of screen it authors; the chooser orders and groups catalogs by it. */
  val platform: UiBuilderCatalogPlatform = UiBuilderCatalogPlatform.MOBILE,
  val previewCatalog: CapabilityCatalog? = null,
  /**
   * The type card's second line, from the catalog's `newDesign.description`. Declaring it makes the
   * card the catalog's own: [label] over this, instead of the built-in words for the catalogs this
   * builder has always named.
   */
  val description: String? = null,
  /**
   * Whether the card sits in the type picker's row (true) or behind "Other design types…" (false),
   * from `newDesign.primary`; null leaves it to [isPrimary]'s default.
   */
  val primary: Boolean? = null,
)

/**
 * One host-side native Compose render of the current design.
 *
 * An [ImageBitmap] because decoding differs between `wasmJs` and the JVM and is the host's job.
 * [refusals] means the generator cannot express the design (fix the design); [failure] means the
 * transport failed (try again).
 */
data class UiBuilderNativeRender(
  val image: ImageBitmap? = null,
  val refusals: List<String> = emptyList(),
  val failure: String? = null,
  /**
   * Design node id → the box it drew, in [image]'s pixels. Nodes without a box are not selectable.
   */
  val nodeBounds: Map<String, UiBuilderNativeNodeBounds> = emptyMap(),
  /** The live session behind this render, or null when the pane can only draw the still [image]. */
  val live: UiBuilderNativeLive? = null,
)

/**
 * Where a native render's live session is. Opaque to the editor: only the host knows how to reach
 * it.
 */
data class UiBuilderNativeLive(val sessionId: String, val previewId: String)

/**
 * One decoded frame off a live native session; its pixels are the [UiBuilderNativeInput] coordinate
 * space.
 */
data class UiBuilderNativeFrame(val image: ImageBitmap, val sequence: Long = 0)

/**
 * One user input for a live native composition. [kind] uses the daemon's wire spellings (`click`,
 * `pointerDown`, `pointerMove`, `pointerUp`, `scroll`); coordinates are frame pixels.
 */
data class UiBuilderNativeInput(
  val kind: String,
  val pixelX: Int,
  val pixelY: Int,
  val pointerId: Int = 0,
  val scrollDeltaY: Float? = null,
)

/**
 * A live native session opened by the host. Frames are exposed as state so the pane just draws the
 * newest one.
 */
interface UiBuilderNativeStream {
  /** The newest frame, or null until the first one lands. */
  val frame: UiBuilderNativeFrame?

  /** Why there is no frame, or null while the stream is healthy or still connecting. */
  val failure: String?

  /** Dispatch one input; dropped silently while the socket is not open. */
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
   * True for a device preview pane (a press presses the design). False on the editing canvas, where
   * the editor hit-tests clicks to select and forwards list wheels as [scroll].
   */
  val interactive: Boolean = true,
  /**
   * Node to reveal via a `revealNode` action when it changes; only sent to runtimes that announce
   * that capability.
   */
  val revealNodeId: String? = null,
  /**
   * The latest list wheel forwarded to the runtime; sent once per [UiBuilderCanvasScroll.sequence].
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
   * Capabilities the runtime announced beyond the base protocol (`revealNode`, `horizontalUnroll`);
   * empty for older runtimes.
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

// Deliberately not a composition local: the pinned renderer is a sandboxed iframe with its own Wasm
// runtime, and a local let every design-drawing surface start one. Only the editing canvas gets it.
