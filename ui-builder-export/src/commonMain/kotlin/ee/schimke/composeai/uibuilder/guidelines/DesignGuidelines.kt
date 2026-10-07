package ee.schimke.composeai.uibuilder.guidelines

import ee.schimke.composeai.uibuilder.EMBEDDED_ANDROID_DESIGN_GUIDELINES_JSON
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * One rule from the Android design guides, as a model is asked to judge it: the guidance quoted
 * from [source], and a yes/no [check] where YES means the design follows it. [kind] is `structure`
 * when the design tree is enough evidence and `visual` when the model needs a picture.
 */
@Serializable
data class DesignGuidelineRule(
  val id: String,
  val platforms: List<String>,
  val kind: String,
  val severity: String,
  val guidance: String,
  val check: String,
  val source: String,
) {
  val visual: Boolean
    get() = kind == KIND_VISUAL

  companion object {
    const val KIND_VISUAL: String = "visual"
  }
}

@Serializable
data class DesignGuidelineRuleSet(
  val schema: String,
  val version: Int,
  val about: String = "",
  val rules: List<DesignGuidelineRule>,
) {
  fun forPlatform(platform: String): List<DesignGuidelineRule> = rules.filter {
    platform in it.platforms
  }

  companion object {
    /**
     * `docs/guidelines/android-design-guidelines.json`, embedded at build time by
     * `:ui-builder-export:embedDesignGuidelines`: the rules the editor and compose-preview-server
     * both ask, from the one file.
     */
    val Bundled: DesignGuidelineRuleSet by lazy {
      GUIDELINE_JSON.decodeFromString(serializer(), EMBEDDED_ANDROID_DESIGN_GUIDELINES_JSON)
    }
  }
}

/** One rule's answer, as the model returns it. */
@Serializable
data class DesignGuidelineVerdict(
  val ruleId: String,
  /** `pass`, `fail` or `not_applicable`. */
  val verdict: String,
  val confidence: Double = 0.0,
  val nodeIds: List<String> = emptyList(),
  val reason: String = "",
)

@Serializable private data class DesignGuidelineVerdicts(val verdicts: List<DesignGuidelineVerdict>)

/** A rule the model judged broken, ready for the Issues panel. */
data class DesignGuidelineFinding(
  val rule: DesignGuidelineRule,
  val reason: String,
  val confidence: Double,
  /** The design nodes it is about, filtered to ones that exist; empty for the whole design. */
  val nodeIds: List<String>,
) {
  val confidencePercent: Int
    get() = (confidence * 100).roundToInt()
}

/**
 * The OpenRouter request a guidelines check sends and the reading of the reply. Pure, so the
 * browser host only moves bytes. compose-preview-server builds the same request from the same rules
 * for its server-side `guidelines` check.
 */
object DesignGuidelinePrompt {
  /** Which guides apply to a catalog, read from its system id; null for one with none yet. */
  fun platformOf(systemId: String): String? {
    val id = systemId.lowercase()
    return when {
      "glimmer" in id || "glasses" in id -> "glasses"
      "wear" in id || id.startsWith("remote") -> "wear"
      // Material 3 for phones and tablets: the plain `m3` catalog and its siblings.
      id == "m3" || id.startsWith("m3-") || "material3" in id -> "mobile"
      else -> null
    }
  }

  /** How much generated source a request carries; a screen's export is well under this. */
  const val MAX_SOURCE_CHARS: Int = 16_000

  const val SYSTEM_PROMPT: String =
    "You review UI designs against Android design guidelines. For every rule you are given, " +
      "answer the rule's yes/no `check` for this design: verdict `pass` when the answer is yes, " +
      "`fail` when it is no, `not_applicable` when the rule does not apply to this design or the " +
      "evidence cannot decide it. Judge only from what is provided: the design tree, and when " +
      "given the generated Jetpack Compose source (the code this design exports to; use it for " +
      "questions about code) and a rendered picture. Do not " +
      "assume content that is not there. Answer `fail` only when the design clearly breaks the " +
      "rule. `confidence` is your probability (0 to 1) that the verdict is right. `nodeIds` names " +
      "the design-tree node ids a `fail` is about (empty otherwise). `reason` is one short " +
      "sentence a designer can act on. Reply with JSON only: " +
      "{\"verdicts\":[{\"ruleId\":…,\"verdict\":…,\"confidence\":…,\"nodeIds\":[…],\"reason\":…}]}, " +
      "one entry per rule."

