package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.HostLogic
import ee.schimke.composeai.uibuilder.export.HostLogicProgram
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.hostlogic.HostLogicController
import ee.schimke.composeai.uibuilder.hostlogic.HostLogicEngine
import ee.schimke.composeai.uibuilder.hostlogic.HostLogicStatus
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasHostAction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The controller between a canvas and an engine: what it hands the program, what it draws from the
 * answer, and what it does when the program fails. The engine here is a counter written in Kotlin
 * that answers the way the JavaScript would.
 */
class HostLogicControllerTest {
  private val document =
    Json.decodeFromJsonElement<UiBuilderDocument>(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"counter","title":"Counter","revision":1,
         "catalogPin":{},"environment":{},
         "stateVariables":{
           "count.display":{"type":"text","valueType":"string","initialValue":"–",
             "nullable":false,"persistence":"host"}},
         "roots":[],"nodes":{}}
        """,
      ) as JsonObject,
    )
  private val withLogic = document.copy(hostLogic = HostLogic.encode("// a counter"))

  /** Counts presses; `boom` throws, `bad` answers a value the design does not declare. */
  private class CounterEngine : HostLogicEngine {
    val calls = mutableListOf<String>()

    override suspend fun load(source: String): Result<HostLogicProgram> =
      Result.success(
        object : HostLogicProgram {
          override fun call(
            function: String,
            argumentsJson: String,
            now: Long,
            seed: Int,
          ): Result<String> {
            calls += "$function#$seed"
            val arguments = Json.parseToJsonElement(argumentsJson) as JsonArray
            if (function == "init")
              return Result.success("""{"state":{"n":0},"values":{"count.display":"0"}}""")
            val n = arguments[0].jsonObject.getValue("n").jsonPrimitive.int
            return when (arguments[1].jsonObject.getValue("name").jsonPrimitive.content) {
              "boom" -> Result.failure(IllegalStateException("Error: boom"))
              "bad" -> Result.success("""{"values":{"elsewhere":"1"}}""")
              else ->
                Result.success("""{"state":{"n":${n + 1}},"values":{"count.display":"${n + 1}"}}""")
            }
          }

          override fun close() {}
        },
      )
  }

  private fun press(name: String) = CanvasHostAction("key", "click", name, null)

  @Test
  fun `init draws the program's first values, and each action its next`() = runBlocking {
    val engine = CounterEngine()
    val controller = HostLogicController(engine)
    controller.load(withLogic)

    assertEquals(HostLogicStatus.Running, controller.status)
    assertEquals(mapOf("count.display" to "0"), controller.values)

    controller.onAction(press("count.add"))
    controller.onAction(press("count.add"))
    assertEquals(mapOf("count.display" to "2"), controller.values)
    assertEquals(Json.parseToJsonElement("""{"n":2}"""), controller.state)

    // A reset starts again from init, with the same seeds as the first run.
    controller.reset()
    assertEquals(mapOf("count.display" to "0"), controller.values)
    assertEquals(listOf("init#1", "onAction#2", "onAction#3", "init#1"), engine.calls)
  }

  @Test
  fun `a failure keeps what was drawn, and the next good answer recovers`() = runBlocking {
    val controller = HostLogicController(CounterEngine())
    controller.load(withLogic)
    controller.onAction(press("count.add"))

    controller.onAction(press("boom"))
    assertEquals(HostLogicStatus.Failed("Error: boom"), controller.status)
    assertEquals(mapOf("count.display" to "1"), controller.values)

    controller.onAction(press("bad"))
    assertContains(
      assertIs<HostLogicStatus.Failed>(controller.status).message,
      "which the design does not declare",
    )

    controller.onAction(press("count.add"))
    assertEquals(HostLogicStatus.Running, controller.status)
    assertEquals(mapOf("count.display" to "2"), controller.values)
  }

  @Test
  fun `without a program or an engine nothing runs, and the inputs show their initial values`() =
    runBlocking {
      val none = HostLogicController(CounterEngine())
      none.load(document)
      assertEquals(HostLogicStatus.None, none.status)
      none.onAction(press("count.add"))
      assertEquals(mapOf("count.display" to "–"), none.values)

      val unavailable = HostLogicController(engine = null)
      unavailable.load(withLogic)
      assertEquals(HostLogicStatus.Unavailable, unavailable.status)
    }

  @Test
  fun `a program that cannot load says why`() = runBlocking {
    val failing =
      object : HostLogicEngine {
        override suspend fun load(source: String): Result<HostLogicProgram> =
          Result.failure(IllegalArgumentException("SyntaxError: expecting ';'"))
      }
    val controller = HostLogicController(failing)
    controller.load(withLogic)

    assertEquals(HostLogicStatus.Failed("SyntaxError: expecting ';'"), controller.status)
    assertTrue(controller.values.isNotEmpty())
    assertEquals(JsonPrimitive("–").content, controller.values.getValue("count.display"))
  }
}
