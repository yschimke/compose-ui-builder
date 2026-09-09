package ee.schimke.composeai.uibuilder.service

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
 * `wear-m3` and `remote-m3`, as JSON, checked in.
 *
 * ## Why a golden of something we generate
 *
 * These two catalogs are written in Kotlin **here** — synthesised from the packaged Material 3 one
 * at startup, some 1,300 lines of authored capabilities, shelves, variant properties and slot
 * policy for components this repository has never compiled. The plan
 * ([`UI_BUILDER_CATALOG_CONTRACT.md`](../../../../../../../../docs/design/UI_BUILDER_CATALOG_CONTRACT.md))
 * moves that knowledge into the repositories that own it, where it is published as data and read
 * here rather than constructed here.
 *
 * The first step of that is neither writing a loader nor deleting a generator. It is **writing down
 * what the generators currently produce**, because:
 *
 * - wear-m3-catalog cannot reduce a policy file from a description that exists only as Kotlin in
 *   another repository. This is the description, in a form somebody can read and diff.
 * - the equivalence gate needs a left-hand side. "Is wear-m3-catalog ready?" becomes "does the file
 *   it publishes still describe this catalog?", which is a check rather than a judgement.
 * - it costs nothing and rolls back to nothing. **The runtime still constructs these catalogs**;
 *   this test only records what it constructed. Every reader is untouched, so a change of mind at
 *   any later phase discards two JSON files and this test.
 *
 * ## What a failure means
 *
 * Somebody changed `wearM3Catalog` or `remoteM3Catalog` — or changed the packaged Material 3
 * catalog they are both derived from, which is the case people are surprised by. Run
 * **`scripts/regenerate-goldens.sh`**, which rewrites every committed golden this repository
 * generates and is where AGENTS.md and the `/regenerate-goldens` workflow both send people; then
 * **read the diff**: it is the diff a catalog repository will have to reproduce, and after the
 * cutover it is the diff a catalog repository will have to have caused.
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
          CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
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
  }

  private fun assertGolden(systemId: String) {
    val expected = pretty.encodeToString(JsonObject.serializer(), catalog(systemId))
    val file = goldenFor(systemId)
    if (System.getProperty(WRITE_PROPERTY) == "write") {
      file.parentFile.mkdirs()
      file.writeText(expected + "\n")
      return
    }
    assertEquals(
      expected.trim(),
      file.takeIf { it.isFile }?.readText()?.trim(),
      "${file.path} is stale or missing. Re-run with -PuiBuilderGoldens=write and read the diff: " +
        "it is the description a catalog repository has to be able to reproduce.",
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
    val catalog = executor.listCatalogs().single { it.benchmark.catalogSystemId == systemId }
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
