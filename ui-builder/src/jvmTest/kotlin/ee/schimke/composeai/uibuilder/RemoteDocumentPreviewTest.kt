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
import ee.schimke.composeai.uibuilder.protocol.BrowserPreviewCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
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
    // The interactive document pane is opt-in build surface: `UiBuilderEditor` mounts it only when
    // `UiBuilderBuildFeatures.remoteCompose` is set, so in a shipping build there is no
    // "Live preview · …" label to find and all three cases fail on a missing node rather than on
    // anything they mean to assert. The `.rc` fixtures here are pre-recorded, so nothing else in
    // this file needed the feature — which is why it never got the guard the export tests carry.
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
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
      var requested: UiBuilderDocument? = null
      val source = document(stem)
      val pane =
        UiBuilderVariantPane(
          id = "fixture",
          label = "Fixture",
          widthDp = 360f,
          heightDp = 360f,
          document = source,
        )
      setContent {
        MaterialTheme {
          RemoteDocumentDesignPreviewPane(
            document = source,
            variants = listOf(pane),
            authoritativeGeneration = 0,
            request = {
              requested = it
              ready(it.revision, stem).copy(saved = saved)
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
      mainClock.advanceTimeBy(300)
      waitForIdle()
      val target = onNodeWithContentDescription("Remote document preview · Fixture")
      target.assertExists()
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
  fun `the previous document stays visible while its replacement is captured`() =
    runDesktopComposeUiTest(width = 1000, height = 800) {
      val replacement = CompletableDeferred<UiBuilderDocumentPreview>()
      var current by mutableStateOf(document())
      setContent {
        val pane =
          UiBuilderVariantPane(
            id = "stable-pane",
            label = "Stable",
            widthDp = 360f,
            heightDp = 360f,
            document = current,
          )
        MaterialTheme {
          RemoteDocumentDesignPreviewPane(
            document = current,
            variants = listOf(pane),
            authoritativeGeneration = 0,
            request = { requested ->
              if (requested.revision == 0) ready(0)
              else withContext(NonCancellable) { replacement.await() }
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
      mainClock.advanceTimeBy(300)
      waitForIdle()
      onNodeWithContentDescription("Remote document preview · Stable").assertExists()
      onNodeWithText("Preparing preview…").assertDoesNotExist()

      runOnIdle { current = current.copy(revision = 1) }

      onNodeWithText("Updating Remote preview…").assertExists()
      onNodeWithText("Preparing preview…").assertDoesNotExist()
      runOnIdle { replacement.complete(ready(1)) }
      waitUntil { onAllNodesWithText("Updating Remote preview…").fetchSemanticsNodes().isEmpty() }
    }

  @Test
  fun `all widget hosts share one captured content document`() = runDesktopComposeUiTest {
    val source = document()
    val widget =
      source.copy(
        roots = listOf("widget-root"),
        nodes =
          mapOf(
            "widget-root" to
              UiBuilderNode(
                id = "widget-root",
                componentId = "remote-m3/widget-container-small",
              )
          ),
      )
    var calls = 0
    setContent {
      MaterialTheme {
        RemoteDocumentDesignPreviewPane(
          document = widget,
          variants = emptyList(),
          authoritativeGeneration = 0,
          request = {
            calls++
            UiBuilderDocumentPreview.Failed("Captured once")
          },
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
    mainClock.advanceTimeBy(300)
    waitForIdle()
    onNodeWithText("Pixel Watch").assertExists()
    onNodeWithText("Samsung").assertExists()
    onNodeWithText("Rectangular").assertExists()
    runOnIdle { assertEquals(1, calls) }
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
  fun `the catalog routes Browser Preview to document playback and leaves Native explicit`() =
    runDesktopComposeUiTest(width = 1600, height = 1000) {
      val base =
        CapabilityCatalogParser.parse(
          checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
        )
      val catalog =
        base.copy(
          browserPreview =
            BrowserPreviewCapabilityV1.Builder(
                BrowserPreviewCapabilityV1.REMOTE_COMPOSE_DOCUMENT_RENDERER
              )
              .also { it.format = ExportFormatV1.RC }
              .build()
        )
      var documentRequests = 0
      var nativeRequests = 0
      setContent {
        UiBuilderEditor(
          document(),
          catalog,
          initialPanes = setOf(EditorPane.Preview),
          initialVariantAxes = setOf(EditorVariantAxis.Dark),
          onRequestDocumentPreview = {
            documentRequests++
            UiBuilderDocumentPreview.Failed("Compiled preview requested")
          },
          onRequestNativeRender = {
            nativeRequests++
            UiBuilderNativeRender(failure = "Native preview requested")
          },
        )
      }
      mainClock.advanceTimeBy(300)
      waitForIdle()
      onNodeWithText("Compiled preview requested").assertExists()
      runOnIdle {
        assertEquals(1, documentRequests)
        assertEquals(0, nativeRequests)
      }
    }

  @Test
  fun `opening Native requests only the authoritative compile lane`() =
    runDesktopComposeUiTest(width = 1200, height = 900) {
      val base =
        CapabilityCatalogParser.parse(
          checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
        )
      val catalog =
        base.copy(
          browserPreview =
            BrowserPreviewCapabilityV1.Builder(
                BrowserPreviewCapabilityV1.REMOTE_COMPOSE_DOCUMENT_RENDERER
              )
              .also { it.format = ExportFormatV1.RC }
              .build()
        )
      var documentRequests = 0
      var nativeRequests = 0
      setContent {
        UiBuilderEditor(
          document(),
          catalog,
          initialPanes = setOf(EditorPane.Native),
          onRequestDocumentPreview = {
            documentRequests++
            UiBuilderDocumentPreview.Failed("Document lane should stay idle")
          },
          onRequestNativeRender = {
            nativeRequests++
            UiBuilderNativeRender(failure = "Authoritative native requested")
          },
        )
      }
      onNodeWithText("Authoritative native requested").assertExists()
      onNodeWithText("Document lane should stay idle").assertDoesNotExist()
      runOnIdle {
        assertEquals(0, documentRequests)
        assertEquals(1, nativeRequests)
      }
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
