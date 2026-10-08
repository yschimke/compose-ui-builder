package ee.schimke.composeai.uibuilder

import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorPropertyControl
import ee.schimke.composeai.uibuilder.editor.EditorPropertyField
import ee.schimke.composeai.uibuilder.editor.THEME_BACKGROUND
import ee.schimke.composeai.uibuilder.editor.THEME_CONTENT
import ee.schimke.composeai.uibuilder.editor.THEME_PRIMARY
import ee.schimke.composeai.uibuilder.editor.THEME_SURFACE
import ee.schimke.composeai.uibuilder.editor.ThemeSchemes
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.inspectorPropertyFields
import ee.schimke.composeai.uibuilder.editor.ownsThemePanelProperties
import ee.schimke.composeai.uibuilder.editor.readThemeBuilderScheme
import ee.schimke.composeai.uibuilder.export.ThemeTextStyle
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Theme panel's colour schemes: generated from a seed with materialkolor, read from a Material
 * Theme Builder export, and mapped onto what a design's theme host and tokens can hold.
 */
class ThemeSchemesTest {
  private val m3Catalog =
    CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val m3 = UiBuilderEditorReducer(m3Catalog)
  private val m3Document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private val wearCatalog = CapabilityCatalogParser.parse(resource("/wear-m3-capabilities-v1.json"))
  private val wear = UiBuilderEditorReducer(wearCatalog)
  private val wearScreen =
    UiBuilderNewDesignSeed.document(
        designId = "wear-scheme",
        catalogSystemId = "wear-m3",
        templateId = "wear-screen",
        catalogRevision = "wear-screen-scaffold-v1",
        nativeRuntimeId = "candidate",
        fixture =
          Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject,
      )
      .copy(
        roots = listOf("screen"),
        nodes =
          mapOf(
            "screen" to
              UiBuilderNode(
                id = "screen",
                componentId = WearScreenCodeExporter.SCAFFOLD,
                slots = mapOf("content" to listOf("list")),
              ),
            "list" to UiBuilderNode(id = "list", componentId = "wear-m3/transforming-lazy-column"),
          ),
      )

  private val export =
    """
    {
      "seed": "#6750A4",
      "schemes": {
        "light": { "primary": "#65558F", "background": "#FEF7FF", "surface": "#FEF7FF",
                   "onSurface": "#1D1B20", "secondary": "#625B71" },
        "dark": { "primary": "#CFBDFE", "background": "#141218", "surface": "#141218",
                  "onSurface": "#E6E0E9", "secondary": "#CBC2DB", "shadow": 12 }
      }
    }
    """

  @Test
  fun `a seed generates every Material role, and light and dark differ`() {
    val light = assertNotNull(ThemeSchemes.generate("#6750A4", dark = false))
    val dark = assertNotNull(ThemeSchemes.generate("#6750A4", dark = true))
    assertEquals(36, light.size)
    assertEquals(light.keys, dark.keys)
    assertTrue(light.values.all { Regex("#[0-9A-F]{6}").matches(it) }, "$light")
    assertNotEquals(light["primary"], dark["primary"])
    assertTrue(luminance(light.getValue("background")) > luminance(dark.getValue("background")))
    assertTrue(luminance(light.getValue("onSurface")) < luminance(light.getValue("surface")))
    // Alpha is the field's business, not the seed's.
    assertEquals(light, ThemeSchemes.generate("#FF6750A4", dark = false))
  }

  @Test
  fun `each style and contrast is its own scheme, and not a colour is no scheme`() {
    val tonal = ThemeSchemes.generate("#00897B", dark = false)
    assertEquals(PaletteStyle.TonalSpot, ThemeSchemes.STYLES.first())
    ThemeSchemes.STYLES.forEach { style ->
      assertNotNull(ThemeSchemes.generate("#00897B", dark = false, style = style), "$style")
    }
    assertNotEquals(
      tonal,
      ThemeSchemes.generate("#00897B", dark = false, style = PaletteStyle.Monochrome),
    )
    assertNotEquals(
      tonal,
      ThemeSchemes.generate("#00897B", dark = false, contrast = Contrast.High.value),
    )
    assertNull(ThemeSchemes.generate("teal", dark = false))
    assertNull(ThemeSchemes.generate("#12345", dark = false))
  }

  @Test
  fun `a watch starts dark and a phone light`() {
    assertEquals("dark", ThemeSchemes.preferredScheme(UiBuilderCatalogPlatform.WEAR))
    assertEquals("light", ThemeSchemes.preferredScheme(UiBuilderCatalogPlatform.MOBILE))
  }

  @Test
  fun `the Material surface holds four roles, content being onSurface`() {
    val host = assertNotNull(m3.themePanelHost(m3.initial(m3Document)))
    assertEquals("root-surface", host.nodeId)
    assertFalse(host.wearScale)
    assertTrue(host.scaleAndShape)
    assertEquals(
      mapOf(
        "primary" to THEME_PRIMARY,
        "background" to THEME_BACKGROUND,
        "surface" to THEME_SURFACE,
        "onSurface" to THEME_CONTENT,
      ),
      host.colorProperties,
    )
  }

  @Test
  fun `a Wear screen holds a property per role`() {
    val host = assertNotNull(wear.themePanelHost(wear.initial(wearScreen, "list")))
    assertEquals("screen", host.nodeId, "the screen holding the selection")
    assertTrue(host.wearScale)
    assertFalse(host.scaleAndShape)
    assertEquals("themeSurfaceContainerLowColor", host.colorProperties["surfaceContainerLow"])
    assertEquals("themeOnErrorColor", host.colorProperties["onError"])
    assertEquals(23, host.colorProperties.size)
  }

