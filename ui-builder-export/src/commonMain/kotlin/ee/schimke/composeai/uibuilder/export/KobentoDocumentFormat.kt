package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import kotlinx.serialization.json.JsonObject

/**
 * Kobento component JSON as a [CatalogDocumentFormat]: [KobentoComponentJsonExporter], with its
 * options read from the catalog's `statusSemantics.kobento` policy.
 */
object KobentoDocumentFormat : CatalogDocumentFormat {
  override val id: String = "kobento-component-json"
  override val version: Int = 1
  override val label: String = "Kobento component JSON"
  override val policyKey: String = "kobento"
  override val wireFormat: ExportFormatV1 = ExportFormatV1.KOBENTO_JSON
  override val mediaType: String = "application/json"
  override val encoding: ExportEncodingV1 = ExportEncodingV1.UTF8

  override fun offered(capabilities: ExportCapabilitiesV1): Boolean = capabilities.kobentoJson

  override fun offer(builder: ExportCapabilitiesV1.Builder, enabled: Boolean) {
    builder.kobentoJson = enabled
  }

  override fun export(
    document: UiBuilderDocument,
    policy: JsonObject,
  ): CatalogDocumentFormat.Result =
    when (
      val result =
        KobentoComponentJsonExporter.export(
          document,
          KobentoComponentJsonExporter.Options().withCatalog(policy),
        )
    ) {
      is KobentoComponentJsonExporter.Result.Emitted ->
        CatalogDocumentFormat.Result.Emitted(result.source)
      is KobentoComponentJsonExporter.Result.Refused ->
        CatalogDocumentFormat.Result.Refused(result.reasons)
    }
}
