package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The host exports either the saved revision or the supplied current document. */
sealed interface UiBuilderDocumentPreview {
  data object WaitingForSave : UiBuilderDocumentPreview

  data class Ready(val revision: Int, val documentBase64: String, val saved: Boolean = true) :
    UiBuilderDocumentPreview

  data class Failed(val message: String) : UiBuilderDocumentPreview
}

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
