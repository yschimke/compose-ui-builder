package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1

/**
 * Generates the Kotlin a Wear widget design becomes: a `GlanceWearWidget`, its Remote Compose
 * content, and a `@Preview` per host container shape.
 *
 * Not [CapabilityComposeCodeExporter]: a widget runs Remote Compose and is delivered as a
 * `WearWidgetDocument`, so `remote-m3` has no component record. The `remote-m3/widget-container-*`
 * scaffold stands in for the host's `WearWidgetContainer` and is not emitted: its background
 * becomes the `WearWidgetBrush` and its size picks the preview providers. Unwritable nodes are
 * refused by name, never approximated.
 */
object WearWidgetCodeExporter {

  /** What a design generates, or why it does not. */
  sealed interface Result {
    data class Emitted(
      val source: String,
      /** What the source needs but cannot say for itself; see [ExportDiagnosticCodes]. */
      val diagnostics: List<ExportDiagnosticV1> = emptyList(),
    ) : Result {
      /** The published constructor without [diagnostics], kept for binaries compiled against it. */
      @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
      constructor(source: String) : this(source, emptyList())

      /** The published `copy` without [diagnostics], kept for binaries compiled against it. */
      @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
      fun copy(source: String = this.source): Emitted = Emitted(source, diagnostics)
    }

    data class Refused(val reasons: List<String>) : Result
  }

  /** What a design bundles, or why it does not: [Result] with the archive's other files. */
  sealed interface BundleResult {
    data class Emitted(val bundle: WidgetBundle) : BundleResult

    data class Refused(val reasons: List<String>) : BundleResult
  }

  /**
   * @param packageName the package the emitted file declares, or null for the pane's snippet; the
   *   two differ by exactly this line, written after `@file:Suppress`.
   */
  fun export(
    document: UiBuilderDocument,
    packageName: String? = null,
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
    components: Map<String, ComponentRecord> = emptyMap(),
  ): Result =
    when (val outcome = generate(document, packageName, assets, bundled = null, components)) {
      is Outcome.Refused -> Result.Refused(outcome.reasons)
      is Outcome.Generated -> Result.Emitted(outcome.source, outcome.diagnostics)
    }

