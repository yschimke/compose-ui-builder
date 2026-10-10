package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
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
}
