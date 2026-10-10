package ee.schimke.composeai.uibuilder.guidelines

import ee.schimke.composeai.uibuilder.export.LauncherWidgetCodeExporter
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class CatalogGuidelinesTest {
  private val guidelines =
    CatalogGuidelines.parse(
      """
      {
        "schema": "compose-ui-builder/catalog-guidelines/v1",
        "catalog": "remote-m3", "platform": "wear", "version": 3,
        "frames": [
          {"kind": "device", "surface": "screen"},
          {"kind": "unrolled", "surface": "screen", "heightFactor": 4, "whenScrolls": true},
          {"kind": "widget-host", "surface": "widget", "hostShape": "round", "label": "Samsung",
           "description": "the widget in a stadium."},
          {"kind": "sized", "label": "tablet", "widthDp": 1280, "heightDp": 800}
        ],
        "rules": [
          {"id": "any", "kind": "structure", "severity": "info", "guidance": "g", "check": "ok?",
           "source": "https://developer.android.com/a"},
          {"id": "widget-only", "kind": "visual", "severity": "warning", "guidance": "g",
           "check": "ok?", "source": "https://developer.android.com/b", "surfaces": ["widget"]},
          {"id": "v7-only", "kind": "structure", "severity": "warning", "guidance": "g",
           "check": "ok?", "source": "https://developer.android.com/c",
           "profiles": ["launcher-widgets-v7"]},
          {"id": "v7-experimental", "kind": "structure", "severity": "info", "guidance": "g",
           "check": "ok?", "source": "https://developer.android.com/d",
           "profiles": ["launcher-widgets-v7+experimental"]}
        ]
      }
      """
    )

  @Test
  fun `the catalog's frames are drawn for the surface they name`() {
    val widget =
      DesignGuidelineFrames.plan(document(WearWidgetScaffoldSize.Large.componentId), guidelines)
    assertEquals(listOf(DesignGuidelinePicture.WIDGET_SAMSUNG, "tablet"), widget.map { it.kind })
    assertEquals(
      JsonPrimitive(WearWidgetHostShape.Round.id),
      widget[0].environment[WearWidgetHostShape.ENVIRONMENT_KEY],
    )
    assertEquals(
      "Picture 1 (widget-samsung picture, 230×168dp): the widget in a stadium.",
      widget[0].describe(1),
    )

    val screen =
      DesignGuidelineFrames.plan(document("wear-m3/screen-scaffold", "wear-m3"), guidelines)
    assertEquals(listOf(DesignGuidelinePicture.DEVICE, "tablet"), screen.map { it.kind })
    val scrolling =
      DesignGuidelineFrames.plan(
        document("wear-m3/screen-scaffold", "wear-m3"),
        guidelines,
        scrolls = true,
      )
    assertEquals(768, scrolling.single { it.kind == DesignGuidelinePicture.UNROLLED }.heightDp)
  }

  @Test
  fun `rules are narrowed by surface and by the profile the design targets`() {
    assertEquals(listOf("any"), guidelines.rulesFor("screen").map { it.id })
    assertEquals(listOf("any", "widget-only"), guidelines.rulesFor("widget").map { it.id })
    assertEquals(
      listOf("any", "widget-only", "v7-only"),
      guidelines.rulesFor("widget", "launcher-widgets-v7").map { it.id },
    )
    assertEquals(
      listOf("any", "widget-only", "v7-only", "v7-experimental"),
      guidelines.rulesFor("widget", "launcher-widgets-v7+experimental").map { it.id },
    )
    assertEquals(
      listOf("any", "widget-only"),
      guidelines.rulesFor("widget", "launcher-widgets-v6").map { it.id },
    )
  }

  @Test
  fun `a request names the catalog the rules came from`() {
    val doc = document(WearWidgetScaffoldSize.Small.componentId)
    val request =
      DesignGuidelinePrompt.prepare(
        guidelines,
        "d",
        1,
        Json.encodeToJsonElement(UiBuilderDocument.serializer(), doc).jsonObject,
        emptyList(),
        null,
        rulesSource = "https://example.test/remote-m3/ui-builder.guidelines.json",
      )
    assertEquals("wear", request.platform)
    assertEquals(3, request.rules.version)
    assertEquals(listOf("any"), request.rules.asked.map { it.id }, "no picture, no visual rules")
    assertEquals(1, request.rules.visualSkipped)
    assertTrue(
      "`remote-m3` catalog's own" in request.provenance.first(),
      request.provenance.first(),
    )
    assertEquals("https://example.test/remote-m3/ui-builder.guidelines.json", request.rules.source)
  }

  @Test
  fun `a launcher widget is a widget`() {
    val doc = document(LauncherWidgetCodeExporter.ROOT)
    assertEquals(
      DesignGuidelineRule.SURFACE_WIDGET,
      DesignGuidelinePrompt.surfaceOf(
        Json.encodeToJsonElement(UiBuilderDocument.serializer(), doc).jsonObject
      ),
    )
  }

  private fun document(root: String, systemId: String = "remote-m3"): UiBuilderDocument =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "d", "title": "D", "revision": 1,
        "catalogPin": {"systemId": "$systemId"},
        "environment": {"widthDp": 192, "heightDp": 192},
        "stateVariables": {},
        "roots": ["root"],
        "nodes": {"root": {"id": "root", "componentId": "$root"}}
      }
      """,
    )
}
