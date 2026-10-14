package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generates the Kotlin a `wear-m3` screen design becomes: a `ScreenScaffold` around a
 * `TransformingLazyColumn`, with the row transformation the canvas cannot draw, and the
 * `@WearPreviewDevices` preview that renders it at every round size.
 *
 * Unlike [WearWidgetCodeExporter]'s host frame, `ScreenScaffold` is emitted: the author calls it.
 * `transformedHeight`/`SurfaceTransformation` are written unconditionally since they are what a
 * `TransformingLazyColumn` is. Unmappable nodes are refused by name, never approximated.
 */
object WearScreenCodeExporter {

  /** What a design generates, or why it does not. */
  sealed interface Result {
    /**
     * @param screenName the composable the file declares, derived from the title via
     *   [screenIdentifier]; the native preview lane imports it. Without previews it names a wrapper
     *   adding the `AppScaffold` and frozen `TimeText`, with the body as `<Screen>Content`.
     */
    data class Emitted(
      val source: String,
      val screenName: String,
      /** What the source needs but cannot say for itself; see [ExportDiagnosticCodes]. */
      val diagnostics: List<ExportDiagnosticV1> = emptyList(),
    ) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /**
   * @param packageName the package the emitted file declares, or null for the pane's snippet. See
   *   [WearWidgetCodeExporter.export]; the two lanes differ by exactly this line.
   * @param tagNodes whether every emitted composable carries `Modifier.testTag("<node id>")`. For
   *   the native preview lane only, where tags map rendered bounds back to design nodes; exports
   *   stay untagged.
   * @param packComponents component-pack components this screen may hold, by id, each as its
   *   record; written from the record's proven call site. Empty refuses every pack node. See
   *   [WearContentEmitter.emitPack].
   * @param previews whether the file carries the `@WearPreviewDevices` / `@ScrollingPreview`
   *   fan-out. Defaults to `!tagNodes`: the native lane's classpath lacks the tooling artifacts and
   *   writes its own `@Preview`.
   */
  fun export(
    document: UiBuilderDocument,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
  ): Result =
    export(document, packageName, tagNodes, previews, packComponents, VariableFontExport.None)

