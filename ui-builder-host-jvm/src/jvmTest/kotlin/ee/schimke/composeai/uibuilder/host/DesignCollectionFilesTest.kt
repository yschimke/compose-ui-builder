package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.UidDesignCollection
import ee.schimke.composeai.uibuilder.UidDesignFiles
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DesignCollectionFilesTest {
  private val catalog = OfflineCatalog.WEAR_M3.capabilityCatalog()
  private val first =
    OfflineCatalog.WEAR_M3.seed(
      designId = "first",
      catalogRevision = catalog.benchmark.catalogRevision,
      nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
    )
  private val second = first.copy(id = "second", title = "Second")

  @Test
  fun `guarded saves edit the active design and keep the others`() {
    val dir = Files.createTempDirectory("design-collection")
    try {
      val path = dir.resolve("screens.uid")
      Files.writeString(
        path,
        UidDesignFiles.encodeCollection(UidDesignCollection("second", listOf(first, second))),
      )
      val guard = DesignFileGuard(path)
      assertEquals(second, DesignFiles.read(path))

      val edited = second.copy(title = "Second, edited", revision = 1)
      guard.write(edited.toDesignDocumentV1())

      assertEquals(edited, DesignFiles.read(path))
      assertEquals(listOf(first, edited), DesignFiles.readCollection(path).designs)

      DesignFiles.updateCollection(path) { it.withActive("first") }
      assertEquals(first, DesignFiles.read(path))
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `adding a design to an ordinary file makes it a collection`() {
    val dir = Files.createTempDirectory("design-collection")
    try {
      val path = dir.resolve("screen.uid")
      DesignFiles.write(path, first.toDesignDocumentV1())

      DesignFiles.updateCollection(path) { it.plus(second) }

      assertEquals(second, DesignFiles.read(path))
      assertEquals(listOf(first, second), DesignFiles.readCollection(path).designs)
    } finally {
      dir.toFile().deleteRecursively()
    }
  }
}
