@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.FontSettings
import ee.schimke.composeai.uibuilder.export.ThemeTextStyle
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import ee.schimke.composeai.uibuilder.export.VariableFontText
import ee.schimke.composeai.uibuilder.export.WearScreenTheme
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCardinalityV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The values `wear-m3` lets a design re-skin, read by the editor's Theme panel (`DesignTokens.from`
 * in `:ui-builder`, unreachable here — hence literal keys). Every default is null, meaning "keep
 * Wear's own". Theme tokens land on `ScreenScaffold`; component tokens on every node of the
 * components they name.
 */
internal fun wearDesignTokens(): JsonObject {
  val scaffold = "wear-m3/screen-scaffold"
  fun theme(id: String, label: String, property: String) = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("label", JsonPrimitive(label))
    put("kind", JsonPrimitive("color"))
    put("default", kotlinx.serialization.json.JsonNull)
    putJsonArray("theme") {
      add(
        buildJsonObject {
          put("component", JsonPrimitive(scaffold))
          put("property", JsonPrimitive(property))
        }
      )
    }
  }
  fun components(
    id: String,
    label: String,
    kind: String,
    targets: List<Pair<String, String>>,
    range: Pair<Int, Int>? = null,
  ) = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("label", JsonPrimitive(label))
    put("kind", JsonPrimitive(kind))
    put("default", kotlinx.serialization.json.JsonNull)
    range?.let { (minimum, maximum) ->
      put("minimum", JsonPrimitive(minimum))
      put("maximum", JsonPrimitive(maximum))
    }
    putJsonArray("components") {
      targets.forEach { (component, property) ->
        add(
          buildJsonObject {
            put("component", JsonPrimitive(component))
            put("property", JsonPrimitive(property))
          }
        )
      }
    }
  }
  val buttons = listOf("wear-m3/button", "wear-m3/icon-button", "wear-m3/edge-button")
  return buildJsonObject {
    putJsonArray("tokens") {
      add(theme("color.primary", "Primary", "themePrimaryColor"))
      add(theme("color.onPrimary", "On primary", "themeOnPrimaryColor"))
      add(theme("color.primaryContainer", "Primary container", "themePrimaryContainerColor"))
      add(theme("color.secondary", "Secondary", "themeSecondaryColor"))
      add(theme("color.tertiary", "Tertiary", "themeTertiaryColor"))
      add(theme("color.surfaceContainer", "Surface container", "themeSurfaceContainerColor"))
      add(theme("color.onSurface", "On surface", "themeOnSurfaceColor"))
      add(theme("color.background", "Background", "themeBackgroundColor"))
      add(theme("color.error", "Error", "themeErrorColor"))
      add(
        components(
          "space.list",
          "List spacing",
          "number",
          listOf(
            "wear-m3/transforming-lazy-column" to "verticalSpacingDp",
            "layout/column" to "verticalSpacingDp",
          ),
          range = 0 to 24,
        )
      )
      add(
        components(
          "space.row",
          "Row spacing",
          "number",
          listOf("layout/row" to "horizontalSpacingDp"),
          range = 0 to 24,
        )
      )
      add(
        components(
          "size.icon",
          "Icon size",
          "number",
          listOf("wear-m3/icon" to "sizeDp"),
          range = 12 to 48,
        )
      )
      add(
        components(
          "color.buttonContainer",
          "Button container",
          "color",
          buttons.map { it to "containerColor" },
        )
      )
      add(
        components(
          "color.buttonContent",
          "Button content",
          "color",
          buttons.map { it to "contentColor" },
        )
      )
      add(
        components(
          "color.cardContainer",
          "Card container",
          "color",
          listOf("wear-m3/card" to "containerColor"),
        )
      )
    }
  }
}

/**
 * How the builder's insert panel shelves `wear-m3`, and which property carries a component's
 * variants. Wear's own families lead (screen, list); `wear-m3/edge-button`'s `size` is a variant.
 * Absent as variants: lazy column `transformation`, slider `segmented`, `iconKey` and text
 * `style`, which are properties rather than kinds of component.
 */
internal fun wearComponentMenu(): JsonObject {
  val shelves =
    listOf(
      "Screens" to listOf("wear-m3/screen-scaffold"),
      "Layout" to listOf("layout/box", "layout/column", "layout/row"),
      "Lists" to
        listOf(
          "wear-m3/transforming-lazy-column",
          "wear-m3/list-header",
          "wear-m3/list-sub-header",
        ),
      "Actions" to
        listOf(
          "wear-m3/button",
          "wear-m3/text-button",
          "wear-m3/icon-button",
          "wear-m3/edge-button",
          "wear-m3/button-group",
        ),
      "Selection" to
        listOf(
          "wear-m3/checkbox-button",
          "wear-m3/switch-button",
          "wear-m3/radio-button",
          "wear-m3/slider",
          "wear-m3/stepper",
          "wear-m3/date-picker",
          "wear-m3/time-picker",
        ),
      "Containment" to
        listOf(
          "wear-m3/card",
          "wear-m3/alert-dialog",
          "wear-m3/confirmation-dialog",
          "wear-m3/open-on-phone-dialog",
        ),
      "Communication" to listOf("wear-m3/progress-indicator"),
      "Content" to listOf("wear-m3/text", VariableFontText.WEAR_ID, "wear-m3/icon", "asset/image"),
      // The "Embedded" shelf held the three Remote Compose seams and is gone with them. It comes
      // back when they do; a shelf with nothing on it is a heading an author opens for nothing.
    )
  val variantProperties =
    mapOf(
      "wear-m3/card" to "variant",
      "wear-m3/button" to "variant",
      "wear-m3/text-button" to "variant",
      "wear-m3/icon-button" to "variant",
      "wear-m3/edge-button" to "size",
      "wear-m3/progress-indicator" to "variant",
      "wear-m3/confirmation-dialog" to "variant",
      "wear-m3/date-picker" to "type",
      "wear-m3/time-picker" to "type",
    )
  return buildJsonObject {
    putJsonArray("groupOrder") { shelves.forEach { (name, _) -> add(JsonPrimitive(name)) } }
    putJsonObject("components") {
      shelves.forEach { (name, componentIds) ->
        componentIds.forEach { componentId ->
          putJsonObject(componentId) {
            put("group", JsonPrimitive(name))
            variantProperties[componentId]?.let { put("variantProperty", JsonPrimitive(it)) }
          }
        }
      }
    }
  }
}

/**
 * The Wear screen host's authored parameters. Unlike the widget container, `ScreenScaffold` is
 * emitted (the author calls it); only its drawing is approximated on the canvas. The screen
 * diameter is absent because it is the document's own frame.
 */
