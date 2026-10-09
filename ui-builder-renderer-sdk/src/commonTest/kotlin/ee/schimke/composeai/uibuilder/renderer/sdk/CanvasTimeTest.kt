package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiTimeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CanvasTimeTest {
  private fun document(vararg nodes: UiBuilderNode) =
    UiBuilderDocument(
      schema = "compose-ui-builder/v1",
      id = "design",
      title = "Design",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(mapOf("animations" to JsonPrimitive("settled"))),
      stateVariables = JsonObject(emptyMap()),
      roots = nodes.map { it.id },
      nodes = nodes.associateBy { it.id },
    )

  private fun system(id: String) =
    JsonObject(mapOf("type" to JsonPrimitive("system"), "value" to JsonPrimitive(id)))

  private fun node(
    id: String,
    componentId: String = "draw/rect",
    vararg properties: Pair<String, JsonObject>,
  ) = UiBuilderNode(id = id, componentId = componentId, properties = JsonObject(properties.toMap()))

  @Test
  fun `a design reads the clock through its time values`() {
    assertEquals(ClockReads.NONE, document(node("a")).clockReads())
    assertEquals(
      ClockReads.WHOLE_SECONDS,
      document(node("a", properties = arrayOf("xDp" to system("time.secondOfHour")))).clockReads(),
    )
    assertEquals(
      ClockReads.CONTINUOUS,
      document(
          node("a", properties = arrayOf("xDp" to system("time.secondOfHour"))),
          node("b", properties = arrayOf("yDp" to system("time.continuousSecond"))),
        )
        .clockReads(),
    )
  }

  @Test
  fun `a time text reads the clock with no time value of its own`() {
    assertEquals(ClockReads.WHOLE_SECONDS, document(node("t", UiTimeText.ID)).clockReads())
  }

  @Test
  fun `a design reads the clock when anything in it does`() {
    assertFalse(document(node("a")).readsClock)
    assertTrue(document(node("t", UiTimeText.ID)).readsClock)
  }

  @Test
  fun `time runs only where a surface says so`() {
    val settled = document(node("a"))
    assertFalse(settled.timeRuns)
    assertTrue(settled.withTimeRunning(true).timeRuns)
    assertFalse(settled.withTimeRunning(true).withTimeRunning(false).timeRuns)
    assertTrue(settled.withTimeRunning(false) === settled)
  }
}
