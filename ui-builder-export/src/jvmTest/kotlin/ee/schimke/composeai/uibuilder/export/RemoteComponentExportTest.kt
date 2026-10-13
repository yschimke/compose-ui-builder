package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * A Remote design's components export alone, as `internal` `@RemoteComposable` functions a catalog
 * calls itself (yschimke/remote-m3-catalog's shadow catalog): bindings are parameters, theme roles
 * are read from the caller's theme, and the placing root is not written.
 */
class RemoteComponentExportTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun document(root: String = "place", key: String = "label") =
    json
      .decodeFromString<DesignDocumentV1>(
        """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "list-header", "title": "KitListHeaderRemote", "revision": 0,
        "catalogPin": {"systemId": "remote-m3", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 192, "heightDp": 192, "density": 2.0, "theme": "dark",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
        "roots": ["$root"],
        "components": {"list-header": {"name": "KitListHeaderRemote", "root": "frame"}},
        "nodes": {
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "list-header",
              "arguments": {"$key": {"type": "string", "value": "Title"}}}},
          "frame": {"id": "frame", "componentId": "layout/box",
            "properties": {"contentAlignment": {"type": "enum", "value": "center"}},
            "modifiers": [{"type": "width", "widthDp": 180},
              {"type": "padding", "startDp": 14, "topDp": 16, "endDp": 14, "bottomDp": 12}],
            "slots": {"children": ["title"]}},
          "title": {"id": "title", "componentId": "remote-m3/remote-text",
            "properties": {"text": {"type": "binding", "value": "$key"},
              "style": {"type": "enum", "value": "titleMedium"},
              "color": {"type": "enum", "value": "onBackground"},
              "textAlign": {"type": "enum", "value": "center"}},
            "modifiers": [{"type": "fillMaxWidth"}]}
        }
      }
      """
      )
      .toUiBuilderDocument()

  @Test
  fun `components export alone, internal, with bindings as parameters`() {
    val result =
      InlineRemoteContentExporter.exportComponents(document(), "example.remote.generated")
    val emitted = assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString())
    assertEquals("KitListHeaderRemote", emitted.functionName)
    val source = emitted.source
    assertTrue("internal fun KitListHeaderRemote(" in source, source)
    assertTrue(
      "label: androidx.compose.remote.creation.compose.state.RemoteString" in source,
      source,
    )
    assertTrue("text = label" in source, source)
    assertTrue("RemoteMaterialTheme.typography.titleMedium" in source, source)
    // The placing root is not written, and no theme is wrapped around a component.
    assertFalse("RemoteContent" in source, source)
    assertFalse("RemoteMaterialTheme {" in source, source)
  }

  @Test
  fun `a key spelled like a generated parameter keeps the generated spelling`() {
    for (key in listOf("argument1", "capture0")) {
      val result = InlineRemoteContentExporter.exportComponents(document(key = key))
      val source =
        assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString()).source
      assertTrue("argument0: " in source, source)
      assertFalse("$key: " in source, source)
    }
  }

  @Test
  fun `an action-free click is an Action parameter the caller supplies`() {
    val card =
      json
        .decodeFromString<DesignDocumentV1>(
          """
          {
            "schema": "compose-ui-builder-document/v1-candidate",
            "id": "card", "title": "KitCardRemote", "revision": 0,
            "catalogPin": {"systemId": "remote-m3", "catalogRevision": "candidate",
              "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
            "environment": {"widthDp": 192, "heightDp": 192, "density": 2.0, "theme": "dark",
              "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
            "roots": ["place"],
            "components": {"card": {"name": "KitCardRemote", "root": "card"}},
            "nodes": {
              "place": {"id": "place", "componentId": "design/component-instance",
                "component": {"componentKey": "card",
                  "arguments": {"content": {"type": "string", "value": "Content"}}}},
              "card": {"id": "card", "componentId": "remote-m3/remote-card",
                "slots": {"content": ["text"]}, "eventBindings": {"click": []}},
              "text": {"id": "text", "componentId": "remote-m3/remote-text",
                "properties": {"text": {"type": "binding", "value": "content"}}}
            }
          }
          """
        )
        .toUiBuilderDocument()
    val result = InlineRemoteContentExporter.exportComponents(card)
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString()).source
    assertTrue(
      "onCardClick: androidx.compose.remote.creation.compose.action.Action" in source,
      source,
    )
    assertTrue("onClick = onCardClick" in source, source)
    assertFalse("lambdaAction {}" in source, source)
  }

  private fun remoteDocument(components: String, nodes: String) =
    json
      .decodeFromString<DesignDocumentV1>(
        """
        {
          "schema": "compose-ui-builder-document/v1-candidate",
          "id": "doc", "title": "Doc", "revision": 0,
          "catalogPin": {"systemId": "remote-m3", "catalogRevision": "candidate",
            "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
          "environment": {"widthDp": 192, "heightDp": 192, "density": 2.0, "theme": "dark",
            "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
          "roots": ["place"],
          "components": {$components},
          "nodes": {$nodes}
        }
        """
      )
      .toUiBuilderDocument()

  @Test
  fun `two nodes that spell the same intent do not share a parameter`() {
    val result =
      InlineRemoteContentExporter.exportComponents(
        remoteDocument(
          """"pair": {"name": "KitPair", "root": "column"}""",
          """
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "pair"}},
          "column": {"id": "column", "componentId": "layout/column",
            "slots": {"children": ["card-one", "card_one"]}},
          "card-one": {"id": "card-one", "componentId": "remote-m3/remote-card",
            "eventBindings": {"click": []}},
          "card_one": {"id": "card_one", "componentId": "remote-m3/remote-card",
            "eventBindings": {"click": []}}
          """,
        )
      )
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString()).source
    assertEquals(1, Regex("onCardOneClick: ").findAll(source).count(), source)
    assertEquals(1, Regex("onClick = onCardOneClick").findAll(source).count(), source)
  }

  @Test
  fun `a placed component's intent is forwarded through the component that places it`() {
    val result =
      InlineRemoteContentExporter.exportComponents(
        remoteDocument(
          """
          "outer": {"name": "KitOuter", "root": "column"},
          "card": {"name": "KitCardRemote", "root": "card"}
          """,
          """
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "outer"}},
          "column": {"id": "column", "componentId": "layout/column",
            "slots": {"children": ["inner"]}},
          "inner": {"id": "inner", "componentId": "design/component-instance",
            "component": {"componentKey": "card"}},
          "card": {"id": "card", "componentId": "remote-m3/remote-card",
            "eventBindings": {"click": []}}
          """,
        )
      )
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString()).source
    assertEquals(2, Regex("onCardClick: ").findAll(source).count(), source)
    assertTrue("onCardClick = onCardClick" in source, source)
    assertFalse("lambdaAction {}" in source, source)
  }

  @Test
  fun `a root that is not a placement is refused`() {
    val result = InlineRemoteContentExporter.exportComponents(document(root = "frame"))
    assertIs<InlineRemoteContentExporter.Result.Refused>(result, result.toString())
  }
}
