package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.VariableFontText
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a container arrives holding when it is inserted from the palette.
 *
 * Components with a recognisable shape (a button has a label, a list has items, a search field has
 * a placeholder and a magnifier) are seeded with typical content, so inserts don't need repairing.
 * Seeded children are ordinary nodes, so a wrong guess costs one keystroke.
 *
 * Not seeded: the pure layout primitives (`layout/box`, `layout/row`, `layout/column`,
 * `m3/surface`), whose shape is whatever they hold.
 *
 * Every entry is checked against the catalog at
 * [ee.schimke.composeai.uibuilder.appendDefaultSubtree]'s call site; one that doesn't check out
 * degrades to the required-slot fill rather than failing the insert. `StarterContentTest` asserts
 * the same rules. Components the table doesn't mention get required slots filled generically and
 * optional slots left empty.
 */
internal data class StarterNode(
  val componentId: String,
  /**
   * Encoded property values to set on the seeded node, over the component's own defaults (e.g.
   * `{"type":"string","value":"Button"}`, as a document node holds them).
   */
  val properties: Map<String, JsonObject> = emptyMap(),
  /**
   * Children by slot name. A named slot is authored exactly and its children get no further starter
   * content. An unnamed slot falls back to the ordinary expansion (the child's own starter entry,
   * then the required-slot fill).
   */
  val slots: Map<String, List<StarterNode>> = emptyMap(),
)

internal object StarterContent {

  /** The seed for [componentId], by slot name, or empty when the table says nothing about it. */
  fun forComponent(componentId: String): Map<String, List<StarterNode>> =
    TABLE[componentId].orEmpty()

  /**
   * Every component the table seeds. Exists for the test that checks the table against a catalog.
   */
  val componentIds: Set<String>
    get() = TABLE.keys + PROPERTY_TABLE.keys

  /**
   * Values to set on [componentId] itself when it is inserted, over the catalog's own defaults.
   * Only for components whose default is invisible (e.g. `shape/colour-dot` with no colour or
   * diameter).
   */
  fun propertiesFor(componentId: String): Map<String, JsonObject> =
    PROPERTY_TABLE[componentId].orEmpty()

  /**
   * Remote Material 3's required values; a missing one also blocks export
   * (`RemoteCheckboxButton(checked = …)` has no default).
   */
  private val REMOTE_MATERIAL_3_PROPERTIES: Map<String, Map<String, JsonObject>> =
    mapOf(
      "remote-m3/remote-checkbox-button" to mapOf("checked" to starterBool(true)),
      "remote-m3/remote-split-checkbox-button" to mapOf("checked" to starterBool(true)),
      "remote-m3/remote-switch-button" to mapOf("checked" to starterBool(true)),
      "remote-m3/remote-split-switch-button" to mapOf("checked" to starterBool(true)),
      "remote-m3/remote-radio-button" to mapOf("selected" to starterBool(true)),
      "remote-m3/remote-split-radio-button" to mapOf("selected" to starterBool(true)),
      "remote-m3/remote-circular-progress-indicator" to mapOf("progress" to starterFraction(0.6)),
      "remote-m3/remote-linear-progress-indicator" to mapOf("progress" to starterFraction(0.6)),
      "remote-m3/remote-curved-progress-indicator" to mapOf("progress" to starterFraction(0.6)),
      "remote-m3/remote-slider" to mapOf("value" to starterFraction(0.5)),
      "remote-m3/remote-stepper" to mapOf("value" to starterFraction(0.5)),
      "remote-m3/remote-text" to mapOf("text" to starterLiteral("string", "Text")),
    )

