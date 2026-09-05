package ee.schimke.composeai.uibuilder

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
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Capturing a catalog component as a picture, so it can be *placed* on the reference.
 *
 * This is the design → reference direction of the one-way door, and it is deliberately a
 * rasterisation: the component is composed by the same renderer that draws the document, captured
 * to pixels at the moment it is placed, and from then on it is a picture like every other piece.
 * Nothing live ever lives in the reference layer — that is what keeps "exportable Compose the
 * catalog vouched for" and "pixels that are nobody's truth" from becoming one confused thing.
 *
 * The picture is not one-way in the sense of being a dead end, though: the piece keeps the
 * component's id, and [UiBuilderEditorEvent.PromoteReferencePiece] builds the real component back
 * out of it. See `docs/design/UI_BUILDER_REFERENCE_OVERLAY.md`.
 */

/** What the editor has asked to capture, and where the result is going. */
internal data class ReferenceCaptureRequest(val componentId: String, val sequence: Int)

/**
 * Composes [request]'s component off to one side and hands back a PNG of it.
 *
 * A real composition rather than a synthetic drawing, because the whole value of a placed component
 * is that it looks exactly like the component will: the same renderer, the same catalog defaults,
 * the same theme. It is drawn at zero effective size — `requiredSize` inside a `wrapContentSize`
 * that reports nothing — so it never disturbs the editor's layout, and its content goes to a
 * graphics layer rather than to the screen.
 *
 * [onCaptured] fires once per request, with null when the catalog cannot build the component or the
 * platform will not encode a bitmap. The caller decides what a failure says; this composable does
 * not draw errors.
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
 * A single-component document, built the way the editor builds an insertion.
 *
 * Through the reducer rather than by hand, so a specimen is composed of exactly the nodes a real
 * insertion would produce — catalog defaults, required slots filled, and all. A picture of
 * something the editor could not actually insert would be a lie the operator only discovers when
 * they promote it.
 *
 * The root is a bare `layout/box` rather than the blank template's scaffold: a scaffold paints a
 * whole screen's background, and a piece is meant to be a component on transparency, not a
 * component in a rectangle of surface colour.
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
    // The design's own environment, minus its frame: a component captured at the design's density,
    // locale, font scale and theme is the component the design will get, and one captured at some
    // default is a picture of a different screen.
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

/**
 * The frame a specimen is composed in, in dp.
 *
 * Generous, because it is trimmed afterwards: a frame that is too small clips a component and the
 * operator gets a picture of half a card, where a frame that is too large costs one capture's worth
 * of transparent pixels that [trimmed] then throws away.
 */
private const val SPECIMEN_WIDTH_DP = 480

private const val SPECIMEN_HEIGHT_DP = 320

/**
 * The same picture with its transparent margins removed, or the original when it is all empty.
 *
 * A component composed in a 480 × 320 frame is mostly nothing, and a piece whose rectangle is
 * mostly nothing cannot be positioned — its visible edge would never line up with where the
 * operator dropped it, and its selection outline would enclose a lot of air. Trimming makes the
 * piece's rectangle the component's own bounds, which is what makes dragging it feel like moving
 * the thing rather than moving a canvas it happens to sit on.
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
 * Not zero: a component's own antialiased edge and any shadow it casts fade to alpha values a long
 * way below visible, and trimming to "anything but exactly transparent" keeps a halo of them that
 * is wider than the component.
 */
private const val TRIM_ALPHA_FLOOR = 0.02f
