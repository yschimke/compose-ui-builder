package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1

/**
 * The designs whose Kotlin comes from a dedicated emitter rather than a component record: Wear
 * widgets ([WearWidgetCodeExporter]) and Wear screens ([WearScreenCodeExporter]), whose catalogs
 * deliberately have no record. One place so the Code pane, the server's export and the
 * `composeCode` capability agree.
 */
object RecordFreeExport {

  /**
   * Record-free export under the catalog-owned cutover: [route] is what
   * `CatalogExportRouting.route` resolved from the catalog's `composeSourceExport` declaration.
   *
   * A declared launcher widget reaches [LauncherWidgetCodeExporter] whatever its root is called,
   * previewed at the catalog's [frameSizes]; every other record-free route is the platform-routed
   * [generate] below, asked as the platform its declaration names. A route that offers no
   * record-free export (an undeclared or unsupported one, or the record projection) is null, as a
   * design the record-driven generator owns always was.
   */
  fun generate(
    document: UiBuilderDocument,
    route: CatalogExportRouting.Route,
    frameSizes: List<CatalogFrameSize> = emptyList(),
    packageName: String? = null,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Generated? {
    if (
      route is CatalogExportRouting.Route.RecordFree &&
        route.adapter == CatalogExportRouting.LAUNCHER_WIDGET
    ) {
      if (!UiBuilderBuildFeatures.remoteCompose) return null
      val root =
        document.roots.singleOrNull()?.let(document.nodes::get)
          ?: return Generated.Refused(listOf("a widget design has one root"))
      return when (
        val result =
          LauncherWidgetCodeExporter.export(
            document,
            packageName,
            packComponents,
            rootComponentId = root.componentId,
            frameSizes = frameSizes.takeIf { it.isNotEmpty() },
          )
      ) {
        is LauncherWidgetCodeExporter.Result.Emitted -> Generated.Emitted(result.source)
        is LauncherWidgetCodeExporter.Result.Refused -> Generated.Refused(result.reasons)
      }
    }
    val platform = CatalogExportRouting.recordFreePlatform(route) ?: return null
    return generate(document, platform, packageName, packComponents, assets)
  }

  /** Catalog-directed source export; ordinary Remote roots need no Wear widget scaffold. */
  fun generate(
    document: UiBuilderDocument,
    platform: UiBuilderCatalogPlatform,
    packageName: String? = null,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Generated? {
    // An A2UI design is never a widget or a Wear screen, and every one of its designs has Kotlin:
    // the app-side program that sends its payload to AndroidX's A2UI processor. Asked first so a
    // design pinned to the A2UI catalog is answered by the A2UI emitter whatever its root is — a
    // root outside the palette is then that emitter's refusal, named, rather than a fall-through to
    // a generator that has never heard of `a2ui/`.
    if (platform == UiBuilderCatalogPlatform.A2UI) {
      return when (val result = A2uiComposeExporter.export(document, packageName)) {
        is A2uiComposeExporter.Result.Emitted ->
          Generated.Emitted(result.source, result.composableName)
        is A2uiComposeExporter.Result.Refused -> Generated.Refused(result.reasons)
      }
    }
    generate(document, packageName, packComponents = packComponents, assets = assets)?.let {
      return it
    }
    wearRootRefusal(platform, document.roots, document.nodes.values.map { it.componentId }) {
        document.nodes[it]?.componentId
      }
      ?.let {
        return it
      }
    if (
      !UiBuilderBuildFeatures.remoteCompose || platform != UiBuilderCatalogPlatform.REMOTE_COMPOSE
    )
      return null
    // A launcher widget is a Remote Compose root with a scaffold of its own: the
    // `RemoteComposeWidget` class around the body, which the inline exporter below never writes.
    if (document.isLauncherWidget()) {
      return when (
        val result = LauncherWidgetCodeExporter.export(document, packageName, packComponents)
      ) {
        is LauncherWidgetCodeExporter.Result.Emitted -> Generated.Emitted(result.source)
        is LauncherWidgetCodeExporter.Result.Refused -> Generated.Refused(result.reasons)
      }
    }
    return when (
      val result =
        InlineRemoteContentExporter.exportRoots(document, packageName, packComponents, assets)
    ) {
      is InlineRemoteContentExporter.Result.Emitted ->
        Generated.Emitted(result.source, result.functionName)
      is InlineRemoteContentExporter.Result.Refused -> Generated.Refused(result.reasons)
    }
  }

  fun generate(
    document: DesignDocumentV1,
    platform: UiBuilderCatalogPlatform,
    packageName: String? = null,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Generated? {
    if (!applies(document, platform)) return null
    wearRootRefusal(platform, document.roots, document.nodes.values.map { it.componentId }) {
        document.nodes[it]?.componentId
      }
      ?.let {
        return it
      }
    if (platform == UiBuilderCatalogPlatform.A2UI) {
      // What the candidate document cannot carry, refused here rather than dropped by the
      // conversion below: A2UI states accessibility and visibility as properties of its own
      // components, and has no token or asset registry to resolve a binding against.
      val unsupported = buildList {
        if (document.tokenBindings.isNotEmpty())
          add("tokenBindings: A2UI has no design tokens; write the value on the component")
        document.nodes.forEach { (id, node) ->
          if (node.predicate != null)
            add("nodes.$id.predicate: A2UI has no conditional nodes to lower a predicate to")
          if (node.accessibility != null)
            add("nodes.$id.accessibility: set the component's own `accessibility` property instead")
          if (node.assetBindings.isNotEmpty())
            add("nodes.$id.assetBindings: an A2UI image or video names its `url` directly")
          if (node.tokenBindings.isNotEmpty())
            add(
              "nodes.$id.tokenBindings: A2UI has no design tokens; write the value on the component"
            )
        }
      }
      if (unsupported.isNotEmpty()) return Generated.Refused(unsupported)
    } else if (!document.isRecordFree()) {
      val unsupported = buildList {
        if (document.tokenBindings.isNotEmpty())
          add("tokenBindings: resolve catalog tokens before Remote Kotlin export")
        document.nodes.forEach { (id, node) ->
          if (node.predicate != null)
            add("nodes.$id.predicate: Remote Kotlin predicate lowering is not available")
          if (node.accessibility != null)
            add("nodes.$id.accessibility: Remote Kotlin semantics lowering is not available")
          if (node.assetBindings.isNotEmpty())
            add("nodes.$id.assetBindings: Remote Kotlin asset binding lowering is not available")
          if (node.tokenBindings.isNotEmpty())
            add("nodes.$id.tokenBindings: resolve catalog tokens before Remote Kotlin export")
        }
      }
      if (unsupported.isNotEmpty()) return Generated.Refused(unsupported)
    }
    return runCatching {
      generate(document.toUiBuilderDocument(), platform, packageName, packComponents, assets)
    }
      .getOrElse {
        Generated.Refused(
          listOf("document: Remote source export could not read the design: ${it.message}")
        )
      }
  }

  fun applies(document: DesignDocumentV1, platform: UiBuilderCatalogPlatform): Boolean =
    platform == UiBuilderCatalogPlatform.A2UI ||
      (UiBuilderBuildFeatures.remoteCompose &&
        platform == UiBuilderCatalogPlatform.REMOTE_COMPOSE) ||
      document.isRecordFree() ||
      misplacesWearContent(platform, document.nodes.values.map { it.componentId })

  /**
   * Why a Wear design neither Wear emitter takes (one whose root is not a widget container or
   * screen scaffold) does not export, as one sentence instead of a per-component "no record"
   * refusal. Only for a [UiBuilderCatalogPlatform.WEAR] catalog and a design holding a record-free
   * component ([CATALOG_SYSTEM_IDS]).
   */
  private fun wearRootRefusal(
    platform: UiBuilderCatalogPlatform,
    roots: List<String>,
    componentIds: Collection<String>,
    componentOf: (nodeId: String) -> String?,
  ): Generated.Refused? {
    if (!misplacesWearContent(platform, componentIds)) return null
    if (roots.singleOrNull()?.let(componentOf) in ROOT_ONLY_COMPONENT_IDS) return null
    val root =
      roots.singleOrNull()?.let { "the root is `${componentOf(it) ?: it}`" }
        ?: "this design has ${roots.size} roots"
    return Generated.Refused(
      listOf(
        "$root, but a Wear design exports only as a Wear screen or a Wear widget: put its " +
          "content inside a `${WearScreenCodeExporter.SCAFFOLD}` (or start from the " +
          "\"wear-screen\" template), or make the root a Wear widget container"
      )
    )
  }

  private fun misplacesWearContent(
    platform: UiBuilderCatalogPlatform,
    componentIds: Collection<String>,
  ): Boolean =
    platform == UiBuilderCatalogPlatform.WEAR &&
      componentIds.any { it.substringBefore('/') in CATALOG_SYSTEM_IDS }

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
    (WEAR_WIDGET_CONTAINER_IDS + WearScreenCodeExporter.SCAFFOLD).mapTo(mutableSetOf()) {
      it.substringBefore('/')
    }

  /**
   * Components their emitters only write as the document's root, so authoring surfaces can refuse
   * placing them elsewhere. Derived from the same sources as [CATALOG_SYSTEM_IDS].
   */
  val ROOT_ONLY_COMPONENT_IDS: Set<String> =
    (WEAR_WIDGET_CONTAINER_IDS + WearScreenCodeExporter.SCAFFOLD).toSet()

  /**
   * The Kotlin [document] generates on its own, or null when it is an ordinary screen the
   * record-driven generator owns.
   *
   * @param packageName the package the file declares, or null for a snippet (the Code pane).
   *     @param previews whether a Wear screen's file carries its preview fan-out; the native
   *       preview lane passes `false` because its bundle lacks tooling (see
   *       [WearScreenCodeExporter.export]).
   *     @param packComponents pack components a Wear screen may hold, by id, as records; widgets
   *       take none.
   */
  fun generate(
    document: UiBuilderDocument,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
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
        WearScreenCodeExporter.export(document, packageName, tagNodes, previews, packComponents)
          .generated()
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
   * The same for the saved document. The kind is decided before converting, so a failed conversion
   * is a refusal rather than a fall-through to the record-driven generator.
   */
  fun generate(
    document: DesignDocumentV1,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Generated? {
    if (!document.isRecordFree()) return null
    return runCatching {
      generate(
        document.toUiBuilderDocument(),
        packageName,
        tagNodes,
        previews,
        packComponents,
        assets,
      )
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
   * The tagged overloads above, plus the Wear root refusal when [platform] is given (the native
   * preview lane needs [tagNodes]). Separate overloads rather than a default argument to keep the
   * published JVM descriptors (see [nativePreview]).
   */
  fun generate(
    document: UiBuilderDocument,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    platform: UiBuilderCatalogPlatform?,
  ): Generated? =
    generate(document, packageName, tagNodes, previews, packComponents, assets)
      ?: platform?.let {
        wearRootRefusal(
          it,
          document.roots,
          document.nodes.values.map { node -> node.componentId },
        ) { id ->
          document.nodes[id]?.componentId
        }
      }

  /** As above, for the saved document the server holds. */
  fun generate(
    document: DesignDocumentV1,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    platform: UiBuilderCatalogPlatform?,
  ): Generated? =
    generate(document, packageName, tagNodes, previews, packComponents, assets)
      ?: platform?.let {
        wearRootRefusal(
          it,
          document.roots,
          document.nodes.values.map { node -> node.componentId },
        ) { id ->
          document.nodes[id]?.componentId
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
   * Whether a record-free design's source is Kotlin a Compose classpath can build: Wear screens
   * are, so the native lane can render them on the Android daemon
   * (`docs/design/UI_BUILDER_WEAR_SCREEN.md`); Wear widgets are Remote Compose and reach that lane
   * through [nativePreview].
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
    return root.componentId in WEAR_WIDGET_CONTAINER_IDS
  }

  /**
   * The Kotlin the native preview lane compiles for a widget, or why there is none; null when
   * [document] is not a widget. See [WearWidgetNativePreviewExporter].
   */
  fun nativePreview(
    document: DesignDocumentV1,
    packageName: String,
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    shape: WearWidgetHostShape = WearWidgetHostShape.Default,
  ): NativePreview? = nativePreview(document, packageName, assets, shape, emptyMap())

  /**
   * As above, resolving components without a hand-written case through [components]. A separate
   * overload (not a default argument) because v3.21.0 shipped the other descriptor (#691).
   *
   * @param components the catalog's component record by id — the same map [generate] takes as
   *   `packComponents`, so preview and export cannot disagree.
   */
  fun nativePreview(
    document: DesignDocumentV1,
    packageName: String,
    assets: WidgetAssetBytes,
    shape: WearWidgetHostShape,
    components: Map<String, ComponentRecord>,
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
    return root.componentId in WEAR_WIDGET_CONTAINER_IDS ||
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
  return root.componentId in WEAR_WIDGET_CONTAINER_IDS
}

/** A design whose root is the Wear screen scaffold, which [WearScreenCodeExporter] writes. */
fun UiBuilderDocument.isWearScreen(): Boolean {
  val root = roots.singleOrNull()?.let(nodes::get) ?: return false
  return root.componentId == WearScreenCodeExporter.SCAFFOLD
}
