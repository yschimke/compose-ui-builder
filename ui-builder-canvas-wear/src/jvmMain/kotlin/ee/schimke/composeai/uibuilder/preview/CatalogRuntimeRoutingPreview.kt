package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasRenderer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.helloWidgetUiBuilderDocument

/**
 * Which surfaces a catalog's pinned runtime draws, on a `remote-m3` widget with the preview panes
 * and the component list open.
 *
 * A catalog that publishes a runtime (`remote-m3` in the browser and VS Code) hands the editor a
 * [UiBuilderCanvasRenderer]; everything else draws in-process. The split is a decision about each
 * surface — the editing canvas, every device preview pane, every palette tile, the drag ghost — and
 * getting it wrong is invisible in every other preview, because none of them passes a renderer.
 * `#274` moved the device panes in-process to stop a runtime booting per palette tile, and the
 * panes then drew `remote-m3` components the in-process canvas cannot draw as themselves.
 *
 * The runtime here is a stand-in: the real one is a sandboxed Wasm iframe a preview cannot boot. It
 * draws the same in-process surface, framed in magenta and stamped with the surface mode it was
 * asked for, so the render answers the question directly: a pane stamped `DEVICE` went through the
 * runtime, an unstamped one did not. The editing canvas must be stamped; palette tiles must not.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun CatalogRuntimeRoutingPreview() {
  UiBuilderEditor(
    document =
      helloWidgetUiBuilderDocument(
        designId = "runtime-routing",
        catalogPin = wearWidgetSampleCatalogPin,
        environment = wearWidgetSampleEnvironment,
      ),
    catalog = remoteM3PreviewCatalog,
    initialSelectedNodeId = "hello-text",
    initialComponentsOpen = true,
    initialPanes = setOf(EditorPane.Editor, EditorPane.Preview),
    canvasRenderer = STAND_IN_CATALOG_RUNTIME,
  )
}

/** The editing canvas, the preview panes and the tiles marked by what drew them. */
private val STAND_IN_CATALOG_RUNTIME: UiBuilderCanvasRenderer =
  { document, surface, selectedNodeId, _, _, _ ->
    // The surface is drawn at the design's own size and scaled by the canvas, so the mark is sized
    // for a 216 dp widget: a border that survives any zoom, and a one-line tag in the corner.
    Box(Modifier.fillMaxSize().border(1.dp, RUNTIME_MARK)) {
      UiBuilderSurface(document = document, editorOverlay = false, selectedNodeId = selectedNodeId)
      Text(
        "runtime · ${surface.mode.name.substringBefore('_')}",
        color = Color.White,
        fontSize = 5.sp,
        lineHeight = 6.sp,
        maxLines = 1,
        softWrap = false,
        modifier =
          Modifier.align(Alignment.BottomEnd).background(RUNTIME_MARK).padding(horizontal = 2.dp),
      )
    }
  }

private val RUNTIME_MARK = Color(0xffd6008f)

/** The published `remote-m3` capability catalog, the one a widget design is pinned to. */
private val remoteM3PreviewCatalog by lazy {
  CapabilityCatalogParser.parse(
    checkNotNull(WearWidgetScaffoldSize::class.java.getResource("/remote-m3-capabilities-v1.json"))
      .readText()
  )
}
