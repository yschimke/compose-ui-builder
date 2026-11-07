package ee.schimke.composeai.uibuilder.hostlogic

import ee.schimke.composeai.uibuilder.export.HostLogic
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The calculator from `UI_BUILDER_HOST_LOGIC.md`, run headless the way `simulate_action` runs it.
 */
class HostLogicSimulatorTest {
  private val calculator =
    """
    export function init(env) {
      return { state: { acc: 0, op: null, shown: "0", fresh: true }, values: { "calc.display": "0" } };
    }
    export function onAction(state, action) {
      const k = action.value;
      if (action.name !== "calc.key") return { state };
      let { acc, op, shown, fresh } = state;
      if ("0123456789".includes(k)) {
        shown = fresh || shown === "0" ? k : (shown + k).slice(0, 9);
        fresh = false;
      } else {
        const n = Number(shown);
        acc = op === "+" ? acc + n : op === "-" ? acc - n : n;
        op = k === "=" ? null : k;
        shown = String(acc);
        fresh = true;
      }
      return { state: { acc, op, shown, fresh }, values: { "calc.display": shown } };
    }
    """
      .trimIndent()

  private fun design(source: String?) =
    Json.decodeFromJsonElement<UiBuilderDocument>(
      Json.parseToJsonElement(
          """
        {"schema":"ui-builder-design-v1","id":"calc","title":"Calculator","revision":1,
         "catalogPin":{},"environment":{},
         "stateVariables":{
           "calc.display":{"type":"text","valueType":"string","initialValue":"–",
             "nullable":false,"persistence":"host"},
           "local":{"type":"text","valueType":"string","initialValue":"","nullable":false}},
         "roots":[],"nodes":{}}
        """,
        )
        .jsonObject
        .let {
          if (source == null) it else JsonObject(it + ("hostLogic" to HostLogic.encode(source)))
        },
    )

  private fun key(k: String) =
    JsonObject(mapOf("name" to JsonPrimitive("calc.key"), "value" to JsonPrimitive(k)))

  @Test
  fun `the calculator shows 1, 1, 2, 3 for 1 + 2 =`() {
    val run = HostLogicSimulator.simulate(design(calculator), listOf("1", "+", "2", "=").map(::key))
    assertNull(run.stopped)
    assertEquals(
      listOf("0", "1", "1", "2", "3"),
      run.steps.map { it.values.getValue("calc.display") },
    )
    assertEquals(
      listOf(null, "calc.key", "calc.key", "calc.key", "calc.key"),
      run.steps.map { it.action },
    )
    assertEquals(mapOf("calc.display" to "3"), run.steps.last().set)
    val json = run.toJson().toString()
    assertContains(json, "\"call\":\"onAction\"")
  }

  @Test
  fun `a failing step is reported and the next action still sent`() {
    val source =
      """
      export function init() { return { state: 0, values: { "calc.display": "0" } }; }
      export function onAction(state, action) {
        if (action.name === "boom") throw new Error("boom");
        if (action.name === "local") return { values: { local: "x" } };
        return { state: state + 1, values: { "calc.display": String(state + 1) } };
      }
      """
        .trimIndent()
    val name = { n: String -> JsonObject(mapOf("name" to JsonPrimitive(n))) }
    val run =
      HostLogicSimulator.simulate(design(source), listOf("next", "boom", "local", "next").map(name))
    assertNull(run.stopped)
    assertEquals(listOf(null, null, "Error: boom", null, null).size, run.steps.size)
    assertEquals("Error: boom", run.steps[2].error)
    assertContains(run.steps[3].error!!, "the design's own state")
    assertEquals("2", run.steps[4].values["calc.display"])
  }

  @Test
  fun `a program that overruns its budget stops the simulation`() {
    val run =
      HostLogicSimulator.simulate(
        design("export function init() { for (;;) {} } export function onAction() {}"),
        listOf(key("1")),
      )
    assertContains(run.stopped!!, "interrupted")
    assertEquals(1, run.steps.size)
  }

  @Test
  fun `check reports what stops an engine, and a design without logic says nothing`() {
    assertEquals(emptyList(), HostLogicSimulator.check(design(null)).diagnostics)
    assertNull(HostLogicSimulator.check(design(null)).digest)

    val ok = HostLogicSimulator.check(design(calculator))
    assertEquals(emptyList(), ok.diagnostics)
    assertTrue(ok.digest!!.startsWith("sha256:"))

    val syntax = HostLogicSimulator.check(design("export function init( {"))
    assertEquals("hostLogic", syntax.diagnostics.single().field)
    val badInit =
      HostLogicSimulator.check(
        design(
          "export function init() { return { values: { nope: 1 } }; } export function onAction() {}",
        ),
      )
    assertContains(badInit.diagnostics.single().message, "init: host logic set `nope`")
  }

  @Test
  fun `actions a host action could not carry are refused before anything runs`() {
    assertFailsWith<IllegalArgumentException> {
      HostLogicSimulator.simulate(
        design(calculator),
        listOf(JsonObject(mapOf("name" to JsonPrimitive("x"), "value" to JsonPrimitive(true)))),
      )
    }
    assertEquals(
      "the design has no host logic",
      HostLogicSimulator.simulate(design(null), emptyList()).stopped,
    )
  }
}
