package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.skia.Image

/**
 * The editor's native pane, streamed off the serve host's existing live lane.
 *
 * ## Nothing here is a new protocol
 *
 * `/{session}/ws/{preview}` is the socket the viewer's Live toggle has always opened: frames are
 * pushed as `{"type":"frame","dataBase64":"…","codec":"…","seq":n}`, and `{"type":"input", …}` goes
 * the other way carrying a pointer event in the frame's own pixels. The native preview route
 * already compiled the design and redeemed its token into exactly such a session, so this class is
 * a socket and a decoder and no more than that. See `ServeStreamProtocol` for the wire shapes.
 *
 * ## Why this lives in `wasmJsMain`
 *
 * `:ui-builder`'s editor is common Compose with no `WebSocket` and no base64 in it — the same rule
 * `BrowserCommentHost` and `BrowserUiBuilderWebSocketTransport` follow. The editor is handed
 * [UiBuilderNativeStream], which is a frame, a failure and a `send`; everything that knows about
 * origins, tokens and PNG bytes is here.
 *
 * ## Visibility, and why the socket says so
 *
 * A native pane in a tab nobody is looking at is a held Android daemon rendering into nothing. The
 * lane throttles on `{"type":"visibility"}` rather than tearing down, so coming back repaints from
 * a keyframe instead of paying for another boot; this reports the page's own visibility once on
 * open and on every change while the pane is up.
 */
internal class BrowserNativeStream(live: UiBuilderNativeLive) : UiBuilderNativeStream {

  override var frame: UiBuilderNativeFrame? by mutableStateOf(null)
    private set

  override var failure: String? by mutableStateOf(null)
    private set

  /**
   * The newest sequence painted, so a frame that arrives out of order is dropped rather than drawn.
   *
   * The lane's own `seq` restarts at 0 on a reconnect, and this object is never reused across one:
   * a new session is a new stream and a new instance of this class.
   */
  private var painted = -1L

  private var closed = false

  private val socket: JsAny? =
    try {
      openNativeStreamSocket(
        url = nativeStreamUrl(live.sessionId, live.previewId),
        onTextMessage = ::onMessage,
        onClosed = {
          // Only a close *before* the first frame is a failure worth a sentence. After one, a
          // close is an ordinary teardown — the pane already has a picture, and replacing it with
          // an error because the seat was reclaimed would be a worse answer than the last frame.
          if (!closed && frame == null && failure == null) {
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
        // `dataBase64`, which is what `ServeStreamProtocol.frameMessage` writes — *not* `image`,
        // which is the still render's field on the HTTP payload. Reading the wrong one silently
        // dropped every frame and left the pane on "connecting…" forever.
        val encoded = message["dataBase64"]?.jsonPrimitive?.contentOrNull ?: return
        val decoded = decode(encoded) ?: return
        painted = sequence
        failure = null
        frame = UiBuilderNativeFrame(image = decoded, sequence = sequence)
      }
      // Reported rather than thrown: the lane sends these for a bad override or a backend that
      // cannot stream, and the pane has a still to fall back on either way.
      "error" ->
        failure = message["message"]?.jsonPrimitive?.contentOrNull ?: "the live native lane failed"
    }
  }

  /**
   * The frame's bytes as an image, or null when they will not decode.
   *
   * The lane sends either a bare base64 payload or a `data:` URI depending on the codec, exactly as
   * the still render's `imageBase64` does, so both spellings are accepted here for the same reason
   * they are there: the field is named for its payload and the prefix is a wrapper. A frame that
   * will not decode is dropped — the next one repaints — rather than reported, because a single bad
   * frame is not a broken stream.
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
 * The socket URL for one live session, in the path form the lane reads a session id out of.
 *
 * `/{session}/ws/{preview}` rather than `/ws/{preview}?session=` because the path form is the one
 * the redeemed playground session is reachable under, and the access token the page was opened with
 * rides along so a token-gated host accepts the upgrade.
 */
private fun nativeStreamUrl(sessionId: String, previewId: String): String =
  js(
    """(function () {
      var url = new URL(
        '/' + encodeURIComponent(sessionId) + '/ws/' + encodeURIComponent(previewId),
        window.location.href
      );
      var pageToken = new URL(window.location.href).searchParams.get('token');
      if (pageToken) url.searchParams.set('token', pageToken);
      url.searchParams.set('codec', 'webp');
      if (url.protocol === 'http:') url.protocol = 'ws:';
      if (url.protocol === 'https:') url.protocol = 'wss:';
      return url.toString();
    })()"""
  )

private fun openNativeStreamSocket(
  url: String,
  onTextMessage: (String) -> Unit,
  onClosed: () -> Unit,
): JsAny =
  js(
    """(function () {
      var socket = new WebSocket(url);
      function reportVisibility() {
        if (socket.readyState !== 1) return;
        socket.send(JSON.stringify({ type: 'visibility', visible: !document.hidden }));
      }
      socket.onopen = function () { if (document.hidden) reportVisibility(); };
      socket.onclose = function () {
        document.removeEventListener('visibilitychange', reportVisibility);
        onClosed();
      };
      socket.onerror = function () { onClosed(); };
      socket.onmessage = function (event) { onTextMessage(String(event.data)); };
      document.addEventListener('visibilitychange', reportVisibility);
      return socket;
    })()"""
  )

private fun sendNativeStreamText(socket: JsAny, text: String): Unit =
  js("""(function () { if (socket.readyState === 1) socket.send(text); })()""")

private fun closeNativeStreamSocket(socket: JsAny): Unit = js("socket.close()")
