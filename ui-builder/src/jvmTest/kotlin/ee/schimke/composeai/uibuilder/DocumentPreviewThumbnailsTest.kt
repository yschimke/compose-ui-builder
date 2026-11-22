package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.DocumentPreviewThumbnails
import ee.schimke.composeai.uibuilder.editor.UiBuilderDocumentPreview
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

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
}
