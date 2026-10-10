package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The catalogs an offline host packages, derived from what each catalog **publishes** rather than
 * from this build's Kotlin definitions.
 *
 * `docs/design/fixtures/ui-builder/published/<id>-capabilities-v1.json` is the catalog a deployment
 * serves when it owns `<id>`: the captured `catalog-cutover/<id>/` policy and record, composed by
 * [PublishedUiBuilderCatalog] and served by [CurrentM3UiBuilderCatalogExecutor] with every catalog
 * owned — the same path as preview.coo.ee. Beside it, `published/<id>/designs/` holds the template
 * documents its policy names. The offline and desktop hosts cannot run the composer (the editor's
 * classpath stays service-free), so they read these instead of the `<id>-capabilities-v1.json`
 * goldens [SynthesisedCatalogGoldenTest] writes from the Kotlin catalogs — which is what lets those
 * goldens and their Kotlin catalogs be deleted (UI_BUILDER_CATALOG_CUTOVER.md, step 3).
 *
 * Regenerated with the goldens (`scripts/regenerate-goldens.sh`, `-PuiBuilderGoldens=write`), and
 * after every `scripts/capture-catalog-cutover-fixtures.sh`.
 */
class PublishedCatalogGoldenTest {
  private val pretty = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  private val executor =
    CatalogCutoverFixtures.executor(
      CatalogCutoverFixtures.catalogIds,
      CatalogOwnership.of(CatalogCutoverFixtures.catalogIds.toSet()),
    )

  @Test
  fun `every published catalog's offline capability matches what it publishes`() {
    for (id in CatalogCutoverFixtures.catalogIds) assertGolden(id)
  }

  @Test
  fun `every published catalog's offline templates are the documents it publishes`() {
    for (id in CatalogCutoverFixtures.catalogIds) {
      for (path in CatalogCutoverFixtures.templatePaths(id)) {
        val name = path.substringAfterLast('/')
        val expected = checkNotNull(CatalogCutoverFixtures.text("$id/designs/$name"))
        val file = File(publishedDir(), "$id/designs/$name")
        if (System.getProperty(WRITE_PROPERTY) == "write") {
          file.parentFile.mkdirs()
          file.writeText(expected)
          continue
        }
        assertEquals(
          expected,
          file.takeIf { it.isFile }?.readText(),
          "${file.path} is stale or missing. Re-run with -PuiBuilderGoldens=write.",
        )
      }
    }
  }

  private fun assertGolden(id: String) {
    val served = executor.listCatalogs().single { it.benchmark.catalogSystemId == id }
    val expected =
      pretty.encodeToString(
        JsonObject.serializer(),
        sortKeys(pretty.parseToJsonElement(pretty.encodeToString(served))) as JsonObject,
      )
    val file = File(publishedDir(), "$id-capabilities-v1.json")
    if (System.getProperty(WRITE_PROPERTY) == "write") {
      check(UiBuilderBuildFeatures.remoteCompose) {
        "Regenerate the complete catalog vocabulary with -PuiBuilderRemoteCompose=true"
      }
      file.parentFile.mkdirs()
      file.writeText(expected + "\n")
      return
    }
    assertEquals(
      withoutDisabledVocabulary(expected).trim(),
      file.takeIf { it.isFile }?.readText()?.let(::withoutDisabledVocabulary)?.trim(),
      "${file.path} is stale or missing. Re-run with -PuiBuilderGoldens=write and read the diff.",
    )
  }

  // As [SynthesisedCatalogGoldenTest]: the file keeps the complete vocabulary, and a build without
  // the experimental Remote selection property compares everything else.
  private fun withoutDisabledVocabulary(text: String): String {
    if (UiBuilderBuildFeatures.remoteCompose) return text
    val catalog = pretty.parseToJsonElement(text) as JsonObject
    val components =
      (catalog.getValue("components") as JsonArray).map { element ->
        val component = element as JsonObject
        val properties =
          (component["properties"] as? JsonArray ?: JsonArray(emptyList())).filterNot {
            ((it as JsonObject)["name"] as? JsonPrimitive)?.content == "showByState"
          }
        JsonObject(component + ("properties" to JsonArray(properties)))
      }
    return pretty.encodeToString(
      JsonObject.serializer(),
      JsonObject(catalog + ("components" to JsonArray(components))),
    )
  }

  private fun sortKeys(value: JsonElement): JsonElement =
    when (value) {
      is JsonObject ->
        buildJsonObject { value.keys.sorted().forEach { put(it, sortKeys(value.getValue(it))) } }
      is JsonArray -> JsonArray(value.map { sortKeys(it) })
      else -> value
    }

  private fun publishedDir(): File {
    val fixtures = "docs/design/fixtures/ui-builder"
    val dir = File("../$fixtures").takeIf { it.isDirectory } ?: File(fixtures)
    return File(dir, "published")
  }

  private companion object {
    const val WRITE_PROPERTY = "ui.builder.goldens"
  }
}
