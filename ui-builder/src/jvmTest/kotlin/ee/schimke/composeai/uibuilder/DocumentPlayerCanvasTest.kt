package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.protocol.RcAccessibilitySemantics
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasInspection
import ee.schimke.composeai.uibuilder.editor.UiBuilderDocumentPreview
import ee.schimke.composeai.uibuilder.editor.documentPlayerCanvasRenderer
import ee.schimke.composeai.uibuilder.editor.playedCanvasDocument
import ee.schimke.composeai.uibuilder.export.DOCUMENT_INSPECTION_TAGS_KEY
import ee.schimke.composeai.uibuilder.export.NativeNodeTags
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The editing canvas that plays its host's export: the export is asked for with node tags, each
 * tagged component's box comes back as its node's bounds, and an export with no tags (or one the
 * player cannot read) is not played.
 */
@OptIn(ExperimentalTestApi::class)
class DocumentPlayerCanvasTest {
  private val design =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "canvas",
      title = "Canvas",
      revision = 3,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("column"),
      nodes =
        mapOf(
          "column" to
            UiBuilderNode(
              id = "column",
              componentId = "layout/column",
              slots = mapOf("children" to listOf("gauge", "button")),
            ),
          "gauge" to UiBuilderNode(id = "gauge", componentId = "vendor/gauge"),
          "button" to UiBuilderNode(id = "button", componentId = "vendor/button"),
        ),
    )

  @Test
  fun `each tagged component is its node, and an untagged export is not played`() {
    val played = assertNotNull(playedCanvasDocument(design, encoded(tagged = true)))
    assertEquals(mapOf(3 to "column", 5 to "gauge", 6 to "button"), played.nodeOfComponent)
    assertNull(playedCanvasDocument(design, encoded(tagged = false)))
    assertNull(playedCanvasDocument(design, "bm90IGFuIHJj"))
  }

  @Test
  fun `the canvas asks for a tagged export and reports where each node drew`() =
    runDesktopComposeUiTest(width = 200, height = 200) {
      val asked = mutableListOf<UiBuilderDocument>()
      var inspection: UiBuilderCanvasInspection? = null
      val renderer =
        documentPlayerCanvasRenderer(
          request = { document ->
            asked += document
            UiBuilderDocumentPreview.Ready(document.revision, encoded(tagged = true))
          },
        )
      setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
          // Offset in the window, as the canvas sits inside the editor.
          Box(Modifier.padding(start = 10.dp, top = 20.dp).size(100.dp)) {
            renderer(design, CANVAS_SURFACE, null, true, {}) { inspection = it }
          }
        }
      }
      waitUntil { inspection?.editor?.nodes?.count { it.bounds != null } == 3 }
      assertEquals(JsonPrimitive(true), asked.first().environment[DOCUMENT_INSPECTION_TAGS_KEY])
      fun boxes(snapshot: UiBuilderInspectionSnapshot) =
        snapshot.nodes.associate { node ->
          node.nodeId to node.bounds!!.let { listOf(it.x, it.y, it.width, it.height) }
        }
      // The renderer's own boxes are its frame's; the editor's are the editor root's.
      assertEquals(
        mapOf(
          "column" to listOf(0f, 0f, 60f, 50f),
          "gauge" to listOf(0f, 0f, 40f, 10f),
          "button" to listOf(0f, 10f, 30f, 20f),
        ),
        boxes(inspection!!.renderer),
      )
      assertEquals(
        mapOf(
          "column" to listOf(10f, 20f, 60f, 50f),
          "gauge" to listOf(10f, 20f, 40f, 10f),
          "button" to listOf(10f, 30f, 30f, 20f),
        ),
        boxes(inspection!!.editor),
      )
    }

  @Test
  fun `the selected node is outlined over the picture, and not while selection is off`() =
    runDesktopComposeUiTest(width = 100, height = 100) {
      var inspection: UiBuilderCanvasInspection? = null
      var selectionEnabled by mutableStateOf(true)
      val renderer =
        documentPlayerCanvasRenderer(
          request = { document ->
            UiBuilderDocumentPreview.Ready(document.revision, encoded(tagged = true))
          },
        )
      setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
          Box(Modifier.size(100.dp)) {
            renderer(design, CANVAS_SURFACE, "button", selectionEnabled, {}) { inspection = it }
          }
        }
      }
      waitUntil { inspection?.editor?.nodes?.count { it.bounds != null } == 3 }
      waitForIdle()
      fun pixel(x: Int, y: Int) = onRoot().captureToImage().toPixelMap()[x, y]
      // The button is at (0, 10), 30 by 20: its top edge is outlined, the gauge above it is not.
      assertEquals(Color(0xff6750a4), pixel(15, 10))
      assertNotEquals(Color(0xff6750a4), pixel(15, 4))
      selectionEnabled = false
      waitForIdle()
      assertNotEquals(Color(0xff6750a4), pixel(15, 10))
    }

  @Test
  fun `a deleted node stops being selectable before the next export answers`() =
    runDesktopComposeUiTest(width = 100, height = 100) {
      var inspection: UiBuilderCanvasInspection? = null
      var current by mutableStateOf(design)
      val renderer =
        documentPlayerCanvasRenderer(
          request = { document ->
            // The first export answers; the one after the edit never does.
            if (document.revision != design.revision) awaitCancellation()
            UiBuilderDocumentPreview.Ready(document.revision, encoded(tagged = true))
          },
        )
      setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
          Box(Modifier.size(100.dp)) {
            renderer(current, CANVAS_SURFACE, null, true, {}) { inspection = it }
          }
        }
      }
      waitUntil { inspection?.editor?.nodes?.count { it.bounds != null } == 3 }
      val column = design.nodes.getValue("column")
      current =
        design.copy(
          revision = design.revision + 1,
          nodes =
            design.nodes - "button" +
              ("column" to column.copy(slots = mapOf("children" to listOf("gauge")))),
        )
      waitUntil { inspection?.editor?.nodes?.none { it.nodeId == "button" } == true }
      assertEquals(listOf("column", "gauge"), inspection!!.editor.nodes.map { it.nodeId }.sorted())
    }

  /** A column holding two fixed-size canvases, tagged as the export tags them where [tagged]. */
  private fun encoded(tagged: Boolean): String {
    val end = RcNoArg(RcOpcodes.CONTAINER_END)
    fun width(px: Float) = RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(px))
    fun height(px: Float) = RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(px))
    fun tag(textId: Int) =
      if (!tagged) emptyList()
      else
        listOf(
          RcAccessibilitySemantics(
            contentDescriptionId = textId,
            role = RcAccessibilitySemantics.ROLE_UNKNOWN,
            textId = 0,
            stateDescriptionId = 0,
            mode = RcAccessibilitySemantics.MODE_SET,
            enabled = true,
            clickable = false,
          ),
        )
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 100, legacyHeight = 100, modern = false),
        listOf(
          RcTextData(10, NativeNodeTags.tag("column")),
          RcTextData(11, NativeNodeTags.tag("gauge")),
          RcTextData(12, NativeNodeTags.tag("button")),
          RcRootLayout(1),
          RcLayoutContent(2),
          RcColumnLayout(3, 30, 1, 4, RcFloatWord.literal(0f)),
          width(60f),
          height(50f),
          *tag(10).toTypedArray(),
          RcLayoutContent(4),
          RcCanvasLayout(5, 50),
          width(40f),
          height(10f),
          *tag(11).toTypedArray(),
          end,
          RcCanvasLayout(6, 60),
          width(30f),
          height(20f),
          *tag(12).toTypedArray(),
          end,
          end,
          end,
          end,
          end,
        ),
      )
    return Base64.Default.encode(RcDocumentCodec.encode(document))
  }

  private companion object {
    val CANVAS_SURFACE =
      ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasSurface(
        widthDp = 100f,
        heightDp = 100f,
        density = 1f,
        mode =
          ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED,
        interactive = false,
      )
  }
}
