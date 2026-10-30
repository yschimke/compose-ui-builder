package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The landing page's template designs (`site/designs/`) are exactly what New from template seeds
 * via [UiBuilderNewDesignSeed], against the bundled capability file's pin.
 *
 * Regenerate after a template change: `UPDATE_SITE_DESIGNS=1 ./gradlew :ui-builder-export:jvmTest
 * --tests '*SiteTemplateDesignsTest*'`.
 */
class SiteTemplateDesignsTest {

  private val json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
  }

  /** File name to (catalog, template, title). */
  private val designs =
    mapOf(
      "wear-list" to Triple("wear-m3", "wear-list", "Wear list screen"),
      "weather-widget" to
        Triple("remote-m3", WearWidgetSample.Weather.templateId, WearWidgetSample.Weather.label),
    )

  @Test
  fun `the site's template designs are what the catalogs' published templates seed`() {
    val root = repositoryRoot()
    val update = System.getenv("UPDATE_SITE_DESIGNS") != null
    val stale = mutableListOf<String>()
    for ((name, design) in designs) {
      val (catalog, template, title) = design
      val pin =
        Json.parseToJsonElement(
            File(root, "docs/design/fixtures/ui-builder/$catalog-capabilities-v1.json").readText()
          )
          .jsonObject
          .getValue("benchmark")
          .jsonObject
      // The catalog's captured published document, as `/templates` serves it.
      val captured =
        File(root, "ui-builder-runtime/src/test/resources/catalog-cutover/$catalog/designs")
      val published =
        when (
          val read =
            CatalogSeedTemplates.read(catalog, listOf("ui-builder/designs/$template.json")) {
              File(captured, it.substringAfterLast('/')).takeIf(File::isFile)?.readText()
            }
        ) {
          is CatalogSeedTemplates.Result.Read -> read.templates
          is CatalogSeedTemplates.Result.Unusable -> error(read.reason)
        }
      val document =
        UiBuilderNewDesignSeed.document(
            designId = name,
            templateId = template,
            catalogPin =
              CatalogReferenceV1(
                catalog,
                pin.getValue("catalogRevision").jsonPrimitive.content,
                "candidate",
                pin.getValue("nativeRuntimeId").jsonPrimitive.content,
              ),
            published = published,
          )
          .copy(title = title)
      val text =
        json.encodeToString(DesignDocumentV1.serializer(), document.toDesignDocumentV1()) + "\n"
      val file = File(root, "site/designs/$name.uid")
      if (update) {
        file.parentFile.mkdirs()
        file.writeText(text)
      } else if (!file.isFile || file.readText() != text) {
        stale += file.relativeTo(root).path
      }
    }
    assertEquals(
      emptyList(),
      stale,
      "Stale site designs. Regenerate with UPDATE_SITE_DESIGNS=1 ./gradlew " +
        ":ui-builder-export:jvmTest --tests '*SiteTemplateDesignsTest*'",
    )
  }

  private fun repositoryRoot(): File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .first { File(it, "site/index.html").isFile }
}
