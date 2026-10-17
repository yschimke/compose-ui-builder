package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.NewDesignTypeChoice
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignTemplate
import ee.schimke.composeai.uibuilder.editor.homeDesignKindLabel
import ee.schimke.composeai.uibuilder.editor.isWidgetDesign
import ee.schimke.composeai.uibuilder.editor.newDesignTargetGroups
import ee.schimke.composeai.uibuilder.editor.newDesignTypeChoice
import ee.schimke.composeai.uibuilder.editor.newDesignTypeOptions
import ee.schimke.composeai.uibuilder.editor.templatesForTarget
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The New design form's "What are you designing?" row: the built-in words where the catalog says
 * nothing, the catalog's own where it does, every catalog when a brand narrowed them, and a single
 * catalog's template groups when those are the only choice.
 */
class NewDesignTypesTest {
  private fun card(
    id: String,
    label: String = id,
    templates: List<UiBuilderNewDesignTemplate> =
      listOf(UiBuilderNewDesignTemplate("blank", "Blank", "")),
    description: String? = null,
    primary: Boolean? = null,
    platform: UiBuilderCatalogPlatform = UiBuilderCatalogPlatform.MOBILE,
  ) =
    UiBuilderNewDesignCatalog(
      systemId = id,
      label = label,
      templates = templates,
      platform = platform,
      description = description,
      primary = primary,
    )

  private val m3 = card("m3-catalog", "Mobile app", description = "Phone & tablet", primary = true)
  private val wear =
    card(
      "wear-m3",
      "Wear app",
      description = "Watch screens",
      primary = true,
      platform = UiBuilderCatalogPlatform.WEAR,
    )
  private val remote =
    card(
      "remote-m3",
      "Remote Compose",
      description = "Watch widgets & clocks",
      primary = true,
      platform = UiBuilderCatalogPlatform.REMOTE_COMPOSE,
    )
  private val launcher =
    card("remote-widgets", "Launcher widget", platform = UiBuilderCatalogPlatform.REMOTE_COMPOSE)
  private val a2ui = card("a2ui-catalog", "A2UI surface", platform = UiBuilderCatalogPlatform.A2UI)

  private val haTemplates =
    listOf(
      UiBuilderNewDesignTemplate("tile", "Tile card", "", group = "Dashboard card"),
      UiBuilderNewDesignTemplate("entities", "Entities card", "", group = "Dashboard card"),
      UiBuilderNewDesignTemplate("wear-entity", "Entity widget", "", group = "Wear widget"),
      UiBuilderNewDesignTemplate("blank", "Blank", ""),
      UiBuilderNewDesignTemplate("launcher-entity", "Entity widget", "", group = "Launcher widget"),
    )
  private val ha =
    card(
      "ha-cards",
      "Home Assistant",
      haTemplates,
      description = "Dashboard cards and widgets",
      primary = true,
    )

  @Test
  fun `type cards use the catalogs declared copy and primary placement`() {
    val options = newDesignTypeOptions(listOf(m3, wear, remote, launcher, a2ui))
    assertEquals(
      listOf(
        NewDesignTypeChoice("m3-catalog", "Mobile app", "Phone & tablet"),
        NewDesignTypeChoice("wear-m3", "Wear app", "Watch screens"),
        NewDesignTypeChoice("remote-m3", "Remote Compose", "Watch widgets & clocks"),
      ),
      options.primary,
    )
    assertEquals(listOf("remote-widgets", "a2ui-catalog"), options.other.map { it.systemId })
    assertFalse(options.byGroup)
  }

  @Test
  fun `a catalog supplies its description and primary placement`() {
    assertEquals(
      NewDesignTypeChoice("ha-cards", "Home Assistant", "Dashboard cards and widgets"),
      newDesignTypeChoice(ha),
    )
    // Described, even a built-in id reads as the catalog says.
    assertEquals(
      NewDesignTypeChoice("m3-catalog", "Material 3", "Any Android screen"),
      newDesignTypeChoice(m3.copy(label = "Material 3", description = "Any Android screen")),
    )
    // Undescribed and unknown: its label alone.
    assertEquals(
      NewDesignTypeChoice("remote-widgets", "Launcher widget", null),
      newDesignTypeChoice(launcher),
    )
    val options = newDesignTypeOptions(listOf(m3, wear, remote, ha, a2ui))
    assertEquals(
      listOf("m3-catalog", "wear-m3", "remote-m3", "ha-cards"),
      options.primary.map { it.catalogSystemId },
    )
    // And `primary: false` steps a built-in one out of the row.
    assertEquals(
      listOf("m3-catalog", "remote-m3"),
      newDesignTypeOptions(listOf(m3, wear.copy(primary = false), remote)).primary.map {
        it.catalogSystemId
      },
    )
  }

