package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * The native pane's live half: the streamed frame, and the taps that reach the screen drawing it.
 *
 * The socket itself is the host's and is not under test here — what is, is the contract the editor
 * holds it to. A frame replaces the still, a press that does not move arrives as one `click` in the
 * frame's own pixels, and the caption says which of the two you are looking at, because "is this
 * live" is otherwise only discoverable by poking the screen and seeing whether it answers.
 */
@OptIn(ExperimentalTestApi::class)
class NativeLivePaneTest {

  /** A stream a test drives directly: no socket, and every input it was sent, in order. */
  private class FakeStream(width: Int = 40, height: Int = 20) : UiBuilderNativeStream {
    val sent = mutableListOf<UiBuilderNativeInput>()
    var closes = 0
    // Filled rather than blank: a blank `ImageBitmap` is transparent, which composites to the
    // pane's own surface colour and is indistinguishable from a stream that never painted.
    private val painted =
      ImageBitmap(width, height).also { bitmap ->
        Canvas(bitmap)
          .drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint().apply { color = FRAME_COLOR },
          )
      }

    override var frame: UiBuilderNativeFrame? by mutableStateOf(null)

    override var failure: String? by mutableStateOf(null)

    fun paint(sequence: Long = 0) {
      frame = UiBuilderNativeFrame(painted, sequence)
    }

    override fun send(input: UiBuilderNativeInput) {
      sent += input
    }

