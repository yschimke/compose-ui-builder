package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.WATCH_INSTALLS_UNAVAILABLE
import ee.schimke.composeai.uibuilder.editor.WatchFaceMemoryContributor
import ee.schimke.composeai.uibuilder.editor.WatchFaceMemoryFootprint
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationCheck
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationFinding
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationPanel
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationReport
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationView
import ee.schimke.composeai.uibuilder.editor.WatchInstall
import ee.schimke.composeai.uibuilder.editor.WatchInstallsView

/*
 * The Issues panel's Validate section for a Watch Face Format design, one preview per state, at
 * the inspector's 320dp width. Fixed: revision 12 of a face, the contract's four checks, and two
 * linked watches. The section's host is the server's `watchface-validation` and `watch-installs`
 * routes; these draw the panel without it.
 */

@Preview(widthDp = 320, heightDp = 400)
@Composable
fun WatchFaceValidationNotRunPreview() {
  ValidationFrame(WatchFaceValidationView.NotRun, WatchInstallsView.Listed(emptyList()))
}

@Preview(widthDp = 320, heightDp = 560)
@Composable
fun WatchFaceValidationRunningPreview() {
  ValidationFrame(
    WatchFaceValidationView.Running(previous = passedReport.copy(revision = 11)),
    WatchInstallsView.Loading,
  )
}

@Preview(widthDp = 320, heightDp = 700)
@Composable
fun WatchFaceValidationAllPassedPreview() {
  ValidationFrame(WatchFaceValidationView.Ran(passedReport), upToDateInstalls)
}

@Preview(widthDp = 320, heightDp = 880)
@Composable
fun WatchFaceValidationFailuresPreview() {
  ValidationFrame(WatchFaceValidationView.Ran(failedReport), upToDateInstalls)
}

@Preview(widthDp = 320, heightDp = 840)
@Composable
fun WatchFaceValidationMemoryOverLimitPreview() {
  ValidationFrame(WatchFaceValidationView.Ran(memoryOverReport), upToDateInstalls)
}

@Preview(widthDp = 320, heightDp = 640)
@Composable
fun WatchFaceValidationSkippedPreview() {
  ValidationFrame(WatchFaceValidationView.Ran(skippedReport), WatchInstallsView.Listed(emptyList()))
}

/** Four watches: current and active, a revision behind, never activated, and a failed install. */
@Preview(widthDp = 320, heightDp = 800)
@Composable
fun WatchFaceValidationWatchInstallsPreview() {
  ValidationFrame(
    WatchFaceValidationView.Ran(passedReport),
    WatchInstallsView.Listed(
      listOf(
        install,
        install.copy(
          deviceName = "Galaxy Watch8",
          deviceModel = "SM-L330",
          wearOsApi = 36,
          version = "11",
          revisionPublished = 9,
          result = "updated",
        ),
        install.copy(
          deviceName = "Pixel Watch 2",
          deviceModel = "Pixel Watch 2",
          wearOsApi = 34,
          result = "replaced",
          activation = "not_requested",
        ),
        install.copy(
          deviceName = "TicWatch Pro 5",
          deviceModel = "WH12018",
          wearOsApi = 33,
          revisionPublished = 12,
          result = "failed",
          activation = "failed",
          errorKind = "INSTALL_FAILED_VERSION_DOWNGRADE",
        ),
      ),
    ),
  )
}

/** A design kept in this browser: the section stays, saying why it cannot run. */
@Preview(widthDp = 320, heightDp = 260)
@Composable
fun WatchFaceValidationLocalDesignPreview() {
  val reason =
    "This design is kept in this browser. Validation and watch installs run on the server " +
      "against a stored revision; save the design to the server to validate it."
  ValidationFrame(
    WatchFaceValidationView.Unavailable(reason),
    WatchInstallsView.Unavailable(WATCH_INSTALLS_UNAVAILABLE),
  )
}

/** The request itself refused: shown in the error colour, the last result kept under it. */
@Preview(widthDp = 320, heightDp = 300)
@Composable
fun WatchFaceValidationRefusedPreview() {
  ValidationFrame(
    WatchFaceValidationView.Failed(
      "Sign in with an account that can edit this design to validate it.",
    ),
    WatchInstallsView.Unavailable("Only the design's owner sees which watches it is on."),
  )
}

@Composable
private fun ValidationFrame(validation: WatchFaceValidationView, installs: WatchInstallsView) {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface(Modifier.fillMaxSize()) {
      WatchFaceValidationPanel(
        validation = validation,
        installs = installs,
        revision = 12,
        onRun = {},
        onOpenFinding = {},
        modifier = Modifier.width(320.dp).padding(horizontal = 16.dp, vertical = 12.dp),
      )
    }
  }
}

private const val MB = 1024L * 1024L

private val passedMemory =
  WatchFaceMemoryFootprint(
    totalBytes = 3 * MB + 400 * 1024,
    maxActiveBytes = 6 * MB + 200 * 1024,
    maxAmbientBytes = 1 * MB + 300 * 1024,
    activeLimitBytes = 10 * MB,
    ambientLimitBytes = 2 * MB,
  )

