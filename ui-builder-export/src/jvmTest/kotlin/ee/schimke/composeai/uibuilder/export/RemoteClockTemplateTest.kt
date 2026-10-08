package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * The Clock template is Remote content that animates on its own: one `loop` draws the hour marks,
 * and each hand is turned by a `RemoteTime()` expression rather than by state.
 */
class RemoteClockTemplateTest {
  private val clock =
    RemoteClockTemplate.document("clock", JsonObject(emptyMap()), JsonObject(emptyMap()))

  private fun rotation(id: String) =
    UiExpressions.format(clock.nodes.getValue(id).properties.getValue("rotate"))

  @Test
  fun `it is remote content, not a widget`() {
    assertEquals("layout/box", clock.nodes.getValue(clock.roots.single()).componentId)
    assertTrue(clock.nodes.values.none { it.componentId.startsWith("remote-m3/widget-container") })
    assertTrue(clock.stateVariables.isEmpty())
  }

  @Test
  fun `the hour marks are one tick in a loop and the hands read the clock`() {
    assertEquals(listOf("hour"), UiDrawing.loopIndices(clock, "clock-tick-mark"))
    assertEquals("@hour * 30", rotation("clock-tick"))
    assertEquals("time.minuteOfDay % 720 / 2", rotation("clock-hour-hand"))
    assertEquals("time.secondOfHour / 10", rotation("clock-minute-hand"))
    assertEquals("time.continuousSecond % 60 * 6", rotation("clock-second-hand"))
  }

  @Test
  fun `it exports as a RemoteCanvas the player animates`() {
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(
          InlineRemoteContentExporter.exportRoots(
            clock,
            "proof.draw",
            emptyMap(),
            WidgetAssetBytes { null },
          )
        )
        .source
    // Kept for compiling against the released libraries; see RemoteDrawingCompileProof.
    File("build/draw-proof").apply { mkdirs() }.resolve("ClockContent.kt").writeText(source)

    assertContains(source, "RemoteCanvas(")
    assertEquals(1, Regex("""\bloop\(""").findAll(source).count(), source)
    assertContains(source, "RemoteTime().Minutes()")
    assertContains(source, "RemoteTime().Seconds()")
    assertContains(source, "RemoteTime().ContinuousSec()")
    assertTrue("rememberMutableRemote" !in source, source)
  }

  @Test
  fun `the remote-m3 catalog offers it`() {
    assertTrue(RemoteClockTemplate.TEMPLATE_ID in UiBuilderNewDesignSeed.templateIds("remote-m3"))
  }
}
