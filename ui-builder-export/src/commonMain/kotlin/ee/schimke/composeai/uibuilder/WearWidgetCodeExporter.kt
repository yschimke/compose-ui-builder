package ee.schimke.composeai.uibuilder

/**
 * Generates the Kotlin a Wear widget design becomes: a `GlanceWearWidget`, its Remote Compose
 * content, and the `@Preview` that draws it through the real host tooling.
 *
 * ## Why this is not the Compose exporter
 *
 * [CapabilityComposeCodeExporter] writes Jetpack Compose Material 3 against a discovered component
 * record. A Wear widget is neither: it runs Remote Compose on a watch, its composables come from
 * `androidx.compose.remote.creation.compose` and `androidx.wear.compose.remote.material3`, and it
 * is delivered as a `WearWidgetDocument` rather than called from a screen. `remote-m3` therefore
 * has no component record and advertises no Compose export — correctly — and a widget design used
 * to generate nothing at all.
 *
 * ## The scaffold does not appear in the output
 *
 * `remote-m3/widget-container-*` is this builder's stand-in for
 * `androidx.glance.wear.composable.WearWidgetContainer`, and on-device that container belongs to
 * the host: the launcher draws it around widget content from `WearWidgetParams`. So the generated
 * code names it nowhere. Its background becomes the `WearWidgetBrush` handed to
 * `WearWidgetDocument`, its size picks the preview-params provider, and its padding and radius are
 * asserted against the shipped spec rather than emitted — a widget cannot choose them.
 *
 * ## Refusals are by name
 *
 * The same discipline as the Compose exporter: a node this emitter cannot write is refused and said
 * out loud, never approximated. A design that generates plausible Kotlin which draws something else
 * is worse than one that generates nothing.
 */
object WearWidgetCodeExporter {

