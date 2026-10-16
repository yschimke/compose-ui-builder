package ee.schimke.composeai.uibuilder.export

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sign
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
 * Computed property values: `{"type":"expr","op":…,"args":[…]}` and `{"type":"system","value":…}` —
 * values the player evaluates (clocks, progress-driven gauges, counters).
 *
 * A typed tree rather than a string, so every writer produces one validated shape and errors name
 * their operand. Operands are ordinary property wrappers plus `system` (host values like the clock)
 * and nested `expr`s; [check] infers each [UiValueKind] and refuses what does not type.
 *
 * Three readers share this meaning:
 * - the canvas, through [evaluate], at the preview state and the environment's fixed time;
 * - the Remote Kotlin writer, lowering to Remote operators ([RemoteExpressionWriter]);
 * - the inspector, via [format]/[parseFormula].
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
    /** What [remote] needs imported. */
    val imports: List<String> =
      listOf("androidx.compose.remote.creation.compose.layout.RemoteTime"),
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
        // Not the wall clock: seconds since the player started this document, so a motion
        // written over it starts from its beginning wherever and whenever the document is shown.
        SystemValue(
          "time.animation",
          UiValueKind.FLOAT,
          "Seconds since the document started playing",
          "RemoteFloat(RemoteContext.FLOAT_ANIMATION_TIME)",
          imports =
            listOf(
              "androidx.compose.remote.creation.compose.state.RemoteFloat",
              "androidx.compose.remote.core.RemoteContext",
            ),
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
    /** Radians, as the player's `asin`/`acos`/`atan`. */
    ASIN("asin", 1..1),
    ACOS("acos", 1..1),
    ATAN("atan", 1..1),
    /** The angle of the point (x, y): `atan2(y, x)`, in radians. */
    ATAN2("atan2", 2..2),
    /** Degrees from radians, and back. */
    DEGREES("degrees", 1..1),
    RADIANS("radians", 1..1),
    EXP("exp", 1..1),
    /** The natural logarithm. */
    LN("ln", 1..1),
    LOG10("log10", 1..1),
    /** -1, 0 or 1. */
    SIGN("sign", 1..1),
    /** `x - floor(x)`: 0 up to 1 for any x, so `fract(-0.25)` is 0.75. */
    FRACT("fract", 1..1),
    /** `step(edge, x)`: 1 where x is at least edge, otherwise 0. */
    STEP("step", 2..2),
    /** `smoothstep(edge0, edge1, x)`: 0 below edge0, 1 above edge1, a smooth curve between. */
    SMOOTHSTEP("smoothstep", 3..3),
    /** `sqrt(x * x + y * y)`. */
    HYPOT("hypot", 2..2),
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
    TO_STRING("toString", 1..1),
    /**
     * `format(x, decimals[, minDigits])`: a number as text with exactly `decimals` digits after the
     * point and the whole part zero-padded to `minDigits` (`format(7.5, 1, 2)` is `07.5`), as the
     * player's text-from-float prints it. The spec is literal.
     */
    FORMAT("format", 2..3),
    /**
     * Its first argument, animated: whenever it changes the player moves toward it over a duration
     * in milliseconds, with an optional [EASINGS] name. A preview shows where it settles.
     */
    TWEEN("tween", 2..3),
    /** As [TWEEN], on a spring: optional stiffness (default 50) and damping ratio (default 1). */
    SPRING("spring", 1..3);

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

      /**
       * The kind of a state variable an expression may read, or null. Nullable declarations, or
       * ones whose initial value is absent, null or mistyped, are not operands: the Remote writer
       * refuses them.
       */
      fun stateKind(declaration: JsonObject?): UiValueKind? {
        declaration ?: return null
        if ((declaration["nullable"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val initial = declaration["initialValue"] as? JsonPrimitive ?: return null
        if (initial is JsonNull) return null
        val inferred =
          when {
            initial.isString -> UiValueKind.STRING
            initial.booleanOrNull != null -> UiValueKind.BOOL
            initial.content.toLongOrNull() != null -> UiValueKind.INT
            initial.doubleOrNull?.isFinite() == true -> UiValueKind.FLOAT
            else -> return null
          }
        val declared =
          (declaration["valueType"] as? JsonPrimitive)?.contentOrNull?.let(UiValueKind::fromWire)
            ?: return inferred
        val fits =
          when (declared) {
            UiValueKind.FLOAT -> inferred == UiValueKind.FLOAT || inferred == UiValueKind.INT
            else -> declared == inferred
          }
        return declared.takeIf { fits }
      }
    }
  }

  /**
   * Whether the linked wire contract can carry a computed value (`UiValueV1` has `expr`/`system`
   * since compose-preview-contracts 3.20.0). The inspector offers computed values only when true.
   * Asked of the serializer rather than a version number.
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
  val OPERAND_LITERALS: Set<String> = setOf("float", "int", "bool", "string", "color", COLOR_TOKEN)

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
      "color",
      COLOR_TOKEN -> literal(type, obj["value"] as? JsonPrimitive, where)
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
        Expr.Call(op, args, resultKind(op, args.map { it.kind }, where)).also {
          (animationSpecIssue(it) ?: formatSpecIssue(it))?.let { issue ->
            throw ExpressionIssue("$where: $issue")
          }
        }
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
        COLOR_TOKEN -> value.isString && THEME_ROLE.matches(value.content)
        else -> value.isString && COLOR_LITERAL.matches(value.content)
      }
    if (!ok) throw ExpressionIssue("$where: `${value.content}` is not a $type")
    return Expr.Literal(UiValueKind.fromWire(type) ?: UiValueKind.COLOR, value)
  }

  /** The easings `tween` names, as the player's `RemoteEasing` constants. */
  val EASINGS: List<String> =
    listOf(
      "standard",
      "linear",
      "accelerate",
      "decelerate",
      "anticipate",
      "overshoot",
      "bounce",
      "elastic",
    )

  /**
   * What is wrong with an animation's spec, or null. Its value may be any formula, but the spec is
   * the player's animation, fixed when the document is written: literal and in range.
   */
  private fun animationSpecIssue(call: Expr.Call): String? {
    if (call.op != Op.TWEEN && call.op != Op.SPRING) return null
    val spec = call.args.drop(1)
    if (spec.any { it !is Expr.Literal }) {
      return "`${call.op.wire}` takes a literal duration, easing, stiffness and damping"
    }
    val numbers = spec.mapNotNull { (it as Expr.Literal).value.doubleOrNull }
    return when (call.op) {
      Op.TWEEN ->
        when {
          numbers.first().let { it < 1.0 || it % 1.0 != 0.0 } ->
            "a tween's duration is whole milliseconds, at least 1"
          spec.size == 2 && (spec[1] as Expr.Literal).value.content !in EASINGS ->
            "easing must be one of ${EASINGS.joinToString()}"
          else -> null
        }
      else ->
        if (numbers.any { it <= 0.0 }) "a spring's stiffness and damping must be more than 0"
        else null
    }
  }

  /**
   * What is wrong with a `format`'s spec, or null. The player's text-from-float takes its digit
   * counts when the document is written, so they are whole literals: up to six decimals, a whole
   * part padded to between one and nine digits.
   */
  private fun formatSpecIssue(call: Expr.Call): String? {
    if (call.op != Op.FORMAT) return null
    val spec = call.args.drop(1)
    val counts = spec.map { (it as? Expr.Literal)?.value?.doubleOrNull }
    return when {
      counts.any { it == null || it % 1.0 != 0.0 } ->
        "`format` takes whole literal numbers for its decimals and minimum digits"
      counts[0]!! !in 0.0..6.0 -> "`format` shows 0 to 6 decimals"
      counts.size > 1 && counts[1]!! !in 1.0..9.0 -> "`format` pads to 1 to 9 digits"
      else -> null
    }
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
      Op.ASIN,
      Op.ACOS,
      Op.ATAN,
      Op.DEGREES,
      Op.RADIANS,
      Op.EXP,
      Op.LN,
      Op.LOG10,
      Op.SIGN,
      Op.FRACT,
      Op.TO_FLOAT -> if (numeric(0)) UiValueKind.FLOAT else fail()
      Op.POW,
      Op.ATAN2,
      Op.STEP,
      Op.HYPOT -> if (numeric(0, 1)) UiValueKind.FLOAT else fail()
      Op.SMOOTHSTEP -> if (numeric(0, 1, 2)) UiValueKind.FLOAT else fail()
      // Two colours mixed by a fraction, as the player's colour `tween(from, to, fraction)`.
      Op.LERP ->
        when {
          numeric(0, 1, 2) -> UiValueKind.FLOAT
          kinds[0] == UiValueKind.COLOR && kinds[1] == UiValueKind.COLOR && numeric(2) ->
            UiValueKind.COLOR
          else -> fail()
        }
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
      Op.TWEEN ->
        if (numeric(0, 1) && (kinds.size < 3 || kinds[2] == UiValueKind.STRING)) UiValueKind.FLOAT
        else fail()
      Op.SPRING -> if (kinds.indices.all { numeric(it) }) UiValueKind.FLOAT else fail()
      Op.CONCAT -> if (kinds.none { it == UiValueKind.COLOR }) UiValueKind.STRING else fail()
      Op.TO_STRING -> if (kinds[0] != UiValueKind.COLOR) UiValueKind.STRING else fail()
      Op.FORMAT -> if (kinds.indices.all { numeric(it) }) UiValueKind.STRING else fail()
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

  /**
   * What the canvas evaluates against: preview state, row fields and a clock — the document's fixed
   * time, or the wall clock on a surface that lets time run.
   */
  class Environment(
    val state: Map<String, String?> = emptyMap(),
    val bindings: (String) -> JsonPrimitive? = { null },
    val clock: Clock = Clock.DEFAULT,
    /** A theme role's colour as `#AARRGGBB`, where the caller knows the theme. */
    val colorRoles: (String) -> String? = { null },
  )

  /** A wall-clock instant broken into the fields the player publishes. */
  data class Clock(
    val hour: Int,
    val minute: Int,
    val second: Int,
    val dayOfWeek: Int,
    val dayOfMonth: Int,
    /** The fraction of [second] that has passed, which only `time.continuousSecond` reads. */
    val millisecond: Int = 0,
    val utcOffsetSeconds: Int = 0,
    val year: Int = 2024,
    val month: Int = 5,
    /** `time.animation`: 0 at a fixed time, the seconds a live preview has been running. */
    val animationSeconds: Double = 0.0,
  ) {
    /** The instant these local fields name, for a host that hands a player a frozen clock. */
    val epochMillis: Long
      get() =
        daysFromCivil(year, month, dayOfMonth) * MILLIS_PER_DAY +
          ((hour * 60L + minute) * 60L + second) * 1000L +
          millisecond - utcOffsetSeconds * 1000L

    /** 1 on the 1st of January. */
    val dayOfYear: Int
      get() = (daysFromCivil(year, month, dayOfMonth) - daysFromCivil(year, 1, 1)).toInt() + 1

    fun read(id: String): Double =
      when (id) {
        "time.hour" -> hour.toDouble()
        "time.minuteOfDay" -> (hour * 60 + minute).toDouble()
        "time.secondOfHour" -> (minute * 60 + second).toDouble()
        "time.continuousSecond" -> minute * 60 + second + millisecond / 1000.0
        "time.dayOfWeek" -> dayOfWeek.toDouble()
        "time.dayOfMonth" -> dayOfMonth.toDouble()
        "time.utcOffset" -> utcOffsetSeconds.toDouble()
        "time.animation" -> animationSeconds
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
        val fraction = match.groupValues[7]
        // A well-shaped but invalid instant (month 13) draws at the default rather than crashing.
        if (
          month.toInt() !in 1..12 ||
            day.toInt() !in 1..31 ||
            hour.toInt() !in 0..23 ||
            minute.toInt() !in 0..59 ||
            second.toInt() !in 0..60
        )
          return DEFAULT
        return Clock(
          hour = hour.toInt(),
          minute = minute.toInt(),
          second = second.toInt(),
          dayOfWeek = isoDayOfWeek(year.toInt(), month.toInt(), day.toInt()),
          dayOfMonth = day.toInt(),
          millisecond = fraction.take(3).padEnd(3, '0').toInt(),
          year = year.toInt(),
          month = month.toInt(),
        )
      }

      /**
       * The local time at [epochMillis] in a zone [utcOffsetSeconds] ahead of UTC: what a live
       * preview reads each frame, the same fields the player derives from its own clock.
       */
      fun at(epochMillis: Long, utcOffsetSeconds: Int = 0): Clock {
        val local = epochMillis + utcOffsetSeconds * 1000L
        val days = local.floorDiv(MILLIS_PER_DAY)
        val millisOfDay = local.mod(MILLIS_PER_DAY)
        // 1970-01-01 was a Thursday: ISO day 4.
        val dayOfWeek = (days + 3).mod(7L).toInt() + 1
        val (year, month, day) = civilFromDays(days)
        return Clock(
          hour = (millisOfDay / 3_600_000L).toInt(),
          minute = (millisOfDay / 60_000L % 60).toInt(),
          second = (millisOfDay / 1000L % 60).toInt(),
          dayOfWeek = dayOfWeek,
          dayOfMonth = day,
          millisecond = (millisOfDay % 1000L).toInt(),
          utcOffsetSeconds = utcOffsetSeconds,
          year = year,
          month = month,
        )
      }

      private const val MILLIS_PER_DAY = 86_400_000L

      /** Year, month and day [epochDays] after 1970-01-01, by Hinnant's civil-from-days. */
      private fun civilFromDays(epochDays: Long): Triple<Int, Int, Int> {
        val z = epochDays + 719_468
        val era = z.floorDiv(146_097L)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthIndex = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * monthIndex + 2) / 5 + 1).toInt()
        val month = (if (monthIndex < 10) monthIndex + 3 else monthIndex - 9).toInt()
        val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
      }

      /** Days from 1970-01-01 to [year]-[month]-[day], by Hinnant's days-from-civil. */
      private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
      }

      private val ISO_INSTANT =
        Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(?:Z|[+-]00:?00)?""")

      /** Monday = 1 … Sunday = 7, by Sakamoto's method. */
      private fun isoDayOfWeek(year: Int, month: Int, day: Int): Int {
        val offsets = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        val y = if (month < 3) year - 1 else year
        val sunday0 = (y + y / 4 - y / 100 + y / 400 + offsets[month - 1] + day) % 7
        return if (sunday0 == 0) 7 else sunday0
      }
    }
  }

  /**
   * The literal [expr] evaluates to, wrapped as its kind's property wrapper. A colour the
   * environment could not resolve stays a theme role, wrapped as a `colorToken` for the canvas to.
   */
  fun evaluateWrapped(expr: Expr, environment: Environment): JsonObject {
    val value = evaluate(expr, environment)
    val type =
      if (expr.kind == UiValueKind.COLOR && isThemeRole(value.toString())) COLOR_TOKEN
      else expr.kind.wire
    return JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value.toJson(expr.kind)))
  }

  /**
   * A colour value that names a theme role (`primary`) rather than spelling `#AARRGGBB`.
   * `transparent` is a `colorToken` too, but no role: it is the literal `#00000000`.
   */
  fun isThemeRole(color: String): Boolean = THEME_ROLE.matches(color) && color != TRANSPARENT

  /**
   * The kind a typed wrapper carries: its wire name, and `colorToken` (a theme role or
   * `transparent`) as a colour.
   */
  fun kindOfWrapper(type: String): UiValueKind? =
    if (type == COLOR_TOKEN) UiValueKind.COLOR else UiValueKind.fromWire(type)

  /** [color] with `transparent` spelled as the literal it stands for. */
  private fun literalColor(color: String): String = if (color == TRANSPARENT) "#00000000" else color

  /**
   * [from] mixed toward [to] by [fraction], channel by channel, as the player's colour tween. A
   * role the environment could not resolve has no channels, so the nearer end stands in for it.
   */
  private fun mixColors(from: String, to: String, fraction: Double): String {
    val t = fraction.coerceIn(0.0, 1.0)
    fun argb(color: String): Long? =
      color
        .takeIf { COLOR_LITERAL.matches(it) }
        ?.drop(1)
        ?.let { if (it.length == 6) "FF$it" else it }
        ?.toLong(16)
    val a = argb(from)
    val b = argb(to)
    if (a == null || b == null) return if (t < 0.5) from else to
    val mixed =
      (0 until 4).fold(0L) { acc, channel ->
        val shift = 24 - channel * 8
        val x = (a shr shift) and 0xFF
        val y = (b shr shift) and 0xFF
        acc or ((x + (y - x) * t).roundToLong().coerceIn(0, 255) shl shift)
      }
    return "#" + mixed.toString(16).uppercase().padStart(8, '0')
  }

  /** Evaluate [expr] the way the player would at [environment]. */
  fun evaluate(expr: Expr, environment: Environment): Any =
    when (expr) {
      is Expr.Literal ->
        when (expr.kind) {
          UiValueKind.FLOAT -> expr.value.content.toFloat().toDouble()
          UiValueKind.INT -> expr.value.content.toInt()
          UiValueKind.BOOL -> expr.value.content.toBoolean()
          UiValueKind.STRING -> expr.value.content
          UiValueKind.COLOR ->
            literalColor(expr.value.content).let {
              if (isThemeRole(it)) environment.colorRoles(it) ?: it else it
            }
        }
      is Expr.State -> coerce(environment.state[expr.variable], expr.kind)
      is Expr.Binding -> coerce(environment.bindings(expr.key)?.contentOrNull, expr.kind)
      is Expr.System -> environment.clock.read(expr.value.id)
      is Expr.Call -> call(expr, expr.args.map { evaluate(it, environment) })
    }

  private fun coerce(raw: String?, kind: UiValueKind): Any =
    when (kind) {
      UiValueKind.FLOAT -> raw?.toFloatOrNull()?.toDouble() ?: 0.0
      UiValueKind.INT -> raw?.toDoubleOrNull()?.toInt() ?: 0
      UiValueKind.BOOL -> raw == "true"
      UiValueKind.STRING -> raw.orEmpty()
      UiValueKind.COLOR -> raw?.let(::literalColor) ?: "#00000000"
    }

  private fun call(expr: Expr.Call, values: List<Any>): Any {
    // The player computes in Float, so every operand and result is rounded to a Float as it is
    // here: `16777216.0 + 1.0 == 16777216.0` is true on the watch and must be true in the preview.
    fun d(i: Int) = (values[i] as Number).toFloat().toDouble()
    fun b(i: Int) = values[i] as Boolean
    val integral = expr.kind == UiValueKind.INT
    fun num(value: Double): Any = if (integral) value.toInt() else value.toFloat().toDouble()
    return when (expr.op) {
      Op.ADD -> num(d(0) + d(1))
      Op.SUB -> num(d(0) - d(1))
      Op.MUL -> num(d(0) * d(1))
      Op.DIV ->
        if (integral) (if (d(1) == 0.0) 0 else (d(0) / d(1)).toInt())
        else num(if (d(1) == 0.0) 0.0 else d(0) / d(1))
      Op.MOD -> if (d(1) == 0.0) num(0.0) else num(d(0) % d(1))
      Op.NEG -> num(-d(0))
      Op.MIN -> num(minOf(d(0), d(1)))
      Op.MAX -> num(maxOf(d(0), d(1)))
      Op.CLAMP -> num(d(0).coerceIn(minOf(d(1), d(2)), maxOf(d(1), d(2))))
      Op.ABS -> num(abs(d(0)))
      Op.FLOOR -> num(floor(d(0)))
      Op.CEIL -> num(ceil(d(0)))
      Op.ROUND -> num(round(d(0)))
      Op.SQRT -> num(sqrt(d(0)))
      Op.POW -> num(d(0).pow(d(1)))
      Op.SIN -> num(sin(d(0)))
      Op.COS -> num(cos(d(0)))
      Op.TAN -> num(tan(d(0)))
      Op.ASIN -> num(asin(d(0)))
      Op.ACOS -> num(acos(d(0)))
      Op.ATAN -> num(atan(d(0)))
      Op.ATAN2 -> num(atan2(d(0), d(1)))
      Op.DEGREES -> num(d(0) * (180.0 / PI))
      Op.RADIANS -> num(d(0) * (PI / 180.0))
      Op.EXP -> num(exp(d(0)))
      Op.LN -> num(ln(d(0)))
      Op.LOG10 -> num(log10(d(0)))
      Op.SIGN -> num(sign(d(0)))
      Op.FRACT -> num(d(0) - floor(d(0)))
      Op.STEP -> num(if (d(1) >= d(0)) 1.0 else 0.0)
      Op.SMOOTHSTEP -> num(smoothstep(d(0), d(1), d(2)))
      // Squared and summed in Float, as the written-out `sqrt(x * x + y * y)` is on the player, so
      // a square past Float's range is infinite here too.
      Op.HYPOT -> {
        val x = d(0).toFloat()
        val y = d(1).toFloat()
        num(sqrt(x * x + y * y).toDouble())
      }
      Op.LERP ->
        if (expr.kind == UiValueKind.COLOR)
          mixColors(values[0] as String, values[1] as String, d(2))
        else num(d(0) + (d(1) - d(0)) * d(2))
      Op.TO_INT -> d(0).toInt()
      Op.TO_FLOAT -> num(d(0))
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
      // Where the animation settles: the preview is a still frame of a finished animation.
      Op.TWEEN,
      Op.SPRING -> num(d(0))
      Op.CONCAT -> values.joinToString("") { it.display() }
      Op.TO_STRING -> values[0].display()
      Op.FORMAT ->
        formatNumber(
          d(0),
          (values[1] as Number).toInt(),
          (values.getOrNull(2) as? Number)?.toInt() ?: 1,
        )
    }
  }

  /**
   * Hermite smoothing between two edges, computed as the Remote writer lowers it: `t * t * (3 - 2 *
   * t)` over `t = clamp((x - edge0) / (edge1 - edge0), 0, 1)`, in Float like the player.
   */
  private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
    val t = ((x - edge0).toFloat() / (edge1 - edge0).toFloat()).coerceIn(0f, 1f).toDouble()
    return t * t * (3.0 - 2.0 * t)
  }

  /**
   * [value] with exactly [decimals] fraction digits and its whole part zero-padded to [minDigits],
   * no grouping, as the player's text-from-float prints it (`StringUtils` in remote-core): the
   * magnitude has half a unit added, in Float, and is truncated, so a tie rounds away from zero;
   * and any negative value keeps its sign, so `-0.01` to one decimal is `-0.0`.
   */
  internal fun formatNumber(value: Double, decimals: Int, minDigits: Int): String {
    val magnitude = abs(value).toFloat()
    var half = 0.5f
    repeat(decimals) { half /= 10f }
    val scaled = ((magnitude + half).toDouble() * 10.0.pow(decimals)).toFloat()
    val truncated = floor(scaled.toDouble()).toLong()
    val digits = truncated.toString().padStart(decimals + 1, '0')
    val whole = digits.dropLast(decimals).padStart(minDigits, '0')
    val fraction = digits.takeLast(decimals)
    val sign = if (value < 0) "-" else ""
    return sign + whole + if (decimals > 0) ".$fraction" else ""
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

  /** A colour scheme role's name, as the Remote theme's `colorScheme` spells its members. */
  private val THEME_ROLE = Regex("[a-z][A-Za-z0-9]*")

  private const val TRANSPARENT = "transparent"

  /** The wrapper for a theme role as a colour: `{"type": "colorToken", "value": "primary"}`. */
  const val COLOR_TOKEN: String = "colorToken"
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

/** How a formula spells a theme role as a colour: `theme.primary`. */
private const val THEME_PREFIX = "theme."

private object FormulaPrinter {
  private const val UNARY = 7
  private const val POWER = 8

  /** A signed operand of a power is bracketed: `(-2) ^ 2` is not `-2 ^ 2`. */
  private fun String.signed(parent: Int): String =
    if (startsWith("-") && parent > UNARY) "($this)" else this

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
      "float" ->
        primitive
          ?.content
          ?.let { if ('.' in it || 'e' in it) it else "$it.0" }
          .orEmpty()
          .signed(parent)
      "int" -> primitive?.content.orEmpty().signed(parent)
      "bool" -> primitive?.content.orEmpty()
      "string" ->
        "\"" + primitive?.content.orEmpty().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      "color" -> primitive?.content.orEmpty()
      UiExpressions.COLOR_TOKEN -> THEME_PREFIX + primitive?.content.orEmpty()
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
          // Powers read as maths: right-associative, tighter than a sign, so `-x ^ 2` is -(x²).
          op == "pow" && args.size == 2 -> {
            val text = "${print(args[0], POWER + 1)} ^ ${print(args[1], POWER)}"
            if (POWER < parent) "($text)" else text
          }
          op == "neg" -> ("-" + print(args.firstOrNull() ?: JsonNull, UNARY)).signed(parent)
          op == "not" -> "!" + print(args.firstOrNull() ?: JsonNull, UNARY)
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
      else -> power()
    }

  /** `a ^ b`, right-associative and above a sign: `-x ^ 2` is `-(x ^ 2)`, `2 ^ -1` a half. */
  private fun power(): JsonObject {
    val base = primary()
    return if (accept("^")) call("pow", base, unary()) else base
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
          name.startsWith(THEME_PREFIX) ->
            literal(UiExpressions.COLOR_TOKEN, JsonPrimitive(name.removePrefix(THEME_PREFIX)))
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
