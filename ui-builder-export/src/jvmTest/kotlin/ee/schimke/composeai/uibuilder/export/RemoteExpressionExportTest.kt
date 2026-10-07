package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Computed values are written as the Remote operators the player evaluates, never folded to the
 * value they have at authoring time: a clock written as `10` is a picture of a clock.
 */
class RemoteExpressionExportTest {
  private val states = setOf("count", "on")

  private fun formula(text: String): JsonObject = UiExpressions.parseFormula(text, states)

  private fun emit(
    properties: Map<String, JsonElement>,
    modifiers: List<JsonObject> = emptyList(),
  ): Pair<String, List<String>> {
    val refusals = mutableListOf<String>()
    val emitter =
      RemoteContentEmitter(
        UiBuilderDocument(
          schema = "ui-builder-design-v1",
          id = "expressions",
          title = "Expressions",
          revision = 1,
          catalogPin = JsonObject(emptyMap()),
          environment = JsonObject(emptyMap()),
          stateVariables =
            JsonObject(
              mapOf(
                "count" to state("int", JsonPrimitive(3)),
                "on" to state("bool", JsonPrimitive(true)),
              )
            ),
          roots = listOf("label"),
          nodes =
            mapOf(
              "label" to
                UiBuilderNode(
                  "label",
                  REMOTE_TEXT_COMPONENT_ID,
                  properties = JsonObject(properties),
                  modifiers = JsonArray(modifiers),
                )
            ),
        ),
        refusals,
      )
    val source = emitter.emit("label", 1).joinToString("\n")
    return (source +
      "\n" +
      emitter.stateLocals().joinToString("\n") +
      "\n" +
      emitter.imports(null).joinToString("\n")) to refusals
  }

  private fun state(type: String, initial: JsonPrimitive) =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive("value"),
        "valueType" to JsonPrimitive(type),
        "initialValue" to initial,
      )
    )

  @Test
  fun `text computed from state is a RemoteString expression over the state`() {
    val (source, refusals) =
      emit(mapOf("text" to formula("concat(count + 1, \" items\", select(on, \"!\", \"\"))")))

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(
      source,
      "((count + 1.ri).toRemoteString() + \" items\".rs + on.select(\"!\".rs, \"\".rs))",
    )
    assertContains(source, "val count = rememberMutableRemoteInt(3)")
    assertContains(source, "androidx.compose.remote.creation.compose.state.ri")
  }

  @Test
  fun `a modifier argument can follow the clock`() {
    val (source, refusals) =
      emit(
        mapOf(
          "text" to
            JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("|")))
        ),
        modifiers =
          listOf(
            JsonObject(
              mapOf(
                "type" to JsonPrimitive("rotate"),
                "degrees" to formula("time.secondOfHour % 60 * 6"),
              )
            ),
            JsonObject(
              mapOf("type" to JsonPrimitive("alpha"), "alpha" to formula("select(on, 1.0, 0.5)"))
            ),
          ),
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "rotate(((RemoteTime().Seconds() % 60.rf) * 6.rf))")
    assertContains(source, "alpha(on.select(1.rf, 0.5f.rf))")
    assertContains(source, "androidx.compose.remote.creation.compose.layout.RemoteTime")
  }

  @Test
  fun `math functions import their top-level operator`() {
    val (source, refusals) =
      emit(mapOf("text" to formula("toString(clamp(sin(time.continuousSecond), 0.0, 1.0))")))

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "clamp(sin(RemoteTime().ContinuousSec()), 0.rf, 1.rf).toRemoteString()")
    assertContains(source, "androidx.compose.remote.creation.compose.state.clamp")
    assertContains(source, "androidx.compose.remote.creation.compose.state.sin")
  }

  @Test
  fun `a tree that does not type is a located refusal, not source`() {
    val (_, refusals) = emit(mapOf("text" to formula("on * 2")))

    assertTrue(refusals.any { "nodes.label.text" in it && "mul" in it }, refusals.toString())
  }
}
