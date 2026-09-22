package ee.schimke.composeai.uibuilder.desktop

import ee.schimke.composeai.uibuilder.EditorSubmission
import ee.schimke.composeai.uibuilder.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.toUiBuilderDocument
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class OfflineUiBuilderSessionTest {
  @Test
  fun `a project document writes its authoritative revision back through the host`() = runBlocking {
    val seed =
      OfflineUiBuilderSession(Files.createTempDirectory("ui-builder-project-seed")).use { session ->
        withTimeout(10.seconds) { session.snapshot.filterNotNull().first() }
          .snapshot
          .state
          .document
          .toUiBuilderDocument()
      }
    val committed = CompletableDeferred<ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1>()
    OfflineUiBuilderSession.projectDocument(seed) { committed.complete(it) }
      .use { session ->
        val opened = withTimeout(10.seconds) { session.snapshot.filterNotNull().first() }
        val document = opened.snapshot.state.document.toUiBuilderDocument()
        val reducer = UiBuilderEditorReducer(session.catalog, "project-test", "project-test")
        val initial = reducer.initial(document)
        val settings = document.screenEnvironmentSettings()
        val edited =
          reducer.reduce(
            initial,
            UiBuilderEditorEvent.UpdateEnvironment(settings.copy(heightDp = settings.heightDp + 1)),
          )

        session.submit(
          assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(initial, edited))
        )

        val saved = withTimeout(10.seconds) { committed.await() }.toUiBuilderDocument()
        assertEquals(settings.heightDp + 1, saved.screenEnvironmentSettings().heightDp)
        assertEquals(document.revision + 1, saved.revision)
      }
  }

  @Test
  fun `editor submissions are persisted in order and broadcast to every view`() = runBlocking {
    val storage = Files.createTempDirectory("ui-builder-session-test")
    OfflineUiBuilderSession(storage).use { session ->
      val opened = withTimeout(10.seconds) { session.snapshot.filterNotNull().first() }
      val document = opened.snapshot.state.document.toUiBuilderDocument()
      val reducer =
        UiBuilderEditorReducer(
          catalog = session.catalog,
          actorId = "session-test",
          clientId = "session-test",
          operationIdPrefix = "session-test",
        )
      val initial = reducer.initial(document)
      val settings = document.screenEnvironmentSettings()
      val firstState =
        reducer.reduce(
          initial,
          UiBuilderEditorEvent.UpdateEnvironment(settings.copy(widthDp = settings.widthDp + 1)),
        )
      val secondState =
        reducer.reduce(
          firstState,
          UiBuilderEditorEvent.UpdateEnvironment(settings.copy(widthDp = settings.widthDp + 2)),
        )
      val firstSubmission =
        assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(initial, firstState))
      val secondSubmission =
        assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(firstState, secondState))

      // These stand in for independently composed editor and Preview views. StateFlow must
      // broadcast the same authoritative revision to both, even when edits were queued together.
      val editorView = async {
        session.snapshot.filterNotNull().first {
          it.snapshot.state.document.revision == document.revision + 2L
        }
      }
      val previewView = async {
        session.snapshot.filterNotNull().first {
          it.snapshot.state.document.revision == document.revision + 2L
        }
      }

      session.submit(firstSubmission)
      session.submit(secondSubmission)

      val editorDocument =
        withTimeout(10.seconds) { editorView.await() }.snapshot.state.document.toUiBuilderDocument()
      val previewDocument =
        withTimeout(10.seconds) { previewView.await() }
          .snapshot
          .state
          .document
          .toUiBuilderDocument()
      assertEquals(settings.widthDp + 2, editorDocument.screenEnvironmentSettings().widthDp)
      assertEquals(editorDocument, previewDocument)
      assertNull(session.failure.value)
    }
  }
}
