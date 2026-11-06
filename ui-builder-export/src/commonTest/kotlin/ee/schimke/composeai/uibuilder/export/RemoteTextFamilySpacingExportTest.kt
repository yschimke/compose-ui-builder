package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A remote text's family, letter spacing and line height reach the generated `RemoteText`. */
class RemoteTextFamilySpacingExportTest {
  @Test
  fun `a generic family is written as its own member`() {
    val source = source(text(family = "monospace"))
    assertTrue("fontFamily = RemoteFontFamily.Monospace" in source, source)
    assertTrue(
      "import androidx.compose.remote.creation.compose.text.RemoteFontFamily" in source,
      source,
    )
  }

  @Test
  fun `any other family is written by name for the player to resolve`() {
    val source = source(text(family = "google:Michroma"))
    assertTrue("fontFamily = RemoteFontFamily.Named(\"google:Michroma\")" in source, source)
  }

  @Test
  fun `spacing merges into the ambient style when no role is set`() {
    val source = source(text(letterSpacing = 0.5, lineHeight = 20.0))
    assertTrue(
      "style = LocalRemoteTextStyle.current.merge(letterSpacing = 0.5f.sp.asRemoteTextUnit(), " +
        "lineHeight = 20.rsp)" in source,
      source,
    )
    assertTrue("is not written" !in source, source)
  }

  @Test
  fun `spacing merges into the role's style alongside the features`() {
    val source = source(text(style = "titleMedium", letterSpacing = -1.0, features = "tnum"))
    assertTrue(
      "style = RemoteMaterialTheme.typography.titleMedium.merge(" +
        "letterSpacing = (-1.0f).sp.asRemoteTextUnit(), fontFeatureSettings = \"tnum\")" in source,
      source,
    )
  }

  @Test
  fun `the published component's own spellings are read too`() {
    val label =
      UiBuilderNode(
        id = "label",
        componentId = "remote-m3/remote-text",
        properties =
          buildJsonObject {
            put("text", typed("string", JsonPrimitive("12:30")))
            put("letterSpacing", typed("number", JsonPrimitive(2)))
            put("lineHeight", typed("number", JsonPrimitive(18.5)))
          },
      )
    val source = source(label)
    assertTrue(
      "merge(letterSpacing = 2.rsp, lineHeight = 18.5f.sp.asRemoteTextUnit())" in source,
      source,
    )
  }

  @Test
  fun `neutral spacing writes nothing`() {
    val source = source(text(letterSpacing = 0.0, lineHeight = 0.0))
    assertTrue("style =" !in source, source)
    assertTrue("LocalRemoteTextStyle" !in source, source)
  }

  private fun source(label: UiBuilderNode): String {
    val design =
      UiBuilderDocument(
        schema = "compose-ui-builder-document/v1-candidate",
        id = "design",
        title = "Design",
        revision = 1,
        catalogPin = JsonObject(mapOf("systemId" to JsonPrimitive("m3-catalog"))),
        environment = JsonObject(emptyMap()),
        stateVariables = JsonObject(emptyMap()),
        roots = listOf("screen"),
        nodes =
          listOf(
              UiBuilderNode(
                "screen",
                "layout/column",
                slots = mapOf("children" to listOf("remote")),
              ),
              UiBuilderNode(
                "remote",
                REMOTE_COMPOSE_INLINE_COMPONENT_ID,
                slots = mapOf("content" to listOf(label.id)),
              ),
              label,
            )
            .associateBy { it.id },
      )
    return assertIs<InlineRemoteContentExporter.Result.Emitted>(
        InlineRemoteContentExporter.export(design, "remote"),
      )
      .source
  }

  private fun text(
    family: String? = null,
    letterSpacing: Double? = null,
    lineHeight: Double? = null,
    style: String? = null,
    features: String? = null,
  ) =
    UiBuilderNode(
      id = "label",
      componentId = "m3/text",
      properties =
        buildJsonObject {
          putJsonObject("text") {
            put("type", "string")
            put("value", "12:30")
          }
          family?.let { put("fontFamily", typed("string", JsonPrimitive(it))) }
          letterSpacing?.let { put("letterSpacingSp", typed("number", JsonPrimitive(it))) }
          lineHeight?.let { put("lineHeightSp", typed("number", JsonPrimitive(it))) }
          style?.let { put("style", typed("string", JsonPrimitive(it))) }
          features?.let { put(FontSettings.FEATURE_PROPERTY, typed("string", JsonPrimitive(it))) }
        },
    )

  private fun typed(type: String, value: JsonPrimitive) = buildJsonObject {
    put("type", type)
    put("value", value)
  }
}
