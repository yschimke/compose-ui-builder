package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class UiExpressionsTest {
  private val scope =
    UiExpressions.Scope(
      mapOf(
        "count" to UiValueKind.INT,
        "progress" to UiValueKind.FLOAT,
        "on" to UiValueKind.BOOL,
        "name" to UiValueKind.STRING,
      )
    )
  private val states = scope.stateKinds.keys

  private fun parse(text: String): JsonObject = UiExpressions.parseFormula(text, states)

  private fun typed(text: String): UiExpressions.Expr =
    assertIs<UiExpressions.Checked.Ok>(UiExpressions.check(parse(text), scope)).expr

  private fun evaluate(
    text: String,
    state: Map<String, String?> = emptyMap(),
    clock: UiExpressions.Clock = UiExpressions.Clock.DEFAULT,
  ): Any = UiExpressions.evaluate(typed(text), UiExpressions.Environment(state, clock = clock))

  @Test
  fun `infix operators follow C precedence`() {
    assertEquals(7, evaluate("1 + 2 * 3"))
    assertEquals(9, evaluate("(1 + 2) * 3"))
    assertEquals(true, evaluate("1 + 1 == 2 && !false"))
    assertEquals(-4, evaluate("-4"))
  }

  @Test
  fun `integer arithmetic stays integral and mixing promotes to float`() {
    assertEquals(UiValueKind.INT, typed("count + 1").kind)
    assertEquals(UiValueKind.FLOAT, typed("count + 0.5").kind)
    assertEquals(3, evaluate("7 / 2"))
    assertEquals(3.5, evaluate("7 / 2.0"))
  }

  @Test
  fun `state reads evaluate at the preview state`() {
    assertEquals(6, evaluate("count + 1", mapOf("count" to "5")))
    assertEquals("Hi Ada", evaluate("concat(\"Hi \", name)", mapOf("name" to "Ada")))
    assertEquals("On", evaluate("select(on, \"On\", \"Off\")", mapOf("on" to "true")))
  }

  @Test
  fun `text concatenation prints whole numbers without a fraction`() {
    assertEquals("3 items", evaluate("concat(count, \" items\")", mapOf("count" to "3")))
    assertEquals("50%", evaluate("concat(progress * 100, \"%\")", mapOf("progress" to "0.5")))
  }

  @Test
  fun `the clock follows the player's fields at the fixed time`() {
    val clock = UiExpressions.Clock.of("2024-05-16T12:34:56Z")
    assertEquals(12.0, evaluate("time.hour", clock = clock))
    assertEquals(754.0, evaluate("time.minuteOfDay", clock = clock))
    assertEquals(2096.0, evaluate("time.secondOfHour", clock = clock))
    // 2024-05-16 is a Thursday.
    assertEquals(4.0, evaluate("time.dayOfWeek", clock = clock))
    assertEquals(UiExpressions.Clock.DEFAULT, UiExpressions.Clock.of(null))
  }

  @Test
  fun `a tree that does not type is refused with the operand it is about`() {
    val issue =
      assertIs<UiExpressions.Checked.Issue>(
        UiExpressions.check(parse("name * 2"), scope, "nodes.label.text")
      )
    assertTrue("nodes.label.text" in issue.message, issue.message)
    assertTrue("mul" in issue.message, issue.message)
    assertIs<UiExpressions.Checked.Issue>(
      UiExpressions.check(
        JsonObject(mapOf("type" to JsonPrimitive("state"), "variable" to JsonPrimitive("nope"))),
        scope,
      )
    )
  }

  @Test
  fun `formula text round-trips through the tree`() {
    for (text in
      listOf(
        "time.secondOfHour / 60 * 6",
        "count + 1",
        "select(on, \"On\", \"Off\")",
        "clamp(progress, 0.0, 1.0)",
        "(count + 1) * 2",
        "!on || count > 3",
        "progress ^ 2",
        "-progress ^ 2",
        "(-progress) ^ 2",
        "(-2) ^ 2",
        "2 ^ 3 ^ 2",
        "(2 ^ 3) ^ 2",
        "sqrt(progress ^ 2 + 1) * 2",
      )) {
      assertEquals(text, UiExpressions.format(parse(text)), text)
    }
  }

  @Test
  fun `powers bind like maths`() {
    assertEquals(-4.0, evaluate("-2 ^ 2"))
    assertEquals(4.0, evaluate("(-2) ^ 2"))
    assertEquals(512.0, evaluate("2 ^ 3 ^ 2"))
    assertEquals(0.5, evaluate("2 ^ -1"))
    assertEquals("pow", parse("count ^ 2")["op"]!!.jsonPrimitive.content)
  }

  @Test
  fun `unknown names and functions are formula errors`() {
    assertFailsWith<UiExpressions.FormulaError> { parse("missing + 1") }
    assertFailsWith<UiExpressions.FormulaError> { parse("frobnicate(1)") }
    assertFailsWith<UiExpressions.FormulaError> { parse("1 +") }
  }

  @Test
  fun `the shape check runs without the document`() {
    assertNull(UiExpressions.shapeIssue(parse("count + 1")))
    assertNotNull(
      UiExpressions.shapeIssue(
        JsonObject(mapOf("type" to JsonPrimitive("expr"), "op" to JsonPrimitive("add")))
      )
    )
    assertNotNull(
      UiExpressions.shapeIssue(
        JsonObject(mapOf("type" to JsonPrimitive("system"), "value" to JsonPrimitive("time.year")))
      )
    )
  }

  @Test
  fun `a computed value matches the catalog by the kind it produces`() {
    val declarations =
      mapOf(
        "count" to
          JsonObject(mapOf("valueType" to JsonPrimitive("int"), "initialValue" to JsonPrimitive(0)))
      )
    val number = JsonPrimitive("number")
    val text = JsonPrimitive("string")
    val counter = parse("count + 1".replace("count", "count"))
    assertEquals(
      true,
      stateBindingMatchesCatalog(counter, number, emptyList(), declarations, "progress"),
    )
    // Any printable value may fill text.
    assertEquals(true, stateBindingMatchesCatalog(counter, text, emptyList(), declarations, "text"))
    // A number is not a colour.
    assertEquals(
      false,
      stateBindingMatchesCatalog(counter, text, emptyList(), declarations, "color"),
    )
    val evaluated =
      UiExpressions.evaluateWrapped(
        typed("count + 1"),
        UiExpressions.Environment(mapOf("count" to "41")),
      )
    assertEquals("int", evaluated["type"]!!.jsonPrimitive.content)
    assertEquals(42, evaluated["value"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun `the preview computes in Float, as the player does`() {
    assertEquals(true, evaluate("16777216.0 + 1.0 == 16777216.0"))
  }

  @Test
  fun `a fixed time that names no instant draws at the default`() {
    assertEquals(UiExpressions.Clock.DEFAULT, UiExpressions.Clock.of("2024-13-16T12:00:00Z"))
    assertEquals(UiExpressions.Clock.DEFAULT, UiExpressions.Clock.of("2024-05-16T25:00:00Z"))
  }

  @Test
  fun `nullable or uninitialised state is not an operand`() {
    fun kind(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) =
      UiExpressions.Scope.stateKind(JsonObject(mapOf(*fields)))
    assertEquals(
      null,
      kind(
        "valueType" to JsonPrimitive("int"),
        "initialValue" to JsonPrimitive(1),
        "nullable" to JsonPrimitive(true),
      ),
    )
    assertEquals(null, kind("valueType" to JsonPrimitive("int")))
    assertEquals(
      null,
      kind("valueType" to JsonPrimitive("int"), "initialValue" to JsonPrimitive("x")),
    )
    assertEquals(
      UiValueKind.FLOAT,
      kind("valueType" to JsonPrimitive("float"), "initialValue" to JsonPrimitive(1)),
    )
  }

  @Test
  fun `an object type does not stand in for the kind a formula produces`() {
    val declarations =
      mapOf(
        "on" to
          JsonObject(
            mapOf("valueType" to JsonPrimitive("bool"), "initialValue" to JsonPrimitive(true))
          )
      )
    val numberOrBinding =
      kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("object")))
    val flag = UiExpressions.parseFormula("!on", setOf("on"))
    assertEquals(
      false,
      stateBindingMatchesCatalog(flag, numberOrBinding, emptyList(), declarations, "progress"),
    )
    // A structural value such as `showByState` holds nothing a formula produces.
    assertEquals(
      false,
      stateBindingMatchesCatalog(
        flag,
        JsonPrimitive("object"),
        emptyList(),
        declarations,
        "showByState",
      ),
    )
  }
}
