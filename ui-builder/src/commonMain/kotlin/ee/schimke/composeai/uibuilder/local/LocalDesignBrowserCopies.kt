package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.editor.UiBuilderBrowserDesign
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1

/**
 * The rules for a caller who may read a server's designs but not write them (see #342), kept apart
 * from the page so they're testable without a browser. The server enforces; this only decides what
 * to offer.
 */

/** Where an edit made in the editor goes. */
enum class EditorEditRoute {
  /** Queued for the server this design lives on. */
  Server,

  /**
   * Copied into this browser under a new id first, then applied to the copy; nothing is sent to the
   * server.
   */
  BrowserCopy,
}

/**
 * Which way an edit goes. [keptInBrowser]: a browser-held design, whose service is local.
 * [revisionPinned]: a history page, which takes no edits (a copy would be a copy of history).
 */
fun editorEditRoute(
  keptInBrowser: Boolean,
  canWrite: Boolean,
  revisionPinned: Boolean,
): EditorEditRoute =
  if (!keptInBrowser && !canWrite && !revisionPinned) EditorEditRoute.BrowserCopy
  else EditorEditRoute.Server

/**
 * Whether a refused save was refused for who is asking (expired session, no or revoked write
 * access) rather than for the edit itself. Such edits become a browser copy; other refusals are
 * reconciled by the reducer.
 */
fun isWriteRefusal(code: ServiceErrorCodeV1): Boolean =
  code == ServiceErrorCodeV1.UNAUTHORIZED || code == ServiceErrorCodeV1.FORBIDDEN

/**
 * This browser's designs, as the home screen's **In this browser** panel lists them. [updatedLabel]
 * formats epoch millis in the browser's locale and time zone.
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
 * What to tell a person about this browser's room for designs, or null. `localStorage` is ~5 MB per
 * origin counted in UTF-16 units, so [budgetChars] is the conservative half; past [warnFraction]
 * the person is told to download and delete before an edit is refused.
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
 * What `navigator.storage.estimate()` and `persisted()` say about this origin. [indexedDb] decides
 * which limit matters: the design size for `localStorage`, the origin quota for IndexedDB.
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
 * A browser design as the server's create route takes it (`PUT /api/ui-builder/v1/designs/{id}`
 * with `If-None-Match: *`): at revision 0 (the route requires it; local revisions mean nothing to
 * the server) and without a `home` (the server stamps its own).
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
