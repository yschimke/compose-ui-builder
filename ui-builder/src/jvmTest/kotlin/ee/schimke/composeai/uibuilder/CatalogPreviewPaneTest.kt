package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CatalogPreview
import ee.schimke.composeai.uibuilder.editor.LAUNCHER_RESIZABLE_PANE_ID
import ee.schimke.composeai.uibuilder.editor.catalogPreviewPanes
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.export.LauncherWidgetTemplates
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.blankUiBuilderDocument
import ee.schimke.composeai.uibuilder.export.hostSpec
import ee.schimke.composeai.uibuilder.export.wearWidgetUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A catalog's `frame.previews`: every design shown in each host its catalog renders it in. */
class CatalogPreviewPaneTest {

  private val semantics =
    Json.parseToJsonElement(
        """
        {"frame": {"adapter": "frame/widget-host", "previews": [
          {"kind": "wear-widget", "size": "small", "shape": "round"},
          {"kind": "wear-widget", "size": "large", "shape": "round"},
          {"kind": "wear-widget", "size": "large", "shape": "squircle", "label": "Squircle · Large"},
          {"kind": "launcher-resizable"},
          {"kind": "frame", "widthDp": 360, "label": "Phone"},
          {"kind": "frame", "widthDp": 480, "heightDp": 240, "label": "Tablet"},
          {"kind": "wear-widget", "size": "huge", "shape": "round"},
          {"kind": "hologram"},
          {"kind": "frame"}
        ]}}
        """
      )
      .jsonObject

  private val previews = CatalogPreview.from(semantics)

  private fun card(): UiBuilderDocument =
    blankUiBuilderDocument(
      "card",
      JsonObject(emptyMap()),
      JsonObject(mapOf("widthDp" to JsonPrimitive(360), "heightDp" to JsonPrimitive(120))),
    )

  @Test
  fun `previews are read in order and entries this build cannot read are skipped`() {
    assertEquals(
      listOf(
        CatalogPreview.WearWidget(WearWidgetScaffoldSize.Small, WearWidgetHostShape.Round),
        CatalogPreview.WearWidget(WearWidgetScaffoldSize.Large, WearWidgetHostShape.Round),
        CatalogPreview.WearWidget(
          WearWidgetScaffoldSize.Large,
          WearWidgetHostShape.Squircle,
          "Squircle · Large",
        ),
        CatalogPreview.LauncherResizable(),
        CatalogPreview.Frame(360, null, "Phone"),
        CatalogPreview.Frame(480, 240, "Tablet"),
      ),
      previews,
    )
    assertEquals(emptyList(), CatalogPreview.from(JsonObject(emptyMap())))
  }

  @Test
  fun `a card is drawn in each Wear host, the resizable launcher and each app frame`() {
    val panes = assertNotNull(card().catalogPreviewPanes(previews))
    assertEquals(
      listOf(
        "Samsung · Small",
        "Samsung · Large",
        "Squircle · Large",
        "Resizable",
        "Phone · 360×120dp",
        "Tablet · 480×240dp",
      ),
      panes.map { it.label },
    )

    // A Wear pane is the host's frame, with the design laid out in its content box.
    val small = panes[0]
    val spec = WearWidgetScaffoldSize.Small.hostSpec(WearWidgetHostShape.Round)
    assertEquals(spec, small.wearHostFrame)
    assertEquals(spec.frameWidthDp.toFloat(), small.widthDp)
    assertEquals(spec.contentWidthDp, small.document.screenEnvironmentSettings().widthDp)
    assertEquals(spec.contentHeightDp, small.document.screenEnvironmentSettings().heightDp)

    assertTrue(panes[3].launcherGridResizable)
    assertEquals(LAUNCHER_RESIZABLE_PANE_ID, panes[3].id)

    // A frame with no height keeps the design's own.
    assertEquals(120, panes[4].document.screenEnvironmentSettings().heightDp)
    assertEquals(480, panes[5].document.screenEnvironmentSettings().widthDp)
  }

  @Test
  fun `fixed stills leave the resizable pane out`() {
    val panes = assertNotNull(card().catalogPreviewPanes(previews, resizable = false))
    assertTrue(panes.none { it.launcherGridResizable })
    assertEquals(5, panes.size)
  }

  @Test
  fun `widgets keep their own host panes, and no previews leaves the strip alone`() {
    val wear =
      wearWidgetUiBuilderDocument(
        "wear",
        JsonObject(emptyMap()),
        JsonObject(emptyMap()),
        WearWidgetScaffoldSize.Small,
      )
    assertNull(wear.catalogPreviewPanes(previews))
    val launcher =
      LauncherWidgetTemplates.document(
        templateId = LauncherWidgetTemplates.HELLO_TEMPLATE,
        designId = "hello",
        catalogPin = JsonObject(mapOf("systemId" to JsonPrimitive("remote-widgets"))),
        environment = JsonObject(emptyMap()),
      )
    assertNull(launcher.catalogPreviewPanes(previews))
    assertNull(card().catalogPreviewPanes(emptyList()))
  }
}
