package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject

/**
 * The ratchet that keeps the core catalog-agnostic (`docs/design/UI_BUILDER_CATALOG_FORMATS.md`
 * §5).
 *
 * It counts the lines in the core source sets that name a specific catalog or its format, per file,
 * against the committed baseline `catalog-agnostic-core-baseline.json`. A count may only go down: a
 * new branch on a catalog fails here with the lines that added it, and a migration that removes
 * some fails until the baseline is lowered to match, so the gain is kept. The end state is an empty
 * baseline.
 *
 * Format code itself (`Kobento*`, `WatchFace*`, `Wff*`, `Lovelace*`, `A2ui*` files) and the one
 * list of shipped formats (`BuiltInCatalogDocumentFormats`) are where such names belong, and are
 * not counted. Neither are host catalog inventories (locations, the desktop's catalog list): they
 * are data, not branches.
 */
class CatalogAgnosticCoreTest {

  @Test
  fun `the core names no more catalogs than the baseline allows`() {
    val counts = coreCounts()
    val baseline = baseline()
    val grown = counts.filter { (file, lines) -> lines.size > (baseline[file] ?: 0) }
    if (grown.isNotEmpty()) {
      fail(
        "These core files now name a specific catalog or format more often than the baseline " +
          "allows. Dispatch through CatalogDocumentFormats or a declared catalog fact instead " +
          "(docs/design/UI_BUILDER_CATALOG_FORMATS.md):\n" +
          grown.entries.joinToString("\n") { (file, lines) ->
            "$file (baseline ${baseline[file] ?: 0}):\n" + lines.joinToString("\n") { "  $it" }
          },
      )
    }
    val shrunk = baseline.filter { (file, allowed) -> (counts[file]?.size ?: 0) < allowed }
    if (shrunk.isNotEmpty()) {
      fail(
        "Catalog-specific references went down: lower the baseline to keep the gain. Replace " +
          "ui-builder-export/src/jvmTest/resources/catalog-agnostic-core-baseline.json's " +
          "\"files\" with:\n" +
          counts.toSortedMap().entries.joinToString(",\n", "{\n", "\n}") { (file, lines) ->
            "  \"$file\": ${lines.size}"
          },
      )
    }
  }

  @Test
  fun `the core names no Kobento at all`() {
    // Kobento was the first format moved onto the seam: it is gone from the core, not ratcheted.
    // The contract's wire names for it (`ExportFormatV1.KOBENTO_JSON`, `kobentoJson`) stay in the
    // exhaustive `when`s over the wire format until the generic `DOCUMENT` format replaces them
    // (step 2 of the design); the ratchet still counts them.
    val kobento =
      coreCounts().flatMap { (file, lines) ->
        lines.filter { KOBENTO.containsMatchIn(it.replace(KOBENTO_WIRE, "")) }.map { "$file: $it" }
      }
    assertTrue(kobento.isEmpty(), kobento.joinToString("\n"))
  }

  @Test
  fun `the scan reaches the core it guards`() {
    // A moved directory must not turn the ratchet into a test of nothing.
    val scanned = CORE.flatMap { dir -> sources(File(root, dir)) }
    assertTrue(scanned.any { it.name == "UiBuilderEditorState.kt" }, "editor not scanned")
    assertTrue(scanned.any { it.name == "ProductionUiBuilderRuntime.kt" }, "runtime not scanned")
    assertTrue(scanned.any { it.name == "RecordFreeExport.kt" }, "export not scanned")
    assertEquals(
      emptyList(),
      baseline().keys.filterNot { File(root, it).isFile },
      "baseline names files that no longer exist",
    )
  }

  private val root: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .first { File(it, "settings.gradle.kts").isFile && File(it, "ui-builder-export").isDirectory }

  private fun sources(dir: File): List<File> =
    dir.walkTopDown().filter { it.isFile && it.extension == "kt" && !isFormatFile(it) }.toList()

  private fun isFormatFile(file: File): Boolean =
    FORMAT_FILE.matches(file.nameWithoutExtension) ||
      file.nameWithoutExtension == "BuiltInCatalogDocumentFormats"

  /** Per core file (repository-relative), its lines that name a specific catalog or format. */
  private fun coreCounts(): Map<String, List<String>> =
    CORE.flatMap { dir -> sources(File(root, dir)) }
      .associate { file ->
        file.relativeTo(root).invariantSeparatorsPath to
          file.readLines().map(String::trim).filter { line ->
            SPECIFIC.any { it.containsMatchIn(line) }
          }
      }
      .filterValues { it.isNotEmpty() }

  private fun baseline(): Map<String, Int> {
    val text =
      checkNotNull(javaClass.getResource("/catalog-agnostic-core-baseline.json")) {
          "catalog-agnostic-core-baseline.json is missing"
        }
        .readText()
    val files = Json.parseToJsonElement(text).jsonObject.getValue("files") as JsonObject
    return files.mapValues { (_, count) -> (count as JsonPrimitive).int }
  }

  private companion object {
    /** The source sets that must not know which catalog they serve. */
    val CORE =
      listOf(
        "ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/editor",
        "ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/canvas",
        "ui-builder/src/wasmJsMain/kotlin",
        "ui-builder-runtime/src/main/kotlin/ee/schimke/composeai/uibuilder/service",
        "ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export",
      )

    val FORMAT_FILE = Regex("""(Kobento|WatchFace|Wff|Lovelace|A2ui)\w*""")

    val KOBENTO = Regex("""(?i)kobento""")

    val KOBENTO_WIRE = Regex("""\bExportFormatV1\.KOBENTO_JSON\b|\bkobentoJson\b""")

    val SPECIFIC =
      listOf(
        Regex("""UiBuilderCatalogPlatform\.(WATCH_FACE|LOVELACE|A2UI)\b"""),
        Regex("""\b(WATCH_FACE|A2UI)_PLATFORM\b"""),
        Regex("\"lovelace\""),
        Regex("""\b(Kobento|WatchFace|Wff|Lovelace|A2ui)[A-Z]\w*"""),
        KOBENTO,
      )
  }
}