private fun wearScreenScaffoldProperties(): List<PropertyCapabilityV1> =
  listOf(
    PropertyCapabilityV1.Builder("timeText", JsonPrimitive("string"))
      .also {
        it.notes =
          "The curved status strip's text. Frozen rather than live: a design whose render changed " +
            "every minute could not be diffed. Empty draws no strip, which is `ScreenScaffold` " +
            "without a `timeText` argument."
      }
      .build(),
    PropertyCapabilityV1.Builder("scrollIndicator", JsonPrimitive("boolean"))
      .also {
        it.notes =
          "Whether the generated screen gives `ScreenScaffold` a scroll indicator. The canvas draws " +
            "none either way: an indicator shows where a viewport sits in the content, and the " +
            "long-screenshot extent has no viewport. The real capture agrees — a `ScrollMode.LONG` " +
            "render sets `LocalScrollCaptureInProgress` and the emitted scaffold suppresses the " +
            "indicator while it is set, which is what keeps a stitched capture free of the dashes " +
            "an indicator drawn per frame leaves down the edge."
      }
      .build(),
    PropertyCapabilityV1.Builder("background", JsonPrimitive("string"))
      .also {
        it.notes =
          "The screen's background. Wear is dark-first, so it defaults to the Wear Material 3 " +
            "`background` — pure black — rather than to the editor theme's surface."
      }
      .build(),
  ) +
    // One Wear colour role per property; the canvas and the generated
    // `MaterialTheme(colorScheme = …copy(…))` both apply them to the whole screen.
    WearScreenTheme.ROLES.map { role ->
      wearString(
        WearScreenTheme.property(role),
        notes =
          "Re-skins the screen's `$role` role for everything inside it. A colour, written as " +
            "{\"type\":\"color\",\"value\":\"#RRGGBB\"}, or a colourToken naming another role of " +
            "the stock Wear scheme. Unset keeps Wear's own.",
      )
    } +
    // The screen's typefaces, one per group of Wear type-scale roles, drawn and generated the same
    // way as the colours above (yschimke/wear-m3-catalog#684).
    ThemeTypefaces.WEAR_GROUPS.map { group ->
      wearString(group.property, notes = ThemeTypefaces.notes(group, wear = true))
    } +
    // The role text with no `style` of its own is set in; see [ThemeTextStyle].
    wearString(
      ThemeTextStyle.PROPERTY,
      notes = ThemeTextStyle.notes(wear = true),
      allowed = ThemeTextStyle.WEAR_ROLES,
    )

// Shared property vocabulary: Wear and Material 3 components state their own properties from
// these builders; no Wear component is derived from a Material 3 one.

/** A free string, or a closed set when [allowed] is given. */
private fun wearString(
  name: String,
  allowed: List<String> = emptyList(),
  required: Boolean = false,
  notes: String? = null,
): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(name, JsonPrimitive("string"))
    .also {
      it.required = required
      it.allowedValues = allowed.map(::JsonPrimitive)
      notes?.let { note -> it.notes = note }
    }
    .build()

/** A number: a dp, a size, a spacing. A constant rather than a value a state drives. */
private fun wearNumber(name: String, notes: String? = null): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(name, JsonPrimitive("number"))
    .also { notes?.let { note -> it.notes = note } }
    .build()

/** A number a state variable can drive, which arrives as a wrapper object. */
/**
 * The action a click runs: an object or null (a binding), as the mobile catalog declares it.
 */
private fun wearClickAction(): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(
      "onClickAction",
      JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))),
    )
    .build()

private fun wearBindableNumber(name: String, notes: String? = null): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(
      name,
      JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("object"))),
    )
    .also { it.notes = notes }
    .build()

/** A count, where a fractional value is meaningless. */
private fun wearInteger(name: String, notes: String? = null): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(name, JsonPrimitive("integer"))
    .also { notes?.let { note -> it.notes = note } }
    .build()

/** A boolean, or an object when a state binding is written into it. */
private fun wearBoolean(name: String, notes: String? = null): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(name, JsonPrimitive("boolean"))
    .also { notes?.let { note -> it.notes = note } }
    .build()

/** A boolean a state variable can drive, which arrives as a wrapper object. */
private fun wearBindableBoolean(name: String, notes: String? = null): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(
      name,
      JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("object"))),
    )
    .also { notes?.let { note -> it.notes = note } }
    .build()

/** A colour, in either of the two shapes the colour token vocabulary allows. */
private fun wearColor(name: String = "color"): PropertyCapabilityV1 =
  wearString(
    name,
    notes =
      "A colour, written as {\"type\":\"color\",\"value\":\"#RRGGBB\"} or as " +
        "{\"type\":\"colorToken\",\"value\":\"primary\"} naming one of " +
        "statusSemantics.colorTokens. A string wrapper, or a role the canvas does not draw, is " +
        "rejected rather than guessed at.",
  )

/** What an axis property of a variable font text says: its range, and how it is set. */
private fun variableAxisNotes(property: String): String {
  val ranges =
    VariableFontText.Font.entries.mapNotNull { font ->
      font.axis(property)?.let { "${it.min.text()}–${it.max.text()} in ${font.family}" }
    }
  val axis = VariableFontText.Font.entries.firstNotNullOf { it.axis(property) }
  return "The `${axis.tag}` axis: ${ranges.joinToString(", ")}. A literal holds it; a `state` " +
    "read of a decimal variable animates it. Unset is the font's own ${axis.default.text()}."
}

private fun Float.text(): String = if (this == toInt().toFloat()) toInt().toString() else toString()

/** One content slot holding a single child, for the components whose API takes one lambda. */
private fun singleSlot(name: String, traits: List<String>, min: Int = 0): SlotCapabilityV1 =
  SlotCapabilityV1.Builder(
      name,
      SlotCardinalityV1.Builder()
        .also {
          it.min = min
          it.max = 1
        }
        .build(),
      true,
    )
    .also {
      it.acceptedRoles = emptyList()
      it.acceptedTraits = traits
    }
    .build()

/** A slot holding any number of children. */
private fun manySlot(
  name: String,
  traits: List<String>,
  roles: List<String> = emptyList(),
  min: Int = 0,
): SlotCapabilityV1 =
  SlotCapabilityV1.Builder(
      name,
      SlotCardinalityV1.Builder()
        .also {
          it.min = min
          it.max = null
        }
        .build(),
      true,
    )
    .also {
      it.acceptedRoles = roles
      it.acceptedTraits = traits
    }
    .build()

/**
 * The modifiers the canvas applies to a node that draws no surface of its own (text, a button, a
 * list). No `fillMaxSize`: these nodes size themselves.
 */
