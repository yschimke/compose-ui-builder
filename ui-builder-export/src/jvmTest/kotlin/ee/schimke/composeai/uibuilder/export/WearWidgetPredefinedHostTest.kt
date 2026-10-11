package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A widget's frame is one of the predefined host shapes and nothing else: legacy
 * `horizontalPaddingDp`, `verticalPaddingDp` and `cornerRadiusDp` on the container are ignored by
 * every lane, and the export does not refuse them.
 */
class WearWidgetPredefinedHostTest {
  private fun float(value: Float) =
    JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(value)))

  private fun overridden(size: WearWidgetScaffoldSize): UiBuilderDocument {
    val document =
      wearWidgetUiBuilderDocument("legacy", JsonObject(emptyMap()), JsonObject(emptyMap()), size)
    val rootId = document.roots.single()
    val root = document.nodes.getValue(rootId)
    val properties =
      root.properties +
        mapOf(
          "horizontalPaddingDp" to float(20f),
          "verticalPaddingDp" to float(14f),
          "cornerRadiusDp" to float(999f),
        )
    return document.copy(
      nodes = document.nodes + (rootId to root.copy(properties = JsonObject(properties)))
    )
  }

  @Test
  fun `the native preview frames a legacy override in the host shape's own spec`() {
    WearWidgetScaffoldSize.entries.forEach { size ->
      WearWidgetHostShape.entries.forEach { shape ->
        val spec = size.hostSpec(shape)
        val emitted =
          assertIs<WearWidgetNativePreviewExporter.Result.Emitted>(
            WearWidgetNativePreviewExporter.export(overridden(size), "preview", shape = shape)
          )
        assertEquals(spec.frameWidthDp, emitted.widthDp, "$size $shape width")
        assertEquals(spec.frameHeightDp, emitted.heightDp, "$size $shape height")
        assertTrue("horizontalPaddingDp = 20f" !in emitted.source, emitted.source)
        assertTrue("cornerRadiusDp = 999f" !in emitted.source || spec.cornerRadiusDp == 999f)
      }
    }
  }

  @Test
  fun `the export ignores a legacy override instead of refusing it`() {
    WearWidgetScaffoldSize.entries.forEach { size ->
      val result = WearWidgetCodeExporter.export(overridden(size), "widget")
      assertIs<WearWidgetCodeExporter.Result.Emitted>(result, "$size: $result")
    }
  }
}
