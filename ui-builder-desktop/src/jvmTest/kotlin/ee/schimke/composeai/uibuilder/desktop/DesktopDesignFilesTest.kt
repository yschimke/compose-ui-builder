package ee.schimke.composeai.uibuilder.desktop

import ee.schimke.composeai.uibuilder.editor.EditorSubmission
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.host.DesignFiles
import ee.schimke.composeai.uibuilder.host.OfflineCatalog
import ee.schimke.composeai.uibuilder.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.toUiBuilderDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopDesignFilesTest {
  private val catalog = OfflineCatalog.WEAR_M3.capabilityCatalog()
  private val seed =
    OfflineCatalog.WEAR_M3.seed(
      designId = "file-test",
      catalogRevision = catalog.benchmark.catalogRevision,
      nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
    )

  @Test
  fun `a written design reads back as the same document`() {
    val path = Files.createTempDirectory("ui-builder-files").resolve("design.uid")

    DesignFiles.write(path, seed.toDesignDocumentV1())

    assertEquals(seed.toDesignDocumentV1(), DesignFiles.read(path).toDesignDocumentV1())
    assertEquals(
      listOf("design.uid"),
      Files.list(path.parent).map { it.fileName.toString() }.toList(),
    )
  }

  @Test
  fun `a file with a schema this editor cannot write back is refused`() {
    val path = Files.createTempDirectory("ui-builder-files").resolve("future.uid")
    DesignFiles.write(
      path,
      seed.toDesignDocumentV1().copy(schema = "compose-ui-builder-document/v9"),
    )

    assertFailsWith<IllegalArgumentException> { DesignFiles.read(path) }
  }

  @Test
  fun `edits to an opened design file are written back to it`() = runBlocking {
    val path = Files.createTempDirectory("ui-builder-files").resolve("design.uid")
    DesignFiles.write(path, seed.toDesignDocumentV1())

    openDesktopSession(DesktopDesign.File(path), path.parent, remoteServer = null).use { session ->
      val document =
        withTimeout(10.seconds) { session.snapshot.filterNotNull().first() }
          .snapshot
          .state
          .document
          .toUiBuilderDocument()
      val reducer = UiBuilderEditorReducer(session.catalog, "file-test", "file-test")
      val initial = reducer.initial(document)
      val settings = document.screenEnvironmentSettings()
      val edited =
        reducer.reduce(
          initial,
          UiBuilderEditorEvent.UpdateEnvironment(settings.copy(heightDp = settings.heightDp + 2)),
        )
      session.submit(assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(initial, edited)))

      withTimeout(10.seconds) {
        while (DesignFiles.read(path).revision == document.revision) delay(20)
      }
      assertEquals(
        settings.heightDp + 2,
        DesignFiles.read(path).screenEnvironmentSettings().heightDp,
      )
    }
  }

  @Test
  fun `a design file combines with the flags in any order`() {
    assertNull(DesktopLaunchOptions.parse(emptyArray()).designFile)
    assertEquals(
      DesktopLaunchOptions("https://preview.example", designFile = Path.of("a.uid")),
      DesktopLaunchOptions.parse(arrayOf("a.uid", "--server", "https://preview.example")),
    )
    assertEquals(
      DesktopLaunchOptions(null, OfflineCatalog.WEAR_M3, Path.of("a.uid")),
      DesktopLaunchOptions.parse(arrayOf("--catalog", "wear-m3", "a.uid")),
    )
    assertFailsWith<IllegalArgumentException> {
      DesktopLaunchOptions.parse(arrayOf("a.uid", "b.uid"))
    }
  }
}
