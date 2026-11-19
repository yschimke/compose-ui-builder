@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toComposeImageBitmap
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeFrame
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeInput
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeLive
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeStream
import ee.schimke.composeai.uibuilder.editor.UiBuilderWatchFaceStage
import ee.schimke.composeai.uibuilder.editor.WatchFaceDesiredControls
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.skia.Image

/**
 * The editor's native pane, streamed off the serve host's existing live lane
 * (`/{session}/ws/{preview}`, the viewer's Live toggle socket): frames arrive as
 * `{"type":"frame","dataBase64":"…","codec":"…","seq":n}` and `{"type":"input", …}` carries pointer
 * events in frame pixels. See `ServeStreamProtocol` for the wire shapes.
 *
 * Lives in `wasmJsMain` because the common editor has no `WebSocket` or base64; it only sees
 * [UiBuilderNativeStream] (a frame, a failure and a `send`).
 *
 * Reports page visibility on open and on change (`{"type":"visibility"}`), so the lane throttles a
 * hidden pane instead of tearing it down, and repaints from a keyframe on return.
 */
internal class BrowserNativeStream(url: String) : UiBuilderNativeStream {

  constructor(live: UiBuilderNativeLive) : this(nativeStreamUrl(live.sessionId, live.previewId))

  override var frame: UiBuilderNativeFrame? by mutableStateOf(null)
    private set

  override var failure: String? by mutableStateOf(null)
    private set

  /** The newest `{"type":"stage"}` a watch-face session sent; null from a lane that sends none. */
  override var stage: UiBuilderWatchFaceStage? by mutableStateOf(null)
    private set

  /**
   * The newest sequence painted, so out-of-order frames are dropped. `seq` restarts on reconnect,
   * but each session gets a new instance.
   */
  private var painted = -1L

  private var closed = false

  private val socket: JsAny? =
    try {
      openNativeStreamSocket(
        url = url,
        onTextMessage = ::onMessage,
        // Every open, a reconnect included: the whole desired state goes out.
        onOpened = { controls.opened() },
        onClosed = {
          // Only a close before the first frame is a failure; afterwards the last frame stays.
          // A failed stage already says why, in the host's words.
          if (!closed && frame == null && failure == null && stage?.failed != true) {
            failure = "the live native session closed before it drew a frame"
          }
        },
      )
    } catch (_: Throwable) {
      // A same-origin violation or a browser with the socket blocked. The still is still drawn, so
      // this is a sentence rather than an empty pane.
      failure = "this browser could not open the live native session"
      null
    }

  override fun send(input: UiBuilderNativeInput) {
    val open = socket ?: return
    if (closed) return
    sendNativeStreamText(
      open,
      buildString {
        append("{\"type\":\"input\",\"kind\":\"").append(input.kind).append('"')
        append(",\"pixelX\":").append(input.pixelX)
        append(",\"pixelY\":").append(input.pixelY)
        append(",\"pointerId\":").append(input.pointerId)
        input.scrollDeltaY?.let { append(",\"scrollDeltaY\":").append(it) }
        // Absent means touch on the daemon's side, and a design being poked in a browser with a
        // mouse is a mouse: it is what decides whether a hover state is plausible.
        append(",\"pointerType\":\"mouse\"}")
      },
    )
  }

  /**
   * The controls a session should have, sent once the socket is open and again on every open, so a
   * switch flipped during the handshake is not lost.
   */
  private val controls = WatchFaceDesiredControls { text ->
    val open = socket
    open != null && !closed && sendNativeStreamText(open, text)
  }

  /** Merges a `controls` patch into the desired state; see [WatchFaceDesiredControls]. */
  fun sendControls(patch: String) = controls.set(patch)

  override fun close() {
    if (closed) return
    closed = true
    socket?.let { closeNativeStreamSocket(it) }
  }

  private fun onMessage(text: String) {
    if (closed) return
    val message =
      try {
        json.parseToJsonElement(text) as? JsonObject ?: return
      } catch (_: Throwable) {
        return
      }
    when (message["type"]?.jsonPrimitive?.contentOrNull) {
      "frame" -> {
        val sequence = message["seq"]?.jsonPrimitive?.longOrNull ?: (painted + 1)
        if (sequence < painted) return
        // `dataBase64`, as `ServeStreamProtocol.frameMessage` writes it (not the still render's
        // `image`).
        val encoded = message["dataBase64"]?.jsonPrimitive?.contentOrNull ?: return
        val decoded = decode(encoded) ?: return
        painted = sequence
        failure = null
        frame = UiBuilderNativeFrame(image = decoded, sequence = sequence)
      }
      // A watch-face session's loading stages. The pane counts the elapsed time on between them.
      "stage" -> UiBuilderWatchFaceStage.of(message)?.let { stage = it }
      // Reported rather than thrown: the lane sends these for a bad override or a backend that
      // cannot stream, and the pane has a still to fall back on either way.
      "error" ->
        failure = message["message"]?.jsonPrimitive?.contentOrNull ?: "the live native lane failed"
    }
  }

  /**
   * The frame's bytes as an image, or null when they won't decode. Accepts bare base64 or a `data:`
   * URI, as the still render does. A bad frame is dropped (the next one repaints).
   */
  @OptIn(ExperimentalEncodingApi::class)
  private fun decode(encoded: String) =
    try {
      Image.makeFromEncoded(Base64.decode(encoded.substringAfterLast("base64,")))
        .toComposeImageBitmap()
    } catch (_: Throwable) {
      null
    }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}

/**
 * The socket URL for one live session, in the path form the redeemed playground session is
 * reachable under. No token: the same-origin upgrade carries the browse cookie.
 */
internal fun nativeStreamUrl(sessionId: String, previewId: String): String =
  js(
    """(function () {
      var url = new URL(
        '/' + encodeURIComponent(sessionId) + '/ws/' + encodeURIComponent(previewId),
        window.location.href
      );
      url.searchParams.set('codec', 'webp');
      if (url.protocol === 'http:') url.protocol = 'ws:';
      if (url.protocol === 'https:') url.protocol = 'wss:';
      return url.toString();
    })()""",
  )

private fun openNativeStreamSocket(
  url: String,
  onTextMessage: (String) -> Unit,
  onOpened: () -> Unit,
  onClosed: () -> Unit,
): JsAny =
  js(
    """(function () {
      var socket = new WebSocket(url);
      function reportVisibility() {
        if (socket.readyState !== 1) return;
        socket.send(JSON.stringify({ type: 'visibility', visible: !document.hidden }));
      }
      socket.onopen = function () { if (document.hidden) reportVisibility(); onOpened(); };
      socket.onclose = function () {
        document.removeEventListener('visibilitychange', reportVisibility);
        onClosed();
      };
      socket.onerror = function () { onClosed(); };
      socket.onmessage = function (event) { onTextMessage(String(event.data)); };
      document.addEventListener('visibilitychange', reportVisibility);
      return socket;
    })()""",
  )

private fun sendNativeStreamText(socket: JsAny, text: String): Boolean =
  js(
    """(function () {
      if (socket.readyState !== 1) return false;
      socket.send(text);
      return true;
    })()""",
  )

private fun closeNativeStreamSocket(socket: JsAny): Unit = js("socket.close()")

/** A same-origin session socket from its server-relative path, as the dwf-preview start returns. */
internal fun sessionSocketUrl(socketPath: String): String =
  js(
    """(function () {
      var url = new URL(socketPath, window.location.href);
      if (url.protocol === 'http:') url.protocol = 'ws:';
      if (url.protocol === 'https:') url.protocol = 'wss:';
      return url.toString();
    })()""",
  )
