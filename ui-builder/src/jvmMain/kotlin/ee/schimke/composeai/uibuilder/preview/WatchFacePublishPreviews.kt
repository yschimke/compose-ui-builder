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
import ee.schimke.composeai.uibuilder.editor.TestOnWatchPanel
import ee.schimke.composeai.uibuilder.editor.WATCH_FACE_PUBLISH_OWNER_ONLY
import ee.schimke.composeai.uibuilder.editor.WatchFacePackaging
import ee.schimke.composeai.uibuilder.editor.WatchFacePublication
import ee.schimke.composeai.uibuilder.editor.WatchFacePublicationApk
import ee.schimke.composeai.uibuilder.editor.WatchFacePublishFailure
import ee.schimke.composeai.uibuilder.editor.WatchFacePublishView
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationPanel
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationView
import ee.schimke.composeai.uibuilder.editor.WatchInstall
import ee.schimke.composeai.uibuilder.editor.WatchInstallsView

/*
 * "Test on your watch", the watch-face editor's main next step, one preview per state at the
 * inspector's 320dp width. Fixed: the design is at revision 12, and a publication is version 3.
 */

/** Never published: the action and what follows it. */
@Preview(widthDp = 320, heightDp = 300)
@Composable
fun TestOnWatchNotPublishedPreview() {
  PublishFrame(WatchFacePublishView.Ready(WatchFacePublication(designId = "aurora")), null)
}

/** Published, no watch linked yet: how to get Faces on the watch and link it. */
@Preview(widthDp = 320, heightDp = 460)
@Composable
fun TestOnWatchNotLinkedPreview() {
  PublishFrame(WatchFacePublishView.Ready(published), watchesLinked = false)
}

/** Published and a watch has it: open Faces to install. */
@Preview(widthDp = 320, heightDp = 340)
@Composable
fun TestOnWatchLinkedPreview() {
  PublishFrame(WatchFacePublishView.Ready(published), watchesLinked = true)
}

/** Watches have an older revision than the one being edited. */
@Preview(widthDp = 320, heightDp = 380)
@Composable
fun TestOnWatchBehindPreview() {
  PublishFrame(WatchFacePublishView.Ready(published.copy(revision = 9)), watchesLinked = true)
}

/** The packager refused the publish: its diagnostics, with the last good version kept. */
@Preview(widthDp = 320, heightDp = 560)
@Composable
fun TestOnWatchPackagingFailedPreview() {
  PublishFrame(
    WatchFacePublishView.Ready(
      published.copy(revision = 9),
      failure =
        WatchFacePublishFailure(
          "error[wff-validator]: the face did not pass Google's validator",
          listOf(
            "error[wff-validator]: watchface.xml:41:9 attribute 'angle' is not allowed on PartDraw",
            "error[aapt2]: res/drawable/dial.png: file not found",
          ),
        ),
    ),
    watchesLinked = true,
  )
}

/** Publishing in flight. */
@Preview(widthDp = 320, heightDp = 340)
@Composable
fun TestOnWatchPublishingPreview() {
  PublishFrame(WatchFacePublishView.Ready(published, busy = true), watchesLinked = true)
}

/** An agent or a delegated session: why there is no action. */
@Preview(widthDp = 320, heightDp = 220)
@Composable
fun TestOnWatchOwnerOnlyPreview() {
  PublishFrame(WatchFacePublishView.NotAllowed(WATCH_FACE_PUBLISH_OWNER_ONLY), null)
}

/** This server cannot build APKs right now. */
@Preview(widthDp = 320, heightDp = 300)
@Composable
fun TestOnWatchPackagingUnavailablePreview() {
  PublishFrame(
    WatchFacePublishView.Ready(
      WatchFacePublication(
        designId = "aurora",
        apk = WatchFacePublicationApk(status = "unavailable"),
        packaging =
          WatchFacePackaging(
            code = "DWF_ANDROID_SDK_MISSING",
            reason = "the Android SDK build tools are not installed",
          ),
      ),
    ),
    null,
  )
}

/** Where the step sits: between the checks and "On your watch". */
@Preview(widthDp = 320, heightDp = 640)
@Composable
fun TestOnWatchInValidatePanelPreview() {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface(Modifier.fillMaxSize()) {
      WatchFaceValidationPanel(
        validation = WatchFaceValidationView.NotRun,
        installs = WatchInstallsView.Listed(listOf(watch)),
        revision = 12,
        onRun = {},
        onOpenFinding = {},
        modifier = Modifier.width(320.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        testOnWatch = {
          TestOnWatchPanel(
            WatchFacePublishView.Ready(published),
            revision = 12,
            watchesLinked = true,
            onPublish = {},
            onUnpublish = {},
          )
        },
      )
    }
  }
}

@Composable
private fun PublishFrame(view: WatchFacePublishView, watchesLinked: Boolean?) {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface(Modifier.fillMaxSize()) {
      TestOnWatchPanel(
        view = view,
        revision = 12,
        watchesLinked = watchesLinked,
        onPublish = {},
        onUnpublish = {},
        modifier = Modifier.width(320.dp).padding(horizontal = 16.dp, vertical = 12.dp),
      )
    }
  }
}

private val published =
  WatchFacePublication(
    designId = "aurora",
    published = true,
    version = 3,
    revision = 12,
    visibility = "private",
    requestedVisibility = "private",
    faceName = "aurora",
    packageName = "ee.schimke.watchface.push.watchfacepush.aurora",
    apk = WatchFacePublicationApk(status = "ready", sizeBytes = 18_213),
  )

private val watch =
  WatchInstall(
    deviceName = "Pixel Watch 3",
    deviceModel = "Pixel Watch 3",
    wearOsApi = 36,
    version = "3",
    revisionPublished = 12,
    result = "installed",
    activation = "activated",
  )
