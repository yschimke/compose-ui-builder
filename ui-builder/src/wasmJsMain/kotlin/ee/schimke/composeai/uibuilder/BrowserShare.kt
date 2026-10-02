@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import kotlin.js.Promise

/**
 * The Web Share half of getting a file out of the editor on a phone.
 *
 * A download on a phone browser lands in a Downloads folder that most people never open, and the
 * clipboard cannot carry a file at all; the share sheet is where a file *goes* on a phone — to
 * Files, to a chat, to Figma's app. So where the browser can share files, the editor offers that,
 * and the download stays behind it as the fallback for everything the sheet refuses.
 *
 * "Can share files" is asked twice. Once up front to decide whether to offer the row at all — and
 * only on a coarse pointer, because desktop Chrome and Safari can share files too, and a desktop
 * keeps exactly the menu it had. Then again on the real file, because browsers allow only some
 * types: Chrome shares an SVG or a PNG but not a `.json`, and that file then simply downloads.
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
 * Shares [text] as a file named [filename], or downloads it where the browser will not share that
 * file. Synchronous up to the share call on purpose: it runs inside the tap, and a share sheet
 * needs that tap's user activation, which an `await` before it could spend.
 *
 * Resolves with a sentence: empty when the sheet took the file, "Share cancelled" when the person
 * closed it, or why it fell back to a download.
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
