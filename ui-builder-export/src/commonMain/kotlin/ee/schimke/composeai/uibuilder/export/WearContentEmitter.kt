package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.TargetParameter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Writes a screen's designed content as Wear Compose Material 3 source. [WearScreenCodeExporter]
 * owns the screen's shape; this owns the body's vocabulary. Refusals are collected, so one pass
 * reports every unmappable node.
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

  /** `androidx.compose.foundation` symbols a painting modifier used, by path below that package. */
  private val foundationImports = mutableSetOf<String>()

  private var usesRectangleShape = false

  private var usesAlertDialogDefaults = false

  /** Wear `…Defaults` objects named by list padding or button colours, by simple name. */
  private val listPaddingDefaults = mutableSetOf<String>()

  /** Text arguments' own types, by simple name, with their packages in [imports]. */
  private val textImports = mutableSetOf<String>()
  /** Fully-qualified imports a text's [FontSettings] arguments need. */
  private val fontImports = mutableSetOf<String>()

  private var usesMaterialTheme = false

  /** The imports the pack components this screen holds resolve through; see [emitPack]. */
  private val packImports = mutableSetOf<String>()

  /**
   * The `remember`s the screen function declares before anything is drawn. Controlled components
   * (`Slider`, `Stepper`) need hoisted state to be usable; the branch that needs one can be
   * arbitrarily deep, so they are collected here.
   */
  private val rememberedState = mutableListOf<String>()

  /** The hoisted state declarations, in the order their nodes were reached. */
  fun stateDeclarations(): List<String> = rememberedState.toList()

  /** The variable font texts this screen draws, in the order their nodes were reached. */
  private val variableFontSpecs = mutableListOf<VariableFontText.Spec>()

  /** The declarations [variableFontSpecs] need generated, one per distinct text. */
  fun variableFontRequests(): List<VariableFontText.Request> =
    VariableFontText.requests(variableFontSpecs)

  /**
   * The scaffold's own `modifier`, which exists only to carry its test tag so a native overlay can
   * select the root. Null when nothing is being tagged.
   */
  fun rootModifier(nodeId: String): String? = modifierChain(nodeId, authored = false)

  /**
   * The scaffold's re-skinned colour scheme for a `MaterialTheme` wrap, or null when the design
   * overrides no role. A token role reads the stock scheme (`primary = secondary` swaps them), as
   * on the canvas. See [WearScreenTheme].
   */
  fun themeColorScheme(nodeId: String): String? {
    val node = document.nodes[nodeId] ?: return null
    val overrides =
      WearScreenTheme.ROLES.mapNotNull { role ->
        colorExpression(node, WearScreenTheme.property(role))?.let { "$role = $it" }
      }
    if (overrides.isEmpty()) return null
    usesMaterialTheme = true
    return "MaterialTheme.colorScheme.copy(${overrides.joinToString(", ")})"
  }

  /**
   * The scaffold's typefaces, as the lines of a `typography = …` argument to the screen's
   * `MaterialTheme`, or null when the design names none. The families are declared through [fonts].
   * See [ThemeTypefaces].
   */
  fun themeTypography(nodeId: String, fonts: AndroidGoogleFonts, depth: Int): List<String>? {
    val node = document.nodes[nodeId] ?: return null
    val roles = ThemeTypefaces.wearRoleFamilies(ThemeTypefaces.families(node))
    if (roles.isEmpty()) return null
    usesMaterialTheme = true
    return fonts.typography("MaterialTheme.typography", roles, depth)
  }

  fun emitScaffoldBody(nodeId: String): List<String> {
    val node = document.nodes[nodeId] ?: return refused("the content node `$nodeId` is missing")
    if (node.componentId != WearScreenCodeExporter.TRANSFORMING_LAZY_COLUMN) {
      // Anything that is not a list goes straight into `ScreenScaffold`'s content lambda: a single
      // full-screen control (`Stepper`, picker) is a real screen and has no item scope.
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
      // `stableKey` is the item's identity, spelled as the lazy list's `key`, as in the mobile
      // lane.
      val key =
        document.nodes[itemId]
          ?.stringOrNull("stableKey")
          // Blank is absent: two items sharing `key = ""` would fail at runtime.
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
   * [transformed] carries the row treatment down exactly one level, to the item body's
   * composable: `transformedHeight` measures against the list's own scroll.
   */
  fun emit(nodeId: String, depth: Int, transformed: Boolean = false): List<String> {
    val node = document.nodes[nodeId] ?: return refused("node `$nodeId` is missing")
    val pad = indent(depth)
    return when (node.componentId) {
      VariableFontText.WEAR_ID -> variableFontText(node, nodeId, pad, transformed)
      WearScreenCodeExporter.TEXT -> {
        usesText = true
        // `SurfaceTransformation` is for components that draw a surface; a bare `Text` gets the
        // height treatment and `TextDefaults`' list padding instead.
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
      // `ListHeader`, not a padded Text, so the generated header has the canvas's 48dp height.
      WearScreenCodeExporter.LIST_HEADER -> {
        usesListHeader = true
        usesText = true
        // `ListHeader` takes a content lambda, so the label's `maxLines`/`overflow` go on this
        // `Text`.
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
        // The variant picks the upstream card (as for `m3/card`). `OutlinedCard` and `Card` take a
        // single content lambda, so they skip the line recognition below.
        if (variant == "outlined" || variant == "plain") {
          val symbol = if (variant == "outlined") "OutlinedCard" else "Card"
          usesPlainCard += symbol
          return listOf("${pad}$symbol(", "${pad}${INDENT}onClick = {},") +
            cardColors(node, nodeId, pad + INDENT, symbol) +
            surfaceArguments(pad + INDENT, nodeId, transformed, symbol) +
            listOf("${pad}) {") +
            content.flatMap { emit(it, depth + 1) } +
            listOf("${pad}}")
        }
        // The canvas draws a card's lines as a Column, so they are recognised here and written into
        // upstream's slots: `AppCard` as app name, title, body; `TitleCard` as title, body. The
        // body is the card's content, not its `subtitle`.
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
            cardColors(node, nodeId, pad + INDENT, "AppCard") +
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
          cardColors(node, nodeId, pad + INDENT, "TitleCard") +
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
        // Written only when false, so a default call keeps the short form.
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
        // The slot overload (`icon`, `label`, `secondaryLabel`), as both upstream samples use; each
        // slot carries its own typography, colour and spacing.
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
      // No transformation on a layout container: a `Column` draws no surface; nested surfaces get
      // it.
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
      // As in the m3 lane: `Image(painter = ColorPainter(…))` in the theme's ground, with a comment
      // naming the line to replace. The bytes live in the asset store and the resource symbol
      // belongs to the receiving project.
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
        // Icons are extension properties on `Icons.Filled` in the lowercased package, so the
        // property is imported and the expression stays `Icons.Filled.X`. Same table as
        // `ScreenDocumentProjection.icon`.
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
            // The design's description, null only when it gave none (fine beside a label, not for
            // an icon-only button).
            "${pad}${INDENT}contentDescription = " +
              (node.stringOrNull("contentDescription")?.takeIf(String::isNotEmpty)?.quoted()
                ?: "null") +
              ",",
          ) +
          (modifier?.let { listOf("${pad}${INDENT}modifier = $it,") } ?: emptyList()) +
          // Unset is Wear's own `LocalContentColor`, which is the parent button's content colour —
          // what the canvas draws too, so only an authored colour is written.
          (colorExpression(node, "color")?.let { listOf("${pad}${INDENT}tint = $it,") }
            ?: emptyList()) +
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
        // The authored value becomes the `remember` the screen reads and writes, so the control
        // works.
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
          progressColors(node).map { "${pad}${INDENT}$it," } +
          (modifierChain(nodeId, transformedHeight(transformed))?.let {
            listOf("${pad}${INDENT}modifier = $it,")
          } ?: emptyList()) +
          listOf("${pad})")
      }
      WearScreenCodeExporter.BUTTON_GROUP -> {
        usesButtonGroup = true
        // `ButtonGroup` takes its own `SurfaceTransformation` (wear-compose-material3 1.7.0-rc01)
        // and
        // `ButtonGroupDefaults` list padding, so it scales and fades with the other rows.
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
   * A pack component — another catalog's composable admitted into this one — written from the
   * record it was projected from (callable, parameters and a proven `code.call` call site).
   *
   * Each parameter becomes:
   * - a slot: the node's children for that slot, emitted through [emit];
   * - a literal (`String`, `Boolean`, `Int`, `Long`, `Float`, `Double`): the design's value;
   * - a `Modifier`: the node's chain (tag, row treatment), omitted when empty;
   * - a `SurfaceTransformation`: the row treatment;
   * - anything else unset: omitted when defaulted, else the call site's placeholder read back
   *   from `code.call`. A required parameter with no placeholder refuses by name.
   *
   * Imports are the call site's own (`code.imports`).
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
   * The design's value for a literal parameter as a Kotlin literal, or null. Matched on the
   * qualified type, or the rendered spelling for records predating `typeFqn`.
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
   * The placeholder the proven call site wrote for each parameter, by name. `code.call` is
   * `Name(a = x, b = y)` as `ComponentSnippets` prints it, and no placeholder contains
   * `, <name> = `, so each value runs to the next parameter's marker.
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
   * The scaffold's `edgeButton` slot, always written as an `EdgeButton` (the canvas draws whatever
   * component the slot holds); a plain `Button` would not follow the bottom curve.
   */
  fun emitEdgeButton(nodeId: String, depth: Int): List<String> {
    val node = document.nodes[nodeId] ?: return refused("the edge button node `$nodeId` is missing")
    // `wear-m3/button` is still accepted for designs predating `wear-m3/edge-button`; only the
    // latter can choose a size.
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
        // `EdgeButton` is always filled, so a legacy button's colours use
        // `ButtonDefaults.buttonColors`.
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
   * A `layout/column` holding only texts: a card's or a button's lines, in order. Only a column
   * with no authored modifiers qualifies, since recognition writes the column itself into nothing
   * (its native test tag is lost).
   */
  private fun textLines(nodeId: String): List<String>? {
    val node = document.nodes[nodeId] ?: return null
    if (node.componentId != "layout/column") return null
    if (node.modifiers.isNotEmpty()) return null
    // Likewise a column with an arrangement, which the slots would drop. A plain gap is fine: the
    // component's slot spacing replaces it.
    if (
      node.string("verticalArrangement") in COLUMN_ARRANGED ||
        node.string("horizontalAlignment") in setOf("center", "end")
    ) {
      return null
    }
    val children = node.slots["children"].orEmpty()
    if (children.isEmpty()) return null
    if (children.any { document.nodes[it]?.componentId != WearScreenCodeExporter.TEXT }) return null
    return children
  }

  /**
   * The call's closing, with its content lambda when there is a body. [required] is for a component
   * whose `content` has no default (`AppCard`), where ending at `)` matches no overload.
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
    val arguments =
      listOfNotNull(modifierChain(node.id)?.let { "modifier = $it" }) +
        containerArguments(symbol, node)
    val head =
      when (arguments.size) {
        0 -> listOf("${pad}$symbol {")
        1 -> listOf("${pad}$symbol(${arguments.single()}) {")
        else -> listOf("${pad}$symbol(") + arguments.map { "${pad}${INDENT}$it," } + "${pad}) {"
      }
    return head + children.flatMap { emit(it, depth + 1) } + listOf("${pad}}")
  }

  /**
   * How a `Row`, `Column` or `Box` lays its children out, read exactly as the canvas
   * (`UiBuilderRenderer`) reads it:
   *
   * - A spacing composes with aligned arrangements via `spacedBy(space, alignment)` and is spent
   *   on the `space*` ones.
   * - A row centres vertically by default (the canvas's default, not Compose's `Top`).
   * - Anything at Compose's default is omitted.
   */
  private fun containerArguments(symbol: String, node: UiBuilderNode): List<String> =
    when (symbol) {
      "Column" ->
        listOfNotNull(
          arrangement(
              node.string("verticalArrangement"),
              node.number("verticalSpacingDp") ?: 0f,
              aligned = mapOf("center" to "CenterVertically", "bottom" to "Bottom"),
              bare = mapOf("center" to "Center", "bottom" to "Bottom"),
            )
            ?.let { "verticalArrangement = $it" },
          when (node.string("horizontalAlignment")) {
            "center" -> "CenterHorizontally"
            "end" -> "End"
            else -> null
          }?.let {
            usesAlignment = true
            "horizontalAlignment = Alignment.$it"
          },
        )
      "Row" ->
        listOfNotNull(
          arrangement(
              node.string("horizontalArrangement"),
              node.number("horizontalSpacingDp") ?: 0f,
              aligned = mapOf("center" to "CenterHorizontally", "end" to "End"),
              bare = mapOf("center" to "Center", "end" to "End"),
            )
            ?.let { "horizontalArrangement = $it" },
          when (node.string("verticalAlignment")) {
            "top" -> null
            "bottom" -> "Bottom"
            else -> "CenterVertically"
          }?.let {
            usesAlignment = true
            "verticalAlignment = Alignment.$it"
          },
        )
      else ->
        listOfNotNull(
          node
            .string("contentAlignment")
            .takeIf { it != "topStart" }
            ?.let(ScreenDocumentProjection.ALIGNMENT_MEMBERS::get)
            ?.let {
              usesAlignment = true
              "contentAlignment = Alignment.$it"
            }
        )
    }

  /**
   * One axis's `Arrangement`, or null for the start default with no gap. [aligned] maps words to
   * the `Alignment` `spacedBy` takes; [bare] to the lone `Arrangement` member.
   */
  private fun arrangement(
    value: String,
    spacing: Float,
    aligned: Map<String, String>,
    bare: Map<String, String>,
  ): String? {
    val spread =
      when (value) {
        "spaceBetween" -> "SpaceBetween"
        "spaceAround" -> "SpaceAround"
        "spaceEvenly" -> "SpaceEvenly"
        else -> null
      }
    val expression =
      when {
        spread != null -> "Arrangement.$spread"
        value in aligned ->
          if (spacing > 0f) {
            usesDp = true
            usesAlignment = true
            "Arrangement.spacedBy(${spacing.dp()}.dp, Alignment.${aligned.getValue(value)})"
          } else "Arrangement.${bare.getValue(value)}"
        spacing > 0f -> {
          usesDp = true
          "Arrangement.spacedBy(${spacing.dp()}.dp)"
        }
        else -> return null
      }
    usesArrangement = true
    return expression
  }

  /**
   * The `modifier =` chain one node carries, or null when it carries none, so untagged exports keep
   * the short form. `testTag` leads because it is identity rather than layout.
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
      // What the design authored comes before what the list adds.
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
   * The modifiers a design authored on [node] as `Modifier` calls — the canvas's
   * `uiBuilderModifier` vocabulary in plain Compose. Anything unwritable is refused by name, never
   * dropped.
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
        // The painting modifiers `wear-m3/card` and `layout/box` declare. Values are read as the
        // canvas's
        // `uiBuilderModifier` reads them; what the canvas ignores is ignored here too.
        "background" -> {
          val color = modifierColor(modifier) ?: return@mapNotNull null
          val shape = modifierShape(text("shape")) ?: return@mapNotNull null
          foundationImports += "background"
          "background($color${shape.argument()})"
        }
        "border" -> {
          val color = modifierColor(modifier) ?: return@mapNotNull null
          val width = number("widthDp") ?: return@mapNotNull null
          val shape = modifierShape(text("shape")) ?: return@mapNotNull null
          foundationImports += "border"
          "border(${dp(width)}, $color${shape.argument()})"
        }
        "clip" -> {
          val shape = modifierShape(text("shape")) ?: return@mapNotNull null
          drawImports += "clip"
          "clip(${shape.expression ?: rectangle()})"
        }
        "shadow" -> {
          val elevation = number("elevationDp") ?: return@mapNotNull null
          val shape = modifierShape(text("shape")) ?: return@mapNotNull null
          val clip = modifier["clip"]?.jsonPrimitive?.booleanOrNull
          drawImports += "shadow"
          // The canvas clips when the shadow has any elevation unless told otherwise, which is
          // also `Modifier.shadow`'s own default, so only a contrary answer is written.
          val clipArgument = if (clip != null && clip != (elevation > 0f)) ", clip = $clip" else ""
          "shadow(${dp(elevation)}, ${shape.expression ?: rectangle()}$clipArgument)"
        }
        "wrapContentSize" -> {
          val alignment = text("alignment")
          if (alignment == null) layout("wrapContentSize()")
          else {
            val member =
              ScreenDocumentProjection.ALIGNMENT_MEMBERS[alignment] ?: return@mapNotNull null
            usesAlignment = true
            layout("wrapContentSize(Alignment.$member)")
          }
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
   * A painting modifier's shape, or null when the canvas would not resolve it. A null
   * [ShapeArgument.expression] is the default rectangle. Named radii are the canvas's Wear values
   * (26dp, ¾, ½) as numbers, since Wear's `MaterialTheme.shapes` differ.
   */
  private fun modifierShape(value: String?): ShapeArgument? {
    val radius =
      when (value) {
        null,
        "" -> return ShapeArgument(null)
        "large" -> WEAR_CORNER_RADIUS_DP
        "medium" -> WEAR_CORNER_RADIUS_DP * 0.75f
        "small" -> WEAR_CORNER_RADIUS_DP * 0.5f
        else -> value.toFloatOrNull() ?: return null
      }
    foundationImports += "shape.RoundedCornerShape"
    usesDp = true
    return ShapeArgument("RoundedCornerShape(${radius.dp()}.dp)")
  }

  private class ShapeArgument(val expression: String?) {
    fun argument(): String = expression?.let { ", $it" }.orEmpty()
  }

  private fun rectangle(): String {
    usesRectangleShape = true
    return "RectangleShape"
  }

  /**
   * A modifier's `color` (a typed value): a literal or `colorToken` the canvas can draw, else null.
   */
  private fun modifierColor(modifier: JsonObject): String? {
    val color = modifier["color"] as? JsonObject ?: return null
    val value = color["value"]?.jsonPrimitive?.contentOrNull ?: return null
    return when (color["type"]?.jsonPrimitive?.contentOrNull) {
      "color" -> value.takeIf { it.startsWith("#") }?.let(::colorValueExpression)
      "colorToken" -> value.takeIf { it in MODIFIER_COLOR_TOKENS }?.let(::colorValueExpression)
      else -> null
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
   * The minimum vertical padding Wear Material 3 asks a transforming list row to keep, from the
   * component's own `…Defaults`. Components without a published minimum get none.
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
   * The modifier a list row takes, and `transformation` only where the component declares one.
   *
   * Wear Material 3 has the `transformation` parameter only on surface components (cards, buttons,
   * selection rows, `ButtonGroup`, list headers); `Slider`, `Stepper`, progress indicators,
   * `IconButton` and `TextButton` do not, so writing it there does not compile. The set below is
   * the library's signature list, pinned by [WearSurfaceTransformationTest].
   */
  private fun surfaceArguments(
    pad: String,
    nodeId: String,
    transformed: Boolean,
    symbol: String,
  ): List<String> =
    // The lists guide's order: authored modifiers, then `transformedHeight`, then minimum list
    // padding.
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
   * `colors = <Defaults>.<variant>Colors(containerColor = …, contentColor = …)` for a recoloured
   * button. The variant's own function, so it still decides every colour not named. Outlined and
   * child buttons take no `containerColor`; naming one is refused rather than dropped.
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

  /**
   * `colors = CardDefaults.…Colors(…)` for a recoloured card. `outlinedCardColors` takes no
   * container colour, so one there is refused by name, as for buttons.
   */
  private fun cardColors(
    node: UiBuilderNode,
    nodeId: String,
    pad: String,
    symbol: String,
  ): List<String> {
    val container = colorExpression(node, "containerColor")
    val content = colorExpression(node, "contentColor")
    if (container == null && content == null) return emptyList()
    if (symbol == "OutlinedCard" && container != null) {
      return refused(
        "`${node.componentId}` (node `$nodeId`) sets `containerColor` on `OutlinedCard`, which " +
          "draws no container — Wear's `CardDefaults.outlinedCardColors` takes only content colours"
      )
    }
    val function = if (symbol == "OutlinedCard") "outlinedCardColors" else "cardColors"
    listPaddingDefaults += "CardDefaults"
    val arguments =
      listOfNotNull(
        container?.let { "containerColor = $it" },
        content?.let { "contentColor = $it" },
      )
    return listOf("${pad}colors = CardDefaults.$function(${arguments.joinToString(", ")}),")
  }

  /**
   * A progress indicator's `colors`, from the two roles it draws: the indicator and its track.
   * Every Wear indicator takes the same `ProgressIndicatorColors`, so one call serves all four.
   */
  private fun progressColors(node: UiBuilderNode): List<String> {
    val arguments =
      listOfNotNull(
        colorExpression(node, "indicatorColor")?.let { "indicatorColor = $it" },
        colorExpression(node, "trackColor")?.let { "trackColor = $it" },
      )
    if (arguments.isEmpty()) return emptyList()
    listPaddingDefaults += "ProgressIndicatorDefaults"
    return listOf("colors = ProgressIndicatorDefaults.colors(${arguments.joinToString(", ")})")
  }

  private fun refused(reason: String): List<String> {
    refusals += reason
    return emptyList()
  }

  /**
   * A call to the flexpress-generated composable for [node]'s text (font, text and axes are the
   * declaration's; see [WearScreenCodeExporter.export]). Axes are held at their values; bound or
   * computed axes are refused, and the text must be literal.
   */
  private fun variableFontText(
    node: UiBuilderNode,
    nodeId: String,
    pad: String,
    transformed: Boolean,
  ): List<String> {
    val label = "`${node.componentId}` (node `$nodeId`)"
    val fontName = node.stringOrNull("font")?.takeIf(String::isNotEmpty)
    val font =
      if (fontName == null) VariableFontText.Font.RobotoFlex
      else
        VariableFontText.Font.fromWire(fontName)
          ?: return refused("$label names the font `$fontName`, which is not one it draws in")
    val text = node.stringOrNull("text") ?: return refused("$label has no literal text to draw")
    val bound =
      VariableFontText.AXIS_PROPERTIES.filter { it in node.properties && node.number(it) == null }
    if (bound.isNotEmpty()) {
      return refused(
        "$label binds ${bound.joinToString { "`$it`" }}: a Wear screen exports a variable font " +
          "text's axes as literals until its generator declares the design's state"
      )
    }
    val location =
      VariableFontText.AXIS_PROPERTIES.mapNotNull { property ->
          node.number(property)?.let { (font.axis(property)?.tag ?: property) to it }
        }
        .toMap()
    val spec =
      VariableFontText.Spec(font, text, emptyList(), location, VariableFontText.Target.COMPOSE_UI)
    val problems = VariableFontText.problems(spec)
    if (problems.isNotEmpty()) return problems.flatMap { refused("$label: $it") }
    variableFontSpecs += spec
    val name = with(VariableFontText) { requests(variableFontSpecs).of(spec).functionName }
    textImports += "sp"
    val arguments =
      listOf("fontSize = ${(node.number("fontSizeSp")?.takeIf { it > 0f } ?: 32f).dp()}.sp") +
        listOfNotNull(
          modifierChain(nodeId, textPropertyModifier(node), transformedHeight(transformed))?.let {
            "modifier = $it"
          },
          colorExpression(node, "color")?.let { "color = $it" },
        )
    return listOf("${pad}$name(") + arguments.map { "${pad}${INDENT}$it," } + listOf("${pad})")
  }

  /**
   * `CheckboxButton`, `SwitchButton` and `RadioButton`: one labelled full-width row shape with
   * three controls, differing only in composable and flag name.
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
      documentFlag(node, flag)
        ?: rememberedBoolean(
          nodeId,
          flag.replaceFirstChar(Char::uppercaseChar),
          node.boolean(flag) ?: false,
        )
    val secondary = node.string("secondaryLabel")
    return listOf("${pad}$symbol(") +
      listOf(
        "${pad}${INDENT}$flag = $state,",
        // `onSelect` takes no argument; `onCheckedChange` receives the new value.
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
   * A Wear dialog, emitted beside the `ScreenScaffold` (as upstream samples do), since dialogs are
   * full-screen states with a `visible` flag. This is what the scaffold's `overlays` slot means.
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
          // Wear's own confirm/dismiss buttons with the guidance's icons and sizes. A filled slot
          // is the default button, matching the canvas (which reads presence, not content). Both
          // dismiss; the app wires its own action.
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
   * State names already declared, so ids that fold to one name get distinct locals (`2`, `3`, … in
   * document order) instead of a redeclaration.
   */
  private val stateNames = mutableSetOf<String>()

  /**
   * Every design variable's exported name, reserved so node-local names cannot take it and make
   * state sharing depend on traversal order.
   */
  private val documentStateNames: Set<String> =
    document.stateVariables.keys.mapTo(mutableSetOf(), ::exportedStateIdentifier)

  private fun uniqueStateName(nodeId: String, role: String): String {
    val base = nodeId.stateIdentifier(role)
    var name = base
    var index = 2
    while (name in documentStateNames || !stateNames.add(name)) name = "$base${index++}"
    return name
  }

  private fun rememberedFloat(nodeId: String, role: String, initial: Float): String {
    val name = uniqueStateName(nodeId, role)
    usesRememberState = true
    rememberedState += "var $name by remember { mutableFloatStateOf(${initial.dp()}f) }"
    return name
  }

  /** Document state variables already declared, by exported identifier. */
  private val documentState = mutableSetOf<String>()

  /**
   * The design state variable [flag] is two-way bound to (declared once at the top), or null when
   * [flag] is not a whole read of a non-null `bool` with a Boolean initial value. Controls bound to
   * one variable share it, as on the canvas; see [TwoWayStateBinding].
   */
  private fun documentFlag(node: UiBuilderNode, flag: String): String? {
    val variable = TwoWayStateBinding.boundVariable(node.properties[flag]) ?: return null
    val declaration = document.stateVariables[variable] as? JsonObject ?: return null
    val initial = (declaration["initialValue"] as? JsonPrimitive)?.takeUnless { it.isString }
    val valueType = (declaration["valueType"] as? JsonPrimitive)?.contentOrNull
    if ((declaration["nullable"] as? JsonPrimitive)?.booleanOrNull == true) return null
    if (valueType != null && valueType != "bool") return null
    val value = initial?.booleanOrNull ?: return null
    val name = exportedStateIdentifier(variable)
    if (name !in documentState) {
      // Reserved from the node-locals up front (documentStateNames), so this name is free.
      stateNames += name
      documentState += name
      usesRememberState = true
      rememberedState += "var $name by remember { mutableStateOf($value) }"
    }
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
    // Unconditional: `AppScaffold` is always written, with or without a `timeText`.
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
    fontImports.forEach(::add)
    layoutImports.forEach { add("androidx.compose.foundation.layout.$it") }
    drawImports.forEach { add("androidx.compose.ui.draw.$it") }
    if (usesZIndex) add("androidx.compose.ui.zIndex")
    foundationImports.forEach { add("androidx.compose.foundation.$it") }
    if (usesRectangleShape) add("androidx.compose.ui.graphics.RectangleShape")
    if (timeText) add("androidx.wear.compose.material3.TimeText")
    if (timeText) add("androidx.wear.compose.material3.timeTextCurvedText")
    if (usesListHeader) add("androidx.wear.compose.material3.ListHeader")
    if (usesCard) add("androidx.wear.compose.material3.TitleCard")
    usesPlainCard.forEach { add("androidx.wear.compose.material3.$it") }
    listPaddingDefaults.forEach { add("androidx.wear.compose.material3.$it") }
    add("androidx.wear.compose.material3.lazy.rememberTransformationSpec")
    add("androidx.wear.compose.material3.lazy.transformedHeight")
    // `androidx.wear.compose:compose-ui-tooling` holds the multipreview. Only imported when
    // previews are written, since neither it nor `preview-annotations` is on a catalog's runtime
    // bundle.
    if (previews) {
      add("androidx.wear.compose.ui.tooling.preview.WearPreviewDevices")
      // The parity capture's annotations: platform `Preview` plus compose-ai-tools'
      // `preview-annotations` scrolling pair.
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
   * [role] as a member of Wear's `Typography`. Material 3's `headline*` roles have no Wear
   * counterpart, so they map to the title role `wearTextStyle` draws on the canvas.
   */
  private fun wearTypographyRole(role: String): String =
    when (role) {
      "headlineLarge" -> "titleLarge"
      "headlineMedium" -> "titleMedium"
      "headlineSmall" -> "titleSmall"
      else -> role
    }

  private fun textArguments(node: UiBuilderNode): List<String> = buildList {
    val font = FontSettings.composeArguments(document, node.id)
    fontImports += font.imports
    val featureCopy = font.styleCopy
    val role = node.stringOrNull("style")?.takeIf(String::isNotEmpty)
    if (role != null) {
      usesMaterialTheme = true
      add("style = MaterialTheme.typography.${wearTypographyRole(role)}${featureCopy.orEmpty()}")
    } else if (featureCopy != null) {
      fontImports += "androidx.wear.compose.material3.LocalTextStyle"
      add("style = LocalTextStyle.current$featureCopy")
    }
    colorExpression(node, "color")?.let { add("color = $it") }
    font.fontFamily?.let { add("fontFamily = $it") }
    font.fontWeight?.let { add("fontWeight = $it") }
    node
      .stringOrNull("fontWeight")
      ?.takeUnless { font.overridesWeight }
      ?.let {
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
   * The two `wear-m3/text` properties that are its parent's business: `alignment` in a `Box` and
   * `weight` in a `Row`/`Column`, written as scoped modifiers; ignored elsewhere, as on the canvas.
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
   * A colour property as Wear Compose source: a theme role is `MaterialTheme.colorScheme.<role>`;
   * a `#RRGGBB` / `#AARRGGBB` literal is a `Color(0x…)`.
   */
  private fun colorExpression(node: UiBuilderNode, name: String): String? {
    val property = node.properties[name] as? JsonObject ?: return null
    val value =
      property["value"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty) ?: return null
    return colorValueExpression(value)
  }

  /** [colorExpression] for a value already read, wherever it came from. */
  private fun colorValueExpression(value: String): String {
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
      // Shared token vocabulary: Wear's `ColorScheme` lacks `surface` and
      // `surfaceContainerHighest`, so each maps to the role the canvas draws it as.
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
   * A label's `maxLines` and `overflow`, shared by both header emitters (upstream headers take a
   * content lambda, not a string).
   */
  private fun UiBuilderNode.labelArguments(): List<String> = buildList {
    // Clamped as the canvas does: `Text` throws below one.
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
   * `LocalDate.parse("…")`, or today. Parsed in the generated code so a bad date fails visibly in
   * the author's project rather than being silently replaced.
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

    /** The canvas's corner radius on a Wear screen: Wear's large card corner. */
    const val WEAR_CORNER_RADIUS_DP = 26f

    /** The colour tokens the canvas resolves inside a modifier; anything else it ignores. */
    val MODIFIER_COLOR_TOKENS =
      setOf(
        "background",
        "surface",
        "surfaceContainer",
        "surfaceContainerLow",
        "surfaceContainerHigh",
        "surfaceContainerHighest",
        "primary",
        "onPrimary",
        "secondary",
        "onSecondary",
        "tertiary",
        "onTertiary",
        "onSurface",
        "onSurfaceVariant",
        "outlineVariant",
        "transparent",
      )

    /** The `verticalArrangement` words that move a column's children off the top. */
    val COLUMN_ARRANGED = setOf("center", "bottom", "spaceBetween", "spaceAround", "spaceEvenly")

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
 * A node id as the Kotlin name of the state it holds: `error-dialog` + `Visible` →
 * `errorDialogVisible`. The id is unique and traceable; the role suffix avoids keyword
 * collisions.
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
 * A Kotlin `"…"` literal, escaping quotes, line breaks and `$` — the same set as
 * `RemoteContentEmitter.escaped`.
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
