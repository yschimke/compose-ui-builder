@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CodeCapabilityV1
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
 * How the builder's insert panel shelves `wear-m3`, and which property carries a component's
 * variants.
 *
 * Wear's own families rather than Material's: a watch app is a **screen** holding a **list**, so
 * those lead, and `Text inputs` — a whole shelf on the phone catalog — does not exist here at all.
 * `wear-m3/edge-button`'s `size` is a variant rather than a dimension: an edge button comes in four
 * sizes the way a card comes in four kinds, and picking one is choosing which button, not nudging a
 * number.
 *
 * Deliberately absent: `transformation` on the lazy column and `segmented` on the slider are
 * behaviours a design turns on, not kinds of component; `iconKey` is forty-six icons; and
 * `wear-m3/text.style` is fifteen type scales, which is a property of a text rather than a kind of
 * Text. The same three calls the M3 declaration makes, for the same reasons.
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
      "Content" to listOf("wear-m3/text", "wear-m3/icon", "asset/image"),
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
 * The Wear screen host's authored parameters.
 *
 * `androidx.wear.compose.material3.ScreenScaffold` is NOT the widget container's kind of stand-in.
 * The widget frame is drawn by the launcher, so `WearWidgetCodeExporter` erases it;
 * `ScreenScaffold` is a composable the author calls, so this scaffold is *emitted* rather than
 * erased. What it fakes is only the drawing: the browser has no Wear Compose to draw with, so the
 * canvas approximates the frame and the generated Kotlin names the real one.
 *
 * The screen's diameter is deliberately absent. It is the document's own frame — the Screen
 * inspector's Wear OS device presets already carry 192/227/240dp at the right density — and a fifth
 * property would be a second answer to a question the environment already answers.
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
  )

// ── The property vocabulary
//
// Small builders rather than one component's list copied onto another's. A Wear `Text` and a
// Material 3 `Text` both take a `style` whose values are the same fifteen role names, and that
// sameness is a fact about Compose's type scale rather than about either component — so the
// *vocabulary* is shared and every component states its own properties from it. What is not shared
// is the component: nothing here derives a Wear component from a Material 3 one.

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
 * The action a click runs.
 *
 * An object or null rather than a string, which is the shape the mobile catalog declares for the
 * same property: it is a binding, not a name, and declaring it as a string here would make one
 * property mean two things across the two catalogs.
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

/**
 * A colour, in either of the two shapes the colour token vocabulary allows.
 *
 * The note is the catalog's rather than a component's: it is the same string wherever a colour is
 * authorable, and it is the one an author reads to find out how to write one.
 */
