package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.client.toProtocolDocument
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed.Vocabulary.PACKAGED
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Every seed template, against the catalog it is offered for: does it validate, and does it
 * generate source?
 *
 * The question a template has to answer before it can be MOVED anywhere. A template is the one
 * document nobody authored, so nothing else catches a property its catalog does not declare or a
 * child a slot refuses — it opens as an editor full of issues. And a template a catalog repository
 * is asked to carry is only worth carrying if it exports: the two claims the builder makes about
 * every design are that it is valid against its catalog pin and that it can be written as Kotlin.
 *
 * Written as a table walk rather than a test per template, because the point is coverage of
 * [UiBuilderNewDesignSeed.templateIds] for every served catalog: a per-template method cannot fail
 * when a template is added, and a template nobody checked is the case this exists for.
 */
class SeedTemplateCatalogReadinessTest {

  /** Where the generated Kotlin lands, for a reader and for the evidence in the plan. */
  private val sourceDirectory = File("build/seed-template-sources")

  private val fixture =
    Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject

  /**
   * The packaged capability documents the offline hosts (the desktop app, the IntelliJ plugin)
   * author against, with the in-process canvas that speaks the same vocabulary. Their templates are
   * [UiBuilderNewDesignSeed.Vocabulary.PACKAGED]; `m3-catalog` is also the one catalog a server
   * serves built in.
   */
  private val catalogs: Map<String, CapabilityCatalog> =
    mapOf(
      "m3-catalog" to catalog("/m3-catalog-capabilities-v1.json"),
      "wear-m3" to catalog("/wear-m3-capabilities-v1.json"),
      "remote-m3" to catalog("/remote-m3-capabilities-v1.json"),
    )

  /**
   * The same three catalogs as a deployment actually serves them: composed from the
   * `ui-builder.json` each catalog repository publishes (`m3-catalog-out`, and
   * `wear-m3-catalog-out`'s `wear-m3-catalog` and `remote-m3`), with the builder's own vocabulary
   * added — captured from a running server's `listCatalogs`.
   *
   * The frozen documents above are what the retired Kotlin catalogs described. Every template
   * validated against those while the published catalogs refused five of them, so a new Wear design
   * on preview.coo.ee answered with a redirect to a design that was never created. These are what a
   * template has to satisfy now; refresh them from a server when a catalog republishes.
   */
  private val publishedCatalogs: Map<String, CapabilityCatalog> =
    mapOf(
      "m3-catalog" to catalog("/published/m3-catalog-capabilities-v1.json"),
      "wear-m3" to catalog("/published/wear-m3-capabilities-v1.json"),
      "remote-m3" to catalog("/published/remote-m3-capabilities-v1.json"),
    )

  @Test
  fun `every template of every catalog validates against the catalog as published`() {
    val failures = publishedCatalogs.flatMap { (systemId, catalog) ->
      UiBuilderNewDesignSeed.templateIds(systemId).sorted().mapNotNull { templateId ->
        val validation = CapabilityValidator(catalog).validate(seed(systemId, templateId))
        if (validation.structurallyValid) null
        else "$systemId/$templateId: ${validation.issues.joinToString { it.message }}"
      }
    }

    assertEquals(emptyList(), failures, failures.joinToString("\n"))
  }

  @Test
  fun `every template but jetcaster generates source against the catalog as published`() {
    val failures = publishedCatalogs.flatMap { (systemId, catalog) ->
      UiBuilderNewDesignSeed.templateIds(systemId)
        .sorted()
        .filter { "$systemId/$it" !in NOT_EXPORTABLE }
        .mapNotNull { templateId ->
          when (val outcome = generate(catalog, seed(systemId, templateId))) {
            is Generated.Source -> {
              val file = sourceDirectory.resolve("published/$systemId-$templateId.kt")
              file.parentFile.mkdirs()
              file.writeText(outcome.kotlin)
              null
            }
            is Generated.Refused -> "$systemId/$templateId: ${outcome.reasons.joinToString("; ")}"
          }
        }
    }

    assertEquals(emptyList(), failures, failures.joinToString("\n"))
  }

  @Test
  fun `every template of every catalog validates against that catalog`() {
    val failures = catalogs.flatMap { (systemId, catalog) ->
      UiBuilderNewDesignSeed.templateIds(systemId, PACKAGED).sorted().mapNotNull { templateId ->
        val document = seed(systemId, templateId, PACKAGED)
        val validation = CapabilityValidator(catalog).validate(document)
        if (validation.structurallyValid) null
        else "$systemId/$templateId: ${validation.issues.joinToString { it.message }}"
      }
    }

    assertEquals(emptyList(), failures, failures.joinToString("\n"))
  }

