package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import ee.schimke.composeai.uibuilder.editor.WATCH_INSTALLS_UNAVAILABLE
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationAnswer
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationController
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationFinding
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationHost
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationJson
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationPanel
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationReport
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationView
import ee.schimke.composeai.uibuilder.editor.WatchInstalls
import ee.schimke.composeai.uibuilder.editor.WatchInstallsAnswer
import ee.schimke.composeai.uibuilder.editor.WatchInstallsView
import ee.schimke.composeai.uibuilder.editor.formatBytes
import ee.schimke.composeai.uibuilder.editor.formatEpochMillis
import ee.schimke.composeai.uibuilder.editor.nodeForResource
import ee.schimke.composeai.uibuilder.editor.suggestion
import ee.schimke.composeai.uibuilder.editor.topContributors
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The Validate section against the shared contract's `watchface-validation` shapes. */
@OptIn(ExperimentalTestApi::class)
class WatchFaceValidationTest {
  @Test
  fun `the contract's report decodes, with numeric versions and unknown fields`() {
    val report =
      WatchFaceValidationJson.decodeFromString(WatchFaceValidationReport.serializer(), REPORT)
    assertEquals(12, report.revision)
    assertEquals("4", report.wffVersion)
    assertEquals(
      listOf("wff-schema", "memory-footprint", "push-validator"),
      report.checks.map { it.id },
    )
    val finding = report.checks[0].findings.single()
    assertEquals(41, finding.line)
    assertEquals(9, finding.column)
    assertEquals(11L * 1024 * 1024, report.checks[1].memory?.maxActiveBytes)
    assertEquals(false, report.checks[2].tokenIssued)
    assertEquals(listOf("manifest"), report.checks[2].passedChecks)
    assertEquals(1_795_081_320_000, report.ranAtEpochMillis)
    assertEquals("remove the angle", finding.suggestion)
    assertEquals(2, report.checks[1].memory?.contributors?.size)
  }

  @Test
  fun `the largest contributor leads and an oversized one is told its drawn size`() {
    val memory = report().checks[1].memory!!
    val top = topContributors(memory)
    assertEquals("drawable/background.png", top.first().resource)
    assertEquals("downscale to 450x450", top.first().suggestion())
    assertNull(top.last().suggestion())
  }

