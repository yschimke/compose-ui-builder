package ee.schimke.composeai.uibuilder.export

/**
 * The traits a catalog gives a component so the builder can recognise what the component IS, rather
 * than recognising its id.
 *
 * A catalog's traits are otherwise its own: slot acceptance is a relation inside one catalog, and
 * no reader compares `TextContent` across two of them. These few are the exception — a small,
 * closed vocabulary the builder reads across every catalog — and each replaces an id the builder
 * used to know (`FontSettings.TEXT_COMPONENTS`, `CanvasPopOut`'s scroller sets, the reducer's
 * `a2ui/Text`). The `UiBuilder` prefix is what keeps them from colliding with a catalog's own trait
 * of the same meaning.
 *
 * Every reader takes a trait **in addition to** the id it knew, never instead, so a catalog that
 * publishes none of these is drawn and edited exactly as before. When every catalog publishes them,
 * the ids go; `UI_BUILDER_CATALOG_CONTRACT.md` § Builder roles lists which.
 */
public object CatalogBuilderRoles {

  /**
   * The component draws a run of text.
   *
   * The builder gives it the [FontSettings] properties, and fills a slot that accepts text with it
   * when the catalog seeds nothing there — preferring the one in the slot owner's own namespace.
   */
  public const val TEXT: String = "UiBuilderText"

  /** The component's content scrolls vertically on the device, so the editor can pop it out. */
  public const val VERTICAL_SCROLLER: String = "UiBuilderVerticalScroller"

  /** The component's content scrolls horizontally on the device. */
  public const val HORIZONTAL_SCROLLER: String = "UiBuilderHorizontalScroller"

  /**
   * The component a launcher widget is rooted on. Spelled before this vocabulary existed, so it
   * keeps its name; see [CatalogExportRouting.LAUNCHER_HOST_TRAIT].
   */
  public const val LAUNCHER_WIDGET_HOST: String = CatalogExportRouting.LAUNCHER_HOST_TRAIT

  /** Every role, for a gate that checks a catalog spells them as this build reads them. */
  public val ALL: Set<String> =
    linkedSetOf(TEXT, VERTICAL_SCROLLER, HORIZONTAL_SCROLLER, LAUNCHER_WIDGET_HOST)

  /** Whether a component with [componentId] and [traits] draws text, by role or by legacy id. */
  public fun isText(componentId: String, traits: Collection<String>): Boolean =
    TEXT in traits || componentId in FontSettings.TEXT_COMPONENTS
}
