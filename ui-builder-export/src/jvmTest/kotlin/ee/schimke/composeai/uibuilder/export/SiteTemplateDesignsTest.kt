package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The landing page's template designs (`site/designs/`) are exactly what New from template seeds.
 *
 * The site opens these in the real editor, for its screenshots and its live gallery, so a template
 * change that did not reach them would advertise a starting point the builder no longer gives. Each
 * is seeded here by [UiBuilderNewDesignSeed], the same object the server's New design form and the
 * browser's local designs run, against the catalog pin of the capability file the editor bundles.
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
      "wear-list" to
        Triple("wear-m3", UiBuilderNewDesignSeed.WEAR_LIST_TEMPLATE, "Wear list screen"),
      "weather-widget" to
        Triple("remote-m3", WearWidgetSample.Weather.templateId, WearWidgetSample.Weather.label),
    )

  @Test
  fun `the site's template designs are what the templates seed`() {
    val root = repositoryRoot()
    val fixture =
      Json.parseToJsonElement(
          File(root, "docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
            .readText()
        )
        .jsonObject
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
      val document =
        UiBuilderNewDesignSeed.document(
            designId = name,
            catalogSystemId = catalog,
            templateId = template,
            catalogRevision = pin.getValue("catalogRevision").jsonPrimitive.content,
            nativeRuntimeId = pin.getValue("nativeRuntimeId").jsonPrimitive.content,
            fixture = fixture,
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
