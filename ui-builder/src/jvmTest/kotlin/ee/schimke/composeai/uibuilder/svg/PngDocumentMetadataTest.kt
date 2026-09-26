package ee.schimke.composeai.uibuilder.svg

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderDocumentHome
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

class PngDocumentMetadataTest {
  @Test
  fun `PNG text metadata names canonical home and revision`() {
    val png =
      byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)
    val document =
      UiBuilderDocument(
        schema = "compose-ui-builder-document/v1",
        id = "login-v2",
        title = "Login",
        revision = 4,
        catalogPin = JsonObject(emptyMap()),
        environment = JsonObject(emptyMap()),
        stateVariables = JsonObject(emptyMap()),
        roots = emptyList(),
        nodes = emptyMap(),
        home = UiBuilderDocumentHome.Repo("ui-builder/designs/login-v2.uid"),
      )

    val annotated = png.withDocumentMetadata(document).decodeToString()

    assertTrue(annotated.contains("compose-ui-builder\u0000designId=login-v2;revision=4"))
    assertTrue(annotated.contains("home=repository ui-builder/designs/login-v2.uid"))
  }
}
