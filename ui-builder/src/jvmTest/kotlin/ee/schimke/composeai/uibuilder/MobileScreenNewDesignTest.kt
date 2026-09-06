package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The frame a new Material 3 screen opens on.
 *
 * Seeded straight from the fixture's environment, a blank design inherited Jetcaster's 1280x800 at
 * density 1 — the expanded supporting-pane canvas that design is *about*, and a frame no handset
 * has. So every new screen opened on a device menu reading "Custom size", and the first thing an
 * author had to do was pick one. A phone is what a screen is drawn for; `pixel_6` is the typical
 * one, and its geometry is shared by four catalog entries, so the match survives the catalog
 * growing.
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
   * A device is a frame, not an environment: the theme, locale, font scale and direction the
   * fixture carries survive the re-frame, the same way picking a device in the inspector leaves
   * them alone.
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
   * The Jetcaster template keeps the fixture's own frame.
   *
   * It is that design at that size — re-pinning it to a phone would redraw the expanded supporting
   * pane it exists to show as a compact one, which is a different screen.
   */
  @Test
  fun `the worked template keeps its own canvas`() {
    val environment = seed(UiBuilderNewDesignSeed.DEFAULT_TEMPLATE).environment

    assertEquals("1280", environment.getValue("widthDp").jsonPrimitive.content)
    assertEquals("800", environment.getValue("heightDp").jsonPrimitive.content)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
