package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.ScreenDocumentProjection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
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
   */
  fun export(
    document: UiBuilderDocument,
    packageName: String? = null,
    tagNodes: Boolean = false,
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
    val emitter = WearContentEmitter(document, refusals, tagNodes)
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
    val edgeButton = edgeButtonIds.firstOrNull()?.let { emitter.emitEdgeButton(it, depth = 4) }
    // Emitted after the body and before the source is assembled, because a dialog is a sibling of
    // the `ScreenScaffold` rather than a node inside it — and because its `visible` flag hoists a
    // `remember` that has to be declared above both.
    val overlays = root.slots["overlays"].orEmpty().flatMap { emitter.emitOverlay(it, depth = 2) }
    if (refusals.isNotEmpty()) return Result.Refused(refusals.distinct())

    val name = document.screenIdentifier()
    val timeText = root.text("timeText")
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
          emitter.imports(timeText != null).forEach { appendLine("import $it") }
          appendLine()
          appendLine("@Composable")
          appendLine("fun $name() {")
          appendLine("${INDENT}val listState = rememberTransformingLazyColumnState()")
          appendLine("${INDENT}val spec = rememberTransformationSpec()")
          // A slider, a stepper, a selection control and a dialog are all controlled: they take a
          // value and hand back a new one. Hoisting that here is what an author would write, and it
          // is the difference between a generated screen you can run and one you have to finish.
          emitter.stateDeclarations().forEach { appendLine("${INDENT}$it") }
          if (timeText != null) {
            // `AppScaffold` is what owns `TimeText` upstream — `ScreenScaffold` has no `timeText`
            // argument of its own — so a design that declares one generates the pair rather than
            // an argument that does not exist.
            appendLine(
              "${INDENT}AppScaffold(timeText = { TimeText { timeTextCurvedText(${timeText.quoted()}) } }) {"
            )
          } else {
            appendLine("${INDENT}AppScaffold {")
          }
          append("${INDENT}${INDENT}ScreenScaffold(scrollState = listState")
          emitter.rootModifier(rootId)?.let { append(", modifier = $it") }
          // The scroll indicator is transient chrome, and a long screenshot is exactly when it must
          // not be drawn: the platform composites many frames into one tall image, and an indicator
          // painted at a different offset and opacity in every slice lands as a column of dashes
          // down
          // the edge. `LocalScrollCaptureInProgress` is the platform's own signal for that —
          // Android's
          // system long-screenshot sets it, and so does the renderer for a `ScrollMode.LONG`
          // capture
          // —
          // so reading it here is app behaviour that happens to make the parity capture clean,
          // rather
          // than a preview concession baked into a screen.
          appendLine(",")
          appendLine(
            "${INDENT}${INDENT}${INDENT}scrollIndicator = { if (!LocalScrollCaptureInProgress.current) ScrollIndicator(listState) },"
          )
          if (edgeButton != null) {
            appendLine("${INDENT}${INDENT}${INDENT}edgeButton = {")
            edgeButton.forEach { appendLine(it) }
            appendLine("${INDENT}${INDENT}${INDENT}},")
            appendLine("${INDENT}${INDENT}${INDENT}},")
          }
          appendLine("${INDENT}${INDENT}) { contentPadding ->")
          body.forEach { appendLine(it) }
          appendLine("${INDENT}${INDENT}}")
          overlays.forEach { appendLine(it) }
          appendLine("${INDENT}}")
          appendLine("}")
          appendLine()
          // Every round size, because a Wear screen that only ever rendered at one is a screen
          // whose
          // list has not been seen wrap. `WearPreviewDevices` is the shipped provider for exactly
          // this and is what `samples/design-catalog-wear-m3` fans its full-screen stickers out
          // with.
          appendLine("@WearPreviewDevices")
          appendLine("@Composable")
          appendLine("fun ${name}Preview() = $name()")
          appendLine()
          // The second preview is the one that answers "is the canvas telling the truth?".
          //
          // `ScrollMode.LONG` stitches the whole scroll into one tall PNG **with the row
          // transformation off**, which is exactly what the builder's stadium draws — so this
          // render
          // and the design as it appeared on the canvas are the same picture, and a difference
          // between them is a bug in one of the two. The multipreview above cannot carry it: `LONG`
          // on five devices is five stitched captures to answer a question one answers, and the
          // parity claim is about the small round screen a design is authored on.
          appendLine(
            "@Preview(device = ${WEAR_PARITY_DEVICE.quoted()}, showBackground = true, backgroundColor = 0xFF000000)"
          )
          appendLine("@ScrollingPreview(modes = [ScrollMode.LONG])")
          appendLine("@Composable")
          appendLine("fun ${name}LongPreview() = $name()")
        },
    )
  }

  private fun refuse(reason: String) = Result.Refused(listOf(reason))

  /** The scaffold's `timeText`, or null when the design declares none. */
  private fun UiBuilderNode.text(name: String): String? =
    (properties[name] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull?.takeIf {
      it.isNotEmpty()
    }

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

  internal const val INDENT = "    "

  /**
   * The screen the parity capture is taken on: `wearos_small_round`, the smallest and the tightest.
   *
   * A list that fits at 192dp fits everywhere, and 192dp is the frame a `wear-m3` design is created
   * on, so this is the one where the canvas and the render are the same design at the same size.
   */
  internal const val WEAR_PARITY_DEVICE: String = "id:wearos_small_round"
}

/**
 * Writes a screen's designed content as Wear Compose Material 3 source.
 *
 * Split from [WearScreenCodeExporter] for the reason [RemoteContentEmitter] is split from the
 * widget generator: that one owns the screen's *shape* — the scaffold, the state, the preview — and
 * this one owns the vocabulary the body is written in. Refusals collect here rather than throwing,
 * so one pass reports every unmappable node instead of the first.
 */
