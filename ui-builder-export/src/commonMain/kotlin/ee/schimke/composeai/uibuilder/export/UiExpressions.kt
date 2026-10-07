package ee.schimke.composeai.uibuilder.export

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Computed property values: `{"type":"expr","op":…,"args":[…]}` and `{"type":"system","value":…}`.
 *
 * A property used to be a literal, a state read or a loop binding, so nothing an author built could
 * change while the document played: a clock, a gauge driven by progress or a label that counts
 * needed a value the *player* evaluates. An expression is that value, written as a tree rather than
 * a string so every writer — the inspector's formula field, MCP, a hand-edited fixture — produces
 * the same validated shape, and so an error can name the operand it is about.
 *
 * Operands are ordinary property wrappers — `float`, `int`, `bool`, `string`, `color`, `state`,
 * `binding` — plus `system` for a value the host supplies (the clock) and nested `expr`s. The tree
 * is typed: [check] infers each operation's [UiValueKind] from its operands' and refuses what does
 * not type, so an export never discovers that `"a" * 2` means nothing.
 *
 * Three readers agree on the meaning here, and only here:
 * - the canvas, through [evaluate], at the preview state and the environment's fixed time;
 * - the Remote Kotlin writer, which lowers each node to the `RemoteFloat`/`RemoteInt`/
 *   `RemoteBoolean`/`RemoteString` operator the player evaluates ([RemoteExpressionWriter]);
 * - the inspector, which shows and accepts [format]/[parseFormula]'s text.
 */
object UiExpressions {
  const val EXPR: String = "expr"
  const val SYSTEM: String = "system"

  /** A value the host supplies at play time, and its stand-in at preview. */
  class SystemValue(
    val id: String,
    val kind: UiValueKind,
    val label: String,
    /** The Remote Kotlin that reads it. */
    val remote: String,
  )

  /**
   * The clock, as the Remote Compose player publishes it. Values follow `RemoteClock`: the hour is
   * 0–23, minutes count from midnight, seconds count within the hour, day of week is 1 (Monday)
   * – 7.
   */
  val SYSTEM_VALUES: Map<String, SystemValue> =
    listOf(
        SystemValue("time.hour", UiValueKind.FLOAT, "Hour of day (0–23)", "RemoteTime().Hour()"),
        SystemValue(
          "time.minuteOfDay",
          UiValueKind.FLOAT,
          "Minutes since midnight (0–1439)",
          "RemoteTime().Minutes()",
        ),
        SystemValue(
          "time.secondOfHour",
          UiValueKind.FLOAT,
          "Seconds into the hour (0–3599)",
          "RemoteTime().Seconds()",
        ),
        SystemValue(
          "time.continuousSecond",
          UiValueKind.FLOAT,
          "Seconds into the hour, continuously",
          "RemoteTime().ContinuousSec()",
        ),
        SystemValue(
          "time.dayOfWeek",
          UiValueKind.FLOAT,
          "Day of week (1 = Monday)",
          "RemoteTime().DayOfWeek()",
        ),
        SystemValue(
          "time.dayOfMonth",
          UiValueKind.FLOAT,
          "Day of month (1–31)",
          "RemoteTime().DayOfMonth()",
        ),
        SystemValue(
          "time.utcOffset",
          UiValueKind.FLOAT,
          "Offset from UTC in seconds",
          "RemoteTime().UtcOffset()",
        ),
      )
      .associateBy { it.id }

