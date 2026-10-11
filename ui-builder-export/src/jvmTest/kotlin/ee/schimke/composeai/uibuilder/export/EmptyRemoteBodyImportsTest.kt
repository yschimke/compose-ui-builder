package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * An empty body is still a `RemoteBox`, so the file must import it (and `RemoteModifier`): the
 * native and inline lanes write it through `RemoteContentEmitter.emptyBox`.
 */
class EmptyRemoteBodyImportsTest {
  private val required =
    listOf(
      "import androidx.compose.remote.creation.compose.layout.RemoteBox",
      "import androidx.compose.remote.creation.compose.modifier.RemoteModifier",
      "import androidx.compose.remote.creation.compose.modifier.fillMaxSize",
    )

  @Test
  fun `an empty widget's native preview imports the box it draws`() {
    WearWidgetScaffoldSize.entries.forEach { size ->
      val empty =
        wearWidgetUiBuilderDocument(
          "empty",
          JsonObject(emptyMap()),
          JsonObject(emptyMap()),
          size,
        )
      val source =
        assertIs<WearWidgetNativePreviewExporter.Result.Emitted>(
            WearWidgetNativePreviewExporter.export(empty, "preview")
          )
          .source
      assertTrue("RemoteBox(modifier = RemoteModifier.fillMaxSize())" in source, source)
      required.forEach { assertTrue(it in source, "$size is missing `$it`:\n$source") }
    }
  }

  @Test
  fun `an empty inline remote body imports the box it draws`() {
    val inline =
      UiBuilderNode(
        id = "inline",
        componentId = REMOTE_COMPOSE_INLINE_COMPONENT_ID,
        slots = mapOf("content" to emptyList()),
      )
    val document =
      wearWidgetUiBuilderDocument(
          "empty",
          JsonObject(emptyMap()),
          JsonObject(emptyMap()),
          WearWidgetScaffoldSize.Small,
        )
        .let { it.copy(nodes = it.nodes + ("inline" to inline)) }
    val result = InlineRemoteContentExporter.export(document, "inline")
    val source = assertIs<InlineRemoteContentExporter.Result.Emitted>(result, "$result").source
    assertTrue("RemoteBox(modifier = RemoteModifier.fillMaxSize())" in source, source)
    required.forEach { assertTrue(it in source, "missing `$it`:\n$source") }
  }
}
