package ee.schimke.composeai.uibuilder.service

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shadow report every captured catalog gives today: exactly what compose-preview-server logs
 * for it at startup (`serve: UI-builder catalog <id> shadow: …`), computed by the same
 * [CatalogCutoverShadow.report] against the same published files ([CatalogCutoverFixtures]).
 *
 * Committed so the answer to "could this catalog be owned now?" is a reviewable fact rather than a
 * box log: a re-capture that changes any row fails here and shows the change as a diff. A row that
 * gains a finding or a loss is a regression in that catalog's repository, not a table to update.
 *
 * - [Row.ready]: owning it would refuse nothing it serves now and take nothing away.
 * - [Row.findings]: what owning it would refuse — the gap ledger's lines for that catalog.
 * - [Row.losses]: what owning it would take away from an editor, against its Kotlin catalog. `null`
 *   when the builder synthesises nothing for the catalog, so there is nothing to compare.
 */
class CatalogCutoverShadowTableTest {

  private data class Row(val ready: Boolean, val findings: List<String>, val losses: List<String>?)

  private val expected: Map<String, Row> =
    mapOf(
      "m3-catalog" to
        Row(
          ready = false,
          findings = emptyList(),
          losses = listOf("m3-catalog/m3/navigation-suite-scaffold: role Scaffold -> Container"),
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
              "a2ui-catalog/a2ui/AudioPlayer: property description type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/AudioPlayer: property url type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/Button: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui-catalog/a2ui/CheckBox: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui-catalog/a2ui/CheckBox: property label type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/CheckBox: property value type [\"boolean\",\"object\"] -> \"boolean\"",
              "a2ui-catalog/a2ui/ChoicePicker: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui-catalog/a2ui/ChoicePicker: property label type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/ChoicePicker: property options type [\"array\",\"object\"] -> \"array\"",
              "a2ui-catalog/a2ui/ChoicePicker: property value type [\"array\",\"object\"] -> \"array\"",
              "a2ui-catalog/a2ui/Column: slot children cardinality 0..null -> 1..null",
              "a2ui-catalog/a2ui/DateTimeInput: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui-catalog/a2ui/DateTimeInput: property label type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/DateTimeInput: property max type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/DateTimeInput: property min type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/DateTimeInput: property value type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/Image: property description type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/Image: property url type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/List: slot children cardinality 0..null -> 1..null",
              "a2ui-catalog/a2ui/Row: slot children cardinality 0..null -> 1..null",
              "a2ui-catalog/a2ui/Slider: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui-catalog/a2ui/Slider: property label type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/Slider: property value type [\"number\",\"object\"] -> \"number\"",
              "a2ui-catalog/a2ui/Tabs: properties: loses titles; gains tabs",
              "a2ui-catalog/a2ui/Tabs: slots: loses tabs",
              "a2ui-catalog/a2ui/Text: property text type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/TextField: property checks type [\"array\",\"object\"] -> \"array\"",
              "a2ui-catalog/a2ui/TextField: property label type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/TextField: property value type [\"string\",\"object\"] -> \"string\"",
              "a2ui-catalog/a2ui/Video: property url type [\"string\",\"object\"] -> \"string\"",
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
        val report =
          CatalogCutoverShadow.report(
            catalogId = id,
            published = CatalogCutoverFixtures.catalog(id),
            templates = CatalogCutoverFixtures.templates(id),
            fixture = CatalogCutoverProbe.fixture,
            packComponents = CatalogCutoverFixtures.composed(id).records,
            exportRecord = CatalogCutoverFixtures.exportRecord(id),
            nativeRuntimeId = CatalogCutoverFixtures.rendererRuntimeId(id),
          )
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
}
