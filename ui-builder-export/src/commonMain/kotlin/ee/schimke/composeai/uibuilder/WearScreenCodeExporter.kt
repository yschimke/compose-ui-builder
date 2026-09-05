package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonObject
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
    data class Emitted(val source: String) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /**
   * @param packageName the package the emitted file declares, or null for the pane's snippet. See
   *   [WearWidgetCodeExporter.export]; the two lanes differ by exactly this line.
   */
  fun export(document: UiBuilderDocument, packageName: String? = null): Result {
    val rootId = document.roots.singleOrNull() ?: return refuse("a screen design has one root")
    val root = document.nodes[rootId] ?: return refuse("the root node `$rootId` is missing")
    if (root.componentId != SCAFFOLD) {
      return refuse(
        "the root is `${root.componentId}`, not `$SCAFFOLD` — this generator writes Wear screens, " +
          "and a widget belongs to WearWidgetCodeExporter"
      )
    }

    val refusals = mutableListOf<String>()
    val emitter = WearContentEmitter(document, refusals)
    val contentIds = root.slots["content"].orEmpty()
    val body =
      when (contentIds.size) {
        0 -> listOf("${INDENT}${INDENT}${INDENT}item {}")
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
    if (refusals.isNotEmpty()) return Result.Refused(refusals.distinct())

    val name = document.screenIdentifier()
    val timeText = root.text("timeText")
    return Result.Emitted(
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
        // The scroll indicator is transient chrome, and a long screenshot is exactly when it must
        // not be drawn: the platform composites many frames into one tall image, and an indicator
        // painted at a different offset and opacity in every slice lands as a column of dashes down
        // the edge. `LocalScrollCaptureInProgress` is the platform's own signal for that —
        // Android's
        // system long-screenshot sets it, and so does the renderer for a `ScrollMode.LONG` capture
        // —
        // so reading it here is app behaviour that happens to make the parity capture clean, rather
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
        appendLine("${INDENT}}")
        appendLine("}")
        appendLine()
        // Every round size, because a Wear screen that only ever rendered at one is a screen whose
        // list has not been seen wrap. `WearPreviewDevices` is the shipped provider for exactly
        // this and is what `samples/design-catalog-wear-m3` fans its full-screen stickers out with.
        appendLine("@WearPreviewDevices")
        appendLine("@Composable")
        appendLine("fun ${name}Preview() = $name()")
        appendLine()
        // The second preview is the one that answers "is the canvas telling the truth?".
        //
        // `ScrollMode.LONG` stitches the whole scroll into one tall PNG **with the row
        // transformation off**, which is exactly what the builder's stadium draws — so this render
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
      }
    )
  }

  private fun refuse(reason: String) = Result.Refused(listOf(reason))

  /** The scaffold's `timeText`, or null when the design declares none. */
  private fun UiBuilderNode.text(name: String): String? =
    (properties[name] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull?.takeIf {
      it.isNotEmpty()
    }

  internal const val SCAFFOLD = "wear-m3/screen-scaffold"

  internal const val TRANSFORMING_LAZY_COLUMN = "wear-m3/transforming-lazy-column"

  internal const val LIST_HEADER = "wear-m3/list-header"

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
  internal const val TEXT = "wear-m3/text"

  internal const val CARD = "wear-m3/card"

  internal const val BUTTON = "wear-m3/button"

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
) {
  private var usesText = false
  private var usesColumn = false
  private var usesRow = false
  private var usesBox = false
  private var usesButton = false
  private var usesCard = false
  private var usesListHeader = false
  private var usesEdgeButton = false
  private var usesArrangement = false
  private var usesDp = false

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
  fun emitScaffoldBody(nodeId: String): List<String> {
    val node = document.nodes[nodeId] ?: return refused("the content node `$nodeId` is missing")
    if (node.componentId != WearScreenCodeExporter.TRANSFORMING_LAZY_COLUMN) {
      return listOf("${indent(3)}item {") + emit(nodeId, depth = 4) + "${indent(3)}}"
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
    lines += "${indent(4)}modifier = Modifier.fillMaxSize(),"
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
        if (transformed) {
          // `SurfaceTransformation` is a *surface* treatment — upstream applies it to `ListHeader`,
          // `TitleCard`, `Button`, the things that draw a background. A bare `Text` has no surface
          // to transform, so it takes the height treatment alone rather than an argument
          // `androidx.wear.compose.material3.Text` does not have.
          listOf(
            "${pad}Text(",
            "${pad}${INDENT}text = ${text.quoted()},",
            "${pad}${INDENT}modifier = Modifier.transformedHeight(this, spec),",
            "${pad})",
          )
        } else {
          listOf("${pad}Text(text = ${text.quoted()})")
        }
      }
      // `ListHeader`, not a Text with padding. The canvas draws a 48dp item and so does this, which
      // is the whole reason the component exists: the template used to fake the height with a
      // padded `m3/text`, and the generated screen came out 31.5dp shorter than the design.
      WearScreenCodeExporter.LIST_HEADER -> {
        usesListHeader = true
        usesText = true
        listOf("${pad}ListHeader(") +
          (if (transformed) transformationLines(pad + INDENT) else emptyList()) +
          listOf(
            "${pad}) {",
            "${pad}${INDENT}Text(text = ${node.string("text").quoted()})",
            "${pad}}",
          )
      }
      WearScreenCodeExporter.CARD -> {
        usesCard = true
        val content = node.slots["content"].orEmpty()
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
          (if (transformed) transformationLines(pad + INDENT) else emptyList()) +
          listOf("${pad})")
      }
      WearScreenCodeExporter.BUTTON -> {
        usesButton = true
        val content = node.slots["content"].orEmpty()
        listOf("${pad}Button(", "${pad}${INDENT}onClick = {},") +
          (if (transformed) transformationLines(pad + INDENT) else emptyList()) +
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
      WearScreenCodeExporter.TRANSFORMING_LAZY_COLUMN ->
        refused(
          "`${node.componentId}` (node `$nodeId`) is nested inside another node; a screen has one " +
            "list, directly in the scaffold's content slot — a Wear screen with two scrolling " +
            "columns has no scroll state the scaffold can follow"
        )
      else ->
        refused(
          "`${node.componentId}` (node `$nodeId`) has no Wear Compose Material 3 counterpart this " +
            "generator can write; `wear-m3` borrows only foundation components from m3-catalog, " +
            "and a Material 3 component is not a Wear one"
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
    if (node.componentId != WearScreenCodeExporter.BUTTON) {
      return refused(
        "`${node.componentId}` (node `$nodeId`) is in the scaffold's edgeButton slot; that slot " +
          "generates an `EdgeButton`, which only a button can be"
      )
    }
    usesEdgeButton = true
    val pad = indent(depth)
    return listOf("${pad}EdgeButton(onClick = {}) {") +
      node.slots["content"].orEmpty().flatMap { emit(it, depth + 1) } +
      listOf("${pad}}")
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
    return listOf("${pad}$symbol {") + children.flatMap { emit(it, depth + 1) } + listOf("${pad}}")
  }

  private fun transformationLines(pad: String): List<String> =
    listOf(
      "${pad}modifier = Modifier.transformedHeight(this, spec),",
      "${pad}transformation = SurfaceTransformation(spec),",
    )

  private fun refused(reason: String): List<String> {
    refusals += reason
    return emptyList()
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
    if (usesDp) add("androidx.compose.ui.unit.dp")
    add("androidx.wear.compose.foundation.lazy.TransformingLazyColumn")
    add("androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState")
    if (timeText) add("androidx.wear.compose.material3.AppScaffold")
    if (usesButton) add("androidx.wear.compose.material3.Button")
    add("androidx.compose.ui.platform.LocalScrollCaptureInProgress")
    add("androidx.wear.compose.material3.ScreenScaffold")
    add("androidx.wear.compose.material3.ScrollIndicator")
    add("androidx.wear.compose.material3.SurfaceTransformation")
    if (usesText) add("androidx.wear.compose.material3.Text")
    if (timeText) add("androidx.wear.compose.material3.TimeText")
    if (timeText) add("androidx.wear.compose.material3.timeTextCurvedText")
    if (usesListHeader) add("androidx.wear.compose.material3.ListHeader")
    if (usesCard) add("androidx.wear.compose.material3.TitleCard")
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

  /** `transformation = "none"` opts a list out of the row treatment; anything else keeps it. */
  private fun UiBuilderNode.transformation(): Boolean = string("transformation") != "none"

  private companion object {
    const val INDENT = WearScreenCodeExporter.INDENT
  }
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
