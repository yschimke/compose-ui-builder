package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sentence a reader gets when a design will not open.
 *
 * Asserted apart from the composable because the claim worth pinning is not how it is laid out: it
 * is whether the reader is told to retry something that cannot work. Before this screen existed the
 * editor drew nothing at all for these cases, so every one of them looked identical — a white page
 * — whether the design was missing, private, or pinned to a catalog the host had stopped serving.
 */
class UnopenableDesignGuidanceTest {

  /**
   * The case that produced the bug report: 36 designs on `preview.coo.ee` after the catalog source
   * flip, every one of them opening to nothing.
   *
   * Reloading is precisely what does not help here — the pin names a source this deployment does
   * not serve, and no number of retries changes that — so the guidance has to say so rather than
   * offering the reflex.
   */
  @Test
  fun `an unavailable catalog tells the reader not to retry`() {
    val guidance = unopenableDesignGuidance(ServiceErrorCodeV1.CATALOG_UNAVAILABLE)

    assertTrue(guidance.contains("no longer serves"), guidance)
    assertTrue(guidance.contains("Reloading will not change that"), guidance)
    assertTrue(guidance.contains("operator"), guidance)
  }

  /** A private design must not be told apart from a missing one by the guidance either. */
  @Test
  fun `forbidden and unauthorized give the same answer`() {
    assertEquals(
      unopenableDesignGuidance(ServiceErrorCodeV1.FORBIDDEN),
      unopenableDesignGuidance(ServiceErrorCodeV1.UNAUTHORIZED),
    )
  }

  /**
   * Only the codes that genuinely cannot be retried say so.
   *
   * The fallback exists for codes this screen has no specific answer for, and for those a reload is
   * a reasonable first move — so it must keep offering it.
   */
  @Test
  fun `the fallback still offers a reload`() {
    assertTrue(unopenableDesignGuidance(null).contains("Reloading the page may help"))
    assertTrue(
      unopenableDesignGuidance(ServiceErrorCodeV1.BAD_REQUEST)
        .contains("Reloading the page may help")
    )
  }

  /** Every code answers with something, and nothing answers with the enum name. */
  @Test
  fun `every code has a human sentence`() {
    ServiceErrorCodeV1.entries.forEach { code ->
      val guidance = unopenableDesignGuidance(code)
      assertTrue(guidance.isNotBlank(), "no guidance for $code")
      assertFalse(guidance.contains(code.name), "guidance for $code leaks the enum name: $guidance")
    }
  }
}
