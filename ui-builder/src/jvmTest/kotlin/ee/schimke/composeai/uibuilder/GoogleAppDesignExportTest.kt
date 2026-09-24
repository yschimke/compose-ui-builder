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
 * The five Google-app sample designs, through the same gate the editor's Code pane and the server's
 * Compose export ask: each one has to come out as Kotlin.
 *
 * The samples exist to show that a real tablet screen can be built against the real composables and
 * the AndroidX adaptive libraries. A sample the export refuses shows the opposite — the canvas
 * draws it, and nothing can be taken away from it — so a refusal here is a finding about the
 * builder, not about the sample.
 *
 * `DesignFixturesTest` already exports every fixture, but through `CapabilityComposeCodeExporter`,
 * which has a hand-written emitter for every catalog id. That is how all five passed there while
 * the server's export — this gate — refused every one of them.
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
   * `singlePane` and a pane spacing adjust the computed directive the way the canvas does: one
   * `copy(…)` member call, whose result is both the `directive` argument and the receiver the value
   * reads its partition count from. The generator holds each receiver in a typed local, so the
   * adjusted directive is the thing the scaffold and its value agree on.
   */
  @Test
  fun `singlePane and a pane spacing export through the directive's copy`() {
    val document = designFixtureDocument("google-gmail-tablet").toProtocolDocument()
    val scaffold =
      document.nodes.values.single { it.componentId == "layout/supporting-pane-scaffold" }
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
        "google-photos-tablet",
        "google-keep-tablet",
        "google-play-tablet",
      )
  }
}
