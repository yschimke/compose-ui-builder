package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapterMappings
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogComponentIds
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderNativeOnly
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.CatalogThumbnail
import ee.schimke.composeai.uibuilder.editor.CatalogThumbnailOutcome
import ee.schimke.composeai.uibuilder.editor.EditorCatalogVariant
import ee.schimke.composeai.uibuilder.editor.EditorComponentKind
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every palette thumbnail of every catalog this build ships, drawn the way the component browser
 * draws it — the same composable, under the same catalog locals the editor provides — and checked
 * for the ways a thumbnail has gone wrong before:
 * - a component whose insert is refused has no picture, and cannot be added either;
 * - a component that animates keeps the panel drawing every frame for as long as it is on screen;
 * - a component that opens a window (a real `Dialog`) escapes its tile and covers the editor;
 * - a picker or dialog squeezed into the default frame is cropped rather than shown as a miniature.
 *
 * Writes one contact sheet per catalog to `build/catalog-thumbnails/`, which is the thing to look
 * at when changing how thumbnails draw: whether a picture reads as its component is a judgement no
 * assertion here makes.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalLayoutApi::class)
class CatalogThumbnailRenderTest {
  @Test
  fun `m3-catalog thumbnails all draw`() =
    assertSheet(
      "m3-catalog",
      miniatures = setOf("m3/date-picker", "m3/time-picker", "m3/dialog"),
      needsContent = setOf("remote-compose/document", "asset/image"),
    )

  @Test
  fun `wear-m3 thumbnails all draw`() =
    assertSheet(
      "wear-m3",
      // The screen is the root of a Wear design; the thumbnail frame is a box it cannot sit in.
      rootOnly = setOf("wear-m3/screen-scaffold"),
      miniatures = setOf("wear-m3/date-picker", "wear-m3/time-picker"),
      needsContent = setOf("asset/image"),
    )

  @Test
  fun `remote-m3 thumbnails all draw`() =
    assertSheet(
      "remote-m3",
      // A widget container is the widget: the root of a Remote design, never a child of a box.
      rootOnly =
        setOf(
          "remote-m3/widget-container-small",
          "remote-m3/widget-container-large",
          "remote-m3/widget-container-adaptive",
        ),
      needsContent = setOf("remote-compose/document", "asset/image", "remote-m3/lottie"),
    )

  @Test
  fun `a2ui-catalog tiles show no picture, and nothing escapes or animates`() =
    assertSheet(
      "a2ui-catalog",
      // None, by construction for now: the thumbnail frame is a `layout/box`, which A2UI's catalog
      // does not have, so no insert into it validates — and the canvas draws every A2UI component
      // as a named placeholder anyway, which a tile's name already says. When A2UI components draw
      // as themselves, this list is the thing that should shrink.
      rootOnly =
        setOf(
          "a2ui/Text",
          "a2ui/Image",
          "a2ui/Icon",
          "a2ui/Video",
          "a2ui/AudioPlayer",
          "a2ui/Row",
          "a2ui/Column",
          "a2ui/List",
          "a2ui/Card",
          "a2ui/Tabs",
          "a2ui/Modal",
          "a2ui/Divider",
          "a2ui/Button",
          "a2ui/TextField",
          "a2ui/CheckBox",
          "a2ui/ChoicePicker",
          "a2ui/Slider",
          "a2ui/DateTimeInput",
        ),
    )

