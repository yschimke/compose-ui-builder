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

/**
 * A `remoteCall` modifier is a `RemoteModifier` call from the generated vocabulary, written with
 * named arguments in the overload the arguments pick, and refused by name when the released API
 * does not have it.
 */
class RemoteCallModifierExportTest {
  private fun document(modifiers: String): UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"calls","title":"Calls","revision":1,
         "catalogPin":{},"environment":{},
         "stateVariables":{
           "shown":{"type":"value","valueType":"int","initialValue":1,
             "nullable":false,"persistence":"session"},
           "progress":{"type":"value","valueType":"float","initialValue":0.5,
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
        WearWidgetCodeExporter.export(document(modifiers), packageName = "proof.calls")
      )
      .source

  private fun refused(modifiers: String): List<String> =
    assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(document(modifiers))
      )
      .reasons

  @Test
  fun `the vocabulary is the released API's value-authorable surface`() {
    assertEquals("1.0.0-alpha20", RemoteModifierVocabulary.version)
    val names = RemoteModifierVocabulary.modifiers.keys
    assertTrue(names.containsAll(listOf("border", "visibility", "basicMarquee", "defaultMinSize")))
    // Behaviour is event bindings, and lambda-only calls cannot be a document value.
    assertTrue("clickable" !in names && "combinedClickable" !in names, names.toString())
    assertTrue("graphicsLayer" !in names && "drawWithContent" !in names, names.toString())
  }

  @Test
  fun `literal, state and computed arguments are written as the parameter types need`() {
    val source =
      emitted(
          """
        [{"type":"remoteCall","name":"border","args":{
           "width":{"type":"float","value":2},
           "color":{"type":"colorToken","value":"primary"}}},
         {"type":"remoteCall","name":"visibility","args":{
           "visible":{"type":"state","variable":"shown"}}},
         {"type":"remoteCall","name":"alpha","args":{
           "alpha":{"type":"expr","op":"mul","args":[
             {"type":"state","variable":"progress"},{"type":"float","value":2}]}}},
         {"type":"remoteCall","name":"basicMarquee","args":{
           "iterations":{"type":"int","value":3},"velocity":{"type":"float","value":40}}}]
        """
        )
        .also {
          File("build/remote-call-proof").apply { mkdirs() }.resolve("Calls.kt").writeText(it)
        }

    assertContains(source, "border(width = 2.rdp, color = RemoteMaterialTheme.colorScheme.primary)")
    assertContains(source, "visibility(visible = shown)")
    assertContains(source, "alpha(alpha = (progress * 2.rf))")
    assertContains(source, "basicMarquee(iterations = 3, velocity = 40.0f)")
    assertContains(source, "import androidx.compose.remote.creation.compose.modifier.border")
    assertContains(source, "import androidx.compose.remote.creation.compose.modifier.basicMarquee")
  }

  @Test
  fun `an unknown call, an unknown argument and a computed plain value are refused by name`() {
    val unknown = refused("""[{"type":"remoteCall","name":"blur","args":{}}]""")
    assertTrue(unknown.any { "`blur` is not a RemoteModifier call" in it }, unknown.toString())

    val argument =
      refused(
        """[{"type":"remoteCall","name":"border","args":{"radius":{"type":"float","value":1}}}]"""
      )
    assertTrue(argument.any { "`border` takes (width, color)" in it }, argument.toString())

    val plain =
      refused(
        """
        [{"type":"remoteCall","name":"basicMarquee","args":{
          "velocity":{"type":"state","variable":"progress"}}}]
        """
      )
    assertTrue(plain.any { "plain Kotlin value upstream" in it }, plain.toString())
  }
}
