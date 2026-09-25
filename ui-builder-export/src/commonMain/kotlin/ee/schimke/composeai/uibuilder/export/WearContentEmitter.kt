package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.TargetParameter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

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
  /** See [WearScreenCodeExporter.export]. */
  private val packComponents: Map<String, ComponentRecord> = emptyMap(),
) {
  private var usesText = false
  private var usesTextOverflow = false
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
  private var usesImage = false
  private var usesContentScale = false
  private var usesAlignment = false
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

  /** `androidx.compose.foundation.layout` modifiers an authored chain used, by name. */
  private val layoutImports = mutableSetOf<String>()

  /** `androidx.compose.ui.draw` modifiers an authored chain used, by name. */
  private val drawImports = mutableSetOf<String>()

  private var usesZIndex = false

  private var usesAlertDialogDefaults = false

  /**
   * Wear `…Defaults` objects a row's minimum list padding or a button's colours named, by simple
   * name.
   */
  private val listPaddingDefaults = mutableSetOf<String>()

  /** Text arguments' own types, by simple name, with their packages in [imports]. */
  private val textImports = mutableSetOf<String>()

  private var usesMaterialTheme = false

  /** The imports the pack components this screen holds resolve through; see [emitPack]. */
  private val packImports = mutableSetOf<String>()

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
  fun rootModifier(nodeId: String): String? = modifierChain(nodeId, authored = false)

  fun emitScaffoldBody(nodeId: String): List<String> {
    val node = document.nodes[nodeId] ?: return refused("the content node `$nodeId` is missing")
    if (node.componentId != WearScreenCodeExporter.TRANSFORMING_LAZY_COLUMN) {
      // Anything that is not the list is written straight into `ScreenScaffold`'s content lambda.
      // A `Stepper` or a picker owns the whole round display and a single centred control is a
      // real screen; neither is a row, and neither has a `TransformingLazyColumnScope` to be an
      // `item` of. This is also the shape a full-screen component needs to compile at all.
      return emit(nodeId, depth = 2)
    }
    val spacing = node.number("verticalSpacingDp")
    val lines = mutableListOf<String>()
    lines += "${indent(2)}TransformingLazyColumn("
    lines += "${indent(3)}state = listState,"
    lines += "${indent(3)}contentPadding = contentPadding,"
    if (spacing != null) {
      usesArrangement = true
      usesDp = true
      lines += "${indent(3)}verticalArrangement = Arrangement.spacedBy(${spacing.dp()}.dp),"
    }
    // The list fills the scaffold by construction, so an authored `fillMaxSize` on it is already
    // said, and the list takes no other modifier a design could want.
    lines += "${indent(3)}modifier = ${modifierChain(nodeId, "fillMaxSize()", authored = false)},"
    lines += "${indent(2)}) {"
    node.slots["items"].orEmpty().forEach { itemId ->
      // `stableKey` is the item's identity, and this is where identity is spelled: a lazy list's
      // `key`. It is read here rather than by the canvas, which draws the extent as a Column and
      // has
      // no item identity to keep — the same division the mobile lane already makes, where the
      // exporter turns the same property into the same argument.
      val key =
        document.nodes[itemId]
          ?.stringOrNull("stableKey")
          // Blank is absent, which is what the mobile exporter does with the same property: a key
          // is an identity, and two items sharing `key = ""` are one item as far as a lazy layout
          // is concerned — the generated screen fails when it runs, not when it compiles.
          ?.takeIf { it.isNotBlank() }
      lines +=
        if (key == null) "${indent(3)}item {" else "${indent(3)}item(key = ${key.quoted()}) {"
      lines += emit(itemId, depth = 4, transformed = node.transformation())
      lines += "${indent(3)}}"
    }
    lines += "${indent(2)}}"
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
        // `SurfaceTransformation` is a *surface* treatment — upstream applies it to `ListHeader`,
        // `TitleCard`, `Button`, the things that draw a background. A bare `Text` has no surface
        // to transform, so it takes the height treatment and `TextDefaults`' list padding rather
        // than an argument `androidx.wear.compose.material3.Text` does not have.
        val modifier =
          modifierChain(
            nodeId,
            textPropertyModifier(node),
            transformedHeight(transformed),
            minimumVerticalListContentPadding(transformed, "Text"),
          )
        val arguments =
          listOf("text = ${node.string("text").quoted()}") +
            listOfNotNull(modifier?.let { "modifier = $it" }) +
            textArguments(node)
        if (arguments.size == 1) listOf("${pad}Text(${arguments.single()})")
        else listOf("${pad}Text(") + arguments.map { "${pad}${INDENT}$it," } + listOf("${pad})")
      }
      // `ListHeader`, not a Text with padding. The canvas draws a 48dp item and so does this, which
      // is the whole reason the component exists: the template used to fake the height with a
      // padded `m3/text`, and the generated screen came out 31.5dp shorter than the design.
      WearScreenCodeExporter.LIST_HEADER -> {
        usesListHeader = true
        usesText = true
        // `maxLines` and `overflow` are the label's, and the label is this `Text`: upstream's
        // `ListHeader` takes a content lambda rather than a string, so they belong here rather than
        // on the header. Both were declared and read by nobody — a design that truncated its header
        // to one line got as many as the string wrapped to.
        val label = node.labelArguments()
        listOf("${pad}ListHeader(") +
          surfaceArguments(pad + INDENT, nodeId, transformed, "ListHeader") +
          listOf("${pad}) {") +
          (if (label.isEmpty()) {
            listOf("${pad}${INDENT}Text(text = ${node.string("text").quoted()})")
          } else {
            listOf("${pad}${INDENT}Text(") +
              listOf("${pad}${INDENT}${INDENT}text = ${node.string("text").quoted()},") +
              label.map { "${pad}${INDENT}${INDENT}$it," } +
              listOf("${pad}${INDENT})")
          }) +
          listOf("${pad}}")
      }
      WearScreenCodeExporter.CARD -> {
        val content = node.slots["content"].orEmpty()
        val variant = node.string("variant")
        // Which card upstream publishes, chosen by the variant rather than by recolouring one —
        // the same rule `m3/card`'s variant follows on the mobile side. `OutlinedCard` and `Card`
        // take a single content lambda instead of `TitleCard`'s title and content, so they are
        // written as one block and never reach the line recognition below.
        if (variant == "outlined" || variant == "plain") {
          val symbol = if (variant == "outlined") "OutlinedCard" else "Card"
          usesPlainCard += symbol
          return listOf("${pad}$symbol(", "${pad}${INDENT}onClick = {},") +
            surfaceArguments(pad + INDENT, nodeId, transformed, symbol) +
            listOf("${pad}) {") +
            content.flatMap { emit(it, depth + 1) } +
            listOf("${pad}}")
        }
        // The canvas can only draw a card's lines as a Column in its single content slot, so the
        // lines are recognised here and written as the slots upstream publishes. `AppCard` reads
        // them as app name, title and body; `TitleCard` as title and body. The body is the card's
        // **content** — ComposeStarter's `TitleCard(title = { … }) { Text(body) }` — rather than
        // its `subtitle`, which this used to guess and which draws in the subtitle's tertiary
        // colour: a column of texts cannot say which one the author meant, so the common case
        // wins.
        val lines = content.singleOrNull()?.let(::textLines)
        if (variant == "app") {
          if (lines == null || lines.size < 2) {
            return refused(
              "`${node.componentId}` (node `$nodeId`) is an `app` card, and `AppCard` needs an " +
                "app name and a title: give it a column of at least two texts, the app name first"
            )
          }
          usesPlainCard += "AppCard"
          return listOf("${pad}AppCard(", "${pad}${INDENT}onClick = {},") +
            listOf("${pad}${INDENT}appName = {") +
            emit(lines[0], depth + 2) +
            listOf("${pad}${INDENT}},", "${pad}${INDENT}title = {") +
            emit(lines[1], depth + 2) +
            listOf("${pad}${INDENT}},") +
            surfaceArguments(pad + INDENT, nodeId, transformed, "AppCard") +
            trailingContent(pad, depth, lines.drop(2), required = true)
        }
        usesCard = true
        val (title, body) =
          if (lines != null) listOf(lines.first()) to lines.drop(1) else content to emptyList()
        listOf("${pad}TitleCard(", "${pad}${INDENT}onClick = {},") +
          listOf("${pad}${INDENT}title = {") +
          title.flatMap { emit(it, depth + 2) } +
          listOf("${pad}${INDENT}},") +
          surfaceArguments(pad + INDENT, nodeId, transformed, "TitleCard") +
          trailingContent(pad, depth, body)
      }
      WearScreenCodeExporter.BUTTON -> {
        // Wear's four, and none of them is a FAB — a watch publishes no floating action button.
        val symbol =
          when (node.string("variant")) {
            "filled-tonal" -> "FilledTonalButton"
            "outlined" -> "OutlinedButton"
            "child" -> "ChildButton"
            else -> "Button"
          }
        usesButtonSymbol += symbol
        val content = node.slots["content"].orEmpty()
        // `enabled` is the design's, and the canvas has always honoured it: the generated button
        // did not, so a design that disabled one published a picture of a disabled button and
        // source for a working one. Written only when it is false, so a default call keeps the
        // short form.
        val enabled =
          if (node.boolean("enabled") != false) emptyList()
          else listOf("${pad}${INDENT}enabled = false,")
        val colors = colorsArgument(node, nodeId, pad + INDENT, symbol)
        val slots = buttonSlots(content)
        if (slots == null) {
          // Nothing the slots can say: the content overload, which is still Wear's `Button`.
          return listOf("${pad}$symbol(", "${pad}${INDENT}onClick = {},") +
            enabled +
            colors +
            surfaceArguments(pad + INDENT, nodeId, transformed, symbol) +
            listOf("${pad}) {") +
            content.flatMap { emit(it, depth + 1) } +
            listOf("${pad}}")
        }
        // Wear Material 3's button is built around its slots — `icon`, `label`, `secondaryLabel` —
        // each with its own typography, colour role and spacing, and it is what both upstream
        // samples call. The content overload put the icon flush against the text and drew a
        // title-over-date row as two identical centred lines.
        listOf("${pad}$symbol(", "${pad}${INDENT}onClick = {},") +
          enabled +
          colors +
          (slots.icon?.let {
            listOf("${pad}${INDENT}icon = {") + emit(it, depth + 2) + listOf("${pad}${INDENT}},")
          } ?: emptyList()) +
          (slots.secondaryLabel?.let {
            listOf("${pad}${INDENT}secondaryLabel = {") +
              emit(it, depth + 2) +
              listOf("${pad}${INDENT}},")
          } ?: emptyList()) +
          surfaceArguments(pad + INDENT, nodeId, transformed, symbol) +
          listOf("${pad}) {") +
          emit(slots.label, depth + 1) +
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
      // The fourth borrowed foundation component, and the only one of the four that is not a
      // container. It refused here until now, for no reason but that nobody had written the
      // branch: the `else` below has always named `asset/image` among what `wear-m3` borrows,
      // while this `when` had nothing to write it with, so the generator's own refusal contradicted
      // its own list.
      //
      // What it writes is what the m3 lane writes, and what the Wear catalog already tells an
      // author it writes: `Image(painter = ColorPainter(…))`. The picture cannot travel in
      // generated source — it is bytes in the design's asset store, and the resource symbol that
      // would name them (`R.drawable.…` on one host, `Res.drawable.…` on another) is declared by
      // the project receiving this file, not by anything here. So the frame is drawn in the
      // theme's own ground and the line to replace is called out in a comment above it, which is
      // this lane's version of `ScreenDocumentProjection`'s `ASSET_PLACEHOLDER` warning: `Result`
      // here carries a source and a name and has nowhere else to put it.
      "asset/image" -> {
        val key = node.string("assetKey")
        if (key.isEmpty()) {
          return refused(
            "`asset/image` (node `$nodeId`) names no `assetKey`; the property is required, and " +
              "without it there is no picture to stand in for"
          )
        }
        val scaleValue = node.stringOrNull("contentScale")
        val scale = scaleValue?.let {
          ScreenDocumentProjection.CONTENT_SCALE_MEMBERS[it]
            ?: return refused(
              "`asset/image` (node `$nodeId`) names the content scale `$it`, which is not one " +
                "of " +
                ScreenDocumentProjection.CONTENT_SCALE_MEMBERS.keys.sorted().joinToString(", ")
            )
        }
        val alignmentValue = node.stringOrNull("alignment")
        val alignment = alignmentValue?.let {
          ScreenDocumentProjection.ALIGNMENT_MEMBERS[it]
            ?: return refused(
              "`asset/image` (node `$nodeId`) names the alignment `$it`, which is not one of " +
                ScreenDocumentProjection.ALIGNMENT_MEMBERS.keys.sorted().joinToString(", ")
            )
        }
        usesImage = true
        if (scale != null) usesContentScale = true
        if (alignment != null) usesAlignment = true
        val modifier = modifierChain(nodeId, transformedHeight(transformed))
        listOf(
          "${pad}// The asset `$key` is bytes in the design, not a resource this file can name.",
          "${pad}// Replace this painter with the picture's own to draw it.",
          "${pad}Image(",
          "${pad}${INDENT}painter = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh),",
          // Stated rather than defaulted, and taken from the design when the author wrote one:
          // `Image`'s own parameter has no default, so it is written either way.
          "${pad}${INDENT}contentDescription = " +
            (node.stringOrNull("contentDescription")?.quoted() ?: "null") +
            ",",
        ) +
          (scale?.let { listOf("${pad}${INDENT}contentScale = ContentScale.$it,") }
            ?: emptyList()) +
          (alignment?.let { listOf("${pad}${INDENT}alignment = Alignment.$it,") } ?: emptyList()) +
          (modifier?.let { listOf("${pad}${INDENT}modifier = $it,") } ?: emptyList()) +
          listOf("${pad})")
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
            // The design's, and null only when it gave none. Null is right beside a label, which
            // already names the action, and wrong for an icon that *is* the button: the play and
            // delete buttons in the upstream samples each pass a string resource here.
            "${pad}${INDENT}contentDescription = " +
              (node.stringOrNull("contentDescription")?.takeIf(String::isNotEmpty)?.quoted()
                ?: "null") +
              ",",
          ) +
          (modifier?.let { listOf("${pad}${INDENT}modifier = $it,") } ?: emptyList()) +
          listOf("${pad})")
      }
      WearScreenCodeExporter.ICON_BUTTON -> {
        val symbol = iconButtonSymbol(node.string("variant"))
        usesIconButton += symbol
        listOf("${pad}$symbol(", "${pad}${INDENT}onClick = {},") +
          colorsArgument(node, nodeId, pad + INDENT, symbol) +
          surfaceArguments(pad + INDENT, nodeId, transformed, symbol) +
          listOf("${pad}) {") +
          node.slots["content"].orEmpty().flatMap { emit(it, depth + 1) } +
          listOf("${pad}}")
      }
      WearScreenCodeExporter.TEXT_BUTTON -> {
        val symbol = textButtonSymbol(node.string("variant"))
        usesTextButton += symbol
        listOf("${pad}$symbol(", "${pad}${INDENT}onClick = {},") +
          surfaceArguments(pad + INDENT, nodeId, transformed, symbol) +
          listOf("${pad}) {") +
          node.slots["content"].orEmpty().flatMap { emit(it, depth + 1) } +
          listOf("${pad}}")
      }
      WearScreenCodeExporter.LIST_SUB_HEADER -> {
        usesListSubHeader = true
        usesText = true
        listOf("${pad}ListSubHeader(") +
          surfaceArguments(pad + INDENT, nodeId, transformed, "ListSubHeader") +
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
        val state = rememberedFloat(nodeId, "Value", node.number("value") ?: 0f)
        listOf("${pad}Slider(") +
          listOf(
            "${pad}${INDENT}value = $state,",
            "${pad}${INDENT}onValueChange = { $state = it },",
            "${pad}${INDENT}valueRange = ${node.range()},",
            "${pad}${INDENT}steps = ${(node.number("steps") ?: 0f).toInt()},",
            "${pad}${INDENT}segmented = $segmented,",
          ) +
          surfaceArguments(pad + INDENT, nodeId, transformed, "Slider") +
          listOf("${pad})")
      }
      WearScreenCodeExporter.STEPPER -> {
        usesStepper = true
        val state = rememberedFloat(nodeId, "Value", node.number("value") ?: 0f)
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
        // `ButtonGroup` takes a `SurfaceTransformation` of its own (wear-compose-material3
        // 1.7.0-rc01), and `ButtonGroupDefaults` its list padding. Without the first the group
        // neither scaled nor faded at the screen's edge while every row around it did.
        listOf("${pad}ButtonGroup(") +
          surfaceArguments(pad + INDENT, nodeId, transformed, "ButtonGroup") +
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
      in packComponents ->
        emitPack(node, packComponents.getValue(node.componentId), depth, transformed)
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
   * A **pack** component — another catalog's composable admitted into this one — written from the
   * record it was projected from.
   *
   * ## Why the record and not a table
   *
   * Every other branch of [emit] is authored: this file knows that `wear-m3/card` is a `TitleCard`
   * and what its arguments mean. A pack component is one nobody here has met. What is known about
   * it is exactly what its catalog's `components.json` proved — the callable, its parameters, and a
   * call site the producer showed compiles (`code.call`) — and that is what this writes from. The
   * projection in the server (`ComponentRecordPacks`) offers a pack component only when that call
   * site exists, so a record reaching here has one.
   *
   * ## What each parameter becomes
   *
   * - A **slot** (a `@Composable` lambda the record lists under `slots`) holds the node's children
   *   for that slot, emitted through [emit] like any other content — so a pack container can hold
   *   `wear-m3/text` rows, or another pack node.
   * - A **literal** parameter (`String`, `Boolean`, `Int`, `Long`, `Float`, `Double`) takes the
   *   value the design set, written as the Kotlin literal its type spells.
   * - A **`Modifier`** parameter takes the node's chain — the test tag when tagging, and the row
   *   treatment when the node is a list item — and is omitted when there is nothing to say, as
   *   every authored branch omits it.
   * - A **`SurfaceTransformation`** parameter takes the row treatment, which is the argument
   *   `ListHeader` and `TitleCard` take for the same thing; a pack component declaring one is
   *   saying it is a surface.
   * - Anything else the design did not set is **omitted when defaulted** and otherwise takes the
   *   placeholder the proven call site used for it, read back out of `code.call` by parameter name.
   *   A required parameter with no placeholder there refuses by name, because inventing a value is
   *   how generated code stops compiling — the same rule `ComponentSnippets` keeps.
   *
   * The imports are the call site's own (`code.imports`): the callable plus whatever a constructed
   * placeholder needs. Nothing is spelled from a type name.
   */
  fun emitPack(
    node: UiBuilderNode,
    record: ComponentRecord,
    depth: Int,
    transformed: Boolean,
  ): List<String> {
    val pad = indent(depth)
    val name = record.symbol.name
    val placeholders = placeholderArguments(record)
    val slotNames = record.slots.mapTo(mutableSetOf()) { it.name }
    val arguments = mutableListOf<String>()
    for (parameter in record.parameters) {
      val argument = parameter.name
      when {
        parameter.composableSlot || argument in slotNames -> {
          val children = node.slots[argument].orEmpty()
          if (children.isNotEmpty()) {
            arguments += "${pad}${INDENT}$argument = {"
            arguments += children.flatMap { emit(it, depth + 2) }
            arguments += "${pad}${INDENT}},"
          } else if (!parameter.hasDefault) {
            arguments += "${pad}${INDENT}$argument = ${placeholders[argument] ?: "{}"},"
          }
        }
        parameter.typeFqn == WearScreenCodeExporter.MODIFIER_FQN -> {
          val chain = modifierChain(node.id, transformedHeight(transformed))
          if (chain != null) arguments += "${pad}${INDENT}$argument = $chain,"
          else if (!parameter.hasDefault) arguments += "${pad}${INDENT}$argument = Modifier,"
        }
        parameter.typeFqn == WearScreenCodeExporter.SURFACE_TRANSFORMATION_FQN -> {
          if (transformed) arguments += "${pad}${INDENT}$argument = SurfaceTransformation(spec),"
          else if (!parameter.hasDefault) {
            arguments += "${pad}${INDENT}$argument = ${placeholders[argument] ?: "null"},"
          }
        }
        else -> {
          val value = literal(node, parameter)
          when {
            value != null -> arguments += "${pad}${INDENT}$argument = $value,"
            parameter.hasDefault -> Unit
            else -> {
              val placeholder =
                placeholders[argument]
                  ?: return refused(
                    "`${node.componentId}` (node `${node.id}`) needs a value for " +
                      "`$argument: ${parameter.type}`, and its catalog's record proved no " +
                      "placeholder for one; set it in the design or give the parameter a default"
                  )
              arguments += "${pad}${INDENT}$argument = $placeholder,"
            }
          }
        }
      }
    }
    packImports += record.code?.imports.orEmpty().ifEmpty { listOf(record.symbol.callable) }
    return if (arguments.isEmpty()) listOf("${pad}$name()")
    else listOf("${pad}$name(") + arguments + listOf("${pad})")
  }

  /**
   * The value the design set for a literal parameter, as the Kotlin literal its type spells, or
   * null when the parameter is not a literal one or the design set nothing.
   *
   * Matched on the qualified type where the record carries one, and on the rendered spelling for a
   * record written before `typeFqn` existed — the same fallback `ComponentSnippets` makes, for the
   * same reason: a value written for `com.example.String` does not compile.
   */
  private fun literal(node: UiBuilderNode, parameter: TargetParameter): String? {
    val type =
      parameter.typeFqn
        ?: when (parameter.type.removeSuffix("?")) {
          "String" -> "kotlin.String"
          "Boolean" -> "kotlin.Boolean"
          "Int" -> "kotlin.Int"
          "Long" -> "kotlin.Long"
          "Float" -> "kotlin.Float"
          "Double" -> "kotlin.Double"
          else -> return null
        }
    val name = parameter.name
    return when (type) {
      "kotlin.String" -> node.stringOrNull(name)?.quoted()
      "kotlin.Boolean" -> node.boolean(name)?.toString()
      "kotlin.Int" -> node.number(name)?.toInt()?.toString()
      "kotlin.Long" -> node.number(name)?.toLong()?.let { "${it}L" }
      "kotlin.Float" -> node.number(name)?.let { "${it.dp()}f" }
      "kotlin.Double" -> node.number(name)?.let { it.toDouble().toString() }
      else -> null
    }
  }

  /**
   * The placeholder the proven call site wrote for each parameter, by name.
   *
   * `code.call` is `Name(a = x, b = y)` as `ComponentSnippets` prints it: every required parameter
   * once, `name = placeholder`, comma-separated, and no placeholder ever contains `, <name> = ` —
   * they are literals, `null`, `{}`, `{ 0 }`, `Type()` and `rememberType()`. So each value runs
   * from its own marker to the next parameter's. Read back here rather than re-derived, because the
   * placeholder table is that library's and is deliberately not reproduced anywhere else.
   */
  private fun placeholderArguments(record: ComponentRecord): Map<String, String> {
    val call = record.code?.call ?: return emptyMap()
    val open = call.indexOf('(')
    if (open < 0 || !call.endsWith(")")) return emptyMap()
    val body = call.substring(open + 1, call.length - 1)
    val starts =
      record.parameters
        .mapNotNull { parameter ->
          val markers = listOf("${parameter.name} = ", "`${parameter.name}` = ")
          markers.firstNotNullOfOrNull { marker ->
            argumentStart(body, marker)?.let { Triple(parameter.name, it, marker.length) }
          }
        }
        .sortedBy { it.second }
    return starts
      .mapIndexed { index, (name, at, markerLength) ->
        val end = starts.getOrNull(index + 1)?.second ?: body.length
        name to body.substring(at + markerLength, end).trim().removeSuffix(",").trim()
      }
      .toMap()
  }

  /** Where [marker] begins an argument in [body]: at its start, or right after `, `. */
  private fun argumentStart(body: String, marker: String): Int? {
    var from = 0
    while (true) {
      val at = body.indexOf(marker, from)
      if (at < 0) return null
      if (at == 0 || body.startsWith(", ", at - 2)) return at
      from = at + 1
    }
  }

  /**
   * The scaffold's `edgeButton` slot, as the `EdgeButton` that is the only thing it holds.
   *
   * `ScreenScaffold(edgeButton = …)` reveals its slot from the scroll state and shapes it to the
   * bottom curve, which is `EdgeButton`'s whole reason to exist — a plain `Button` in there is a
   * rectangle pinned to a curve. The canvas draws whatever component the design put in the slot, so
   * this is where the two part company.
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
        // `enabled` is the design's and the canvas has always honoured it: written only when it is
        // false, so a default call keeps the short form.
        if (node.boolean("enabled") == false) "enabled = false" else null,
        size?.let { "buttonSize = EdgeButtonSize.$it" },
        // A `wear-m3/button` here may carry the button's colours, and the canvas draws them.
        // `EdgeButton` is always the filled shape and takes `ButtonDefaults.buttonColors`, so the
        // colours are written against that whatever the legacy node's variant said.
        colorsCall(node, nodeId, "Button")?.let { "colors = $it" },
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

  /**
   * A `layout/column` holding only texts: a card's or a button's lines, in order.
   *
   * Only a column that says nothing of its own. Recognising the lines writes them into the
   * component's slots and the column itself into nothing, so a column carrying authored modifiers
   * keeps its place in the content form rather than having them dropped. The native lane's test tag
   * is the one thing a recognised column does lose: it has no counterpart in the generated code, so
   * its lines are what a native overlay selects — as a title card's two lines always have been.
   */
  private fun textLines(nodeId: String): List<String>? {
    val node = document.nodes[nodeId] ?: return null
    if (node.componentId != "layout/column") return null
    if (node.modifiers.isNotEmpty()) return null
    val children = node.slots["children"].orEmpty()
    if (children.isEmpty()) return null
    if (children.any { document.nodes[it]?.componentId != WearScreenCodeExporter.TEXT }) return null
    return children
  }

  /** A card's trailing content lambda, or its closing parenthesis when there is no body. */
  /**
   * The call's closing, with its content lambda when there is a body. [required] is for a component
   * whose `content` has no default: `AppCard`'s is non-null, so a two-line app card that ends at
   * `)` matches no overload, where `TitleCard`'s is nullable and may.
   */
  private fun trailingContent(
    pad: String,
    depth: Int,
    body: List<String>,
    required: Boolean = false,
  ): List<String> =
    if (body.isEmpty()) listOf(if (required) "${pad}) {}" else "${pad})")
    else listOf("${pad}) {") + body.flatMap { emit(it, depth + 1) } + listOf("${pad}}")

  /** A button's content, as the slots Wear's `Button` publishes. */
  private class ButtonSlots(val icon: String?, val label: String, val secondaryLabel: String?)

  /**
   * The slot shape of a button's content, or null when the content is something the slots cannot
   * hold: a text, an icon and a text, or either with a column of two texts as label and secondary
   * label.
   */
  private fun buttonSlots(content: List<String>): ButtonSlots? {
    fun componentOf(id: String) = document.nodes[id]?.componentId
    val icon = content.firstOrNull()?.takeIf { componentOf(it) == WearScreenCodeExporter.ICON }
    val rest = if (icon == null) content else content.drop(1)
    val labelId = rest.singleOrNull() ?: return null
    if (componentOf(labelId) == WearScreenCodeExporter.TEXT) return ButtonSlots(icon, labelId, null)
    val lines = textLines(labelId) ?: return null
    return when (lines.size) {
      1 -> ButtonSlots(icon, lines[0], null)
      2 -> ButtonSlots(icon, lines[0], lines[1])
      else -> null
    }
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
  private fun modifierChain(
    nodeId: String,
    vararg calls: String?,
    authored: Boolean = true,
  ): String? {
    val chain = buildList {
      if (tagNodes) {
        usesTestTag = true
        add("testTag(${nodeId.quoted()})")
      }
      // What the design asked for, before what the list adds. These were never read: every row
      // in the upstream samples is `fillMaxWidth`, the canvas drew it that way, and the generated
      // screen drew content-width pills instead.
      if (authored) document.nodes[nodeId]?.let { addAll(authoredModifiers(it)) }
      calls.filterNotNullTo(this)
    }
    return if (chain.isEmpty()) null else chain.joinToString(".", prefix = "Modifier.")
  }

  /** Each node's parent, for the scope a `weight` or an `align` means something in. */
  private val parents: Map<String, UiBuilderNode> = buildMap {
    document.nodes.values.forEach { parent ->
      parent.slots.values.forEach { children -> children.forEach { put(it, parent) } }
    }
  }

  /**
   * The modifiers a design authored on [node], as the `Modifier` calls they become.
   *
   * The same vocabulary the canvas draws (`uiBuilderModifier`), written as plain Compose. What this
   * generator cannot write is refused by name rather than dropped — a node that silently loses its
   * `size` generates a screen the canvas never showed, which is the drift this fixes.
   */
  private fun authoredModifiers(node: UiBuilderNode): List<String> =
    node.modifiers.mapNotNull { element ->
      val modifier = element as? JsonObject ?: return@mapNotNull null
      fun number(name: String): Float? = modifier[name]?.jsonPrimitive?.floatOrNull
      fun text(name: String): String? = modifier[name]?.jsonPrimitive?.contentOrNull
      fun dp(value: Float?): String {
        usesDp = true
        return "${(value ?: 0f).dp()}.dp"
      }
      fun layout(call: String): String {
        layoutImports += call.substringBefore('(')
        return call
      }
      when (val type = text("type")) {
        "fillMaxWidth",
        "fillMaxSize",
        "fillMaxHeight" -> layout("$type()")
        "padding" -> {
          val start = number("startDp") ?: 0f
          val top = number("topDp") ?: 0f
          val end = number("endDp") ?: 0f
          val bottom = number("bottomDp") ?: 0f
          layout(
            when {
              start == top && top == end && end == bottom -> "padding(${dp(start)})"
              start == end && top == bottom ->
                "padding(horizontal = ${dp(start)}, vertical = ${dp(top)})"
              else ->
                "padding(start = ${dp(start)}, top = ${dp(top)}, end = ${dp(end)}, " +
                  "bottom = ${dp(bottom)})"
            }
          )
        }
        "size" -> {
          val width = number("widthDp")
          val height = number("heightDp")
          when {
            width != null && width == height -> layout("size(${dp(width)})")
            width != null && height != null -> layout("size(${dp(width)}, ${dp(height)})")
            width != null -> layout("width(${dp(width)})")
            height != null -> layout("height(${dp(height)})")
            else -> null
          }
        }
        "width" -> layout("width(${dp(number("widthDp"))})")
        "height" -> layout("height(${dp(number("heightDp"))})")
        "widthIn",
        "heightIn" ->
          layout(
            "$type(" +
              listOfNotNull(
                  number("minDp")?.let { "min = ${dp(it)}" },
                  number("maxDp")?.let { "max = ${dp(it)}" },
                )
                .joinToString() +
              ")"
          )
        "aspectRatio" -> layout("aspectRatio(${(number("ratio") ?: 1f).dp()}f)")
        "offset" -> layout("offset(${dp(number("xDp"))}, ${dp(number("yDp"))})")
        "alpha" -> {
          drawImports += "alpha"
          "alpha(${(number("alpha") ?: 1f).dp()}f)"
        }
        "rotate" -> {
          drawImports += "rotate"
          "rotate(${(number("degrees") ?: 0f).dp()}f)"
        }
        "scale" -> {
          drawImports += "scale"
          "scale(${(number("scaleX") ?: 1f).dp()}f, ${(number("scaleY") ?: 1f).dp()}f)"
        }
        "zIndex" -> {
          usesZIndex = true
          "zIndex(${(number("zIndex") ?: 0f).dp()}f)"
        }
        // Identity rather than layout. The native lane's own tag is written first and wins; an
        // authored one is kept in an export, where it is the only tag.
        "testTag" ->
          if (tagNodes) null
          else
            text("tag")?.takeIf(String::isNotBlank)?.let {
              usesTestTag = true
              "testTag(${it.quoted()})"
            }
        "weight" ->
          weightCall(node, number("weight"), modifier["fill"]?.jsonPrimitive?.booleanOrNull)
        "align",
        "alignHorizontal",
        "alignVertical" -> alignCall(node, type, text("alignment"))
        null -> null
        else -> {
          refusals +=
            "the `$type` modifier on `${node.id}` is not one the Wear screen generator writes; " +
              "the canvas draws it, so exporting without it would generate a different screen"
          null
        }
      }
    }

  /**
   * `weight`, in the three scopes that define one: `Row`, `Column` and `ButtonGroup`, whose own
   * `ButtonGroupScope.weight` is how Jetcaster's episode screen makes play the wider button.
   */
  private fun weightCall(node: UiBuilderNode, weight: Float?, fill: Boolean?): String? {
    if (weight == null || weight <= 0f) return null
    val parent = parents[node.id]?.componentId
    return when (parent) {
      "layout/row",
      "layout/column" ->
        "weight(${weight.dp()}f" + (if (fill == false) ", fill = false" else "") + ")"
      WearScreenCodeExporter.BUTTON_GROUP -> "weight(${weight.dp()}f)"
      else -> {
        refusals +=
          "the `weight` modifier on `${node.id}` shares out a row's, a column's or a button " +
            "group's space, and this node's parent is ${parent?.let { "`$it`" } ?: "the screen"}"
        null
      }
    }
  }

  /** `align`, where the parent's scope defines it, on the axis that scope aligns on. */
  private fun alignCall(node: UiBuilderNode, type: String, alignment: String?): String? {
    if (alignment.isNullOrEmpty()) return null
    val parent = parents[node.id]?.componentId
    val expected =
      when (type) {
        "align" -> "layout/box"
        "alignHorizontal" -> "layout/column"
        else -> "layout/row"
      }
    if (parent != expected) {
      refusals +=
        "the `$type` modifier on `${node.id}` aligns within a `$expected`, and this node's " +
          "parent is ${parent?.let { "`$it`" } ?: "the screen"}"
      return null
    }
    usesAlignment = true
    return "align(Alignment.${alignment.replaceFirstChar(Char::uppercaseChar)})"
  }

  /** `transformedHeight`, or nothing, so [modifierChain] can take it positionally. */
  private fun transformedHeight(transformed: Boolean): String? =
    if (transformed) "transformedHeight(this, spec)" else null

  /**
   * The minimum vertical padding Wear Material 3 asks a transforming list's row to keep, from the
   * component's own `…Defaults`.
   *
   * Every row type has one — `ListHeaderDefaults` and `TextDefaults` as a top/bottom pair, the rest
   * as one value — and both upstream samples set it on every item. This used to be written for
   * cards only, so a header or a button sat on the list's flat spacing where the round screen's top
   * and bottom clip it. A component with no published minimum gets none rather than a borrowed one.
   */
  private fun minimumVerticalListContentPadding(transformed: Boolean, symbol: String): String? {
    if (!transformed) return null
    val pair =
      when (symbol) {
        "ListHeader" -> "ListHeaderDefaults"
        "Text" -> "TextDefaults"
        else -> null
      }
    if (pair != null) {
      listPaddingDefaults += pair
      return "minimumVerticalContentPadding($pair.minimumTopListContentPadding, " +
        "$pair.minimumBottomListContentPadding)"
    }
    val defaults =
      when (symbol) {
        "Card",
        "OutlinedCard",
        "TitleCard",
        "AppCard" -> "CardDefaults"
        "Button",
        "FilledTonalButton",
        "OutlinedButton",
        "ChildButton" -> "ButtonDefaults"
        "ButtonGroup" -> "ButtonGroupDefaults"
        in ICON_BUTTON_SYMBOLS -> "IconButtonDefaults"
        in TEXT_BUTTON_SYMBOLS -> "TextButtonDefaults"
        else -> return null
      }
    listPaddingDefaults += defaults
    return "minimumVerticalContentPadding($defaults.minimumVerticalListContentPadding)"
  }

  /**
   * What a **surface** node adds to its call: the row treatment, and the tag if one is being
   * written.
   *
   * `transformation = SurfaceTransformation(spec)` is a real argument of `ListHeader`, `TitleCard`
   * and `Button` rather than a modifier, which is why this is a pair of lines and not one chain.
   */
  /**
   * The modifier a list row takes, and `transformation` only where the component declares one.
   *
   * ## Why the symbol has to be named
   *
   * `SurfaceTransformation` is a **surface** treatment, and Wear Material 3 declares the parameter
   * only on the components that draw a surface: the cards, the buttons, the three selection rows,
   * `ButtonGroup`, `ListHeader` and `ListSubHeader`. `Slider`, `Stepper`, the progress indicators,
   * `IconButton` and `TextButton` have no such parameter.
   *
   * This wrote it unconditionally, so a `wear-m3/slider` placed in a `TransformingLazyColumn`
   * generated `Slider(…, transformation = SurfaceTransformation(spec))` — which does not compile.
   * Nothing here caught it, because every test of this generator asserted the call's *text*; the
   * first thing that actually put the emitted Kotlin in front of a compiler was exporting the
   * committed `google-home-wear` sample and building it, and all three of its sliders failed.
   *
   * So the set below is not a style choice, it is the library's own signature list, and
   * [WearSurfaceTransformationTest] pins it against what this generator emits for every component.
   */
  private fun surfaceArguments(
    pad: String,
    nodeId: String,
    transformed: Boolean,
    symbol: String,
  ): List<String> =
    // The lists guide's order: what the design authored, then `transformedHeight`, then the
    // component's minimum list padding — `fillMaxWidth().transformedHeight(this, spec)
    // .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding)`.
    (modifierChain(
        nodeId,
        transformedHeight(transformed),
        minimumVerticalListContentPadding(transformed, symbol),
      )
      ?.let { listOf("${pad}modifier = $it,") } ?: emptyList()) +
      if (transformed && symbol in WearScreenCodeExporter.SURFACE_TRANSFORMATION_SYMBOLS)
        listOf("${pad}transformation = SurfaceTransformation(spec),")
      else emptyList()

  /**
   * `colors = <Defaults>.<variant>Colors(containerColor = …, contentColor = …)` for a button that
   * recolours its variant, which is how both upstream samples mark hierarchy — ComposeStarter's
   * settings button on `secondary`, Jetcaster's first library row on `surfaceContainer`.
   *
   * The variant's own colours function, not a generic one, so the variant still decides every
   * colour the design did not name (the disabled pair, the secondary label, the icon). An outlined
   * or child button draws no container, and Wear's colours functions for them take no
   * `containerColor`; a design that names one there is refused rather than silently dropped.
   */
  private fun colorsArgument(
    node: UiBuilderNode,
    nodeId: String,
    pad: String,
    symbol: String,
  ): List<String> =
    colorsCall(node, nodeId, symbol)?.let { listOf("${pad}colors = $it,") } ?: emptyList()

  /** The `…Colors(…)` call [colorsArgument] writes, or null when the design recolours nothing. */
  private fun colorsCall(node: UiBuilderNode, nodeId: String, symbol: String): String? {
    val container = colorExpression(node, "containerColor")
    val content = colorExpression(node, "contentColor")
    if (container == null && content == null) return null
    val (defaults, function) =
      when (symbol) {
        "Button" -> "ButtonDefaults" to "buttonColors"
        "FilledTonalButton" -> "ButtonDefaults" to "filledTonalButtonColors"
        "OutlinedButton" -> "ButtonDefaults" to "outlinedButtonColors"
        "ChildButton" -> "ButtonDefaults" to "childButtonColors"
        "FilledIconButton" -> "IconButtonDefaults" to "filledIconButtonColors"
        "FilledTonalIconButton" -> "IconButtonDefaults" to "filledTonalIconButtonColors"
        "FilledVariantIconButton" -> "IconButtonDefaults" to "filledVariantIconButtonColors"
        "OutlinedIconButton" -> "IconButtonDefaults" to "outlinedIconButtonColors"
        else -> "IconButtonDefaults" to "iconButtonColors"
      }
    if (container != null && symbol in NO_CONTAINER_BUTTON_SYMBOLS) {
      refused(
        "`${node.componentId}` (node `$nodeId`) sets `containerColor` on `$symbol`, which draws " +
          "no container — Wear's `$defaults.$function` takes only content colours"
      )
      return null
    }
    listPaddingDefaults += defaults
    val arguments =
      listOfNotNull(
        container?.let { "containerColor = $it" },
        content?.let { "contentColor = $it" },
      )
    return "$defaults.$function(${arguments.joinToString(", ")})"
  }

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
    val state =
      rememberedBoolean(
        nodeId,
        flag.replaceFirstChar(Char::uppercaseChar),
        node.boolean(flag) ?: false,
      )
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
      surfaceArguments(pad + INDENT, nodeId, transformed, symbol) +
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
    val visible = rememberedBoolean(nodeId, "Visible", node.boolean("visible") ?: false)
    val dismiss = "${pad}${INDENT}onDismissRequest = { $visible = false },"
    return when (node.componentId) {
      WearScreenCodeExporter.ALERT_DIALOG -> {
        usesDialog += "AlertDialog"
        usesText = true
        val text = node.string("text")
        val confirm = node.slots["confirmButton"].orEmpty().singleOrNull()
        val dismissButton = node.slots["dismissButton"].orEmpty().singleOrNull()
        val extra = node.slots["content"].orEmpty()
        if (dismissButton != null && confirm == null) {
          return refused(
            "`${node.componentId}` (node `$nodeId`) has a dismiss button and no confirm button, " +
              "and Wear's `AlertDialog` has no such shape: `dismissButton` is a parameter of the " +
              "two-button overload only, which also takes the confirm"
          )
        }
        listOf("${pad}AlertDialog(") +
          listOf(
            "${pad}${INDENT}visible = $visible,",
            dismiss,
            "${pad}${INDENT}title = { Text(text = ${node.string("title").quoted()}) },",
          ) +
          (if (text.isEmpty()) emptyList()
          else listOf("${pad}${INDENT}text = { Text(text = ${text.quoted()}) },")) +
          // Wear's own confirm and dismiss buttons, which carry the icons, sizes, shapes and
          // content
          // descriptions the dialog guidance specifies. A filled slot is written as the default
          // button because that is what the canvas draws for one — it reads the slot's presence,
          // not its content — so the design and the code show the same dialog. Both dismiss the
          // dialog; the app wires its own action beside that.
          (confirm?.let {
            usesAlertDialogDefaults = true
            listOf(
              "${pad}${INDENT}confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = { $visible = false }) },"
            )
          } ?: emptyList()) +
          (dismissButton?.let {
            usesAlertDialogDefaults = true
            listOf(
              "${pad}${INDENT}dismissButton = { AlertDialogDefaults.DismissButton(onClick = { $visible = false }) },"
            )
          } ?: emptyList()) +
          (modifierChain(nodeId)?.let { listOf("${pad}${INDENT}modifier = $it,") } ?: emptyList()) +
          // The content lambda only when the design put something in it: `content` is nullable
          // upstream, and an empty `{ }` after every dialog was a line of nothing to read past.
          (if (extra.isEmpty()) listOf("${pad})")
          else listOf("${pad}) {") + extra.flatMap { emit(it, depth + 1) } + listOf("${pad}}"))
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
  /**
   * The state names already declared, so two ids that fold to one name — `volume-level` and
   * `volume_level` both read `volumeLevelValue` — get distinct locals instead of a redeclaration
   * that does not compile. The first keeps the plain name; later ones take `2`, `3`, … in the order
   * their nodes are reached, which is document order and so stable across exports.
   */
  private val stateNames = mutableSetOf<String>()

  private fun uniqueStateName(nodeId: String, role: String): String {
    val base = nodeId.stateIdentifier(role)
    var name = base
    var index = 2
    while (!stateNames.add(name)) name = "$base${index++}"
    return name
  }

  private fun rememberedFloat(nodeId: String, role: String, initial: Float): String {
    val name = uniqueStateName(nodeId, role)
    usesRememberState = true
    rememberedState += "var $name by remember { mutableFloatStateOf(${initial.dp()}f) }"
    return name
  }

  private fun rememberedBoolean(nodeId: String, role: String, initial: Boolean): String {
    val name = uniqueStateName(nodeId, role)
    usesRememberState = true
    rememberedState += "var $name by remember { mutableStateOf($initial) }"
    return name
  }

  /** The imports the emitted source needs, in the order ktfmt sorts them. */
  fun imports(timeText: Boolean, previews: Boolean = true): List<String> = buildList {
    add("androidx.compose.foundation.layout.fillMaxSize")
    if (usesArrangement) add("androidx.compose.foundation.layout.Arrangement")
    if (usesBox) add("androidx.compose.foundation.layout.Box")
    if (usesColumn) add("androidx.compose.foundation.layout.Column")
    if (usesRow) add("androidx.compose.foundation.layout.Row")
    add("androidx.compose.runtime.Composable")
    add("androidx.compose.ui.Modifier")
    if (usesTestTag) add("androidx.compose.ui.platform.testTag")
    if (usesDp) add("androidx.compose.ui.unit.dp")
    if (usesImage) add("androidx.compose.foundation.Image")
    if (usesImage) add("androidx.compose.ui.graphics.painter.ColorPainter")
    if (usesContentScale) add("androidx.compose.ui.layout.ContentScale")
    if (usesAlignment) add("androidx.compose.ui.Alignment")
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
    if (usesImage || usesMaterialTheme) add("androidx.wear.compose.material3.MaterialTheme")
    if (usesIcon) add("androidx.wear.compose.material3.Icon")
    usesIconButton.forEach { add("androidx.wear.compose.material3.$it") }
    usesTextButton.forEach { add("androidx.wear.compose.material3.$it") }
    usesSelection.forEach { add("androidx.wear.compose.material3.$it") }
    usesProgress.forEach { add("androidx.wear.compose.material3.$it") }
    usesDialog.forEach { add("androidx.wear.compose.material3.$it") }
    if (usesAlertDialogDefaults) add("androidx.wear.compose.material3.AlertDialogDefaults")
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
    if (usesTextOverflow) add("androidx.compose.ui.text.style.TextOverflow")
    textImports.forEach {
      add(
        when (it) {
          "FontWeight" -> "androidx.compose.ui.text.font.FontWeight"
          "FontStyle" -> "androidx.compose.ui.text.font.FontStyle"
          "TextDecoration" -> "androidx.compose.ui.text.style.TextDecoration"
          "TextAlign" -> "androidx.compose.ui.text.style.TextAlign"
          "Color" -> "androidx.compose.ui.graphics.Color"
          else -> "androidx.compose.ui.unit.sp"
        }
      )
    }
    layoutImports.forEach { add("androidx.compose.foundation.layout.$it") }
    drawImports.forEach { add("androidx.compose.ui.draw.$it") }
    if (usesZIndex) add("androidx.compose.ui.zIndex")
    if (timeText) add("androidx.wear.compose.material3.TimeText")
    if (timeText) add("androidx.wear.compose.material3.timeTextCurvedText")
    if (usesListHeader) add("androidx.wear.compose.material3.ListHeader")
    if (usesCard) add("androidx.wear.compose.material3.TitleCard")
    usesPlainCard.forEach { add("androidx.wear.compose.material3.$it") }
    listPaddingDefaults.forEach { add("androidx.wear.compose.material3.$it") }
    add("androidx.wear.compose.material3.lazy.rememberTransformationSpec")
    add("androidx.wear.compose.material3.lazy.transformedHeight")
    // `androidx.wear.compose:compose-ui-tooling`, not `androidx.wear:wear-tooling-preview`. The
    // latter is the device-id constants (`WearDevices`); the multipreview lives with Wear Compose,
    // and a project that renders Wear previews already has it. Only when the previews are written:
    // neither this artifact nor compose-ai-tools' `preview-annotations` below is on a catalog's
    // runtime bundle, so importing them for a file that does not use them is a compile error on the
    // one lane that has to compile this source.
    if (previews) {
      add("androidx.wear.compose.ui.tooling.preview.WearPreviewDevices")
      // The parity capture's own three. `Preview` is the platform annotation the device spec rides
      // on, and the scrolling pair is compose-ai-tools' `preview-annotations`, already on the
      // classpath of anything the compose-preview plugin renders.
      add("androidx.compose.ui.tooling.preview.Preview")
      add("ee.schimke.composeai.preview.ScrollMode")
      add("ee.schimke.composeai.preview.ScrollingPreview")
    }
    if (usesEdgeButton) add("androidx.wear.compose.material3.EdgeButton")
    if (usesEdgeButtonSize) add("androidx.wear.compose.material3.EdgeButtonSize")
    addAll(packImports)
  }
    .distinct()
    .sorted()

  private fun indent(depth: Int) = INDENT.repeat(depth)

  // Properties arrive as the wire's typed values — `{"type": "string", "value": "…"}` — so a read
  // that took `properties[name]` straight would see the wrapper object and never the value.
  private fun UiBuilderNode.string(name: String): String = stringOrNull(name).orEmpty()

  private fun UiBuilderNode.stringOrNull(name: String): String? =
    (properties[name] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull

  /**
   * Everything `wear-m3/text` declares beyond its string, as `Text` arguments.
   *
   * The catalog declares sixteen properties and the canvas has drawn all of them; this wrote the
   * string alone, so Jetcaster's `bodySmall` date came out in the default body style and wrapped
   * where the design truncated it. Unset properties write nothing, so an undecorated text keeps the
   * short form.
   */
  private fun textArguments(node: UiBuilderNode): List<String> = buildList {
    node.stringOrNull("style")?.takeIf(String::isNotEmpty)?.let {
      usesMaterialTheme = true
      add("style = MaterialTheme.typography.$it")
    }
    colorExpression(node, "color")?.let { add("color = $it") }
    node.stringOrNull("fontWeight")?.let {
      textImports += "FontWeight"
      add(
        "fontWeight = FontWeight." +
          when (it) {
            "medium" -> "Medium"
            "semiBold" -> "SemiBold"
            "bold" -> "Bold"
            else -> "Normal"
          }
      )
    }
    node.stringOrNull("fontStyle")?.let {
      textImports += "FontStyle"
      add("fontStyle = FontStyle." + if (it == "italic") "Italic" else "Normal")
    }
    node
      .number("fontSizeSp")
      ?.takeIf { it > 0f }
      ?.let {
        textImports += "sp"
        add("fontSize = ${it.dp()}.sp")
      }
    node
      .number("lineHeightSp")
      ?.takeIf { it > 0f }
      ?.let {
        textImports += "sp"
        add("lineHeight = ${it.dp()}.sp")
      }
    node.number("letterSpacingSp")?.let {
      textImports += "sp"
      add("letterSpacing = ${it.dp()}.sp")
    }
    node
      .stringOrNull("textDecoration")
      ?.takeIf { it != "none" }
      ?.let {
        textImports += "TextDecoration"
        add(
          "textDecoration = TextDecoration." + if (it == "underline") "Underline" else "LineThrough"
        )
      }
    node.stringOrNull("textAlign")?.let {
      textImports += "TextAlign"
      add("textAlign = TextAlign." + it.replaceFirstChar(Char::uppercaseChar))
    }
    node.number("minLines")?.let { add("minLines = ${it.toInt().coerceAtLeast(1)}") }
    if (node.boolean("softWrap") == false) add("softWrap = false")
    addAll(node.labelArguments())
  }

  /**
   * The two `wear-m3/text` properties that are really its parent's business: `alignment` in a `Box`
   * and `weight` in a `Row` or `Column`. Written as the modifier they mean in that scope, and
   * nothing anywhere else, because the canvas ignores them there too.
   */
  private fun textPropertyModifier(node: UiBuilderNode): String? {
    val parent = parents[node.id]?.componentId
    node
      .number("weight")
      ?.takeIf { it > 0f }
      ?.let { weight ->
        if (parent == "layout/row" || parent == "layout/column") return "weight(${weight.dp()}f)"
      }
    node.stringOrNull("alignment")?.let { alignment ->
      if (parent == "layout/box") {
        usesAlignment = true
        return "align(Alignment.${alignment.replaceFirstChar(Char::uppercaseChar)})"
      }
    }
    return null
  }

  /**
   * A colour property as Wear Compose source: a theme role is `MaterialTheme.colorScheme.<role>`,
   * which follows the app's theme the way the canvas's does, and a `#RRGGBB` / `#AARRGGBB` literal
   * is a `Color(0x…)`.
   */
  private fun colorExpression(node: UiBuilderNode, name: String): String? {
    val property = node.properties[name] as? JsonObject ?: return null
    val value =
      property["value"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty) ?: return null
    return if (value.startsWith("#")) {
      val hex = value.removePrefix("#").uppercase()
      val argb = if (hex.length == 6) "FF$hex" else hex
      textImports += "Color"
      "Color(0x$argb)"
    } else if (value == "transparent") {
      textImports += "Color"
      "Color.Transparent"
    } else {
      usesMaterialTheme = true
      // The token vocabulary is shared with the mobile catalog, and Wear's `ColorScheme` has no
      // `surface` and no `surfaceContainerHighest`: a watch draws on black and publishes three
      // container tones. Each maps to the Wear role the canvas draws it as, so the code compiles
      // and shows the colour the design did.
      val role =
        when (value) {
          "surface" -> "surfaceContainer"
          "surfaceContainerHighest" -> "surfaceContainerHigh"
          else -> value
        }
      "MaterialTheme.colorScheme.$role"
    }
  }

  /**
   * A label's own arguments — `maxLines` and `overflow`, which upstream's `ListHeader` and
   * `ListSubHeader` cannot take because they take a content lambda instead of a string.
   *
   * Named here rather than inline so the two header emitters read the same properties, and so the
   * set is one list to check against the catalog's declaration.
   */
  private fun UiBuilderNode.labelArguments(): List<String> = buildList {
    // Clamped, because the catalog declares an unbounded integer and `Text` throws below one: the
    // canvas clamps the same value the same way, so an out-of-range document draws and generates
    // the same screen instead of failing in one lane only.
    number("maxLines")?.let { add("maxLines = ${it.toInt().coerceAtLeast(1)}") }
    stringOrNull("overflow")?.let {
      usesTextOverflow = true
      // The same three spellings `UiBuilderRenderer.textOverflow` reads, so a design's
      // `overflow` means one thing on both lanes.
      add(
        "overflow = TextOverflow." +
          when (it) {
            "visible" -> "Visible"
            "ellipsis" -> "Ellipsis"
            else -> "Clip"
          }
      )
    }
  }

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

    val ICON_BUTTON_SYMBOLS =
      setOf(
        "FilledIconButton",
        "FilledTonalIconButton",
        "FilledVariantIconButton",
        "OutlinedIconButton",
        "IconButton",
      )

    /** The buttons whose colours function has no `containerColor`, because they draw none. */
    val NO_CONTAINER_BUTTON_SYMBOLS = setOf("OutlinedButton", "ChildButton", "OutlinedIconButton")

    val TEXT_BUTTON_SYMBOLS =
      setOf(
        "FilledTextButton",
        "FilledTonalTextButton",
        "FilledVariantTextButton",
        "OutlinedTextButton",
        "TextButton",
      )
  }
}

/**
 * A node id as the Kotlin name of the state it holds: `error-dialog` and `Visible` become
 * `errorDialogVisible`.
 *
 * The id is used rather than a counter because it is already unique across the document and because
 * it is how a reader gets from a line of generated Kotlin back to the node that produced it. It
 * used to be folded to `error_dialog`, which is not how Kotlin names a local, and said nothing
 * about what the state was; the role suffix says that, and also keeps an id like `in` or `list`
 * from colliding with a keyword or with `listState`.
 */
private fun String.stateIdentifier(role: String): String {
  val words = split(Regex("[^A-Za-z0-9]+")).filter(String::isNotEmpty)
  val camel =
    words
      .mapIndexed { index, word ->
        if (index == 0) word.replaceFirstChar(Char::lowercaseChar)
        else word.replaceFirstChar(Char::uppercaseChar)
      }
      .joinToString("")
  val base = if (camel.isEmpty() || camel.first().isDigit()) "state$camel" else camel
  return base + role
}

/** `8.0` reads as `8` in a `.dp` literal. */
private fun Float.dp(): String = toString().removeSuffix(".0")

/**
 * A Kotlin `"…"` literal.
 *
 * Escapes the line breaks and `$` as well as the quote: a label is authored text, and the upstream
 * ComposeStarter greeting — `"From the Round world,\nHello, Android!"` — generated a string literal
 * broken across two lines, which does not compile; a `$` would have become a template. The same set
 * `RemoteContentEmitter.escaped` has always used.
 */
internal fun String.quoted(): String =
  "\"" +
    replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("$", "\\$")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t") +
    "\""

/** The design's title as a composable name: "Activity list" becomes `ActivityListScreen`. */
internal fun UiBuilderDocument.screenIdentifier(): String {
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