internal class WearContentEmitter(
  private val document: UiBuilderDocument,
  private val refusals: MutableList<String>,
  /**
   * See [WearScreenCodeExporter.export]; the native preview lane is the only caller that sets it.
   */
  private val tagNodes: Boolean = false,
) {
  private var usesText = false
  private var usesColumn = false
  private var usesRow = false
  private var usesBox = false
  private val usesButtonSymbol = mutableSetOf<String>()
  private var usesCard = false
  private var usesListHeader = false
  private var usesEdgeButton = false
  private var usesArrangement = false
  private var usesDp = false
  private var usesTestTag = false
  private var usesIcon = false
  private var usesRememberState = false
  private var usesTime = false
  private var usesEdgeButtonSize = false
  private val usesPlainCard = mutableSetOf<String>()
  private var usesListSubHeader = false
  private var usesSlider = false
  private var usesStepper = false
  private var usesButtonGroup = false
  private var usesDatePicker = false
  private var usesTimePicker = false
  private val usesIconButton = mutableSetOf<String>()
  private val usesTextButton = mutableSetOf<String>()
  private val usesProgress = mutableSetOf<String>()
  private val usesSelection = mutableSetOf<String>()
  private val usesDialog = mutableSetOf<String>()
  private val iconImports = mutableSetOf<String>()

  /**
   * The `remember`s the screen function declares before anything is drawn.
   *
   * A `Slider` and a `Stepper` are **controlled** components: they take a value and a callback, and
   * a generated screen that passed a literal and an empty lambda would draw a control that cannot
   * move. Hoisting the state is what the author would write by hand, so it is what this writes —
   * collected here because the declarations belong at the top of the function and the branch that
   * needs one is arbitrarily deep inside its body.
   */
  private val rememberedState = mutableListOf<String>()

  /** The hoisted state declarations, in the order their nodes were reached. */
  fun stateDeclarations(): List<String> = rememberedState.toList()

  /**
   * The scaffold's content, as `TransformingLazyColumn` item bodies.
   *
   * A Wear screen's content slot is a list far more often than it is anything else, and the two
   * cases are written differently: a list becomes items in the column the scaffold's
   * `contentPadding` is handed to, and anything else becomes a single `item {}` holding it. The
   * second is not a fallback — a screen that is one centred control is a real screen — but it is
   * the one that loses the scaffold's scroll relationship, so it is written as such rather than
   * pretending the node was a list.
   */
  /**
   * The scaffold's own `modifier`, which exists only to carry its test tag.
   *
   * The root is a node like any other and a native render's overlay has to be able to point at it —
   * without this the one rectangle covering the whole design is the one with no id. Null when
   * nothing is being tagged, so an ordinary export's `ScreenScaffold` call is unchanged.
   */
  fun rootModifier(nodeId: String): String? = modifierChain(nodeId)

  fun emitScaffoldBody(nodeId: String): List<String> {
    val node = document.nodes[nodeId] ?: return refused("the content node `$nodeId` is missing")
    if (node.componentId != WearScreenCodeExporter.TRANSFORMING_LAZY_COLUMN) {
      // Anything that is not the list is written straight into `ScreenScaffold`'s content lambda.
      // A `Stepper` or a picker owns the whole round display and a single centred control is a
      // real screen; neither is a row, and neither has a `TransformingLazyColumnScope` to be an
      // `item` of. This is also the shape a full-screen component needs to compile at all.
      return emit(nodeId, depth = 3)
    }
    val spacing = node.number("verticalSpacingDp")
    val lines = mutableListOf<String>()
    lines += "${indent(3)}TransformingLazyColumn("
    lines += "${indent(4)}state = listState,"
    lines += "${indent(4)}contentPadding = contentPadding,"
    if (spacing != null) {
      usesArrangement = true
      usesDp = true
      lines += "${indent(4)}verticalArrangement = Arrangement.spacedBy(${spacing.dp()}.dp),"
    }
    lines += "${indent(4)}modifier = ${modifierChain(nodeId, "fillMaxSize()")},"
    lines += "${indent(3)}) {"
    node.slots["items"].orEmpty().forEach { itemId ->
      lines += "${indent(4)}item {"
      lines += emit(itemId, depth = 5, transformed = node.transformation())
      lines += "${indent(4)}}"
    }
    lines += "${indent(3)}}"
    return lines
  }

  /**
   * One node as Wear Compose source.
   *
   * [transformed] carries the row treatment down exactly one level, to the composable the
   * `TransformingLazyColumn` item body holds. It is not inherited further: `transformedHeight`
   * measures an item against the list's own scroll, so putting it on a grandchild would ask the
   * layout a question about a node that is not an item.
   */
  fun emit(nodeId: String, depth: Int, transformed: Boolean = false): List<String> {
    val node = document.nodes[nodeId] ?: return refused("node `$nodeId` is missing")
    val pad = indent(depth)
    return when (node.componentId) {
      WearScreenCodeExporter.TEXT -> {
        usesText = true
        val text = node.string("text")
        // `SurfaceTransformation` is a *surface* treatment — upstream applies it to `ListHeader`,
        // `TitleCard`, `Button`, the things that draw a background. A bare `Text` has no surface
        // to transform, so it takes the height treatment alone rather than an argument
        // `androidx.wear.compose.material3.Text` does not have.
        val modifier = modifierChain(nodeId, transformedHeight(transformed))
        if (modifier == null) {
          listOf("${pad}Text(text = ${text.quoted()})")
        } else {
          listOf(
            "${pad}Text(",
            "${pad}${INDENT}text = ${text.quoted()},",
            "${pad}${INDENT}modifier = $modifier,",
            "${pad})",
          )
        }
      }
      // `ListHeader`, not a Text with padding. The canvas draws a 48dp item and so does this, which
      // is the whole reason the component exists: the template used to fake the height with a
      // padded `m3/text`, and the generated screen came out 31.5dp shorter than the design.
      WearScreenCodeExporter.LIST_HEADER -> {
        usesListHeader = true
        usesText = true
        listOf("${pad}ListHeader(") +
          surfaceArguments(pad + INDENT, nodeId, transformed) +
          listOf(
            "${pad}) {",
            "${pad}${INDENT}Text(text = ${node.string("text").quoted()})",
            "${pad}}",
          )
      }
      WearScreenCodeExporter.CARD -> {
        val content = node.slots["content"].orEmpty()
        val variant = node.string("variant")
        // Which card upstream publishes, chosen by the variant rather than by recolouring one —
        // the same rule `m3/card`'s variant follows on the mobile side. `OutlinedCard` and `Card`
        // take a single content lambda instead of `TitleCard`'s title/subtitle pair, so they are
        // written as one block and never reach the pair-recognition below.
        if (variant == "outlined" || variant == "plain") {
          val symbol = if (variant == "outlined") "OutlinedCard" else "Card"
          usesPlainCard += symbol
          return listOf("${pad}$symbol(", "${pad}${INDENT}onClick = {},") +
            surfaceArguments(pad + INDENT, nodeId, transformed) +
            listOf("${pad}) {") +
            content.flatMap { emit(it, depth + 1) } +
            listOf("${pad}}")
        }
        usesCard = true
        // `TitleCard` takes `title` and `subtitle` as separate slots, and a two-line row is what a
        // Wear list is mostly made of. The canvas can only draw those two lines as a Column inside
        // the card's single content slot — `m3/card` has one — so the pair is recognised here and
        // written as the API upstream actually publishes, rather than as a Column nested in a
        // `title` lambda that would compile and read as a mistake.
        val lines = content.singleOrNull()?.let(::twoTextLines)
        val slots =
          if (lines != null) {
            listOf("${pad}${INDENT}title = {") +
              emit(lines.first, depth + 2) +
              listOf("${pad}${INDENT}},", "${pad}${INDENT}subtitle = {") +
              emit(lines.second, depth + 2) +
              listOf("${pad}${INDENT}},")
          } else {
            listOf("${pad}${INDENT}title = {") +
              content.flatMap { emit(it, depth + 2) } +
              listOf("${pad}${INDENT}},")
          }
        listOf("${pad}TitleCard(", "${pad}${INDENT}onClick = {},") +
          slots +
          surfaceArguments(pad + INDENT, nodeId, transformed) +
          listOf("${pad})")
      }
      WearScreenCodeExporter.BUTTON -> {
        // Wear's four, and none of them is a FAB — the mobile `style` this borrowed offered `fab`
        // and `elevated`, which no watch publishes.
        val symbol =
          when (node.string("variant")) {
            "filled-tonal" -> "FilledTonalButton"
            "outlined" -> "OutlinedButton"
            "child" -> "ChildButton"
            else -> "Button"
          }
        usesButtonSymbol += symbol
        val content = node.slots["content"].orEmpty()
        listOf("${pad}$symbol(", "${pad}${INDENT}onClick = {},") +
          surfaceArguments(pad + INDENT, nodeId, transformed) +
          listOf("${pad}) {") +
          content.flatMap { emit(it, depth + 1) } +
          listOf("${pad}}")
      }
      // No transformation on a layout container, even directly inside an item.
      // `SurfaceTransformation` is a surface treatment and a `Column` draws no surface; upstream
      // puts it on the `TitleCard` or the `ListHeader` inside, which is what a nested emit reaches.
      "layout/column" -> {
        usesColumn = true
        container("Column", node, "children", pad, depth)
      }
      "layout/row" -> {
        usesRow = true
        container("Row", node, "children", pad, depth)
      }
      "layout/box" -> {
        usesBox = true
        container("Box", node, "children", pad, depth)
      }
      WearScreenCodeExporter.ICON -> {
        val key = node.string("iconKey")
        val member =
          ScreenDocumentProjection.ICON_MEMBERS[key]
            ?: return refused(
              "`${WearScreenCodeExporter.ICON}` (node `$nodeId`) names the icon key `$key`, " +
                "which is not one of " +
                ScreenDocumentProjection.ICON_MEMBERS.keys.sorted().joinToString(", ")
            )
        // The vector is `androidx.compose.material.icons`, which Wear and mobile Compose share —
        // one of the very few symbols that really is the same on both. `Icon` itself is Wear's.
        usesIcon = true
        // An icon is an **extension property** on `Icons.Filled`, declared in
        // `androidx.compose.material.icons.filled` — the package is the member path lowercased.
        // `androidx.compose.material.icons.Icons.Filled.AccountCircle` written out is not a
        // spelling of anything, which is why the property is imported and the expression stays
        // `Icons.Filled.AccountCircle`. Same derivation as `ScreenDocumentProjection.icon`, off the
        // same table.
        val path = member.split(".")
        iconImports +=
          "androidx.compose.material.icons." +
            path.dropLast(1).joinToString(".") { it.lowercase() } +
            "." +
            path.last()
        val size = node.number("sizeDp")
        if (size != null) usesDp = true
        val modifier =
          modifierChain(
            nodeId,
            transformedHeight(transformed),
            size?.let { "size(${it.dp()}.dp)" },
          )
        listOf("${pad}Icon(") +
          listOf(
            "${pad}${INDENT}imageVector = Icons.$member,",
            // Null, and stated rather than defaulted: the builder has no place to author an icon's
            // description yet, and a made-up one is worse for a screen reader than none.
            "${pad}${INDENT}contentDescription = null,",
          ) +
          (modifier?.let { listOf("${pad}${INDENT}modifier = $it,") } ?: emptyList()) +
          listOf("${pad})")
      }
      WearScreenCodeExporter.ICON_BUTTON -> {
        val symbol = iconButtonSymbol(node.string("variant"))
        usesIconButton += symbol
        listOf("${pad}$symbol(", "${pad}${INDENT}onClick = {},") +
          surfaceArguments(pad + INDENT, nodeId, transformed) +
          listOf("${pad}) {") +
          node.slots["content"].orEmpty().flatMap { emit(it, depth + 1) } +
          listOf("${pad}}")
      }
      WearScreenCodeExporter.TEXT_BUTTON -> {
        val symbol = textButtonSymbol(node.string("variant"))
        usesTextButton += symbol
        listOf("${pad}$symbol(", "${pad}${INDENT}onClick = {},") +
          surfaceArguments(pad + INDENT, nodeId, transformed) +
          listOf("${pad}) {") +
          node.slots["content"].orEmpty().flatMap { emit(it, depth + 1) } +
          listOf("${pad}}")
      }
      WearScreenCodeExporter.LIST_SUB_HEADER -> {
        usesListSubHeader = true
        usesText = true
        listOf("${pad}ListSubHeader(") +
          surfaceArguments(pad + INDENT, nodeId, transformed) +
          listOf(
            "${pad}) {",
            "${pad}${INDENT}Text(text = ${node.string("text").quoted()})",
            "${pad}}",
          )
      }
      WearScreenCodeExporter.CHECKBOX_BUTTON ->
        selectionButton(node, nodeId, pad, depth, transformed, "CheckboxButton", "checked")
      WearScreenCodeExporter.SWITCH_BUTTON ->
        selectionButton(node, nodeId, pad, depth, transformed, "SwitchButton", "checked")
      WearScreenCodeExporter.RADIO_BUTTON ->
        selectionButton(node, nodeId, pad, depth, transformed, "RadioButton", "selected")
      WearScreenCodeExporter.SLIDER -> {
        usesSlider = true
        val segmented = node.string("segmented") == "segmented"
        // A slider is state, and this generator writes a screen rather than a view model. The
        // authored value becomes the `remember` the screen reads and writes, which is what makes
        // the emitted code something you can run rather than something you have to finish.
        val state = rememberedFloat(nodeId, node.number("value") ?: 0f)
        listOf("${pad}Slider(") +
          listOf(
            "${pad}${INDENT}value = $state,",
            "${pad}${INDENT}onValueChange = { $state = it },",
            "${pad}${INDENT}valueRange = ${node.range()},",
            "${pad}${INDENT}steps = ${(node.number("steps") ?: 0f).toInt()},",
            "${pad}${INDENT}segmented = $segmented,",
          ) +
          surfaceArguments(pad + INDENT, nodeId, transformed) +
          listOf("${pad})")
      }
      WearScreenCodeExporter.STEPPER -> {
        usesStepper = true
        val state = rememberedFloat(nodeId, node.number("value") ?: 0f)
        listOf("${pad}Stepper(") +
          listOf(
            "${pad}${INDENT}value = $state,",
            "${pad}${INDENT}onValueChange = { $state = it },",
            "${pad}${INDENT}valueRange = ${node.range()},",
            "${pad}${INDENT}steps = ${(node.number("steps") ?: 0f).toInt()},",
          ) +
          (modifierChain(nodeId)?.let { listOf("${pad}${INDENT}modifier = $it,") } ?: emptyList()) +
          listOf("${pad}) {") +
          node.slots["content"].orEmpty().flatMap { emit(it, depth + 1) } +
          listOf("${pad}}")
      }
      WearScreenCodeExporter.PROGRESS_INDICATOR -> {
        val symbol = progressSymbol(node.string("variant"))
        usesProgress += symbol
        val progress = node.number("progress")
        val segments = node.number("segments")
        listOf("${pad}$symbol(") +
          // `segmentCount` first, because upstream declares it first and it is not optional on the
          // segmented form — a segmented indicator with no count is an indicator with one segment.
          (if (symbol == "SegmentedCircularProgressIndicator")
            listOf("${pad}${INDENT}segmentCount = ${(segments ?: 1f).toInt()},")
          else emptyList()) +
          // A determinate indicator takes `progress` as a lambda; the indeterminate overload takes
          // no progress at all, which is what an absent property means rather than zero.
          (progress?.let { listOf("${pad}${INDENT}progress = { ${it.dp()}f },") } ?: emptyList()) +
          (modifierChain(nodeId, transformedHeight(transformed))?.let {
            listOf("${pad}${INDENT}modifier = $it,")
          } ?: emptyList()) +
          listOf("${pad})")
      }
      WearScreenCodeExporter.BUTTON_GROUP -> {
        usesButtonGroup = true
        listOf("${pad}ButtonGroup(") +
          (modifierChain(nodeId, transformedHeight(transformed))?.let {
            listOf("${pad}${INDENT}modifier = $it,")
          } ?: emptyList()) +
          listOf("${pad}) {") +
          node.slots["children"].orEmpty().flatMap { emit(it, depth + 1) } +
          listOf("${pad}}")
      }
      WearScreenCodeExporter.DATE_PICKER -> {
        usesDatePicker = true
        listOf("${pad}DatePicker(") +
          listOf(
            "${pad}${INDENT}initialDate = ${node.localDate("initialDate")},",
            "${pad}${INDENT}onDatePicked = {},",
          ) +
          (node.string("type").datePickerType()?.let {
            listOf("${pad}${INDENT}datePickerType = DatePickerType.$it,")
          } ?: emptyList()) +
          (modifierChain(nodeId)?.let { listOf("${pad}${INDENT}modifier = $it,") } ?: emptyList()) +
          listOf("${pad})")
      }
      WearScreenCodeExporter.TIME_PICKER -> {
        usesTimePicker = true
        listOf("${pad}TimePicker(") +
          listOf(
            "${pad}${INDENT}initialTime = ${node.localTime("initialTime")},",
            "${pad}${INDENT}onTimePicked = {},",
          ) +
          (node.string("type").timePickerType()?.let {
            listOf("${pad}${INDENT}timePickerType = TimePickerType.$it,")
          } ?: emptyList()) +
          (modifierChain(nodeId)?.let { listOf("${pad}${INDENT}modifier = $it,") } ?: emptyList()) +
          listOf("${pad})")
      }
      in WearScreenCodeExporter.OVERLAYS ->
        refused(
          "`${node.componentId}` (node `$nodeId`) is a dialog, and a dialog is a screen state " +
            "rather than a row of one — Wear draws it over everything when its `visible` flag is " +
            "set. Move it to the screen scaffold's `overlays` slot, which is where this generator " +
            "writes it as a sibling of the `ScreenScaffold`"
        )
      WearScreenCodeExporter.TRANSFORMING_LAZY_COLUMN ->
        refused(
          "`${node.componentId}` (node `$nodeId`) is nested inside another node; a screen has one " +
            "list, directly in the scaffold's content slot — a Wear screen with two scrolling " +
            "columns has no scroll state the scaffold can follow"
        )
      else ->
        refused(
          "`${node.componentId}` (node `$nodeId`) has no Wear Compose Material 3 counterpart this " +
            "generator can write. `wear-m3` publishes Wear's own components under `wear-m3/…` and " +
            "borrows only foundation — `layout/box`, `layout/column`, `layout/row`, " +
            "`asset/image` — from m3-catalog; a Material 3 component is not a Wear one, and there " +
            "is nothing on a watch to write it as"
        )
    }
  }

  /**
   * The scaffold's `edgeButton` slot, as the `EdgeButton` that is the only thing it holds.
   *
   * `ScreenScaffold(edgeButton = …)` reveals its slot from the scroll state and shapes it to the
   * bottom curve, which is `EdgeButton`'s whole reason to exist — a plain `Button` in there is a
   * rectangle pinned to a curve. The canvas draws the borrowed `m3/button`, so this is where the
   * two part company.
   */
  fun emitEdgeButton(nodeId: String, depth: Int): List<String> {
    val node = document.nodes[nodeId] ?: return refused("the edge button node `$nodeId` is missing")
    // `wear-m3/edge-button` is the component for this slot and `wear-m3/button` is still accepted,
    // because designs authored before that id existed put an ordinary button here and the slot
    // always generated an `EdgeButton` from it. Both write the same call; only the former can also
    // choose a size.
    if (
      node.componentId != WearScreenCodeExporter.EDGE_BUTTON &&
        node.componentId != WearScreenCodeExporter.BUTTON
    ) {
      return refused(
        "`${node.componentId}` (node `$nodeId`) is in the scaffold's edgeButton slot; that slot " +
          "generates an `EdgeButton`, which only a button can be"
      )
    }
    usesEdgeButton = true
    val pad = indent(depth)
    val modifier = modifierChain(nodeId)
    val size = node.string("size").edgeButtonSize()?.also { usesEdgeButtonSize = true }
    val arguments =
      listOfNotNull(
        "onClick = {}",
        size?.let { "buttonSize = EdgeButtonSize.$it" },
        modifier?.let { "modifier = $it" },
      )
    val head = "${pad}EdgeButton(${arguments.joinToString(", ")}) {"
    return listOf(head) +
      node.slots["content"].orEmpty().flatMap { emit(it, depth + 1) } +
      listOf("${pad}}")
  }

  /** `EdgeButtonSize`, whose four members are the only sizes upstream publishes for this shape. */
  private fun String.edgeButtonSize(): String? =
    when (this) {
      "extra-small" -> "ExtraSmall"
      "small" -> "Small"
      "medium" -> "Medium"
      "large" -> "Large"
      else -> null
    }

  /** A container holding exactly two texts, which is a `TitleCard`'s title and subtitle. */
  private fun twoTextLines(nodeId: String): Pair<String, String>? {
    val node = document.nodes[nodeId] ?: return null
    if (node.componentId != "layout/column") return null
    val children = node.slots["children"].orEmpty()
    if (children.size != 2) return null
    if (children.any { document.nodes[it]?.componentId != WearScreenCodeExporter.TEXT }) return null
    return children[0] to children[1]
  }

  private fun container(
    symbol: String,
    node: UiBuilderNode,
    slot: String,
    pad: String,
    depth: Int,
  ): List<String> {
    val children = node.slots[slot].orEmpty()
    val modifier = modifierChain(node.id)
    val head = if (modifier == null) "${pad}$symbol {" else "${pad}$symbol(modifier = $modifier) {"
    return listOf(head) + children.flatMap { emit(it, depth + 1) } + listOf("${pad}}")
  }

  /**
   * The `modifier =` chain one node carries, or null when it carries none.
   *
   * Null rather than `Modifier`, so a call with nothing to say keeps the short form it always had:
   * `Text(text = "Runs")` is what an untagged export emits and what the round-trip sample carries,
   * and a bare `modifier = Modifier` on every node would be a diff in every generated file to buy
   * nothing. `testTag` leads the chain because it is identity rather than layout — everything after
   * it changes how the node draws, and reading the id first is how a tagged render is checked
   * against the design.
   */
  private fun modifierChain(nodeId: String, vararg calls: String?): String? {
    val chain = buildList {
      if (tagNodes) {
        usesTestTag = true
        add("testTag(${nodeId.quoted()})")
      }
      calls.filterNotNullTo(this)
    }
    return if (chain.isEmpty()) null else chain.joinToString(".", prefix = "Modifier.")
  }

  /** `transformedHeight`, or nothing, so [modifierChain] can take it positionally. */
  private fun transformedHeight(transformed: Boolean): String? =
    if (transformed) "transformedHeight(this, spec)" else null

  /**
   * What a **surface** node adds to its call: the row treatment, and the tag if one is being
   * written.
   *
   * `transformation = SurfaceTransformation(spec)` is a real argument of `ListHeader`, `TitleCard`
   * and `Button` rather than a modifier, which is why this is a pair of lines and not one chain.
   */
  private fun surfaceArguments(
    pad: String,
    nodeId: String,
    transformed: Boolean,
  ): List<String> =
    (modifierChain(nodeId, transformedHeight(transformed))?.let { listOf("${pad}modifier = $it,") }
      ?: emptyList()) +
      if (transformed) listOf("${pad}transformation = SurfaceTransformation(spec),")
      else emptyList()

  private fun refused(reason: String): List<String> {
    refusals += reason
    return emptyList()
  }

  /**
   * `CheckboxButton`, `SwitchButton` and `RadioButton`, which are one shape with three controls.
   *
   * Upstream declares them as a labelled full-width row — `label`, an optional `secondaryLabel`,
   * and a checked/selected flag with its callback — and that shared shape is exactly why the mobile
   * `Checkbox` could never have stood in for any of them: a 20dp square is not a row. The only
   * thing that differs between the three is the composable's name and the name of its flag, so that
   * is all this takes.
   */
  private fun selectionButton(
    node: UiBuilderNode,
    nodeId: String,
    pad: String,
    depth: Int,
    transformed: Boolean,
    symbol: String,
    flag: String,
  ): List<String> {
    usesSelection += symbol
    usesText = true
    val callback = if (flag == "selected") "onSelect" else "onCheckedChange"
    val state = rememberedBoolean(nodeId, node.boolean(flag) ?: false)
    val secondary = node.string("secondaryLabel")
    return listOf("${pad}$symbol(") +
      listOf(
        "${pad}${INDENT}$flag = $state,",
        // `onSelect` takes no argument — a radio row selects itself — where `onCheckedChange`
        // receives the new value. Writing the wrong one is a compile error rather than a subtle
        // bug, which is the good kind of difference to get right here.
        if (flag == "selected") "${pad}${INDENT}$callback = { $state = true },"
        else "${pad}${INDENT}$callback = { $state = it },",
        "${pad}${INDENT}label = { Text(text = ${node.string("label").quoted()}) },",
      ) +
      (if (secondary.isEmpty()) emptyList()
      else listOf("${pad}${INDENT}secondaryLabel = { Text(text = ${secondary.quoted()}) },")) +
      surfaceArguments(pad + INDENT, nodeId, transformed) +
      listOf("${pad})")
  }

  /**
   * A Wear dialog, emitted beside the `ScreenScaffold` rather than inside it.
   *
   * Wear's dialogs are full-screen and take a `visible` flag: they are a state the screen is in,
   * not a node in its layout, and upstream's own samples put them next to the scaffold in the same
   * `AppScaffold`. That is what the scaffold's `overlays` slot means, and this is the only place
   * this generator writes one.
   */
  fun emitOverlay(nodeId: String, depth: Int): List<String> {
    val node = document.nodes[nodeId] ?: return refused("the overlay node `$nodeId` is missing")
    val pad = indent(depth)
    val visible = rememberedBoolean(nodeId, node.boolean("visible") ?: false)
    val dismiss = "${pad}${INDENT}onDismissRequest = { $visible = false },"
    return when (node.componentId) {
      WearScreenCodeExporter.ALERT_DIALOG -> {
        usesDialog += "AlertDialog"
        usesText = true
        val text = node.string("text")
        val confirm = node.slots["confirmButton"].orEmpty().singleOrNull()
        val dismissButton = node.slots["dismissButton"].orEmpty().singleOrNull()
        val extra = node.slots["content"].orEmpty()
        listOf("${pad}AlertDialog(") +
          listOf(
            "${pad}${INDENT}visible = $visible,",
            dismiss,
            "${pad}${INDENT}title = { Text(text = ${node.string("title").quoted()}) },",
          ) +
          (if (text.isEmpty()) emptyList()
          else listOf("${pad}${INDENT}text = { Text(text = ${text.quoted()}) },")) +
          (confirm?.let {
            listOf("${pad}${INDENT}confirmButton = {") +
              emit(it, depth + 2) +
              listOf("${pad}${INDENT}},")
          } ?: emptyList()) +
          (dismissButton?.let {
            listOf("${pad}${INDENT}dismissButton = {") +
              emit(it, depth + 2) +
              listOf("${pad}${INDENT}},")
          } ?: emptyList()) +
          (modifierChain(nodeId)?.let { listOf("${pad}${INDENT}modifier = $it,") } ?: emptyList()) +
          listOf("${pad}) {") +
          extra.flatMap { emit(it, depth + 1) } +
          listOf("${pad}}")
      }
      WearScreenCodeExporter.CONFIRMATION_DIALOG -> {
        val symbol =
          when (node.string("variant")) {
            "success" -> "SuccessConfirmationDialog"
            "failure" -> "FailureConfirmationDialog"
            else -> "ConfirmationDialog"
          }
        usesDialog += symbol
        usesText = true
        listOf("${pad}$symbol(") +
          listOf(
            "${pad}${INDENT}visible = $visible,",
            dismiss,
            "${pad}${INDENT}curvedText = { confirmationDialogCurvedText(${node.string("text").quoted()}) },",
          ) +
          (modifierChain(nodeId)?.let { listOf("${pad}${INDENT}modifier = $it,") } ?: emptyList()) +
          // The generic form takes its own content lambda; the two named ones bring their icon.
          (if (symbol == "ConfirmationDialog") listOf("${pad}) {}") else listOf("${pad})"))
      }
      WearScreenCodeExporter.OPEN_ON_PHONE_DIALOG -> {
        usesDialog += "OpenOnPhoneDialog"
        val text = node.string("text")
        listOf("${pad}OpenOnPhoneDialog(") +
          listOf("${pad}${INDENT}visible = $visible,", dismiss) +
          (if (text.isEmpty()) emptyList()
          else
            listOf(
              "${pad}${INDENT}curvedText = { openOnPhoneDialogCurvedText(${text.quoted()}) },"
            )) +
          (modifierChain(nodeId)?.let { listOf("${pad}${INDENT}modifier = $it,") } ?: emptyList()) +
          listOf("${pad})")
      }
      else ->
        refused(
          "`${node.componentId}` (node `$nodeId`) is in the screen scaffold's `overlays` slot, " +
            "which holds Wear's full-screen dialogs — an alert, a confirmation or an open-on-phone"
        )
    }
  }

  /** `FilledIconButton` and friends: the variant names the composable, it does not tint one. */
  private fun iconButtonSymbol(variant: String) =
    when (variant) {
      "filled" -> "FilledIconButton"
      "filled-tonal" -> "FilledTonalIconButton"
      "filled-variant" -> "FilledVariantIconButton"
      "outlined" -> "OutlinedIconButton"
      else -> "IconButton"
    }

  private fun textButtonSymbol(variant: String) =
    when (variant) {
      "filled" -> "FilledTextButton"
      "filled-tonal" -> "FilledTonalTextButton"
      "filled-variant" -> "FilledVariantTextButton"
      "outlined" -> "OutlinedTextButton"
      else -> "TextButton"
    }

  private fun progressSymbol(variant: String) =
    when (variant) {
      "linear" -> "LinearProgressIndicator"
      "arc" -> "ArcProgressIndicator"
      "segmented-circular" -> "SegmentedCircularProgressIndicator"
      else -> "CircularProgressIndicator"
    }

  /**
   * One hoisted `var` for a control's value, named after the node it belongs to.
   *
   * The node id is the name because it is the one string already unique across the document, and
   * because a reader comparing the generated screen with the design can find the control it came
   * from. Non-identifier characters are folded to `_`, which is what makes a node id like
   * `slider-1` legal Kotlin.
   */
  private fun rememberedFloat(nodeId: String, initial: Float): String {
    val name = nodeId.stateIdentifier()
    usesRememberState = true
    rememberedState += "var $name by remember { mutableFloatStateOf(${initial.dp()}f) }"
    return name
  }

  private fun rememberedBoolean(nodeId: String, initial: Boolean): String {
    val name = nodeId.stateIdentifier()
    usesRememberState = true
    rememberedState += "var $name by remember { mutableStateOf($initial) }"
    return name
  }

  /** The imports the emitted source needs, in the order ktfmt sorts them. */
  fun imports(timeText: Boolean): List<String> = buildList {
    add("androidx.compose.foundation.layout.fillMaxSize")
    if (usesArrangement) add("androidx.compose.foundation.layout.Arrangement")
    if (usesBox) add("androidx.compose.foundation.layout.Box")
    if (usesColumn) add("androidx.compose.foundation.layout.Column")
    if (usesRow) add("androidx.compose.foundation.layout.Row")
    add("androidx.compose.runtime.Composable")
    add("androidx.compose.ui.Modifier")
    if (usesTestTag) add("androidx.compose.ui.platform.testTag")
    if (usesDp) add("androidx.compose.ui.unit.dp")
    if (usesIcon) add("androidx.compose.foundation.layout.size")
    if (usesIcon) add("androidx.compose.material.icons.Icons")
    addAll(iconImports)
    if (usesRememberState) {
      add("androidx.compose.runtime.getValue")
      add("androidx.compose.runtime.mutableFloatStateOf")
      add("androidx.compose.runtime.mutableStateOf")
      add("androidx.compose.runtime.remember")
      add("androidx.compose.runtime.setValue")
    }
    if (usesTime) add("java.time.LocalDate")
    if (usesTime) add("java.time.LocalTime")
    if (usesIcon) add("androidx.wear.compose.material3.Icon")
    usesIconButton.forEach { add("androidx.wear.compose.material3.$it") }
    usesTextButton.forEach { add("androidx.wear.compose.material3.$it") }
    usesSelection.forEach { add("androidx.wear.compose.material3.$it") }
    usesProgress.forEach { add("androidx.wear.compose.material3.$it") }
    usesDialog.forEach { add("androidx.wear.compose.material3.$it") }
    if (usesDialog.any { it.endsWith("ConfirmationDialog") })
      add("androidx.wear.compose.material3.confirmationDialogCurvedText")
    if ("OpenOnPhoneDialog" in usesDialog)
      add("androidx.wear.compose.material3.openOnPhoneDialogCurvedText")
    if (usesListSubHeader) add("androidx.wear.compose.material3.ListSubHeader")
    if (usesSlider) add("androidx.wear.compose.material3.Slider")
    if (usesStepper) add("androidx.wear.compose.material3.Stepper")
    if (usesButtonGroup) add("androidx.wear.compose.material3.ButtonGroup")
    if (usesDatePicker) {
      add("androidx.wear.compose.material3.DatePicker")
      add("androidx.wear.compose.material3.DatePickerType")
    }
    if (usesTimePicker) {
      add("androidx.wear.compose.material3.TimePicker")
      add("androidx.wear.compose.material3.TimePickerType")
    }
    add("androidx.wear.compose.foundation.lazy.TransformingLazyColumn")
    add("androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState")
    // Unconditional, because the scaffold pair is. `AppScaffold` is written whether or not the
    // design declares a `timeText` — it is what owns the status strip's *place* — so importing it
    // only in the `timeText` case emitted a file that did not compile for every design without one.
    add("androidx.wear.compose.material3.AppScaffold")
    usesButtonSymbol.forEach { add("androidx.wear.compose.material3.$it") }
    add("androidx.compose.ui.platform.LocalScrollCaptureInProgress")
    add("androidx.wear.compose.material3.ScreenScaffold")
    add("androidx.wear.compose.material3.ScrollIndicator")
    add("androidx.wear.compose.material3.SurfaceTransformation")
    if (usesText) add("androidx.wear.compose.material3.Text")
    if (timeText) add("androidx.wear.compose.material3.TimeText")
    if (timeText) add("androidx.wear.compose.material3.timeTextCurvedText")
    if (usesListHeader) add("androidx.wear.compose.material3.ListHeader")
    if (usesCard) add("androidx.wear.compose.material3.TitleCard")
    usesPlainCard.forEach { add("androidx.wear.compose.material3.$it") }
    add("androidx.wear.compose.material3.lazy.rememberTransformationSpec")
    add("androidx.wear.compose.material3.lazy.transformedHeight")
    // `androidx.wear.compose:compose-ui-tooling`, not `androidx.wear:wear-tooling-preview`. The
    // latter is the device-id constants (`WearDevices`); the multipreview lives with Wear Compose,
    // and a project that renders Wear previews already has it.
    add("androidx.wear.compose.ui.tooling.preview.WearPreviewDevices")
    // The parity capture's own three. `Preview` is the platform annotation the device spec rides
    // on, and the scrolling pair is compose-ai-tools' `preview-annotations`, already on the
    // classpath of anything the compose-preview plugin renders.
    add("androidx.compose.ui.tooling.preview.Preview")
    add("ee.schimke.composeai.preview.ScrollMode")
    add("ee.schimke.composeai.preview.ScrollingPreview")
    if (usesEdgeButton) add("androidx.wear.compose.material3.EdgeButton")
    if (usesEdgeButtonSize) add("androidx.wear.compose.material3.EdgeButtonSize")
  }
    .distinct()
    .sorted()

  private fun indent(depth: Int) = INDENT.repeat(depth)

  // Properties arrive as the wire's typed values — `{"type": "string", "value": "…"}` — so a read
  // that took `properties[name]` straight would see the wrapper object and never the value.
  private fun UiBuilderNode.string(name: String): String =
    (properties[name] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty()

  private fun UiBuilderNode.number(name: String): Float? =
    (properties[name] as? JsonObject)?.get("value")?.jsonPrimitive?.floatOrNull

  private fun UiBuilderNode.boolean(name: String): Boolean? =
    (properties[name] as? JsonObject)?.get("value")?.jsonPrimitive?.booleanOrNull

  /** `valueRange`, from the two ends the design declares or from upstream's own 0..1. */
  private fun UiBuilderNode.range(): String =
    "${(number("valueFrom") ?: 0f).dp()}f..${(number("valueTo") ?: 1f).dp()}f"

  /**
   * `LocalDate.parse("…")`, or today, which is what upstream's own sample opens on.
   *
   * Parsed in the generated code rather than here: this emitter has no `LocalDate` to validate
   * against on every platform it compiles for, and a date the author typed wrongly should fail
   * where they can see it — in their own project, on the line that holds the string — rather than
   * be silently replaced by one they did not choose.
   */
  private fun UiBuilderNode.localDate(name: String): String {
    val value = string(name)
    usesTime = true
    return if (value.isEmpty()) "LocalDate.now()" else "LocalDate.parse(${value.quoted()})"
  }

  private fun UiBuilderNode.localTime(name: String): String {
    val value = string(name)
    usesTime = true
    return if (value.isEmpty()) "LocalTime.now()" else "LocalTime.parse(${value.quoted()})"
  }

  private fun String.datePickerType(): String? =
    when (this) {
      "day-month-year" -> "DayMonthYear"
      "month-day-year" -> "MonthDayYear"
      "year-month-day" -> "YearMonthDay"
      else -> null
    }

  private fun String.timePickerType(): String? =
    when (this) {
      "hours-minutes-am-pm" -> "HoursMinutesAmPm12H"
      "hours-minutes-24h" -> "HoursMinutes24H"
      "hours-minutes-seconds" -> "HoursMinutesSeconds24H"
      else -> null
    }

  /** `transformation = "none"` opts a list out of the row treatment; anything else keeps it. */
  private fun UiBuilderNode.transformation(): Boolean = string("transformation") != "none"

  private companion object {
    const val INDENT = WearScreenCodeExporter.INDENT
  }
}

/**
 * A node id as a Kotlin identifier, for the `var` a control's hoisted state is held in.
 *
 * `slider-1` becomes `slider_1`; a leading digit gains a prefix. The id is used rather than a
 * counter because it is already unique across the document and because it is how a reader gets from
 * a line of generated Kotlin back to the node in the design that produced it.
 */
private fun String.stateIdentifier(): String {
  val folded = map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
  return if (folded.isEmpty() || folded.first().isDigit()) "state_$folded" else folded
}

/** `8.0` reads as `8` in a `.dp` literal. */
private fun Float.dp(): String = toString().removeSuffix(".0")

private fun String.quoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** The design's title as a composable name: "Activity list" becomes `ActivityListScreen`. */
private fun UiBuilderDocument.screenIdentifier(): String {
  val words =
    title
      .substringBefore('·')
      .split(Regex("[^A-Za-z0-9]+"))
      .filter { it.isNotEmpty() }
      .map { word -> word.replaceFirstChar(Char::uppercaseChar) }
  val joined = words.joinToString("").ifEmpty { "Generated" }
  val identifier = if (joined.first().isDigit()) "Screen$joined" else joined
  return if (identifier.endsWith("Screen")) identifier else "${identifier}Screen"
}
