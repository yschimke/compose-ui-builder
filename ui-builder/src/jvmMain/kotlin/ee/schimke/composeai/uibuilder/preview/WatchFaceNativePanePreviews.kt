package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.ProductionUiBuilderSurface
import ee.schimke.composeai.uibuilder.editor.UiBuilderWatchFaceStage
import ee.schimke.composeai.uibuilder.editor.WatchFaceRuntimePaneContent
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter

/*
 * The watch-face Native pane at each loading stage the host reports (compose-preview-server
 * #2016), and the canvas cutting a complication's content to its bounding box as the watch does.
 */

@Composable
private fun NativePaneFrame(content: @Composable () -> Unit) {
  MaterialTheme { Box(Modifier.fillMaxSize()) { content() } }
}

@Composable
private fun StagePane(stage: UiBuilderWatchFaceStage?, frame: ImageBitmap? = null) {
  NativePaneFrame {
    WatchFaceRuntimePaneContent(
      modifier = Modifier.fillMaxSize(),
      stage = stage,
      frame = frame,
      connected = true,
      pagePath = "/ui-builder/face/watch-face?session=s1",
      onAmbientChange = if (frame != null) ({}) else null,
    )
  }
}

private fun working(
  stage: String,
  step: Int,
  label: String,
  elapsed: Long,
  cached: Boolean = false,
) =
  UiBuilderWatchFaceStage(
    stage = stage,
    step = step,
    label = label,
    elapsedMillis = elapsed,
    cachedPackage = cached,
  )

@Preview(widthDp = 360, heightDp = 300)
@Composable
fun WatchFaceNativePaneQueuedPreview() =
  StagePane(
    UiBuilderWatchFaceStage(
      stage = UiBuilderWatchFaceStage.QUEUED,
      label = "Waiting for a free runtime (2 ahead)",
      elapsedMillis = 3_000,
      queueAhead = 2,
    ),
  )

@Preview(widthDp = 360, heightDp = 300)
@Composable
fun WatchFaceNativePaneExportingPreview() =
  StagePane(working(UiBuilderWatchFaceStage.EXPORTING, 1, "Exporting the watch face", 1_200))

@Preview(widthDp = 360, heightDp = 300)
@Composable
fun WatchFaceNativePanePackagingPreview() =
  StagePane(working(UiBuilderWatchFaceStage.PACKAGING, 2, "Packaging the watch face", 9_400))

@Preview(widthDp = 360, heightDp = 300)
@Composable
fun WatchFaceNativePaneCachedPackagePreview() =
  StagePane(
    working(UiBuilderWatchFaceStage.PACKAGING, 2, "Using the cached package", 1_800, cached = true),
  )

@Preview(widthDp = 360, heightDp = 300)
@Composable
fun WatchFaceNativePaneStartingRuntimePreview() =
  StagePane(
    working(UiBuilderWatchFaceStage.STARTING_RUNTIME, 3, "Starting the watch-face runtime", 24_000),
  )

@Preview(widthDp = 360, heightDp = 300)
@Composable
fun WatchFaceNativePaneInstallingPreview() =
  StagePane(working(UiBuilderWatchFaceStage.INSTALLING, 4, "Installing the watch face", 52_000))

@Preview(widthDp = 360, heightDp = 300)
@Composable
fun WatchFaceNativePaneLoadingFacePreview() =
  StagePane(working(UiBuilderWatchFaceStage.LOADING_FACE, 5, "Loading the watch face", 81_000))

@Preview(widthDp = 360, heightDp = 560)
@Composable
fun WatchFaceNativePaneLivePreview() {
  val frame = remember { sampleRuntimeFrame() }
  StagePane(
    UiBuilderWatchFaceStage(
      stage = UiBuilderWatchFaceStage.LIVE,
      step = 5,
      label = "Live",
      elapsedMillis = 41_000,
      frameIntervalMillis = 250,
      cachedPackage = true,
    ),
    frame = frame,
  )
}

@Preview(widthDp = 360, heightDp = 400)
@Composable
fun WatchFaceNativePaneFailedPreview() =
  StagePane(
    UiBuilderWatchFaceStage(
      stage = UiBuilderWatchFaceStage.FAILED,
      step = 2,
      label = "Packaging failed",
      elapsedMillis = 6_300,
      code = "DWF_PACKAGING_FAILED",
      reason =
        "Validation failed: res/raw/watchface.xml:41: <PartText> attribute width=\"-4\" " +
          "is not a valid dimension (case_steel)",
    ),
  )