  @Test
  fun `a Theme Builder scheme lands on the Material surface as one undoable edit`() {
    val dark = readThemeBuilderScheme(export, "dark").getOrThrow()
    assertEquals("dark", dark.name)
    assertEquals(listOf("light", "dark"), dark.available)
    assertFalse("shadow" in dark.roles, "a number is not a colour")

    val initial = m3.initial(m3Document)
    val applied = initial.on(m3, UiBuilderEditorEvent.ApplyColorScheme(dark.roles))
    assertIs<CommandOutcome.Accepted>(applied.lastOutcome, "${applied.lastOutcome}")
    assertEquals(initial.document.revision + 1, applied.document.revision, "one edit")
    val root = applied.document.nodes.getValue("root-surface")
    assertEquals("#CFBDFE", root.colour(THEME_PRIMARY))
    assertEquals("#141218", root.colour(THEME_BACKGROUND))
    assertEquals("#141218", root.colour(THEME_SURFACE))
    assertEquals("#E6E0E9", root.colour(THEME_CONTENT))
    assertEquals("#CFBDFE", m3.themeSettings(applied).primaryColor)

    val undone = applied.on(m3, UiBuilderEditorEvent.Undo)
    assertEquals(
      initial.document.nodes.getValue("root-surface").properties,
      undone.document.nodes.getValue("root-surface").properties,
    )

    assertEquals("light", readThemeBuilderScheme(export, "light").getOrThrow().name)
    assertEquals("light", readThemeBuilderScheme(export, "contrast").getOrThrow().name)
    assertTrue(readThemeBuilderScheme("""{"primary":"#000000"}""").isFailure)
  }

  @Test
  fun `a generated scheme lands on the Wear screen and its tokens as one edit`() {
    val scheme = assertNotNull(ThemeSchemes.generate("#00897B", dark = true))
    val initial = wear.initial(wearScreen)
    val applied = initial.on(wear, UiBuilderEditorEvent.ApplyColorScheme(scheme))
    assertIs<CommandOutcome.Accepted>(applied.lastOutcome, "${applied.lastOutcome}")
    assertEquals(initial.document.revision + 1, applied.document.revision)
    val screen = applied.document.nodes.getValue("screen")
    assertEquals(scheme["primary"], screen.colour("themePrimaryColor"))
    // No token for this one: the host's own property carries it.
    assertEquals(scheme["surfaceContainerLow"], screen.colour("themeSurfaceContainerLowColor"))
    assertEquals(
      scheme["primary"],
      (wear.designTokenRows(applied).single { it.token.id == "color.primary" }.value
          as ee.schimke.composeai.uibuilder.editor.DesignTokenValue.Set)
        .value
        .content,
    )
    assertEquals(
      mapOf("color.primary" to scheme["primary"], "color.onSurface" to scheme["onSurface"]),
      ThemeSchemes.tokenValues(scheme, wearCatalog.designTokens).filterKeys {
        it == "color.primary" || it == "color.onSurface"
      },
    )
  }

  @Test
  fun `a scheme that is not colours is refused whole`() {
    val initial = m3.initial(m3Document)
    val refused =
      initial.on(m3, UiBuilderEditorEvent.ApplyColorScheme(mapOf("primary" to "purple")))
    assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertEquals(initial.document, refused.document)
  }

  @Test
  fun `the typefaces and text style leave the property list, and an agent can still set them`() {
    val state = m3.initial(m3Document, "root-surface")
    val fields = m3.propertyFields(state)
    assertTrue(ownsThemePanelProperties(fields))
    val listed = inspectorPropertyFields(fields).map { it.name }
    assertTrue(listed.isNotEmpty())
    assertTrue(listed.none { it in ThemeTypefaces.PROPERTIES || it == ThemeTextStyle.PROPERTY })
    assertFalse(
      ownsThemePanelProperties(m3.propertyFields(m3.initial(m3Document, "discover-grid")))
    )

    val committed =
      state.on(
        m3,
        UiBuilderEditorEvent.CommitProperty("root-surface", "themeDisplayTypeface", "Michroma"),
        UiBuilderEditorEvent.CommitProperty("root-surface", ThemeTextStyle.PROPERTY, "labelLarge"),
      )
    val root = committed.document.nodes.getValue("root-surface")
    assertEquals("Michroma", root.colour("themeDisplayTypeface"))
    assertEquals("labelLarge", root.colour(ThemeTextStyle.PROPERTY))

    val wearFields = wear.propertyFields(wear.initial(wearScreen, "screen"))
    assertTrue(ownsThemePanelProperties(wearFields))
    assertTrue(
      inspectorPropertyFields(wearFields).none {
        it.name in ThemeTypefaces.PROPERTIES || it.name == ThemeTextStyle.PROPERTY
      }
    )
    val plain =
      EditorPropertyField(
          nodeId = "n",
          name = "text",
          label = "Text",
          required = false,
          control = EditorPropertyControl.Text,
          value = "",
        )
        .let { listOf(it, it.copy(name = "themeBodyTypeface")) }
    assertEquals(listOf("text"), inspectorPropertyFields(plain).map { it.name })
    assertEquals(EditorPropertyControl.Text, plain.first().control)
  }

  private fun UiBuilderEditorState.on(
    reducer: UiBuilderEditorReducer,
    vararg events: UiBuilderEditorEvent,
  ): UiBuilderEditorState = events.fold(this, reducer::reduce)

  private fun UiBuilderNode.colour(property: String): String? =
    (properties[property] as? JsonObject)?.get("value")?.jsonPrimitive?.content

  private fun luminance(hex: String): Double {
    val rgb = hex.removePrefix("#").chunked(2).map { it.toInt(16) / 255.0 }
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