private fun wearColor(name: String = "color"): PropertyCapabilityV1 =
  wearString(
    name,
    notes =
      "A colour, written as {\"type\":\"color\",\"value\":\"#RRGGBB\"} or as " +
        "{\"type\":\"colorToken\",\"value\":\"primary\"} naming one of " +
        "statusSemantics.colorTokens. A string wrapper, or a role the canvas does not draw, is " +
        "rejected rather than guessed at.",
  )

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
 * The modifiers the canvas applies to a node that draws no surface of its own: text, a button, a
 * list.
 *
 * `Modifier` extensions rather than arguments of any composable, which is why they are a vocabulary
 * the canvas owns rather than a component's surface — and why `fillMaxSize` is not among them,
 * these being nodes that size themselves.
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
 * What a button that can sit in a `ButtonGroup` takes: its share of the group, and the few
 * modifiers that size or tag it. `weight` is `ButtonGroupScope.weight` there, and refused anywhere
 * without a row, a column or a group to share.
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
 * The label a header draws, and its truncation.
 *
 * Upstream's `ListHeader` and `ListSubHeader` take a content lambda rather than a string, so these
 * are the `Text` inside them — one vocabulary for both, which is why it is a function.
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
 * The Wear Material 3 components this catalog offers that have **no Material 3 counterpart at
 * all**.
 *
 * ## Why these exist
 *
 * `wear-m3/checkbox-button` was refused once. The rule it was refused under is worth quoting rather
 * than paraphrasing: *do not fabricate a component in the Wasm canvas to stand in for a library the
 * canvas cannot link*. That rule closed
 * [#395](https://github.com/yschimke/compose-preview-server/pull/395), which built
 * `CheckboxButton`, `SwitchButton` and `RadioButton` as hand-assembled Material 3 shapes at sizes
 * read off a screenshot — an impression of upstream with nothing in the build to check it against,
 * wrong silently in the one surface an author trusts.
 *
 * They are drawn by the real thing now, so the rule is kept rather than bent: nothing here is a
 * lookalike, and nothing here is a placeholder. `ee.schimke.wearcmp:*` is Wear Compose compiled for
 * Compose Multiplatform, which is what lets the canvas call the same `CheckboxButton`, `Slider` and
 * `DatePicker` the generated screen names. See `wearM3Catalog` for the three the canvas still draws
 * for itself, and why each is about the shape of the page rather than about the library.
 *
 * ## Why the name says "only"
 *
 * These are Wear's own: a labelled full-width row, a segmented slider, a three-column picker. They
 * have no Material 3 counterpart at all, which is why they are declared here rather than beside the
 * three that do — and the three that do are declared just the same way, because a shared name is
 * not a shared component.
 */
private fun wearOnlyComponents(
  canvasSupported: WasmCapabilityV1,
  noStructuredSvg: SvgCapabilityV1?,
  iconKeys: List<JsonElement>,
): List<ComponentCapabilityV1> {
  /**
   * The note every component in this group carries, with its own composable named.
   *
   * [drawnBy] is the one thing that differs between them, and it is a parameter because the
   * catalog's claim has to match the renderer's branch: an entry that says "Wear Compose itself"
   * about a component the canvas draws some other way is the same failure as a `supported` status
   * with no branch behind it, one layer up and harder to see.
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
    variantComposables: List<String> = emptyList(),
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
        // No modifier vocabulary by default, and that is a statement rather than an omission: a
        // Wear control is laid out by the list and the scaffold around it — a `CheckboxButton` is
        // a full-width row whose height upstream fixes. The exceptions pass one: an icon button in
        // a `ButtonGroup` is sized by its `weight`, which is how Jetcaster makes play the wider of
        // two, and `WearScreenCodeExporter` writes a node's authored chain.
        it.modifierCapabilities = modifierCapabilities
        it.code = wearCode(composable, variantComposables)
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
        ),
      // The one component in this group the canvas does NOT draw with Wear Compose, and the
      // difference is stated rather than left to be discovered. `BuilderIcon` is the canvas's own
      // icon drawer, because an icon is a tinted vector at a size on both platforms — Wear
      // publishes no shape of its own here — and `BuilderIcon` is what owns this build's key table,
      // its tint resolution and the structured-path export the SVG lane needs. Drawing it twice
      // would be two answers to one question.
      drawnBy =
        "the canvas's own icon drawer, not Wear's `Icon` — an icon is a tinted vector on both " +
          "platforms, and that drawer owns this build's key table, tint resolution and " +
          "structured-path export",
    ),
    component(
      componentId = "wear-m3/icon-button",
      displayName = "Icon button",
      composable = "IconButton",
      variantComposables =
        listOf(
          "FilledIconButton",
          "FilledTonalIconButton",
          "FilledVariantIconButton",
          "OutlinedIconButton",
        ),
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
          // Recolours the variant's own palette rather than replacing the variant: upstream
          // passes `IconButtonDefaults.filledIconButtonColors(containerColor = …)` to a
          // `FilledIconButton`, so the variant still decides the shape and the disabled colours.
          wearColor("containerColor"),
          wearColor("contentColor"),
        ),
      modifierCapabilities = WEAR_BUTTON_GROUP_CHILD_MODIFIERS,
    ),
    component(
      componentId = "wear-m3/text-button",
      displayName = "Text button",
      composable = "TextButton",
      variantComposables =
        listOf(
          "FilledTextButton",
          "FilledTonalTextButton",
          "FilledVariantTextButton",
          "OutlinedTextButton",
        ),
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
      // The same three the header declares, and for the same reason: upstream takes a content
      // lambda, so the label and its truncation are the `Text` inside it. Offering only `text` here
      // made the canvas's and the exporter's reads unreachable — the service refuses a property the
      // catalog does not declare.
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
      variantComposables =
        listOf(
          "SegmentedCircularProgressIndicator",
          "LinearProgressIndicator",
          "ArcProgressIndicator",
        ),
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
          )
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
      variantComposables = listOf("SuccessConfirmationDialog", "FailureConfirmationDialog"),
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
 * ## The components are Wear's own, through the port
 *
 * `androidx.wear.compose:compose-material3` is an Android AAR and the builder's canvas is Compose
 * Multiplatform for Wasm, which cannot link an AAR — but the canvas never had to link *that*
 * artifact. `ee.schimke.wearcmp:*` is the same library's source compiled for Compose Multiplatform
 * with `jvm` and `wasmJs` variants, which are exactly this module's targets, so every component
 * below is drawn by Wear Compose rather than by an impression of it. The generated screen and the
 * native render use the real AAR; the canvas is the lane that trades a port for a browser.
 *
 * Three things the canvas still draws for itself, each for a reason of its own rather than a
 * missing dependency, and each saying so in its `wasm` note: the screen scaffold (the extent has no
 * viewport), the unrolled list (a `ScrollMode.LONG` capture turns the row transformation off), and
 * `wear-m3/text` (the canvas's theme still carries the mobile type scale).
 *
 * ## The two components that are this catalog's whole point
 *
 * `wear-m3/screen-scaffold` and `wear-m3/transforming-lazy-column`. A Wear screen is a
 * `ScreenScaffold` wrapping a `TransformingLazyColumn` in something over ninety per cent of the
 * Wear Material 3 surface area.
 *
 * The canvas draws the scaffold as a **stadium** — the screen's width, the content's height, round
 * caps — which is the Wear long-screenshot convention rather than a device. That is a deliberate
 * choice about what an author is building: the whole scrolling extent at once, not a 192dp keyhole
 * onto it. What it costs is stated in the wasm notes and again in
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md`: straight sides overstate the width a row actually gets
 * near the curve, and the row transformation is not drawn at the extent — the frame pane beside it
 * is where the real lazy layout and its transformation are.
 *
 * ## What used to be here
 *
 * The Wear content ids, and a note on every one of them reading "drawn as its mobile counterpart"
 * or "the canvas draws a named placeholder". Both were true while `wear-m3` was a re-creation of
 * the library out of Material 3 shapes, and both stopped being true when the port landed — which is
 * exactly the kind of claim that outlives its reason, because nothing fails when prose goes stale.
 */
internal fun wearM3Catalog(base: CatalogCapabilityV1): CatalogCapabilityV1 {
  val components = base.components.associateBy { it.componentId }
  val box = components.getValue("layout/box")
  // Foundation's lazy column, read for its MODIFIER vocabulary alone — see the Wear list's
  // declaration. `layout/lazy-column` is `androidx.compose.foundation`, which both platforms
  // share, so this is the one entry here a Wear component legitimately reads anything from.
  val lazyColumn = components.getValue("layout/lazy-column")
  // The shared capability blocks, read off the packaged catalog once because they are statements
  // about a LANE rather than about a component: `canvasSupported` is what the canvas says about a
  // component it draws, `noStructuredSvg` what the SVG lane says about one with no call site to
  // walk, and `recordedTextSvg` what the same-runtime recorder says about text. None of the three
  // is a claim about Material 3, which is why none of them is a component being copied.
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
  // What the SVG lane says about a node it can record structurally. The recorder walks the composed
  // scene, so this is a statement about the recorder rather than about any component — which is why
  // the same block answers for a Wear card and a Material 3 one.
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
  // `ScreenScaffold(edgeButton = …)` takes one composable, and upstream's own samples put an
  // `EdgeButton` in it and nothing else. Narrowed to `Action` so a Text cannot be dropped into a
  // slot whose whole job is to hug the bottom curve with a button in it.
  val edgeButtonSlot =
    contentSlot
      .newBuilder()
      .also {
        it.name = "edgeButton"
        it.acceptedRoles = emptyList()
        it.acceptedTraits = listOf("Action")
      }
      .build()
  // Wear's dialogs are a screen *state*, not a place in the layout: each takes a `visible` flag and
  // draws over the whole display when it is set, and upstream's own samples put them beside the
  // scaffold in the same `AppScaffold`. A slot in `content` would have made them list rows, which
  // is a full-screen dialog inside a scrolling item. Unbounded, because a screen can have more than
  // one dialog it shows at different moments — only one is ever `visible`.
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
              // The drawing that frames this screen root, named rather than left to the renderer to
              // recognise by id. `frame/round-screen` is an adapter this build ships; a catalog
              // whose screen root is called something else names the same adapter and gets the same
              // frame.
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
        it.code = wearCode("ScreenScaffold")
        it.svg =
          noStructuredSvg
            ?.newBuilder()
            ?.also {
              it.notes = "The stadium screen frame has not been through structured SVG parity."
            }
            ?.build()
      }
      .build()

  // Wear's `ListHeader`, and it exists because the round trip found it: it is a 48dp item at every
  // screen size — measured — and the template used to fake that with a padded `m3/text`. The canvas
  // matched; the *generated screen* did not, because a padded `Text` is not a `ListHeader` and the
  // generator has no business emitting one as the other. Fifteen dp of header is the difference
  // between "the two pictures agree" and "the two pictures agree except at the top".
  //
  // Declared rather than filtered out of `m3/text`, and the label's properties are the header's own
  // vocabulary: upstream takes a content lambda, so `maxLines` and `overflow` are the `Text` inside
  // it.
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
        it.code = wearCode("ListHeader")
        it.svg = recordedTextSvg
      }
      .build()

  // Wear's own lazy list, from `androidx.wear.compose.foundation.lazy`. It shares the *shape* of a
  // lazy column with foundation's — ordered, vertical, scrollable, repeated — and none of its
  // implementation, which is why the traits are stated here rather than inherited from
  // `layout/lazy-column`: what the two have in common is Compose's vocabulary, not a component.
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
        // The modifier vocabulary, and the one thing here that IS taken from another entry: these
        // are `Modifier` extensions — `size`, `padding`, `alpha`, `weight` — which every container
        // accepts, so they are the canvas's vocabulary rather than this component's arguments. The
        // entry they are read from is foundation's, which is what both platforms share.
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
        it.code = wearCode("TransformingLazyColumn", pkg = "androidx.wear.compose.foundation.lazy")
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
   * Wear's three content components that have a Material 3 namesake: `Text`, `TitleCard` and
   * `Button`.
   *
   * ## Why they are declared here rather than derived from the mobile ones
   *
   * They share a NAME with a Material 3 component and nothing else. `wear-m3` and `m3` describe two
   * different libraries — different theme systems, different type scales, different sizes, and
   * **you do not use them together** — so a Wear card is not a recoloured Material 3 card, and
   * saying so by copying one and renaming it was a claim this catalog had no business making. What
   * the two do share is Compose's own vocabulary: a `style` is one of the same fifteen role names,
   * a `color` is written the same way, and `Modifier` extensions are `Modifier` extensions. That
   * vocabulary lives in the helpers above and every component states its own surface from it.
   *
   * The differences are the point, and they show up as differences: Wear publishes ONE card shape
   * where Material 3 publishes three, so there is no `shape` here to move; Wear's `Button` has no
   * `selected` and no `containerColor`; and `TitleCard`, `AppCard`, `OutlinedCard` and `Card` are
   * four composables rather than four styles of one, which is why `variant` selects a composable.
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
    variantComposables: List<String> = emptyList(),
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
        it.code = wearCode(composable, variantComposables)
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

  val wearCard =
    wearComponent(
      componentId = "wear-m3/card",
      displayName = "Card",
      role = "Container",
      composable = "TitleCard",
      variantComposables = listOf("AppCard", "OutlinedCard", "Card"),
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
          wearClickAction(),
        ),
    )

  val wearButton =
    wearComponent(
      componentId = "wear-m3/button",
      displayName = "Button",
      role = "Container",
      composable = "Button",
      variantComposables = listOf("FilledTonalButton", "OutlinedButton", "ChildButton"),
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

  // Foundation only. Every one of these is `androidx.compose.foundation` or `androidx.compose.ui`,
  // the same declaration on both platforms, so sharing it says nothing about which Material library
  // a Wear screen is built from — which is exactly what sharing a Material component would claim.
  //
  // `m3/icon` left with the Material ones and has no Wear id yet: the icon key resolves to a vector
  // through a table this export module cannot reach, so `wear-m3/icon` would be a palette entry
  // that
  // refuses on export — which is what `m3/icon` already was here.
  // Four, and all four are genuinely shared: `Box`, `Column`, `Row` and `Image` are
  // `androidx.compose.foundation` / `androidx.compose.ui`, the same declarations on both platforms,
  // taken from the base catalog here rather than restated — which is what keeps one source for
  // them. Wear publishes no `Image` of its own; it does publish `Icon`, which is why `wear-m3/icon`
  // is a Wear component of its own.
  //
  // The three Remote Compose seams — `remote-compose/document`, `-inline` and `-custom` — were here
  // too, and were offered without being usable: the whole-screen Wear generator has no case for any
  // of them, so a design that placed one was refused at export. An entry that cannot be exported is
  // worse than a missing one, because it is only discovered at the end. Withdrawn rather than
  // fixed, because what a Remote Compose seam means inside a Wear SCREEN is a real question — they
  // are widget vocabulary and this generator writes plain Compose — and is tracked to come back
  // once it has an answer.
  val foundationIds = listOf("layout/box", "layout/column", "layout/row", "asset/image")
  val sharedFoundation =
    foundationIds.map(components::getValue).map { component ->
      // The note every shared foundation component carries, and it says the opposite of what the
      // Material components' notes used to say. A Material component was a stand-in — "drawn as
      // the Material 3 component of the same name" — because the two libraries publish different
      // ones and the catalog had picked the wrong library. A foundation component is the same
      // declaration on both platforms, so there is nothing to stand in for.
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
      // The one thing this catalog has to say about itself that is not a component.
      //
      // The Wasm canvas is where a `wear-m3` design is *authored* — you select a node on it, drag
      // it, watch the layout — and it is not where the design is *looked at*. The components on it
      // are Wear Compose's own, drawn through a Compose Multiplatform build of the library, but the
      // screen frame is a stand-in (the extent has no viewport) and the font is the port's. Saying
      // that here rather than leaving each surface to work it out is what stops the editor
      // offering a Preview mode whose claim is false and the server picking a daemon by guessing
      // from a catalog id.
      //
      // `statusSemantics` rather than a field of its own because `CatalogCapabilityV1` is published
      // from compose-preview-contracts and cannot grow one from here; this map is the catalog's own
      // open vocabulary and already carries `adapterStatus` and `svgStatus`. Read back by
      // `UiBuilderPreviewSurfaces.from`.
      it.statusSemantics =
        JsonObject(
          base.statusSemantics +
            // A round watch screen against Wear Compose: grouped apart from the phone screens in
            // the
            // chooser, and offered no mobile pack — Wear Material 3 and Material 3 are not used
            // together, which is the rule this whole catalog is written around.
            (CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY to JsonPrimitive("wear")) +
            // The frame, which used to exist only as branches in the renderer: `wear-m3`'s screen
            // root was special-cased by id, and the measured content padding was a constant there
            // while the catalog's own `ui-builder.policy.json` declared it — the same three pairs,
            // written by a test in that repository and by hand here. The catalog states it now and
            // the renderer reads it, which is what makes the frame a catalog fact rather than a
            // Wear fact. `wear-m3-differences.json` recorded the gap in three `frame.*` exemptions;
            // they are gone, because both sides state this block.
            ("frame" to
              buildJsonObject {
                put("adapter", JsonPrimitive("frame/round-screen"))
                put("seedDevice", JsonPrimitive("id:wearos_small_round"))
                putJsonObject("geometry") {
                  put(
                    "contentPadding",
                    // `ScreenScaffoldContentPaddingTest` in wear-m3-catalog composes the real
                    // `ScreenScaffold` at each round size and asserts its own policy file equals
                    // the
                    // measurement; these are that measurement.
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
                  // The catalog's own sentence, and stated here rather than left to the reader to
                  // infer from `backend`: this is the lane that renders the design's own generated
                  // Kotlin against the real `androidx.wear.compose` AAR under Robolectric, which is
                  // the same lane that produces wear-m3-catalog's published stickers.
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
            // The other thing a catalog says about itself that is not a component: how the
            // builder's
            // insert panel shelves it. Until a catalog could declare this, the shelves were a table
            // in `:ui-builder` keyed by m3-catalog's ids, so `wear-m3` fell back to
            // Scaffolds/Containers/Composables — a statement about what each component may *hold*
            // rather than about what any of them is for. Same mechanism, and the same reason, as
            // `previewSurfaces` above; read back by `ComponentMenu.from`.
            //
            // It REPLACES the base catalog's declaration rather than merging with it: that one is
            // keyed by `m3/…` ids this catalog does not have, so a merge would leave every Wear
            // component unshelved while carrying entries for thirty-nine components that are gone.
            // The key as a literal, like `previewSurfaces` above: `ComponentMenu` lives in
            // `:ui-builder`, which this module must never depend on —
            // `checkUiBuilderRuntimeBoundary`
            // enforces the arrow, and the editor is above the runtime, not beside it.
            ("componentMenu" to wearComponentMenu())
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
        listOf(scaffold, transformingLazyColumn, listHeader, wearText, wearCard, wearButton) +
          wearOnly +
          sharedFoundation
    }
    .build()
}

/**
 * The call a Wear component is written as, and — where its `variant` picks between composables
 * rather than styling one — the others it can be, the way `m3/card`'s `code` lists all three cards
 * while naming one. The symbol is the simple name, as the packaged Material 3 catalog writes it: a
 * qualified one is what a component pack's record is recognised by.
 */
private fun wearCode(
  symbol: String,
  variantComposables: List<String> = emptyList(),
  pkg: String = "androidx.wear.compose.material3",
): CodeCapabilityV1 =
  CodeCapabilityV1.Builder(symbol)
    .also { it.imports = (listOf(symbol) + variantComposables).map { name -> "$pkg.$name" } }
    .build()
