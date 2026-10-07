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