  /**
   * The same widget as a project fragment: readable source, with pictures shipped as files. Same
   * walk and refusals as [export]; only where picture bytes go differs. See
   * `docs/design/UI_BUILDER_EXPORT_BUNDLE.md`.
   */
  fun exportBundle(
    document: UiBuilderDocument,
    packageName: String? = null,
    assets: WidgetAssetContents,
    components: Map<String, ComponentRecord> = emptyMap(),
  ): BundleResult =
    when (
      val outcome = generate(document, packageName, WidgetAssetBytes { null }, assets, components)
    ) {
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

  /** One container size's body, and the emitter that wrote it. */
  private class Variant(
    val emitter: RemoteContentEmitter,
    val background: RemoteContentEmitter.Background,
    val body: List<String>,
  )

  private sealed interface Outcome {
    data class Generated(
      val name: String,
      val source: String,
      val files: List<WidgetBundleFile>,
      val parameters: List<RemoteContentEmitter.ImageParameter>,
      val diagnostics: List<ExportDiagnosticV1>,
    ) : Outcome

    data class Refused(val reasons: List<String>) : Outcome
  }

  /**
   * The one walk both lanes take; [bundled] decides the lane (null inlines picture bytes, a
   * registry ships them beside the source).
   */
  private fun generate(
    document: UiBuilderDocument,
    packageName: String?,
    assets: WidgetAssetBytes,
    bundled: WidgetAssetContents?,
    components: Map<String, ComponentRecord> = emptyMap(),
  ): Outcome {
    val rootId = document.roots.singleOrNull() ?: return refuse("a widget design has one root")
    val root = document.nodes[rootId] ?: return refuse("the root node `$rootId` is missing")
    // An adaptive widget is written once per container size from the design it resolves to there
    // (`AdaptiveWearWidget.resolve`). Large first: all its slots are present.
    val adaptive = root.componentId == AdaptiveWearWidget.COMPONENT_ID
    val sizes =
      if (adaptive) listOf(WearWidgetScaffoldSize.Large, WearWidgetScaffoldSize.Small)
      else
        listOf(
          WearWidgetScaffoldSize.entries.firstOrNull { it.componentId == root.componentId }
            ?: return refuse(
              "the root is `${root.componentId}`, not a Wear widget container — this generator " +
                "writes widgets, and a screen belongs to the Compose exporter"
            )
        )

    val refusals = mutableListOf<String>()

    // Padding and corner radius are the host's, not the design's: only the predefined host shapes
    // are supported, so a legacy override on the container is ignored here as it is on the canvas.

    val resolved = sizes.map { AdaptiveWearWidget.resolve(document, it) }
    // The emitter needs the body's depth before writing (a theme wrapper indents it), but only
    // learns whether a theme is needed by emitting. So a throwaway pass asks; its refusals are
    // dropped.
    val usesTheme = resolved.any { sized ->
      val sizedRoot = sized.nodes.getValue(rootId)
      val contentIds = sizedRoot.slots["content"].orEmpty()
      RemoteContentEmitter(
          sized,
          mutableListOf(),
          assets,
          bundled = bundled,
          components = components,
          frameFillingRoot = contentIds.singleOrNull(),
          glanceWearWidget = true,
        )
        .let { probe ->
          probe.background(sizedRoot)
          probe.themeTypography(sizedRoot, depth = 2)
          probe.themeTextStyle(sizedRoot)
          contentIds.singleOrNull()?.let { probe.emit(it, depth = 1) }
          probe.usesTheme
        }
    }
    // An adaptive body sits inside its `if (large)` branch, one level further in.
    val depth = (if (usesTheme) 2 else 1) + (if (adaptive) 1 else 0)

    val variants = resolved.map { sized ->
      val sizedRoot = sized.nodes.getValue(rootId)
      val contentIds = sizedRoot.slots["content"].orEmpty()
      val emitter =
        RemoteContentEmitter(
          sized,
          refusals,
          assets,
          bundled = bundled,
          components = components,
          frameFillingRoot = contentIds.singleOrNull(),
          glanceWearWidget = true,
        )
      val background = emitter.background(sizedRoot)
      emitter.themeTypography(sizedRoot, depth = 2)
      emitter.themeTextStyle(sizedRoot)
      val body =
        when (contentIds.size) {
          0 -> listOf(emitter.emptyBox(depth))
          1 -> emitter.emit(contentIds.single(), depth = depth)
          else -> {
            refusals += "the widget container holds one body; this design has ${contentIds.size}"
            emptyList()
          }
        }
      emitter.validateFunctionNames(
        "${document.widgetIdentifier()}Content",
        document.widgetIdentifier(),
      )
      Variant(emitter, background, body)
    }
    // The background is the container's and does not change with its size, so the first variant's
    // is the widget's; every other file-level fact is the union of what the sizes wrote.
    val emitter = variants.first().emitter
    val emitters = variants.map(Variant::emitter)
    val background = variants.first().background
    if (refusals.isNotEmpty()) return Outcome.Refused(refusals.distinct())

    val name = document.widgetIdentifier()
    val parameters = emitters.flatMap { it.imageParameters }.distinctBy { it.identifier }
    val previews = sizes.flatMap { size -> size.previewShapes.map { size to it } }
    val source = buildString {
      appendLine("// Generated from a Compose UI builder design. Do not edit by hand.")
      appendLine("@file:Suppress(\"RestrictedApi\")")
      appendLine()
      if (packageName != null) {
        appendLine("package $packageName")
        appendLine()
      }
      val shape =
        WidgetSourceShape.Exported(previews.map { (size, it) -> it.paramsProviderFor(size) })
      (emitters.flatMap { it.imports(shape) } +
          listOfNotNull("androidx.glance.wear.core.ContainerInfo".takeIf { adaptive }))
        .distinct()
        .sorted()
        .forEach { appendLine("import $it") }
      appendLine()
      appendLine("@RemoteComposable")
      appendLine("@Composable")
      val signature =
        listOfNotNull(
          "large: Boolean".takeIf { adaptive },
          parameterList(parameters).ifEmpty { null },
        )
      appendLine("fun ${name}Content(${signature.joinToString()}) {")
      // Declare only the state variables an action writes; a `valueChange` names a Remote mutable.
      emitters.flatMap { it.stateLocals() }.distinct().forEach { appendLine("$INDENT$it") }
      val body =
        if (!adaptive) variants.single().body
        else {
          // The one question an adaptive widget asks its host: which container it was given.
          val pad = INDENT.repeat(depth - 1)
          listOf("${pad}if (large) {") +
            variants[0].body +
            listOf("$pad} else {") +
            variants[1].body +
            listOf("$pad}")
        }
      if (usesTheme) {
        val typography = emitters.firstNotNullOfOrNull { it.typography }
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
      appendLine(widgetClassHeader(name, parameters, bundled != null))
      appendLine("${INDENT}override suspend fun provideWidgetData(")
      appendLine("$INDENT${INDENT}context: Context,")
      appendLine("$INDENT${INDENT}params: WearWidgetParams,")
      appendLine("$INDENT): WearWidgetData {")
      // Bundled pictures are opened here, where the `Context` is; constructor defaults have none.
      bundledLocals(bundled != null, emitter.bundledBackgrounds, parameters).forEach {
        appendLine("$INDENT$INDENT$it")
      }
      background.locals.forEach { appendLine("$INDENT$INDENT$it") }
      documentReturn(background).forEach(::appendLine)
      val arguments =
        listOfNotNull(
          "large = params.containerType == ContainerInfo.CONTAINER_TYPE_LARGE".takeIf { adaptive },
          argumentList(parameters).ifEmpty { null },
        )
      appendLine("$INDENT$INDENT${INDENT}${name}Content(${arguments.joinToString()})")
      appendLine("$INDENT$INDENT}")
      appendLine("$INDENT}")
      appendLine("}")
      // One preview per host shape, at the provider's widest footprint (chosen by width, not
      // order). An adaptive widget previews at both sizes.
      previews.forEach { (size, shape) ->
        val label = if (adaptive) "${shape.label} ${size.name}" else shape.label
        appendLine()
        appendLine("@Preview(name = \"$label Preview\")")
        appendLine("@Composable")
        appendLine("fun $name${label.replace(" ", "")}Preview() =")
        appendLine("${INDENT}WearWidgetPreview(")
        appendLine("$INDENT$INDENT$name(),")
        appendLine("$INDENT$INDENT${shape.paramsProviderFor(size)}().values.maxBy { it.widthDp },")
        appendLine("$INDENT)")
      }
      // Inlined pictures go below the preview so readers can stop before the base64.
      inlineBitmapDeclarations(emitters.flatMap { it.inlineBitmaps }.distinct()).forEach {
        appendLine()
        appendLine(it)
      }
      // The bundle lane's one declaration, and it is four lines: the archive holds the bytes, so
      // all the file needs is the way in to them.
      bundledBitmapReader(emitters.any { it.usesBundledBitmap })?.let {
        appendLine()
        appendLine(it)
      }
      // Lottie JSON goes last, for the same reason.
      emitters
        .flatMap { it.declarations }
        .distinct()
        .forEach {
          appendLine()
          appendLine(it)
        }
    }
    return Outcome.Generated(
      name = name,
      source = source,
      // `AssetManager` is rooted at `assets/`, so the source opens `uibuilder/...` while the
      // archive path includes `assets/`.
      files =
        (emitter.bundledBackgrounds + parameters.mapNotNull { it.bundled }).map {
          WidgetBundleFile(
            path = "$ASSET_SOURCE_DIRECTORY/${it.path}",
            mediaType = it.mediaType,
            base64 = it.base64,
          )
        },
      parameters = parameters,
      // An adaptive widget writes most nodes once per size, so the same notice arrives twice.
      diagnostics = emitters.flatMap { it.diagnostics }.distinct(),
    )
  }

  /**
   * The locals bundled pictures become. Backgrounds are unconditional; content pictures keep the
   * (nullable) parameter and fall back to the shipped artwork, else the inlining lane's blank
   * bitmap.
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
   * The widget class, taking the body's pictures as parameters (they are app data), defaulted so
   * the generated `@Preview` can construct it with no arguments: a 1×1 transparent bitmap when
   * inlining, or nullable `null` meaning "the shipped artwork" in a bundle, resolved in
   * `provideWidgetData` where the `Context` is.
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
   * `return WearWidgetDocument(background = <brush>) {`, on one line when it fits [MAX_LINE];
   * otherwise the brush is hoisted into a local.
   */
  private fun documentReturn(background: RemoteContentEmitter.Background): List<String> {
    val expression = background.expression
    val body = "$INDENT${INDENT}return WearWidgetDocument(background = "
    val single = "$body$expression) {"
    if (single.length <= MAX_LINE) return listOf(single)
    // If the hoisted brush is still too long, break it between the chain's calls.
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

  private const val INDENT = "    "

  private const val MAX_LINE = 100

  /** Where an Android module's `AssetManager` root is, which is what the archive has to name. */
  private const val ASSET_SOURCE_DIRECTORY = "assets"
}

/**
 * The shapes a generated widget file previews itself in, in order: the default squircle first (what
 * the canvas opens on), then the rectangular frame the widget picker editor shows.
 *
 * Every [WearWidgetHostShape], so editor and export cannot drift. Providers are size-specific,
 * since a design is authored at one container size. The widget is unchanged between shapes; the
 * frame comes from `WearWidgetParams`.
 */
internal val WearWidgetScaffoldSize.previewShapes: List<WearWidgetHostShape>
  get() = WearWidgetHostShape.entries

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
