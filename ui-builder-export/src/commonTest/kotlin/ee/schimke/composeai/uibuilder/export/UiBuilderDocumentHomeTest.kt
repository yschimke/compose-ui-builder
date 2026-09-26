package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class UiBuilderDocumentHomeTest {
  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }

  @Test
  fun `repository home round trips with its stable kind`() {
    val document = document(home = UiBuilderDocumentHome.Repo("ui-builder/designs/login-v2.uid"))

    val encoded = json.encodeToString(document)

    assertTrue(encoded.contains("\"home\":{\"kind\":\"repo\""), encoded)
    assertEquals(document, json.decodeFromString<UiBuilderDocument>(encoded))
  }

  @Test
  fun `absent home preserves the v1 wire shape`() {
    val encoded = json.encodeToString(document())

    assertFalse(encoded.contains("\"home\":"), encoded)
  }

  @Test
  fun `home crosses the released document boundary`() {
    val document =
      document(home = UiBuilderDocumentHome.Server("https://preview.coo.ee", "login-v2"))

    assertEquals(
      document.home,
      document.toDesignDocumentV1().toUiBuilderDocument().home,
    )
  }

  private fun document(home: UiBuilderDocumentHome? = null) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "login-v2",
      title = "Login",
      revision = 4,
      catalogPin =
        kotlinx.serialization.json.buildJsonObject {
          put("systemId", JsonPrimitive("m3-catalog"))
          put("catalogRevision", JsonPrimitive("candidate"))
          put("capabilityDigest", JsonPrimitive("candidate"))
          put("nativeRuntimeId", JsonPrimitive("m3"))
        },
      environment =
        kotlinx.serialization.json.buildJsonObject {
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
      home = home,
    )
}
