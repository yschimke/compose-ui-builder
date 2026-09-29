package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.export.UiBuilderDocumentHome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A server design copied into the browser for a caller who may read it but not write it.
 *
 * The copy is a different design from the moment it is made: a new id, provenance rather than a
 * fork point, and no server home. Anything else would let it look like a checkout that could sync
 * back into a design this caller was never allowed to change.
 */
class LocalDesignCopyTest {
  private val source =
    LocalDesignFixtures.document()
      .copy(home = UiBuilderDocumentHome.Server("https://preview.coo.ee", "shameless-potato"))

  private fun copy(newDesignId: String = "brave-kettle") =
    localCopyRecord(
      document = source,
      documentDigest = "digest",
      catalogSystemId = LocalDesignFixtures.CATALOG_SYSTEM_ID,
      newDesignId = newDesignId,
      server = "https://preview.coo.ee",
      nowEpochMillis = 42,
    )

  @Test
  fun `a copy is a new design that remembers where it came from`() {
    val record = copy()

    assertEquals("brave-kettle", record.designId)
    assertEquals("brave-kettle", record.seed.id)
    assertEquals(source.nodes, record.seed.nodes)
    assertEquals(
      LocalDesignCopyV1(
        server = "https://preview.coo.ee",
        designId = source.id,
        revision = source.revision,
        documentDigest = "digest",
        copiedAtEpochMillis = 42,
      ),
      record.copiedFrom,
    )
  }

  @Test
  fun `a copy is not a checkout, so it offers nothing to sync back into`() {
    val record = copy()

    // No fork point: sync-back needs write access to the source, which this caller does not have.
    assertNull(record.origin)
    // And no server home: this copy's home is this browser.
    assertNull(record.seed.home)
  }

  @Test
  fun `a copy never takes the source's id`() {
    assertFailsWith<IllegalArgumentException> { copy(newDesignId = source.id) }
  }
}
