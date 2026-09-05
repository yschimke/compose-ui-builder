package ee.schimke.composeai.uibuilder

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
    )

  private val TABLE: Map<String, Map<String, List<StarterNode>>> =
    mapOf(
      // A button is its label. The generic fill got this one actively wrong: `content` accepts
      // `TextContent` *and* `IconContent`, the icon branch was tested first, and so every button
      // inserted from the palette arrived as a button containing a clock.
      "m3/button" to mapOf("content" to listOf(text("Button", "labelLarge"))),
      // The example from the goal. `content` takes exactly one `IconContent` child, so the only
      // decision here is which icon — and any icon reads as an icon button, while the enum's first
      // entry (`accessTime`) reads as a clock somebody forgot to change.
      "m3/icon-button" to mapOf("content" to listOf(icon("favorite", "Favorite"))),
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
      // Three is the smallest count that shows a list is a list — spacing, repetition and the
      // scroll direction are all invisible with one item.
      "layout/lazy-column" to
        mapOf("items" to itemCards("List item one", "List item two", "List item three")),
      "layout/lazy-row" to mapOf("items" to itemCards("Item one", "Item two", "Item three")),
      "layout/lazy-grid" to
        mapOf("items" to itemCards("Item one", "Item two", "Item three", "Item four")),
      "layout/horizontal-carousel" to
        mapOf("items" to itemCards("Item one", "Item two", "Item three")),
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

private fun column(vararg children: StarterNode): StarterNode =
  StarterNode(componentId = "layout/column", slots = mapOf("children" to children.toList()))

private fun tab(label: String, selected: Boolean = false): StarterNode =
  StarterNode(
    componentId = "m3/tab",
    properties = mapOf("selected" to starterBool(selected)),
    slots = mapOf("text" to listOf(text(label, "titleSmall"))),
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
