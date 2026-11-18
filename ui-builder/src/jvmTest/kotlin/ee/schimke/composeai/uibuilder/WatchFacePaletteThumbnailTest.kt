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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogComponentIds
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.CatalogThumbnail
import ee.schimke.composeai.uibuilder.editor.CatalogThumbnailOutcome
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The components panel of the `wff-watchface` catalog. Its thumbnails were all the placeholder
 * glyph: the generic frame cannot hold a WFF part, so [UiBuilderEditorReducer.previewDocument] had
 * nothing to draw. Each component now has a small round scene, and each must draw something.
 *
 * Writes the panel as `build/catalog-thumbnails/wff-watchface.png`.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalLayoutApi::class)
class WatchFacePaletteThumbnailTest {
  private val catalog: CapabilityCatalog = wffCatalog()
  private val reducer = UiBuilderEditorReducer(catalog)
  private val ids = catalog.components.map { it.componentId }

  @Test
  fun `the catalog is the watch face one and offers every WFF element`() {
    assertEquals(UiBuilderCatalogPlatform.WATCH_FACE, catalog.platform)
    assertTrue(ids.size >= 29, ids.toString())
  }

  @Test
  fun `every component has a preview document that holds a scene`() {
    val none = ids.filter { reducer.previewDocument(it) == null }
    assertEquals(emptyList(), none, "components with no palette picture")
    for (id in ids) {
      val document = assertNotNull(reducer.previewDocument(id))
      assertEquals(listOf("catalog-thumbnail-cell"), document.roots, id)
      assertEquals("wff/scene", document.nodes.getValue(document.roots.single()).componentId, id)
    }
  }

  @Test
  fun `a component sits in its own picture`() {
    // The picture of a part is that part (or, for a configuration, the real element in `face`).
    for (id in ids) {
      val document = assertNotNull(reducer.previewDocument(id))
      assertTrue(
        document.nodes.values.any { it.componentId == id },
        "$id's own picture does not contain it",
      )
    }
  }

  @Test
  fun `every thumbnail draws something, not the placeholder`() {
    val outcomes = mutableMapOf<String, CatalogThumbnailOutcome>()
    runDesktopComposeUiTest(width = 1100, height = 700) {
      mainClock.autoAdvance = false
      setContent { Panel { id, outcome -> outcomes[id] = outcome } }
      repeat(10) { mainClock.advanceTimeByFrame() }
      mainClock.advanceTimeBy(1_000)

      val panel = onNodeWithTag("panel").captureToImage().toAwtImage()
      File("build/catalog-thumbnails").mkdirs()
      ImageIO.write(panel, "png", File("build/catalog-thumbnails/wff-watchface.png"))

      val blank =
        ids.indices.filter { index ->
          val tile = onNodeWithTag("tile-$index").captureToImage().toAwtImage()
          inkFraction(tile) < MIN_INK
        }
      assertEquals(emptyList(), blank.map { ids[it] }, "thumbnails that render blank")
    }
    for (id in ids) {
      // A scene fills the frame, so the panel may draw it whole as a miniature; either is a
      // picture.
      assertTrue(
        outcomes[id] == CatalogThumbnailOutcome.Drawn ||
          outcomes[id] == CatalogThumbnailOutcome.Miniature,
        "$id drew ${outcomes[id]}, not a picture",
      )
    }
  }

  /** The share of pixels that differ from the tile's background, its most common colour. */
  private fun inkFraction(image: java.awt.image.BufferedImage): Double {
    val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    val background = pixels.groupBy { it }.maxByOrNull { it.value.size }!!.key
    return pixels.count { it != background }.toDouble() / pixels.size
  }

  @androidx.compose.runtime.Composable
  private fun Panel(onOutcome: (String, CatalogThumbnailOutcome) -> Unit) {
    CompositionLocalProvider(
      LocalUiBuilderCatalogComponentIds provides catalog.componentsById.keys,
      LocalUiBuilderCatalogPlatform provides catalog.platform.wireValue,
    ) {
      MaterialTheme(colorScheme = darkColorScheme()) {
        FlowRow(
          Modifier.testTag("panel")
            .width(1100.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        ) {
          ids.forEachIndexed { index, id ->
            val component = catalog.componentsById.getValue(id)
            Column(Modifier.width(120.dp).padding(4.dp)) {
              Column(Modifier.testTag("tile-$index")) {
                CatalogThumbnail(
                  document = reducer.previewDocument(id),
                  componentId = id,
                  label = component.displayName,
                  size = DpSize(104.dp, 72.dp),
                  container = false,
                  onOutcome = { onOutcome(id, it) },
                )
              }
              Text(component.displayName, color = Color.White, fontSize = 9.sp)
            }
          }
        }
      }
    }
  }

  private companion object {
    /** A picture covers at least this share of its tile; a blank one is 0, a glyph is far under. */
    const val MIN_INK = 0.04

    fun wffCatalog(): CapabilityCatalog {
      val published =
        generateSequence(File("").absoluteFile) { it.parentFile }
          .map { File(it, "docs/design/fixtures/ui-builder/wff-watchface-published-v1.json") }
          .first { it.isFile }
          .readText()
      val composed =
        assertIs<PublishedUiBuilderCatalog.Result.Composed>(
          PublishedUiBuilderCatalog.compose(
            published,
            null,
            ExportCapabilitiesV1.Builder().apply { composeCode = true }.build(),
          ),
        )
      // Served as the editor receives it: through the executor's listing.
      val served =
        CurrentM3UiBuilderCatalogExecutor.Builder()
          .also {
            it.catalogSystemIds = setOf("wff-watchface")
            it.published = mapOf("wff-watchface" to composed.catalog)
          }
          .build()
          .listCatalogs()
          .single { it.benchmark.catalogSystemId == "wff-watchface" }
      return CapabilityCatalogParser.parse(Json.encodeToString(served))
    }
  }
}
