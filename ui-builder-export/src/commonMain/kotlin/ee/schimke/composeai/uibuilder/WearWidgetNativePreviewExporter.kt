package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentRecord

/**
 * The source the **native preview lane** compiles for a Wear widget design.
 *
 * ## Why the exported widget file is not what this lane submits
 *
 * [WearWidgetCodeExporter] writes the artifact a designer keeps, and every choice in it is made for
 * a reader who will paste it into their own module: a `GlanceWearWidget` class, the
 * `WearWidgetDocument` it provides, a content picture asked for as a **parameter** because it is
 * application data, and a `@Preview` per host container shape driven by the shipped
 * `WidgetPreviewParams` providers because those are the only container specs such a file can name.
 *
 * Each of those is exactly wrong for a lane whose whole job is to draw *this design*:
 *
 * 1. nothing downstream can pass an argument, so a picture asked for as a parameter arrives as the
 *    blank 1×1 placeholder the widget class defaults to — a render with a hole in it where the
 *    canvas beside it shows the album art;
 * 2. the shipped providers carry only the specs upstream publishes — for the shape a design is
 *    authored against, the squircle's 8dp padding and 26dp radius — so a design that moved either
 *    is refused by the exporter rather than drawn, a refusal that is true of a pasteable file and
 *    not of a render this host builds its own params for; and
 * 3. the widget class and its `provideWidgetData` are ceremony around a body nobody here calls
 *    through them.
 *
 * So this writes the three things the lane actually needs and nothing else: the `@RemoteComposable`
 * body with its pictures **inlined**, the widget's own `WearWidgetBrush`, and the
 * `WearWidgetParams` the design's scaffold describes. The preview entry that puts them together
 * lives in the server, beside the entry it already synthesizes for a screen.
 *
 * ## What it still does not do
 *
 * It does not tag nodes. A `testTag` is a Compose UI modifier and this body is Remote Compose —
 * recorded into a document, not composed into a semantics tree — so a widget's frame comes back as
 * a picture without the clickable overlay a screen's has. That is the honest outcome rather than a
 * gap to paper over: the layers panel still selects, and a rectangle the lane could not measure is
 * one it must not draw.
 */
internal object WearWidgetNativePreviewExporter {

