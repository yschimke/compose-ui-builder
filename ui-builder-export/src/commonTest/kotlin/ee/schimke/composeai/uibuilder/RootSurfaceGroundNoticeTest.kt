package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** When [RootSurfaceGround] speaks, and — as importantly — when it stays quiet. */
class RootSurfaceGroundNoticeTest {

  @Test
  fun `a coloured root with no size modifier is reported by id`() {
    val notice = assertNotNull(RootSurfaceGround.diagnose(document(root("#313338"))))
    assertEquals("root", notice.nodeId)
  }

  @Test
  fun `a theme token is a colour too`() {
    assertNotNull(
      RootSurfaceGround.diagnose(document(root("surfaceContainer", type = "colorToken")))
    )
  }

  @Test
  fun `a root that fills the frame is not reported, however it says so`() {
    assertNull(RootSurfaceGround.diagnose(document(root("#313338", "fillMaxSize"))))
    assertNull(RootSurfaceGround.diagnose(document(root("#313338", "matchParentSize"))))
    assertNull(
      RootSurfaceGround.diagnose(document(root("#313338", "fillMaxWidth", "fillMaxHeight")))
    )
  }

  @Test
  fun `a root with no containerColor has nothing to be misread`() {
    assertNull(RootSurfaceGround.diagnose(document(root(null))))
  }

  @Test
  fun `a nested surface is not the frame's business`() {
    val nested =
      document(
        root(null, "fillMaxSize").copy(slots = mapOf("content" to listOf("inner"))),
        UiBuilderNode(
          id = "inner",
          componentId = "m3/surface",
          properties = JsonObject(mapOf("containerColor" to value("#383A40", "color"))),
        ),
      )
    assertNull(RootSurfaceGround.diagnose(nested))
  }

  @Test
  fun `a root that is not a surface is not reported`() {
    val column =
      document(
        UiBuilderNode(
          id = "root",
          componentId = "layout/column",
          properties = JsonObject(mapOf("containerColor" to value("#313338", "color"))),
        )
      )
    assertNull(RootSurfaceGround.diagnose(column))
  }

  private fun root(colour: String?, vararg modifiers: String, type: String = "color") =
    UiBuilderNode(
      id = "root",
      componentId = "m3/surface",
      properties =
        JsonObject(colour?.let { mapOf("containerColor" to value(it, type)) } ?: emptyMap()),
      modifiers = JsonArray(modifiers.map { JsonObject(mapOf("type" to JsonPrimitive(it))) }),
    )

  private fun value(content: String, type: String) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(content)))

  private fun document(root: UiBuilderNode, vararg others: UiBuilderNode) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "ground",
      title = "Ground",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(root.id),
      nodes = (listOf(root) + others).associateBy { it.id },
    )
}
