package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcRemark
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteComposeDocumentTest {
  @Test
  fun `embedded Remote Compose document decodes`() {
    val expected =
      RcDocument(
        header = RcHeader(RcVersion(0, 1, 0)),
        operations = listOf(RcRemark("nested document")),
      )
    val encoded = Base64.Default.encode(RcDocumentCodec.encode(expected))

    assertEquals(expected, decodeRemoteComposeDocument(encoded).getOrThrow())
  }

  @Test
  fun `missing and malformed Remote Compose documents are rejected`() {
    assertTrue(decodeRemoteComposeDocument("").isFailure)
    assertTrue(decodeRemoteComposeDocument("not base64").isFailure)
  }
}