private val passedReport =
  WatchFaceValidationReport(
    designId = "preview-face",
    revision = 12,
    digest = "sha256:5f1c",
    // 2026-11-19 09:42 UTC.
    ranAtEpochMillis = 1_795_081_320_000,
    schemaVersion = "1.4.0",
    wffVersion = "4",
    minimumWffVersion = "2",
    passed = true,
    checks =
      listOf(
        WatchFaceValidationCheck("wff-schema", "WFF schema", "passed"),
        WatchFaceValidationCheck("wff-xsd", "XSD", "passed"),
        WatchFaceValidationCheck(
          "memory-footprint",
          "Memory footprint",
          "passed",
          memory = passedMemory,
        ),
        WatchFaceValidationCheck(
          "push-validator",
          "Push validator",
          "passed",
          passedChecks = listOf("manifest", "resources", "watchface.xml"),
          tokenIssued = true,
        ),
      ),
  )

private val failedReport =
  passedReport.copy(
    passed = false,
    checks =
      listOf(
        WatchFaceValidationCheck(
          "wff-schema",
          "WFF schema",
          "failed",
          findings =
            listOf(
              WatchFaceValidationFinding(
                "error",
                "Attribute 'angle' is not allowed on element 'PartDraw'.",
                line = 41,
                column = 9,
                path = "/WatchFace/Scene/Group[2]/PartDraw",
              ),
              WatchFaceValidationFinding(
                "warning",
                "Element 'Arc' has no 'endAngle'; it draws nothing.",
                line = 58,
                column = 13,
              ),
            ),
        ),
        WatchFaceValidationCheck(
          "wff-xsd",
          "XSD",
          "failed",
          findings =
            listOf(
              WatchFaceValidationFinding(
                "error",
                "cvc-complex-type.4: Attribute 'height' must appear on element 'Group'.",
                line = 22,
                column = 5,
              ),
            ),
        ),
        WatchFaceValidationCheck(
          "memory-footprint",
          "Memory footprint",
          "passed",
          memory = passedMemory,
        ),
        WatchFaceValidationCheck(
          "push-validator",
          "Push validator",
          "failed",
          findings =
            listOf(WatchFaceValidationFinding("error", "The package did not pass validation.")),
          passedChecks = listOf("manifest"),
          tokenIssued = false,
        ),
      ),
  )

/** Largest first after sorting; the background is stored at twice the size the face draws it. */
private val memoryContributors =
  listOf(
    WatchFaceMemoryContributor(
      resource = "hand_seconds",
      kind = "image",
      width = 32,
      height = 450,
      format = "PNG",
      activeBytes = 57_600,
      ambientBytes = 0,
      renderedMaxWidth = 32,
      renderedMaxHeight = 450,
    ),
    WatchFaceMemoryContributor(
      resource = "background",
      kind = "image",
      width = 900,
      height = 900,
      format = "PNG",
      decodedBytes = 3_240_000,
      activeBytes = 3_240_000,
      ambientBytes = 3_240_000,
      renderedMaxWidth = 450,
      renderedMaxHeight = 450,
    ),
    WatchFaceMemoryContributor(
      resource = "font_digits",
      kind = "font",
      activeBytes = 412_000,
      ambientBytes = 412_000,
    ),
    WatchFaceMemoryContributor(
      resource = "dial_numbers",
      kind = "image",
      width = 450,
      height = 450,
      format = "WEBP",
      activeBytes = 810_000,
      ambientBytes = 810_000,
      renderedMaxWidth = 450,
      renderedMaxHeight = 450,
    ),
  )

private val memoryOverReport =
  passedReport.copy(
    passed = false,
    checks =
      passedReport.checks.map {
        if (it.id != "memory-footprint") it
        else
          it.copy(
            status = "failed",
            findings =
              listOf(
                WatchFaceValidationFinding(
                  "error",
                  "Ambient mode needs 2.6 MB; the limit is 2 MB.",
                  path = "/WatchFace/Scene/Variant[@mode='AMBIENT']",
                  suggestion = "downscale background.png to 450x450",
                ),
              ),
            memory =
              passedMemory.copy(
                maxActiveBytes = 11 * MB,
                maxAmbientBytes = 2 * MB + 600 * 1024,
                contributors = memoryContributors,
              ),
          )
      },
  )

private val skippedReport =
  passedReport.copy(
    passed = true,
    checks =
      passedReport.checks.map {
        when (it.id) {
          "memory-footprint" ->
            it.copy(
              status = "skipped",
              memory = null,
              findings =
                listOf(
                  WatchFaceValidationFinding(
                    "warning",
                    "Skipped: the memory footprint tool is not installed on this host.",
                  ),
                ),
            )
          "push-validator" -> it.copy(status = "skipped", passedChecks = null, tokenIssued = null)
          else -> it
        }
      },
  )

private val install =
  WatchInstall(
    deviceName = "Pixel Watch 3",
    deviceModel = "Pixel Watch 3",
    wearOsApi = 35,
    version = "12",
    revisionPublished = 12,
    result = "installed",
    activation = "activated",
    atEpochMillis = 1_795_081_500_000,
  )

private val upToDateInstalls = WatchInstallsView.Listed(listOf(install))
