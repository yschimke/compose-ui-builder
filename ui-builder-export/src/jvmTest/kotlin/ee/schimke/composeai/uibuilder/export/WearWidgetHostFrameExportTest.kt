package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The container's `hostFrame` picks the document a widget returns: upstream's `WearWidgetDocument`
 * by default, which bakes the host frame, or `HostFramedWearWidgetDocument`, which leaves it to the
 * device.
 */
class WearWidgetHostFrameExportTest {
  private fun string(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))

  private fun design(size: WearWidgetScaffoldSize, hostFrame: String?): UiBuilderDocument {
    val document =
      wearWidgetUiBuilderDocument("framed", JsonObject(emptyMap()), JsonObject(emptyMap()), size)
    if (hostFrame == null) return document
    val rootId = document.roots.single()
    val root = document.nodes.getValue(rootId)
    val properties = root.properties + (WearWidgetHostFrame.PROPERTY to string(hostFrame))
    return document.copy(
      nodes = document.nodes + (rootId to root.copy(properties = JsonObject(properties)))
    )
  }

  private fun source(size: WearWidgetScaffoldSize, hostFrame: String?): String =
    assertIs<WearWidgetCodeExporter.Result.Emitted>(
        WearWidgetCodeExporter.export(design(size, hostFrame), "widget")
      )
      .source

  @Test
  fun `unset or host returns upstream's document, as before`() {
    WearWidgetScaffoldSize.entries.forEach { size ->
      val unset = source(size, null)
      assertEquals(unset, source(size, "host"), "$size")
      assertTrue("import androidx.glance.wear.WearWidgetDocument\n" in unset, unset)
      assertTrue("return WearWidgetDocument(background = " in unset, unset)
      assertTrue("HostFramedWearWidgetDocument" !in unset, unset)
    }
  }

  @Test
  fun `device returns the host-framed document instead`() {
    WearWidgetScaffoldSize.entries.forEach { size ->
      val device = source(size, "device")
      assertTrue(
        "import ee.schimke.composeai.wear.preview.HostFramedWearWidgetDocument\n" in device,
        device,
      )
      assertTrue("return HostFramedWearWidgetDocument(background = " in device, device)
      assertTrue("import androidx.glance.wear.WearWidgetDocument\n" !in device, device)
      // Everything else is the widget the default export writes (imports stay sorted, so the
      // document's import line is compared by its absence).
      assertEquals(
        source(size, null)
          .replace("import androidx.glance.wear.WearWidgetDocument\n", "")
          .replace("return WearWidgetDocument(", "return HostFramedWearWidgetDocument("),
        device.replace(
          "import ee.schimke.composeai.wear.preview.HostFramedWearWidgetDocument\n",
          "",
        ),
        "$size",
      )
    }
  }

  @Test
  fun `a device-framed bundle says what it depends on`() {
    val bundle =
      assertIs<WearWidgetCodeExporter.BundleResult.Emitted>(
          WearWidgetCodeExporter.exportBundle(
            design(WearWidgetScaffoldSize.Large, "device"),
            "widget",
            WidgetAssetContents { null },
          )
        )
        .bundle
    assertTrue(WearWidgetHostFrame.DEVICE_ARTIFACT in bundle.readme, bundle.readme)
    val host =
      assertIs<WearWidgetCodeExporter.BundleResult.Emitted>(
          WearWidgetCodeExporter.exportBundle(
            design(WearWidgetScaffoldSize.Large, null),
            "widget",
            WidgetAssetContents { null },
          )
        )
        .bundle
    assertTrue(WearWidgetHostFrame.DEVICE_ARTIFACT !in host.readme, host.readme)
  }

  @Test
  fun `an unknown host frame is refused by name`() {
    val result =
      WearWidgetCodeExporter.export(design(WearWidgetScaffoldSize.Small, "squircle"), "widget")
    val refused = assertIs<WearWidgetCodeExporter.Result.Refused>(result)
    assertTrue(refused.reasons.single().contains("`squircle`"), refused.reasons.toString())
  }
}