private val WEAR_MODIFIERS =
  listOf(
    "size",
    "fillMaxWidth",
    "padding",
    "alpha",
    "offset",
    "rotate",
    "scale",
    "zIndex",
    "testTag",
    "width",
    "height",
    "widthIn",
    "heightIn",
    "aspectRatio",
    "align",
    "alignHorizontal",
    "alignVertical",
    "weight",
  )

/**
 * What a button that can sit in a `ButtonGroup` takes: `weight` (`ButtonGroupScope.weight`) and
 * the few modifiers that size or tag it.
 */
private val WEAR_BUTTON_GROUP_CHILD_MODIFIERS =
  listOf("weight", "size", "width", "padding", "testTag")

/**
 * What a node that paints its own surface can take as well: the clip and the four painting
 * modifiers, which a `Text` or a list has nothing to apply them to.
 */
private val WEAR_SURFACE_MODIFIERS =
  listOf(
    "size",
    "fillMaxWidth",
    "padding",
    "clip",
    "alpha",
    "offset",
    "rotate",
    "scale",
    "zIndex",
    "testTag",
    "width",
    "height",
    "widthIn",
    "heightIn",
    "aspectRatio",
    "background",
    "border",
    "shadow",
    "wrapContentSize",
    "align",
    "alignHorizontal",
    "alignVertical",
    "weight",
  )

/** Where a node sits in a parent that gives it more room than it needs. */
private val WEAR_ALIGNMENTS =
  listOf(
    "topStart",
    "topCenter",
    "topEnd",
    "centerStart",
    "center",
    "centerEnd",
    "bottomStart",
    "bottomCenter",
    "bottomEnd",
  )

/** The fifteen type-scale roles both libraries publish, under the names they share. */
private val WEAR_TYPE_ROLES =
  listOf(
    "displayLarge",
    "displayMedium",
    "displaySmall",
    "headlineLarge",
    "headlineMedium",
    "headlineSmall",
    "titleLarge",
    "titleMedium",
    "titleSmall",
    "bodyLarge",
    "bodyMedium",
    "bodySmall",
    "labelLarge",
    "labelMedium",
    "labelSmall",
  )

/**
 * The label a header draws, and its truncation: the `Text` inside upstream's content lambda,
 * shared by `ListHeader` and `ListSubHeader`.
 */
private fun wearLabelProperties(): List<PropertyCapabilityV1> =
  listOf(
    wearString("text", required = true, notes = "The label."),
    wearInteger("maxLines", "How many lines the label may occupy before it truncates."),
    wearString(
      "overflow",
      allowed = listOf("clip", "ellipsis", "visible"),
      notes = "What a truncated label does at its edge.",
    ),
  )

/** `TransformingLazyColumn`'s authored parameters, minus the ones its state object carries. */
private fun wearTransformingLazyColumnProperties(): List<PropertyCapabilityV1> =
  listOf(
    wearNumber(
      "verticalSpacingDp",
      "`Arrangement.spacedBy` between items; 4dp is the Wear list default.",
    ),
    wearString(
      "transformation",
      allowed = listOf("spec", "none"),
      notes =
        "Whether each item carries `SurfaceTransformation(spec)` and `transformedHeight`. The " +
          "canvas draws both in a bounded frame and neither at the extent — see the wasm note — " +
          "and the generated Kotlin emits them either way.",
    ),
  )

/**
 * The Wear Material 3 components with no Material 3 counterpart at all (labelled full-width
 * rows, segmented slider, pickers).
 *
 * Nothing here is a lookalike: `ee.schimke.wearcmp:*` is Wear Compose compiled for Compose
 * Multiplatform, so the canvas calls the same composables the generated screen names. See
 * `wearM3Catalog` for the three the canvas still draws itself.
 */
