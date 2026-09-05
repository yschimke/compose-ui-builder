package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1

/**
 * The one answer to "would the Compose export refuse this design, and why?".
 *
 * ## Why one
 *
 * Two things asked that question and answered it differently. The server's export ran
 * [ScreenDocumentProjection] and the real `ScreenGenerator`; the browser editor's problems panel
 * ran `CapabilityComposeCodeExporter`, which has an emitter for every catalog id. So the panel
 * could say nothing was blocking an export right up until the export refused — most often with
 * `NO_COMPONENT_RECORD`, for a component the panel had no idea was unbacked.
 *
 * They can share now, and could not before: the projection and the generator are multiplatform as
 * of `ee.schimke.composeai:screen-model` 1.77.0, so the editor reaches the same code rather than
 * approximating it.
 *
 * ## Why the constants live here
 *
 * [EXPRESSION_PACKAGES] is a **security** guard, not a formatting choice — the generator refuses
 * any reference, construct or chain link outside it rather than emitting a call into a package the
 * document's author picked. A copy of it beside a second caller is a copy that can be widened
 * without the first caller's review, which is the one kind of drift that matters here. Same for
 * [PACKAGE_NAME]: a panel judging a document against a different package than the export uses would
 * disagree on exactly the shadowing cases the generator refuses.
 */
object ScreenExportGate {

  /** The packages a generated screen may name. Narrow on purpose; widening is a reviewed act. */
  val EXPRESSION_PACKAGES: Set<String> = setOf("androidx.compose")

  /** The package a generated screen is emitted into. */
  const val PACKAGE_NAME: String = "generated.uibuilder"

  /**
   * The whole answer, rather than the half each caller happened to need first.
   *
   * Two surfaces want two halves of this and neither should run the generator twice to get its own:
   * the problems panel wants [Refused.reasons], the code pane wants [Emitted.source]. A gate that
   * could only report refusals is why the editor kept a second emitter — it had no way to ask this
   * one for the Kotlin.
   */
  sealed interface Outcome {
    /** The Kotlin the export would write. */
    data class Emitted(val source: String) : Outcome

    /** Every unexpressible thing found, not the first — a builder wants the whole list. */
    data class Refused(val reasons: List<String>) : Outcome
  }

  /** What the export would produce for [document], or why it would refuse. */
  fun export(document: DesignDocumentV1, record: ComponentRecordFile?): Outcome {
    if (record == null) {
      // Named rather than silent: "this host has no record for the catalog" is a different problem
      // from "this design is unexpressible", and only the first is fixed by configuration.
      return Outcome.Refused(
        listOf("no component record is configured for this catalog, so nothing can be exported")
      )
    }
    return when (val projected = ScreenDocumentProjection.project(document)) {
      is ScreenDocumentProjection.Outcome.Refused -> Outcome.Refused(projected.reasons)
      is ScreenDocumentProjection.Outcome.Projected ->
        when (
          val generated =
            ScreenGenerator.generate(projected.document, record, PACKAGE_NAME, EXPRESSION_PACKAGES)
        ) {
          is ScreenGenerator.Result.Refused -> Outcome.Refused(generated.reasons)
          is ScreenGenerator.Result.Emitted -> Outcome.Emitted(generated.source)
        }
    }
  }

  /** Why the export would refuse [document], or empty when it would succeed. */
  fun refusals(document: DesignDocumentV1, record: ComponentRecordFile?): List<String> =
    when (val outcome = export(document, record)) {
      is Outcome.Refused -> outcome.reasons
      is Outcome.Emitted -> emptyList()
    }
}