/** An older host: no stages, so the socket's first frame is all the pane can wait for. */
@Preview(widthDp = 360, heightDp = 260)
@Composable
fun WatchFaceNativePaneOlderHostPreview() = StagePane(stage = null)

@Preview(widthDp = 360, heightDp = 300)
@Composable
fun WatchFaceNativePaneUnavailablePreview() = NativePaneFrame {
  WatchFaceRuntimePaneContent(
    modifier = Modifier.fillMaxSize(),
    unavailable =
      "DWF_RUNTIME_MISSING: The Wear OS watch-face runtime (DeclarativeWatchFaceRuntime.apk) " +
        "is not installed on this server.",
  )
}

/** The host's native-preview flag is off: greyed, never starting a session. */
@Preview(widthDp = 360, heightDp = 220)
@Composable
fun WatchFaceNativePaneTurnedOffPreview() = NativePaneFrame {
  WatchFaceRuntimePaneContent(modifier = Modifier.fillMaxSize(), disabled = true)
}

/** A stand-in for a runtime frame: a dark dial with a ring, drawn rather than shipped. */
private fun sampleRuntimeFrame(): ImageBitmap =
  ImageBitmap(300, 300).also { bitmap ->
    val canvas = Canvas(bitmap)
    canvas.drawCircle(Offset(150f, 150f), 150f, Paint().apply { color = Color(0xFF0E1319) })
    canvas.drawCircle(
      Offset(150f, 150f),
      120f,
      Paint().apply {
        color = Color(0xFF4FC3F7)
        style = PaintingStyle.Stroke
        strokeWidth = 10f
      },
    )
    canvas.drawCircle(Offset(150f, 60f), 14f, Paint().apply { color = Color(0xFFF6B26B) })
  }

/**
 * brazen-nugget's slot 1, reduced: a full-face slot whose 96×96 `<BoundingBox>` at 252,306 holds a
 * ring but not the label above it. Left as designed, where the watch cuts the label at y=306 and
 * now the canvas does too; right with the box grown to cover it, drawn whole.
 */
private fun clippedComplicationFace(box: String) =
  """
    <WatchFace width="450" height="450">
      <Scene backgroundColor="#ff0e1319">
        <ComplicationSlot slotId="1" supportedTypes="SHORT_TEXT" x="0" y="0" width="450" height="450">
          <BoundingBox $box/>
          <DefaultProviderPolicy defaultSystemProvider="WATCH_BATTERY" defaultSystemProviderType="SHORT_TEXT"/>
          <Complication type="SHORT_TEXT">
            <PartDraw x="262" y="316" width="76" height="76">
              <Ellipse x="0" y="0" width="76" height="76"><Stroke color="#ff4fc3f7" thickness="8"/></Ellipse>
            </PartDraw>
            <PartText x="255" y="291" width="120" height="36">
              <Text><Font family="SYNC_TO_DEVICE" size="30" color="#ffffffff"><Template>100%</Template></Font></Text>
            </PartText>
          </Complication>
        </ComplicationSlot>
      </Scene>
    </WatchFace>
  """
    .trimIndent()

@Composable
private fun ClippedFace(box: String, caption: String, modifier: Modifier) {
  val document =
    remember(box) {
      (WatchFaceXmlImporter.import(clippedComplicationFace(box))
          as WatchFaceXmlImporter.Result.Imported)
        .document
    }
  Column(
    modifier,
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(Modifier.weight(1f)) { ProductionUiBuilderSurface(document) }
    Text(caption, style = MaterialTheme.typography.bodySmall)
  }
}

@Preview(widthDp = 640, heightDp = 380)
@Composable
fun WatchFaceClippedComplicationPreview() = MaterialTheme {
  Surface(Modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxSize().padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      ClippedFace(
        """x="252" y="306" width="96" height="96"""",
        "Label outside the 96×96 box: cut, as on the watch",
        Modifier.weight(1f).fillMaxHeight(),
      )
      ClippedFace(
        """x="240" y="280" width="140" height="130"""",
        "Box grown to cover it: whole",
        Modifier.weight(1f).fillMaxHeight(),
      )
    }
  }
}