  @Test
  fun `a contributor selects the node whose resource it is`() {
    val image = { id: String, resource: String ->
      UiBuilderNode(
        id,
        "wff/part-image",
        // Typed, as saved designs store it (see the bitmap-analog fixture).
        properties =
          JsonObject(
            mapOf(
              "resource" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(resource)),
                ),
            ),
          ),
      )
    }
    val document =
      UiBuilderDocument(
        schema = "ui-builder-document/v1",
        id = "d",
        title = "Face",
        revision = 1,
        catalogPin = JsonObject(emptyMap()),
        environment = JsonObject(emptyMap()),
        stateVariables = JsonObject(emptyMap()),
        roots = listOf("dial", "bg"),
        nodes = mapOf("dial" to image("dial", "dial"), "bg" to image("bg", "background")),
      )
    assertEquals("bg", document.nodeForResource("drawable/background.png"))
    assertEquals("dial", document.nodeForResource("dial"))
    assertEquals("dial", document.nodeForResource("drawable/dial.png"))
    assertNull(document.nodeForResource("font_digits"))
  }

  @Test
  fun `a run time reads to the minute in UTC`() {
    assertEquals("2026-11-19 09:42 UTC", formatEpochMillis(1_795_081_320_000))
  }

  @Test
  fun `the contract's installs decode`() {
    val installs = WatchFaceValidationJson.decodeFromString(WatchInstalls.serializer(), INSTALLS)
    val install = installs.installs.single()
    assertEquals("Pixel Watch 3", install.deviceName)
    assertEquals(35, install.wearOsApi)
    assertEquals("12", install.version)
    assertEquals("not_requested", install.activation)
    assertNull(install.errorKind)
    assertEquals(1_795_081_500_000, install.atEpochMillis)
  }

  @Test
  fun `a 404 is not run, a run replaces it, and a refusal keeps the last report`() = runBlocking {
    val host = FakeHost()
    val controller = WatchFaceValidationController(host, this)
    controller.load(12)
    assertEquals(WatchFaceValidationView.NotRun, controller.validation.value)

    host.next = WatchFaceValidationAnswer.Ran(report())
    controller.run(12)
    assertEquals(listOf(12L), host.runs)
    val ran = assertIs<WatchFaceValidationView.Ran>(controller.validation.value)

    host.next = WatchFaceValidationAnswer.Refused("signed out")
    controller.run(12)
    val failed = assertIs<WatchFaceValidationView.Failed>(controller.validation.value)
    assertEquals("signed out", failed.reason)
    assertEquals(ran.report, failed.previous)
  }

  @Test
  fun `a read that lands after a newer run is dropped`() = runBlocking {
    val host = FakeHost()
    val gate = CompletableDeferred<WatchFaceValidationAnswer>()
    host.latestAnswer = { gate.await() }
    val controller = WatchFaceValidationController(host, this)
    val read = launch { controller.load(12) }
    yield()
    host.next = WatchFaceValidationAnswer.Ran(report())
    controller.run(12)
    // The read was out before the run started, and answers only now, with an older verdict.
    gate.complete(WatchFaceValidationAnswer.NeverRun)
    read.join()
    assertIs<WatchFaceValidationView.Ran>(controller.validation.value)
    Unit
  }

  @Test
  fun `a started run outlives the composition that asked for it`() = runBlocking {
    val host = FakeHost()
    host.next = WatchFaceValidationAnswer.Ran(report())
    val design = CoroutineScope(coroutineContext + Job())
    val controller = WatchFaceValidationController(host, design)
    controller.start(12)
    design.coroutineContext[Job]!!.children.forEach { it.join() }
    assertIs<WatchFaceValidationView.Ran>(controller.validation.value)
    design.cancel()
  }

  @Test
  fun `a server with validation switched off hides the button`() = runBlocking {
    val host = FakeHost()
    host.latestAnswer = { WatchFaceValidationAnswer.Disabled("off here") }
    val controller = WatchFaceValidationController(host, this)
    controller.load(12)
    assertEquals(WatchFaceValidationView.Unavailable("off here"), controller.validation.value)
  }

  @Test
  fun `a local design never reaches the host and says why`() = runBlocking {
    val host = FakeHost(unavailableReason = "kept in this browser")
    val controller = WatchFaceValidationController(host, this)
    controller.load(3)
    controller.run(3)
    controller.loadInstalls()
    assertEquals(
      WatchFaceValidationView.Unavailable("kept in this browser"),
      controller.validation.value,
    )
    assertEquals(
      WatchInstallsView.Unavailable(WATCH_INSTALLS_UNAVAILABLE),
      controller.installs.value,
    )
    assertTrue(host.runs.isEmpty() && host.reads == 0)
  }

  @Test
  fun `failed findings are open and a located one opens the XML`() = runComposeUiTest {
    val opened = mutableListOf<WatchFaceValidationFinding>()
    val report =
      WatchFaceValidationJson.decodeFromString(WatchFaceValidationReport.serializer(), REPORT)
    setContent {
      MaterialTheme {
        WatchFaceValidationPanel(
          validation = WatchFaceValidationView.Ran(report),
          installs = WatchInstallsView.Listed(emptyList()),
          revision = 13,
          onRun = {},
          onOpenFinding = { opened += it },
        )
      }
    }
    onNodeWithText("2 checks failed on revision 12").assertExists()
    onNodeWithText("Shown: revision 12", substring = true).assertExists()
    onNodeWithText("Push validator: token issued — no").assertExists()
    onNodeWithText("over by", substring = true).assertExists()
    onNodeWithText("Error · line 41:9", substring = true).performClick()
    assertEquals(41, opened.single().line)
  }

  @Test
  fun `a watch a revision behind says so`() = runComposeUiTest {
    val installs = WatchFaceValidationJson.decodeFromString(WatchInstalls.serializer(), INSTALLS)
    setContent {
      MaterialTheme {
        WatchFaceValidationPanel(
          validation = WatchFaceValidationView.NotRun,
          installs = WatchInstallsView.Listed(installs.installs),
          revision = 14,
          onRun = {},
          onOpenFinding = {},
        )
      }
    }
    onNodeWithText("Behind").assertExists()
    onNodeWithText("Revision 12; the design is at 14 · not activated").assertExists()
  }

  @Test
  fun `bytes read as the limits are quoted`() {
    assertEquals("2 MB", formatBytes(2L * 1024 * 1024))
    assertEquals("2.6 MB", formatBytes(2L * 1024 * 1024 + 600 * 1024))
    assertEquals("512 KB", formatBytes(512L * 1024))
    assertEquals("12 B", formatBytes(12))
  }

  private class FakeHost(override val unavailableReason: String? = null) : WatchFaceValidationHost {
    var next: WatchFaceValidationAnswer = WatchFaceValidationAnswer.NeverRun
    val runs = mutableListOf<Long>()
    var reads = 0
    var latestAnswer: suspend () -> WatchFaceValidationAnswer = {
      WatchFaceValidationAnswer.NeverRun
    }

    override suspend fun latest(revision: Long): WatchFaceValidationAnswer {
      reads++
      return latestAnswer()
    }

    override suspend fun run(revision: Long): WatchFaceValidationAnswer {
      runs += revision
      return next
    }

    override suspend fun installs(): WatchInstallsAnswer = WatchInstallsAnswer.Listed(emptyList())
  }

  private fun report() =
    WatchFaceValidationJson.decodeFromString(WatchFaceValidationReport.serializer(), REPORT)

  private companion object {
    val REPORT =
      """
      {"designId":"d1","revision":12,"digest":"sha256:ab","ranAtEpochMillis":1795081320000,
       "schemaVersion":"1.4.0","wffVersion":4,"minimumWffVersion":2,"passed":false,
       "futureField":{"x":1},
       "checks":[
        {"id":"wff-schema","title":"WFF schema","status":"failed",
         "findings":[{"severity":"error","message":"bad attribute","line":41,"column":9,
                      "path":"/WatchFace/Scene","suggestion":"remove the angle"}]},
        {"id":"memory-footprint","title":"Memory footprint","status":"failed","findings":[],
         "memory":{"totalBytes":3000000,"maxActiveBytes":11534336,"maxAmbientBytes":1000000,
                   "activeLimitBytes":10485760,"ambientLimitBytes":2097152,
                   "contributors":[
                     {"resource":"small","activeBytes":1000},
                     {"resource":"drawable/background.png","kind":"image","width":900,
                      "height":900,"format":"PNG","activeBytes":3240000,
                      "renderedMaxWidth":450,"renderedMaxHeight":450}]}},
        {"id":"push-validator","title":"Push validator","status":"passed","findings":[],
         "passedChecks":["manifest"],"tokenIssued":false}
       ]}
      """
        .trimIndent()

    val INSTALLS =
      """
      {"installs":[{"deviceName":"Pixel Watch 3","deviceModel":"Pixel Watch 3","wearOsApi":35,
        "version":12,"revisionPublished":12,"result":"installed","activation":"not_requested",
        "errorKind":null,"atEpochMillis":1795081500000}]}
      """
        .trimIndent()
  }
}
