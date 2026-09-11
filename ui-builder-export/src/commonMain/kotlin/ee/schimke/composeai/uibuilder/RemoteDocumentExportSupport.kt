package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1

/** Remote formats are declared by the released contracts, but enabled only in an opt-in build. */
object RemoteDocumentExportSupport {
  val jsonFormat: ExportFormatV1 = ExportFormatV1.JSON
  val documentFormat: ExportFormatV1 = ExportFormatV1.RC
  val formats: List<ExportFormatV1> =
    if (UiBuilderBuildFeatures.remoteCompose) listOf(jsonFormat, documentFormat) else emptyList()

  fun supports(capabilities: ExportCapabilitiesV1, format: ExportFormatV1): Boolean =
    UiBuilderBuildFeatures.remoteCompose &&
      when (format) {
        ExportFormatV1.JSON -> capabilities.remoteJson
        ExportFormatV1.RC -> capabilities.remoteDocument
        else -> false
      }

  fun capabilities(
    base: ExportCapabilitiesV1,
    json: Boolean,
    document: Boolean,
  ): ExportCapabilitiesV1 =
    base.copy(
      remoteJson = UiBuilderBuildFeatures.remoteCompose && json,
      remoteDocument = UiBuilderBuildFeatures.remoteCompose && document,
    )
}
