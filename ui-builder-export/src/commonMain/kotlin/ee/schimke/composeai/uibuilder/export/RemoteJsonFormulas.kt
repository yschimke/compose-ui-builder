package ee.schimke.composeai.uibuilder.export

/**
 * The `remotecompose-json` codec's float formulas (`ping_pong(continuousSec(), 4.0) / 4.0`,
 * `clamp(0.0, 1.0, 1.0 - abs(@wx - 180.0) / 96.0)`) rewritten as builder formulas.
 *
 * The codec writes each function as the remote-core `AnimatedFloatExpression` operator of that
 * name, its arguments pushed in order, so each is translated by what that operator does with them:
 * its `clamp(a, b, x)` is `min(max(a, x), b)`, `step(a, b)` is `a > b`, `ifElse(a, b, c)` is `c > 0
 * ? b : a`. Its system names (`continuousSec`, `touchX`, `componentWidth`) become the builder's
 * system values, pixel ones scaled back by the density. `@name` inlines a variable's formula; a
 * loop's index, read bare, is the builder's `@index`.
 */
internal object RemoteJsonFormulas {
  class Untranslatable(message: String) : IllegalArgumentException(message)

  /**
   * @param arrays the document's float arrays, by name. Their numbers are fixed, so a function of
   *   one folds into a number, and `arrayGet` into a choice of its numbers by the index.
   */
  fun translate(
    source: String,
    variables: Map<String, String>,
    indices: Set<String>,
    arrays: Map<String, List<Double>> = emptyMap(),
  ): String = Parser(source, variables, indices, arrays).run()

  /** What an `@name` of an array translates to: only an array function's argument reads it. */
  private const val ARRAY = "\u0000array:"

  /** Builder formulas for the codec's system names; pixel values are its dp ones times density. */
  private val SYSTEM: Map<String, String> = buildMap {
    fun all(formula: String, vararg names: String) = names.forEach { put(it, formula) }
    all("time.continuousSecond", "time", "continuousSec")
    all("time.secondOfHour", "seconds", "timeInSec")
    all("time.minuteOfDay", "timeInMin")
    all("time.hour", "timeInHr")
    all("time.animation", "animationTime", "animTime")
    all("time.animationDelta", "deltaTime", "delta_time", "dt", "animationDeltaTime")
    all("time.month", "calendarMonth", "month")
    all("time.utcOffset", "offsetToUtc", "utcOffset")
    all("time.dayOfWeek", "weekDay", "weekday", "dayOfWeek")
    all("time.dayOfMonth", "dayOfMonth", "day")
    all("time.dayOfYear", "dayOfYear")
    all("time.year", "year")
    all("(device.windowWidth * device.density)", "windowWidth")
    all("(device.windowHeight * device.density)", "windowHeight")
    all("device.density", "density")
    // The player's font size: 14sp in pixels.
    all("(device.fontScale * device.density * 14)", "fontSize")
    all("(touch.x * device.density)", "touchX", "touchPosX", "touchPositionX")
    all("(touch.y * device.density)", "touchY", "touchPosY", "touchPositionY")
    all("(touch.velocityX * device.density)", "touchVelX", "touchVelocityX")
    all("(touch.velocityY * device.density)", "touchVelY", "touchVelocityY")
    all("touch.time", "touchTime", "touchEventTime")
    all("sensor.accelerationX", "accelX", "accelerationX")
    all("sensor.accelerationY", "accelY", "accelerationY")
    all("sensor.accelerationZ", "accelZ", "accelerationZ")
    all("sensor.rotationX", "gyroX", "gyroRotX", "gyroRotationX")
    all("sensor.rotationY", "gyroY", "gyroRotY", "gyroRotationY")
    all("sensor.rotationZ", "gyroZ", "gyroRotZ", "gyroRotationZ")
    all("sensor.magneticX", "magneticX")
    all("sensor.magneticY", "magneticY")
    all("sensor.magneticZ", "magneticZ")
    all("sensor.light", "light", "lightLevel")
    // The size the commands draw in: the importer's canvas viewport, the document's units.
    all("canvas.width", "width", "componentWidth")
    all("canvas.height", "height", "componentHeight")
  }