  /**
   * The A2UI basic catalog's required values that no neutral default can supply. Each is
   * schema-required and non-string, so the empty-string default would fail validation (Button's
   * action, Slider's `max`, CheckBox's state, ChoicePicker's options and selection). Seeded to the
   * smallest value that draws; lists and objects use the builder's `list`/`object` wrappers,
   * lowered to plain JSON by the A2UI export.
   */
  private val A2UI_PROPERTIES: Map<String, Map<String, JsonObject>> =
    mapOf(
      "a2ui/Button" to mapOf("action" to a2uiEvent("submit")),
      "a2ui/CheckBox" to
        mapOf("label" to starterLiteral("string", "Checkbox"), "value" to starterBool(true)),
      "a2ui/Slider" to mapOf("value" to starterNumber(50), "max" to starterNumber(100)),
      "a2ui/TextField" to mapOf("label" to starterLiteral("string", "Label")),
      "a2ui/ChoicePicker" to
        mapOf(
          "options" to
            starterList(
              listOf("One", "Two", "Three").map { label ->
                starterObject(
                  "label" to starterLiteral("string", label),
                  "value" to starterLiteral("string", label.lowercase()),
                )
              }
            ),
          "value" to starterList(listOf(starterLiteral("string", "one"))),
        ),
    )

  private val VARIABLE_FONT_TEXT_STARTER: Map<String, JsonObject> =
    mapOf(
      "text" to starterLiteral("string", "Flex"),
      "font" to starterLiteral("enum", VariableFontText.Font.RobotoFlex.wire),
    )

  private val PROPERTY_TABLE: Map<String, Map<String, JsonObject>> =
    mapOf(
      "shape/colour-dot" to
        mapOf("color" to starterLiteral("color", "#FF6750A4"), "diameterDp" to starterNumber(8)),
      // A word to see the axes move on, rather than an empty outline.
      VariableFontText.M3_ID to VARIABLE_FONT_TEXT_STARTER,
      VariableFontText.WEAR_ID to VARIABLE_FONT_TEXT_STARTER,
      // Lists hold cards, which need spacing so they don't run together.
      "layout/lazy-row" to mapOf("horizontalSpacingDp" to starterNumber(8)),
      "layout/lazy-column" to mapOf("verticalSpacingDp" to starterNumber(8)),
      "layout/lazy-grid" to
        mapOf("horizontalSpacingDp" to starterNumber(8), "verticalSpacingDp" to starterNumber(8)),
      "m3/checkbox" to mapOf("checked" to starterBool(true)),
      // One of a group is chosen, or the group is a row of empty circles.
      "m3/radio-button" to mapOf("selected" to starterBool(true)),
      // Mid-range values, so a new slider or progress indicator doesn't look broken.
      "m3/slider" to mapOf("value" to starterFraction(0.5), "valueTo" to starterNumber(1)),
      "m3/progress-indicator" to mapOf("progress" to starterFraction(0.6)),
      "m3/switch" to mapOf("checked" to starterBool(true)),
      // Zero is already what the catalog's neutral default writes; it is spelled out because a tab
      // row is required to carry the index and a row with none draws no indicator at all.
      "m3/primary-tab-row" to mapOf("selectedIndex" to starterNumber(0)),
      "m3/primary-scrollable-tab-row" to mapOf("selectedIndex" to starterNumber(0)),
      // An item has to say whether it is the current destination, and one the author has to set
      // first is one that inserts looking wrong in a rail or bar where another is selected.
      "m3/navigation-suite-item" to mapOf("selected" to starterBool(false)),
      // Wear rows show as named placeholders on the canvas, so a seeded `label` is what
      // distinguishes them until rendered.
      WearScreenCodeExporter.CHECKBOX_BUTTON to
        mapOf("label" to starterLiteral("string", "Checkbox"), "checked" to starterBool(true)),
      WearScreenCodeExporter.SWITCH_BUTTON to
        mapOf("label" to starterLiteral("string", "Switch"), "checked" to starterBool(true)),
      WearScreenCodeExporter.RADIO_BUTTON to
        mapOf("label" to starterLiteral("string", "Option"), "selected" to starterBool(true)),
      WearScreenCodeExporter.LIST_SUB_HEADER to
        mapOf("text" to starterLiteral("string", "Section")),
      // A slider at zero is a track with the thumb against the decrement button, which reads as
      // broken; a `valueTo` of one and a value in the middle reads as new.
      WearScreenCodeExporter.SLIDER to
        mapOf("value" to starterFraction(0.5), "valueTo" to starterNumber(1)),
      WearScreenCodeExporter.STEPPER to
        mapOf("value" to starterFraction(0.5), "valueTo" to starterNumber(1)),
      WearScreenCodeExporter.PROGRESS_INDICATOR to mapOf("progress" to starterFraction(0.6)),
      // A dialog arrives showing (`visible`), otherwise it has nothing to look at on any surface.
      WearScreenCodeExporter.ALERT_DIALOG to
        mapOf("title" to starterLiteral("string", "Are you sure?"), "visible" to starterBool(true)),
      WearScreenCodeExporter.CONFIRMATION_DIALOG to
        mapOf("text" to starterLiteral("string", "Done"), "visible" to starterBool(true)),
      WearScreenCodeExporter.OPEN_ON_PHONE_DIALOG to mapOf("visible" to starterBool(true)),
    ) + REMOTE_MATERIAL_3_PROPERTIES + A2UI_PROPERTIES

