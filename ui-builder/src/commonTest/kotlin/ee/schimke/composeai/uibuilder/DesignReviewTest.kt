package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.DesignReview
import ee.schimke.composeai.uibuilder.editor.DesignReviewDecision
import ee.schimke.composeai.uibuilder.editor.DesignReviewVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesignReviewTest {
  private fun decision(revision: Long, verdict: DesignReviewVerdict, who: String = "alice") =
    DesignReviewDecision(revision = revision, verdict = verdict, decidedBy = who)

  @Test
  fun `the latest verdict on a revision is the newest one on that revision`() {
    val review =
      DesignReview(
        listOf(
          decision(3, DesignReviewVerdict.Reject),
          decision(3, DesignReviewVerdict.Approve, who = "bob"),
          decision(4, DesignReviewVerdict.Reject),
        )
      )
    assertEquals("bob", review.latestFor(3)?.decidedBy)
    assertEquals(DesignReviewVerdict.Reject, review.latestFor(4)?.verdict)
    assertNull(review.latestFor(5))
    assertEquals(4, review.latest?.revision)
  }

  @Test
  fun `the name shown is the display name, or the account when there is none`() {
    assertEquals("alice", decision(1, DesignReviewVerdict.Approve).who)
    assertEquals("Alice", decision(1, DesignReviewVerdict.Approve).copy(displayName = "Alice").who)
  }

  @Test
  fun `only known verdicts are read off the wire`() {
    assertEquals(DesignReviewVerdict.Approve, DesignReviewVerdict.ofWire("approve"))
    assertNull(DesignReviewVerdict.ofWire("maybe"))
  }
}
