package ee.schimke.composeai.uibuilder.reference

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Capturing a catalog component as a picture, so it can be *placed* on the reference. Deliberately
 * a rasterisation by the document's renderer: nothing live lives in the reference layer. The piece
 * keeps the component id, so [UiBuilderEditorEvent.PromoteReferencePiece] can rebuild the real
 * component. See `docs/design/UI_BUILDER_REFERENCE_OVERLAY.md`.
 */

/** What the editor has asked to capture, and where the result is going. */
internal data class ReferenceCaptureRequest(val componentId: String, val sequence: Int)

/**
 * Composes [request]'s component off to one side and hands back a PNG of it: a real composition
 * (same renderer, defaults and theme) at zero effective size, drawn to a graphics layer.
 * [onCaptured] fires once per request, with null if the catalog can't build it or the platform
 * can't encode; the caller words failures.
 */
@Composable
internal fun ReferenceComponentCapture(
  request: ReferenceCaptureRequest?,
  catalog: CapabilityCatalog,
  document: UiBuilderDocument,
  onCaptured: (ReferenceImage?) -> Unit,
) {
  if (request == null) return
  val specimen =
    remember(request.sequence) { componentSpecimenDocument(catalog, request.componentId, document) }
  if (specimen == null) {
    LaunchedEffect(request.sequence) { onCaptured(null) }
    return
  }
  val layer = rememberGraphicsLayer()
  Box(
    Modifier.wrapContentSize(unbounded = true, align = Alignment.TopStart)
      .requiredSize(SPECIMEN_WIDTH_DP.dp, SPECIMEN_HEIGHT_DP.dp)
      .drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        // Recorded and not drawn: the specimen is being photographed, not shown. Drawing it would
        // put a stray component in the corner of the editor for as long as the capture took.
        drawLayer(layer)
      }
  ) {
    UiBuilderSurface(document = specimen, editorOverlay = false)
  }
  LaunchedEffect(request.sequence) {
    val captured =
      try {
        layer.toImageBitmap()
      } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        null
      }
    onCaptured(captured?.let { trimmed(it) }?.let { bitmap -> encoded(bitmap, request) })
  }
}

private fun encoded(bitmap: ImageBitmap, request: ReferenceCaptureRequest): ReferenceImage? {
  val png = encodeReferencePng(bitmap) ?: return null
  return ReferenceImage(
    id = "component-${request.componentId}-${request.sequence}",
    name = request.componentId,
    mediaType = "image/png",
    base64 = Base64.Default.encode(png),
    widthPx = bitmap.width,
    heightPx = bitmap.height,
  )
}

/**
 * A single-component document, built through the reducer as a real insertion would (defaults,
 * required slots). The root is a bare `layout/box`, not a scaffold, so the piece is on
 * transparency.
 */
internal fun componentSpecimenDocument(
  catalog: CapabilityCatalog,
  componentId: String,
  source: UiBuilderDocument,
): UiBuilderDocument? {
  if (catalog.componentsById[componentId] == null) return null
  val reducer = UiBuilderEditorReducer(catalog, actorId = "specimen", clientId = "specimen")
  val base = specimenBaseDocument(source)
  val state = reducer.initial(base, selectedNodeId = SPECIMEN_ROOT_ID)
  val target = reducer.dropTarget(state, componentId) ?: return null
  val inserted = reducer.reduce(state, UiBuilderEditorEvent.InsertComponent(componentId, target))
  return inserted.document.takeIf { it.nodes.size > base.nodes.size }
}

/** The empty frame a specimen is composed into: one box, the source design's pin and theme. */
private fun specimenBaseDocument(source: UiBuilderDocument): UiBuilderDocument =
  UiBuilderDocument(
    schema = source.schema,
    id = "specimen",
    title = "Component specimen",
    revision = 0,
    catalogPin = source.catalogPin,
    // The design's environment minus its frame, so the capture matches the design's density,
    // locale, font scale and theme.
    environment =
      JsonObject(
        source.environment +
          mapOf(
            "widthDp" to JsonPrimitive(SPECIMEN_WIDTH_DP),
            "heightDp" to JsonPrimitive(SPECIMEN_HEIGHT_DP),
          )
      ),
    stateVariables = source.stateVariables,
    roots = listOf(SPECIMEN_ROOT_ID),
    nodes =
      mapOf(
        SPECIMEN_ROOT_ID to
          UiBuilderNode(
            id = SPECIMEN_ROOT_ID,
            componentId = "layout/box",
            properties = JsonObject(emptyMap()),
            modifiers = JsonArray(emptyList()),
            slots = mapOf("children" to emptyList()),
          )
      ),
  )

private const val SPECIMEN_ROOT_ID = "specimen-root"

/** The frame a specimen is composed in, in dp: generous, since [trimmed] removes the excess. */
private const val SPECIMEN_WIDTH_DP = 480

private const val SPECIMEN_HEIGHT_DP = 320

/**
 * The same picture with its transparent margins removed (or the original if all empty), so the
 * piece's rectangle is the component's own bounds.
 */
internal fun trimmed(bitmap: ImageBitmap): ImageBitmap {
  val pixels = bitmap.toPixelMap()
  var left = bitmap.width
  var top = bitmap.height
  var right = -1
  var bottom = -1
  for (y in 0 until bitmap.height) {
    for (x in 0 until bitmap.width) {
      if (pixels[x, y].alpha <= TRIM_ALPHA_FLOOR) continue
      if (x < left) left = x
      if (x > right) right = x
      if (y < top) top = y
      if (y > bottom) bottom = y
    }
  }
  if (right < left || bottom < top) return bitmap
  if (left == 0 && top == 0 && right == bitmap.width - 1 && bottom == bitmap.height - 1) {
    return bitmap
  }
  return bitmap.cropped(left, top, right - left + 1, bottom - top + 1)
}

/**
 * Not zero: antialiased edges and shadows leave faint alpha that would otherwise widen the trim.
 */
private const val TRIM_ALPHA_FLOOR = 0.02f
