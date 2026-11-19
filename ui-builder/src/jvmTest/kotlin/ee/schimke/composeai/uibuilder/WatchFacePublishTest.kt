package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import ee.schimke.composeai.uibuilder.editor.TestOnWatchPanel
import ee.schimke.composeai.uibuilder.editor.WATCH_FACE_PUBLISH_OWNER_ONLY
import ee.schimke.composeai.uibuilder.editor.WatchFacePublication
import ee.schimke.composeai.uibuilder.editor.WatchFacePublicationAnswer
import ee.schimke.composeai.uibuilder.editor.WatchFacePublicationBuild
import ee.schimke.composeai.uibuilder.editor.WatchFacePublishController
import ee.schimke.composeai.uibuilder.editor.WatchFacePublishHost
import ee.schimke.composeai.uibuilder.editor.WatchFacePublishView
import ee.schimke.composeai.uibuilder.editor.WatchFacePushCapability
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationJson
import ee.schimke.composeai.uibuilder.editor.buildChipText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** "Test on your watch" against compose-preview-server's `watch-face-publication` route. */
@OptIn(ExperimentalTestApi::class)
class WatchFacePublishTest {
  @Test
  fun `the server's publication decodes`() {
    val publication =
      WatchFaceValidationJson.decodeFromString(WatchFacePublication.serializer(), PUBLICATION)
    assertEquals(true, publication.published)
    assertEquals(3, publication.version)
    assertEquals(41, publication.revision)
    assertEquals("public", publication.visibility)
    assertEquals("ready", publication.apk?.status)
    assertEquals(listOf("error[wff-validator]: x"), publication.refusal)
  }

  @Test
  fun `no capability hides the step and a reader is told why not`() = runBlocking {
    val hidden = WatchFacePublishController(FakeHost(capability = null), this)
    hidden.load()
    assertEquals(WatchFacePublishView.Hidden, hidden.view.value)

    val reader = WatchFacePublishController(FakeHost(WatchFacePushCapability("/p", false)), this)
    reader.load()
    assertEquals(WatchFacePublishView.NotAllowed(WATCH_FACE_PUBLISH_OWNER_ONLY), reader.view.value)
  }

  @Test
  fun `a local design says why before asking the server`() = runBlocking {
    val host = FakeHost(unavailableReason = "kept in this browser")
    val controller = WatchFacePublishController(host, this)
    controller.load()
    assertEquals(WatchFacePublishView.NotAllowed("kept in this browser"), controller.view.value)
    assertEquals(0, host.reads)
  }

  @Test
  fun `a refused publish keeps what is on watches and shows the diagnostics`() = runBlocking {
    val host = FakeHost()
    val design = CoroutineScope(coroutineContext + Job())
    val controller = WatchFacePublishController(host, design)
    controller.load()
    host.next = WatchFacePublicationAnswer.PackagingFailed("refused", listOf("error[aapt2]: x"))
    controller.publish("private")
    design.coroutineContext[Job]!!.children.forEach { it.join() }
    val ready = assertIs<WatchFacePublishView.Ready>(controller.view.value)
    assertEquals(3, ready.publication.version)
    assertEquals(listOf("error[aapt2]: x"), ready.failure?.diagnostics)
    assertEquals(listOf("private"), host.published)
    design.cancel()
  }

  @Test
  fun `publish sends the chosen visibility and an older pinned revision says so`() =
    runComposeUiTest {
      var sent: String? = null
      setContent {
        MaterialTheme {
          TestOnWatchPanel(
            view = WatchFacePublishView.Ready(PUBLISHED.copy(revision = 9)),
            revision = 12,
            watchesLinked = false,
            onPublish = { sent = it },
            onUnpublish = {},
          )
        }
      }
      onNodeWithText("Watches have revision 9", substring = true).assertExists()
      onNodeWithText("Get it on Google Play").assertExists()
      onNodeWithText("Every watch").performClick()
      onNodeWithText("Publish revision 12").performClick()
      assertEquals("public", sent)
    }

