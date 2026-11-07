package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A widget made of empty containers, written to `build/draw-proof` so the
 * `experiments/remote-export-compile` lane compiles it. Only `RemoteBox(modifier)` and
 * `RemoteFitBox` have calls without a content lambda; an aligned box, a column or a row written
 * bare does not compile, and string assertions alone did not catch that.
 */
class EmptyRemoteContainerCompileProofTest {
  private val document: UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"empty-cells","title":"Empty cells","revision":1,
         "catalogPin":{},"environment":{},"stateVariables":{},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-large",
            "slots":{"content":["grid"]}},
          "grid":{"id":"grid","componentId":"layout/column",
            "slots":{"children":["alignedBox","plainBox","sizedBox","column","row","fitBox"]}},
          "alignedBox":{"id":"alignedBox","componentId":"layout/box",
            "properties":{"contentAlignment":{"type":"enum","value":"center"}},
            "slots":{"children":[]}},
          "plainBox":{"id":"plainBox","componentId":"layout/box","slots":{"children":[]}},
          "sizedBox":{"id":"sizedBox","componentId":"layout/box",
            "modifiers":[{"type":"fillMaxWidth"}],
            "properties":{"contentAlignment":{"type":"enum","value":"bottomEnd"}},
            "slots":{"children":[]}},
          "column":{"id":"column","componentId":"layout/column","slots":{"children":[]}},
          "row":{"id":"row","componentId":"layout/row","slots":{"children":[]}},
          "fitBox":{"id":"fitBox","componentId":"layout/fit-box","slots":{"children":[]}}}}
        """,
      ) as JsonObject,
    )

  @Test
  fun `a widget of empty containers is written for the compile lane`() {
    val result = WearWidgetCodeExporter.export(document, packageName = "proof.draw")
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          result,
          (result as? WearWidgetCodeExporter.Result.Refused)?.reasons?.toString(),
        )
        .source
    File("build/draw-proof").apply { mkdirs() }.resolve("EmptyCellsWidget.kt").writeText(source)

    assertContains(source, "RemoteBox(contentAlignment = RemoteAlignment.Center) {}")
    assertContains(source, "RemoteColumn {}")
    assertContains(source, "RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {}")
  }
}
