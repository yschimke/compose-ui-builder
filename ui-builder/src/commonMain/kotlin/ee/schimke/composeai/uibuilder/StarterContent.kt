package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a container arrives holding when it is inserted from the palette.
 *
 * ## Why a container should not arrive empty
 *
 * Insertion used to fill exactly the slots a component could not legally be without — the ones
 * whose cardinality declares a minimum — and to fill them with whatever the slot's accepted traits
 * implied: a `m3/text` reading "New text", a `m3/icon` showing the first icon in the enum, or a
 * bare `layout/box`. Everything else came in empty. That is defensible as a rule about validity and
 * it is the wrong thing to look at: a Card that is one line of "New text", a lazy column that is a
 * blank rectangle and a floating toolbar with nothing on it all render as something nobody would
 * have designed, so the first thing an operator does after every insert is repair the component
 * into the shape it obviously wanted.
 *
 * A component seeded with typical content starts at that shape instead. An icon button arrives
 * showing an icon, a button arrives reading "Button", a list arrives with three items in it. The
 * seeded children are ordinary nodes — selectable, editable, deletable — so the cost of a wrong
 * guess is one keystroke, while the cost of an empty container is retyping the same subtree every
 * time.
 *
 * ## What is deliberately not seeded
 *
 * The pure layout primitives: `layout/box`, `layout/row`, `layout/column` and `m3/surface`. They
 * exist to hold whatever is put in them and have no typical content to be right about, so anything
 * seeded there is a node the operator has to delete rather than one they would have drawn. The
 * dividing line is the one the goal states: a component complex enough that its *shape* is
 * recognisable — a button has a label, a list has items, a search field has a placeholder and a
 * magnifier — is seeded; a container whose shape is whatever it holds is not.
 *
 * ## The rules the table obeys
 *
 * Every entry is checked against the catalog before it is used, at
 * [ee.schimke.composeai.uibuilder.appendDefaultSubtree]'s call site, and an entry that does not
 * check out degrades to the old required-slot fill rather than failing the insert. `StarterContent`
 * is a nicety and must never be the reason a component cannot be added. `StarterContentTest` holds
 * the same rules as assertions, so a table entry that stops matching the catalog — a renamed slot,
 * a dropped icon key, a component that left the catalog — fails a test rather than silently
 * degrading in the product.
 *
 * A component the table says nothing about behaves exactly as it did before: required slots filled
 * generically, optional slots left empty.
 */
