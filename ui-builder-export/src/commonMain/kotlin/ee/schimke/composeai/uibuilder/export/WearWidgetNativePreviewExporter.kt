package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord

/**
 * The source the native preview lane compiles for a Wear widget design.
 *
 * Not [WearWidgetCodeExporter]'s artifact, which is shaped for a reader: pictures as parameters
 * (which would render as blank placeholders here), a `@Preview` per host shape, and widget-class
 * ceremony. This writes only the `@RemoteComposable` body with pictures inlined, the widget's
 * `WearWidgetBrush`, and the selected host shape's `WearWidgetParams` (padding and radius from
 * `hostSpec`). The server writes the preview entry.
 *
 * With `tagNodes`, every placed node carries its [NativeNodeTags.tag] as a content description,
 * which the Compose Multiplatform player turns into a semantics node with that component's bounds:
 * the widget counterpart of the `testTag` a Compose screen's native source carries.
 */
internal object WearWidgetNativePreviewExporter {

  /** What a design generates for this lane, or why it does not. */
  sealed interface Result {
    /**
     * @property name the base identifier for `<name>Content`, `<name>Background` and `<name>Params`
     *   (the server derives all three).
     * @property widthDp the container's frame: content plus padding on both edges, as
     *   `WearWidgetPreview` measures and the canvas draws.
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
   * @param shape which host container to build params for; the requester supplies it, matching the
   *   editor's canvas.
   * @param components the catalog's component record by id, for `RemoteContentEmitter`'s fallback —
   *   the same map the export lane passes, so render and export cannot disagree.
   */
  fun export(
    authored: UiBuilderDocument,
    packageName: String,
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    shape: WearWidgetHostShape = WearWidgetHostShape.Default,
    components: Map<String, ComponentRecord> = emptyMap(),
    adaptiveSize: WearWidgetScaffoldSize = WearWidgetScaffoldSize.Large,
    tagNodes: Boolean = false,
  ): Result {
    // A render is one container, so an adaptive design renders as the fixed design it becomes at
    // [adaptiveSize] — Large by default, the size the canvas edits at because it shows every slot.
    val document = AdaptiveWearWidget.resolve(authored, adaptiveSize)
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
    // The same throwaway depth probe as the other emitters; its refusals are dropped.
    val depth =
      if (
        RemoteContentEmitter(
            document,
            mutableListOf(),
            assets,
            inlineContentImages = true,
            components = components,
            frameFillingRoot = contentIds.singleOrNull(),
            tagNodes = tagNodes,
          )
          .let { probe ->
            probe.background(root)
            probe.themeTypography(root, depth = 2)
            probe.themeTextStyle(root)
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
        frameFillingRoot = contentIds.singleOrNull(),
        tagNodes = tagNodes,
      )
    val background = emitter.background(root)
    val typography = emitter.themeTypography(root, depth = 2)
    emitter.themeTextStyle(root)
    val body =
      when (contentIds.size) {
        // Through the emitter, so the file imports the `RemoteBox` and `RemoteModifier` it calls.
        0 -> listOf(emitter.emptyBox(depth))
        1 -> emitter.emit(contentIds.single(), depth = depth)
        else -> {
          refusals += "the widget container holds one body; this design has ${contentIds.size}"
          emptyList()
        }
      }
    emitter.validateFunctionNames("${document.widgetIdentifier()}Content")
    if (refusals.isNotEmpty()) return Result.Refused(refusals.distinct())

    val name = document.widgetIdentifier()
    // The selected shape's published spec, exactly as the canvas beside this render draws it. Only
    // the predefined host shapes are supported: a legacy padding or radius override is ignored.
    val spec = size.hostSpec(shape)
    val horizontalPadding = spec.horizontalPaddingDp
    val verticalPadding = spec.verticalPaddingDp
    val cornerRadius = spec.cornerRadiusDp
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
          // No parameters: pictures are inlined, since the only caller is a generated `@Preview`.
          appendLine("fun ${name}Content() {")
          emitter.stateLocals().forEach { appendLine("$INDENT$it") }
          if (emitter.usesTheme) {
            if (typography == null) appendLine("${INDENT}RemoteMaterialTheme {")
            else {
              appendLine("${INDENT}RemoteMaterialTheme(")
              typography.forEach(::appendLine)
              appendLine("$INDENT) {")
            }
            emitter.provideTextStyle(body, depth = 2).forEach(::appendLine)
            appendLine("$INDENT}")
          } else {
            body.forEach(::appendLine)
          }
          appendLine("}")
          appendLine()
          // A function, not a `val`: a top-level val reading an inlined bitmap declared below would
          // not be initialized.
          appendLine("fun ${name}Background(): WearWidgetBrush {")
          background.locals.forEach { appendLine("$INDENT$it") }
          appendLine("${INDENT}return ${background.expression}")
          appendLine("}")
          appendLine()
          // The selected host shape's published params, constructed here because this host builds
          // its own `WearWidgetParams` rather than naming a shipped provider.
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
          // Inlined bitmaps go last, so readers can stop before the base64.
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
 * Inert in a preview capture, but distinct per size (as wear-m3-catalog's stickers keep it) for two
 * widgets in one carousel.
 */
internal val WearWidgetScaffoldSize.instanceId: Int
  get() =
    when (this) {
      WearWidgetScaffoldSize.Small -> 1
      WearWidgetScaffoldSize.Large -> 2
    }
