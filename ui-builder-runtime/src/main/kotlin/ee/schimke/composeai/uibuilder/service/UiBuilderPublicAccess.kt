package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1

/**
 * Public designs: a design any visitor may open, read-only.
 *
 * Expressed in the access list the protocol already has rather than as a new field on it — a
 * [VIEWER][ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1.VIEWER] grant whose actor is
 * [ANYONE_ACTOR_ID]. So making a design public or private is an ordinary grant or revoke by its
 * owner, audited and revisioned like any other, and a host that knows nothing of this keeps every
 * design exactly as private as it was.
 *
 * The grant only ever lets people look: the service refuses one that carries anything beyond
 * [PUBLIC_ACTIONS]. A design that is public still belongs to its owner, and is listed only to the
 * owner and the people it was shared with by name — being readable by everyone does not put it in
 * everyone's file manager.
 */
public object UiBuilderPublicAccess {
  /** The grant target that means "anyone who can reach this design", signed in or not. */
  public const val ANYONE_ACTOR_ID: String = "public:anyone"

  /** What a public grant may carry: looking, and taking the Kotlin of what you are looking at. */
  public val PUBLIC_ACTIONS: List<DesignAccessActionV1> =
    listOf(DesignAccessActionV1.READ, DesignAccessActionV1.EXPORT)
}
