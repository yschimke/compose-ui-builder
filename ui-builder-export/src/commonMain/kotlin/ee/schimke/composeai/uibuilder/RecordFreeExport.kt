package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1

/**
 * The designs whose Kotlin comes from a **dedicated emitter** rather than from a discovered
 * component record.
 *
 * `ScreenGenerator` writes a call site only where a component record proves one can be written, and
 * that is the right discipline for a Material 3 screen made of catalog components. It is not
 * available to every design this builder authors: a Wear widget ships as a `WearWidgetDocument` of
 * Remote Compose, and a Wear screen's `ScreenScaffold` takes a scroll state that has to agree with
 * the list inside its own content lambda. Neither can be recovered from a record, so both catalogs
 * deliberately have none — and both nevertheless generate source, through [WearWidgetCodeExporter]
 * and [WearScreenCodeExporter].
 *
 * Collected here rather than restated at each caller because three surfaces ask the same question
 * and used to answer it separately: the editor's Code pane, the server's Compose-export action, and
 * the per-catalog `composeCode` capability that decides whether the action is offered at all. A
 * catalog advertised as exportable whose designs then refuse, or a design that generates in the
 * pane and refuses in the export, is exactly the disagreement `:ui-builder-export` exists to end.
 */
object RecordFreeExport {

  /**
   * The catalog system ids whose designs export without a component record.
   *
   * Derived from the roots the emitters actually accept rather than declared beside them: a
   * component id is `<catalog system id>/<component>` throughout this builder, so a new record-free
   * root is in this set the moment its emitter accepts it. A hand-kept list would let the two drift
   * — and drifting *towards* exportable is a catalog that advertises an action every export of it
   * then refuses.
   */
  val CATALOG_SYSTEM_IDS: Set<String> =
    (WearWidgetScaffoldSize.entries.map { it.componentId } + WearScreenCodeExporter.SCAFFOLD).mapTo(
      mutableSetOf()
    ) {
      it.substringBefore('/')
    }

  /**
   * The Kotlin [document] generates on its own, or null when it is an ordinary screen the record
   * -driven generator owns.
   *
   * Null rather than a refusal for the screen case: "this is not a widget" is not something to tell
   * a caller that never asked about widgets, and the record-driven generator is the answer, not a
   * fallback after a failure.
   *
   * @param packageName the package the emitted file declares, or null for a snippet without one —
   *   which is what the editor's Code pane wants and what an exported *file* must not be.
   */
  fun generate(document: UiBuilderDocument, packageName: String? = null): Generated? =
    when {
      document.isWearWidget() -> WearWidgetCodeExporter.export(document, packageName).generated()
      document.isWearScreen() -> WearScreenCodeExporter.export(document, packageName).generated()
      else -> null
    }

  /** What a record-free design generates, or why it does not. */
  sealed interface Generated {
    data class Emitted(val source: String) : Generated

    data class Refused(val reasons: List<String>) : Generated
  }

  /**
   * The same question asked of the **saved** document, which is the shape the server holds.
   *
   * The kind is decided on `DesignDocumentV1` itself rather than after converting, so a conversion
   * that fails is a refusal about a design already known to be record-free rather than a silent
   * fall-through to the record-driven generator — which would then refuse a Wear widget for having
   * no component record, advice nobody can act on. The conversion narrows the wire's `Long`
   * revision to the candidate document's `Int`, the same narrowing the browser client makes at its
   * own boundary; the emitters never read it, and the artifact's provenance header carries the
   * revision the export was pinned to.
   */
  fun generate(document: DesignDocumentV1, packageName: String? = null): Generated? {
    if (!document.isRecordFree()) return null
    return runCatching { generate(document.toUiBuilderDocument(), packageName) }
      .getOrElse { failure ->
        Generated.Refused(
          listOf(
            "this design could not be read as a builder document" +
              (failure.message?.let { ": $it" } ?: "")
          )
        )
      }
  }

  /**
   * Whether [document] generates through an emitter here rather than through `ScreenGenerator`.
   *
   * Public because one caller needs the question without the answer: the server's **native
   * preview** lane compiles generated Jetpack Compose against the catalog bundle, and a design
   * whose source is Remote Compose or Wear Compose is not something it can build — so it refuses,
   * and wants to say why rather than run a generator and discard the source.
   */
  fun applies(document: DesignDocumentV1): Boolean = document.isRecordFree()

  /** Whether [document]'s single root is one the emitters above accept. */
  private fun DesignDocumentV1.isRecordFree(): Boolean {
    val root = roots.singleOrNull()?.let(nodes::get) ?: return false
    return WearWidgetScaffoldSize.entries.any { it.componentId == root.componentId } ||
      root.componentId == WearScreenCodeExporter.SCAFFOLD
  }

  private fun WearWidgetCodeExporter.Result.generated(): Generated =
    when (this) {
      is WearWidgetCodeExporter.Result.Emitted -> Generated.Emitted(source)
      is WearWidgetCodeExporter.Result.Refused -> Generated.Refused(reasons)
    }

  private fun WearScreenCodeExporter.Result.generated(): Generated =
    when (this) {
      is WearScreenCodeExporter.Result.Emitted -> Generated.Emitted(source)
      is WearScreenCodeExporter.Result.Refused -> Generated.Refused(reasons)
    }
}

/** Whether this design is a Wear widget, which generates through a different emitter entirely. */
fun UiBuilderDocument.isWearWidget(): Boolean {
  val root = roots.singleOrNull()?.let(nodes::get) ?: return false
  return WearWidgetScaffoldSize.entries.any { it.componentId == root.componentId }
}

/** A design whose root is the Wear screen scaffold, which [WearScreenCodeExporter] writes. */
fun UiBuilderDocument.isWearScreen(): Boolean {
  val root = roots.singleOrNull()?.let(nodes::get) ?: return false
  return root.componentId == WearScreenCodeExporter.SCAFFOLD
}
