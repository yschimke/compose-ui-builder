package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** `remote-m3/remote-label-button` is `RemoteButton`'s label overload, its slots named. */
class RemoteLabelButtonExportTest {
  private fun document(slots: String): UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"labelled","title":"Labelled","revision":1,
         "catalogPin":{},"environment":{},"stateVariables":{},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-large",
            "slots":{"content":["button"]}},
          "button":{"id":"button","componentId":"remote-m3/remote-label-button",
            "modifiers":[{"type":"fillMaxWidth"}],"slots":$slots},
          "title":{"id":"title","componentId":"remote-m3/remote-text",
            "properties":{"text":{"type":"string","value":"Start run"}}},
          "detail":{"id":"detail","componentId":"remote-m3/remote-text",
            "properties":{"text":{"type":"string","value":"5 km"}}},
          "glyph":{"id":"glyph","componentId":"remote-m3/remote-icon"}}}
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
  fun `the label, secondary label and icon are the overload's named slots`() {
    val source =
      exported(document("""{"label":["title"],"secondaryLabel":["detail"],"icon":["glyph"]}"""))
    File("build/draw-proof").apply { mkdirs() }.resolve("LabelButtonWidget.kt").writeText(source)

    assertContains(source, "RemoteButton(")
    assertContains(source, "label = {")
    assertContains(source, "secondaryLabel = {")
    assertContains(source, "icon = {")
    assertContains(source, "\"Start run\"")
  }

  @Test
  fun `a label alone is still named, so only the label overload matches`() {
    val source = exported(document("""{"label":["title"]}"""))
    File("build/draw-proof")
      .apply { mkdirs() }
      .resolve("LabelOnlyButtonWidget.kt")
      .writeText(source.replace("LabelledWidget", "LabelOnlyWidget"))

    assertContains(source, "label = {")
    assertFalse("RemoteButton(onClick = lambdaAction {}) {" in source, source)
  }
}
