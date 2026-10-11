package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.toProtocolDocument
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.preview.designFixtureDocument
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The six Google-app sample designs, through the same gate as the editor's Code pane and the
 * server's Compose export: each must come out as Kotlin, or it's a finding about the builder.
 * (`DesignFixturesTest` uses `CapabilityComposeCodeExporter`, which has an emitter for every
 * catalog id, so it can't stand in for this gate.)
 */
class GoogleAppDesignExportTest {

  private val sourceDirectory = File("build/google-app-sources")

  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )

  private fun export(designId: String): ScreenExportGate.Outcome =
    ScreenExportGate.export(
      designFixtureDocument(designId).toProtocolDocument(),
      catalog.exportRecord(embeddedComponentRecord()),
    )

  @Test
  fun `every Google app design exports as Kotlin`() {
    val failures = EXPORTABLE.mapNotNull { designId ->
      when (val outcome = export(designId)) {
        is ScreenExportGate.Outcome.Emitted -> {
          // Kept on disk: what the native lane compiles, and the readable evidence that "it
          // exports" means a screen rather than a comment.
          val file = sourceDirectory.resolve("$designId.kt")
          file.parentFile.mkdirs()
          file.writeText(outcome.source)
          null
        }
        is ScreenExportGate.Outcome.Refused ->
          "$designId:\n" + outcome.reasons.joinToString("\n") { "  - $it" }
      }
    }

    assertEquals(emptyList(), failures, failures.joinToString("\n"))
  }

  /**
   * `singlePane` and pane spacing adjust the computed directive as the canvas does: one `copy(…)`
   * held in a typed local, used as both the `directive` argument and the value's receiver.
   */
  @Test
  fun `singlePane and a pane spacing export through the directive's copy`() {
    val document = designFixtureDocument("google-gmail-tablet").toProtocolDocument()
    val scaffold =
      document.nodes.values.single { it.componentId == "layout/list-detail-pane-scaffold" }
    val adjusted =
      document.copy(
        nodes =
          document.nodes +
            (scaffold.id to
              scaffold.copy(
                properties =
                  scaffold.properties +
                    mapOf(
                      "layoutMode" to EnumValueV1("singlePane"),
                      "paneSpacingDp" to DecimalValueV1(12.0),
                    )
              ))
      )
    val outcome = ScreenExportGate.export(adjusted, catalog.exportRecord(embeddedComponentRecord()))
    val source =
      assertIs<ScreenExportGate.Outcome.Emitted>(
          outcome,
          (outcome as? ScreenExportGate.Outcome.Refused)?.reasons?.joinToString("\n").orEmpty(),
        )
        .source
    assertTrue(
      ".copy(maxHorizontalPartitions = 1, horizontalPartitionSpacerSize = 12.dp)" in source,
      source,
    )
    // One computation of the window's directive, however many places read it.
    assertEquals(1, source.split("calculatePaneScaffoldDirective(").size - 1, source)
  }

  private companion object {
    val EXPORTABLE =
      listOf(
        "google-gmail-tablet",
        "google-calendar-tablet",
        "google-docs-tablet",
        "google-photos-tablet",
        "google-keep-tablet",
        "google-play-tablet",
      )
  }
}
