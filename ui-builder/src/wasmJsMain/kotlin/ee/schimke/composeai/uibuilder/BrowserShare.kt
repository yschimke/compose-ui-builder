@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import kotlin.js.Promise

/**
 * The Web Share half of getting a file out of the editor on a phone, where downloads are hard to
 * find and the clipboard can't carry files; the download stays as the fallback.
 *
 * Asked twice: up front (only on a coarse pointer, so desktops keep their menu) to decide whether
 * to offer the row, then on the real file, since browsers allow only some types (Chrome shares SVG
 * and PNG, not `.json`).
 */
internal fun browserCanShareFiles(): Boolean = browserCanShareFile("probe.png", "image/png")

/** Whether a file called [filename] of [type] would be taken by the share sheet, on a phone. */
@JsFun(
  """(filename, type) => {
    try {
      if (typeof navigator.share !== 'function' || typeof navigator.canShare !== 'function') {
        return false;
      }
      if (!globalThis.matchMedia || !globalThis.matchMedia('(pointer: coarse)').matches) return false;
      const probe = new File([new Uint8Array([0x7b, 0x7d])], filename, { type });
      return navigator.canShare({ files: [probe] });
    } catch (e) {
      return false;
    }
  }"""
)
internal external fun browserCanShareFile(filename: String, type: String): Boolean

/**
 * Shares [text] as a file named [filename], or downloads it where the browser won't share that
 * file. Synchronous up to the share call, so it keeps the tap's user activation. Resolves with a
 * sentence: empty when shared, "Share cancelled" when closed, or why it downloaded instead.
 */
@JsFun(
  """(filename, text, type) => {
    const save = () => {
      const objectUrl = URL.createObjectURL(new Blob([text], { type }));
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = filename;
      anchor.rel = 'noopener';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    };
    let file = null;
    try {
      file = new File([text], filename, { type });
    } catch (e) {}
    if (!file || typeof navigator.canShare !== 'function' || !navigator.canShare({ files: [file] })) {
      save();
      return Promise.resolve('');
    }
    return navigator.share({ files: [file], title: filename }).then(
      () => '',
      (error) => {
        if (error && error.name === 'AbortError') return 'Share cancelled';
        save();
        return 'Could not share, so it was downloaded instead';
      },
    );
  }"""
)
internal external fun shareOrDownloadTextPromise(
  filename: String,
  text: String,
  type: String,
): Promise<JsString>
