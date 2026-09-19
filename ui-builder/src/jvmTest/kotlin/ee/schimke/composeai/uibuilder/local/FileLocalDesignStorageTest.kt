package ee.schimke.composeai.uibuilder.local

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileLocalDesignStorageTest {
  @Test
  fun `persists values and does not expose filesystem names as keys`() {
    val directory = Files.createTempDirectory("ui-builder-local-storage")
    try {
      val storage = FileLocalDesignStorage(directory)

      storage.write("ui-builder.local.design.desktop", "saved design")

      assertEquals("saved design", storage.read("ui-builder.local.design.desktop"))
      assertEquals(listOf("ui-builder.local.design.desktop"), storage.keys())
      storage.remove("ui-builder.local.design.desktop")
      assertNull(storage.read("ui-builder.local.design.desktop"))
    } finally {
      Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
      }
    }
  }
}
