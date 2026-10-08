package ee.schimke.composeai.uibuilder.guidelines

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.hostSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class DesignGuidelineFramesTest {
  @Test
  fun `a widget is drawn in the Samsung and Pixel Watch containers`() {
    val frames =
      DesignGuidelineFrames.plan(
        document("remote-m3", WearWidgetScaffoldSize.Large.componentId),
        "wear",
      )
    assertEquals(
      listOf(DesignGuidelinePicture.WIDGET_SAMSUNG, DesignGuidelinePicture.WIDGET_PIXEL_WATCH),
      frames.map { it.kind },
    )
    val samsung = WearWidgetScaffoldSize.Large.hostSpec(WearWidgetHostShape.Round)
    assertEquals(
      samsung.frameWidthDp to samsung.frameHeightDp,
      frames[0].widthDp to frames[0].heightDp,
    )
    assertEquals(
      JsonPrimitive(WearWidgetHostShape.Round.id),
      frames[0].environment[WearWidgetHostShape.ENVIRONMENT_KEY],
    )
    assertEquals(
      JsonPrimitive(WearWidgetHostShape.Squircle.id),
      frames[1].environment[WearWidgetHostShape.ENVIRONMENT_KEY],
    )
    assertTrue("fully rounded ends" in frames[0].describe(1))
  }

  @Test
  fun `a phone design is drawn at a phone and a tablet size, whatever it was authored at`() {
    val frames = DesignGuidelineFrames.plan(document("m3", "m3/scaffold", 360, 640), "mobile")
    assertEquals(
      listOf(DesignGuidelinePicture.PHONE, DesignGuidelinePicture.TABLET),
      frames.map { it.kind },
    )
    assertEquals(JsonPrimitive(1280), frames[1].environment["widthDp"])
    assertTrue("compared" in frames[1].describe(2) || "comparing" in frames[1].describe(2))
  }

  @Test
  fun `a scrolling Wear screen adds the unrolled picture`() {
    val screen = document("wear-m3", "wear-m3/screen-scaffold", 192, 192)
    assertEquals(
      listOf(DesignGuidelinePicture.DEVICE),
      DesignGuidelineFrames.plan(screen, "wear").map { it.kind },
    )
    val frames = DesignGuidelineFrames.plan(screen, "wear", scrolls = true)
    assertEquals(
      listOf(DesignGuidelinePicture.DEVICE, DesignGuidelinePicture.UNROLLED),
      frames.map { it.kind },
    )
    assertEquals(768, frames[1].heightDp)
    assertTrue(frames[0].environment.isEmpty(), "the device picture is the design as authored")
  }

  @Test
  fun `a request carries every planned picture, described in order`() {
    val document = document("m3", "m3/scaffold", 360, 640)
    val pictures =
      DesignGuidelineFrames.plan(document, "mobile").mapIndexed { index, frame ->
        DesignGuidelinePicture.of(frame, index + 1, "data:image/png;base64,AA")
      }
    val request =
      DesignGuidelinePrompt.prepare(
        DesignGuidelineRuleSet.Bundled,
        "d",
        1,
        Json.encodeToJsonElement(UiBuilderDocument.serializer(), document).jsonObject,
        pictures,
        null,
      )
    assertEquals(listOf("phone", "tablet"), request.pictures.map { it.kind })
    assertTrue(request.rules.asked.any { it.visual }, "with pictures the visual rules are asked")
    assertTrue("Picture 2 (tablet picture)" in request.userText, request.userText)
    assertTrue(request.provenance.any { "tablet picture" in it })
  }

  @Test
  fun `a widget is asked only the rules for widgets, and a screen only those for screens`() {
    fun asked(root: String) =
      DesignGuidelinePrompt.prepare(
          DesignGuidelineRuleSet.Bundled,
          "d",
          1,
          Json.encodeToJsonElement(UiBuilderDocument.serializer(), document("wear-m3", root))
            .jsonObject,
          listOf(DesignGuidelinePicture.device(192, 192, "data:image/png;base64,AA")),
          null,
        )
        .rules
        .asked
        .map { it.id }
    val widget = asked(WearWidgetScaffoldSize.Small.componentId)
    val screen = asked("wear-m3/screen-scaffold")
    assertTrue("wear.widgets.focused" in widget && "wear.widgets.focused" !in screen)
    assertTrue("wear.edge-button.in-slot" in screen && "wear.edge-button.in-slot" !in widget)
    assertTrue("wear.touch-target-48dp" in widget && "wear.touch-target-48dp" in screen)
    assertTrue(widget.size < 20 && screen.size < 30, "widget ${widget.size}, screen ${screen.size}")
  }

  private fun document(
    systemId: String,
    root: String,
    widthDp: Int = 216,
    heightDp: Int = 124,
  ): UiBuilderDocument =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "d", "title": "D", "revision": 1,
        "catalogPin": {"systemId": "$systemId"},
        "environment": {"widthDp": $widthDp, "heightDp": $heightDp},
        "stateVariables": {},
        "roots": ["root"],
        "nodes": {"root": {"id": "root", "componentId": "$root"}}
      }
      """,
    )
}
