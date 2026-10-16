package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Authoring JSON, as the `remotecompose-json` codec reads it and the RemoteCompose-Examples corpus
 * writes it, imported into a design: layout and text as builder components, a canvas's commands as
 * drawing operations with the codec's paint and formulas carried onto them.
 */
class RemoteJsonImporterTest {
  private fun imported(json: String): RemoteJsonImporter.Result =
    RemoteJsonImporter.import(Json.parseToJsonElement(json) as JsonObject, SYSTEM_ID)

  private fun formula(
    text: String,
    variables: Map<String, String> = emptyMap(),
    indices: Set<String> = emptySet(),
  ) = RemoteJsonFormulas.translate(text, variables, indices)

  @Test
  fun `the codec's functions become the builder's, in the order remote-core reads them`() {
    assertEquals(
      "pingPong(time.continuousSecond, 4.0) / 4.0",
      formula("ping_pong(continuousSec(), 4.0) / 4.0"),
    )
    // CLAMP is min(max(a, c), b): the corpus writes clamp(low, high, x).
    assertEquals(
      "min(max(0.0, 1.0 - abs(@i)), 1.0)",
      formula("clamp(0.0, 1.0, 1.0 - abs(i))", indices = setOf("i")),
    )
    assertEquals("select(2 > 1, 1, 0)", formula("step(2, 1)"))
    assertEquals("select(3 > 0, 2, 1)", formula("ifElse(1, 2, 3)"))
    assertEquals("smoothstep(0, 1, 0.5)", formula("smooth_step(0.5, 1, 0)"))
    assertEquals("log10(100)", formula("log(100)"))
    assertEquals("(2 * 2)", formula("square(2)"))
    // Pixel-valued system names are the builder's dp ones times the density.
    assertEquals(
      "(touch.x * device.density) + canvas.width",
      formula("touchX() + componentWidth()"),
    )
    // A variable inlines; a loop's index, bare, is the builder's @index.
    assertEquals(
      "(time.animation * 2) + @i",
      formula("@speed + i", mapOf("speed" to "time.animation * 2"), setOf("i")),
    )
    assertFailsWith<RemoteJsonFormulas.Untranslatable> { formula("arrayGet(@data, 2)") }
    assertFailsWith<RemoteJsonFormulas.Untranslatable> { formula("@missing + 1") }
  }

  @Test
  fun `a fixed array folds into the formulas reading it`() {
    val data = mapOf("data" to listOf(40.0, 90.0, 60.0), "w" to listOf(1.0, 2.0, 3.0))
    fun folded(text: String, indices: Set<String> = emptySet()) =
      RemoteJsonFormulas.translate(text, emptyMap(), indices, data)
    assertEquals("3", folded("arrayLength(@data)"))
    assertEquals("190", folded("arraySum(@data)"))
    assertEquals("400", folded("arraySumXY(@data, @w)"))
    assertEquals("90", folded("arrayMax(@data)"))
    assertEquals("60", folded("arrayGet(@data, 2)"))
    // A live index picks by halves, truncating as the player does.
    assertEquals(
      "200 - select((@i) < 1, 40, select((@i) < 2, 90, 60))",
      folded("200 - arrayGet(@data, i)", setOf("i")),
    )
    assertFailsWith<RemoteJsonFormulas.Untranslatable> { folded("@data + 1") }
    assertFailsWith<RemoteJsonFormulas.Untranslatable> { folded("abs(@data)") }
  }

