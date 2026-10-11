package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.unopenableDesignGuidance
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sentence a reader gets when a design will not open, tested apart from layout: the claim is
 * whether they're told to retry something that can't work.
 */
class UnopenableDesignGuidanceTest {

  /**
   * A design pinned to a catalog the host no longer serves: reloading won't help, so the guidance
   * points at the validated recovery action.
   */
  @Test
  fun `an unavailable catalog points the reader at exact pin recovery`() {
    val guidance = unopenableDesignGuidance(ServiceErrorCodeV1.CATALOG_UNAVAILABLE)

    assertTrue(guidance.contains("no longer serves"), guidance)
    assertTrue(guidance.contains("validates against the current catalog"), guidance)
    assertTrue(guidance.contains("new exact pin"), guidance)
    assertFalse(guidance.contains("Reloading"), guidance)
  }

  /** A private design must not be told apart from a missing one by the guidance either. */
  @Test
  fun `forbidden and unauthorized give the same answer`() {
    assertEquals(
      unopenableDesignGuidance(ServiceErrorCodeV1.FORBIDDEN),
      unopenableDesignGuidance(ServiceErrorCodeV1.UNAUTHORIZED),
    )
  }

  /** Only codes that genuinely can't be retried say so; the fallback still offers a reload. */
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
