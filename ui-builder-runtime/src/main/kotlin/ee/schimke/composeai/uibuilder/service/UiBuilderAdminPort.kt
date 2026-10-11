package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1

/**
 * One design as an operator sees it, regardless of ownership or grants. Not
 * [ee.schimke.composeai.uibuilder.protocol.DesignListItemV1], which carries the requester's access.
 */
public data class UiBuilderAdminDesignSummary(
  val designId: String,
  val title: String,
  val revision: Long,
  val catalogPin: CatalogReferenceV1,
  val ownerActorId: String,
  /** How many actors beside the owner hold a grant on the design. */
  val collaborators: Int,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
  /** Live subscribers on the design right now (open editors, agent streams). */
  val activeSubscribers: Int,
)

/**
 * The operator's view of a UI-builder host, reached only through an admin-token route; not an actor
 * operation, so no ACL, capability or protocol shape. New methods get defaults to stay
 * binary-compatible.
 */
public interface UiBuilderAdminPort {
  /** Every design on the host, oldest-created first. */
  public fun adminListDesigns(): List<UiBuilderAdminDesignSummary>

  /**
   * One design by id, or null. The default scans [adminListDesigns]; stores keyed by id override
   * it.
   */
  public fun adminDesignSummary(designId: String): UiBuilderAdminDesignSummary? =
    adminListDesigns().firstOrNull { it.designId == designId }

  /**
   * Remove a design and everything held for it (history, snapshots, presence, subscribers), at once
   * and for good, bypassing the trash; a design already in the trash is emptied out of it. Durable
   * before it returns; false when no such design exists.
   */
  public fun adminDeleteDesign(designId: String): Boolean

  /** Every design in the trash, whoever deleted it, most recently deleted first. */
  public fun adminListTrash(): List<UiBuilderTrashedDesign> = emptyList()

  /**
   * Permanently remove every trashed design whose retention has passed, returning their ids so the
   * host can drop what it keeps beside each design (comments, links, overlays). The runtime runs no
   * timer: a host calls this on a schedule. See `UI_BUILDER_TRASH.md`.
   */
  public fun purgeExpiredTrash(): List<String> = emptyList()

  /**
   * Stored designs this build cannot serve, by id, with the reason — so an operator can repair the
   * pinned catalog or retire the design with [adminDeleteDesign].
   */
  public fun adminUnusableDesigns(): Map<String, String> = emptyMap()

  /**
   * Stored designs that still open but carry properties their pinned catalog no longer declares.
   * Unlike [adminUnusableDesigns] they stay editable and export without the stale properties.
   */
  public fun adminDegradedDesigns(): Map<String, String> = emptyMap()

  /**
   * The subset of [adminUnusableDesigns] whose stored files could not be read. These have no
   * document to download or repair; retiring them is the only option.
   */
  public fun adminUnreadableDesigns(): Set<String> = emptySet()

  /**
   * The stored design document as JSON, or null when no design has this id. The recovery path for a
   * quarantined design, which the catalog-rendered export cannot reach. Reads the document as
   * stored without interpreting it; grants, history and presence are not included.
   */
  public fun adminDesignDocument(designId: String): String? = null

  /**
   * Put a repaired document back in place of a design [adminUnusableDesigns] names, without a
   * restart. The candidate is checked against the same conditions that held the original back, and
   * refused with what is still wrong.
   */
  public fun adminRepairDesign(designId: String, documentJson: String): UiBuilderAdminRepair =
    UiBuilderAdminRepair.Rejected("design repair is not supported by this runtime")
}

/** What [UiBuilderAdminPort.adminRepairDesign] did, or why it did nothing. */
public sealed interface UiBuilderAdminRepair {
  /** The design is servable again at [revision]; [previousReason] is what had held it back. */
  public data class Repaired(
    val designId: String,
    val revision: Long,
    val previousReason: String,
  ) : UiBuilderAdminRepair

  /** No design on this host has this id. */
  public data class NotFound(val designId: String) : UiBuilderAdminRepair

  /**
   * Nothing was written: the candidate is unreadable or still unservable, and [reason] says why in
   * the quarantine's words.
   */
  public data class Rejected(val reason: String) : UiBuilderAdminRepair
}