  private val TABLE: Map<String, Map<String, List<StarterNode>>> =
    mapOf(
      // A button is its label (the generic fill would pick an icon, since `content` accepts both).
      "m3/button" to mapOf("content" to listOf(text("Button", "labelLarge"))),
      // The same reason, in the A2UI palette: a button is its label.
      "a2ui/Button" to
        mapOf(
          "child" to
            listOf(
              StarterNode(
                componentId = "a2ui/Text",
                properties = mapOf("text" to starterLiteral("string", "Button")),
              )
            )
        ),
      // `content` takes exactly one `IconContent` child; seed a recognisable icon rather than the
      // enum's first (`accessTime`, a clock).
      "m3/icon-button" to mapOf("content" to listOf(icon("favorite", "Favorite"))),
      // The Wear pair: an icon button without its icon is an empty circle and a text button without
      // a label a pill. `wear-m3/icon` shares `m3/icon`'s keys.
      WearScreenCodeExporter.ICON_BUTTON to mapOf("content" to listOf(wearIcon("favorite"))),
      WearScreenCodeExporter.TEXT_BUTTON to mapOf("content" to listOf(wearText("Button"))),
      // Required: `content` has a minimum of one, and the generic fill would look for `m3/text` in
      // a catalog that only has `wear-m3/text` and refuse the insert.
      WearScreenCodeExporter.BUTTON to mapOf("content" to listOf(wearText("Button"))),
      // A list of three rows, the way a Wear list is used: an empty one fills the screen with
      // nothing, which on the canvas is an empty watch and on the palette an empty black circle.
      WearScreenCodeExporter.TRANSFORMING_LAZY_COLUMN to
        mapOf(
          "items" to
            listOf("Row one", "Row two", "Row three").map { label ->
              StarterNode(
                WearScreenCodeExporter.BUTTON,
                slots = mapOf("content" to listOf(wearText(label))),
              )
            }
        ),
      WearScreenCodeExporter.CARD to mapOf("content" to listOf(wearText("Card"))),
      // Buttons, because `ButtonGroup` lays out buttons — anything else has no scope to be laid
      // out in, and the export refuses it.
      WearScreenCodeExporter.BUTTON_GROUP to
        mapOf(
          "children" to
            listOf(
              StarterNode(WearScreenCodeExporter.BUTTON),
              StarterNode(WearScreenCodeExporter.BUTTON),
            )
        ),
      WearScreenCodeExporter.EDGE_BUTTON to mapOf("content" to listOf(wearText("Done"))),
      // The adaptive widget arrives with each role filled, so both preview sizes have something to
      // show (Small drops the supporting line). Remote Material 3 labels are `m3/text`, exported as
      // `RemoteText`.
      AdaptiveWearWidget.COMPONENT_ID to
        mapOf(
          AdaptiveWearWidget.HEADLINE to listOf(remoteText("Headline")),
          AdaptiveWearWidget.SUPPORTING to listOf(remoteText("Supporting text")),
          AdaptiveWearWidget.ACTION to
            listOf(
              StarterNode(
                "remote-m3/remote-button",
                slots = mapOf("content" to listOf(remoteText("Open"))),
              )
            ),
        ),
      "remote-m3/remote-button" to mapOf("content" to listOf(remoteText("Button"))),
      "remote-m3/remote-compact-button" to mapOf("label" to listOf(remoteText("Compact"))),
      "remote-m3/remote-edge-button" to mapOf("content" to listOf(remoteText("Done"))),
      "remote-m3/remote-text-button" to mapOf("content" to listOf(remoteText("OK"))),
      "remote-m3/remote-icon-button" to mapOf("content" to listOf(remoteText("+"))),
      "remote-m3/remote-button-group" to
        mapOf(
          "content" to
            listOf(StarterNode("remote-m3/remote-button"), StarterNode("remote-m3/remote-button"))
        ),
      "remote-m3/remote-card" to mapOf("content" to listOf(remoteText("Card"))),
      "remote-m3/remote-outlined-card" to mapOf("content" to listOf(remoteText("Card"))),
      "remote-m3/remote-title-card" to
        mapOf("title" to listOf(remoteText("Title")), "content" to listOf(remoteText("Card"))),
      "remote-m3/remote-app-card" to
        mapOf(
          "appName" to listOf(remoteText("App")),
          "title" to listOf(remoteText("Title")),
          "content" to listOf(remoteText("Card")),
        ),
      "remote-m3/remote-checkbox-button" to mapOf("label" to listOf(remoteText("Checkbox"))),
      "remote-m3/remote-split-checkbox-button" to mapOf("label" to listOf(remoteText("Checkbox"))),
      "remote-m3/remote-switch-button" to mapOf("label" to listOf(remoteText("Switch"))),
      "remote-m3/remote-split-switch-button" to mapOf("label" to listOf(remoteText("Switch"))),
      "remote-m3/remote-radio-button" to mapOf("label" to listOf(remoteText("Option"))),
      "remote-m3/remote-split-radio-button" to mapOf("label" to listOf(remoteText("Option"))),
      "m3/filter-chip" to mapOf("label" to listOf(text("Filter", "labelLarge"))),
      "m3/center-aligned-top-app-bar" to mapOf("title" to listOf(text("Title", "titleLarge"))),
      // A label and a placeholder, as Material's samples draw a text field.
      "m3/text-field" to
        mapOf(
          "label" to listOf(text("Label", "bodyLarge")),
          "placeholder" to listOf(text("Placeholder", "bodyLarge")),
        ),
      // Headline over supporting text, which is the two-line list item Material draws and the shape
      // a bare headline does not suggest.
      "m3/list-item" to
        mapOf(
          "headline" to listOf(text("List item", "bodyLarge")),
          "supporting" to listOf(text("Supporting text", "bodyMedium")),
        ),
      // Three tabs with the first selected: one tab is not a tab row, and a row where none is
      // selected draws no indicator at all.
      "m3/primary-tab-row" to
        mapOf("tabs" to listOf(tab("Tab 1", selected = true), tab("Tab 2"), tab("Tab 3"))),
      // Five, one more than a phone's width divides legibly, so the insert shows what scrolling
      // is for.
      "m3/primary-scrollable-tab-row" to
        mapOf(
          "tabs" to
            listOf(
              tab("Tab 1", selected = true),
              tab("Tab 2"),
              tab("Tab 3"),
              tab("Tab 4"),
              tab("Tab 5"),
            )
        ),
      "m3/tab" to mapOf("text" to listOf(text("Tab", "titleSmall"))),
      // A dialog with a message and the two usual answers; `confirmButton` has a minimum of one,
      // which the generic fill would satisfy with an empty box.
      "m3/dialog" to
        mapOf(
          "title" to listOf(text("Dialog title", "headlineSmall")),
          "text" to
            listOf(text("Supporting text explaining what this dialog is asking.", "bodyMedium")),
          // Material's dialog actions are text buttons, and the dismissing one comes first.
          "dismissButton" to listOf(textButton("Cancel")),
          "confirmButton" to listOf(textButton("OK")),
        ),
      // Title over supporting text: the shape every Material card sample has, and the shape a card
      // holding one line of "New text" does not.
      "m3/card" to
        mapOf(
          "content" to
            listOf(
              column(
                text("Card title", "titleMedium"),
                text("Supporting text for this card.", "bodyMedium"),
              )
            )
        ),
      // Reached without an entry of its own on `m3/search-bar`, whose required `inputField` fill
      // resolves to this component and then picks this entry up.
      "m3/search-input-field" to
        mapOf(
          "placeholder" to listOf(text("Search", "bodyLarge")),
          "leadingIcon" to listOf(icon("search", "Search")),
        ),
      "layout/scaffold" to
        mapOf(
          "content" to
            listOf(
              column(
                text("Headline", "headlineSmall"),
                text("Body text goes here.", "bodyMedium"),
              )
            )
        ),
      // Both panes, including the optional one: a supporting-pane scaffold showing one pane is
      // indistinguishable from the plain scaffold above it in the palette.
      "layout/list-detail-pane-scaffold" to
        mapOf(
          "listPane" to listOf(column(text("List", "titleMedium"))),
          "detailPane" to listOf(column(text("Detail", "titleMedium"))),
        ),
      "layout/supporting-pane-scaffold" to
        mapOf(
          "mainPane" to listOf(column(text("Main pane", "titleMedium"))),
          "supportingPane" to listOf(column(text("Supporting pane", "titleMedium"))),
        ),
      // Three destinations with the first selected, over one content pane. The library picks rail
      // or bar per frame.
      "m3/navigation-suite-scaffold" to
        mapOf(
          "navigationItems" to
            listOf(
              navigationItem("filled/home", "Home", selected = true),
              navigationItem("filled/search", "Search"),
              navigationItem("filled/settings", "Settings"),
            ),
          "content" to listOf(column(text("Content", "titleMedium"))),
        ),
      "m3/navigation-suite-item" to
        mapOf(
          "icon" to listOf(icon("filled/home", "Home")),
          "label" to listOf(text("Home", "labelMedium")),
        ),
      // Three is the smallest count that shows a list is a list — spacing, repetition and the
      // scroll direction are all invisible with one item.
      "layout/lazy-column" to
        mapOf("items" to itemCards("List item one", "List item two", "List item three")),
      "layout/lazy-row" to mapOf("items" to itemCards("Item one", "Item two", "Item three")),
      "layout/lazy-grid" to
        mapOf("items" to itemCards("Item one", "Item two", "Item three", "Item four")),
      "layout/horizontal-carousel" to
        mapOf("items" to itemCards("Item one", "Item two", "Item three")),
      // The template reads the row, so the three rows differ.
      "layout/for-each" to mapOf("template" to listOf(rowTemplate())),
      "m3/horizontal-floating-toolbar" to
        mapOf(
          "content" to
            listOf(
              iconButton("edit", "Edit"),
              iconButton("share", "Share"),
              iconButton("delete", "Delete"),
            )
        ),
    )
}