  /**
   * [export], writing each variable font text's declaration with [variableFonts] and joining it
   * into this file after the screen. An overload of its own, not a default argument, to keep the
   * published JVM descriptor above.
   */
  fun export(
    document: UiBuilderDocument,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
    variableFonts: VariableFontExport,
  ): Result {
    val rootId = document.roots.singleOrNull() ?: return refuse("a screen design has one root")
    val root = document.nodes[rootId] ?: return refuse("the root node `$rootId` is missing")
    if (root.componentId != SCAFFOLD) {
      return refuse(
        "the root is `${root.componentId}`, not `$SCAFFOLD` — this generator writes Wear screens, " +
          "and a widget belongs to WearWidgetCodeExporter"
      )
    }

    val refusals = mutableListOf<String>()
    val emitter = WearContentEmitter(document, refusals, tagNodes, packComponents)
    val contentIds = root.slots["content"].orEmpty()
    val body =
      when (contentIds.size) {
        // An empty screen is `ScreenScaffold { }`; a bare `item {}` here would have no item scope.
        0 -> emptyList()
        1 -> emitter.emitScaffoldBody(contentIds.single())
        else -> {
          refusals +=
            "the screen scaffold holds one content body; this design has ${contentIds.size}"
          emptyList()
        }
      }
    val edgeButtonIds = root.slots["edgeButton"].orEmpty()
    if (edgeButtonIds.size > 1) {
      refusals +=
        "`ScreenScaffold(edgeButton = …)` takes one composable; this design has ${edgeButtonIds.size}"
    }
    val edgeButton = edgeButtonIds.firstOrNull()?.let { emitter.emitEdgeButton(it, depth = 3) }
    // Dialogs are siblings of `ScreenScaffold`, and their `visible` flags hoist a `remember` that
    // must be declared above both.
    val overlays = root.slots["overlays"].orEmpty().flatMap { emitter.emitOverlay(it, depth = 1) }
    // Read before the imports are, because naming a theme is what imports `MaterialTheme`.
    val colorScheme = emitter.themeColorScheme(rootId)
    val fonts = AndroidGoogleFonts(INDENT)
    val typography = emitter.themeTypography(rootId, fonts, depth = 2)
    // The role text with no `style` is set in, as the canvas provides it; see [ThemeTextStyle].
    val textRole = ThemeTextStyle.role(root)?.let(ThemeTextStyle::wearRole)
    val variableFontRequests = emitter.variableFontRequests()
    val variableFontMode = variableFonts.mode
    val variableFontSources = variableFontRequests.map {
      variableFonts.generator.generate(it, packageName ?: VARIABLE_FONT_PACKAGE, variableFontMode)
    }
    variableFontSources.filterIsInstance<VariableFontSource.Refused>().forEach {
      refusals += it.reason
    }
    if (refusals.isNotEmpty()) return Result.Refused(refusals.distinct())
    val variableFontFiles =
      variableFontSources.filterIsInstance<VariableFontSource.Generated>().map { it.source }
    val variableFontsPending = variableFontRequests.filterIndexed { index, _ ->
      variableFontSources[index] == null
    }

    val name = document.screenIdentifier()
    val timeText = root.text("timeText")
    // Without previews (native lane) the design's name is the `AppScaffold` wrapper and the body
    // moves to `<Screen>Content`; an export keeps the body under the design's name.
    val screenFunction = if (previews) name else "${name}Content"
    val screenSource = buildString {
      appendLine("// Generated from a Compose UI builder design. Do not edit by hand.")
      if (variableFontFiles.isNotEmpty() && variableFontMode == VariableFontExportMode.LIBRARY) {
        appendLine(
          "// Its variable font text draws through flexpress: " +
            "implementation(\"ee.schimke.flexpress:flexpress-compose:$FLEXPRESS_VERSION\")."
        )
      }
      appendLine()
      if (packageName != null) {
        appendLine("package $packageName")
        appendLine()
      }
      (emitter.imports(timeText != null, previews) +
          fonts.imports +
          if (textRole != null)
            listOf(
              "androidx.wear.compose.material3.MaterialTheme",
              "androidx.wear.compose.material3.ProvideTextStyle",
            )
          else emptyList())
        .distinct()
        .sorted()
        .forEach { appendLine("import $it") }
      appendLine()
      appendLine("@Composable")
      appendLine("fun $screenFunction() {")
      appendLine("${INDENT}val listState = rememberTransformingLazyColumnState()")
      appendLine("${INDENT}val spec = rememberTransformationSpec()")
      // Hoisted state for controlled components, so the generated screen runs as-is.
      emitter.stateDeclarations().forEach { appendLine("${INDENT}$it") }
      // No `AppScaffold` here: it belongs once at the app root (as in ComposeStarter's `WearApp`);
      // the previews supply it. The theme wraps the scaffold and its dialogs as an indented block.
      val screen = StringBuilder()
      with(screen) {
        append("${INDENT}ScreenScaffold(scrollState = listState")
        emitter.rootModifier(rootId)?.let { append(", modifier = $it") }
        appendLine(",")
        // The indicator is the design's choice; the capture guard is not.
        // `LocalScrollCaptureInProgress` hides it during long screenshots, which would otherwise
        // paint it once per slice.
        if (root.flag("scrollIndicator") ?: true) {
          appendLine(
            "${INDENT}${INDENT}scrollIndicator = { if (!LocalScrollCaptureInProgress.current) ScrollIndicator(listState) },"
          )
        } else {
          appendLine("${INDENT}${INDENT}scrollIndicator = null,")
        }
        if (edgeButton != null) {
          appendLine("${INDENT}${INDENT}edgeButton = {")
          edgeButton.forEach { appendLine(it) }
          appendLine("${INDENT}${INDENT}},")
        }
        appendLine("${INDENT}) { contentPadding ->")
        body.forEach { appendLine(it) }
        appendLine("${INDENT}}")
        overlays.forEach { appendLine(it) }
      }
      if (textRole != null) {
        // Inside the theme, so the role is the themed one; around everything the screen draws.
        val provided = screen.lines().dropLast(1)
        screen.clear()
        screen.appendLine("${INDENT}ProvideTextStyle(MaterialTheme.typography.$textRole) {")
        provided.forEach { screen.appendLine(if (it.isEmpty()) it else INDENT + it) }
        screen.appendLine("${INDENT}}")
      }
      if (colorScheme == null && typography == null) append(screen)
      else {
        if (typography == null) appendLine("${INDENT}MaterialTheme(colorScheme = $colorScheme) {")
        else {
          // The typefaces as well as the colours: one theme, so the scaffold's text and every
          // row inside it read the same type scale the canvas drew.
          appendLine("${INDENT}MaterialTheme(")
          colorScheme?.let { appendLine("$INDENT${INDENT}colorScheme = $it,") }
          typography.forEach(::appendLine)
          appendLine("$INDENT) {")
        }
        screen.lines().dropLast(1).forEach { appendLine(if (it.isEmpty()) it else INDENT + it) }
        appendLine("${INDENT}}")
      }
      appendLine("}")
      fonts.declarations.forEach {
        appendLine()
        appendLine(it)
      }
      // `AppScaffold` owns the status strip — `ScreenScaffold` has no `timeText` argument — so
      // a design that declares one gets the pair, frozen, around the screen.
      val appScaffold =
        if (timeText != null)
          "AppScaffold(timeText = { TimeText { timeTextCurvedText(${timeText.quoted()}) } }) { $screenFunction() }"
        else "AppScaffold { $screenFunction() }"
      if (previews) {
        appendLine()
        // Every round size, via the shipped `WearPreviewDevices` provider.
        appendLine("@WearPreviewDevices")
        appendLine("@Composable")
        appendLine("fun ${name}Preview() {")
        appendLine("${INDENT}$appScaffold")
        appendLine("}")
        appendLine()
        // `ScrollMode.LONG` stitches the scroll with the row transformation off — what the canvas
        // draws — so this render checks canvas parity. One device suffices: the small round screen
        // designs are authored on.
        appendLine(
          "@Preview(device = ${WEAR_PARITY_DEVICE.quoted()}, showBackground = true, backgroundColor = 0xFF000000)"
        )
        appendLine("@ScrollingPreview(modes = [ScrollMode.LONG])")
        appendLine("@Composable")
        appendLine("fun ${name}LongPreview() {")
        appendLine("${INDENT}$appScaffold")
        appendLine("}")
      } else {
        appendLine()
        appendLine("@Composable")
        appendLine("fun $name() {")
        appendLine("${INDENT}$appScaffold")
        appendLine("}")
      }
      variableFontsPending.forEach {
        appendLine()
        appendLine(
          "// ${it.functionName} draws \"${it.spec.text}\" in ${it.spec.font.family} " +
            "from the font's outlines (flexpress). It is generated when the design exports."
        )
      }
    }
    return Result.Emitted(
      screenName = name,
      source = joinKotlinFiles(screenSource, variableFontFiles),
      diagnostics = emitter.diagnostics,
    )
  }

