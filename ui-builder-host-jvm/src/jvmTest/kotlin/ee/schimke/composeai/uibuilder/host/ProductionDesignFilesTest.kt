package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.export.production.ProductionUidFiles
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import java.nio.file.Files
import kotlin.test.*

class ProductionDesignFilesTest {
  private val fixture =
    checkNotNull(javaClass.classLoader.getResource("production-editor.uid")).readText()

  @Test
  fun `guarded native saves and save-as retain the complete production wrapper`() {
    val dir = Files.createTempDirectory("production-editor")
    try {
      val path = dir.resolve("Card.uid")
      Files.writeString(path, fixture)
      val guard = DesignFileGuard(path)
      val initial = DesignFiles.read(path)
      val edited = initial.copy(title = "Native edit", revision = 1)
      guard.write(edited.toDesignDocumentV1())
      val expected = ProductionUidFiles.decode(fixture).copy(design = edited)
      assertEquals(expected, ProductionUidFiles.decode(Files.readString(path)))
      assertEquals(edited, DesignFiles.read(path))
      val second = dir.resolve("Copy.uid")
      DesignFiles.write(second, edited.toDesignDocumentV1(), fixture)
      assertEquals(expected, ProductionUidFiles.decode(Files.readString(second)))
      Files.writeString(
        path,
        fixture.replace("example.ui.components.EpisodeCard", "example.ui.components.ExternalEdit"),
      )
      assertFailsWith<IllegalStateException> { guard.write(edited.toDesignDocumentV1()) }
      assertContains(Files.readString(path), "ExternalEdit")
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `native writes cannot remove a production binding target`() {
    val original = DesignFiles.decode(fixture)
    assertFails { DesignFiles.encode(original.copy(nodes = emptyMap()), fixture) }
  }
}
