package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A host action hands an event to the host that feeds the document — a calculator key, say — and
 * writes nothing in the document itself. It is Remote Compose's named `hostAction`, with at most
 * one value.
 */
class RemoteHostActionExportTest {
  private fun export(
    actions: String,
    stateVariables: String = "{}",
  ): WearWidgetCodeExporter.Result =
    WearWidgetCodeExporter.export(
      Json.decodeFromJsonElement<UiBuilderDocument>(
        Json.parseToJsonElement(
          """
          {"schema":"ui-builder-design-v1","id":"calc","title":"Calc","revision":1,
           "catalogPin":{},"environment":{},
           "stateVariables":$stateVariables,
           "roots":["host"],
           "nodes":{
            "host":{"id":"host","componentId":"remote-m3/widget-container-small",
              "slots":{"content":["key"]}},
            "key":{"id":"key","componentId":"layout/box",
              "eventBindings":{"click":$actions}}}}
          """,
        ) as JsonObject,
      ),
      // A package, so the compile proof can move each file into one of its own.
      packageName = "proof.hostaction",
    )

  private fun emitted(actions: String, stateVariables: String = "{}", proof: String? = null) =
    assertIs<WearWidgetCodeExporter.Result.Emitted>(export(actions, stateVariables)).source.also {
      // Compiled against the released AndroidX libraries by experiments/remote-export-compile.
      if (proof != null)
        File("build/remote-call-proof").apply { mkdirs() }.resolve(proof).writeText(it)
    }

  @Test
  fun `a key sends its value to the host by name`() {
    val source =
      emitted(
        """[{"type":"hostAction","name":"calc.key","value":"7"}]""",
        proof = "HostActionTextWidget.kt",
      )

    assertContains(source, "hostAction(\"calc.key\".rs, \"7\".rs)")
    assertContains(source, "import androidx.compose.remote.creation.compose.action.hostAction")
    assertFalse("valueChange" in source, source)
  }

  @Test
  fun `a host action may carry nothing, a whole number or a decimal`() {
    assertContains(
      emitted("""[{"type":"hostAction","name":"calc.clear"}]""", proof = "HostActionBareWidget.kt"),
      "hostAction(\"calc.clear\".rs)",
    )
    assertContains(
      emitted(
        """[{"type":"hostAction","name":"calc.digit","value":7}]""",
        proof = "HostActionIntWidget.kt",
      ),
      "hostAction(\"calc.digit\".rs, 7.ri)",
    )
    assertContains(
      emitted(
        """[{"type":"hostAction","name":"volume","value":0.5}]""",
        proof = "HostActionFloatWidget.kt",
      ),
      "hostAction(\"volume\".rs, 0.5f.rf)",
    )
  }

  @Test
  fun `a host action runs in order with the design's own state writes`() {
    val source =
      emitted(
        """[{"type":"increment","variable":"presses"},
            {"type":"hostAction","name":"calc.key","value":"+"}]""",
        stateVariables =
          """{"presses":{"type":"value","valueType":"int","initialValue":0,
               "nullable":false,"persistence":"session"}}""",
        proof = "HostActionCombinedWidget.kt",
      )

    assertContains(source, "combinedAction(valueChange(presses, presses + 1), ")
    assertContains(source, "hostAction(\"calc.key\".rs, \"+\".rs))")
  }

  @Test
  fun `what Remote Compose cannot deliver is refused by name`() {
    fun reasons(actions: String) =
      assertIs<WearWidgetCodeExporter.Result.Refused>(export(actions)).reasons.joinToString("\n")

    assertContains(
      reasons("""[{"type":"hostAction","name":" "}]"""),
      "the `click` host action on `key`: a host action needs a name",
    )
    assertContains(
      reasons("""[{"type":"hostAction","name":"lights.on","value":true}]"""),
      "Remote Compose has no flag value; send 1 or 0",
    )
    assertContains(
      reasons(
        """[{"type":"hostAction","name":"calc.key","value":{"type":"state","variable":"x"}}]""",
      ),
      "a formula or a state read is not sent yet",
    )
    assertContains(
      reasons("""[{"type":"hostAction","name":"big","value":4294967296}]"""),
      "must fit an Int",
    )
  }

  @Test
  fun `the authoring JSON lane refuses a host action rather than dropping it`() {
    val document =
      Json.decodeFromJsonElement<UiBuilderDocument>(
        Json.parseToJsonElement(
          """
          {"schema":"ui-builder-design-v1","id":"calc","title":"Calc","revision":1,
           "catalogPin":{},"environment":{},"stateVariables":{},"roots":["key"],
           "nodes":{"key":{"id":"key","componentId":"layout/box",
             "eventBindings":{"click":[{"type":"hostAction","name":"calc.key","value":"7"}]}}}}
          """,
        ) as JsonObject,
      )
    val refused =
      assertIs<RemoteDocumentJsonExporter.Result.Refused>(
        RemoteDocumentJsonExporter.export(document),
      )
    assertTrue(refused.reasons.any { "host action" in it }, refused.reasons.toString())
  }
}
