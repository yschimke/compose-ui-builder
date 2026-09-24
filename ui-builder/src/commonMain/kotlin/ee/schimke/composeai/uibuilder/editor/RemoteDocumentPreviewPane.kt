package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.uibuilder.canvas.WearWidgetContainerScaffold
import ee.schimke.composeai.uibuilder.canvas.decodeRemoteComposeDocument
import ee.schimke.composeai.uibuilder.canvas.renderDensity
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.hostSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The host exports either the saved revision or the supplied current document. */
sealed interface UiBuilderDocumentPreview {
  data object WaitingForSave : UiBuilderDocumentPreview

  data class Ready(val revision: Int, val documentBase64: String, val saved: Boolean = true) :
    UiBuilderDocumentPreview

  data class Failed(val message: String) : UiBuilderDocumentPreview
}

/**
 * The selected browser Preview variants, played from exported documents rather than redrawn by the
 * editing canvas.
 *
 * The catalog opts into this through `CatalogCapabilityV1.browserPreview`; this composable knows
 * nothing about catalog ids or platforms. Documents equal by value are captured once and shared —
 * the three Wear widget host shapes deliberately carry one document — while device and environment
 * variants whose documents differ receive their own export.
 *
 * A successful frame remains on screen while a newer revision is prepared. Clearing it first made
 * an ordinary property edit flash a blank Preview for the entire compile/capture round trip, which
 * turns an asynchronous fidelity upgrade into a worse editing surface.
 */
@Composable
internal fun RemoteDocumentDesignPreviewPane(
  document: UiBuilderDocument,
  variants: List<UiBuilderVariantPane>,
  authoritativeGeneration: Int,
  request: suspend (UiBuilderDocument) -> UiBuilderDocumentPreview,
  modifier: Modifier = Modifier,
) {
  val panes = document.wearWidgetScaffoldSize()?.let(document::wearWidgetPreviewPanes) ?: variants
  var results by
    remember(document.id) { mutableStateOf<Map<String, UiBuilderDocumentPreview>>(emptyMap()) }
  var pending by remember(document.id) { mutableStateOf(false) }
  var requestSequence by remember(document.id) { mutableIntStateOf(0) }
  val latestRequest by rememberUpdatedState(request)
  val requested = panes.map(UiBuilderVariantPane::document).distinct()
  LaunchedEffect(requested, authoritativeGeneration) {
    if (requested.isEmpty()) return@LaunchedEffect
    val sequence = ++requestSequence
    pending = true
    // Collapse a burst of property edits into the revision that survived it. The previous captured
    // frame stays visible during the debounce and the host compilation that follows.
    delay(DOCUMENT_PREVIEW_DEBOUNCE_MS)
    val next = mutableMapOf<UiBuilderDocument, UiBuilderDocumentPreview>()
    requested.forEach { variantDocument ->
      next[variantDocument] =
        try {
          latestRequest(variantDocument).also {
            require(
              it !is UiBuilderDocumentPreview.Ready || it.revision == variantDocument.revision
            ) {
              "Preview returned a different revision"
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: Exception) {
          UiBuilderDocumentPreview.Failed(failure.message ?: "Document preview failed")
        }
    }
    // A host request is allowed to finish in NonCancellable cleanup. In that case the replacement
    // effect has already advanced the sequence and this old response must not repaint the panes.
    if (requestSequence == sequence) {
      results = results + panes.associate { pane -> pane.id to checkNotNull(next[pane.document]) }
      pending = false
    }
  }

  Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
      if (pending) {
        Text("Updating Remote preview…", style = MaterialTheme.typography.labelSmall)
      }
      BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        if (panes.isEmpty()) {
          PreviewMessage("Select devices or display variants to compare")
          return@BoxWithConstraints
        }
        val tallest = panes.maxOf { it.heightDp }
        val widest = panes.maxOf { it.widthDp }
        val scale =
          minOf(maxHeight.value / (tallest + REMOTE_VARIANT_LABEL_ROOM_DP), maxWidth.value / widest)
            .coerceIn(MIN_CANVAS_ZOOM, 1f)
        val gap = 16.dp
        val perRow = ((maxWidth + gap) / ((widest * scale).dp + gap)).toInt().coerceAtLeast(1)
        val frame: @Composable (UiBuilderVariantPane) -> Unit = { pane ->
          key(pane.id) {
            RemoteDocumentVariantPane(
              pane = pane,
              result = results[pane.id],
              scale = scale,
            )
          }
        }
        if (perRow >= 2) {
          Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            FlowRow(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
              verticalArrangement = Arrangement.spacedBy(gap),
              maxItemsInEachRow = perRow,
            ) {
              for (pane in panes) frame(pane)
            }
          }
        } else {
          Row(
            Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            for (pane in panes) frame(pane)
          }
        }
      }
    }
  }
}

@Composable
private fun RemoteDocumentVariantPane(
  pane: UiBuilderVariantPane,
  result: UiBuilderDocumentPreview?,
  scale: Float,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      pane.label,
      Modifier.height(REMOTE_VARIANT_LABEL_ROOM_DP.dp).widthIn(max = (pane.widthDp * scale).dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
    Box(
      Modifier.size((pane.widthDp * scale).dp, (pane.heightDp * scale).dp)
        .clipToBounds()
        .semantics { contentDescription = "Remote document preview · ${pane.label}" },
      contentAlignment = Alignment.Center,
    ) {
      when (result) {
        null -> PreviewMessage("Preparing preview…")
        UiBuilderDocumentPreview.WaitingForSave -> PreviewMessage("Waiting for save…")
        is UiBuilderDocumentPreview.Failed -> PreviewMessage(result.message)
        is UiBuilderDocumentPreview.Ready ->
          PlayedRemoteDocumentVariant(pane = pane, encoded = result.documentBase64, scale = scale)
      }
    }
  }
}