/** A card holding one text bound to the row's `label` — what a starter loop draws per row. */
private fun rowTemplate(): StarterNode =
  StarterNode(
    componentId = "m3/card",
    slots =
      mapOf(
        "content" to
          listOf(
            StarterNode(
              componentId = "m3/text",
              properties =
                mapOf(
                  "text" to
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("binding"),
                        "value" to JsonPrimitive("label"),
                      )
                    ),
                  "style" to starterLiteral("enum", "bodyMedium"),
                ),
            )
          )
      ),
  )

private fun text(value: String, style: String): StarterNode =
  StarterNode(
    componentId = "m3/text",
    properties =
      mapOf("text" to starterLiteral("string", value), "style" to starterLiteral("enum", style)),
  )

private fun icon(iconKey: String, contentDescription: String): StarterNode =
  StarterNode(
    componentId = "m3/icon",
    properties =
      mapOf(
        "iconKey" to starterLiteral("enum", iconKey),
        "contentDescription" to starterLiteral("string", contentDescription),
      ),
  )

/**
 * `wear-m3/text`, seeded like [text] but without a `style`: Wear's type scale is its own, and its
 * default is right.
 */
private fun wearText(value: String): StarterNode =
  StarterNode(
    componentId = WearScreenCodeExporter.TEXT,
    properties = mapOf("text" to starterLiteral("string", value)),
  )

