package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The frame a new Material 3 screen opens on: a phone (`pixel_6`, whose geometry four catalog
 * entries share), not the fixture's 1280x800 at density 1, which no handset has.
 */
class MobileScreenNewDesignTest {
  private val fixture =
    Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject

  private fun seed(templateId: String) =
    UiBuilderNewDesignSeed.document(
      designId = "screen",
      catalogSystemId = "m3-catalog",
      templateId = templateId,
      catalogRevision = "candidate",
      nativeRuntimeId = "candidate",
      fixture = fixture,
    )

  @Test
  fun `a blank screen is created on the pixel 6 frame`() {
    val environment = seed("blank").environment

    assertEquals("411", environment.getValue("widthDp").jsonPrimitive.content)
    assertEquals("914", environment.getValue("heightDp").jsonPrimitive.content)
    assertEquals("2.625", environment.getValue("density").jsonPrimitive.content)
  }

  /**
   * A device is a frame, not an environment: theme, locale, font scale and direction survive the
   * re-frame.
   */
  @Test
  fun `the rest of the environment survives the frame`() {
    val environment = seed("blank").environment

    assertEquals("dark", environment.getValue("theme").jsonPrimitive.content)
    assertEquals("en-US", environment.getValue("locale").jsonPrimitive.content)
    assertEquals("1.0", environment.getValue("fontScale").jsonPrimitive.content)
    assertEquals("ltr", environment.getValue("layoutDirection").jsonPrimitive.content)
  }

  /**
   * The Jetcaster template keeps the fixture's own frame: it exists to show the expanded supporting
   * pane.
   */
  @Test
  fun `the worked template keeps its own canvas`() {
    val environment = seed(UiBuilderNewDesignSeed.FIXTURE_TEMPLATE).environment

    assertEquals("1280", environment.getValue("widthDp").jsonPrimitive.content)
    assertEquals("800", environment.getValue("heightDp").jsonPrimitive.content)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
