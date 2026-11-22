package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

class KobentoComponentExpanderTest {
  /** A tiny library in Kobento's definition shape: what each rule below needs, nothing more. */
  private val library =
    KobentoComponentExpander.Library.parse(
      definitions =
        """
        {"components": {
          "Pill": {
            "params": {
              "label": {"type": "string", "required": true},
              "size": {"type": "float", "default": 2, "static": true},
              "style": {"type": "enum", "default": "filled", "values": ["filled", "outlined"]},
              "onClick": {"type": "action"}
            },
            "slots": {"icon": {"optional": true}},
            "locals": {"pad": "@theme.space_2 * @size"},
            "body": {"row": {
              "modifiers": [{"padding": "@pad"}, {"onClick": "@onClick"},
                {"${'$'}if": "style == 'filled'", "then": {"background": "@colors.primary"}}],
              "children": [{"${'$'}slot": "icon"}, {"text": {"value": "Go @label"}}]}}
          },
          "Dots": {
            "params": {"items": {"type": "list", "required": true}, "value": {"type": "float", "default": 0}},
            "locals": {
              "n": {"${'$'}count": "items"},
              "w": "componentWidth() / @n",
              "eased": {"value": "@value", "anim": 1},
              "shown": "1 - 0"
            },
            "body": {"canvas": {"modifiers": [{"visibility": "@shown"}], "commands": [
              {"variable": {"name": "x", "value": "@eased * @w"}},
              {"${'$'}each": "items", "as": "it", "index": "i",
               "do": {"drawCircle": {"cx": "@x + @i", "cy": 0, "radius": "@it"}}}]}}
          },
          "Toggle": {
            "params": {"checked": {"type": "variable", "initial": 1}},
            "body": {"box": {"modifiers": [{"onClick": {"type": "valueFloatChange", "target": "@checked", "value": 0}}]}}
          },
          "Echo": {
            "params": {"value": {"type": "float", "default": 0}, "size": {"type": "float", "default": "@theme.space_2"}},
            "locals": {"twice": "@size * 2"},
            "body": {"canvas": {"commands": [
              {"variable": {"name": "x", "value": "@value"}},
              {"variable": {"name": "now", "value": "@x"}},
              {"drawCircle": {"cx": "@twice", "cy": 0, "radius": 1}}]}}
          },
          "Pair": {"body": [{"box": {}}, {"box": {}}]},
          "Glyph": {"params": {"name": {"type": "string", "required": true}}, "body": {"canvas": {"commands": [{"${'$'}icon": "@name"}]}}}
        }}
        """,
      theme =
        """{"numbers": {"space_2": 8}, "colors": {"primary": {"light": "#FF1565D8", "dark": "#FF4DA3FF"}}}""",
    )

  private fun expanded(root: String): JsonObject {
    val document =
      Json.parseToJsonElement("""{"header": {"apiLevel": 7}, "root": $root}""").jsonObject
    val result = KobentoComponentExpander.expand(document, library)
    check(result is KobentoComponentExpander.Result.Expanded) { result.toString() }
    return result.document
  }

  @Test
  fun `a call is its body with its arguments, defaults, theme and locals written in`() {
    val document =
      expanded(
        """{"column": {"children": [
          {"Pill": {"label": "now", "slots": {"icon": [{"box": {}}]}}},
          {"Pill": {"label": "later", "style": "outlined", "size": 3}}]}}""",
      )
    kotlin.test.assertEquals(
      Json.parseToJsonElement(
        """{"column": {"children": [
          {"row": {"modifiers": [{"padding": 16}, {"background": "@colors.primary"}],
                   "children": [{"box": {}}, {"text": {"value": "Go now"}}]}},
          {"row": {"modifiers": [{"padding": 24}], "children": [{"text": {"value": "Go later"}}]}}]}}""",
      ),
      document["root"],
    )
    // Each theme colour read is the document's, in light and dark.
    kotlin.test.assertEquals(
      Json.parseToJsonElement("""{"primary": {"light": "#FF1565D8", "dark": "#FF4DA3FF"}}"""),
      document["resources"]!!.jsonObject["colors"],
    )
  }

