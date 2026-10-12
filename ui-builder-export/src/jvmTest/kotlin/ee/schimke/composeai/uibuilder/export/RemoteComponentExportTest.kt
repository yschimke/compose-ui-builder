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

  private fun document(root: String = "place") =
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
              "arguments": {"label": {"type": "string", "value": "Title"}}}},
          "frame": {"id": "frame", "componentId": "layout/box",
            "properties": {"contentAlignment": {"type": "enum", "value": "center"}},
            "modifiers": [{"type": "width", "widthDp": 180},
              {"type": "padding", "startDp": 14, "topDp": 16, "endDp": 14, "bottomDp": 12}],
            "slots": {"children": ["title"]}},
          "title": {"id": "title", "componentId": "remote-m3/remote-text",
            "properties": {"text": {"type": "binding", "value": "label"},
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
  fun `a root that is not a placement is refused`() {
    val result = InlineRemoteContentExporter.exportComponents(document(root = "frame"))
    assertIs<InlineRemoteContentExporter.Result.Refused>(result, result.toString())
  }
}