  fun userText(
    platform: String,
    document: JsonObject,
    rules: List<DesignGuidelineRule>,
    pictures: List<String> = emptyList(),
    source: String? = null,
  ): String = buildString {
    val environment = document["environment"] as? JsonObject
    val width = environment?.get("widthDp")?.numberOrNull()
    val height = environment?.get("heightDp")?.numberOrNull()
    val theme = environment?.get("theme")?.stringOrNull()
    append("Platform: ").append(platform).append('\n')
    append("Design: ").append(document["title"]?.stringOrNull() ?: "untitled")
    if (width != null && height != null) {
      append(", ").append(width.toInt()).append('×').append(height.toInt()).append("dp")
    }
    if (theme != null) append(", ").append(theme).append(" theme")
    append('\n')
    if (pictures.isNotEmpty()) {
      append("Attached pictures, in order:\n")
      pictures.forEach { append("- ").append(it).append('\n') }
    }
    append("\nDesign tree (node id, component, properties, modifiers; children by slot):\n")
    append(outline(document))
    if (source != null) {
      append("\nGenerated Jetpack Compose source (what this design exports to):\n```kotlin\n")
      if (source.length > MAX_SOURCE_CHARS) {
        append(source.take(MAX_SOURCE_CHARS)).append("\n// … ")
        append(source.length - MAX_SOURCE_CHARS).append(" more characters not shown\n")
      } else {
        append(source.trimEnd()).append('\n')
      }
      append("```\n")
    }
    append("\nRules:\n")
    rules.forEach { rule ->
      append("- ruleId: ").append(rule.id).append('\n')
      append("  guidance: ").append(rule.guidance).append('\n')
      append("  check: ").append(rule.check).append('\n')
    }
  }

  /**
   * The design as an indented tree a model can read: one line per node with its scalar properties
   * and modifiers, children under the slot that holds them. Asset bytes and bindings never appear.
   */
  fun outline(document: JsonObject, maxNodes: Int = 250): String {
    val nodes = document["nodes"] as? JsonObject ?: return "(no nodes)\n"
    val roots =
      (document["roots"] as? JsonArray)
        ?.mapNotNull { it.stringOrNull() }
        .orEmpty()
        .ifEmpty { nodes.keys.toList() }
    val seen = mutableSetOf<String>()
    val out = StringBuilder()
    fun visit(id: String, depth: Int) {
      if (seen.size >= maxNodes || !seen.add(id)) return
      val node = nodes[id] as? JsonObject ?: return
      out.append("  ".repeat(depth)).append("- ").append(id).append(": ")
      out.append(node["componentId"]?.stringOrNull() ?: "?")
      val properties = (node["properties"] as? JsonObject).orEmpty()
      val described =
        properties.entries.mapNotNull { (name, value) -> describeValue(value)?.let { "$name=$it" } }
      if (described.isNotEmpty()) out.append(" {").append(described.joinToString(", ")).append('}')
      val modifiers = (node["modifiers"] as? JsonArray).orEmpty().mapNotNull(::describeModifier)
      if (modifiers.isNotEmpty()) {
        out.append(" modifiers[").append(modifiers.joinToString(", ")).append(']')
      }
      val events = (node["eventBindings"] as? JsonObject)?.keys.orEmpty()
      if (events.isNotEmpty()) out.append(" events[").append(events.joinToString(", ")).append(']')
      (node["component"] as? JsonObject)?.let { instance ->
        out.append(" instance of ").append(instance["componentKey"]?.stringOrNull() ?: "?")
        val arguments =
          (instance["arguments"] as? JsonObject).orEmpty().entries.mapNotNull { (name, value) ->
            describeValue(value)?.let { "$name=$it" }
          }
        if (arguments.isNotEmpty())
          out.append(" (").append(arguments.joinToString(", ")).append(')')
      }
      out.append('\n')
      (node["slots"] as? JsonObject).orEmpty().forEach { (slot, children) ->
        val ids = (children as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }
        if (ids.isEmpty()) return@forEach
        out.append("  ".repeat(depth + 1)).append(slot).append(":\n")
        ids.forEach { visit(it, depth + 2) }
      }
    }
    roots.forEach { visit(it, 0) }
    // A component's body hangs off `components[key].root`, not off the slots of the nodes that
    // place it, so it is outlined once here and each placement names it with "instance of".
    (document["components"] as? JsonObject).orEmpty().forEach { (key, component) ->
      val root = (component as? JsonObject)?.get("root")?.stringOrNull() ?: return@forEach
      if (root in seen) return@forEach
      val name = (component as JsonObject)["name"]?.stringOrNull()
      out.append("component ").append(key).append(name?.let { " ($it)" }.orEmpty()).append(":\n")
      visit(root, 1)
    }
    if (seen.size < nodes.size) out.append("(${nodes.size - seen.size} more nodes not shown)\n")
    return out.toString()
  }

  /** A typed protocol value (`{"type":"string","value":"OK"}`) as a short literal, or null. */
  private fun describeValue(value: JsonElement): String? {
    val obj = value as? JsonObject ?: return (value as? JsonPrimitive)?.contentOrNull?.take(60)
    val type = obj["type"]?.stringOrNull()
    return when (type) {
      "string" -> obj["value"]?.stringOrNull()?.let { "\"${it.take(60)}\"" }
      "bool",
      "int",
      "float",
      "double",
      "enum",
      "dp",
      "sp" -> obj["value"]?.let(::primitiveText)
      "color" -> obj["value"]?.let(::primitiveText) ?: obj["role"]?.stringOrNull()
      "null" -> null
      "binding" -> obj["value"]?.stringOrNull()?.let { "{$it}" }
      "list" -> (obj["values"] as? JsonArray)?.let { "[${it.size} items]" }
      else ->
        obj["value"]?.let(::primitiveText)
          ?: obj["role"]?.stringOrNull()
          ?: obj["iconName"]?.stringOrNull()
          ?: type
    }
  }

