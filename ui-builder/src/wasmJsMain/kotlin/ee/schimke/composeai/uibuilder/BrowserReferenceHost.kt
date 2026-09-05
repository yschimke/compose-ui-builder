@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolHttpClient
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.ExportResponseV1
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json

/**
 * The browser half of the reference overlay: picking a picture, catching a paste, snapshotting the
 * design, and keeping the whole stack on the server.
 *
 * Everything unportable lives here on purpose. `:ui-builder`'s editor is common Compose and has no
 * file picker, no clipboard, no `fetch` and no idea what a `Blob` is; it takes a
 * [ReferenceImportOutcome] and a [RestoredReference] and asks no further questions. That is the
 * same split the device-preset menu and the native-render pane already use.
 *
 * ### On Figma
 *
 * There is no Figma API call here, and there is not meant to be: the serve host holds no Figma
 * credential and makes no outbound call for a reference. The import path is the one Figma already
 * supports well — select a frame or a component, copy it as PNG or SVG, and paste it here (or
 * export it and pick the file). [sourceUrl] carries the Figma node URL when the operator supplies
 * one, as a link back, never as something this code will fetch.
 */
internal class BrowserReferenceHost(
  private val designId: String,
  private val http: UiBuilderProtocolHttpClient,
) {
  /** What the design has attached, or null when it has nothing and when the host will not say. */
  suspend fun load(): RestoredReference? {
    val response = referenceRequest("GET", referencePath(), null)
    if (response.status != 200) return null
    val stored =
      try {
        referenceJson.decodeFromString(ReferenceRecordWire.serializer(), response.body)
      } catch (_: Exception) {
        return null
      }
    return RestoredReference(
      image = stored.image?.toEditorImage(),
      settings =
        ReferenceOverlaySettings(
          mode = ReferenceDiffMode.ofWire(stored.settings.mode),
          visible = stored.settings.visible,
          opacityPercent = stored.settings.opacityPercent,
          offsetXDp = stored.settings.offsetXDp,
          offsetYDp = stored.settings.offsetYDp,
          scalePercent = stored.settings.scalePercent,
          splitPercent = stored.settings.splitPercent,
          alwaysShowBoxes = stored.settings.alwaysShowBoxes,
        ),
      pieces =
        stored.pieces.map {
          ReferencePiece(
            id = it.id,
            image = it.image.toEditorImage(),
            left = it.left,
            top = it.top,
            right = it.right,
            bottom = it.bottom,
            opacityPercent = it.opacityPercent,
            componentId = it.componentId,
          )
        },
      marks =
        stored.marks.map {
          ReferenceMark(
            id = it.id,
            kind = ReferenceMarkupKind.ofWire(it.kind),
            points = it.points,
            colorArgb = it.colorArgb,
            strokeWidthDp = it.strokeWidthDp,
            text = it.text,
          )
        },
    )
  }

  /**
   * Persist the current stack.
   *
   * Two routes rather than one, chosen by whether the pictures changed. Dragging an opacity slider
   * or rubbing out a mark must not re-upload several megabytes per frame, and it is the caller —
   * which knows what it last sent — that can tell the two apart cheaply.
   */
  suspend fun save(reference: ReferenceOverlayState, imagesChanged: Boolean): String? {
    if (!reference.hasContent) {
      referenceRequest("DELETE", referencePath(), null)
      return null
    }
    val settings = reference.settings.toWire()
    val pieces = reference.pieces.map { it.toWire() }
    val marks = reference.marks.map { it.toWire() }
    val response =
      if (imagesChanged) {
        referenceRequest(
          "PUT",
          referencePath(),
          referenceJson.encodeToString(
            ReferenceUploadWire.serializer(),
            ReferenceUploadWire(reference.image?.toWire(), settings, pieces, marks),
          ),
        )
      } else {
        referenceRequest(
          "PUT",
          "${referencePath()}/settings",
          referenceJson.encodeToString(
            ReferenceSettingsWire.serializer(),
            ReferenceSettingsWire(settings, pieces, marks),
          ),
        )
      }
    if (response.status in 200..299) return null
    return try {
      referenceJson.decodeFromString(ReferenceErrorWire.serializer(), response.body).message
    } catch (_: Exception) {
      "the host answered ${response.status} when storing the reference"
    }
  }

  /** A file the operator chose, refused with a reason, or nothing because they dismissed it. */
  suspend fun pickFile(): ReferenceImportOutcome =
    awaitImport(::pickReferenceFilePromise, "import-file")

  /**
   * The next image pasted onto the page.
   *
   * A promise resolved by a `paste` listener rather than a poll: pasting is an event, and a loop
   * asking a global every few hundred milliseconds would either miss one or burn a frame budget
   * doing nothing. Copy a frame in Figma, press paste here, and this returns.
   */
  suspend fun awaitPaste(): ReferenceImportOutcome =
    awaitImport(::awaitReferencePastePromise, "import-paste")

  /**
   * The design as it stands, rendered by the host, as a picture to build against.
   *
   * Uses the design's own PNG export rather than a screen grab of the editor: the export is the
   * design, with none of the editor's chrome, selection outlines or overlay in it — which is what
   * makes it usable as the *next* reference rather than a picture of the last one.
   */
  suspend fun snapshotDesign(): ReferenceImportOutcome {
    val result =
      http.execute(
        ExportDesignRequestV1(designId = designId, revision = null, format = ExportFormatV1.PNG)
      )
    val artifact =
      when (result) {
        is UiBuilderHttpResult.Response ->
          (result.response as? ExportResponseV1)?.artifact
            ?: return ReferenceImportOutcome.Refused("the host did not return a rendered design")
        is UiBuilderHttpResult.ServiceError ->
          return ReferenceImportOutcome.Refused(result.error.message)
        is UiBuilderHttpResult.SnapshotRequired ->
          return ReferenceImportOutcome.Refused(result.error.message)
      }
    if (artifact.encoding != ExportEncodingV1.BASE64) {
      return ReferenceImportOutcome.Refused("the design exported as text rather than a picture")
    }
    return ReferenceImportOutcome.Imported(
      ReferenceImage(
        // The digest the export already computed: identical pixels get an identical id, so
        // snapshotting twice without an edit does not force the editor to decode twice.
        id = "snapshot-${artifact.contentDigest}",
        name = "Snapshot of this design",
        mediaType = artifact.mediaType,
        base64 = artifact.content,
      )
    )
  }

  private suspend fun awaitImport(
    source: () -> Promise<JsString>,
    idPrefix: String,
  ): ReferenceImportOutcome {
    val encoded =
      try {
        awaitJsString(source())
      } catch (_: Exception) {
        return ReferenceImportOutcome.Refused("the picture could not be read")
      }
    if (encoded.isEmpty()) return ReferenceImportOutcome.Cancelled
    val picked =
      try {
        referenceJson.decodeFromString(PickedFileWire.serializer(), encoded)
      } catch (_: Exception) {
        return ReferenceImportOutcome.Refused("the picture could not be read")
      }
    if (picked.error.isNotEmpty()) return ReferenceImportOutcome.Refused(picked.error)
    // Checked here as well as on the host, so a bad paste says so at once rather than after a
    // round trip. The host's copy is the authority; this one only has to be no stricter.
    val svgText = if (picked.mediaType == ReferenceImage.SVG_MEDIA_TYPE) picked.text else null
    referenceImportRefusal(picked.mediaType, picked.byteCount, svgText)?.let {
      return ReferenceImportOutcome.Refused(it)
    }
    return ReferenceImportOutcome.Imported(
      ReferenceImage(
        // Provisional, and replaced by the host's content digest the next time the design is
        // opened. It only has to be unique within this session, which a counter is.
        id = "$idPrefix-${++importSequence}",
        name = picked.name.ifBlank { "Reference" },
        mediaType = picked.mediaType,
        base64 = picked.base64,
        widthPx = picked.widthPx,
        heightPx = picked.heightPx,
      )
    )
  }

  private var importSequence = 0

  private fun referencePath() = "/api/ui-builder/v1/designs/$designId/reference"

  private suspend fun referenceRequest(
    method: String,
    url: String,
    body: String?,
  ): ReferenceHttpResponse {
    val encoded =
      try {
        awaitJsString(referenceFetch(method, url, body ?: "", body != null))
      } catch (_: Exception) {
        return ReferenceHttpResponse(0, "")
      }
    return try {
      referenceJson.decodeFromString(ReferenceHttpResponse.serializer(), encoded)
    } catch (_: Exception) {
      ReferenceHttpResponse(0, "")
    }
  }
}

