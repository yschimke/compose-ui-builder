package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

/** Run in both compile configurations; no request or catalog can override the build's choice. */
class RemoteComposeBuildFlagTest {
  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "experiments/remote-state-selection/bound-actions.document.json").isFile }
  private val document = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString<UiBuilderDocument>(
      File(root, "experiments/remote-state-selection/bound-actions.document.json").readText()
    )

  @Test
  fun `a catalog cannot enable Remote formats in a disabled build`() {
    val requested = ExportCapabilitiesV1(remoteJson = true, remoteDocument = true)
    assertEquals(
      UiBuilderBuildFeatures.remoteCompose,
      RemoteDocumentExportSupport.supports(requested, ExportFormatV1.JSON),
    )
    assertEquals(
      UiBuilderBuildFeatures.remoteCompose,
      RemoteDocumentExportSupport.supports(requested, ExportFormatV1.RC),
    )
    val advertised =
      RemoteDocumentExportSupport.capabilities(requested, json = true, document = true)
    assertEquals(UiBuilderBuildFeatures.remoteCompose, advertised.remoteJson)
    assertEquals(UiBuilderBuildFeatures.remoteCompose, advertised.remoteDocument)
    assertEquals(
      if (UiBuilderBuildFeatures.remoteCompose) 2 else 0,
      RemoteDocumentExportSupport.formats.size,
    )
  }

  @Test
  fun `ordinary Remote source is opt in even with an explicitly supplied platform`() {
    val result = RecordFreeExport.generate(document, UiBuilderCatalogPlatform.REMOTE_COMPOSE)
    if (UiBuilderBuildFeatures.remoteCompose) assertIs<RecordFreeExport.Generated.Emitted>(result)
    else assertNull(result)
  }

  @Test
  fun `direct source lowering cannot bypass the build flag for stateful layouts`() {
    val result =
      InlineRemoteContentExporter.exportRoots(document, null, emptyMap(), WidgetAssetBytes { null })
    if (UiBuilderBuildFeatures.remoteCompose)
      assertIs<InlineRemoteContentExporter.Result.Emitted>(result)
    else
      assertTrue(
        assertIs<InlineRemoteContentExporter.Result.Refused>(result).reasons.any {
          "disabled in this build" in it
        }
      )
  }
}
