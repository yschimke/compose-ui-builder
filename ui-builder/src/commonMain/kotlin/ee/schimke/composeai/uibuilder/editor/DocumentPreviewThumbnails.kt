package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.uibuilder.canvas.decodeRemoteComposeDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The palette's pictures for a catalog whose Preview plays the document its host exports (the
 * `remote-compose-document` browser preview): the canvas cannot draw such a catalog's components (a
 * document format's own library, compiled by the host), so a thumbnail plays the document the host
 * exports for the thumbnail's own one-component design, as the Preview pane does for the design.
 *
 * One request per distinct thumbnail document, at most [concurrency] at a time, kept for the
 * editor's life: a palette scrolled back, or opened again, asks nothing twice. A failure is kept
 * too, and that tile stays on the canvas's picture.
 *
 * Requests run in the editor's [scope], not a tile's: the same component can show on two shelves,
 * and a tile leaving composition (a shelf collapsed) must not cancel the export the other waits on.
 */
internal class DocumentPreviewThumbnails(
  private val scope: CoroutineScope,
  private val request: suspend (UiBuilderDocument) -> UiBuilderDocumentPreview,
  concurrency: Int = 2,
) {
  private val permits = Semaphore(concurrency)
  private val results = mutableStateMapOf<UiBuilderDocument, Result>()
  private val inFlight = mutableSetOf<UiBuilderDocument>()

  sealed interface Result {
    data class Played(val document: RcDocument) : Result

    data object Unavailable : Result
  }

  /** What [document] plays as, or null until its export has answered. */
  fun result(document: UiBuilderDocument): Result? = results[document]

  /** Starts exporting [document] in the editor's scope, unless it already was (or is being). */
  fun prefetch(document: UiBuilderDocument) {
    if (document in results || document in inFlight) return
    scope.launch { load(document) }
  }

  /** Exports [document] unless it already was (or is being). */
  suspend fun load(document: UiBuilderDocument) {
    if (document in results || !inFlight.add(document)) return
    try {
      results[document] = permits.withPermit {
        when (val preview = request(document)) {
          is UiBuilderDocumentPreview.Ready ->
            decodeRemoteComposeDocument(preview.documentBase64)
              .getOrNull()
              // A document this player refuses draws nothing: the canvas's picture is better.
              ?.takeIf { it.blockingPlayerIssues().isEmpty() }
              ?.let(Result::Played) ?: Result.Unavailable
          else -> Result.Unavailable
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      results[document] = Result.Unavailable
    } finally {
      inFlight.remove(document)
    }
  }
}

/** The editor's [DocumentPreviewThumbnails], where its catalog previews exported documents. */
internal val LocalDocumentPreviewThumbnails =
  staticCompositionLocalOf<DocumentPreviewThumbnails?> { null }

/**
 * [document]'s exported picture in a [tile], scaled to fit its [frameWidthDp] x [frameHeightDp]
 * frame and never magnified; [fallback] (the canvas's own picture) until the export answers, and
 * for good where it cannot be played.
 *
 * A picture, as the canvas's is: the player's controls are hidden from accessibility and take no
 * press, so a tap or drag reaches the palette tile, named "Drag [label]" as the canvas's tile is.
 */
@Composable
internal fun DocumentPreviewThumbnail(
  thumbnails: DocumentPreviewThumbnails,
  document: UiBuilderDocument,
  label: String,
  tile: DpSize,
  frameWidthDp: Int,
  frameHeightDp: Int,
  fallback: @Composable () -> Unit,
) {
  LaunchedEffect(thumbnails, document) { thumbnails.prefetch(document) }
  val played = thumbnails.result(document) as? DocumentPreviewThumbnails.Result.Played
  if (played == null) {
    fallback()
    return
  }
  val scale =
    minOf(tile.width.value / frameWidthDp, tile.height.value / frameHeightDp).coerceAtMost(1f)
  val theme =
    when (document.environment["theme"]?.jsonPrimitive?.contentOrNull) {
      "light" -> RcPlayerTheme.Light
      "dark" -> RcPlayerTheme.Dark
      else -> RcPlayerTheme.System
    }
  Box(Modifier.size(tile).clipToBounds(), contentAlignment = Alignment.Center) {
    val scaled = remember(scale) { DpSize((frameWidthDp * scale).dp, (frameHeightDp * scale).dp) }
    Box(Modifier.size(scaled)) {
      Box(
        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
          .requiredSize(frameWidthDp.dp, frameHeightDp.dp)
          .graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = scale
            scaleY = scale
          }
          .clearAndSetSemantics {},
      ) {
        // The thumbnail's own fixed time, as the Preview pane plays a frozen design: a clock's
        // picture must not move while the palette scrolls.
        ProvidePreviewPlayerTime(document, running = false) {
          RcComposePlayer(
            document = played.document,
            theme = theme,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }
    // On top of the player: sees every press first, consumes none, and the tile's drag gets them.
    Box(
      Modifier.matchParentSize()
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
        .semantics { contentDescription = "Drag $label" },
    )
  }
}

/**
 * [this] as the host's service takes it: the service document requires a `density` and a
 * `fontScale`, which a palette thumbnail leaves out on purpose (so the canvas lays it out at its
 * own scale, see `previewDocument`). Without them the request could not be built, and a component
 * only the service can compile never got a picture. 1 is what an exported `.rc` is authored at.
 */
internal fun UiBuilderDocument.withServiceScale(): UiBuilderDocument {
  val missing = SERVICE_SCALE.filterKeys { it !in environment }
  return if (missing.isEmpty()) this else copy(environment = JsonObject(environment + missing))
}

/**
 * [this] pinned as [design] is, when both name the same catalog system.
 *
 * A palette thumbnail pins the catalog from its local benchmark, with the catalog revision standing
 * in for the capability digest, which is what the browser's own runtimes compare against. The
 * service only accepts the pins it publishes (whose digest is still its placeholder), so a
 * thumbnail sent there as-is was refused `409 catalog pin is unavailable`, signed in or not. The
 * open design names the same catalog under a pin the service accepted when it created or served it.
 */
internal fun UiBuilderDocument.withCatalogPinOf(design: UiBuilderDocument?): UiBuilderDocument {
  val pin = design?.catalogPin ?: return this
  if (pin["systemId"] != catalogPin["systemId"] || pin == catalogPin) return this
  return copy(catalogPin = pin)
}

private val SERVICE_SCALE =
  mapOf("density" to JsonPrimitive(1.0), "fontScale" to JsonPrimitive(1.0))
