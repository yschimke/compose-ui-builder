package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** `remote-m3/remote-time-text` is written as `RemoteTimeText`, the time left to the player. */
class RemoteTimeTextExportTest {
  private fun document(properties: String): UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"clock","title":"Clock","revision":1,
         "catalogPin":{},"environment":{},
         "stateVariables":{"steps":{"type":"value","valueType":"int","initialValue":4200,
           "nullable":false,"persistence":"session"}},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-large",
            "slots":{"content":["time"]}},
          "time":{"id":"time","componentId":"remote-m3/remote-time-text",
            "modifiers":[{"type":"fillMaxSize"}],
            "properties":$properties}}}
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
  fun `a bare time text is the component with its own defaults`() {
    val source = exported(document("{}"))

    assertContains(source, "import androidx.wear.compose.remote.material3.RemoteTimeText")
    assertContains(source, "RemoteTimeText(modifier = RemoteModifier.fillMaxSize())")
  }

  @Test
  fun `the text beside the time, its size and colour are written as stated`() {
    val source =
      exported(
        document(
          """{"leadingText":{"type":"expr","op":"concat","args":[
              {"type":"state","variable":"steps"},{"type":"string","value":" steps"}]},
            "trailingText":{"type":"string","value":"Wed"},
            "separator":{"type":"string","value":" | "},
            "textSizeSp":{"type":"float","value":12},
            "color":{"type":"colorToken","value":"primary"}}"""
        )
      )
    File("build/draw-proof").apply { mkdirs() }.resolve("TimeTextWidget.kt").writeText(source)

    assertContains(source, "leadingText = (steps.toRemoteString() + \" steps\".rs)")
    assertContains(source, "trailingText = \"Wed\".rs")
    assertContains(source, "separator = \" | \".rs")
    assertContains(source, "fontSize = 12.rsp")
    assertContains(source, "color = RemoteMaterialTheme.colorScheme.primary")
  }

  @Test
  fun `a computed size is refused, having no RemoteTextUnit spelling`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(
          document(
            """{"textSizeSp":{"type":"expr","op":"mul","args":[
              {"type":"int","value":2},{"type":"int","value":7}]}}"""
          )
        )
      )

    assertTrue(refused.reasons.any { "time.textSizeSp" in it }, refused.reasons.toString())
  }

  @Test
  fun `the line reads leading, time, trailing with the separator between`() {
    assertEquals("Mon·10:10·5°", UiTimeText.line("10:10", "Mon", "5°", null))
    assertEquals("10:10", UiTimeText.line("10:10", "", null, " | "))
  }
}