private fun wearOnlyComponents(
  canvasSupported: WasmCapabilityV1,
  noStructuredSvg: SvgCapabilityV1?,
  iconKeys: List<JsonElement>,
): List<ComponentCapabilityV1> {
  /**
   * The note every component in this group carries. [drawnBy] must match the renderer's actual
   * branch.
   */
  fun note(
    composable: String,
    extra: String = "",
    drawnBy: String =
      "Wear Compose itself, through the Compose Multiplatform build of the library the canvas " +
        "links instead of the Android AAR",
  ) =
    "Wear Material 3's `$composable`." +
      (if (extra.isEmpty()) "" else " $extra") +
      " The canvas draws it with $drawnBy; the generated screen and the native render use " +
      "`androidx.wear.compose` itself."

  fun component(
    componentId: String,
    displayName: String,
    composable: String,
    role: String,
    traits: List<String>,
    properties: List<PropertyCapabilityV1> = emptyList(),
    slots: List<SlotCapabilityV1> = emptyList(),
    extra: String = "",
    drawnBy: String? = null,
    modifierCapabilities: List<String> = emptyList(),
  ) =
    ComponentCapabilityV1.Builder(
        componentId,
        displayName,
        role,
        canvasSupported
          .newBuilder()
          .also {
            it.notes =
              if (drawnBy == null) note(composable, extra) else note(composable, extra, drawnBy)
          }
          .build(),
      )
      .also {
        it.traits = traits
        it.slots = slots
        it.properties = properties
        // No modifier vocabulary by default: Wear controls are laid out by the list and scaffold.
        // The exceptions pass one (e.g. an icon button's `weight` in a `ButtonGroup`).
        it.modifierCapabilities = modifierCapabilities
        it.code = null
        it.svg =
          noStructuredSvg
            ?.newBuilder()
            ?.also {
              it.notes =
                "No structured SVG from this catalog's own record: `$composable` has no " +
                  "per-component call site to walk — `WearScreenCodeExporter` writes the whole " +
                  "screen — so the lane has nothing to emit from. The canvas draws the component " +
                  "itself."
            }
            ?.build()
      }
      .build()

  /** `label` and `secondaryLabel`, which is the shape every Wear selection control shares. */
  fun labelled(secondary: Boolean = true) = buildList {
    add(
      PropertyCapabilityV1.Builder("label", JsonPrimitive("string"))
        .also {
          it.required = true
          it.notes =
            "The row's primary label. Wear's selection controls are labelled rows, not bare boxes."
        }
        .build()
    )
    if (secondary) {
      add(
        PropertyCapabilityV1.Builder("secondaryLabel", JsonPrimitive("string"))
          .also {
            it.notes =
              "The second line, where there is one. Empty emits no `secondaryLabel` argument."
          }
          .build()
      )
    }
  }

  /**
   * A checked/selected flag, drivable from a state variable exactly as `m3/checkbox.checked` is.
   */
  fun flag(name: String, notes: String) =
    PropertyCapabilityV1.Builder(
        name,
        JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("object"))),
      )
      .also { it.notes = notes }
      .build()

  fun enum(name: String, values: List<String>, notes: String, required: Boolean = false) =
    PropertyCapabilityV1.Builder(name, JsonPrimitive("string"))
      .also {
        it.required = required
        it.allowedValues = values.map(::JsonPrimitive)
        it.notes = notes
      }
      .build()

  fun number(name: String, notes: String) =
    PropertyCapabilityV1.Builder(
        name,
        JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("object"))),
      )
      .also { it.notes = notes }
      .build()

  fun text(name: String, notes: String, required: Boolean = false) =
    PropertyCapabilityV1.Builder(name, JsonPrimitive("string"))
      .also {
        it.required = required
        it.notes = notes
      }
      .build()

  val listItem = listOf("ListItem", "WearListContent")

  return listOf(
    component(
      componentId = "wear-m3/icon",
      displayName = "Icon",
      composable = "Icon",
      role = "Leaf",
      traits = listOf("Adornment"),
      properties =
        listOf(
          PropertyCapabilityV1.Builder("iconKey", JsonPrimitive("string"))
            .also {
              it.required = true
              it.allowedValues = iconKeys
              it.notes =
                "A Material icon key, resolved to `Icons.…` by the same table `m3/icon` uses. The " +
                  "vectors are `androidx.compose.material.icons`, which both platforms share, so " +
                  "this key means the same thing on a watch as on a phone."
            }
            .build(),
          number("sizeDp", "The icon's box. Wear's own default is 24dp inside a button."),
          text(
            "contentDescription",
            "What a screen reader says for the icon. Leave it empty beside a label, which already " +
              "names the action; set it when the icon is the whole button — an icon button's only " +
              "accessible name is this.",
          ),
          // `Icon`'s `tint`. Unset inherits the parent's content colour, which is what a button's
          // `contentColor` reaches it through.
          wearColor(),
        ),
      // The one component here not drawn with Wear Compose: `BuilderIcon` owns this build's icon
      // key table, tint resolution and structured-path SVG export.
      drawnBy =
        "the canvas's own icon drawer, not Wear's `Icon` — an icon is a tinted vector on both " +
          "platforms, and that drawer owns this build's key table, tint resolution and " +
          "structured-path export",
    ),
    component(
      componentId = "wear-m3/icon-button",
      displayName = "Icon button",
      composable = "IconButton",
      role = "Container",
      traits = listOf("Action", "ListItem"),
      slots = listOf(singleSlot("content", listOf("Adornment"), min = 1)),
      properties =
        listOf(
          enum(
            "variant",
            listOf("filled", "filled-tonal", "filled-variant", "outlined", "standard"),
            "Which of the five `IconButton` overloads is written: `FilledIconButton`, " +
              "`FilledTonalIconButton`, `FilledVariantIconButton`, `OutlinedIconButton` or plain " +
              "`IconButton`. A variant selects the composable rather than tinting one, the way " +
              "`m3/button`'s style does.",
          ),
          // Recolours the variant's palette
          // (`IconButtonDefaults.filledIconButtonColors(containerColor = …)`), so the variant still
          // decides shape and disabled colours.
          wearColor("containerColor"),
          wearColor("contentColor"),
        ),
      modifierCapabilities = WEAR_BUTTON_GROUP_CHILD_MODIFIERS,
    ),
    component(
      componentId = "wear-m3/text-button",
      displayName = "Text button",
      composable = "TextButton",
      role = "Container",
      traits = listOf("Action", "ListItem"),
      slots = listOf(singleSlot("content", listOf("AnyContent"), min = 1)),
      properties =
        listOf(
          enum(
            "variant",
            listOf("filled", "filled-tonal", "filled-variant", "outlined", "standard"),
            "As `wear-m3/icon-button`'s: the variant names the composable — `FilledTextButton` " +
              "and the rest — rather than recolouring one.",
          )
        ),
      modifierCapabilities = WEAR_BUTTON_GROUP_CHILD_MODIFIERS,
    ),
    component(
      componentId = "wear-m3/list-sub-header",
      displayName = "List sub-header",
      composable = "ListSubHeader",
      role = "Leaf",
      traits = listItem,
      // The same label properties as the header: upstream takes a content lambda, and undeclared
      // properties are refused by the service.
      properties = wearLabelProperties(),
      extra =
        "The second level of list heading, under `wear-m3/list-header`: smaller, start-aligned, " +
          "and the one used to divide a long list into named runs.",
    ),
    component(
      componentId = "wear-m3/checkbox-button",
      displayName = "Checkbox button",
      composable = "CheckboxButton",
      role = "Leaf",
      traits = listItem + "Selection",
      properties =
        labelled() + flag("checked", "Whether the box is ticked. Bindable to a state variable."),
      extra =
        "A full-width labelled row with the checkbox at its end — not the mobile 20dp square, " +
          "which is why Material 3's `Checkbox` could never stand in for it.",
    ),
    component(
      componentId = "wear-m3/switch-button",
      displayName = "Switch button",
      composable = "SwitchButton",
      role = "Leaf",
      traits = listItem + "Selection",
      properties =
        labelled() + flag("checked", "Whether the switch is on. Bindable to a state variable."),
      extra = "The same labelled row as `wear-m3/checkbox-button`, with a switch as its control.",
    ),
    component(
      componentId = "wear-m3/radio-button",
      displayName = "Radio button",
      composable = "RadioButton",
      role = "Leaf",
      traits = listItem + "Selection",
      properties =
        labelled() + flag("selected", "Whether this row is the chosen one of its group."),
      extra = "The same labelled row again, with a radio control and single-choice semantics.",
    ),
    component(
      componentId = "wear-m3/slider",
      displayName = "Slider",
      composable = "Slider",
      role = "Leaf",
      traits = listItem,
      properties =
        listOf(
          number("value", "The current value, between `valueFrom` and `valueTo`."),
          number("valueFrom", "The low end of the range. 0 when absent."),
          number("valueTo", "The high end of the range. 1 when absent."),
          number("steps", "How many discrete stops sit between the ends. 0 is continuous."),
          enum(
            "segmented",
            listOf("segmented", "continuous"),
            "Whether the track is drawn as separated segments, which is what Wear's stepped " +
              "slider looks like.",
          ),
        ),
      extra =
        "Wear's slider is a row with a decrement and an increment button around the track, not a " +
          "bare thumb on a line.",
    ),
    component(
      componentId = "wear-m3/stepper",
      displayName = "Stepper",
      composable = "Stepper",
      role = "Container",
      traits = listOf("ScreenContent"),
      slots = listOf(singleSlot("content", listOf("AnyContent"), min = 1)),
      properties =
        listOf(
          number("value", "The current value."),
          number("valueFrom", "The low end of the range. 0 when absent."),
          number("valueTo", "The high end of the range. 1 when absent."),
          number("steps", "How many discrete stops sit between the ends. 0 is continuous."),
        ),
      extra =
        "A full-screen control: increment and decrement buttons at the top and bottom of the " +
          "round display with the current value between them. It is not a list row, which is why " +
          "it carries `ScreenContent` rather than `ListItem`.",
    ),
    component(
      componentId = "wear-m3/progress-indicator",
      displayName = "Progress indicator",
      composable = "CircularProgressIndicator",
      role = "Leaf",
      traits = listItem + "ScreenContent",
      properties =
        listOf(
          enum(
            "variant",
            listOf("circular", "segmented-circular", "linear", "arc"),
            "Which indicator is written: `CircularProgressIndicator`, " +
              "`SegmentedCircularProgressIndicator`, `LinearProgressIndicator` or " +
              "`ArcProgressIndicator`. The circular ones ring the whole display; the linear one is " +
              "a list row.",
          ),
          number("progress", "0..1. Absent is the indeterminate form, which takes no progress."),
          number("segments", "How many segments the segmented circular form is divided into."),
          // `ProgressIndicatorDefaults.colors(indicatorColor, trackColor)`, which all four take.
          wearColor("indicatorColor"),
          wearColor("trackColor"),
        ),
      extra = "Wear publishes four, and which one you get is the `variant`.",
    ),
    component(
      componentId = "wear-m3/edge-button",
      displayName = "Edge button",
      composable = "EdgeButton",
      role = "Container",
      traits = listOf("Action"),
      slots = listOf(singleSlot("content", listOf("AnyContent"), min = 1)),
      properties =
        listOf(
          enum(
            "size",
            listOf("extra-small", "small", "medium", "large"),
            "`EdgeButtonSize`. The button's shape comes from the screen's bottom curve, so its " +
              "size is chosen from upstream's four rather than set in dp.",
          ),
          // `ButtonDefaults.buttonColors`, which `EdgeButton` takes as the filled button does.
          wearColor("containerColor"),
          wearColor("contentColor"),
        ),
      extra =
        "The button that hugs the bottom of a round screen. It belongs in the scaffold's " +
          "`edgeButton` slot, where `ScreenScaffold` reveals it from the scroll state; it is a " +
          "component of its own now rather than a `wear-m3/button` placed there, because " +
          "`EdgeButton` is a different composable with a different shape and a size enum.",
    ),
    component(
      componentId = "wear-m3/button-group",
      displayName = "Button group",
      composable = "ButtonGroup",
      role = "Container",
      traits = listItem,
      slots = listOf(manySlot("children", listOf("Action"))),
      extra =
        "A row of buttons that share the width and grow the one being pressed. Its children are " +
          "buttons; anything else has no `ButtonGroupScope` to be laid out in.",
    ),
    component(
      componentId = "wear-m3/alert-dialog",
      displayName = "Alert dialog",
      composable = "AlertDialog",
      role = "Container",
      traits = listOf("Overlay"),
      slots =
        listOf(
          manySlot("content", listOf("AnyContent")),
          singleSlot("confirmButton", listOf("Action")),
          singleSlot("dismissButton", listOf("Action")),
        ),
      properties =
        listOf(
          text("title", "The dialog's title.", required = true),
          text("text", "The supporting line under the title. Empty emits no `text` argument."),
          flag(
            "visible",
            "Whether the dialog is showing. A dialog is a screen state rather than a place in the " +
              "layout, so this is what the generated `AlertDialog(visible = …)` reads.",
          ),
        ),
      extra =
        "Wear's own, which is a full-screen scrolling dialog with its buttons on the bottom curve " +
          "— not a card floating over a scrim.",
    ),
    component(
      componentId = "wear-m3/confirmation-dialog",
      displayName = "Confirmation dialog",
      composable = "ConfirmationDialog",
      role = "Leaf",
      traits = listOf("Overlay"),
      properties =
        listOf(
          text("text", "The line shown under the icon.", required = true),
          enum(
            "variant",
            listOf("generic", "success", "failure"),
            "`ConfirmationDialog`, `SuccessConfirmationDialog` or `FailureConfirmationDialog`. " +
              "The two named ones bring their own icon and curved text; the generic one takes the " +
              "text alone.",
          ),
          flag("visible", "Whether it is showing, as for `wear-m3/alert-dialog`."),
        ),
      extra =
        "The brief full-screen acknowledgement Wear shows after an action and then dismisses.",
    ),
    component(
      componentId = "wear-m3/open-on-phone-dialog",
      displayName = "Open on phone dialog",
      composable = "OpenOnPhoneDialog",
      role = "Leaf",
      traits = listOf("Overlay"),
      properties =
        listOf(
          text("text", "The curved line under the animation. Empty takes upstream's own."),
          flag("visible", "Whether it is showing, as for `wear-m3/alert-dialog`."),
        ),
      extra =
        "The one Wear surface with no mobile analogue at all: it tells the wearer the rest of this " +
          "journey happens on their phone.",
    ),
    component(
      componentId = "wear-m3/date-picker",
      displayName = "Date picker",
      composable = "DatePicker",
      role = "Leaf",
      traits = listOf("ScreenContent"),
      properties =
        listOf(
          text("initialDate", "ISO-8601 `yyyy-MM-dd`. Empty picks upstream's own initial date."),
          enum(
            "type",
            listOf("year-month-day", "day-month-year", "month-day-year"),
            "`DatePickerType`, which is field order rather than formatting.",
          ),
        ),
      extra = "A full-screen three-column picker, driven by the rotary side button.",
    ),
    component(
      componentId = "wear-m3/time-picker",
      displayName = "Time picker",
      composable = "TimePicker",
      role = "Leaf",
      traits = listOf("ScreenContent"),
      properties =
        listOf(
          text("initialTime", "ISO-8601 `HH:mm[:ss]`. Empty picks upstream's own initial time."),
          enum(
            "type",
            listOf("hours-minutes-seconds", "hours-minutes-am-pm", "hours-minutes-24h"),
            "`TimePickerType`, which decides both the columns and the clock.",
          ),
        ),
      extra = "The time counterpart of `wear-m3/date-picker`, and the same full-screen shape.",
    ),
  )
}

