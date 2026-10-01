package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `layout/box`'s own `contentAlignment` reaches the exported `RemoteBox`.
 *
 * The emitter used to read only the children's own alignment, so a box that centred an unaligned
 * child — a 40dp badge with an arrow in it — exported without `contentAlignment`, and the native
 * render drew the arrow top-left while the browser preview centred it (wear-m3-catalog#594).
 */
class RemoteBoxContentAlignmentTest {
  /** A document property as the builder stores one: `{"type": …, "value": …}`. */
  private fun typed(type: String, value: String) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private fun box(contentAlignment: String?, vararg childAlignments: String?): String? {
    val children = childAlignments.mapIndexed { index, alignment ->
      UiBuilderNode(
        id = "child$index",
        componentId = REMOTE_TEXT_COMPONENT_ID,
        properties =
          JsonObject(
            buildMap {
              put("text", typed("string", "→"))
              alignment?.let { put("alignment", typed("enum", it)) }
            }
          ),
      )
    }
    val box =
      UiBuilderNode(
        id = "badge",
        componentId = "layout/box",
        properties =
          JsonObject(
            contentAlignment?.let { mapOf("contentAlignment" to typed("enum", it)) } ?: emptyMap()
          ),
        slots = mapOf("children" to children.map { it.id }),
      )
    val inline =
      UiBuilderNode(
        id = "inline",
        componentId = REMOTE_COMPOSE_INLINE_COMPONENT_ID,
        slots = mapOf("content" to listOf("badge")),
      )
    val document =
      wearWidgetUiBuilderDocument(
          "badge",
          JsonObject(emptyMap()),
          JsonObject(emptyMap()),
          WearWidgetScaffoldSize.Small,
        )
        .let {
          it.copy(nodes = it.nodes + (children + box + inline).associateBy { node -> node.id })
        }
    return when (val result = InlineRemoteContentExporter.export(document, "inline")) {
      is InlineRemoteContentExporter.Result.Emitted -> result.source
      else -> null
    }
  }

  private fun emitted(contentAlignment: String?, vararg childAlignments: String?): String =
    assertIs<String>(box(contentAlignment, *childAlignments), "expected an export")

  @Test
  fun `a box's own contentAlignment aligns an unaligned child`() {
    val source = emitted("center", null)
    assertTrue("contentAlignment = RemoteAlignment.Center" in source, source)
  }

  @Test
  fun `a child's own alignment wins over the box's, as in Compose`() {
    val source = emitted("center", "topEnd")
    assertTrue("contentAlignment = RemoteAlignment.TopEnd" in source, source)
  }

  @Test
  fun `children resolving to one corner export as one contentAlignment`() {
    val source = emitted("center", null, "center")
    assertTrue("contentAlignment = RemoteAlignment.Center" in source, source)
  }

  @Test
  fun `an explicit topStart beside an unaligned child is one corner, not two`() {
    val source = emitted(null, "topStart", null)
    assertTrue("contentAlignment = RemoteAlignment.TopStart" in source, source)
  }

  @Test
  fun `children resolving to different corners still refuse`() {
    assertTrue(box("center", null, "topEnd") == null, "a box RemoteBox cannot align as a group")
  }

  @Test
  fun `a box nobody aligns writes no contentAlignment`() {
    val source = emitted(null, null)
    assertFalse("contentAlignment" in source, source)
  }
}
