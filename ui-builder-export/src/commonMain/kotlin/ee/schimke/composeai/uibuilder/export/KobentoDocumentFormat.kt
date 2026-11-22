package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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

  override fun writes(componentId: String): Boolean =
    componentId.startsWith(KobentoComponentJsonExporter.COMPONENT_PREFIX) ||
      componentId in KobentoComponentJsonExporter.BUILDER_LAYOUTS

  override fun offered(capabilities: ExportCapabilitiesV1): Boolean = capabilities.kobentoJson

  override fun offer(builder: ExportCapabilitiesV1.Builder, enabled: Boolean) {
    builder.kobentoJson = enabled
  }

  /**
   * The catalog's `localCompile` block: its definitions and theme (kobento-catalog's
   * `kobento/definitions.json` and `kobento/theme.json`), from which [KobentoComponentExpander]
   * writes a design as plain Remote Compose JSON, and Kobento's `json2rc` module that compiles it.
   * A catalog without the block is compiled by the host's service only.
   */
  override fun localCompilation(policy: JsonObject): LocalCompilation? {
    val block = policy["localCompile"] as? JsonObject ?: return null
    fun path(key: String) =
      (block[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val definitions = path("definitions") ?: return null
    val theme = path("theme") ?: return null
    val compiler = path("compiler") ?: return null
    return LocalCompilation(listOf(definitions, theme), compiler) { document, files ->
      val definitionsText = files[definitions]
      val themeText = files[theme]
      if (definitionsText == null || themeText == null) {
        return@LocalCompilation CatalogDocumentFormat.Result.Refused(
          listOf("the catalog's Kobento definitions are not available"),
        )
      }
      when (val written = export(document, policy)) {
        is CatalogDocumentFormat.Result.Refused -> written
        is CatalogDocumentFormat.Result.Emitted -> {
          val library = KobentoComponentExpander.Library.parse(definitionsText, themeText)
          val source = Json.parseToJsonElement(written.content).jsonObject
          when (val expanded = KobentoComponentExpander.expand(source, library)) {
            is KobentoComponentExpander.Result.Expanded ->
              CatalogDocumentFormat.Result.Emitted(expanded.document.toString())
            is KobentoComponentExpander.Result.Refused ->
              CatalogDocumentFormat.Result.Refused(expanded.reasons)
          }
        }
      }
    }
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
