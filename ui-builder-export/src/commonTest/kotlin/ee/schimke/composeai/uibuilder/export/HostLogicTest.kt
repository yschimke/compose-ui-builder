package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** What a host-logic program is handed, and what of its answer the canvas may draw. */
class HostLogicTest {
  private val document =
    Json.decodeFromJsonElement<UiBuilderDocument>(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"calc","title":"Calc","revision":1,
         "catalogPin":{},"environment":{"fixedTime":"2024-05-16T12:00:00Z","locale":"en-US"},
         "stateVariables":{
           "calc.display":{"type":"text","valueType":"string","initialValue":"0",
             "nullable":false,"persistence":"host"},
           "calc.memory":{"type":"value","valueType":"float","initialValue":null,
             "nullable":true,"persistence":"host"},
           "pressed":{"type":"value","valueType":"bool","initialValue":false,
             "nullable":false,"persistence":"session"}},
         "roots":[],"nodes":{},
         "hostLogic":{"language":"javascript","source":"export function init() {}"}}
        """,
      ) as JsonObject,
    )

  @Test
  fun `a program is read off the document, and one an engine cannot take is not`() {
    assertEquals(
      HostLogic.Program("javascript", "export function init() {}"),
      HostLogic.of(document),
    )
    assertNull(HostLogic.of(document.copy(hostLogic = null)))
    val kotlin =
      JsonObject(mapOf("language" to JsonPrimitive("kotlin"), "source" to JsonPrimitive("")))
    assertContains(HostLogic.issue(kotlin)!!, "`kotlin` is not supported")
    assertNull(HostLogic.of(document.copy(hostLogic = kotlin)))
    val huge = HostLogic.encode("x".repeat(HostLogic.MAX_SOURCE_LENGTH + 1))
    assertContains(HostLogic.issue(huge)!!, "at most 64 KiB")
  }

  @Test
  fun `the program sees its host inputs typed, the environment and a fixed clock`() {
    val env = HostLogic.environment(document, mapOf("calc.display" to "12", "calc.memory" to "1.5"))

    assertEquals(
      Json.parseToJsonElement("""{"calc.display":"12","calc.memory":1.5}"""),
      env["values"],
    )
    assertEquals(document.environment, env["environment"])
    assertEquals(JsonPrimitive(1715860800000L), env["now"])
    assertEquals(
      Json.parseToJsonElement(
        """[{},{"name":"calc.key","value":"7"},$env]""",
      ),
      Json.parseToJsonElement(
        HostLogic.actionArguments(
          document,
          JsonObject(emptyMap()),
          "calc.key",
          JsonPrimitive("7"),
          mapOf("calc.display" to "12", "calc.memory" to "1.5"),
        ),
      ),
    )
  }

  @Test
  fun `an answer sets host inputs as the canvas holds them`() {
    val step =
      assertIs<HostLogic.Step.Ok>(
        HostLogic.step(
          document,
          """{"state":{"acc":3},"values":{"calc.display":"3","calc.memory":null}}""",
        ),
      )
    assertEquals(Json.parseToJsonElement("""{"acc":3}"""), step.state)
    assertEquals(mapOf("calc.display" to "3", "calc.memory" to null), step.values)

    // A number shown as text is text: a display string may well be computed.
    assertEquals(
      mapOf("calc.display" to "7"),
      assertIs<HostLogic.Step.Ok>(HostLogic.step(document, """{"values":{"calc.display":7}}"""))
        .values,
    )
  }

  @Test
  fun `an answer that is not the host's to give is refused by name`() {
    fun failure(json: String) =
      assertIs<HostLogic.Step.Failed>(HostLogic.step(document, json)).message

    assertContains(failure("""null"""), "must return an object")
    assertContains(failure("""{"values":[1]}"""), "must be an object")
    assertContains(failure("""{"values":{"pressed":true}}"""), "the design's own state")
    assertContains(failure("""{"values":{"calc.other":"1"}}"""), "does not declare")
    assertContains(failure("""{"values":{"calc.display":null}}"""), "cannot hold")
    assertContains(failure("""{"values":{"calc.memory":"lots"}}"""), "float host input")
  }
}