  /** The package a variable font text's declaration takes when the screen has none (a snippet). */
  private const val VARIABLE_FONT_PACKAGE = "generated.uibuilder"

  private fun refuse(reason: String) = Result.Refused(listOf(reason))

  /** The scaffold's `timeText`, or null when the design declares none. */
  private fun UiBuilderNode.text(name: String): String? =
    (properties[name] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull?.takeIf {
      it.isNotEmpty()
    }

  /** A boolean property, read the same way [text] reads a string one. */
  private fun UiBuilderNode.flag(name: String): Boolean? =
    (properties[name] as? JsonObject)?.get("value")?.jsonPrimitive?.booleanOrNull

  const val SCAFFOLD = "wear-m3/screen-scaffold"

  const val TRANSFORMING_LAZY_COLUMN = "wear-m3/transforming-lazy-column"

  const val LIST_HEADER = "wear-m3/list-header"

  /**
   * The Wear content ids this generator writes. Wear-specific ids because Material 3 and Wear
   * Material 3 are not used together.
   */
  const val TEXT = "wear-m3/text"

  const val CARD = "wear-m3/card"

  const val BUTTON = "wear-m3/button"

  /**
   * The Wear components with no Material 3 counterpart. The canvas draws a named placeholder; the
   * native preview lane renders this generator's output against real Wear Compose. See
   * `docs/design/UI_BUILDER_WEAR_SCREEN.md`.
   */
  const val ICON = "wear-m3/icon"

  const val ICON_BUTTON = "wear-m3/icon-button"

  const val TEXT_BUTTON = "wear-m3/text-button"

  const val LIST_SUB_HEADER = "wear-m3/list-sub-header"

  const val CHECKBOX_BUTTON = "wear-m3/checkbox-button"

  const val SWITCH_BUTTON = "wear-m3/switch-button"

  const val RADIO_BUTTON = "wear-m3/radio-button"

  const val SLIDER = "wear-m3/slider"

  const val STEPPER = "wear-m3/stepper"

  const val PROGRESS_INDICATOR = "wear-m3/progress-indicator"

  const val EDGE_BUTTON = "wear-m3/edge-button"

  const val BUTTON_GROUP = "wear-m3/button-group"

  const val ALERT_DIALOG = "wear-m3/alert-dialog"

  const val CONFIRMATION_DIALOG = "wear-m3/confirmation-dialog"

  const val OPEN_ON_PHONE_DIALOG = "wear-m3/open-on-phone-dialog"

  const val DATE_PICKER = "wear-m3/date-picker"

  const val TIME_PICKER = "wear-m3/time-picker"

  /**
   * Components that own the whole round display (`Stepper`, pickers); they go in the scaffold's
   * content slot instead of the list.
   */
  val FULL_SCREEN: Set<String> = setOf(STEPPER, DATE_PICKER, TIME_PICKER)

  /**
   * Components that are a screen state rather than a place in the layout: dialogs with a `visible`
   * flag, emitted as siblings of `ScreenScaffold` via the `overlays` slot.
   */
  val OVERLAYS: Set<String> = setOf(ALERT_DIALOG, CONFIRMATION_DIALOG, OPEN_ON_PHONE_DIALOG)

  /**
   * Every id above, as one public set the canvas reads to draw named placeholders, so it cannot
   * fall back to "Unsupported component" for something that exports fine.
   */
  val NATIVE_ONLY_COMPONENT_IDS: Set<String> =
    setOf(
      ICON,
      ICON_BUTTON,
      TEXT_BUTTON,
      LIST_SUB_HEADER,
      CHECKBOX_BUTTON,
      SWITCH_BUTTON,
      RADIO_BUTTON,
      SLIDER,
      STEPPER,
      PROGRESS_INDICATOR,
      EDGE_BUTTON,
      BUTTON_GROUP,
      DATE_PICKER,
      TIME_PICKER,
    ) + OVERLAYS

  /**
   * Every Wear Material 3 composable that declares a `transformation: SurfaceTransformation`, read
   * off the library (`wear-compose-material3` 1.7.0-beta02). Passing it to anything else does not
   * compile. See [WearContentEmitter.surfaceArguments].
   */
  val SURFACE_TRANSFORMATION_SYMBOLS: Set<String> =
    setOf(
      "AppCard",
      "Button",
      "ButtonGroup",
      "Card",
      "CheckboxButton",
      "ChildButton",
      "CompactButton",
      "FilledTonalButton",
      "ListHeader",
      "ListSubHeader",
      "OutlinedButton",
      "OutlinedCard",
      "RadioButton",
      "SplitCheckboxButton",
      "SplitRadioButton",
      "SplitSwitchButton",
      "SwitchButton",
      "TitleCard",
    )

  internal const val INDENT = "    "

  /** The two parameter types a pack component's row treatment reaches; see [emitPack]. */
  const val MODIFIER_FQN = "androidx.compose.ui.Modifier"
  const val SURFACE_TRANSFORMATION_FQN = "androidx.wear.compose.material3.SurfaceTransformation"

  /**
   * The parity capture's screen: `wearos_small_round`, the tightest, and the frame `wear-m3`
   * designs are created on.
   */
  internal const val WEAR_PARITY_DEVICE: String = "id:wearos_small_round"
}
