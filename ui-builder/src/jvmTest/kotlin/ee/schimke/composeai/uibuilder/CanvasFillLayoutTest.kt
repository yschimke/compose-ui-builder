package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class CanvasFillLayoutTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `the saved export sample fills the frame inside its authored padding`() {
    assertSelectedChildSize(1f)
  }

  @Test
  fun `fill sizing uses the design density rather than the browser density`() {
    assertSelectedChildSize(2.625f)
  }

  @Test
  fun `preview clicks unroll a long list and restore the fill branch without editing the document`() =
    runDesktopComposeUiTest(width = 1200, height = 1200) {
      org.junit.Assume.assumeTrue(
        "Enable with -PuiBuilderRemoteCompose=true",
        UiBuilderBuildFeatures.remoteCompose,
      )
      val saved = sample(1f)
      fun setPage(value: Int) =
        Json.parseToJsonElement("""{"click":[{"type":"set","variable":"page","value":$value}]}""")
          .jsonObject
      val items =
        (1..20).associate { index ->
          val id = "item-$index"
          id to
            UiBuilderNode(
              id,
              "layout/box",
              modifiers =
                Json.parseToJsonElement(
                    """[{"type":"height","heightDp":40},{"type":"fillMaxWidth"}]"""
                  )
                  .jsonArray,
            )
        }
      val document =
        saved.copy(
          nodes =
            saved.nodes +
              items +
              mapOf(
                "First" to saved.nodes.getValue("First").copy(eventBindings = setPage(20)),
                "Second" to
                  saved.nodes
                    .getValue("Second")
                    .copy(
                      componentId = "layout/lazy-column",
                      modifiers =
                        Json.parseToJsonElement("""[{"type":"fillMaxWidth"}]""").jsonArray,
                      slots = mapOf("items" to items.keys.toList()),
                      eventBindings = setPage(10),
                    ),
              )
        )
      var inspection: UiBuilderInspectionSnapshot? = null
      var canvasHeight = 0
      setContent { Canvas(document, { inspection = it }, { canvasHeight = it }, editing = false) }
      waitForIdle()
      assertChildSize(inspection)
      onRoot().performTouchInput { click(Offset(100f, 100f)) }
      waitForIdle()
      assertTrue(canvasHeight == 848, "clicked list extent: $canvasHeight")
      val last = checkNotNull(inspection?.nodes?.firstOrNull { it.nodeId == "item-20" }?.bounds)
      assertTrue(abs(last.height - 40f) <= 1f, "last item height: ${last.height}")
      onRoot().performTouchInput { click(Offset(100f, 100f)) }
      waitForIdle()
      assertTrue(canvasHeight == 360, "clicked fill extent: $canvasHeight")
      assertChildSize(inspection)
      assertTrue(inspection?.documentRevision == 0)
    }

  @Test
  fun `selecting a shorter branch shrinks the extent back to the frame`() =
    runDesktopComposeUiTest(width = 1200, height = 1200) {
      org.junit.Assume.assumeTrue(
        "Enable with -PuiBuilderRemoteCompose=true",
        UiBuilderBuildFeatures.remoteCompose,
      )
      val saved = sample(1f)
      val tallBranch =
        saved.nodes
          .getValue("Second")
          .copy(
            modifiers =
              Json.parseToJsonElement(
                  """[{"type":"fillMaxWidth"},{"type":"height","heightDp":800}]"""
                )
                .jsonArray
          )
      var document by
        mutableStateOf(
          saved.copy(
            nodes = saved.nodes + ("Second" to tallBranch),
            stateVariables =
              JsonObject(
                mapOf(
                  "page" to
                    JsonObject(
                      saved.stateVariables.getValue("page").jsonObject +
                        ("initialValue" to JsonPrimitive(20))
                    )
                )
              ),
          )
        )
      var inspection: UiBuilderInspectionSnapshot? = null
      var canvasHeight = 0
      setContent { Canvas(document, { inspection = it }, { canvasHeight = it }) }
      waitForIdle()
      assertTrue(canvasHeight == 848, "long branch extent: $canvasHeight")
      val tall = checkNotNull(inspection?.nodes?.firstOrNull { it.nodeId == "Second" }?.bounds)
      assertTrue(abs(tall.height - 800f) <= 1f, "long branch height: ${tall.height}")

      runOnIdle { document = document.copy(revision = 1, stateVariables = saved.stateVariables) }
      waitForIdle()
      assertTrue(canvasHeight == 360, "short branch extent: $canvasHeight")
      assertChildSize(inspection)
    }

  private fun sample(designDensity: Float): UiBuilderDocument {
    val sample =
      File(
        System.getProperty("uiBuilderProjectDir"),
        "../docs/design/evidence/ui-builder-document-exports/sample.document.json",
      )
    val saved = json.decodeFromString<UiBuilderDocument>(sample.readText())
    return saved.copy(
      environment = JsonObject(saved.environment + ("density" to JsonPrimitive(designDensity)))
    )
  }

  private fun assertSelectedChildSize(designDensity: Float) {
    var inspection: UiBuilderInspectionSnapshot? = null
    renderComposeScene(1200, 1200, Density(1f)) {
      Canvas(sample(designDensity), { inspection = it })
    }
    assertChildSize(inspection)
  }

  private fun assertChildSize(inspection: UiBuilderInspectionSnapshot?) {
    val bounds = checkNotNull(inspection?.nodes?.firstOrNull { it.nodeId == "First" }?.bounds)
    assertTrue(abs(bounds.width - 312f) <= 1f, "selected child width: ${bounds.width}")
    assertTrue(abs(bounds.height - 312f) <= 1f, "selected child height: ${bounds.height}")
  }

  @Composable
  private fun Canvas(
    document: UiBuilderDocument,
    onInspection: (UiBuilderInspectionSnapshot) -> Unit,
    onHeight: (Int) -> Unit = {},
    editing: Boolean = true,
  ) {
    PinnedDesignCanvas(
      document = document,
      selectedNodeId = "choice",
      onNodeSelected = {},
      onCanvasMetrics = { _, height, _ -> onHeight(height) },
      onCanvasBounds = {},
      dropHovered = false,
      showSelectionOverlay = editing,
      reference = ReferenceOverlayState(),
      onMarkDrawn = { _, _ -> },
      onPieceMoved = { _, _, _ -> },
      collaborators = emptyList(),
      commentThreads = emptyList(),
      selectedThreadId = null,
      onCommentThreadSelected = {},
      onInspectionSnapshot = onInspection,
      onInspectionInvalidated = null,
      selectionMenu = {},
      hoverEditor = null,
      zoom = 1f,
      onZoomChanged = {},
      modifier = Modifier.fillMaxSize(),
    )
  }
}
