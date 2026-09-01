package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class UiBuilderInspectionTest {
  private val json = Json { encodeDefaults = true }

  @Test
  fun `capture is ordered quantized and includes slot unions text baselines and semantics`() {
    val document = UiBuilderReducer.replay(jetcasterFixture()).document
    val collector = UiBuilderInspectionCollector(document)

    // Deliberately report children and parent out of document/id order.
    collector.recordNodeBounds("chip-news-label", 24.001f, 41.998f, 64.004f, 62.002f)
    collector.recordTextLayout(
      "chip-news-label",
      lineCount = 1,
      firstBaseline = 14.001f,
      lastBaseline = 14.001f,
      contentOffsetY = 6f,
    )
    collector.recordNodeBounds("chip-crime", 10f, 32f, 74f, 72f)
    collector.recordNodeBounds("chip-news", 82f, 32f, 142f, 72f)
    collector.recordNodeBounds("category-row", 0f, 24f, 744f, 80f)

    val snapshot = collector.snapshot()
    val label = snapshot.nodes.single { it.nodeId == "chip-news-label" }
    val items =
      snapshot.slots.single { it.parentNodeId == "category-row" && it.slotName == "items" }

    assertEquals("compose-ui-builder-inspection/v1", snapshot.schema)
    assertEquals("fixture-jetcaster-discover-expanded@108", snapshot.generation.key)
    assertEquals(false, snapshot.generation.completed)
    assertEquals(document.nodes.keys.sorted(), snapshot.generation.expectedAuthoredNodeIds)
    assertEquals(
      document.nodes.values.filter { it.componentId == "m3/text" }.map { it.id }.sorted(),
      snapshot.generation.expectedAuthoredTextNodeIds,
    )
    assertEquals(
      listOf("category-row", "chip-crime", "chip-news", "chip-news-label"),
      snapshot.generation.measuredNodeIds,
    )
    assertEquals(listOf("chip-news-label"), snapshot.generation.measuredTextNodeIds)
    assertEquals(document.nodes.keys.sorted(), snapshot.nodes.map { it.nodeId })
    assertEquals(UiBuilderPixelBounds(24f, 42f, 40f, 20f), label.bounds)
    assertEquals(62f, assertNotNull(label.text).firstBaselineY)
    assertEquals("News", label.semantics.label)
    assertEquals(listOf("chip-crime", "chip-news"), items.measuredChildNodeIds)
    assertEquals(UiBuilderPixelBounds(10f, 32f, 132f, 40f), items.bounds)
    assertTrue(items.childNodeIds.contains("chip-comedy"))
    assertNull(snapshot.nodes.single { it.nodeId == "chip-comedy" }.bounds)
  }

  @Test
  fun `measurement callback order and editor layer do not alter clean geometry contract`() {
    val document = UiBuilderReducer.replay(jetcasterFixture()).document
    val forward = UiBuilderInspectionCollector(document)
    val reverse = UiBuilderInspectionCollector(document)
    val measurements =
      listOf(
        Measurement("root-surface", 0f, 0f, 1280f, 800f),
        Measurement("pane-scaffold", 0f, 0f, 1280f, 800f),
        Measurement("main-background", 0f, 0f, 744f, 800f),
        Measurement("detail-scaffold", 768f, 0f, 1280f, 800f),
      )
    measurements.forEach { forward.record(it) }
    measurements.reversed().forEach { reverse.record(it) }

    assertEquals(
      json.encodeToString(forward.snapshot()),
      json.encodeToString(reverse.snapshot()),
    )
    assertEquals(
      listOf(UiBuilderLayer.Design),
      uiBuilderLayers(editorOverlay = false),
    )
    assertEquals(
      forward.snapshot(),
      reverse.snapshot(),
      "the editor overlay is deliberately absent from the design inspection product",
    )
  }

  @Test
  fun `state backed selection and authored actions remain inspectable`() {
    val document = UiBuilderReducer.replay(jetcasterFixture()).document
    val collector = UiBuilderInspectionCollector(document)
    val selected = collector.snapshot().nodes.single { it.nodeId == "chip-crime" }.semantics
    val unselected = collector.snapshot().nodes.single { it.nodeId == "chip-news" }.semantics

    assertEquals(true, selected.selected)
    assertEquals(false, unselected.selected)
    assertEquals(true, selected.enabled)
    assertEquals(listOf("click"), selected.actions)
  }

  private fun jetcasterFixture() =
    json
      .parseToJsonElement(
        checkNotNull(javaClass.getResource("/jetcaster-discover-operations-v1.json")).readText()
      )
      .jsonObject

  private data class Measurement(
    val nodeId: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
  )

  private fun UiBuilderInspectionCollector.record(value: Measurement) {
    recordNodeBounds(value.nodeId, value.left, value.top, value.right, value.bottom)
  }
}
