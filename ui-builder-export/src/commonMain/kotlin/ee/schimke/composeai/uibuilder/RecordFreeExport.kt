package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentRecord
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
   * Components their emitters will only write when they are the document's **root**.
   *
   * [generate] routes on the root component id, so one of these anywhere else — a Wear screen
   * scaffold that has become one item of a board, say — falls through to the record-driven
   * generator instead, losing the emitter and the authoritative native preview lane that go with
   * it. An authoring surface that can place a component somewhere other than the root asks this
   * first.
   *
   * Derived from the same two sources [CATALOG_SYSTEM_IDS] is derived from, and for its reason: a
   * hand-kept list drifts towards claiming a component is placeable while its emitter still demands
   * the root.
   */
  val ROOT_ONLY_COMPONENT_IDS: Set<String> =
    (WearWidgetScaffoldSize.entries.map { it.componentId } + WearScreenCodeExporter.SCAFFOLD)
      .toSet()

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
   * @param packComponents the component pack components a Wear **screen** may hold, by component
   *   id, each as its record — see [WearScreenCodeExporter.export]. A widget takes none: its source
   *   is Remote Compose, which no pack's Jetpack Compose composable can be played as.
   */
  fun generate(
    document: UiBuilderDocument,
    packageName: String? = null,
    tagNodes: Boolean = false,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Generated? =
    when {
      // A widget takes no [tagNodes]: it generates a `WearWidgetDocument` of Remote Compose, whose
      // nodes are not Compose modifiers, so there is nothing for a test tag to be written onto —
      // and the native preview lane, which is the only caller that asks for tags, reaches a widget
      // through [nativePreview] rather than here. Accepting the flag and dropping it would read as
      // support.
      document.isWearWidget() ->
        // A widget takes no PACK components — a pack's Jetpack Compose composable cannot be
        // played as Remote Compose — and it does take its own catalog's record, which is a
        // different thing wearing the same parameter. `RemoteContentEmitter` falls back to it for
        // a component it has no hand-written case for, which is most of what a Remote catalog
        // publishes.
        WearWidgetCodeExporter.export(document, packageName, assets, packComponents).generated()
      document.isWearScreen() ->
        WearScreenCodeExporter.export(document, packageName, tagNodes, packComponents).generated()
      else -> null
    }

  /** What a record-free design generates, or why it does not. */
  sealed interface Generated {
    /**
     * @param composableName the function the file declares, where the emitter writes one this
     *   caller has to name again — the native preview lane wraps it in a `@Preview` and imports it
     *   by name. Null for a Wear widget, whose source declares a `WearWidgetDocument` rather than a
     *   composable and which that lane reaches through [nativePreview] instead.
     */
    data class Emitted(val source: String, val composableName: String? = null) : Generated

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
  fun generate(
    document: DesignDocumentV1,
    packageName: String? = null,
    tagNodes: Boolean = false,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Generated? {
    if (!document.isRecordFree()) return null
    return runCatching {
      generate(document.toUiBuilderDocument(), packageName, tagNodes, packComponents, assets)
    }
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

  /**
   * Whether a record-free design's source is **Kotlin a Compose classpath can build**.
   *
   * The two record-free emitters are not the same kind of thing, and the native preview lane is
   * where the difference finally matters. [WearScreenCodeExporter] writes ordinary Wear Compose —
   * `ScreenScaffold`, `TitleCard`, `Text` — so a host holding a bundle that carries
   * `androidx.wear.compose:compose-material3` can compile it and render it on the
   * Android/Robolectric daemon, which is the only honest picture a Wear design has: the browser's
   * Wasm canvas cannot link an Android AAR and never will
   * (`docs/design/UI_BUILDER_WEAR_SCREEN.md`). [WearWidgetCodeExporter] writes a
   * `WearWidgetDocument` — Remote Compose, played rather than composed — which is not a screen this
   * lane can call, so a widget answers false here and reaches the same lane by [nativePreview]
   * instead.
   *
   * Asked of the emitter's target rather than of the catalog id, for the reason
   * [CATALOG_SYSTEM_IDS] is derived: a third record-free emitter answers this question by which of
   * the two shapes it writes, not by being added to a list somebody remembers.
   */
  fun composeCompilable(document: DesignDocumentV1): Boolean {
    val root = document.roots.singleOrNull()?.let(document.nodes::get) ?: return false
    return root.componentId == WearScreenCodeExporter.SCAFFOLD
  }

  /**
   * Whether this record-free design is a **Wear widget**, whose native lane source is its own.
   *
   * The complement of [composeCompilable] within [applies] rather than its negation restated: a
   * widget reaches the native preview lane too, and by a third road — [nativePreview] writes a
   * `@RemoteComposable` body, a `WearWidgetBrush` and a container spec, none of which is the file
   * [generate] hands a designer. Asked of the emitter's target for the reason [CATALOG_SYSTEM_IDS]
   * is derived from one.
   */
  fun isWearWidget(document: DesignDocumentV1): Boolean {
    val root = document.roots.singleOrNull()?.let(document.nodes::get) ?: return false
    return WearWidgetScaffoldSize.entries.any { it.componentId == root.componentId }
  }

  /**
   * The Kotlin the **native preview lane** compiles for a widget, or why there is none.
   *
   * A second entry point beside [generate] rather than a flag on it, because the two produce files
   * with different contents for different readers, and the one place that could confuse them —
   * "which of these does an export write?" — is answered by which function was called.
   * [WearWidgetNativePreviewExporter] carries the full reasoning.
   *
   * Null when [document] is not a widget at all, matching [generate]'s "this is not mine" rather
   * than refusing a caller that never asked about widgets.
   *
   * @param components the catalog's own component record, by component id — the same map [generate]
   *   takes as `packComponents` for a widget, and it has to be the same one: a design whose picture
   *   refuses while its file exports, or the reverse, is a disagreement between two surfaces
   *   showing the same design.
   */
  fun nativePreview(
    document: DesignDocumentV1,
    packageName: String,
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    shape: WearWidgetHostShape = WearWidgetHostShape.Default,
    components: Map<String, ComponentRecord> = emptyMap(),
  ): NativePreview? {
    if (!isWearWidget(document)) return null
    return runCatching {
      when (
        val result =
          WearWidgetNativePreviewExporter.export(
            document.toUiBuilderDocument(),
            packageName,
            assets,
            shape,
            components,
          )
      ) {
        is WearWidgetNativePreviewExporter.Result.Emitted ->
          NativePreview.Emitted(result.source, result.name, result.widthDp, result.heightDp)
        is WearWidgetNativePreviewExporter.Result.Refused -> NativePreview.Refused(result.reasons)
      }
    }
      .getOrElse { failure ->
        NativePreview.Refused(
          listOf(
            "this design could not be read as a builder document" +
              (failure.message?.let { ": $it" } ?: "")
          )
        )
      }
  }

  /** What a widget generates for the native preview lane, or why it does not. */
  sealed interface NativePreview {
    /**
     * @param name the base identifier the file declares `<name>Content`, `<name>Background` and
     *   `<name>Params` under.
     * @param widthDp the container's whole frame — content plus padding — which is what the preview
     *   is measured at and what the canvas beside it draws.
     */
    data class Emitted(
      val source: String,
      val name: String,
      val widthDp: Int,
      val heightDp: Int,
    ) : NativePreview

    data class Refused(val reasons: List<String>) : NativePreview
  }

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
      is WearScreenCodeExporter.Result.Emitted -> Generated.Emitted(source, screenName)
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
