package ee.schimke.composeai.uibuilder.export

/**
 * The traits a catalog gives a component so the builder can recognise what it is rather than its
 * id: a small closed vocabulary read across catalogs, `UiBuilder`-prefixed to avoid collisions.
 * Readers accept a trait in addition to the id they knew, so catalogs that publish none behave as
 * before. `UI_BUILDER_CATALOG_CONTRACT.md` § Builder roles lists the ids to retire.
 */
public object CatalogBuilderRoles {

  /**
   * The component draws a run of text: it gets the [FontSettings] properties, and fills an unseeded
   * text-accepting slot (preferring the slot owner's namespace).
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