  @Test
  fun `a global block's resources are read before its layout`() {
    val result =
      imported(
        """{"header":{"width":320,"height":220},"root":[{"global":[
          {"resources":{
            "floatArrays":[{"data":[40,90,60]}],
            "integers":[{"bars":3}],
            "colors":[{"ink":"#FF42A5F5"},{"accent":"@colors.ink"},{"themed":{"light":"#FF000000","dark":"#FFFFFFFF"}}],
            "variables":[{"n":"arrayLength(@data)"},{"gap":{"value":42,"export":true}}]}},
          {"canvas":{"modifiers":["fillMaxSize"],"commands":[
            {"paint":{"color":"@colors.accent","style":"fill"}},
            {"loop":{"from":0.0,"until":"@n","index":"i","commands":[
              {"drawRect":{"left":"20 + i * @gap","top":"200 - arrayGet(@data, i)","right":"52 + i * @gap","bottom":200.0}}]}}
          ]}}]}]}"""
      )
    assertEquals(
      listOf(
        "root[0].global[0].resources.colors.themed: a colour with light and dark values has no builder equivalent"
      ),
      result.gaps,
    )
    val nodes = result.document.nodes
    val repeat = nodes.values.single { it.componentId == UiDrawing.REPEAT }
    assertEquals("3", UiExpressions.format(repeat.properties.getValue("until").jsonObject))
    val bar = nodes.getValue(repeat.slots.getValue(UiDrawing.OPS_SLOT).single())
    assertEquals("#FF42A5F5", bar.properties["color"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    assertContains(
      UiExpressions.format(bar.properties.getValue("yDp").jsonObject),
      "select(@i < 1, 40, select(@i < 2, 90, 60))",
    )
  }

  @Test
  fun `resources are read before any layout, in each of the codec's encodings`() {
    val result =
      imported(
        """{"header":{"width":100,"height":100},"root":[
          {"canvas":{"commands":[
            {"paint":{"color":"${'$'}colors.ink"}},
            {"drawCircle":{"cx":"@cx","cy":"arrayGet(@data, 1)","radius":4}}]}},
          {"resources":{
            "colors":{"ink":"#FF42A5F5"},
            "variables":[{"name":"cx","value":"12 * 2"}],
            "floatArrays":[{"name":"data","value":[5,7]}]}}]}"""
      )
    assertEquals(emptyList(), result.gaps)
    val dot = result.document.nodes.values.single { it.componentId == "draw/circle" }
    assertEquals("#FF42A5F5", dot.properties["color"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    assertEquals("12 * 2", UiExpressions.format(dot.properties.getValue("centerXDp").jsonObject))
    assertEquals("7", UiExpressions.format(dot.properties.getValue("centerYDp").jsonObject))
  }

  @Test
  fun `layout and text import as builder components`() {
    val result =
      imported(
        """{"header":{"width":200,"height":100},"root":[{"column":{
          "modifiers":["fillMaxSize",{"padding":8},{"background":"#102030"}],
          "horizontalAlignment":"center",
          "children":[
            {"type":"text","value":"Hello","fontSize":20.0,"color":"#FFDCE4F2","modifiers":[]},
            {"type":"spacer","modifiers":[{"height":4}]},
            {"type":"text","value":"World","fontSize":11.0,"color":"#FF000000","modifiers":[{"width":120}]}
          ]}}]}"""
      )
    assertEquals(emptyList(), result.gaps)
    val document = result.document
    assertEquals(200.0, document.environment.getValue("widthDp").jsonPrimitive.content.toDouble())
    val column = document.nodes.values.single { it.componentId == "layout/column" }
    assertEquals(
      listOf("fillMaxSize", "padding", "background"),
      column.modifiers.map { it.jsonObject.getValue("type").jsonPrimitive.content },
    )
    assertEquals(
      "#FF102030",
      column.modifiers[2].jsonObject["color"]!!.jsonObject["value"]!!.jsonPrimitive.content,
    )
    val children = column.slots.getValue("children").map(document.nodes::getValue)
    assertEquals(
      listOf(REMOTE_TEXT_COMPONENT_ID, "layout/box", REMOTE_TEXT_COMPONENT_ID),
      children.map { it.componentId },
    )
    assertEquals(
      "Hello",
      children[0].properties["text"]!!.jsonObject["value"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `a scaled touch position is a drag the canvas's operations read`() {
    val result =
      imported(
        """{"header":{"width":360,"height":100},"root":[{"type":"canvas","commands":[
          {"touchExpression":{"name":"stage","defaultValue":0.42,"min":0.0,"max":1.0,
            "stopMode":"gently","expression":"touchX() / 480"}},
          {"touchExpression":{"name":"lift","defaultValue":4.0,"min":1.0,"max":10.0,
            "expression":"1 - touchY() / 400 * 11"}},
          {"drawCircle":{"cx":"@stage * 360","cy":"@lift * 10","radius":4}}
        ]}]}"""
      )
    assertEquals(emptyList(), result.gaps)
    val dot = result.document.nodes.values.single { it.componentId == "draw/circle" }
    assertEquals(
      UiExpressions.parseFormula("(dragX(0.42, 0, 1, 0.0020833333333333333)) * 360", emptySet()),
      dot.properties.getValue("centerXDp"),
    )
    assertEquals(
      UiExpressions.parseFormula("(dragY(4, 1, 10, -0.0275)) * 10", emptySet()),
      dot.properties.getValue("centerYDp"),
    )
    // A drag stops gently; another stop is reported rather than changed.
    for (mode in listOf("\"touchMode\":2", "\"stopMode\":\"instantly\"")) {
      val refused =
        imported(
          """{"header":{"width":360,"height":100},"root":[{"type":"canvas","commands":[
            {"touchExpression":{"name":"snap","min":0.0,"max":1.0,$mode,"expression":"touchX()"}}
          ]}]}"""
        )
      assertTrue(refused.gaps.single().contains("stops other than gently"), refused.gaps.toString())
    }
  }

  @Test
  fun `a canvas sized by a width and a height apart takes them as its viewport`() {
    val result =
      imported(
        """{"header":{"width":360,"height":360},"root":[{"type":"canvas",
          "modifiers":[{"width":120},{"height":48}],
          "commands":[{"drawRect":{"left":0,"top":0,"right":120,"bottom":48}}]}]}"""
      )
    val canvas = result.document.nodes.values.single { it.componentId == UiDrawing.CANVAS }
    fun viewport(name: String) =
      canvas.properties[name]!!.jsonObject["value"]!!.jsonPrimitive.content
    assertEquals("120.0", viewport("viewportWidth"))
    assertEquals("48.0", viewport("viewportHeight"))
  }

  @Test
  fun `a loop or clip refused for its bounds leaves no unreachable commands`() {
    val result =
      imported(
        """{"header":{"width":100,"height":100},"root":[{"type":"canvas","commands":[
          {"loop":{"index":"i","from":0,"commands":[{"drawCircle":{"cx":"i * 10","cy":50,"radius":4}}]}},
          {"drawRect":{"left":0,"top":0,"right":10,"bottom":10}}
        ]}]}"""
      )
    assertTrue(result.gaps.any { "until" in it }, result.gaps.toString())
    val document = result.document
    val reachable = mutableSetOf<String>()
    fun walk(id: String) {
      if (!reachable.add(id)) return
      document.nodes.getValue(id).slots.values.flatten().forEach(::walk)
    }
    document.roots.forEach(::walk)
    assertEquals(document.nodes.keys, reachable)
    assertTrue(document.nodes.values.none { it.componentId == "draw/circle" })
  }

  @Test
  fun `a canvas's commands become operations carrying the paint before them`() {
    val result =
      imported(
        """{"header":{"width":360,"height":360},"root":[{"type":"canvas","modifiers":["fillMaxSize"],"commands":[
          {"paint":{"ops":[{"color":"#080B10"},{"style":"fill"},{"alpha":1.0}]}},
          {"drawRect":{"left":0,"top":0,"right":360.0,"bottom":360.0}},
          {"paint":{"ops":[{"color":"#243141"},{"style":"stroke"},{"strokeWidth":1.4}]}},
          {"drawRoundRect":{"left":10,"top":10,"right":350.0,"bottom":350.0,"rx":18,"ry":18}},
          {"variable":{"name":"walk","value":"ping_pong(continuousSec(), 4.0) / 4.0"}},
          {"drawCircle":{"cx":"56.0 + @walk * 248.0","cy":180,"radius":12}},
          {"paint":{"ops":[{"textSize":34}]}},
          {"drawTextAnchored":{"text":"Hi","x":180,"y":40,"panX":-1.0,"panY":1.0}}
        ]}]}"""
      )
    assertEquals(emptyList(), result.gaps)
    val document = result.document
    val canvas = document.nodes.values.single { it.componentId == UiDrawing.CANVAS }
    assertEquals(
      "360.0",
      canvas.properties["viewportWidth"]!!.jsonObject["value"]!!.jsonPrimitive.content,
    )
    val ops = canvas.slots.getValue(UiDrawing.OPS_SLOT).map(document.nodes::getValue)
    assertEquals(
      listOf("draw/rect", "draw/rect", "draw/circle", "draw/text"),
      ops.map { it.componentId },
    )
    val (fill, outline, dot, text) = ops
    assertEquals(
      "#FF080B10",
      fill.properties["color"]!!.jsonObject["value"]!!.jsonPrimitive.content,
    )
    assertEquals(null, fill.properties["style"])
    assertEquals(
      "stroke",
      outline.properties["style"]!!.jsonObject["value"]!!.jsonPrimitive.content,
    )
    assertEquals(
      "340.0",
      outline.properties["widthDp"]!!.jsonObject["value"]!!.jsonPrimitive.content,
    )
    assertEquals(
      "18.0",
      outline.properties["cornerRadiusDp"]!!.jsonObject["value"]!!.jsonPrimitive.content,
    )
    // The circle's centre is a live formula with the variable inlined.
    val centre = dot.properties.getValue("centerXDp")
    assertEquals("expr", centre.jsonObject["type"]!!.jsonPrimitive.content)
    assertContains(
      UiExpressions.format(centre.jsonObject),
      "pingPong(time.continuousSecond, 4.0) / 4.0 * 248.0",
    )
    assertIs<UiExpressions.Checked.Ok>(UiExpressions.check(centre, UiExpressions.Scope(emptyMap())))
    // panX -1 starts the text at the anchor; panY 1 hangs it below.
    assertEquals("start", text.properties["align"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    assertEquals(
      "top",
      text.properties["verticalAlign"]!!.jsonObject["value"]!!.jsonPrimitive.content,
    )
    assertEquals(
      "34.0",
      text.properties["textSizeSp"]!!.jsonObject["value"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `transforms, clips, loops and conditions nest what they apply to`() {
    val result =
      imported(
        """{"header":{"width":360,"height":360},"root":[{"type":"canvas","modifiers":["fillMaxSize"],"commands":[
          {"loop":{"index":"i","from":0.0,"until":12.0,"step":1.0,"commands":[
            {"variable":{"name":"ra","value":"i * 30.0"}},
            {"save":{"commands":[
              {"rotate":{"angle":"@ra","pivotX":180,"pivotY":150}},
              {"drawLine":{"x1":180,"y1":84,"x2":180,"y2":70}}]}}]}},
          {"save":{}},{"clipRect":{"left":62,"top":88,"right":298,"bottom":228}},
          {"drawOval":{"left":70,"top":90,"right":290,"bottom":220}},{"restore":{}},
          {"conditionalOperations":{"condition":"lt","v1":"continuousSec()","v2":0.5,"commands":[
            {"drawRect":{"left":0,"top":0,"right":10,"bottom":10}}]}}
        ]}]}"""
      )
    assertEquals(emptyList(), result.gaps)
    val nodes = result.document.nodes
    val canvas = nodes.values.single { it.componentId == UiDrawing.CANVAS }
    val top = canvas.slots.getValue(UiDrawing.OPS_SLOT).map(nodes::getValue)
    assertEquals(
      listOf(UiDrawing.REPEAT, UiDrawing.GROUP, UiDrawing.IF),
      top.map { it.componentId },
    )
    val repeat = top[0]
    assertEquals("i", repeat.properties["index"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    // save → group; the rotate in it wraps the line in a group of its own.
    val save = nodes.getValue(repeat.slots.getValue(UiDrawing.OPS_SLOT).single())
    val rotated = nodes.getValue(save.slots.getValue(UiDrawing.OPS_SLOT).single())
    assertContains(
      UiExpressions.format(rotated.properties.getValue("rotate").jsonObject),
      "@i * 30",
    )
    assertEquals(
      "draw/line",
      nodes.getValue(rotated.slots.getValue(UiDrawing.OPS_SLOT).single()).componentId,
    )
    // A flat save … restore is the same group, the clip inside it holding the oval.
    val clip = nodes.getValue(top[1].slots.getValue(UiDrawing.OPS_SLOT).single())
    assertEquals(UiDrawing.CLIP, clip.componentId)
    assertEquals(
      "draw/oval",
      nodes.getValue(clip.slots.getValue(UiDrawing.OPS_SLOT).single()).componentId,
    )
    assertContains(UiExpressions.format(top[2].properties.getValue("condition").jsonObject), "<")
  }

  @Test
  fun `a straight path expression draws as a polyline sampling its parameter`() {
    val result =
      imported(
        """{"header":{"width":400,"height":400},"root":[{"type":"canvas","commands":[
          {"variable":{"name":"endX","value":"componentWidth()"}},
          {"pathExpression":{"id":"curve","expressionX":"a[0] * 2","expressionY":"200 + 150 * sin(a[0] + continuousSec())",
            "start":0,"end":"@endX","count":128.0,"flags":4}},
          {"polarPathExpression":{"id":"ring","expressionR":"100 + 10 * sin(a[0] * 6)",
            "start":0,"end":6.2831853,"count":64,"cx":200,"cy":200,"flags":5}},
          {"pathExpression":{"id":"smooth","expressionX":"a[0]","expressionY":"a[0]","start":0,"end":1,"count":8,"flags":0}},
          {"pathExpression":{"id":"wound","expressionX":"a[0]","expressionY":"a[0]","start":0,"end":1,"count":8,"flags":16777220}},
          {"paint":{"ops":[{"color":"#FF2E6F52"},{"style":"stroke"},{"strokeWidth":3}]}},
          {"drawPath":{"path":"curve"}},
          {"drawPath":{"path":"ring"}},
          {"drawPath":{"path":"smooth"}}
        ]}]}"""
      )
    val nodes = result.document.nodes
    val canvas = nodes.values.single { it.componentId == UiDrawing.CANVAS }
    val (curve, ring) = canvas.slots.getValue(UiDrawing.OPS_SLOT).map(nodes::getValue)
    assertEquals(UiDrawing.POLYLINE, curve.componentId)
    fun text(node: UiBuilderNode, key: String) =
      node.properties.getValue(key).jsonObject.let {
        if (it["type"]!!.jsonPrimitive.content == "expr") UiExpressions.format(it)
        else it["value"]!!.jsonPrimitive.content
      }
    assertEquals("128.0", text(curve, "count"))
    assertEquals("point", text(curve, "index"))
    // a[0] runs from start to end over count - 1 steps: the last point is at `end`.
    assertEquals("(0 + @point * (canvas.width - 0) / 127) * 2", text(curve, "pointXDp"))
    assertContains(text(curve, "pointYDp"), "time.continuousSecond")
    assertEquals("stroke", text(curve, "style"))
    // A loop closes, a step short of `end`; a polar radius turns about the centre.
    assertEquals("true", text(ring, "closed"))
    assertContains(text(ring, "pointXDp"), "cos(")
    assertContains(text(ring, "pointXDp"), "/ 64")
    // A smoothed path is reported where it is defined, and drawing it is not read as SVG data.
    // So is one asking for a winding the polyline's fill would not keep.
    assertEquals(3, result.gaps.size, result.gaps.toString())
    assertTrue(result.gaps[0].startsWith("root[0].commands[3]") && "smoothed" in result.gaps[0])
    assertTrue(result.gaps[1].startsWith("root[0].commands[4]") && "winding" in result.gaps[1])
    assertTrue("`smooth` was not imported" in result.gaps[2], result.gaps.toString())
  }

  @Test
  fun `a path built by commands is its path data, or a polyline when its points are live`() {
    val result =
      imported(
        """{"header":{"width":300,"height":300},"root":[{"type":"canvas","commands":[
          {"pathCreate":{"id":"tri","x":150.0,"y":40.0}},
          {"pathAppendLineTo":{"path":"tri","x":260,"y":260.0}},
          {"pathAppendLineTo":{"path":"tri","x":40.0,"y":260.0}},
          {"pathAppendClose":{"path":"tri"}},
          {"paint":{"ops":[{"color":"#FF2E6F52"}]}},
          {"drawPath":{"path":"tri"}},
          {"pathCreate":{"id":"live","x":0,"y":0}},
          {"pathAppendLineTo":{"path":"live","x":"width - 40","y":10}},
          {"pathAppendLineTo":{"path":"live","x":5,"y":"continuousSec()"}},
          {"drawPath":{"path":"live"}},
          {"loop":{"index":"i","from":0,"until":3,"commands":[
            {"pathCreate":{"id":"fan","x":150,"y":150}},
            {"pathAppendLineTo":{"path":"fan","x":"150 + i","y":0}},
            {"drawPath":{"path":"fan"}}]}},
          {"pathCreate":{"id":"wave","x":0,"y":0}},
          {"loop":{"index":"i","from":1,"until":4,"commands":[
            {"pathAppendLineTo":{"path":"wave","x":"i * 5","y":"sin(i)"}}]}},
          {"pathAppendLineTo":{"path":"wave","x":20,"y":0}},
          {"drawPath":{"path":"wave"}},
          {"pathCreate":{"id":"mixed","x":0,"y":0}},
          {"loop":{"index":"i","from":0,"until":3,"commands":[
            {"variable":{"name":"v","value":"i * 2"}},
            {"pathAppendLineTo":{"path":"mixed","x":"@v","y":"i"}}]}},
          {"drawPath":{"path":"mixed"}}
        ]}]}"""
      )
    val nodes = result.document.nodes
    val canvas = nodes.values.single { it.componentId == UiDrawing.CANVAS }
    val (tri, live, repeat) = canvas.slots.getValue(UiDrawing.OPS_SLOT).map(nodes::getValue)
    fun text(node: UiBuilderNode, key: String) =
      node.properties.getValue(key).jsonObject.let {
        if (it["type"]!!.jsonPrimitive.content == "expr") UiExpressions.format(it)
        else it["value"]!!.jsonPrimitive.content
      }
    assertEquals("draw/path", tri.componentId)
    assertEquals("M150 40 L260 260 L40 260 Z", text(tri, "pathData"))
    assertEquals("#FF2E6F52", text(tri, "color"))
    assertEquals(UiDrawing.POLYLINE, live.componentId)
    assertEquals("3.0", text(live, "count"))
    assertEquals(
      "select(@point < 1, 0, select(@point < 2, 10, time.continuousSecond))",
      text(live, "pointYDp"),
    )
    // A path built and drawn in each pass of a loop is a polyline in the repeat.
    val fan = nodes.getValue(repeat.slots.getValue(UiDrawing.OPS_SLOT).single())
    assertEquals(UiDrawing.POLYLINE, fan.componentId)
    assertContains(text(fan, "pointXDp"), "150 + @i")
    // A loop whose every pass appends a line is a run of points, reading the loop's index from
    // the point's number: points 1..3 are passes 1..3, point 4 the line after the loop.
    val wave = canvas.slots.getValue(UiDrawing.OPS_SLOT).map(nodes::getValue)[3]
    assertEquals(UiDrawing.POLYLINE, wave.componentId)
    assertEquals("5.0", text(wave, "count"))
    assertEquals(
      "select(@point < 1, 0, select(@point < 4, (1 + (@point - 1) * 1) * 5, 20))",
      text(wave, "pointXDp"),
    )
    // A loop doing more than appending builds a different path each pass, and is refused.
    assertTrue(
      result.gaps.any { "`mixed` is appended to from another loop" in it },
      result.gaps.toString(),
    )
    assertTrue(result.gaps.any { "`mixed` was not imported" in it }, result.gaps.toString())
  }

  @Test
  fun `what has no builder equivalent is skipped and reported where it is`() {
    val result =
      imported(
        """{"header":{"width":100,"height":100},"root":[{"type":"canvas","commands":[
          {"drawRect":{"left":0,"top":0,"right":10,"bottom":10}},
          {"matrix3D":{"m":[1,0,0,1]}},
          {"drawCircle":{"cx":"arrayGet(@data, 0)","cy":5,"radius":2}}
        ]}]}"""
      )
    val ops =
      result.document.nodes.values.filter {
        it.componentId.startsWith("draw/") && it.componentId != UiDrawing.CANVAS
      }
    assertEquals(listOf("draw/rect"), ops.map { it.componentId })
    assertTrue(
      result.gaps.any { "root[0].commands[1]" in it && "matrix3D" in it },
      result.gaps.toString(),
    )
    assertTrue(
      result.gaps.any { "root[0].commands[2]" in it && "arrayGet" in it },
      result.gaps.toString(),
    )
  }

  /**
   * Run with `-PremoteJsonCorpus=<RemoteCompose-Examples checkout>` to import every corpus document
   * and write `build/remote-json-import/report.md`: how many import whole, and what stops the rest.
   */
  @Test
  fun `the corpus imports as far as the builder reaches`() {
    val corpus = System.getProperty("remoteJsonCorpus")?.let(::File) ?: return
    val documents =
      corpus.resolve("docs").walkTopDown().filter { it.name == "doc.json" }.sorted().toList()
    check(documents.isNotEmpty()) { "no docs/*/*/doc.json under $corpus" }
    val reasons = mutableMapOf<String, MutableSet<String>>()
    var clean = 0
    var refused = 0
    for (file in documents) {
      val json = Json.parseToJsonElement(file.readText()) as? JsonObject ?: continue
      if (json["root"] == null) {
        refused++
        reasons.getOrPut("no `root` layout tree (an `ops` document)") { mutableSetOf() } +=
          file.parentFile.name
        continue
      }
      val result = RemoteJsonImporter.import(json, SYSTEM_ID, id = file.parentFile.name)
      if (result.gaps.isEmpty()) clean++
      result.gaps
        .map { gap ->
          gap
            .substringAfter(": ")
            .replace(
              Regex("`[^`]*` is not defined( before)? here"),
              "a variable is not defined here",
            )
            .replace(Regex("""`[^`]*`: """), "")
        }
        .map { gap ->
          // Numbers vary per document; names in backticks do not.
          gap
            .split('`')
            .mapIndexed { i, part ->
              if (i % 2 == 1) part else part.replace(Regex("""\d+(\.\d+)?"""), "N")
            }
            .joinToString("`")
            .take(110)
        }
        .toSet()
        .forEach { reasons.getOrPut(it) { mutableSetOf() } += file.parentFile.name }
    }
    val report = buildString {
      appendLine("# RemoteCompose-Examples import")
      appendLine()
      appendLine(
        "${documents.size} documents: $clean import with no gaps, $refused have no layout tree."
      )
      appendLine()
      appendLine("| Documents | Gap |")
      appendLine("| ---: | --- |")
      reasons.entries
        .sortedByDescending { it.value.size }
        .forEach { (reason, docs) ->
          appendLine("| ${docs.size} | ${reason.replace("|", "\\|")} |")
        }
    }
    File("build/remote-json-import").apply { mkdirs() }.resolve("report.md").writeText(report)
    println(report.lines().take(40).joinToString("\n"))
  }
}

private const val SYSTEM_ID = "remote-m3"
