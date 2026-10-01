package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.reference.ReferenceUrl
import ee.schimke.composeai.uibuilder.reference.declaredDensityFromName
import ee.schimke.composeai.uibuilder.reference.parseReferenceUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReferenceUrlTest {
  @Test
  fun `a design link names its file and its frame in API form`() {
    val link =
      assertIs<ReferenceUrl.Figma>(
        parseReferenceUrl("https://www.figma.com/design/AbCdEf1234567890/App?node-id=12-345&t=x")
      )
    assertEquals("AbCdEf1234567890", link.fileKey)
    assertEquals("12:345", link.nodeId)
  }

  @Test
  fun `old file links, encoded colons and branches all parse`() {
    assertEquals(
      "1:2",
      (parseReferenceUrl("https://figma.com/file/AbCdEf1234567890/x?node-id=1%3A2")
          as ReferenceUrl.Figma)
        .nodeId,
    )
    val branch =
      parseReferenceUrl(
        "https://www.figma.com/design/AbCdEf1234567890/branch/ZyXwVu0987654321/App?node-id=3-4"
      )
        as ReferenceUrl.Figma
    assertEquals("ZyXwVu0987654321", branch.fileKey)
  }

  @Test
  fun `a whole-file link has no frame`() {
    val link =
      assertIs<ReferenceUrl.Figma>(
        parseReferenceUrl("https://www.figma.com/design/AbCdEf1234567890/App")
      )
    assertEquals(null, link.nodeId)
  }

  @Test
  fun `a fetched frame is named with the scale it was rendered at`() {
    val link = parseReferenceUrl("https://www.figma.com/design/AbCdEf1234567890/App?node-id=1-2")
    assertEquals(2f, declaredDensityFromName((link as ReferenceUrl.Figma).importName(2)))
  }

  @Test
  fun `images must be https, and non-links are refused`() {
    assertIs<ReferenceUrl.Image>(parseReferenceUrl(" https://example.com/mock.png "))
    assertIs<ReferenceUrl.Unsupported>(parseReferenceUrl("http://example.com/mock.png"))
    assertIs<ReferenceUrl.Unsupported>(parseReferenceUrl("file:///etc/passwd"))
    assertIs<ReferenceUrl.Unsupported>(parseReferenceUrl("mock.png"))
    assertIs<ReferenceUrl.Unsupported>(
      parseReferenceUrl("https://www.figma.com/community/plugin/1")
    )
  }
}
