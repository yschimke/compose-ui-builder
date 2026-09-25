package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generates the Kotlin a `wear-m3` screen design becomes: a `ScreenScaffold` around a
 * `TransformingLazyColumn`, with the row transformation the canvas cannot draw, and the
 * `@WearPreviewDevices` preview that renders it at every round size.
 *
 * ## The stand-in is emitted, not erased
 *
 * This is the difference from [WearWidgetCodeExporter], and it is the whole reason a second
 * generator exists rather than a flag on that one. A widget's host frame is drawn by the launcher:
 * `remote-m3/widget-container-*` stands in for something the widget does not own, so the generated
 * widget names it nowhere. `ScreenScaffold` is the opposite — the author calls it, it is in their
 * source, and a screen that did not emit it would not compile into anything a watch shows. So
 * `wear-m3/screen-scaffold` is faked only in the *drawing*: the canvas has no Wear Compose to draw
 * with, and this writes the real call.
 *
 * ## What the canvas is not showing you
 *
 * Two things, and both are emitted here. `Modifier.transformedHeight(this, spec)` and
 * `SurfaceTransformation(spec)` are what scale and fade a row toward the curved edges, and the Wasm
 * canvas draws a plain Column instead. And the round screen insets a row's width near the caps,
 * which the stadium's straight sides do not. Neither is a property somebody set — they are what a
 * `TransformingLazyColumn` *is* — so they are written unconditionally and the generated source is
 * the honest picture of the design.
 *
 * ## Refusals are by name
 *
 * The same discipline as the other two generators. `wear-m3` borrows its content components from
 * `m3-catalog` while it has none of its own, and a borrowed component only exports where Wear
 * Compose Material 3 publishes something it plainly maps to. Everything else is refused with the
 * node named, never approximated: `Text` maps, a `SearchBar` does not and a watch has no such thing
 * to map it to.
 */
object WearScreenCodeExporter {

