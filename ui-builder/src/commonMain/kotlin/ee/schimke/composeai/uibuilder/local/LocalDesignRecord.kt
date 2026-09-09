package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.RedoCommand
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UndoCommand
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The schema every stored design carries, so a future shape can refuse an older one by name. */
const val LOCAL_DESIGN_SCHEMA: String = "compose-ui-builder-local-design/v1"

/**
 * One design as this browser holds it: a seed document plus the commands accepted since.
 *
 * A log rather than only the current document, because undo is not a property of a document — the
 * reducer's compensation history is what makes "undo the thing I just did" mean anything, and the
 * reducer rebuilds that history exactly by being handed the same commands in the same order. So a
 * reload of a locally stored design is a replay, and the undo stack survives it, which is the whole
 * difference between this and writing `document.json` into a key.
 *
 * The cost is that the log grows, and the origin's quota does not. [LocalDesignStore] compacts:
 * past a byte budget the current document becomes the new [seed] and the log is dropped, which
 * trades the undo history older than that point for room to keep editing. That trade is stated here
 * because it is the one thing a person can notice — the Undo button stops before the beginning of
 * time — and it is preferred over the alternative, which is a design that cannot be saved.
 *
 * [seedSequence] keeps the durable sequence numbers a client sees monotonic across a compaction:
 * the snapshot the editor is handed names one, and a compaction that restarted the count at zero
 * would rewind a cursor the page had already passed.
 */
@Serializable
data class LocalDesignRecordV1(
  val schema: String = LOCAL_DESIGN_SCHEMA,
  val designId: String,
  val catalogSystemId: String,
  val seed: UiBuilderDocument,
  val seedSequence: Long = 0,
  val log: List<LocalSubmissionRecordV1> = emptyList(),
  val updatedAtEpochMillis: Long = 0,
  /**
   * Where this design was taken from, when it was taken offline rather than created here.
   *
   * Null for a design this browser made, and that is not a missing field — a design with no
   * ancestor has no fork point, and syncing it to a server is a create rather than a merge
   * ([`UI_BUILDER_DESIGN_PORTABILITY.md`](../../../../../../../../docs/design/UI_BUILDER_DESIGN_PORTABILITY.md)).
   */
  val origin: LocalDesignOriginV1? = null,
)

/**
 * The fork point: the design, the server, and the exact revision this copy diverged from.
 *
 * It exists only at the moment of the checkout and cannot be recovered afterwards, which is why it
 * is written then. Without it there are two documents and a guess; with it there is a common
 * ancestor, which is the whole difference between a merge and an overwrite.
 *
 * [documentDigest] is the server's own `documentHash` for that revision —
 * `sha256Hex(canonicalDocument(document))` — so the claim is checkable rather than asserted: a fork
 * point naming revision 41 of a design whose retained revision 41 hashes differently is a record
 * from another design's history, and a sync that trusted it would produce a document nobody
 * authored.
 */
@Serializable
data class LocalDesignOriginV1(
  /** The server's origin, as the page saw it: `https://preview.coo.ee`. */
  val server: String,
  val designId: String,
  val revision: Int,
  val sequence: Long,
  val documentDigest: String,
  val takenAtEpochMillis: Long,
)

/**
 * One accepted submission, in the reducer's own vocabulary rather than the wire's.
 *
 * The reducer is what replays these, and its command types are already `@Serializable`; storing the
 * protocol spelling instead would mean converting in both directions on every load and every save
 * for no gain, since nothing but this page ever reads these bytes.
 */
@Serializable
sealed interface LocalSubmissionRecordV1 {
  @Serializable
  @SerialName("batch")
  data class Batch(val command: DesignCommand) : LocalSubmissionRecordV1

  @Serializable
  @SerialName("undo")
  data class Undo(val command: UndoCommand) : LocalSubmissionRecordV1

  @Serializable
  @SerialName("redo")
  data class Redo(val command: RedoCommand) : LocalSubmissionRecordV1
}
