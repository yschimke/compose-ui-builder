package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.editor.UiBuilderBrowserDesign
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1

/**
 * The rules for a caller who may read a server's designs but not write them (#342), kept apart from
 * the page that follows them so they can be asserted without a browser.
 *
 * The server is the only enforcement point. Everything here decides what to *offer*: a caller the
 * server would refuse is not sent to it, and a refusal that arrives anyway loses nothing.
 */

/** Where an edit made in the editor goes. */
enum class EditorEditRoute {
  /** Queued for the server this design lives on, as every edit was before #342. */
  Server,

  /**
   * Copied into this browser under a new id first, then applied to the copy. Nothing is sent to the
   * server, which would refuse it.
   */
  BrowserCopy,
}

/**
 * Which way an edit goes.
 *
 * [keptInBrowser] is a design this browser already holds, whose "server" is the local service: its
 * edits never leave the page. [revisionPinned] is a page showing history, which takes no edits of
 * its own — and a copy made from it would silently become a copy of history.
 */
fun editorEditRoute(
  keptInBrowser: Boolean,
  canWrite: Boolean,
  revisionPinned: Boolean,
): EditorEditRoute =
  if (!keptInBrowser && !canWrite && !revisionPinned) EditorEditRoute.BrowserCopy
  else EditorEditRoute.Server

/**
 * Whether a refused save was refused for who is asking, rather than for what was asked.
 *
 * Such an edit is still a good edit: the session expired, or the caller never could write here, or
 * the design's owner took write access away. It becomes a browser copy rather than a lost change.
 * Every other refusal is about the edit itself, and the reducer's reconciliation already answers
 * those.
 */
fun isWriteRefusal(code: ServiceErrorCodeV1): Boolean =
  code == ServiceErrorCodeV1.UNAUTHORIZED || code == ServiceErrorCodeV1.FORBIDDEN

/**
 * This browser's designs, as the home screen's **In this browser** panel lists them.
 *
 * [updatedLabel] formats an epoch millisecond, which is the browser's question (its locale, its
 * time zone), not this module's.
 */
fun browserHomeDesigns(
  summaries: List<LocalDesignSummary>,
  updatedLabel: (Long) -> String,
): List<UiBuilderBrowserDesign> = summaries.map { summary ->
  UiBuilderBrowserDesign(
    designId = summary.designId,
    title = summary.title,
    catalogSystemId = summary.catalogSystemId,
    updatedLabel =
      if (summary.corrupted || summary.updatedAtEpochMillis <= 0L) ""
      else "edited ${updatedLabel(summary.updatedAtEpochMillis)}",
    copiedFrom = summary.copiedFrom?.designId,
    unreadable = summary.corrupted,
  )
}

/**
 * What to tell a person about this browser's room for designs, or null when there is nothing worth
 * saying.
 *
 * Browsers give an origin about five megabytes of `localStorage` and count it in UTF-16 code units,
 * some of them two bytes each, so [budgetChars] is the conservative half of that. Past
 * [warnFraction] of it the next edit may be the one that is refused, and the way out — download a
 * copy, then delete it — is worth knowing before that happens rather than after.
 */
fun localStorageNotice(
  summaries: List<LocalDesignSummary>,
  budgetChars: Int = LOCAL_STORAGE_BUDGET_CHARS,
  warnFraction: Double = 0.8,
  estimate: BrowserStorageEstimate? = null,
): String? {
  // Kept in IndexedDB, the designs are not what fills the origin first: the browser's own estimate
  // of this origin's usage against its quota is the number that predicts a refusal.
  if (estimate != null && estimate.indexedDb) {
    if (estimate.usageBytes < estimate.quotaBytes * warnFraction) return null
    return "Browser storage is nearly full (${estimate.summary()}). Download a copy you want to " +
      "keep, then delete it from this browser to make room."
  }
  val used = summaries.sumOf { it.storedBytes.toLong() }
  if (used < budgetChars * warnFraction) return null
  val percent = (used * 100 / budgetChars).coerceAtMost(100)
  return "Browser storage is nearly full ($percent% used by designs kept here" +
    (estimate?.let { "; ${it.summary()}" } ?: "") +
    "). Download a copy you want to keep, then delete it from this browser to make room."
}

/**
 * What `navigator.storage.estimate()` and `persisted()` say about this origin.
 *
 * [indexedDb] is where the designs are, which decides which number matters: in `localStorage` the
 * five-megabyte area runs out long before the origin's quota does, so the designs' own size is the
 * warning; in IndexedDB the origin's quota is the only limit.
 */
data class BrowserStorageEstimate(
  val usageBytes: Long,
  val quotaBytes: Long,
  val persisted: Boolean,
  val indexedDb: Boolean,
) {
  /** "3.2 MB of 1,024 MB this browser allows this site", plus whether it may clear it. */
  fun summary(): String =
    "${megabytes(usageBytes)} of ${megabytes(quotaBytes)} this browser allows this site" +
      if (persisted) "" else ", which it may clear when space runs short"

  private fun megabytes(bytes: Long): String {
    val tenths = (bytes * 10 + MEGABYTE / 2) / MEGABYTE
    return "${tenths / 10}.${tenths % 10} MB"
  }

  private companion object {
    const val MEGABYTE = 1_048_576L
  }
}

/** Half of the ~5 MB most browsers give an origin, in characters. See [localStorageNotice]. */
const val LOCAL_STORAGE_BUDGET_CHARS: Int = 2_500_000

/**
 * A browser design as the server's create route takes it: `PUT /api/ui-builder/v1/designs/{id}`
 * with `If-None-Match: *`.
 *
 * At revision 0, because a create starts history there and the route refuses anything else — the
 * revisions this browser counted are its own, and mean nothing to the server. Without a `home`,
 * because the server stamps its own; a copy never had one to carry. Everything else is the design
 * as it stands in this browser.
 */
fun publishableDocument(document: DesignDocumentV1): DesignDocumentV1 =
  document.copy(revision = 0, home = null)

/**
 * The sentence for a publish the server answered with [status] and [body], or null when it created
 * the design.
 */
fun publishRefusal(designId: String, status: Int, body: String): String? =
  when (status) {
    in 200..299 -> null
    // `If-None-Match: *` failed: the id is somebody's design already. A create never replaces.
    412 ->
      "The server already has a design called $designId. Publishing never replaces one; " +
        "duplicate this design under a new name and publish that."
    401,
    403 -> "The server will not take new designs from this account: ${body.ifBlank { "refused" }}"
    else ->
      "The server refused to publish $designId (HTTP $status): ${body.ifBlank { "no reason given" }}"
  }