  @Test
  fun `layout reads are tree variables, names a body declares are the call's, and each repeats`() {
    val document = expanded("""{"Dots": {"items": [3, 5], "value": "@level"}}""")
    kotlin.test.assertEquals(
      Json.parseToJsonElement(
        """{"canvas": {"modifiers": [{"visibility": "@vis_1"}], "commands": [
          {"variable": {"name": "dots1_w", "value": "componentWidth() / 2"}},
          {"variable": {"name": "dots1_eased", "value": {"value": "@level", "anim": 0.999}}},
          {"variable": {"name": "dots1_x", "value": "@dots1_eased * @dots1_w"}},
          {"drawCircle": {"cx": "@dots1_x + 0", "cy": 0, "radius": 3}},
          {"drawCircle": {"cx": "@dots1_x + 1", "cy": 0, "radius": 5}}]}}""",
      ),
      document["root"],
    )
    // A visibility that folded is read from a declared variable, named after its value.
    kotlin.test.assertEquals(
      Json.parseToJsonElement("""{"vis_1": 1}"""),
      document["resources"]!!.jsonObject["variables"],
    )
  }

  @Test
  fun `an unbound variable param is the call's own variable, starting at its initial value`() {
    val document =
      expanded("""{"row": {"children": [{"Toggle": {}}, {"Toggle": {"checked": "@wifi"}}]}}""")
    kotlin.test.assertEquals(
      Json.parseToJsonElement("""{"toggle1_checked": 1}"""),
      document["resources"]!!.jsonObject["variables"],
    )
    kotlin.test.assertEquals(
      "@wifi",
      document["root"]!!.toString().let {
        Regex(""""target":"(@\w+)"""").findAll(it).last().groupValues[1]
      },
    )
  }

  @Test
  fun `a caller's variable keeps its name where the body declares one of the same name`() {
    val root = expanded("""{"Echo": {"value": "@x"}}""")["root"].toString()
    // The body's x is the call's own; the caller's @x, its argument, stays the caller's.
    kotlin.test.assertTrue(""""name":"echo1_x","value":"@x"""" in root, root)
    kotlin.test.assertTrue(""""name":"echo1_now","value":"@echo1_x"""" in root, root)
  }

  @Test
  fun `a theme number reaches resources, and a root of several nodes is refused`() {
    val document = expanded("""{"Echo": {"value": 1}}""")
    // The local reads a body name, so it is a tree variable here; a theme default is its number.
    kotlin.test.assertTrue("@theme" !in document.toString(), document.toString())
    kotlin.test.assertEquals(
      KobentoComponentExpander.Result.Refused(
        listOf("root: expands to 2 nodes; a document has one root"),
      ),
      KobentoComponentExpander.expand(
        Json.parseToJsonElement("""{"root": {"Pair": {}}}""").jsonObject,
        library,
      ),
    )
  }

  @Test
  fun `a local cannot read a name the body declares, and a grouped condition is one term`() {
    val ordered =
      KobentoComponentExpander.Library.parse(
        """{"components": {
          "Late": {"locals": {"y": "@x * 2"}, "body": {"canvas": {"commands": [
            {"variable": {"name": "x", "value": "componentWidth()"}},
            {"drawCircle": {"cx": "@y", "cy": 0, "radius": 1}}]}}},
          "Pick": {"params": {"a": {"type": "bool", "default": true}, "b": {"type": "bool", "default": false},
                              "c": {"type": "bool", "default": true}},
                   "body": {"${'$'}if": "(a || b) && c", "then": {"box": {}}, "else": {"row": {}}}}
        }}""",
        """{"numbers": {}, "colors": {}}""",
      )
    kotlin.test.assertEquals(
      KobentoComponentExpander.Result.Refused(
        listOf("root.Late.locals.y: reads late1_x, which the body declares after it"),
      ),
      KobentoComponentExpander.expand(
        Json.parseToJsonElement("""{"root": {"Late": {}}}""").jsonObject,
        ordered,
      ),
    )
    val picked =
      KobentoComponentExpander.expand(
        Json.parseToJsonElement("""{"root": {"Pick": {}}}""").jsonObject,
        ordered,
      )
    kotlin.test.assertEquals(
      Json.parseToJsonElement("""{"box": {}}"""),
      (picked as KobentoComponentExpander.Result.Expanded).document["root"],
    )
  }

  @Test
  fun `a body that calls a component keeps its tree variables and their readers in the tree`() {
    val nested =
      KobentoComponentExpander.Library.parse(
        """{"components": {
          "Outer": {"locals": {"w": "componentWidth()"}, "body": {"Inner": {"width": "@w"}}},
          "Inner": {"params": {"width": {"type": "float", "required": true}},
                    "locals": {"r": "@width * 2"},
                    "body": {"box": {"modifiers": [{"width": "@r"}]}}}
        }}""",
        """{"numbers": {}, "colors": {}}""",
      )
    val document =
      KobentoComponentExpander.expand(
        Json.parseToJsonElement("""{"root": {"Outer": {}}}""").jsonObject,
        nested,
      ) as KobentoComponentExpander.Result.Expanded
    kotlin.test.assertEquals(
      Json.parseToJsonElement(
        """{"box": {"modifiers": [{"width": "@inner1_r"}], "children": [
          {"variable": {"name": "outer1_w", "value": "componentWidth()"}},
          {"variable": {"name": "inner1_r", "value": "@outer1_w * 2"}}]}}""",
      ),
      document.document["root"],
    )
    kotlin.test.assertEquals(null, document.document["resources"])
  }

  @Test
  fun `a name the document already declares, and a definition that calls itself, are refused`() {
    val taken =
      Json.parseToJsonElement(
          """{"resources": {"variables": {"toggle1_checked": 0}}, "root": {"Toggle": {}}}""",
        )
        .jsonObject
    kotlin.test.assertEquals(
      KobentoComponentExpander.Result.Refused(
        listOf(
          "resources.variables.toggle1_checked: the document declares toggle1_checked, which an expansion declares too",
        ),
      ),
      KobentoComponentExpander.expand(taken, library),
    )
    val loop =
      Json.parseToJsonElement(
          """{"components": {"Ping": {"body": {"Pong": {}}}, "Pong": {"body": {"Ping": {}}}},
            "root": {"Ping": {}}}""",
        )
        .jsonObject
    kotlin.test.assertEquals(
      KobentoComponentExpander.Result.Refused(
        listOf("root.Ping[0].Pong[0].Ping: Ping calls itself (Ping > Pong > Ping)"),
      ),
      KobentoComponentExpander.expand(loop, library),
    )
    // A component in its own slot, from the caller, nests: it ends where the caller's nodes do.
    val nested =
      Json.parseToJsonElement(
          """{"components": {"Wrap": {"slots": {"content": {}},
              "body": {"box": {"children": [{"${'$'}slot": "content"}]}}}},
            "root": {"Wrap": {"children": [{"Wrap": {}}]}}}""",
        )
        .jsonObject
    val result = KobentoComponentExpander.expand(nested, library)
    check(result is KobentoComponentExpander.Result.Expanded) { result.toString() }
    kotlin.test.assertEquals(
      Json.parseToJsonElement("""{"box": {"children": [{"box": {"children": []}}]}}"""),
      result.document["root"],
    )
  }

  @Test
  fun `what the expander cannot write is refused with its path`() {
    val document = Json.parseToJsonElement("""{"root": {"Glyph": {"name": "home"}}}""").jsonObject
    val refused = KobentoComponentExpander.expand(document, library)
    kotlin.test.assertEquals(
      KobentoComponentExpander.Result.Refused(
        listOf("root.Glyph.body.canvas.commands[0]: \$icon is not expanded yet"),
      ),
      refused,
    )
    val missing = Json.parseToJsonElement("""{"root": {"Pill": {}}}""").jsonObject
    kotlin.test.assertEquals(
      KobentoComponentExpander.Result.Refused(listOf("root.Pill.label: Pill needs label")),
      KobentoComponentExpander.expand(missing, library),
    )
  }

  /**
   * Run with `-PkobentoExpansionCorpus=<dir>` to expand every demo there and compare it with
   * Kobento's own expansion, writing `build/kobento-expansion/report.md`: how many match, and the
   * first difference in each that does not.
   */
  @Test
  fun `the corpus expands as Kobento expands it`() {
    val corpus = System.getProperty("kobentoExpansionCorpus")?.let(::File) ?: return
    val library =
      KobentoComponentExpander.Library.parse(
        corpus.resolve("definitions.json").readText(),
        corpus.resolve("theme.json").readText(),
      )
    val demos = corpus.resolve("demos").listFiles()!!.filter { it.extension == "json" }.sorted()
    var matched = 0
    val rows = mutableListOf<String>()
    for (demo in demos) {
      val name = demo.nameWithoutExtension
      val expected = Json.parseToJsonElement(corpus.resolve("expanded/$name.json").readText())
      val result = runCatching {
        KobentoComponentExpander.expand(
          Json.parseToJsonElement(demo.readText()).jsonObject,
          library,
        )
      }
        .getOrElse { failure -> KobentoComponentExpander.Result.Refused(listOf("threw $failure")) }
      when (result) {
        is KobentoComponentExpander.Result.Refused ->
          rows += "| $name | refused | ${result.reasons.first().take(160).escaped()} |"
        is KobentoComponentExpander.Result.Expanded -> {
          val difference = firstDifference(expected, result.document, "")
          if (difference == null) matched++
          else rows += "| $name | differs | ${difference.take(220).escaped()} |"
        }
      }
    }
    val report = buildString {
      appendLine("# Kobento expansion")
      appendLine()
      appendLine("$matched of ${demos.size} demos expand exactly as Kobento expands them.")
      appendLine()
      appendLine("| Demo | | First difference |")
      appendLine("| --- | --- | --- |")
      rows.forEach(::appendLine)
    }
    File("build/kobento-expansion").apply { mkdirs() }.resolve("report.md").writeText(report)
    println(report.lines().take(3).joinToString("\n"))
  }

  private fun String.escaped() = replace("|", "\\|").replace("\n", " ")

  /**
   * The first place [actual] differs from [expected], as JSON values compare (numbers by value).
   */
  private fun firstDifference(expected: JsonElement, actual: JsonElement, path: String): String? {
    if (expected is JsonObject && actual is JsonObject) {
      (expected.keys + actual.keys).forEach { key ->
        val e = expected[key]
        val a = actual[key]
        if (e == null) return "$path.$key: unexpected ${a.short()}"
        if (a == null) return "$path.$key: missing ${e.short()}"
        firstDifference(e, a, "$path.$key")?.let {
          return it
        }
      }
      return null
    }
    if (expected is JsonArray && actual is JsonArray) {
      expected.indices.forEach { i ->
        val a = actual.getOrNull(i) ?: return "$path[$i]: missing ${expected[i].short()}"
        firstDifference(expected[i], a, "$path[$i]")?.let {
          return it
        }
      }
      if (actual.size > expected.size)
        return "$path[${expected.size}]: unexpected ${actual[expected.size].short()}"
      return null
    }
    if (expected is JsonPrimitive && actual is JsonPrimitive) {
      if (expected == actual) return null
      val e = expected.takeUnless { it.isString }?.doubleOrNull
      val a = actual.takeUnless { it.isString }?.doubleOrNull
      if (e != null && a != null && e == a) return null
    }
    return "$path: expected ${expected.short()}, got ${actual.short()}"
  }

  private fun JsonElement?.short() = toString().take(90)
}