private fun ReferenceImage.toWire() =
  ReferenceImageWire(
    id = id,
    name = name,
    mediaType = mediaType,
    base64 = base64,
    widthPx = widthPx,
    heightPx = heightPx,
    sourceUrl = sourceUrl,
  )

private fun ReferenceImageWire.toEditorImage() =
  ReferenceImage(
    id = id.ifBlank { "stored-${base64.length}" },
    name = name,
    mediaType = mediaType,
    base64 = base64,
    widthPx = widthPx,
    heightPx = heightPx,
    sourceUrl = sourceUrl,
  )

private fun ReferenceOverlaySettings.toWire() =
  ReferenceSettingsPayload(
    mode = mode.wireValue,
    visible = visible,
    opacityPercent = opacityPercent,
    offsetXDp = offsetXDp,
    offsetYDp = offsetYDp,
    scalePercent = scalePercent,
    splitPercent = splitPercent,
    alwaysShowBoxes = alwaysShowBoxes,
  )

private fun ReferencePiece.toWire() =
  ReferencePieceWire(
    id = id,
    image = image.toWire(),
    left = left,
    top = top,
    right = right,
    bottom = bottom,
    opacityPercent = opacityPercent,
    componentId = componentId,
  )

private fun ReferenceMark.toWire() =
  ReferenceMarkWire(
    id = id,
    kind = kind.wireValue,
    points = points,
    colorArgb = colorArgb,
    strokeWidthDp = strokeWidthDp,
    text = text,
  )

