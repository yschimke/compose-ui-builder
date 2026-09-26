package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderDocumentHome
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class DesignFilesHomeTest {
  @Test
  fun `a project file retains its repository home through desktop persistence`() {
    val path = Files.createTempFile("design-home", ".uid")
    val document =
      UiBuilderDocument(
        schema = "compose-ui-builder-document/v1",
        id = "login-v2",
        title = "Login",
        revision = 4,
        catalogPin =
          buildJsonObject {
            put("systemId", JsonPrimitive("m3-catalog"))
            put("catalogRevision", JsonPrimitive("candidate"))
            put("capabilityDigest", JsonPrimitive("candidate"))
            put("nativeRuntimeId", JsonPrimitive("m3"))
          },
        environment =
          buildJsonObject {
            put("widthDp", JsonPrimitive(360))
            put("heightDp", JsonPrimitive(800))
            put("density", JsonPrimitive(1.0))
            put("theme", JsonPrimitive("light"))
            put("locale", JsonPrimitive("en-US"))
            put("fontScale", JsonPrimitive(1.0))
            put("layoutDirection", JsonPrimitive("ltr"))
          },
        stateVariables = kotlinx.serialization.json.JsonObject(emptyMap()),
        roots = emptyList(),
        nodes = emptyMap(),
        home = UiBuilderDocumentHome.Repo("ui-builder/designs/login-v2.uid"),
      )

    DesignFiles.write(path, document.toDesignDocumentV1())

    assertEquals(document.home, DesignFiles.read(path).home)
  }
}
