package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.newDocument
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionCollector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

class CatalogRuntimeDocumentTest {
  private val adaptive =
    AdaptiveWearWidget.newDocument("meeting", JsonObject(emptyMap()), JsonObject(emptyMap()))

  @Test
  fun `an adaptive widget reaches the runtime as the Large container it resolves to`() {
    val sent = adaptive.forCatalogRuntime()
    val root = sent.nodes.getValue(sent.roots.single())

    // The component remote-m3's runtime draws, not the builder template it does not ship — which
    // it drew as an empty frame.
    assertEquals(WearWidgetScaffoldSize.Large.componentId, root.componentId)
    assertTrue(sent.nodes.values.none { it.componentId == AdaptiveWearWidget.COMPONENT_ID })
    // Every node the designer placed is still there under its own id, so a canvas click selects it.
    for (id in adaptive.nodes.keys) assertTrue(id in sent.nodes, "$id dropped")
    assertEquals(adaptive.roots, sent.roots)
  }

  @Test
  fun `any other design is sent as it is`() {
    val small = AdaptiveWearWidget.resolve(adaptive, WearWidgetScaffoldSize.Small)
    assertSame(small, small.forCatalogRuntime())
  }

  @Test
  fun `the runtime's inspection keeps only the nodes and slots the designer placed`() {
    val sent = adaptive.forCatalogRuntime()
    val synthesised = sent.nodes.keys - adaptive.nodes.keys
    assertTrue(synthesised.isNotEmpty(), "resolution adds the layout nodes this test is about")
    val inspected = UiBuilderInspectionCollector(sent).snapshot()

    val authored = inspected.authoredIn(adaptive)

    // A tap on one of these selected a node the editor refuses, so none may reach hit testing.
    assertEquals(adaptive.nodes.keys, authored.nodes.map { it.nodeId }.toSet())
    for (slot in authored.slots) {
      val parent = adaptive.nodes.getValue(slot.parentNodeId)
      assertTrue(slot.slotName in parent.slots, "${slot.parentNodeId}.${slot.slotName}")
    }
    val small = AdaptiveWearWidget.resolve(adaptive, WearWidgetScaffoldSize.Small)
    val plain = UiBuilderInspectionCollector(small).snapshot()
    assertEquals(plain, plain.authoredIn(small), "a design sent as it is loses nothing")
  }
}
