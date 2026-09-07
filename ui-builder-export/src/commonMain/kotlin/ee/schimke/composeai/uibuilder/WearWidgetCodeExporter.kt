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

  /** What a design bundles, or why it does not: [Result] with the archive's other files. */
  sealed interface BundleResult {
    data class Emitted(val bundle: WidgetBundle) : BundleResult

    data class Refused(val reasons: List<String>) : BundleResult
  }

  /**
   * @param packageName the package the emitted file declares, or null for the pane's snippet. A
   *   snippet is written to be pasted into a file that already has one; an export is the file, so
   *   the two differ by exactly this line. It goes after `@file:Suppress`, which Kotlin requires
   *   before the package declaration.
   */
  fun export(
    document: UiBuilderDocument,
    packageName: String? = null,
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Result =
    when (val outcome = generate(document, packageName, assets, bundled = null)) {
      is Outcome.Refused -> Result.Refused(outcome.reasons)
      is Outcome.Generated -> Result.Emitted(outcome.source)
    }

  /**
   * The same widget as a **project fragment**: readable source, and its pictures as files.
   *
   * The design, and why it is a second entry point rather than a size threshold on [export], is
   * `docs/design/UI_BUILDER_EXPORT_BUNDLE.md` (yschimke/compose-preview-server#528). Both lanes
   * walk the same document through the same emitter and refuse the same designs for the same
   * reasons; they differ over one question — where a picture's bytes go — and over the two things
   * that follow from it: the source opens files instead of decoding literals, and a content
   * picture's parameter can default to the design's own artwork instead of to a blank bitmap.
   */
  fun exportBundle(
    document: UiBuilderDocument,
    packageName: String? = null,
    assets: WidgetAssetContents,
  ): BundleResult =
    when (val outcome = generate(document, packageName, WidgetAssetBytes { null }, assets)) {
      is Outcome.Refused -> BundleResult.Refused(outcome.reasons)
      is Outcome.Generated ->
        BundleResult.Emitted(
          WidgetBundle(
            sourceFileName = "${outcome.name}.kt",
            source = outcome.source,
            readme = readme(outcome),
            files = outcome.files,
          )
        )
    }

  private sealed interface Outcome {
    data class Generated(
      val name: String,
      val source: String,
      val files: List<WidgetBundleFile>,
      val parameters: List<RemoteContentEmitter.ImageParameter>,
    ) : Outcome

    data class Refused(val reasons: List<String>) : Outcome
  }

  /**
   * The one walk both lanes take.
   *
   * [bundled] decides the lane: null inlines a picture's bytes into the source, and a registry
   * ships them beside it. Everything between — the scaffold checks, the depth probe, the body, the
   * refusals — is the same code answering the same questions, which is the point of writing it
   * once: two emitters would be two answers to "does this design export?", and this generator
   * exists because that question already had two answers once.
   */
  private fun generate(
    document: UiBuilderDocument,
    packageName: String?,
    assets: WidgetAssetBytes,
    bundled: WidgetAssetContents?,
  ): Outcome {
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
        RemoteContentEmitter(document, mutableListOf(), assets, bundled = bundled).let { probe ->
          probe.background(root)
          contentIds.singleOrNull()?.let { probe.emit(it, depth = 1) }
          probe.usesTheme
        }
      )
        2
      else 1

    val emitter = RemoteContentEmitter(document, refusals, assets, bundled = bundled)
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
    if (refusals.isNotEmpty()) return Outcome.Refused(refusals.distinct())

    val name = document.widgetIdentifier()
    val parameters = emitter.imageParameters
    val source = buildString {
      appendLine("// Generated from a Compose UI builder design. Do not edit by hand.")
      appendLine("@file:Suppress(\"RestrictedApi\")")
      appendLine()
      if (packageName != null) {
        appendLine("package $packageName")
        appendLine()
      }
      emitter.imports(WidgetSourceShape.Exported(size.previewParamsProvider)).forEach {
        appendLine("import $it")
      }
      appendLine()
      appendLine("@RemoteComposable")
      appendLine("@Composable")
      appendLine("fun ${name}Content(${parameterList(parameters)}) {")
      if (emitter.usesTheme) {
        appendLine("${INDENT}RemoteMaterialTheme {")
        body.forEach(::appendLine)
        appendLine("$INDENT}")
      } else {
        body.forEach(::appendLine)
      }
      appendLine("}")
      appendLine()
      appendLine(widgetClassHeader(name, parameters, bundled != null))
      appendLine("${INDENT}override suspend fun provideWidgetData(")
      appendLine("$INDENT${INDENT}context: Context,")
      appendLine("$INDENT${INDENT}params: WearWidgetParams,")
      appendLine("$INDENT): WearWidgetData {")
      // The bundle lane's pictures are opened here and nowhere else: `provideWidgetData` is where
      // the `Context` is, and a constructor default — which is where the inlining lane puts a
      // picture — has none. That is also what lets a parameter default to the design's own
      // artwork rather than to a blank bitmap.
      bundledLocals(bundled != null, emitter.bundledBackgrounds, parameters).forEach {
        appendLine("$INDENT$INDENT$it")
      }
      background.locals.forEach { appendLine("$INDENT$INDENT$it") }
      documentReturn(background).forEach(::appendLine)
      appendLine("$INDENT$INDENT${INDENT}${name}Content(${argumentList(parameters)})")
      appendLine("$INDENT$INDENT}")
      appendLine("$INDENT}")
      appendLine("}")
      appendLine()
      // One preview, not the provider's fan-out. `@PreviewParameter` unrolls a preview per
      // footprint the container ships — for Large that is a constrained 182×112dp beside the
      // 216×124dp the design is authored against — and a scaffold does not need both to show
      // what it looks like. The largest is picked by width rather than by position, so the
      // choice does not rest on the order a provider happens to yield.
      appendLine("@Preview(name = \"Squircle Preview\")")
      appendLine("@Composable")
      appendLine("fun ${name}SquirclePreview() =")
      appendLine("${INDENT}WearWidgetPreview(")
      appendLine("$INDENT$INDENT$name(),")
      appendLine("$INDENT$INDENT${size.previewParamsProvider}().values.maxBy { it.widthDp },")
      appendLine("$INDENT)")
      // The inlined pictures sit below the preview for the same reason the declarations do:
      // a base64 PNG is thousands of columns, and a reader who has to scroll past it to reach
      // the widget has been handed a worse file than one who can stop reading at the preview.
      inlineBitmapDeclarations(emitter.inlineBitmaps).forEach {
        appendLine()
        appendLine(it)
      }
      // The bundle lane's one declaration, and it is four lines: the archive holds the bytes, so
      // all the file needs is the way in to them.
      bundledBitmapReader(emitter.usesBundledBitmap)?.let {
        appendLine()
        appendLine(it)
      }
      // Last in the file, and deliberately: a Lottie animation is a few thousand columns of
      // minified JSON, and a reader who has to scroll past it to reach the widget has been
      // handed a worse file than one who can stop reading at the preview.
      emitter.declarations.forEach {
        appendLine()
        appendLine(it)
      }
    }
    return Outcome.Generated(
      name = name,
      source = source,
      // `assets/` here and not in the path the source opens: `AssetManager` is already rooted at
      // the source set's `assets/` directory, so the widget asks for `uibuilder/...` while the
      // archive has to say where that directory goes.
      files =
        (emitter.bundledBackgrounds + parameters.mapNotNull { it.bundled }).map {
          WidgetBundleFile(
            path = "$ASSET_SOURCE_DIRECTORY/${it.path}",
            mediaType = it.mediaType,
            base64 = it.base64,
          )
        },
      parameters = parameters,
    )
  }

  /**
   * The locals a bundled picture becomes, in the order the reader meets them.
   *
   * A background is unconditional — the design draws that picture and no application supplies it. A
   * content picture is the application's, so its local keeps the parameter and falls back: to the
   * design's own artwork where the archive carries it, and to the blank bitmap the inlining lane
   * uses where it does not. Every parameter gets a line, not only the ones the archive can answer:
   * in this lane they are all nullable, and the content function takes bitmaps that are not.
   */
  private fun bundledLocals(
    bundled: Boolean,
    backgrounds: List<RemoteContentEmitter.BundledAsset>,
    parameters: List<RemoteContentEmitter.ImageParameter>,
  ): List<String> {
    if (!bundled) return emptyList()
    return backgrounds.map { "val ${it.identifier} = context.bundledBitmap(\"${it.path}\")" } +
      parameters.map { parameter ->
        val fallback =
          parameter.bundled?.let { "context.bundledBitmap(\"${it.path}\")" }
            ?: "ImageBitmap(1, 1).rb"
        "val ${parameter.identifier} = ${parameter.identifier} ?: $fallback"
      }
  }

  /** The archive's own reader: one file, opened by path and decoded where it is drawn. */
  private fun bundledBitmapReader(used: Boolean): String? {
    if (!used) return null
    return buildString {
      appendLine("/** A picture this widget's bundle ships, under the module's `assets/`. */")
      appendLine("private fun Context.bundledBitmap(path: String): RemoteImageBitmap =")
      appendLine("${INDENT}assets.open(path).use { BitmapFactory.decodeStream(it) }")
      appendLine("$INDENT$INDENT.asImageBitmap()")
      append("$INDENT$INDENT.rb")
    }
  }

  /** What the archive says about itself: where the files go, and what the application passes. */
  private fun readme(outcome: Outcome.Generated): String = buildString {
    appendLine("# ${outcome.name}")
    appendLine()
    appendLine("Generated from a Compose UI builder design.")
    appendLine()
    appendLine("## Where the files go")
    appendLine()
    appendLine("- `${outcome.name}.kt` — anywhere in your source set; set its package to match.")
    if (outcome.files.isNotEmpty()) {
      appendLine(
        "- everything under `$ASSET_SOURCE_DIRECTORY/` — into the module's " +
          "`src/main/$ASSET_SOURCE_DIRECTORY/`, keeping the paths."
      )
      appendLine()
      appendLine("The widget opens each picture through `AssetManager`, by the path written in the")
      appendLine("source, so the directories have to survive the copy:")
      appendLine()
      outcome.files.forEach { appendLine("- `${it.path}` (`${it.mediaType}`)") }
    }
    if (outcome.parameters.isNotEmpty()) {
      appendLine()
      appendLine("## What your application passes")
      appendLine()
      appendLine("The widget's constructor takes one bitmap per content picture. Each defaults to")
      appendLine("the artwork the design was drawn with, so the `@Preview` shows the design; pass")
      appendLine("your own to draw real data:")
      appendLine()
      outcome.parameters.forEach {
        val default =
          if (it.bundled != null) "the design's `${it.assetKey}` artwork"
          else "a blank bitmap — the design's `${it.assetKey}` is not in this archive"
        appendLine("- `${it.identifier}` — $default")
      }
    }
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
   * generated `@Preview` below still compile — it constructs this class with no arguments.
   *
   * What that default can be is the difference between the lanes. Inlining has nowhere to put a
   * second picture, so it is a 1×1 transparent bitmap: a placeholder that looked like a picture
   * would be a preview showing something the design does not have, and the hole is the honest
   * preview. A bundle carries the artwork, so the parameter is nullable and `null` means "what the
   * design was drawn with" — resolved in `provideWidgetData`, where the `Context` is, because a
   * constructor default cannot open a file (`docs/design/UI_BUILDER_EXPORT_BUNDLE.md`).
   */
  private fun widgetClassHeader(
    name: String,
    parameters: List<RemoteContentEmitter.ImageParameter>,
    bundled: Boolean,
  ): String {
    if (parameters.isEmpty()) return "class $name : GlanceWearWidget() {"
    return buildString {
      appendLine("class $name(")
      parameters.forEach {
        appendLine("$INDENT// The design's `${it.assetKey.escapeComment()}` asset.")
        if (bundled) {
          appendLine("${INDENT}private val ${it.identifier}: RemoteImageBitmap? = null,")
        } else {
          appendLine(
            "${INDENT}private val ${it.identifier}: RemoteImageBitmap = ImageBitmap(1, 1).rb,"
          )
        }
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
  private fun documentReturn(background: RemoteContentEmitter.Background): List<String> {
    val expression = background.expression
    val body = "$INDENT${INDENT}return WearWidgetDocument(background = "
    val single = "$body$expression) {"
    if (single.length <= MAX_LINE) return listOf(single)
    // Hoisted, and then broken between the chain's calls when the hoist is itself too long. An
    // image element carries no length of its own — the bytes live in a val below — but a widget
    // that layers a picture under two literal gradients still spends well past the budget on one
    // line, and a generated file nobody can read across is a worse answer than a wrapped one.
    val hoisted = "$INDENT$INDENT$INDENT$expression"
    val chain =
      if (hoisted.length <= MAX_LINE || background.elements.size < 2) listOf(hoisted)
      else
        background.elements.mapIndexed { index, element ->
          if (index == 0) "$INDENT$INDENT${INDENT}WearWidgetBrush.$element"
          else "$INDENT$INDENT$INDENT$INDENT.$element"
        }
    return listOf("$INDENT${INDENT}val background =") +
      chain +
      listOf("$INDENT${INDENT}return WearWidgetDocument(background = background) {")
  }

  private fun refuse(reason: String) = Outcome.Refused(listOf(reason))

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

  private const val MAX_LINE = 100

  internal const val WEAR_WIDGET_SPEC_PADDING_DP = 8f

  internal const val WEAR_WIDGET_SPEC_CORNER_RADIUS_DP = 26f

  /** Where an Android module's `AssetManager` root is, which is what the archive has to name. */
  private const val ASSET_SOURCE_DIRECTORY = "assets"
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
internal fun UiBuilderDocument.widgetIdentifier(): String {
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