/**
 * `wear-m3`: the Wear Compose Material 3 screen, as an authoring surface.
 *
 * Components are drawn by `ee.schimke.wearcmp:*` (Wear Compose compiled for Compose
 * Multiplatform); the generated screen and native render use the real AAR. The canvas still draws
 * three things itself, each noted in its `wasm` note: the screen scaffold (no viewport), the
 * unrolled list (long captures disable the transformation), and `wear-m3/text` (the mobile type
 * scale).
 *
 * The scaffold is drawn as a stadium — the long-screenshot convention, showing the whole extent.
 * Straight sides overstate row width near the curve; the frame pane shows the real layout. See
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md`.
 */
internal fun wearM3Catalog(base: CatalogCapabilityV1): CatalogCapabilityV1 {
  val components = base.components.associateBy { it.componentId }
  val box = components.getValue("layout/box")
  // Foundation's lazy column, read only for its modifier vocabulary (shared by both platforms).
  val lazyColumn = components.getValue("layout/lazy-column")
  // Shared capability blocks: statements about a lane (canvas, SVG, text recorder), not about a
  // Material 3 component.
  val canvasSupported =
    components
      .getValue("m3/text")
      .wasm
      .newBuilder()
      .also {
        it.adapterStatus = WasmAdapterStatusV1.SUPPORTED
        it.platformSupported = JsonPrimitive(true)
      }
      .build()
  val noStructuredSvg = components.getValue("remote-compose/document").svg
  val recordedTextSvg = components.getValue("m3/text").svg
  // The SVG lane's block for a structurally recordable node; it describes the recorder, not the
  // component.
  val recordedSceneSvg = components.getValue("m3/card").svg
  val boxSlot = box.slots.single()

  val contentSlot =
    boxSlot
      .newBuilder()
      .also {
        it.name = "content"
        it.cardinality =
          boxSlot.cardinality
            .newBuilder()
            .also {
              it.min = 0
              it.max = 1
            }
            .build()
      }
      .build()
  // Narrowed to `Action`: the slot exists for an `EdgeButton` hugging the bottom curve.
  val edgeButtonSlot =
    contentSlot
      .newBuilder()
      .also {
        it.name = "edgeButton"
        it.acceptedRoles = emptyList()
        it.acceptedTraits = listOf("Action")
      }
      .build()
  // Dialogs are a screen state drawn over everything (each has a `visible` flag), so they sit
  // beside the scaffold, not in `content`. Unbounded: only one is visible at a time.
  val overlaySlot =
    contentSlot
      .newBuilder()
      .also {
        it.name = "overlays"
        it.cardinality =
          contentSlot.cardinality
            .newBuilder()
            .also {
              it.min = 0
              it.max = null
            }
            .build()
        it.acceptedRoles = emptyList()
        it.acceptedTraits = listOf("Overlay")
      }
      .build()

  val scaffold =
    box
      .newBuilder()
      .also {
        it.componentId = "wear-m3/screen-scaffold"
        it.displayName = "Wear screen · ScreenScaffold"
        it.role = "Scaffold"
        it.traits = listOf("ScreenContent", "WearScreenHost")
        it.slots = listOf(contentSlot, edgeButtonSlot, overlaySlot)
        it.properties = wearScreenScaffoldProperties()
        it.modifierCapabilities = emptyList()
        it.wasm =
          canvasSupported
            .newBuilder()
            .also {
              // Names the frame adapter rather than letting the renderer recognise the root by id.
              it.canvas = "frame/round-screen"
              it.notes =
                "Drawn as a Wear long-screenshot stadium at the document frame's width, with the " +
                  "content padding the real `ScreenScaffold` computes for that screen size, the " +
                  "clock where `AppScaffold` puts it, and a bezel scroll indicator. It is not Wear " +
                  "Compose's own scaffold, and that is about the shape of the page rather than " +
                  "about the library: this pane draws the design's EXTENT — the long-screenshot " +
                  "form, with no viewport for a scroll state to be live in — and `ScreenScaffold` " +
                  "is a viewport. What it does claim is the geometry: wear-m3-catalog's stitched " +
                  "`ScrollMode.LONG` capture of the same list matches this to within a dp."
            }
            .build()
        it.code = null
        it.svg =
          noStructuredSvg
            ?.newBuilder()
            ?.also {
              it.notes = "The stadium screen frame has not been through structured SVG parity."
            }
            ?.build()
      }
      .build()

  // Wear's `ListHeader` (48dp at every size), so the generated screen matches the canvas rather
  // than faking it with a padded `m3/text`. Label properties are the inner `Text`'s.
  val listHeader =
    ComponentCapabilityV1.Builder(
        "wear-m3/list-header",
        "List header",
        "Leaf",
        canvasSupported
          .newBuilder()
          .also {
            it.notes =
              "Wear Material 3's `ListHeader`: a 48dp item whose label sits low in it, drawn on " +
                "the screen's own background rather than on a surface. The height is upstream's " +
                "and is what makes a generated screen's first row land where the canvas puts it."
          }
          .build(),
      )
      .also {
        it.traits = listOf("TextContent", "RemoteAuthorable", "ListItem")
        it.properties = wearLabelProperties()
        it.modifierCapabilities = emptyList()
        it.code = null
        it.svg = recordedTextSvg
      }
      .build()

  // Wear's own lazy list; it shares only the shape of foundation's, so traits are stated here.
  val transformingLazyColumn =
    ComponentCapabilityV1.Builder(
        "wear-m3/transforming-lazy-column",
        "Transforming lazy column",
        "Container",
        canvasSupported
          .newBuilder()
          .also {
            it.notes =
              "Drawn by Wear Compose's own `TransformingLazyColumn` in a bounded frame, where " +
                "it scales and fades each row through the library's `transformedHeight` and its " +
                "own `SurfaceTransformation`. At the extent it is a plain Column at the list's " +
                "own spacing, which is what a stitched `ScrollMode.LONG` capture of the real " +
                "one is: `LONG` turns the row transformation off in order to stitch, so every " +
                "row on the reference is full content width at every position and the Column " +
                "reproduces it exactly."
          }
          .build(),
      )
      .also {
        it.traits =
          listOf(
            "OrderedContent",
            "VerticalContent",
            "ScrollableContent",
            "RepeatedContent",
            "WearListContent",
          )
        it.slots = listOf(manySlot("items", listOf("AnyContent"), listOf("Container", "Leaf")))
        // `Modifier` extensions every container accepts, taken from foundation's shared entry.
        it.modifierCapabilities = lazyColumn.modifierCapabilities
        it.properties = wearTransformingLazyColumnProperties()
        it.wasm =
          canvasSupported
            .newBuilder()
            .also {
              it.notes =
                "Drawn by Wear Compose's own `TransformingLazyColumn` in a bounded frame, where " +
                  "it scales and fades each row through the library's `transformedHeight` and its " +
                  "own `SurfaceTransformation`. At the extent it is a plain Column at the list's " +
                  "own spacing, which is what a stitched `ScrollMode.LONG` capture of the real " +
                  "one is: `LONG` turns the row transformation off in order to stitch, so every " +
                  "row on the reference is full content width at every position and the Column " +
                  "reproduces it exactly."
            }
            .build()
        it.code = null
        it.svg =
          noStructuredSvg
            ?.newBuilder()
            ?.also {
              it.notes =
                "No structured SVG from this catalog's own record: `TransformingLazyColumn` has " +
                  "no per-component call site to walk — `WearScreenCodeExporter` writes the whole " +
                  "screen — so the lane has nothing to emit from. The extent's untransformed rows " +
                  "could not be claimed as the list's own layout in any case."
            }
            ?.build()
      }
      .build()

  /**
   * Wear's three content components with a Material 3 namesake: `Text`, `TitleCard` and `Button`.
   *
   * Declared here, not derived from the mobile ones: they share a name and Compose's vocabulary,
   * not a component (one card shape vs three, no `selected` on `Button`, and `TitleCard`/`AppCard`/
   * `OutlinedCard`/`Card` are four composables, so `variant` selects a composable).
   */
  fun wearComponent(
    componentId: String,
    displayName: String,
    role: String,
    composable: String,
    traits: List<String>,
    properties: List<PropertyCapabilityV1>,
    slots: List<SlotCapabilityV1> = emptyList(),
    modifierCapabilities: List<String> = emptyList(),
    svg: SvgCapabilityV1? = null,
  ) =
    ComponentCapabilityV1.Builder(
        componentId,
        displayName,
        role,
        canvasSupported
          .newBuilder()
          .also {
            it.notes =
              "Wear Material 3's `$composable`. The canvas draws it with Wear Compose's own " +
                "`$composable`, out of the Compose Multiplatform build of the library it links; " +
                "the generated screen and the native render use `androidx.wear.compose` itself."
          }
          .build(),
      )
      .also {
        it.traits = traits
        it.slots = slots
        it.properties = properties
        it.modifierCapabilities = modifierCapabilities
        it.code = null
        it.svg = svg
      }
      .build()

  val wearText =
    wearComponent(
        componentId = "wear-m3/text",
        displayName = "Text",
        role = "Leaf",
        composable = "Text",
        traits = listOf("TextContent", "RemoteAuthorable"),
        modifierCapabilities = WEAR_MODIFIERS,
        properties =
          listOf(
            wearString("text", required = true),
            wearString(
              "style",
              allowed = WEAR_TYPE_ROLES,
              notes = "The role the text is set in, resolved against Wear's own type scale.",
            ),
            wearString(
              "fontWeight",
              allowed = listOf("normal", "medium", "semiBold", "bold"),
              notes = "Overrides the role's weight.",
            ),
            wearString(
              "fontStyle",
              allowed = listOf("normal", "italic"),
              notes = "Overrides the role's style.",
            ),
            wearColor(),
            wearNumber("fontSizeSp", "Overrides the role's size."),
            wearNumber("lineHeightSp", "Overrides the role's line height."),
            wearNumber("letterSpacingSp", "Overrides the role's tracking."),
            wearString(FontSettings.VARIATION_PROPERTY, notes = FontSettings.VARIATION_NOTES),
            wearString(FontSettings.FEATURE_PROPERTY, notes = FontSettings.FEATURE_NOTES),
            wearInteger("minLines", "The smallest height the text occupies, in lines."),
            wearInteger("maxLines", "How many lines the text may occupy before it truncates."),
            wearBoolean("softWrap", "Whether the text breaks at soft line breaks."),
            wearString(
              "overflow",
              allowed = listOf("clip", "ellipsis", "visible"),
              notes = "What a truncated text does at its edge.",
            ),
            wearString(
              "textAlign",
              allowed = listOf("start", "center", "end", "justify"),
              notes = "How the lines are aligned within the text's own width.",
            ),
            wearString(
              "textDecoration",
              allowed = listOf("none", "underline", "lineThrough"),
              notes = "A decoration painted under, or through, the glyphs.",
            ),
            wearString(
              "alignment",
              allowed = WEAR_ALIGNMENTS,
              notes = "Where the text sits in a parent that gives it more room than it needs.",
            ),
            wearNumber("weight", "The share of a parent's remaining space the text takes."),
          ),
      )
      .let { text ->
        // The one thing a text node adds to the shared block: what the recorder does with text.
        text.newBuilder().also { it.svg = recordedTextSvg }.build()
      }

  // flexpress's `VariableFontText`, not a Wear Material 3 component; the canvas draws a stand-in
  // since flexpress is JVM-only.
  val wearVariableFontText =
    wearComponent(
        componentId = VariableFontText.WEAR_ID,
        displayName = "Variable font text",
        role = "Leaf",
        composable = "VariableFontText",
        traits = listOf("TextContent"),
        modifierCapabilities = WEAR_MODIFIERS,
        properties =
          listOf(
            wearString(
              "text",
              required = true,
              notes =
                "A literal: the outline is worked out from the font when the design exports, so " +
                  "the text cannot change at run time.",
            ),
            wearString(
              "font",
              allowed = VariableFontText.Font.entries.map { it.wire },
              notes = "The variable font the text is drawn from. Absent is Roboto Flex.",
            ),
          ) +
            VariableFontText.AXIS_PROPERTIES.map { wearBindableNumber(it, variableAxisNotes(it)) } +
            listOf(wearNumber("fontSizeSp", "The text's size. Absent is 32."), wearColor()),
      )
      .let { text ->
        text
          .newBuilder()
          .also {
            it.wasm =
              text.wasm
                .newBuilder()
                .also { wasm ->
                  wasm.notes =
                    "flexpress's VariableFontText draws the text from the font's outlines, its " +
                      "axes free to move. flexpress runs on the JVM only, so the canvas draws a " +
                      "stand-in: Text in the vendored variable font, instanced at the same axes."
                }
                .build()
            it.svg = recordedTextSvg
          }
          .build()
      }

  val wearCard =
    wearComponent(
      componentId = "wear-m3/card",
      displayName = "Card",
      role = "Container",
      composable = "TitleCard",
      traits = listOf("GridItem", "CarouselItem", "ListItem", "OverlayContent"),
      modifierCapabilities = WEAR_SURFACE_MODIFIERS,
      slots =
        listOf(manySlot("content", listOf("AnyContent"), listOf("Container", "Leaf"), min = 1)),
      svg = recordedSceneSvg,
      properties =
        listOf(
          wearString(
            "variant",
            allowed = listOf("title", "app", "outlined", "plain"),
            notes =
              "Which card is written: `TitleCard`, `AppCard`, `OutlinedCard` or `Card`. Wear " +
                "publishes four composables with different content lambdas rather than four " +
                "styles of one, so this selects the composable.",
          ),
          wearString(
            "stableKey",
            notes =
              "The item's identity, written as the generated lazy list's `key`. An identity " +
                "rather than a look: two rows sharing one are one row to a lazy layout.",
          ),
          // `CardDefaults.cardColors` tints the card itself; `OutlinedCard` has no container, so a
          // `containerColor` there is refused at export.
          wearColor("containerColor"),
          wearColor("contentColor"),
          wearClickAction(),
        ),
    )

  val wearButton =
    wearComponent(
      componentId = "wear-m3/button",
      displayName = "Button",
      role = "Container",
      composable = "Button",
      traits = listOf("Action", "ToolbarItem"),
      modifierCapabilities = WEAR_MODIFIERS,
      slots =
        listOf(manySlot("content", listOf("AnyContent"), listOf("Leaf", "Container"), min = 1)),
      svg = recordedSceneSvg,
      properties =
        listOf(
          wearString(
            "variant",
            allowed = listOf("filled", "filled-tonal", "outlined", "child"),
            notes =
              "Which button is written: `Button`, `FilledTonalButton`, `OutlinedButton` or " +
                "`ChildButton`. There is no `fab` and no `elevated` — a watch publishes neither.",
          ),
          wearBoolean("enabled", "Whether the button responds to a press."),
          wearColor("containerColor"),
          wearColor("contentColor"),
          wearClickAction(),
        ),
    )

  val wearOnly =
    wearOnlyComponents(
      canvasSupported = canvasSupported,
      noStructuredSvg = noStructuredSvg,
      // The icon key table is `m3/icon`'s, taken from the catalog rather than restated: two lists
      // of icon names is two chances to disagree about which vector `genres` is.
      iconKeys =
        components.getValue("m3/icon").properties.single { it.name == "iconKey" }.allowedValues,
    )

  // Foundation only: `Box`, `Column`, `Row` and `Image` are the same declarations on both
  // platforms, taken from the base catalog. Wear publishes its own `Icon`, hence `wear-m3/icon`.
  //
  // The Remote Compose seams are withdrawn: the Wear screen generator cannot export them, and what
  // they mean inside a Wear screen is still an open question.
  val foundationIds = listOf("layout/box", "layout/column", "layout/row", "asset/image")
  val sharedFoundation =
    foundationIds.map(components::getValue).map { component ->
      // Foundation components are the same declaration on both platforms, so nothing is stood in
      // for.
      component
        .newBuilder()
        .also {
          it.wasm = component.wasm.newBuilder().also { it.notes = WEAR_FOUNDATION_NOTE }.build()
        }
        .build()
    }

  return base
    .newBuilder()
    .also {
      // The canvas is where a `wear-m3` design is authored, not looked at: components are Wear
      // Compose's own, but the frame is a stand-in and the font is the port's. Stated here so the
      // editor and server do not guess. In `statusSemantics` because `CatalogCapabilityV1` cannot
      // grow a field from here; read by `UiBuilderPreviewSurfaces.from`.
      it.statusSemantics =
        JsonObject(
          base.statusSemantics +
            // Grouped apart from phone screens and offered no mobile pack: Wear Material 3 and
            // Material 3 are not used together.
            (CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY to JsonPrimitive("wear")) +
            // The frame is a catalog fact the renderer reads, matching the catalog's own
            // `ui-builder.policy.json`.
            ("frame" to
              buildJsonObject {
                put("adapter", JsonPrimitive("frame/round-screen"))
                put("seedDevice", JsonPrimitive("id:wearos_small_round"))
                putJsonObject("geometry") {
                  put(
                    "contentPadding",
                    // Measured by `ScreenScaffoldContentPaddingTest` in wear-m3-catalog against the
                    // real
                    // `ScreenScaffold`.
                    JsonArray(
                      listOf(
                          Triple(192, 10, 20),
                          Triple(227, 12, 23),
                          Triple(240, 13, 24),
                        )
                        .map { (screen, horizontal, vertical) ->
                          JsonObject(
                            mapOf(
                              "screenDp" to JsonPrimitive(screen),
                              "horizontalDp" to JsonPrimitive(horizontal),
                              "verticalDp" to JsonPrimitive(vertical),
                            )
                          )
                        }
                    ),
                  )
                }
              }) +
            ("previewSurfaces" to
              buildJsonObject {
                putJsonObject("wasm") {
                  put("fidelity", JsonPrimitive("approximate"))
                  put(
                    "reason",
                    JsonPrimitive(
                      "The canvas draws this catalog's components with a Compose Multiplatform " +
                        "build of Wear Compose — the Android AAR has no browser variant — so each " +
                        "is the component itself rather than an impression of it. What is still " +
                        "the canvas's own: the screen frame (the extent has no viewport for a " +
                        "real scaffold), the unrolled list (a long screenshot turns the row " +
                        "transformation off), and the font, which is the port's vendored Roboto " +
                        "Flex rather than the platform's. Author on it; check a size on the " +
                        "Android preview, which compiles this design's own generated Kotlin " +
                        "against the real AAR."
                    ),
                  )
                }
                putJsonObject("native") {
                  put("fidelity", JsonPrimitive("authoritative"))
                  put("backend", JsonPrimitive("android"))
                  // This lane renders the generated Kotlin against the real `androidx.wear.compose`
                  // AAR under Robolectric, as wear-m3-catalog's published stickers are.
                  put(
                    "reason",
                    JsonPrimitive(
                      "The generated Kotlin compiled against this module's own classpath and " +
                        "rendered under Robolectric: the same lane that produces this catalog's " +
                        "stickers."
                    ),
                  )
                }
              }) +
            // How the insert panel shelves this catalog; read by `ComponentMenu.from`. Replaces the
            // base catalog's menu rather than merging (its `m3/…` ids are gone). A literal key
            // because
            // `:ui-builder` is above this module (`checkUiBuilderRuntimeBoundary`).
            ("componentMenu" to wearComponentMenu()) +
            // What a design may re-skin, and where each value lands; see [wearDesignTokens].
            ("designTokens" to wearDesignTokens())
        )
      it.benchmark =
        base.benchmark
          .newBuilder()
          .also { benchmark ->
            benchmark.id = "wear-m3-screen-scaffold"
            benchmark.sourceRevision = "compose-ai-tools:samples/design-catalog-wear-m3"
            benchmark.catalogSystemId = CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID
            benchmark.catalogRevision = "wear-screen-scaffold-v1"
          }
          .build()
      it.components =
        listOf(
          scaffold,
          transformingLazyColumn,
          listHeader,
          wearText,
          wearVariableFontText,
          wearCard,
          wearButton,
        ) + wearOnly + sharedFoundation
    }
    .build()
}
