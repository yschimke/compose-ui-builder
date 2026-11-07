package ee.schimke.composeai.uibuilder.hostlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The JVM engine gives the answers every host-logic engine must: `host-logic-parity-v1.json`, which
 * the browser's engine is held to by `scripts/ui-builder-web-smoke/host-logic-engine.test.mjs`. So
 * a simulation on the server and a press on the canvas cannot disagree.
 */
class HostLogicParityTest {
  private val fixture =
    Json.parseToJsonElement(
        File("../docs/design/fixtures/ui-builder/host-logic-parity-v1.json").readText(),
      )
      .jsonObject

  @Test
  fun `the JVM engine answers as the browser's does`() {
    // A short budget keeps the runaway case quick; the rest finish in a few milliseconds.
    val engine = JvmHostLogicEngine(callBudgetMs = 300)
    for (case in fixture.getValue("cases").jsonArray.map { it.jsonObject }) {
      val name = case.string("name")
      val loaded = engine.load(case.string("source"))
      case["loadError"]?.let { expected ->
        val message = loaded.exceptionOrNull()?.message ?: fail("$name: loaded")
        assertTrue(expected.jsonPrimitive.content in message, "$name: $message")
        return@let
      }
      if ("loadError" in case) continue
      val program = loaded.getOrElse { fail("$name: ${it.message}") }
      try {
        for (call in case.getValue("calls").jsonArray.map { it.jsonObject }) {
          val answer =
            program.call(
              call.string("function"),
              call.getValue("arguments").toString(),
              call.getValue("now").jsonPrimitive.long,
              call.getValue("seed").jsonPrimitive.content.toInt(),
            )
          val error = call["error"]?.jsonPrimitive?.content
          if (error != null) {
            val message = answer.exceptionOrNull()?.message ?: fail("$name: answered $answer")
            assertTrue(error in message, "$name: expected `$error` in `$message`")
          } else {
            assertEquals(
              call.getValue("result"),
              Json.parseToJsonElement(answer.getOrElse { fail("$name: ${it.message}") }),
              name,
            )
          }
        }
      } finally {
        program.close()
      }
    }
  }

  private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
