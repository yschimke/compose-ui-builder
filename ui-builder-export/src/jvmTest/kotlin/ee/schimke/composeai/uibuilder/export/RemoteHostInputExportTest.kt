package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A host input (`"persistence": "host"`) is a value the embedding host owns and pushes by name into
 * the running document, so the export names it — verbatim, in the User domain — instead of
 * remembering an anonymous mutable the host could never reach (compose-ui-builder#750).
 */
class RemoteHostInputExportTest {
  private fun export(
    clickActions: String = "[]",
    nameVariable: String = "light.s0.name",
  ): WearWidgetCodeExporter.Result {
    // The name text reads [nameVariable]; anything but the host input is a design-local string.
    val extraState =
      if (nameVariable == "light.s0.name") ""
      else
        """"$nameVariable":{"type":"text","valueType":"string","initialValue":"Kitchen",
           "nullable":false,"persistence":"session"},"""
    return WearWidgetCodeExporter.export(
      Json.decodeFromJsonElement<UiBuilderDocument>(
        Json.parseToJsonElement(
          """
          {"schema":"ui-builder-design-v1","id":"tile","title":"Tile","revision":1,
           "catalogPin":{},"environment":{},
           "stateVariables":{
             $extraState
             "light.s0.name":{"type":"text","valueType":"string","initialValue":"Kitchen",
               "nullable":false,"persistence":"host"},
             "light.s0.is_on":{"type":"value","valueType":"bool","initialValue":true,
               "nullable":false,"persistence":"host"},
             "expanded":{"type":"value","valueType":"bool","initialValue":false,
               "nullable":false,"persistence":"session"}},
           "roots":["host"],
           "nodes":{
            "host":{"id":"host","componentId":"remote-m3/widget-container-small",
              "slots":{"content":["column"]}},
            "column":{"id":"column","componentId":"layout/column",
              "slots":{"children":["name","switch"]}},
            "name":{"id":"name","componentId":"remote-m3/remote-text",
              "properties":{"text":{"type":"state","variable":"$nameVariable"}},
              "eventBindings":{"click":$clickActions}},
            "switch":{"id":"switch","componentId":"remote-m3/remote-switch-button",
              "properties":{"checked":{"type":"state","variable":"light.s0.is_on"}},
              "slots":{"label":[]}}}}
          """
        ) as JsonObject
      )
    )
  }

  @Test
  fun `a host input is a named User-domain value under its declared name`() {
    val result = export()
    val source = assertIs<WearWidgetCodeExporter.Result.Emitted>(result, result.toString()).source

    assertContains(
      source,
      "val light_s0_name = RemoteString.createNamedRemoteString(\"light.s0.name\", \"Kitchen\", " +
        "RemoteState.Domain.User)",
    )
    assertContains(
      source,
      "val light_s0_is_on = RemoteBoolean.createNamedRemoteBoolean(\"light.s0.is_on\", true, " +
        "RemoteState.Domain.User)",
    )
    assertContains(source, "import androidx.compose.remote.creation.compose.state.RemoteState")
    assertContains(source, "import androidx.compose.remote.creation.compose.state.RemoteString")
    assertFalse("rememberMutableRemoteString" in source, source)
  }

  @Test
  fun `a control bound to a host input reads it and writes nothing back`() {
    val result = export()
    val source = assertIs<WearWidgetCodeExporter.Result.Emitted>(result, result.toString()).source

    assertFalse("valueChange(light_s0_is_on" in source, source)
  }

  @Test
  fun `a local cannot shadow the types a host input is declared through`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(export(nameVariable = "RemoteState"))

    assertTrue(
      refused.reasons.any { "`RemoteState` conflicts with a generated Kotlin identifier" in it },
      refused.reasons.toString(),
    )
  }

  @Test
  fun `writing a host input is refused where it is`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        export("""[{"type":"setText","variable":"light.s0.name","value":"Hall"}]""")
      )

    assertTrue(
      refused.reasons.any { "writes host input `light.s0.name`" in it },
      refused.reasons.toString(),
    )
  }
}
