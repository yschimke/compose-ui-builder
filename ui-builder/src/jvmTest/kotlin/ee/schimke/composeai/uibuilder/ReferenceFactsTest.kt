package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.reference.ReferenceFacts
import ee.schimke.composeai.uibuilder.reference.ReferenceFit
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import ee.schimke.composeai.uibuilder.reference.ReferenceKind
import ee.schimke.composeai.uibuilder.reference.declaredDensityFromName
import ee.schimke.composeai.uibuilder.reference.facts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a picture is, against a 360 × 800 dp frame that renders at 3×. */
class ReferenceFactsTest {
  private fun facts(width: Int, height: Int, name: String = "mock.png") =
    ReferenceImage("id", name, "image/png", "AAAA", width, height).facts(360f, 800f, 3f)

  @Test
  fun `a screenshot of the same screen is read at the device's density`() {
    val facts = facts(1080, 2400)
    assertEquals(ReferenceKind.Screen, facts.kind)
    assertEquals(3f, facts.density)
    assertEquals("xxhdpi", facts.densityBucket)
    assertEquals(360f, facts.widthDp)
    assertTrue(facts.sameDeviceDensity)
    assertEquals(ReferenceFit.Contain, facts.recommendedFit)
    assertTrue(facts.pixelComparable(ReferenceFit.Contain))
  }

  @Test
  fun `a measured density near a bucket snaps to it, one nowhere near is kept`() {
    assertEquals(2.625f, ReferenceFacts.snapDensity(2.6247f))
    assertEquals(1.234f, ReferenceFacts.snapDensity(1.2341f))
  }

  @Test
  fun `a capture taller than the frame is fitted across its width`() {
    val facts = facts(1080, 2520)
    assertEquals(ReferenceKind.TallScreen, facts.kind)
    assertEquals(ReferenceFit.Width, facts.recommendedFit)
    assertFalse(facts.pixelComparable(ReferenceFit.Contain))
    assertTrue(facts.pixelComparable(ReferenceFit.Width))
  }

  @Test
  fun `a declared crop is a region, comparable only at its actual size`() {
    val facts = facts(984, 168, name = "Button@3x.png")
    assertEquals(3f, facts.declaredDensity)
    assertEquals(ReferenceKind.Region, facts.kind)
    assertEquals(328f, facts.widthDp)
    assertEquals(56f, facts.heightDp)
    assertEquals(ReferenceFit.Actual, facts.recommendedFit)
    assertFalse(facts.pixelComparable(ReferenceFit.Contain))
    assertTrue(facts.pixelComparable(ReferenceFit.Actual))
    assertTrue("328 × 56 dp" in facts.advice(ReferenceFit.Contain))
  }

  @Test
  fun `a landscape mock against a portrait frame is compared by eye only`() {
    val facts = facts(2400, 1080)
    assertEquals(ReferenceKind.Mismatched, facts.kind)
    ReferenceFit.entries.forEach { assertFalse(facts.pixelComparable(it)) }
  }

  @Test
  fun `a picture with no size says so`() {
    val facts = facts(0, 0)
    assertEquals(ReferenceKind.Unknown, facts.kind)
    assertNull(facts.recommendedFit)
  }

  @Test
  fun `only the conventional suffix declares a density`() {
    assertEquals(2f, declaredDensityFromName("card@2x.png"))
    assertEquals(3f, declaredDensityFromName("Frame 12 @3x.PNG"))
    assertEquals(1.5f, declaredDensityFromName("icon@1.5x.webp"))
    assertNull(declaredDensityFromName("2x-card.png"))
    assertNull(declaredDensityFromName("card@2x-final.png"))
  }

  @Test
  fun `an svg declares nothing, whatever its name`() {
    val svg = ReferenceImage("id", "frame@2x.svg", ReferenceImage.SVG_MEDIA_TYPE, "AAAA", 720, 1600)
    assertNull(svg.facts(360f, 800f, 3f).declaredDensity)
  }
}
