package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeFrame
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeInput
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeStream
import ee.schimke.composeai.uibuilder.editor.UiBuilderWatchFaceStart
import ee.schimke.composeai.uibuilder.editor.WatchFaceRuntimePane
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The Native pane of a watch face: a host that cannot run the runtime says why in a banner, one
 * that can draws the session's frames inline and sends the ambient switch back as a `controls`
 * patch. The socket is the host's, so a fake stream stands in for it.
 */
@OptIn(ExperimentalTestApi::class)
class WatchFaceRuntimePaneTest {
  private class FakeStream : UiBuilderNativeStream {
    var closes = 0
    override var frame: UiBuilderNativeFrame? by mutableStateOf(null)
    override var failure: String? by mutableStateOf(null)

    override fun send(input: UiBuilderNativeInput) = Unit

    override fun close() {
      closes++
    }
  }

  private fun solid(color: Color): ImageBitmap =
    ImageBitmap(120, 120).also { bitmap ->
      Canvas(bitmap).drawRect(0f, 0f, 120f, 120f, Paint().apply { this.color = color })
    }

  private fun save(name: String, image: ImageBitmap) {
    File("build/evidence").apply {
      mkdirs()
      File(this, name)
        .writeBytes(
          checkNotNull(
              Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG),
            )
            .bytes,
        )
    }
  }

  @Test
  fun `a host without the runtime shows its reason as a banner and starts nothing`() =
    runDesktopComposeUiTest(width = 420, height = 360) {
      var starts = 0
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 360.dp)) {
            WatchFaceRuntimePane(
              wffXml = { "<WatchFace/>" },
              start = {
                starts++
                UiBuilderWatchFaceStart()
              },
              status = {
                "DWF_RUNTIME_MISSING: The Wear OS watch-face runtime (DeclarativeWatchFaceRuntime.apk) " +
                  "is not installed on this server."
              },
            )
          }
        }
      }
      waitForIdle()
      onNodeWithText("DWF_RUNTIME_MISSING", substring = true).assertExists()
      assertEquals(0, starts)
      save("native-banner.png", onRoot().captureToImage())
    }

  @Test
  fun `an available runtime draws the streamed frame and sends ambient back`() =
    runDesktopComposeUiTest(width = 420, height = 460) {
      val stream = FakeStream()
      val controls = mutableListOf<String>()
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 460.dp)) {
            WatchFaceRuntimePane(
              wffXml = { "<WatchFace/>" },
              start = {
                UiBuilderWatchFaceStart(
                  pagePath = "/ui-builder/face/watch-face?session=s1",
                  stream = stream,
                  sendControls = { controls += it },
                )
              },
              status = { null },
            )
          }
        }
      }
      waitForIdle()
      onNodeWithText("Waiting for the first frame…").assertExists()
      stream.frame = UiBuilderNativeFrame(solid(Color(0xFF12E3A2)), 1)
      waitForIdle()
      onNodeWithContentDescription("Watch face on the real runtime").assertExists()
      onNodeWithText("Open in a tab").assertExists()

      onNodeWithText("Ambient").performClick()
      waitForIdle()
      assertEquals(listOf("{\"ambient\":true}"), controls)
      onNodeWithText("Ambient").performClick()
      waitForIdle()
      assertEquals(listOf("{\"ambient\":true}", "{\"ambient\":false}"), controls)
    }

  @Test
  fun `a reload the export refuses still closes the stream it replaces`() =
    runDesktopComposeUiTest(width = 420, height = 460) {
      val stream = FakeStream()
      var exportable = true
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 460.dp)) {
            WatchFaceRuntimePane(
              wffXml = { if (exportable) "<WatchFace/>" else null },
              start = { UiBuilderWatchFaceStart(pagePath = "/tab", stream = stream) },
              status = { null },
            )
          }
        }
      }
      waitForIdle()
      assertEquals(0, stream.closes)
      exportable = false
      onNodeWithText("Reload with the current design").performClick()
      waitForIdle()
      assertEquals(1, stream.closes)
    }

  @Test
  fun `a reload is off until the status is known and while a start runs, so one stream is live`() =
    runDesktopComposeUiTest(width = 420, height = 460) {
      val status = kotlinx.coroutines.CompletableDeferred<String?>()
      val started = kotlinx.coroutines.CompletableDeferred<UiBuilderWatchFaceStart>()
      val streams = mutableListOf<FakeStream>()
      var starts = 0
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 460.dp)) {
            WatchFaceRuntimePane(
              wffXml = { "<WatchFace/>" },
              start = {
                starts++
                started.await()
              },
              status = { status.await() },
            )
          }
        }
      }
      waitForIdle()
      onNodeWithText("Reload with the current design").assertIsNotEnabled()
      assertEquals(0, starts)
      status.complete(null)
      waitForIdle()
      assertEquals(1, starts)
      // Still starting: the label says so and reload stays off.
      onNodeWithText("Starting…").assertIsNotEnabled()
      val stream = FakeStream().also(streams::add)
      started.complete(UiBuilderWatchFaceStart(pagePath = "/tab", stream = stream))
      waitForIdle()
      onNodeWithText("Reload with the current design").assertIsEnabled()
      assertEquals(1, starts)
      assertEquals(listOf(0), streams.map { it.closes })
    }

  @Test
  fun `while a reload starts, the previous session's ambient switch is gone`() =
    runDesktopComposeUiTest(width = 420, height = 460) {
      val second = kotlinx.coroutines.CompletableDeferred<UiBuilderWatchFaceStart>()
      val first = FakeStream()
      val controls = mutableListOf<String>()
      var calls = 0
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 460.dp)) {
            WatchFaceRuntimePane(
              wffXml = { "<WatchFace/>" },
              start = {
                if (calls++ == 0)
                  UiBuilderWatchFaceStart(
                    pagePath = "/tab",
                    stream = first,
                    sendControls = { controls += it },
                  )
                else second.await()
              },
              status = { null },
            )
          }
        }
      }
      waitForIdle()
      onNodeWithText("Ambient").assertExists()
      onNodeWithText("Reload with the current design").performClick()
      waitForIdle()
      assertEquals(1, first.closes)
      onNodeWithText("Ambient").assertDoesNotExist()
      assertEquals(emptyList(), controls)
    }
}
