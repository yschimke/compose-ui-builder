package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ChainLink
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlin.test.*
import kotlinx.serialization.json.Json

/**
 * `layout/scaffold` pads its content by the padding `Scaffold` hands its content lambda, the way
 * the canvas does — `Scaffold(…) { contentPadding -> Column(Modifier.padding(contentPadding)…) }` —
 * rather than drawing the body under the top bar.
 */
class ScaffoldContentPaddingProjectionTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun document(contentModifiers: String = "[]", content: String = "[\"body\"]") =
    json.decodeFromString<DesignDocumentV1>(
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "scaffold-padding",
        "title": "Scaffold padding",
        "revision": 0,
        "catalogPin": {"systemId": "m3-catalog", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 360, "heightDp": 640, "density": 1.0, "theme": "light",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
        "roots": ["screen"],
        "nodes": {
          "screen": {"id": "screen", "componentId": "layout/scaffold", "properties": {},
            "slots": {"topBar": ["bar"], "content": $content}, "modifiers": []},
          "bar": {"id": "bar", "componentId": "layout/box", "properties": {}, "slots": {},
            "modifiers": []},
          "body": {"id": "body", "componentId": "layout/column", "properties": {}, "slots": {},
            "modifiers": $contentModifiers}
        }
      }
      """
    )

  private fun root(document: DesignDocumentV1): ScreenNode =
    assertIs<ScreenDocumentProjection.Outcome.Projected>(
        ScreenDocumentProjection.project(document, "ScaffoldPadding")
      )
      .document
      .root
      .let { if (it.componentId == "layout/scaffold") it else it.slots.values.flatten().single() }

  private val padding =
    ChainLink(
      "androidx.compose.foundation.layout.padding",
      positional =
        listOf(
          ScreenValue.SlotParameterRead(
            "contentPadding",
            "androidx.compose.foundation.layout.PaddingValues",
          )
        ),
    )

  private fun links(node: ScreenNode) =
    assertIs<ScreenValue.Chain>(node.arguments.getValue("modifier")).links

  @Test
  fun `the content lambda's parameter is named and each content child is padded by it`() {
    val scaffold = root(document())
    assertEquals(mapOf("content" to "contentPadding"), scaffold.slotParameters)
    assertEquals(listOf(padding), links(scaffold.slots.getValue("content").single()))
  }

  @Test
  fun `the padding leads, so the child's own modifiers apply inside it`() {
    val body =
      root(document(contentModifiers = """[{"type": "fillMaxSize"}]"""))
        .slots
        .getValue("content")
        .single()
    assertEquals(padding, links(body).first())
    assertEquals("androidx.compose.foundation.layout.fillMaxSize", links(body)[1].callableFqn)
  }

  @Test
  fun `only content is padded, and a scaffold with no content binds nothing`() {
    val bar = root(document()).slots.getValue("topBar").single()
    assertNull(bar.arguments["modifier"])
    assertEquals(emptyMap(), root(document(content = "[]")).slotParameters)
  }
}