internal data class StarterNode(
  val componentId: String,
  /**
   * Encoded property values to set on the seeded node, over the component's own defaults.
   *
   * Encoded — `{"type":"string","value":"Button"}` — because that is what a document node holds; a
   * bare primitive here would produce a node the validator rejects and the renderer cannot read.
   */
  val properties: Map<String, JsonObject> = emptyMap(),
  /**
   * Children by slot name.
   *
   * A slot named here is authored exactly, and the seeded child does **not** then get its own
   * starter content: the entry has already said what belongs in it. A slot left unnamed falls back
   * to the ordinary expansion — that component's own starter entry, then the required-slot fill —
   * which is what lets `m3/search-bar` say nothing at all and still arrive holding a search field
   * with a placeholder and a magnifier in it.
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
   *
   * Most components need none: their defaults are already sensible, or the property is required and
   * the catalog's first allowed value answers it. This exists for the ones whose default is not a
   * value anybody wants to look at — `shape/colour-dot` forced it, because a dot with no colour and
   * no diameter is a dot you cannot see.
   */
  fun propertiesFor(componentId: String): Map<String, JsonObject> =
    PROPERTY_TABLE[componentId].orEmpty()

  /**
   * Remote Material 3's required values, seeded for the reasons Wear's are — and because here a
   * missing one is also a refusal: `RemoteCheckboxButton(checked = …)` has no default, so a
   * checkbox dropped without one is a widget that does not export.
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
    )

  /**
   * The A2UI basic catalog's required values that no neutral default can supply.
   *
   * Each is required by the catalog's own schema and none is a string, so the generic default — an
   * empty string — would be refused by validation and the insert with it: a Button has to say what
   * it dispatches, a Slider needs its `max`, a CheckBox its state, a ChoicePicker its options and
   * the selection among them. Seeded to the smallest value that draws as the component, so a drop
   * from the palette is a component rather than a refusal. Lists and objects use the builder's own
   * `list` and `object` wrappers, which the A2UI export lowers to plain JSON.
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

  private val PROPERTY_TABLE: Map<String, Map<String, JsonObject>> =
    mapOf(
      "shape/colour-dot" to
        mapOf("color" to starterLiteral("color", "#FF6750A4"), "diameterDp" to starterNumber(8)),
      // Ticked and on. Material draws an unchecked box as an empty square and an off switch as a
      // grey pill, and a palette drop that looks like neither a checkbox nor a switch is the case
      // starter content exists for. Turning one off is a click.
      "m3/checkbox" to mapOf("checked" to starterBool(true)),
      // One of a group is chosen, or the group is a row of empty circles.
      "m3/radio-button" to mapOf("selected" to starterBool(true)),
      // Something to look at. A slider at zero is a track with the thumb jammed against the left
      // end, and a progress indicator at zero is an empty line — both read as broken rather than as
      // new, which is the whole point of seeding a value.
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
      // Wear's own, and the reason each needs a seed is the reason its mobile counterpart does —
      // with one difference that matters. These are not drawn on the canvas at all: they show as a
      // named placeholder, and the picture comes from the Android preview. So a seeded `label` is
      // the *only* thing that distinguishes one dropped row from the next until somebody renders,
      // which makes the seed more load-bearing here than on the mobile side rather than less.
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
      // A dialog arrives *showing*. Its `visible` flag is what the generated code reads, and a
      // dialog seeded hidden is a node in the layers panel with nothing to look at on any surface —
      // including the Android one, which is where the whole point of this component is.
      WearScreenCodeExporter.ALERT_DIALOG to
        mapOf("title" to starterLiteral("string", "Are you sure?"), "visible" to starterBool(true)),
      WearScreenCodeExporter.CONFIRMATION_DIALOG to
        mapOf("text" to starterLiteral("string", "Done"), "visible" to starterBool(true)),
      WearScreenCodeExporter.OPEN_ON_PHONE_DIALOG to mapOf("visible" to starterBool(true)),
    ) + REMOTE_MATERIAL_3_PROPERTIES + A2UI_PROPERTIES

  private val TABLE: Map<String, Map<String, List<StarterNode>>> =
    mapOf(
      // A button is its label. The generic fill got this one actively wrong: `content` accepts
      // `TextContent` *and* `IconContent`, the icon branch was tested first, and so every button
      // inserted from the palette arrived as a button containing a clock.
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
      // The example from the goal. `content` takes exactly one `IconContent` child, so the only
      // decision here is which icon — and any icon reads as an icon button, while the enum's first
      // entry (`accessTime`) reads as a clock somebody forgot to change.
      "m3/icon-button" to mapOf("content" to listOf(icon("favorite", "Favorite"))),
      // The Wear pair, seeded for the same reasons as the mobile ones above: an icon button whose
      // one required child is missing is an empty circle, and a text button with no label is a
      // pill. `wear-m3/icon` takes the same icon keys `m3/icon` does — the vectors are
      // `androidx.compose.material.icons`, which both platforms share.
      WearScreenCodeExporter.ICON_BUTTON to mapOf("content" to listOf(wearIcon("favorite"))),
      WearScreenCodeExporter.TEXT_BUTTON to mapOf("content" to listOf(wearText("Button"))),
      // Required, not only nicer: `content` has a minimum of one, and without a seed the generic
      // fill went looking for `m3/text` in a catalog that has only `wear-m3/text` and refused the
      // insert — so a Wear Button or Card could not be added from the palette at all.
      WearScreenCodeExporter.BUTTON to mapOf("content" to listOf(wearText("Button"))),
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
      // Remote Material 3, labelled for the reason its Wear counterparts are: a button with no
      // label is a pill and a card with no content is an empty rounded rectangle. The label is
      // `m3/text`, which is what this palette's Text is and what exports as `RemoteText`.
      // The adaptive widget arrives with each role filled, so both preview sizes have something to
      // show: the Small panes keep the headline and the button and drop the supporting line.
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
      // A label and a placeholder, which is the field Material's own samples draw. An empty text
      // field is a rounded rectangle: nothing about it says what it is for, and `label` is the part
      // a form is unreadable without.
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
      // The example the goal named: a dialog that arrives saying something and offering the two
      // answers every dialog offers. `confirmButton` has a minimum of one, so without this entry
      // the required fill would put an empty `layout/box` where the OK button belongs — a dialog
      // with a blank rectangle for its primary action.
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
      "layout/supporting-pane-scaffold" to
        mapOf(
          "mainPane" to listOf(column(text("Main pane", "titleMedium"))),
          "supportingPane" to listOf(column(text("Supporting pane", "titleMedium"))),
        ),
      // Three destinations with the first selected, over one content pane: a rail or bar with one
      // item is not navigation, and one where none is selected draws no indicator. The type is the
      // library's per frame, so the same insert is a rail on a tablet and a bar on a phone.
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
      // One template, and it reads the row rather than holding a literal: an inserted loop that
      // drew the same words three times would look like a bug in the loop rather than like a
      // design waiting for its data.
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
 * `wear-m3/text`, seeded the way [text] seeds the mobile one — minus the style.
 *
 * No `style` argument: Wear's type scale is its own, and a role seeded from the mobile catalog's
 * names would put a value in the document that the Wear generator would then have to refuse. The
 * component's default is Wear's own, which is the right answer.
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
 * A card per label, each holding one line.
 *
 * Cards rather than bare text because every one of the four repeating containers accepts a card —
 * `ListItem`, `GridItem` and `CarouselItem` are all traits it declares — and because an item with a
 * surface behind it is what makes the spacing between items visible. Their `content` is authored
 * here, which is what stops each item from also picking up `m3/card`'s own two-line starter.
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
