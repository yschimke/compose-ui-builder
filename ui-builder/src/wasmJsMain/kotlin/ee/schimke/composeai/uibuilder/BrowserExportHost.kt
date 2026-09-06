package ee.schimke.composeai.uibuilder

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The browser half of [UiBuilderExportHost]: the clipboard, the download, the page's own origin.
 *
 * ## One address for everything
 *
 * Every verb here reads the design's **live export URL** —
 * `/api/ui-builder/v1/designs/<id>/export.svg` or `.png` — rather than posting a protocol request
 * and carrying the artifact around. That is deliberate, and it is what makes "Copy link" honest:
 * the link a person copies is the very URL the Copy and Download rows fetched, so what they pasted
 * into Figma and what a colleague opens from the link are the same bytes for the same revision. The
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
) : UiBuilderExportHost {

  override suspend fun copyPicture(format: EditorExportFormat): String {
    val url = sameOriginRequestUrl(livePath(format))
    val outcome =
      try {
        when (format) {
          EditorExportFormat.Svg -> awaitJsString(copySvgTextPromise(url))
          EditorExportFormat.Png -> awaitJsString(copyPngImagePromise(url))
        }
      } catch (failure: Exception) {
        return "Copy ${format.label} failed: ${failure.message?.trimJsError() ?: "unknown error"}"
      }
    return if (outcome.isEmpty()) "${format.label} copied — paste it into Figma" else outcome
  }

  override suspend fun copyLink(format: EditorExportFormat): String {
    val link = shareableUrl(livePath(format))
    val outcome =
      try {
        awaitJsString(copyTextPromise(link))
      } catch (failure: Exception) {
        return "Copy link failed: ${failure.message?.trimJsError() ?: "unknown error"}"
      }
    return if (outcome.isEmpty()) "Live ${format.label} link copied" else outcome
  }

  override suspend fun download(format: EditorExportFormat): String {
    val url = sameOriginRequestUrl("${livePath(format)}?download=1")
    val outcome =
      try {
        awaitJsString(downloadPromise(url, "$designId.${format.extension}"))
      } catch (failure: Exception) {
        return "Download failed: ${failure.message?.trimJsError() ?: "unknown error"}"
      }
    return if (outcome.isEmpty()) "Downloading $designId.${format.extension}" else outcome
  }

  /** The live route, relative to the page; see [UI_BUILDER_LIVE_EXPORT_PATH]. */
  internal fun livePath(format: EditorExportFormat): String =
    "$UI_BUILDER_LIVE_EXPORT_PATH/${encodeUriComponent(designId)}/export.${format.extension}"

  private fun String.trimJsError(): String = removePrefix("Error: ").removePrefix("JsError: ")
}

internal const val UI_BUILDER_LIVE_EXPORT_PATH = "/api/ui-builder/v1/designs"

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
  """(url) => {
    if (!navigator.clipboard || !navigator.clipboard.writeText) {
      return Promise.resolve('the browser offers no clipboard here (is the page on https or localhost?)');
    }
    return fetch(url).then((response) => {
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
private external fun copySvgTextPromise(url: String): Promise<JsString>

/**
 * Fetches the PNG and puts it on the clipboard as an image.
 *
 * The blob is a promise inside the `ClipboardItem`, built synchronously in the click: Safari only
 * honours a write whose item existed before the first `await`, and Chrome accepts the promise form
 * too. Where `ClipboardItem` is missing altogether the answer says so rather than falling back to a
 * data URL nobody can paste as a picture. Resolves empty on success.
 */
@JsFun(
  """(url) => {
    if (!navigator.clipboard || !navigator.clipboard.write || typeof ClipboardItem === 'undefined') {
      return Promise.resolve('this browser cannot copy images (try Download PNG)');
    }
    const blob = fetch(url).then((response) => {
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
private external fun copyPngImagePromise(url: String): Promise<JsString>

/**
 * Saves the file through an anchor click.
 *
 * The route answers `Content-Disposition: attachment` for `?download=1`, so the anchor's own
 * `download` attribute is belt and braces: same-origin, either alone would do. Resolves empty; a
 * navigation cannot report whether the save dialog was accepted.
 */
@JsFun(
  """(url, filename) => {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    anchor.rel = 'noopener';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    return Promise.resolve('');
  }"""
)
private external fun downloadPromise(url: String, filename: String): Promise<JsString>

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