/** A widget body's text: `m3/text`, which a Remote catalog writes as `RemoteText`. */
private fun remoteText(value: String): StarterNode =
  StarterNode(
    componentId = "m3/text",
    properties = mapOf("text" to starterLiteral("string", value)),
  )

/** `wear-m3/icon`, off the same key table `m3/icon` uses — the vectors are shared. */
private fun wearIcon(iconKey: String): StarterNode =
  StarterNode(
    componentId = WearScreenCodeExporter.ICON,
    properties = mapOf("iconKey" to starterLiteral("enum", iconKey)),
  )

private fun column(vararg children: StarterNode): StarterNode =
  StarterNode(componentId = "layout/column", slots = mapOf("children" to children.toList()))

private fun tab(label: String, selected: Boolean = false): StarterNode =
  StarterNode(
    componentId = "m3/tab",
    properties = mapOf("selected" to starterBool(selected)),
    slots = mapOf("text" to listOf(text(label, "titleSmall"))),
  )

private fun navigationItem(
  iconKey: String,
  label: String,
  selected: Boolean = false,
): StarterNode =
  StarterNode(
    componentId = "m3/navigation-suite-item",
    properties = mapOf("selected" to starterBool(selected)),
    slots =
      mapOf(
        "icon" to listOf(icon(iconKey, label)),
        "label" to listOf(text(label, "labelMedium")),
      ),
  )

