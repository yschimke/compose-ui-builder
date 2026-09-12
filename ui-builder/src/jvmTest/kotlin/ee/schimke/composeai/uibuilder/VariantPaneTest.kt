package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
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

    // The label states what the pane applied, not just which device it is named for: a row of
    // frames whose names are "Pixel 6" and "Pixel Tablet" leaves somebody comparing two of them to
    // guess which of width, height and density moved. Nothing draws a bezel — a device pane *is*
    // these three numbers.
    assertEquals(
      listOf("Pixel 6 · 411×914dp · 2.625×", "Pixel Tablet · 1280×800dp · 2×"),
      panes.map { it.label },
    )
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

    assertEquals(listOf("Pixel 6"), panes.map { it.deviceName() })
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

    assertEquals(listOf("Pixel 6", "Pixel Tablet"), panes.map { it.deviceName() })
  }

  /**
   * The device a pane is named for, without the properties after it.
   *
   * Used by the tests about *which* devices get a pane, so the label's wording is pinned in exactly
   * one place — the test above — rather than in every test that happens to read a label.
   */
  private fun UiBuilderVariantPane.deviceName(): String = label.substringBefore(" · ")

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

  /**
   * You build the UI once and watch it adapt beside you.
   *
   * The devices and axes used to be drawn on the authoring canvas, which made the one surface you
   * edit on grow a row of surfaces you cannot. They are the preview pane's now and nowhere else:
   * with that pane shut, the workspace holds one frame however many devices the design claims.
   */
  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `the devices a design claims are drawn in the preview pane and nowhere else`() =
    runDesktopComposeUiTest(width = 1600, height = 900) {
      val claiming = withDevices("id:pixel_tablet")
      var panes by mutableStateOf(setOf(EditorPane.Editor))
      setContent {
        MaterialTheme {
          // Keyed on the pane set, because the editor reads `initialPanes` once — this stands in
          // for the menu toggling the pane rather than testing the menu.
          key(panes) {
            UiBuilderEditor(
              document = claiming,
              catalog = catalog,
              initialPanes = panes,
              devicePresets = presets,
            )
          }
        }
      }
      onNodeWithText("Pixel Tablet", substring = true).assertDoesNotExist()

      runOnIdle { panes = setOf(EditorPane.Editor, EditorPane.Preview) }
      waitForIdle()
      onNodeWithText("Pixel Tablet", substring = true).assertExists()
    }
}