  private fun describeModifier(value: JsonElement): String? {
    val obj = value as? JsonObject ?: return null
    val type = obj["type"]?.stringOrNull() ?: return null
    val args =
      obj.entries
        .filter { it.key != "type" }
        .mapNotNull { (name, arg) -> primitiveText(arg)?.let { "$name=$it" } }
    return if (args.isEmpty()) type else "$type(${args.joinToString(", ")})"
  }

  private fun primitiveText(value: JsonElement): String? =
    (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.take(40)

  /**
   * The verdicts out of an OpenAI-style chat completion: `choices[0].message.content`, which may be
   * fenced or carry text around the JSON when a model ignores `response_format`.
   */
  fun parseCompletion(body: String): Result<List<DesignGuidelineVerdict>> = runCatching {
    val completion = GUIDELINE_JSON.parseToJsonElement(body).jsonObject
    val content =
      completion["choices"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("message")
        ?.jsonObject
        ?.get("content")
        ?.stringOrNull() ?: error("no message content in the completion")
    parseVerdicts(content)
  }

  fun parseVerdicts(content: String): List<DesignGuidelineVerdict> {
    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    require(start >= 0 && end > start) { "no JSON object in: ${content.take(200)}" }
    return GUIDELINE_JSON.decodeFromString(
        DesignGuidelineVerdicts.serializer(),
        content.substring(start, end + 1),
      )
      .verdicts
      .map { it.copy(confidence = it.confidence.coerceIn(0.0, 1.0)) }
  }

  /** Confident `fail` verdicts on rules that were asked, as findings on nodes that exist. */
  fun findings(
    verdicts: List<DesignGuidelineVerdict>,
    asked: List<DesignGuidelineRule>,
    nodeIds: Set<String>,
    minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
  ): List<DesignGuidelineFinding> {
    val byId = asked.associateBy { it.id }
    return answered(verdicts, asked)
      .filter { it.verdict == VERDICT_FAIL && it.confidence >= minConfidence }
      .mapNotNull { verdict ->
        val rule = byId[verdict.ruleId] ?: return@mapNotNull null
        DesignGuidelineFinding(
          rule = rule,
          reason = verdict.reason.trim().ifEmpty { rule.check },
          confidence = verdict.confidence,
          nodeIds = verdict.nodeIds.filter { it in nodeIds }.distinct(),
        )
      }
      .sortedWith(compareBy({ it.rule.severity != "warning" }, { -it.confidence }))
  }

  /**
   * One verdict per rule asked: the first for each id, nothing for a rule nobody asked about. A
   * rule missing here got no answer, which is unchecked, never passed.
   */
  fun answered(
    verdicts: List<DesignGuidelineVerdict>,
    asked: List<DesignGuidelineRule>,
  ): List<DesignGuidelineVerdict> {
    val ids = asked.mapTo(mutableSetOf()) { it.id }
    return verdicts.filter { it.ruleId in ids }.distinctBy { it.ruleId }
  }

  /** OpenRouter's `{"error":{"message":…}}`, or the start of whatever came back. */
  fun errorMessage(body: String): String =
    runCatching {
      GUIDELINE_JSON.parseToJsonElement(body)
        .jsonObject["error"]
        ?.jsonObject
        ?.get("message")
        ?.stringOrNull()
    }
      .getOrNull() ?: body.take(200)

  const val VERDICT_FAIL: String = "fail"
  const val DEFAULT_MIN_CONFIDENCE: Double = 0.5

  /** The JSON schema a model's reply is held to. */
  val responseSchema: JsonObject
    get() = VERDICTS_SCHEMA

  private val VERDICTS_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonArray("required") { add(JsonPrimitive("verdicts")) }
    putJsonObject("properties") {
      putJsonObject("verdicts") {
        put("type", "array")
        putJsonObject("items") {
          put("type", "object")
          put("additionalProperties", false)
          putJsonArray("required") {
            listOf("ruleId", "verdict", "confidence", "nodeIds", "reason").forEach {
              add(JsonPrimitive(it))
            }
          }
          putJsonObject("properties") {
            putJsonObject("ruleId") { put("type", "string") }
            putJsonObject("verdict") {
              put("type", "string")
              put(
                "enum",
                buildJsonArray {
                  add(JsonPrimitive("pass"))
                  add(JsonPrimitive("fail"))
                  add(JsonPrimitive("not_applicable"))
                },
              )
            }
            putJsonObject("confidence") { put("type", "number") }
            putJsonObject("nodeIds") {
              put("type", "array")
              putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("reason") { put("type", "string") }
          }
        }
      }
    }
  }
}

internal val GUIDELINE_JSON: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

private fun JsonElement.stringOrNull(): String? =
  (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.numberOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull
