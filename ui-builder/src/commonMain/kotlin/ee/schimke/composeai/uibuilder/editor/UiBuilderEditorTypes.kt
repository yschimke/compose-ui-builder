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
  /**
   * What the design is for, as a person picks it first in the New design form: the catalog's
   * `newDesign.device` word (`phone`, `watch`, `widget`, `glasses`, `smart-home`, `agent`, or its
   * own). Null leaves it to [newDesignDevice]'s fallback.
   */
  val device: String? = null,
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
 * The outcome of asking the host to start a real Wear OS watch-face runtime session for the current
 * design (the `android-dwf` native backend): the tab to open, or the host's own reason it cannot.
 */
data class UiBuilderWatchFaceStart(
  /** Same-origin path of the watch-face tab that shows the session, or null on [failure]. */
  val pagePath: String? = null,
  val failure: String? = null,
  /**
   * The session's frames, drawn inline in the native pane, or null where the host cannot open a
   * socket (the tab link is then the only way to see it). The pane closes it.
   */
  val stream: UiBuilderNativeStream? = null,
  /**
   * Sends one `controls` patch to the session: the JSON of its `controls` object, for example
   * `{"ambient":true}`. Null with no [stream].
   */
  val sendControls: ((String) -> Unit)? = null,
  /**
   * Where an asynchronous start stood when the host answered (`202`), before the socket reports
   * anything. Null from a host that starts synchronously, which reports no stages at all.
   */
  val stage: UiBuilderWatchFaceStage? = null,
  /**
   * Frees the session's runtime slot at once (`DELETE …/dwf-preview/{sessionId}`), rather than when
   * the host notices the socket has gone. Null where the host gave no session id.
   */
  val release: (() -> Unit)? = null,
  /**
   * The host has native preview switched off ([WATCH_FACE_NATIVE_PREVIEW_DISABLED]): the pane shows
   * that instead of a failure, and offers no reload.
   */
  val disabled: Boolean = false,
) {
  /** Closes the stream and releases the session; what a Reload and a closed pane do. */
  fun end() {
    stream?.close()
    release?.invoke()
  }
}

/**
 * The code a host answers with when native preview is switched off on it: in `dwf-preview/status`,
 * and as a start's `503`. A pane status that starts with it puts the pane in its turned-off state,
 * which never starts a session.
 */
const val WATCH_FACE_NATIVE_PREVIEW_DISABLED: String = "native_preview_disabled"

/** Whether a status reason (`CODE: reason`) says native preview is switched off. */
fun watchFaceNativePreviewDisabled(reason: String?): Boolean =
  reason != null &&
    reason.substringBefore(':').trim().equals(WATCH_FACE_NATIVE_PREVIEW_DISABLED, ignoreCase = true)

/**
 * One loading stage of a watch-face runtime session (`compose-preview/ui-builder-dwf-preview-
 * session/v1`), from the start's `202`, the status route or a `{"type":"stage"}` socket message.
 *
 * [stage] is one of [QUEUED], [EXPORTING], [PACKAGING], [STARTING_RUNTIME], [INSTALLING],
 * [LOADING_FACE], [LIVE] or [FAILED]. [label] is the host's sentence for it, and on a failure its
 * diagnostic; [reason] is the packager's own text, shown verbatim.
 */
