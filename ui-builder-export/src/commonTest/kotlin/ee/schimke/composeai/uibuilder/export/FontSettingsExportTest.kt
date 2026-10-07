package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A text's axes and features reach the Wear and Remote generated code, in each one's own API. */
class FontSettingsExportTest {
  @Test
  fun `a Wear text in Wear's own face carries its axes on the device face`() {
    val source = wearSource(text("label", "wear-m3/text", "wdth 80, wght 650", "tnum"))
    assertTrue(
      "fontFamily = FontFamily(Font(DeviceFontFamilyName(\"roboto-flex\"), variationSettings = " +
        "FontVariation.Settings(FontVariation.Setting(\"wdth\", 80f), " +
        "FontVariation.Setting(\"wght\", 650f))))" in source,
      source,
    )
    assertTrue(
      "style = LocalTextStyle.current.copy(fontFeatureSettings = \"tnum\")" in source,
      source,
    )
    listOf(
        "androidx.compose.ui.text.font.DeviceFontFamilyName",
        "androidx.compose.ui.text.font.FontVariation",
        "androidx.wear.compose.material3.LocalTextStyle",
      )
      .forEach { assertTrue("import $it" in source, "missing import $it:\n$source") }
  }

  @Test
  fun `a Wear text in a Google Fonts theme face writes its weight axis as the weight`() {
    val source =
      wearSource(
        text("label", "wear-m3/text", "wght 650, wdth 80", null, style = "titleMedium"),
        host = mapOf("themeTitleTypeface" to "Michroma"),
      )
    assertTrue("fontWeight = FontWeight(650)" in source, source)
    assertTrue("DeviceFontFamilyName" !in source, source)
  }

  @Test
  fun `a remote text writes its axes into the document and its features on the style`() {
    val design =
      document(
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
              slots = mapOf("content" to listOf("label")),
            ),
            text("label", "m3/text", "wght 650", "tnum, liga 0"),
          ),
      )
    val emitted =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(
        InlineRemoteContentExporter.export(design, "remote")
      )
    val source = emitted.source
    assertTrue(
      "fontVariationSettings = FontVariation.Settings(FontVariation.Setting(\"wght\", 650f))" in
        source,
      source,
    )
    assertTrue(
      "style = LocalRemoteTextStyle.current.merge(fontFeatureSettings = \"tnum, liga 0\")" in
        source,
      source,
    )
    assertTrue("import androidx.compose.ui.text.font.FontVariation" in source, source)
  }

  private fun wearSource(text: UiBuilderNode, host: Map<String, String> = emptyMap()): String {
    val screen =
      UiBuilderNode(
        "screen",
        WearScreenCodeExporter.SCAFFOLD,
        properties = JsonObject(host.mapValues { (_, value) -> string(value) }),
        slots = mapOf("content" to listOf("list")),
      )
    val list =
      UiBuilderNode(
        "list",
        "wear-m3/transforming-lazy-column",
        slots = mapOf("items" to listOf(text.id)),
      )
    val generated =
      RecordFreeExport.generate(
        document(listOf("screen"), listOf(screen, list, text), systemId = "wear-m3"),
        UiBuilderCatalogPlatform.WEAR,
      )
    return assertIs<RecordFreeExport.Generated.Emitted>(generated, "$generated").source
  }

  private fun string(value: String) = buildJsonObject {
    put("type", "string")
    put("value", value)
  }

  private fun text(
    id: String,
    componentId: String,
    variations: String?,
    features: String?,
    style: String? = null,
  ) =
    UiBuilderNode(
      id = id,
      componentId = componentId,
      properties =
        buildJsonObject {
          putJsonObject("text") {
            put("type", "string")
            put("value", "12:30")
          }
          style?.let { put("style", string(it)) }
          variations?.let { put(FontSettings.VARIATION_PROPERTY, string(it)) }
          features?.let { put(FontSettings.FEATURE_PROPERTY, string(it)) }
        },
    )

  private fun document(
    roots: List<String>,
    nodes: List<UiBuilderNode>,
    systemId: String = "m3-catalog",
  ) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "design",
      title = "Design",
      revision = 1,
      catalogPin = JsonObject(mapOf("systemId" to JsonPrimitive(systemId))),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = roots,
      nodes = nodes.associateBy { it.id },
    )
}
