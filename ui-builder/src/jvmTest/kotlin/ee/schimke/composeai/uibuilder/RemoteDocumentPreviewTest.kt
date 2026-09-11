package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@OptIn(ExperimentalTestApi::class)
class RemoteDocumentPreviewTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val directory =
    File(
      System.getProperty("uiBuilderProjectDir"),
      "../docs/design/evidence/ui-builder-live-document-preview",
    )

  private fun document(stem: String = "sample") =
    json.decodeFromString<UiBuilderDocument>(File(directory, "$stem.document.json").readText())

  private fun ready(revision: Int = 0, stem: String = "sample") =
    UiBuilderDocumentPreview.Ready(
      revision,
      Base64.encode(File(directory, "$stem.rc").readBytes()),
    )

  @Test
  fun `Preview in the existing editor plays exported state and click actions`() {
    assertPlayback("sample")
  }

  @Test
  fun `a density-2 export keeps its displayed size and click coordinates`() {
    assertPlayback("sample-density-2")
  }

  @Test
  fun `unsaved current content plays without claiming a saved revision`() {
    assertPlayback("sample", saved = false)
  }

  private fun assertPlayback(stem: String, saved: Boolean = true) {
    val report =
      decodeRemoteComposeDocument(ready(stem = stem).documentBase64)
        .getOrThrow()
        .composeSupportReport()
    val legacyEmptyBox =
      report.issues.any { it.detail.contains("RcBoxLayout requires LayoutComponentContent") }
    if (System.getenv("VERIFY_REMOTE_DOCUMENT_PREVIEW") == "true") {
      assertTrue(report.fullyRenderable, report.issues.toString())
    }
    org.junit.Assume.assumeFalse(
      "Stage rc-players#92 to play the legal empty-Box fixture",
      legacyEmptyBox,
    )
    assertTrue(report.fullyRenderable, report.issues.toString())
    runDesktopComposeUiTest(width = 1200, height = 1000) {
      val catalog =
        CapabilityCatalogParser.parse(
          checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
        )
      var requested: UiBuilderDocument? = null
      setContent {
        UiBuilderEditor(
          document(stem),
          catalog,
          initialPreviewMode = true,
          onRequestDocumentPreview = {
            requested = it
            ready(it.revision, stem).copy(saved = saved)
          },
        )
      }
      onNodeWithText(if (saved) "Live preview · revision 0" else "Live preview · unsaved changes")
        .assertExists()
      val target = onNodeWithContentDescription("Interactive document preview")
      assertEquals(360f, target.fetchSemanticsNode().boundsInRoot.width)
      assertEquals(360f, target.fetchSemanticsNode().boundsInRoot.height)
      fun color(expected: Color) {
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        val pixels = target.captureToImage().toPixelMap()
        val actual = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue(
          abs(actual.red - expected.red) < .06f &&
            abs(actual.green - expected.green) < .06f &&
            abs(actual.blue - expected.blue) < .06f,
          "expected $expected, got $actual",
        )
      }
      color(Color(0xff6750a4))
      target.performTouchInput { click() }
      color(Color(0xff008577))
      target.performTouchInput { click() }
      color(Color(0xff3949ab))
      target.performTouchInput { click() }
      color(Color(0xff6750a4))
      assertEquals(document(stem), requested)
    }
  }

  @Test
  fun `a pending save retries when the authoritative generation advances`() =
    runDesktopComposeUiTest {
      var generation by mutableIntStateOf(0)
      var calls = 0
      setContent {
        MaterialTheme {
          RemoteDocumentPreviewPane(
            document(),
            generation,
            {
              calls++
              if (generation == 0) UiBuilderDocumentPreview.WaitingForSave
              else UiBuilderDocumentPreview.Failed("Located export diagnostic")
            },
            Modifier.fillMaxSize(),
          )
        }
      }
      onNodeWithText("Waiting for changes to be saved…").assertExists()
      runOnIdle { generation++ }
      onNodeWithText("Located export diagnostic").assertExists()
      assertEquals(2, calls)
    }

  @Test
  fun `the additional interactive pane uses the document preview host too`() =
    runDesktopComposeUiTest(width = 1600, height = 1000) {
      val catalog =
        CapabilityCatalogParser.parse(
          checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
        )
      var inspection: UiBuilderInspectionSnapshot? = null
      setContent {
        UiBuilderEditor(
          document(),
          catalog,
          initialPreviewSurface = EditorPreviewSurface.Both,
          onInspectionSnapshot = { inspection = it },
          onRequestDocumentPreview = {
            UiBuilderDocumentPreview.Failed("Compiled preview requested")
          },
        )
      }
      if (UiBuilderBuildFeatures.remoteCompose)
        onNodeWithText("Compiled preview requested").assertExists()
      else onNodeWithText("Compiled preview requested").assertDoesNotExist()
      assertNotNull(inspection?.nodes?.firstOrNull { it.nodeId == "choice" }?.bounds)
    }

  @Test
  fun `a late response cannot replace a newer revision`() = runDesktopComposeUiTest {
    val late = CompletableDeferred<UiBuilderDocumentPreview>()
    var current by mutableStateOf(document())
    setContent {
      MaterialTheme {
        RemoteDocumentPreviewPane(
          current,
          0,
          { requested ->
            if (requested.revision == 0) withContext(NonCancellable) { late.await() }
            else UiBuilderDocumentPreview.Failed("Current revision diagnostic")
          },
          Modifier.fillMaxSize(),
        )
      }
    }
    onNodeWithText("Preparing preview…").assertExists()
    runOnIdle { current = current.copy(revision = 1) }
    onNodeWithText("Current revision diagnostic").assertExists()
    runOnIdle { late.complete(UiBuilderDocumentPreview.Failed("Stale diagnostic")) }
    onNodeWithText("Current revision diagnostic").assertExists()
    onNodeWithText("Stale diagnostic").assertDoesNotExist()
  }

  @Test
  fun `a response with the wrong revision is refused`() = runDesktopComposeUiTest {
    setContent { RemoteDocumentPreviewPane(document(), 0, { ready(2) }, Modifier.fillMaxSize()) }
    onNodeWithText("Preview returned a different revision").assertExists()
    onNodeWithContentDescription("Interactive document preview").assertDoesNotExist()
  }
}