/**
 * Tolerant on the way in, for the same reason the device-preset payload is: a host that learns a
 * new reference field must not blank somebody's overlay.
 */
private val referenceJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  explicitNulls = false
}

@kotlinx.serialization.Serializable
private data class ReferenceHttpResponse(val status: Int = 0, val body: String = "")

@kotlinx.serialization.Serializable private data class ReferenceErrorWire(val message: String = "")

@kotlinx.serialization.Serializable
private data class ReferenceRecordWire(
  val image: ReferenceImageWire? = null,
  val settings: ReferenceSettingsPayload = ReferenceSettingsPayload(),
  val pieces: List<ReferencePieceWire> = emptyList(),
  val marks: List<ReferenceMarkWire> = emptyList(),
)

@kotlinx.serialization.Serializable
private data class ReferenceUploadWire(
  val image: ReferenceImageWire? = null,
  val settings: ReferenceSettingsPayload = ReferenceSettingsPayload(),
  val pieces: List<ReferencePieceWire> = emptyList(),
  val marks: List<ReferenceMarkWire> = emptyList(),
)

@kotlinx.serialization.Serializable
private data class ReferenceSettingsWire(
  val settings: ReferenceSettingsPayload,
  val pieces: List<ReferencePieceWire> = emptyList(),
  val marks: List<ReferenceMarkWire> = emptyList(),
)

@kotlinx.serialization.Serializable
private data class ReferenceImageWire(
  val id: String = "",
  val name: String = "reference",
  val mediaType: String = "image/png",
  val base64: String = "",
  val widthPx: Int = 0,
  val heightPx: Int = 0,
  val sourceUrl: String? = null,
)

@kotlinx.serialization.Serializable
private data class ReferenceSettingsPayload(
  val mode: String = "overlay",
  val visible: Boolean = true,
  val opacityPercent: Int = 50,
  val offsetXDp: Float = 0f,
  val offsetYDp: Float = 0f,
  val scalePercent: Int = 100,
  val splitPercent: Int = 50,
  val alwaysShowBoxes: Boolean = false,
)

@kotlinx.serialization.Serializable
private data class ReferencePieceWire(
  val id: String,
  val image: ReferenceImageWire,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
  val opacityPercent: Int = 100,
  val componentId: String? = null,
)

@kotlinx.serialization.Serializable
private data class ReferenceMarkWire(
  val id: String,
  val kind: String,
  val points: List<Float>,
  val colorArgb: Long,
  val strokeWidthDp: Float = 2f,
  val text: String? = null,
)

/** What the browser bridge hands back for one picked or pasted file. */
@kotlinx.serialization.Serializable
private data class PickedFileWire(
  val name: String = "",
  val mediaType: String = "",
  val base64: String = "",
  val byteCount: Int = 0,
  val widthPx: Int = 0,
  val heightPx: Int = 0,
  /** SVG source, so the editor's own refusal can read it without decoding base64 twice. */
  val text: String? = null,
  val error: String = "",
)