private fun textButton(label: String): StarterNode =
  StarterNode(
    componentId = "m3/button",
    properties = mapOf("style" to starterLiteral("enum", "text")),
    slots = mapOf("content" to listOf(text(label, "labelLarge"))),
  )

private fun iconButton(iconKey: String, contentDescription: String): StarterNode =
  StarterNode(
    componentId = "m3/icon-button",
    slots = mapOf("content" to listOf(icon(iconKey, contentDescription))),
  )

/**
 * A card per label, each holding one line. Every repeating container accepts a card, and the
 * surface makes item spacing visible. Authored `content` stops each card picking up `m3/card`'s own
 * starter.
 */
private fun itemCards(vararg labels: String): List<StarterNode> = labels.map { label ->
  StarterNode(
    componentId = "m3/card",
    slots = mapOf("content" to listOf(text(label, "bodyLarge"))),
  )
}

private fun starterLiteral(type: String, value: String): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

private fun starterNumber(value: Int): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(value)))

private fun starterFraction(value: Double): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(value)))

private fun starterBool(value: Boolean): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive("bool"), "value" to JsonPrimitive(value)))

private fun starterObject(vararg fields: Pair<String, JsonObject>): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive("object"), "fields" to JsonObject(mapOf(*fields))))

private fun starterList(values: List<JsonObject>): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive("list"), "values" to JsonArray(values)))

/** An A2UI `action` that dispatches the event [name] to the agent. */
private fun a2uiEvent(name: String): JsonObject =
  starterObject("event" to starterObject("name" to starterLiteral("string", name)))