  /** Functions whose builder name and argument order are the codec's. */
  private val SAME: Map<String, String> =
    mapOf(
      "sin" to "sin",
      "cos" to "cos",
      "tan" to "tan",
      "asin" to "asin",
      "acos" to "acos",
      "atan" to "atan",
      "atan2" to "atan2",
      "sqrt" to "sqrt",
      "abs" to "abs",
      "pow" to "pow",
      "min" to "min",
      "max" to "max",
      "floor" to "floor",
      "ceil" to "ceil",
      "sign" to "sign",
      "round" to "round",
      "exp" to "exp",
      "hypot" to "hypot",
      "fract" to "fract",
      "lerp" to "lerp",
      "cbrt" to "cbrt",
      "copySign" to "copySign",
      "ping_pong" to "pingPong",
      // remote-core's LOG is base 10, its LN natural.
      "log" to "log10",
      "ln" to "ln",
      "deg" to "degrees",
      "rad" to "radians",
    )

  private class Parser(
    val source: String,
    val variables: Map<String, String>,
    val indices: Set<String>,
    val arrays: Map<String, List<Double>>,
  ) {
    var at = 0

    fun run(): String {
      val result = additive()
      skip()
      if (at < source.length) fail("unexpected `${source.substring(at)}`")
      if (ARRAY in result) fail("an array is read as a number")
      return result
    }

    /** The array an argument names, else null. */
    fun array(arg: String): List<Double>? =
      arg.takeIf { it.startsWith(ARRAY) }?.let { arrays.getValue(it.removePrefix(ARRAY)) }

    /**
     * An array function, folded: the array's numbers are fixed. `arrayGet` truncates its index to a
     * whole number as the player does, so a live index picks between the numbers by halves.
     */
    fun arrayCall(name: String, args: List<String>): String? {
      val first = args.firstOrNull()?.let(::array) ?: return null
      fun one(): List<Float> {
        if (args.size != 1) fail("`$name` takes one array")
        return first.map { it.toFloat() }
      }
      fun sum(values: List<Float>) = values.fold(0f) { total, v -> total + v }
      val folded: Float =
        when (name) {
          "arrayLength" -> one().size.toFloat()
          "arraySum" -> sum(one())
          "arraySumSqr" -> sum(one().map { it * it })
          "arrayMax" -> one().maxOrNull() ?: 0f
          "arrayMin" -> one().minOrNull() ?: 0f
          "arraySumXY" -> {
            val second = args.getOrNull(1)?.let(::array)
            if (args.size != 2 || second == null) fail("`$name` takes two arrays")
            sum(first.zip(second) { a, b -> a.toFloat() * b.toFloat() })
          }
          "arrayGet" -> {
            if (args.size != 2 || ARRAY in args[1]) fail("`$name` takes an array and an index")
            if (first.isEmpty()) fail("`$name` reads an empty array")
            args[1].toDoubleOrNull()?.let {
              return literal(first[it.toInt().coerceIn(0, first.lastIndex)].toFloat())
            }
            return pick(first, "(${args[1]})", 0, first.size)
          }
          else -> fail("`$name` has no builder equivalent")
        }
      return literal(folded)
    }

    fun pick(values: List<Double>, index: String, from: Int, until: Int): String {
      if (until - from == 1) return literal(values[from].toFloat())
      val half = (from + until) / 2
      return "select($index < $half, ${pick(values, index, from, half)}, " +
        "${pick(values, index, half, until)})"
    }

    fun literal(value: Float): String =
      if (value % 1f == 0f && kotlin.math.abs(value) < 1e9f) value.toLong().toString()
      else value.toString()

    fun fail(message: String): Nothing = throw Untranslatable("`$source`: $message")

    fun skip() {
      while (at < source.length && source[at].isWhitespace()) at++
    }

    fun peek(): Char? {
      skip()
      return source.getOrNull(at)
    }

    fun additive(): String {
      var left = multiplicative()
      while (true) {
        val c = peek()
        if (c != '+' && c != '-') return left
        at++
        left = "$left $c ${multiplicative()}"
      }
    }

    fun multiplicative(): String {
      var left = unary()
      while (true) {
        val c = peek()
        if (c != '*' && c != '/' && c != '%') return left
        at++
        left = "$left $c ${unary()}"
      }
    }

    fun unary(): String {
      if (peek() == '-') {
        at++
        return "-${unary()}"
      }
      if (peek() == '+') {
        at++
        return unary()
      }
      return primary()
    }

    fun primary(): String {
      val c = peek() ?: fail("ends early")
      when {
        c == '(' -> {
          at++
          val inner = additive()
          expect(')')
          return "($inner)"
        }
        c.isDigit() || c == '.' -> return numberLiteral()
        c == '@' || c == '$' -> {
          at++
          var name = identifier()
          if (name == "vars" && peek() == '.') {
            at++
            name = identifier()
          }
          if (name in indices) return "@$name"
          if (name in arrays && name !in variables) return "$ARRAY$name"
          return variables[name]?.let { "($it)" } ?: fail("variable `$name` is not defined here")
        }
        c.isLetter() || c == '_' -> {
          val name = identifier()
          if (peek() == '(') {
            at++
            val args = mutableListOf<String>()
            if (peek() != ')') {
              args += additive()
              while (peek() == ',') {
                at++
                args += additive()
              }
            }
            expect(')')
            return call(name, args)
          }
          if (name in indices) return "@$name"
          variables[name]?.let {
            return "($it)"
          }
          SYSTEM[name]?.let {
            return it
          }
          if (name == "PI") return "3.1415927"
          fail("`$name` is not a value the importer knows")
        }
        else -> fail("unexpected `$c`")
      }
    }

    fun call(name: String, args: List<String>): String {
      arrayCall(name, args)?.let {
        return it
      }
      fun arity(n: Int) {
        if (args.size != n) fail("`$name` takes $n arguments")
      }
      if (args.isEmpty() && name in SYSTEM) return SYSTEM.getValue(name)
      SAME[name]?.let {
        return "$it(${args.joinToString(", ")})"
      }
      return when (name) {
        // CLAMP: min(max(a, c), b) over the arguments in order.
        "clamp" -> {
          arity(3)
          "min(max(${args[0]}, ${args[2]}), ${args[1]})"
        }
        // STEP: a > b ? 1 : 0.
        "step" -> {
          arity(2)
          "select(${args[0]} > ${args[1]}, 1, 0)"
        }
        // SMOOTH_STEP takes (x, high, low).
        "smooth_step" -> {
          arity(3)
          "smoothstep(${args[2]}, ${args[1]}, ${args[0]})"
        }
        // The ternary: c > 0 ? b : a.
        "ifElse" -> {
          arity(3)
          "select(${args[2]} > 0, ${args[1]}, ${args[0]})"
        }
        // MAD: a * b + c.
        "mad" -> {
          arity(3)
          "(${args[0]} * ${args[1]} + ${args[2]})"
        }
        "square" -> {
          arity(1)
          "(${args[0]} * ${args[0]})"
        }
        "rand" -> if (args.isEmpty()) "rand()" else fail("seeded `rand` has no builder equivalent")
        "rand_in_range" -> {
          arity(2)
          "randRange(${args[0]}, ${args[1]})"
        }
        else -> fail("`$name` has no builder equivalent")
      }
    }

    fun identifier(): String {
      val start = at
      while (at < source.length && (source[at].isLetterOrDigit() || source[at] == '_')) at++
      if (start == at) fail("expected a name")
      return source.substring(start, at)
    }

    fun numberLiteral(): String {
      val start = at
      while (at < source.length && (source[at].isDigit() || source[at] == '.')) at++
      if (at < source.length && (source[at] == 'e' || source[at] == 'E')) {
        at++
        if (at < source.length && (source[at] == '+' || source[at] == '-')) at++
        while (at < source.length && source[at].isDigit()) at++
      }
      // The builder's formulas have no exponent; written out as a plain decimal.
      val text = source.substring(start, at)
      val value = text.toDoubleOrNull() ?: fail("`$text` is not a number")
      return if ('e' in text || 'E' in text) value.toBigDecimalString() else text.removeSuffix(".")
    }

    fun expect(c: Char) {
      if (peek() != c) fail("expected `$c`")
      at++
    }
  }

  private fun Double.toBigDecimalString(): String {
    if (this == 0.0) return "0"
    var digits = 0
    var scaled = kotlin.math.abs(this)
    while (scaled != kotlin.math.floor(scaled) && digits < 12) {
      scaled *= 10
      digits++
    }
    val whole =
      kotlin.math
        .round(kotlin.math.abs(this) * 10.0.pow(digits))
        .toLong()
        .toString()
        .padStart(digits + 1, '0')
    val text = if (digits == 0) whole else whole.dropLast(digits) + "." + whole.takeLast(digits)
    return if (this < 0) "-$text" else text
  }

  private fun Double.pow(n: Int): Double {
    var r = 1.0
    repeat(n) { r *= this }
    return r
  }
}
