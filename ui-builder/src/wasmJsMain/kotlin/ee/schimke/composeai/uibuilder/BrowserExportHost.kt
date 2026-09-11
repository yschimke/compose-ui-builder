@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

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
 * ## One address for everything
 *
 * Saved artifacts use the design's **live export URL** —
 * `/api/ui-builder/v1/designs/<id>/export.svg` or `.png`. Current drafts are submitted to
 * `/api/ui-builder/v1/documents/export.png`, `.json` or `.rc`; the document is captured once per
 * action. A local design has no Copy link row. Saved links retain the existing guarantee: the link
 * a person copies is the very URL the Copy and Download rows fetched, so what they pasted into
 * Figma and what a colleague opens from the link are the same bytes for the same revision. The
 * route renders the current committed revision on each request, so the address is live.
 *
 * ## What lands on the clipboard
 *
 * The same shapes the catalog viewer's preview page writes, for the same consumers:
 * - SVG goes on as **text**, because that is what Figma pastes as editable layers — a
 *   `ClipboardItem` of `image/svg+xml` is not something browsers or Figma accept today.
 * - PNG goes on as an **image** (`ClipboardItem` of `image/png`), and the blob is handed over as a
 *   promise so the item is built synchronously inside the click — Safari refuses one built after an
 *   `await`.
 * - A link goes on as text, and it is the plain URL: the page's `?token=` is stripped, because a
 *   shared link is an address and never a credential. The fetches this host makes for itself do
 *   carry it, through [sameOriginRequestUrl], the way every other UI-builder request does.
 *
 * ## Why the answers are sentences
 *
 * The toolbar shows what the host says, so a refused clipboard write ("the browser refused the
 * clipboard") is reported where the button is, rather than swallowed. Nothing here throws to the
 * editor: a failure is a sentence too.
 */
internal class BrowserExportHost(
  private val designId: String,
  override val formats: List<EditorExportFormat>,
  /**
   * The committed revision every route this host builds is pinned to, or null for the head.
   *
   * Set only where the editor itself is pinned. The export routes render on request, so a host left
   * unpinned answers a historical page with the picture of the current design; a reader who copied
   * that link, or pasted that PNG into a review, would be showing the wrong revision under a banner
   * naming the right one.
   */
  private val revision: Long? = null,
  override val supportsLinks: Boolean = true,
  private val suppliedDocument: (() -> UiBuilderDocument?)? = null,
) : UiBuilderExportHost {

  override suspend fun copyPicture(format: EditorExportFormat): String {
    val document = currentDocument(format)
    val url = sameOriginRequestUrl(if (document == null) livePath(format) else suppliedPath(format))
    val outcome =
      try {
        when (format) {
          EditorExportFormat.Svg,
          EditorExportFormat.Json -> awaitJsString(copySvgTextPromise(url, document))
          EditorExportFormat.Png -> awaitJsString(copyPngImagePromise(url, document))
          EditorExportFormat.Rc -> return "Use Download to save the binary document"
        }
      } catch (failure: Exception) {
        return "Copy ${format.label} failed: ${failure.message?.trimJsError() ?: "unknown error"}"
      }
    return if (outcome.isNotEmpty()) outcome
    else if (format == EditorExportFormat.Json) "JSON source copied"
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

  private fun currentDocument(format: EditorExportFormat): String? =
    if (format == EditorExportFormat.Svg) null
    else suppliedDocument?.invoke()?.let { Json.encodeToString(it.toDesignDocumentV1()) }

  private fun suppliedPath(format: EditorExportFormat): String =
    "$UI_BUILDER_DOCUMENT_EXPORT_PATH/export.${format.extension}"

  /**
   * The export route for one format, relative to the page; see [UI_BUILDER_LIVE_EXPORT_PATH].
   *
   * The query is assembled from a list rather than concatenated, because there are now two things
   * that can go in it and `?download=1` written twice — once as the first parameter and once after
   * a revision — is how that becomes a broken URL.
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
 * Copies one design link — a node, a thread, a revision — and answers with a sentence.
 *
 * Beside the export host rather than inside it, because it is not an export: the address it copies
 * names a place in the *editor*, not a rendered picture, and it is offered from a layer's menu and
 * a thread's card rather than from the Export menu. What it shares with that host is the two rules
 * that matter — the link goes through [shareableUrl], so the page's `?token=` never rides along,
 * and a browser that refuses the clipboard is reported in a sentence rather than thrown.
 *
 * The address is shown as well as copied. A link is the one thing on this page whose value a person
 * checks before pasting it into a pull request, and a bare "Copied" makes them paste it somewhere
 * to find out what they have.
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

/**
 * An absolute URL for sharing: resolved against the page, with no credential on it.
 *
 * The opposite of [sameOriginRequestUrl] in the one respect that matters — that one *adds* the
 * page's `?token=` so the request authenticates; this one makes sure it is absent, so a link pasted
 * into a chat does not carry the operator token with it. Whoever opens the link presents their own
 * credential, the way they would for the editor's own URL.
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
 * Fetches the SVG and puts its markup on the clipboard as text.
 *
 * `fetch` first and `writeText` after: Chrome and Firefox keep the click's activation alive across
 * the fetch, and this is exactly what the catalog viewer's Copy SVG does. Resolves empty on
 * success.
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
 * Fetches the PNG and puts it on the clipboard as an image.
 *
 * The blob is a promise inside the `ClipboardItem`, built synchronously in the click: Safari only
 * honours a write whose item existed before the first `await`, and Chrome accepts the promise form
 * too. Where `ClipboardItem` is missing altogether the answer says so rather than falling back to a
 * data URL nobody can paste as a picture. Resolves empty on success.
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
 * Saves the file through an anchor click.
 *
 * The route answers `Content-Disposition: attachment` for `?download=1`, so the anchor's own
 * `download` attribute is belt and braces: same-origin, either alone would do. Resolves empty; a
 * navigation cannot report whether the save dialog was accepted.
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
