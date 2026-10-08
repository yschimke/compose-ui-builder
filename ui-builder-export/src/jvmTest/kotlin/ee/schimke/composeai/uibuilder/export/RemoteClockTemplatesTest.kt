package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The clock templates are Remote content that animates on its own: loops draw their repeated marks,
 * and everything that moves is a `RemoteTime()` expression rather than state.
 */
class RemoteClockTemplatesTest {
  private fun document(template: RemoteClockTemplates) =
    template.document(template.templateId, JsonObject(emptyMap()), JsonObject(emptyMap()))

  private fun UiBuilderDocument.property(id: String, name: String) =
    UiExpressions.format(nodes.getValue(id).properties.getValue(name))

  private fun exported(template: RemoteClockTemplates): String =
    assertIs<InlineRemoteContentExporter.Result.Emitted>(
        InlineRemoteContentExporter.exportRoots(
          document(template),
          "proof.draw.${template.name.lowercase()}",
          emptyMap(),
          WidgetAssetBytes { null },
        )
      )
      .source
      .also { source ->
        // Kept for compiling against the released libraries; see RemoteDrawingCompileProof.
        File("build/draw-proof")
          .apply { mkdirs() }
          .resolve("Clock${template.name}.kt")
          .writeText(source)
      }

  @Test
  fun `every clock is remote content, not a widget, and holds no state`() {
    RemoteClockTemplates.entries.forEach { template ->
      val clock = document(template)
      assertEquals("layout/box", clock.nodes.getValue(clock.roots.single()).componentId)
      assertTrue(clock.nodes.values.none { it.componentId.startsWith("remote-m3/widget-") })
      assertTrue(clock.stateVariables.isEmpty())
    }
  }

  @Test
  fun `every clock exports as a RemoteCanvas driven by the clock`() {
    RemoteClockTemplates.entries.forEach { template ->
      val source = exported(template)
      assertContains(source, "RemoteCanvas(")
      assertContains(source, "loop(")
      assertContains(source, "RemoteTime()")
      assertTrue("rememberMutableRemote" !in source, source)
    }
  }

  @Test
  fun `the analog clock loops its hour marks and names the day`() {
    val clock = document(RemoteClockTemplates.Analog)

    assertEquals(listOf("hour"), UiDrawing.loopIndices(clock, "clock-tick-mark"))
    assertEquals("@hour * 30", clock.property("clock-tick", "rotate"))
    assertEquals("time.minuteOfDay % 720 / 2", clock.property("clock-hour-hand", "rotate"))
    assertEquals("time.secondOfHour / 10", clock.property("clock-minute-hand", "rotate"))
    assertEquals(
      "time.continuousSecond % 60 * 6",
      clock.property("clock-second-hand", "rotate"),
    )
    assertEquals("THU", evaluate(clock, "clock-day"))
  }

  @Test
  fun `the digital clock writes the time and its complications`() {
    val clock = document(RemoteClockTemplates.Digital)

    assertEquals("10:10", evaluate(clock, "digital-time"))
    assertEquals("30", evaluate(clock, "digital-time-seconds"))
    assertEquals("THU 16", evaluate(clock, "digital-date"))
    assertEquals("+0", evaluate(clock, "digital-utc"))
    assertEquals(listOf("s"), UiDrawing.loopIndices(clock, "digital-second-mark"))
  }

  @Test
  fun `every clock opens at 10 10 30, whatever time the environment it is given names`() {
    RemoteClockTemplates.entries.forEach {
      val clock =
        it.document(
          it.templateId,
          JsonObject(emptyMap()),
          JsonObject(mapOf("fixedTime" to JsonPrimitive("2024-05-16T12:00:00Z"))),
        )
      assertEquals(JsonPrimitive(ClockCanvas.PREVIEW_TIME), clock.environment["fixedTime"])
    }
  }

  @Test
  fun `a clock seed keeps the state the New design form declared`() {
    if (!UiBuilderBuildFeatures.remoteCompose) return
    val declared = NewDesignState("alarmOn", NewDesignStateType.Flag, JsonPrimitive(false))
    val root =
      generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "docs/design/fixtures/ui-builder").isDirectory }
    val fixture =
      Json.parseToJsonElement(
          File(root, "docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
            .readText()
        )
        .jsonObject
    val seeded =
      UiBuilderNewDesignSeed.document(
        catalogSystemId = "remote-m3",
        templateId = RemoteClockTemplates.Analog.templateId,
        designId = "clock",
        catalogRevision = "candidate",
        nativeRuntimeId = "candidate",
        fixture = fixture,
        state = listOf(declared),
      )
    assertTrue("alarmOn" in seeded.stateVariables, seeded.stateVariables.toString())
  }

  @Test
  fun `the remote-m3 catalog offers every clock where Remote root content exports`() {
    RemoteClockTemplates.entries.forEach {
      assertEquals(
        UiBuilderBuildFeatures.remoteCompose,
        it.templateId in UiBuilderNewDesignSeed.templateIds("remote-m3"),
        it.templateId,
      )
      assertEquals(it, RemoteClockTemplates.forTemplate(it.templateId))
    }
  }

  /** [id]'s `text` at the canvas's default clock, Thursday 16 May 2024, 10:10:30 UTC. */
  private fun evaluate(document: UiBuilderDocument, id: String): String {
    val checked =
      UiExpressions.check(
        document.nodes.getValue(id).properties.getValue("text"),
        UiDrawing.expressionScope(document, id),
      )
    val expr = assertIs<UiExpressions.Checked.Ok>(checked, checked.toString()).expr
    return UiExpressions.evaluate(expr, UiExpressions.Environment()).toString()
  }
}
