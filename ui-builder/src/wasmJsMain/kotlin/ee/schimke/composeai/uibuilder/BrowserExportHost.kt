@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.EditorExportFormat
import ee.schimke.composeai.uibuilder.editor.UiBuilderExportHost
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The browser half of [UiBuilderExportHost]: the clipboard, the download, the page's own origin.
 *
 * Saved artifacts use the design's **live export URL**
 * (`/api/ui-builder/v1/designs/<id>/export.svg` or `.png`), so a copied link fetches the same bytes
 * the Copy and Download rows did for the same revision. Current drafts are submitted to
 * `/api/ui-builder/v1/documents/export.png`, `.json` or `.rc`, captured once per action. Local
 * designs have no Copy link row.
 *
 * Clipboard shapes, as the catalog viewer writes them:
 * - SVG as **text** (what Figma pastes as editable layers; `image/svg+xml` items aren't accepted).
 * - PNG as an **image** `ClipboardItem` whose blob is a promise, built synchronously in the click
 *   (Safari refuses items built after an `await`).
 * - Links as plain text with `?token=` stripped; this host's own fetches carry it via
 *   [sameOriginRequestUrl].
 *
 * Nothing here throws to the editor: every outcome, including failures, is a sentence shown by the
 * toolbar.
 */
internal class BrowserExportHost(
  private val designId: String,
  override val formats: List<EditorExportFormat>,
  /**
   * The committed revision every route this host builds is pinned to, or null for the head. Set
   * only when the editor is pinned, so exports from a historical page show that revision.
   */
  private val revision: Long? = null,
  override val supportsLinks: Boolean = true,
  private val suppliedDocument: ((EditorExportFormat) -> UiBuilderDocument?)? = null,
) : UiBuilderExportHost {

  override suspend fun copyPicture(format: EditorExportFormat): String {
    val document = currentDocument(format)
    val url = sameOriginRequestUrl(if (document == null) livePath(format) else suppliedPath(format))
    val outcome =
      try {
        when (format) {
          EditorExportFormat.Svg,
          EditorExportFormat.Json,
          EditorExportFormat.LovelaceJson,
          EditorExportFormat.A2uiJson -> awaitJsString(copySvgTextPromise(url, document))
          EditorExportFormat.Png -> awaitJsString(copyPngImagePromise(url, document))
          EditorExportFormat.Rc -> return "Use Download to save the binary document"
        }
      } catch (failure: Exception) {
        return "Copy ${format.label} failed: ${failure.message?.trimJsError() ?: "unknown error"}"
      }
    return if (outcome.isNotEmpty()) outcome
    else if (format == EditorExportFormat.Json) "JSON source copied"
    else if (format == EditorExportFormat.A2uiJson) "A2UI messages copied"
    else "${format.label} copied — paste it into Figma"
  }

  override suspend fun copyLink(format: EditorExportFormat): String {
    if (!supportsLinks) return "Save the design to share an export link"
    val link = shareableUrl(livePath(format))
    val outcome =
      try {
        awaitJsString(copyTextPromise(link))
      } catch (failure: Exception) {
        return "Copy link failed: ${failure.message?.trimJsError() ?: "unknown error"}"
      }
    val what = if (revision == null) "Live" else "Revision $revision"
    return if (outcome.isEmpty()) "$what ${format.label} link copied" else outcome
  }

  override suspend fun download(format: EditorExportFormat): String {
    val document = currentDocument(format)
    val url =
      sameOriginRequestUrl(
        if (document == null) livePath(format, download = true) else suppliedPath(format)
      )
    val outcome =
      try {
        awaitJsString(downloadPromise(url, "$designId.${format.extension}", document))
      } catch (failure: Exception) {
        return "Download failed: ${failure.message?.trimJsError() ?: "unknown error"}"
      }
    return if (outcome.isEmpty()) "Downloading $designId.${format.extension}" else outcome
  }

  /** A phone browser whose share sheet takes files; see [browserCanShareFiles]. */
  override val supportsShare: Boolean by lazy { browserCanShareFiles() }

  override suspend fun share(format: EditorExportFormat): String {
    if (!supportsShare) return download(format)
    val document = currentDocument(format)
    val url = sameOriginRequestUrl(if (document == null) livePath(format) else suppliedPath(format))
    val filename = "$designId.${format.extension}"
    val outcome =
      try {
        awaitJsString(sharePromise(url, filename, format.mediaType(), document))
      } catch (failure: Exception) {
        return "Share failed: ${failure.message?.trimJsError() ?: "unknown error"}"
      }
    return outcome.ifEmpty { "Shared $filename" }
  }

  private fun EditorExportFormat.mediaType(): String =
    when (this) {
      EditorExportFormat.Svg -> "image/svg+xml"
      EditorExportFormat.Png -> "image/png"
      EditorExportFormat.Json,
      EditorExportFormat.LovelaceJson,
      EditorExportFormat.A2uiJson -> "application/json"
      EditorExportFormat.Rc -> "application/octet-stream"
    }

  private fun currentDocument(format: EditorExportFormat): String? =
    if (format == EditorExportFormat.Svg) null
    else suppliedDocument?.invoke(format)?.let { Json.encodeToString(it.toDesignDocumentV1()) }

  private fun suppliedPath(format: EditorExportFormat): String =
    "$UI_BUILDER_DOCUMENT_EXPORT_PATH/export.${format.extension}"

  /**
   * The export route for one format, relative to the page; see [UI_BUILDER_LIVE_EXPORT_PATH]. The
   * query is built from a list since it may hold both a revision and `download=1`.
   */
  internal fun livePath(format: EditorExportFormat, download: Boolean = false): String {
    val path =
      "$UI_BUILDER_LIVE_EXPORT_PATH/${encodeUriComponent(designId)}/export.${format.extension}"
    val query = buildList {
      revision?.let { add("revision=$it") }
      if (download) add("download=1")
    }
    return if (query.isEmpty()) path else "$path?${query.joinToString("&")}"
  }

  private fun String.trimJsError(): String = removePrefix("Error: ").removePrefix("JsError: ")
}

internal const val UI_BUILDER_LIVE_EXPORT_PATH = "/api/ui-builder/v1/designs"
internal const val UI_BUILDER_DOCUMENT_EXPORT_PATH = "/api/ui-builder/v1/documents"

/**
 * Copies one design link (a node, a thread, a revision) and answers with a sentence. Not an export:
 * it names a place in the editor. Like the export host, it goes through [shareableUrl] (no
 * `?token=`) and reports clipboard refusals. The address is shown as well as copied.
 */
internal suspend fun copyDesignLink(path: String): String {
  val link = shareableUrl(path)
  val outcome =
    try {
      awaitJsString(copyTextPromise(link))
    } catch (failure: Exception) {
      return "Copy link failed: ${failure.message ?: "unknown error"}"
    }
  return if (outcome.isEmpty()) "Link copied · $link" else outcome
}

/** Copies agent instructions without routing credentials through the browser URL. */
internal suspend fun copyAiPrompt(prompt: String): String =
  try {
    val outcome = awaitJsString(copyTextPromise(prompt))
    if (outcome.isEmpty()) "Prompt copied · paste it into your agent" else outcome
  } catch (failure: Exception) {
    "Copy AI prompt failed: ${failure.message ?: "unknown error"}"
  }

/**
 * An absolute URL for sharing: resolved against the page, with no credential on it. The opposite of
 * [sameOriginRequestUrl], which adds `?token=`; recipients present their own credential.
 */
@JsFun(
  """(path) => {
    const resolved = new URL(path, window.location.href);
    resolved.searchParams.delete('token');
    return resolved.toString();
  }"""
)
internal external fun shareableUrl(path: String): String

/** Writes text to the clipboard; resolves empty on success, with a sentence on refusal. */
@JsFun(
  """(text) => {
    if (!navigator.clipboard || !navigator.clipboard.writeText) {
      return Promise.resolve('the browser offers no clipboard here (is the page on https or localhost?)');
    }
    return navigator.clipboard.writeText(text).then(
      () => '',
      (error) => 'the browser refused the clipboard: ' + (error && error.message ? error.message : error),
    );
  }"""
)
private external fun copyTextPromise(text: String): Promise<JsString>

/**
 * Fetches the SVG and puts its markup on the clipboard as text. Chrome and Firefox keep the click's
 * activation across the fetch. Resolves empty on success.
 */
@JsFun(
  """(url, document) => {
    if (!navigator.clipboard || !navigator.clipboard.writeText) {
      return Promise.resolve('the browser offers no clipboard here (is the page on https or localhost?)');
    }
    return fetch(url, document == null ? undefined : {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: document,
    }).then((response) => {
      if (!response.ok) {
        return response.text().then((body) => {
          throw new Error('HTTP ' + response.status + (body ? ': ' + body : ''));
        });
      }
      return response.text();
    }).then((markup) => navigator.clipboard.writeText(markup).then(
      () => '',
      (error) => 'the browser refused the clipboard: ' + (error && error.message ? error.message : error),
    ));
  }"""
)
private external fun copySvgTextPromise(url: String, document: String?): Promise<JsString>

/**
 * Fetches the PNG and puts it on the clipboard as an image. The blob is a promise inside a
 * `ClipboardItem` built synchronously in the click (Safari's requirement). Without `ClipboardItem`,
 * says so. Resolves empty on success.
 */
@JsFun(
  """(url, document) => {
    if (!navigator.clipboard || !navigator.clipboard.write || typeof ClipboardItem === 'undefined') {
      return Promise.resolve('this browser cannot copy images (try Download PNG)');
    }
    const blob = fetch(url, document == null ? undefined : {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: document,
    }).then((response) => {
      if (!response.ok) throw new Error('HTTP ' + response.status);
      return response.blob();
    }).then((body) => body.type === 'image/png' ? body : new Blob([body], { type: 'image/png' }));
    let item;
    try {
      item = new ClipboardItem({ 'image/png': blob });
    } catch (error) {
      return Promise.resolve('this browser cannot copy images (try Download PNG)');
    }
    return navigator.clipboard.write([item]).then(
      () => '',
      (error) => 'the browser refused the clipboard: ' + (error && error.message ? error.message : error),
    );
  }"""
)
private external fun copyPngImagePromise(url: String, document: String?): Promise<JsString>

/**
 * Saves the file through an anchor click (`?download=1` answers `Content-Disposition: attachment`,
 * plus the anchor's `download` attribute). Resolves empty; a navigation can't report the outcome.
 */
@JsFun(
  """(url, filename, content) => {
    const save = (href) => {
      const anchor = document.createElement('a');
      anchor.href = href;
      anchor.download = filename;
      anchor.rel = 'noopener';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
    };
    if (content == null) { save(url); return Promise.resolve(''); }
    return fetch(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: content,
    }).then(async response => {
      if (!response.ok) throw new Error('HTTP ' + response.status + ': ' + await response.text());
      const objectUrl = URL.createObjectURL(await response.blob());
      save(objectUrl);
      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
      return '';
    });
  }"""
)
private external fun downloadPromise(
  url: String,
  filename: String,
  document: String?,
): Promise<JsString>

/**
 * Fetches the rendered file and hands it to the share sheet, downloading it instead where the sheet
 * won't take it. The fetch spends some of the tap's activation, so `NotAllowedError` falls back to
 * a download; `AbortError` (closed sheet) saves nothing. Resolves empty when shared.
 */
@JsFun(
  """(url, filename, type, content) => {
    const request = content == null ? undefined : {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: content,
    };
    return fetch(url, request).then(async (response) => {
      if (!response.ok) throw new Error('HTTP ' + response.status + ': ' + await response.text());
      const blob = await response.blob();
      const save = () => {
        const objectUrl = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = objectUrl;
        anchor.download = filename;
        anchor.rel = 'noopener';
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
      };
      const file = new File([blob], filename, { type });
      if (typeof navigator.canShare !== 'function' || !navigator.canShare({ files: [file] })) {
        save();
        return 'This browser cannot share ' + filename + ', so it was downloaded instead';
      }
      try {
        await navigator.share({ files: [file], title: filename });
        return '';
      } catch (error) {
        if (error && error.name === 'AbortError') return 'Share cancelled';
        save();
        return 'Could not share ' + filename + ', so it was downloaded instead';
      }
    });
  }"""
)
private external fun sharePromise(
  url: String,
  filename: String,
  type: String,
  document: String?,
): Promise<JsString>

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
