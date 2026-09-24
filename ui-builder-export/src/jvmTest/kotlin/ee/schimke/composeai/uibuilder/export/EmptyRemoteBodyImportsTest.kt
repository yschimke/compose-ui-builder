package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * An empty body is still a `RemoteBox`, and a file that writes one has to import it.
 *
 * The native lane and the inline lane each wrote the empty box as a literal string rather than
 * through `RemoteContentEmitter.emptyBox`, so the emitter never learned it had used `RemoteBox` or
 * `RemoteModifier`. A freshly created widget — a container with nothing in it yet — then failed to
 * compile for its Native preview with `Unresolved reference 'RemoteBox'`, on the deployed server.
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
