package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

class AdaptiveWearWidgetTest {
  private val adaptive =
    AdaptiveWearWidget.newDocument("meeting", JsonObject(emptyMap()), JsonObject(emptyMap()))

  @Test
  fun `Large keeps every slot in a column`() {
    val large = AdaptiveWearWidget.resolve(adaptive, WearWidgetScaffoldSize.Large)
    val root = large.nodes.getValue(large.roots.single())

    assertEquals(WearWidgetScaffoldSize.Large.componentId, root.componentId)
    val layout = large.nodes.getValue(root.slots.getValue("content").single())
    assertEquals("layout/column", layout.componentId)
    val (text, action) = layout.slots.getValue("children")
    assertEquals("adaptive-action", action)
    assertEquals(
      listOf("adaptive-headline", "adaptive-supporting"),
      large.nodes.getValue(text).slots["children"],
    )
  }

  @Test
  fun `Small keeps the headline and action in a row and drops the supporting subtree`() {
    val small = AdaptiveWearWidget.resolve(adaptive, WearWidgetScaffoldSize.Small)
    val root = small.nodes.getValue(small.roots.single())

    assertEquals(WearWidgetScaffoldSize.Small.componentId, root.componentId)
    val layout = small.nodes.getValue(root.slots.getValue("content").single())
    assertEquals("layout/row", layout.componentId)
    val (text, action) = layout.slots.getValue("children")
    assertEquals("adaptive-action", action)
    assertEquals(listOf("adaptive-headline"), small.nodes.getValue(text).slots["children"])
    assertFalse("adaptive-supporting" in small.nodes, "a hidden slot's node is still in the design")
    assertTrue("adaptive-action-label" in small.nodes, "a visible slot lost its subtree")
  }

  @Test
  fun `the container keeps its id and its own properties through resolution`() {
    WearWidgetScaffoldSize.entries.forEach { size ->
      val resolved = AdaptiveWearWidget.resolve(adaptive, size)
      val root = resolved.nodes.getValue("wear-widget-adaptive")
      assertEquals(adaptive.nodes.getValue("wear-widget-adaptive").properties, root.properties)
    }
  }

  @Test
  fun `a synthesized node never takes an id the design already uses`() {
    // Headline and supporting nodes named exactly what resolution would otherwise synthesize.
    val taken = listOf("wear-widget-adaptive-large", "wear-widget-adaptive-large-text")
    val renamed =
      adaptive.copy(
        nodes =
          adaptive.nodes.mapValues { (id, node) ->
            when (id) {
              "wear-widget-adaptive" ->
                node.copy(
                  slots =
                    node.slots +
                      mapOf(
                        AdaptiveWearWidget.HEADLINE to listOf(taken[0]),
                        AdaptiveWearWidget.SUPPORTING to listOf(taken[1]),
                      )
                )
              else -> node
            }
          } - "adaptive-headline" - "adaptive-supporting" +
            (taken[0] to adaptive.nodes.getValue("adaptive-headline").copy(id = taken[0])) +
            (taken[1] to adaptive.nodes.getValue("adaptive-supporting").copy(id = taken[1]))
      )

    val large = AdaptiveWearWidget.resolve(renamed, WearWidgetScaffoldSize.Large)

    taken.forEach { assertEquals("m3/text", large.nodes.getValue(it).componentId, it) }
    val layout =
      large.nodes.getValue(
        large.nodes.getValue("wear-widget-adaptive").slots.getValue("content").single()
      )
    val text = large.nodes.getValue(layout.slots.getValue("children").first())
    assertEquals("layout/column", text.componentId)
    assertEquals(taken, text.slots["children"])
    assertTrue(layout.id !in taken && text.id !in taken, "${layout.id}, ${text.id}")
  }

  @Test
  fun `a fixed-container design resolves to itself`() {
    val small =
      wearWidgetUiBuilderDocument(
        "hello",
        JsonObject(emptyMap()),
        JsonObject(emptyMap()),
        WearWidgetScaffoldSize.Small,
      )
    assertEquals(small, AdaptiveWearWidget.resolve(small, WearWidgetScaffoldSize.Large))
  }

  @Test
  fun `the export branches on the container the host passes and previews both sizes`() {
    val source = assertIs<WearWidgetCodeExporter.Result.Emitted>(export()).source

    assertTrue("fun MeetingWidgetContent(large: Boolean) {" in source, source)
    assertTrue("if (large) {" in source, source)
    assertTrue(
      "MeetingWidgetContent(large = params.containerType == ContainerInfo.CONTAINER_TYPE_LARGE)" in
        source,
      source,
    )
    assertTrue("import androidx.glance.wear.core.ContainerInfo" in source, source)
    // The supporting line is written once, in the Large branch only.
    val large = source.substringAfter("if (large) {").substringBefore("} else {")
    val small = source.substringAfter("} else {").substringBefore("class MeetingWidget")
    assertTrue("Room 4" in large, large)
    assertFalse("Room 4" in small, small)
    assertTrue("RemoteColumn(" in large, large)
    assertTrue("RemoteRow(" in small, small)
    // The text takes what the action leaves, by weight, in both branches.
    assertTrue("weight(1.rf)" in large && "weight(1.rf)" in small, source)
    assertTrue("RemoteArrangement.spacedBy(8.rdp)" in large, large)
    assertTrue("RemoteArrangement.spacedBy(8.rdp)" in small, small)
    // Every shape at both sizes, each from its own shipped provider.
    val previews =
      Regex("@Preview\\(name = \"([^\"]+)\"\\)").findAll(source).map { it.groupValues[1] }
    assertEquals(
      WearWidgetHostShape.entries
        .flatMap { shape -> listOf("${shape.label} Large Preview", "${shape.label} Small Preview") }
        .sorted(),
      previews.toList().sorted(),
    )
    WearWidgetHostShape.entries.forEach { shape ->
      WearWidgetScaffoldSize.entries.forEach { size ->
        assertTrue("${shape.paramsProviderFor(size)}()" in source, source)
      }
    }
  }

  @Test
  fun `the native lane renders the size it is asked for`() {
    WearWidgetScaffoldSize.entries.forEach { size ->
      val rendered =
        assertIs<WearWidgetNativePreviewExporter.Result.Emitted>(
          WearWidgetNativePreviewExporter.export(adaptive, "preview", adaptiveSize = size)
        )
      assertEquals(
        size == WearWidgetScaffoldSize.Large,
        "Room 4" in rendered.source,
        rendered.source,
      )
    }
  }

  private fun export() =
    WearWidgetCodeExporter.export(
      adaptive.copy(title = "Meeting"),
      packageName = "com.example",
      components = RemoteMaterial3.records,
    )
}
