package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CanvasStructuralNodesTest {
  @Test
  fun `loop rows flow through a placement into its component body`() {
    val tree =
      CanvasRenderTree(
        document =
          document(
            nodes =
              mapOf(
                "loop" to
                  node(
                    id = "loop",
                    componentId = "layout/for-each",
                    properties =
                      JsonObject(
                        mapOf(
                          "data" to
                            JsonObject(
                              mapOf(
                                "type" to JsonPrimitive("list"),
                                "values" to
                                  JsonArray(
                                    listOf(
                                      JsonObject(
                                        mapOf(
                                          "type" to JsonPrimitive("object"),
                                          "fields" to
                                            JsonObject(mapOf("shade" to wrapper("string", "Blue"))),
                                        )
                                      )
                                    )
                                  ),
                              )
                            )
                        )
                      ),
                    slots = mapOf("template" to listOf("placement")),
                  ),
                "placement" to
                  node(
                    id = "placement",
                    componentId = "design/component-instance",
                    component =
                      JsonObject(
                        mapOf(
                          "componentKey" to JsonPrimitive("label"),
                          "arguments" to JsonObject(mapOf("tone" to wrapper("binding", "shade"))),
                        )
                      ),
                  ),
                "body" to
                  node(
                    id = "body",
                    componentId = "catalog/text",
                    properties = JsonObject(mapOf("text" to wrapper("binding", "tone"))),
                  ),
              ),
            components =
              JsonObject(
                mapOf(
                  "label" to
                    JsonObject(
                      mapOf("name" to JsonPrimitive("Label"), "root" to JsonPrimitive("body"))
                    )
                )
              ),
          ),
        state = emptyMap(),
        adapterIds = emptyMap(),
        adapterMappings = emptyMap(),
      )

    val loop = requireNotNull(tree.root("loop"))
    val placement = loop.repeatedTemplateEntries().single()
    val body = requireNotNull(placement.placedComponentEntry())

    assertEquals("loop#0/placement", placement.path.toString())
    assertEquals("loop#0/placement/body", body.path.toString())
    assertEquals("Blue", body.node.value("text"))
  }

  @Test
  fun `malformed rows and missing component roots are omitted`() {
    val tree =
      CanvasRenderTree(
        document =
          document(
            nodes =
              mapOf(
                "loop" to
                  node(
                    id = "loop",
                    componentId = "layout/for-each",
                    properties =
                      JsonObject(
                        mapOf(
                          "data" to
                            JsonObject(
                              mapOf(
                                "type" to JsonPrimitive("list"),
                                "values" to JsonArray(listOf(JsonPrimitive("bad"))),
                              )
                            )
                        )
                      ),
                    slots = mapOf("template" to listOf("placement")),
                  ),
                "placement" to
                  node(
                    id = "placement",
                    componentId = "design/component-instance",
                    component = JsonObject(mapOf("componentKey" to JsonPrimitive("missing"))),
                  ),
              )
          ),
        state = emptyMap(),
        adapterIds = emptyMap(),
        adapterMappings = emptyMap(),
      )

    val loop = requireNotNull(tree.root("loop"))

    assertEquals(emptyList(), loop.repeatedTemplateEntries())
    assertNull(requireNotNull(tree.root("placement")).placedComponentEntry())
  }

  private fun document(
    nodes: Map<String, UiBuilderNode>,
    components: JsonObject = JsonObject(emptyMap()),
  ) =
    UiBuilderDocument(
      schema = "compose-ui-builder/v1",
      id = "design",
      title = "Design",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("loop"),
      nodes = nodes,
      components = components,
    )

  private fun node(
    id: String,
    componentId: String,
    properties: JsonObject = JsonObject(emptyMap()),
    slots: Map<String, List<String>> = emptyMap(),
    component: JsonObject? = null,
  ) =
    UiBuilderNode(
      id = id,
      componentId = componentId,
      properties = properties,
      slots = slots,
      component = component,
    )

  private fun wrapper(type: String, value: String) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private fun UiBuilderNode.value(name: String): String =
    ((properties.getValue(name) as JsonObject).getValue("value") as JsonPrimitive).content
}