  /** What a design generates, or why it does not. */
  sealed interface Result {
    data class Emitted(val source: String) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /**
   * @param packageName the package the emitted file declares, or null for the pane's snippet. A
   *   snippet is written to be pasted into a file that already has one; an export is the file, so
   *   the two differ by exactly this line. It goes after `@file:Suppress`, which Kotlin requires
   *   before the package declaration.
   */
  fun export(document: UiBuilderDocument, packageName: String? = null): Result {
    val rootId = document.roots.singleOrNull() ?: return refuse("a widget design has one root")
    val root = document.nodes[rootId] ?: return refuse("the root node `$rootId` is missing")
    val size =
      WearWidgetScaffoldSize.entries.firstOrNull { it.componentId == root.componentId }
        ?: return refuse(
          "the root is `${root.componentId}`, not a Wear widget container — this generator writes " +
            "widgets, and a screen belongs to the Compose exporter"
        )

    val refusals = mutableListOf<String>()
    // The host owns these, and only the shipped providers can be named in a `@Preview`. A design
    // that moved them would generate a preview drawing a frame the design does not have, which is
    // exactly the silent disagreement this generator exists to avoid.
    root.exportFloat("horizontalPaddingDp", WEAR_WIDGET_SPEC_PADDING_DP)?.let {
      refusals +=
        "horizontal padding is ${it.withoutTrailingZero()}dp; the published widget preview params only carry " +
          "${WEAR_WIDGET_SPEC_PADDING_DP.withoutTrailingZero()}dp, so no generated preview can show it"
    }
    root.exportFloat("verticalPaddingDp", WEAR_WIDGET_SPEC_PADDING_DP)?.let {
      refusals +=
        "vertical padding is ${it.withoutTrailingZero()}dp; the published widget preview params only carry " +
          "${WEAR_WIDGET_SPEC_PADDING_DP.withoutTrailingZero()}dp, so no generated preview can show it"
    }
    root.exportFloat("cornerRadiusDp", WEAR_WIDGET_SPEC_CORNER_RADIUS_DP)?.let {
      refusals +=
        "corner radius is ${it.withoutTrailingZero()}dp; the squircle providers carry " +
          "${WEAR_WIDGET_SPEC_CORNER_RADIUS_DP.withoutTrailingZero()}dp — 999dp is the round shape, which needs " +
          "the Round provider this generator does not select yet"
    }

    val contentIds = root.slots["content"].orEmpty()
    // How deep the body sits, which the emitter needs *before* it writes a line: it wraps a call at
    // its own column budget, and a theme wrapper moves every line of the body one level right. The
    // wrapper is only wanted once a colour or type token has been written, though, which is
    // something an emitter learns by emitting — so a throwaway pass asks the question and its
    // refusals are dropped, the real emitter below being the one that reports them.
    val depth =
      if (
        RemoteContentEmitter(document, mutableListOf()).let { probe ->
          probe.background(root)
          contentIds.singleOrNull()?.let { probe.emit(it, depth = 1) }
          probe.usesTheme
        }
      )
        2
      else 1

    val emitter = RemoteContentEmitter(document, refusals)
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
    if (refusals.isNotEmpty()) return Result.Refused(refusals.distinct())

    val name = document.widgetIdentifier()
    return Result.Emitted(
      buildString {
        appendLine("// Generated from a Compose UI builder design. Do not edit by hand.")
        appendLine("@file:Suppress(\"RestrictedApi\")")
        appendLine()
        if (packageName != null) {
          appendLine("package $packageName")
          appendLine()
        }
        emitter.imports(size.previewParamsProvider).forEach { appendLine("import $it") }
        appendLine()
        appendLine("@RemoteComposable")
        appendLine("@Composable")
        appendLine("fun ${name}Content(${parameterList(emitter.imageParameters)}) {")
        if (emitter.usesTheme) {
          appendLine("${INDENT}RemoteMaterialTheme {")
          body.forEach(::appendLine)
          appendLine("$INDENT}")
        } else {
          body.forEach(::appendLine)
        }
        appendLine("}")
        appendLine()
        appendLine(widgetClassHeader(name, emitter.imageParameters))
        appendLine("${INDENT}override suspend fun provideWidgetData(")
        appendLine("$INDENT${INDENT}context: Context,")
        appendLine("$INDENT${INDENT}params: WearWidgetParams,")
        appendLine("$INDENT): WearWidgetData {")
        background.locals.forEach { appendLine("$INDENT$INDENT$it") }
        documentReturn(background.expression).forEach(::appendLine)
        appendLine(
          "$INDENT$INDENT${INDENT}${name}Content(${argumentList(emitter.imageParameters)})"
        )
        appendLine("$INDENT$INDENT}")
        appendLine("$INDENT}")
        appendLine("}")
        appendLine()
        appendLine("@Preview(name = \"Squircle Preview\")")
        appendLine("@Composable")
        appendLine("fun ${name}SquirclePreview(")
        appendLine(
          "$INDENT@PreviewParameter(${size.previewParamsProvider}::class) params: WearWidgetParams"
        )
        appendLine(") = WearWidgetPreview($name(), params)")
        // Last in the file, and deliberately: a Lottie animation is a few thousand columns of
        // minified JSON, and a reader who has to scroll past it to reach the widget has been
        // handed a worse file than one who can stop reading at the preview.
        emitter.declarations.forEach {
          appendLine()
          appendLine(it)
        }
      }
    )
  }

  /** `albumArt: RemoteImageBitmap`, once per picture the body draws, or nothing at all. */
  private fun parameterList(parameters: List<RemoteContentEmitter.ImageParameter>): String =
    parameters.joinToString {
      "${it.identifier}: RemoteImageBitmap"
    }

  private fun argumentList(parameters: List<RemoteContentEmitter.ImageParameter>): String =
    parameters.joinToString {
      "${it.identifier} = ${it.identifier}"
    }

