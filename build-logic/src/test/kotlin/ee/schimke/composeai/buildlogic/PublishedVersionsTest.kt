package ee.schimke.composeai.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PublishedVersionsTest {
  private val manifest = """{"modules":{"compose-preview-a":"1.0.0","compose-preview-b":"2.0.0"}}"""

  @Test
  fun `no plan publishes everything at the tag`() {
    assertEquals("3.0.0", PublishedVersions.resolve("compose-preview-a", "3.0.0", null, ""))
  }

  @Test
  fun `a planned module takes the tag and a skipped one its recorded version`() {
    val plan = setOf("compose-preview-a")
    assertEquals("3.0.0", PublishedVersions.resolve("compose-preview-a", "3.0.0", plan, manifest))
    assertEquals("2.0.0", PublishedVersions.resolve("compose-preview-b", "3.0.0", plan, manifest))
  }

  @Test
  fun `a skipped module with no recorded version is an error, not the tag`() {
    assertFailsWith<IllegalStateException> {
      PublishedVersions.resolve("compose-preview-c", "3.0.0", emptySet(), manifest)
    }
  }

  @Test
  fun `absent and empty plans are different`() {
    assertNull(PublishedVersions.parsePublishSet(null))
    assertEquals(emptySet(), PublishedVersions.parsePublishSet(""))
    assertEquals(setOf("x", "y"), PublishedVersions.parsePublishSet("x, y,"))
  }
}