  @Test
  fun `the chip says where the background build is`() {
    val now = 1_000_000L
    fun chip(build: WatchFacePublicationBuild) = buildChipText(build, now, publishedVersion = 6)
    assertEquals("Builds in 8 min", chip(WatchFacePublicationBuild("pending", now + 8 * 60_000)))
    assertEquals("Queued, 1 ahead", chip(WatchFacePublicationBuild("queued", queueAhead = 1)))
    assertEquals("Queued, next", chip(WatchFacePublicationBuild("queued", queueAhead = 0)))
    assertEquals("Packaging…", chip(WatchFacePublicationBuild("building")))
    assertEquals("On your watch: v7", chip(WatchFacePublicationBuild("built", version = 7)))
    assertEquals("On your watch: v6", chip(WatchFacePublicationBuild("built")))
    assertEquals(
      "Refused: error[aapt2]: x",
      chip(WatchFacePublicationBuild("refused", diagnostics = listOf("error[aapt2]: x"))),
    )
    assertEquals(
      "Build failed, retrying in 2 min",
      chip(WatchFacePublicationBuild("failed", nextRetryAtEpochMillis = now + 90_000)),
    )
    assertNull(buildChipText(null, now, 6))
  }

  @Test
  fun `a queued build is read again until it is built, and then left alone`() = runBlocking {
    val host = FakeHost()
    val design = CoroutineScope(coroutineContext + Job())
    val controller = WatchFacePublishController(host, design, now = { 0L })
    host.reads = 0
    host.publications +=
      listOf(
        PUBLISHED.copy(build = WatchFacePublicationBuild("queued", queueAhead = 1)),
        PUBLISHED.copy(build = WatchFacePublicationBuild("building")),
        PUBLISHED.copy(version = 4, build = WatchFacePublicationBuild("built", version = 4)),
      )
    withTimeout(60_000) {
      controller.load()
      while (
        (controller.view.value as? WatchFacePublishView.Ready)?.publication?.build?.state != "built"
      ) {
        delay(100)
      }
    }
    // The load and two follow-up reads; a built state schedules nothing more.
    assertEquals(3, host.publicationReads)
    assertTrue(design.coroutineContext[Job]!!.children.none { it.isActive })
    design.cancel()
  }

  private class FakeHost(
    private val capability: WatchFacePushCapability? = WatchFacePushCapability("/p", true),
    override val unavailableReason: String? = null,
  ) : WatchFacePublishHost {
    var reads = 0
    var next: WatchFacePublicationAnswer = WatchFacePublicationAnswer.Ok(PUBLISHED)
    val published = mutableListOf<String>()

    override suspend fun capability(): WatchFacePushCapability? {
      reads++
      return capability
    }

    /** Answered in order by successive reads; the last one repeats. */
    val publications = mutableListOf<WatchFacePublication>()
    var publicationReads = 0

    override suspend fun publication(): WatchFacePublicationAnswer {
      val answer =
        publications.getOrNull(publicationReads) ?: publications.lastOrNull() ?: PUBLISHED
      publicationReads++
      return WatchFacePublicationAnswer.Ok(answer)
    }

    override suspend fun publish(visibility: String): WatchFacePublicationAnswer {
      published += visibility
      return next
    }

    override suspend fun unpublish() = next
  }

  private companion object {
    val PUBLISHED =
      WatchFacePublication(
        designId = "aurora",
        published = true,
        version = 3,
        revision = 12,
        visibility = "private",
        requestedVisibility = "private",
      )

    val PUBLICATION =
      """
      {"schema":"compose-preview/watchface-push-publication/v1","designId":"aurora",
       "published":true,"version":3,"revision":41,"visibility":"public",
       "requestedVisibility":"public","automatic":false,"faceName":"aurora",
       "packageName":"ee.schimke.watchface.push.watchfacepush.aurora",
       "apk":{"status":"ready","sha256":"ab","sizeBytes":18213},
       "refusal":["error[wff-validator]: x"]}
      """
        .trimIndent()
  }
}
