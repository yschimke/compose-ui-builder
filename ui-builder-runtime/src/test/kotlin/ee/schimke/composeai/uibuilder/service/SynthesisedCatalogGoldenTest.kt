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
 * `a2ui-catalog`, as JSON, checked in — and, before them, `wear-m3` and `remote-m3`.
 *
 * ## Why a golden of something we generate
 *
 * A catalog written in Kotlin here is a description nobody outside this repository can read or
 * diff. The plan
 * ([`UI_BUILDER_CATALOG_CONTRACT.md`](../../../../../../../../docs/design/UI_BUILDER_CATALOG_CONTRACT.md))
 * moves that knowledge into the repositories that own it, and its first step was writing down what
 * the generators produced, so a catalog repository had something to reproduce and the equivalence
 * gate had a left-hand side.
 *
 * `wear-m3` and `remote-m3` went all the way: wear-m3-catalog publishes both, every deployment
 * serves them from that, and their generators are deleted (#819 step 3). Their two files stay as
 * frozen fixtures of what is published — tests read them through [PublishedCatalogFixtures] and the
 * gate still compares them — but nothing here writes them. `a2ui-catalog` is still generated here,
 * so its golden is still asserted.
 *
 * ## What a failure means
 *
 * Somebody changed `a2uiCatalog` — or the packaged Material 3 catalog it is derived from, which is
 * the case people are surprised by. Run **`scripts/regenerate-goldens.sh`**, which rewrites every
 * committed golden this repository generates, and **read the diff**.
 *
 * That script passes `-PuiBuilderGoldens=write`, which is what this class reads. A Gradle property
 * rather than a bare `-D`, because `-D` on the command line reaches the Gradle JVM and not the
 * forked test JVM, and the silent no-op that follows is a confusing half hour.
 */
class SynthesisedCatalogGoldenTest {

  private val executor =
    CurrentM3UiBuilderCatalogExecutor(
      catalogSystemIds =
        setOf(
          CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID,
        )
    )

  // `wear-m3` and `remote-m3` have no golden test any more: the generators they were goldens of are
  // deleted (#819 step 3), and the two files are frozen fixtures of the catalogs wear-m3-catalog
  // publishes, which `ui-builder-equivalence.sh --strict` holds to the published policy in CI.

  @Test
  fun `the a2ui catalog matches its checked-in golden`() {
    assertGolden(CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID)
  }

  @Test
  fun `every catalog fixture declares its own catalog id and platform`() {
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
   * The catalog as a `JsonObject`, sorted so the file is byte-reproducible.
   *
   * Round-tripped through the wire type rather than reflected over, because the golden's whole
   * purpose is to be the same bytes a published `ui-builder.json` would be compared against, and a
   * rendering of the in-memory object would compare a shape nothing publishes.
   */
  private fun catalog(systemId: String): JsonObject {
    val catalog =
      if (systemId in PublishedCatalogFixtures.servedIds) PublishedCatalogFixtures.catalog(systemId)
      else executor.listCatalogs().single { it.benchmark.catalogSystemId == systemId }
    val encoded = pretty.encodeToString(catalog)
    return sortKeys(pretty.parseToJsonElement(encoded) as JsonObject) as JsonObject
  }

  private fun JsonObject.catalogSystemId(): String? =
    (this["catalogSystemId"] as? JsonPrimitive)?.content

  private fun JsonObject.platform(): String? =
    ((this["statusSemantics"] as? JsonObject)?.get("platform") as? JsonPrimitive)?.content

  /**
   * Key order is not information here, and an unsorted golden would diff on it.
   *
   * Object keys are sorted at every depth, **including inside arrays** — a catalog's components,
   * properties and slots all live in arrays of objects, so stopping at the array boundary left
   * hundreds of objects per golden ordered by whatever their serializer emitted. Reordering a field
   * in a data class would then rewrite the goldens without changing a single fact, which is the
   * churn this sorting exists to prevent.
   *
   * ELEMENT order is untouched, and that is a different thing from key order. Shelf order, allowed
   * values and modifier capabilities are all information carried by an array's sequence, and
   * sorting one would hide a real change.
   */
  private fun sortKeys(value: JsonElement): JsonElement =
    when (value) {
      is JsonObject ->
        buildJsonObject { value.keys.sorted().forEach { put(it, sortKeys(value.getValue(it))) } }
      is JsonArray -> JsonArray(value.map { sortKeys(it) })
      else -> value
    }

  private fun goldenFor(systemId: String): File {
    // A unit test runs with the module directory as its working directory; `..` reaches the
    // repository root, the way every other fixture-reading test here does it. Resolved by the
    // DIRECTORY rather than by the file: on the first write the file does not exist yet, and a
    // fallback keyed on it would silently create `ui-builder-runtime/docs/design/…` instead.
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