private suspend fun awaitJsString(promise: Promise<JsString>): String =
  suspendCancellableCoroutine { continuation ->
    promise
      .then { value ->
        if (continuation.isActive) continuation.resume(value.toString())
        null
      }
      .catch { error ->
        if (continuation.isActive) {
          continuation.resumeWithException(IllegalStateException(error.toString()))
        }
        null
      }
  }

/**
 * One `fetch` with a method of our choosing.
 *
 * The shared [ee.schimke.composeai.uibuilder.client.UiBuilderHttpTransport] is POST-only, because
 * the protocol it was built for is; these routes are plain REST, and widening that interface for
 * one caller would push a browser concern into common code. Same-origin, so the browser attaches
 * the session cookie and this needs no credential of its own.
 */
@JsFun(
  """(method, url, body, hasBody) => fetch(url, {
    method,
    headers: hasBody ? { 'content-type': 'application/json' } : {},
    body: hasBody ? body : undefined,
  }).then((response) => response.text().then((text) => JSON.stringify({
    status: response.status,
    body: text,
  })))"""
)
private external fun referenceFetch(
  method: String,
  url: String,
  body: String,
  hasBody: Boolean,
): Promise<JsString>

/**
 * A file dialog, resolved with the chosen picture or with an empty string when it is dismissed.
 *
 * Cancellation cannot be detected reliably across browsers, so this also resolves empty when the
 * window regains focus with nothing chosen — otherwise a dismissed dialog would leave the panel
 * saying "Choosing…" until the page was reloaded.
 */
@JsFun(
  """() => new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/png,image/jpeg,image/webp,image/svg+xml';
    let settled = false;
    const settle = (value) => { if (!settled) { settled = true; resolve(value); } };
    input.addEventListener('change', () => {
      const file = input.files && input.files[0];
      if (!file) { settle(''); return; }
      globalThis.__composeReferenceRead(file).then(settle);
    });
    globalThis.addEventListener('focus', () => {
      setTimeout(() => { if (!(input.files && input.files.length)) settle(''); }, 500);
    }, { once: true });
    input.click();
  })"""
)
private external fun pickReferenceFilePromise(): Promise<JsString>

/** Resolves with the next image pasted onto the page. Text pastes are ignored, not refused. */
@JsFun(
  """() => new Promise((resolve) => {
    const handler = (event) => {
      const items = (event.clipboardData && event.clipboardData.items) || [];
      for (const item of items) {
        if (item.kind !== 'file') continue;
        const file = item.getAsFile();
        if (!file) continue;
        event.preventDefault();
        globalThis.removeEventListener('paste', handler, true);
        globalThis.__composeReferenceRead(file).then(resolve);
        return;
      }
    };
    globalThis.addEventListener('paste', handler, true);
  })"""
)
private external fun awaitReferencePastePromise(): Promise<JsString>

/**
 * Installs the shared file reader the two pickers above call.
 *
 * One implementation rather than two copies inside the `@JsFun` bodies: reading a `Blob` into
 * base64, sniffing its type and measuring it is the fiddly part, and having it twice is how the
 * paste path and the file path drift into disagreeing about what they accept.
 */
@JsFun(
  """() => {
    globalThis.__composeReferenceRead = async (file) => {
      try {
        const mediaType = (file.type || '').split(';')[0].toLowerCase();
        const buffer = await file.arrayBuffer();
        const bytes = new Uint8Array(buffer);
        let binary = '';
        const chunk = 0x8000;
        for (let index = 0; index < bytes.length; index += chunk) {
          binary += String.fromCharCode.apply(null, bytes.subarray(index, index + chunk));
        }
        let width = 0;
        let height = 0;
        try {
          const decoded = await createImageBitmap(file);
          width = decoded.width;
          height = decoded.height;
          decoded.close();
        } catch (ignored) {
          // An SVG without an intrinsic size, or a format this browser will not decode off the
          // main thread. The editor's own decoder settles the size either way.
        }
        const text = mediaType === 'image/svg+xml' ? new TextDecoder().decode(bytes) : null;
        return JSON.stringify({
          name: file.name || 'Pasted picture',
          mediaType,
          base64: btoa(binary),
          byteCount: bytes.length,
          widthPx: width,
          heightPx: height,
          text,
        });
      } catch (failure) {
        return JSON.stringify({ error: 'the picture could not be read' });
      }
    };
  }"""
)
internal external fun installReferenceBridge()
