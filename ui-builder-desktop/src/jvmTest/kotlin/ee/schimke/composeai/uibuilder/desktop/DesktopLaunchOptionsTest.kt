package ee.schimke.composeai.uibuilder.desktop

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
}