  private fun assertSheet(
    systemId: String,
    /**
     * Components with no thumbnail document: root-only ones, or a catalog the frame cannot hold.
     */
    rootOnly: Set<String> = emptySet(),
    miniatures: Set<String> = emptySet(),
    /** Components that are nothing until given a document, an asset or an animation. */
    needsContent: Set<String> = emptySet(),
  ) {
    val catalog =
      CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/$systemId-capabilities-v1.json")).readText()
      )
    val reducer = UiBuilderEditorReducer(catalog)
    val tiles =
      reducer.catalogItems("").flatMap { item ->
        (listOf(null) + item.variants).map { variant ->
          Tile(item.componentId, item.displayName, variant, item.kind)
        }
      }
    val outcomes = mutableMapOf<Tile, CatalogThumbnailOutcome>()
    // Landscape, like the laptop window the editor usually runs in: a component that reads the host
    // window rather than its frame (Material's TimePicker did) shows up here.
    runDesktopComposeUiTest(width = SHEET_WIDTH, height = 1200) {
      // The clock is driven by hand, so an animating thumbnail shows up as a changed picture
      // rather than as a test that never goes idle.
      mainClock.autoAdvance = false
      setContent { Sheet(catalog, reducer, tiles) { tile, outcome -> outcomes[tile] = outcome } }
      repeat(10) { mainClock.advanceTimeByFrame() }
      mainClock.advanceTimeBy(2_000)

      // One root: a thumbnail that opened a window of its own would add another.
      assertEquals(
        1,
        onAllNodes(isRoot()).fetchSemanticsNodes().size,
        "a thumbnail opened a window",
      )

      val before = tiles.indices.map { onNodeWithTag("tile-$it").captureToImage().toAwtImage() }
      mainClock.advanceTimeBy(1_000)
      val moving =
        tiles.indices
          .filter { index ->
            val after = onNodeWithTag("tile-$index").captureToImage().toAwtImage()
            !samePixels(before[index], after)
          }
          .map { tiles[it].name }
      val out = File("build/catalog-thumbnails").apply { mkdirs() }
      ImageIO.write(
        onNodeWithTag("sheet").captureToImage().toAwtImage(),
        "png",
        File(out, "$systemId.png"),
      )

      assertEquals(emptyList(), moving, "thumbnails still animating after 2s")
    }

    val noDocument =
      tiles.filter { outcomes[it] == CatalogThumbnailOutcome.NoDocument }.map { it.componentId }
    assertEquals(rootOnly, noDocument.toSet(), "components with no thumbnail document")
    val missing =
      tiles.filter { outcomes[it] == CatalogThumbnailOutcome.NeedsContent }.map { it.componentId }
    assertEquals(needsContent, missing.toSet(), "thumbnails drawing a fill-me-in stand-in")
    val miniatureIds =
      tiles.filter { outcomes[it] == CatalogThumbnailOutcome.Miniature }.map { it.componentId }
    val squeezed = miniatures - miniatureIds.toSet()
    assertEquals(emptySet(), squeezed, "pickers and dialogs not drawn as miniatures")
  }

  @androidx.compose.runtime.Composable
  private fun Sheet(
    catalog: CapabilityCatalog,
    reducer: UiBuilderEditorReducer,
    tiles: List<Tile>,
    onOutcome: (Tile, CatalogThumbnailOutcome) -> Unit,
  ) {
    // What `UiBuilderEditor` provides around the palette. Without these a Remote catalog's
    // components all draw as "Unsupported component" and a Wear one is laid out for no watch.
    CompositionLocalProvider(
      LocalUiBuilderNativeOnly provides catalog.nativeOnlyComponentIds,
      LocalUiBuilderCatalogComponentIds provides catalog.componentsById.keys,
      LocalUiBuilderCanvasAdapters provides catalog.canvasAdapterIds,
      LocalUiBuilderCanvasAdapterMappings provides catalog.canvasAdapterMappings,
      LocalUiBuilderFrameGeometry provides catalog.frameGeometry,
      LocalUiBuilderCatalogPlatform provides catalog.platform.wireValue,
    ) {
      MaterialTheme(colorScheme = darkColorScheme()) {
        FlowRow(
          Modifier.testTag("sheet")
            .width(SHEET_WIDTH.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
        ) {
          tiles.forEachIndexed { index, tile ->
            Column(Modifier.width(120.dp).padding(4.dp)) {
              Column(Modifier.testTag("tile-$index")) {
                CatalogThumbnail(
                  document = reducer.previewDocument(tile.componentId, tile.variant),
                  componentId = tile.componentId,
                  label = tile.name,
                  size = DpSize(104.dp, 72.dp),
                  container = tile.kind == EditorComponentKind.Container,
                  onOutcome = { onOutcome(tile, it) },
                )
              }
              Text(tile.name, color = Color.White, fontSize = 9.sp)
            }
          }
        }
      }
    }
  }

  private data class Tile(
    val componentId: String,
    val displayName: String,
    val variant: EditorCatalogVariant?,
    val kind: EditorComponentKind,
  ) {
    val name: String
      get() = componentId + (variant?.let { " · ${it.value}" } ?: "")
  }

  private fun samePixels(a: java.awt.image.BufferedImage, b: java.awt.image.BufferedImage) =
    a.width == b.width &&
      a.height == b.height &&
      a.getRGB(0, 0, a.width, a.height, null, 0, a.width)
        .contentEquals(b.getRGB(0, 0, b.width, b.height, null, 0, b.width))

  private companion object {
    const val SHEET_WIDTH = 1216
  }
}
