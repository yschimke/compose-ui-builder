package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.LAUNCHER_RESIZE_MAX
import ee.schimke.composeai.uibuilder.editor.launcherSizeAfterDrag
import ee.schimke.composeai.uibuilder.editor.mobileTabLabel
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.editor.widgetPreviewPanes
import ee.schimke.composeai.uibuilder.export.LauncherWidgetGrid
import ee.schimke.composeai.uibuilder.export.LauncherWidgetTemplates
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.blankUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A launcher widget previews on the launcher grid: fixed cell counts, and one pane that resizes.
 */
class LauncherWidgetPreviewPaneTest {

  private fun hello(): UiBuilderDocument =
    LauncherWidgetTemplates.document(
      templateId = LauncherWidgetTemplates.HELLO_TEMPLATE,
      designId = "hello",
      catalogPin = JsonObject(mapOf("systemId" to JsonPrimitive("remote-widgets"))),
      environment = JsonObject(emptyMap()),
    )

  @Test
  fun `a launcher widget previews resizable first, then each grid size smallest first`() {
    val panes = hello().widgetPreviewPanes()!!

    assertEquals(
      listOf(
        "Resizable",
        "2x1 · 130×102dp",
        "3x1 · 203×102dp · Current",
        "2x2 · 130×220dp",
        "3x2 · 203×220dp",
        "4x2 · 276×220dp",
      ),
      panes.map { it.label },
    )
    assertTrue(panes.first().launcherGridResizable)
    // The resizable pane takes the room of the largest footprint it reaches, margins included.
    assertEquals(
      (73f * LAUNCHER_RESIZE_MAX.columns) to (118f * LAUNCHER_RESIZE_MAX.rows),
      panes.first().widthDp to panes.first().heightDp,
    )
    // Each fixed pane draws the design at its size, so its layout is the one that size gets.
    panes.drop(1).forEach { pane ->
      val settings = pane.document.screenEnvironmentSettings()
      assertEquals(
        pane.widthDp to pane.heightDp,
        settings.widthDp.toFloat() to settings.heightDp.toFloat(),
      )
      assertTrue(!pane.launcherGridResizable)
    }
  }

  @Test
  fun `a host drawing fixed renders gets the grid sizes alone`() {
    val panes = hello().widgetPreviewPanes(resizable = false)!!
    assertTrue(panes.none { it.launcherGridResizable })
    assertEquals("3x2", panes.first { it.widthDp == 203f && it.heightDp == 220f }.mobileTabLabel())
  }

  @Test
  fun `a design that is not a widget keeps its device strip`() {
    assertNull(
      blankUiBuilderDocument("blank", JsonObject(emptyMap()), JsonObject(emptyMap()))
        .widgetPreviewPanes()
    )
  }

  @Test
  fun `a drag snaps to the nearest whole cell and stays on the patch`() {
    val from = LauncherWidgetGrid.Size(2, 2)
    assertEquals(LauncherWidgetGrid.Size(2, 2), launcherSizeAfterDrag(from, 0.4f, -0.4f))
    assertEquals(LauncherWidgetGrid.Size(3, 1), launcherSizeAfterDrag(from, 0.6f, -0.6f))
    assertEquals(LauncherWidgetGrid.Size(1, 1), launcherSizeAfterDrag(from, -9f, -9f))
    assertEquals(LAUNCHER_RESIZE_MAX, launcherSizeAfterDrag(from, 9f, 9f))
  }
}
