package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiBuilderBrandTest {
  @Test
  fun noMetasIsNoBrand() {
    assertNull(UiBuilderBrand.parse(null, null, null, null))
    assertNull(UiBuilderBrand.parse("", " ", "", ""))
    // Values that are all refused are as good as absent.
    assertNull(UiBuilderBrand.parse(null, "blue", "javascript:alert(1)", ",,"))
  }

  @Test
  fun aWhiteLabelledHostReadsInFull() {
    val brand =
      UiBuilderBrand.parse(
        name = " Home Assistant card designer ",
        accent = "#03A9F4",
        home = "https://ha.coo.ee/",
        catalogs = "remote-m3",
      )
    assertEquals(
      UiBuilderBrand(
        name = "Home Assistant card designer",
        accentArgb = 0xFF03A9F4,
        homeHref = "https://ha.coo.ee/",
        catalogs = listOf("remote-m3"),
      ),
      brand,
    )
    assertTrue(brand!!.singleCatalog)
    assertTrue(brand.allows("remote-m3"))
    assertFalse(brand.allows("m3-catalog"))
    assertEquals("Home Assistant card designer", brand.nameOr("UI Builder"))
  }

  @Test
  fun eachFieldIsCheckedOnItsOwn() {
    val brand = UiBuilderBrand.parse("Card designer", "not-a-colour", "//evil.example/", null)!!
    assertEquals("Card designer", brand.name)
    assertNull(brand.accentArgb)
    assertNull(brand.homeHref)
    // No catalog list: every catalog is offered, and there is a choice to make.
    assertTrue(brand.allows("anything"))
    assertFalse(brand.singleCatalog)
    assertEquals("UI Builder", UiBuilderBrand(accentArgb = 0xFF000000).nameOr("UI Builder"))
  }

  @Test
  fun aNameMustBeANameAndAnAccentAColour() {
    assertNull(UiBuilderBrand.parseName("x".repeat(UiBuilderBrand.NAME_MAX + 1)))
    assertNull(UiBuilderBrand.parseName("two\nlines"))
    assertEquals(0xFF000000, UiBuilderBrand.parseAccent("#000000"))
    assertEquals(0xFFFFFFFF, UiBuilderBrand.parseAccent("#ffffff"))
    assertNull(UiBuilderBrand.parseAccent("#fff"))
    assertNull(UiBuilderBrand.parseAccent("03a9f4"))
    assertNull(UiBuilderBrand.parseAccent("#03a9f4ff"))
  }

  @Test
  fun homeIsASameOriginPathOrAnHttpsUrl() {
    assertEquals("/", UiBuilderBrand.parseHome("/"))
    assertEquals("/ui-builder/?x=1", UiBuilderBrand.parseHome("/ui-builder/?x=1"))
    assertEquals("https://ha.coo.ee/cards", UiBuilderBrand.parseHome("https://ha.coo.ee/cards"))
    assertNull(UiBuilderBrand.parseHome("//evil.example/"))
    assertNull(UiBuilderBrand.parseHome("/\\evil.example/"))
    assertNull(UiBuilderBrand.parseHome("/\tx"))
    assertNull(UiBuilderBrand.parseHome("http://ha.coo.ee/"))
    assertNull(UiBuilderBrand.parseHome("javascript:alert(1)"))
    assertNull(UiBuilderBrand.parseHome("https://user@evil.example/"))
    assertNull(UiBuilderBrand.parseHome("https:///path"))
    assertNull(UiBuilderBrand.parseHome("ha.coo.ee"))
  }

  @Test
  fun catalogsAreIdsInTheHostsOrder() {
    assertEquals(
      listOf("remote-m3", "wear-m3"),
      UiBuilderBrand.parseCatalogs(" remote-m3 ,wear-m3,remote-m3,,bad id,-bad"),
    )
    assertEquals(emptyList(), UiBuilderBrand.parseCatalogs(null))
  }
}