@Composable
private fun PlayedRemoteDocumentVariant(
  pane: UiBuilderVariantPane,
  encoded: String,
  scale: Float,
) {
  val decoded = remember(encoded) { decodeRemoteComposeDocument(encoded) }
  val target = decoded.getOrNull()
  val issues = remember(target) { target?.composeSupportReport()?.issues.orEmpty() }
  if (target == null) {
    PreviewMessage(decoded.exceptionOrNull()?.message ?: "Invalid document preview")
    return
  }
  if (issues.isNotEmpty()) {
    PreviewMessage(issues.joinToString("\n") { it.detail })
    return
  }
  val densityRatio =
    pane.document.renderDensity(LocalDensity.current).density / LocalDensity.current.density
  val theme =
    when (pane.document.environment["theme"]?.jsonPrimitive?.contentOrNull) {
      "light" -> RcPlayerTheme.Light
      "dark" -> RcPlayerTheme.Dark
      else -> RcPlayerTheme.System
    }
  Box(
    Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
      .requiredSize((pane.widthDp * densityRatio).dp, (pane.heightDp * densityRatio).dp)
      .clip(RoundedCornerShape(0.dp))
      .graphicsLayer {
        scaleX = scale / densityRatio
        scaleY = scale / densityRatio
        transformOrigin = TransformOrigin(0f, 0f)
      }
  ) {
    val widgetSize = pane.document.wearWidgetScaffoldSize()
    val hostShape = pane.wearWidgetHostShape
    val root = pane.document.roots.singleOrNull()?.let(pane.document.nodes::get)
    if (widgetSize != null && hostShape != null && root != null) {
      WearWidgetContainerScaffold(
        node = root,
        modifier = Modifier,
        spec = widgetSize.hostSpec(hostShape),
        brushes = { _ -> },
        hasBrushes = false,
      ) {
        RcComposePlayer(document = target, theme = theme, modifier = Modifier.fillMaxSize())
      }
    } else {
      RcComposePlayer(document = target, theme = theme, modifier = Modifier.fillMaxSize())
    }
  }
}

private const val DOCUMENT_PREVIEW_DEBOUNCE_MS = 250L

private const val REMOTE_VARIANT_LABEL_ROOM_DP = 18f

/** Plays the exported document, independently of the semantic authoring renderer and its state. */
@Composable
internal fun RemoteDocumentPreviewPane(
  document: UiBuilderDocument,
  authoritativeGeneration: Int,
  request: suspend (UiBuilderDocument) -> UiBuilderDocumentPreview,
  modifier: Modifier = Modifier,
) {
  var result by remember(document) { mutableStateOf<UiBuilderDocumentPreview?>(null) }
  val latestRequest by rememberUpdatedState(request)
  LaunchedEffect(document, authoritativeGeneration) {
    result = null
    result =
      try {
        latestRequest(document).also {
          require(it !is UiBuilderDocumentPreview.Ready || it.revision == document.revision) {
            "Preview returned a different revision"
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        UiBuilderDocumentPreview.Failed(failure.message ?: "Document preview failed")
      }
  }
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    when (val current = result) {
      null -> PreviewMessage("Preparing preview…")
      UiBuilderDocumentPreview.WaitingForSave -> PreviewMessage("Waiting for changes to be saved…")
      is UiBuilderDocumentPreview.Failed -> PreviewMessage(current.message)
      is UiBuilderDocumentPreview.Ready ->
        key(document.id, current) {
          val decoded =
            remember(current.documentBase64) { decodeRemoteComposeDocument(current.documentBase64) }
          val target = decoded.getOrNull()
          val issues = remember(target) { target?.composeSupportReport()?.issues.orEmpty() }
          if (target == null) {
            PreviewMessage(decoded.exceptionOrNull()?.message ?: "Invalid document preview")
          } else if (issues.isNotEmpty()) {
            PreviewMessage(issues.joinToString("\n") { it.detail })
          } else {
            val width =
              document.environment["widthDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                ?: 1280f
            val height =
              document.environment["heightDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                ?: 800f
            val densityRatio =
              document.renderDensity(LocalDensity.current).density / LocalDensity.current.density
            val theme =
              when (document.environment["theme"]?.jsonPrimitive?.contentOrNull) {
                "light" -> RcPlayerTheme.Light
                "dark" -> RcPlayerTheme.Dark
                else -> RcPlayerTheme.System
              }
            Column(Modifier.fillMaxSize().padding(12.dp)) {
              Text(
                if (current.saved) "Live preview · revision ${current.revision}"
                else "Live preview · unsaved changes",
                style = MaterialTheme.typography.labelSmall,
              )
              BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                contentAlignment = Alignment.Center,
              ) {
                val scale = minOf(maxWidth.value / width, maxHeight.value / height).coerceAtMost(1f)
                Box(
                  Modifier.size((width * scale).dp, (height * scale).dp).semantics {
                    contentDescription = "Interactive document preview"
                  }
                ) {
                  RcComposePlayer(
                    document = target,
                    theme = theme,
                    modifier =
                      Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize((width * densityRatio).dp, (height * densityRatio).dp)
                        .graphicsLayer {
                          scaleX = scale / densityRatio
                          scaleY = scale / densityRatio
                          transformOrigin = TransformOrigin(0f, 0f)
                        },
                  )
                }
              }
            }
          }
        }
    }
  }
}

@Composable
private fun PreviewMessage(message: String) {
  Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
