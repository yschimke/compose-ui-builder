package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.client.toProtocolDocument
import ee.schimke.composeai.uibuilder.export.A2uiDocumentExporter
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every seed template, against the catalog it is offered for: does it validate, and does it
 * generate source? Templates are documents nobody authored, so nothing else catches their mistakes.
 * A table walk over [UiBuilderNewDesignSeed.templateIds] for every served catalog, so a newly added
 * template is covered automatically.
 */
class SeedTemplateCatalogReadinessTest {

  /** Where the generated Kotlin lands, for a reader and for the evidence in the plan. */
  private val sourceDirectory = File("build/seed-template-sources")

  private val fixture =
    Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject

  /** The catalogs the builder serves, each with its captured published capability document. */
  private val catalogs: Map<String, CapabilityCatalog> =
    mapOf(
      "m3-catalog" to publishedTestCatalog("m3-catalog"),
      "wear-m3" to publishedTestCatalog("wear-m3"),
      "remote-m3" to publishedTestCatalog("remote-m3"),
      A2uiDocumentExporter.CATALOG_SYSTEM_ID to SyntheticCatalogs.a2ui,
    )

  @Test
  fun `every template of every catalog validates against that catalog`() {
    val failures = catalogs.flatMap { (systemId, catalog) ->
      UiBuilderNewDesignSeed.templateIds(systemId).sorted().mapNotNull { templateId ->
        val document = seed(systemId, templateId)
        val validation = CapabilityValidator(catalog).validate(document)
        if (validation.structurallyValid) null
        else "$systemId/$templateId: ${validation.issues.joinToString { it.message }}"
      }
    }

    assertEquals(emptyList(), failures, failures.joinToString("\n"))
  }

  @Test
  fun `every template generates source`() {
    val failures = catalogs.flatMap { (systemId, catalog) ->
      UiBuilderNewDesignSeed.templateIds(systemId).sorted().mapNotNull { templateId ->
        when (val outcome = generate(catalog, seed(systemId, templateId))) {
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
   * An A2UI design's Kotlin is the app that sends it, plus the payload; every template must produce
   * both, with the Kotlin sending exactly the JSON's components.
   */
  @Test
  fun `every A2UI template exports its messages as A2UI JSON`() {
    val systemId = A2uiDocumentExporter.CATALOG_SYSTEM_ID
    val catalog = catalogs.getValue(systemId)
    assertEquals(UiBuilderCatalogPlatform.A2UI, catalog.platform)
    UiBuilderNewDesignSeed.templateIds(systemId).sorted().forEach { templateId ->
      val document = seed(systemId, templateId)
      val messages =
        assertIs<A2uiDocumentExporter.Result.Emitted>(
          A2uiDocumentExporter.export(document),
          "$systemId/$templateId",
        )
      val components =
        messages.messages.last().getValue("updateComponents").jsonObject.getValue("components")
          as JsonArray
      val kotlin = assertIs<Generated.Source>(generate(catalog, document)).kotlin
      components.forEach { component ->
        val id = component.jsonObject.getValue("id").jsonPrimitive.content
        assertTrue("id = \"$id\"" in kotlin, "$systemId/$templateId: the Kotlin does not send $id")
      }
    }
  }

  /**
   * The Jetcaster fixture ([UiBuilderNewDesignSeed.FIXTURE_TEMPLATE]) is no longer offered but
   * remains the benchmark, and refuses for the **projection's** reasons: a carousel `items` DSL
   * (`CarouselScope`), wrapper-owned grid spans, and `selected` state comparisons have no Kotlin in
   * this vocabulary (and it uses `m3/snackbar-host`, absent from the published `m3-catalog`). If it
   * starts exporting, this fails so someone notices.
   */
  @Test
  fun `jetcaster refuses for reasons no component record fixes`() {
    val catalog = catalogs.getValue("m3-catalog")
    val document = seed("m3-catalog", UiBuilderNewDesignSeed.FIXTURE_TEMPLATE)

    val reasons =
      assertIs<Generated.Refused>(
          generate(catalog, document),
          "the Jetcaster fixture exported: it could be offered as a template again",
        )
        .reasons

    // The projection's own refusals. The pane scaffold isn't among them: its directive, value and
    // spacing (via `PaneScaffoldDirective.copy`) are written.
    assertTrue(
      reasons.none { it.contains("PaneScaffoldDirective") },
      "the pane scaffold still refuses: ${reasons.joinToString("; ")}",
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

  private sealed interface Generated {
    data class Source(val kotlin: String) : Generated

    data class Refused(val reasons: List<String>) : Generated
  }

  /**
   * The same two lanes as the editor's Code pane, in order: the dedicated emitters
   * ([RecordFreeExport], for Wear screen and Remote widget catalogs), then the record-driven
   * generator.
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

  private fun seed(systemId: String, templateId: String): UiBuilderDocument =
    UiBuilderNewDesignSeed.document(
      designId = "$systemId-$templateId",
      catalogSystemId = systemId,
      templateId = templateId,
      catalogRevision = "readiness",
      nativeRuntimeId = "readiness",
      fixture = fixture,
    )

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
