package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CanvasRenderTreeTest {
  @Test
  fun `entry resolves bindings state mapping and adapter before dispatch`() {
    val child =
      UiBuilderNode(
        id = "label",
        componentId = "catalog/label",
        properties =
          JsonObject(
            mapOf(
              "headline" to wrapper("binding", "title"),
              "selected" to
                JsonObject(
                  mapOf(
                    "type" to JsonPrimitive("stateEquals"),
                    "variable" to JsonPrimitive("selection"),
                    "value" to JsonPrimitive("label"),
                  )
                ),
            )
          ),
        slots = mapOf("body" to listOf("leaf")),
      )
    val tree =
      tree(
        nodes =
          mapOf(
            "root" to node("root", slots = mapOf("content" to listOf("label"))),
            "label" to child,
            "leaf" to node("leaf"),
          ),
        state = mapOf("selection" to "label"),
        adapterIds = mapOf("catalog/label" to "catalog/text"),
        adapterMappings =
          mapOf(
            "catalog/label" to
              CanvasAdapterMappingV1.Builder()
                .apply {
                  properties = mapOf("text" to "headline")
                  slots = mapOf("content" to "body")
                }
                .build()
          ),
      )

    val root = requireNotNull(tree.root("root"))
    val resolved =
      requireNotNull(
        root.occurrenceChild(
          nodeId = "label",
          index = 2,
          arguments = JsonObject(mapOf("title" to wrapper("string", "Resolved"))),
        )
      )

    assertEquals("catalog/text", resolved.adapterId)
    assertEquals("Resolved", resolved.node.value("text"))
    assertEquals("true", resolved.node.value("selected"))
    assertEquals("root#2/label", resolved.path.toString())
    assertEquals(listOf("leaf"), resolved.slot("content").map { it.node.id })
    assertEquals("root#2/label/leaf", resolved.slot("content").single().path.toString())
  }

  @Test
  fun `missing and cyclic references are omitted`() {
    val tree =
      tree(
        nodes =
          mapOf(
            "root" to node("root", slots = mapOf("content" to listOf("child", "missing"))),
            "child" to node("child", slots = mapOf("content" to listOf("root"))),
          )
      )

    val root = requireNotNull(tree.root("root"))
    val child = requireNotNull(root.child("child"))

    assertEquals(listOf("child"), root.slot("content").map { it.node.id })
    assertNull(child.child("root"))
    assertNull(tree.root("missing"))
  }

  private fun tree(
    nodes: Map<String, UiBuilderNode>,
    state: Map<String, String?> = emptyMap(),
    adapterIds: Map<String, String> = emptyMap(),
    adapterMappings: Map<String, CanvasAdapterMappingV1> = emptyMap(),
  ) =
    CanvasRenderTree(
      document =
        UiBuilderDocument(
          schema = "compose-ui-builder/v1",
          id = "design",
          title = "Design",
          revision = 0,
          catalogPin = JsonObject(emptyMap()),
          environment = JsonObject(emptyMap()),
          stateVariables = JsonObject(emptyMap()),
          roots = listOf("root"),
          nodes = nodes,
        ),
      state = state,
      adapterIds = adapterIds,
      adapterMappings = adapterMappings,
    )

  private fun node(
    id: String,
    slots: Map<String, List<String>> = emptyMap(),
  ) = UiBuilderNode(id = id, componentId = "layout/$id", slots = slots)

  private fun wrapper(type: String, value: String) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private fun UiBuilderNode.value(name: String): String =
    ((properties.getValue(name) as JsonObject).getValue("value") as JsonPrimitive).content
}
