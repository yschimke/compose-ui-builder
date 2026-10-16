package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
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
  fun `animation time is the player's own clock, not the wall clock`() {
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
                "degrees" to formula("time.animation * 90"),
              )
            )
          ),
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "rotate((RemoteFloat(RemoteContext.FLOAT_ANIMATION_TIME) * 90.rf))")
    assertContains(source, "\nandroidx.compose.remote.core.RemoteContext\n")
    assertContains(source, "\nandroidx.compose.remote.creation.compose.state.RemoteFloat\n")
    assertFalse("RemoteTime" in source, source)
  }

  @Test
  fun `an animated value is the player's animation toward it`() {
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
                "type" to JsonPrimitive("alpha"),
                "alpha" to formula("tween(select(on, 1.0, 0.25), 300, \"overshoot\")"),
              )
            ),
            JsonObject(
              mapOf(
                "type" to JsonPrimitive("rotate"),
                "degrees" to formula("spring(select(on, 90, 0), 200, 0.5)"),
              )
            ),
          ),
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(
      source,
      "alpha(animateRemoteFloatAsState(on.select(1.rf, 0.25f.rf), " +
        "remoteTween(300, RemoteEasing.Overshoot)))",
    )
    assertContains(
      source,
      "rotate(animateRemoteFloatAsState(on.select(90.ri, 0.ri).toRemoteFloat(), " +
        "remoteSpring(stiffness = 200.0f, dampingRatio = 0.5f)))",
    )
    for (symbol in
      listOf("animateRemoteFloatAsState", "remoteTween", "RemoteEasing", "remoteSpring")) {
      assertContains(source, "\nandroidx.compose.remote.creation.compose.state.$symbol\n")
    }
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
  fun `library functions lower to the Remote operator of the same meaning`() {
    val (source, refusals) =
      emit(
        mapOf(
          "text" to
            formula(
              "toString(degrees(atan2(asin(0.5), acos(0.5))) + radians(exp(ln(log10(sign(" +
                "time.animation))))))"
            )
        )
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(
      source,
      "(toDeg(atan2(asin(0.5f.rf), acos(0.5f.rf))) + " +
        "toRad(exp(ln(log(sign(RemoteFloat(RemoteContext.FLOAT_ANIMATION_TIME)))))))",
    )
    for (symbol in listOf("toDeg", "toRad", "atan2", "asin", "acos", "exp", "ln", "log", "sign")) {
      assertContains(source, "\nandroidx.compose.remote.creation.compose.state.$symbol\n")
    }
  }

  @Test
  fun `shaping functions are written out over public operators`() {
    val (source, refusals) =
      emit(
        mapOf(
          "text" to
            formula(
              "toString(fract(time.animation) + step(0.5, time.animation) + " +
                "smoothstep(1.0, 3.0, time.animation) + hypot(count, 4))"
            )
        )
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    val time = "RemoteFloat(RemoteContext.FLOAT_ANIMATION_TIME)"
    assertContains(source, "($time - floor($time))")
    assertContains(source, "$time.isGreaterThanOrEqualTo(0.5f.rf).select(1.rf, 0.rf)")
    val t = "clamp((($time - 1.rf) / (3.rf - 1.rf)), 0.rf, 1.rf)"
    assertContains(source, "($t * $t * (3.rf - 2.rf * $t))")
    assertContains(
      source,
      "sqrt((count.toRemoteFloat() * count.toRemoteFloat() + 4.rf * 4.rf))",
    )
    for (symbol in listOf("floor", "clamp", "sqrt")) {
      assertContains(source, "\nandroidx.compose.remote.creation.compose.state.$symbol\n")
    }
  }

  @Test
  fun `format writes the player's text-from-float format`() {
    val (source, refusals) =
      emit(mapOf("text" to formula("concat(format(time.animation, 1, 2), \" s\")")))

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(
      source,
      "RemoteFloat(RemoteContext.FLOAT_ANIMATION_TIME).toRemoteString(" +
        "DecimalFormat(\"00.0\", DecimalFormatSymbols(Locale.ROOT)))",
    )
    for (import in
      listOf("java.text.DecimalFormat", "java.text.DecimalFormatSymbols", "java.util.Locale")) {
      assertTrue(import in source.lines(), "$import is not imported:\n$source")
    }
    // A whole-number format has no point at all.
    val (whole, _) = emit(mapOf("text" to formula("format(time.animation, 0)")))
    assertContains(whole, "DecimalFormat(\"0\", DecimalFormatSymbols(Locale.ROOT))")
  }

  @Test
  fun `date and device values read the player's context`() {
    val (source, refusals) =
      emit(
        mapOf(
          "text" to
            formula(
              "toString(time.year + time.month + time.dayOfYear + time.animationDelta + " +
                "device.density + device.fontScale + device.windowWidth + device.windowHeight)"
            )
        )
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    for (constant in
      listOf(
        "FLOAT_YEAR",
        "FLOAT_CALENDAR_MONTH",
        "FLOAT_DAY_OF_YEAR",
        "FLOAT_ANIMATION_DELTA_TIME",
        "FLOAT_DENSITY",
      )) {
      assertContains(source, "RemoteFloat(RemoteContext.$constant)")
    }
    assertContains(
      source,
      "(RemoteFloat(RemoteContext.FLOAT_FONT_SIZE) / (RemoteFloat(RemoteContext.FLOAT_DENSITY) * 14f.rf))",
    )
    assertContains(
      source,
      "(RemoteFloat(RemoteContext.FLOAT_WINDOW_WIDTH) / RemoteFloat(RemoteContext.FLOAT_DENSITY))",
    )
  }

  @Test
  fun `random, ping-pong, colour and text functions lower to public operators`() {
    val (source, refusals) =
      emit(
        mapOf(
          "text" to
            formula(
              "concat(uppercase(substring(trim(lowercase(\"Ab\")), 0, count)), " +
                "toString(rand() + randRange(1, 2) + pingPong(time.animation, 3)))"
            )
        ),
        modifiers =
          listOf(
            JsonObject(
              mapOf(
                "type" to JsonPrimitive("background"),
                "color" to formula("hsv(0.5, 1, rand())"),
              )
            )
          ),
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "\"Ab\".rs.lowercase().trim().substring(0.ri, count).uppercase()")
    assertContains(source, "rand()")
    assertContains(source, "randRange(1.rf, 2.rf)")
    val time = "RemoteFloat(RemoteContext.FLOAT_ANIMATION_TIME)"
    assertContains(
      source,
      "(abs($time) % (3.rf * 2.rf)).isLessThan(3.rf).select((abs($time) % (3.rf * 2.rf)), " +
        "((3.rf * 2.rf) - (abs($time) % (3.rf * 2.rf))))",
    )
    for (symbol in listOf("rand", "randRange")) {
      assertContains(source, "\nandroidx.compose.remote.creation.compose.state.$symbol\n")
    }
  }

  @Test
  fun `a computed colour is a RemoteColor built from its components`() {
    val (source, refusals) =
      emit(
        mapOf(
          "text" to
            JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("|"))),
          "color" to formula("rgb(1, 0.5, 0, select(on, 1.0, 0.5))"),
        )
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "RemoteColor.rgb(1.rf, 0.5f.rf, 0.rf, on.select(1.rf, 0.5f.rf))")
    assertTrue("androidx.compose.remote.creation.compose.state.RemoteColor" in source.lines())
  }

  @Test
  fun `touch and sensor values are the player's context floats, touch lengths in dp`() {
    val (source, refusals) =
      emit(
        mapOf(
          "text" to
            formula(
              "toString(touch.x + touch.velocityY + touch.time + sensor.light + sensor.rotationZ)"
            )
        )
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(
      source,
      "(RemoteFloat(RemoteContext.FLOAT_TOUCH_POS_X) / RemoteFloat(RemoteContext.FLOAT_DENSITY))",
    )
    assertContains(
      source,
      "(RemoteFloat(RemoteContext.FLOAT_TOUCH_VEL_Y) / RemoteFloat(RemoteContext.FLOAT_DENSITY))",
    )
    for (constant in listOf("FLOAT_TOUCH_EVENT_TIME", "FLOAT_LIGHT", "FLOAT_GYRO_ROT_Z")) {
      assertContains(source, "RemoteFloat(RemoteContext.$constant)")
    }
    assertContains(source, "\nandroidx.compose.remote.core.RemoteContext\n")
  }

  @Test
  fun `the canvas's size outside a canvas is a located refusal`() {
    val (_, refusals) = emit(mapOf("text" to formula("toString(canvas.width)")))

    assertTrue(
      refusals.any { "nodes.label.text" in it && "canvas.width" in it },
      refusals.toString(),
    )
  }

  @Test
  fun `splines, cube roots, copied signs and countdowns lower to the player's operators`() {
    val (source, refusals) =
      emit(
        mapOf(
          "text" to
            formula(
              "toString(spline(time.animation, 0, 1.5, 0) + splineLoop(time.animation, 0, 1) + " +
                "cbrt(count) + copySign(2, time.animation) + " +
                "secondsUntil(\"2026-12-25T00:00:00Z\") + hoursUntil(\"2026-12-25T00:00:00Z\"))"
            )
        )
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    val time = "RemoteFloat(RemoteContext.FLOAT_ANIMATION_TIME)"
    assertContains(
      source,
      "evalSpline(RemoteFloatArray(listOf(0.rf, 1.5f.rf, 0.rf)), false, $time)",
    )
    assertContains(source, "evalSpline(RemoteFloatArray(listOf(0.rf, 1.rf)), true, $time)")
    assertContains(source, "cbrt(count.toRemoteFloat())")
    assertContains(source, "copySign(2.rf, $time)")
    assertContains(source, "deltaFromReferenceInSeconds(RemoteLong(1798156800000L))")
    assertContains(source, "deltaFromReferenceInHours(RemoteLong(1798156800000L))")
    for (symbol in
      listOf(
        "evalSpline",
        "RemoteFloatArray",
        "cbrt",
        "copySign",
        "RemoteLong",
        "deltaFromReferenceInSeconds",
        "deltaFromReferenceInHours",
      )) {
      assertContains(source, "\nandroidx.compose.remote.creation.compose.state.$symbol\n")
    }
  }

  @Test
  fun `a tree that does not type is a located refusal, not source`() {
    val (_, refusals) = emit(mapOf("text" to formula("on * 2")))

    assertTrue(refusals.any { "nodes.label.text" in it && "mul" in it }, refusals.toString())
  }
}
