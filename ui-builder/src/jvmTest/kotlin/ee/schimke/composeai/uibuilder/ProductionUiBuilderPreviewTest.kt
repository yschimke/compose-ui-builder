package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductionUiBuilderPreviewTest {
  @Test
  fun `production preview decodes a 99 node projected saved document without dropping typed data`() {
    val nodes =
      (1..99).joinToString(",") { index ->
        "\"text-$index\":{" +
          "\"id\":\"text-$index\"," +
          "\"componentId\":\"m3/text\"," +
          "\"properties\":{\"text\":{\"type\":\"string\",\"value\":\"Node $index\"}}," +
          "\"modifiers\":[],\"slots\":{},\"eventBindings\":{}}"
      }
    val roots = (1..99).joinToString(",") { "\"text-$it\"" }
    val source =
      """{"schema":"compose-ui-builder-document/v1-candidate","id":"large-design","title":"Large design","revision":99,"catalogPin":{"systemId":"m3-catalog","catalogRevision":"candidate","capabilityDigest":"candidate","nativeRuntimeId":"candidate"},"environment":{"widthDp":1280,"heightDp":800,"density":1.0,"theme":"dark","locale":"en-US","fontScale":1.0,"layoutDirection":"ltr","windowPosture":"flat","animations":"settled"},"stateVariables":{},"roots":[$roots],"nodes":{$nodes}}"""

    val decoded = decodeProductionRendererDocument(source)

    assertEquals(99, decoded.revision)
    assertEquals(99, decoded.nodes.size)
    assertEquals((1..99).map { "text-$it" }, decoded.roots)
    assertTrue(decoded.nodes.getValue("text-99").properties.toString().contains("Node 99"))
  }
}
