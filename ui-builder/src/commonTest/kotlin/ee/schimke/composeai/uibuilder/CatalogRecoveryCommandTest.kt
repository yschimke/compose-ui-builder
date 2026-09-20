package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeMutationV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewStatusV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CatalogRecoveryCommandTest {
  @Test
  fun `the confirmed command quotes every hash and pin from its preview`() {
    val preview = preview(CatalogUpgradePreviewStatusV1.READY, targetHash = "target-hash")

    val command = preview.catalogRecoveryCommand("owner", "browser")!!
    val mutation = assertIs<CatalogUpgradeMutationV1>(command.operations.single())

    assertEquals("catalog-recovery:preview-digest", command.operationId)
    assertEquals(12, command.baseRevision)
    assertEquals(SOURCE, mutation.sourceCatalogPin)
    assertEquals(TARGET, mutation.targetCatalogPin)
    assertEquals("source-hash", mutation.sourceDocumentHash)
    assertEquals("target-hash", mutation.targetDocumentHash)
    assertEquals("preview-digest", mutation.previewDigest)
  }

  @Test
  fun `a blocked or incomplete preview cannot become a write`() {
    assertNull(
      preview(CatalogUpgradePreviewStatusV1.BLOCKED, "target-hash").catalogRecoveryCommand("a", "c")
    )
    assertNull(preview(CatalogUpgradePreviewStatusV1.READY, null).catalogRecoveryCommand("a", "c"))
  }

  private fun preview(status: CatalogUpgradePreviewStatusV1, targetHash: String?) =
    CatalogUpgradePreviewV1(
      designId = "widget",
      baseRevision = 12,
      sourceCatalogPin = SOURCE,
      targetCatalogPin = TARGET,
      sourceDocumentHash = "source-hash",
      status = status,
      previewDigest = "preview-digest",
      candidateDocumentHash = targetHash,
    )

  private companion object {
    val SOURCE = CatalogReferenceV1("remote-m3", "old", "old-digest", "runtime")
    val TARGET = CatalogReferenceV1("remote-m3", "current", "current-digest", "runtime")
  }
}
