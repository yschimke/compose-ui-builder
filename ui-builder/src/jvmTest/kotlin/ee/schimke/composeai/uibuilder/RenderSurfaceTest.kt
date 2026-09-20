package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalComposeUiApi::class)
class RenderSurfaceTest {
  @Test
  fun `protocol surface density controls authored dp measurement`() {
    val node =
      UiBuilderNode(
        id = "root",
        componentId = "layout/box",
        modifiers =
          JsonArray(
            listOf(
              JsonObject(
                mapOf(
                  "type" to JsonPrimitive("size"),
                  "widthDp" to JsonPrimitive(10),
                  "heightDp" to JsonPrimitive(12),
                )
              )
            )
          ),
      )
    val document =
      UiBuilderDocument(
        schema = "compose-ui-builder-document/v1",
        id = "surface-density",
        title = "Surface density",
        revision = 1,
        catalogPin = JsonObject(emptyMap()),
        environment = JsonObject(emptyMap()),
        stateVariables = JsonObject(emptyMap()),
        roots = listOf(node.id),
        nodes = mapOf(node.id to node),
      )
    var snapshot: UiBuilderInspectionSnapshot? = null

    renderComposeScene(100, 100, Density(1f)) {
      UiBuilderSurface(
        document = document,
        renderSurface =
          UiBuilderRendererSurfaceV2(
            mode = UiBuilderRendererSurfaceModeV2.DEVICE,
            widthDp = 50f,
            heightDp = 50f,
            density = 2f,
            surfaceId = "wear-small",
          ),
        onInspectionSnapshot = { snapshot = it },
      )
    }

    val bounds = checkNotNull(snapshot).nodes.single().bounds
    assertEquals(20f, bounds?.width)
    assertEquals(24f, bounds?.height)
  }
}
