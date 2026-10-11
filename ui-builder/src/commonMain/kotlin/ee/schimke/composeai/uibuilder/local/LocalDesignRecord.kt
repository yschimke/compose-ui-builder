package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.RedoCommand
import ee.schimke.composeai.uibuilder.UndoCommand
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The schema every stored design carries, so a future shape can refuse an older one by name. */
const val LOCAL_DESIGN_SCHEMA: String = "compose-ui-builder-local-design/v1"

/**
 * One design as this browser holds it: a seed document plus the commands accepted since. A log, so
 * a reload replays it and rebuilds the reducer's undo history.
 *
 * [LocalDesignStore] compacts past a byte budget: the current document becomes the new [seed] and
 * the log is dropped, trading old undo history for room to keep saving.
 *
 * [seedSequence] keeps durable sequence numbers monotonic across a compaction.
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
   * When this browser created the design, stamped into snapshots like the hosted service does. Null
   * for older records.
   */
  val createdAtEpochMillis: Long? = null,
  /**
   * Where this design was taken from, when taken offline rather than created here. Null means no
   * fork point: syncing is a create, not a merge
   * ([`UI_BUILDER_DESIGN_PORTABILITY.md`](../../../../../../../../docs/design/UI_BUILDER_DESIGN_PORTABILITY.md)).
   */
  val origin: LocalDesignOriginV1? = null,
  /**
   * The server design this was copied from by a caller without write access: provenance only, not
   * an [origin] (which would imply syncing back). Publishing a copy is a create.
   */
  val copiedFrom: LocalDesignCopyV1? = null,
)

/** Where a browser copy came from: the design, the server, and the revision that was copied. */
@Serializable
data class LocalDesignCopyV1(
  /** The server's origin, as the page saw it: `https://preview.coo.ee`. */
  val server: String,
  val designId: String,
  val revision: Int,
  val documentDigest: String,
  val copiedAtEpochMillis: Long,
)

/**
 * The fork point: the design, the server, and the exact revision this copy diverged from, recorded
 * at checkout (it can't be recovered later). [documentDigest] is the server's `documentHash` for
 * that revision (`sha256Hex(canonicalDocument(document))`), so a sync can verify the fork point
 * belongs to this design's history.
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
 * One accepted submission, in the reducer's own (already `@Serializable`) vocabulary rather than
 * the wire's; only this page reads these bytes.
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
