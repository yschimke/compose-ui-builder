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