  /**
   * The widget class, which takes the body's pictures and defaults each to a blank bitmap.
   *
   * The parameters are the honest half: a widget's picture is application data, and the design
   * carries an asset key rather than bytes source could name. The **default** is what makes the
   * generated `@Preview` below still compile — it constructs this class with no arguments — and it
   * is a 1×1 transparent bitmap rather than anything drawn, because a placeholder that looked like
   * a picture would be a preview showing something the design does not have. An application passes
   * the real bitmap; a preview shows the hole it goes in.
   */
  private fun widgetClassHeader(
    name: String,
    parameters: List<RemoteContentEmitter.ImageParameter>,
  ): String {
    if (parameters.isEmpty()) return "class $name : GlanceWearWidget() {"
    return buildString {
      appendLine("class $name(")
      parameters.forEach {
        appendLine("$INDENT// The design's `${it.assetKey.escapeComment()}` asset.")
        appendLine(
          "${INDENT}private val ${it.identifier}: RemoteImageBitmap = ImageBitmap(1, 1).rb,"
        )
      }
      append(") : GlanceWearWidget() {")
    }
  }

  /**
   * `return WearWidgetDocument(background = <brush>) {`, wrapped when it would run long.
   *
   * The brush chain is the one part of this file whose length the design controls — two literal
   * gradient stops already spend 84 columns — so the single line the samples show is emitted only
   * when it fits the [MAX_LINE] budget the rest of the generator keeps. Past that the brush is
   * hoisted into a local, which is the shape `background.locals` already puts above it.
   */
  private fun documentReturn(expression: String): List<String> {
    val body = "$INDENT${INDENT}return WearWidgetDocument(background = "
    val single = "$body$expression) {"
    if (single.length <= MAX_LINE) return listOf(single)
    return listOf(
      "$INDENT${INDENT}val background =",
      "$INDENT$INDENT$INDENT$expression",
      "$INDENT${INDENT}return WearWidgetDocument(background = background) {",
    )
  }

  private fun refuse(reason: String) = Result.Refused(listOf(reason))

  /**
   * The value this property holds when it differs from [expected], or null when it agrees.
   *
   * An absent property agrees: the scaffold's defaults ARE the shipped spec, so a design that says
   * nothing is the one the providers describe.
   */
  private fun UiBuilderNode.exportFloat(name: String, expected: Float): String? {
    val declared = properties[name] ?: return null
    val value = declared.numberOrNull() ?: return null
    return if (value == expected) null else value.toString()
  }

  /** `8.0` reads as `8` in a sentence about dp. */
  private fun String.withoutTrailingZero(): String = removeSuffix(".0")

  private fun Float.withoutTrailingZero(): String = toString().removeSuffix(".0")

  private const val INDENT = "    "

  /** ktfmt's own default, as [RemoteContentEmitter] keeps for the body. */
  private const val MAX_LINE = 100

  internal const val WEAR_WIDGET_SPEC_PADDING_DP = 8f

  internal const val WEAR_WIDGET_SPEC_CORNER_RADIUS_DP = 26f
}

/**
 * The size-specific squircle provider.
 *
 * Size-specific rather than the samples' `SquircleAllWidgetPreviewParams`, because a design is
 * authored at one container size: previewing a Small design at both sizes would show a Large frame
 * nobody drew. The provider still yields both screen diameters, which is the axis the design does
 * not fix.
 */
private val WearWidgetScaffoldSize.previewParamsProvider: String
  get() =
    when (this) {
      WearWidgetScaffoldSize.Small -> "SquircleSmallWidgetPreviewParams"
      WearWidgetScaffoldSize.Large -> "SquircleLargeWidgetPreviewParams"
    }

/** `Hello widget · Small (216×76dp)` becomes `HelloWidget`. */
private fun UiBuilderDocument.widgetIdentifier(): String {
  val words =
    title
      .substringBefore('·')
      .split(Regex("[^A-Za-z0-9]+"))
      .filter { it.isNotEmpty() }
      .map { word -> word.replaceFirstChar(Char::uppercaseChar) }
  val joined = words.joinToString("").ifEmpty { "Generated" }
  val identifier = if (joined.first().isDigit()) "Widget$joined" else joined
  return if (identifier.endsWith("Widget")) identifier else "${identifier}Widget"
}
