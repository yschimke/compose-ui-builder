package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The device-preset contract the Screen inspector relies on. The presets themselves are the
 * server's (derived from the render lane's `DeviceDimensions`); what is tested here is what the
 * editor does with one.
 */
class DevicePresetTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private val phone = UiBuilderDevicePreset("id:pixel_7", "Pixel 7", "Phones", 411, 914, 2.625)
  private val tablet =
    UiBuilderDevicePreset("id:pixel_tablet", "Pixel Tablet", "Tablets", 1280, 800, 2.0)
  private val watch =
    UiBuilderDevicePreset("id:wearos_square", "Wear OS Square", "Wear OS", 180, 180, 2.0)

  @Test
  fun `a preset replaces the frame and leaves the rest of the environment alone`() {
    val custom =
      ScreenEnvironmentSettings(
        widthDp = 500,
        heightDp = 500,
        density = 1.0,
        fontScale = 1.3,
        locale = "ar-EG",
        theme = EditorScreenTheme.Dark,
        layoutDirection = EditorLayoutDirection.Rtl,
      )

    val applied = custom.withDevicePreset(phone)

    assertEquals(411, applied.widthDp)
    assertEquals(914, applied.heightDp)
    assertEquals(2.625, applied.density)
    // A device is a frame, not a whole environment: checking RTL across three devices must not
    // mean re-picking RTL three times.
    assertEquals(custom.copy(widthDp = 411, heightDp = 914, density = 2.625), applied)
  }

  @Test
  fun `choosing a preset is one undoable step covering width height and density`() {
    val initial = reducer.initial(document, selectedNodeId = "main-episode-title")
    // Start on the phone so all three fields differ from the tablet; the fixture's own default
    // frame happens to be 1280 x 800, which would hide a width/height regression.
    val onPhone =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          initial.document.screenEnvironmentSettings().withDevicePreset(phone)
        ),
      )

    val onTablet =
      reducer.reduce(
        onPhone,
        UiBuilderEditorEvent.UpdateEnvironment(
          onPhone.document.screenEnvironmentSettings().withDevicePreset(tablet)
        ),
      )
    assertEquals(1280, onTablet.environmentInt("widthDp"))
    assertEquals(800, onTablet.environmentInt("heightDp"))
    assertEquals(2.0, onTablet.environmentDouble("density"))

    // One command, so one undo target. Were the three fields dispatched separately, undo after
    // phone -> tablet would leave a phone-width tablet on the canvas.
    val submission = assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(onPhone, onTablet))
    assertEquals(
      setOf("widthDp", "heightDp", "density"),
      submission.command.operations
        .map { assertIs<DesignOperation.SetEnvironment>(it).field }
        .toSet(),
    )

    val undone = reducer.reduce(onTablet, UiBuilderEditorEvent.Undo)
    assertIs<CommandOutcome.Accepted>(undone.lastOutcome)
    assertEquals(411, undone.environmentInt("widthDp"))
    assertEquals(914, undone.environmentInt("heightDp"))
    assertEquals(2.625, undone.environmentDouble("density"))
  }

  @Test
  fun `a preset only writes the fields it actually changes`() {
    // `updateEnvironment` skips a field already at the target value, so switching between two
    // frames that share an axis stays a single-field command — still one undo step.
    val initial = reducer.initial(document, selectedNodeId = "main-episode-title")
    val settings = initial.document.screenEnvironmentSettings()
    assertEquals(1280 to 800, settings.widthDp to settings.heightDp)

    val onTablet =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(settings.withDevicePreset(tablet)),
      )
    val submission = assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(initial, onTablet))
    assertEquals(
      listOf("density"),
      submission.command.operations.map { assertIs<DesignOperation.SetEnvironment>(it).field },
    )
  }

  @Test
  fun `the smallest catalog frame is applicable`() {
    // 180 x 180 (`id:wearos_square`) is the floor `validationError` is written against. A stricter
    // floor would list half the Wear presets in the menu and reject them on click.
    val initial = reducer.initial(document, selectedNodeId = "main-episode-title")
    val settings = initial.document.screenEnvironmentSettings().withDevicePreset(watch)
    assertNull(settings.validationError())

    val applied = reducer.reduce(initial, UiBuilderEditorEvent.UpdateEnvironment(settings))
    assertIs<CommandOutcome.Accepted>(applied.lastOutcome)
    assertEquals(180, applied.environmentInt("widthDp"))
  }

  @Test
  fun `the picker names the frame the canvas is at, and says so when it is hand-typed`() {
    val presets = listOf(phone, tablet, watch)
    val base = document.screenEnvironmentSettings()

    assertEquals(phone, base.withDevicePreset(phone).matchingDevicePreset(presets))
    assertEquals(tablet, base.withDevicePreset(tablet).matchingDevicePreset(presets))
    // Same dp as the tablet but a hand-typed density is not that tablet.
    assertNull(base.withDevicePreset(tablet).copy(density = 3.0).matchingDevicePreset(presets))
    assertNull(
      base.copy(widthDp = 512, heightDp = 512, density = 1.0).matchingDevicePreset(presets)
    )
  }

  @Test
  fun `the menu summary reads as a frame, not a float dump`() {
    assertEquals("411 × 914 dp · 2.625×", phone.summary)
    assertEquals("1280 × 800 dp · 2×", tablet.summary)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun UiBuilderEditorState.environmentInt(field: String): Int =
    document.environment.getValue(field).jsonPrimitive.content.toInt()

  private fun UiBuilderEditorState.environmentDouble(field: String): Double =
    document.environment.getValue(field).jsonPrimitive.content.toDouble()
}
