package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1

/**
 * The designs whose Kotlin comes from a dedicated emitter rather than a component record: Wear
 * widgets ([WearWidgetCodeExporter]) and Wear screens ([WearScreenCodeExporter]), whose catalogs
 * deliberately have no record. One place so the Code pane, the server's export and the
 * `composeCode` capability agree.
 */
object RecordFreeExport {

  /**
   * Record-free export under the catalog-owned cutover, where [route] is what
   * `CatalogExportRouting.route` resolved from the catalog's `composeSourceExport` declaration.
   *
   * A declared launcher widget reaches [LauncherWidgetCodeExporter] when its root is one of
   * [launcherRoots], previewed at [frameSizes]. Other record-free routes use the platform-routed
   * [generate] below. The record projection and undeclared or unsupported routes return null: the
   * record-driven generator owns those.
   */
  fun generate(
    document: UiBuilderDocument,
    route: CatalogExportRouting.Route,
    frameSizes: List<CatalogFrameSize> = emptyList(),
    launcherRoots: Set<String> = emptySet(),
    packageName: String? = null,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Generated? =
    generate(
      document,
      route,
      frameSizes,
      launcherRoots,
      packageName,
      packComponents,
      assets,
      VariableFontExport.None,
    )

  /**
   * The route-aware [generate], writing variable font text declarations with [variableFonts]. An
   * overload rather than a default argument, to keep published JVM descriptors.
   */
  fun generate(
    document: UiBuilderDocument,
    route: CatalogExportRouting.Route,
    frameSizes: List<CatalogFrameSize> = emptyList(),
    launcherRoots: Set<String> = emptySet(),
    packageName: String? = null,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    variableFonts: VariableFontExport,
  ): Generated? =
    when (route) {
      is CatalogExportRouting.Route.RecordFree ->
        if (route.adapter == CatalogExportRouting.LAUNCHER_WIDGET)
          launcherWidget(document, frameSizes, launcherRoots, packageName, packComponents)
        else generate(document, route.platform, packageName, packComponents, assets, variableFonts)
      is CatalogExportRouting.Route.BuiltIn ->
        generate(document, route.platform, packageName, packComponents, assets, variableFonts)
      is CatalogExportRouting.Route.ComponentRecords,
      CatalogExportRouting.Route.NotDeclared,
      is CatalogExportRouting.Route.Unsupported -> null
    }

  /**
   * The route-aware [generate] for the service's document. The candidate document drops
   * `predicate`, `accessibility` and token/asset bindings, so a converting route refuses designs
   * holding them.
   */
  fun generate(
    document: DesignDocumentV1,
    route: CatalogExportRouting.Route,
    frameSizes: List<CatalogFrameSize> = emptyList(),
    launcherRoots: Set<String> = emptySet(),
    packageName: String? = null,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Generated? =
    generate(
      document,
      route,
      frameSizes,
      launcherRoots,
      packageName,
      packComponents,
      assets,
      VariableFontExport.None,
    )

  /** As above, writing variable font text's declarations with [variableFonts]. */
  fun generate(
    document: DesignDocumentV1,
    route: CatalogExportRouting.Route,
    frameSizes: List<CatalogFrameSize> = emptyList(),
    launcherRoots: Set<String> = emptySet(),
    packageName: String? = null,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    variableFonts: VariableFontExport,
  ): Generated? =
    when (route) {
      is CatalogExportRouting.Route.RecordFree ->
        if (route.adapter == CatalogExportRouting.LAUNCHER_WIDGET) {
          remoteKotlinUnsupported(document).takeIf { it.isNotEmpty() }?.let(Generated::Refused)
            ?: runCatching {
              launcherWidget(
                document.toUiBuilderDocument(),
                frameSizes,
                launcherRoots,
                packageName,
                packComponents,
              )
            }
              .getOrElse {
                Generated.Refused(
                  listOf("document: Remote source export could not read the design: ${it.message}")
                )
              }
        } else
          generate(document, route.platform, packageName, packComponents, assets, variableFonts)
      is CatalogExportRouting.Route.BuiltIn ->
        generate(document, route.platform, packageName, packComponents, assets, variableFonts)
      is CatalogExportRouting.Route.ComponentRecords,
      CatalogExportRouting.Route.NotDeclared,
      is CatalogExportRouting.Route.Unsupported -> null
    }

  private fun launcherWidget(
    document: UiBuilderDocument,
    frameSizes: List<CatalogFrameSize>,
    launcherRoots: Set<String>,
    packageName: String?,
    packComponents: Map<String, ComponentRecord>,
  ): Generated? {
    val root =
      document.roots.singleOrNull()?.let(document.nodes::get)
        ?: return Generated.Refused(listOf("a widget design has one root"))
    if (root.componentId !in launcherRoots) {
      return Generated.Refused(
        listOf(
          "the root is `${root.componentId}`, which this catalog does not mark as a launcher " +
            "widget (`${CatalogExportRouting.LAUNCHER_HOST_TRAIT}`)"
        )
      )
    }
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
      is LauncherWidgetCodeExporter.Result.Emitted ->
        Generated.Emitted(result.source, diagnostics = result.diagnostics)
      is LauncherWidgetCodeExporter.Result.Refused -> Generated.Refused(result.reasons)
    }
  }

  /** Catalog-directed source export; ordinary Remote roots need no Wear widget scaffold. */
  fun generate(
    document: UiBuilderDocument,
    platform: UiBuilderCatalogPlatform,
    packageName: String? = null,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Generated? =
    generate(document, platform, packageName, packComponents, assets, VariableFontExport.None)

  /** As above, writing variable font text's declarations with [variableFonts]. */
  fun generate(
    document: UiBuilderDocument,
    platform: UiBuilderCatalogPlatform,
    packageName: String? = null,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    variableFonts: VariableFontExport,
  ): Generated? {
    // A2UI designs always have Kotlin (the app-side program sending the payload). Asked first so
    // the A2UI emitter answers, and names any refusal, whatever the root.
    if (platform == UiBuilderCatalogPlatform.A2UI) {
      return when (val result = A2uiComposeExporter.export(document, packageName)) {
        is A2uiComposeExporter.Result.Emitted ->
          Generated.Emitted(result.source, result.composableName)
        is A2uiComposeExporter.Result.Refused -> Generated.Refused(result.reasons)
      }
    }
    generate(
        document,
        packageName,
        packComponents = packComponents,
        assets = assets,
        variableFonts = variableFonts,
      )
      ?.let {
        return it
      }
    wearRootRefusal(platform, document.roots, document.nodes.values.map { it.componentId }) {
        document.nodes[it]?.componentId
      }
      ?.let {
        return it
      }
    // Remote source is always written; features the build flag withholds are refused by name.
    if (platform != UiBuilderCatalogPlatform.REMOTE_COMPOSE) return null
    // A launcher widget is a Remote Compose root with a scaffold of its own: the
    // `RemoteComposeWidget` class around the body, which the inline exporter below never writes.
    if (document.isLauncherWidget()) {
      return when (
        val result = LauncherWidgetCodeExporter.export(document, packageName, packComponents)
      ) {
        is LauncherWidgetCodeExporter.Result.Emitted ->
          Generated.Emitted(result.source, diagnostics = result.diagnostics)
        is LauncherWidgetCodeExporter.Result.Refused -> Generated.Refused(result.reasons)
      }
    }
    return when (
      val result =
        InlineRemoteContentExporter.exportRoots(document, packageName, packComponents, assets)
    ) {
      is InlineRemoteContentExporter.Result.Emitted ->
        Generated.Emitted(result.source, result.functionName, result.diagnostics)
      is InlineRemoteContentExporter.Result.Refused -> Generated.Refused(result.reasons)
    }
  }

  fun generate(
    document: DesignDocumentV1,
    platform: UiBuilderCatalogPlatform,
    packageName: String? = null,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Generated? =
    generate(document, platform, packageName, packComponents, assets, VariableFontExport.None)

  /** As above, writing variable font text's declarations with [variableFonts]. */
  fun generate(
    document: DesignDocumentV1,
    platform: UiBuilderCatalogPlatform,
    packageName: String? = null,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    variableFonts: VariableFontExport,
  ): Generated? {
    if (!applies(document, platform)) return null
    wearRootRefusal(platform, document.roots, document.nodes.values.map { it.componentId }) {
        document.nodes[it]?.componentId
      }
      ?.let {
        return it
      }
    if (platform == UiBuilderCatalogPlatform.A2UI) {
      // What the candidate document cannot carry is refused rather than dropped: A2UI states
      // accessibility and visibility as component properties and has no token or asset registry.
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
      val unsupported = remoteKotlinUnsupported(document)
      if (unsupported.isNotEmpty()) return Generated.Refused(unsupported)
    }
    return runCatching {
      generate(
        document.toUiBuilderDocument(),
        platform,
        packageName,
        packComponents,
        assets,
        variableFonts,
      )
    }
      .getOrElse {
        Generated.Refused(
          listOf("document: Remote source export could not read the design: ${it.message}")
        )
      }
  }

  /** What the candidate document cannot carry into Remote Kotlin, one line per field. */
  private fun remoteKotlinUnsupported(document: DesignDocumentV1): List<String> = buildList {
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

  fun applies(document: DesignDocumentV1, platform: UiBuilderCatalogPlatform): Boolean =
    platform == UiBuilderCatalogPlatform.A2UI ||
      platform == UiBuilderCatalogPlatform.REMOTE_COMPOSE ||
      document.isRecordFree() ||
      misplacesWearContent(platform, document.nodes.values.map { it.componentId })

  /**
   * Why a Wear design neither Wear emitter takes (root is not a widget container or screen
   * scaffold) does not export, as one sentence. Only for a [UiBuilderCatalogPlatform.WEAR] catalog
   * and a design holding a record-free component ([CATALOG_SYSTEM_IDS]).
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
   * The catalog system ids whose designs export without a component record, derived from the roots
   * the emitters accept so the two cannot drift.
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
   * @param tagNodes whether each node says which design node it is, for the native preview lane: a
   *   Wear screen's composables carry `Modifier.testTag("<node id>")`, and a widget's Remote nodes
   *   carry [NativeNodeTags.tag] as a content description, since a `RemoteModifier` has no test
   *   tag.
   * @param previews whether a Wear screen's file carries its preview fan-out; the native preview
   *   lane passes `false` (see [WearScreenCodeExporter.export]).
   * @param packComponents pack components a Wear screen may hold, by id, as records; widgets take
   *   none.
   */
  fun generate(
    document: UiBuilderDocument,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Generated? =
    generate(
      document,
      packageName,
      tagNodes,
      previews,
      packComponents,
      assets,
      variableFonts = VariableFontExport.None,
    )

  /** As above, writing variable font text's declarations with [variableFonts]. */
  fun generate(
    document: UiBuilderDocument,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    variableFonts: VariableFontExport,
  ): Generated? =
    when {
      // A widget's [tagNodes] are content descriptions rather than test tags (see
      // [NativeNodeTags]); the native lane's own widget source comes from [nativePreview].
      document.isWearWidget() ->
        // A widget takes no pack components (Jetpack Compose cannot play as Remote Compose); the
        // parameter carries its own catalog's record instead, for `RemoteContentEmitter`'s
        // fallback.
        WearWidgetCodeExporter.export(document, packageName, assets, packComponents, tagNodes)
          .generated()
      document.isWearScreen() ->
        WearScreenCodeExporter.export(
            document,
            packageName,
            tagNodes,
            previews,
            packComponents,
            variableFonts,
          )
          .generated()
      else -> null
    }

  /** What a record-free design generates, or why it does not. */
  sealed interface Generated {
    /**
     * @param composableName the function the file declares, which the native preview lane imports
     *   and wraps in a `@Preview`. Null for a Wear widget, which declares a `WearWidgetDocument`.
     * @param diagnostics what the source needs but cannot say for itself, node by node: a
     *   dependency, a setting it leaves out, a layout that throws when captured (see
     *   [ExportDiagnosticCodes]). The artifact's diagnostics, where a refusal's reasons would be.
     */
    data class Emitted(
      val source: String,
      val composableName: String? = null,
      val diagnostics: List<ExportDiagnosticV1> = emptyList(),
    ) : Generated {
      /**
       * The published two-field constructor and its default, kept for binaries compiled against
       * them.
       */
      @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
      constructor(
        source: String,
        composableName: String? = null,
      ) : this(source, composableName, emptyList())

      /** The published two-field `copy`, kept for binaries compiled against it. */
      @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
      fun copy(
        source: String = this.source,
        composableName: String? = this.composableName,
      ): Emitted = Emitted(source, composableName, diagnostics)
    }

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
  ): Generated? =
    generate(
      document,
      packageName,
      tagNodes,
      previews,
      packComponents,
      assets,
      variableFonts = VariableFontExport.None,
    )

  /** As above, writing variable font text's declarations with [variableFonts]. */
  fun generate(
    document: DesignDocumentV1,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    variableFonts: VariableFontExport,
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
        variableFonts = variableFonts,
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
   * The tagged overloads above, plus the Wear root refusal when [platform] is given. Separate
   * overloads to keep the published JVM descriptors (see [nativePreview]).
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
    generate(
      document,
      packageName,
      tagNodes,
      previews,
      packComponents,
      assets,
      platform,
      VariableFontExport.None,
    )

  /** As above, writing variable font text's declarations with [variableFonts]. */
  fun generate(
    document: UiBuilderDocument,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    platform: UiBuilderCatalogPlatform?,
    variableFonts: VariableFontExport,
  ): Generated? =
    generate(
      document,
      packageName,
      tagNodes,
      previews,
      packComponents,
      assets,
      variableFonts = variableFonts,
    )
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
    generate(
      document,
      packageName,
      tagNodes,
      previews,
      packComponents,
      assets,
      platform,
      VariableFontExport.None,
    )

  /** As above, writing variable font text's declarations with [variableFonts]. */
  fun generate(
    document: DesignDocumentV1,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    platform: UiBuilderCatalogPlatform?,
    variableFonts: VariableFontExport,
  ): Generated? =
    generate(
      document,
      packageName,
      tagNodes,
      previews,
      packComponents,
      assets,
      variableFonts = variableFonts,
    )
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
   * Whether [document] generates through an emitter here rather than `ScreenGenerator`. Public so
   * the native preview lane can explain a refusal without running a generator.
   */
  fun applies(document: DesignDocumentV1): Boolean = document.isRecordFree()

  /**
   * Whether a record-free design's source can build on a Compose classpath: Wear screens can
   * (`docs/design/UI_BUILDER_WEAR_SCREEN.md`); Wear widgets are Remote Compose and use
   * [nativePreview].
   */
  fun composeCompilable(document: DesignDocumentV1): Boolean {
    val root = document.roots.singleOrNull()?.let(document.nodes::get) ?: return false
    return root.componentId == WearScreenCodeExporter.SCAFFOLD
  }

  /**
   * Whether this record-free design is a Wear widget, whose native-lane source comes from
   * [nativePreview] rather than [generate].
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
   * overload because v3.21.0 shipped the other descriptor.
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
  ): NativePreview? = nativePreview(document, packageName, assets, shape, components, false)

  /**
   * As above, with [tagNodes]: every placed Remote node carries its [NativeNodeTags.tag] as a
   * content description, so the lane can read each node's bounds from the played semantics tree and
   * recover its id with [NativeNodeTags.nodeIds]. A separate overload to keep the published
   * descriptors above.
   */
  fun nativePreview(
    document: DesignDocumentV1,
    packageName: String,
    assets: WidgetAssetBytes,
    shape: WearWidgetHostShape,
    components: Map<String, ComponentRecord>,
    tagNodes: Boolean,
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
            tagNodes = tagNodes,
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
     * @param name the base identifier for `<name>Content`, `<name>Background` and `<name>Params`.
     * @param widthDp the container's whole frame (content plus padding), as previewed and drawn.
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
      is WearWidgetCodeExporter.Result.Emitted ->
        Generated.Emitted(source, diagnostics = diagnostics)
      is WearWidgetCodeExporter.Result.Refused -> Generated.Refused(reasons)
    }

  private fun WearScreenCodeExporter.Result.generated(): Generated =
    when (this) {
      is WearScreenCodeExporter.Result.Emitted -> Generated.Emitted(source, screenName, diagnostics)
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
