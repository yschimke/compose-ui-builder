package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * `graphicsLayer` is a Remote call like any other, and `semantics` — whose released signature is a
 * receiver lambda — is written as that lambda around the values the design states.
 */
class RemoteLayerAndSemanticsExportTest {
  private fun document(modifiers: String): UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"layers","title":"Layers","revision":1,
         "catalogPin":{},"environment":{},
         "stateVariables":{"count":{"type":"value","valueType":"int","initialValue":3,
           "nullable":false,"persistence":"session"}},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-small",
            "slots":{"content":["box"]}},
          "box":{"id":"box","componentId":"layout/box","modifiers":$modifiers}}}
        """
      ) as JsonObject
    )

  private fun emitted(modifiers: String): String =
    assertIs<WearWidgetCodeExporter.Result.Emitted>(
        WearWidgetCodeExporter.export(document(modifiers), packageName = "proof.draw"),
        (WearWidgetCodeExporter.export(document(modifiers))
            as? WearWidgetCodeExporter.Result.Refused)
          ?.reasons
          ?.toString(),
      )
      .source

  private fun refused(modifiers: String): List<String> =
    assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(document(modifiers))
      )
      .reasons

  @Test
  fun `graphicsLayer writes the layer's values`() {
    val source =
      emitted(
        """[{"type":"remoteCall","name":"graphicsLayer","args":{
          "rotationZ":{"type":"float","value":15},"alpha":{"type":"float","value":0.5}}}]"""
      )
    File("build/draw-proof").apply { mkdirs() }.resolve("LayerWidget.kt").writeText(source)

    assertContains(source, "graphicsLayer(rotationZ = 15.rf, alpha = 0.5f.rf)")
    assertContains(source, "import androidx.compose.remote.creation.compose.modifier.graphicsLayer")
  }

  @Test
  fun `semantics is the receiver lambda around what the design states`() {
    val source =
      emitted(
        """[{"type":"remoteCall","name":"semantics","args":{
          "contentDescription":{"type":"expr","op":"concat","args":[
            {"type":"state","variable":"count"},{"type":"string","value":" unread"}]},
          "role":{"type":"string","value":"Button"},
          "mergeDescendants":{"type":"bool","value":true}}}]"""
      )
    File("build/draw-proof").apply { mkdirs() }.resolve("SemanticsWidget.kt").writeText(source)

    assertContains(
      source,
      "semantics(mergeDescendants = true) { contentDescription = " +
        "(count.toRemoteString() + \" unread\".rs); role = Role.Button }",
    )
    assertContains(source, "import androidx.compose.ui.semantics.Role")
    assertContains(
      source,
      "import androidx.compose.remote.creation.compose.modifier.contentDescription",
    )
  }

  @Test
  fun `clearing semantics is clearAndSetSemantics`() {
    val source =
      emitted(
        """[{"type":"remoteCall","name":"semantics","args":{
          "contentDescription":{"type":"string","value":"Steps today"},
          "clear":{"type":"bool","value":true}}}]"""
      )
    File("build/draw-proof").apply { mkdirs() }.resolve("ClearWidget.kt").writeText(source)

    assertContains(source, "clearAndSetSemantics { contentDescription = \"Steps today\".rs }")
  }

  @Test
  fun `an unknown role and a clear that also merges are refused`() {
    assertTrue(
      refused(
          """[{"type":"remoteCall","name":"semantics","args":{
            "role":{"type":"string","value":"Slider"}}}]"""
        )
        .any { "semantics.role" in it }
    )
    assertTrue(
      refused(
          """[{"type":"remoteCall","name":"semantics","args":{
            "clear":{"type":"bool","value":true},"mergeDescendants":{"type":"bool","value":true}}}]"""
        )
        .any { "semantics" in it }
    )
  }
}
