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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

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
  }

  @Test
  fun `a 404 is not run, a run replaces it, and a refusal keeps the last report`() = runBlocking {
    val host = FakeHost()
    val controller = WatchFaceValidationController(host)
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
  fun `a local design never reaches the host and says why`() = runBlocking {
    val host = FakeHost(unavailableReason = "kept in this browser")
    val controller = WatchFaceValidationController(host)
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

    override suspend fun latest(revision: Long): WatchFaceValidationAnswer {
      reads++
      return WatchFaceValidationAnswer.NeverRun
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
      {"designId":"d1","revision":12,"digest":"sha256:ab","ranAt":1763545320000,
       "schemaVersion":"1.4.0","wffVersion":4,"minimumWffVersion":2,"passed":false,
       "futureField":{"x":1},
       "checks":[
        {"id":"wff-schema","title":"WFF schema","status":"failed",
         "findings":[{"severity":"error","message":"bad attribute","line":41,"column":9,
                      "path":"/WatchFace/Scene"}]},
        {"id":"memory-footprint","title":"Memory footprint","status":"failed","findings":[],
         "memory":{"totalBytes":3000000,"maxActiveBytes":11534336,"maxAmbientBytes":1000000,
                   "activeLimitBytes":10485760,"ambientLimitBytes":2097152}},
        {"id":"push-validator","title":"Push validator","status":"passed","findings":[],
         "passedChecks":["manifest"],"tokenIssued":false}
       ]}
      """
        .trimIndent()

    val INSTALLS =
      """
      {"installs":[{"deviceName":"Pixel Watch 3","deviceModel":"Pixel Watch 3","wearOsApi":35,
        "version":12,"revisionPublished":12,"result":"installed","activation":"not_requested",
        "errorKind":null,"at":"2026-11-19T09:45:00Z"}]}
      """
        .trimIndent()
  }
}