  /** Every operation, its arity, and the label the inspector shows. */
  enum class Op(val wire: String, val arity: IntRange, val symbol: String? = null) {
    ADD("add", 2..2, "+"),
    SUB("sub", 2..2, "-"),
    MUL("mul", 2..2, "*"),
    DIV("div", 2..2, "/"),
    MOD("mod", 2..2, "%"),
    NEG("neg", 1..1),
    MIN("min", 2..2),
    MAX("max", 2..2),
    CLAMP("clamp", 3..3),
    ABS("abs", 1..1),
    FLOOR("floor", 1..1),
    CEIL("ceil", 1..1),
    ROUND("round", 1..1),
    SQRT("sqrt", 1..1),
    POW("pow", 2..2),
    /** Radians, as the player's `sin`. */
    SIN("sin", 1..1),
    COS("cos", 1..1),
    TAN("tan", 1..1),
    LERP("lerp", 3..3),
    TO_INT("toInt", 1..1),
    TO_FLOAT("toFloat", 1..1),
    EQ("eq", 2..2, "=="),
    NE("ne", 2..2, "!="),
    LT("lt", 2..2, "<"),
    LE("le", 2..2, "<="),
    GT("gt", 2..2, ">"),
    GE("ge", 2..2, ">="),
    AND("and", 2..2, "&&"),
    OR("or", 2..2, "||"),
    NOT("not", 1..1),
    SELECT("select", 3..3),
    CONCAT("concat", 1..16),
    TO_STRING("toString", 1..1);

    companion object {
      val byWire: Map<String, Op> = entries.associateBy { it.wire }
    }
  }

  /** A typed expression tree. */
  sealed interface Expr {
    val kind: UiValueKind

    data class Literal(override val kind: UiValueKind, val value: JsonPrimitive) : Expr

    data class State(val variable: String, override val kind: UiValueKind) : Expr

    data class Binding(val key: String, override val kind: UiValueKind) : Expr

    data class System(val value: SystemValue) : Expr {
      override val kind: UiValueKind
        get() = value.kind
    }

    data class Call(val op: Op, val args: List<Expr>, override val kind: UiValueKind) : Expr
  }

  /** What [check] needs to type a tree: declared state kinds and, inside a loop, row fields. */
  class Scope(
    val stateKinds: Map<String, UiValueKind>,
    val bindingKinds: (String) -> UiValueKind? = { null },
  ) {
    companion object {
      /** The state kinds a document declares, from each variable's `valueType`/initial value. */
      fun of(document: UiBuilderDocument): Scope =
        Scope(
          document.stateVariables
            .mapNotNull { (name, declaration) ->
              stateKind(declaration as? JsonObject)?.let { name to it }
            }
            .toMap()
        )

      fun stateKind(declaration: JsonObject?): UiValueKind? {
        declaration ?: return null
        val declared = (declaration["valueType"] as? JsonPrimitive)?.contentOrNull
        declared
          ?.let { UiValueKind.fromWire(it) }
          ?.let {
            return it
          }
        val initial = declaration["initialValue"] as? JsonPrimitive ?: return null
        return when {
          initial is JsonNull -> null
          initial.isString -> UiValueKind.STRING
          initial.booleanOrNull != null -> UiValueKind.BOOL
          initial.content.toLongOrNull() != null -> UiValueKind.INT
          else -> UiValueKind.FLOAT
        }
      }
    }
  }

  /**
   * Whether the published wire contract linked into this build can carry a computed value.
   *
   * `UiValueV1` in compose-preview-contracts is a closed hierarchy, and until it has `expr` and
   * `system` subtypes a design holding one cannot be written as a `.uid` file or committed through
   * a server. The canvas and every export read computed values regardless; what waits on this is
   * offering them in the inspector, so an author is never handed a value their save then refuses.
   * Asked of the serializer itself, so the day the contracts add the types this turns true with no
   * change here.
   */
  val wireSupported: Boolean by lazy {
    runCatching {
      WIRE_JSON.decodeFromString(
        ee.schimke.composeai.uibuilder.protocol.UiValueV1.serializer(),
        """{"type":"system","value":"time.hour"}""",
      )
    }
      .isSuccess
  }

  private val WIRE_JSON = kotlinx.serialization.json.Json { classDiscriminator = "type" }

