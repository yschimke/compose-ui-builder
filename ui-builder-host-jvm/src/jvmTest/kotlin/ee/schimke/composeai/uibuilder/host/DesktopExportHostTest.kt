package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.editor.EditorExportFormat
import java.awt.Image
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopExportHostTest {
  private val catalog = OfflineCatalog.M3.capabilityCatalog()
  private val document =
    OfflineCatalog.M3.seed(
      designId = "export-test",
      catalogRevision = catalog.benchmark.catalogRevision,
      nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
    )

  @Test
  fun `downloads a Figma-pasteable SVG and a PNG of the design's own size`() = runBlocking {
    val directory = Files.createTempDirectory("ui-builder-export")
    val chosen = mutableListOf<String>()
    val host =
      DesktopExportHost(
        catalog = catalog,
        document = { document },
        files = { name, _ -> directory.resolve(name).also { chosen += name } },
        clipboard = RecordingClipboard(),
      )

    assertEquals("Saved export-test.svg", host.download(EditorExportFormat.Svg))
    assertEquals("Saved export-test.png", host.download(EditorExportFormat.Png))

    assertEquals(listOf("export-test.svg", "export-test.png"), chosen)
    assertTrue(Files.readString(directory.resolve("export-test.svg")).contains("<svg"))
    val png = assertNotNull(ImageIO.read(directory.resolve("export-test.png").toFile()))
    assertTrue(png.width > 0 && png.height > 0)
  }

  @Test
  fun `copies SVG as text and PNG as an image, and offers no link`() = runBlocking {
    val clipboard = RecordingClipboard()
    val host =
      DesktopExportHost(catalog, { document }, files = { _, _ -> null }, clipboard = clipboard)

    assertEquals("SVG copied — paste it into Figma", host.copyPicture(EditorExportFormat.Svg))
    assertEquals("PNG copied", host.copyPicture(EditorExportFormat.Png))

    assertTrue(clipboard.text.orEmpty().contains("<svg"))
    assertNotNull(clipboard.image)
    assertFalse(host.supportsLinks)
  }

  @Test
  fun `a cancelled save writes nothing`() = runBlocking {
    val host =
      DesktopExportHost(catalog, { document }, files = { _, _ -> null }, RecordingClipboard())

    assertEquals("Export cancelled", host.download(EditorExportFormat.Png))
  }

  private class RecordingClipboard : ExportClipboard {
    var text: String? = null
    var image: Image? = null

    override fun putText(text: String) {
      this.text = text
    }

    override fun putImage(image: Image) {
      this.image = image
    }
  }
}
