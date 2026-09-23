package ee.schimke.composeai.uibuilder.desktop

import ee.schimke.composeai.uibuilder.host.OfflineCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DesktopLaunchOptionsTest {
  @Test
  fun `no arguments opens the Material 3 workspace offline`() {
    val options = DesktopLaunchOptions.parse(emptyArray())

    assertEquals(OfflineCatalog.M3, options.catalog)
    assertNull(options.remoteServer)
  }

  @Test
  fun `the widget catalog is reachable from the command line`() {
    val options = DesktopLaunchOptions.parse(arrayOf("--catalog", "remote-m3"))

    assertEquals(OfflineCatalog.REMOTE_M3, options.catalog)
  }

  @Test
  fun `catalog and server combine in either order`() {
    val expected = DesktopLaunchOptions("https://preview.coo.ee", OfflineCatalog.WEAR_M3)

    assertEquals(
      expected,
      DesktopLaunchOptions.parse(
        arrayOf("--catalog", "wear-m3", "--server", "https://preview.coo.ee")
      ),
    )
    assertEquals(
      expected,
      DesktopLaunchOptions.parse(
        arrayOf("--server", "https://preview.coo.ee", "--catalog", "wear-m3")
      ),
    )
  }

  @Test
  fun `an unknown catalog or flag is refused with the usage`() {
    val unknown =
      assertFailsWith<IllegalArgumentException> {
        DesktopLaunchOptions.parse(arrayOf("--catalog", "remote/m3"))
      }
    assertEquals(true, unknown.message?.contains("remote-m3"))
    assertFailsWith<IllegalArgumentException> { DesktopLaunchOptions.parse(arrayOf("--catalog")) }
    assertFailsWith<IllegalArgumentException> {
      DesktopLaunchOptions.parse(arrayOf("--widget", "remote-m3"))
    }
    assertFailsWith<IllegalArgumentException> {
      DesktopLaunchOptions.parse(arrayOf("--catalog", "m3-catalog", "--catalog", "remote-m3"))
    }
  }

  @Test
  fun `each catalog keeps its own workspace and Material 3 keeps the original one`() {
    val m3 = designStorePath(OfflineCatalog.M3)

    assertEquals("ui-builder-desktop", m3.fileName.toString())
    assertEquals(m3.resolve("remote-m3"), designStorePath(OfflineCatalog.REMOTE_M3))
    assertNotEquals(
      designStorePath(OfflineCatalog.WEAR_M3),
      designStorePath(OfflineCatalog.REMOTE_M3),
    )
  }

  @Test
  fun `a widget sample opens by its template id`() {
    val options =
      DesktopLaunchOptions.parse(arrayOf("--catalog", "remote-m3", "--template", "weather-widget"))

    assertEquals(OfflineCatalog.REMOTE_M3, options.catalog)
    assertEquals("weather-widget", options.template)
  }

  @Test
  fun `a template the catalog does not have is refused with the ones it does`() {
    val refused =
      assertFailsWith<IllegalArgumentException> {
        // A widget template asked of the phone catalog, which is the easy mistake to make.
        DesktopLaunchOptions.parse(arrayOf("--template", "weather-widget"))
      }

    assertEquals(true, refused.message?.contains("jetcaster"), refused.message)
  }

  @Test
  fun `a named template keeps a workspace of its own`() {
    val widgets = designStorePath(OfflineCatalog.REMOTE_M3)

    assertEquals(
      widgets.resolve("template-weather-widget"),
      designStorePath(OfflineCatalog.REMOTE_M3, template = "weather-widget"),
    )
    assertNotEquals(
      designStorePath(OfflineCatalog.REMOTE_M3, template = "hello-widget"),
      designStorePath(OfflineCatalog.REMOTE_M3, template = "weather-widget"),
    )
  }

  @Test
  fun `a template and a design file are not asked for together`() {
    assertFailsWith<IllegalArgumentException> {
      DesktopLaunchOptions.parse(
        arrayOf("--catalog", "remote-m3", "--template", "weather-widget", "a.uid")
      )
    }
  }

  @Test
  fun `a template is named for people in the menu and the title`() {
    assertEquals("Weather widget", templateLabel("weather-widget"))
    assertEquals(
      "Wear widgets Weather widget",
      DesktopDesign.Scratch(OfflineCatalog.REMOTE_M3, "weather-widget").title,
    )
    assertEquals("Wear widgets scratch", DesktopDesign.Scratch(OfflineCatalog.REMOTE_M3).title)
  }

  @Test
  fun `a catalog file picks its own catalog and must agree with --catalog`() {
    val file = java.nio.file.Files.createTempFile("wear", ".json")
    java.nio.file.Files.writeString(
      file,
      checkNotNull(javaClass.getResource("/wear-m3-capabilities-v1.json")).readText(),
    )

    val options = DesktopLaunchOptions.parse(arrayOf("--catalog-file", file.toString()))

    assertEquals(OfflineCatalog.WEAR_M3, options.catalog)
    assertEquals("wear-m3", options.catalogOverride?.systemId)
    assertFailsWith<IllegalArgumentException> {
      DesktopLaunchOptions.parse(
        arrayOf("--catalog", "remote-m3", "--catalog-file", file.toString())
      )
    }
  }
}