    override fun close() {
      closes++
    }
  }

  private companion object {
    /** Not a Material colour: nothing else in the pane can produce it by accident. */
    val FRAME_COLOR = Color(0xFF12E3A2)
  }

  private fun blank(designId: String) =
    blankUiBuilderDocument(designId, JsonObject(emptyMap()), JsonObject(emptyMap()))

  private fun catalog() =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )

  @Test
  fun `a streamed frame replaces the still and says it is live`() =
    runDesktopComposeUiTest(
      width = 1400,
      height = 900,
    ) {
      val stream = FakeStream()
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = blank("live-pane"),
            catalog = catalog(),
            initialPanes = setOf(EditorPane.Native),
            initialNativeRender =
              UiBuilderNativeRender(live = UiBuilderNativeLive("session", "preview")),
            onRequestNativeRender = {
              UiBuilderNativeRender(live = UiBuilderNativeLive("session", "preview"))
            },
            onOpenNativeStream = { stream },
          )
        }
      }
      // Until a frame lands the pane is honest about what it has: a session it is talking to, not a
      // picture. "Compiling this design…" would be the wrong sentence — the compile is done.
      // `m3-catalog` declares no native backend, so the default — desktop — is what it names. The
      // point is that it names one rather than saying "compiling".
      onNodeWithText("Native · connecting to desktop…", substring = true).assertExists()

      runOnIdle { stream.paint() }
      waitForIdle()
      onNodeWithText("taps reach the screen", substring = true).assertExists()
      onNodeWithContentDescription("Live native preview").assertExists()
    }

  @Test
  fun `a press that does not move arrives as one click in the frame's own pixels`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      val stream = FakeStream(width = 40, height = 20)
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = blank("live-input"),
            catalog = catalog(),
            initialPanes = setOf(EditorPane.Native),
            initialNativeRender =
              UiBuilderNativeRender(live = UiBuilderNativeLive("session", "preview")),
            onRequestNativeRender = {
              UiBuilderNativeRender(live = UiBuilderNativeLive("session", "preview"))
            },
            onOpenNativeStream = { stream },
          )
        }
      }
      runOnIdle { stream.paint() }
      waitForIdle()
      val target = onNodeWithContentDescription("Live native preview")
      target.performTouchInput { click(center) }
      waitForIdle()

      // One event, not a down/up pair: the daemon's click fast-path renders between press and
      // release, so a batched pair can race `Modifier.clickable` and land as nothing.
      assertEquals(listOf("click"), stream.sent.map { it.kind })
      val click = stream.sent.single()
      // The middle of the frame, in the frame's pixels rather than the pane's dp — the pane is far
      // bigger than 40×20, so a coordinate that was not converted would be wildly out of range.
      assertTrue(click.pixelX in 0 until 40, "x ${click.pixelX} is outside the frame")
      assertTrue(click.pixelY in 0 until 20, "y ${click.pixelY} is outside the frame")
      assertEquals(20 to 10, click.pixelX to click.pixelY)
    }

  @Test
  fun `closing the native pane releases the session`() =
    runDesktopComposeUiTest(
      width = 1400,
      height = 900,
    ) {
      val stream = FakeStream()
      // The editor reads `initialPanes` once, so the pane set cannot be driven from outside it.
      // What is under test is the effect's `onDispose`, and leaving the composition is what runs
      // it — which is also what a closed tab and a navigated-away editor do.
      var open by mutableStateOf(true)
      setContent {
        MaterialTheme {
          if (open) {
            UiBuilderEditor(
              document = blank("live-close"),
              catalog = catalog(),
              initialPanes = setOf(EditorPane.Native),
              initialNativeRender =
                UiBuilderNativeRender(live = UiBuilderNativeLive("session", "preview")),
              onRequestNativeRender = {
                UiBuilderNativeRender(live = UiBuilderNativeLive("session", "preview"))
              },
              onOpenNativeStream = { stream },
            )
          }
        }
      }
      runOnIdle { stream.paint() }
      waitForIdle()
      assertEquals(0, stream.closes)

      runOnIdle { open = false }
      waitForIdle()
      // A held Android daemon is the most expensive thing this editor can leave running, so the
      // socket closing with the pane is not a tidiness detail.
      assertTrue(stream.closes >= 1, "the live session outlived the pane")
    }

  @Test
  fun `the caption names the backend the catalog declared`() {
    // "Native" is a claim about a toolkit; which one is the whole point of the pane.
    assertEquals(
      "Native · live on Android · taps reach the screen",
      nativePaneCaption(live = true, connecting = true, backend = "android"),
    )
    assertEquals(
      "Native · connecting to Android…",
      nativePaneCaption(live = false, connecting = true, backend = "android"),
    )
    assertEquals(
      "Native · live on desktop · taps reach the screen",
      nativePaneCaption(live = true, connecting = true, backend = "desktop"),
    )
    // No stream at all is the state this pane has always had, and it keeps the words it had.
    assertEquals(
      "Native render · compiled on the host",
      nativePaneCaption(live = false, connecting = false, backend = "android"),
    )
  }

  @Test
  fun `a stream failure is reported rather than left as an empty pane`() =
    runDesktopComposeUiTest(
      width = 1400,
      height = 900,
    ) {
      val stream = FakeStream()
      stream.failure = "the live native session closed before it drew a frame"
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = blank("live-failure"),
            catalog = catalog(),
            initialPanes = setOf(EditorPane.Native),
            initialNativeRender =
              UiBuilderNativeRender(live = UiBuilderNativeLive("session", "preview")),
            onRequestNativeRender = {
              UiBuilderNativeRender(live = UiBuilderNativeLive("session", "preview"))
            },
            onOpenNativeStream = { stream },
          )
        }
      }
      onNodeWithText("closed before it drew a frame", substring = true).assertExists()
    }

  @Test
  fun `a host with no stream seam keeps the still it always drew`() =
    runDesktopComposeUiTest(
      width = 1400,
      height = 900,
    ) {
      val still = ImageBitmap(8, 8)
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = blank("still-only"),
            catalog = catalog(),
            initialPanes = setOf(EditorPane.Native),
            initialNativeRender = UiBuilderNativeRender(image = still),
            onRequestNativeRender = { UiBuilderNativeRender(image = still) },
          )
        }
      }
      onNodeWithText("Native render · compiled on the host").assertExists()
      // And no live affordance is advertised, so nobody learns to tap a picture.
      onNodeWithContentDescription("Live native preview").assertDoesNotExist()
    }

  @Test
  fun `the streamed frame is what the pane paints`() =
    runDesktopComposeUiTest(
      width = 1400,
      height = 900,
    ) {
      val stream = FakeStream(width = 40, height = 20)
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = blank("live-pixels"),
            catalog = catalog(),
            initialPanes = setOf(EditorPane.Native),
            initialNativeRender =
              UiBuilderNativeRender(live = UiBuilderNativeLive("session", "preview")),
            onRequestNativeRender = {
              UiBuilderNativeRender(live = UiBuilderNativeLive("session", "preview"))
            },
            onOpenNativeStream = { stream },
          )
        }
      }
      runOnIdle { stream.paint() }
      waitForIdle()
      val pixels = onNodeWithContentDescription("Live native preview").captureToImage().toPixelMap()
      assertTrue(pixels.width > 0 && pixels.height > 0)
      // The stream's own pixels, not the pane's surface under a transparent frame: this is the
      // assertion that the streamed image is the thing being drawn.
      assertEquals(FRAME_COLOR, pixels[pixels.width / 2, pixels.height / 2])
    }

  @Test
  fun `a stream failure does not blank a still that arrived`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      val stream = FakeStream()
      stream.failure = "live preview at capacity — try again shortly"
      val still = ImageBitmap(8, 8)
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = blank("live-degraded"),
            catalog = catalog(),
            initialPanes = setOf(EditorPane.Native),
            initialNativeRender =
              UiBuilderNativeRender(image = still, live = UiBuilderNativeLive("s", "p")),
            onRequestNativeRender = {
              UiBuilderNativeRender(image = still, live = UiBuilderNativeLive("s", "p"))
            },
            onOpenNativeStream = { stream },
          )
        }
      }
      // The live lane is the optional half of this pane. A full live-seat budget, or a grant
      // without live scope, must not take a compiled frame that arrived perfectly well with it.
      onNodeWithText("live preview at capacity", substring = true).assertDoesNotExist()
      // And the caption stops claiming to be connecting to something that has given up.
      onNodeWithText("Native render · compiled on the host").assertExists()
    }
}
