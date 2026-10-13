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
  fun `an outlined card's border is a RemoteBorderStroke from its border colour and width`() {
    val result =
      InlineRemoteContentExporter.exportComponents(
        remoteDocument(
          """"card": {"name": "KitOutlinedCardRemote", "root": "card"}""",
          """
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "card"}},
          "card": {"id": "card", "componentId": "remote-m3/remote-outlined-card",
            "properties": {"borderColor": {"type": "colorToken", "value": "outline"},
              "borderWidthDp": {"type": "int", "value": 2}},
            "eventBindings": {"click": []}}
          """,
        )
      )
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString()).source
    assertTrue(
      "border = RemoteBorderStroke(2.rdp, RemoteMaterialTheme.colorScheme.outline)" in source,
      source,
    )
    assertTrue("import androidx.wear.compose.remote.material3.RemoteBorderStroke" in source, source)
  }

  @Test
  fun `a record's RemoteDp and RemoteColor parameters take numbers and theme roles`() {
    val result =
      InlineRemoteContentExporter.exportComponents(
        remoteDocument(
          """"button": {"name": "KitButtonRemote", "root": "button"}""",
          """
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "button"}},
          "button": {"id": "button", "componentId": "remote-m3/remote-button",
            "properties": {"border": {"type": "int", "value": 1},
              "borderColor": {"type": "colorToken", "value": "outline"}},
            "eventBindings": {"click": []}}
          """,
        )
      )
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString()).source
    assertTrue("border = 1.rdp" in source, source)
    assertTrue("borderColor = RemoteMaterialTheme.colorScheme.outline" in source, source)
  }

  @Test
  fun `a progress indicator binds its progress, enabled state and stroke width`() {
    val result =
      InlineRemoteContentExporter.exportComponents(
        remoteDocument(
          """"ring": {"name": "KitRing", "root": "ring"}""",
          """
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "ring", "arguments": {
              "progress": {"type": "float", "value": 0.6},
              "enabled": {"type": "bool", "value": true},
              "strokeWidth": {"type": "int", "value": 8}}}},
          "ring": {"id": "ring", "componentId": "remote-m3/remote-circular-progress-indicator",
            "properties": {"progress": {"type": "binding", "value": "progress"},
              "enabled": {"type": "binding", "value": "enabled"},
              "strokeWidth": {"type": "binding", "value": "strokeWidth"}}}
          """,
        )
      )
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString()).source
    assertTrue(
      "strokeWidth: androidx.compose.remote.creation.compose.state.RemoteDp" in source,
      source,
    )
    assertTrue("strokeWidth = strokeWidth" in source, source)
    assertTrue("progress = progress" in source, source)
  }

  @Test
  fun `an indeterminate progress indicator calls the overload without progress`() {
    val result =
      InlineRemoteContentExporter.exportComponents(
        remoteDocument(
          """"ring": {"name": "KitRing", "root": "ring"}""",
          """
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "ring"}},
          "ring": {"id": "ring", "componentId": "remote-m3/remote-circular-progress-indicator",
            "properties": {"indeterminate": {"type": "bool", "value": true},
              "strokeWidth": {"type": "int", "value": 8}}}
          """,
        )
      )
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString()).source
    assertTrue("RemoteCircularProgressIndicator(" in source, source)
    assertTrue("strokeWidth = 8.rdp" in source, source)
    assertFalse("progress =" in source, source)
    assertFalse("enabled =" in source, source)
  }

  @Test
  fun `an indeterminate progress indicator refuses a progress value`() {
    val result =
      InlineRemoteContentExporter.exportComponents(
        remoteDocument(
          """"ring": {"name": "KitRing", "root": "ring"}""",
          """
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "ring"}},
          "ring": {"id": "ring", "componentId": "remote-m3/remote-circular-progress-indicator",
            "properties": {"indeterminate": {"type": "bool", "value": true},
              "progress": {"type": "float", "value": 0.5}}}
          """,
        )
      )
    assertIs<InlineRemoteContentExporter.Result.Refused>(result, result.toString())
  }

  @Test
  fun `a button's colour roles fill the library's colours factory`() {
    val result =
      InlineRemoteContentExporter.exportComponents(
        remoteDocument(
          """"button": {"name": "KitTonalButtonRemote", "root": "button"}""",
          """
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "button"}},
          "button": {"id": "button", "componentId": "remote-m3/remote-button",
            "properties": {
              "containerColor": {"type": "colorToken", "value": "surfaceContainer"},
              "contentColor": {"type": "colorToken", "value": "onSurface"}},
            "eventBindings": {"click": []}}
          """,
        )
      )
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString()).source
    assertTrue(
      "colors = RemoteButtonDefaults.buttonColors(containerColor = " +
        "RemoteMaterialTheme.colorScheme.surfaceContainer, contentColor = " +
        "RemoteMaterialTheme.colorScheme.onSurface)" in source,
      source,
    )
    assertTrue(
      "import androidx.wear.compose.remote.material3.RemoteButtonDefaults" in source,
      source,
    )
  }

  @Test
  fun `a colour role that is not a colour is refused`() {
    val result =
      InlineRemoteContentExporter.exportComponents(
        remoteDocument(
          """"button": {"name": "KitButtonRemote", "root": "button"}""",
          """
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "button"}},
          "button": {"id": "button", "componentId": "remote-m3/remote-button",
            "properties": {"containerColor": {"type": "colorToken", "value": "not a role"}},
            "eventBindings": {"click": []}}
          """,
        )
      )
    assertIs<InlineRemoteContentExporter.Result.Refused>(result, result.toString())
  }

  @Test
  fun `a bound colour takes a theme role from its placement`() {
    val result =
      InlineRemoteContentExporter.exportComponents(
        remoteDocument(
          """"button": {"name": "KitButtonRemote", "root": "button"}""",
          """
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "button", "arguments": {
              "borderColor": {"type": "colorToken", "value": "outline"}}}},
          "button": {"id": "button", "componentId": "remote-m3/remote-button",
            "properties": {"borderColor": {"type": "binding", "value": "borderColor"}},
            "eventBindings": {"click": []}}
          """,
        )
      )
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString()).source
    assertTrue(
      "borderColor: androidx.compose.remote.creation.compose.state.RemoteColor" in source,
      source,
    )
  }

  @Test
  fun `a page indicator writes its radius and padding in dp`() {
    val result =
      InlineRemoteContentExporter.exportComponents(
        remoteDocument(
          """"dots": {"name": "KitDots", "root": "dots"}""",
          """
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "dots"}},
          "dots": {"id": "dots", "componentId": "remote-m3/remote-horizontal-page-indicator",
            "properties": {"pageCount": {"type": "int", "value": 3},
              "indicatorRadius": {"type": "int", "value": 3},
              "padding": {"type": "int", "value": 2}}}
          """,
        )
      )
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(result, result.toString()).source
    assertTrue("indicatorRadius = 3.rdp" in source, source)
    assertTrue("padding = 2.rdp" in source, source)
  }

  @Test
  fun `a root that is not a placement is refused`() {
    val result = InlineRemoteContentExporter.exportComponents(document(root = "frame"))
    assertIs<InlineRemoteContentExporter.Result.Refused>(result, result.toString())
  }
}
