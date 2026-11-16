package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The Remote Compose alpha24 types a widget's text and image settings are written with, where
 * alpha23 took Compose UI's own (`TextAlign`, `TextOverflow`, `FontVariation`, `ContentScale`). The
 * widgets are written under `build/draw-proof`, so experiments/remote-export-compile compiles them.
 */
class RemoteAlpha24SpellingsExportTest {
  private fun widget(body: String): UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"spellings","title":"Spellings","revision":1,
         "catalogPin":{},"environment":{},"stateVariables":{},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-large",
            "slots":{"content":["body"]}},
          "body":$body}}
        """,
      ) as JsonObject,
    )

  private fun exported(document: UiBuilderDocument, file: String): String =
    assertIs<WearWidgetCodeExporter.Result.Emitted>(
        WearWidgetCodeExporter.export(document, packageName = "proof.draw"),
        (WearWidgetCodeExporter.export(document) as? WearWidgetCodeExporter.Result.Refused)
          ?.reasons
          ?.toString(),
      )
      .source
      .also { File("build/draw-proof").apply { mkdirs() }.resolve(file).writeText(it) }

  @Test
  fun `a text's alignment, overflow and axes are the Remote text types`() {
    val source =
      exported(
        widget(
          """
          {"id":"body","componentId":"remote-m3/remote-text","properties":{
            "text":{"type":"string","value":"Now playing"},
            "fontSize":{"type":"float","value":14.5},
            "textAlign":{"type":"enum","value":"center"},
            "maxLines":{"type":"int","value":1},
            "overflow":{"type":"enum","value":"ellipsis"},
            "fontVariationSettings":{"type":"string","value":"wght 650, wdth 87.5"}}}
          """,
        ),
        "TextSpellingsWidget.kt",
      )

    assertContains(source, "fontSize = 14.5f.sp.asRemoteTextUnit()")
    assertContains(source, "textAlign = RemoteTextAlign.Center")
    assertContains(source, "overflow = RemoteTextOverflow.Ellipsis")
    assertContains(
      source,
      "fontVariationSettings = RemoteFontVariation.Settings(" +
        "RemoteFontVariation.Setting(\"wght\", 650f.rf), " +
        "RemoteFontVariation.Setting(\"wdth\", 87.5f.rf))",
    )
    assertContains(source, "import androidx.compose.remote.creation.compose.text.RemoteTextAlign")
    assertContains(
      source,
      "import androidx.compose.remote.creation.compose.text.RemoteTextOverflow",
    )
    assertContains(
      source,
      "import androidx.compose.remote.creation.compose.text.RemoteFontVariation",
    )
    assertFalse("androidx.compose.ui.text." in source, source)
  }

  @Test
  fun `an image's scale is a RemoteContentScale`() {
    val source =
      exported(
        widget(
          """
          {"id":"body","componentId":"asset/image","properties":{
            "assetKey":{"type":"assetKey","value":"mountain"},
            "contentScale":{"type":"enum","value":"fit"}}}
          """,
        ),
        "ImageScaleWidget.kt",
      )

    assertContains(source, "contentScale = RemoteContentScale.Fit")
    assertContains(
      source,
      "import androidx.compose.remote.creation.compose.layout.RemoteContentScale",
    )
    assertFalse("androidx.compose.ui.layout.ContentScale" in source, source)
  }
}
