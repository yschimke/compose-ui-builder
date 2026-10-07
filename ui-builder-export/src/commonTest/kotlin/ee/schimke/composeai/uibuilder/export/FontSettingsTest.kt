package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FontSettingsTest {
  @Test
  fun variationsParseEveryCssSpellingAndFormatCanonically() {
    val axes =
      FontSettings.parseVariations("\"wght\" 650, 'wdth' 87.5, GRAD -50, opsz=14, bad, 'wght' 700")
    assertEquals(
      listOf(
        FontSettings.Axis("wght", 700f),
        FontSettings.Axis("wdth", 87.5f),
        FontSettings.Axis("GRAD", -50f),
        FontSettings.Axis("opsz", 14f),
      ),
      axes,
    )
    assertEquals(
      "wght 700, wdth 87.5, GRAD -50, opsz 14",
      FontSettings.formatVariations(axes),
    )
  }

  @Test
  fun featuresReadOnOffAndAlternates() {
    val features = FontSettings.parseFeatures("'tnum', \"liga\" off, ss01 on, 'salt' 2, 'kern' -1")
    assertEquals(
      listOf(
        FontSettings.Feature("tnum", 1),
        FontSettings.Feature("liga", 0),
        FontSettings.Feature("ss01", 1),
        FontSettings.Feature("salt", 2),
      ),
      features,
    )
    assertEquals("tnum, liga 0, ss01, salt 2", FontSettings.formatFeatures(features))
    assertEquals(emptyList(), FontSettings.parseFeatures(null))
  }

  @Test
  fun familyFollowsTheRoleTheThemeHostAndThePlatform() {
    val host = mapOf("themeDisplayTypeface" to "Michroma", "themeTextStyle" to "labelLarge")::get
    assertEquals("Michroma", FontSettings.familyFor("displaySmall", host, "Inter", wear = false))
    // No role of its own: the host's ambient role, which has no typeface, so the document's.
    assertEquals("Inter", FontSettings.familyFor(null, host, "Inter", wear = false))
    assertNull(FontSettings.familyFor("bodyLarge", { null }, null, wear = false))
    // Wear's numerals are the display group, and an unset group is Wear's own face.
    assertEquals("Michroma", FontSettings.familyFor("numeralLarge", host, null, wear = true))
    assertEquals("Roboto Flex", FontSettings.familyFor("bodySmall", host, null, wear = true))
  }

  @Test
  fun numbersDropTrailingZeros() {
    assertEquals("650", FontSettings.number(650f))
    assertEquals("87.5", FontSettings.number(87.5f))
    assertEquals("-0.25", FontSettings.number(-0.25f))
  }
}
