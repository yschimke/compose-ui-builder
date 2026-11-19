package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.*

/**
 * A design authored against the `kobento` catalog, written as Kobento component JSON: a Remote
 * Compose authoring document that **calls** Kobento's library components (`{"Button": {...}}`) and
 * names the libraries it calls them from in `componentSets`.
 *
 * Only Kobento's own `convert` compiles this. Its library lives on Kobento's server, so a call is
 * expanded there, into the plain AndroidX dialect that `remotecompose-json` and every player read.
 * A plain `.rc` of a Kobento design is therefore this document, converted by Kobento. See
 * `docs/design/UI_BUILDER_KOBENTO.md`.
 *
 * Sizes are written in dp with the header at the design's dp size: Kobento documents are authored
 * at density 1, and the player scales them.
 */
object KobentoComponentJsonExporter {
  const val COMPONENT_PREFIX: String = "kobento/"
  const val DOCUMENT: String = "kobento/document"
  const val TEXT: String = "kobento/text"

  /** The looks Kobento expands a component call in, plus its two whole-document modes. */
  val COMPONENT_THEMES: Set<String> =
    setOf("auto", "all", "light", "dark", "mono-dark", "mono-light")

  data class Options(
    /** `componentSets`, first entry winning: `"lib:<name>"`, `"lib:<name>@<version>"` or a set. */
    val componentSets: List<String> = emptyList(),
    /** `componentTheme`; null leaves Kobento's default (`auto`, light and dark). */
    val componentTheme: String? = null,
    /**
     * Per component id, the params Kobento fixes when it expands the call, so a state binding there
     * would be frozen at its initial value. The catalog publishes these as
     * `statusSemantics.kobento.staticParams`.
     */
    val staticParams: Map<String, Set<String>> = emptyMap(),
    /**
     * The component ids whose call takes an `onClick`, as `statusSemantics.kobento.clickable`
     * publishes them. Null skips the check; [withCatalog] fills it from the catalog.
     */
    val clickable: Set<String>? = null,
  ) {
    /** These options with [staticParams] and [clickable] read from `statusSemantics.kobento`. */
    fun withCatalog(kobento: JsonObject): Options =
      copy(
        staticParams =
          (kobento["staticParams"] as? JsonObject).orEmpty().mapValues { (_, names) ->
            (names as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
          },
        clickable =
          (kobento["clickable"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet(),
      )
  }

  sealed interface Result {
    data class Emitted(val source: String) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /** `kobento/activity-rings` -> `ActivityRings`; null for an id outside the catalog. */
  fun componentName(componentId: String): String? =
    componentId
      .removePrefix(COMPONENT_PREFIX)
      .takeIf { componentId.startsWith(COMPONENT_PREFIX) && it.isNotEmpty() }
      ?.split('-')
      ?.joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }

  fun export(document: UiBuilderDocument, options: Options = Options()): Result =
    Emitter(document, options).export()

  private class Emitter(val document: UiBuilderDocument, val options: Options) {
    val errors = mutableListOf<String>()
    val stateKinds = mutableMapOf<String, String>()
    val active = mutableSetOf<String>()

    fun export(): Result {
      options.componentTheme?.let {
        if (it !in COMPONENT_THEMES)
          errors += "options.componentTheme: $it is not one of ${COMPONENT_THEMES.joinToString()}"
      }
      options.componentSets
        .filter { it.isBlank() }
        .forEach { _ -> errors += "options.componentSets: a set needs a name" }
      val width = number(document.environment["widthDp"], "environment.widthDp", 360.0)
      val height = number(document.environment["heightDp"], "environment.heightDp", 640.0)
      if (width <= 0 || height <= 0) errors += "environment: the design needs a positive size"
      // As RemoteDocumentJsonExporter: what the canvas applies and this lowering does not write is
      // refused, so the export never shows a different arrangement or ground than the editor.
      if (document.environment["layoutDirection"]?.jsonPrimitive?.contentOrNull == "rtl")
        errors += "environment.layoutDirection: RTL lowering is not available"
      if (document.environment["background"]?.takeUnless { it is JsonNull } != null)
        errors += "environment.background: background lowering is not available"
      val variables = buildJsonObject {
        document.stateVariables.entries
          .sortedBy { it.key }
          .forEach { (name, declaration) -> declare(name, declaration)?.let { put(name, it) } }
      }
      val root =
        when (document.roots.size) {
          1 -> node(document.roots.single())
          else -> {
            errors += "roots: a Kobento document has exactly one root, not ${document.roots.size}"
            null
          }
        }
      if (errors.isNotEmpty() || root == null) return Result.Refused(errors.distinct())
      val source = buildJsonObject {
        putJsonObject("header") {
          put("apiLevel", 7)
          put("width", width.roundedNumber())
          put("height", height.roundedNumber())
          put("profiles", 513)
          if (document.title.isNotBlank()) put("contentDescription", document.title)
        }
        if (options.componentSets.isNotEmpty())
          put("componentSets", JsonArray(options.componentSets.map(::JsonPrimitive)))
        options.componentTheme?.let { put("componentTheme", it) }
        if (variables.isNotEmpty()) putJsonObject("resources") { put("variables", variables) }
        put("root", root)
      }
      return Result.Emitted(prettyJson.encodeToString(JsonObject.serializer(), source))
    }

    /**
     * A state variable as a Kobento float variable, exported so the host can read and set it.
     * Kobento's components take a bool as an exact 0/1 float.
     */
    fun declare(name: String, value: JsonElement): JsonElement? {
      val path = "stateVariables.$name"
      val declaration = value as? JsonObject
      val kind = declaration?.get("valueType")?.jsonPrimitive?.contentOrNull
      // A Kobento float has no null, so nullable state cannot keep its meaning.
      if ((declaration?.get("nullable") as? JsonPrimitive)?.booleanOrNull == true) {
        errors += "$path: nullable state has no Kobento variable"
        return null
      }
      val initial =
        declaration?.get("initialValue")?.takeUnless { it is JsonNull } as? JsonPrimitive
      val number: Double? =
        when {
          // An absent initial value is the kind's zero, as in the other lanes.
          initial == null -> if (kind in setOf("bool", "int", "float")) 0.0 else null
          kind == "bool" -> initial.booleanOrNull?.let { if (it) 1.0 else 0.0 }
          kind == "int" || kind == "float" -> initial.takeUnless { it.isString }?.doubleOrNull
          else -> null
        }
      if (number == null || !number.isFinite()) {
        errors += "$path: only bool, int and float state has a Kobento variable"
        return null
      }
      if (kind == "int" && !exactInFloat(number)) {
        errors += "$path: an int beyond ±$MAX_EXACT_FLOAT_INT is not exact in a Kobento float"
        return null
      }
      stateKinds[name] = kind!!
      return buildJsonObject {
        put("value", number.roundedNumber())
        put("export", true)
      }
    }

    fun node(id: String): JsonObject? {
      val node = document.nodes[id]
      val path = "nodes.$id"
      if (node == null) {
        errors += "$path: missing node"
        return null
      }
      if (!active.add(id)) {
        errors += "$path: cycle"
        return null
      }
      try {
        return when (node.componentId) {
          DOCUMENT -> document(node)
          "layout/column",
          "layout/row",
          "layout/box" -> layout(node)
          TEXT -> text(node)
          else -> component(node)
        }
      } finally {
        active.remove(id)
      }
    }

    fun document(node: UiBuilderNode): JsonObject {
      val path = "nodes.${node.id}"
      unsupportedSlots(node, setOf("content"))
      node.properties.keys.forEach {
        errors += "$path.properties.$it: property lowering is not available"
      }
      clickNotAllowed(node)
      return single(
        "column",
        buildJsonObject {
          put(
            "modifiers",
            buildJsonArray {
              add("fillMaxSize")
              add(buildJsonObject { put("background", "@colors.background") })
              modifiers(node).forEach(::add)
            },
          )
          children(node, "content")?.let { put("children", it) }
        },
      )
    }

    fun layout(node: UiBuilderNode): JsonObject {
      val path = "nodes.${node.id}"
      val type = node.componentId.removePrefix("layout/")
      val spacing =
        when (type) {
          "row" -> "horizontalSpacingDp"
          "column" -> "verticalSpacingDp"
          else -> null
        }
      val alignments =
        when (type) {
          "row" -> mapOf("verticalAlignment" to setOf("top", "center", "bottom"))
          "column" -> mapOf("horizontalAlignment" to setOf("start", "center", "end"))
          else -> emptyMap()
        }
      node.properties.keys
        .filter { it != spacing && it !in alignments }
        .forEach { errors += "$path.properties.$it: property lowering is not available" }
      unsupportedSlots(node, setOf("children"))
      clickNotAllowed(node)
      return single(
        type,
        buildJsonObject {
          alignments.forEach { (key, allowed) ->
            val value = node.properties[key] ?: return@forEach
            val word = (literal(value, "$path.properties.$key") as? JsonPrimitive)?.contentOrNull
            if (word in allowed) {
              put(key, word)
            } else {
              errors += "$path.properties.$key: expected ${allowed.joinToString()}"
            }
          }
          put(
            "modifiers",
            buildJsonArray {
              modifiers(node).forEach(::add)
              spacing
                ?.let { node.properties[it] }
                ?.let { value ->
                  add(
                    buildJsonObject {
                      put(
                        "spacedBy",
                        number(
                            literal(value, "$path.properties.$spacing"),
                            "$path.properties.$spacing",
                            0.0,
                          )
                          .roundedNumber(),
                      )
                    },
                  )
                }
            },
          )
          children(node, "children")?.let { put("children", it) }
        },
      )
    }

    fun text(node: UiBuilderNode): JsonObject {
      val path = "nodes.${node.id}"
      unsupportedSlots(node, emptySet())
      clickNotAllowed(node)
      val fields = buildJsonObject {
        node.properties.forEach { (name, value) ->
          if (name !in setOf("value", "fontSize", "color"))
            errors += "$path.properties.$name: property lowering is not available"
          else value(value, "$path.properties.$name")?.let { put(name, it) }
        }
        val modifiers = modifiers(node)
        if (modifiers.isNotEmpty()) put("modifiers", JsonArray(modifiers))
      }
      if ("value" !in fields) errors += "$path.properties.value: text needs a value"
      return single("text", fields)
    }

    fun component(node: UiBuilderNode): JsonObject? {
      val path = "nodes.${node.id}"
      val name = componentName(node.componentId)
      if (name == null) {
        errors += "$path.componentId: ${node.componentId} is not a Kobento component"
        return null
      }
      if (node.modifiers.isNotEmpty())
        errors += "$path.modifiers: a Kobento component call takes params, not modifiers"
      return single(
        name,
        buildJsonObject {
          val static = options.staticParams[node.componentId].orEmpty()
          node.properties.forEach { (property, value) ->
            if (
              property in static && (value as? JsonObject)?.get("type") == JsonPrimitive("state")
            ) {
              errors += "$path.properties.$property: a static Kobento param cannot follow state"
              return@forEach
            }
            value(value, "$path.properties.$property")?.let { put(property, it) }
          }
          node.eventBindings.forEach { (event, actions) ->
            if (event != "click") {
              errors += "$path.eventBindings.$event: only click has a Kobento mapping (onClick)"
              return@forEach
            }
            if (options.clickable != null && node.componentId !in options.clickable) {
              errors += "$path.eventBindings.click: ${node.componentId} takes no onClick"
              return@forEach
            }
            put("onClick", actions(actions, "$path.eventBindings.click"))
          }
          if (node.slots.values.any { it.isNotEmpty() })
            putJsonObject("slots") {
              node.slots.forEach { (slot, ids) ->
                if (ids.isNotEmpty()) put(slot, JsonArray(ids.mapNotNull { node(it) }))
              }
            }
        },
      )
    }

    fun children(node: UiBuilderNode, slot: String): JsonArray? =
      node.slots[slot]?.takeIf { it.isNotEmpty() }?.let { ids -> JsonArray(ids.mapNotNull(::node)) }

    fun unsupportedSlots(node: UiBuilderNode, allowed: Set<String>) {
      node.slots.keys
        .filter { it !in allowed }
        .forEach { errors += "nodes.${node.id}.slots.$it: unsupported slot" }
    }

    fun clickNotAllowed(node: UiBuilderNode) {
      node.eventBindings.keys.forEach {
        errors += "nodes.${node.id}.eventBindings.$it: put the action on a Kobento component"
      }
    }

    /** A property value: a literal is written as itself, a state binding as `"@name"`. */
    fun value(value: JsonElement, path: String): JsonElement? {
      val typed = value as? JsonObject ?: return value.takeUnless { it is JsonNull }
      return when (typed["type"]?.jsonPrimitive?.contentOrNull) {
        "state" -> {
          val variable = typed["variable"]?.jsonPrimitive?.contentOrNull
          if (variable == null || variable !in stateKinds) {
            errors += "$path: state ${variable ?: "?"} is not a declared bool, int or float"
            null
          } else JsonPrimitive("@$variable")
        }
        "binding" -> {
          errors += "$path: component arguments are not lowered to Kobento yet"
          null
        }
        // Only literal wrappers are written as themselves. Anything computed (`stateEquals`, a
        // `system` read such as `time.hour`, a formula) has no Kobento lowering yet; reading its
        // operand as a literal would drop the dependency.
        in LITERAL_TYPES -> literal(value, path)
        else -> {
          errors +=
            "$path: ${typed["type"]?.jsonPrimitive?.contentOrNull} values are not lowered to Kobento yet"
          null
        }
      }
    }

    fun literal(value: JsonElement, path: String): JsonElement? {
      val typed = value as? JsonObject ?: return value
      val literal = typed["value"]
      if (literal == null || typed["type"]?.jsonPrimitive?.contentOrNull == "state") {
        errors += "$path: expected a literal value"
        return null
      }
      return literal
    }

    fun actions(value: JsonElement, path: String): JsonElement {
      val list = value as? JsonArray
      if (list == null) {
        errors += "$path: expected an ordered action list"
        return JsonArray(emptyList())
      }
      val actions = list.mapIndexedNotNull { index, action ->
        action(action as? JsonObject, "$path[$index]")
      }
      return actions.singleOrNull() ?: JsonArray(actions)
    }

    fun action(action: JsonObject?, path: String): JsonObject? {
      if (action == null) {
        errors += "$path: expected an action"
        return null
      }
      if (HostActions.isHostAction(action)) {
        HostActions.issue(action)?.let {
          errors += "$path: $it"
          return null
        }
        return buildJsonObject {
          put("type", "hostNamedAction")
          put("name", HostActions.name(action)!!)
          HostActions.value(action)?.let { put("value", it.json) }
        }
      }
      val variable = action["variable"]?.jsonPrimitive?.contentOrNull
      val kind = stateKinds[variable]
      if (variable == null || kind == null) {
        errors += "$path.variable: state is not a declared bool, int or float"
        return null
      }
      val target = "@$variable"
      return when (action["type"]?.jsonPrimitive?.contentOrNull) {
        "toggle" ->
          if (kind == "bool")
            buildJsonObject {
              put("type", "valueFloatExpressionChange")
              put("target", target)
              put("value", "1 - $target")
            }
          else null.also { errors += "$path: toggle needs a bool" }
        "increment" -> {
          val authored = action["amount"]
          // Absent is one step. A computed amount (a formula) is refused, not read as one.
          val amount =
            if (authored == null || authored is JsonNull) 1.0
            else (authored as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
          if (amount == null || !amount.isFinite()) {
            errors += "$path.amount: only a literal number is lowered to Kobento"
            return null
          }
          buildJsonObject {
            put("type", "valueFloatExpressionChange")
            put("target", target)
            put(
              "value",
              if (amount < 0) "$target - ${(-amount).roundedNumber()}"
              else "$target + ${amount.roundedNumber()}",
            )
          }
        }
        "set",
        "select" -> {
          val literal = action["value"] as? JsonPrimitive
          val number =
            when {
              literal == null || literal is JsonNull || literal.isString -> null
              kind == "bool" -> literal.booleanOrNull?.let { if (it) 1.0 else 0.0 }
              else -> literal.doubleOrNull
            }
          if (number != null && kind == "int" && !exactInFloat(number)) {
            errors += "$path: an int beyond ±$MAX_EXACT_FLOAT_INT is not exact in a Kobento float"
            null
          } else if (number == null) {
            errors += "$path: set needs a $kind literal"
            null
          } else
            buildJsonObject {
              put("type", "valueFloatChange")
              put("target", target)
              put("value", number.roundedNumber())
            }
        }
        else -> null.also { errors += "$path: ${action["type"]} has no Kobento mapping" }
      }
    }

    fun modifiers(node: UiBuilderNode): List<JsonElement> =
      node.modifiers.flatMapIndexed { index, value ->
        modifier(value as? JsonObject, "nodes.${node.id}.modifiers[$index]")
      }

    fun modifier(modifier: JsonObject?, path: String): List<JsonElement> {
      // As RemoteDocumentJsonExporter: a field this lowering cannot write (a background's `shape`,
      // say) is refused rather than dropped, so the export never draws something else.
      val written =
        when (modifier?.get("type")?.jsonPrimitive?.contentOrNull) {
          "fillMaxSize",
          "fillMaxWidth",
          "fillMaxHeight" -> setOf("fraction")
          "width" -> setOf("widthDp")
          "height" -> setOf("heightDp")
          "size" -> setOf("widthDp", "heightDp")
          "padding" -> setOf("startDp", "topDp", "endDp", "bottomDp")
          "background" -> setOf("color")
          else -> null
        }
      if (written != null)
        modifier!!
          .entries
          .filter { it.key != "type" && it.key !in written && it.value !is JsonNull }
          .forEach { errors += "$path.${it.key}: modifier field lowering is not available" }
      fun dp(key: String): JsonPrimitive {
        val value = number(modifier?.get(key), "$path.$key", 0.0)
        if (value < 0) errors += "$path.$key: dimension must be non-negative"
        return value.roundedNumber()
      }
      return when (val type = modifier?.get("type")?.jsonPrimitive?.contentOrNull) {
        "fillMaxSize",
        "fillMaxWidth",
        "fillMaxHeight" -> {
          val fraction = number(modifier["fraction"], "$path.fraction", 1.0)
          if (fraction !in 0.0..1.0) errors += "$path.fraction: expected a value in 0..1"
          listOf(
            if (fraction == 1.0) JsonPrimitive(type)
            else buildJsonObject { put(type, fraction.roundedNumber()) },
          )
        }
        "width",
        "height" -> listOf(buildJsonObject { put(type, dp("${type}Dp")) })
        "size" ->
          listOf(
            buildJsonObject { put("width", dp("widthDp")) },
            buildJsonObject { put("height", dp("heightDp")) },
          )
        "padding" ->
          listOf(
            buildJsonObject {
              putJsonObject("padding") {
                listOf("start", "top", "end", "bottom").forEach { put(it, dp("${it}Dp")) }
              }
            },
          )
        "background" -> {
          val authored = modifier["color"]
          val color = ((authored as? JsonObject)?.get("value") ?: authored) as? JsonPrimitive
          if (color?.isString == true && COLOR.matches(color.content))
            listOf(buildJsonObject { put("background", color) })
          else {
            errors += "$path.color: expected #RRGGBB, #AARRGGBB or a @colors token"
            emptyList()
          }
        }
        else -> {
          errors += "$path: modifier $type has no Kobento mapping"
          emptyList()
        }
      }
    }

    fun number(value: JsonElement?, path: String, default: Double): Double {
      if (value == null) return default
      val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
      if (number == null || !number.isFinite()) {
        errors += "$path: expected a finite number"
        return default
      }
      return number
    }
  }

  private fun single(key: String, value: JsonObject) = buildJsonObject { put(key, value) }

  /** Whole numbers are written without a fraction, as Kobento's own documents are. */
  private fun Double.roundedNumber(): JsonPrimitive =
    if (this == kotlin.math.floor(this) && kotlin.math.abs(this) < 1e15) JsonPrimitive(toLong())
    else JsonPrimitive(this)

  /**
   * Kobento variables are floats, so an int is exact only up to 2^24. Ints beyond that are refused
   * rather than rounded. A counter that steps past it at run time still rounds: lowering int state
   * to Kobento's integer resources is the follow-up.
   */
  private const val MAX_EXACT_FLOAT_INT: Int = 1 shl 24

  private fun exactInFloat(value: Double): Boolean =
    value == kotlin.math.floor(value) && kotlin.math.abs(value) <= MAX_EXACT_FLOAT_INT

  /** The property wrappers that hold a literal, as `UiExpressions.OPERAND_LITERALS` plus `enum`. */
  private val LITERAL_TYPES = setOf("string", "int", "float", "bool", "enum", "color")

  private val COLOR = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?|@colors\\.[A-Za-z][A-Za-z0-9_]*")

  @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
  private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
  }
}
