package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1

/**
 * One design as an operator sees it: every design on the host, regardless of who owns it or who has
 * been granted access.
 *
 * Deliberately not [ee.schimke.composeai.uibuilder.protocol.DesignListItemV1]: that shape carries
 * the *requester's* access and cannot be built for an actor the design was never shared with, which
 * is precisely the position an administrator is in.
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
 * The operator's view of a UI-builder host.
 *
 * Separate from [UiBuilderServicePort] because nothing here is an actor operation: there is no ACL
 * check, no capability, and no protocol request shape (`ui-builder-protocol` is a published
 * contract and has no delete). It is reached only through an admin-token route.
 */
public interface UiBuilderAdminPort {
  /** Every design on the host, oldest-created first. */
  public fun adminListDesigns(): List<UiBuilderAdminDesignSummary>

  /**
   * Remove a design and everything the service holds for it: its history, snapshots, presence and
   * subscribers (whose streams are closed). Durable before it returns. False when no such design
   * exists.
   */
  public fun adminDeleteDesign(designId: String): Boolean

  /**
   * Stored designs this build cannot serve, by id, each with the reason.
   *
   * `diagnostics()` counts them; this names them. A count tells an operator that something is being
   * held back and nothing about which design or why, which is the difference between knowing a host
   * has a problem and being able to act on it — repair the catalog the design pins and restart, or
   * retire it with [adminDeleteDesign].
   *
   * Deliberately a method beside [adminListDesigns] rather than a field on
   * [UiBuilderAdminDesignSummary]: this artifact is published and its ABI is checked, and a new
   * constructor parameter on a data class changes `copy` and every `componentN` — binary-breaking
   * for a consumer compiled against an earlier release. A defaulted method is additive.
   */
  public fun adminUnusableDesigns(): Map<String, String> = emptyMap()

  /**
   * Of those, the designs whose stored **files** could not be read.
   *
   * The two kinds of unusable design recover differently and the difference is not cosmetic. A
   * design the catalog outgrew has a document the host read fine: it can be downloaded, edited and
   * put back. A design whose files would not decode has no document to hand anybody — download and
   * repair both answer "not found" — and retiring it is the only move. Telling an operator to
   * download a design that cannot be read sends them somewhere there is nothing, during an
   * incident, which is the worst moment to be sent there.
   *
   * Defaulted empty for the same reason [adminUnusableDesigns] is: a new method is additive where a
   * changed return type would be binary-breaking.
   */
  public fun adminUnreadableDesigns(): Set<String> = emptySet()

  /**
   * The stored design document as JSON, or null when no design has this id.
   *
   * The recovery path for a design [adminUnusableDesigns] names, and the reason it is here rather
   * than on [UiBuilderServicePort]: the ordinary export renders through the catalog, so a design
   * held back *because* its catalog cannot serve it is exactly the one that export cannot reach.
   * Without this an operator's only move on a quarantined design is [adminDeleteDesign], which
   * loses the document — and a design is generally quarantined by a rule that changed under it, not
   * by being worthless. So: take a copy, repair it against the current rules, create it again.
   *
   * Deliberately the document alone, and deliberately not gated on the design being servable. It
   * reads what is already on disk and interprets none of it, which is what makes it usable in the
   * state it exists for. Access grants, history and presence are not included: they are the
   * service's bookkeeping rather than the operator's content, and the owner of the repaired copy is
   * whoever creates it.
   */
  public fun adminDesignDocument(designId: String): String? = null

  /**
   * Put a repaired document back in place of a quarantined one.
   *
   * The other half of [adminDesignDocument], and what turns quarantine into a workflow rather than
   * a waiting room: copy the design out, edit it to satisfy the rule that changed under it, put it
   * back, and the host serves it again without a restart and without anything else on the host
   * being touched. The candidate is checked against the same conditions that held the original
   * back, so a repair that does not repair is refused with what is still wrong rather than stored.
   *
   * Only a design [adminUnusableDesigns] names may be repaired this way. A design the host serves
   * is edited through the service, with an actor, authorization and a sequence; this is the door
   * that exists because that one is closed.
   */
  public fun adminRepairDesign(designId: String, documentJson: String): UiBuilderAdminRepair =
    UiBuilderAdminRepair.Rejected("design repair is not supported by this runtime")
}

/** What [UiBuilderAdminPort.adminRepairDesign] did, or why it did nothing. */
public sealed interface UiBuilderAdminRepair {
  /**
   * The design is stored and servable again, at [revision]. [previousReason] is what had held it
   * back, kept so the operator's log says what was repaired and not merely that something was.
   */
  public data class Repaired(
    val designId: String,
    val revision: Long,
    val previousReason: String,
  ) : UiBuilderAdminRepair

  /** No design on this host has this id. */
  public data class NotFound(val designId: String) : UiBuilderAdminRepair

  /**
   * Nothing was written. Either the candidate is not a readable design, or it is one the host still
   * cannot serve — [reason] is the remaining failure, in the same words the quarantine uses.
   */
  public data class Rejected(val reason: String) : UiBuilderAdminRepair
}
