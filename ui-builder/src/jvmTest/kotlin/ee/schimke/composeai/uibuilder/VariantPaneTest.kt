package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The strip beside the design: one document, one stored environment, several panes.
 *
 * A variant is a way of *looking*, so nothing here writes to the document and nothing here is a
 * second design — the rules and the reasons are in
 * `docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`.
 */
class VariantPaneTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private val presets =
    listOf(
      UiBuilderDevicePreset("id:pixel_6", "Pixel 6", "Phones", 411, 914, 2.625),
      UiBuilderDevicePreset("id:pixel_tablet", "Pixel Tablet", "Tablets", 1280, 800, 2.0),
    )

  private fun withDevices(vararg ids: String): UiBuilderDocument =
    reducer
      .reduce(
        reducer.initial(document, selectedNodeId = null),
        UiBuilderEditorEvent.UpdateEnvironment(
          document.screenEnvironmentSettings().copy(exportDevices = ids.toList())
        ),
      )
      .document

  @Test
  fun `a design that claims no devices and asks no questions has no strip`() {
    assertEquals(emptyList(), document.variantPanes(presets, emptySet()))
  }

  /**
   * The set the export writes is the set the workspace draws. Before the strip existed a design
   * could claim three devices and show its author one, with neither decision showing the other.
   */
  @Test
  fun `each claimed device becomes a pane at that device's frame`() {
    val panes = withDevices("id:pixel_6", "id:pixel_tablet").variantPanes(presets, emptySet())

    assertEquals(listOf("Pixel 6", "Pixel Tablet"), panes.map { it.label })
    assertEquals(411f to 914f, panes[0].widthDp to panes[0].heightDp)
    assertEquals("411", panes[0].document.environment["widthDp"]?.let(::plain))
    assertEquals("2.625", panes[0].document.environment["density"]?.let(::plain))
  }

  /**
   * A preset carries the only geometry there is, so an id the host offers no preset for is skipped
   * rather than guessed at — the alternative draws a picture no renderer would reproduce.
   */
  @Test
  fun `a device the host has no preset for is skipped`() {
    val panes = withDevices("id:pixel_6", "id:no_such_device").variantPanes(presets, emptySet())

    assertEquals(listOf("Pixel 6"), panes.map { it.label })
  }

  /**
   * `exportDevices` is stored verbatim — no uniqueness, no size bound — so a document written
   * through the protocol or MCP can name one device many times. Each repeat would otherwise be
   * another full surface sharing one pane id.
   */
  @Test
  fun `a device named twice draws one pane`() {
    val panes =
      withDevices("id:pixel_6", "id:pixel_6", "id:pixel_tablet", "id:pixel_6")
        .variantPanes(presets, emptySet())

    assertEquals(listOf("Pixel 6", "Pixel Tablet"), panes.map { it.label })
  }

  @Test
  fun `an axis writes only its own field over the design's environment`() {
    val panes = document.variantPanes(presets, setOf(EditorVariantAxis.Dark))

    val dark = panes.single()
    assertEquals("dark", plain(dark.document.environment.getValue("theme")))
    // Everything else is the design's own: a variant is the same design seen differently.
    assertEquals(
      document.environment["widthDp"]?.let(::plain),
      dark.document.environment["widthDp"]?.let(::plain),
    )
    assertEquals(document.id, dark.document.id)
    assertEquals(document.revision, dark.document.revision)
    assertEquals(document.nodes, dark.document.nodes)
  }

  @Test
  fun `the axes are drawn in a fixed order whatever order they were switched on in`() {
    val axes = setOf(EditorVariantAxis.LargeFont, EditorVariantAxis.Dark, EditorVariantAxis.Rtl)

    assertEquals(
      listOf("Dark", "RTL", "Font 1.5×"),
      document.variantPanes(presets, axes).map { it.label },
    )
  }

  /** Two panes of the same design, so the render session id is what keeps their geometry apart. */
  @Test
  fun `every pane has its own render session id`() {
    val panes =
      withDevices("id:pixel_6", "id:pixel_tablet")
        .variantPanes(presets, EditorVariantAxis.entries.toSet())

    assertEquals(panes.size, panes.map { it.id }.toSet().size)
  }

  @Test
  fun `switching an axis on and off leaves the document alone`() {
    val initial = reducer.initial(document, selectedNodeId = null)

    val on = reducer.reduce(initial, UiBuilderEditorEvent.ToggleVariantAxis(EditorVariantAxis.Rtl))
    val off = reducer.reduce(on, UiBuilderEditorEvent.ToggleVariantAxis(EditorVariantAxis.Rtl))

    assertEquals(setOf(EditorVariantAxis.Rtl), on.variantAxes)
    assertEquals(emptySet(), off.variantAxes)
    assertEquals(document.revision, off.document.revision)
    assertEquals(document, off.document)
  }

  /**
   * Both record-free emitters route on the root component id, so wrapping a Wear screen would
   * quietly hand it to the record-driven generator instead. A board must not convert a design into
   * something that exports differently without saying so.
   */
  @Test
  fun `a Wear screen refuses to become one item of a board`() {
    val wear =
      document.copy(
        roots = listOf("wear-root"),
        nodes = mapOf("wear-root" to UiBuilderNode("wear-root", "wear-m3/screen-scaffold")),
      )

    assertNotNull(reducer.besideRefusal(reducer.initial(wear, selectedNodeId = null)))
    assertNull(reducer.besideRefusal(reducer.initial(document, selectedNodeId = null)))
  }

  @Test
  fun `a design already on a board is never refused`() {
    val onBoard =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = null),
        UiBuilderEditorEvent.InsertComponentBeside("m3/card"),
      )

    assertTrue(onBoard.document.isBoard)
    assertNull(reducer.besideRefusal(onBoard))
  }

  private fun plain(value: kotlinx.serialization.json.JsonElement): String =
    (value as JsonPrimitive).content

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
