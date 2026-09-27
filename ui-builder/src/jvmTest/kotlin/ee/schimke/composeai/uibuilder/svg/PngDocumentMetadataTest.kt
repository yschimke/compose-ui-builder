package ee.schimke.composeai.uibuilder.svg

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderDocumentHome
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject

class PngDocumentMetadataTest {
  @Test
  fun `PNG international text metadata preserves Unicode canonical home and revision`() {
    val png =
      byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)
    val document =
      UiBuilderDocument(
        schema = "compose-ui-builder-document/v1",
        id = "ログイン-β",
        title = "Login",
        revision = 4,
        catalogPin = JsonObject(emptyMap()),
        environment = JsonObject(emptyMap()),
        stateVariables = JsonObject(emptyMap()),
        roots = emptyList(),
        nodes = emptyMap(),
        home = UiBuilderDocumentHome.Repo("ui-builder/designs/ログイン.uid"),
      )

    val payload = png.withDocumentMetadata(document).chunkPayload("iTXt")
    val keywordEnd = payload.indexOf(0)

    assertEquals("compose-ui-builder", payload.decodeToString(0, keywordEnd))
    assertContentEquals(
      byteArrayOf(0, 0, 0, 0),
      payload.copyOfRange(keywordEnd + 1, keywordEnd + 5),
    )
    assertEquals(
      "designId=ログイン-β;revision=4;home=repository ui-builder/designs/ログイン.uid",
      payload.decodeToString(keywordEnd + 5, payload.size),
    )
  }

  private fun ByteArray.chunkPayload(expectedType: String): ByteArray {
    var offset = 8
    while (offset + 12 <= size) {
      val length = readPngInt(offset)
      val type = decodeToString(offset + 4, offset + 8)
      if (type == expectedType) return copyOfRange(offset + 8, offset + 8 + length)
      offset += 12 + length
    }
    error("PNG has no $expectedType chunk")
  }

  private fun ByteArray.readPngInt(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 24) or
      ((this[offset + 1].toInt() and 0xff) shl 16) or
      ((this[offset + 2].toInt() and 0xff) shl 8) or
      (this[offset + 3].toInt() and 0xff)
}
