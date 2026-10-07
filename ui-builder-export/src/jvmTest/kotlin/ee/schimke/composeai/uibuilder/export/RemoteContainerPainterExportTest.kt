package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** `containerImageKey` writes a button's or card's `containerPainter` overload. */
class RemoteContainerPainterExportTest {
  private fun document(componentId: String, properties: String): UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"pictured","title":"Pictured","revision":1,
         "catalogPin":{},"environment":{},"stateVariables":{},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-large",
            "slots":{"content":["box"]}},
          "box":{"id":"box","componentId":"$componentId","properties":$properties,
            "slots":{"content":["title"]}},
          "title":{"id":"title","componentId":"remote-m3/remote-text",
            "properties":{"text":{"type":"string","value":"Summit"}}}}}
        """
      ) as JsonObject
    )

  private fun exported(document: UiBuilderDocument): String =
    assertIs<WearWidgetCodeExporter.Result.Emitted>(
        WearWidgetCodeExporter.export(document, packageName = "proof.draw"),
        (WearWidgetCodeExporter.export(document) as? WearWidgetCodeExporter.Result.Refused)
          ?.reasons
          ?.toString(),
      )
      .source

  @Test
  fun `a card with a container image is the painter overload`() {
    val source =
      exported(
        document(
          "remote-m3/remote-card",
          """{"containerImageKey":{"type":"assetKey","value":"mountain"}}""",
        )
      )
    File("build/draw-proof").apply { mkdirs() }.resolve("PictureCardWidget.kt").writeText(source)

    assertContains(
      source,
      "import androidx.compose.remote.creation.compose.painter.painterRemoteImageBitmap",
    )
    assertContains(source, "containerPainter = painterRemoteImageBitmap(")
    assertContains(source, "RemoteCard(")
  }

  @Test
  fun `a button with a container image is the painter overload`() {
    val source =
      exported(
        document(
          "remote-m3/remote-button",
          """{"containerImageKey":{"type":"assetKey","value":"mountain"}}""",
        )
      )

    assertContains(source, "containerPainter = painterRemoteImageBitmap(")
  }

  @Test
  fun `without one the plain overload is written`() {
    val source = exported(document("remote-m3/remote-card", "{}"))

    assertFalse("containerPainter" in source, source)
  }
}
