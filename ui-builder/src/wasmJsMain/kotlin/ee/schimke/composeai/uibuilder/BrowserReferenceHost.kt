@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolHttpClient
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.ExportResponseV1
import ee.schimke.composeai.uibuilder.reference.FIGMA_PASTE_ADVICE
import ee.schimke.composeai.uibuilder.reference.ReferenceDiffMode
import ee.schimke.composeai.uibuilder.reference.ReferenceFit
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import ee.schimke.composeai.uibuilder.reference.ReferenceImportOutcome
import ee.schimke.composeai.uibuilder.reference.ReferenceMark
import ee.schimke.composeai.uibuilder.reference.ReferenceMarkupKind
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlaySettings
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.reference.ReferencePiece
import ee.schimke.composeai.uibuilder.reference.ReferenceUrl
import ee.schimke.composeai.uibuilder.reference.RestoredReference
import ee.schimke.composeai.uibuilder.reference.parseReferenceUrl
import ee.schimke.composeai.uibuilder.reference.referenceImportRefusal
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json

/**
 * The browser half of the reference overlay: picking a picture, catching a paste, snapshotting the
 * design, and keeping the stack on the server. The common editor only sees [ReferenceImportOutcome]
 * and [RestoredReference].
 *
 * No Figma API call is made: the host holds no Figma credential. Users copy a frame as PNG or SVG
 * and paste it (or pick an exported file); [sourceUrl] keeps the Figma node URL as a link only.
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
          fit = ReferenceFit.ofWire(stored.settings.fit),
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
   * Persist the current stack. Two routes, chosen by whether the pictures changed, so slider drags
   * and erasing don't re-upload megabytes; the caller knows what it last sent.
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

  /** The next image pasted onto the page, resolved by a `paste` listener. */
  suspend fun awaitPaste(): ReferenceImportOutcome =
    awaitImport(::awaitReferencePastePromise, "import-paste")

  /**
   * A picture fetched from a link the operator pasted. Plain image URLs are fetched by the browser
   * (works only if the site allows cross-origin reads). Figma links are refused with the paste
   * route instead, since rendering needs a Figma token this host doesn't hold.
   */
  suspend fun fetchUrl(text: String): ReferenceImportOutcome =
    when (val parsed = parseReferenceUrl(text)) {
      is ReferenceUrl.Unsupported -> ReferenceImportOutcome.Refused(parsed.reason)
      is ReferenceUrl.Figma ->
        ReferenceImportOutcome.Refused(
          "The web editor does not hold a Figma token, so it cannot render that frame. " +
            FIGMA_PASTE_ADVICE
        )
      is ReferenceUrl.Image ->
        awaitImport({ fetchReferenceUrlPromise(parsed.url) }, "import-url", sourceUrl = parsed.url)
    }

  /**
   * The design as it stands, rendered by the host's PNG export (no editor chrome, selection or
   * overlay), as a picture to build against.
   */
  suspend fun snapshotDesign(revision: Long? = null): ReferenceImportOutcome {
    val result =
      http.execute(
        ExportDesignRequestV1(designId = designId, revision = revision, format = ExportFormatV1.PNG)
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
    sourceUrl: String? = null,
  ): ReferenceImportOutcome {
    val encoded =
      try {
        awaitJsString(source())
      } catch (e: CancellationException) {
        // Cancellation, not a refusal: answering `Refused` here made the `while (true)` paste loop
        // in `LiveSessionApp` spin without suspending and freeze the tab.
        throw e
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
        sourceUrl = sourceUrl,
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
        awaitJsString(referenceFetch(method, sameOriginRequestUrl(url), body ?: "", body != null))
      } catch (e: CancellationException) {
        throw e
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
    fit = fit.wireValue,
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

/** Lenient decoding, so a new reference field on the host doesn't blank the overlay. */
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
  /**
   * Hosts whose stored shape predates the field drop it, and the editor reads
   * [ReferenceFit.Contain] back.
   */
  val fit: String = "contain",
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
 * One `fetch` with a method of our choosing: the shared
 * [ee.schimke.composeai.uibuilder.client.UiBuilderHttpTransport] is POST-only and these are REST
 * routes. The URL comes from [sameOriginRequestUrl], which carries the page's token (a cookie alone
 * isn't enough on a `--token` server).
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
 * A cross-origin image fetch, handed to the same reader a picked file goes through so links and
 * files obey the same rules.
 */
@JsFun(
  """(url) => fetch(url, { mode: 'cors', credentials: 'omit' })
    .then((response) => {
      if (!response.ok) {
        return JSON.stringify({ error: 'the link answered ' + response.status });
      }
      return response.blob().then((blob) => {
        const name = decodeURIComponent(new URL(url).pathname.split('/').pop() || 'Linked picture');
        return globalThis.__composeReferenceRead(new File([blob], name, { type: blob.type }));
      });
    })
    .catch(() => JSON.stringify({
      error: 'the browser would not fetch that picture: the site allows no cross-origin reads, ' +
        'or this page may not reach it. Download it and import the file instead',
    }))"""
)
private external fun fetchReferenceUrlPromise(url: String): Promise<JsString>

/**
 * A file dialog, resolved with the chosen picture or empty when dismissed. Cancellation isn't
 * reliably detectable, so it also resolves empty when the window regains focus with nothing chosen.
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
 * Installs the shared file reader the two pickers above call (base64, type sniffing, measuring), so
 * paste and file paths can't disagree.
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