  @Test
  fun `every template but jetcaster generates source`() {
    val failures = catalogs.flatMap { (systemId, catalog) ->
      UiBuilderNewDesignSeed.templateIds(systemId, PACKAGED)
        .sorted()
        .filter { "$systemId/$it" !in NOT_EXPORTABLE }
        .mapNotNull { templateId ->
          when (val outcome = generate(catalog, seed(systemId, templateId, PACKAGED))) {
            is Generated.Source -> {
              // Kept on disk: what a catalog repository's round-trip test would compile, and the
              // only readable evidence that "it generates" means a composable and not a comment.
              val file = sourceDirectory.resolve("$systemId-$templateId.kt")
              file.parentFile.mkdirs()
              file.writeText(outcome.kotlin)
              if (!outcome.kotlin.contains("@Composable") || !outcome.kotlin.contains("fun "))
                "$systemId/$templateId: generated no composable function (see $file)"
              else null
            }
            is Generated.Refused -> "$systemId/$templateId: ${outcome.reasons.joinToString("; ")}"
          }
        }
    }

    assertEquals(emptyList(), failures, failures.joinToString("\n"))
  }

  /**
   * `jetcaster` refuses, and the reasons it refuses for are the **projection's** — which is why it
   * is named above rather than waived.
   *
   * The template is the Jetcaster reference design, drawn to exercise the canvas rather than
   * written to be exported: it holds an adaptive `SupportingPaneScaffold` with a pane spacing, a
   * carousel whose `items` is a `CarouselScope` DSL, grid spans belonging to the wrapper around a
   * node, and `selected` properties comparing a state variable. None of those is a component the
   * export has not been shown — they are values this vocabulary has no Kotlin for, so no component
   * record, however complete, makes this document export.
   *
   * Asserted rather than skipped because the interesting change is the one that makes it pass: a
   * template that starts exporting is a template a catalog repository can carry, and this test
   * failing is how anyone learns that.
   */
  @Test
  fun `jetcaster refuses for reasons no component record fixes`() {
    val catalog = catalogs.getValue("m3-catalog")
    val document = seed("m3-catalog", "jetcaster", PACKAGED)

    val reasons =
      assertIs<Generated.Refused>(
          generate(catalog, document),
          "jetcaster exported: drop it from NOT_EXPORTABLE and delete this test",
        )
        .reasons

    // The projection's own refusals, the ones no record can answer.
    // The pane scaffold's refusal. It used to be `layoutMode` itself; the directive and value are
    // computations the projection now writes, and what is left is what that computation cannot
    // spell yet — `PaneScaffoldDirective.copy` for this template's authored pane spacing.
    assertTrue(
      reasons.any { it.contains("PaneScaffoldDirective") },
      "no adaptive pane-scaffold refusal: ${reasons.joinToString("; ")}",
    )
    assertTrue(
      reasons.any { it.contains("CarouselScope") },
      "no carousel-slot refusal: ${reasons.joinToString("; ")}",
    )
    assertTrue(
      reasons.any { it.contains("compares the state variable") },
      "no state-comparison refusal: ${reasons.joinToString("; ")}",
    )
    // The catalog is not the reason: the document validates against it, which is the whole point of
    // separating the two questions a template has to answer.
    assertTrue(CapabilityValidator(catalog).validate(document).structurallyValid)
  }

  private companion object {
    /**
     * `<catalog>/<template>` pairs the Compose export refuses, each with a test below saying why.
     *
     * A list of one. It is not a waiver: the named test asserts the refusal and its reasons, so
     * this entry cannot outlive the problem silently.
     */
    val NOT_EXPORTABLE: Set<String> = setOf("m3-catalog/jetcaster")
  }

  private sealed interface Generated {
    data class Source(val kotlin: String) : Generated

    data class Refused(val reasons: List<String>) : Generated
  }

  /**
   * The same two lanes the editor's Code pane asks in the same order: the dedicated emitters first
   * ([RecordFreeExport], which answers for the Wear screen and the Remote widget catalogs), then
   * the record-driven generator for everything else.
   */
  private fun generate(catalog: CapabilityCatalog, document: UiBuilderDocument): Generated {
    RecordFreeExport.generate(
        document,
        catalog.platform,
        packComponents = catalog.packComponentsById(),
      )
      ?.let { recordFree ->
        return when (recordFree) {
          is RecordFreeExport.Generated.Emitted -> Generated.Source(recordFree.source)
          is RecordFreeExport.Generated.Refused -> Generated.Refused(recordFree.reasons)
        }
      }
    return when (
      val outcome =
        ScreenExportGate.export(
          document.toProtocolDocument(),
          catalog.exportRecord(embeddedComponentRecord()),
        )
    ) {
      is ScreenExportGate.Outcome.Emitted -> Generated.Source(outcome.source)
      is ScreenExportGate.Outcome.Refused -> Generated.Refused(outcome.reasons)
    }
  }

  private fun seed(
    systemId: String,
    templateId: String,
    vocabulary: UiBuilderNewDesignSeed.Vocabulary = UiBuilderNewDesignSeed.Vocabulary.PUBLISHED,
  ): UiBuilderDocument =
    UiBuilderNewDesignSeed.document(
      designId = "$systemId-$templateId",
      catalogSystemId = systemId,
      templateId = templateId,
      catalogRevision = "readiness",
      nativeRuntimeId = "readiness",
      fixture = fixture,
      vocabulary = vocabulary,
    )

  private fun catalog(path: String): CapabilityCatalog =
    CapabilityCatalogParser.parse(resource(path))

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
