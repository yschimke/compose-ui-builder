package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlin.test.*
import kotlinx.serialization.json.Json

/**
 * `m3/card` and `m3/icon-button` select their Material 3 function by `style`, the collapsed
 * catalog's spelling, and still by `variant`, which designs saved before it carry. Either is spent
 * on the call site rather than handed to the component as a parameter it does not declare.
 */
class StyleSelectsComponentProjectionTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun document(vararg nodes: Pair<String, String>) =
    json.decodeFromString<DesignDocumentV1>(
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "styled",
        "title": "Styled",
        "revision": 0,
        "catalogPin": {"systemId": "m3-catalog", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 360, "heightDp": 640, "density": 1.0, "theme": "light",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
        "roots": ["column"],
        "nodes": {
          "column": {"id": "column", "componentId": "layout/column", "properties": {},
            "slots": {"children": [${nodes.joinToString { "\"${it.first}\"" }}]}, "modifiers": []},
          ${nodes.joinToString(",\n") { (id, body) -> "\"$id\": $body" }}
        }
      }
      """
    )

  private fun node(id: String, componentId: String, properties: String) =
    id to
      """{"id": "$id", "componentId": "$componentId", "properties": {$properties},
        "slots": {}, "modifiers": []}"""

  private fun projected(document: DesignDocumentV1) =
    assertIs<ScreenDocumentProjection.Outcome.Projected>(
        ScreenDocumentProjection.project(document, "Styled")
      )
      .document
      .root
      .slots
      .values
      .flatten()

  @Test
  fun `style selects the component, and variant still does for older designs`() {
    val children =
      projected(
        document(
          node("styled", "m3/card", """"style": {"type": "enum", "value": "outlined"}"""),
          node("legacy", "m3/card", """"variant": {"type": "enum", "value": "elevated"}"""),
          node(
            "both",
            "m3/card",
            """"style": {"type": "enum", "value": "outlined"},
              "variant": {"type": "enum", "value": "elevated"}""",
          ),
          node("tonal", "m3/icon-button", """"style": {"type": "enum", "value": "tonal"}"""),
        )
      )
    assertEquals(
      listOf(
        "androidx.compose.material3.CardKt.OutlinedCard",
        "androidx.compose.material3.CardKt.ElevatedCard",
        "androidx.compose.material3.CardKt.OutlinedCard",
        "androidx.compose.material3.IconButtonKt.FilledTonalIconButton",
      ),
      children.map { it.componentId },
    )
    children.forEach { child ->
      assertNull(child.arguments["style"], child.toString())
      assertNull(child.arguments["variant"], child.toString())
    }
  }
}
