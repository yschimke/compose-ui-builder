package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.reference.ReferenceFit
import ee.schimke.composeai.uibuilder.reference.ReferenceImportOutcome
import ee.schimke.composeai.uibuilder.reference.ReferenceMark
import ee.schimke.composeai.uibuilder.reference.ReferenceMarkupKind
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlaySettings
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class JvmReferenceHostTest {
  private val directory = Files.createTempDirectory("references")

  @Test
  fun `a png file is imported with its size and a declared density`() {
    val file = directory.resolve("card@2x.png")
    ImageIO.write(BufferedImage(656, 112, BufferedImage.TYPE_INT_ARGB), "png", file.toFile())
    val imported =
      assertIs<ReferenceImportOutcome.Imported>(JvmReferenceHost(null).importFile(file)).image
    assertEquals("image/png", imported.mediaType)
    assertEquals(656, imported.widthPx)
    assertEquals(112, imported.heightPx)
    assertEquals("card@2x.png", imported.name)
  }

  @Test
  fun `bytes that are not a picture are refused`() {
    val file = directory.resolve("notes.png")
    Files.writeString(file, "not a picture")
    assertIs<ReferenceImportOutcome.Refused>(JvmReferenceHost(null).importFile(file))
  }

  @Test
  fun `an svg with a script is refused at the door`() {
    val file = directory.resolve("mock.svg")
    Files.writeString(
      file,
      "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>",
    )
    assertIs<ReferenceImportOutcome.Refused>(JvmReferenceHost(null).importFile(file))
  }

  @Test
  fun `media types are sniffed from the bytes, not the name`() {
    assertEquals("image/jpeg", JvmReferenceHost.sniffMediaType(byteArrayOf(-1, -40, -1, 0)))
    assertEquals(
      "image/webp",
      JvmReferenceHost.sniffMediaType("RIFF0000WEBPVP8 ".toByteArray(Charsets.US_ASCII)),
    )
    assertEquals("image/svg+xml", JvmReferenceHost.sniffMediaType("<?xml?>\n<svg/>".toByteArray()))
    assertNull(JvmReferenceHost.sniffMediaType("GIF89a".toByteArray()))
  }

  @Test
  fun `the stack survives a reopen, fit included, and an empty stack removes the file`() {
    val file = directory.resolve("nested/one.json")
    val host = JvmReferenceHost(file)
    val png = directory.resolve("shot.png")
    ImageIO.write(BufferedImage(1080, 2400, BufferedImage.TYPE_INT_ARGB), "png", png.toFile())
    val image = (host.importFile(png) as ReferenceImportOutcome.Imported).image
    val state =
      ReferenceOverlayState(
        image = image,
        settings = ReferenceOverlaySettings(offsetXDp = 2f, fit = ReferenceFit.Width),
        marks =
          listOf(ReferenceMark("mark-1", ReferenceMarkupKind.Rectangle, listOf(0f, 0f, 1f, 1f))),
      )
    assertNull(host.save(state))
    val restored = JvmReferenceHost(file).load()!!
    assertEquals(image.id, restored.image?.id)
    assertEquals(1080, restored.image?.widthPx)
    assertEquals(ReferenceFit.Width, restored.settings.fit)
    assertEquals(2f, restored.settings.offsetXDp)
    assertEquals(1, restored.marks.size)
    assertNull(host.save(ReferenceOverlayState()))
    assertFalse(Files.exists(file))
  }

  @Test
  fun `a design id never becomes a path`() {
    val file = JvmReferenceHost.storeFor(directory, "../../etc/passwd")
    assertEquals(directory, file.parent)
    assertTrue(file.fileName.toString().matches(Regex("[0-9a-f]{32}\\.json")))
  }

  @Test
  fun `figma links need a token and a frame`(): Unit = runBlocking {
    val host = JvmReferenceHost(null, figmaToken = { null })
    val noToken = host.fetchUrl("https://www.figma.com/design/AbCdEf1234567890/App?node-id=1-2")
    assertTrue((noToken as ReferenceImportOutcome.Refused).reason.contains("FIGMA_TOKEN"))
    val wholeFile = host.fetchUrl("https://www.figma.com/design/AbCdEf1234567890/App")
    assertIs<ReferenceImportOutcome.Refused>(wholeFile)
  }
}
