package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * `tagNodes` for Remote content: each placed node's id as a content description the native lane
 * reads bounds back from (see [NativeNodeTags]), written ahead of the node's own semantics.
 */
class RemoteNodeTagExportTest {
  private val document: UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"tagged","title":"Tagged","revision":1,
         "catalogPin":{},"environment":{},"stateVariables":{},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-large",
            "slots":{"content":["column"]}},
          "column":{"id":"column","componentId":"layout/column",
            "slots":{"children":["button","play","steps"]}},
          "button":{"id":"button","componentId":"remote-m3/remote-label-button",
            "modifiers":[{"type":"fillMaxWidth"}],
            "slots":{"label":["title"],"icon":["glyph"]}},
          "title":{"id":"title","componentId":"remote-m3/remote-text",
            "properties":{"text":{"type":"string","value":"Start run"}}},
          "glyph":{"id":"glyph","componentId":"remote-m3/remote-icon",
            "properties":{"contentDescription":{"type":"string","value":"Run"}}},
          "play":{"id":"play","componentId":"layout/box",
            "modifiers":[{"type":"size","widthDp":24,"heightDp":24},
              {"type":"remoteCall","name":"semantics","args":{
                "contentDescription":{"type":"string","value":"Play"}}}]},
          "steps":{"id":"steps","componentId":"layout/box",
            "modifiers":[{"type":"remoteCall","name":"semantics","args":{
              "contentDescription":{"type":"string","value":"Steps today"},
              "clear":{"type":"bool","value":true}}}]}}}
        """
      ) as JsonObject
    )

  private fun exported(tagNodes: Boolean): String {
    val result = WearWidgetCodeExporter.export(document, "proof.tags", tagNodes = tagNodes)
    return assertIs<WearWidgetCodeExporter.Result.Emitted>(
        result,
        (result as? WearWidgetCodeExporter.Result.Refused)?.reasons?.toString(),
      )
      .source
  }

  private fun tag(id: String) = "semantics { contentDescription = \"uib-node:$id\".rs }"

  @Test
  fun `every placed node carries its id as a content description`() {
    val source = exported(tagNodes = true)

    listOf("column", "button", "title", "glyph", "play", "steps").forEach {
      assertContains(source, tag(it))
    }
    // The widget container is a scaffold, not emitted content.
    assertFalse("uib-node:host" in source, source)
    assertContains(source, "import androidx.compose.remote.creation.compose.modifier.semantics")
    assertContains(
      source,
      "import androidx.compose.remote.creation.compose.modifier.contentDescription",
    )
    assertContains(source, "import androidx.compose.remote.creation.compose.state.rs")
  }

  @Test
  fun `a node's own semantics are kept, behind its tag`() {
    val source = exported(tagNodes = true)

    // The chain is broken at its dots, the tag first.
    val pad = "\n                ."
    assertContains(
      source,
      "RemoteModifier.${tag("play")}${pad}size(24.rdp, 24.rdp)" +
        "${pad}semantics { contentDescription = \"Play\".rs }",
    )
    assertContains(
      source,
      "RemoteModifier.${tag("steps")}${pad}clearAndSetSemantics { contentDescription = " +
        "\"Steps today\".rs }",
    )
    assertContains(source, "contentDescription = \"Run\".rs")
  }

  @Test
  fun `without tagNodes nothing is tagged`() {
    val source = exported(tagNodes = false)

    assertFalse(NativeNodeTags.PREFIX in source, source)
    assertEquals(source, exported(tagNodes = false))
    assertEquals(
      source,
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(document, "proof.tags")
        )
        .source,
    )
  }

  @Test
  fun `the record-free generator and the native preview lane pass tagNodes to a widget`() {
    val generated =
      assertIs<RecordFreeExport.Generated.Emitted>(
        RecordFreeExport.generate(document, "proof.tags", tagNodes = true)
      )
    assertContains(generated.source, tag("play"))
    val untagged =
      assertIs<RecordFreeExport.Generated.Emitted>(
        RecordFreeExport.generate(document, "proof.tags")
      )
    assertFalse(NativeNodeTags.PREFIX in untagged.source)

    val native =
      assertIs<WearWidgetNativePreviewExporter.Result.Emitted>(
        WearWidgetNativePreviewExporter.export(document, "proof.tags", tagNodes = true)
      )
    assertContains(native.source, tag("steps"))
    val plain =
      assertIs<WearWidgetNativePreviewExporter.Result.Emitted>(
        WearWidgetNativePreviewExporter.export(document, "proof.tags")
      )
    assertFalse(NativeNodeTags.PREFIX in plain.source)
  }

  @Test
  fun `node ids are recovered from joined and list-rendered descriptions`() {
    assertEquals("uib-node:a", NativeNodeTags.tag("a"))
    assertEquals(listOf("a"), NativeNodeTags.nodeIds("uib-node:a"))
    assertEquals(listOf("a"), NativeNodeTags.nodeIds("Play, uib-node:a"))
    assertEquals(listOf("a", "b"), NativeNodeTags.nodeIds("[Play, uib-node:a, uib-node:b]"))
    assertEquals(listOf("a"), NativeNodeTags.nodeIds(listOf("Play", "uib-node:a")))
    assertEquals(listOf("a", "b"), NativeNodeTags.nodeIds(listOf("[uib-node:a]", "uib-node:b")))
    assertTrue(NativeNodeTags.nodeIds("Play").isEmpty())
    assertTrue(NativeNodeTags.nodeIds("uib-node:").isEmpty())
    assertTrue(NativeNodeTags.nodeIds("").isEmpty())
    // Only a value that starts with the prefix is a tag.
    assertTrue(NativeNodeTags.nodeIds("see uib-node:a").isEmpty())
  }
}
