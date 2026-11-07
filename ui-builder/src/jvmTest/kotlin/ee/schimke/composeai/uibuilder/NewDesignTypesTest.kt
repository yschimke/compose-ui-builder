package ee.schimke.composeai.uibuilder

import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.NewDesignDevice
import ee.schimke.composeai.uibuilder.editor.NewDesignTypeChoice
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignTemplate
import ee.schimke.composeai.uibuilder.editor.homeDesignKindLabel
import ee.schimke.composeai.uibuilder.editor.isWidgetDesign
import ee.schimke.composeai.uibuilder.editor.newDesignAsksDeviceFirst
import ee.schimke.composeai.uibuilder.editor.newDesignDevice
import ee.schimke.composeai.uibuilder.editor.newDesignDeviceGroups
import ee.schimke.composeai.uibuilder.editor.newDesignTargetGroups
import ee.schimke.composeai.uibuilder.editor.newDesignTemplateColumns
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
    device: String? = null,
  ) =
    UiBuilderNewDesignCatalog(
      systemId = id,
      label = label,
      templates = templates,
      platform = platform,
      description = description,
      primary = primary,
      device = device,
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
  fun `with no catalog asking for the row, the first ones fill it`() {
    val quiet = listOf(m3, wear, remote, launcher, a2ui).map { it.copy(primary = null) }
    val options = newDesignTypeOptions(quiet)
    assertEquals(
      listOf("m3-catalog", "wear-m3", "remote-m3"),
      options.primary.map { it.catalogSystemId },
    )
    assertEquals(listOf("remote-widgets", "a2ui-catalog"), options.other.map { it.systemId })
  }

  @Test
  fun `catalogs sharing a label are told apart by their id`() {
    val phones =
      listOf(
        card("m3-catalog", "Mobile"),
        card("compose-foundation", "Mobile"),
        card("mobile", "mobile"),
        card("wear-m3", "Wear"),
      )
    val options = newDesignTypeOptions(phones)
    assertEquals(
      listOf("Mobile · M3 Catalog", "Mobile · Compose Foundation", "Mobile"),
      options.primary.map { it.title },
    )
    assertEquals(listOf("Wear"), options.other.map { it.label })
  }

  @Test
  fun `the template grid is two columns on a phone and at most four`() {
    assertEquals(2, newDesignTemplateColumns(200.dp))
    assertEquals(2, newDesignTemplateColumns(340.dp))
    assertEquals(3, newDesignTemplateColumns(460.dp))
    assertEquals(4, newDesignTemplateColumns(1000.dp))
  }

  @Test
  fun `a catalog's device is what it declares, else inferred from its label and platform`() {
    assertEquals(NewDesignDevice.PHONE, m3.newDesignDevice)
    assertEquals(NewDesignDevice.WATCH, wear.newDesignDevice)
    // Remote Compose is a widget whether it lands on a watch or a launcher.
    assertEquals(NewDesignDevice.WIDGET, remote.newDesignDevice)
    assertEquals(NewDesignDevice.WIDGET, launcher.newDesignDevice)
    assertEquals(NewDesignDevice.AGENT, a2ui.newDesignDevice)
    // Labels name devices the platform enum has no word for.
    assertEquals(NewDesignDevice.GLASSES, card("xr-glasses", "Glasses").newDesignDevice)
    assertEquals(NewDesignDevice.SMART_HOME, ha.newDesignDevice)
    // A declared device wins, known or the catalog's own.
    assertEquals(NewDesignDevice.PHONE, launcher.copy(device = "phone").newDesignDevice)
    assertEquals(
      NewDesignDevice("car", "Car"),
      card("auto", "Android Auto", device = "car").newDesignDevice,
    )
  }

  @Test
  fun `devices are offered in a fixed order, primary catalogs first within each`() {
    val glasses = card("xr-glasses", "Glasses")
    val groups = newDesignDeviceGroups(listOf(a2ui, glasses, launcher, m3, remote, wear, ha))
    assertEquals(
      listOf(
        NewDesignDevice.PHONE,
        NewDesignDevice.WATCH,
        NewDesignDevice.WIDGET,
        NewDesignDevice.GLASSES,
        NewDesignDevice.SMART_HOME,
        NewDesignDevice.AGENT,
      ),
      groups.map { it.first },
    )
    assertEquals(
      listOf("remote-m3", "remote-widgets"),
      groups.first { it.first == NewDesignDevice.WIDGET }.second.map { it.systemId },
    )
  }

  @Test
  fun `the device is asked first only across devices, on an unnarrowed host`() {
    assertTrue(newDesignAsksDeviceFirst(listOf(m3, wear)))
    assertFalse(newDesignAsksDeviceFirst(listOf(m3, card("compose-foundation", "Foundation"))))
    assertFalse(
      newDesignAsksDeviceFirst(listOf(m3, wear), UiBuilderBrand(catalogs = listOf("m3-catalog"))),
    )
    // One catalog's template groups are the choice instead.
    assertFalse(newDesignAsksDeviceFirst(listOf(ha), UiBuilderBrand(catalogs = listOf("ha-cards"))))
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
