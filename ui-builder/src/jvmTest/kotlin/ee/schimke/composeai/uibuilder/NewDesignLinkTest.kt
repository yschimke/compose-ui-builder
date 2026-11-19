package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.NewDesignLink
import ee.schimke.composeai.uibuilder.editor.NewDesignSelection
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignTemplate
import ee.schimke.composeai.uibuilder.editor.newDesignLikeThisLabel
import ee.schimke.composeai.uibuilder.editor.newDesignLinkOf
import ee.schimke.composeai.uibuilder.editor.resolveNewDesignLink
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The New design form's choices as a link: read leniently, written canonically, round-tripped. */
class NewDesignLinkTest {
  private fun template(id: String, group: String? = null) =
    UiBuilderNewDesignTemplate(id, id, "", group = group)

  private val m3 =
    UiBuilderNewDesignCatalog(
      "m3-catalog",
      "Mobile app",
      listOf(template("blank"), template("jetcaster-discover")),
    )
  private val wear =
    UiBuilderNewDesignCatalog(
      "wear-m3",
      "Wear",
      listOf(template("blank"), template("wear-list")),
      platform = UiBuilderCatalogPlatform.WEAR,
    )
  private val watchFace =
    UiBuilderNewDesignCatalog(
      "wff-watchface",
      "Watch face",
      listOf(template("analog-watch-face"), template("digital-watch-face")),
      platform = UiBuilderCatalogPlatform.WATCH_FACE,
    )
  private val catalogs = listOf(m3, wear, watchFace)

  @Test
  fun `the documented link selects the watch face template`() {
    val link =
      NewDesignLink.parse(
        mapOf("new" to "watch", "kind" to "watchface", "template" to "digital-watch-face"),
      )
    assertEquals(
      NewDesignSelection("wff-watchface", null, "digital-watch-face"),
      resolveNewDesignLink(link, catalogs),
    )
  }

  @Test
  fun `a catalog answers to its id, its label in any spacing, and its id's last word`() {
    for (word in listOf("wff-watchface", "Watch face", "watch-face", "WATCHFACE")) {
      assertEquals(
        "wff-watchface",
        resolveNewDesignLink(NewDesignLink(kind = word), catalogs)?.catalogSystemId,
        "for $word",
      )
    }
  }

  @Test
  fun `type reads as a catalog when it names one and as a device otherwise`() {
    assertEquals(
      "wff-watchface",
      resolveNewDesignLink(NewDesignLink.parse(mapOf("type" to "watchface")), catalogs)
        ?.catalogSystemId,
    )
    // The watch's first catalog, as the device chip picks it.
    assertEquals(
      "wear-m3",
      resolveNewDesignLink(NewDesignLink.parse(mapOf("type" to "watch")), catalogs)
        ?.catalogSystemId,
    )
  }

  @Test
  fun `a template alone finds the catalog that offers it`() {
    assertEquals(
      NewDesignSelection("wff-watchface", null, "analog-watch-face"),
      resolveNewDesignLink(NewDesignLink(template = "analog-watch-face"), catalogs),
    )
  }

  @Test
  fun `unknown parts fall back one at a time`() {
    // An unknown template keeps the catalog, at its first starting point.
    assertEquals(
      NewDesignSelection("wff-watchface", null, "analog-watch-face"),
      resolveNewDesignLink(NewDesignLink(kind = "watchface", template = "nope"), catalogs),
    )
    // An unknown catalog keeps the device.
    assertEquals(
      "wear-m3",
      resolveNewDesignLink(NewDesignLink(device = "watch", kind = "nope"), catalogs)
        ?.catalogSystemId,
    )
    // A template under the wrong device does not move the device.
    assertEquals(
      NewDesignSelection("m3-catalog", null, "blank"),
      resolveNewDesignLink(
        NewDesignLink(device = "phone", template = "analog-watch-face"),
        catalogs,
      ),
    )
    // Nothing on offer, nothing chosen: the form keeps its defaults.
    assertNull(resolveNewDesignLink(NewDesignLink(device = "toaster", kind = "nope"), catalogs))
    assertNull(resolveNewDesignLink(NewDesignLink(), catalogs))
    assertNull(resolveNewDesignLink(NewDesignLink(kind = "watchface"), emptyList()))
  }

  @Test
  fun `a single grouped catalog is chosen by its group`() {
    val only =
      UiBuilderNewDesignCatalog(
        "brand",
        "Brand",
        listOf(template("blank"), template("tile", "Tiles"), template("card", "Cards")),
      )
    assertEquals(
      NewDesignSelection("brand", "Cards", "blank"),
      resolveNewDesignLink(NewDesignLink(group = "cards"), listOf(only)),
    )
    assertEquals(
      NewDesignSelection("brand", "Cards", "card"),
      resolveNewDesignLink(NewDesignLink(template = "card"), listOf(only)),
    )
  }

  @Test
  fun `a written link reads back to the same selection`() {
    val selection = NewDesignSelection("wff-watchface", null, "digital-watch-face")
    val link = newDesignLinkOf(selection, catalogs, title = " My face ", publicRead = true)
    assertEquals(
      listOf(
        "new" to "watch",
        "kind" to "wff-watchface",
        "template" to "digital-watch-face",
        "title" to "My face",
        "visibility" to "public",
      ),
      link.toQueryPairs(),
    )
    val read = NewDesignLink.parse(link.toQueryPairs().toMap())
    assertEquals(link, read)
    assertEquals(selection, resolveNewDesignLink(read, catalogs))
  }

  @Test
  fun `blank and untitled values are not written or read`() {
    val link = newDesignLinkOf(NewDesignSelection("m3-catalog", null, "blank"), catalogs)
    assertFalse(link.toQueryPairs().any { it.first == "title" || it.first == "visibility" })
    val parsed = NewDesignLink.parse(mapOf("new" to " ", "title" to "", "visibility" to "private"))
    assertTrue(parsed.isEmpty)
  }

  @Test
  fun `the one-click label reads as an action`() {
    assertEquals("New watch face", newDesignLikeThisLabel(watchFace))
    assertEquals("New Wear design", newDesignLikeThisLabel(wear))
    assertEquals("New mobile app", newDesignLikeThisLabel(m3))
    assertEquals(
      "New Mobile · M3 Catalog design",
      newDesignLikeThisLabel(m3.copy(label = "Mobile · M3 Catalog")),
    )
  }
}
