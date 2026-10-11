package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `wear-m3` and `remote-m3`, as checked-in JSON: a record of what the Kotlin generators produce
 * ([`UI_BUILDER_CATALOG_CONTRACT.md`](../../../../../../../../docs/design/UI_BUILDER_CATALOG_CONTRACT.md)),
 * giving catalog repositories a readable description and the equivalence gate a left-hand side. The
 * runtime still constructs these catalogs.
 *
 * A failure means `wearM3Catalog`/`remoteM3Catalog` or the packaged Material 3 catalog changed.
 * Run `scripts/regenerate-goldens.sh` (which passes `-PuiBuilderGoldens=write`; a Gradle property
 * because `-D` does not reach the forked test JVM) and read the diff.
 */
class SynthesisedCatalogGoldenTest {

  private val executor =
    CurrentM3UiBuilderCatalogExecutor(
      catalogSystemIds =
        setOf(
          CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID,
        )
    )

  @Test
  fun `the wear-m3 catalog matches its checked-in golden`() {
    assertGolden(CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID)
  }

  @Test
  fun `the remote-m3 catalog matches its checked-in golden`() {
    assertGolden(CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID)
  }

  @Test
  fun `the a2ui catalog matches its checked-in golden`() {
    assertGolden(CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID)
  }

  @Test
  fun `both goldens declare their own catalog id and platform`() {
    // Cheap, and it is the pair of fields the whole contract turns on: a catalog is identified by
    // its id and grouped by its platform WORD, and a golden that agreed with the other one about
    // either would be a golden of the wrong catalog.
    val wear = catalog(CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID)
    val remote = catalog(CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID)

    assertEquals("wear-m3", wear["benchmark"]?.let { (it as JsonObject) }?.catalogSystemId())
    assertEquals("remote-m3", remote["benchmark"]?.let { (it as JsonObject) }?.catalogSystemId())
    assertEquals("wear", wear.platform())
    assertEquals("remote-compose", remote.platform())
    val a2ui = catalog(CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID)
    assertEquals("a2ui-catalog", (a2ui["benchmark"] as JsonObject).catalogSystemId())
    assertEquals("a2ui", a2ui.platform())
  }

  private fun assertGolden(systemId: String) {
    val expected = pretty.encodeToString(JsonObject.serializer(), catalog(systemId))
    val file = goldenFor(systemId)
    if (System.getProperty(WRITE_PROPERTY) == "write") {
      check(UiBuilderBuildFeatures.remoteCompose) {
        "Regenerate the complete catalog vocabulary with -PuiBuilderRemoteCompose=true"
      }
      file.parentFile.mkdirs()
      file.writeText(expected + "\n")
      return
    }
    assertEquals(
      expected.trim(),
      file.takeIf { it.isFile }?.let(::expectedForBuild)?.trim(),
      "${file.path} is stale or missing. Re-run with -PuiBuilderGoldens=write and read the diff: " +
        "it is the description a catalog repository has to be able to reproduce.",
    )
  }

  // The golden retains the complete vocabulary. The disabled build omits only the experimental
  // selection property; every other field and its ordering must still match the committed catalog.
  private fun expectedForBuild(file: File): String {
    if (UiBuilderBuildFeatures.remoteCompose) return file.readText()
    val catalog = pretty.parseToJsonElement(file.readText()) as JsonObject
    val components =
      (catalog.getValue("components") as JsonArray).map { element ->
        val component = element as JsonObject
        val properties =
          (component.getValue("properties") as JsonArray).filterNot {
            ((it as JsonObject)["name"] as? JsonPrimitive)?.content == "showByState"
          }
        JsonObject(component + ("properties" to JsonArray(properties)))
      }
    return pretty.encodeToString(
      JsonObject.serializer(),
      JsonObject(catalog + ("components" to JsonArray(components))),
    )
  }

  /**
   * The catalog as a sorted `JsonObject`, round-tripped through the wire type so the golden is the
   * bytes a published `ui-builder.json` would be compared against.
   */
  private fun catalog(systemId: String): JsonObject {
    val catalog = executor.listCatalogs().single { it.benchmark.catalogSystemId == systemId }
    val encoded = pretty.encodeToString(catalog)
    return sortKeys(pretty.parseToJsonElement(encoded) as JsonObject) as JsonObject
  }

  private fun JsonObject.catalogSystemId(): String? =
    (this["catalogSystemId"] as? JsonPrimitive)?.content

  private fun JsonObject.platform(): String? =
    ((this["statusSemantics"] as? JsonObject)?.get("platform") as? JsonPrimitive)?.content

  /**
   * Sorts object keys at every depth, including inside arrays, so data class field order cannot
   * churn the goldens. Element order is information (shelves, allowed values) and is kept.
   */
  private fun sortKeys(value: JsonElement): JsonElement =
    when (value) {
      is JsonObject ->
        buildJsonObject { value.keys.sorted().forEach { put(it, sortKeys(value.getValue(it))) } }
      is JsonArray -> JsonArray(value.map { sortKeys(it) })
      else -> value
    }

  private fun goldenFor(systemId: String): File {
    // Tests run from the module directory; resolve the repository root by the directory, since the
    // file may not exist on first write.
    val fixtures = "docs/design/fixtures/ui-builder"
    val dir = File("../$fixtures").takeIf { it.isDirectory } ?: File(fixtures)
    return File(dir, "$systemId-capabilities-v1.json")
  }

  private companion object {
    const val WRITE_PROPERTY = "ui.builder.goldens"

    val pretty = Json {
      prettyPrint = true
      encodeDefaults = true
    }
  }
}
