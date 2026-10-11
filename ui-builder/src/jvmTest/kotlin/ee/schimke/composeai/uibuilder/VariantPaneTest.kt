package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.canvas.isBoard
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.EditorVariantAxis
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderVariantPane
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.editor.variantPanes
import ee.schimke.composeai.uibuilder.editor.wearWidgetPreviewPanes
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.blankUiBuilderDocument
import ee.schimke.composeai.uibuilder.export.hostSpec
import ee.schimke.composeai.uibuilder.export.wearScreenUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The strip beside the design: one document, one stored environment, several panes. Variants never
 * write to the document; see `docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`.
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

  @Test
  fun `a widget preview always shows the three launcher hosts`() {
    val widget =
      blankUiBuilderDocument(
          "widget-preview",
          buildJsonObject {
            put("widthDp", "216")
            put("heightDp", "124")
            put("density", "2")
          },
          JsonObject(emptyMap()),
        )
        .copy(
          roots = listOf("widget"),
          nodes =
            mapOf(
              "widget" to
                UiBuilderNode(
                  "widget",
                  WearWidgetScaffoldSize.Large.componentId,
                  slots = emptyMap(),
                )
            ),
        )

    val panes = widget.wearWidgetPreviewPanes(WearWidgetScaffoldSize.Large)

    assertEquals(listOf("Rectangular", "Pixel Watch", "Samsung"), panes.map { it.label })
    assertEquals(
      listOf(232f to 144f, 216f to 124f, 230f to 168f),
      panes.map { it.widthDp to it.heightDp },
    )
    assertEquals(
      listOf(
        WearWidgetHostShape.Rectangular,
        WearWidgetHostShape.Squircle,
        WearWidgetHostShape.Round,
      ),
      panes.map { it.wearWidgetHostShape },
    )
  }

  @Test
  fun `an adaptive widget previews its resolved design at both sizes in every host`() {
    val adaptive =
      AdaptiveWearWidget.newDocument("adaptive", JsonObject(emptyMap()), JsonObject(emptyMap()))

    val panes = adaptive.wearWidgetPreviewPanes(WearWidgetScaffoldSize.Large)

    assertEquals(
      listOf(
        "Rectangular · Small",
        "Pixel Watch · Small",
        "Samsung · Small",
        "Rectangular · Large",
        "Pixel Watch · Large",
        "Samsung · Large",
      ),
      panes.map { it.label },
    )
    assertEquals(panes.size, panes.map { it.id }.toSet().size, "pane ids collide")
    panes.forEach { pane ->
      val size =
        if (pane.label.endsWith("Small")) WearWidgetScaffoldSize.Small
        else WearWidgetScaffoldSize.Large
      assertEquals(AdaptiveWearWidget.resolve(adaptive, size), pane.document, pane.label)
      val spec = size.hostSpec(assertNotNull(pane.wearWidgetHostShape))
      assertEquals(spec.frameWidthDp.toFloat(), pane.widthDp, pane.label)
      assertEquals(spec.frameHeightDp.toFloat(), pane.heightDp, pane.label)
    }
  }

  /** The set the export writes is the set the workspace draws. */
  @Test
  fun `each claimed device becomes a pane at that device's frame`() {
    val panes = withDevices("id:pixel_6", "id:pixel_tablet").variantPanes(presets, emptySet())

    // The label states what the pane applied (width, height, density), not just the device name; no
    // bezel is drawn.
    assertEquals(
      listOf("Pixel 6 · 411×914dp · 2.625×", "Pixel Tablet · 1280×800dp · 2×"),
      panes.map { it.label },
    )
    assertEquals(411f to 914f, panes[0].widthDp to panes[0].heightDp)
    assertEquals("411", panes[0].document.environment["widthDp"]?.let(::plain))
    assertEquals("2.625", panes[0].document.environment["density"]?.let(::plain))
  }

  /** An id the host offers no preset for is skipped rather than guessed at. */
  @Test
  fun `a device the host has no preset for is skipped`() {
    val panes = withDevices("id:pixel_6", "id:no_such_device").variantPanes(presets, emptySet())

    assertEquals(listOf("Pixel 6"), panes.map { it.deviceName() })
  }

  /**
   * `exportDevices` is stored verbatim, so repeated devices must not yield duplicate panes sharing
   * one id.
   */
  @Test
  fun `a device named twice draws one pane`() {
    val panes =
      withDevices("id:pixel_6", "id:pixel_6", "id:pixel_tablet", "id:pixel_6")
        .variantPanes(presets, emptySet())

    assertEquals(listOf("Pixel 6", "Pixel Tablet"), panes.map { it.deviceName() })
  }

  /**
   * The device a pane is named for, without the properties after it, so the label wording is pinned
   * only in the test above.
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

  @Test
  fun `a watch is compared at Wear OS's largest font size, not a phone's`() {
    val wear = wearScreenUiBuilderDocument("list", JsonObject(emptyMap()), document.environment)

    val pane = wear.variantPanes(presets, setOf(EditorVariantAxis.LargeFont)).single()

    assertEquals("Font 1.24×", pane.label)
    assertEquals("1.24", pane.document.environment["fontScale"]?.let(::plain))
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
   * Record-free emitters route on the root id, so wrapping a Wear screen in a board would silently
   * change how it exports; refused.
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
   * You build the UI once and watch it adapt beside you: the preview pane holds the devices and
   * axes, while the editor canvas stays a single editable frame.
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
      onNodeWithText("Pixel Tablet", substring = true).assertExists()

      runOnIdle { panes = setOf(EditorPane.Editor, EditorPane.Preview) }
      waitForIdle()
      onNodeWithText("Pixel Tablet", substring = true).assertExists()
    }

  /**
   * Small frames wrap into rows instead of scrolling off the pane: with four 2x watch frames, the
   * fourth starts below the first and the third shares its top line.
   */
  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `watch frames wrap into a grid rather than scrolling off the pane`() =
    runDesktopComposeUiTest(width = 1800, height = 900) {
      val watches =
        listOf(
          UiBuilderDevicePreset(
            "id:wearos_small_round",
            "Wear OS Small Round",
            "Watches",
            192,
            192,
            2.0,
          ),
          UiBuilderDevicePreset(
            "id:wearos_large_round",
            "Wear OS Large Round",
            "Watches",
            227,
            227,
            2.0,
          ),
          UiBuilderDevicePreset("id:wearos_xl_round", "Wear OS XL Round", "Watches", 240, 240, 2.0),
          UiBuilderDevicePreset(
            "id:wearos_xxl_round",
            "Wear OS XXL Round",
            "Watches",
            260,
            260,
            2.0,
          ),
        )
      val environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(192),
            "heightDp" to JsonPrimitive(192),
            "density" to JsonPrimitive(2.0),
            "theme" to JsonPrimitive("dark"),
          )
        )
      val base = wearScreenUiBuilderDocument("grid", JsonObject(emptyMap()), environment)
      val wear =
        base.copy(
          environment =
            JsonObject(
              base.environment +
                ("exportDevices" to
                  JsonArray(
                    listOf(
                        "id:wearos_small_round",
                        "id:wearos_large_round",
                        "id:wearos_xl_round",
                        "id:wearos_xxl_round",
                      )
                      .map(::JsonPrimitive)
                  ))
            )
        )
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = wear,
            catalog = catalog,
            initialPanes = setOf(EditorPane.Preview),
            devicePresets = watches,
          )
        }
      }
      waitForIdle()
      val small = onNodeWithText("Wear OS Small Round", substring = true).getBoundsInRoot()
      val large = onNodeWithText("Wear OS Large Round", substring = true).getBoundsInRoot()
      val xl = onNodeWithText("Wear OS XL Round", substring = true).getBoundsInRoot()
      val xxl = onNodeWithText("Wear OS XXL Round", substring = true).getBoundsInRoot()
      // The first three share a row…
      assertTrue(
        kotlin.math.abs(small.top.value - large.top.value) < 2f,
        "expected Small and Large on one row: ${small.top} vs ${large.top}",
      )
      assertTrue(
        kotlin.math.abs(small.top.value - xl.top.value) < 2f,
        "expected Small and XL on one row: ${small.top} vs ${xl.top}",
      )
      // …and the fourth selected device wrapped below.
      assertTrue(
        xxl.top.value > small.bottom.value,
        "expected XXL on a second row, below Small: ${xxl.top} vs ${small.bottom}",
      )
    }
}