  /** What a design generates for this lane, or why it does not. */
  sealed interface Result {
    /**
     * @property name the base identifier the file declares three things under — `<name>Content`,
     *   `<name>Background` and `<name>Params`. One name rather than three, because the entry the
     *   server writes derives them and a trio on the wire is three ways for them to disagree.
     * @property widthDp the frame the container occupies: the content box plus its padding on both
     *   edges, which is what `WearWidgetPreview` measures and what the canvas already draws.
     */
    data class Emitted(
      val source: String,
      val name: String,
      val widthDp: Int,
      val heightDp: Int,
    ) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /**
   * @param shape which host container to build the params for. The design does not carry this — the
   *   frame is the host's — so it arrives from whoever asked for the render, and the editor's
   *   canvas is drawing the same shape beside this pane.
   * @param components the catalog's own component record, by component id, for the fallback in
   *   `RemoteContentEmitter`. The same map the export lane passes, because a design that exports
   *   and does not render — or renders and does not export — is the one disagreement between the
   *   two surfaces worth refusing outright.
   */
  fun export(
    document: UiBuilderDocument,
    packageName: String,
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    shape: WearWidgetHostShape = WearWidgetHostShape.Default,
    components: Map<String, ComponentRecord> = emptyMap(),
  ): Result {
    val rootId = document.roots.singleOrNull() ?: return refuse("a widget design has one root")
    val root = document.nodes[rootId] ?: return refuse("the root node `$rootId` is missing")
    val size =
      WearWidgetScaffoldSize.entries.firstOrNull { it.componentId == root.componentId }
        ?: return refuse(
          "the root is `${root.componentId}`, not a Wear widget container — this lane renders " +
            "widgets, and a screen reaches it through the Compose generator"
        )

    val contentIds = root.slots["content"].orEmpty()
    val refusals = mutableListOf<String>()
    // The same throwaway probe the two other emitters run, and for the same reason: the theme
    // wrapper moves every line of the body one level right, and whether it is wanted is something
    // an emitter only learns by emitting. Its refusals are dropped; the real pass below reports.
    val depth =
      if (
        RemoteContentEmitter(
            document,
            mutableListOf(),
            assets,
            inlineContentImages = true,
            components = components,
          )
          .let { probe ->
            probe.background(root)
            contentIds.singleOrNull()?.let { probe.emit(it, depth = 1) }
            probe.usesTheme
          }
      )
        2
      else 1

    val emitter =
      RemoteContentEmitter(
        document,
        refusals,
        assets,
        inlineContentImages = true,
        components = components,
      )
    val background = emitter.background(root)
    val body =
      when (contentIds.size) {
        0 -> listOf("${INDENT.repeat(depth)}RemoteBox(modifier = RemoteModifier.fillMaxSize())")
        1 -> emitter.emit(contentIds.single(), depth = depth)
        else -> {
          refusals += "the widget container holds one body; this design has ${contentIds.size}"
          emptyList()
        }
      }
    emitter.validateFunctionNames("${document.widgetIdentifier()}Content")
    if (refusals.isNotEmpty()) return Result.Refused(refusals.distinct())

    val name = document.widgetIdentifier()
    // The selected shape's published spec is the baseline; a design that authored its own padding
    // or radius overrides it, exactly as the canvas beside this render does. Content box is not on
    // that list and cannot be: it is the footprint the host reserves, not a value a widget holds.
    val spec = size.hostSpec(shape)
    val horizontalPadding = root.previewFloat("horizontalPaddingDp", spec.horizontalPaddingDp)
    val verticalPadding = root.previewFloat("verticalPaddingDp", spec.verticalPaddingDp)
    val cornerRadius = root.previewFloat("cornerRadiusDp", spec.cornerRadiusDp)
    return Result.Emitted(
      name = name,
      widthDp = (spec.contentWidthDp + 2f * horizontalPadding).toInt(),
      heightDp = (spec.contentHeightDp + 2f * verticalPadding).toInt(),
      source =
        buildString {
          appendLine("// Generated from a Compose UI builder design. Do not edit by hand.")
          appendLine(
            "// Native preview of design ${document.id.escapeComment()} revision " +
              "${document.revision}, in the ${shape.label.lowercase()} host container."
          )
          appendLine("@file:Suppress(\"RestrictedApi\")")
          appendLine()
          appendLine("package $packageName")
          appendLine()
          emitter.imports(WidgetSourceShape.NativePreview).forEach { appendLine("import $it") }
          appendLine()
          appendLine("@RemoteComposable")
          appendLine("@Composable")
          // No parameters, and that is the difference from every other emitter here: the pictures
          // are inlined above rather than asked for, because this function's only caller is a
          // generated `@Preview`.
          appendLine("fun ${name}Content() {")
          emitter.stateLocals().forEach { appendLine("$INDENT$it") }
          if (emitter.usesTheme) {
            appendLine("${INDENT}RemoteMaterialTheme {")
            body.forEach(::appendLine)
            appendLine("$INDENT}")
          } else {
            body.forEach(::appendLine)
          }
          appendLine("}")
          appendLine()
          // A function rather than a `val`, for the reason the widget's `provideWidgetData` is one:
          // a theme-token background instantiates a `RemoteColorScheme` first, and a top-level val
          // reading an inlined bitmap declared below it would compile to "must be initialized".
          appendLine("fun ${name}Background(): WearWidgetBrush {")
          background.locals.forEach { appendLine("$INDENT$it") }
          appendLine("${INDENT}return ${background.expression}")
          appendLine("}")
          appendLine()
          // The design's own container spec, not a shipped provider's. This is the whole reason a
          // design that authored its padding or its radius renders here while its export refuses:
          // an exported file may only name what upstream publishes, and this host constructs the
          // params itself.
          appendLine("fun ${name}Params(): WearWidgetParams =")
          appendLine("${INDENT}WearWidgetParams(")
          appendLine(
            "$INDENT${INDENT}instanceId = WidgetInstanceId(\"tiles\", ${size.instanceId}),"
          )
          appendLine("$INDENT${INDENT}containerType = ContainerInfo.${size.containerType},")
          appendLine("$INDENT${INDENT}widthDp = ${spec.contentWidthDp.dpLiteral()},")
          appendLine("$INDENT${INDENT}heightDp = ${spec.contentHeightDp.dpLiteral()},")
          appendLine("$INDENT${INDENT}horizontalPaddingDp = ${horizontalPadding.dpLiteral()},")
          appendLine("$INDENT${INDENT}verticalPaddingDp = ${verticalPadding.dpLiteral()},")
          appendLine("$INDENT${INDENT}cornerRadiusDp = ${cornerRadius.dpLiteral()},")
          appendLine("$INDENT)")
          // Below the declarations for the reason the widget export puts them there: a base64 PNG
          // is thousands of columns, and a reader who has to scroll past it to reach the body has
          // been handed a worse file than one who can stop reading at the params.
          inlineBitmapDeclarations(emitter.inlineBitmaps).forEach {
            appendLine()
            appendLine(it)
          }
          emitter.declarations.forEach {
            appendLine()
            appendLine(it)
          }
        },
    )
  }

  private fun refuse(reason: String) = Result.Refused(listOf(reason))

  /** The design's own value, or the shipped default when it declares none. */
  private fun UiBuilderNode.previewFloat(name: String, fallback: Float): Float =
    properties[name]?.numberOrNull() ?: fallback

  /** `8.0` is what a `Float` argument reads as; `8` would be an `Int`. */
  private fun Float.dpLiteral(): String = if (this % 1f == 0f) "${toInt()}f" else "${this}f"

  private fun Int.dpLiteral(): String = "${this}f"

  private const val INDENT = "    "
}

/** `ContainerInfo`'s own name for this footprint. */
internal val WearWidgetScaffoldSize.containerType: String
  get() =
    when (this) {
      WearWidgetScaffoldSize.Small -> "CONTAINER_TYPE_SMALL"
      WearWidgetScaffoldSize.Large -> "CONTAINER_TYPE_LARGE"
    }

/**
 * Inert in a preview capture — it only matters to a live host round-trip — and distinct per size
 * for the reason `wear-m3-catalog`'s stickers keep it distinct: two widgets in one carousel.
 */
internal val WearWidgetScaffoldSize.instanceId: Int
  get() =
    when (this) {
      WearWidgetScaffoldSize.Small -> 1
      WearWidgetScaffoldSize.Large -> 2
    }
