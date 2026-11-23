package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.ExperimentalRcPlayerApi
import ee.schimke.composeai.rcplayer.compose.LocalRcComponentCoordinates
import ee.schimke.composeai.rcplayer.compose.RcComponentCoordinatesListener
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcNativeNodeSnapshot
import ee.schimke.composeai.rcplayer.compose.RcNativeSnapshotBridge
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.uibuilder.authoredIn
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.DOCUMENT_INSPECTION_TAGS_KEY
import ee.schimke.composeai.uibuilder.export.NativeNodeTags
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionCollector
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The editing canvas for a catalog whose components only its host's export can draw (the
 * `remote-compose-document` browser preview, a document format's own library): the canvas plays the
 * `.rc` the host exports for the design, so it shows the components as they are, not as boxes.
 *
 * The export is asked for with [DOCUMENT_INSPECTION_TAGS_KEY], so each node's component carries its
 * node id as a semantics label. The player says where every component landed
 * ([LocalRcComponentCoordinates]); a labelled one's box is its node's, which is what the editor
 * selects, drops into and outlines over the picture.
 *
 * Until the first export answers, and for good where it fails or the player refuses it, the canvas
 * draws its own picture: the layout, with each component a named box. An edit keeps the last
 * picture up until the next export answers, [DEBOUNCE_MILLIS] after typing stops.
 */
internal fun documentPlayerCanvasRenderer(
  request: suspend (UiBuilderDocument) -> UiBuilderDocumentPreview,
): UiBuilderCanvasRenderer =
  { document, _, selectedNodeId, selectionEnabled, _, onInspectionSnapshot ->
    DocumentPlayerCanvas(
      document,
      request,
      selectedNodeId.takeIf { selectionEnabled },
      onInspectionSnapshot,
    )
  }

/**
 * A played export: the design it was asked for, the document, and which component is which node.
 */
internal class PlayedCanvasDocument(
  val source: UiBuilderDocument,
  val document: RcDocument,
  val nodeOfComponent: Map<Int, String>,
)

/**
 * [this] design with every node's component labelled in the export; see
 * [documentPlayerCanvasRenderer].
 */
internal fun UiBuilderDocument.withInspectionTags(): UiBuilderDocument =
  copy(
    environment =
      JsonObject(
        environment + (DOCUMENT_INSPECTION_TAGS_KEY to JsonPrimitive(true)),
      ),
  )

/**
 * [base64] decoded for the canvas, or null where it cannot be played: undecodable, refused by the
 * player, or with no node labelled (an export that ignored the tags would select nothing).
 */
internal fun playedCanvasDocument(
  source: UiBuilderDocument,
  base64: String,
): PlayedCanvasDocument? {
  val bytes = runCatching { Base64.Default.decode(base64) }.getOrNull() ?: return null
  val document = runCatching { RcDocumentCodec.decode(bytes) }.getOrNull() ?: return null
  if (document.composeSupportReport().issues.isNotEmpty()) return null
  val root = runCatching { RcNativeSnapshotBridge.decode(bytes).root }.getOrNull() ?: return null
  val nodeOfComponent = buildMap {
    fun walk(node: RcNativeNodeSnapshot) {
      node.semanticLabel
        ?.let(NativeNodeTags::nodeIds)
        ?.firstOrNull { it in source.nodes }
        ?.let { put(node.componentId, it) }
      node.children.forEach(::walk)
    }
    walk(root)
  }
  if (nodeOfComponent.isEmpty()) return null
  return PlayedCanvasDocument(source, document, nodeOfComponent)
}

