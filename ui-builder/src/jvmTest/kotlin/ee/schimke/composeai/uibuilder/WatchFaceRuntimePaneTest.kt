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
import ee.schimke.composeai.uibuilder.editor.UiBuilderWatchFaceStage
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
    override var stage: UiBuilderWatchFaceStage? by mutableStateOf(null)

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
  fun `design edits never start a session, they offer a Refresh that does`() =
    runDesktopComposeUiTest(width = 420, height = 560) {
      val streams = mutableListOf<FakeStream>()
      var revision by mutableStateOf(3)
      var starts = 0
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 560.dp)) {
            WatchFaceRuntimePane(
              wffXml = { "<WatchFace revision=\"$revision\"/>" },
              start = {
                starts++
                val stream = FakeStream().also(streams::add)
                stream.frame = UiBuilderNativeFrame(solid(Color(0xFF12E3A2)), 1)
                UiBuilderWatchFaceStart(pagePath = "/tab", stream = stream)
              },
              status = { null },
              revision = revision,
            )
          }
        }
      }
      waitForIdle()
      assertEquals(1, starts)
      onNodeWithContentDescription("Design changed").assertDoesNotExist()
      // Several edits, and long enough for any debounce or idle timer to have fired.
      repeat(5) {
        revision++
        waitForIdle()
      }
      mainClock.advanceTimeBy(120_000)
      waitForIdle()
      assertEquals(1, starts, "an edit must not start, package or install a session")
      assertEquals(0, streams.single().closes)
      onNodeWithContentDescription("Design changed").assertExists()
      save("native-stale.png", onRoot().captureToImage())

      onNodeWithText("Refresh").performClick()
      waitForIdle()
      assertEquals(2, starts)
      assertEquals(1, streams.first().closes)
      onNodeWithContentDescription("Design changed").assertDoesNotExist()
    }

  @Test
  fun `the host's stale verdict wins over the revision compare`() =
    runDesktopComposeUiTest(width = 420, height = 560) {
      var revision by mutableStateOf(3)
      var hostSays: Boolean? = false
      var asked = 0
      var starts = 0
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 560.dp)) {
            WatchFaceRuntimePane(
              wffXml = { "<WatchFace/>" },
              start = {
                starts++
                UiBuilderWatchFaceStart(
                  pagePath = "/tab",
                  stream = FakeStream(),
                  staleness = {
                    asked++
                    hostSays
                  },
                )
              },
              status = { null },
              revision = revision,
            )
          }
        }
      }
      waitForIdle()
      revision++
      mainClock.advanceTimeBy(5_000)
      waitForIdle()
      assertEquals(1, asked)
      // A selection-only revision bump the host does not count: no note.
      onNodeWithContentDescription("Design changed").assertDoesNotExist()
      hostSays = true
      revision++
      mainClock.advanceTimeBy(5_000)
      waitForIdle()
      onNodeWithContentDescription("Design changed").assertExists()
      assertEquals(1, starts)
    }

  @Test
  fun `the optional idle refresh happens once, after 30 s without an edit`() =
    runDesktopComposeUiTest(width = 420, height = 560) {
      var revision by mutableStateOf(1)
      var starts = 0
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 560.dp)) {
            WatchFaceRuntimePane(
              wffXml = { "<WatchFace/>" },
              start = {
                starts++
                UiBuilderWatchFaceStart(pagePath = "/tab", stream = FakeStream())
              },
              status = { null },
              revision = revision,
              autoRefreshWhenIdle = true,
            )
          }
        }
      }
      waitForIdle()
      assertEquals(1, starts)
      revision++
      mainClock.advanceTimeBy(20_000)
      // Still editing: the timer starts again.
      revision++
      mainClock.advanceTimeBy(20_000)
      waitForIdle()
      assertEquals(1, starts)
      mainClock.advanceTimeBy(15_000)
      waitForIdle()
      assertEquals(2, starts)
      revision++
      mainClock.advanceTimeBy(120_000)
      waitForIdle()
      assertEquals(2, starts, "one gentle refresh, not one per edit")
      onNodeWithContentDescription("Design changed").assertExists()
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

  @Test
  fun `an async start shows each stage the host reports, then the frame`() =
    runDesktopComposeUiTest(width = 420, height = 520) {
      val stream = FakeStream()
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 520.dp)) {
            WatchFaceRuntimePane(
              wffXml = { "<WatchFace/>" },
              start = {
                UiBuilderWatchFaceStart(
                  pagePath = "/tab",
                  stream = stream,
                  stage =
                    UiBuilderWatchFaceStage(
                      stage = UiBuilderWatchFaceStage.QUEUED,
                      label = "Waiting for a free runtime (2 ahead)",
                      queueAhead = 2,
                    ),
                )
              },
              status = { null },
            )
          }
        }
      }
      waitForIdle()
      onNodeWithText("Waiting for a free runtime (2 ahead)").assertExists()
      onNodeWithText("Queued · 2 sessions ahead").assertExists()
      // Available from the 202 on, before any frame.
      onNodeWithText("Open in a tab").assertExists()
      stream.stage =
        UiBuilderWatchFaceStage(
          stage = UiBuilderWatchFaceStage.PACKAGING,
          step = 2,
          label = "Using the cached package",
          elapsedMillis = 14_000,
          cachedPackage = true,
        )
      waitForIdle()
      onNodeWithText("Using the cached package").assertExists()
      onNodeWithText("Step 2 of 5 · cached package").assertExists()
      onNodeWithText("14 s", substring = true).assertExists()
      stream.stage =
        UiBuilderWatchFaceStage(
          stage = UiBuilderWatchFaceStage.LIVE,
          step = 5,
          label = "Live",
          elapsedMillis = 41_000,
          frameIntervalMillis = 250,
        )
      stream.frame = UiBuilderNativeFrame(solid(Color(0xFF12E3A2)), 1)
      waitForIdle()
      onNodeWithContentDescription("Watch face on the real runtime").assertExists()
      onNodeWithText("Live · ready in 41 s · redraws every 250 ms").assertExists()
    }

  @Test
  fun `a failed stage shows the packager's reason verbatim and no socket error over it`() =
    runDesktopComposeUiTest(width = 420, height = 520) {
      val reason =
        "aapt2: error: res/raw/watchface.xml:12: <PartText> width=\"-4\" is not a valid dimension"
      val stream = FakeStream()
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 520.dp)) {
            WatchFaceRuntimePane(
              wffXml = { "<WatchFace/>" },
              start = { UiBuilderWatchFaceStart(pagePath = "/tab", stream = stream) },
              status = { null },
            )
          }
        }
      }
      waitForIdle()
      stream.stage =
        UiBuilderWatchFaceStage(
          stage = UiBuilderWatchFaceStage.FAILED,
          step = 2,
          label = "Packaging failed",
          code = "DWF_PACKAGING_FAILED",
          reason = reason,
        )
      stream.failure = "the live native lane failed"
      waitForIdle()
      onNodeWithText("Failed at step 2 of 5: Package").assertExists()
      onNodeWithText("DWF_PACKAGING_FAILED").assertExists()
      onNodeWithText(reason).assertExists()
      onNodeWithText("the live native lane failed").assertDoesNotExist()
    }

  @Test
  fun `a reload and a closed pane release the session they end`() =
    runDesktopComposeUiTest(width = 420, height = 460) {
      val releases = mutableListOf<Int>()
      var calls = 0
      var open by mutableStateOf(true)
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 460.dp)) {
            if (open)
              WatchFaceRuntimePane(
                wffXml = { "<WatchFace/>" },
                start = {
                  val session = calls++
                  UiBuilderWatchFaceStart(
                    pagePath = "/tab",
                    stream = FakeStream(),
                    release = { releases += session },
                  )
                },
                status = { null },
              )
          }
        }
      }
      waitForIdle()
      assertEquals(emptyList(), releases)
      onNodeWithText("Reload with the current design").performClick()
      waitForIdle()
      assertEquals(listOf(0), releases)
      open = false
      waitForIdle()
      assertEquals(listOf(0, 1), releases)
    }

  @Test
  fun `a closed pane starts nothing`() =
    runDesktopComposeUiTest(width = 420, height = 460) {
      var starts = 0
      var statuses = 0
      val open by mutableStateOf(false)
      setContent {
        MaterialTheme {
          if (open)
            WatchFaceRuntimePane(
              wffXml = { "<WatchFace/>" },
              start = {
                starts++
                UiBuilderWatchFaceStart()
              },
              status = {
                statuses++
                null
              },
            )
        }
      }
      waitForIdle()
      assertEquals(0 to 0, starts to statuses)
    }

  @Test
  fun `stages parse from the start's body and the socket's messages`() {
    val started =
      UiBuilderWatchFaceStage.of(
        """{"sessionId":"s1","socketPath":"/ws","pagePath":"/tab","statusPath":"/st","stage":"queued","step":0,"steps":5,"label":"Waiting for a free runtime (next in line)","queueAhead":0}""",
      )
    assertEquals(UiBuilderWatchFaceStage.QUEUED, started?.stage)
    assertEquals(0, started?.queueAhead)
    val message =
      UiBuilderWatchFaceStage.of(
        """{"type":"stage","stage":"failed","step":2,"steps":5,"label":"x","elapsedMillis":9100,"code":"DWF_PACKAGING_FAILED","reason":"line 1\nline 2","cachedPackage":false}""",
      )
    assertEquals(2, message?.step)
    assertEquals(9100L, message?.elapsedMillis)
    assertEquals("line 1\nline 2", message?.reason)
    // An older host's 201 states no stage.
    assertEquals(null, UiBuilderWatchFaceStage.of("""{"pagePath":"/tab","socketPath":"/ws"}"""))
  }

  @Test
  fun `a host with native preview switched off greys the pane and never starts a session`() =
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
              status = { "native_preview_disabled" },
            )
          }
        }
      }
      waitForIdle()
      assertEquals(0, starts)
      onNodeWithContentDescription("Native preview is turned off").assertExists()
      onNodeWithText("Reload with the current design").assertDoesNotExist()
    }

  @Test
  fun `a start the host refuses as switched off shows the same turned-off state`() =
    runDesktopComposeUiTest(width = 420, height = 360) {
      setContent {
        MaterialTheme {
          Box(Modifier.size(420.dp, 360.dp)) {
            WatchFaceRuntimePane(
              wffXml = { "<WatchFace/>" },
              start = { UiBuilderWatchFaceStart(disabled = true) },
              status = { null },
            )
          }
        }
      }
      waitForIdle()
      onNodeWithContentDescription("Native preview is turned off").assertExists()
      onNodeWithText("Reload with the current design").assertDoesNotExist()
    }
}