  /** Whether [value] is a computed wrapper: an `expr` or a `system` read. */
  fun isComputed(value: JsonElement?): Boolean {
    val type = ((value as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
    return type == EXPR || type == SYSTEM
  }

  /**
   * The shape-only check the reducers run on a write, where the document's state is not to hand.
   * Null when [value] is a well-formed `expr` or `system` wrapper.
   */
  fun shapeIssue(value: JsonObject, depth: Int = 0): String? {
    if (depth > MAX_DEPTH) return "expression nests deeper than $MAX_DEPTH levels"
    return when ((value["type"] as? JsonPrimitive)?.contentOrNull) {
      SYSTEM -> {
        val id = (value["value"] as? JsonPrimitive)?.contentOrNull
        when {
          value.keys != setOf("type", "value") ->
            "system wrapper must contain exactly type and value"
          id !in SYSTEM_VALUES ->
            "system value `$id` is not one of ${SYSTEM_VALUES.keys.joinToString()}"
          else -> null
        }
      }
      EXPR -> {
        val opName = (value["op"] as? JsonPrimitive)?.contentOrNull
        val op = opName?.let(Op.byWire::get)
        val args = value["args"] as? JsonArray
        when {
          value.keys != setOf("type", "op", "args") ->
            "expr wrapper must contain exactly type, op and args"
          op == null -> "expr op `$opName` is not one of ${Op.entries.joinToString { it.wire }}"
          args == null -> "expr args must be an array"
          args.size !in op.arity -> "`${op.wire}` takes ${op.arity.describe()}, not ${args.size}"
          else ->
            args.withIndex().firstNotNullOfOrNull { (index, arg) ->
              val nested =
                arg as? JsonObject ?: return@firstNotNullOfOrNull "arg $index must be a typed value"
              val type = (nested["type"] as? JsonPrimitive)?.contentOrNull
              when (type) {
                EXPR,
                SYSTEM -> shapeIssue(nested, depth + 1)
                in OPERAND_LITERALS ->
                  if (nested.keys == setOf("type", "value") && nested["value"] is JsonPrimitive)
                    null
                  else "literal wrapper must contain exactly type and value"
                "state" ->
                  if (nested.keys == setOf("type", "variable")) null
                  else "state wrapper must contain exactly type and a non-empty variable"
                "binding" ->
                  if (nested.keys == setOf("type", "value")) null
                  else "binding wrapper must contain exactly type and a non-empty value"
                else -> "operand type `$type` cannot appear in an expression"
              }?.let { "arg $index: $it" }
            }
        }
      }
      else -> "not an expression"
    }
  }

  /** The literal wrappers an expression may take as operands. */
  val OPERAND_LITERALS: Set<String> = setOf("float", "int", "bool", "string", "color")

  /** A typed tree, or the located reason [value] does not type. */
  sealed interface Checked {
    data class Ok(val expr: Expr) : Checked

    data class Issue(val message: String) : Checked
  }

  fun check(value: JsonElement, scope: Scope, where: String = "value"): Checked {
    shapeIssue(value as? JsonObject ?: return Checked.Issue("$where: not an expression"))?.let {
      if ((value["type"] as? JsonPrimitive)?.contentOrNull in setOf(EXPR, SYSTEM))
        return Checked.Issue("$where: $it")
    }
    return try {
      Checked.Ok(typed(value, scope, where))
    } catch (failure: ExpressionIssue) {
      Checked.Issue(failure.message.orEmpty())
    }
  }

  private class ExpressionIssue(message: String) : Exception(message)

  private fun typed(value: JsonElement, scope: Scope, where: String): Expr {
    val obj = value as? JsonObject ?: throw ExpressionIssue("$where: not a typed value")
    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
    return when (type) {
      "float",
      "int",
      "bool",
      "string",
      "color" -> literal(type, obj["value"] as? JsonPrimitive, where)
      "state" -> {
        val variable =
          (obj["variable"] as? JsonPrimitive)?.contentOrNull
            ?: throw ExpressionIssue("$where: state read names no variable")
        val kind =
          scope.stateKinds[variable]
            ?: throw ExpressionIssue("$where: `$variable` is not a declared, typed state variable")
        Expr.State(variable, kind)
      }
      "binding" -> {
        val key =
          (obj["value"] as? JsonPrimitive)?.contentOrNull
            ?: throw ExpressionIssue("$where: binding names no field")
        val kind =
          scope.bindingKinds(key)
            ?: throw ExpressionIssue("$where: the row field `$key` is not in scope here")
        Expr.Binding(key, kind)
      }
      SYSTEM -> {
        val id = (obj["value"] as? JsonPrimitive)?.contentOrNull
        Expr.System(
          SYSTEM_VALUES[id] ?: throw ExpressionIssue("$where: unknown system value `$id`")
        )
      }
      EXPR -> {
        val op =
          Op.byWire[(obj["op"] as? JsonPrimitive)?.contentOrNull]
            ?: throw ExpressionIssue("$where: unknown operation")
        val args =
          (obj["args"] as? JsonArray).orEmpty().mapIndexed { index, arg ->
            typed(arg, scope, "$where.args[$index]")
          }
        if (args.size !in op.arity)
          throw ExpressionIssue("$where: `${op.wire}` takes ${op.arity.describe()}")
        Expr.Call(op, args, resultKind(op, args.map { it.kind }, where))
      }
      else -> throw ExpressionIssue("$where: `$type` cannot appear in an expression")
    }
  }

  private fun literal(type: String, value: JsonPrimitive?, where: String): Expr.Literal {
    value ?: throw ExpressionIssue("$where: literal has no value")
    val ok =
      when (type) {
        "float" -> !value.isString && value.doubleOrNull?.isFinite() == true
        "int" -> !value.isString && value.content.toIntOrNull() != null
        "bool" -> !value.isString && value.booleanOrNull != null
        "string" -> value.isString
        else -> value.isString && COLOR_LITERAL.matches(value.content)
      }
    if (!ok) throw ExpressionIssue("$where: `${value.content}` is not a $type")
    return Expr.Literal(UiValueKind.fromWire(type)!!, value)
  }

  private fun resultKind(op: Op, kinds: List<UiValueKind>, where: String): UiValueKind {
    fun fail(): Nothing =
      throw ExpressionIssue(
        "$where: `${op.wire}` does not apply to ${kinds.joinToString { it.wire }}"
      )
    fun numeric(vararg index: Int) = index.all { kinds[it].numeric }
    fun promoted(vararg index: Int) =
      if (index.all { kinds[it] == UiValueKind.INT }) UiValueKind.INT else UiValueKind.FLOAT
    return when (op) {
      Op.ADD,
      Op.SUB,
      Op.MUL,
      Op.DIV,
      Op.MOD,
      Op.MIN,
      Op.MAX -> if (numeric(0, 1)) promoted(0, 1) else fail()
      Op.CLAMP -> if (numeric(0, 1, 2)) promoted(0, 1, 2) else fail()
      Op.NEG,
      Op.ABS -> if (numeric(0)) kinds[0] else fail()
      Op.FLOOR,
      Op.CEIL,
      Op.ROUND,
      Op.SQRT,
      Op.SIN,
      Op.COS,
      Op.TAN,
      Op.TO_FLOAT -> if (numeric(0)) UiValueKind.FLOAT else fail()
      Op.POW -> if (numeric(0, 1)) UiValueKind.FLOAT else fail()
      Op.LERP -> if (numeric(0, 1, 2)) UiValueKind.FLOAT else fail()
      Op.TO_INT -> if (numeric(0)) UiValueKind.INT else fail()
      Op.EQ,
      Op.NE ->
        if (numeric(0, 1) || (kinds[0] == UiValueKind.BOOL && kinds[1] == UiValueKind.BOOL))
          UiValueKind.BOOL
        else fail()
      Op.LT,
      Op.LE,
      Op.GT,
      Op.GE -> if (numeric(0, 1)) UiValueKind.BOOL else fail()
      Op.AND,
      Op.OR ->
        if (kinds[0] == UiValueKind.BOOL && kinds[1] == UiValueKind.BOOL) UiValueKind.BOOL
        else fail()
      Op.NOT -> if (kinds[0] == UiValueKind.BOOL) UiValueKind.BOOL else fail()
      Op.SELECT ->
        when {
          kinds[0] != UiValueKind.BOOL -> fail()
          kinds[1] == kinds[2] -> kinds[1]
          kinds[1].numeric && kinds[2].numeric -> UiValueKind.FLOAT
          else -> fail()
        }
      Op.CONCAT -> if (kinds.none { it == UiValueKind.COLOR }) UiValueKind.STRING else fail()
      Op.TO_STRING -> if (kinds[0] != UiValueKind.COLOR) UiValueKind.STRING else fail()
    }
  }

  /** Whether [expr] reads anything that changes while the document plays. */
  fun isDynamic(expr: Expr): Boolean =
    when (expr) {
      is Expr.Literal -> false
      is Expr.Binding -> false
      is Expr.State,
      is Expr.System -> true
      is Expr.Call -> expr.args.any(::isDynamic)
    }

  // ---- Preview evaluation ----------------------------------------------------------------------

  /** What the canvas evaluates against: preview state, row fields and a frozen clock. */
  class Environment(
    val state: Map<String, String?> = emptyMap(),
    val bindings: (String) -> JsonPrimitive? = { null },
    val clock: Clock = Clock.DEFAULT,
  )

  /** A wall-clock instant broken into the fields the player publishes. */
  data class Clock(
    val hour: Int,
    val minute: Int,
    val second: Int,
    val dayOfWeek: Int,
    val dayOfMonth: Int,
  ) {
    fun read(id: String): Double =
      when (id) {
        "time.hour" -> hour.toDouble()
        "time.minuteOfDay" -> (hour * 60 + minute).toDouble()
        "time.secondOfHour" -> (minute * 60 + second).toDouble()
        "time.continuousSecond" -> (minute * 60 + second).toDouble()
        "time.dayOfWeek" -> dayOfWeek.toDouble()
        "time.dayOfMonth" -> dayOfMonth.toDouble()
        else -> 0.0
      }

    companion object {
      /** Wear's canonical preview time, 10:10:30, on the fixture date. */
      val DEFAULT: Clock =
        Clock(hour = 10, minute = 10, second = 30, dayOfWeek = 4, dayOfMonth = 16)

      /**
       * `environment.fixedTime`, an ISO-8601 UTC instant, or [DEFAULT] when absent or unreadable.
       */
      fun of(fixedTime: String?): Clock {
        val match = fixedTime?.let(ISO_INSTANT::matchEntire) ?: return DEFAULT
        val (year, month, day, hour, minute, second) = match.destructured
        return Clock(
          hour = hour.toInt(),
          minute = minute.toInt(),
          second = second.toInt(),
          dayOfWeek = isoDayOfWeek(year.toInt(), month.toInt(), day.toInt()),
          dayOfMonth = day.toInt(),
        )
      }

      private val ISO_INSTANT =
        Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:Z|[+-]00:?00)?""")

      /** Monday = 1 … Sunday = 7, by Sakamoto's method. */
      private fun isoDayOfWeek(year: Int, month: Int, day: Int): Int {
        val offsets = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        val y = if (month < 3) year - 1 else year
        val sunday0 = (y + y / 4 - y / 100 + y / 400 + offsets[month - 1] + day) % 7
        return if (sunday0 == 0) 7 else sunday0
      }
    }
  }

  /** The literal [expr] evaluates to, wrapped as its kind's property wrapper. */
  fun evaluateWrapped(expr: Expr, environment: Environment): JsonObject =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive(expr.kind.wire),
        "value" to evaluate(expr, environment).toJson(expr.kind),
      )
    )

  /** Evaluate [expr] the way the player would at [environment]. */
  fun evaluate(expr: Expr, environment: Environment): Any =
    when (expr) {
      is Expr.Literal ->
        when (expr.kind) {
          UiValueKind.FLOAT -> expr.value.content.toDouble()
          UiValueKind.INT -> expr.value.content.toInt()
          UiValueKind.BOOL -> expr.value.content.toBoolean()
          UiValueKind.STRING,
          UiValueKind.COLOR -> expr.value.content
        }
      is Expr.State -> coerce(environment.state[expr.variable], expr.kind)
      is Expr.Binding -> coerce(environment.bindings(expr.key)?.contentOrNull, expr.kind)
      is Expr.System -> environment.clock.read(expr.value.id)
      is Expr.Call -> call(expr, expr.args.map { evaluate(it, environment) })
    }

  private fun coerce(raw: String?, kind: UiValueKind): Any =
    when (kind) {
      UiValueKind.FLOAT -> raw?.toDoubleOrNull() ?: 0.0
      UiValueKind.INT -> raw?.toDoubleOrNull()?.toInt() ?: 0
      UiValueKind.BOOL -> raw == "true"
      UiValueKind.STRING -> raw.orEmpty()
      UiValueKind.COLOR -> raw ?: "#00000000"
    }

  private fun call(expr: Expr.Call, values: List<Any>): Any {
    fun d(i: Int) = (values[i] as Number).toDouble()
    fun b(i: Int) = values[i] as Boolean
    val integral = expr.kind == UiValueKind.INT
    fun num(value: Double): Any = if (integral) value.toInt() else value
    return when (expr.op) {
      Op.ADD -> num(d(0) + d(1))
      Op.SUB -> num(d(0) - d(1))
      Op.MUL -> num(d(0) * d(1))
      Op.DIV ->
        if (integral) (if (d(1) == 0.0) 0 else (d(0) / d(1)).toInt())
        else if (d(1) == 0.0) 0.0 else d(0) / d(1)
      Op.MOD -> if (d(1) == 0.0) num(0.0) else num(d(0) % d(1))
      Op.NEG -> num(-d(0))
      Op.MIN -> num(minOf(d(0), d(1)))
      Op.MAX -> num(maxOf(d(0), d(1)))
      Op.CLAMP -> num(d(0).coerceIn(minOf(d(1), d(2)), maxOf(d(1), d(2))))
      Op.ABS -> num(abs(d(0)))
      Op.FLOOR -> floor(d(0))
      Op.CEIL -> ceil(d(0))
      Op.ROUND -> round(d(0))
      Op.SQRT -> sqrt(d(0))
      Op.POW -> d(0).pow(d(1))
      Op.SIN -> sin(d(0))
      Op.COS -> cos(d(0))
      Op.TAN -> tan(d(0))
      Op.LERP -> d(0) + (d(1) - d(0)) * d(2)
      Op.TO_INT -> d(0).toInt()
      Op.TO_FLOAT -> d(0)
      Op.EQ -> values[0].normalised() == values[1].normalised()
      Op.NE -> values[0].normalised() != values[1].normalised()
      Op.LT -> d(0) < d(1)
      Op.LE -> d(0) <= d(1)
      Op.GT -> d(0) > d(1)
      Op.GE -> d(0) >= d(1)
      Op.AND -> b(0) && b(1)
      Op.OR -> b(0) || b(1)
      Op.NOT -> !b(0)
      Op.SELECT -> if (b(0)) values[1].promote(expr.kind) else values[2].promote(expr.kind)
      Op.CONCAT -> values.joinToString("") { it.display() }
      Op.TO_STRING -> values[0].display()
    }
  }

  private fun Any.normalised(): Any = if (this is Number) toDouble() else this

  private fun Any.promote(kind: UiValueKind): Any =
    if (kind == UiValueKind.FLOAT && this is Number) toDouble() else this

  /** How the player prints a number into text: whole values without a fraction. */
  private fun Any.display(): String =
    when (this) {
      is Double ->
        if (this % 1.0 == 0.0 && abs(this) < 1e15) toLong().toString()
        else ((this * 100).let(::round) / 100).toString()
      else -> toString()
    }

  private fun Any.toJson(kind: UiValueKind): JsonPrimitive =
    when (kind) {
      UiValueKind.FLOAT -> JsonPrimitive((this as Number).toDouble().toFloat())
      UiValueKind.INT -> JsonPrimitive((this as Number).toInt())
      UiValueKind.BOOL -> JsonPrimitive(this as Boolean)
      UiValueKind.STRING,
      UiValueKind.COLOR -> JsonPrimitive(toString())
    }

  // ---- Formula text ----------------------------------------------------------------------------

  /**
   * [value] as the formula an author would type: `time.secondOfHour / 60 * 6`, `count + 1`,
   * `select(on, "On", "Off")`.
   */
  fun format(value: JsonElement): String = FormulaPrinter.print(value)

  /**
   * Parse a formula into its wrapper. Identifiers are state variables, `time.*` system values or,
   * with an `@` prefix, row fields; functions are the [Op] names; infix operators follow C
   * precedence. Null and [FormulaError] on text that does not parse.
   */
  fun parseFormula(text: String, stateNames: Set<String>): JsonObject =
    FormulaParser(text, stateNames).parse()

  class FormulaError(message: String) : IllegalArgumentException(message)

  internal fun IntRange.describe(): String =
    if (first == last) "$first argument${if (first == 1) "" else "s"}" else "$first–$last arguments"

  private const val MAX_DEPTH = 32

  private val COLOR_LITERAL = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
}

/** The kinds an expression can produce, by their wrapper names. */
enum class UiValueKind(val wire: String) {
  FLOAT("float"),
  INT("int"),
  BOOL("bool"),
  STRING("string"),
  COLOR("color");

  val numeric: Boolean
    get() = this == FLOAT || this == INT

  companion object {
    fun fromWire(wire: String): UiValueKind? = entries.firstOrNull { it.wire == wire }
  }
}

private object FormulaPrinter {
  private val INFIX_PRECEDENCE =
    mapOf(
      "or" to 1,
      "and" to 2,
      "eq" to 3,
      "ne" to 3,
      "lt" to 4,
      "le" to 4,
      "gt" to 4,
      "ge" to 4,
      "add" to 5,
      "sub" to 5,
      "mul" to 6,
      "div" to 6,
      "mod" to 6,
    )

  fun print(value: JsonElement, parent: Int = 0): String {
    val obj = value as? JsonObject ?: return value.toString()
    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
    val primitive = obj["value"] as? JsonPrimitive
    return when (type) {
      "float" -> primitive?.content?.let { if ('.' in it || 'e' in it) it else "$it.0" }.orEmpty()
      "int",
      "bool" -> primitive?.content.orEmpty()
      "string" ->
        "\"" + primitive?.content.orEmpty().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      "color" -> primitive?.content.orEmpty()
      "state" -> (obj["variable"] as? JsonPrimitive)?.content.orEmpty()
      "binding" -> "@" + primitive?.content.orEmpty()
      UiExpressions.SYSTEM -> primitive?.content.orEmpty()
      UiExpressions.EXPR -> {
        val op = (obj["op"] as? JsonPrimitive)?.content.orEmpty()
        val args = (obj["args"] as? JsonArray).orEmpty()
        val precedence = INFIX_PRECEDENCE[op]
        val symbol = UiExpressions.Op.byWire[op]?.symbol
        when {
          precedence != null && symbol != null && args.size == 2 -> {
            val text = "${print(args[0], precedence)} $symbol ${print(args[1], precedence + 1)}"
            if (precedence < parent) "($text)" else text
          }
          op == "neg" -> "-" + print(args.firstOrNull() ?: JsonNull, 7)
          op == "not" -> "!" + print(args.firstOrNull() ?: JsonNull, 7)
          else -> "$op(${args.joinToString(", ") { print(it) }})"
        }
      }
      else -> value.toString()
    }
  }
}

private class FormulaParser(private val text: String, private val stateNames: Set<String>) {
  private var position = 0

  fun parse(): JsonObject {
    val result = or()
    skip()
    if (position != text.length) fail("unexpected `${text.substring(position)}`")
    return result
  }

  private fun fail(message: String): Nothing =
    throw UiExpressions.FormulaError("$message at column ${position + 1}")

  private fun skip() {
    while (position < text.length && text[position].isWhitespace()) position++
  }

  private fun accept(token: String): Boolean {
    skip()
    if (!text.startsWith(token, position)) return false
    // `<` must not swallow `<=`, `=` alone is not an operator.
    if (token in setOf("<", ">", "!") && text.getOrNull(position + 1) == '=') return false
    position += token.length
    return true
  }

  private fun call(op: String, vararg args: JsonObject): JsonObject =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive(UiExpressions.EXPR),
        "op" to JsonPrimitive(op),
        "args" to JsonArray(args.toList()),
      )
    )

  private fun binary(next: () -> JsonObject, vararg operators: Pair<String, String>): JsonObject {
    var left = next()
    while (true) {
      val match = operators.firstOrNull { accept(it.first) } ?: return left
      left = call(match.second, left, next())
    }
  }

  private fun or(): JsonObject = binary(::and, "||" to "or")

  private fun and(): JsonObject = binary(::equality, "&&" to "and")

  private fun equality(): JsonObject = binary(::comparison, "==" to "eq", "!=" to "ne")

  private fun comparison(): JsonObject =
    binary(::additive, "<=" to "le", ">=" to "ge", "<" to "lt", ">" to "gt")

  private fun additive(): JsonObject = binary(::multiplicative, "+" to "add", "-" to "sub")

  private fun multiplicative(): JsonObject =
    binary(::unary, "*" to "mul", "/" to "div", "%" to "mod")

  private fun unary(): JsonObject =
    when {
      accept("-") -> {
        val operand = unary()
        val literal = operand["value"] as? JsonPrimitive
        val type = (operand["type"] as? JsonPrimitive)?.content
        if ((type == "int" || type == "float") && literal != null)
          literal(
            type,
            if (type == "int") JsonPrimitive(-literal.content.toInt())
            else JsonPrimitive(-literal.content.toDouble()),
          )
        else call("neg", operand)
      }
      accept("!") -> call("not", unary())
      else -> primary()
    }

  private fun literal(type: String, value: JsonPrimitive): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

  private fun primary(): JsonObject {
    skip()
    if (position >= text.length) fail("expected a value")
    val c = text[position]
    return when {
      c == '(' -> {
        position++
        val inner = or()
        if (!accept(")")) fail("expected `)`")
        inner
      }
      c == '"' -> literal("string", JsonPrimitive(string()))
      c == '#' -> {
        val start = position
        position++
        while (position < text.length && text[position].isLetterOrDigit()) position++
        val color = text.substring(start, position)
        if (!Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(color)) fail("bad colour `$color`")
        literal("color", JsonPrimitive(color))
      }
      c.isDigit() || c == '.' -> number()
      c == '@' -> {
        position++
        val name = identifier()
        JsonObject(mapOf("type" to JsonPrimitive("binding"), "value" to JsonPrimitive(name)))
      }
      c.isLetter() || c == '_' -> {
        val name = identifier()
        when {
          name == "true" || name == "false" -> literal("bool", JsonPrimitive(name.toBoolean()))
          accept("(") -> {
            val op = UiExpressions.Op.byWire[name] ?: fail("unknown function `$name`")
            val args = mutableListOf<JsonObject>()
            if (!accept(")")) {
              do args += or() while (accept(","))
              if (!accept(")")) fail("expected `)`")
            }
            call(op.wire, *args.toTypedArray())
          }
          name in UiExpressions.SYSTEM_VALUES ->
            JsonObject(
              mapOf("type" to JsonPrimitive(UiExpressions.SYSTEM), "value" to JsonPrimitive(name))
            )
          name in stateNames ->
            JsonObject(mapOf("type" to JsonPrimitive("state"), "variable" to JsonPrimitive(name)))
          else -> fail("`$name` is not a state variable or system value")
        }
      }
      else -> fail("unexpected `$c`")
    }
  }

  private fun identifier(): String {
    val start = position
    while (
      position < text.length &&
        (text[position].isLetterOrDigit() || text[position] == '_' || text[position] == '.')
    ) position++
    if (start == position) fail("expected a name")
    return text.substring(start, position)
  }

  private fun number(): JsonObject {
    val start = position
    while (position < text.length && (text[position].isDigit() || text[position] == '.')) position++
    val raw = text.substring(start, position)
    return if ('.' in raw) {
      literal("float", JsonPrimitive(raw.toDoubleOrNull() ?: fail("bad number `$raw`")))
    } else literal("int", JsonPrimitive(raw.toIntOrNull() ?: fail("bad number `$raw`")))
  }

  private fun string(): String {
    position++
    val out = StringBuilder()
    while (position < text.length && text[position] != '"') {
      if (text[position] == '\\' && position + 1 < text.length) position++
      out.append(text[position++])
    }
    if (position >= text.length) fail("unterminated string")
    position++
    return out.toString()
  }
}