@OptIn(ExperimentalRcPlayerApi::class)
@Composable
private fun DocumentPlayerCanvas(
  document: UiBuilderDocument,
  request: suspend (UiBuilderDocument) -> UiBuilderDocumentPreview,
  selectedNodeId: String?,
  onInspectionSnapshot: (UiBuilderCanvasInspection) -> Unit,
) {
  val latestRequest by rememberUpdatedState(request)
  val latestSnapshot by rememberUpdatedState(onInspectionSnapshot)
  val latestDocument by rememberUpdatedState(document)
  var played by remember(document.id) { mutableStateOf<PlayedCanvasDocument?>(null) }
  LaunchedEffect(document) {
    // The first picture is asked for at once; an edit waits for typing to stop.
    if (played != null) delay(DEBOUNCE_MILLIS)
    val preview =
      try {
        latestRequest(document.withInspectionTags())
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }
    // A failed export keeps the last picture: the next edit may well export again.
    (preview as? UiBuilderDocumentPreview.Ready)
      ?.let { playedCanvasDocument(document, it.documentBase64) }
      ?.let { played = it }
  }
  val current = played
  // The canvas box, and what was last measured in it: the editor's snapshot is that, moved to the
  // editor's root, sent again whenever the box moves (a pan or a zoom lays nothing out again).
  var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
  var measured by remember(current) { mutableStateOf<UiBuilderInspectionSnapshot?>(null) }
  fun publish() {
    val local = measured ?: return
    val frame = root?.takeIf { it.isAttached } ?: return
    // What the design still holds: a node deleted since this export cannot be selected.
    val authored = local.authoredIn(latestDocument)
    latestSnapshot(UiBuilderCanvasInspection(authored, authored.mapped(frame::localToRoot)))
  }
  if (current == null) {
    var placeholders by remember { mutableStateOf<UiBuilderInspectionSnapshot?>(null) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { root = it }) {
      UiBuilderSurface(
        document = document,
        editorOverlay = false,
        // The surface measures in root coordinates already; the renderer's own are the box's.
        onInspectionSnapshot = { snapshot ->
          val frame = root?.takeIf { it.isAttached }
          val local =
            frame?.let { box ->
              val origin = box.findRootCoordinates()
              snapshot.mapped { box.localPositionOf(origin, it) }
            } ?: snapshot
          placeholders = local
          latestSnapshot(UiBuilderCanvasInspection(local, snapshot))
        },
      )
      SelectionOutline(placeholders, selectedNodeId)
    }
    return
  }
  val collector =
    remember(current) {
      UiBuilderInspectionCollector(
        current.source,
        onSnapshot = { snapshot ->
          measured = snapshot
          publish()
        },
      )
    }
  // An edit (a node deleted, say) changes what may be selected before anything lays out again: the
  // retained boxes are filtered against it now, not at the next layout or export.
  LaunchedEffect(document) { publish() }
  val listener =
    remember(collector) {
      RcComponentCoordinatesListener { componentId, coordinates ->
        val nodeId = current.nodeOfComponent[componentId] ?: return@RcComponentCoordinatesListener
        val frame = root?.takeIf { it.isAttached } ?: return@RcComponentCoordinatesListener
        if (!coordinates.isAttached) return@RcComponentCoordinatesListener
        val box = frame.localBoundingBoxOf(coordinates, clipBounds = false)
        collector.recordNodeBounds(nodeId, box.left, box.top, box.right, box.bottom)
      }
    }
  val theme =
    when (document.environment["theme"]?.jsonPrimitive?.contentOrNull) {
      "light" -> RcPlayerTheme.Light
      "dark" -> RcPlayerTheme.Dark
      else -> RcPlayerTheme.System
    }
  Box(
    Modifier.fillMaxSize().onGloballyPositioned {
      root = it
      publish()
    },
  ) {
    CompositionLocalProvider(LocalRcComponentCoordinates provides listener) {
      // Frozen at the design's fixed time, as the palette's pictures are: the canvas edits a
      // design,
      // it does not run one.
      ProvidePreviewPlayerTime(document, running = false) {
        RcComposePlayer(
          document = current.document,
          theme = theme,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
    SelectionOutline(measured, selectedNodeId)
    // On top of the player: a press is the editor's (select, drag), never the drawn control's. It
    // sees every press first and consumes none, so the canvas's own gestures still get them.
    Box(
      Modifier.matchParentSize().pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent() }
      },
    )
  }
}

/**
 * The selection, drawn as the editor's own canvas draws it: an outline around every box
 * [selectedNodeId] drew in [snapshot] (the canvas's own coordinates), above the picture.
 */
@Composable
private fun BoxScope.SelectionOutline(
  snapshot: UiBuilderInspectionSnapshot?,
  selectedNodeId: String?,
) {
  val boxes =
    snapshot?.nodes.orEmpty().filter { it.nodeId == selectedNodeId }.mapNotNull { it.bounds }
  if (boxes.isEmpty()) return
  Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
    boxes.forEach { box ->
      drawRect(
        SELECTION_COLOR,
        Offset(box.x, box.y),
        Size(box.width, box.height),
        style = Stroke(2.dp.toPx()),
      )
    }
  }
}

/** The editor canvas's selection colour (`UiBuilderSurface`'s overlay). */
private val SELECTION_COLOR = Color(0xff6750a4)

/** [this] snapshot's boxes moved by [move], a mapping of points that may translate and scale. */
private fun UiBuilderInspectionSnapshot.mapped(
  move: (Offset) -> Offset,
): UiBuilderInspectionSnapshot {
  fun UiBuilderPixelBounds.moved(): UiBuilderPixelBounds {
    val topLeft = move(Offset(x, y))
    val bottomRight = move(Offset(x + width, y + height))
    return copy(
      x = topLeft.x,
      y = topLeft.y,
      width = bottomRight.x - topLeft.x,
      height = bottomRight.y - topLeft.y,
    )
  }
  return copy(
    nodes = nodes.map { node -> node.copy(bounds = node.bounds?.moved()) },
    slots = slots.map { slot -> slot.copy(bounds = slot.bounds?.moved()) },
  )
}

/** How long after the last edit the canvas asks the host for a new picture. */
private const val DEBOUNCE_MILLIS = 400L