  @Test
  fun `a brand that narrows the catalogs shows every one of them in the row`() {
    val brand = UiBuilderBrand(name = "Home Assistant", catalogs = listOf("ha-cards", "ha-tiles"))
    val tiles = card("ha-tiles", "Tiles")
    val options = newDesignTypeOptions(listOf(ha, tiles, a2ui), brand)
    assertEquals(
      listOf("ha-cards", "ha-tiles", "a2ui-catalog"),
      options.primary.map { it.catalogSystemId },
    )
    assertTrue(options.other.isEmpty())
    // A brand that only renames the product narrows nothing.
    assertEquals(
      listOf("a2ui-catalog"),
      newDesignTypeOptions(listOf(m3, a2ui), UiBuilderBrand(name = "Acme")).other.map {
        it.systemId
      },
    )
  }

  @Test
  fun `one catalog with grouped templates offers its groups as the targets`() {
    val options = newDesignTypeOptions(listOf(ha), UiBuilderBrand(catalogs = listOf("ha-cards")))
    assertTrue(options.byGroup)
    assertEquals(
      listOf(
        NewDesignTypeChoice("ha-cards", "Dashboard card", "3 starting points", "Dashboard card"),
        NewDesignTypeChoice("ha-cards", "Wear widget", "2 starting points", "Wear widget"),
        NewDesignTypeChoice("ha-cards", "Launcher widget", "2 starting points", "Launcher widget"),
      ),
      options.primary,
    )
    // An ungrouped template (the generic blank) belongs to every target.
    assertEquals(
      listOf("wear-entity", "blank"),
      templatesForTarget(ha, "Wear widget").map { it.id },
    )
    assertEquals(haTemplates, templatesForTarget(ha, null))
    // Beside another catalog the choice is between catalogs again, and the groups head sections.
    assertFalse(newDesignTypeOptions(listOf(ha, m3)).byGroup)
  }

  @Test
  fun `one group, or none, is no choice`() {
    assertEquals(emptyList(), newDesignTargetGroups(m3))
    val oneGroup =
      card(
        "remote-m3",
        templates =
          listOf(
            UiBuilderNewDesignTemplate("a", "A", "", group = "Wear widget"),
            UiBuilderNewDesignTemplate("b", "B", ""),
          ),
      )
    assertEquals(emptyList(), newDesignTargetGroups(oneGroup))
    assertFalse(newDesignTypeOptions(listOf(oneGroup)).byGroup)
  }

  @Test
  fun `a design's kind and widget shape come from its catalog`() {
    val offered = listOf(m3, remote, launcher, ha)
    assertEquals("Mobile app", homeDesignKindLabel("m3-catalog", offered))
    assertEquals("Remote Compose", homeDesignKindLabel("remote-m3", offered))
    assertEquals("Home Assistant", homeDesignKindLabel("ha-cards", offered))
    assertEquals("Launcher widget", homeDesignKindLabel("remote-widgets", offered))
    assertEquals("a2ui-catalog", homeDesignKindLabel("a2ui-catalog", emptyList()))
    assertEquals("gone-catalog", homeDesignKindLabel("gone-catalog", offered))

    assertTrue(isWidgetDesign("remote-m3", offered))
    assertTrue(isWidgetDesign("remote-widgets", offered))
    assertFalse(isWidgetDesign("m3-catalog", offered))
    assertFalse(isWidgetDesign("ha-cards", offered))
    // A missing catalog has no platform metadata.
    assertFalse(isWidgetDesign("remote-m3", emptyList()))
    assertFalse(isWidgetDesign("gone-catalog", emptyList()))
  }
}
