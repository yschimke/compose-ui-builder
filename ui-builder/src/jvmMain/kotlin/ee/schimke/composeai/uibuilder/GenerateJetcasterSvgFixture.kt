package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

object GenerateJetcasterSvgFixture {
  @JvmStatic
  fun main(args: Array<String>) {
    require(args.size == 1) { "usage: GenerateJetcasterSvgFixture <output.svg>" }
    val loader = GenerateJetcasterSvgFixture::class.java
    val operations =
      Json.parseToJsonElement(
          checkNotNull(loader.getResource("/jetcaster-discover-operations-v1.json")).readText()
        )
        .jsonObject
    val document = UiBuilderReducer.replay(operations).document
    val catalog =
      CapabilityCatalogParser.parse(
        checkNotNull(loader.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )
    val result =
      executeSavedDocumentSvgExport(
        SavedDocumentSvgExportJob(
          pin = SavedDocumentRevisionPin.from(document),
          documentSnapshot = document,
          recorderKind = StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS,
        ),
        catalog,
        JvmSkiaStructuredSvgRecorder,
      )
    val exported = result as? SavedDocumentSvgExportResult.Ok ?: error(result.toString())
    File(args.single()).apply {
      parentFile.mkdirs()
      writeText(exported.svg)
    }
  }
}