  /** What a design generates, or why it does not. */
  sealed interface Result {
    /**
     * @param screenName the composable the file declares, which the caller needs and cannot
     *   recompute. The native preview lane imports it by name into the `@Preview` it wraps the
     *   design in, and the name comes from the design's *title* through [screenIdentifier] — a
     *   transformation nothing outside this file should be reimplementing.
     *
     *   The same name with or without previews. Without them (the native lane) it names the wrapper
     *   that puts the screen in the `AppScaffold` and frozen `TimeText` an app's root would
     *   provide, with the scaffold body as `<Screen>Content`: the exported screen no longer carries
     *   them (see [export]), and a native render without the status strip would be a picture of a
     *   screen no watch shows.
     */
    data class Emitted(val source: String, val screenName: String) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /**
   * @param packageName the package the emitted file declares, or null for the pane's snippet. See
   *   [WearWidgetCodeExporter.export]; the two lanes differ by exactly this line.
   * @param tagNodes whether every emitted composable carries `Modifier.testTag("<node id>")`.
   *
   * Tagging is for the **native preview lane** and for nothing else. A streamed frame is a picture,
   * and a picture is not an editor: without a tag there is no way to say which rectangle on it
   * draws the card you selected. The server's annotation lane reports authored test tags with their
   * bounds in render pixels, so a tagged render comes back with a map from design node id to
   * rectangle — which is what makes an overlay and a clickable region possible over an image the
   * browser did not draw. An export artifact is left untagged, because a test tag is not something
   * a designer asked for in source they keep.
   *
   * @param packComponents the **component pack** components this screen may hold, by component id
   *   (`confetti-wear/section-header`), each as the record the pack was projected from. A pack is
   *   another catalog's composable admitted into `wear-m3` (`UiBuilderComponentPacks`); the canvas
   *   draws it as a placeholder and this writes the real call from its record — the proven call
   *   site's imports and placeholders, the design's literals for its parameters, its children in
   *   its slots. Empty by default, which refuses every pack node by name exactly as before packs
   *   existed. See [WearContentEmitter.emitPack].
   * @param previews whether the file carries the `@WearPreviewDevices` / `@ScrollingPreview`
   *   fan-out an **export artifact** wants. It defaults to `!tagNodes`, because the two questions
   *   have the same answer: tagging is what the native preview lane asks for and nothing else does,
   *   and that lane is also the one that cannot carry these previews. They import
   *   `androidx.wear.compose:compose-ui-tooling` and compose-ai-tools' `preview-annotations`,
   *   neither of which is on a catalog's runtime bundle — the classpath the lane compiles against —
   *   so emitting them made every Wear design fail there with `Unresolved reference
   *   'WearPreviewDevices'`, which reads like a broken design rather than a source file asking for
   *   artifacts the host deliberately does not have. The lane writes its own `@Preview` around the
   *   composable (`UiBuilderGeneratedPreviewAdapter`), so nothing is lost by their absence.
   *
   *   Deriving the default rather than requiring the caller to pass both is what keeps this a
   *   change to one repository. A caller that has to say `tagNodes = true, previews = false` is a
   *   caller that cannot compile until this module publishes, which is a two-repository release for
   *   one flag.
   */
  fun export(
    document: UiBuilderDocument,
    packageName: String? = null,
    tagNodes: Boolean = false,
    previews: Boolean = !tagNodes,
    packComponents: Map<String, ComponentRecord> = emptyMap(),
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
        // An empty screen draws nothing, which is what `ScreenScaffold { }` is. It used to write a
        // bare `item {}` into the scaffold's own content lambda, which has no item scope and never
        // compiled.
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
    // Emitted after the body and before the source is assembled, because a dialog is a sibling of
    // the `ScreenScaffold` rather than a node inside it — and because its `visible` flag hoists a
    // `remember` that has to be declared above both.
    val overlays = root.slots["overlays"].orEmpty().flatMap { emitter.emitOverlay(it, depth = 1) }
    if (refusals.isNotEmpty()) return Result.Refused(refusals.distinct())

    val name = document.screenIdentifier()
    // The clock: a `timeText` string on the scaffold in the retired vocabulary, a
    // `wear-m3/time-text` component in its `timeText` slot in the one catalogs publish.
    val timeText =
      root.text("timeText")?.let { "TimeText { timeTextCurvedText(${it.quoted()}) }" }
        ?: root.slots["timeText"]
          ?.firstOrNull()
          ?.let(document.nodes::get)
          ?.takeIf { it.componentId == TIME_TEXT }
          ?.let { "TimeText()" }
    // The native lane renders the composable named [Result.Emitted.screenName] and writes no
    // previews, so there the screen is the `AppScaffold` wrapper under the design's name and the
    // scaffold body moves to `<Screen>Content`. The name the lane imports is the same in both
    // modes, and the source it compiles is never one a person keeps. An export keeps the body
    // under the design's name and its previews wrap it.
    val screenFunction = if (previews) name else "${name}Content"
    return Result.Emitted(
      screenName = name,
      source =
        buildString {
          appendLine("// Generated from a Compose UI builder design. Do not edit by hand.")
          appendLine()
          if (packageName != null) {
            appendLine("package $packageName")
            appendLine()
          }
          emitter.imports(timeText != null, previews).forEach { appendLine("import $it") }
          appendLine()
          appendLine("@Composable")
          appendLine("fun $screenFunction() {")
          appendLine("${INDENT}val listState = rememberTransformingLazyColumnState()")
          appendLine("${INDENT}val spec = rememberTransformationSpec()")
          // A slider, a stepper, a selection control and a dialog are all controlled: they take a
          // value and hand back a new one. Hoisting that here is what an author would write, and it
          // is the difference between a generated screen you can run and one you have to finish.
          emitter.stateDeclarations().forEach { appendLine("${INDENT}$it") }
          // No `AppScaffold` here. It belongs once at the app's root, around the navigation host,
          // with `ScreenScaffold` per destination — which is how ComposeStarter's `WearApp` is
          // built. A screen that brought its own nested one inside every destination it was
          // dropped into, and its `TimeText` froze the clock at the design's `10:10` in shipping
          // code. The previews below supply both, which is where a frozen time belongs.
          append("${INDENT}ScreenScaffold(scrollState = listState")
          emitter.rootModifier(rootId)?.let { append(", modifier = $it") }
          appendLine(",")
          // The indicator is the design's choice, and the capture guard is not. The guard stays on
          // both arms: a long screenshot composites many frames into one image, and an indicator
          // painted at a different offset in every slice lands as a column of dashes down the
          // edge. `LocalScrollCaptureInProgress` is the platform's own signal for that — Android's
          // system long-screenshot sets it — so reading it is app behaviour rather than a preview
          // concession.
          // A slot in the published vocabulary, a flag in the retired one.
          if (root.slots["scrollIndicator"]?.isNotEmpty() ?: root.flag("scrollIndicator") ?: true) {
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
          appendLine("}")
          // `AppScaffold` owns the status strip — `ScreenScaffold` has no `timeText` argument — so
          // a design that declares one gets the pair, frozen, around the screen.
          val appScaffold =
            if (timeText != null) "AppScaffold(timeText = { $timeText }) { $screenFunction() }"
            else "AppScaffold { $screenFunction() }"
          if (previews) {
            appendLine()
            // Every round size, because a Wear screen that only ever rendered at one is a screen
            // whose list has not been seen wrap. `WearPreviewDevices` is the shipped provider for
            // exactly this.
            appendLine("@WearPreviewDevices")
            appendLine("@Composable")
            appendLine("fun ${name}Preview() {")
            appendLine("${INDENT}$appScaffold")
            appendLine("}")
            appendLine()
            // The second preview is the one that answers "is the canvas telling the truth?".
            //
            // `ScrollMode.LONG` stitches the whole scroll into one tall PNG **with the row
            // transformation off**, which is exactly what the builder's stadium draws — so this
            // render and the design as it appeared on the canvas are the same picture, and a
            // difference between them is a bug in one of the two. The multipreview above cannot
            // carry it: `LONG` on five devices is five stitched captures to answer a question one
            // answers, and the parity claim is about the small round screen a design is authored
            // on.
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
        },
    )
  }

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
   * The Wear content ids this generator writes.
   *
   * They used to be `m3/text`, `m3/card` and `m3/button` — borrowed mobile Material ids that this
   * emitter quietly translated into `Text`, `TitleCard` and `Button` from
   * `androidx.wear.compose.material3`. The translation was right and the naming was not: **Material
   * 3 and Wear Material 3 are not used together**, so a Wear design holding a component called
   * `m3/card` claimed something no watch screen can mean. The ids are Wear's now; the canvas still
   * draws the Material 3 lookalike, because it has no Wear Compose to draw with.
   */
  const val TEXT = "wear-m3/text"

  const val CARD = "wear-m3/card"

  /** Published `TitleCard`, with its title, subtitle and time as slots rather than a column. */
  const val TITLE_CARD = "wear-m3/title-card"

  /** Published `TimeText`, placed in the scaffold's `timeText` slot. */
  const val TIME_TEXT = "wear-m3/time-text"

  const val BUTTON = "wear-m3/button"

  /**
   * The Wear components that have **no Material 3 counterpart at all**, and so were never
   * borrowable.
   *
   * They are here because the fidelity question moved. While the browser canvas was the only place
   * a Wear design was looked at, adding one of these meant hand-assembling a lookalike for a
   * library Wasm cannot link, which
   * [`docs/design/UI_BUILDER_WEAR_SCREEN.md`](../../../../../../../docs/design/UI_BUILDER_WEAR_SCREEN.md)
   * rules out and [#395](https://github.com/yschimke/compose-preview-server/pull/395) proved by
   * building it. The native preview lane compiles this generator's own output against real Wear
   * Compose on the Android daemon, so the picture comes from there and the canvas draws a named
   * placeholder that claims nothing. What this file owes them is the call site, and that is what
   * the branches below are.
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
   * The components that own the whole round display rather than a row of a list.
   *
   * A `Stepper` puts its increment and decrement buttons on the top and bottom of the screen and a
   * picker fills it with columns: putting either in a `TransformingLazyColumn` item is a screen
   * inside a scroll inside a screen. They go in the scaffold's content slot **instead of** the
   * list, which is the one place they fit, and the generator writes them there directly.
   */
  val FULL_SCREEN: Set<String> = setOf(STEPPER, DATE_PICKER, TIME_PICKER)

  /**
   * The components that are a screen *state* rather than a place in the layout.
   *
   * Wear's dialogs take a `visible` flag and draw over everything when it is set, so they are
   * emitted as siblings of the `ScreenScaffold` inside `AppScaffold` — never as list items, which
   * is where a slot in the content tree would have put them. That is why the scaffold grew an
   * `overlays` slot rather than accepting them in `content`.
   */
  val OVERLAYS: Set<String> = setOf(ALERT_DIALOG, CONFIRMATION_DIALOG, OPEN_ON_PHONE_DIALOG)

  /**
   * Every id above, as one public set — the canvas's half of the same fact.
   *
   * The editor draws these as named placeholders rather than as components, and the generator
   * writes them as real Wear Compose. Those two have to be the same list, so the canvas reads this
   * rather than keeping a copy: a component added to the generator and missed by the canvas falls
   * through to the red "Unsupported component" box, which is exactly the wrong thing to tell an
   * author about a component that exports perfectly well.
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
   * Every Wear Material 3 composable that declares a `transformation: SurfaceTransformation`.
   *
   * Read off the library rather than reasoned about: these are exactly the symbols whose signature
   * carries the parameter (`wear-compose-material3` 1.7.0-beta02). A surface treatment belongs to
   * things that draw a surface, so the sliders, steppers, progress indicators, pickers, icon
   * buttons and text buttons are deliberately absent — passing it to any of them is a compile error
   * in the receiving project, which is what this set exists to prevent. See
   * [WearContentEmitter.surfaceArguments].
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
   * The screen the parity capture is taken on: `wearos_small_round`, the smallest and the tightest.
   *
   * A list that fits at 192dp fits everywhere, and 192dp is the frame a `wear-m3` design is created
   * on, so this is the one where the canvas and the render are the same design at the same size.
   */
  internal const val WEAR_PARITY_DEVICE: String = "id:wearos_small_round"
}
