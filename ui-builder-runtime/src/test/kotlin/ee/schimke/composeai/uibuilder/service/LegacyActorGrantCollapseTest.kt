package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * A stored ACL holding two case-variants of one GitHub login resolves to the later grant.
 *
 * Folding a login on the way in fixes grants written since that started working. A record persisted
 * before it can hold `github:Alice` AND `github:alice`, because the replacement it went through
 * compared exact strings — a mixed-case EDITOR share followed by a lowercase VIEWER downgrade left
 * both entries. Authorization then asked `any` of that list, which is the UNION of the two, so the
 * downgrade did not take WRITE away; and the listing answered with `firstOrNull`, so the role a
 * reader was shown could disagree with the one being enforced.
 *
 * The service can no longer create such a record, which is why this is built by hand: it is a
 * migration question, not a behaviour of the current write path.
 */
class LegacyActorGrantCollapseTest {

  @Test
  fun `two case variants of one login collapse to the last grant written`() {
    val access =
      access(
        grant("github:Alice", DesignAccessRoleV1.EDITOR, DesignAccessActionV1.entries, 1_000),
        grant("github:alice", DesignAccessRoleV1.VIEWER, listOf(DesignAccessActionV1.READ), 2_000),
      )

    val effective = access.effectiveGrants().single()

    // The downgrade is the later grant, so it is the one that governs — not the union of the two,
    // which is what left WRITE and EXPORT reachable from a stale mixed-case EDITOR share. Read off
    // the grant's own timestamp rather than its position in the list.
    assertEquals(DesignAccessRoleV1.VIEWER, effective.role)
    assertEquals(listOf(DesignAccessActionV1.READ), effective.allowedActions)
  }

  @Test
  fun `the surviving grant is the same one the listing reports`() {
    val access =
      access(
        grant("github:alice", DesignAccessRoleV1.VIEWER, listOf(DesignAccessActionV1.READ), 1_000),
        grant("github:ALICE", DesignAccessRoleV1.EDITOR, DesignAccessActionV1.entries, 2_000),
      )

    // One grant, whichever question is asked of it — the disagreement between `any` and
    // `firstOrNull` is only possible while two entries survive.
    assertEquals(1, access.effectiveGrants().size)
    assertEquals(DesignAccessRoleV1.EDITOR, access.effectiveGrants().single().role)
  }

  @Test
  fun `a non-github actor is not folded`() {
    // Case is meaningful in an agent fingerprint: folding one would make two distinct agents equal,
    // which would be a widening of access rather than a narrowing.
    val access =
      access(
        grant("agent:ABCD", DesignAccessRoleV1.VIEWER, listOf(DesignAccessActionV1.READ), 1_000),
        grant("agent:abcd", DesignAccessRoleV1.EDITOR, DesignAccessActionV1.entries, 2_000),
      )

    assertEquals(2, access.effectiveGrants().size)
  }

  @Test
  fun `an ordinary record is returned unchanged`() {
    // The common case allocates nothing: every record already has one grant per actor.
    val access =
      access(
        grant("github:alice", DesignAccessRoleV1.VIEWER, listOf(DesignAccessActionV1.READ)),
        grant("github:bob", DesignAccessRoleV1.EDITOR, DesignAccessActionV1.entries),
      )

    assertSame(access.actorGrants, access.effectiveGrants())
  }

  private fun access(vararg grants: DesignActorGrantV1) =
    DesignAccessControlV1(
      ownerActorId = "github:owner",
      accessRevision = 1,
      actorGrants = grants.toList(),
    )

  /** [grantedAtEpochMillis] is the rule the collapse reads, so each caller states it. */
  private fun grant(
    actorId: String,
    role: DesignAccessRoleV1,
    actions: List<DesignAccessActionV1>,
    grantedAtEpochMillis: Long = 0,
  ) = DesignActorGrantV1(actorId, role, actions, "github:owner", grantedAtEpochMillis)
}
