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
}
