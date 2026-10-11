package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1

/**
 * Public designs: any visitor may open them read-only, expressed as a
 * [VIEWER][ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1.VIEWER] grant to
 * [ANYONE_ACTOR_ID] — an ordinary, audited grant or revoke by the owner. The service refuses such a
 * grant beyond [PUBLIC_ACTIONS], and public designs are not listed to everyone.
 */
public object UiBuilderPublicAccess {
  /** The grant target that means "anyone who can reach this design", signed in or not. */
  public const val ANYONE_ACTOR_ID: String = "public:anyone"

  /** What a public grant may carry: looking, and taking the Kotlin of what you are looking at. */
  public val PUBLIC_ACTIONS: List<DesignAccessActionV1> =
    listOf(DesignAccessActionV1.READ, DesignAccessActionV1.EXPORT)
}
