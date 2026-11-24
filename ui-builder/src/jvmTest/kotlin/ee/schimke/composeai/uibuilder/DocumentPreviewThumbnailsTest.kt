package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.DocumentPreviewThumbnails
import ee.schimke.composeai.uibuilder.editor.UiBuilderDocumentPreview
import ee.schimke.composeai.uibuilder.editor.withCatalogPinOf
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A document-format catalog's palette is pictured from the host's exported documents: one request
 * per distinct thumbnail document, kept for the editor's life, and the canvas's own picture
 * wherever the export cannot be played.
 */
class DocumentPreviewThumbnailsTest {
  private fun thumbnail(componentId: String) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "catalog-thumbnail-frame",
      title = "Catalog thumbnail",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("cell"),
      nodes = mapOf("cell" to UiBuilderNode(id = "cell", componentId = componentId)),
    )

  @Test
  fun `each thumbnail document is exported once`() {
    runBlocking {
      val asked = mutableListOf<String>()
      val thumbnails =
        DocumentPreviewThumbnails(
          scope = this,
          request = { document ->
            asked += document.nodes.getValue("cell").componentId
            UiBuilderDocumentPreview.Failed("no host")
          },
        )
      val gauge = thumbnail("vendor/gauge")
      assertNull(thumbnails.result(gauge))
      thumbnails.load(gauge)
      thumbnails.load(gauge)
      thumbnails.load(thumbnail("vendor/gauge"))
      thumbnails.load(thumbnail("vendor/switch"))
      assertEquals(listOf("vendor/gauge", "vendor/switch"), asked)
    }
  }

  @Test
  fun `an export that cannot be played keeps the canvas's picture`() {
    runBlocking {
      val failing = thumbnail("vendor/broken")
      val undecodable = thumbnail("vendor/garbled")
      val thumbnails =
        DocumentPreviewThumbnails(
          scope = this,
          request = { document ->
            if (document == failing) error("convert refused the design")
            UiBuilderDocumentPreview.Ready(revision = 0, documentBase64 = "bm90IGFuIHJj")
          },
        )
      thumbnails.load(failing)
      thumbnails.load(undecodable)
      assertIs<DocumentPreviewThumbnails.Result.Unavailable>(thumbnails.result(failing))
      assertIs<DocumentPreviewThumbnails.Result.Unavailable>(thumbnails.result(undecodable))
    }
  }

  @Test
  fun `a tile leaving composition does not cancel the export another tile waits on`() {
    runBlocking {
      val answer = CompletableDeferred<UiBuilderDocumentPreview>()
      var asked = 0
      val editorJob = Job(coroutineContext[Job])
      val editor = CoroutineScope(coroutineContext + editorJob)
      val thumbnails =
        DocumentPreviewThumbnails(
          scope = editor,
          request = {
            asked += 1
            answer.await()
          },
        )
      val gauge = thumbnail("vendor/gauge")
      // The pinned shelf's tile asks, then its shelf collapses before the host answers.
      launch { thumbnails.prefetch(gauge) }.join()
      yield()
      // The gauge's own shelf asks too: the export is still running, so nothing new is sent.
      thumbnails.prefetch(gauge)
      answer.complete(UiBuilderDocumentPreview.Failed("no host"))
      editorJob.complete()
      editorJob.join()
      assertEquals(1, asked)
      assertIs<DocumentPreviewThumbnails.Result.Unavailable>(thumbnails.result(gauge))
    }
  }

  private fun pin(systemId: String, digest: String) =
    JsonObject(
      mapOf(
        "systemId" to JsonPrimitive(systemId),
        "catalogRevision" to JsonPrimitive("sha256:abc"),
        "capabilityDigest" to JsonPrimitive(digest),
        "nativeRuntimeId" to JsonPrimitive("candidate"),
      ),
    )

  @Test
  fun `a thumbnail sent to the service takes the open design's pin for the same catalog`() {
    val tile = thumbnail("vendor/gauge").copy(catalogPin = pin("vendor", "sha256:abc"))
    val design = thumbnail("vendor/screen").copy(catalogPin = pin("vendor", "candidate"))
    assertEquals(pin("vendor", "candidate"), tile.withCatalogPinOf(design).catalogPin)
    assertEquals(tile, tile.withCatalogPinOf(null))
    val elsewhere = design.copy(catalogPin = pin("other", "candidate"))
    assertEquals(tile, tile.withCatalogPinOf(elsewhere))
  }
}
