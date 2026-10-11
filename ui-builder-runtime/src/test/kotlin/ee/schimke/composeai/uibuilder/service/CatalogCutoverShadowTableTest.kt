package ee.schimke.composeai.uibuilder.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shadow report each captured catalog gives today (what compose-preview-server logs at
 * startup), computed by [CatalogCutoverShadow.report] against [CatalogCutoverFixtures] and
 * committed so changes show as a diff. A row gaining a finding or loss is a catalog regression, not
 * a table to update.
 *
 * - [Row.ready]: owning it would refuse nothing it serves now and take nothing away.
 * - [Row.findings]: what owning it would refuse — the gap ledger's lines for that catalog.
 * - [Row.losses]: what owning it would take away, against its Kotlin catalog; `null` when nothing
 *   is synthesised for it.
 */
class CatalogCutoverShadowTableTest {

  private data class Row(val ready: Boolean, val findings: List<String>, val losses: List<String>?)

  private val expected: Map<String, Row> =
    mapOf(
      "m3-catalog" to
        Row(
          ready = false,
          findings = emptyList(),
          losses = listOf("m3/navigation-suite-scaffold: role Scaffold -> Container"),
        ),
      "wear-m3" to
        Row(
          ready = true,
          findings = emptyList(),
          losses = emptyList(),
        ),
      "remote-m3" to
        Row(
          ready = true,
          findings = emptyList(),
          losses = emptyList(),
        ),
      "a2ui-catalog" to
        Row(
          ready = false,
          findings = emptyList(),
          losses =
            listOf(
              "a2ui/AudioPlayer: property description type [\"string\",\"object\"] -> \"string\"",
              "a2ui/AudioPlayer: property url type [\"string\",\"object\"] -> \"string\"",
              "a2ui/Button: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui/CheckBox: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui/CheckBox: property label type [\"string\",\"object\"] -> \"string\"",
              "a2ui/CheckBox: property value type [\"boolean\",\"object\"] -> \"boolean\"",
              "a2ui/ChoicePicker: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui/ChoicePicker: property label type [\"string\",\"object\"] -> \"string\"",
              "a2ui/ChoicePicker: property options type [\"array\",\"object\"] -> \"array\"",
              "a2ui/ChoicePicker: property value type [\"array\",\"object\"] -> \"array\"",
              "a2ui/Column: slot children cardinality 0..null -> 1..null",
              "a2ui/DateTimeInput: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui/DateTimeInput: property label type [\"string\",\"object\"] -> \"string\"",
              "a2ui/DateTimeInput: property max type [\"string\",\"object\"] -> \"string\"",
              "a2ui/DateTimeInput: property min type [\"string\",\"object\"] -> \"string\"",
              "a2ui/DateTimeInput: property value type [\"string\",\"object\"] -> \"string\"",
              "a2ui/Image: property description type [\"string\",\"object\"] -> \"string\"",
              "a2ui/Image: property url type [\"string\",\"object\"] -> \"string\"",
              "a2ui/List: slot children cardinality 0..null -> 1..null",
              "a2ui/Row: slot children cardinality 0..null -> 1..null",
              "a2ui/Slider: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui/Slider: property label type [\"string\",\"object\"] -> \"string\"",
              "a2ui/Slider: property value type [\"number\",\"object\"] -> \"number\"",
              "a2ui/Tabs: properties: loses titles; gains tabs",
              "a2ui/Tabs: slots: loses tabs",
              "a2ui/Text: property text type [\"string\",\"object\"] -> \"string\"",
              "a2ui/TextField: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui/TextField: property label type [\"string\",\"object\"] -> \"string\"",
              "a2ui/TextField: property value type [\"string\",\"object\"] -> \"string\"",
              "a2ui/Video: property url type [\"string\",\"object\"] -> \"string\"",
            ),
        ),
      "glimmer-catalog" to
        Row(
          ready = false,
          findings =
            listOf("glimmer-catalog: declares no composeSourceExport, so no export is offered"),
          losses = null,
        ),
      "remote-widgets" to
        Row(
          ready = true,
          findings = emptyList(),
          losses = null,
        ),
    )

  @Test
  fun `each catalog's shadow report is exactly the committed table`() {
    val actual =
      CatalogCutoverFixtures.catalogIds.associateWith { id ->
        val report = report(id)
        Row(report.ready, report.findings, report.losses)
      }
    // Per catalog, so a failure names the catalog whose answer moved.
    for (id in CatalogCutoverFixtures.catalogIds) {
      assertEquals(expected[id], actual[id], "$id's shadow report")
    }
    assertEquals(expected.keys, actual.keys)
  }

  @Test
  fun `the catalogs the switch is waiting on are ready, bar m3-catalog's scaffold role`() {
    // The four shadowed on preview.coo.ee. m3-catalog's one loss clears when compose-ai-tools
    // publishes a record component's `shelfRole` and m3-catalog states it for the scaffold.
    assertEquals(
      mapOf(
        "wear-m3" to true,
        "remote-m3" to true,
        "remote-widgets" to true,
        "m3-catalog" to false,
      ),
      listOf("wear-m3", "remote-m3", "remote-widgets", "m3-catalog").associateWith {
        expected.getValue(it).ready
      },
    )
  }

  /**
   * The rest of each report: the differences that are not losses. A delivery branch that adds a
   * component, trait or property, or relaxes a constraint, changes what the server logs while
   * `ready`, `findings` and `losses` stay put, so the table above alone let such a recapture pass
   * unreviewed. Too long to sit in the table (a couple of hundred lines), so it is a committed text
   * file, rewritten with `-PuiBuilderGoldens=write` and read as a diff.
   */
  @Test
  fun `each catalog's other differences are exactly the committed golden`() {
    val actual =
      CatalogCutoverFixtures.catalogIds.sorted().joinToString("") { id ->
        val report = report(id)
        val others = report.differences?.minus(report.losses.orEmpty().toSet())
        "## $id\n" +
          (others?.joinToString("") { "$it\n" } ?: "(the build synthesises nothing to compare)\n")
      }
    val file = File(DIFFERENCES_GOLDEN)
    if (System.getProperty("ui.builder.goldens") == "write") {
      file.writeText(actual)
      return
    }
    assertEquals(
      file.takeIf { it.isFile }?.readText(),
      actual,
      "$DIFFERENCES_GOLDEN is stale or missing. Re-run with -PuiBuilderGoldens=write and read " +
        "the diff: every line is one the server's shadow log changes by.",
    )
  }

  private fun report(id: String): CatalogCutoverShadow.Report =
    CatalogCutoverShadow.report(
      catalogId = id,
      published = CatalogCutoverFixtures.catalog(id),
      templates = CatalogCutoverFixtures.templates(id),
      fixture = CatalogCutoverProbe.fixture,
      packComponents = CatalogCutoverFixtures.composed(id).records,
      exportRecord = CatalogCutoverFixtures.exportRecord(id),
      nativeRuntimeId = CatalogCutoverFixtures.rendererRuntimeId(id),
    )

  private companion object {
    /** Relative to the module, the test's working directory; a test resource, so a task input. */
    const val DIFFERENCES_GOLDEN = "src/test/resources/cutover-shadow-differences.txt"
  }
}