data class UiBuilderWatchFaceStage(
  val stage: String,
  /** 0 while queued, 1–5 while working, [steps] once live; on [FAILED], the step it failed at. */
  val step: Int = 0,
  val steps: Int = DEFAULT_STEPS,
  val label: String = "",
  /** Since the start request, as the host measured it when it sent this. */
  val elapsedMillis: Long = 0,
  val stageElapsedMillis: Long = 0,
  /** The package came from the host's digest cache, so the packager did not run. */
  val cachedPackage: Boolean = false,
  /** Sessions ahead of this one, only while [QUEUED]. */
  val queueAhead: Int? = null,
  val code: String? = null,
  val reason: String? = null,
  /** How often a live session redraws. */
  val frameIntervalMillis: Long? = null,
) {
  val queued: Boolean
    get() = stage == QUEUED

  val live: Boolean
    get() = stage == LIVE

  val failed: Boolean
    get() = stage == FAILED

  /** Whether the host will report no further stage. */
  val settled: Boolean
    get() = live || failed

  companion object {
    const val QUEUED: String = "queued"
    const val EXPORTING: String = "exporting"
    const val PACKAGING: String = "packaging"
    const val STARTING_RUNTIME: String = "starting-runtime"
    const val INSTALLING: String = "installing"
    const val LOADING_FACE: String = "loading-face"
    const val LIVE: String = "live"
    const val FAILED: String = "failed"
    const val DEFAULT_STEPS: Int = 5

    /**
     * The stage [message] states, or null when it states none: an older host's start, or a socket
     * message that is not about stages. `stage` may be the stage's name with its fields beside it,
     * or an object holding them.
     */
    fun of(message: kotlinx.serialization.json.JsonObject): UiBuilderWatchFaceStage? {
      val nested = message["stage"] as? kotlinx.serialization.json.JsonObject
      val fields = nested ?: message
      val name = fields.text("stage") ?: return null
      return UiBuilderWatchFaceStage(
        stage = name,
        step = fields.number("step")?.toInt() ?: 0,
        steps = fields.number("steps")?.toInt()?.takeIf { it > 0 } ?: DEFAULT_STEPS,
        label = fields.text("label").orEmpty(),
        elapsedMillis = fields.number("elapsedMillis")?.toLong() ?: 0,
        stageElapsedMillis = fields.number("stageElapsedMillis")?.toLong() ?: 0,
        cachedPackage = fields.text("cachedPackage") == "true",
        queueAhead = fields.number("queueAhead")?.toInt(),
        code = fields.text("code"),
        reason = fields.text("reason"),
        frameIntervalMillis = fields.number("frameIntervalMillis")?.toLong(),
      )
    }

    /** [of] for a JSON body; null when it is not an object or states no stage. */
    fun of(json: String): UiBuilderWatchFaceStage? = runCatching {
      kotlinx.serialization.json.Json.parseToJsonElement(json)
        as? kotlinx.serialization.json.JsonObject
    }
      .getOrNull()
      ?.let(::of)

    private fun kotlinx.serialization.json.JsonObject.text(name: String): String? =
      (get(name) as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        ?.content

    private fun kotlinx.serialization.json.JsonObject.number(name: String): Double? =
      text(name)?.toDoubleOrNull()
  }
}

/** A watch-face session's socket: its frames, and a state-based way to send it `controls`. */
class WatchFaceSocket(val stream: UiBuilderNativeStream, val sendControls: (String) -> Unit)

/**
 * The controls a watch-face session should have, as state rather than a stream of messages. Each
 * patch is merged into the desired state and the whole state is sent when the socket is open; one
 * that is not open yet (the handshake, a reconnect) keeps it, so anything set meanwhile is
 * coalesced to its latest value and the full state goes out again on every [opened].
 *
 * [transmit] returns whether the text was sent.
 */
class WatchFaceDesiredControls(private val transmit: (String) -> Boolean) {
  private var desired: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()

  /** Merges a `controls` patch (a JSON object) and sends the state if the socket is open. */
  fun set(patch: String) {
    val parsed =
      runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(patch)
          as? kotlinx.serialization.json.JsonObject
      }
        .getOrNull() ?: return
    desired = desired + parsed
    flush()
  }

  /** The socket opened (or reopened): the full desired state goes out. */
  fun opened() = flush()

  private fun flush() {
    if (desired.isEmpty()) return
    transmit(
      "{\"type\":\"controls\",\"controls\":${kotlinx.serialization.json.JsonObject(desired)}}",
    )
  }
}

/**
 * The start result for a session the host started: the tab to open, and, when [openSocket] can open
 * the session's socket, its stream and a `controls` sender, so the pane draws it inline. A socket
 * that cannot be opened leaves the tab link as the only way to see it.
 */
fun watchFaceStartOf(
  pagePath: String,
  socketPath: String?,
  stage: UiBuilderWatchFaceStage? = null,
  release: (() -> Unit)? = null,
  openSocket: (String) -> WatchFaceSocket?,
): UiBuilderWatchFaceStart {
  val socket =
    socketPath?.takeIf { it.isNotBlank() }?.let { runCatching { openSocket(it) }.getOrNull() }
  return UiBuilderWatchFaceStart(
    pagePath = pagePath,
    stream = socket?.stream,
    sendControls = socket?.sendControls,
    stage = stage,
    release = release,
  )
}

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

  /**
   * The newest loading stage the session reported (`{"type":"stage"}`), or null from a host that
   * reports none.
   */
  val stage: UiBuilderWatchFaceStage?
    get() = null

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
